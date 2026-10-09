package ai.loli.desktop.voice

import ai.loli.core.util.Logger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Озвучка встроенным синтезатором Windows (System.Speech). Русский голос появляется, когда в Windows
 * добавлен русский язык с речью (обычно «Microsoft Irina»).
 *
 * Синтезатор живёт в одном фоновом процессе PowerShell: запуск PowerShell и загрузка System.Speech
 * занимают 1–2 секунды, поэтому делаем это один раз, а фразы передаём построчно. Весь текст идёт в base64 —
 * никакие символы из ответа не могут стать командой. «Замолчать» = остановить процесс; следующий
 * поднимается заранее, чтобы ответ после этого не ждал.
 */
class SpeechOutput {
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val lock = ReentrantLock()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "loli-tts-watchdog").apply { isDaemon = true } }
    @Volatile private var worker: Worker? = null
    @Volatile private var cachedVoices: List<Voice>? = null
    @Volatile private var closed = false

    val available: Boolean get() = windows

    data class Voice(val name: String, val culture: String) {
        val russian: Boolean get() = culture.lowercase().startsWith("ru")
    }

    private class Worker(val process: Process) {
        val input: BufferedWriter = process.outputStream.bufferedWriter(Charsets.US_ASCII)
        val output: BufferedReader = process.inputStream.bufferedReader(Charsets.UTF_8)

        /** Отправить команду и прочитать ответ до строки END; null — процесс умер или его остановили. */
        fun call(command: String): List<String>? = runCatching {
            input.write(command); input.newLine(); input.flush()
            val lines = ArrayList<String>()
            while (true) {
                val line = output.readLine() ?: return@runCatching null
                if (line == "END") break
                lines += line
            }
            lines
        }.getOrNull()

        fun kill() {
            runCatching { process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly() }
        }
    }

    /** Голоса Windows, доступные синтезатору (запоминаются после первого успешного запроса). */
    fun voices(): List<Voice> {
        if (!windows) return emptyList()
        cachedVoices?.let { return it }
        val lines = lock.withLock { ensureWorker()?.call("VOICES") } ?: return emptyList()
        return lines.mapNotNull { l -> l.trim().takeIf { '|' in it && !it.startsWith("ERR ") }?.let { Voice(it.substringBefore('|'), it.substringAfter('|')) } }
            .also { if (it.isNotEmpty()) cachedVoices = it }
    }

    /** Произносит текст и ждёт окончания (или [stop]). [voice] пусто — первый русский голос, если есть. */
    fun speak(text: String, voice: String, rate: Int) {
        if (!windows || text.isBlank()) return
        val command = "SAY|" + b64(voice) + "|" + rate.coerceIn(-10, 10) + "|" + b64(text.take(3000))
        lock.withLock {
            val w = ensureWorker()
            val answer = w?.call(command)
            answer?.firstOrNull { it.startsWith("ERR ") }?.let { Logger.w(TAG, "Синтезатор: ${it.removePrefix("ERR ")}") }
            if (w == null) speakOnce(text, voice, rate)
        }
    }

    /** Замолчать сейчас же. */
    fun stop() {
        val w = worker ?: return
        worker = null
        w.kill()
        warmUp()
    }

    /** Заранее поднять синтезатор в фоне, чтобы первый ответ прозвучал без задержки. */
    fun warmUp() {
        if (!windows || closed) return
        Thread({ runCatching { lock.withLock { ensureWorker() } } }, "loli-tts-warmup").apply { isDaemon = true }.start()
    }

    /** Выход из программы. */
    fun shutdown() {
        closed = true
        worker?.kill()
        worker = null
        watchdog.shutdownNow()
    }

    /** Для проверки на Windows без звуковой карты: синтезатор пишет «в никуда». */
    internal fun muteForTest(): Boolean = lock.withLock { ensureWorker()?.call("MUTE") != null }

    internal fun ping(): Boolean = lock.withLock { ensureWorker()?.call("PING")?.contains("PONG") == true }

    /** Вызывать под [lock]. */
    private fun ensureWorker(): Worker? {
        if (closed) return null
        worker?.takeIf { it.process.isAlive }?.let { return it }
        return runCatching {
            val p = powershell(WORKER_SCRIPT)
            val w = Worker(p)
            // Не дождались готовности за 20 с — что-то не так с PowerShell; не висим вечно.
            val timer = watchdog.schedule({ w.kill() }, 20, TimeUnit.SECONDS)
            val ready = generateSequence { w.output.readLine() }.firstOrNull { it == "READY" } != null
            timer.cancel(false)
            if (!ready) { w.kill(); null } else w.also { worker = it }
        }.onFailure { Logger.w(TAG, "Синтезатор Windows не запустился", it) }.getOrNull()
    }

    /** Запасной путь: отдельный PowerShell на одну фразу (если постоянный не поднялся). */
    private fun speakOnce(text: String, voice: String, rate: Int) {
        val script = """
            Add-Type -AssemblyName System.Speech
            ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            ${'$'}want = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${b64(voice)}'))
            ${'$'}all = @(${'$'}s.GetInstalledVoices() | Where-Object { ${'$'}_.Enabled })
            ${'$'}pick = ${'$'}all | Where-Object { ${'$'}_.VoiceInfo.Name -eq ${'$'}want } | Select-Object -First 1
            if (-not ${'$'}pick) { ${'$'}pick = ${'$'}all | Where-Object { ${'$'}_.VoiceInfo.Culture.Name -like 'ru*' } | Select-Object -First 1 }
            if (${'$'}pick) { ${'$'}s.SelectVoice(${'$'}pick.VoiceInfo.Name) }
            ${'$'}s.Rate = ${rate.coerceIn(-10, 10)}
            ${'$'}s.Speak([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${b64(text.take(3000))}')))
        """.trimIndent()
        runCatching {
            val p = powershell(script)
            val w = Worker(p)
            worker = w // чтобы [stop] мог прервать и эту фразу
            p.inputStream.readAllBytes()
            p.waitFor(120, TimeUnit.SECONDS)
            if (worker === w) worker = null
        }.onFailure { Logger.w(TAG, "Синтезатор Windows не ответил", it) }
    }

    private fun powershell(script: String): Process {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        return ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
            .redirectErrorStream(true).start()
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private companion object {
        const val TAG = "SpeechOutput"

        /** Постоянный синтезатор: читает команды построчно, на каждую отвечает строками и END. */
        val WORKER_SCRIPT = """
            ${'$'}ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Speech
            ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            ${'$'}out = [Console]::Out
            ${'$'}out.WriteLine('READY'); ${'$'}out.Flush()
            while (${'$'}true) {
              ${'$'}line = [Console]::In.ReadLine()
              if (${'$'}line -eq ${'$'}null) { break }
              try {
                if (${'$'}line -eq 'PING') { ${'$'}out.WriteLine('PONG') }
                elseif (${'$'}line -eq 'MUTE') { ${'$'}s.SetOutputToNull() }
                elseif (${'$'}line -eq 'VOICES') {
                  foreach (${'$'}v in ${'$'}s.GetInstalledVoices()) { if (${'$'}v.Enabled) { ${'$'}out.WriteLine(${'$'}v.VoiceInfo.Name + '|' + ${'$'}v.VoiceInfo.Culture.Name) } }
                }
                elseif (${'$'}line.StartsWith('SAY|')) {
                  ${'$'}p = ${'$'}line.Split('|')
                  ${'$'}want = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(${'$'}p[1]))
                  ${'$'}all = @(${'$'}s.GetInstalledVoices() | Where-Object { ${'$'}_.Enabled })
                  ${'$'}pick = ${'$'}all | Where-Object { ${'$'}_.VoiceInfo.Name -eq ${'$'}want } | Select-Object -First 1
                  if (-not ${'$'}pick) { ${'$'}pick = ${'$'}all | Where-Object { ${'$'}_.VoiceInfo.Culture.Name -like 'ru*' } | Select-Object -First 1 }
                  if (${'$'}pick) { ${'$'}s.SelectVoice(${'$'}pick.VoiceInfo.Name) }
                  ${'$'}s.Rate = [int]${'$'}p[2]
                  ${'$'}s.Speak([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(${'$'}p[3])))
                }
              } catch { ${'$'}out.WriteLine('ERR ' + ${'$'}_.Exception.Message) }
              ${'$'}out.WriteLine('END'); ${'$'}out.Flush()
            }
        """.trimIndent()
    }
}

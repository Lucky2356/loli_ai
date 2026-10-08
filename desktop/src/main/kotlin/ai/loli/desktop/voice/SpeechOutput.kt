package ai.loli.desktop.voice

import ai.loli.core.util.Logger
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Озвучка встроенным синтезатором Windows (System.Speech через PowerShell). Русский голос появляется,
 * когда в Windows добавлен русский язык с речью (обычно «Microsoft Irina»). Текст передаётся в base64 —
 * никакие символы из ответа не могут стать командой PowerShell.
 */
class SpeechOutput {
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    @Volatile private var process: Process? = null

    val available: Boolean get() = windows

    data class Voice(val name: String, val culture: String) {
        val russian: Boolean get() = culture.lowercase().startsWith("ru")
    }

    /** Голоса Windows, доступные синтезатору. */
    fun voices(): List<Voice> {
        if (!windows) return emptyList()
        val out = run(
            "[Console]::OutputEncoding = [Text.Encoding]::UTF8; Add-Type -AssemblyName System.Speech; " +
                "(New-Object System.Speech.Synthesis.SpeechSynthesizer).GetInstalledVoices() | Where-Object { \$_.Enabled } | " +
                "ForEach-Object { \$_.VoiceInfo.Name + '|' + \$_.VoiceInfo.Culture.Name }",
            wait = true,
        ) ?: return emptyList()
        return out.lines().mapNotNull { l -> l.trim().takeIf { '|' in it }?.let { Voice(it.substringBefore('|'), it.substringAfter('|')) } }
    }

    /** Произносит текст и ждёт окончания (или [stop]). [voice] пусто — первый русский голос, если есть. */
    fun speak(text: String, voice: String, rate: Int) {
        if (!windows || text.isBlank()) return
        val b64 = Base64.getEncoder().encodeToString(text.take(3000).toByteArray(Charsets.UTF_8))
        val voiceB64 = Base64.getEncoder().encodeToString(voice.toByteArray(Charsets.UTF_8))
        val script = """
            Add-Type -AssemblyName System.Speech
            ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            ${'$'}want = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$voiceB64'))
            ${'$'}all = ${'$'}s.GetInstalledVoices() | Where-Object { ${'$'}_.Enabled }
            ${'$'}pick = ${'$'}all | Where-Object { ${'$'}_.VoiceInfo.Name -eq ${'$'}want } | Select-Object -First 1
            if (-not ${'$'}pick) { ${'$'}pick = ${'$'}all | Where-Object { ${'$'}_.VoiceInfo.Culture.Name -like 'ru*' } | Select-Object -First 1 }
            if (${'$'}pick) { ${'$'}s.SelectVoice(${'$'}pick.VoiceInfo.Name) }
            ${'$'}s.Rate = ${rate.coerceIn(-10, 10)}
            ${'$'}s.Speak([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$b64')))
        """.trimIndent()
        run(script, wait = true)
    }

    fun stop() {
        process?.let { p -> runCatching { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() } }
        process = null
    }

    private fun run(script: String, wait: Boolean): String? = runCatching {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
            .redirectErrorStream(true).start()
        process = p
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (wait) p.waitFor(120, TimeUnit.SECONDS)
        if (process === p) process = null
        out
    }.onFailure { Logger.w("SpeechOutput", "Синтезатор Windows не ответил", it) }.getOrNull()
}

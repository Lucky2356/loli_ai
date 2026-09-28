package ai.loli.app.health

import ai.loli.core.assistant.RelaxKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Дыхательное упражнение 4-7-8 и медитация: Лоли подсказывает голосом, когда вдыхать и выдыхать.
 * Начинается после вступления (его озвучивает сам ответ), прерывается «стоп медитация».
 */
class Relaxation(private val scope: CoroutineScope, private val speak: suspend (String) -> Unit) {
    @Volatile private var job: Job? = null

    fun start(kind: RelaxKind, minutes: Int) {
        job?.cancel()
        job = scope.launch {
            // Даём договорить вступление.
            delay(9_000)
            when (kind) {
                RelaxKind.BREATHING -> breathing(minutes)
                RelaxKind.MEDITATION -> meditation(minutes)
                RelaxKind.STOP -> Unit
            }
        }
    }

    fun stop(): Boolean {
        val j = job ?: return false
        job = null
        val active = j.isActive
        j.cancel()
        return active
    }

    private suspend fun breathing(minutes: Int) {
        // Один цикл 4-7-8 — около 20 секунд.
        val cycles = (minutes * 60 / 20).coerceIn(2, 30)
        repeat(cycles) { i ->
            speak(if (i == 0) "Вдох через нос" else "Вдох")
            delay(4_000)
            speak("Задержите дыхание")
            delay(7_000)
            speak(if (i == 0) "Медленный выдох через рот" else "Выдох")
            delay(8_000)
        }
        speak("Отлично. Упражнение закончено. Посидите немного спокойно.")
    }

    private suspend fun meditation(minutes: Int) {
        val prompts = listOf(
            "Почувствуйте, как воздух входит и выходит.",
            "Если отвлеклись — это нормально. Мягко верните внимание к дыханию.",
            "Расслабьте плечи и лицо.",
            "Просто наблюдайте за дыханием, ничего не меняя.",
            "Почувствуйте опору под собой.",
        )
        val total = minutes * 60_000L
        val step = 60_000L
        var passed = 0L
        var i = 0
        while (passed + step < total) {
            delay(step)
            passed += step
            speak(prompts[i++ % prompts.size])
        }
        delay(total - passed)
        speak("Медленно откройте глаза. Медитация окончена.")
    }
}

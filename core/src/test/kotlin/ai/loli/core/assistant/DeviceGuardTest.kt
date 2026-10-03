package ai.loli.core.assistant

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceGuardTest {
    private fun ok(cmd: DeviceCommand, said: String) = DeviceGuard.allowed(cmd, said)

    @Test fun urlMustBeNamedByTheUser() {
        // Слово «открой» есть, но адрес взят из заметки, а не из просьбы.
        assertFalse(ok(DeviceCommand.OpenUrl("https://evil.example/pay"), "открой заметку про банк"))
        assertFalse(ok(DeviceCommand.OpenUrl("https://bank-login.ru"), "открой заметку про банк"))
        assertTrue(ok(DeviceCommand.OpenUrl("https://evil.example"), "открой сайт evil.example"))
        assertTrue(ok(DeviceCommand.OpenUrl("https://yandex.ru"), "открой сайт яндекс точка ру"))
        assertTrue(ok(DeviceCommand.OpenUrl("https://www.google.com"), "открой сайт гугла"))
        assertTrue(ok(DeviceCommand.OpenUrl("https://vk.com/id1"), "открой ссылку вконтакте"))
    }

    @Test fun callMustNameTheNumberOrPerson() {
        assertFalse(ok(DeviceCommand.Call("89001234567"), "позвони маме"))
        assertTrue(ok(DeviceCommand.Call("мама"), "позвони маме"))
        assertTrue(ok(DeviceCommand.Call("8 900 123-45-67"), "набери 8 900 123 45 67"))
        assertFalse(ok(DeviceCommand.Call("мама"), "какая погода"))
    }

    @Test fun messageRecipientMustBeNamed() {
        assertTrue(ok(DeviceCommand.Message("Маше", "привет"), "напиши Маше привет"))
        assertFalse(ok(DeviceCommand.Message("Иван", "привет"), "напиши Маше привет"))
    }

    @Test fun shareNeedsTheAppNamed() {
        assertTrue(ok(DeviceCommand.Share("телеграм", "привет"), "отправь в телеграм привет"))
        assertFalse(ok(DeviceCommand.Share("whatsapp", "секрет"), "отправь в телеграм привет"))
        assertTrue(ok(DeviceCommand.Share("", "привет"), "отправь привет"))
    }

    @Test fun systemButtonsOnlyOnDirectRequest() {
        assertFalse(ok(DeviceCommand.Global(GlobalAction.LOCK), "прочитай заметку про рецепт"))
        assertTrue(ok(DeviceCommand.Global(GlobalAction.LOCK), "заблокируй экран"))
        assertFalse(ok(DeviceCommand.Driving(true), "запиши расход 500"))
        assertTrue(ok(DeviceCommand.Driving(true), "я за рулём"))
        assertFalse(ok(DeviceCommand.DoNotDisturb(true), "какая погода"))
        assertFalse(ok(DeviceCommand.Camera(), "что в заметке"))
        assertTrue(ok(DeviceCommand.Camera(selfie = true), "сделай селфи"))
    }

    @Test fun harmlessCommandsPass() {
        assertTrue(ok(DeviceCommand.Timer(300), "поставь таймер на 5 минут"))
        assertTrue(ok(DeviceCommand.Battery, "сколько заряда"))
    }

    @Test fun backupPhrasesOpenBackupScreen() {
        val parser = LocalCommandParser()
        val now = java.time.Instant.parse("2026-09-25T09:00:00Z")
        val zone = java.time.ZoneId.of("Europe/Moscow")
        for (phrase in listOf("сделай резервную копию", "создай бэкап", "сохрани копию данных", "восстанови данные из копии", "как перенести данные на новый телефон", "резервная копия")) {
            val cmd = (parser.parse(phrase, now, zone)?.actions?.singleOrNull() as? AssistantAction.Device)?.command
            kotlin.test.assertEquals(DeviceCommand.OpenBackup, cmd, phrase)
        }
        assertTrue(ok(DeviceCommand.OpenBackup, "сделай резервную копию"))
        assertFalse(ok(DeviceCommand.OpenBackup, "какая погода"))
        // Копия — не «копировать текст» и не заметка про копию.
        val note = parser.parse("запиши заметку про копию ключей", now, zone)?.actions?.singleOrNull()
        assertTrue(note !is AssistantAction.Device)
    }
}

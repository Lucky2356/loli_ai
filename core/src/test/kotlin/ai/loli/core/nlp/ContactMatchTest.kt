package ai.loli.core.nlp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContactMatchTest {
    @Test fun picksExactNameNotFirstPrefix() {
        val names = listOf("Александр Петров", "Алексей Иванов", "Алексис")
        assertEquals(1, ContactMatch.best("Алексею", names))
        assertEquals(0, ContactMatch.best("Александру", names))
    }

    @Test fun mamaIsNotMamedov() {
        val names = listOf("Мамедов Иван", "Мама", "Мамонтова Ольга")
        assertEquals(1, ContactMatch.best("маме", names))
    }

    @Test fun fullNameBeatsFirstNameOnly() {
        val names = listOf("Иван Сидоров", "Иван Петров")
        assertEquals(1, ContactMatch.best("Ивану Петрову", names))
    }

    @Test fun shortNameWinsOverLongerOne() {
        val names = listOf("Маша Иванова Работа", "Маша")
        assertEquals(1, ContactMatch.best("Машу", names))
    }

    @Test fun nothingSuitableIsNull() {
        assertNull(ContactMatch.best("Борису", listOf("Алексей", "Мама")))
    }

    @Test fun caseAndYoAreIgnored() {
        assertEquals(0, ContactMatch.best("федор", listOf("ФЁДОР Иванов", "Федя")))
        assertEquals(100, ContactMatch.score("Мама", "мама"))
    }
}

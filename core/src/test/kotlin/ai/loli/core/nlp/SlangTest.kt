package ai.loli.core.nlp

import kotlin.test.Test
import kotlin.test.assertEquals

class SlangTest {
    @Test fun colloquial() {
        assertEquals("что там по погоде", Slang.normalize("чё там по погоде"))
        assertEquals("сколько сейчас времени", Slang.normalize("скока щас времени"))
        assertEquals("какой курс доллара", Slang.normalize("слушай, подскажи-ка, какой курс доллара"))
        assertEquals("поставь таймер пожалуйста", Slang.normalize("поставь таймер плиз"))
        assertEquals("чем пахнет", Slang.normalize("чем пахнет"))
        assertEquals("чек из магазина", Slang.normalize("чек из магазина"))
        assertEquals("скажи мне правду", Slang.normalize("скажи мне правду"))
    }
}

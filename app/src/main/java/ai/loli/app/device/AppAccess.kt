package ai.loli.app.device

import android.content.Context
import android.content.Intent
import ai.loli.app.settings.AppSettings

data class InstalledApp(val packageName: String, val label: String, val sensitive: Boolean)

/**
 * Какие приложения Лоли может открывать по голосу.
 *  - Пользователь явно разрешил/запретил приложение — решение пользователя главное.
 *  - Иначе: обычные приложения разрешены (если включено «разрешать обычные автоматически»),
 *    а банки, платёжные, государственные, криптокошельки и менеджеры паролей закрыты, пока пользователь не разрешит.
 */
class AppAccess(private val context: Context, private val settings: () -> AppSettings) {

    fun installed(): List<InstalledApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val payments = paymentApps()
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .map { (pkg, label) -> InstalledApp(pkg, label, pkg in payments || isSensitive(pkg, label)) }
            // Финансовые и личные — первыми: их проверяют чаще всего.
            .sortedWith(compareBy({ !it.sensitive }, { it.label.lowercase() }))
    }

    fun isAllowed(pkg: String, label: String): Boolean {
        val s = settings()
        s.appAccess[pkg]?.let { return it }
        return s.autoAllowApps && !isSensitive(pkg, label) && pkg !in paymentApps()
    }

    /** Приложения, которые умеют оплачивать по NFC (банки, кошельки, транспортные карты) — в любом случае платёжные. */
    private fun paymentApps(): Set<String> {
        cachedPayments?.let { (at, set) -> if (System.currentTimeMillis() - at < 60_000) return set }
        val set = runCatching {
            context.packageManager.queryIntentServices(Intent("android.nfc.cardemulation.action.HOST_APDU_SERVICE"), 0)
                .map { it.serviceInfo.packageName }.toSet()
        }.getOrDefault(emptySet()) - context.packageName
        cachedPayments = System.currentTimeMillis() to set
        return set
    }

    @Volatile private var cachedPayments: Pair<Long, Set<String>>? = null

    companion object {
        /** Подстроки названия или пакета. */
        private val SENSITIVE_WORDS = listOf(
            // Банки
            "банк", "bank", "сбер", "sber", "сбол", "тинькофф", "tinkoff", "т-банк", "tbank", "альфа", "alfa", "втб", "vtb", "газпромбанк", "gazprombank",
            "открытие", "райффайзен", "raiffeisen", "почта банк", "совком", "sovcom", "халва", "halva", "росбанк", "rosbank", "мтс банк", "mtsbank",
            "озон банк", "ozonbank", "уралсиб", "uralsib", "промсвязь", "psbank", "россельхоз", "rshb", "ак барс", "akbars", "хоум", "home credit",
            "homecredit", "ренессанс", "renins", "rencredit", "русский стандарт", "юникредит", "unicredit", "ситибанк", "citibank", "точка", "tochka",
            "модульбанк", "modulbank", "дом.рф", "domrf", "зенит", "zenit", "центр-инвест", "авангард", "avangard", "credit",
            "кредит", "займ", "zaim", "микрозайм", "деньги", "money", "финанс", "financ", "fintech", "cash", "кэш", "кешбэк",
            // Платежи и кошельки
            "pay", "пэй", "wallet", "кошел", "qiwi", "киви", "юmoney", "yoomoney", "ymoney", "webmoney", "payeer", "paypal", "revolut", "wise", "мир pay",
            "mirpay", "sberpay", "сбп", "nspk", "kaspi", "каспи", "халык", "halyk", "jusan",
            // Госуслуги и налоги
            "госуслуг", "gosuslugi", "налог", "nalog", "мос.ру", "mos.ru", "пфр", "социальный фонд", "sfr", "egov", "фнс",
            // Инвестиции, брокеры, крипта
            "binance", "bybit", "okx", "kucoin", "coinbase", "kraken", "exodus", "tonkeeper", "trust wallet", "metamask", "ledger", "crypto", "крипт",
            "bitcoin", "биткоин", "биржа", "broker", "брокер", "инвест", "invest", "trade", "трейд", "бкс", "bcs", "финам", "finam", "quik",
            // Страхование
            "страхов", "insur", "ресо-гарантия", "resogarant", "ингосстрах", "ingos", "согаз", "sogaz",
            // Пароли и коды входа
            "password", "пароль", "bitwarden", "1password", "lastpass", "keepass", "authenticator", "аутентификатор", "authy", "2fa",
        )
        /** Сокращения — только целым словом, чтобы «псб» не совпадало внутри других слов. */
        private val SENSITIVE_ABBR = Regex("""(?:^|[^\p{L}])(?:псб|мкб|рсхб|отп|бкс|вбрр|рнкб|абр|цб)(?:$|[^\p{L}])""")
        private val SENSITIVE_PACKAGES = listOf(
            "ru.sberbankmobile", "ru.sberbank", "com.idamob.tinkoff", "ru.tinkoff", "ru.alfabank", "ru.vtb24", "ru.vtb", "ru.gosuslugi",
            "ru.psbank", "ru.rshb", "ru.raiffeisen", "ru.mw", "ru.yoo", "ru.nspk", "com.google.android.apps.walletnfcrel",
            "com.samsung.android.spay", "com.samsung.android.samsungpay", "com.google.android.apps.authenticator2", "com.azure.authenticator",
        )
        /** Ложные совпадения: «Google Play», «Display» и т. п. */
        private val SAFE_PACKAGES = setOf("com.android.vending", "com.google.android.play.games", "com.google.android.apps.youtube.music")

        fun isSensitive(pkg: String, label: String): Boolean {
            if (pkg in SAFE_PACKAGES) return false
            val l = label.lowercase()
            val p = pkg.lowercase()
            return SENSITIVE_PACKAGES.any { p.startsWith(it) } ||
                SENSITIVE_ABBR.containsMatchIn(l) ||
                SENSITIVE_WORDS.any { w -> l.contains(w) || (w.length >= 4 && p.contains(w.replace(" ", ""))) }
        }
    }
}

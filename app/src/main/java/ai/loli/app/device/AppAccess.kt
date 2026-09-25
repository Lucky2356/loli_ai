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
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .map { (pkg, label) -> InstalledApp(pkg, label, isSensitive(pkg, label)) }
            .sortedBy { it.label.lowercase() }
    }

    fun isAllowed(pkg: String, label: String): Boolean {
        val s = settings()
        s.appAccess[pkg]?.let { return it }
        return s.autoAllowApps && !isSensitive(pkg, label)
    }

    companion object {
        private val SENSITIVE_WORDS = listOf(
            "банк", "bank", "сбер", "sber", "тинькофф", "tinkoff", "т-банк", "tbank", "альфа", "alfa", "втб", "vtb", "газпромбанк",
            "открытие", "райффайзен", "raiffeisen", "почта банк", "совком", "росбанк", "мтс банк", "озон банк", "яндекс пэй",
            "pay", "wallet", "кошел", "qiwi", "юmoney", "yoomoney", "paypal", "revolut", "wise", "мир pay", "sberpay",
            "госуслуг", "gosuslugi", "налог", "nalog", "мос.ру", "mos.ru", "binance", "bybit", "crypto", "крипт", "trust wallet",
            "metamask", "kaspi", "халык", "halyk", "биржа", "broker", "брокер", "инвест", "invest", "trade",
            "password", "пароль", "bitwarden", "1password", "lastpass", "keepass", "authenticator", "аутентификатор",
        )
        private val SENSITIVE_PACKAGES = listOf(
            "ru.sberbankmobile", "com.idamob.tinkoff", "ru.alfabank", "ru.vtb24", "ru.gosuslugi", "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.authenticator2", "com.azure.authenticator",
        )

        fun isSensitive(pkg: String, label: String): Boolean {
            val l = label.lowercase()
            return SENSITIVE_PACKAGES.any { pkg.startsWith(it) } || SENSITIVE_WORDS.any { l.contains(it) || pkg.contains(it.replace(" ", "")) }
        }
    }
}

package ai.loli.app.device

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import ai.loli.core.nlp.ContactMatch
import ai.loli.core.nlp.TextAnalysis

/**
 * Поиск контакта по сказанному имени. Системный поиск отдаёт всех, чьё имя начинается похоже
 * («Алексею» → Александр, Алексей, Алексис), поэтому лучший выбирается оценкой [ContactMatch], а не первый в списке.
 */
object ContactLookup {
    data class Hit(val name: String, val phones: List<String>)

    /** Нужен доступ READ_CONTACTS — проверяет вызывающий. */
    fun find(context: Context, who: String): Hit? {
        val stem = TextAnalysis.stems(who).firstOrNull() ?: who.lowercase()
        val key = if (stem.length > 3) stem.take(5) else stem
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(key))
        val rows = ArrayList<Pair<String, String>>()
        context.contentResolver.query(
            uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null,
        )?.use { c ->
            while (c.moveToNext() && rows.size < 300) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                rows += name to number
            }
        }
        if (rows.isEmpty()) return null
        val names = rows.map { it.first }.distinct()
        // Если оценка никого не выбрала, остаётся первый найденный системой — как раньше.
        val name = names[ContactMatch.best(who, names) ?: 0]
        val phones = rows.filter { it.first == name }.map { it.second }.distinctBy { p -> p.filter(Char::isDigit).takeLast(10) }
        return Hit(name, phones)
    }
}

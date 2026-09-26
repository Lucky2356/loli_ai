package ai.loli.app.device

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import ai.loli.app.AppContainer
import ai.loli.core.assistant.AssistantAction

/** Дни рождения из контактов телефона → ежегодные напоминания (накануне вечером и в сам день). */
object BirthdayImport {
    data class Result(val imported: Int, val permissionDenied: Boolean = false)

    suspend fun run(context: Context, c: AppContainer): Result {
        if (!c.permissions.ensure(Manifest.permission.READ_CONTACTS)) return Result(0, permissionDenied = true)
        val found = read(context)
        if (found.isEmpty()) return Result(0)
        val known = c.store.reminders.all().map { it.text.lowercase() }
        val fresh = found.filter { (name, _, _) -> known.none { "день рождения ${name.lowercase()}" in it } }
        if (fresh.isEmpty()) return Result(0)
        c.executor.execute(fresh.map { (name, m, d) -> AssistantAction.AddBirthday(name, m, d) }, c.engine.context)
        return Result(fresh.size)
    }

    private fun read(context: Context): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        val projection = arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE)
        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?"
        val args = arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString())
        runCatching {
            context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, args, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(0)?.trim().orEmpty()
                    val date = cur.getString(1)?.trim().orEmpty()
                    // Форматы: 1990-05-05, --05-05, 05.05.1990
                    val m = Regex("""(?:\d{4}|-)-(\d{1,2})-(\d{1,2})""").find(date)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                        ?: Regex("""(\d{1,2})\.(\d{1,2})(?:\.\d{2,4})?""").find(date)?.let { it.groupValues[2].toInt() to it.groupValues[1].toInt() }
                    if (name.isNotEmpty() && m != null && m.first in 1..12 && m.second in 1..31) out += Triple(name, m.first, m.second)
                }
            }
        }
        return out.distinctBy { it.first.lowercase() }.take(300)
    }
}

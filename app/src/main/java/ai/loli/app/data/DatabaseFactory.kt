package ai.loli.app.data

import android.content.Context
import ai.loli.app.security.KeystoreSecretStore
import ai.loli.core.db.LoliDatabase
import ai.loli.core.util.Logger
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Локальная БД зашифрована SQLCipher. Пароль — случайные 256 бит, хранятся зашифрованными ключом Android Keystore.
 */
object DatabaseFactory {
    private const val TAG = "Database"
    const val NAME = "loli.db"

    fun create(context: Context, secrets: KeystoreSecretStore): SqlDriver {
        System.loadLibrary("sqlcipher")
        val hadPassphrase = secrets.hasDatabasePassphrase()
        if (!hadPassphrase && context.getDatabasePath(NAME).exists()) {
            // БД есть, а пароля нет — открыть её нельзя. Откладываем файл (не удаляем).
            Logger.w(TAG, "Пароль локальной БД отсутствует — откладываю старую БД и создаю новую")
            val db = context.getDatabasePath(NAME)
            db.renameTo(java.io.File(db.parentFile, "$NAME.orphan-${System.currentTimeMillis()}"))
        }
        return try {
            open(context, secrets).also { verify(it) }
        } catch (e: Exception) {
            // Не удаляем данные: откладываем файл БД и пароль к нему, создаём новую БД.
            // Несинхронизированные записи можно будет восстановить, облачные вернутся синхронизацией.
            Logger.e(TAG, "Не удалось открыть зашифрованную БД — сохраняю копию и создаю новую", e)
            val suffix = System.currentTimeMillis().toString()
            val db = context.getDatabasePath(NAME)
            if (db.exists()) db.renameTo(java.io.File(db.parentFile, "$NAME.bak-$suffix"))
            java.io.File(db.parentFile, "$NAME-journal").delete()
            java.io.File(db.parentFile, "$NAME-wal").delete()
            java.io.File(db.parentFile, "$NAME-shm").delete()
            secrets.moveDatabasePassphraseAside(suffix)
            open(context, secrets).also { verify(it) }
        }
    }

    private fun open(context: Context, secrets: KeystoreSecretStore): SqlDriver =
        AndroidSqliteDriver(
            schema = LoliDatabase.Schema,
            context = context,
            name = NAME,
            factory = SupportOpenHelperFactory(secrets.databasePassphrase()),
        )

    private fun verify(driver: SqlDriver) {
        driver.executeQuery(null, "SELECT count(*) FROM sqlite_master", { cursor ->
            cursor.next()
            app.cash.sqldelight.db.QueryResult.Value(Unit)
        }, 0)
    }
}

package ai.loli.core.remote

/**
 * Подключение к Supabase. anon/publishable key публичен по замыслу Supabase (попадает в клиент),
 * доступ к данным ограничивается Row Level Security. service_role ключ в приложении не используется никогда.
 */
class SupabaseConfig(url: String, val anonKey: String) {
    val url: String = url.trim().trimEnd('/')
    val isConfigured: Boolean get() = url.startsWith("https://") && anonKey.isNotBlank()
    override fun toString() = "SupabaseConfig(url=$url, anonKey=${if (anonKey.isBlank()) "<empty>" else "***"})"
}

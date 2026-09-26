package ai.loli.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import ai.loli.app.LoliApp
import ai.loli.app.R
import ai.loli.app.ui.MainActivity
import ai.loli.core.assistant.RuFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Виджеты со списками: «Задачи на сегодня» и «Покупки». Касание открывает Лоли.
 * Если в Лоли включён вход по отпечатку, содержимое на рабочем столе не показывается.
 */
abstract class ListWidget : AppWidgetProvider() {
    protected abstract suspend fun content(context: Context): Pair<String, String>

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext as LoliApp
        app.container.appScope.launch {
            try {
                val (title, body) = runCatching {
                    if (app.container.settings.current().appLock) "Лоли" to "Скрыто: включена защита входа. Нажмите, чтобы открыть."
                    else withContext(Dispatchers.IO) { content(context) }
                }.getOrElse { "Лоли" to "Нажмите, чтобы открыть" }
                val open = PendingIntent.getActivity(
                    context, javaClass.name.hashCode(),
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val views = RemoteViews(context.packageName, R.layout.widget_list).apply {
                    setTextViewText(R.id.widget_list_title, title)
                    setTextViewText(R.id.widget_list_body, body)
                    setOnClickPendingIntent(R.id.widget_list_root, open)
                }
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Обновить все виджеты-списки (после изменения задач или покупок). */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            for (cls in listOf(TasksWidget::class.java, ShoppingWidget::class.java)) {
                val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, cls)) }.getOrNull() ?: continue
                if (ids.isEmpty()) continue
                context.sendBroadcast(
                    Intent(context, cls).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
                )
            }
        }
    }
}

class TasksWidget : ListWidget() {
    override suspend fun content(context: Context): Pair<String, String> {
        val c = (context.applicationContext as LoliApp).container
        val today = c.time.today()
        val tasks = c.store.tasks.all().filter { !it.done }
        val overdue = tasks.filter { it.dueDate?.isBefore(today) == true }
        val due = tasks.filter { it.dueDate == today }.sortedBy { it.dueTime }
        val lines = (overdue.map { "⚠ ${it.title}" } + due.map { t -> (t.dueTime?.let { "${RuFormat.time(it)} " } ?: "• ") + t.title })
        val title = "Сегодня · " + RuFormat.count(due.size, "задача", "задачи", "задач") + (if (overdue.isNotEmpty()) " · просрочено ${overdue.size}" else "")
        return title to (if (lines.isEmpty()) "На сегодня задач нет" else lines.take(7).joinToString("\n"))
    }
}

class ShoppingWidget : ListWidget() {
    override suspend fun content(context: Context): Pair<String, String> {
        val c = (context.applicationContext as LoliApp).container
        val left = c.store.shopping.all().filter { !it.done }
        val title = if (left.isEmpty()) "Покупки" else "Купить · ${left.size}"
        return title to (if (left.isEmpty()) "Всё куплено. Скажите: «добавь в покупки…»" else left.take(7).joinToString("\n") { "• ${it.text}" })
    }
}

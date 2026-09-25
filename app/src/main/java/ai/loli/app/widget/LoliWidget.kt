package ai.loli.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import ai.loli.app.R
import ai.loli.app.assist.AssistActivity

/** Виджет на рабочий стол: быстрый вызов Лоли, даже если она не выбрана ассистентом по умолчанию. */
class LoliWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val intent = Intent(context, AssistActivity::class.java).setAction(Intent.ACTION_ASSIST)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val views = RemoteViews(context.packageName, R.layout.widget_loli).apply { setOnClickPendingIntent(R.id.widget_root, pi) }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}

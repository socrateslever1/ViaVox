package com.example.viavox.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.example.viavox.MainActivity
import com.example.viavox.R
import com.example.viavox.data.TrackingRepository
import com.example.viavox.model.TrackingState
import com.example.viavox.service.ViaVoxTrackingService

/** Home-screen widget updates only from real GPS/session events. */
class ViaVoxWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray
    ) {
        draw(context, TrackingRepository.state.value, appWidgetManager, appWidgetIds)
    }

    companion object {
        fun refresh(context: Context, state: TrackingState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, ViaVoxWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) draw(context, state, manager, ids)
        }

        private fun draw(
            context: Context, state: TrackingState,
            manager: AppWidgetManager, ids: IntArray
        ) {
            val active = state.isActive && state.hasLocationFix
            val street = when {
                !state.isActive -> "Localização desligada"
                !state.hasLocationFix -> "Aguardando sinal GPS"
                else -> state.place?.road?.takeIf { it.isNotBlank() }
                    ?: "Identificando a rua"
            }
            val neighbourhood = if (active) state.place?.neighbourhood
                ?.takeIf { it.isNotBlank() } ?: "Bairro não identificado"
                else if (state.isActive) "GPS em funcionamento" else "Toque para abrir o ViaVox"

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(
                context, 7201, Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags
            )
            val speak = if (state.isActive) {
                PendingIntent.getForegroundService(
                    context, 7202, Intent(context, ViaVoxTrackingService::class.java)
                        .setAction(ViaVoxTrackingService.ACTION_REPEAT_SPEECH),
                    flags
                )
            } else open

            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.viavox_widget)
                views.setTextViewText(R.id.viavox_widget_street, street)
                views.setTextViewText(R.id.viavox_widget_neighbourhood, neighbourhood)
                views.setTextViewText(R.id.viavox_widget_status,
                    if (state.isActive) "GPS ATIVO" else "GPS DESLIGADO")
                views.setTextViewText(R.id.viavox_widget_listen,
                    if (state.isActive) "Ouvir onde estou" else "Abrir para ativar")
                views.setOnClickPendingIntent(R.id.viavox_widget_root, open)
                views.setOnClickPendingIntent(R.id.viavox_widget_listen, speak)
                manager.updateAppWidget(id, views)
            }
        }
    }
}

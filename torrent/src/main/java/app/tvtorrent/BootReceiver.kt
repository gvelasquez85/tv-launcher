package app.tvtorrent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Arranca el portal con la TV si "siempre activo" o el modo descarga estan encendidos. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && (Store.alwaysOn(context) || Store.downloadMode(context))) {
            TorrentService.start(context)
        }
    }
}

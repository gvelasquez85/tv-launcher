package app.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Al arrancar la TV se asegura de que el servicio de botones siga activo. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) KeyMap.ensureService(context)
    }
}

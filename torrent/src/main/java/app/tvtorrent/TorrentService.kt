package app.tvtorrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.net.Uri
import android.net.wifi.WifiManager

/**
 * Mantiene vivo el motor de torrents y el portal web mientras haya algo que hacer:
 * descargas en curso, la pantalla de Descargas abierta o actividad web reciente.
 * Tras 15 minutos sin nada se apaga solo para devolver la RAM a la TV.
 */
class TorrentService : Service() {

    @Volatile private var web: Web? = null
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastUi = System.currentTimeMillis()
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    /** Con el modo descarga activo la CPU y el Wi-Fi no se duermen aunque la TV apague la pantalla. */
    private fun syncLocks() {
        val on = Store.downloadMode(this)
        if (on && wake == null) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvtorrent:download")
                .apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }   // tope de 12 h por seguridad
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvtorrent:download").apply { setReferenceCounted(false); acquire() }
        } else if (!on) releaseLocks()
    }

    private fun releaseLocks() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }; wake = null
        runCatching { wifi?.takeIf { it.isHeld }?.release() }; wifi = null
    }

    /** Todo termino: se sueltan los bloqueos y, si la TV ya esta en reposo, se queda apagada. Si alguien la esta viendo, solo se avisa. */
    private fun finishMode() {
        Store.setDownloadMode(this, false)
        releaseLocks()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("done", "Descargas terminadas", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(2, Notification.Builder(this, "done").setContentTitle("Descargas completas")
            .setContentText("Modo descarga terminado. La TV puede apagarse.").setSmallIcon(android.R.drawable.stat_sys_download_done).build())
    }

    private val tick = object : Runnable {
        override fun run() {
            // La comprobacion toca el motor: va en el hilo de fondo, no en el principal.
            worker.execute {
                Engine.checkStorage(this@TorrentService)   // detecta si el USB se fue o volvio
                val now = System.currentTimeMillis()
                val busy = Engine.anyActive(this@TorrentService)
                if (Store.downloadMode(this@TorrentService)) { if (busy) syncLocks() else finishMode() }
                val recent = maxOf(lastUi, web?.lastActivity ?: 0L)
                handler.post {
                    val keep = Store.alwaysOn(this@TorrentService)   // "siempre activo": nunca se apaga solo
                    if (!busy && !keep && Web.streams.get() == 0 && now - recent > IDLE_MS) stopSelf() else handler.postDelayed(this, if (Store.downloadMode(this@TorrentService)) 15_000 else 30_000)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Descargas", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Descargas activas")
            .setContentText("Portal en http://${LocalNet.ipv4() ?: "?"}:${Store.PORT}")
            .setSmallIcon(android.R.drawable.stat_sys_download).build()
        startForeground(1, n)
        // El motor toca el USB y carga la libreria nativa: nunca en el hilo principal.
        worker.execute {
            Engine.start(this)
            web = Web(this).also { runCatching { it.start(NanoHTTPDTimeout, false) } }
        }
        handler.postDelayed(tick, 30_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastUi = System.currentTimeMillis()
        worker.execute { syncLocks() }
        if (intent?.getBooleanExtra(EXTRA_SLEEP, false) == true) {
            // Unos segundos para que se lea el aviso en pantalla; luego la TV pasa a reposo.
            handler.postDelayed({ worker.execute { PowerMode.sleepTv(this) } }, 4000)
        }
        intent?.getStringExtra(EXTRA_MAGNET)?.let { magnet ->
            worker.execute { runCatching { Engine.add(this, magnet) } }
        }
        return if (Store.alwaysOn(this) || Store.downloadMode(this)) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        releaseLocks()
        runCatching { web?.stop() }
        Engine.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "torrent"
        private const val IDLE_MS = 15 * 60 * 1000L
        const val EXTRA_MAGNET = "magnet"
        const val EXTRA_SLEEP = "sleep"
        private const val NanoHTTPDTimeout = 5000

        fun start(ctx: Context, magnet: String? = null, sleepNow: Boolean = false) {
            val i = Intent(ctx, TorrentService::class.java).putExtra(EXTRA_SLEEP, sleepNow)
            if (magnet != null) i.putExtra(EXTRA_MAGNET, magnet)
            ctx.startForegroundService(i)
        }
    }
}

/** Modo descarga: pone la TV en reposo con las descargas activas y la deja apagar al terminar. */
object PowerMode {
    /** Devuelve null si se activo o el motivo si no se pudo. */
    fun enable(ctx: Context): String? {
        if (!Engine.anyActive(ctx)) return "No hay descargas en curso. Añade una primero."
        Store.setDownloadMode(ctx, true)
        TorrentService.start(ctx, sleepNow = true)
        return null
    }

    fun disable(ctx: Context) {
        Store.setDownloadMode(ctx, false)
        TorrentService.start(ctx)   // el servicio suelta los bloqueos
    }

    fun screenOn(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isInteractive

    /** Pide al launcher (servicio de accesibilidad) que apague la pantalla. */
    fun sleepTv(ctx: Context): Boolean = try {
        ctx.contentResolver.call(Uri.parse("content://app.tvlauncher.config"), "sleep", null, null)
            ?.getString("json")?.contains("true") == true
    } catch (_: Exception) { false }
}

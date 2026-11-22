package app.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Cargador de imagenes minimo y ligero (la TV tiene 2 GB de RAM):
 * cache en memoria de 20 MB con RGB_565 y cache en disco de hasta ~100 MB.
 */
object Images {
    private val mem = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private const val DISK_LIMIT = 100L * 1024 * 1024

    /** Carga [url] en [view]; si la vista se reutiliza antes de terminar, se descarta el resultado. */
    fun load(ctx: Context, url: String?, view: ImageView, onDone: ((Bitmap) -> Unit)? = null) {
        view.tag = url
        if (url == null) { view.setImageDrawable(null); return }
        mem.get(url)?.let { view.setImageBitmap(it); onDone?.invoke(it); return }
        val dir = File(ctx.cacheDir, "img").apply { mkdirs() }
        pool.execute {
            val bmp = try { fetch(dir, url) } catch (_: Exception) { null }
            if (bmp != null) {
                mem.put(url, bmp)
                main.post { if (view.tag == url) { view.setImageBitmap(bmp); onDone?.invoke(bmp) } }
            }
        }
    }

    private fun fetch(dir: File, url: String): Bitmap? {
        val f = File(dir, sha1(url))
        if (!f.exists()) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 15000
            try {
                if (c.responseCode != 200) return null
                f.outputStream().use { out -> c.inputStream.use { it.copyTo(out) } }
            } finally { c.disconnect() }
            trim(dir)
        } else f.setLastModified(System.currentTimeMillis())
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeFile(f.path, opts)
    }

    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_LIMIT) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length(); f.delete()
            if (total <= DISK_LIMIT * 8 / 10) break
        }
    }

    private fun sha1(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

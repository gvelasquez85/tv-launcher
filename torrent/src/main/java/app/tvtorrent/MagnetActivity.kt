package app.tvtorrent

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** Recibe un enlace magnet abierto desde otra app (o con `adb shell am start -d "magnet:..."`). */
class MagnetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val magnet = intent?.dataString
        if (magnet != null && Engine.hashOf(magnet) != null) {
            TorrentService.start(this, magnet)
            Toast.makeText(this, "Agregado a Descargas", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Enlace magnet no válido", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}

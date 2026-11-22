package app.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Headless entry point used by the installer:
 *   adb shell am start -n app.tvlauncher/.SetupActivity --es tmdb_key KEY --es region US --es lang en-US
 * Only stores the TMDB settings and closes.
 */
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val e = intent?.extras
        val err = if (e != null && (e.containsKey("tmdb_key") || e.containsKey("region") || e.containsKey("lang")))
            Config.set(this, e.getString("tmdb_key"), e.getString("region"), e.getString("lang")) else "Nothing to set."
        Toast.makeText(this, err ?: "TMDB settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}

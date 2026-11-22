package app.tvlauncher

import android.content.Context

/**
 * TMDB settings chosen by the user at run time (the installer, the TV or the web manager set them).
 * Build-time values from local.properties are only used as defaults for developers building from source.
 * Ajustes de TMDB elegidos por el usuario en tiempo de ejecucion; los de local.properties son solo valores por defecto.
 */
object Config {
    private const val PREFS = "config"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tmdbKey(c: Context): String = prefs(c).getString("tmdb_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.TMDB_KEY
    fun region(c: Context): String = prefs(c).getString("region", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.REGION
    fun lang(c: Context): String = prefs(c).getString("lang", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.LANG

    /** Returns null if valid, or the reason why not. Only the fields that are not null are changed. */
    fun set(c: Context, key: String?, region: String?, lang: String?): String? {
        key?.let { if (it.isNotBlank() && !Regex("[0-9a-fA-F]{32}").matches(it.trim())) return "The TMDB v3 API key is 32 hexadecimal characters." }
        region?.let { if (it.isNotBlank() && !Regex("[A-Za-z]{2}").matches(it.trim())) return "Region: two letters, e.g. US, CO, ES." }
        lang?.let { if (it.isNotBlank() && !Regex("[a-z]{2}(-[A-Za-z]{2})?").matches(it.trim())) return "Language like en-US, es-CO, pt-BR." }
        prefs(c).edit().apply {
            key?.let { putString("tmdb_key", it.trim()) }
            region?.let { putString("region", it.trim().uppercase()) }
            lang?.let { putString("lang", it.trim()) }
        }.apply()
        Catalog.reset(c)   // different region/language/key: old suggestions no longer apply
        return null
    }
}

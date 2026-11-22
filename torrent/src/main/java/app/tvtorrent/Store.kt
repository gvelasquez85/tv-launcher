package app.tvtorrent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** Un torrent que se mantiene entre reinicios. */
data class Entry(val hash: String, val magnet: String, val addedAt: Long)

/** Configuracion y lista de torrents guardadas en la TV. */
object Store {
    const val PORT = 8800
    private const val PREFS = "torrent"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun token(len: Int): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"   // sin caracteres ambiguos (0/o, 1/l)
        val r = SecureRandom()
        return (1..len).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
    }

    /** Clave para entrar al portal desde el computador. Se muestra en la pantalla de la TV. */
    @Synchronized fun password(c: Context): String =
        prefs(c).getString("password", null) ?: token(10).also { prefs(c).edit().putString("password", it).apply() }

    /** Si esta activo, el motor y el portal arrancan con la TV y no se apagan por inactividad. */
    fun alwaysOn(c: Context): Boolean = prefs(c).getBoolean("alwaysOn", false)
    fun setAlwaysOn(c: Context, on: Boolean) { prefs(c).edit().putBoolean("alwaysOn", on).apply() }

    /** Reproductor con el que se abren los videos. Kodi se ve limpio en esta TV; VLC sale con franjas verdes. */
    val PLAYERS = linkedMapOf("org.xbmc.kodi" to "Kodi", "org.videolan.vlc" to "VLC")
    fun player(c: Context): String = prefs(c).getString("player", null)?.takeIf { it in PLAYERS } ?: "org.xbmc.kodi"
    fun setPlayer(c: Context, pkg: String) { if (pkg in PLAYERS) prefs(c).edit().putString("player", pkg).apply() }

    /** Hashes de torrents pausados por el usuario y de los que bajan en orden secuencial (se recuerdan entre reinicios). */
    @Synchronized fun flagged(c: Context, key: String): Set<String> = prefs(c).getStringSet(key, emptySet()) ?: emptySet()
    @Synchronized fun setFlagged(c: Context, key: String, hash: String, on: Boolean) {
        val s = flagged(c, key).toMutableSet(); if (on) s += hash else s -= hash
        prefs(c).edit().putStringSet(key, s).apply()
    }
    const val PAUSED = "paused"
    const val SEQUENTIAL = "sequential"

    /** Modo descarga: la TV queda en reposo con las descargas activas y al terminar se deja apagar. */
    fun downloadMode(c: Context): Boolean = prefs(c).getBoolean("downloadMode", false)
    fun setDownloadMode(c: Context, on: Boolean) { prefs(c).edit().putBoolean("downloadMode", on).apply() }

    /** Carpeta de descargas elegida (se recuerda para no perder el progreso si cambia el almacenamiento). */
    fun dirPath(c: Context): String? = prefs(c).getString("dir", null)
    fun setDirPath(c: Context, path: String) { prefs(c).edit().putString("dir", path).apply() }

    /** Pone una clave fija elegida por el usuario. Devuelve null si es valida o el motivo si no. */
    @Synchronized fun setPassword(c: Context, p: String): String? {
        if (p.length < 8) return "La clave debe tener al menos 8 caracteres."
        if (p.length > 64) return "La clave es demasiado larga (máximo 64)."
        if (p.any { it.code < 33 || it.code > 126 }) return "Usa solo letras, números y símbolos (sin espacios ni acentos)."
        if (p.contains(':')) return "La clave no puede llevar el carácter ':'."
        prefs(c).edit().putString("password", p).apply()
        return null
    }

    /** Genera una clave nueva (la anterior deja de funcionar al instante). */
    @Synchronized fun regeneratePassword(c: Context): String =
        token(10).also { prefs(c).edit().putString("password", it).apply() }

    /** Clave aparte para las URL de los archivos (la usa VLC). */
    @Synchronized fun mediaKey(c: Context): String =
        prefs(c).getString("mediaKey", null) ?: token(24).also { prefs(c).edit().putString("mediaKey", it).apply() }

    @Synchronized fun entries(c: Context): List<Entry> = try {
        val a = JSONArray(prefs(c).getString("entries", "[]"))
        List(a.length()) { val o = a.getJSONObject(it); Entry(o.getString("hash"), o.getString("magnet"), o.getLong("addedAt")) }
    } catch (_: Exception) { emptyList() }

    @Synchronized private fun save(c: Context, list: List<Entry>) {
        val a = JSONArray(list.map { JSONObject().put("hash", it.hash).put("magnet", it.magnet).put("addedAt", it.addedAt) })
        prefs(c).edit().putString("entries", a.toString()).apply()
    }

    @Synchronized fun add(c: Context, e: Entry) {
        if (entries(c).none { it.hash == e.hash }) save(c, entries(c) + e)
    }

    @Synchronized fun remove(c: Context, hash: String) {
        save(c, entries(c).filterNot { it.hash == hash })
        setFlagged(c, PAUSED, hash, false); setFlagged(c, SEQUENTIAL, hash, false)
    }
}

package app.tvlauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Un titulo (serie o pelicula) con la informacion basica para mostrar. */
data class Title(
    val id: Int,
    val type: String,          // "tv" | "movie"
    val name: String,
    val year: String,
    val rating: Double,
    val votes: Int,
    val overview: String,
    val backdrop: String?,
    val poster: String?,
    val genres: List<String>,
    val cast: List<String>,
    val extra: String,         // "3 temporadas" | "2 h 10 min"
    val imdb: String = ""      // "tt1234567": permite abrir el titulo exacto en Stremio
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("type", type); put("name", name); put("year", year)
        put("rating", rating); put("votes", votes); put("overview", overview)
        put("backdrop", backdrop ?: ""); put("poster", poster ?: "")
        put("genres", JSONArray(genres)); put("cast", JSONArray(cast)); put("extra", extra)
        put("imdb", imdb)
    }

    companion object {
        fun fromJson(o: JSONObject) = Title(
            o.getInt("id"), o.getString("type"), o.getString("name"), o.getString("year"),
            o.getDouble("rating"), o.getInt("votes"), o.getString("overview"),
            o.optString("backdrop").ifEmpty { null }, o.optString("poster").ifEmpty { null },
            o.getJSONArray("genres").let { a -> List(a.length()) { a.getString(it) } },
            o.getJSONArray("cast").let { a -> List(a.length()) { a.getString(it) } },
            o.getString("extra"),
            o.optString("imdb")
        )
    }
}

/**
 * Catalogo de "lo popular ahora" por plataforma, desde la API de TMDB.
 * Cache en memoria y en disco (12 h). providerId 0 = tendencias generales.
 * Atribucion obligatoria: "Este producto usa la API de TMDB pero no esta avalado ni certificado por TMDB".
 */
object Catalog {
    const val IMG = "https://image.tmdb.org/t/p/"
    private const val API = "https://api.themoviedb.org/3"
    private const val TTL_MS = 12L * 3600 * 1000
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val mem = ConcurrentHashMap<Int, List<Title>>()
    private val waiters = HashMap<Int, MutableList<(List<Title>) -> Unit>>()

    fun hasKey(ctx: Context) = Config.tmdbKey(ctx).isNotBlank()

    /** Forgets cached suggestions (the key, region or language changed). */
    fun reset(ctx: Context) {
        mem.clear()
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("catalog_") }?.forEach { it.delete() }
    }

    /**
     * Entrega SIEMPRE una respuesta en el hilo principal (lista vacia si no hay datos ni red),
     * para que la pantalla nunca se quede mostrando informacion de otra plataforma.
     */
    fun load(ctx: Context, providerId: Int, cb: (List<Title>) -> Unit) {
        mem[providerId]?.let { cb(it); return }
        val file = File(ctx.filesDir, "catalog_v2_${Config.region(ctx)}_${Config.lang(ctx)}_$providerId.json")   // v2: incluye el id de IMDb
        var stale = true
        if (file.exists()) {
            try {
                val list = parse(file.readText())
                mem[providerId] = list
                cb(list)
                stale = System.currentTimeMillis() - file.lastModified() >= TTL_MS
                if (!stale) return
            } catch (_: Exception) { /* cache danada: se vuelve a pedir */ }
        }
        val hadCache = mem.containsKey(providerId)
        if (!hasKey(ctx)) { if (!hadCache) cb(emptyList()); return }
        synchronized(waiters) {
            val running = waiters[providerId]
            if (running != null) {            // ya hay una consulta en curso: solo se suma a la espera
                if (!hadCache) running.add(cb)
                return
            }
            waiters[providerId] = mutableListOf<(List<Title>) -> Unit>().also { if (!hadCache) it.add(cb) }
        }
        pool.execute {
            val result = try { fetch(ctx, providerId) } catch (_: Exception) { emptyList() }
            if (result.isNotEmpty()) {
                try { file.writeText(JSONArray(result.map { it.toJson() }).toString()) } catch (_: Exception) {}
                mem[providerId] = result
            }
            val toNotify = synchronized(waiters) { waiters.remove(providerId) } ?: mutableListOf()
            main.post { toNotify.forEach { it(result) } }
        }
    }

    private fun parse(text: String): List<Title> =
        JSONArray(text).let { a -> List(a.length()) { Title.fromJson(a.getJSONObject(it)) } }

    private fun date(daysAgo: Int): String {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }

    private fun get(ctx: Context, path: String, query: String): JSONObject {
        val url = "$API$path?api_key=${Config.tmdbKey(ctx)}&language=${Config.lang(ctx)}&$query"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000
        try {
            if (c.responseCode != 200) throw RuntimeException("TMDB ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    private fun fetch(ctx: Context, providerId: Int): List<Title> {
        data class Ref(val id: Int, val type: String, val pop: Double)
        val refs = mutableListOf<Ref>()
        if (providerId == 0) {
            val r = get(ctx, "/trending/all/week", "").getJSONArray("results")
            for (i in 0 until r.length()) {
                val o = r.getJSONObject(i)
                val t = o.optString("media_type")
                if (t == "tv" || t == "movie") refs += Ref(o.getInt("id"), t, o.optDouble("popularity"))
            }
        } else {
            val common = "watch_region=${Config.region(ctx)}&with_watch_providers=$providerId" +
                "&with_watch_monetization_types=flatrate%7Cfree%7Cads&sort_by=popularity.desc"
            // Series con episodios recientes y peliculas de los ultimos ~18 meses.
            val tv = get(ctx, "/discover/tv", "$common&air_date.gte=${date(60)}").getJSONArray("results")
            val mv = get(ctx, "/discover/movie", "$common&primary_release_date.gte=${date(540)}").getJSONArray("results")
            for (i in 0 until minOf(tv.length(), 8)) tv.getJSONObject(i).let { refs += Ref(it.getInt("id"), "tv", it.optDouble("popularity")) }
            for (i in 0 until minOf(mv.length(), 8)) mv.getJSONObject(i).let { refs += Ref(it.getInt("id"), "movie", it.optDouble("popularity")) }
        }
        val top = refs.sortedByDescending { it.pop }.take(14)
        return top.mapNotNull { runCatching { detail(ctx, it.id, it.type) }.getOrNull() }
    }

    private fun detail(ctx: Context, id: Int, type: String): Title? {
        val o = get(ctx, "/$type/$id", "append_to_response=credits,external_ids")
        var overview = o.optString("overview")
        if (overview.isBlank()) {
            // Sin traduccion: se usa el texto en ingles antes que dejarlo vacio.
            overview = runCatching {
                val url = "$API/$type/$id?api_key=${Config.tmdbKey(ctx)}&language=en-US"
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 12000
                JSONObject(c.inputStream.bufferedReader().readText()).optString("overview")
            }.getOrDefault("")
        }
        val name = if (type == "tv") o.optString("name") else o.optString("title")
        if (name.isBlank()) return null
        val date = if (type == "tv") o.optString("first_air_date") else o.optString("release_date")
        val genres = o.optJSONArray("genres")?.let { a -> List(a.length()) { a.getJSONObject(it).optString("name") } } ?: emptyList()
        val cast = o.optJSONObject("credits")?.optJSONArray("cast")?.let { a ->
            List(minOf(a.length(), 6)) { a.getJSONObject(it).optString("name") }
        } ?: emptyList()
        val extra = if (type == "tv") {
            val s = o.optInt("number_of_seasons")
            if (s > 0) "$s ${if (s == 1) "temporada" else "temporadas"}" else ""
        } else {
            val m = o.optInt("runtime")
            if (m > 0) "${m / 60} h ${m % 60} min" else ""
        }
        // Series: external_ids.imdb_id. Peliculas: imdb_id en el propio objeto.
        val imdb = (if (type == "tv") o.optJSONObject("external_ids")?.optString("imdb_id") else o.optString("imdb_id")) ?: ""
        return Title(
            id, type, name, date.take(4), o.optDouble("vote_average"), o.optInt("vote_count"),
            overview, o.optString("backdrop_path").ifEmpty { null }, o.optString("poster_path").ifEmpty { null },
            genres, cast, extra, imdb
        )
    }
}

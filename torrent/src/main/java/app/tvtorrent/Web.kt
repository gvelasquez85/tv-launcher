package app.tvtorrent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest

/**
 * Portal web dentro de la TV. Se abre desde el computador o el celular en la misma red:
 *   http://<IP-de-la-TV>:8800   (usuario admin, clave en la pantalla de la TV)
 *
 * Seguridad: clave, cabecera X-Portal en las acciones (anti-CSRF), comprobacion de Host,
 * solo enlaces magnet, y los archivos se sirven con una clave distinta en la URL.
 */
class Web(private val ctx: Context) : NanoHTTPD(Store.PORT) {

    @Volatile var lastActivity = System.currentTimeMillis()

    init { key = Store.mediaKey(ctx) }

    private fun eq(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun json(status: Response.Status, o: Any): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString()).also(::secure)

    private fun text(status: Response.Status, mime: String, body: String): Response =
        newFixedLengthResponse(status, mime, body).also(::secure)

    private fun secure(r: Response) {
        r.addHeader("X-Content-Type-Options", "nosniff")
        r.addHeader("Referrer-Policy", "no-referrer")
        r.addHeader("Cache-Control", "no-store")
    }

    private fun allowedHosts(): Set<String> {
        val s = mutableSetOf("localhost", "127.0.0.1")
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().forEach { ni ->
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>().forEach { s += it.hostAddress ?: "" }
            }
        }
        return s
    }

    override fun serve(session: IHTTPSession): Response {
        lastActivity = System.currentTimeMillis()
        val host = (session.headers["host"] ?: "").substringBefore(":").lowercase()
        if (host !in allowedHosts()) return text(Response.Status.BAD_REQUEST, "text/plain", "Host no permitido")

        val uri = session.uri
        if (uri.startsWith("/media/")) return media(session, uri)

        // Todo lo demas exige usuario y clave.
        val auth = session.headers["authorization"] ?: ""
        val ok = auth.startsWith("Basic ") && runCatching {
            val d = String(Base64.decode(auth.removePrefix("Basic "), Base64.DEFAULT))
            d.substringBefore(":") == "admin" && eq(d.substringAfter(":"), Store.password(ctx))
        }.getOrDefault(false)
        if (!ok) {
            return text(Response.Status.UNAUTHORIZED, "text/plain", "Acceso restringido")
                .also { it.addHeader("WWW-Authenticate", "Basic realm=\"Descargas TV\"") }
        }
        if (session.method == Method.POST && session.headers["x-portal"] != "1") {
            return text(Response.Status.FORBIDDEN, "text/plain", "Falta la cabecera X-Portal")
        }

        return try {
            when {
                session.method == Method.GET && uri == "/" ->
                    text(Response.Status.OK, "text/html; charset=utf-8",
                        ctx.assets.open("index.html").bufferedReader().readText())
                session.method == Method.GET && uri == "/api/status" -> status()
                session.method == Method.GET && uri == "/api/downloads" -> downloads()
                session.method == Method.GET && uri == "/api/library" -> library(host, session)
                session.method == Method.POST && uri == "/api/add" -> add(body(session))
                session.method == Method.POST && uri == "/api/remove" -> remove(body(session))
                session.method == Method.POST && uri == "/api/delete" -> delete(body(session))
                session.method == Method.GET && uri == "/api/torrent" -> torrentDetails(session.parms["gid"] ?: "")
                session.method == Method.POST && uri == "/api/torrent" -> torrentAction(body(session))
                session.method == Method.GET && uri == "/api/mode" -> mode()
                session.method == Method.POST && uri == "/api/mode" -> setMode(body(session))
                session.method == Method.POST && uri == "/api/play" -> play(body(session))
                session.method == Method.GET && uri == "/api/buttons" -> buttons()
                session.method == Method.POST && uri == "/api/buttons" -> saveButtons(body(session))
                session.method == Method.POST && uri == "/api/buttons/learn" -> learnStart()
                session.method == Method.GET && uri == "/api/buttons/learned" -> learnResult()
                session.method == Method.POST && uri == "/api/password" -> password(body(session))
                session.method == Method.GET && uri == "/api/tmdb" -> json(Response.Status.OK, launcher("tmdb_get") ?: JSONObject().put("error", NO_LAUNCHER))
                session.method == Method.POST && uri == "/api/tmdb" -> saveTmdb(body(session))
                session.method == Method.GET && uri == "/api/settings" -> settings()
                session.method == Method.POST && uri == "/api/settings" -> saveSettings(body(session))
                else -> text(Response.Status.NOT_FOUND, "text/plain", "No encontrado")
            }
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "error"))
        }
    }

    private fun body(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return try { JSONObject(files["postData"] ?: "{}") } catch (_: Exception) { JSONObject() }
    }

    // ---------------------------------------------------------------- API

    // Con el USB saturado, leer el disco puede tardar casi un minuto. El portal responde al instante con el
    // ultimo dato conocido y lo renueva en segundo plano.
    private class Cached<T>(private val ttl: Long, private val load: () -> T) {
        @Volatile private var value: T? = null
        @Volatile private var at = 0L
        @Volatile private var loading = false
        fun get(exec: java.util.concurrent.Executor, fallback: T): T {
            if (!loading && System.currentTimeMillis() - at > ttl) {
                loading = true
                exec.execute { try { value = load(); at = System.currentTimeMillis() } catch (_: Throwable) { } finally { loading = false } }
            }
            return value ?: fallback
        }
    }
    private val diskExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val storageCache = Cached(10_000) { Engine.storage(ctx) }
    private val libraryCache = Cached(15_000) { Engine.library(ctx) }

    private fun status(): Response {
        val ip = LocalNet.ipv4() ?: "?"
        val s = storageCache.get(diskExec, StorageInfo(Engine.dir, Engine.dir != null, false, 0, 0, Engine.storageNote))
        return json(Response.Status.OK, JSONObject()
            .put("free_gb", Math.round(s.free / 1e8) / 10.0)
            .put("total_gb", Math.round(s.total / 1e8) / 10.0)
            .put("min_free_gb", Engine.MIN_FREE_BYTES / 1_000_000_000.0)
            .put("download_dir", s.dir?.path ?: "")
            .put("storage_ok", s.available).put("storage_removable", s.removable).put("storage_note", s.note)
            .put("engine_running", Engine.running)
            .put("lan", ip).put("tv", "esta TV"))
    }

    private fun downloads(): Response {
        val a = JSONArray()
        Engine.list(ctx).forEach { r ->
            a.put(JSONObject().put("gid", r.hash).put("name", r.name)
                .put("status", when (r.state) { "complete" -> "complete"; "queued" -> "waiting"; "storage" -> "storage"; "paused" -> "paused"; "checking" -> "checking"; "metadata" -> "metadata"; else -> "active" })
                .put("up", r.up).put("seeds", r.seeds).put("eta", r.eta).put("sequential", r.sequential)
                .put("total", r.total).put("done", r.done).put("speed", r.speed).put("peers", r.peers)
                .put("error", JSONObject.NULL))
        }
        return json(Response.Status.OK, a)
    }

    private val HASH = Regex("[0-9a-f]{40}")

    private fun torrentDetails(gid: String): Response {
        if (!HASH.matches(gid)) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "id inválido"))
        val d = Engine.details(ctx, gid) ?: return json(Response.Status.NOT_FOUND, JSONObject().put("error", "Torrent no encontrado"))
        return json(Response.Status.OK, d)
    }

    /** Acciones: pause, resume, peers, recheck, sequential(on), top, file(index,wanted), remove, cancel (borra lo descargado). */
    private fun torrentAction(b: JSONObject): Response {
        val gid = b.optString("gid")
        if (!HASH.matches(gid)) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "id inválido"))
        when (b.optString("action")) {
            "pause" -> Engine.pause(ctx, gid)
            "resume" -> { Engine.resume(ctx, gid); TorrentService.start(ctx) }
            "peers" -> Engine.morePeers(gid)
            "recheck" -> Engine.recheck(gid)
            "sequential" -> Engine.setSequential(ctx, gid, b.optBoolean("on"))
            "top" -> Engine.toTop(gid)
            "file" -> Engine.setFileWanted(gid, b.optInt("index", -1), b.optBoolean("wanted", true))
            "remove" -> Engine.remove(ctx, gid, deleteFiles = false)
            "cancel" -> Engine.remove(ctx, gid, deleteFiles = true)
            else -> return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "acción desconocida"))
        }
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun mode(): Response = json(Response.Status.OK, JSONObject().put("downloadMode", Store.downloadMode(ctx)))

    private fun setMode(b: JSONObject): Response {
        if (b.optBoolean("on")) {
            val err = PowerMode.enable(ctx)
            if (err != null) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", err))
        } else PowerMode.disable(ctx)
        return mode()
    }

    private fun library(host: String, session: IHTTPSession): Response {
        val dir = Engine.dir ?: return json(Response.Status.OK, JSONArray())
        val port = session.headers["host"]?.substringAfter(":", "")?.ifEmpty { null } ?: Store.PORT.toString()
        val a = JSONArray()
        libraryCache.get(diskExec, emptyList()).forEach { f ->
            val rel = f.relativeTo(dir).path
            a.put(JSONObject().put("path", rel).put("name", f.name).put("size", f.length())
                .put("mtime", f.lastModified() / 1000)
                .put("url", mediaUrl(host, port, rel)))
        }
        return json(Response.Status.OK, a)
    }

    private fun add(b: JSONObject): Response {
        return try {
            val hash = Engine.add(ctx, b.optString("magnet"))
            TorrentService.start(ctx)
            json(Response.Status.OK, JSONObject().put("gid", hash))
        } catch (e: IllegalArgumentException) {
            json(Response.Status.BAD_REQUEST, JSONObject().put("error", e.message))
        }
    }

    private fun remove(b: JSONObject): Response {
        val gid = b.optString("gid")
        if (!Regex("[0-9a-f]{40}").matches(gid)) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "id inválido"))
        Engine.remove(ctx, gid, deleteFiles = false)
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun safeFile(rel: String): File? {
        val base = Engine.dir?.canonicalFile ?: return null
        val f = File(base, rel).canonicalFile
        return if (f.path.startsWith(base.path + File.separator) && f.isFile &&
            f.extension.lowercase() in Engine.VIDEO_EXT) f else null
    }

    private fun delete(b: JSONObject): Response {
        val f = safeFile(b.optString("path")) ?: return json(Response.Status.NOT_FOUND, JSONObject().put("error", "Archivo no encontrado"))
        f.delete()
        var p = f.parentFile
        val base = Engine.dir?.canonicalFile
        while (p != null && base != null && p != base && p.listFiles()?.isEmpty() == true) { p.delete(); p = p.parentFile }
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun play(b: JSONObject): Response {
        val rel = b.optString("path")
        safeFile(rel) ?: return json(Response.Status.NOT_FOUND, JSONObject().put("error", "Archivo no encontrado"))
        return if (playOnTv(ctx, rel)) json(Response.Status.OK, JSONObject().put("ok", true))
        else json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "No se pudo abrir VLC en la TV"))
    }

    // ---------------------------------------------- Botones (los guarda el launcher)

    private val LAUNCHER = Uri.parse("content://app.tvlauncher.config")

    /** Llama al launcher. Las listas se devuelven envueltas en {"data": [...]}. Null si el launcher no responde. */
    private fun launcher(method: String, arg: String? = null): JSONObject? = try {
        ctx.contentResolver.call(LAUNCHER, method, arg, null)?.getString("json")?.let {
            if (it.trimStart().startsWith("[")) JSONObject().put("data", JSONArray(it)) else JSONObject(it)
        }
    } catch (_: Exception) { null }

    private val NO_LAUNCHER = "El launcher no responde. Comprueba que esté instalado y actualizado."

    private fun buttons(): Response {
        val list = launcher("list") ?: return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", NO_LAUNCHER))
        return json(Response.Status.OK, JSONObject()
            .put("mappings", list.optJSONArray("data") ?: JSONArray())
            .put("apps", launcher("apps")?.optJSONArray("data") ?: JSONArray())
            .put("inputs", launcher("inputs")?.optJSONArray("data") ?: JSONArray())
            .put("status", launcher("status") ?: JSONObject()))
    }

    private fun saveButtons(b: JSONObject): Response {
        val r = launcher("save", (b.optJSONArray("list") ?: JSONArray()).toString())
            ?: return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", NO_LAUNCHER))
        return json(if (r.has("error")) Response.Status.BAD_REQUEST else Response.Status.OK, r)
    }

    private fun learnStart(): Response =
        json(Response.Status.OK, launcher("learn_start") ?: JSONObject().put("error", NO_LAUNCHER))

    private fun learnResult(): Response =
        json(Response.Status.OK, launcher("learn_result") ?: JSONObject().put("error", NO_LAUNCHER))

    // ---------------------------------------------------- Clave y ajustes

    private fun password(b: JSONObject): Response {
        val err = Store.setPassword(ctx, b.optString("password"))
        return if (err == null) json(Response.Status.OK, JSONObject().put("ok", true))
        else json(Response.Status.BAD_REQUEST, JSONObject().put("error", err))
    }

    private fun saveTmdb(b: JSONObject): Response {
        val o = JSONObject()
        listOf("key", "region", "lang").forEach { if (b.has(it) && b.optString(it).isNotBlank()) o.put(it, b.optString(it)) }
        val r = launcher("tmdb_set", o.toString()) ?: return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", NO_LAUNCHER))
        return json(if (r.has("error")) Response.Status.BAD_REQUEST else Response.Status.OK, r)
    }

    private fun settings(): Response {
        val players = JSONObject().apply { Store.players(ctx).forEach { (k, v) -> put(k, v) } }
        return json(Response.Status.OK, JSONObject().put("player", Store.player(ctx)).put("players", players)
            .put("alwaysOn", Store.alwaysOn(ctx)))
    }

    private fun saveSettings(b: JSONObject): Response {
        if (b.has("player")) Store.setPlayer(ctx, b.optString("player"))
        if (b.has("alwaysOn")) Store.setAlwaysOn(ctx, b.optBoolean("alwaysOn"))
        return settings()
    }

    // ------------------------------------------------------------- Archivos

    private fun media(session: IHTTPSession, uri: String): Response {
        val parts = uri.removePrefix("/media/").split("/", limit = 2)
        if (parts.size != 2 || !eq(parts[0], Store.mediaKey(ctx))) return text(Response.Status.NOT_FOUND, "text/plain", "No encontrado")
        val f = safeFile(parts[1]) ?: return text(Response.Status.NOT_FOUND, "text/plain", "No encontrado")
        val total = f.length()
        val mime = when (f.extension.lowercase()) {
            "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"; "mov" -> "video/quicktime"; "ts" -> "video/mp2t"
            else -> "application/octet-stream"
        }
        val range = session.headers["range"]
        var start = 0L
        var end = total - 1
        var partial = false
        if (range != null && range.startsWith("bytes=")) {
            val (s, e) = range.removePrefix("bytes=").substringBefore(",").let { it.substringBefore("-") to it.substringAfter("-") }
            if (s.isNotEmpty()) { start = s.toLongOrNull() ?: 0L; if (e.isNotEmpty()) end = minOf(end, e.toLongOrNull() ?: end) }
            else if (e.isNotEmpty()) { start = maxOf(0L, total - (e.toLongOrNull() ?: 0L)) }
            if (start > end || start >= total) {
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                    .also { it.addHeader("Content-Range", "bytes */$total") }
            }
            partial = true
        }
        val len = end - start + 1
        val fis = object : FileInputStream(f) {
            // Mientras haya un video abierto el servicio no se apaga por inactividad.
            init { streams.incrementAndGet() }
            private var closed = false
            override fun read(b: ByteArray, off: Int, len: Int): Int { lastActivity = System.currentTimeMillis(); return super.read(b, off, len) }
            override fun close() { if (!closed) { closed = true; streams.decrementAndGet() }; super.close() }
        }
        var skipped = 0L
        while (skipped < start) { val n = fis.skip(start - skipped); if (n <= 0) break; skipped += n }
        val resp = newFixedLengthResponse(if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK, mime, fis, len)
        resp.addHeader("Accept-Ranges", "bytes")
        if (partial) resp.addHeader("Content-Range", "bytes $start-$end/$total")
        return resp
    }

    companion object {
        /** Videos que se estan sirviendo ahora mismo. */
        val streams = java.util.concurrent.atomic.AtomicInteger(0)

        fun mediaUrl(host: String, port: String, rel: String): String {
            val enc = rel.split("/").joinToString("/") { Uri.encode(it) }
            return "http://$host:$port/media/${key ?: ""}/$enc"
        }
        @Volatile var key: String? = null

        /** Abre el archivo en el reproductor elegido, servido por este mismo portal en 127.0.0.1. */
        fun playOnTv(ctx: Context, rel: String): Boolean {
            // Lo mas fiable: abrir el archivo directo del USB. Asi la reproduccion no depende de que el portal siga vivo.
            // Solo en Android 10 o anterior y en almacenamiento externo (USB): desde Android 11 otras apps no pueden leer
            // Android/data, y la memoria interna de esta app tampoco es legible por el reproductor. Alli se usa el portal.
            val file = (Engine.dir ?: Store.dirPath(ctx)?.let { File(it) })?.let { File(it, rel) }
            val direct = android.os.Build.VERSION.SDK_INT < 30 && file != null && file.path.startsWith("/storage/") &&
                !file.path.startsWith("/storage/emulated/")
            if (direct && file != null && file.isFile) {
                try {
                    android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().build())   // permite pasar file:// a otra app
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file), "video/*")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).also { if (Store.player(ctx).isNotEmpty()) it.setPackage(Store.player(ctx)) })
                    return true
                } catch (_: Exception) { /* se prueba por HTTP */ }
            }
            val url = mediaUrl("127.0.0.1", Store.PORT.toString(), rel)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .also { if (Store.player(ctx).isNotEmpty()) it.setPackage(Store.player(ctx)) }
            return try { ctx.startActivity(i); true } catch (_: Exception) {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                } catch (_: Exception) { false }
            }
        }
    }
}

/** Direccion de red local de la TV. */
object LocalNet {
    fun ipv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}

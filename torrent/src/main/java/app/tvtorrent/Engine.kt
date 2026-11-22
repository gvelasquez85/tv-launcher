package app.tvtorrent

import android.content.Context
import android.os.Environment
import android.os.StatFs
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.FileStorage
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.swig.sha1_hash
import java.io.File
import java.net.URLDecoder

/** Una fila de la lista de descargas. */
data class Row(
    val hash: String, val name: String, val state: String,   // metadata | active | complete | queued | storage | paused | checking
    val total: Long, val done: Long, val speed: Long, val peers: Int,
    val up: Long = 0, val seeds: Int = 0, val paused: Boolean = false, val eta: Long = -1, val sequential: Boolean = false
)

/** Estado del almacenamiento de descargas, para mostrarlo en la TV y en el portal. */
data class StorageInfo(val dir: File?, val available: Boolean, val removable: Boolean, val total: Long, val free: Long, val note: String)

/**
 * Motor de torrents (libtorrent4j). Limites bajos a proposito: la TV tiene 2 GB de RAM.
 *
 * Carpeta de descargas: se elige una vez (almacenamiento extraible montado si lo hay; si no, la
 * carpeta de la app) y se recuerda. Si mas tarde no esta disponible (USB desconectado o sin montar)
 * el motor NO arranca ni crea nada en otro sitio: espera a que vuelva y entonces reanuda.
 */
object Engine {
    private var sm: SessionManager? = null
    private val stoppedSeeding = HashSet<String>()
    @Volatile var dir: File? = null
    @Volatile var storageNote: String = ""
    const val MIN_FREE_BYTES = 1L * 1024 * 1024 * 1024   // se rechaza agregar con menos de 1 GB libre

    val running: Boolean get() = sm?.isRunning == true

    private val MAGNET = Regex("^magnet:\\?xt=urn:btih:([A-Za-z0-9]{32,40})(?:&[^\\s]*)?$")

    /** Devuelve el hash en hexadecimal (40) o null si no es un magnet valido. Acepta hex y base32. */
    fun hashOf(magnet: String): String? {
        if (magnet.length > 4096) return null
        val m = MAGNET.find(magnet.trim()) ?: return null
        val h = m.groupValues[1]
        return when (h.length) {
            40 -> if (h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) h.lowercase() else null
            32 -> base32ToHex(h)
            else -> null
        }
    }

    private fun base32ToHex(s: String): String? {
        val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0; var value = 0
        val out = StringBuilder()
        for (ch in s.uppercase()) {
            val i = alpha.indexOf(ch); if (i < 0) return null
            value = (value shl 5) or i; bits += 5
            if (bits >= 8) { bits -= 8; out.append("%02x".format((value shr bits) and 0xFF)) }
        }
        return if (out.length == 40) out.toString() else null
    }

    // ------------------------------------------------------------ Almacenamiento

    /** La carpeta sirve si existe, se puede escribir y es un sistema de archivos real (no un directorio vacio de RAM). */
    private fun usable(d: File): Boolean = try {
        d.mkdirs()
        d.isDirectory && d.canWrite() && StatFs(d.path).totalBytes > 100L * 1024 * 1024
    } catch (_: Exception) { false }

    private fun isRemovable(d: File) = try { Environment.isExternalStorageRemovable(d) } catch (_: Exception) { false }

    /** Por que una carpeta no sirve (para diagnostico). */
    private fun why(d: File): String = try {
        val st = runCatching { StatFs((if (d.exists()) d else d.parentFile ?: d).path) }.getOrNull()
        "${d.path} existe=${d.exists()} dir=${d.isDirectory} escribible=${d.canWrite()} total=${(st?.totalBytes ?: -1) / 1_000_000}MB libre=${(st?.availableBytes ?: -1) / 1_000_000}MB"
    } catch (e: Exception) { "${d.path} error=${e.javaClass.simpleName}" }

    /** Elige (y recuerda) la carpeta; null si la recordada no esta disponible ahora. */
    private fun chooseDir(ctx: Context): File? {
        Store.dirPath(ctx)?.let { saved ->
            val f = File(saved)
            return if (usable(f)) f.also { storageNote = "" }
            else { storageNote = "La carpeta de descargas no está disponible (¿USB desconectado o sin montar?) · " + why(f); null }
        }
        val removable = ctx.getExternalFilesDirs(null).filterNotNull().firstOrNull {
            isRemovable(it) && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
        }
        val chosen = File(removable ?: ctx.filesDir, "downloads")
        if (!usable(chosen)) { storageNote = "No hay almacenamiento disponible · " + why(chosen); return null }
        Store.setDirPath(ctx, chosen.path)
        storageNote = ""
        return chosen
    }

    fun storage(ctx: Context): StorageInfo {
        val d = dir ?: Store.dirPath(ctx)?.let { File(it) }
        val ok = dir != null
        return try {
            val st = d?.let { if (it.exists()) StatFs(it.path) else null }
            StorageInfo(d, ok, d?.let { isRemovable(it) } ?: false, st?.totalBytes ?: 0, st?.availableBytes ?: 0,
                if (ok) "" else storageNote.ifEmpty { "Almacenamiento no disponible" })
        } catch (_: Exception) { StorageInfo(d, false, false, 0, 0, storageNote.ifEmpty { "Almacenamiento no disponible" }) }
    }

    fun freeBytes(): Long = dir?.let { runCatching { StatFs(it.path).availableBytes }.getOrDefault(0L) } ?: 0L

    // ------------------------------------------------------------------ Motor

    /** Arranca el motor si el almacenamiento esta disponible. Se puede llamar repetidamente: no hace nada si ya corre. */
    @Synchronized fun start(ctx: Context) {
        if (running) return
        val d = chooseDir(ctx)
        dir = d
        if (d == null) return                      // sin almacenamiento: se reintenta mas tarde
        val pack = SettingsPack().apply {
            connectionsLimit(60)
            maxPeerlistSize(200)
            activeDownloads(2)
            activeSeeds(1)
            activeLimit(3)
            uploadRateLimit(150 * 1024)            // 150 KB/s: no saturar la red de la casa
        }
        val s = SessionManager()
        s.start(SessionParams(pack))
        sm = s
        // Cada vez que libtorrent entrega datos de reanudacion (metadatos + piezas listas) se guardan: asi tras un
        // reinicio no hay que pedir otra vez el enlace ni volver a verificar todo el archivo.
        s.addListener(object : org.libtorrent4j.AlertListener {
            override fun types() = intArrayOf(org.libtorrent4j.alerts.AlertType.SAVE_RESUME_DATA.swig())
            override fun alert(a: org.libtorrent4j.alerts.Alert<*>) {
                if (a is org.libtorrent4j.alerts.SaveResumeDataAlert) runCatching {
                    resumeFile(ctx, a.handle().infoHash().toHex()).writeBytes(AddTorrentParams.writeResumeDataBuf(a.params()))
                }
            }
        })
        val paused = Store.flagged(ctx, Store.PAUSED)
        val seq = Store.flagged(ctx, Store.SEQUENTIAL)
        Store.entries(ctx).forEach {
            runCatching {
                var f = if (it.hash in paused) TorrentFlags.PAUSED else TorrentFlags.AUTO_MANAGED
                if (it.hash in seq) f = f.or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                if (!addFromResume(ctx, s, it.hash, d, f)) s.download(it.magnet, d, f)
            }
        }
    }

    private fun resumeFile(ctx: Context, hash: String) = File(File(ctx.filesDir, "resume").apply { mkdirs() }, "$hash.fastresume")

    /** Agrega el torrent con lo guardado la ultima vez. False si no hay datos o no sirven (se usa el magnet). */
    private fun addFromResume(ctx: Context, s: SessionManager, hash: String, d: File, flags: org.libtorrent4j.swig.torrent_flags_t): Boolean = try {
        val f = resumeFile(ctx, hash)
        if (!f.exists()) false else {
            val ec = org.libtorrent4j.swig.error_code()
            val p = org.libtorrent4j.swig.libtorrent.read_resume_data_ex(org.libtorrent4j.Vectors.bytes2byte_vector(f.readBytes()), ec)
            if (ec.value() != 0) false else {
                val atp = AddTorrentParams(p)
                atp.setSavePath(d.absolutePath)
                atp.setFlags(flags)
                s.swig().async_add_torrent(atp.swig())
                true
            }
        }
    } catch (_: Throwable) { false }

    /** Pide a libtorrent que guarde los datos de reanudacion de lo que haya cambiado (se llama cada 30 s). */
    private fun saveResume(ctx: Context) {
        Store.entries(ctx).forEach { e ->
            runCatching { find(e.hash)?.let { if (it.needSaveResumeData()) it.saveResumeData(TorrentHandle.SAVE_INFO_DICT) } }
        }
    }

    @Synchronized fun stop() {
        sm?.stop()
        sm = null
        dir = null
        stoppedSeeding.clear()
    }

    /** Si la carpeta desaparecio (USB desconectado) se detiene el motor para no escribir en el vacio. */
    @Synchronized fun checkStorage(ctx: Context) {
        val d = dir
        if (running && d != null && !usable(d)) {
            storageNote = "Se perdió el almacenamiento de descargas (¿USB desconectado?). Las descargas se reanudarán al volver."
            stop()
        }
        if (!running) start(ctx) else saveResume(ctx)
    }

    /** Agrega un magnet. Lanza IllegalArgumentException con un mensaje legible si no se puede. */
    @Synchronized fun add(ctx: Context, magnet: String): String {
        val hash = hashOf(magnet) ?: throw IllegalArgumentException("Eso no parece un enlace magnet válido.")
        start(ctx)
        val d = dir ?: throw IllegalArgumentException(storageNote.ifEmpty { "El almacenamiento de descargas no está disponible." })
        if (freeBytes() < MIN_FREE_BYTES) {
            throw IllegalArgumentException("Poco espacio libre (${freeBytes() / 1_000_000} MB). Libera espacio o conecta el USB.")
        }
        val s = sm ?: throw IllegalStateException("El motor no está activo")
        Store.add(ctx, Entry(hash, magnet.trim(), System.currentTimeMillis()))
        if (find(hash) == null) s.download(magnet.trim(), d, TorrentFlags.AUTO_MANAGED)
        return hash
    }

    private fun find(hash: String) = runCatching {
        sm?.find(Sha1Hash(sha1_hash.from_hex(hash)))?.takeIf { it.isValid }
    }.getOrNull()

    @Synchronized fun remove(ctx: Context, hash: String, deleteFiles: Boolean) {
        find(hash)?.let { h ->
            if (deleteFiles) sm?.remove(h, SessionHandle.DELETE_FILES) else sm?.remove(h)
        }
        Store.remove(ctx, hash)
        runCatching { resumeFile(ctx, hash).delete() }
        stoppedSeeding.remove(hash)
    }

    private fun isPausedFlag(h: org.libtorrent4j.TorrentHandle) =
        h.getFlags().and_(TorrentFlags.PAUSED).non_zero() && !h.getFlags().and_(TorrentFlags.AUTO_MANAGED).non_zero()

    fun list(ctx: Context): List<Row> = Store.entries(ctx).map { e ->
        val h = find(e.hash)
        if (h == null) {
            Row(e.hash, nameFromMagnet(e.magnet), if (dir == null) "storage" else "queued", 0, 0, 0, 0)
        } else {
            val st = h.status()
            val paused = isPausedFlag(h)
            val state = when {
                !st.hasMetadata() -> if (paused) "paused" else "metadata"
                st.isFinished || st.isSeeding -> "complete"
                paused -> "paused"
                st.state() == org.libtorrent4j.TorrentStatus.State.CHECKING_FILES -> "checking"
                else -> "active"
            }
            // Al terminar se deja de compartir para no gastar RAM ni red.
            if (state == "complete" && stoppedSeeding.add(e.hash)) runCatching { h.pause() }
            val name = st.name().ifBlank { nameFromMagnet(e.magnet) }
            val speed = st.downloadPayloadRate().toLong()
            val left = st.totalWanted() - st.totalWantedDone()
            val eta = if (state == "active" && speed > 0) left / speed else -1
            Row(e.hash, name, state, st.totalWanted(), st.totalWantedDone(), speed, st.numPeers(),
                st.uploadPayloadRate().toLong(), st.numSeeds(), paused, eta,
                h.getFlags().and_(TorrentFlags.SEQUENTIAL_DOWNLOAD).non_zero())
        }
    }

    /** Hay algo por descargar (lo pausado a mano no cuenta). */
    fun anyActive(ctx: Context) = list(ctx).any { it.state == "active" || it.state == "metadata" || it.state == "queued" || it.state == "storage" || it.state == "checking" }

    // ------------------------------------------------ Acciones sobre un torrent

    fun pause(ctx: Context, hash: String) {
        Store.setFlagged(ctx, Store.PAUSED, hash, true)
        find(hash)?.let { it.unsetFlags(TorrentFlags.AUTO_MANAGED); it.pause() }
    }

    fun resume(ctx: Context, hash: String) {
        Store.setFlagged(ctx, Store.PAUSED, hash, false)
        stoppedSeeding.remove(hash)
        find(hash)?.let { it.setFlags(TorrentFlags.AUTO_MANAGED); it.resume() }
    }

    /** Pide peers otra vez a los trackers y a la red DHT. */
    fun morePeers(hash: String) {
        find(hash)?.let { runCatching { it.forceReannounce(0, 0, TorrentHandle.IGNORE_MIN_INTERVAL) }; runCatching { it.forceDHTAnnounce() } }
    }

    fun recheck(hash: String) { find(hash)?.forceRecheck() }

    fun setSequential(ctx: Context, hash: String, on: Boolean) {
        Store.setFlagged(ctx, Store.SEQUENTIAL, hash, on)
        find(hash)?.let { if (on) it.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else it.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
    }

    fun toTop(hash: String) { find(hash)?.queuePositionTop() }

    fun setFileWanted(hash: String, index: Int, wanted: Boolean) {
        find(hash)?.let { h ->
            val n = h.torrentFile()?.numFiles() ?: return
            if (index in 0 until n) h.filePriority(index, if (wanted) Priority.DEFAULT else Priority.IGNORE)
        }
    }

    /** Todo lo que se muestra en la pantalla de detalles. Null si el torrent no esta en el motor. */
    fun details(ctx: Context, hash: String): org.json.JSONObject? {
        val h = find(hash) ?: return null
        val e = Store.entries(ctx).firstOrNull { it.hash == hash }
        val st = h.status()
        val row = list(ctx).firstOrNull { it.hash == hash }
        val o = org.json.JSONObject()
        o.put("hash", hash).put("name", row?.name ?: st.name()).put("state", row?.state ?: "active")
            .put("total", st.totalWanted()).put("done", st.totalWantedDone())
            .put("speed", row?.speed ?: 0).put("up", row?.up ?: 0).put("eta", row?.eta ?: -1)
            .put("downloaded", st.allTimeDownload()).put("uploaded", st.allTimeUpload())
            .put("peers", st.numPeers()).put("seeds", st.numSeeds())
            .put("swarmPeers", st.listPeers()).put("swarmSeeds", st.listSeeds())
            .put("paused", row?.paused ?: false).put("sequential", row?.sequential ?: false)
            .put("queue", st.queuePosition()).put("added", (e?.addedAt ?: 0L) / 1000)
            .put("savePath", dir?.path ?: "").put("hasMetadata", st.hasMetadata())
            .put("error", if (st.errorCode().isError) "Error de disco o de red en este torrent" else org.json.JSONObject.NULL)
        val ti = h.torrentFile()
        if (ti != null) {
            o.put("pieces", ti.numPieces()).put("pieceLength", ti.pieceLength())
            val fs = ti.files()
            val prog = runCatching { h.fileProgress() }.getOrNull()
            val prio = runCatching { h.filePriorities() }.getOrNull()
            val files = org.json.JSONArray()
            for (i in 0 until ti.numFiles()) {
                if (fs.fileFlags(i).and_(FileStorage.FLAG_PAD_FILE).non_zero()) continue
                files.put(org.json.JSONObject().put("i", i).put("name", fs.filePath(i)).put("size", fs.fileSize(i))
                    .put("done", prog?.getOrNull(i) ?: 0L).put("wanted", (prio?.getOrNull(i) ?: Priority.DEFAULT) != Priority.IGNORE))
            }
            o.put("files", files)
        }
        val tr = org.json.JSONArray()
        runCatching { h.trackers().forEach { tr.put(org.json.JSONObject().put("url", it.url()).put("ok", it.isVerified)) } }
        o.put("trackers", tr)
        val pe = org.json.JSONArray()
        runCatching {
            h.peerInfo().sortedByDescending { it.downSpeed() }.take(15).forEach {
                pe.put(org.json.JSONObject().put("ip", it.ip()).put("client", it.client()).put("down", it.downSpeed())
                    .put("up", it.upSpeed()).put("progress", Math.round(it.progress() * 100)))
            }
        }
        o.put("peerList", pe)
        return o
    }

    private fun nameFromMagnet(magnet: String): String {
        val dn = Regex("[?&]dn=([^&]+)").find(magnet)?.groupValues?.get(1)
        return dn?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: "Obteniendo datos del magnet..."
    }

    /** Borra un video descargado y las carpetas que quedan vacias. */
    fun deleteFile(f: File) {
        val base = dir?.canonicalFile ?: return
        val c = f.canonicalFile
        if (!c.path.startsWith(base.path + File.separator)) return
        c.delete()
        var p = c.parentFile
        while (p != null && p != base && p.listFiles()?.isEmpty() == true) { p.delete(); p = p.parentFile }
    }

    val VIDEO_EXT = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "wmv", "mpg", "mpeg")

    /**
     * Videos ya descargados, mas recientes primero. Los archivos de torrents que aun no terminan
     * se ocultan: libtorrent reserva el tamano completo al empezar y se veria un video incompleto.
     */
    fun library(ctx: Context, rows: List<Row> = list(ctx)): List<File> {
        val d = dir ?: return emptyList()
        val inProgress = rows.filter { it.state != "complete" }.map { it.name }.toSet()
        return d.walkTopDown().filter { it.isFile && it.extension.lowercase() in VIDEO_EXT }
            .filter { it.relativeTo(d).path.substringBefore(File.separatorChar) !in inProgress }
            .sortedByDescending { it.lastModified() }.toList()
    }
}

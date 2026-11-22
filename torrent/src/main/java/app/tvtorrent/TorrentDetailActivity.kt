package app.tvtorrent

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Detalles de una descarga: estado, velocidades, conexiones, archivos (se pueden omitir), trackers
 * y las acciones: pausar/reanudar, pedir mas peers, orden secuencial, verificar, subir en la cola, quitar y cancelar.
 */
class TorrentDetailActivity : Activity() {

    private lateinit var hash: String
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false

    private lateinit var title: TextView
    private lateinit var stateView: TextView
    private lateinit var bar: Ui.Bar
    private lateinit var progressText: TextView
    private val stats = LinkedHashMap<String, TextView>()
    private lateinit var pauseBtn: TextView
    private lateinit var seqBtn: TextView
    private lateinit var filesBox: LinearLayout
    private lateinit var trackersView: TextView
    private lateinit var peersView: TextView
    private var fileCount = -1
    private val fileViews = ArrayList<Triple<TextView, TextView, View>>()   // nombre, estado, tarjeta
    private var current: JSONObject? = null

    private val refresher = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 2000) } }

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hash = intent.getStringExtra("hash") ?: run { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Ui.background(); setPadding(dp(56), dp(28), dp(56), dp(16)) }

        title = Ui.text(this, "", 28f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        root.addView(title)
        stateView = Ui.text(this, "", 15f, Ui.ACCENT, bold = true).apply { setPadding(0, dp(4), 0, dp(10)) }
        root.addView(stateView)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(32)) }

        // Progreso
        bar = Ui.bar(this, 12)
        body.addView(bar)
        progressText = Ui.text(this, "", 16f, Ui.TEXT).apply { setPadding(0, dp(8), 0, dp(12)) }
        body.addView(progressText)

        // Estadisticas en dos filas de cuatro
        val names = listOf("Descarga", "Subida", "Quedan", "Tamaño", "Descargado", "Subido", "Conexiones", "Cola")
        for (row in names.chunked(4)) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (n in row) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Ui.shape(this@TorrentDetailActivity, "#141C26", 14)
                    setPadding(dp(18), dp(10), dp(18), dp(10))
                }
                cell.addView(Ui.text(this, n.uppercase(), 11f, Ui.MUTED, bold = true))
                val v = Ui.text(this, "—", 20f, Ui.TEXT, bold = true); stats[n] = v; cell.addView(v)
                r.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(10), dp(10)) })
            }
            body.addView(r)
        }

        // Acciones
        body.addView(Ui.text(this, "ACCIONES", 13f, Ui.MUTED, bold = true).apply { letterSpacing = 0.1f; setPadding(dp(4), dp(10), 0, dp(8)) })
        val a1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pauseBtn = Ui.button(this, "Pausar", primary = true) { togglePause() }
        a1.addView(pauseBtn)
        a1.addView(Ui.button(this, "Pedir más conexiones") { act("peers", "Buscando más conexiones…") })
        seqBtn = Ui.button(this, "Orden secuencial: No") { toggleSequential() }
        a1.addView(seqBtn)
        a1.addView(Ui.button(this, "Verificar archivos") { act("recheck", "Verificando archivos…") })
        body.addView(a1, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })
        val a2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        a2.addView(Ui.button(this, "Subir en la cola") { act("top", "Primera de la cola") })
        a2.addView(Ui.button(this, "Quitar de la lista") { confirmRemove(false) })
        a2.addView(Ui.button(this, "Cancelar y borrar", danger = true) { confirmRemove(true) })
        body.addView(a2)

        // Archivos
        body.addView(Ui.text(this, "ARCHIVOS  (selecciona uno para incluirlo u omitirlo)", 13f, Ui.MUTED, bold = true)
            .apply { letterSpacing = 0.1f; setPadding(dp(4), dp(22), 0, dp(8)) })
        filesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(filesBox)

        body.addView(Ui.text(this, "TRACKERS", 13f, Ui.MUTED, bold = true).apply { letterSpacing = 0.1f; setPadding(dp(4), dp(22), 0, dp(6)) })
        trackersView = Ui.text(this, "", 14f, Ui.MUTED); body.addView(trackersView)
        body.addView(Ui.text(this, "MEJORES CONEXIONES", 13f, Ui.MUTED, bold = true).apply { letterSpacing = 0.1f; setPadding(dp(4), dp(22), 0, dp(6)) })
        peersView = Ui.text(this, "", 14f, Ui.MUTED); body.addView(peersView)

        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); ui.post(refresher) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresher) }
    override fun onDestroy() { bg.shutdown(); super.onDestroy() }

    // ------------------------------------------------------------------ datos

    private fun refresh() {
        if (busy) return
        busy = true
        bg.execute {
            val d = try { Engine.details(this, hash) } catch (_: Exception) { null }
            ui.post { busy = false; if (isDestroyed) return@post; if (d == null) finish() else render(d) }
        }
    }

    private fun render(d: JSONObject) {
        current = d
        val total = d.optLong("total"); val done = d.optLong("done")
        val pct = if (total > 0) done.toDouble() / total else 0.0
        val state = d.optString("state")
        val (label, color) = Ui.stateLabel(Row(hash, "", state, total, done, 0, 0))
        title.text = d.optString("name")
        stateView.text = label + if (!d.isNull("error")) "  ·  ${d.optString("error")}" else ""
        stateView.setTextColor(Color.parseColor(if (!d.isNull("error")) Ui.BAD else color))
        bar.set(pct.toFloat(), color)
        progressText.text = "${(pct * 100).toInt()}%   ·   ${Ui.size(done)} de ${if (total > 0) Ui.size(total) else "?"}"

        stats["Descarga"]?.text = Ui.speed(d.optLong("speed"))
        stats["Subida"]?.text = Ui.speed(d.optLong("up"))
        stats["Quedan"]?.text = if (state == "complete") "—" else Ui.eta(d.optLong("eta", -1))
        stats["Tamaño"]?.text = if (total > 0) Ui.size(total) else "?"
        stats["Descargado"]?.text = Ui.size(d.optLong("downloaded"))
        stats["Subido"]?.text = Ui.size(d.optLong("uploaded"))
        stats["Conexiones"]?.text = "${d.optInt("peers")}  (${d.optInt("seeds")} semillas)"
        stats["Cola"]?.text = "#${d.optInt("queue") + 1}"

        val paused = d.optBoolean("paused")
        pauseBtn.text = if (paused) "Reanudar" else "Pausar"
        seqBtn.text = "Orden secuencial: ${if (d.optBoolean("sequential")) "Sí" else "No"}"

        val files = d.optJSONArray("files")
        if (files != null) {
            if (files.length() != fileCount) {
                fileCount = files.length(); filesBox.removeAllViews(); fileViews.clear()
                for (k in 0 until files.length()) {
                    val idx = files.getJSONObject(k).optInt("i")
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(20), dp(10), dp(20), dp(10))
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(3), dp(4), dp(3)) }
                    }
                    Ui.focusable(card, radius = 12)
                    val nm = Ui.text(this, "", 15f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
                    val st = Ui.text(this, "", 14f, Ui.MUTED)
                    card.addView(nm, LinearLayout.LayoutParams(0, -2, 1f)); card.addView(st)
                    card.setOnClickListener {
                        val f = current?.optJSONArray("files")?.optJSONObject(k) ?: return@setOnClickListener
                        bg.execute { Engine.setFileWanted(hash, idx, !f.optBoolean("wanted")); ui.post { refresh() } }
                    }
                    filesBox.addView(card); fileViews.add(Triple(nm, st, card))
                }
            }
            for (k in 0 until files.length()) {
                val f = files.getJSONObject(k)
                val (nm, st, _) = fileViews[k]
                val size = f.optLong("size"); val fdone = f.optLong("done")
                nm.text = f.optString("name")
                nm.alpha = if (f.optBoolean("wanted")) 1f else 0.45f
                st.text = if (!f.optBoolean("wanted")) "Omitido" else "${if (size > 0) fdone * 100 / size else 0}%  ·  ${Ui.size(size)}"
            }
        } else if (fileCount != 0) {
            fileCount = 0; filesBox.removeAllViews(); fileViews.clear()
            filesBox.addView(Ui.text(this, "Aún no se conocen los archivos: el enlace sigue obteniendo datos.", 14f, Ui.MUTED))
        }

        val tr = d.optJSONArray("trackers")
        trackersView.text = if (tr == null || tr.length() == 0) "Sin trackers (se usa la red DHT)"
            else (0 until minOf(tr.length(), 8)).joinToString("\n") { "${if (tr.getJSONObject(it).optBoolean("ok")) "●" else "○"}  ${tr.getJSONObject(it).optString("url")}" }
        val pe = d.optJSONArray("peerList")
        peersView.text = if (pe == null || pe.length() == 0) "Sin conexiones activas por ahora"
            else (0 until pe.length()).joinToString("\n") {
                val p = pe.getJSONObject(it)
                "${p.optString("ip")}   ${p.optString("client")}   ↓ ${Ui.speed(p.optLong("down"))}   ↑ ${Ui.speed(p.optLong("up"))}   ${p.optInt("progress")}%"
            }
    }

    // ---------------------------------------------------------------- acciones

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun act(action: String, msg: String) {
        bg.execute {
            when (action) { "peers" -> Engine.morePeers(hash); "recheck" -> Engine.recheck(hash); "top" -> Engine.toTop(hash) }
            ui.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); refresh() }
        }
    }

    private fun togglePause() {
        val paused = current?.optBoolean("paused") == true
        bg.execute {
            if (paused) { Engine.resume(this, hash); TorrentService.start(this) } else Engine.pause(this, hash)
            ui.post { refresh() }
        }
    }

    private fun toggleSequential() {
        val on = current?.optBoolean("sequential") != true
        bg.execute { Engine.setSequential(this, hash, on); ui.post { Toast.makeText(this, if (on) "Se descarga en orden: sirve para ver mientras baja" else "Orden normal", Toast.LENGTH_SHORT).show(); refresh() } }
    }

    private fun confirmRemove(deleteFiles: Boolean) {
        dialog().setTitle(if (deleteFiles) "Cancelar y borrar" else "Quitar de la lista")
            .setMessage(if (deleteFiles) "Se detiene la descarga y se borra todo lo descargado. No se puede deshacer."
                else "Se quita de la lista y se conservan los archivos ya descargados.")
            .setPositiveButton(if (deleteFiles) "Borrar" else "Quitar") { _, _ ->
                bg.execute { Engine.remove(this, hash, deleteFiles); ui.post { finish() } }
            }.setNegativeButton("Volver", null).show()
    }
}

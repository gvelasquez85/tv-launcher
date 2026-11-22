package app.tvtorrent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * Pantalla principal de la TV: direccion del portal, descargas en curso y videos listos para ver.
 * Todo acceso al motor y al disco (USB, lento) va en un hilo de fondo: nunca en el principal,
 * o Android mata la app por no responder (ANR). Las filas se actualizan en su sitio para no perder el foco.
 */
class DownloadsActivity : Activity() {

    private lateinit var urlView: TextView
    private lateinit var credView: TextView
    private lateinit var storageView: TextView
    private lateinit var storageBar: Ui.Bar
    private lateinit var modeBadge: TextView
    private lateinit var modeBtn: TextView
    private lateinit var downloads: LinearLayout
    private lateinit var library: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false

    private val refresher = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 2000) }
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    /** Datos listos para dibujar, calculados fuera del hilo principal. */
    private class Snapshot(
        val ip: String?, val password: String, val dir: File?, val free: Long, val total: Long,
        val removable: Boolean, val rows: List<Row>, val lib: List<File>, val mode: Boolean
    )

    /** Vistas de una fila de descarga, para actualizarlas sin recrearlas. */
    private class RowViews(val card: View, val name: TextView, val state: TextView, val bar: Ui.Bar, val info: TextView)

    private val rowViews = LinkedHashMap<String, RowViews>()
    private var libKeys: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.background(); clipChildren = false
            setPadding(dp(56), dp(28), dp(56), dp(16))
        }

        // ---- Cabecera ----
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(this, "Descargas", 32f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        modeBadge = Ui.text(this, "", 14f, Ui.WARN, bold = true)
        head.addView(modeBadge)
        root.addView(head)

        // ---- Tarjeta de informacion: portal + almacenamiento ----
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.shape(this@DownloadsActivity, "#141C26", 18)
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(Ui.text(this, "Abre esto en tu computador o celular", 13f, Ui.MUTED))
        urlView = Ui.text(this, "", 26f, Ui.ACCENT, bold = true)
        left.addView(urlView)
        credView = Ui.text(this, "", 15f, Ui.TEXT)
        left.addView(credView)
        info.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; minimumWidth = dp(300) }
        right.addView(Ui.text(this, "Almacenamiento", 13f, Ui.MUTED))
        storageView = Ui.text(this, "", 17f, Ui.TEXT, bold = true)
        right.addView(storageView)
        storageBar = Ui.bar(this, 6).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) }
        right.addView(storageBar)
        info.addView(right, LinearLayout.LayoutParams(dp(320), -2))
        root.addView(info, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        // ---- Acciones ----
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; setPadding(dp(16), dp(6), 0, dp(6)) }
        buttons.addView(Ui.button(this, "＋  Añadir enlace magnet", primary = true) { askMagnet() })
        modeBtn = Ui.button(this, "Modo descarga") { toggleMode() }
        buttons.addView(modeBtn)
        buttons.addView(Ui.button(this, "Ajustes") { showSettings() })
        root.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16); bottomMargin = dp(4) })

        // ---- Listas ----
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        body.addView(section("EN DESCARGA"))
        downloads = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(downloads)
        body.addView(section("LISTOS PARA VER"))
        library = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(library)
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(body) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        TorrentService.start(this)
        ui.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresher)
    }

    override fun onDestroy() {
        bg.shutdown()
        super.onDestroy()
    }

    // ----------------------------------------------------------------- UI

    private fun section(t: String) = Ui.text(this, t, 13f, Ui.MUTED, bold = true).apply {
        letterSpacing = 0.1f; setPadding(dp(4), dp(20), 0, dp(8))
    }

    private fun hint(t: String) = Ui.text(this, t, 15f, Ui.MUTED).apply { setPadding(dp(4), dp(4), 0, dp(4)) }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(14), dp(22), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(6)) }
    }

    private fun newRow(hash: String): RowViews {
        val c = card()
        Ui.focusable(c)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val name = Ui.text(this, "", 19f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        top.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        val state = Ui.text(this, "", 14f, Ui.ACCENT, bold = true)
        top.addView(state)
        c.addView(top)
        val bar = Ui.bar(this, 8).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(10), 0, dp(8)) }
        c.addView(bar)
        val info = Ui.text(this, "", 14f, Ui.MUTED)
        c.addView(info)
        c.setOnClickListener { startActivity(Intent(this, TorrentDetailActivity::class.java).putExtra("hash", hash)) }
        return RowViews(c, name, state, bar, info)
    }

    private fun fillRow(v: RowViews, r: Row) {
        val (label, color) = Ui.stateLabel(r)
        val pct = if (r.total > 0) r.done.toDouble() / r.total else 0.0
        v.name.text = r.name
        v.state.text = if (r.state == "active") "${(pct * 100).toInt()}%" else label
        v.state.setTextColor(Color.parseColor(color))
        v.bar.set(pct.toFloat(), color)
        v.info.text = when (r.state) {
            "active" -> "${Ui.size(r.done)} de ${Ui.size(r.total)}  ·  ${Ui.speed(r.speed)}  ·  quedan ${Ui.eta(r.eta)}  ·  ${r.peers} conexiones"
            "complete" -> "${Ui.size(r.total)}  ·  lista para ver"
            "paused" -> "${Ui.size(r.done)} de ${Ui.size(r.total)}  ·  seleccionar para reanudar o ver detalles"
            else -> if (r.total > 0) "${Ui.size(r.done)} de ${Ui.size(r.total)}" else "Un momento…"
        }
    }

    /** Pide los datos en un hilo de fondo y los dibuja en el principal. */
    private fun refresh() {
        if (busy) return
        busy = true
        bg.execute {
            val snap = try {
                val rows = Engine.list(this)
                val st = Engine.storage(this)
                Snapshot(LocalNet.ipv4(), Store.password(this), Engine.dir, st.free, st.total, st.removable,
                    rows, Engine.library(this, rows), Store.downloadMode(this))
            } catch (_: Exception) { null }
            ui.post { busy = false; if (snap != null && !isDestroyed) render(snap) }
        }
    }

    private fun render(s: Snapshot) {
        urlView.text = "http://${s.ip ?: "?"}:${Store.PORT}"
        credView.text = "Usuario  admin      Clave  ${s.password}"
        if (s.dir == null) {
            storageView.text = Engine.storageNote.ifEmpty { "Iniciando el motor…" }
            storageView.setTextColor(Color.parseColor(Ui.WARN)); storageBar.set(0f, Ui.WARN)
        } else {
            storageView.text = "${if (s.removable) "USB" else "Interno"}  ·  ${Ui.size(s.free)} libres"
            storageView.setTextColor(Color.parseColor(Ui.TEXT))
            storageBar.set(if (s.total > 0) 1f - s.free.toFloat() / s.total else 0f, if (s.free < Engine.MIN_FREE_BYTES) Ui.BAD else Ui.ACCENT)
        }
        modeBadge.text = if (s.mode) "●  MODO DESCARGA ACTIVO" else ""
        modeBtn.text = if (s.mode) "Cancelar modo descarga" else "Modo descarga"

        // Descargas: se recrean solo si cambia la lista; si no, se actualiza el texto de cada fila.
        val hashes = s.rows.map { it.hash }
        if (hashes != rowViews.keys.toList()) {
            val focused = rowViews.entries.firstOrNull { it.value.card.hasFocus() }?.key
            downloads.removeAllViews(); rowViews.clear()
            if (s.rows.isEmpty()) downloads.addView(hint("No hay descargas. Añade un enlace magnet con el botón de arriba o desde el portal."))
            for (r in s.rows) { val v = newRow(r.hash); rowViews[r.hash] = v; downloads.addView(v.card) }
            focused?.let { rowViews[it]?.card?.requestFocus() }
        }
        for (r in s.rows) rowViews[r.hash]?.let { fillRow(it, r) }

        // Biblioteca: se recrea solo si cambian los archivos.
        val keys = s.lib.map { it.path }
        if (keys != libKeys) {
            libKeys = keys
            library.removeAllViews()
            val base = s.dir
            if (s.lib.isEmpty() || base == null) library.addView(hint("Aún no hay videos descargados."))
            else for (f in s.lib) library.addView(libraryCard(f, base))
        }
    }

    private fun libraryCard(f: File, base: File): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(6)) }
        }
        Ui.focusable(c)
        c.addView(Ui.text(this, "▶", 20f, Ui.ACCENT, bold = true).apply { setPadding(0, 0, dp(18), 0) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(this, f.name, 18f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        col.addView(Ui.text(this, Ui.size(f.length()) + "  ·  mantén pulsado OK para más opciones", 13f, Ui.MUTED))
        c.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        c.setOnClickListener { play(f, base) }
        c.setOnLongClickListener {
            dialog().setTitle(f.name).setItems(arrayOf("Reproducir", "Eliminar archivo del disco", "Cancelar")) { _, w ->
                if (w == 0) play(f, base)
                if (w == 1) confirmDelete(f)
            }.show(); true
        }
        return c
    }

    // ------------------------------------------------------------ Acciones

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun askMagnet() {
        val input = EditText(this).apply {
            hint = "magnet:?xt=urn:btih:..."; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        dialog().setTitle("Enlace magnet").setView(input)
            .setPositiveButton("Descargar") { _, _ ->
                val magnet = input.text.toString()
                bg.execute {
                    val msg = try { Engine.add(this, magnet); "Agregado" } catch (e: Exception) { e.message ?: "Error" }
                    ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); refresh() }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    /** Modo descarga: la TV queda en reposo bajando y se deja apagar al terminar. */
    private fun toggleMode() {
        if (Store.downloadMode(this)) {
            bg.execute { PowerMode.disable(this); ui.post { Toast.makeText(this, "Modo descarga cancelado", Toast.LENGTH_SHORT).show(); refresh() } }
            return
        }
        dialog().setTitle("Modo descarga")
            .setMessage("La pantalla se apagará y la TV quedará en reposo con bajo consumo mientras sigue descargando.\n\n" +
                "Cuando todo termine, se deja apagar sola. Para despertarla en cualquier momento usa el control remoto.")
            .setPositiveButton("Activar y apagar pantalla") { _, _ ->
                bg.execute {
                    val err = PowerMode.enable(this)
                    ui.post { if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show(); refresh() }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun showSettings() {
        val items = arrayOf(
            "Clave fija…",
            "Generar clave nueva",
            "Reproductor: ${Store.PLAYERS[Store.player(this)]}",
            "Siempre activo: ${if (Store.alwaysOn(this)) "Sí" else "No"}"
        )
        dialog().setTitle("Ajustes").setItems(items) { _, w ->
            when (w) {
                0 -> askPassword()
                1 -> dialog().setTitle("Generar clave nueva")
                    .setMessage("La clave actual dejará de funcionar. Tendrás que volver a entrar en el portal con la nueva.")
                    .setPositiveButton("Generar") { _, _ -> bg.execute { Store.regeneratePassword(this); ui.post { refresh() } } }
                    .setNegativeButton("Cancelar", null).show()
                2 -> {
                    val keys = Store.PLAYERS.keys.toList()
                    val next = keys[(keys.indexOf(Store.player(this)) + 1) % keys.size]
                    Store.setPlayer(this, next)
                    Toast.makeText(this, "Reproductor: ${Store.PLAYERS[next]}", Toast.LENGTH_SHORT).show()
                }
                3 -> {
                    val on = !Store.alwaysOn(this)
                    Store.setAlwaysOn(this, on)
                    Toast.makeText(this, if (on) "El portal arrancará con la TV y no se apagará solo (usa más memoria)."
                        else "El portal se apagará tras 15 minutos sin uso.", Toast.LENGTH_LONG).show()
                }
            }
        }.show()
    }

    /** Fija una clave elegida por ti: no vuelve a cambiar sola. */
    private fun askPassword() {
        val input = EditText(this).apply {
            hint = "Nueva clave (8 a 64 caracteres, sin espacios)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        dialog().setTitle("Clave fija del portal").setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val p = input.text.toString()
                bg.execute {
                    val err = Store.setPassword(this, p)
                    ui.post { Toast.makeText(this, err ?: "Clave guardada", Toast.LENGTH_LONG).show(); refresh() }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun confirmDelete(f: File) {
        dialog().setTitle("Eliminar archivo").setMessage("${f.name}\n\nSe borra del disco y no se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ -> bg.execute { Engine.deleteFile(f); ui.post { libKeys = emptyList(); refresh() } } }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun play(f: File, base: File) {
        val rel = f.relativeTo(base).path
        Web.key = Store.mediaKey(this)
        if (!Web.playOnTv(this, rel)) Toast.makeText(this, "No se pudo abrir el reproductor", Toast.LENGTH_LONG).show()
    }
}

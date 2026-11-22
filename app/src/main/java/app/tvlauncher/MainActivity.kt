package app.tvlauncher

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/**
 * Inicio propio:
 *  - arriba: entradas de video (HDMI...), "Continuar viendo", "Botones" y Ajustes;
 *  - centro: fondo e informacion del titulo enfocado (sinopsis, reparto, calificacion);
 *  - abajo: pestanas por tipo de app, las apps del grupo y, debajo, lo popular de la
 *    plataforma enfocada (TMDB). Una app sin catalogo propio no muestra sugerencias ajenas.
 */
class MainActivity : Activity() {

    private lateinit var bg: ImageView
    private lateinit var infoTitle: TextView
    private lateinit var infoMeta: TextView
    private lateinit var infoOverview: TextView
    private lateinit var infoCast: TextView
    private lateinit var heading: TextView
    private lateinit var chips: LinearLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var titlesLabel: TextView
    private lateinit var appsAdapter: TileAdapter
    private lateinit var titlesAdapter: TileAdapter
    private lateinit var appsRow: RecyclerView
    private lateinit var titlesRow: RecyclerView

    private val ui = Handler(Looper.getMainLooper())
    private var allApps: List<Tile> = emptyList()
    private var selectedTab = 0
    private var currentPkg: String? = null
    private var currentLabel = ""
    private var request = 0
    private var firstFocusDone = false
    private var inputsSig = ""
    private var shownBackdrop: String? = null
    private val tabViews = mutableListOf<TextView>()

    private val preferred = listOf(
        "com.stremio.one", "com.netflix.ninja", "com.google.android.youtube.tv",
        "com.disney.disneyplus", "com.wbd.stream", "com.amazon.amazonvideo.livingroom",
        "com.apple.atve.androidtv.appletv", "com.crunchyroll.crunchyroid", "org.xbmc.kodi",
        "org.jellyfin.androidtv", "com.plexapp.android", "org.videolan.vlc",
        "com.spotify.tv.android", "co.movistar.go", "com.directv.dtvlatam", "com.caracol.streaming"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        loadAll()
    }

    override fun onResume() {
        super.onResume()
        KeyMap.ensureService(this)   // reactiva el servicio de botones si Android lo apago
        loadAll()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(bg, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#B3000000"), Color.parseColor("#33000000"), Color.parseColor("#F2000000"))
            )
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT)
            )
        }, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(18), dp(40), dp(8))
        }

        // Barra superior.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading = TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f; text = "Inicio"; isSingleLine = true }
        top.addView(heading, LinearLayout.LayoutParams(dp(120), -2))
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(chips, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(pillButton("Continuar viendo") { openGoogleHome() })
        top.addView(pillButton("Botones") { startActivity(Intent(this, ButtonsActivity::class.java)) })
        top.addView(pillButton("Ajustes") { openSettings() })
        top.addView(TextClock(this).apply {
            setTextColor(Color.WHITE); textSize = 20f
            format12Hour = "h:mm a"; format24Hour = "HH:mm"; setPadding(dp(16), 0, 0, 0)
        })
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

        // Panel de informacion del titulo enfocado.
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
        infoTitle = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 26f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        infoMeta = TextView(this).apply {
            setTextColor(Color.parseColor("#E6FFFFFF")); textSize = 14f; setPadding(0, dp(2), 0, dp(4))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        infoOverview = TextView(this).apply {
            setTextColor(Color.parseColor("#E6FFFFFF")); textSize = 14f
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(dp(1).toFloat(), 1f)
        }
        infoCast = TextView(this).apply {
            setTextColor(Color.parseColor("#B3FFFFFF")); textSize = 13f; setPadding(0, dp(4), 0, dp(2))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        info.addView(infoTitle); info.addView(infoMeta); info.addView(infoOverview); info.addView(infoCast)
        col.addView(info, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.55f).toInt(), 0, 1f))

        // Pestanas por tipo.
        tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), dp(4), 0, 0) }
        AppGroups.tabs.forEachIndexed { i, name -> tabsRow.addView(tabButton(name, i)) }
        col.addView(tabsRow, LinearLayout.LayoutParams(-2, -2))

        // Apps del grupo.
        appsAdapter = TileAdapter(140, 79, false) { t -> onAppFocused(t) }
        appsRow = row(appsAdapter)
        col.addView(appsRow, LinearLayout.LayoutParams(-1, dp(95)))

        // Rotulo + populares de la plataforma enfocada.
        titlesLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#B3FFFFFF")); textSize = 12f; setPadding(dp(6), 0, 0, 0); isSingleLine = true
        }
        col.addView(titlesLabel)
        titlesAdapter = TileAdapter(82, 123, true) { t -> (t.data as? Title)?.let { showInfo(it) } }
        titlesRow = row(titlesAdapter)
        col.addView(titlesRow, LinearLayout.LayoutParams(-1, dp(139)))

        col.addView(TextView(this).apply {
            setTextColor(Color.parseColor("#80FFFFFF")); textSize = 10f
            text = "Datos e imágenes de TMDB. Este producto usa la API de TMDB pero no está avalado ni certificado por TMDB."
        })

        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun row(adapter: TileAdapter) = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
        this.adapter = adapter
        clipToPadding = false; clipChildren = false; itemAnimator = null; setHasFixedSize(true)
        // El contenedor nunca toma el foco: desde ahi las flechas no navegan.
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private fun pillButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 13f
        isSingleLine = true
        setPadding(dp(12), dp(6), dp(12), dp(6))
        isFocusable = true; isClickable = true
        background = pill(false, false)
        setOnClickListener { action() }
        setOnFocusChangeListener { v, has ->
            v.background = pill(has, v.alpha < 1f)
            (v as TextView).setTextColor(if (has) Color.BLACK else Color.WHITE)
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
    }

    private fun tabButton(label: String, index: Int) = TextView(this).apply {
        text = label; textSize = 13f; isSingleLine = true
        setPadding(dp(14), dp(5), dp(14), dp(5))
        isFocusable = true; isClickable = true
        setOnClickListener { selectTab(index) }
        setOnFocusChangeListener { _, has ->
            if (has) selectTab(index)
            styleTabs()
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
        tabViews.add(this)
    }

    private fun styleTabs() {
        tabViews.forEachIndexed { i, v ->
            val focused = v.isFocused
            val selected = i == selectedTab
            v.background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(when { focused -> Color.WHITE; selected -> Color.parseColor("#55FFFFFF"); else -> Color.TRANSPARENT })
            }
            v.setTextColor(if (focused) Color.BLACK else if (selected) Color.WHITE else Color.parseColor("#99FFFFFF"))
        }
    }

    private fun pill(focused: Boolean, dim: Boolean) = GradientDrawable().apply {
        cornerRadius = 100f
        setColor(if (focused) Color.WHITE else Color.parseColor(if (dim) "#14FFFFFF" else "#33FFFFFF"))
    }

    // -------------------------------------------------------------- Datos

    private fun loadAll() {
        loadInputs()
        allApps = loadApps()
        selectTab(selectedTab, force = true)
        if (!firstFocusDone && appsAdapter.items.isNotEmpty()) {
            firstFocusDone = true
            appsRow.post { appsRow.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
        styleTabs()
    }

    private fun selectTab(index: Int, force: Boolean = false) {
        if (index == selectedTab && !force) return
        val changed = index != selectedTab
        selectedTab = index
        appsAdapter.items = allApps.filter { AppGroups.tabOf(it.id) == index }
        // Al cambiar de grupo se limpia todo lo de la app anterior: nada ajeno queda en pantalla.
        if (changed || appsAdapter.items.isEmpty()) {
            request++
            ui.removeCallbacks(showSourceRunnable)
            titlesAdapter.items = emptyList()
            titlesLabel.text = ""
            heading.text = AppGroups.tabs[index]
            showEmpty(
                AppGroups.tabs[index],
                if (appsAdapter.items.isEmpty()) "No hay apps instaladas en este grupo."
                else "Elige una app para ver sus sugerencias."
            )
        }
        styleTabs()
    }

    private fun loadApps(): List<Tile> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val found = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
        val order = { pkg: String -> preferred.indexOf(pkg).let { if (it < 0) Int.MAX_VALUE else it } }
        return found
            .sortedWith(compareBy({ order(it.activityInfo.packageName) }, { it.loadLabel(pm).toString().lowercase(Locale.ROOT) }))
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val label = ri.loadLabel(pm).toString()
                Tile(pkg, label, ri.activityInfo.loadBanner(pm) ?: ri.loadIcon(pm)) { launchApp(pkg, ri.activityInfo.name, label) }
            }
    }

    private fun loadInputs() {
        val tim = getSystemService(TV_INPUT_SERVICE) as TvInputManager
        val list: List<TvInputInfo> = tim.tvInputList.filter { it.isPassthroughInput }.sortedBy { it.loadLabel(this).toString() }
        // Solo se reconstruyen los botones si algo cambio (nombre o conexion): si no, se pierde el foco.
        val sig = list.joinToString { "${it.id}|${it.loadLabel(this)}|${tim.getInputState(it.id)}" }
        if (sig == inputsSig && chips.childCount > 0) return
        inputsSig = sig
        chips.removeAllViews()
        for (info in list) {
            val label = (info.loadCustomLabel(this) ?: info.loadLabel(this)).toString()
            val active = tim.getInputState(info.id) != TvInputManager.INPUT_STATE_DISCONNECTED
            chips.addView(pillButton(label) { switchInput(info.id, label) }.apply { alpha = if (active) 1f else 0.55f })
        }
    }

    // ---------------------------------------------------- Foco y catalogo

    /** Al enfocar una app se limpia lo anterior de inmediato y, tras una pausa breve, se carga lo suyo. */
    private fun onAppFocused(t: Tile) {
        heading.text = t.label
        currentPkg = t.id
        currentLabel = t.label
        request++
        titlesAdapter.items = emptyList()
        titlesLabel.text = ""
        ui.removeCallbacks(showSourceRunnable)
        ui.postDelayed(showSourceRunnable, 300)
    }

    private val showSourceRunnable = Runnable {
        val pkg = currentPkg ?: return@Runnable
        val label = currentLabel
        val src = AppGroups.sourceOf(pkg)
        val mine = ++request
        if (src.kind == Kind.NONE) {
            titlesLabel.text = ""
            showEmpty(label, "Esta app no tiene sugerencias.")
            return@Runnable
        }
        titlesLabel.text = src.label
        showEmpty(label, "Cargando sugerencias...")
        Catalog.load(this, src.providerId) { list ->
            if (mine != request) return@load   // el usuario ya se movio a otra app
            if (list.isEmpty()) {
                showEmpty(label, "Sin datos para esta plataforma por ahora.")
                return@load
            }
            titlesAdapter.items = list.map { t ->
                Tile(t.id.toString(), t.name, imageUrl = t.poster?.let { Catalog.IMG + "w185" + it }, data = t) {
                    openTitle(t)
                }
            }
            if (!titlesRow.hasFocus()) showInfo(list.first())
        }
    }

    /** Estado sin titulos: nunca se deja informacion de otra plataforma en pantalla. */
    private fun showEmpty(title: String, message: String) {
        infoTitle.text = title
        infoMeta.text = ""
        infoOverview.text = message
        infoCast.text = ""
        setBackdrop(null)
    }

    private fun showInfo(t: Title) {
        infoTitle.text = t.name
        val parts = mutableListOf<String>()
        if (t.year.isNotBlank()) parts += t.year
        if (t.rating > 0) parts += "★ " + String.format(Locale.US, "%.1f", t.rating) + (if (t.votes > 0) " (${compact(t.votes)})" else "")
        if (t.genres.isNotEmpty()) parts += t.genres.take(2).joinToString(", ")
        parts += if (t.extra.isNotBlank()) t.extra else if (t.type == "tv") "Serie" else "Película"
        infoMeta.text = parts.joinToString("  ·  ")
        infoOverview.text = t.overview
        infoCast.text = if (t.cast.isEmpty()) "" else "Reparto: " + t.cast.take(5).joinToString(", ")
        setBackdrop(t.backdrop?.let { Catalog.IMG + "w1280" + it })
    }

    private fun setBackdrop(url: String?) {
        if (url == shownBackdrop) return
        shownBackdrop = url
        bg.animate().alpha(0f).setDuration(120).withEndAction {
            if (url == null) {
                Images.load(this, null, bg)
                bg.alpha = 1f
            } else {
                Images.load(this, url, bg) { bg.animate().alpha(1f).setDuration(250).start() }
            }
        }.start()
    }

    private fun compact(n: Int) = if (n >= 1000) String.format(Locale.US, "%.1fk", n / 1000f) else n.toString()

    // ------------------------------------------------------------ Acciones

    private fun launchApp(pkg: String, activity: String, label: String) {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setComponent(ComponentName(pkg, activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { startActivity(launch) } catch (e: Exception) { toast("No se pudo abrir $label") }
    }

    /**
     * Stremio admite enlaces directos a un titulo (por su id de IMDb). Las demas plataformas no
     * ofrecen ese dato, asi que alli solo se abre la app.
     */
    private fun openTitle(t: Title) {
        val pkg = currentPkg ?: return
        if (pkg == "com.stremio.one" && t.imdb.startsWith("tt")) {
            val kind = if (t.type == "tv") "series" else "movie"
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("stremio:///detail/$kind/${t.imdb}"))
                        .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (_: Exception) { /* si falla, se abre la app a secas */ }
        }
        launchByPackage(pkg)
    }

    private fun launchByPackage(pkg: String) {
        packageManager.getLeanbackLaunchIntentForPackage(pkg)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } ?: toast("No se pudo abrir la app")
    }

    private fun switchInput(inputId: String, label: String) {
        val uri = TvContract.buildChannelUriForPassthroughInput(inputId)
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { toast("No se pudo cambiar a $label") }
    }

    /** Abre el launcher de Google (que si ve "continuar viendo") como una pantalla mas. */
    private fun openGoogleHome() {
        try {
            startActivity(Intent().setComponent(ComponentName("com.google.android.tvlauncher", "com.google.android.tvlauncher.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { toast("El launcher de Google no está disponible") }
    }

    private fun openSettings() {
        try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { toast("No se pudo abrir Ajustes") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onBackPressed() { /* Un launcher no cierra con Atras. */ }
}

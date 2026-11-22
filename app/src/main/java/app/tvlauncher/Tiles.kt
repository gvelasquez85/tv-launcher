package app.tvlauncher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Una casilla del inicio: una app, un titulo popular, etc. */
data class Tile(
    val id: String,
    val label: String,
    val image: Drawable? = null,
    val imageUrl: String? = null,
    val dimmed: Boolean = false,
    val data: Any? = null,
    val onClick: () -> Unit = {}
)

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Adaptador horizontal de casillas con foco visible (borde + zoom). */
class TileAdapter(
    private val widthDp: Int,
    private val heightDp: Int,
    private val crop: Boolean,
    private val onFocused: (Tile) -> Unit
) : RecyclerView.Adapter<TileAdapter.VH>() {

    var items: List<Tile> = emptyList()
        set(value) {
            // Si la lista es la misma no se redibuja: hacerlo quita el foco al mosaico seleccionado.
            if (value.map { it.id } == field.map { it.id } && value.isNotEmpty()) {
                field = value          // se conservan los nuevos "onClick" sin tocar las vistas
                return
            }
            field = value
            notifyDataSetChanged()
        }

    class VH(val root: FrameLayout, val img: ImageView, val label: TextView) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val root = FrameLayout(c).apply {
            layoutParams = RecyclerView.LayoutParams(c.dp(widthDp), c.dp(heightDp)).apply {
                setMargins(c.dp(5), c.dp(8), c.dp(5), c.dp(8))
            }
            isFocusable = true
            isClickable = true
            background = box(Color.parseColor("#22FFFFFF"), 0, c)
        }
        val img = ImageView(c).apply {
            scaleType = if (crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            if (!crop) setPadding(c.dp(5), c.dp(5), c.dp(5), c.dp(5))
        }
        val label = TextView(c).apply {
            setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; maxLines = 3
            setPadding(c.dp(6), c.dp(4), c.dp(6), c.dp(4))
        }
        root.addView(img, FrameLayout.LayoutParams(-1, -1))
        root.addView(label, FrameLayout.LayoutParams(-1, -1))
        return VH(root, img, label)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = items[position]
        val c = h.root.context
        when {
            t.imageUrl != null -> {
                h.img.visibility = View.VISIBLE
                h.label.visibility = View.VISIBLE
                h.label.text = t.label
                h.label.alpha = 0.7f
                Images.load(c, t.imageUrl, h.img) { h.label.visibility = View.GONE }
            }
            t.image != null -> {
                h.img.tag = null
                h.img.setImageDrawable(t.image)
                h.img.visibility = View.VISIBLE
                h.label.visibility = View.GONE
            }
            else -> {
                h.img.tag = null
                h.img.visibility = View.GONE
                h.label.visibility = View.VISIBLE
                h.label.alpha = 1f
                h.label.text = t.label
            }
        }
        h.root.alpha = if (t.dimmed) 0.45f else 1f
        h.root.setOnClickListener { t.onClick() }
        h.root.contentDescription = t.label
        h.root.setOnFocusChangeListener { v, has ->
            v.background = if (has) box(Color.parseColor("#33FFFFFF"), c.dp(3), c) else box(Color.parseColor("#22FFFFFF"), 0, c)
            v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(120).start()
            v.alpha = if (has) 1f else if (t.dimmed) 0.45f else 1f
            if (has) onFocused(t)
        }
    }

    override fun getItemCount() = items.size

    private fun box(fill: Int, strokePx: Int, c: Context) = GradientDrawable().apply {
        cornerRadius = c.dp(6).toFloat()
        setColor(fill)
        if (strokePx > 0) setStroke(strokePx, Color.WHITE)
    }
}

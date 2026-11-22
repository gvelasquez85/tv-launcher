package app.tvtorrent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Estilo comun para la TV: fondo oscuro, tarjetas redondeadas, foco con borde y un leve zoom (como Android TV). */
object Ui {
    const val BG_TOP = "#0A0E13"
    const val BG_BOTTOM = "#16202C"
    const val CARD = "#1B2430"
    const val CARD_FOCUS = "#2B3A4D"
    const val ACCENT = "#5B9BFF"
    const val OK = "#3DDC97"
    const val WARN = "#FFB84D"
    const val BAD = "#FF6B6B"
    const val TEXT = "#F2F5F9"
    const val MUTED = "#9AA7B8"

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun background() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor(BG_TOP), Color.parseColor(BG_BOTTOM)))

    fun shape(c: Context, fill: String, radius: Int = 14, stroke: String? = null, strokeDp: Int = 3) = GradientDrawable().apply {
        cornerRadius = dp(c, radius).toFloat()
        setColor(Color.parseColor(fill))
        if (stroke != null) setStroke(dp(c, strokeDp), Color.parseColor(stroke))
    }

    fun text(c: Context, t: String, size: Float, color: String = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = t; textSize = size; setTextColor(Color.parseColor(color))
        if (bold) paint.isFakeBoldText = true
    }

    /** Hace que una vista se vea enfocada: borde claro, fondo mas claro y zoom suave. */
    fun focusable(v: View, normal: String = CARD, focused: String = CARD_FOCUS, radius: Int = 14, onFocus: ((Boolean) -> Unit)? = null) {
        v.isFocusable = true; v.isClickable = true
        v.background = shape(v.context, normal, radius)
        v.setOnFocusChangeListener { view, has ->
            view.background = if (has) shape(view.context, focused, radius, ACCENT) else shape(view.context, normal, radius)
            view.animate().scaleX(if (has) 1.025f else 1f).scaleY(if (has) 1.025f else 1f).setDuration(120).start()
            onFocus?.invoke(has)
        }
    }

    /** Boton en forma de pastilla. `primary` lo pinta con el color de acento. */
    fun button(c: Context, label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): TextView =
        TextView(c).apply {
            text = label; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor(if (danger) BAD else TEXT))
            setPadding(dp(c, 22), dp(c, 12), dp(c, 22), dp(c, 12))
            val normal = if (primary) "#2F6FE0" else "#243040"
            focusable(this, normal, if (primary) "#4C8DFF" else CARD_FOCUS, radius = 28)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, dp(c, 12), 0) }
        }

    /** Barra de progreso fina y redondeada. */
    class Bar(c: Context) : View(c) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33FFFFFF") }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private var value = 0f
        private var color = Color.parseColor(ACCENT)
        fun set(fraction: Float, colorHex: String) {
            value = fraction.coerceIn(0f, 1f); color = Color.parseColor(colorHex); invalidate()
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val r = height / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
            if (value > 0f) {
                fill.color = color
                canvas.drawRoundRect(0f, 0f, maxOf(height.toFloat(), width * value), height.toFloat(), r, r, fill)
            }
        }
    }

    fun bar(c: Context, heightDp: Int = 8) = Bar(c).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(c, heightDp)) }

    // ---- Formatos ----

    fun size(b: Long) = if (b >= 1e9) "%.2f GB".format(b / 1e9) else if (b >= 1e6) "%.0f MB".format(b / 1e6) else "%.0f KB".format(b / 1e3)

    fun speed(b: Long) = "${size(b)}/s"

    fun eta(sec: Long): String = when {
        sec < 0 -> "—"
        sec < 60 -> "${sec} s"
        sec < 3600 -> "${sec / 60} min"
        sec < 86400 -> "${sec / 3600} h ${(sec % 3600) / 60} min"
        else -> "${sec / 86400} d ${(sec % 86400) / 3600} h"
    }

    /** Texto y color del estado de una descarga. */
    fun stateLabel(r: Row): Pair<String, String> = when (r.state) {
        "complete" -> "Completa" to OK
        "metadata" -> "Obteniendo datos del enlace…" to WARN
        "queued" -> "En espera" to MUTED
        "storage" -> "Esperando el USB" to BAD
        "paused" -> "Pausada" to WARN
        "checking" -> "Verificando archivos…" to WARN
        else -> "Descargando" to ACCENT
    }
}

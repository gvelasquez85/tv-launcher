package app.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvInputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Pantalla para programar los botones del control remoto. */
class ButtonsActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var actions: LinearLayout
    private var learning = false
    private val ui = Handler(Looper.getMainLooper())
    private val learnTimeout = Runnable { if (learning) { stopLearning(); toast("Tiempo agotado.") } }
    private val serviceId = "app.tvlauncher/app.tvlauncher.KeyMapService"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
            setPadding(dp(48), dp(28), dp(48), dp(20))
        }
        root.addView(TextView(this).apply { text = "Programar botones del control"; setTextColor(Color.WHITE); textSize = 26f })
        status = TextView(this).apply { setTextColor(Color.parseColor("#B3FFFFFF")); textSize = 14f; setPadding(0, dp(6), 0, dp(10)) }
        root.addView(status)
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(actions)
        root.addView(TextView(this).apply {
            text = "Botones programados (selecciona uno para eliminarlo)"
            setTextColor(Color.parseColor("#80FFFFFF")); textSize = 13f; setPadding(0, dp(16), 0, dp(6))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        if (learning) stopLearning()
    }

    // ----------------------------------------------------------------- UI

    private fun serviceEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("app.tvlauncher/") && s.contains("KeyMapService")
    }

    private fun canSelfEnable() =
        checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val on = serviceEnabled()
        status.text = if (on) "Servicio activo: los botones programados funcionan en cualquier app."
        else "Servicio inactivo: sin él, los botones solo se pueden programar con la app en pantalla y no funcionan en otras apps."
        actions.removeAllViews()
        actions.addView(button("Añadir botón") { startLearning() })
        if (!on) actions.addView(button("Activar servicio") { enableService() })
        actions.addView(button("Volver") { finish() })
        actions.getChildAt(0).requestFocus()

        list.removeAllViews()
        val all = KeyMap.all(this)
        if (all.isEmpty()) list.addView(TextView(this).apply {
            text = "Aún no hay botones programados."; setTextColor(Color.parseColor("#80FFFFFF")); textSize = 15f
            setPadding(dp(4), dp(8), 0, 0)
        })
        for (m in all) list.addView(button("${m.keyName}   →   ${m.label}", wide = true) { confirmDelete(m) })
    }

    private fun button(label: String, wide: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 16f; isSingleLine = true
        setPadding(dp(16), dp(10), dp(16), dp(10))
        isFocusable = true; isClickable = true
        background = pill(false)
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has ->
            v.background = pill(has); (v as TextView).setTextColor(if (has) Color.BLACK else Color.WHITE)
        }
        layoutParams = LinearLayout.LayoutParams(if (wide) -1 else -2, -2).apply { setMargins(0, dp(4), dp(10), dp(4)) }
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun pill(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(if (focused) Color.WHITE else Color.parseColor("#26FFFFFF"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    // ------------------------------------------------------------ Servicio

    private fun enableService() {
        if (!canSelfEnable()) {
            dialog().setTitle("Activar servicio")
                .setMessage("Android pide un permiso que solo se concede desde un computador. Ejecuta una vez:\n\n" +
                    "adb shell pm grant app.tvlauncher android.permission.WRITE_SECURE_SETTINGS\n\n" +
                    "o actívalo a mano en Ajustes > Accesibilidad > Botones del control.")
                .setPositiveButton("Abrir Accesibilidad") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
                }.setNegativeButton("Cerrar", null).show()
            return
        }
        val cur = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val value = if (cur.isNullOrBlank()) serviceId else "$cur:$serviceId"
        Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        ui.postDelayed({ refresh() }, 600)
    }

    // ---------------------------------------------------------- Aprendizaje

    private fun startLearning() {
        learning = true
        status.text = "Pulsa ahora el botón del control que quieres programar...  (Atras cancela, 15 s)"
        KeyMap.learner = { e -> runOnUiThread { onCaptured(e) } }
        ui.postDelayed(learnTimeout, 15000)
    }

    private fun stopLearning() {
        learning = false
        KeyMap.learner = null
        ui.removeCallbacks(learnTimeout)
        refresh()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (learning) { onCaptured(event); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun onCaptured(e: KeyEvent) {
        if (!learning) return
        if (e.keyCode == KeyEvent.KEYCODE_BACK) { stopLearning(); return }
        stopLearning()
        if (KeyMap.isForbidden(e)) {
            toast("Ese botón no se puede reasignar: lo necesitas para navegar.")
            return
        }
        chooseAction(e)
    }

    // --------------------------------------------------------- Elegir accion

    private fun chooseAction(e: KeyEvent) {
        val name = KeyMap.nameOf(e)
        val options = arrayOf(
            "Abrir una app...", "Cambiar de entrada...", "Abrir Ajustes", "Ir al inicio (este launcher)",
            "Abrir el launcher de Google", "No hacer nada (bloquear el botón)"
        )
        dialog().setTitle("Botón $name").setItems(options) { _, which ->
            when (which) {
                0 -> pickApp(e)
                1 -> pickInput(e)
                2 -> save(e, "settings", "", "Abrir Ajustes")
                3 -> save(e, "home", "", "Ir al inicio")
                4 -> save(e, "google_home", "", "Abrir el launcher de Google")
                5 -> save(e, "block", "", "Bloqueado")
            }
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun pickApp(e: KeyEvent) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        dialog().setTitle("Abrir app").setItems(apps.map { it.loadLabel(pm).toString() }.toTypedArray()) { _, i ->
            val ri = apps[i]
            save(e, "app", ri.activityInfo.packageName, "Abrir " + ri.loadLabel(pm))
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun pickInput(e: KeyEvent) {
        val tim = getSystemService(TV_INPUT_SERVICE) as TvInputManager
        val inputs = tim.tvInputList.filter { it.isPassthroughInput }.sortedBy { it.loadLabel(this).toString() }
        val labels = inputs.map { (it.loadCustomLabel(this) ?: it.loadLabel(this)).toString() }
        dialog().setTitle("Cambiar a entrada").setItems(labels.toTypedArray()) { _, i ->
            save(e, "input", inputs[i].id, "Cambiar a " + labels[i])
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun save(e: KeyEvent, type: String, arg: String, label: String) {
        val m = Mapping(e.keyCode, e.scanCode, KeyMap.nameOf(e), type, arg, label)
        val rest = KeyMap.all(this).filterNot { it.keyCode == m.keyCode && it.scanCode == m.scanCode }
        KeyMap.save(this, rest + m)
        toast("Guardado: ${m.keyName} → ${m.label}")
        refresh()
    }

    /** Al elegir un boton ya programado: cambiar su accion o eliminarlo. */
    private fun confirmDelete(m: Mapping) {
        dialog().setTitle("${m.keyName} → ${m.label}")
            .setItems(arrayOf("Cambiar la acción", "Eliminar", "Cancelar")) { _, which ->
                when (which) {
                    0 -> chooseAction(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, m.keyCode, 0, 0, 0, m.scanCode))
                    1 -> {
                        KeyMap.save(this, KeyMap.all(this).filterNot { it.keyCode == m.keyCode && it.scanCode == m.scanCode })
                        refresh()
                    }
                }
            }.show()
    }
}

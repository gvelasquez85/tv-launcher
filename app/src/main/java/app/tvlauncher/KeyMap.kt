package app.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Un boton del control asignado a una accion. */
data class Mapping(
    val keyCode: Int, val scanCode: Int, val keyName: String,
    val type: String, val arg: String, val label: String
)

/**
 * Almacen y ejecucion de las asignaciones de botones del control remoto.
 * Tipos de accion: app, input, settings, home, google_home, block.
 */
object KeyMap {
    /** Mientras no sea null, el siguiente boton pulsado se captura en vez de ejecutarse. */
    @Volatile var learner: ((KeyEvent) -> Unit)? = null

    // Teclas que no se pueden reasignar: sin ellas no se podria navegar ni deshacer el cambio.
    private val forbidden = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_POWER
    )

    fun isForbidden(e: KeyEvent) = e.keyCode in forbidden

    // ---- Aprendizaje remoto (lo usa el portal web): el siguiente boton pulsado queda guardado aqui.
    @Volatile var captured: JSONObject? = null

    fun startLearn() {
        captured = null
        learner = { e ->
            captured = JSONObject().put("keyCode", e.keyCode).put("scanCode", e.scanCode)
                .put("keyName", nameOf(e)).put("forbidden", isForbidden(e))
            learner = null
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (captured == null) learner = null    // tiempo agotado
        }, 20_000)
    }

    fun toJson(list: List<Mapping>): JSONArray = JSONArray(list.map {
        JSONObject().put("keyCode", it.keyCode).put("scanCode", it.scanCode).put("keyName", it.keyName)
            .put("type", it.type).put("arg", it.arg).put("label", it.label)
    })

    /** Valida una lista llegada de fuera (portal web) antes de guardarla. Lanza IllegalArgumentException si algo no cuadra. */
    fun parseValidated(text: String): List<Mapping> {
        val a = try { JSONArray(text) } catch (_: Exception) { throw IllegalArgumentException("Lista inválida") }
        if (a.length() > 40) throw IllegalArgumentException("Demasiados botones (máximo 40)")
        val types = setOf("app", "input", "settings", "home", "google_home", "block")
        val seen = HashSet<String>()
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val m = Mapping(o.getInt("keyCode"), o.getInt("scanCode"), o.getString("keyName").take(40),
                o.getString("type"), o.optString("arg").take(200), o.getString("label").take(80))
            if (m.type !in types) throw IllegalArgumentException("Acción desconocida: ${m.type}")
            if (m.keyCode in forbidden) throw IllegalArgumentException("El botón ${m.keyName} no se puede reasignar")
            if (m.type == "app" && !Regex("[A-Za-z0-9_.]{1,200}").matches(m.arg)) throw IllegalArgumentException("App inválida")
            if (m.type == "input" && m.arg.isBlank()) throw IllegalArgumentException("Entrada inválida")
            if (!seen.add("${m.keyCode}/${m.scanCode}")) throw IllegalArgumentException("Botón repetido: ${m.keyName}")
            m
        }
    }

    private const val SERVICE_ID = "app.tvlauncher/app.tvlauncher.KeyMapService"

    /**
     * Si hay botones programados y el servicio no esta activo, lo reactiva. Android lo apaga al
     * reinstalar o forzar el cierre de la app. Requiere el permiso WRITE_SECURE_SETTINGS (se concede por ADB).
     */
    fun ensureService(ctx: Context) {
        try {
            if (all(ctx).isEmpty()) return
            if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            if (cur.split(":").any { it == SERVICE_ID }) return
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                if (cur.isBlank()) SERVICE_ID else "$cur:$SERVICE_ID")
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (_: Exception) { /* sin permiso: no se hace nada */ }
    }

    private const val PREFS = "keymap"
    private const val KEY = "list"

    fun all(ctx: Context): List<Mapping> {
        val text = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            val a = JSONArray(text)
            List(a.length()) {
                val o = a.getJSONObject(it)
                Mapping(o.getInt("keyCode"), o.getInt("scanCode"), o.getString("keyName"),
                    o.getString("type"), o.optString("arg"), o.getString("label"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun save(ctx: Context, list: List<Mapping>) {
        val a = JSONArray(list.map {
            JSONObject().put("keyCode", it.keyCode).put("scanCode", it.scanCode).put("keyName", it.keyName)
                .put("type", it.type).put("arg", it.arg).put("label", it.label)
        })
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    fun nameOf(e: KeyEvent): String =
        if (e.keyCode != KeyEvent.KEYCODE_UNKNOWN) KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        else "TECLA_${e.scanCode}"

    fun find(ctx: Context, e: KeyEvent): Mapping? = all(ctx).firstOrNull {
        if (it.keyCode != KeyEvent.KEYCODE_UNKNOWN) it.keyCode == e.keyCode
        else e.keyCode == KeyEvent.KEYCODE_UNKNOWN && it.scanCode == e.scanCode
    }

    /** Ejecuta la accion. [ctx] puede ser el servicio de accesibilidad o una actividad. */
    fun run(ctx: Context, m: Mapping) {
        val nt = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            when (m.type) {
                "app" -> {
                    val pm = ctx.packageManager
                    val i = pm.getLeanbackLaunchIntentForPackage(m.arg) ?: pm.getLaunchIntentForPackage(m.arg)
                    if (i != null) ctx.startActivity(i.addFlags(nt)) else toast(ctx, "App no disponible: ${m.label}")
                }
                "input" -> ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(m.arg)).addFlags(nt))
                "settings" -> ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(nt))
                "home" -> ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(nt))
                "google_home" -> ctx.startActivity(
                    Intent().setComponent(ComponentName("com.google.android.tvlauncher", "com.google.android.tvlauncher.MainActivity"))
                        .addFlags(nt))
                "block" -> { /* no hacer nada */ }
            }
        } catch (e: Exception) {
            toast(ctx, "No se pudo ejecutar: ${m.label}")
        }
    }

    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
}

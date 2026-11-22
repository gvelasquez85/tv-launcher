package app.tvlauncher

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Interfaz de configuracion del launcher para el portal web de la app de Descargas.
 * Solo la puede llamar una app firmada con la misma clave (permiso app.tvlauncher.permission.CONFIG).
 * Metodos (ContentResolver.call): list, save, apps, inputs, status, learn_start, learn_result, sleep (pone la TV en reposo).
 * Cada respuesta trae un texto JSON en la clave "json".
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context!!
        val out = Bundle()
        fun reply(o: Any) = out.putString("json", o.toString())
        try {
            when (method) {
                "list" -> reply(KeyMap.toJson(KeyMap.all(ctx)))
                "save" -> {
                    val list = KeyMap.parseValidated(arg ?: "[]")
                    KeyMap.save(ctx, list)
                    KeyMap.ensureService(ctx)
                    reply(JSONObject().put("ok", true).put("count", list.size))
                }
                "apps" -> {
                    val pm = ctx.packageManager
                    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    val a = JSONArray()
                    pm.queryIntentActivities(i, 0).filter { it.activityInfo.packageName != ctx.packageName }
                        .distinctBy { it.activityInfo.packageName }
                        .sortedBy { it.loadLabel(pm).toString().lowercase() }
                        .forEach { a.put(JSONObject().put("pkg", it.activityInfo.packageName).put("label", it.loadLabel(pm).toString())) }
                    reply(a)
                }
                "inputs" -> {
                    val tim = ctx.getSystemService(android.content.Context.TV_INPUT_SERVICE) as TvInputManager
                    val a = JSONArray()
                    tim.tvInputList.filter { it.isPassthroughInput }.sortedBy { it.loadLabel(ctx).toString() }.forEach {
                        a.put(JSONObject().put("id", it.id).put("label", (it.loadCustomLabel(ctx) ?: it.loadLabel(ctx)).toString()))
                    }
                    reply(a)
                }
                "status" -> {
                    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                    reply(JSONObject().put("serviceEnabled", enabled.contains("app.tvlauncher/") && enabled.contains("KeyMapService"))
                        .put("mappings", KeyMap.all(ctx).size))
                }
                "sleep" -> reply(JSONObject().put("ok", KeyMapService.sleepTv()))
                "learn_start" -> { KeyMap.startLearn(); reply(JSONObject().put("ok", true)) }
                "learn_result" -> reply(KeyMap.captured ?: JSONObject().put("pending", KeyMap.learner != null))
                else -> reply(JSONObject().put("error", "método desconocido"))
            }
        } catch (e: IllegalArgumentException) {
            out.putString("json", JSONObject().put("error", e.message ?: "inválido").toString())
        } catch (e: Exception) {
            out.putString("json", JSONObject().put("error", "error interno").toString())
        }
        return out
    }

    // El proveedor no expone filas: solo "call".
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

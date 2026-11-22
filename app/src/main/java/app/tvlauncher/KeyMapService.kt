package app.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Servicio de accesibilidad usado SOLO para ver las pulsaciones del control remoto y
 * poder reasignarlas, incluso cuando otra app esta en primer plano.
 * No lee el contenido de la pantalla ni recibe eventos de ventanas (ver res/xml/key_service.xml).
 */
class KeyMapService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: android.content.Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no se usan eventos */ }

    override fun onInterrupt() {}

    override fun onKeyEvent(e: KeyEvent): Boolean {
        // Sin registros: nunca se anotan las teclas pulsadas.
        // Modo aprendizaje: se captura el boton para mostrarlo en la pantalla de configuracion.
        KeyMap.learner?.let { learn ->
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) learn(e)
            return true
        }
        val m = KeyMap.find(this, e) ?: return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) KeyMap.run(this, m)
        return true // se consumen tambien el "up" y las repeticiones
    }

    companion object {
        @Volatile var instance: KeyMapService? = null

        /** Apaga la pantalla y deja la TV en reposo (lo usa la app de Descargas para el modo descarga). */
        fun sleepTv(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
    }
}

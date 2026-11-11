package fr.maison.assistant.assist

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import fr.maison.assistant.AssistActivity
import fr.maison.assistant.Journal
import fr.maison.assistant.ScreenCapture

/**
 * Appui long sur le bouton d'alimentation ou d'accueil : Android ouvre cette session.
 * On ouvre la fenêtre de l'assistant tout de suite ; la capture d'écran arrive juste après (si autorisée)
 * et la fenêtre la propose en pièce jointe.
 *
 * La session est détruite (finish) après chaque appel : réutilisée après un long écran éteint,
 * Android lui envoyait la capture sans jamais rappeler onShow, et rien ne s'ouvrait.
 */
class AssistSession(context: Context) : VoiceInteractionSession(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var openedAt = 0L

    override fun onCreate() {
        super.onCreate()
        Journal.log("session créée")
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Journal.log("session affichée (flags $showFlags)")
        ScreenCapture.clear()
        openWindow()
        // Laisse le temps à la capture d'arriver avant de détruire la session.
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ finish() }, 2500)
    }

    private fun openWindow() {
        openedAt = SystemClock.elapsedRealtime()
        val intent = Intent(context, AssistActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startAssistantActivity(intent) }
            .onSuccess { Journal.log("fenêtre assistant demandée") }
            .onFailure { e ->
                Journal.log("échec startAssistantActivity : $e — essai direct")
                runCatching { context.startActivity(intent) }.onFailure { Journal.log("échec startActivity : $it") }
            }
    }

    @Deprecated("Remplacée par onHandleAssist à partir d'Android 10, mais toujours appelée pour la capture")
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        Journal.log(if (screenshot != null) "capture reçue" else "pas de capture")
        if (screenshot != null) runCatching { ScreenCapture.save(context, screenshot) }
        // Capture reçue sans onShow juste avant : on ouvre quand même la fenêtre.
        if (SystemClock.elapsedRealtime() - openedAt > 3000) {
            Journal.log("capture sans affichage de session : ouverture forcée")
            openWindow()
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ finish() }, 2500)
        }
    }

    override fun onHide() {
        Journal.log("session cachée")
        super.onHide()
    }

    override fun onDestroy() {
        Journal.log("session détruite")
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

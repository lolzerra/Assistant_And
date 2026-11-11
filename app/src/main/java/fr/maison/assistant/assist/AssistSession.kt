package fr.maison.assistant.assist

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import fr.maison.assistant.AssistActivity
import fr.maison.assistant.Journal
import fr.maison.assistant.ScreenCapture

/**
 * Appui long sur le bouton d'alimentation ou d'accueil : Android ouvre cette session.
 * On ouvre l'appli tout de suite ; la capture d'écran arrive juste après (si l'utilisateur l'a autorisée
 * dans les réglages de l'assistant) et l'appli la propose en pièce jointe.
 */
class AssistSession(context: Context) : VoiceInteractionSession(context) {
    private val handler = Handler(Looper.getMainLooper())

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Journal.log("session affichée (flags $showFlags)")
        ScreenCapture.clear()
        val intent = Intent(context, AssistActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startAssistantActivity(intent) }
            .onSuccess { Journal.log("fenêtre assistant demandée") }
            .onFailure { e ->
                Journal.log("échec startAssistantActivity : $e — essai direct")
                runCatching { context.startActivity(intent) }.onFailure { Journal.log("échec startActivity : $e") }
            }
        // Laisse le temps à la capture d'arriver avant de fermer la session.
        handler.postDelayed({ hide() }, 2500)
    }

    @Deprecated("Remplacée par onHandleAssist à partir d'Android 10, mais toujours appelée pour la capture")
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        Journal.log(if (screenshot != null) "capture reçue" else "pas de capture")
        if (screenshot != null) runCatching { ScreenCapture.save(context, screenshot) }
    }

    override fun onHide() {
        Journal.log("session cachée")
        handler.removeCallbacksAndMessages(null)
        super.onHide()
    }
}

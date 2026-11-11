package fr.maison.assistant.assist

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import fr.maison.assistant.MainActivity
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
        ScreenCapture.clear()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_FROM_ASSIST, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startAssistantActivity(intent)
        // Laisse le temps à la capture d'arriver avant de fermer la session.
        handler.postDelayed({ hide() }, 2500)
    }

    @Deprecated("Remplacée par onHandleAssist à partir d'Android 10, mais toujours appelée pour la capture")
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (screenshot != null) ScreenCapture.save(context, screenshot)
    }

    override fun onHide() {
        handler.removeCallbacksAndMessages(null)
        super.onHide()
    }
}

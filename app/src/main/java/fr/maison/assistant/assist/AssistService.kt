package fr.maison.assistant.assist

import android.service.voice.VoiceInteractionService
import fr.maison.assistant.Journal

/** Service déclaré pour le rôle « assistant numérique » ; tout se passe dans la session. */
class AssistService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Journal.log("service assistant prêt")
    }

    override fun onShutdown() {
        Journal.log("service assistant arrêté")
        super.onShutdown()
    }
}

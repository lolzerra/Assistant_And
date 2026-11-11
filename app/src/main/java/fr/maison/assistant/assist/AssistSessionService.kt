package fr.maison.assistant.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import fr.maison.assistant.Journal

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Journal.log("nouvelle session")
        return AssistSession(this)
    }
}

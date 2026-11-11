package fr.maison.assistant

import android.app.Application

class AssistantApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Journal.init(this)
    }
}

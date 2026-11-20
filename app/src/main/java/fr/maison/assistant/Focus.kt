package fr.maison.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Focus audio passager pendant que l'assistant écoute ou parle : le lecteur de musique se met en pause,
 * puis reprend tout seul quand on rend le focus. (Un focus permanent le mettrait en pause sans reprise.)
 */
object Focus {
    private val holders = mutableSetOf<Any>()
    private var request: AudioFocusRequest? = null

    /** Prend (ou reprend) le focus pour `key` ; à rendre avec release(key). */
    @Synchronized
    fun hold(context: Context, key: Any) {
        holders.add(key)
        val am = context.getSystemService(AudioManager::class.java)
        val r = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build().also { request = it }
        // Redemandé à chaque fois : si une musique a démarré entre-temps, elle repasse en pause.
        runCatching { am.requestAudioFocus(r) }
    }

    @Synchronized
    fun release(context: Context, key: Any) {
        if (!holders.remove(key) || holders.isNotEmpty()) return
        request?.let { r -> runCatching { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(r) } }
    }
}

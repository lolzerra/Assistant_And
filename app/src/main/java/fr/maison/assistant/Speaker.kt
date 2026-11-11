package fr.maison.assistant

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File

/** Lit une réponse à voix haute avec la voix Piper du serveur, morceau par morceau (le suivant se prépare pendant la lecture). */
class Speaker(private val context: Context, private val api: Api, private val scope: CoroutineScope) {
    private var job: Job? = null
    private var player: MediaPlayer? = null

    fun speak(markdown: String) {
        stop()
        job = scope.launch {
            runCatching {
                val segments = api.speechSegments(markdown)
                var next = segments.firstOrNull()?.let { s -> async(Dispatchers.IO) { api.speechAudio(s) } }
                for (i in segments.indices) {
                    val audio = next?.await() ?: break
                    next = segments.getOrNull(i + 1)?.let { s -> async(Dispatchers.IO) { api.speechAudio(s) } }
                    play(audio, i)
                }
            }
        }
    }

    private suspend fun play(audio: ByteArray, i: Int) {
        val file = File(context.cacheDir, "voix-${i % 2}.wav").apply { writeBytes(audio) }
        val done = CompletableDeferred<Unit>()
        val mp = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { done.complete(Unit) }
            setOnErrorListener { _, _, _ -> done.complete(Unit); true }
            prepare()
            start()
        }
        player = mp
        try {
            done.await()
        } finally {
            mp.release()
            if (player === mp) player = null
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { player?.stop() }
        player?.release()
        player = null
    }
}

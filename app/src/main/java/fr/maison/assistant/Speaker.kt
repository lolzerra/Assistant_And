package fr.maison.assistant

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Lit les réponses avec la voix Piper du serveur, sans attendre la fin de la réponse :
 * chaque phrase terminée part en synthèse pendant que la suite s'écrit, et le morceau suivant
 * se prépare pendant la lecture du précédent.
 */
class Speaker(private val context: Context, private val api: Api, private val scope: CoroutineScope) {
    private var jobs = listOf<Job>()
    private var player: MediaPlayer? = null
    private var count = 0

    /** Une réponse lue au fil de l'eau : feed() à chaque morceau reçu, finish() à la fin. */
    inner class Stream {
        private val texts = Channel<String>(Channel.UNLIMITED)
        private val audio = Channel<ByteArray>(2)  // au plus deux morceaux prêts d'avance
        private var spoken = 0       // caractères de la réponse déjà envoyés à la synthèse
        private var first = true

        init {
            val synth = scope.launch {
                for (chunk in texts) {
                    runCatching { api.speechSegments(chunk) }.getOrDefault(emptyList()).forEach { seg ->
                        runCatching { api.speechAudio(seg) }.onSuccess { audio.send(it) }
                    }
                }
                audio.close()
            }
            val play = scope.launch { for (a in audio) play(a) }
            jobs = jobs + synth + play
        }

        fun feed(text: String) {
            if (text.length < spoken) spoken = text.length  // texte corrigé par le serveur : on ne relit pas
            val cut = cutPoint(text, spoken, final = false) ?: return
            send(text.substring(spoken, cut))
            spoken = cut
        }

        fun finish(text: String) {
            if (text.length > spoken) send(text.substring(spoken))
            spoken = text.length
            texts.close()
        }

        private fun send(chunk: String) {
            if (chunk.isNotBlank()) texts.trySend(chunk)
            first = false
        }

        /** Dernière fin de phrase utilisable après `from`, hors bloc de code et hors tableau en cours. */
        private fun cutPoint(text: String, from: Int, final: Boolean): Int? {
            val minLength = if (first) 12 else 120
            var best: Int? = null
            for (m in BOUNDARY.findAll(text, from)) {
                val end = m.range.last + 1
                if (end - from < minLength) continue
                val before = text.substring(0, end)
                if (FENCE.findAll(before).count() % 2 == 1) continue                 // dans un bloc de code
                val lastLine = before.trimEnd().substringAfterLast('\n').trimStart()
                if (lastLine.startsWith("|") && !text.startsWith("\n\n", m.range.first)) continue  // tableau pas fini
                best = end
                if (first) break  // la toute première phrase part seule, pour démarrer vite
            }
            return best
        }
    }

    fun stream(): Stream {
        stop()
        return Stream()
    }

    /** Lecture d'un texte complet (réponse déjà écrite). */
    fun speak(markdown: String) {
        stream().finish(markdown)
    }

    private suspend fun play(audio: ByteArray) {
        val file = File(context.cacheDir, "voix-${count++ % 3}.wav").apply { writeBytes(audio) }
        val done = CompletableDeferred<Unit>()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { done.complete(Unit) }
            mp.setOnErrorListener { _, _, _ -> done.complete(Unit); true }
            mp.prepare()
            mp.start()
        } catch (e: Exception) { // morceau illisible : on passe au suivant au lieu de faire planter l'appli
            Journal.log("lecture voix impossible : $e")
            mp.release()
            return
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
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        runCatching { player?.stop() }
        player?.release()
        player = null
    }

    companion object {
        private val BOUNDARY = Regex("""[.!?…:](?=\s)|\n""")
        private val FENCE = Regex("```")
    }
}

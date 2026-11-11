package fr.maison.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Enregistre le micro en WAV 16 kHz mono 16 bits, le format qu'attend Whisper.
 * Donne le niveau sonore en direct (visualiseur) et s'arrête tout seul après un silence qui suit la parole.
 */
class Recorder {
    @Volatile private var running = false
    private val _level = MutableStateFlow(0f)
    /** Niveau sonore de 0 à 1, mis à jour environ 10 fois par seconde. */
    val level: StateFlow<Float> = _level

    /** Vrai si de la parole a été entendue pendant le dernier enregistrement. */
    var heardSpeech = false
        private set

    val isRecording get() = running

    /** Enregistre jusqu'à stop(), un silence après la parole, ou 2 minutes. Permission vérifiée par l'appelant. */
    @SuppressLint("MissingPermission")
    suspend fun record(autoStop: Boolean = true): ByteArray = withContext(Dispatchers.IO) {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        val pcm = ByteArrayOutputStream()
        val buf = ByteArray(3200) // 100 ms
        var noise = 0.01f        // bruit de fond mesuré pendant les silences
        var silentMs = 0
        var totalMs = 0
        heardSpeech = false
        running = true
        rec.startRecording()
        try {
            while (running && totalMs < 120_000) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                pcm.write(buf, 0, n)
                val ms = n / 32
                totalMs += ms
                val rms = rms(buf, n)
                _level.value = (rms * 6f).coerceIn(0f, 1f)
                val speaking = rms > maxOf(0.02f, noise * 3f)
                if (speaking) {
                    heardSpeech = true
                    silentMs = 0
                } else {
                    noise = 0.95f * noise + 0.05f * rms
                    silentMs += ms
                }
                if (autoStop && heardSpeech && silentMs >= SILENCE_MS) break       // fin de phrase
                if (autoStop && !heardSpeech && totalMs >= NOTHING_MS) break      // personne ne parle
            }
        } finally {
            rec.stop()
            rec.release()
            running = false
            _level.value = 0f
        }
        wav(pcm.toByteArray(), rate)
    }

    fun stop() {
        running = false
    }

    private fun rms(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort() / 32768.0
            sum += s * s
            i += 2
        }
        return sqrt(sum / (n / 2).coerceAtLeast(1)).toFloat()
    }

    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }

    companion object {
        const val SILENCE_MS = 1300
        const val NOTHING_MS = 8000
    }
}

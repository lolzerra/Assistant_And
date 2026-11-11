package fr.maison.assistant

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal de diagnostic : étapes d'ouverture de l'assistant et plantages, gardé dans un fichier
 * puis envoyé au serveur (data/journal-appli.txt) à la prochaine ouverture de l'appli.
 */
object Journal {
    private var file: File? = null
    private val time = SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.FRANCE)

    fun init(context: Context) {
        file = File(context.filesDir, "journal.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
            log("PLANTAGE (${thread.name}) : $trace")
            previous?.uncaughtException(thread, e)
        }
        log("processus démarré")
    }

    @Synchronized
    fun log(message: String) {
        val f = file ?: return
        runCatching {
            f.appendText("${time.format(Date())} $message\n")
            if (f.length() > 150_000) f.writeText(f.readText().takeLast(100_000))
        }
    }

    /** Envoie le journal au serveur et retire ce qui est parti. */
    suspend fun upload(api: Api) {
        val f = file ?: return
        val text = synchronized(this) { runCatching { f.readText() }.getOrDefault("") }
        if (text.isBlank()) return
        runCatching { api.journal(text) }.onSuccess {
            synchronized(this) { runCatching { f.writeText(f.readText().removePrefix(text)) } }
        }
    }
}

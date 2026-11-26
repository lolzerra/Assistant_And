package fr.maison.assistant

import android.app.PendingIntent
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * App Streaming, le lecteur de musique de l'utilisateur : lancement en arrière-plan d'une playlist, d'un morceau
 * ou d'un artiste (MEDIA_PLAY_FROM_SEARCH), et liste des playlists. Le lecteur répond par un PendingIntent.
 * À appeler hors du fil principal, pendant que l'assistant est à l'écran (sinon Android bloque le lancement).
 */
object Music {
    const val PACKAGE = "com.lolzerra.appstreaming"
    private const val RESULT = "fr.maison.assistant.extra.RESULT"
    private const val SHUFFLE = "fr.maison.assistant.extra.SHUFFLE"
    private val counter = AtomicInteger()

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Réponse du lecteur : texte pour le modèle, statut affiché sous la réponse. */
    class Result(val text: String, val shown: String, val status: String, val message: String, val suggestions: List<String>)

    /** Renvoie (texte pour le modèle, statut affiché). */
    fun play(context: Context, query: String, type: String, shuffle: Boolean?): Pair<String, String> =
        result(context, query, type, shuffle).let { it.text to it.shown }

    fun result(context: Context, query: String, type: String, shuffle: Boolean?): Result {
        if (!installed(context)) return Result("Erreur : le lecteur App Streaming n'est pas installé sur le téléphone.",
            "⚠ App Streaming absent", "erreur", "App Streaming n'est pas installé.", emptyList())
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setClassName(PACKAGE, "$PACKAGE.PlayFromSearchActivity")
            .putExtra(SearchManager.QUERY, query)
        if (query.isNotBlank()) when (type) {
            "playlist" -> intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, query)
            "morceau" -> intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
            "artiste" -> intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_ARTIST, query)
            "album" -> intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_ALBUM, query)
        }
        shuffle?.let { intent.putExtra(SHUFFLE, it) }
        Journal.log("musique : « $query » ($type${shuffle?.let { if (it) ", aléatoire" else ", dans l'ordre" }.orEmpty()})")
        val r = request(context, intent)
            ?: return Result("Commande envoyée au lecteur, sans confirmation (pas de réponse en 10 s) : ne promets pas que ça joue.",
                "🎵 Envoyé au lecteur", "ok", "", emptyList())
        val status = r.getString("status").orEmpty()
        val message = r.getString("message").orEmpty()
        val now = r.getString("now_playing")
        val suggestions = r.getStringArrayList("suggestions").orEmpty()
        val text = buildString {
            append(when (status) { "ok" -> "C'est parti"; "introuvable" -> "Introuvable"; else -> "Erreur" })
            if (message.isNotBlank()) append(" : $message")
            now?.let { append(". En cours : $it") }
            if (suggestions.isNotEmpty()) append(". Suggestions : ${suggestions.joinToString(", ")}")
        }
        val shown = when (status) {
            "ok" -> "🎵 ${message.ifBlank { now ?: "Lecture lancée" }}"
            "introuvable" -> "🎵 ${message.ifBlank { "Introuvable" }}"
            else -> "⚠ ${message.ifBlank { "Le lecteur n'a pas pu lancer la musique" }}"
        }
        return Result(text, shown, status, message, suggestions)
    }

    /** Active (true), coupe (false) ou inverse (null) l'aléatoire, sans relancer la musique. */
    fun shuffle(context: Context, on: Boolean?): Pair<String, String> {
        if (!installed(context)) return "Erreur : le lecteur App Streaming n'est pas installé sur le téléphone." to "⚠ App Streaming absent"
        val intent = Intent("$PACKAGE.action.SHUFFLE").setClassName(PACKAGE, "$PACKAGE.ShuffleActivity")
        on?.let { intent.putExtra(SHUFFLE, it) }
        Journal.log("musique : aléatoire ${on ?: "inversé"}")
        val r = request(context, intent) ?: return "Erreur : le lecteur n'a pas répondu (version trop ancienne ?)." to "⚠ Pas de réponse du lecteur"
        val status = r.getString("status").orEmpty()
        val message = r.getString("message").orEmpty().ifBlank { if (status == "ok") "Fait" else "Impossible" }
        return "${if (status == "ok") "OK" else status.ifBlank { "erreur" }} : $message" to
            "${if (status == "ok") "🔀" else "⚠"} $message"
    }

    fun playlists(context: Context): Pair<String, String> {
        if (!installed(context)) return "Erreur : le lecteur App Streaming n'est pas installé sur le téléphone." to "⚠ App Streaming absent"
        val intent = Intent("$PACKAGE.action.LIST_PLAYLISTS").setClassName(PACKAGE, "$PACKAGE.ListPlaylistsActivity")
        val r = request(context, intent) ?: return "Erreur : le lecteur n'a pas répondu." to "⚠ Pas de réponse du lecteur"
        val list = r.getStringArrayList("playlists").orEmpty()
        return (if (list.isEmpty()) "Aucune playlist dans le lecteur." else "Playlists : ${list.joinToString(", ")}") to
            "🎵 ${list.size} playlist${if (list.size > 1) "s" else ""}"
    }

    /** Noms des playlists du lecteur (vide s'il est absent ou ne répond pas). À appeler hors du fil principal. */
    fun playlistNames(context: Context): List<String> {
        if (!installed(context)) return emptyList()
        val intent = Intent("$PACKAGE.action.LIST_PLAYLISTS").setClassName(PACKAGE, "$PACKAGE.ListPlaylistsActivity")
        return request(context, intent)?.getStringArrayList("playlists").orEmpty()
    }

    /** Lance l'Activity invisible du lecteur et attend sa réponse (10 s au plus). */
    private fun request(context: Context, intent: Intent): Bundle? {
        val action = "${context.packageName}.MUSIC_RESULT.${counter.incrementAndGet()}"
        val latch = CountDownLatch(1)
        var result: Bundle? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                result = i.extras ?: Bundle()
                latch.countDown()
            }
        }
        // Le PendingIntent est envoyé en notre nom : un récepteur non exporté le reçoit.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val reply = PendingIntent.getBroadcast(context, counter.get(), Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT)
            context.startActivity(intent.putExtra(RESULT, reply).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            latch.await(10, TimeUnit.SECONDS)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        Journal.log("musique : réponse ${result?.getString("status") ?: "aucune"} ${result?.getString("message").orEmpty()}")
        return result
    }
}

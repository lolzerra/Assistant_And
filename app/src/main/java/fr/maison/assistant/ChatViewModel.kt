package fr.maison.assistant

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Msg(
    val user: Boolean,
    val text: String,
    val steps: List<String> = emptyList(),
    val thumbs: List<ImageBitmap> = emptyList(),  // images envoyées depuis le téléphone
    val imageIds: List<Int> = emptyList(),       // images d'un message rechargé depuis le serveur
    val error: String? = null,
    val stats: String? = null,
    val pending: Boolean = false,
)

class Attachment(val label: String, val bytes: ByteArray) {
    val thumb: ImageBitmap? = Images.thumb(bytes)
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val api = Api(prefs)
    private val recorder = Recorder()
    /** Niveau du micro (0 à 1) pour le visualiseur. */
    val micLevel = recorder.level
    val speaker = Speaker(app, api, viewModelScope)

    val messages = mutableStateListOf<Msg>()
    val attachments = mutableStateListOf<Attachment>()
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var transcribing by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
    private var sending: Job? = null
    /** Conversation affichée ici ; l'autre fenêtre (appli ou assistant) peut en avoir changé entre-temps. */
    private var shown = 0

    init {
        loadConversation()
    }

    fun uploadJournal() {
        if (prefs.loggedIn) viewModelScope.launch { Journal.upload(api) }
    }

    /** Affiche la conversation en cours si l'assistant en a ouvert une autre pendant ce temps. */
    fun syncConversation() {
        if (busy || prefs.conversation == shown) return
        if (prefs.conversation == 0) newConversation() else loadConversation()
    }

    fun loadConversation() {
        val cid = prefs.conversation
        if (!prefs.loggedIn || cid == 0) return
        viewModelScope.launch {
            runCatching { api.messages(cid) }
                .onSuccess { list ->
                    if (prefs.conversation != cid) return@onSuccess // nouvelle conversation entre-temps
                    shown = cid
                    messages.clear()
                    list.forEach { m -> messages.add(Msg(user = m.role == "user", text = m.text, imageIds = m.images)) }
                }
                .onFailure { prefs.conversation = 0 } // conversation supprimée sur le site : on repart de zéro
        }
    }

    fun newConversation() {
        speaker.stop()
        prefs.conversation = 0
        shown = 0
        messages.clear()
        attachments.clear()
        notice = null
    }

    // ---------- Pièces jointes ----------

    fun attachUri(uri: Uri, label: String = "Photo") = viewModelScope.launch {
        runCatching { Images.fromUri(getApplication(), uri) }
            .onSuccess { attachments.add(Attachment(label, it)) }
            .onFailure { notice = "Image illisible : ${it.message}" }
    }

    fun attachFile(file: File, label: String) = viewModelScope.launch {
        runCatching { Images.fromFile(file) }
            .onSuccess { attachments.add(Attachment(label, it)) }
            .onFailure { notice = "Image illisible : ${it.message}" }
    }

    fun removeAttachment(a: Attachment) {
        attachments.remove(a)
    }

    // ---------- Dictée ----------

    fun toggleRecording() {
        if (recording) {
            recorder.stop()
            return
        }
        speaker.stop()
        recording = true
        viewModelScope.launch {
            try {
                val wav = recorder.record()
                recording = false
                if (!recorder.heardSpeech) {
                    notice = "Je n'ai rien entendu"
                    return@launch
                }
                transcribing = true
                val text = api.transcribe(wav).trim()
                if (text.isNotEmpty()) {
                    input = listOf(input.trim(), text).filter { it.isNotEmpty() }.joinToString(" ")
                    if (prefs.sendAfterDictation) send(fromVoice = true)
                }
            } catch (e: Exception) {
                notice = "Dictée impossible : ${e.message}"
            } finally {
                recording = false
                transcribing = false
            }
        }
    }

    // ---------- Envoi ----------

    fun stop() {
        sending?.cancel()
    }

    fun send(fromVoice: Boolean = false) {
        val text = input.trim()
        if (busy || (text.isEmpty() && attachments.isEmpty())) return
        if (!prefs.loggedIn) {
            notice = "Connecte-toi d'abord dans les réglages ⚙"
            return
        }
        val files = attachments.toList()
        input = ""
        attachments.clear()
        notice = null
        speaker.stop()
        busy = true
        messages.add(Msg(user = true, text = text, thumbs = files.mapNotNull { it.thumb }))
        messages.add(Msg(user = false, text = "", pending = true))
        val index = messages.lastIndex
        // Voix phrase par phrase pendant l'écriture de la réponse.
        val voice = if (prefs.speak || fromVoice) speaker.stream() else null
        sending = viewModelScope.launch {
            var reply = ""
            try {
                if (prefs.conversation == 0) prefs.conversation = api.newConversation(prefs.model)
                shown = prefs.conversation
                val ids = files.mapIndexed { i, a -> api.uploadImage("${a.label.lowercase()}-${i + 1}.jpg", a.bytes) }
                val body = JSONObject()
                    .put("content", text)
                    .put("attachments", JSONArray(ids))
                    .put("model", prefs.model)
                    .put("think_level", if (prefs.think) 2 else 0)
                    .put("web", prefs.web)
                    .put("label", "Assistant")
                    .put("instructions", prefs.instructions)
                val steps = mutableListOf<String>()
                api.send(prefs.conversation, body).collect { ev ->
                    when {
                        ev.has("content") -> { reply += ev.getString("content"); voice?.feed(reply) }
                        ev.has("content_reset") -> { reply = ev.getString("content_reset"); voice?.feed(reply) }
                        ev.has("tool") -> steps.add(stepLabel(ev.getJSONObject("tool")))
                        ev.has("error") -> throw ApiError(ev.getString("error"))
                    }
                    val stats = if (ev.optBoolean("done")) {
                        "${ev.optDouble("seconds", 0.0)} s · ${ev.optInt("tokens")} tokens · ${ev.optDouble("tok_s", 0.0)} tok/s"
                    } else messages[index].stats
                    messages[index] = messages[index].copy(text = reply, steps = steps.toList(), stats = stats, pending = !ev.optBoolean("done"))
                }
                voice?.finish(reply)
            } catch (e: kotlinx.coroutines.CancellationException) {
                speaker.stop()
                messages[index] = messages[index].copy(pending = false, error = "Arrêté")
            } catch (e: Exception) {
                voice?.finish(reply)
                if (e.message?.contains("Conversation introuvable") == true) prefs.conversation = 0
                messages[index] = messages[index].copy(text = reply, pending = false, error = e.message ?: "Erreur")
            } finally {
                busy = false
                sending = null
            }
        }
    }

    private fun stepLabel(tool: JSONObject): String = when (tool.optString("name")) {
        "web_search" -> "🔎 Recherche : ${tool.optString("query")}"
        "fetch_page" -> "📄 Page lue : " + (runCatching { java.net.URI(tool.optString("url")).host?.removePrefix("www.") }.getOrNull() ?: "page web")
        else -> "🔧 ${tool.optString("name")}"
    }

    // ---------- Compte ----------

    suspend fun login(server: String, username: String, password: String) {
        prefs.server = server
        prefs.token = api.login(username, password)
        prefs.username = username
        prefs.conversation = 0
        messages.clear()
    }

    fun logout() = viewModelScope.launch {
        api.logout()
        prefs.token = ""
        prefs.username = ""
        prefs.conversation = 0
        messages.clear()
    }
}

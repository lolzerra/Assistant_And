package fr.maison.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiError(message: String) : IOException(message)

/** Client du site Chat IA : mêmes routes que la page web, avec le jeton de l'appli. */
class Api(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // les réponses arrivent en flux, parfois après une longue réflexion
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun request(path: String): Request.Builder =
        Request.Builder().url(prefs.server + path).header("Authorization", "Bearer ${prefs.token}")

    private fun Response.checked(): Response {
        if (isSuccessful) return this
        val text = body?.string().orEmpty()
        val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()?.takeIf { it.isNotBlank() }
        close()
        throw ApiError(
            when (code) {
                401 -> "Non connecté : reconnecte-toi dans les réglages"
                else -> detail ?: "Erreur $code"
            }
        )
    }

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().checked().use { it.body?.string().orEmpty() }
    }

    suspend fun login(username: String, password: String): String {
        val body = JSONObject().put("username", username).put("password", password).put("device", android.os.Build.MODEL)
        val res = call(Request.Builder().url(prefs.server + "/api/app/login").post(body.toString().toRequestBody(json)).build())
        return JSONObject(res).getString("token")
    }

    suspend fun logout() {
        runCatching { call(request("/api/app/token").delete().build()) }
    }

    suspend fun newConversation(model: String): Int {
        val res = call(request("/api/conversations").post(JSONObject().put("model", model).toString().toRequestBody(json)).build())
        return JSONObject(res).getInt("id")
    }

    /** where : « appli » (seulement dans l'appli), « site » (aussi sur le site), « aucun » (retirer de la liste). */
    suspend fun save(conversation: Int, where: String) {
        call(request("/api/conversations/$conversation/save").post(JSONObject().put("where", where).toString().toRequestBody(json)).build())
    }

    data class Saved(val id: Int, val title: String, val updated: Long, val site: Boolean, val count: Int,
                     val model: String = "", val type: String = "")

    /** Onglet « Globales » : toutes les conversations du compte (site et appli), filtrées par titre. */
    suspend fun allConversations(q: String = ""): List<Saved> {
        val arr = JSONArray(call(request("/api/app/conversations?q=" + URLEncoder.encode(q, "UTF-8")).get().build()))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Saved(o.getInt("id"), o.optString("title"), (o.optDouble("updated_at", 0.0) * 1000).toLong(), o.optBoolean("site"),
                o.optInt("n"), if (o.isNull("model")) "" else o.optString("model"), o.optString("type"))
        }
    }

    suspend fun saved(): List<Saved> {
        val arr = JSONArray(call(request("/api/app/saved").get().build()))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Saved(o.getInt("id"), o.optString("title"), (o.optDouble("updated_at", 0.0) * 1000).toLong(), o.optBoolean("site"), o.optInt("n"))
        }
    }

    // ---------- Mémoire ----------

    data class Memory(val id: Int, val title: String, val text: String, val updated: Long, val uses: Int)

    /** Souvenirs (tous, ou ceux qui contiennent les mots de q) et état de l'interrupteur. */
    suspend fun memory(q: String = ""): Pair<Boolean, List<Memory>> {
        val o = JSONObject(call(request("/api/memory?q=" + URLEncoder.encode(q, "UTF-8")).get().build()))
        val arr = o.getJSONArray("memories")
        return o.optBoolean("enabled", true) to List(arr.length()) { i ->
            val m = arr.getJSONObject(i)
            Memory(m.getInt("id"), m.optString("titre"), m.getString("texte"), (m.optDouble("modifie", 0.0) * 1000).toLong(),
                m.optInt("utilisations"))
        }
    }

    /** Titre vide : le serveur en tire un de la description. */
    suspend fun memoryAdd(title: String, text: String) {
        val body = JSONObject().put("texte", text).apply { if (title.isNotBlank()) put("titre", title) }
        call(request("/api/memory").post(body.toString().toRequestBody(json)).build())
    }

    suspend fun memoryEdit(id: Int, title: String, text: String) {
        val body = JSONObject().put("texte", text).put("titre", title)
        call(request("/api/memory/$id").put(body.toString().toRequestBody(json)).build())
    }

    suspend fun memoryDelete(id: Int) {
        call(request("/api/memory/$id").delete().build())
    }

    suspend fun memoryEnabled(on: Boolean) {
        call(request("/api/memory_settings").put(JSONObject().put("enabled", on).toString().toRequestBody(json)).build())
    }

    /** Souvenir proposé par la relecture, en attente de validation. type : ajout, modif ou oubli. */
    data class Proposal(val id: Int, val type: String, val title: String, val text: String, val oldTitle: String, val oldText: String)

    suspend fun memoryProposals(): List<Proposal> {
        val arr = JSONObject(call(request("/api/memory/proposals").get().build())).getJSONArray("proposals")
        return List(arr.length()) { i ->
            val p = arr.getJSONObject(i)
            fun str(k: String) = if (p.isNull(k)) "" else p.optString(k)
            Proposal(p.getInt("id"), str("type"), str("titre"), str("texte"), str("ancien_titre"), str("ancien_texte"))
        }
    }

    /** Valide ou refuse ; renvoie l'état final (accepted, refused). */
    suspend fun memoryDecide(id: Int, accept: Boolean): String {
        val body = JSONObject().put("accept", accept).toString().toRequestBody(json)
        return JSONObject(call(request("/api/memory/proposals/$id").post(body).build())).optString("state")
    }

    /** Relecture mémoire d'un échange : null tant qu'elle tourne, sinon ce qui a changé. */
    suspend fun memoryReview(id: Int): JSONObject? {
        val o = JSONObject(call(request("/api/memory/review/$id").get().build()))
        return if (o.optString("state") == "pending") null else o
    }

    data class Past(val role: String, val text: String, val images: List<Int>, val model: String = "")

    /** Messages d'une conversation, avec les identifiants de leurs images, pour réafficher la discussion en cours. */
    suspend fun messages(conversation: Int): List<Past> {
        val arr = JSONObject(call(request("/api/conversations/$conversation").get().build())).getJSONArray("messages")
        return List(arr.length()) { i ->
            val m = arr.getJSONObject(i)
            val atts = m.optJSONArray("attachments") ?: JSONArray()
            val images = (0 until atts.length()).map { atts.getJSONObject(it) }.filter { it.optString("kind") == "image" }.map { it.getInt("id") }
            Past(m.getString("role"), m.optString("content"), images, if (m.isNull("model")) "" else m.optString("model"))
        }
    }

    /** Image d'une pièce jointe déjà envoyée (pour les miniatures de l'historique). */
    suspend fun image(id: Int): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(request("/api/attachments/$id/image").get().build()).execute().checked().use { it.body!!.bytes() }
    }

    /** Envoie une image (JPEG) ; renvoie l'identifiant de la pièce jointe à passer au message. */
    suspend fun uploadImage(name: String, bytes: ByteArray): Int {
        val url = "/api/attachments?name=" + URLEncoder.encode(name, "UTF-8")
        val res = call(request(url).post(bytes.toRequestBody("image/jpeg".toMediaType())).build())
        return JSONObject(res).getInt("id")
    }

    /** Résultat d'une action demandée pendant la réponse (le serveur l'attend pour continuer). */
    suspend fun phoneResult(id: String, result: String) {
        call(request("/api/phone/result/$id").post(JSONObject().put("result", result.take(30_000)).toString().toRequestBody(json)).build())
    }

    /** Préchauffage : le serveur fait lire d'avance au modèle ses consignes et ses outils. */
    suspend fun warm(model: String, web: Boolean, instructions: String, thinkLevel: Int) {
        call(request("/api/app/warm").post(JSONObject().put("model", model).put("web", web)
            .put("instructions", instructions).put("think_level", thinkLevel).toString().toRequestBody(json)).build())
    }

    /** Image trouvée par image_search, gardée par le site (chemin /api/web_images/….jpg). */
    suspend fun webImage(path: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(request(path).get().build()).execute().checked().use { it.body!!.bytes() }
    }

    /** Clé qui signe les alertes du site (veille…) relayées par Home Assistant. */
    suspend fun alertKey(): String = JSONObject(call(request("/api/app/alert_key").get().build())).getString("key")

    suspend fun alertAck() {
        call(request("/api/app/alert_ack").post("".toRequestBody(json)).build())
    }

    /** Journal de diagnostic de l'appli. */
    suspend fun journal(text: String) {
        call(request("/api/app/journal").post(text.toRequestBody("text/plain; charset=utf-8".toMediaType())).build())
    }

    /** Dictée : un enregistrement WAV 16 kHz, transcrit par Whisper sur la carte 2. */
    suspend fun transcribe(wav: ByteArray): String {
        val res = call(request("/api/transcribe").post(wav.toRequestBody("audio/wav".toMediaType())).build())
        return JSONObject(res).optString("text")
    }

    /** Message et réponse en flux : un objet JSON par ligne (meta, thinking, content, tool, done, error…). */
    fun send(conversation: Int, body: JSONObject): Flow<JSONObject> = flow {
        val req = request("/api/conversations/$conversation/messages").post(body.toString().toRequestBody(json)).build()
        http.newCall(req).execute().checked().use { res ->
            val source = res.body!!.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isNotBlank()) emit(JSONObject(line))
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Texte d'une réponse → morceaux à dire (le serveur retire le code et la mise en forme). */
    suspend fun speechSegments(markdown: String): List<String> {
        val res = call(request("/api/speech/segments").post(JSONObject().put("text", markdown).toString().toRequestBody(json)).build())
        val arr: JSONArray = JSONObject(res).getJSONArray("segments")
        return List(arr.length()) { arr.getString(it) }
    }

    /** Un morceau de texte → WAV, par Piper sur le serveur. */
    suspend fun speechAudio(text: String): ByteArray = withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text).put("voice", prefs.voice)
        val req = request("/api/speech/audio").post(body.toString().toRequestBody(json)).build()
        http.newCall(req).execute().checked().use { it.body!!.bytes() }
    }
}

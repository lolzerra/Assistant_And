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

    /** Messages d'une conversation : [(rôle, texte)], pour réafficher la discussion en cours. */
    suspend fun messages(conversation: Int): List<Pair<String, String>> {
        val arr = JSONObject(call(request("/api/conversations/$conversation").get().build())).getJSONArray("messages")
        return List(arr.length()) { arr.getJSONObject(it).let { m -> m.getString("role") to m.optString("content") } }
    }

    /** Envoie une image (JPEG) ; renvoie l'identifiant de la pièce jointe à passer au message. */
    suspend fun uploadImage(name: String, bytes: ByteArray): Int {
        val url = "/api/attachments?name=" + URLEncoder.encode(name, "UTF-8")
        val res = call(request(url).post(bytes.toRequestBody("image/jpeg".toMediaType())).build())
        return JSONObject(res).getInt("id")
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
        val req = request("/api/speech/audio").post(JSONObject().put("text", text).toString().toRequestBody(json)).build()
        http.newCall(req).execute().checked().use { it.body!!.bytes() }
    }
}

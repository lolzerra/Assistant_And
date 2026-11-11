package fr.maison.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** Mises à jour depuis les Releases du dépôt GitHub public. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/lolzerra/Assistant_And/releases/latest"
    private val http = OkHttpClient()

    data class Release(val version: String, val apkUrl: String, val notes: String)

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext null
            val j = JSONObject(res.body!!.string())
            val assets = j.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return@withContext null
            Release(j.getString("tag_name").removePrefix("v"), apk.getString("browser_download_url"), j.optString("body"))
        }
    }

    /** Vrai si `remote` (ex. 0.2.0) est plus récente que la version installée. */
    fun isNewer(remote: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return current.contains("-dev")
    }

    /** Télécharge l'APK puis ouvre l'installeur d'Android (il demande confirmation). */
    suspend fun install(context: Context, release: Release) {
        val file = File(context.cacheDir, "mise-a-jour.apk")
        withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { res ->
                if (!res.isSuccessful) throw ApiError("Téléchargement impossible (${res.code})")
                file.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
            }
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            throw ApiError("Autorise l'installation pour Assistant, puis relance la mise à jour")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

package fr.maison.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import kotlin.coroutines.resume

/**
 * Avant d'ouvrir Maps ou Waze : si la localisation est coupée, Android affiche sa fenêtre « Activer la
 * localisation ? » (un appui suffit). Une appli ne peut pas l'activer seule, sans cet appui.
 */
object Location {
    /** Demande en cours, lue par LocationActivity : fenêtre de Google et réponse attendue. */
    @Volatile var pending: Pair<PendingIntent, CompletableDeferred<Boolean>>? = null

    private val NAV = listOf("maps", "map", "waze", "gps", "navigation", "itineraire", "carte", "plans")

    fun isNavigationApp(name: String): Boolean {
        val n = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return NAV.any { Regex("\\b$it\\b").containsMatchIn(n) }
    }

    fun enabled(context: Context) =
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true

    /** Demande l'activation si besoin, puis attend la réponse (30 s au plus). */
    suspend fun ensure(context: Context) {
        if (enabled(context)) return
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000).build())
            .setAlwaysShow(true).build()
        val error = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<Exception?> { c ->
                LocationServices.getSettingsClient(context).checkLocationSettings(request)
                    .addOnCompleteListener { t -> if (c.isActive) c.resume(t.exception) }
            }
        }
        if (error !is ResolvableApiException) {
            Journal.log("localisation coupée : pas de fenêtre possible (${error?.javaClass?.simpleName ?: "délai dépassé"})")
            return  // Maps et Waze proposeront eux-mêmes de l'activer
        }
        // La fenêtre est ouverte depuis une Activity à nous (invisible) : lancée depuis le service ou l'application,
        // Android la bloque sans rien dire.
        val answer = CompletableDeferred<Boolean>()
        pending = error.resolution to answer
        val shown = runCatching {
            context.startActivity(Intent(context, LocationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Journal.log("localisation : fenêtre impossible (${it.message})") }.isSuccess
        val ok = if (shown) withTimeoutOrNull(30_000) { answer.await() } else null
        pending = null
        Journal.log("localisation coupée : fenêtre d'activation ${when (ok) { true -> "acceptée"; false -> "refusée"; null -> "sans réponse" }}")
        if (ok == true) withTimeoutOrNull(3_000) { while (!enabled(context)) delay(200) }
        delay(300)  // le temps que la fenêtre se ferme, pour que l'appli s'ouvre bien devant
    }
}

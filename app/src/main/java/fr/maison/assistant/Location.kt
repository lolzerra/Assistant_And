package fr.maison.assistant

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
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
    private val NAV = listOf("maps", "map", "waze", "gps", "navigation", "itineraire", "carte", "plans")

    fun isNavigationApp(name: String): Boolean {
        val n = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return NAV.any { Regex("\\b$it\\b").containsMatchIn(n) }
    }

    fun enabled(context: Context) =
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true

    /** Demande l'activation si besoin, puis attend la réponse (12 s au plus). */
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
        val asked = runCatching {
            if (error is ResolvableApiException) {
                // Fenêtre de Google : « Pour une meilleure expérience, activez la localisation… »
                context.startIntentSender(error.resolution.intentSender, null,
                    Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_NEW_TASK, 0)
                true
            } else false
        }.getOrDefault(false)
        Journal.log("localisation coupée : ${if (asked) "fenêtre d'activation affichée" else "pas de fenêtre possible"}")
        if (!asked) return  // Maps et Waze proposeront eux-mêmes de l'activer
        withTimeoutOrNull(12_000) { while (!enabled(context)) delay(300) }
        delay(300)  // le temps que la fenêtre se ferme, pour que l'appli s'ouvre bien devant
    }
}

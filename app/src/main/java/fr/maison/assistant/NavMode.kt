package fr.maison.assistant

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mode navigation (en voiture) : demandé à l'oral (outil telephone_mode_navigation) ou depuis ⋮ → Mode navigation.
 * L'activation demande d'allumer le Bluetooth et la localisation : Android affiche ses fenêtres, l'utilisateur accepte
 * d'un appui (une appli ne peut pas les allumer seule).
 */
object NavMode {
    var active by mutableStateOf(false)

    /** Demande en cours, lue par BluetoothActivity. */
    @Volatile var pendingBluetooth: CompletableDeferred<Boolean>? = null

    fun bluetoothOn(context: Context) =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /** Allume le Bluetooth si besoin (fenêtre d'Android). Renvoie l'état obtenu. */
    suspend fun ensureBluetooth(context: Context): String {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return "absent"
        if (adapter.isEnabled) return "déjà actif"
        val answer = CompletableDeferred<Boolean>()
        pendingBluetooth = answer
        val shown = runCatching {
            context.startActivity(Intent(context, BluetoothActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Journal.log("bluetooth : fenêtre impossible (${it.message})") }.isSuccess
        val ok = if (shown) withTimeoutOrNull(30_000) { answer.await() } else null
        pendingBluetooth = null
        if (ok == true) withTimeoutOrNull(4_000) { while (!adapter.isEnabled) delay(200) }
        delay(300)
        return when {
            adapter.isEnabled -> "activé"
            ok == false -> "refusé par l'utilisateur"
            else -> "pas activé"
        }.also { Journal.log("bluetooth : $it") }
    }

    /** Active le mode : Bluetooth puis localisation. Renvoie le texte pour le modèle. */
    suspend fun enable(context: Context): Pair<String, String> {
        val bt = ensureBluetooth(context)
        Location.ensure(context)
        val loc = if (Location.enabled(context)) "active" else "coupée"
        active = true
        Journal.log("mode navigation activé (bluetooth $bt, localisation $loc)")
        return "Mode navigation activé. Bluetooth : $bt. Localisation : $loc." to
            "🚗 Mode navigation · Bluetooth ${if (bluetoothOn(context)) "✓" else "✗"} · Localisation ${if (loc == "active") "✓" else "✗"}"
    }

    fun disable(): Pair<String, String> {
        active = false
        Journal.log("mode navigation coupé")
        return "Mode navigation coupé." to "🚗 Mode navigation coupé"
    }
}

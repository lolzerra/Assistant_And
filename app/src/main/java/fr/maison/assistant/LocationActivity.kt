package fr.maison.assistant

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts

/** Activity invisible : montre la fenêtre « Activer la localisation ? » de Google et renvoie la réponse. */
class LocationActivity : ComponentActivity() {
    private val ask = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        Location.pending?.second?.complete(r.resultCode == Activity.RESULT_OK)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return  // recréée pendant que la fenêtre est ouverte : la réponse arrivera
        val request = Location.pending?.first ?: return finish()
        runCatching { ask.launch(IntentSenderRequest.Builder(request).build()) }.onFailure {
            Journal.log("localisation : fenêtre refusée (${it.message})")
            Location.pending?.second?.complete(false)
            finish()
        }
    }
}

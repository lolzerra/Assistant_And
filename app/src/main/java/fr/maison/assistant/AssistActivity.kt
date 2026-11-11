package fr.maison.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import fr.maison.assistant.ui.AssistScreen

/**
 * Fenêtre ouverte par l'appui long : demi-écran par-dessus l'appli en cours, micro lancé d'office.
 * La capture d'écran faite par Android à l'appel reste disponible tant que la fenêtre est ouverte.
 *
 * Téléphone verrouillé : la fenêtre s'affiche par-dessus l'écran de verrouillage (sinon elle s'ouvrirait
 * derrière, invisible) mais ne montre rien de la conversation tant que le téléphone n'est pas déverrouillé.
 */
class AssistActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleRecording() else vm.notice = "Le micro est nécessaire pour parler à l'assistant"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Journal.log("fenêtre assistant créée (restaurée : ${savedInstanceState != null}, verrouillé : ${keyguard.isKeyguardLocked})")
        setShowWhenLocked(true)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = MainActivity.Palette) {
                if (locked) LockedSheet(onUnlock = ::unlockThenListen, onClose = { finish() })
                else AssistScreen(
                    vm,
                    onClose = { finish() },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                    onMic = ::toggleMic,
                )
            }
        }
        if (savedInstanceState == null) unlockThenListen()
        else locked = keyguard.isKeyguardLocked
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Journal.log("fenêtre assistant rappelée (verrouillé : ${keyguard.isKeyguardLocked})")
        unlockThenListen() // nouvel appui long alors que la fenêtre était encore ouverte
    }

    private val keyguard get() = getSystemService(KeyguardManager::class.java)
    private var locked by mutableStateOf(false)

    /** Demande le déverrouillage si besoin (empreinte, code…), puis lance le micro. */
    private fun unlockThenListen() {
        if (!keyguard.isKeyguardLocked) {
            locked = false
            listen()
            return
        }
        locked = true
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                Journal.log("déverrouillé")
                locked = false
                listen()
            }

            override fun onDismissCancelled() = Journal.log("déverrouillage annulé")
            override fun onDismissError() = Journal.log("déverrouillage impossible")
        })
    }

    /** Micro activé par défaut à l'ouverture. */
    private fun listen() {
        if (!vm.prefs.loggedIn) {
            vm.notice = "Connecte-toi d'abord dans l'appli (bouton ↗)"
            return
        }
        if (!vm.recording && !vm.busy && !vm.transcribing) toggleMic()
    }

    private fun toggleMic() {
        if (vm.recording || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.toggleRecording()
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        Journal.log("fenêtre assistant visible")
        vm.uploadJournal()
    }

    override fun onStop() {
        super.onStop()
        Journal.log("fenêtre assistant en arrière-plan (écran allumé : ${getSystemService(PowerManager::class.java).isInteractive})")
        vm.speaker.stop()
        if (vm.recording) vm.toggleRecording()
        // Écran éteint : on ferme, le prochain appui long repart d'une fenêtre neuve.
        // (Pas pour un simple passage à la galerie ou à l'appareil photo, qui reviennent ici.)
        if (!getSystemService(PowerManager::class.java).isInteractive) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        Journal.log("fenêtre assistant détruite (fermée : $isFinishing)")
        if (isFinishing) ScreenCapture.clear()
    }
}

/** Feuille affichée sur l'écran de verrouillage, sans rien de la conversation. */
@Composable
private fun LockedSheet(onUnlock: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.navigationBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Déverrouille le téléphone pour parler à l'assistant")
                Button(onClick = onUnlock) { Text("Déverrouiller") }
                androidx.compose.material3.TextButton(onClick = onClose) { Text("Fermer") }
            }
        }
    }
}

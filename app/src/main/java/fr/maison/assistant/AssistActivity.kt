package fr.maison.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import fr.maison.assistant.ui.AssistScreen

/**
 * Fenêtre ouverte par l'appui long : demi-écran par-dessus l'appli en cours, micro lancé d'office.
 * La capture d'écran faite par Android à l'appel reste disponible tant que la fenêtre est ouverte.
 */
class AssistActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleRecording() else vm.notice = "Le micro est nécessaire pour parler à l'assistant"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = MainActivity.Palette) {
                AssistScreen(
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
        if (savedInstanceState == null) listen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        listen() // nouvel appui long alors que la fenêtre était encore ouverte
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

    override fun onStop() {
        super.onStop()
        vm.speaker.stop()
        if (vm.recording) vm.toggleRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) ScreenCapture.clear()
    }
}

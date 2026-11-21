package fr.maison.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import fr.maison.assistant.ui.ChatScreen
import fr.maison.assistant.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Journal.log("appli ouverte (restaurée : ${savedInstanceState != null})")
        forgetOldCapture(intent)
        openFromAlert(intent)
        askNotifications()
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = Palette) {
                var settings by rememberSaveable { mutableStateOf(!vm.prefs.loggedIn) }
                if (settings) SettingsScreen(vm, onClose = { settings = false })
                else ChatScreen(vm, onSettings = { settings = true }, hasMicPermission = ::hasMic)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forgetOldCapture(intent)
        openFromAlert(intent)
    }

    /** Notification d'alerte touchée : on ouvre la conversation concernée. */
    private fun openFromAlert(intent: Intent?) {
        val cid = intent?.getIntExtra(EXTRA_CONVERSATION, 0) ?: 0
        if (cid == 0) return
        intent?.removeExtra(EXTRA_CONVERSATION)
        Journal.log("ouverture depuis une alerte : conversation $cid")
        vm.openConversation(cid)
    }

    /** Android 13+ : les alertes de la veille ont besoin du droit d'afficher des notifications. */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && vm.prefs.loggedIn &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    /** Ouverture normale (icône, retour à l'appli) : une capture d'un appel précédent n'est plus proposée. */
    private fun forgetOldCapture(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_FROM_ASSIST, false) != true) ScreenCapture.clear()
    }

    override fun onResume() {
        super.onResume()
        Journal.log("appli visible")
        vm.syncAlertKey()
        vm.syncConversation()
        vm.warm()
        vm.uploadJournal()
    }

    override fun onStop() {
        super.onStop()
        Journal.log("appli en arrière-plan")
        vm.speaker.stop() // l'appli passe en arrière-plan : on se tait
    }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_FROM_ASSIST = "from_assist"
        const val EXTRA_CONVERSATION = "conversation"

        val Palette = darkColorScheme(
            primary = Color(0xFF7C6CFF),
            onPrimary = Color.White,
            secondary = Color(0xFF8F81FF),
            background = Color(0xFF0E1016),
            surface = Color(0xFF161923),
            surfaceVariant = Color(0xFF1D2130),
            onBackground = Color(0xFFE8EAF1),
            onSurface = Color(0xFFE8EAF1),
            onSurfaceVariant = Color(0xFF9AA1B5),
            outline = Color(0xFF2A2F40),
            error = Color(0xFFFF6B6B),
        )
    }
}

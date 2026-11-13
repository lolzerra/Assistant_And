package fr.maison.assistant.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.compose.LifecycleResumeEffect
import fr.maison.assistant.NotifService
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.maison.assistant.BuildConfig
import fr.maison.assistant.ChatViewModel
import fr.maison.assistant.Updater
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: ChatViewModel, onClose: () -> Unit) {
    val p = vm.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(p.server) }
    var username by remember { mutableStateOf(p.username) }
    var password by remember { mutableStateOf("") }
    var loggedIn by remember { mutableStateOf(p.loggedIn) }
    var model by remember { mutableStateOf(p.model) }
    var think by remember { mutableStateOf(p.think) }
    var web by remember { mutableStateOf(p.web) }
    var speak by remember { mutableStateOf(p.speak) }
    var voice by remember { mutableStateOf(p.voice) }
    var autoSend by remember { mutableStateOf(p.sendAfterDictation) }
    var newOnShortcut by remember { mutableStateOf(p.newOnShortcut) }
    var instructions by remember { mutableStateOf(p.instructions) }
    var status by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<Updater.Release?>(null) }

    fun save() {
        p.model = model
        p.think = think
        p.web = web
        p.speak = speak
        p.sendAfterDictation = autoSend
        p.newOnShortcut = newOnShortcut
        p.instructions = instructions
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loggedIn) IconButton(onClick = { save(); onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                Text("Réglages", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            }

            Section("Serveur et compte")
            OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Adresse du site") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            if (loggedIn) {
                Text("Connecté en tant que ${p.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { vm.logout(); loggedIn = false }) { Text("Se déconnecter") }
            } else {
                OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Identifiant") }, singleLine = true)
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Mot de passe") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Button(onClick = {
                    scope.launch {
                        status = "Connexion…"
                        runCatching { vm.login(server, username.trim(), password) }
                            .onSuccess { password = ""; loggedIn = true; status = "Connecté ✓" }
                            .onFailure { status = "⚠ ${it.message}" }
                    }
                }) { Text("Se connecter") }
            }

            Section("Autorisations")
            Permissions()

            Section("Assistant du téléphone")
            Text("Pour l'ouvrir par appui long : Applis par défaut → Assistant numérique → Assistant. " +
                "Active aussi « Utiliser une capture d'écran » pour pouvoir lui montrer l'écran.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Ouvrir les réglages d'Android") }

            Section("Comportement")
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Modèle") }, singleLine = true)
            Toggle("Réflexion avant de répondre (plus lent)", think) { think = it }
            Toggle("Recherche sur internet", web) { web = it }
            Toggle("Lire les réponses à voix haute", speak) { speak = it }
            VoicePicker(voice) { chosen ->
                voice = chosen
                p.voice = chosen  // tout de suite, pour l'écouter
                vm.speaker.speak("Bonjour, je suis ton assistant. Voici ma voix.")
            }
            Toggle("Envoyer dès la fin de la dictée", autoSend) { autoSend = it }
            Toggle("Nouvelle conversation à l'appel par raccourci", newOnShortcut) { newOnShortcut = it }
            OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth(), minLines = 4,
                label = { Text("Consignes (personnalité, style, longueur…)") })

            Section("Mises à jour")
            Text("Version installée : ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        status = "Recherche d'une mise à jour…"
                        runCatching { Updater.latest() }
                            .onSuccess { r ->
                                update = r?.takeIf { Updater.isNewer(it.version, BuildConfig.VERSION_NAME) }
                                status = when {
                                    r == null -> "Aucune version publiée pour l'instant"
                                    update != null -> "Version ${r.version} disponible"
                                    else -> "Déjà à jour (${r.version})"
                                }
                            }
                            .onFailure { status = "⚠ ${it.message}" }
                    }
                }) { Text("Vérifier") }
                update?.let { r ->
                    Button(onClick = {
                        scope.launch {
                            status = "Téléchargement de la ${r.version}…"
                            runCatching { Updater.install(context, r) }.onFailure { status = "⚠ ${it.message}" }
                        }
                    }) { Text("Installer la ${r.version}") }
                }
            }

            status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (loggedIn) Button(onClick = { save(); onClose() }, Modifier.fillMaxWidth()) { Text("Enregistrer") }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outline)
    Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

private val VOICES = listOf(
    "tom" to "Tom", "pierre" to "Pierre", "gilles" to "Gilles", "siwis" to "Siwis", "jessica" to "Jessica",
)

/** Choix de la voix : un appui la sélectionne et la fait entendre. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicePicker(selected: String, onPick: (String) -> Unit) {
    Column {
        Text("Voix (appuie pour l'écouter)", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VOICES.forEach { (id, label) ->
                FilterChip(selected = id == selected, onClick = { onPick(id) }, label = { Text(label) })
            }
        }
    }
}

/** Autorisations spéciales d'Android, à activer dans ses réglages ; l'état se met à jour au retour dans l'appli. */
@Composable
private fun Permissions() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    @Suppress("UNUSED_EXPRESSION") tick  // relit l'état à chaque retour
    val nm = context.getSystemService(NotificationManager::class.java)
    fun open(intent: Intent) = context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    PermissionRow("Accès aux notifications", "lire et répondre aux messages, piloter la musique", NotifService.enabled(context)) {
        open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    PermissionRow("Ne pas déranger", "l'activer ou le couper", nm.isNotificationPolicyAccessGranted) {
        open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }
    PermissionRow("Modifier les paramètres système", "régler la luminosité", Settings.System.canWrite(context)) {
        open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)))
    }
    Text("Contacts, appels et SMS sont demandés au premier usage.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PermissionRow(title: String, what: String, granted: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(if (granted) "✓ Autorisé · $what" else what, fontSize = 13.sp,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onOpen) { Text(if (granted) "Gérer" else "Autoriser") }
    }
}

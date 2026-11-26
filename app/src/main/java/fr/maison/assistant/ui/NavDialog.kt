package fr.maison.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.maison.assistant.ChatViewModel
import fr.maison.assistant.Music
import fr.maison.assistant.NavMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** ⋮ → Mode navigation : destination, appli (Maps ou Waze, retenue), playlist du lecteur. */
@Composable
fun NavDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var destination by remember { mutableStateOf("") }
    var app by remember { mutableStateOf(vm.prefs.navApp) }
    var playlists by remember { mutableStateOf<List<String>?>(null) }
    var playlist by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { playlists = withContext(Dispatchers.IO) { Music.playlistNames(context) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🚗 Mode navigation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Le Bluetooth et la localisation seront demandés à l'activation.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(destination, { destination = it }, label = { Text("Destination") },
                    placeholder = { Text("Adresse, lieu, commerce…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(app == "maps", { app = "maps" }, label = { Text("Google Maps") })
                    FilterChip(app == "waze", { app = "waze" }, label = { Text("Waze") })
                }
                Text("Playlist", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val list = playlists
                if (list == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                    Text("Lecture des playlists…", fontSize = 13.sp)
                } else Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        item { Choice("Pas de musique", playlist == null) { playlist = null } }
                        items(list) { name -> Choice(name, playlist == name) { playlist = name } }
                    }
                }
                if (list != null && list.isEmpty()) Text("Aucune playlist trouvée dans App Streaming.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); vm.startNavigation(destination.trim(), app, playlist) }) { Text("Démarrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label)
    }
}

/** Bandeau tant que le mode navigation est actif. */
@Composable
fun NavBanner(vm: ChatViewModel) {
    if (!NavMode.active) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🚗 Mode navigation", fontSize = 13.sp, modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(onClick = vm::stopNavigation) { Text("Quitter") }
        }
    }
}

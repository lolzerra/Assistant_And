package fr.maison.assistant.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.maison.assistant.Api
import fr.maison.assistant.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Réglages → Mémoire : interrupteur (gardé sur le serveur, commun avec le site) et gestion des souvenirs. */
@Composable
fun MemorySettings(vm: ChatViewModel) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    var count by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(open) {
        if (!open && vm.prefs.loggedIn) runCatching { vm.api.memory() }
            .onSuccess { (on, list) -> enabled = on; count = list.size }
            .onFailure { error = it.message }
    }

    Text("Ce que les modèles retiennent de toi d'une conversation à l'autre (commun avec le site). " +
        "Seuls tes messages et leurs réponses sont relus, jamais tes notifications ni tes documents.",
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Mémoire activée", Modifier.weight(1f))
        Switch(checked = enabled == true, enabled = enabled != null, onCheckedChange = { on ->
            enabled = on
            scope.launch { runCatching { vm.api.memoryEnabled(on) }.onFailure { enabled = !on; error = it.message } }
        })
    }
    OutlinedButton(onClick = { open = true }, enabled = enabled != null) {
        Text("Voir la mémoire ($count souvenir${if (count > 1) "s" else ""})")
    }
    error?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
    if (open) MemoryDialog(vm) { open = false }
}

@Composable
private fun MemoryDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<Api.Memory>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf("") }
    var addingTitle by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Api.Memory?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(query, reload) {
        delay(250)
        runCatching { vm.api.memory(query.trim()).second }.onSuccess { list = it; error = null }.onFailure { error = it.message }
    }
    fun act(block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onSuccess { reload++ }.onFailure { error = it.message }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🧠 Mémoire") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Rechercher") })
                OutlinedTextField(addingTitle, { addingTitle = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Titre du nouveau souvenir") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(adding, { adding = it.take(300) }, Modifier.weight(1f), label = { Text("Description") })
                    TextButton(enabled = adding.isNotBlank(), onClick = {
                        val title = addingTitle.trim()
                        val text = adding.trim()
                        adding = ""
                        addingTitle = ""
                        act { vm.api.memoryAdd(title, text) }
                    }) { Text("Ajouter") }
                }
                error?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                val items = list
                when {
                    items == null -> Text("Chargement…")
                    items.isEmpty() -> Text(if (query.isBlank()) "Rien pour l'instant : elle se remplit au fil des conversations." else "Aucun résultat.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(items, key = { it.id }) { m ->
                            Row(Modifier.fillMaxWidth().clickable { editing = m }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    if (m.title.isNotBlank()) Text(m.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(m.text, fontSize = 14.sp)
                                    Text(DateUtils.getRelativeTimeSpanString(m.updated).toString() + " · utilisé ${m.uses} fois", fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { act { vm.api.memoryDelete(m.id) } }) { Icon(Icons.Default.Close, "Oublier") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )

    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.text) }
        var title by remember(m.id) { mutableStateOf(m.title) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Modifier le souvenir") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Titre") })
                    OutlinedTextField(text, { text = it.take(300) }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("Description") })
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { editing = null; act { vm.api.memoryEdit(m.id, title.trim(), text.trim()) } }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuler") } },
        )
    }
}

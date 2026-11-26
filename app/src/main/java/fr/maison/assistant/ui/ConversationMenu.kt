package fr.maison.assistant.ui

import android.content.Intent
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
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import kotlinx.coroutines.delay
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.maison.assistant.Api
import fr.maison.assistant.ChatViewModel
import kotlinx.coroutines.launch

/** Bouton ⋮ de l'en-tête : enregistrer, partager, rouvrir une conversation enregistrée. */
@Composable
fun ConversationMenu(vm: ChatViewModel) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var navigating by remember { mutableStateOf(false) }

    IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Plus") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text("Enregistrer la conversation") },
            leadingIcon = { Icon(Icons.Default.Save, null) },
            enabled = vm.messages.isNotEmpty(),
            onClick = { open = false; saving = true },
        )
        DropdownMenuItem(
            text = { Text("Partager") },
            leadingIcon = { Icon(Icons.Default.Share, null) },
            enabled = vm.messages.isNotEmpty(),
            onClick = {
                open = false
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.transcript())
                context.startActivity(Intent.createChooser(send, "Partager la conversation").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
        DropdownMenuItem(
            text = { Text("Conversations enregistrées") },
            leadingIcon = { Icon(Icons.Default.Bookmarks, null) },
            onClick = { open = false; browsing = true },
        )
        DropdownMenuItem(
            text = { Text(if (fr.maison.assistant.NavMode.active) "Quitter le mode navigation" else "Mode navigation") },
            leadingIcon = { Icon(Icons.Default.DirectionsCar, null) },
            onClick = {
                open = false
                if (fr.maison.assistant.NavMode.active) vm.stopNavigation() else navigating = true
            },
        )
    }

    if (saving) SaveDialog(onDismiss = { saving = false }) { where -> saving = false; vm.saveConversation(where) }
    if (browsing) SavedDialog(vm, onDismiss = { browsing = false })
    if (navigating) NavDialog(vm, onDismiss = { navigating = false })
}

@Composable
private fun SaveDialog(onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enregistrer la conversation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(Icons.Default.PhoneAndroid, "Uniquement dans l'assistant",
                    "Retrouvée dans ⋮ → Conversations enregistrées") { onChoose("appli") }
                Choice(Icons.Default.Language, "Dans l'assistant et sur le site",
                    "Apparaît aussi dans la liste du site, à reprendre avec le modèle de ton choix") { onChoose("site") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun Choice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, fontSize = 15.sp)
                Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SavedDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }  // 0 : Assistant, 1 : Globales
    var query by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<Api.Saved>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tab, query) {
        if (tab == 1) delay(250)
        error = null
        runCatching { if (tab == 0) vm.savedList() else vm.allList(query.trim()) }
            .onSuccess { list = it }.onFailure { error = it.message ?: "Erreur" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conversations enregistrées") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { if (tab != 0) { tab = 0; list = null } }, text = { Text("Assistant") })
                    Tab(selected = tab == 1, onClick = { if (tab != 1) { tab = 1; list = null } }, text = { Text("Globales") })
                }
                if (tab == 1) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Rechercher un titre") })
                val items = list
                when {
                    error != null -> Text("⚠ $error", color = MaterialTheme.colorScheme.error)
                    items == null -> Text("Chargement…")
                    items.isEmpty() -> Text(
                        if (tab == 0) "Aucune pour l'instant : ⋮ → Enregistrer la conversation."
                        else if (query.isBlank()) "Aucune conversation." else "Aucun titre ne correspond.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(items, key = { it.id }) { c ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onDismiss(); vm.openConversation(c.id) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    val icon = when (c.type) { "veille" -> "📰 "; "contexte" -> if (c.title.startsWith("🧭")) "" else "🧭 "; else -> "" }
                                    Text(icon + c.title, fontSize = 15.sp, maxLines = 2)
                                    val ago = DateUtils.getRelativeTimeSpanString(c.updated).toString()
                                    val where = when {
                                        tab == 0 -> if (c.site) " · aussi sur le site" else ""
                                        c.site -> " · site"
                                        else -> " · assistant"
                                    }
                                    Text("$ago · ${c.count} messages$where" + if (tab == 1 && c.model.isNotBlank()) " · ${c.model}" else "",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                // Assistant : retire de cette liste ; une conversation partagée avec le site y reste.
                                if (tab == 0) IconButton(onClick = {
                                    scope.launch {
                                        runCatching { vm.forget(c.id) }
                                            .onSuccess { list = list?.filter { it.id != c.id } }
                                            .onFailure { error = it.message }
                                    }
                                }) { Icon(Icons.Default.Close, "Retirer de la liste") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

package fr.maison.assistant.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import fr.maison.assistant.Images
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import fr.maison.assistant.ChatViewModel
import fr.maison.assistant.Msg
import fr.maison.assistant.ScreenCapture
import java.io.File

@Composable
fun ChatScreen(vm: ChatViewModel, onSettings: () -> Unit, hasMicPermission: () -> Boolean) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val screenshot by ScreenCapture.file.collectAsState()
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var imageMenu by remember { mutableStateOf(false) }

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        uris.forEach { vm.attachUri(it) }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null) vm.attachFile(f, "Photo")
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleRecording() else vm.notice = "Le micro est nécessaire pour dicter"
    }

    // Suivi du bas de la réponse, sauf si l'utilisateur remonte pour lire : on reprend quand il revient en bas.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) follow = !listState.canScrollForward
    }
    LaunchedEffect(vm.messages.size) { follow = true }
    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length, vm.messages.lastOrNull()?.steps?.size) {
        if (follow && vm.messages.isNotEmpty() && !listState.isScrollInProgress) {
            listState.scrollToItem(vm.messages.lastIndex)
            listState.scrollBy(100_000f) // jusqu'au bas du dernier message, quelle que soit sa longueur
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
            // En-tête
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Assistant", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
                IconButton(onClick = { vm.speaker.stop() }) { Icon(Icons.Default.VolumeOff, "Couper la voix") }
                IconButton(onClick = vm::newConversation) { Icon(Icons.Default.AddComment, "Nouvelle conversation") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Réglages") }
            }

            // Messages
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (vm.messages.isEmpty()) item {
                    Text("Pose une question, dicte avec 🎤, ou joins une photo ou l'écran.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
                }
                itemsIndexed(vm.messages) { _, m -> Bubble(vm, m) }
            }

            vm.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            // Images choisies, en attente d'envoi
            if (vm.attachments.isNotEmpty()) {
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(vm.attachments) { a ->
                        InputChip(selected = false, onClick = { vm.removeAttachment(a) },
                            label = { a.thumb?.let { Thumb(it, 40.dp) } ?: Text(a.label) },
                            trailingIcon = { Icon(Icons.Default.Close, "Retirer", Modifier.size(16.dp)) })
                    }
                }
            }

            // Saisie
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { imageMenu = true }) {
                        // Point sur l'icône : une capture de l'écran est prête à être jointe
                        BadgedBox(badge = { if (screenshot != null) Badge(containerColor = MaterialTheme.colorScheme.primary) }) {
                            Icon(Icons.Default.AddPhotoAlternate, "Joindre une image")
                        }
                    }
                    DropdownMenu(expanded = imageMenu, onDismissRequest = { imageMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Galerie") },
                            leadingIcon = { Icon(Icons.Default.Image, null) },
                            onClick = {
                                imageMenu = false
                                pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Appareil photo") },
                            leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                            onClick = {
                                imageMenu = false
                                val f = File(context.cacheDir, "photo-${System.currentTimeMillis()}.jpg")
                                cameraFile = f
                                takePhoto.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
                            },
                        )
                        val shot = screenshot
                        DropdownMenuItem(
                            text = { Text(if (shot != null) "Capture d'écran" else "Capture d'écran (appui long pour appeler l'assistant)") },
                            leadingIcon = {
                                val preview = remember(shot) { shot?.let { runCatching { Images.thumb(it.readBytes()) }.getOrNull() } }
                                preview?.let { Thumb(it, 24.dp) } ?: Icon(Icons.Default.Smartphone, null)
                            },
                            enabled = shot != null,
                            onClick = {
                                imageMenu = false
                                shot?.let { vm.attachFile(it, "Ecran") }
                                ScreenCapture.clear()
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = vm.input, onValueChange = { vm.input = it },
                    modifier = Modifier.weight(1f), maxLines = 6,
                    placeholder = { Text(if (vm.transcribing) "Transcription…" else "Message") },
                )
                Spacer(Modifier.width(4.dp))
                when {
                    vm.busy -> IconButton(onClick = vm::stop) { Icon(Icons.Default.Stop, "Arrêter") }
                    vm.input.isBlank() && vm.attachments.isEmpty() -> IconButton(onClick = {
                        if (vm.recording || hasMicPermission()) vm.toggleRecording() else askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }) {
                        if (vm.transcribing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(if (vm.recording) Icons.Default.StopCircle else Icons.Default.Mic,
                            if (vm.recording) "Arrêter la dictée" else "Dicter",
                            tint = if (vm.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    else -> IconButton(onClick = { vm.send() }) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Envoyer", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(bitmap: ImageBitmap, size: Dp) {
    Image(bitmap, contentDescription = "Image jointe", contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)))
}

/** Miniature d'une image déjà sur le serveur (messages rechargés). */
@Composable
private fun RemoteThumb(vm: ChatViewModel, id: Int, size: Dp) {
    val bitmap by produceState<ImageBitmap?>(null, id) {
        value = runCatching { Images.thumb(vm.api.image(id)) }.getOrNull()
    }
    bitmap?.let { Thumb(it, size) } ?: Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable
private fun Bubble(vm: ChatViewModel, m: Msg) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.user) Alignment.End else Alignment.Start) {
        if (m.user) {
            Column(Modifier.widthIn(max = 320.dp).background(colors.primary, RoundedCornerShape(14.dp)).padding(10.dp)) {
                if (m.text.isNotEmpty()) Text(m.text, color = colors.onPrimary)
                if (m.thumbs.isNotEmpty() || m.imageIds.isNotEmpty()) {
                    Row(Modifier.padding(top = if (m.text.isNotEmpty()) 6.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        m.thumbs.forEach { Thumb(it, 64.dp) }
                        m.imageIds.forEach { RemoteThumb(vm, it, 64.dp) }
                    }
                }
            }
        } else {
            if (m.pending) {
                m.steps.forEach { Text(it, color = colors.onSurfaceVariant, fontSize = 13.sp) }
            } else if (m.steps.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Text("${if (open) "▾" else "▸"} 🔎 Recherches (${m.steps.size})", color = colors.onSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.clickable { open = !open }.padding(vertical = 2.dp))
                if (open) m.steps.forEach { Text(it, color = colors.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(start = 14.dp)) }
            }
            if (m.text.isEmpty() && m.pending) Text("…", color = colors.onSurfaceVariant)
            SelectionContainer { Text(Markdown.render(m.text), color = colors.onBackground) }
            m.error?.let { Text("⚠ $it", color = colors.error, fontSize = 13.sp) }
            m.stats?.let { Text(it, color = colors.onSurfaceVariant, fontSize = 11.sp) }
        }
    }
}

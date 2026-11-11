package fr.maison.assistant.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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

    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
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
                itemsIndexed(vm.messages) { _, m -> Bubble(m) }
            }

            vm.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            // Pièces jointes : capture d'écran proposée, puis images choisies
            if (screenshot != null || vm.attachments.isNotEmpty()) {
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    screenshot?.let { f ->
                        item {
                            AssistChip(onClick = { vm.attachFile(f, "Ecran"); ScreenCapture.clear() },
                                label = { Text("📱 Joindre l'écran") })
                        }
                    }
                    items(vm.attachments) { a ->
                        InputChip(selected = false, onClick = { vm.removeAttachment(a) }, label = { Text("🖼 ${a.label}") },
                            trailingIcon = { Icon(Icons.Default.Close, "Retirer", Modifier.size(16.dp)) })
                    }
                }
            }

            // Saisie
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Default.Image, "Joindre une photo")
                }
                IconButton(onClick = {
                    val f = File(context.cacheDir, "photo-${System.currentTimeMillis()}.jpg")
                    cameraFile = f
                    takePhoto.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
                }) { Icon(Icons.Default.PhotoCamera, "Prendre une photo") }
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
private fun Bubble(m: Msg) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.user) Alignment.End else Alignment.Start) {
        if (m.user) {
            Column(Modifier.widthIn(max = 320.dp).background(colors.primary, RoundedCornerShape(14.dp)).padding(10.dp)) {
                if (m.images > 0) Text("🖼 ${m.images} image${if (m.images > 1) "s" else ""}", color = colors.onPrimary, fontSize = 13.sp)
                if (m.text.isNotEmpty()) Text(m.text, color = colors.onPrimary)
            }
        } else {
            m.steps.forEach { Text(it, color = colors.onSurfaceVariant, fontSize = 13.sp) }
            if (m.text.isEmpty() && m.pending) Text("…", color = colors.onSurfaceVariant)
            SelectionContainer { Text(Markdown.render(m.text), color = colors.onBackground) }
            m.error?.let { Text("⚠ $it", color = colors.error, fontSize = 13.sp) }
            m.stats?.let { Text(it, color = colors.onSurfaceVariant, fontSize = 11.sp) }
        }
    }
}

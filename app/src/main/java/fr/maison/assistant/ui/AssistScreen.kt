package fr.maison.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import fr.maison.assistant.ChatViewModel
import fr.maison.assistant.Images
import fr.maison.assistant.ScreenCapture
import kotlinx.coroutines.delay
import java.io.File

/** Demi-écran de l'assistant : l'appli en cours reste visible au-dessus, avec « Joindre une capture ». */
@Composable
fun AssistScreen(vm: ChatViewModel, onClose: () -> Unit, onOpenApp: () -> Unit, onMic: () -> Unit) {
    val screenshot by ScreenCapture.file.collectAsState()
    var keyboard by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sheetHeight = maxHeight * 0.5f
        Column(Modifier.fillMaxSize()) {
            // Haut transparent : l'écran de l'appli en dessous. Un appui ferme l'assistant.
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
                contentAlignment = Alignment.BottomCenter,
            ) {
                screenshot?.let { shot ->
                    val attached = vm.attachments.any { it.label == "Ecran" }
                    val preview = remember(shot) { runCatching { Images.thumb(shot.readBytes()) }.getOrNull() }
                    FilledTonalButton(onClick = { if (!attached) vm.attachFile(shot, "Ecran") }, modifier = Modifier.padding(12.dp)) {
                        preview?.let { Thumb(it, 24.dp) }
                        Spacer(Modifier.width(8.dp))
                        Text(if (attached) "Capture jointe ✓" else "Joindre une capture")
                    }
                }
            }
            Surface(
                Modifier.fillMaxWidth().height(sheetHeight),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    Header(vm, onOpenApp)
                    Messages(vm, Modifier.weight(1f))
                    vm.notice?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    Attachments(vm)
                    if (keyboard) KeyboardBar(vm, onMic = onMic, onVoice = { keyboard = false })
                    else VoiceBar(vm, onMic = onMic, onKeyboard = { keyboard = true })
                }
            }
        }
    }
}

@Composable
private fun Header(vm: ChatViewModel, onOpenApp: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Assistant", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        IconButton(onClick = { vm.speaker.stop() }) { Icon(Icons.Default.VolumeOff, "Couper la voix") }
        ConversationMenu(vm)
        IconButton(onClick = vm::newConversation) { Icon(Icons.Default.AddComment, "Nouvelle conversation") }
        IconButton(onClick = onOpenApp) { Icon(Icons.Default.OpenInFull, "Ouvrir l'appli") }
    }
}

@Composable
private fun Messages(vm: ChatViewModel, modifier: Modifier) {
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) follow = !listState.canScrollForward
    }
    LaunchedEffect(vm.messages.size) { follow = true }
    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length) {
        if (follow && vm.messages.isNotEmpty() && !listState.isScrollInProgress) {
            listState.scrollToItem(vm.messages.lastIndex)
            listState.scrollBy(100_000f)
        }
    }
    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(vm.messages) { _, m -> Bubble(vm, m) }
    }
}

@Composable
private fun Attachments(vm: ChatViewModel) {
    if (vm.attachments.isEmpty()) return
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(vm.attachments) { a ->
            InputChip(selected = false, onClick = { vm.removeAttachment(a) },
                label = { a.thumb?.let { Thumb(it, 36.dp) } ?: Text(a.label) },
                trailingIcon = { Icon(Icons.Default.Close, "Retirer", Modifier.size(16.dp)) })
        }
    }
}

/** Mode voix (par défaut) : grand bouton micro, visualiseur pendant l'écoute, clavier et images sur les côtés. */
@Composable
private fun VoiceBar(vm: ChatViewModel, onMic: () -> Unit, onKeyboard: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onKeyboard) { Icon(Icons.Default.Keyboard, "Écrire") }
            Spacer(Modifier.weight(1f))
            when {
                vm.busy -> FilledIconButton(onClick = vm::stop, modifier = Modifier.size(64.dp), shape = CircleShape) {
                    Icon(Icons.Default.Stop, "Arrêter la réponse", Modifier.size(30.dp))
                }
                vm.transcribing -> Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> FilledIconButton(
                    onClick = onMic, modifier = Modifier.size(64.dp), shape = CircleShape,
                    colors = if (vm.recording) IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
                    else IconButtonDefaults.filledIconButtonColors(),
                ) { Icon(if (vm.recording) Icons.Default.Stop else Icons.Default.Mic, if (vm.recording) "Arrêter l'écoute" else "Parler", Modifier.size(30.dp)) }
            }
            Spacer(Modifier.weight(1f))
            ImageMenu(vm)
        }
        if (vm.recording) Visualizer(vm) else Spacer(Modifier.height(28.dp))
        Text(
            when {
                vm.recording -> "J'écoute…"
                vm.transcribing -> "Transcription…"
                vm.busy -> "Réponse en cours…"
                else -> "Touche le micro pour parler"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
        )
    }
}

/** Petites barres qui suivent le niveau du micro : on voit qu'on est écouté. */
@Composable
private fun Visualizer(vm: ChatViewModel) {
    val levels = remember { mutableStateListOf<Float>().apply { repeat(BARS) { add(0f) } } }
    val level by vm.micLevel.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            levels.removeAt(0)
            levels.add(level)
            delay(70)
        }
    }
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.padding(top = 8.dp).width(160.dp).height(20.dp)) {
        val w = size.width / BARS
        levels.forEachIndexed { i, v ->
            val h = (size.height * (0.12f + 0.88f * v)).coerceAtMost(size.height)
            drawRoundRect(color, topLeft = Offset(i * w + w * 0.2f, (size.height - h) / 2),
                size = Size(w * 0.6f, h), cornerRadius = CornerRadius(w * 0.3f))
        }
    }
}

/** Mode clavier : champ de texte, envoi, retour au micro. */
@Composable
private fun KeyboardBar(vm: ChatViewModel, onMic: () -> Unit, onVoice: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
        ImageMenu(vm)
        OutlinedTextField(vm.input, { vm.input = it }, Modifier.weight(1f), maxLines = 4, placeholder = { Text("Message") })
        when {
            vm.busy -> IconButton(onClick = vm::stop) { Icon(Icons.Default.Stop, "Arrêter") }
            vm.input.isBlank() && vm.attachments.isEmpty() -> IconButton(onClick = { onVoice(); onMic() }) { Icon(Icons.Default.Mic, "Parler") }
            else -> IconButton(onClick = { vm.send() }) { Icon(Icons.AutoMirrored.Filled.Send, "Envoyer", tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun ImageMenu(vm: ChatViewModel) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris -> uris.forEach { vm.attachUri(it) } }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> cameraFile?.let { if (ok) vm.attachFile(it, "Photo") } }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.AddPhotoAlternate, "Joindre une image") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Galerie") }, leadingIcon = { Icon(Icons.Default.Image, null) }, onClick = {
                open = false
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            DropdownMenuItem(text = { Text("Appareil photo") }, leadingIcon = { Icon(Icons.Default.PhotoCamera, null) }, onClick = {
                open = false
                val f = File(context.cacheDir, "photo-${System.currentTimeMillis()}.jpg")
                cameraFile = f
                shoot.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
            })
        }
    }
}

private const val BARS = 24

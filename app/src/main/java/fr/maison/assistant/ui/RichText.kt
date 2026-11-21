package fr.maison.assistant.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.maison.assistant.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Réponse en markdown, avec les images trouvées par image_search (servies par le site) affichées en grand. */
@Composable
fun RichText(vm: ChatViewModel, text: String) {
    val colors = MaterialTheme.colorScheme
    val parts = WebImages.split(text)
    Column {
        parts.forEach { part ->
            when (part) {
                is WebImages.Part.Picture -> WebImage(vm, part.path, part.alt)
                is WebImages.Part.Prose -> if (part.text.isNotBlank())
                    SelectionContainer { Text(Markdown.render(part.text.trim('\n')), color = colors.onBackground) }
            }
        }
    }
}

@Composable
private fun WebImage(vm: ChatViewModel, path: String, alt: String) {
    val bitmap by produceState(WebImages.cache.get(path), path) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { WebImages.decode(vm.api.webImage(path)) }.getOrNull()?.also { WebImages.cache.put(path, it) }
        }
    }
    val shape = RoundedCornerShape(10.dp)
    val modifier = Modifier.padding(vertical = 6.dp).widthIn(max = 340.dp).clip(shape)
    bitmap?.let {
        Image(it, contentDescription = alt, contentScale = ContentScale.Fit, modifier = modifier.heightIn(max = 360.dp))
    } ?: Box(modifier.fillMaxWidth().height(180.dp).background(MaterialTheme.colorScheme.surfaceVariant, shape)) {
        Text("🖼️ $alt", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
    }
}

object WebImages {
    sealed class Part {
        data class Prose(val text: String) : Part()
        data class Picture(val alt: String, val path: String) : Part()
    }

    private val pattern = Regex("""!\[([^\]]*)]\((/api/web_images/[0-9a-f]{20}\.jpg)\)""")
    val cache = LruCache<String, ImageBitmap>(24)

    fun split(text: String): List<Part> {
        val out = mutableListOf<Part>()
        var pos = 0
        for (m in pattern.findAll(text)) {
            out += Part.Prose(text.substring(pos, m.range.first))
            out += Part.Picture(m.groupValues[1], m.groupValues[2])
            pos = m.range.last + 1
        }
        out += Part.Prose(text.substring(pos))
        return out
    }

    /** Décodage réduit (≈ 1024 px) : assez net pour l'écran, léger en mémoire. */
    fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
}

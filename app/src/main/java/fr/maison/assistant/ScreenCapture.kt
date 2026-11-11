package fr.maison.assistant

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Dernière capture d'écran reçue de l'assistant Android, partagée avec l'écran de discussion. */
object ScreenCapture {
    private val _file = MutableStateFlow<File?>(null)
    val file: StateFlow<File?> = _file

    fun save(context: Context, bitmap: Bitmap) {
        val out = File(context.cacheDir, "capture-ecran.jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        _file.value = out
    }

    fun clear() {
        _file.value = null
    }
}

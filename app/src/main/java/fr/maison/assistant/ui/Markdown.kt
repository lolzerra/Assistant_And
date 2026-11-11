package fr.maison.assistant.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Markdown simple pour les réponses : titres, listes, gras, italique, code. Les tableaux restent en texte. */
object Markdown {
    private val inline = Regex("""\*\*(.+?)\*\*|`([^`]+)`|(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?!\*)|\[([^\]]+)]\([^)]*\)""")
    private val codeBg = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF1D2130))

    fun render(text: String): AnnotatedString = buildAnnotatedString {
        var inCode = false
        text.lines().forEachIndexed { i, raw ->
            if (i > 0) append('\n')
            if (raw.trimStart().startsWith("```")) {
                inCode = !inCode
                return@forEachIndexed
            }
            if (inCode) {
                withStyle(codeBg) { append(raw) }
                return@forEachIndexed
            }
            val heading = Regex("^#{1,6}\\s+").find(raw)
            val bullet = Regex("^(\\s*)[-*+]\\s+").find(raw)
            when {
                heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inlines(raw.substring(heading.range.last + 1)) }
                bullet != null -> {
                    append(bullet.groupValues[1] + "• ")
                    inlines(raw.substring(bullet.range.last + 1))
                }
                else -> inlines(raw)
            }
        }
    }

    private fun AnnotatedString.Builder.inlines(line: String) {
        var pos = 0
        for (m in inline.findAll(line)) {
            append(line.substring(pos, m.range.first))
            val (bold, code, italic, link) = m.destructured
            when {
                bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
                code.isNotEmpty() -> withStyle(codeBg) { append(code) }
                italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(italic) }
                else -> withStyle(SpanStyle(color = Color(0xFF8F81FF))) { append(link) }
            }
            pos = m.range.last + 1
        }
        append(line.substring(pos))
    }
}

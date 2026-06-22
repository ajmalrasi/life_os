package com.lifeos.app.presentation.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.lifeos.app.presentation.theme.LifeOsColors

/**
 * Minimal, dependency-free Markdown renderer for chat replies.
 *
 * The grounded-chat LLM answers in Markdown (bold, bullets, the odd heading), but
 * the bubble used a plain [Text], so users saw raw `**` and `-` characters. This
 * renders the common subset Compose can express without pulling in a Markdown
 * library: headings, bullet/numbered lists, blank-line spacing, and inline
 * bold, italic (either marker), and inline code. Anything it doesn't recognize is shown
 * verbatim, so output is never worse than plain text.
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val codeBg = LifeOsColors.SurfaceMuted
    val lines = text.replace("\r\n", "\n").split("\n")

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (raw in lines) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(2.dp))

                line == "---" || line == "***" || line == "___" ->
                    Spacer(Modifier.height(2.dp))

                line.startsWith("### ") -> Text(
                    inline(line.removePrefix("### ").trim(), codeBg),
                    color = color,
                    style = baseStyle.copy(fontWeight = FontWeight.Bold),
                )

                line.startsWith("## ") -> Text(
                    inline(line.removePrefix("## ").trim(), codeBg),
                    color = color,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )

                line.startsWith("# ") -> Text(
                    inline(line.removePrefix("# ").trim(), codeBg),
                    color = color,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )

                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") ->
                    BulletRow("•", inline(line.drop(2).trim(), codeBg), color, baseStyle)

                NUMBERED.matches(line) -> {
                    val dot = line.indexOf(". ")
                    BulletRow(
                        line.substring(0, dot + 1),
                        inline(line.substring(dot + 2).trim(), codeBg),
                        color,
                        baseStyle,
                    )
                }

                else -> Text(inline(line, codeBg), color = color, style = baseStyle)
            }
        }
    }
}

@Composable
private fun BulletRow(marker: String, content: AnnotatedString, color: Color, style: TextStyle) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(marker, color = color, style = style)
        Text(content, color = color, style = style, modifier = Modifier.weight(1f))
    }
}

private val NUMBERED = Regex("""^\d+\.\s+.+""")

/** Parse inline emphasis into a styled [AnnotatedString]. */
private fun inline(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        when {
            // **bold**
            c == '*' && i + 1 < n && text[i + 1] == '*' -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(c); i++
                }
            }
            // *italic* or _italic_
            (c == '*' || c == '_') -> {
                val end = text.indexOf(c, i + 1)
                if (end != -1 && end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(c); i++
                }
            }
            // `code`
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(c); i++
                }
            }
            else -> {
                append(c); i++
            }
        }
    }
}

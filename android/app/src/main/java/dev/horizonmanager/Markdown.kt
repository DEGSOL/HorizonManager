package dev.horizonmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.commonmark.node.*
import org.commonmark.parser.Parser

private val parser = Parser.builder().build()
internal fun isWeb(url: String) = url.startsWith("https://", true) || url.startsWith("http://", true)

private fun children(node: Node): Sequence<Node> = generateSequence(node.firstChild) { it.next }

internal fun markdownBlocks(markdown: String): List<Node> = children(parser.parse(markdown)).toList()

@Composable
internal fun Markdown(markdown: String) {
    val blocks = remember(markdown) { markdownBlocks(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        blocks.forEach { MarkdownBlock(it) }
    }
}

@Composable
internal fun MarkdownBlock(node: Node) {
    val linkColor = LocalHorizonPalette.current.link
    val uri = LocalUriHandler.current
    when (node) {
        is BulletList, is OrderedList, is ListItem, is BlockQuote -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { children(node).forEach { MarkdownBlock(it) } }
        }
        is ThematicBreak -> androidx.compose.material3.HorizontalDivider()
        is HtmlBlock -> Text(node.literal)
        else -> {
            val style = if (node is Heading && node.level <= 2) MaterialTheme.typography.titleLarge
                else if (node is Heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
            val text = buildAnnotatedString {
                fun inline(value: Node) {
                    when (value) {
                        is org.commonmark.node.Text -> append(value.literal)
                        is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(value.literal) }
                        is FencedCodeBlock -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(value.literal) }
                        is IndentedCodeBlock -> append(value.literal)
                        is HardLineBreak, is SoftLineBreak -> append("\n")
                        is HtmlInline -> append(value.literal)
                        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children(value).forEach(::inline) }
                        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children(value).forEach(::inline) }
                        is Link -> {
                            if (isWeb(value.destination)) {
                                val link = LinkAnnotation.Url(value.destination,
                                    TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) {
                                    runCatching { uri.openUri(value.destination) }
                                }
                                withLink(link) { children(value).forEach(::inline) }
                            } else children(value).forEach(::inline)
                        }
                        else -> children(value).forEach(::inline)
                    }
                }
                if (node.parent is ListItem) {
                    val listItem = node.parent
                    if (node == listItem.firstChild) {
                        val list = listItem?.parent
                        if (list is OrderedList) append("${list.markerStartNumber + children(list).takeWhile { it != listItem }.count()}. ")
                        else append("• ")
                    }
                }
                inline(node)
            }
            Text(text, style = style)
        }
    }
}

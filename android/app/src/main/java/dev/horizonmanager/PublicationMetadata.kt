package dev.horizonmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.net.URI
import java.text.NumberFormat
import java.util.Locale

internal fun publicationName(article: JSONObject): String {
    val channel = telegramPublisher(article)
    if (channel.startsWith('@')) return "· ${channel.drop(1)}"
    return articleField(article, "publisher").ifBlank {
        if (isTelegram(article)) "" else
            runCatching { URI(articleField(article, "url")).host }.getOrNull()
                ?.removePrefix("www.").orEmpty().ifBlank { label(articleField(article, "source")) }
    }
}

@Composable
internal fun PublicationSource(
    article: JSONObject, modifier: Modifier = Modifier, detailed: Boolean = false, ink: CoverInk? = null,
) {
    val source = articleField(article, "source")
    val name = publicationName(article)
    val title = if (detailed) listOf(name, articleField(article, "profile"))
        .filter(String::isNotBlank).distinctBy { it.lowercase(Locale.ROOT) }.joinToString(" · ") else name
    val icon = when {
        isTelegram(article) -> R.drawable.ic_source_telegram
        source.equals("rss", true) || source.equals("atom", true) -> R.drawable.ic_source_feed
        else -> R.drawable.ic_source_news
    }
    val base = if (detailed) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val glyph = ImageVector.vectorResource(icon)
        Box(Modifier.size(15.dp)) {
            if (ink != null) Icon(glyph, null, Modifier.matchParentSize().offset(y = 1.dp)
                .blur(2.dp, BlurredEdgeTreatment.Unbounded), tint = ink.shadow.color)
            Icon(glyph, source.takeIf(String::isNotBlank)?.let(::label),
                Modifier.matchParentSize(), tint = ink?.color ?: Color(0xFFDEDFE0))
        }
        if (title.isNotBlank()) Text(title, color = ink?.color ?: Color(0xFFD1D3D5),
            style = base.copy(fontSize = (base.fontSize.value * .78f).sp,
                lineHeight = (base.lineHeight.value * .78f).sp, shadow = ink?.shadow),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal val publicationScoreAccent = Color(0xFFFFCD89)

@Composable
internal fun PublicationScore(article: JSONObject, overPhoto: Boolean = false) {
    val score = article.optDouble("score").takeIf { it in 0.0..10.0 } ?: return
    val locale = appLocale()
    val text = remember(score, locale) {
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(score)
    }
    val surface = if (overPhoto) Color(0xB826221E) else Color.White.copy(alpha = .10f)
    Row(Modifier.background(surface, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(ImageVector.vectorResource(R.drawable.ic_ai_sparkles), null, Modifier.size(12.dp), tint = publicationScoreAccent)
        Text("$text/10", color = publicationScoreAccent, maxLines = 1, softWrap = false,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, lineHeight = 14.sp))
    }
}

package com.matchday.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.SportsBaseball
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SportsTennis
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.SportsFootball
import androidx.compose.material.icons.filled.SportsHockey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.matchday.app.domain.Competition
import com.matchday.app.domain.Confidence
import com.matchday.app.domain.RecentResult
import com.matchday.app.domain.SportType
import com.matchday.app.ui.theme.Accent
import kotlin.math.abs

private val palette = listOf(Accent.Blue, Accent.Purple, Accent.Green, Accent.Orange, Color(0xFF00B8D9), Color(0xFFE55CA3))

fun colorFor(name: String): Color = palette[abs(name.hashCode()) % palette.size]

fun initials(name: String): String {
    val words = name.split(' ', '-').filter { it.isNotBlank() && it.first().isLetter() }
    return when {
        words.isEmpty() -> name.take(2).uppercase()
        words.size == 1 -> words[0].take(3).uppercase()
        else -> (words[0].take(1) + words[1].take(1) + (words.getOrNull(2)?.take(1) ?: "")).uppercase()
    }
}

@Composable
fun TeamBadge(name: String, crest: String?, size: Dp = 40.dp) {
    val fallback: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(colorFor(name), colorFor(name).copy(alpha = 0.55f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initials(name),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.30f).sp,
                maxLines = 1,
            )
        }
    }
    if (crest.isNullOrBlank()) {
        fallback()
    } else {
        SubcomposeAsyncImage(
            model = crest,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size),
            loading = { fallback() },
            error = { fallback() },
        )
    }
}

fun sportIcon(sport: SportType): ImageVector = when (sport) {
    SportType.FOOTBALL -> Icons.Filled.SportsSoccer
    SportType.BASKETBALL -> Icons.Filled.SportsBasketball
    SportType.TENNIS -> Icons.Filled.SportsTennis
    SportType.BASEBALL -> Icons.Filled.SportsBaseball
    SportType.AMERICAN_FOOTBALL -> Icons.Filled.SportsFootball
    SportType.HOCKEY -> Icons.Filled.SportsHockey
    SportType.OTHER -> Icons.Filled.EmojiEvents
}

fun sportColor(sport: SportType): Color = when (sport) {
    SportType.FOOTBALL -> Accent.Green
    SportType.BASKETBALL -> Accent.Orange
    SportType.TENNIS -> Color(0xFFD4E157)
    SportType.BASEBALL -> Accent.Blue
    SportType.AMERICAN_FOOTBALL -> Color(0xFFB07D4F)
    SportType.HOCKEY -> Color(0xFF00B8D9)
    SportType.OTHER -> Accent.Purple
}

@Composable
fun CompetitionBadge(comp: Competition, size: Dp = 18.dp) {
    val icon: @Composable () -> Unit = {
        Icon(sportIcon(comp.sport), contentDescription = null, tint = sportColor(comp.sport), modifier = Modifier.size(size))
    }
    if (comp.emblem == null) icon() else {
        SubcomposeAsyncImage(
            model = comp.emblem,
            contentDescription = comp.title,
            modifier = Modifier.size(size),
            loading = { icon() },
            error = { icon() },
        )
    }
}

@Composable
fun ConfidenceChip(c: Confidence) {
    val (color, label) = when (c) {
        Confidence.HIGH -> Accent.Green to "High"
        Confidence.MEDIUM -> Accent.Orange to "Medium"
        Confidence.LOW -> Accent.Red to "Low"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val bars = when (c) { Confidence.HIGH -> 3; Confidence.MEDIUM -> 2; Confidence.LOW -> 1 }
            for (i in 1..3) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = (4 + i * 3).dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (i <= bars) color else color.copy(alpha = 0.25f))
                        .align(Alignment.Bottom),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/** A bookmaker price box. [highlight] marks the outcome the prediction favours. */
@Composable
fun OddsBox(
    label: String,
    price: Double?,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    previous: Double? = null,
) {
    val border = if (highlight) Accent.Blue else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlight) Accent.Blue.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (price == null) "N/A" else String.format("%.2f", price),
                style = MaterialTheme.typography.titleSmall,
                color = if (price == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            MovementArrow(price, previous)
        }
    }
}

@Composable
fun MovementArrow(price: Double?, previous: Double?) {
    if (price == null || previous == null || abs(price - previous) < 0.005) return
    val up = price > previous
    Icon(
        if (up) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
        contentDescription = if (up) "drifted" else "shortened",
        tint = if (up) Accent.Green else Accent.Red,
        modifier = Modifier.size(18.dp),
    )
}

/** Horizontal stacked probability bar (home / draw / away). */
@Composable
fun ProbabilityBar(home: Double, draw: Double?, away: Double, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val h by animateFloatAsState(if (started) home.toFloat() else 0.33f, tween(600), label = "h")
    val d by animateFloatAsState(if (started) (draw ?: 0.0).toFloat() else 0.33f, tween(600), label = "d")
    val a by animateFloatAsState(if (started) away.toFloat() else 0.33f, tween(600), label = "a")
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50)),
    ) {
        if (h > 0f) Box(Modifier.weight(h).fillMaxWidth().height(height).background(Accent.Blue))
        if (d > 0f) Box(Modifier.weight(d).fillMaxWidth().height(height).background(Color(0xFF7D879E)))
        if (a > 0f) Box(Modifier.weight(a).fillMaxWidth().height(height).background(Accent.Purple))
    }
}

@Composable
fun FormStrip(results: List<RecentResult>, max: Int = 6) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        results.take(max).forEach { r ->
            val c = when (r.letter) { 'W' -> Accent.Green; 'D' -> Color(0xFF7D879E); else -> Accent.Red }
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(c),
                contentAlignment = Alignment.Center,
            ) {
                Text(r.letter.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, accent: Color, count: Int? = null, trailing: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
    ) {
        Box(Modifier.size(width = 4.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(accent))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(accent.copy(alpha = 0.15f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun Banner(text: String, color: Color = Accent.Orange, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(12.dp),
    )
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun LiveDot() {
    Box(Modifier.size(8.dp).clip(CircleShape).background(Accent.Live))
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
    }
}

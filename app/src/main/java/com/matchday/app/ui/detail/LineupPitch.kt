package com.matchday.app.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.matchday.app.domain.LineupInfo
import com.matchday.app.domain.LineupPlayer
import com.matchday.app.ui.components.InfoCard
import com.matchday.app.ui.components.TeamBadge
import com.matchday.app.ui.theme.Accent

/** Rows of the formation from goalkeeper (first) to attack (last), or null when it can't be laid out honestly. */
fun formationRows(l: LineupInfo): Pair<List<List<LineupPlayer>>, Boolean>? {
    val s = l.starters
    if (s.size != 11) return null
    // 1) Exact positions from the source ("row:col").
    val grid = s.map { p -> p.grid?.split(':')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 } }
    if (grid.all { it != null }) {
        val rows = s.zip(grid).groupBy { it.second!![0] }.toSortedMap().values
            .map { row -> row.sortedBy { it.second!![1] }.map { it.first } }
        return rows to true
    }
    // 2) Formation string + listed positions (approximate order within a line).
    val shape = l.formation?.split('-')?.mapNotNull { it.trim().toIntOrNull() }
    if (shape == null || shape.sum() != 10 || shape.any { it <= 0 }) return null
    val gk = s.firstOrNull { it.position?.uppercase()?.startsWith("G") == true } ?: s.minByOrNull { it.order ?: 99 } ?: return null
    val outfield = s.filter { it !== gk }.sortedWith(compareBy({ lineRank(it.position) }, { it.order ?: 99 }))
    val rows = mutableListOf(listOf(gk))
    var i = 0
    for (n in shape) {
        rows += outfield.subList(i, minOf(i + n, outfield.size)).sortedBy { side(it.position) }
        i += n
    }
    return rows to false
}

private fun lineRank(pos: String?): Int {
    val p = pos?.uppercase()?.trim() ?: return 3
    return when {
        p.startsWith("G") -> 0
        p.startsWith("DM") || p == "CDM" -> 2
        p.startsWith("AM") || p == "CAM" -> 4
        p.startsWith("CD") || p.startsWith("D") || p.endsWith("B") || p == "SW" -> 1
        p.startsWith("F") || p.startsWith("S") || p in setOf("CF", "LF", "RF", "LW", "RW", "W") -> 5
        else -> 3 // midfield
    }
}

private fun side(pos: String?): Int {
    val p = pos?.uppercase()?.trim() ?: return 1
    return when {
        p.endsWith("-L") || p.startsWith("L") -> 0
        p.endsWith("-R") || p.startsWith("R") -> 2
        else -> 1
    }
}

private fun shortName(name: String): String {
    val parts = name.trim().split(" ").filter { it.isNotBlank() }
    return if (parts.size <= 1) name else parts.last()
}

private fun initials(name: String) =
    name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

/** Small round player photo; falls back to the shirt number or initials when there's no photo. */
@Composable
fun PlayerFace(p: LineupPlayer, size: Dp, tint: Color) {
    val fallback: @Composable () -> Unit = {
        Box(
            Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.6f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(p.number ?: initials(p.name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp, maxLines = 1)
        }
    }
    Box(Modifier.size(size).clip(CircleShape).border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)) {
        val photo = p.photo
        if (photo.isNullOrBlank()) fallback() else SubcomposeAsyncImage(
            model = photo, contentDescription = p.name, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).background(Color(0xFF26324A)),
            loading = { fallback() }, error = { fallback() },
        )
    }
}

@Composable
private fun PitchPlayer(p: LineupPlayer, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp)) {
        Box {
            PlayerFace(p, 38.dp, tint)
            p.number?.let {
                Text(
                    it, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 2.dp)
                        .clip(RoundedCornerShape(6.dp)).background(tint).padding(horizontal = 3.dp),
                )
            }
        }
        Text(
            shortName(p.name), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 3.dp),
        )
    }
}

/** Formation diagram: goalkeeper at the bottom, attack at the top. */
@Composable
fun LineupPitch(l: LineupInfo, tint: Color) {
    val laid = formationRows(l) ?: return
    val (rows, exact) = laid
    Box(
        Modifier.fillMaxWidth().height((96 + rows.size * 68).dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1E7A46), Color(0xFF16643A), Color(0xFF1E7A46)))),
    ) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val line = Color.White.copy(alpha = 0.35f)
            val st = Stroke(width = 2f)
            val w = size.width; val h = size.height
            drawRect(line, style = st)
            drawLine(line, Offset(0f, 0f), Offset(w, 0f), 2f)
            drawCircle(line, radius = w * 0.13f, center = Offset(w / 2, 0f), style = st)            // half of the centre circle
            drawRect(line, topLeft = Offset(w * 0.2f, h - h * 0.17f), size = Size(w * 0.6f, h * 0.17f), style = st)   // penalty area
            drawRect(line, topLeft = Offset(w * 0.36f, h - h * 0.07f), size = Size(w * 0.28f, h * 0.07f), style = st) // six-yard box
            drawCircle(line, radius = 3f, center = Offset(w / 2, h - h * 0.12f))
        }
        Column(Modifier.fillMaxSize().padding(vertical = 14.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            rows.asReversed().forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { PitchPlayer(it, tint) }
                }
            }
        }
        if (!exact) {
            Text(
                "Positions within each line are approximate", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        }
    }
}

/** Line-up card: formation diagram, starting XI with faces, bench with faces. */
@Composable
fun LineupCardV2(l: LineupInfo, home: Boolean) {
    val tint = if (home) Accent.Blue else Accent.Red
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamBadge(l.team, l.logo, 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(l.team, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            l.formation?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = Accent.Blue) }
        }
        Spacer(Modifier.height(8.dp))
        LineupPitch(l, tint)
        Spacer(Modifier.height(10.dp))
        Text("Starting line-up", style = MaterialTheme.typography.labelMedium, color = Accent.Green)
        l.starters.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerFace(p, 28.dp, tint)
                Spacer(Modifier.width(8.dp))
                Text(p.number ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
                Text(p.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.position ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (l.bench.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Bench", style = MaterialTheme.typography.labelMedium, color = Accent.Orange)
            LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(l.bench) { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp)) {
                        PlayerFace(p, 32.dp, tint.copy(alpha = 0.7f))
                        Text(shortName(p.name), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

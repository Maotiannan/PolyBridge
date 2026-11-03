package dev.polybridge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.polybridge.app.R

internal val Paper = Color(0xFFF7F6F0)
internal val Ink = Color(0xFF182C27)
internal val Forest = Color(0xFF193F36)
internal val Leaf = Color(0xFFD9F29A)
internal val Muted = Color(0xFF53655D)
internal val Line = Color(0xFFD6DDD2)
internal val Mint = Color(0xFFE6EDDF)
internal val Peach = Color(0xFFF5E3D8)
internal val Clay = Color(0xFF703F30)
internal val CardWhite = Color(0xFFFFFEFA)
internal val Round = RoundedCornerShape(22.dp)

@Composable internal fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Forest, onPrimary = Color.White,
        primaryContainer = Mint, onPrimaryContainer = Ink, secondary = Clay, secondaryContainer = Peach,
        background = Paper, onBackground = Ink, surface = Paper, onSurface = Ink,
        surfaceVariant = Mint, onSurfaceVariant = Muted, outline = Muted, outlineVariant = Line), content = content)
}

internal enum class Mark(val path: String) {
    TALK("M21 11a8 8 0 0 1 -8 8H9l-5 3v-5a8 8 0 0 1 -1 -12a9 9 0 0 1 6 -2h4a8 8 0 0 1 8 8z M7 9h9 M7 13h6"),
    BOOK("M12 5c-3 -2 -6 -2 -10 -1v15c4 -1 7 -1 10 1c3 -2 6 -2 10 -1V4c-4 -1 -7 -1 -10 1z M12 5v15"),
    HISTORY("M3 11a9 9 0 1 1 2 7 M3 5v6h6 M12 7v5l4 2"),
    SETTINGS("M3 7h6m6 0h6 M3 17h10m6 0h2 M9 7a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M13 17a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"),
    MIC("M9 6a3 3 0 0 1 6 0v6a3 3 0 0 1 -6 0z M5 11v1a7 7 0 0 0 14 0v-1 M12 19v3 M9 22h6"),
    TYPE("M3 5h18v14H3z M6 9h1m3 0h1m3 0h1m3 0h0.1 M6 12h1m3 0h1m3 0h1m3 0h0.1 M8 16h8"),
    SPEAK("M3 10h4l5 -5v14l-5 -5H3z M16 8a6 6 0 0 1 0 8 M19 5a10 10 0 0 1 0 14"),
    FORWARD("M5 12h14 M13 6l6 6 -6 6"), BACK("M19 12H5 M11 6l-6 6 6 6"),
    OPEN("M5 18L18 5 M7 5h11v11"), EDIT("M4 16l12 -12 4 4 -12 12 -5 1z M13 7l4 4"),
    FLIP("M4 8a8 8 0 0 1 14 -2l2 3 M20 4v5h-5 M20 16a8 8 0 0 1 -14 2l-2 -3 M4 20v-5h5"),
    AUDIO("M3 11v2 M7 7v10 M12 3v18 M17 6v12 M21 10v4"),
    CLOUD("M6 18a4 4 0 0 1 -1 -8a7 7 0 0 1 13 -2a5 5 0 0 1 0 10z"),
    DELETE("M3 6h18 M9 6V3h6v3 M5 6l1 15h12l1 -15 M10 10v7m4 -7v7"),
    CHECK("M4 12l5 5L20 6")
}

@Composable internal fun Glyph(mark: Mark, color: Color = Ink, dim: Dp = 22.dp) {
    val path = remember(mark) { PathParser().parsePathString(mark.path).toPath() }
    Canvas(Modifier.size(dim)) {
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            drawPath(path, color, style = Stroke(width = 1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable internal fun ToolButton(mark: Mark, label: String, action: () -> Unit, color: Color = Ink, caption: Boolean = false) {
    IconButton(onClick = action, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        if (caption) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Glyph(mark, color, 17.dp)
            Text(label, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        } else Glyph(mark, color)
    }
}

@Composable internal fun Kicker(text: String, color: Color = Muted) {
    Text(text, color = color, fontSize = 11.sp, lineHeight = 16.sp,
        letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
}

@Composable internal fun Arches(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(0.dp))) {
        val w = size.width
        val h = size.height
        for (i in 0..7) {
            val radius = w * (.20f + .095f * i)
            drawArc(color, 118f, 242f, false, Offset(w * .88f - radius, h * .46f - radius),
                Size(radius * 2, radius * 2), style = Stroke(width = 1.2.dp.toPx()))
        }
    }
}

internal enum class Page(val title: String, val mark: Mark) {
    TALK("对话", Mark.TALK), BOOK("常用表达", Mark.BOOK), HISTORY("历史", Mark.HISTORY),
    LIVE("自由监听", Mark.AUDIO), CALL("通话辅助", Mark.TALK),
    TOOLS("工具", Mark.TYPE),
    SETTINGS("设置", Mark.SETTINGS), DIAGNOSTICS("设备诊断", Mark.AUDIO)
}

@Composable internal fun Masthead(configured: Boolean, settings: () -> Unit, cloud: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_polybridge), null, Modifier.size(32.dp))
        Spacer(Modifier.width(8.dp))
        Text("PolyBridge", color = Ink, fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-.7).sp)
        Spacer(Modifier.weight(1f))
        Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = cloud),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.border(1.dp, Line, CircleShape).padding(horizontal = 9.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (configured) Forest else Color(0xFFAF6836), CircleShape))
                Text(if (configured) "千问已配置" else "连接千问", color = Ink, style = MaterialTheme.typography.labelMedium)
            }
        }
        ToolButton(Mark.SETTINGS, "设置", settings)
    }
}

@Composable internal fun Navigation(current: Page, navigate: (Page) -> Unit) {
    HorizontalDivider(color = Line)
    Row(Modifier.fillMaxWidth().heightIn(min = 59.dp).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly) {
        for (page in listOf(Page.TALK, Page.BOOK, Page.HISTORY, Page.TOOLS)) {
            val selected = page == current
            Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(CircleShape)
                .clickable(role = Role.Tab) { navigate(page) }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                Row(Modifier.background(if (selected) Mint else Color.Transparent, CircleShape)
                    .padding(horizontal = 5.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Glyph(page.mark, if (selected) Forest else Muted, 18.dp)
                    Text(page.title, color = if (selected) Forest else Muted, fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable internal fun PageHeading(eyebrow: String, title: String, description: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Kicker(eyebrow)
        Text(title, color = Ink, fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp)
        if (description != null) Text(description, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

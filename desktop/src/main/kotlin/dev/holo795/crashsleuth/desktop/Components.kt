package dev.holo795.crashsleuth.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.holo795.crashsleuth.app.TargetKind
import dev.holo795.crashsleuth.model.Confidence
import java.nio.file.Path

/** Strong ease-out: movement starts at once, then settles. */
val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/** Small uppercase label above a section. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.labelSmall, color = Theme.tones.muted)
}

/** A hairline, the only separator used. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Theme.tones.line))
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun confidenceColors(confidence: Confidence): Pair<Color, Color> = when (confidence) {
    Confidence.CERTAIN, Confidence.HIGH -> Theme.tones.critical to Theme.tones.criticalSoft
    Confidence.MEDIUM -> Theme.tones.warning to Theme.tones.warningSoft
    Confidence.LOW -> Theme.tones.info to Theme.tones.infoSoft
}

/** A coloured dot and a word: how sure, or how it went. */
@Composable
fun Status(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 7.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Facts on one line, separated by a middle dot. */
@Composable
fun Facts(values: List<String>, modifier: Modifier = Modifier) {
    Text(values.filter { it.isNotBlank() }.joinToString("   ·   "), modifier, style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
}

fun kindIcon(kind: TargetKind): ImageVector = when (kind) {
    TargetKind.SERVER -> Icons.Server
    TargetKind.CLIENT -> Icons.Game
    TargetKind.PACK -> Icons.Package
    TargetKind.LOG -> Icons.File
}

/** Pressable surface: the pointer turns into a hand, the element sinks slightly while pressed. */
@Composable
private fun Modifier.pressable(interaction: MutableInteractionSource, enabled: Boolean, onClick: () -> Unit): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, tween(140, easing = EaseOut))
    return this.scale(scale)
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .hoverable(interaction)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, icon: ImageVector? = null, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val base = MaterialTheme.colorScheme.primary
    val color by animateColorAsState(if (!enabled) base.copy(alpha = 0.35f) else if (hovered) base.copy(alpha = 0.9f) else base, tween(120))
    Row(
        modifier.pressable(interaction, enabled, onClick).clip(MaterialTheme.shapes.small).background(color).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
    }
}

/** Secondary action: text only, a soft background on hover. */
@Composable
fun TextButton(text: String, onClick: () -> Unit, icon: ImageVector? = null, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (hovered) Theme.tones.raised else Color.Transparent, tween(120))
    val color by animateColorAsState(if (hovered) MaterialTheme.colorScheme.onSurface else tint, tween(120))
    Row(
        modifier.pressable(interaction, true, onClick).clip(MaterialTheme.shapes.small).background(background).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(15.dp), tint = color)
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** An icon alone, for the back arrow. */
@Composable
fun IconButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (hovered) Theme.tones.raised else Color.Transparent, tween(120))
    Box(modifier.pressable(interaction, true, onClick).size(32.dp).clip(MaterialTheme.shapes.small).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A row that reacts to the pointer, for lists of clickable items. */
@Composable
fun HoverRow(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (hovered) Theme.tones.raised else Color.Transparent, tween(120))
    Row(
        modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand).hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .clip(MaterialTheme.shapes.small).background(background).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun CodeBlock(lines: List<String>, modifier: Modifier = Modifier) {
    SelectionContainer {
        Column(
            modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Theme.tones.code).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            lines.forEach { Text(it, style = CodeStyle, color = Theme.tones.muted) }
        }
    }
}

@Composable
fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** "~/Games/server" instead of the full path, cut in the middle when still too long. */
fun shortPath(path: String, max: Int = 70): String {
    val home = System.getProperty("user.home")
    val short = if (path.startsWith(home)) "~" + path.removePrefix(home) else path
    if (short.length <= max) return short
    val name = Path.of(short).fileName?.toString() ?: short
    return short.take(max - name.length - 4).trimEnd('/') + "/…/" + name
}

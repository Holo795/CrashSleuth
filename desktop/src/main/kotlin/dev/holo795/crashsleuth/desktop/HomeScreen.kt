package dev.holo795.crashsleuth.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import dev.holo795.crashsleuth.model.Side
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(state: AppState, dragging: Boolean, chooseFolder: () -> Unit, chooseFile: () -> Unit) {
    val ui = state.ui
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
            Text(ui["home.title"], style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text(ui["home.subtitle"], style = MaterialTheme.typography.bodyLarge, color = Theme.tones.muted, modifier = Modifier.widthIn(max = 560.dp))
            Spacer(Modifier.height(36.dp))
            DropArea(dragging, ui, chooseFolder, chooseFile)
            Spacer(Modifier.height(14.dp))
            SideChooser(state)
            Spacer(Modifier.height(44.dp))
            Recents(state)
            Spacer(Modifier.height(48.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Shield, null, Modifier.size(14.dp), tint = Theme.tones.muted)
                Spacer(Modifier.width(8.dp))
                Text(ui["home.privacy"], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
            }
            Spacer(Modifier.height(6.dp))
            Text(ui["home.works.list"], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted.copy(alpha = 0.7f), modifier = Modifier.padding(start = 22.dp))
        }
    }
}

@Composable
private fun DropArea(dragging: Boolean, ui: Ui, chooseFolder: () -> Unit, chooseFile: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val border by animateColorAsState(if (dragging) primary else Color.Transparent, tween(160, easing = EaseOut))
    val fill by animateColorAsState(if (dragging) MaterialTheme.colorScheme.primaryContainer else Theme.tones.raised, tween(160, easing = EaseOut))
    val lift by animateFloatAsState(if (dragging) 1.06f else 1f, tween(220, easing = EaseOut))
    Box(
        Modifier.fillMaxWidth().height(260.dp).clip(MaterialTheme.shapes.large).background(fill).border(1.5.dp, border, MaterialTheme.shapes.large),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Logo, null, Modifier.size(34.dp).scale(lift), tint = if (dragging) primary else Theme.tones.muted)
            Spacer(Modifier.height(16.dp))
            Text(if (dragging) ui["home.dropping"] else ui["home.drop"], style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(ui["home.chooseFolder"], chooseFolder, icon = Icons.Folder)
                TextButton(ui["home.chooseFile"], chooseFile, icon = Icons.File)
            }
        }
    }
}

/** Only modpacks and lists of mods need it: a folder or a log says its side itself. */
@Composable
private fun SideChooser(state: AppState) {
    val ui = state.ui
    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(ui["home.sideFor"], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
        Spacer(Modifier.width(12.dp))
        Row(Modifier.clip(MaterialTheme.shapes.small).background(Theme.tones.raised).padding(3.dp)) {
            listOf(Side.SERVER to ui["side.server"], Side.CLIENT to ui["side.client"]).forEach { (value, label) ->
                val on = value == state.side
                Row(
                    Modifier.clip(MaterialTheme.shapes.small).background(if (on) MaterialTheme.colorScheme.background else Color.Transparent)
                        .pointerHoverIcon(PointerIcon.Hand).clickable { state.chooseSide(value) }.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (value == Side.SERVER) Icons.Server else Icons.Game, null, Modifier.size(13.dp), tint = if (on) MaterialTheme.colorScheme.onSurface else Theme.tones.muted)
                    Spacer(Modifier.width(7.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) MaterialTheme.colorScheme.onSurface else Theme.tones.muted)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(ui["home.sideAuto"], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted.copy(alpha = 0.7f))
    }
}

@Composable
private fun Recents(state: AppState) {
    val ui = state.ui
    Overline(ui["home.recent"], Modifier.padding(start = 12.dp))
    Spacer(Modifier.height(8.dp))
    if (state.recents.isEmpty()) {
        Text(ui["home.recentEmpty"], style = MaterialTheme.typography.bodyMedium, color = Theme.tones.muted, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
        return
    }
    Column {
        state.recents.forEach { recent ->
            HoverRow(onClick = { state.openRecent(recent) }) {
                Icon(kindIcon(recent.kind), null, Modifier.size(16.dp), tint = Theme.tones.muted)
                Spacer(Modifier.width(14.dp))
                Text(
                    java.nio.file.Path.of(recent.path).fileName?.toString() ?: recent.path,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(recent.title, style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(14.dp))
                Text(relative(recent.at, ui), style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
            }
        }
    }
}

/** "3 min", "2 h", "5 j": enough to recognise an analysis. */
private fun relative(at: Long, ui: Ui): String {
    val minutes = (System.currentTimeMillis() - at) / 60_000
    val french = ui.locale.language == "fr"
    return when {
        minutes < 1 -> if (french) "à l'instant" else "just now"
        minutes < 60 -> "$minutes min"
        minutes < 60 * 24 -> "${minutes / 60} h"
        else -> "${minutes / (60 * 24)} ${if (french) "j" else "d"}"
    }
}

package dev.holo795.crashsleuth.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.holo795.crashsleuth.app.Analysis
import dev.holo795.crashsleuth.inventory.JarEntry
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Platform
import kotlinx.coroutines.delay

@Composable
fun ReportScreen(state: AppState, analysis: Analysis) {
    val ui = state.ui
    val report = analysis.report
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(Icons.Back, state::home)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(analysis.target.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shortPath(analysis.target.path), style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CopyButton(state, analysis)
            }
            Spacer(Modifier.height(40.dp))
            Column(Modifier.widthIn(max = 680.dp)) {
                val environment = report.environment
                Facts(
                    listOfNotNull(
                        ui["kind.${analysis.target.kind}"],
                        environment.platform.takeIf { it != Platform.UNKNOWN }?.let { "${it.displayName} ${environment.loaderVersion ?: ""}".trim() },
                        (environment.minecraftVersion ?: analysis.target.minecraft)?.let { "Minecraft $it" },
                        environment.javaVersion?.let { "Java $it" },
                    ),
                )
                Spacer(Modifier.height(16.dp))
                val primary = report.primary
                if (primary == null) NoFinding(ui) else Verdict(ui, primary)
                val others = report.findings.drop(1)
                if (others.isNotEmpty()) {
                    Spacer(Modifier.height(48.dp))
                    Overline(ui["report.others"])
                    Spacer(Modifier.height(8.dp))
                    others.forEachIndexed { index, finding ->
                        if (index > 0) Hairline()
                        OtherFinding(ui, finding)
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Theme.tones.line))
        Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 32.dp)) {
            SearchSection(state, analysis)
            analysis.inventory?.takeIf { it.jars.isNotEmpty() }?.let {
                Spacer(Modifier.height(28.dp)); Hairline(); Spacer(Modifier.height(24.dp))
                InstalledSection(ui, it.jars)
            }
            if (analysis.mixinCollisions.isNotEmpty()) {
                Spacer(Modifier.height(28.dp)); Hairline(); Spacer(Modifier.height(24.dp))
                MixinSection(ui, analysis)
            }
        }
    }
}

@Composable
private fun Verdict(ui: Ui, finding: Finding) {
    val (tone, _) = confidenceColors(finding.confidence)
    var evidence by remember(finding) { mutableStateOf(false) }
    val culprits = ui.report.culprits(finding)
    Status(ui["report.confidence.${finding.confidence}"], tone)
    Spacer(Modifier.height(14.dp))
    Text(ui.report.title(finding), style = MaterialTheme.typography.headlineMedium)
    if (culprits.isNotEmpty()) {
        Spacer(Modifier.height(28.dp))
        Overline(if (culprits.size > 1) ui["report.culprits"] else ui["report.culprit"])
        Spacer(Modifier.height(10.dp))
        culprits.forEach { culprit ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text(culprit.label, style = MaterialTheme.typography.headlineSmall, color = tone)
                culprit.version?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Theme.tones.muted, modifier = Modifier.padding(bottom = 3.dp))
                }
                if (culprit.name != null && culprit.name != culprit.id) {
                    Spacer(Modifier.width(10.dp))
                    Text(culprit.id, style = CodeStyle, color = Theme.tones.muted, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(28.dp))
    Overline(ui["report.whatToDo"])
    Spacer(Modifier.height(10.dp))
    Text(ui.report.advice(finding), style = MaterialTheme.typography.bodyLarge)
    if (finding.evidence.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        TextButton(if (evidence) ui["report.hideEvidence"] else ui["report.showEvidence"], { evidence = !evidence }, icon = Icons.Search)
        AnimatedVisibility(evidence, enter = fadeIn(tween(180, easing = EaseOut)) + expandVertically(tween(220, easing = EaseOut)), exit = fadeOut(tween(120)) + shrinkVertically(tween(160))) {
            CodeBlock(finding.evidence, Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun NoFinding(ui: Ui) {
    Status(ui["report.ok"], Theme.tones.success)
    Spacer(Modifier.height(14.dp))
    Text(ui["report.none.title"], style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(14.dp))
    Text(ui["report.none.body"], style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OtherFinding(ui: Ui, finding: Finding) {
    val (tone, _) = confidenceColors(finding.confidence)
    var open by remember(finding) { mutableStateOf(false) }
    Column {
        HoverRow(onClick = { open = !open }, modifier = Modifier.padding(horizontal = 0.dp)) {
            Dot(tone, 7.dp)
            Spacer(Modifier.width(14.dp))
            Text(ui.report.title(finding), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(10.dp))
            Text(ui.report.culpritList(finding), style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Chevron, null, Modifier.size(14.dp), tint = Theme.tones.muted)
        }
        AnimatedVisibility(open, enter = fadeIn(tween(180, easing = EaseOut)) + expandVertically(tween(220, easing = EaseOut)), exit = fadeOut(tween(120)) + shrinkVertically(tween(160))) {
            Column(Modifier.padding(start = 33.dp, end = 12.dp, bottom = 14.dp)) {
                Text(ui.report.advice(finding), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (finding.evidence.isNotEmpty()) CodeBlock(finding.evidence, Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun SearchSection(state: AppState, analysis: Analysis) {
    val ui = state.ui
    Text(ui["search.cta.title"], style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    if (analysis.target.searchable) {
        Text(ui["search.cta.body"], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
        Spacer(Modifier.height(16.dp))
        PrimaryButton(ui["search.cta.button"], { state.prepareSearch(analysis) }, icon = Icons.Play, modifier = Modifier.fillMaxWidth())
    } else {
        Text(ui["search.cta.unavailable"], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
    }
}

@Composable
private fun InstalledSection(ui: Ui, jars: List<JarEntry>) {
    var filter by remember { mutableStateOf("") }
    val mods = jars.count { it.folder == "mods" }
    val plugins = jars.count { it.folder == "plugins" }
    SectionTitle(ui["report.content"]) {
        Text(listOfNotNull(mods.takeIf { it > 0 }?.let { ui["report.mods", it] }, plugins.takeIf { it > 0 }?.let { ui["report.plugins", it] }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
    }
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Theme.tones.raised).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Search, null, Modifier.size(14.dp), tint = Theme.tones.muted)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (filter.isEmpty()) Text(ui["report.filter"], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
            BasicTextField(filter, { filter = it }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
        }
    }
    Spacer(Modifier.height(8.dp))
    val shown = jars.filter { jar -> filter.isBlank() || jar.file.contains(filter, true) || jar.mods.any { it.id.contains(filter, true) || it.name?.contains(filter, true) == true } }
    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
        shown.forEach { jar ->
            val mod = jar.mods.firstOrNull()
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(mod?.name ?: mod?.id ?: jar.file, style = MaterialTheme.typography.bodySmall, color = if (jar.error != null) Theme.tones.critical else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Text(mod?.version?.take(18) ?: "", style = CodeStyle, color = Theme.tones.muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MixinSection(ui: Ui, analysis: Analysis) {
    SectionTitle(ui["report.mixins"]) { Text(analysis.mixinCollisions.size.toString(), style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted) }
    Text(ui["report.mixins.body"], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
    Spacer(Modifier.height(12.dp))
    analysis.mixinCollisions.take(8).forEach { collision ->
        Column(Modifier.padding(vertical = 6.dp)) {
            Text("${collision.targetClass.substringAfterLast('/')}.${collision.method}", style = CodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(collision.mods.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
        }
    }
}

@Composable
private fun CopyButton(state: AppState, analysis: Analysis) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    TextButton(if (copied) state.ui["report.copied"] else state.ui["report.copy"], {
        clipboard.setText(AnnotatedString(plainReport(state.ui, analysis)))
        copied = true
    }, icon = if (copied) Icons.Check else Icons.Copy)
}

/** The same report as the command line prints, to paste in a support channel. */
fun plainReport(ui: Ui, analysis: Analysis): String = buildString {
    val env = analysis.report.environment
    appendLine("CrashSleuth — ${analysis.target.name}")
    appendLine("${env.platform.displayName} ${env.loaderVersion ?: ""} | Minecraft ${env.minecraftVersion ?: "?"} | Java ${env.javaVersion ?: "?"}")
    appendLine()
    if (analysis.report.findings.isEmpty()) appendLine(ui["report.none.title"])
    analysis.report.findings.forEachIndexed { index, finding ->
        appendLine("${if (index == 0) "▶" else "•"} ${ui.report.title(finding)}${ui.report.culpritList(finding).let { if (it.isEmpty()) "" else " — $it" }}")
        if (index == 0) {
            appendLine("  ${ui.report.advice(finding)}")
            finding.evidence.forEach { appendLine("  > $it") }
        }
    }
    appendLine()
    appendLine("github.com/Holo795/CrashSleuth")
}

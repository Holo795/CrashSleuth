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
import dev.holo795.crashsleuth.app.TargetKind
import dev.holo795.crashsleuth.inventory.JarEntry
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Side
import dev.holo795.crashsleuth.model.Situation
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
                ShareButton(state, analysis)
                CopyButton(state, analysis)
            }
            Spacer(Modifier.height(40.dp))
            Column(Modifier.widthIn(max = 680.dp)) {
                val environment = report.environment
                Facts(
                    listOfNotNull(
                        ui["kind.${analysis.target.kind}"],
                        analysis.side?.let { ui[if (it == Side.CLIENT) "side.forClient" else "side.forServer"] },
                        // A game folder without launcher files: the loader its mods are made for.
                        environment.platform.takeIf { it != Platform.UNKNOWN }?.let { "${it.displayName} ${environment.loaderVersion ?: ""}".trim() }
                            ?: analysis.target.loader?.takeIf { it != "vanilla" }?.let { loader -> Platform.entries.firstOrNull { it.name.equals(loader, true) }?.displayName ?: loader },
                        (environment.minecraftVersion ?: analysis.target.minecraft)?.let { "Minecraft $it" },
                        environment.javaVersion?.let { "Java $it" },
                    ),
                )
                Spacer(Modifier.height(16.dp))
                val primary = report.primary
                if (primary == null) NoFinding(ui) else Verdict(ui, primary, analysis)
                androidx.compose.runtime.LaunchedEffect(Unit) { if (state.localAi == null) state.lookForLocalAi() }
                val explained = state.explanation?.takeIf { it.first == analysis.target.path }?.second
                if (explained != null) {
                    Spacer(Modifier.height(28.dp))
                    Overline(ui["ai.title"])
                    Spacer(Modifier.height(8.dp))
                    Text(explained, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (state.localAi != null) {
                    Spacer(Modifier.height(20.dp))
                    TextButton(if (state.working(analysis, "explain")) ui["ai.working"] else ui["ai.button"], { if (!state.working(analysis)) state.explain(analysis) }, icon = Icons.Search)
                }
                if (state.localAi != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ui["ai.model"], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
                        state.aiModels.take(4).forEach { model ->
                            Spacer(Modifier.width(8.dp))
                            val chosen = model == (state.aiModel ?: state.aiModels.firstOrNull())
                            Text(
                                model,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (chosen) MaterialTheme.colorScheme.primary else Theme.tones.muted,
                                modifier = Modifier.clickable { state.chooseAiModel(model) },
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(ui["ai.off"], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted, modifier = Modifier.clickable { state.enableAi(false) })
                    }
                }
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
            if (analysis.inventory?.jars?.isNotEmpty() == true) {
                Spacer(Modifier.height(28.dp)); Hairline(); Spacer(Modifier.height(24.dp))
                ToolsSection(state, analysis)
            }
            analysis.inventory?.takeIf { it.jars.isNotEmpty() }?.let {
                Spacer(Modifier.height(28.dp)); Hairline(); Spacer(Modifier.height(24.dp))
                InstalledSection(state, analysis, it.jars)
            }
            analysis.inventory?.files?.worlds?.filter { it.damagedChunks.isNotEmpty() || it.damagedPlayers.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { worlds ->
                Spacer(Modifier.height(28.dp)); Hairline(); Spacer(Modifier.height(24.dp))
                WorldsSection(state, analysis, worlds)
            }
            if (analysis.mixinCollisions.isNotEmpty()) {
                Spacer(Modifier.height(28.dp)); Hairline(); Spacer(Modifier.height(24.dp))
                MixinSection(ui, analysis)
            }
        }
    }
}

/** Worlds with damaged chunks, entities or player saves, and the way to their files. */
@Composable
private fun WorldsSection(state: AppState, analysis: Analysis, worlds: List<dev.holo795.crashsleuth.inventory.WorldInfo>) {
    val ui = state.ui
    SectionTitle(ui["report.worlds"]) {
        Text(worlds.sumOf { it.damagedChunks.size + it.damagedPlayers.size }.toString(), style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
    }
    Text(ui["report.worlds.body"], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
    Spacer(Modifier.height(10.dp))
    worlds.forEach { world ->
        Text(world.name, style = MaterialTheme.typography.titleSmall)
        world.damagedChunks.take(6).forEach { chunk ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("[${chunk.x}, ${chunk.z}]", style = CodeStyle, color = Theme.tones.critical)
                Spacer(Modifier.width(8.dp))
                Text(chunk.file, style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (world.damagedChunks.size > 6) {
            Text(ui["report.worlds.more", world.damagedChunks.size - 6], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
        }
        world.damagedPlayers.take(4).forEach { player ->
            Text(ui["report.worlds.player", player.name ?: player.file], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
        }
        val folder = java.nio.file.Path.of(analysis.target.path).resolve(world.name)
        Spacer(Modifier.height(6.dp))
        TextButton(ui["report.worlds.open"], { dev.holo795.crashsleuth.app.Reveal.folder(folder) }, icon = Icons.Folder)
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun Verdict(ui: Ui, finding: Finding, analysis: Analysis? = null) {
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
            // The jar of this culprit, when it is on this computer: the person can go straight to it.
            val jar = analysis?.inventory?.jars?.firstOrNull { it.file == culprit.file || it.mods.any { mod -> mod.id == culprit.id } }?.path
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
                if (jar != null) {
                    Spacer(Modifier.width(14.dp))
                    Text(
                        ui["report.reveal"],
                        style = MaterialTheme.typography.bodySmall,
                        color = Theme.tones.muted,
                        modifier = Modifier.padding(bottom = 4.dp).clickable { dev.holo795.crashsleuth.app.Reveal.file(java.nio.file.Path.of(jar)) },
                    )
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

/** Checks that need something more: the server the player joins, or Modrinth. */
@Composable
private fun ToolsSection(state: AppState, analysis: Analysis) {
    val ui = state.ui
    val working = state.working(analysis)
    Text(ui["tools.title"], style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    if (analysis.target.kind != TargetKind.SERVER) {
        Text(analysis.comparedWith?.let { ui["tools.compare.done", shortPath(it)] } ?: ui["tools.compare.body"], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
        Spacer(Modifier.height(10.dp))
        TextButton(if (state.working(analysis, "compare")) ui["tools.working"] else ui["tools.compare.button"], { if (!working) state.compareWithServer(analysis) }, icon = Icons.Folder)
        Spacer(Modifier.height(16.dp))
    }
    val outdated = analysis.report.findings.count { it.situation == Situation.OUTDATED }
    Text(when {
        !analysis.updatesChecked -> ui["tools.updates.body"]
        outdated == 0 -> ui["tools.updates.none"]
        else -> ui["tools.updates.found", outdated]
    }, style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
    Spacer(Modifier.height(10.dp))
    TextButton(if (state.working(analysis, "updates")) ui["tools.working"] else ui["tools.updates.button"], { if (!working) state.checkUpdates(analysis) }, icon = Icons.Search)
    if ((analysis.report.environment.minecraftVersion ?: analysis.target.minecraft) != null && analysis.report.findings.any { it.evidence.isNotEmpty() }) {
        Spacer(Modifier.height(16.dp))
        Text(if (analysis.readable) ui["tools.readable.done"] else ui["tools.readable.body"], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
        if (!analysis.readable) {
            Spacer(Modifier.height(10.dp))
            TextButton(if (state.working(analysis, "readable")) ui["tools.working"] else ui["tools.readable.button"], { if (!working) state.makeReadable(analysis) }, icon = Icons.Search)
        }
    }
    state.notice?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = Theme.tones.critical)
    }
}

@Composable
private fun InstalledSection(state: AppState, analysis: Analysis, jars: List<JarEntry>) {
    val ui = state.ui
    // What the Modrinth check found, by jar name: a newer version and the page of the project.
    val updates = analysis.report.findings.filter { it.situation == Situation.OUTDATED }
        .mapNotNull { finding -> finding.culprits.firstOrNull()?.file?.let { it to finding } }.toMap()
    var filter by remember { mutableStateOf("") }
    val mods = jars.count { it.folder == "mods" }
    val plugins = jars.count { it.folder == "plugins" }
    SectionTitle(ui["report.content"]) {
        Text(listOfNotNull(mods.takeIf { it > 0 }?.let { if (it == 1) ui["report.mod"] else ui["report.mods", it] }, plugins.takeIf { it > 0 }?.let { if (it == 1) ui["report.plugin"] else ui["report.plugins", it] }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
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
            val update = updates[jar.file]
            val project = update?.details?.get("project")
            HoverRow(onClick = {
                when {
                    project != null -> dev.holo795.crashsleuth.app.Reveal.page("https://modrinth.com/project/$project")
                    jar.path != null -> dev.holo795.crashsleuth.app.Reveal.file(java.nio.file.Path.of(jar.path!!))
                    else -> {}
                }
            }, modifier = Modifier.padding(horizontal = 0.dp)) {
                Text(mod?.name ?: mod?.id ?: jar.file, style = MaterialTheme.typography.bodySmall, color = if (jar.error != null) Theme.tones.critical else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (update != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(ui["report.update", update.details["latest"] ?: "?"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
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

/** Copies a link that opens the report in any browser, and opens it: the report travels inside the link. */
@Composable
fun ShareButton(state: AppState, analysis: Analysis, search: dev.holo795.crashsleuth.bisect.SearchResult? = null) {
    val clipboard = LocalClipboardManager.current
    var shared by remember { mutableStateOf(false) }
    LaunchedEffect(shared) { if (shared) { delay(1600); shared = false } }
    TextButton(if (shared) state.ui["report.copied"] else state.ui["report.share"], {
        val link = dev.holo795.crashsleuth.app.ShareLink.link(dev.holo795.crashsleuth.app.ShareLink.build(analysis, state.ui.messages, search))
        clipboard.setText(AnnotatedString(link))
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(link)) }
        shared = true
    }, icon = if (shared) Icons.Check else Icons.Share)
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

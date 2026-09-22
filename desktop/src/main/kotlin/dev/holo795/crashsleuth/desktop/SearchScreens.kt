package dev.holo795.crashsleuth.desktop

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.holo795.crashsleuth.app.Analysis
import dev.holo795.crashsleuth.app.JavaInstall
import dev.holo795.crashsleuth.app.SearchSetup
import dev.holo795.crashsleuth.bisect.RunRecord
import dev.holo795.crashsleuth.runner.Outcome
import kotlinx.coroutines.delay

@Composable
fun SetupScreen(state: AppState, analysis: Analysis, initial: SearchSetup, javas: List<JavaInstall>, chooseJava: () -> String?) {
    val ui = state.ui
    var setup by remember { mutableStateOf(initial) }
    var javaChoices by remember(javas) { mutableStateOf(javas) }
    // The Java installations arrive a moment after the screen: pick the suggested one when they do.
    LaunchedEffect(initial.java) { if (setup.java == "java") setup = setup.copy(java = initial.java) }
    Column(Modifier.fillMaxSize()) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(Icons.Back, { state.back(analysis) })
                Spacer(Modifier.width(12.dp))
                Text(analysis.target.name, style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
            }
            Spacer(Modifier.height(28.dp))
            Text(ui["setup.title"], style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(28.dp))
            if (setup.client) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = Theme.tones.info)
                    Spacer(Modifier.width(10.dp))
                    Text(ui["setup.clientWarning"], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(24.dp))
            }
            Column(Modifier.fillMaxWidth()) {
                Field(ui["setup.java"]) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        javaChoices.take(6).forEach { java ->
                            Choice(selected = setup.java == java.executable, onClick = { setup = setup.copy(java = java.executable) }) {
                                Text("Java ${java.major}", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.width(10.dp))
                                Text(listOfNotNull(java.vendor, java.version).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        TextButton(ui["setup.javaBrowse"], {
                            chooseJava()?.let { path ->
                                javaChoices = javaChoices + JavaInstall(path, 0, path)
                                setup = setup.copy(java = path)
                            }
                        }, icon = Icons.Folder)
                    }
                }
                if (setup.client) {
                    Divider()
                    Field(ui["setup.minecraft"]) { TextInput(setup.minecraft ?: "", { setup = setup.copy(minecraft = it.trim().ifEmpty { null }) }) }
                    Divider()
                    Field(ui["setup.loader"]) {
                        Segmented(listOf("fabric", "vanilla"), setup.loader ?: "fabric") { setup = setup.copy(loader = it) }
                    }
                }
                Divider()
                Field(ui["setup.repeat"], ui["setup.repeat.hint"]) { Stepper(setup.repeat, 1..8) { setup = setup.copy(repeat = it) } }
                Divider()
                Field(ui["setup.parallel"], ui["setup.parallel.hint"]) { Stepper(setup.parallel, 1..4) { setup = setup.copy(parallel = it) } }
                Divider()
                Field(ui["setup.maxRuns"]) { Stepper(setup.maxRuns, 10..200, step = 10) { setup = setup.copy(maxRuns = it) } }
                Divider()
                Field(ui["setup.withWorld"], ui["setup.withWorld.hint"]) {
                    Switch(setup.withWorld, { setup = setup.copy(withWorld = it) }, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
    // Always visible, whatever the height of the window.
    Hairline()
    Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 14.dp), horizontalArrangement = Arrangement.Center) {
        Row(Modifier.widthIn(max = 720.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(ui["setup.cancel"], { state.back(analysis) })
            Spacer(Modifier.width(8.dp))
            PrimaryButton(ui["setup.start"], { state.startSearch(analysis, setup) }, icon = Icons.Play, enabled = !setup.client || setup.minecraft != null)
        }
    }
    }
}

@Composable
private fun Field(label: String, hint: String? = null, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(220.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
        }
        Spacer(Modifier.width(20.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun Divider() {
    Hairline(Modifier.padding(vertical = 10.dp))
}

@Composable
private fun Choice(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(if (selected) Theme.tones.raised else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp).clip(androidx.compose.foundation.shape.CircleShape).border(2.dp, if (selected) primary else Theme.tones.muted, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Dot(primary, 8.dp)
        }
        Spacer(Modifier.width(12.dp))
        content()
    }
}

@Composable
private fun TextInput(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value, onChange, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.width(180.dp).clip(MaterialTheme.shapes.small).background(Theme.tones.raised).padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
private fun Segmented(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.clip(MaterialTheme.shapes.medium).background(Theme.tones.raised).padding(3.dp)) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier.clip(MaterialTheme.shapes.small).background(if (on) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .pointerHoverIcon(PointerIcon.Hand).clickable { onSelect(option) }.padding(horizontal = 16.dp, vertical = 7.dp),
            ) {
                Text(option.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onSurface else Theme.tones.muted)
            }
        }
    }
}

@Composable
private fun Stepper(value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", value - step >= range.first) { onChange(value - step) }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepButton("+", value + step <= range.last) { onChange(value + step) }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(MaterialTheme.shapes.small).background(Theme.tones.raised)
            .alpha(if (enabled) 1f else 0.4f).pointerHoverIcon(PointerIcon.Hand).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.titleMedium) }
}

@Composable
fun SearchScreen(state: AppState, analysis: Analysis, setup: SearchSetup) {
    val ui = state.ui
    val progress = state.search ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val finished = progress.result != null || progress.error != null || progress.cancelled
    LaunchedEffect(finished) { while (!finished) { now = System.currentTimeMillis(); delay(1000) } }
    val elapsed = duration((if (finished) progress.runs.sumOf { it.seconds }.toLong() * 1000 else now - progress.startedAt))
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 48.dp, vertical = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (finished) ui["setup.title"] else ui["search.title"], style = MaterialTheme.typography.headlineMedium)
                    Text("${analysis.target.name} · ${ui["search.launches", progress.runs.size]} · ${ui["search.elapsed", elapsed]}", style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
                }
                if (!finished) {
                    TextButton(if (progress.cancel.get()) ui["search.stopping"] else ui["search.stop"], state::stopSearch, icon = Icons.Stop, tint = Theme.tones.critical)
                } else {
                    PrimaryButton(ui["search.done"], { state.back(analysis) }, icon = Icons.Back)
                }
            }
            Spacer(Modifier.height(20.dp))
            if (finished) ResultCard(ui, progress) else Phase(ui, progress)
            Spacer(Modifier.height(24.dp))
            Overline("${ui["search.launches", progress.runs.size]}")
            Spacer(Modifier.height(10.dp))
            Timeline(ui, progress.runs)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Theme.tones.line))
        SuspectsColumn(ui, progress, setup)
    }
}

@Composable
private fun Phase(ui: Ui, progress: SearchProgress) {
    val pulse by rememberInfiniteTransition().animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(MaterialTheme.colorScheme.primary.copy(alpha = pulse), 8.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                when (progress.phase) {
                    "preparing" -> ui["search.preparing"]
                    "installing" -> ui["search.installing"]
                    else -> ui["search.running", progress.runs.size + 1]
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun ResultCard(ui: Ui, progress: SearchProgress) {
    val result = progress.result
    val (title, body, tone) = when {
        progress.cancelled -> Triple(ui["search.result.cancelled"], null, Theme.tones.muted)
        progress.error != null -> Triple(ui["search.result.error"], progress.error, Theme.tones.critical)
        result == null -> return
        !result.reproduced -> Triple(ui["search.result.notReproduced"], ui["search.result.notReproduced.body"], Theme.tones.success)
        !result.complete -> Triple(ui["search.result.incomplete"], ui["search.result.incomplete.body"], Theme.tones.warning)
        result.culprits.size == 1 -> Triple(ui["search.result.single"], null, Theme.tones.critical)
        else -> Triple(ui["search.result.combination"], null, Theme.tones.critical)
    }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Status(title, tone)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (result != null && result.reproduced) {
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                result.labels.zip(result.culprits).forEach { (label, file) ->
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(label, style = MaterialTheme.typography.headlineSmall, color = if (result.complete) Theme.tones.critical else MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(12.dp))
                        Text(file, style = CodeStyle, color = Theme.tones.muted, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(ui["search.result.cost", result.runs.size, duration(result.runs.sumOf { it.seconds }.toLong() * 1000)], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
        }
    }
}

@Composable
private fun Timeline(ui: Ui, runs: List<RunRecord>) {
    val list = rememberLazyListState()
    LaunchedEffect(runs.size) { if (runs.isNotEmpty()) list.animateScrollToItem(runs.lastIndex) }
    LazyColumn(state = list) {
        items(runs, key = { it.index }) { run ->
            val (tone, soft) = when (run.outcome) {
                Outcome.READY -> Theme.tones.success to Theme.tones.successSoft
                Outcome.CRASHED -> Theme.tones.critical to Theme.tones.criticalSoft
                Outcome.TIMEOUT -> Theme.tones.warning to Theme.tones.warningSoft
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#${run.index}", style = CodeStyle, color = Theme.tones.muted, modifier = Modifier.width(40.dp))
                Status(ui["outcome.${run.outcome}"], tone, Modifier.width(110.dp))
                if (run.sameFailure && run.note != "reference") {
                    Text(ui["search.sameCrash"], style = MaterialTheme.typography.labelMedium, color = Theme.tones.muted, modifier = Modifier.width(100.dp))
                } else {
                    Spacer(Modifier.width(100.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(if (run.note == "reference") ui["search.reference"] else ui[if (run.kept.size == 1) "search.files.one" else "search.files.many", run.kept.size], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${"%.0f".format(run.seconds)} s", style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
            }
        }
    }
}

@Composable
private fun SuspectsColumn(ui: Ui, progress: SearchProgress, setup: SearchSetup) {
    val total = progress.total.coerceAtLeast(1)
    val left = if (progress.suspects.isEmpty()) total else progress.suspects.size
    val fraction by animateFloatAsState(left.toFloat() / total, tween(500))
    Column(Modifier.width(320.dp).fillMaxHeight().padding(24.dp)) {
        Overline(ui["search.suspects"])
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(left.toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Text(" / $total", style = MaterialTheme.typography.titleMedium, color = Theme.tones.muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.small).background(Theme.tones.raised)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primary))
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            progress.suspects.take(60).forEach { key ->
                Text(key.substringAfter('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            listOfNotNull(setup.minecraft?.let { "Minecraft $it" }, setup.loader, "×${setup.repeat}", if (setup.parallel > 1) "∥${setup.parallel}" else null).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted,
        )
    }
}

private fun duration(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds < 60) "${seconds} s" else "${seconds / 60} min ${"%02d".format(seconds % 60)}"
}

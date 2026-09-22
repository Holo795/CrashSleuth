package dev.holo795.crashsleuth.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser

private val MAC = System.getProperty("os.name").lowercase().contains("mac")

fun main(args: Array<String>) {
    if (MAC) System.setProperty("apple.awt.application.appearance", "system")
    application {
        // A path given at start ("Open with", scripts) is analysed right away.
        val state = remember { AppState().also { app -> args.firstOrNull()?.let { app.open(Path.of(it)) } } }
        Window(
            onCloseRequest = { state.stopSearch(); exitApplication() },
            title = "CrashSleuth",
            icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Logo),
            state = rememberWindowState(size = DpSize(1240.dp, 820.dp)),
        ) {
            window.minimumSize = Dimension(1000, 680)
            if (MAC) {
                // The content runs under a transparent title bar: one surface, no grey strip on a dark app.
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            }
            var dragging by remember { mutableStateOf(false) }
            DisposableEffect(Unit) {
                window.dropTarget = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
                    override fun dragEnter(event: DropTargetDragEvent) { dragging = true }
                    override fun dragExit(event: DropTargetEvent) { dragging = false }
                    override fun drop(event: DropTargetDropEvent) {
                        dragging = false
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        @Suppress("UNCHECKED_CAST")
                        val files = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                        files?.firstOrNull()?.let { state.open(it.toPath()) }
                        event.dropComplete(true)
                    }
                }, true)
                onDispose { }
            }
            state.pickFolder = { chooseFolder(window) }
            CrashSleuthTheme {
                App(
                    state = state,
                    dragging = dragging,
                    chooseFolder = { chooseFolder(window)?.let(state::open) },
                    chooseFile = { chooseFile(window, null)?.let(state::open) },
                    chooseJava = { chooseFile(window, if (MAC) null else "java*")?.toString() },
                )
            }
        }
    }
}

@Composable
private fun App(state: AppState, dragging: Boolean, chooseFolder: () -> Unit, chooseFile: () -> Unit, chooseJava: () -> String?) {
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize()) {
        TopBar(state)
        AnimatedContent(state.screen, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, contentKey = { it::class }) { screen ->
            when (screen) {
                Screen.Home -> HomeScreen(state, dragging, chooseFolder, chooseFile)
                is Screen.Analyzing -> Analyzing(state.ui, screen.path)
                is Screen.Failed -> Failed(state, screen)
                is Screen.Report -> ReportScreen(state, screen.analysis)
                is Screen.Shared -> SharedScreen(state, screen.report)
                is Screen.Setup -> SetupScreen(state, screen.analysis, screen.setup, screen.javas, chooseJava)
                is Screen.Searching -> SearchScreen(state, screen.analysis, screen.setup)
            }
        }
    }
    }
}

@Composable
private fun TopBar(state: AppState) {
    // On macOS the window buttons sit over the bar's left side.
    Row(Modifier.fillMaxWidth().padding(start = if (MAC) 84.dp else 24.dp, end = 24.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(MaterialTheme.shapes.medium).pointerHoverIcon(PointerIcon.Hand).clickable { state.home() }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Logo, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("CrashSleuth", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.weight(1f))
        Row {
            listOf("en" to "EN", "fr" to "FR").forEach { (tag, label) ->
                val on = state.ui.locale.language == tag
                Box(
                    Modifier.clip(MaterialTheme.shapes.small).pointerHoverIcon(PointerIcon.Hand).clickable { state.setLanguage(tag) }.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) MaterialTheme.colorScheme.onSurface else Theme.tones.muted)
                }
            }
        }
    }
}

@Composable
private fun Analyzing(ui: Ui, path: Path) {
    val transition = rememberInfiniteTransition()
    val step by transition.animateFloat(0f, 3f, infiniteRepeatable(tween(2400), RepeatMode.Restart))
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Logo, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text(ui["analyzing.title", path.fileName?.toString() ?: path.toString()], style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("analyzing.step1", "analyzing.step2", "analyzing.step3").forEachIndexed { index, key ->
                    val active = step.toInt() == index
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (active) MaterialTheme.colorScheme.primary else Theme.tones.line, 8.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(ui[key], style = MaterialTheme.typography.bodyMedium, color = if (active) MaterialTheme.colorScheme.onSurface else Theme.tones.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun Failed(state: AppState, screen: Screen.Failed) {
    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp)) {
            Status(state.ui["analyzing.failed"], Theme.tones.critical)
            Spacer(Modifier.height(14.dp))
            Text(screen.message, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Text(shortPath(screen.path.toString()), style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
            Spacer(Modifier.height(24.dp))
            TextButton(state.ui["report.back"], state::home, icon = Icons.Back)
        }
    }
}

/** Native folder picker: the macOS dialog when available, Swing elsewhere. */
private fun chooseFolder(owner: Frame): Path? {
    if (MAC) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(owner, "CrashSleuth", FileDialog.LOAD).apply { isVisible = true }
            return dialog.file?.let { Path.of(dialog.directory, it) }
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }
    val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
    return if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

private fun chooseFile(owner: Frame, pattern: String?): Path? {
    val dialog = FileDialog(owner, "CrashSleuth", FileDialog.LOAD).apply {
        if (pattern != null) file = pattern
        isVisible = true
    }
    return dialog.file?.let { Path.of(dialog.directory, it) }
}

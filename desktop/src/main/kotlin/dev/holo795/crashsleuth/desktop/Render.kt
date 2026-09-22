package dev.holo795.crashsleuth.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.holo795.crashsleuth.app.Workbench
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

/**
 * Draws the home screen and the report of a folder into PNG files, without any window: how the screens are
 * checked on a computer someone is using (nothing appears on its screen) or on one without a screen.
 * Arguments: the folder or file to analyse, then the output folder.
 */
fun main(args: Array<String>) {
    val target = Path.of(args[0])
    val out = Files.createDirectories(Path.of(args.getOrElse(1) { "." }))
    val state = AppState()
    val analysis = Workbench().analyze(target)
    val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
        "home" to { HomeScreen(state, dragging = false, chooseFolder = {}, chooseFile = {}) },
        "report" to { ReportScreen(state, analysis) },
    )
    screens.forEach { (name, content) ->
        val scene = ImageComposeScene(1240, 820, Density(1f)) {
            CrashSleuthTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    content()
                }
            }
        }
        // A second frame, once the first one has laid everything out.
        scene.render(0)
        val image = scene.render(500_000_000)
        Files.write(out.resolve("$name.png"), image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("$name.png ${image.width}x${image.height}")
    }
    kotlin.system.exitProcess(0)
}

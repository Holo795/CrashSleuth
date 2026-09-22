package dev.holo795.crashsleuth.engine

import dev.holo795.crashsleuth.logs.LogAnalyzer
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertTrue

/** A pattern that backtracks on long lines once took a minute on a 4 MB log: every real log must stay fast. */
class AnalysisSpeedTest {
    @Test
    fun `every log of the corpus is analysed in a few seconds`() {
        val corpus = Path.of(System.getProperty("crashsleuth.corpus") ?: "lab/corpus")
        if (!Files.isDirectory(corpus)) return
        val logs = Files.walk(corpus).use { paths -> paths.filter { it.isRegularFile() && (it.name.endsWith(".log") || it.name.endsWith(".txt")) }.toList() }
        val analyzer = LogAnalyzer()
        val slow = logs.mapNotNull { log ->
            val text = log.readText()
            val started = System.nanoTime()
            analyzer.analyze(text)
            val seconds = (System.nanoTime() - started) / 1e9
            if (seconds > 10) "${corpus.relativize(log)}: ${"%.1f".format(seconds)} s" else null
        }
        assertTrue(slow.isEmpty(), "slow analyses: $slow")
    }
}

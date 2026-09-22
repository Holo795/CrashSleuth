package dev.holo795.crashsleuth.runner

import dev.holo795.crashsleuth.engine.Diagnoser
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** How a test launch ended, decided without asking anyone. */
@Serializable
enum class Outcome {
    /** Reached "Done (...)!" and kept running until stopped. */
    READY,

    /** Stopped on its own: before being ready, or while it was running. */
    CRASHED,

    /** Neither ready nor stopped in time: frozen or far too slow. */
    TIMEOUT,
}

data class RunResult(
    val outcome: Outcome,
    val seconds: Double,
    val exitCode: Int?,
    /** Console output, latest.log and the crash reports of this launch. */
    val logs: List<Diagnoser.Log>,
)

/** Starts a server folder, watches it, stops it, and says how it went. */
class ServerLauncher(
    private val command: List<String>,
    /** Longest time a launch may take to become ready. */
    private val timeout: Duration = Duration.ofMinutes(10),
    /** Time the server keeps running once ready, so crashes of the first ticks are caught. */
    private val settle: Duration = Duration.ofSeconds(10),
) {
    fun run(directory: Path): RunResult {
        val started = System.nanoTime()
        val process = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start()
        val console = StringBuilder()
        val readyAt = AtomicLong(0)
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(console) { if (console.length < MAX_CONSOLE) console.appendLine(line) }
                    if (readyAt.get() == 0L && DONE.containsMatchIn(line)) readyAt.set(System.nanoTime())
                }
            }
        }.apply { isDaemon = true; start() }

        var stopRequested = false
        var timedOut = false
        while (process.isAlive) {
            val now = System.nanoTime()
            if (readyAt.get() != 0L && !stopRequested && now - readyAt.get() >= settle.toNanos()) {
                stopRequested = true
                runCatching { process.outputStream.apply { write("stop\n".toByteArray()); flush() } }
                if (!process.waitFor(90, TimeUnit.SECONDS)) kill(process)
                break
            }
            if (readyAt.get() == 0L && now - started >= timeout.toNanos()) {
                timedOut = true
                kill(process)
                break
            }
            process.waitFor(200, TimeUnit.MILLISECONDS)
        }
        reader.join(5000)
        val exitCode = if (process.isAlive) null else process.exitValue()
        val crashReports = directory.resolve("crash-reports").takeIf { it.isDirectory() }?.listDirectoryEntries("*.txt").orEmpty() +
            directory.listDirectoryEntries("hs_err_pid*.log")
        val outcome = when {
            timedOut -> Outcome.TIMEOUT
            // Stopped by us, and nothing crashed in between.
            stopRequested && crashReports.isEmpty() -> Outcome.READY
            else -> Outcome.CRASHED
        }
        val logs = buildList {
            add(Diagnoser.Log("console", synchronized(console) { console.toString() }))
            directory.resolve("logs/latest.log").takeIf { it.exists() }?.let { add(Diagnoser.Log("logs/latest.log", it.readText())) }
            crashReports.sortedBy { it.getLastModifiedTime() }.forEach { add(Diagnoser.Log(it.name, it.readText())) }
        }
        return RunResult(outcome, (System.nanoTime() - started) / 1e9, exitCode, logs)
    }

    /** The server often runs under a shell script: its Java child must go too. */
    private fun kill(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(30, TimeUnit.SECONDS)
    }

    companion object {
        private val DONE = Regex("""Done \([\d.,]+s\)! For help, type""")
        private const val MAX_CONSOLE = 8_000_000
    }
}

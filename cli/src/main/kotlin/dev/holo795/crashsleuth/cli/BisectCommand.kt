package dev.holo795.crashsleuth.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.holo795.crashsleuth.bisect.CulpritSearch
import dev.holo795.crashsleuth.bisect.SearchResult
import dev.holo795.crashsleuth.bisect.suspectsOf
import dev.holo795.crashsleuth.engine.Diagnoser
import dev.holo795.crashsleuth.model.Messages
import dev.holo795.crashsleuth.runner.LaunchCommand
import dev.holo795.crashsleuth.runner.ServerLauncher
import dev.holo795.crashsleuth.runner.Workspace
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.time.Duration

class BisectCommand : CliktCommand(name = "bisect") {
    override fun help(context: Context) =
        "Find the mods or plugins that crash a server by launching a copy of it with fewer of them. Your files are never changed."

    private val server by argument(help = "server folder").path(mustExist = true, canBeFile = false)
    private val java by option("--java", help = "Java executable used to start the server").default("java")
    private val command by option("--command", help = "start command, if it cannot be detected (run in the server folder)")
    private val memory by option("--memory", help = "maximum heap, for example 6G (default: the server's own setting, else 4G)")
    private val timeout by option("--timeout", help = "minutes a launch may take to be ready").int().default(10)
    private val settle by option("--settle", help = "seconds a ready server keeps running before being stopped").int().default(10)
    private val maxRuns by option("--max-runs", help = "maximum number of launches").int().default(40)
    private val repeat by option("--repeat", help = "launches per tested set, for crashes that do not happen every time").int().default(1)
    private val parallel by option("--parallel", help = "launches at the same time (each needs its own memory)").int().default(1)
    private val withWorld by option("--with-world", help = "copy the worlds into each launch (crashes that happen in a world)").flag()
    private val work by option("--work", help = "working folder (default: a temporary folder)").path()
    private val keep by option("--keep", help = "keep the working folder").flag()
    private val json by option("--json", help = "print the result as JSON").flag()
    private val language by option("--lang", help = "language of the report (en, fr)")

    override fun run() {
        val messages = Messages.forLanguage(language)
        val (report, inventory) = Diagnoser().diagnoseFolder(server)
        val workspace = Workspace(server, work ?: Files.createTempDirectory("crashsleuth-bisect"), withWorld)
        workspace.prepare()
        val launcher = ServerLauncher(
            command?.split(' ')?.filter { it.isNotBlank() } ?: LaunchCommand.detect(server, java, memory),
            Duration.ofMinutes(timeout.toLong()),
            Duration.ofSeconds(settle.toLong()),
        )
        val slots = ArrayBlockingQueue<Int>(parallel).apply { (0 until parallel).forEach(::add) }
        val search = CulpritSearch(
            inventory,
            launch = { jars ->
                val slot = slots.take()
                try {
                    launcher.run(workspace.newRun(jars.groupBy { it.folder }.mapValues { (folder, entries) -> entries.map { server.resolve(folder).resolve(it.file) } }, slot))
                } finally {
                    slots.put(slot)
                }
            },
            maxRuns = maxRuns,
            repeat = repeat,
            parallel = parallel,
            progress = { System.err.println(it) },
        )
        val result = try {
            search.search(suspectsOf(report))
        } finally {
            if (!keep) workspace.cleanup()
        }
        echo(if (json) JSON.encodeToString(SearchResult.serializer(), result) else render(result, messages))
    }

    private fun render(result: SearchResult, messages: Messages): String = buildString {
        val minutes = result.runs.sumOf { it.seconds } / 60
        if (!result.reproduced) {
            appendLine(messages.get("bisect.notReproduced"))
            return@buildString
        }
        val crash = result.reference?.situation?.let(messages::title) ?: messages.get("bisect.unexplained")
        appendLine("${messages.get("bisect.reproduced")}: $crash")
        appendLine()
        val names = result.labels.zip(result.culprits).joinToString(", ") { (label, file) -> "$label ($file)" }
        when {
            !result.complete -> appendLine(messages.get("bisect.incomplete", result.runs.size, names))
            result.culprits.size == 1 -> appendLine(messages.get("bisect.single", names))
            else -> appendLine(messages.get("bisect.combination", names))
        }
        appendLine(messages.get("bisect.cost", result.runs.size, "%.1f".format(minutes)))
    }

    private companion object {
        val JSON = Json { prettyPrint = true; encodeDefaults = true }
    }
}

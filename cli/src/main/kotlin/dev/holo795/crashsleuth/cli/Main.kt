package dev.holo795.crashsleuth.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import dev.holo795.crashsleuth.app.Analysis
import dev.holo795.crashsleuth.app.LocalAi
import dev.holo795.crashsleuth.app.Mappings
import dev.holo795.crashsleuth.app.McpServer
import dev.holo795.crashsleuth.app.ModrinthCheck
import dev.holo795.crashsleuth.app.ShareLink
import dev.holo795.crashsleuth.app.Target
import dev.holo795.crashsleuth.engine.Diagnoser
import dev.holo795.crashsleuth.inventory.InstanceScanner
import dev.holo795.crashsleuth.inventory.Inventory
import dev.holo795.crashsleuth.inventory.ModCompare
import dev.holo795.crashsleuth.inventory.PackScanner
import dev.holo795.crashsleuth.model.Messages
import dev.holo795.crashsleuth.model.Report
import dev.holo795.crashsleuth.model.Side
import kotlinx.serialization.json.Json
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

private val JSON = Json { prettyPrint = true; encodeDefaults = true }

class CrashSleuth : CliktCommand(name = "crashsleuth") {
    override fun help(context: Context) = "Find out why Minecraft crashes, and who is to blame."

    override fun run() = Unit
}

class Analyze : CliktCommand(name = "analyze") {
    override fun help(context: Context) =
        "Analyze a server or instance folder (logs and installed mods/plugins), or a single crash report or log file."

    private val targets by argument(help = "server or instance folder, and/or crash reports, latest.log, hs_err_pid files")
        .path(mustExist = true, mustBeReadable = true).multiple(required = true)
    private val json by option("--json", help = "print the report as JSON").flag()
    private val language by option("--lang", help = "language of the report (en, fr)")
    private val online by option("--online", help = "ask Modrinth (by file hash only) whether newer versions of the installed mods exist").flag()
    private val share by option("--share", help = "print a link that opens this report in a browser (the report is inside the link; nothing is uploaded)").flag()
    private val side by option("--side", help = "side a modpack is checked for (server, client)").choice("server", "client").default("server")
    private val explain by option("--explain", help = "ask a model running on this computer (Ollama, or CRASHSLEUTH_AI_URL) to explain the report; only an anonymised summary is sent").flag()
    private val readable by option("--readable", help = "translate game code names in the evidence into Mojang's names (downloads the mappings once)").flag()
    private val server by option("--server", help = "server folder or modpack the player joins: the mods of both sides are compared")
        .path(mustExist = true, mustBeReadable = true)

    override fun run() {
        val folder = targets.firstOrNull { it.isDirectory() }
        // A modpack (.mrpack, CurseForge zip, zipped server folder) is checked without being installed.
        val pack = targets.firstOrNull { !it.isDirectory() && PackScanner.isPack(it) }
        val profiles = targets.filter { it.name.endsWith(".sparkprofile") }
        val files = targets.filterNot { it.isDirectory() || it == pack || it in profiles }
        // Logs given explicitly win; with only a folder, its most recent logs are picked.
        val logs = (files.ifEmpty { folder?.let(Diagnoser::recentLogs).orEmpty() })
            .map { Diagnoser.Log(it.name, it.readText(Charsets.UTF_8)) }
        val inventory = folder?.let(InstanceScanner::scan)
            ?: pack?.let { PackScanner().scan(it, if (side == "client" || server != null) Side.CLIENT else Side.SERVER) }
        val sampled = profiles.flatMap { Diagnoser.profileFile(it) } + (if (profiles.isEmpty()) folder?.let(Diagnoser::profileFindings).orEmpty() else emptyList())
        val analysed = Diagnoser().diagnose(logs, inventory, profiles = sampled)
        val serverInventory = server?.let { if (it.isDirectory()) InstanceScanner.scan(it) else PackScanner().scan(it, Side.SERVER) }
        val diagnosed = if (serverInventory == null || inventory == null) analysed else
            analysed.copy(findings = (ModCompare.compare(inventory, serverInventory) + analysed.findings).sortedByDescending { it.confidence })
        val report = if (online && inventory != null) {
            val minecraft = diagnosed.environment.minecraftVersion ?: inventory.minecraftVersion
                ?: folder?.let { Target.refine(Target.of(it), inventory).minecraft }
            val updates = ModrinthCheck().run { findings(check(inventory, minecraft)) }
            diagnosed.copy(findings = diagnosed.findings + updates)
        } else {
            diagnosed
        }
        val shown = if (!readable) report else report.environment.minecraftVersion?.let { minecraft ->
            val mappings = Mappings.load(minecraft)
            report.copy(
                findings = report.findings.map { it.copy(evidence = it.evidence.map(mappings::translate)) },
                exceptions = report.exceptions.map(mappings::translate),
            )
        } ?: report
        inventory?.skipped?.takeIf { it.isNotEmpty() }?.let { skipped ->
            System.err.println(Messages.forLanguage(language).get("report.skipped", skipped.size, skipped.take(5).joinToString()))
        }
        if (json) {
            echo(JSON.encodeToString(Report.serializer(), shown))
        } else {
            echo(ReportPrinter(Messages.forLanguage(language)).render(shown))
        }
        if (explain) {
            val ai = LocalAi()
            val messages = Messages.forLanguage(language)
            echo("")
            echo(messages.get("report.explanation"))
            echo(runCatching { ai.explain(Analysis(Target.of(targets.first()), shown, inventory), messages) }.getOrElse { messages.get("report.explanation.none", ai.url) })
        }
        if (share) {
            val target = targets.first()
            val analysis = Analysis(Target.of(target), report, inventory)
            echo("")
            echo(ShareLink.link(ShareLink.build(analysis, Messages.forLanguage(language))))
        }
    }
}

class ReadableCommand : CliktCommand(name = "readable") {
    override fun help(context: Context) = "Print a log or crash report with the names of game code translated into Mojang's names."

    private val file by argument(help = "latest.log, debug.log or a crash report").path(mustExist = true, mustBeReadable = true)
    private val minecraft by option("--minecraft", help = "Minecraft version (read from the log when it says it)")

    override fun run() {
        val text = file.readText(Charsets.UTF_8)
        val version = minecraft ?: dev.holo795.crashsleuth.logs.LogAnalyzer().analyze(text).environment.minecraftVersion
            ?: throw com.github.ajalt.clikt.core.UsageError("The log does not say its Minecraft version: give it with --minecraft")
        echo(Mappings.load(version).translate(text))
    }
}

class McpCommand : CliktCommand(name = "mcp") {
    override fun help(context: Context) =
        "Serve CrashSleuth to an assistant over the standard input and output (MCP): analyse, inventory, compare, " +
            "readable, read a configuration file, and the settings it knows with their values."

    override fun run() {
        McpServer().serve(System.`in`.bufferedReader(), System.out.writer())
    }
}

class InventoryCommand : CliktCommand(name = "inventory") {
    override fun help(context: Context) = "List the mods and plugins of a folder with their metadata, as JSON."

    private val folder by argument(help = "server or instance folder, or modpack (.mrpack, .zip)").path(mustExist = true)

    override fun run() = echo(JSON.encodeToString(Inventory.serializer(), if (folder.isDirectory()) InstanceScanner.scan(folder) else PackScanner().scan(folder)))
}

fun main(args: Array<String>) = CrashSleuth().subcommands(Analyze(), ReadableCommand(), InventoryCommand(), McpCommand(), BisectCommand(), MixinsCommand(), RunClientCommand()).main(args)

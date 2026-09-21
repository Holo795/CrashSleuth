package dev.holo795.crashsleuth.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import dev.holo795.crashsleuth.engine.Diagnoser
import dev.holo795.crashsleuth.inventory.InstanceScanner
import dev.holo795.crashsleuth.inventory.Inventory
import dev.holo795.crashsleuth.model.Messages
import dev.holo795.crashsleuth.model.Report
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

    override fun run() {
        val folder = targets.firstOrNull { it.isDirectory() }
        val files = targets.filterNot { it.isDirectory() }
        // Logs given explicitly win; with only a folder, its most recent logs are picked.
        val logs = (files.ifEmpty { folder?.let(Diagnoser::recentLogs).orEmpty() })
            .map { Diagnoser.Log(it.name, it.readText(Charsets.UTF_8)) }
        val report = Diagnoser().diagnose(logs, folder?.let(InstanceScanner::scan))
        if (json) {
            echo(JSON.encodeToString(Report.serializer(), report))
        } else {
            echo(ReportPrinter(Messages.forLanguage(language)).render(report))
        }
    }
}

class InventoryCommand : CliktCommand(name = "inventory") {
    override fun help(context: Context) = "List the mods and plugins of a folder with their metadata, as JSON."

    private val folder by argument(help = "server or instance folder").path(mustExist = true, canBeFile = false)

    override fun run() = echo(JSON.encodeToString(Inventory.serializer(), InstanceScanner.scan(folder)))
}

fun main(args: Array<String>) = CrashSleuth().subcommands(Analyze(), InventoryCommand()).main(args)

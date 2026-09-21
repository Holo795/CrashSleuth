package dev.holo795.crashsleuth.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import dev.holo795.crashsleuth.logs.LogAnalyzer
import dev.holo795.crashsleuth.model.Messages
import dev.holo795.crashsleuth.model.Report
import kotlinx.serialization.json.Json

class CrashSleuth : CliktCommand(name = "crashsleuth") {
    override fun help(context: Context) = "Find out why Minecraft crashes, and who is to blame."

    override fun run() = Unit
}

class Analyze : CliktCommand(name = "analyze") {
    override fun help(context: Context) = "Analyze a crash report or a log file."

    private val file by argument(help = "crash report, latest.log, debug.log or hs_err_pid file")
        .path(mustExist = true, canBeDir = false, mustBeReadable = true)
    private val json by option("--json", help = "print the report as JSON").flag()
    private val language by option("--lang", help = "language of the report (en, fr)")

    override fun run() {
        val report = LogAnalyzer().analyze(file)
        if (json) {
            echo(JSON.encodeToString(Report.serializer(), report))
        } else {
            echo(ReportPrinter(Messages.forLanguage(language)).render(report))
        }
    }

    private companion object {
        val JSON = Json { prettyPrint = true; encodeDefaults = true }
    }
}

fun main(args: Array<String>) = CrashSleuth().subcommands(Analyze()).main(args)

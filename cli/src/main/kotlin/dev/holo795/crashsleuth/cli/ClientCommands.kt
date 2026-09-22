package dev.holo795.crashsleuth.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.holo795.crashsleuth.runner.ClientInstaller
import dev.holo795.crashsleuth.runner.ClientLaunch
import java.time.Duration
import kotlin.io.path.createDirectories

class RunClientCommand : CliktCommand(name = "run-client") {
    override fun help(context: Context) =
        "Start the game client once in a small window, with the tool's own Minecraft install, and say whether it started."

    private val gameDir by argument(help = "game folder (its mods/ and config/ are used as they are)").path()
    private val minecraft by option("--minecraft", help = "Minecraft version").required()
    private val loader by option("--loader", help = "vanilla, fabric, neoforge or forge").default("vanilla")
    private val java by option("--java", help = "Java executable").default("java")
    private val timeout by option("--timeout", help = "minutes the game may take to reach the title screen").int().default(5)
    private val settle by option("--settle", help = "seconds the game stays open once ready").int().default(10)
    private val show by option("--show", help = "leave the game window in front (it is hidden by default, the verdict comes from the logs)").flag()
    private val join by option("--join", help = "host:port of a server to join, or world:<save folder> to open, as soon as the game has started")

    override fun run() {
        gameDir.createDirectories()
        val installer = ClientInstaller()
        val profile = installer.install(minecraft, loader)
        val launcher = ClientLaunch.launcher(installer, profile, java, Duration.ofMinutes(timeout.toLong()), Duration.ofSeconds(settle.toLong()), joining = join != null, windows = dev.holo795.crashsleuth.runner.WindowKeeper.forThisSystem(show))
        val command = installer.command(profile, gameDir.toAbsolutePath(), java, join = join)
        if (System.getenv("CRASHSLEUTH_DEBUG") != null) System.err.println(command.joinToString("\n"))
        val result = launcher.withCommand(command).run(gameDir)
        echo("${result.outcome} in ${"%.0f".format(result.seconds)} s")
        if (result.outcome != dev.holo795.crashsleuth.runner.Outcome.READY) {
            echo(result.logs.first().text.lines().takeLast(25).joinToString("\n"))
        }
    }
}

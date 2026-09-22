package dev.holo795.crashsleuth.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import dev.holo795.crashsleuth.inventory.InstanceScanner
import dev.holo795.crashsleuth.inventory.Injection
import dev.holo795.crashsleuth.inventory.MixinIndex
import dev.holo795.crashsleuth.inventory.PackScanner
import dev.holo795.crashsleuth.model.Side
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.io.path.isDirectory

class MixinsCommand : CliktCommand(name = "mixins") {
    override fun help(context: Context) = "List which mods change which methods of the game through mixins, and where they collide."

    private val target by argument(help = "server or instance folder, or modpack (.mrpack, .zip)").path(mustExist = true)
    private val side by option("--side", help = "side a modpack is read for (server, client)").choice("server", "client").default("server")
    private val json by option("--json", help = "print every injection as JSON").flag()

    override fun run() {
        val inventory = if (target.isDirectory()) InstanceScanner.scan(target) else PackScanner().scan(target, if (side == "client") Side.CLIENT else Side.SERVER)
        val index = MixinIndex.build(inventory.jars)
        if (json) {
            echo(Json { prettyPrint = true }.encodeToString(ListSerializer(Injection.serializer()), index.injections))
            return
        }
        val mods = index.injections.groupBy { it.modId }
        echo("${index.injections.size} injections from ${mods.size} mods into ${index.injections.map { it.targetClass }.distinct().size} classes")
        fun describe(group: List<Injection>) = group.joinToString(" / ") { "${it.modId} (${it.mixin})" }
        index.redirectConflicts().forEach { echo("redirect conflict ${it.first().targetClass}.${it.first().method} -> ${it.first().at}: ${describe(it)}") }
        index.overwriteConflicts().forEach { echo("overwrite conflict ${it.first().targetClass}.${it.first().method}: ${describe(it)}") }
    }
}

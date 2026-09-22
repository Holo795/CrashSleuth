package dev.holo795.crashsleuth.inventory

import dev.holo795.crashsleuth.model.Confidence
import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.PlatformKind
import dev.holo795.crashsleuth.model.Side
import dev.holo795.crashsleuth.model.Situation

/**
 * Compares the mods of a player's game with those of the server it joins. Only mods that add content of their
 * own count: the game refuses to connect when the two sides do not register the same blocks, items and
 * recipes, while performance and interface mods may live on one side only.
 */
object ModCompare {
    private val PLATFORM_IDS = setOf("minecraft", "java", "fabricloader", "fabric", "quilt_loader", "neoforge", "forge")

    fun compare(player: Inventory, server: Inventory): List<Finding> = buildList {
        val playerMods = mods(player)
        val serverMods = mods(server)
        val onPlayer = available(player)
        val onServer = available(server)

        if (player.platform != server.platform && player.platform.kind == PlatformKind.MODS && server.platform.kind == PlatformKind.MODS) {
            add(Finding(
                Situation.MOD_MISMATCH, Confidence.CERTAIN, emptyList(),
                evidence = listOf("${player.platform.displayName} ≠ ${server.platform.displayName}"),
                details = mapOf("adviceKey" to "compare.loader", "player" to player.platform.displayName, "server" to server.platform.displayName),
            ))
        }
        val (playerGame, serverGame) = player.minecraftVersion to server.minecraftVersion
        if (playerGame != null && serverGame != null && playerGame != serverGame && server.platform.kind == PlatformKind.MODS) {
            add(Finding(
                Situation.WRONG_MC, Confidence.CERTAIN, emptyList(),
                evidence = listOf("Minecraft $playerGame ≠ $serverGame"),
                details = mapOf("adviceKey" to "compare.minecraft", "player" to playerGame, "server" to serverGame),
            ))
        }
        serverMods.filter { it.mod.environment != Side.SERVER && it.needed && it.mod.id !in onPlayer }.forEach {
            add(missing(it, where = "player"))
        }
        playerMods.filter { it.mod.environment != Side.CLIENT && it.needed && it.mod.id !in onServer }.forEach {
            add(missing(it, where = "server"))
        }
        val serverById = serverMods.associateBy { it.mod.id }
        playerMods.filter { it.needed }.forEach { mine ->
            val theirs = serverById[mine.mod.id] ?: return@forEach
            if (mine.mod.version != null && theirs.mod.version != null && mine.mod.version != theirs.mod.version) {
                add(Finding(
                    Situation.MOD_MISMATCH, Confidence.MEDIUM, listOf(culprit(mine)),
                    evidence = listOf("${mine.jar.folder}/${mine.jar.file} (${mine.mod.version}) ≠ ${theirs.jar.folder}/${theirs.jar.file} (${theirs.mod.version})"),
                    details = mapOf("adviceKey" to "compare.version", "player" to mine.mod.version, "server" to theirs.mod.version),
                ))
            }
        }
    }

    private class Installed(val jar: JarEntry, val mod: ModMetadata) {
        /** Both sides must have it: it adds content and does not say players may go without it. */
        val needed get() = mod.addsContent && !mod.optionalOnClient && mod.id !in PLATFORM_IDS
    }

    private fun mods(inventory: Inventory): List<Installed> =
        inventory.jars.filter { it.folder == "mods" && it.error == null }.flatMap { jar -> jar.mods.filterNot { it.format.isPlugin }.map { Installed(jar, it) } }

    /** Everything a side answers to: top-level mods, the mods nested inside them and the ids they provide. */
    private fun available(inventory: Inventory): Set<String> =
        inventory.jars.filter { it.folder == "mods" }.flatMap { jar -> (jar.mods + jar.nested).flatMap { listOf(it.id) + it.provides } }.toSet()

    private fun culprit(installed: Installed) = Culprit(CulpritKind.MOD, installed.mod.id, installed.mod.name, installed.mod.version, installed.jar.file)

    private fun missing(installed: Installed, where: String) = Finding(
        Situation.MOD_MISMATCH, Confidence.HIGH, listOf(culprit(installed)),
        evidence = listOf("${installed.jar.folder}/${installed.jar.file}"),
        details = mapOf("adviceKey" to "compare.missing-on-$where", "missing" to where),
    )
}

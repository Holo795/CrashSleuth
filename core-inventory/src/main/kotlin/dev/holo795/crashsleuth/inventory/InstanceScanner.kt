package dev.holo795.crashsleuth.inventory

import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Side
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Builds the inventory of a server folder or a client instance (.minecraft, launcher instance). */
object InstanceScanner {
    fun scan(root: Path): Inventory {
        val jars = listOf("mods", "plugins").flatMap { folder ->
            JarScanner.jarsIn(root.resolve(folder)).map { JarScanner.scan(it, folder) }
        }
        val (platform, loaderVersion) = platform(root)
        return Inventory(
            platform = platform,
            minecraftVersion = minecraftVersion(root),
            loaderVersion = loaderVersion,
            side = if (root.resolve("server.properties").exists() || root.resolve("eula.txt").exists()) Side.SERVER else Side.UNKNOWN,
            jars = jars,
        )
    }

    private fun children(path: Path): List<Path> = if (path.isDirectory()) path.listDirectoryEntries().sorted() else emptyList()

    private fun platform(root: Path): Pair<Platform, String?> {
        val libraries = root.resolve("libraries")
        children(libraries.resolve("net/neoforged/neoforge")).lastOrNull()?.let { return Platform.NEOFORGE to it.name }
        children(libraries.resolve("net/neoforged/forge")).lastOrNull()?.let { return Platform.NEOFORGE to it.name.substringAfter('-') }
        children(libraries.resolve("net/minecraftforge/forge")).lastOrNull()?.let { return Platform.FORGE to it.name.substringAfter('-') }
        children(libraries.resolve("org/quiltmc/quilt-loader")).lastOrNull()?.let { return Platform.QUILT to it.name }
        children(libraries.resolve("net/fabricmc/fabric-loader")).lastOrNull()?.let { return Platform.FABRIC to it.name }
        if (root.resolve(".fabric").isDirectory() || root.resolve("fabric-server-launch.jar").exists()) return Platform.FABRIC to null
        // Paper and its forks unpack the server into versions/<minecraft>/<software>-<minecraft>.jar
        val unpacked = children(root.resolve("versions")).flatMap(::children).map { it.name.lowercase() }
        for ((prefix, platform) in listOf("folia" to Platform.FOLIA, "purpur" to Platform.PURPUR, "paper" to Platform.PAPER)) {
            if (unpacked.any { it.startsWith(prefix) }) return platform to null
        }
        if (root.resolve("plugins").isDirectory() || root.resolve("bukkit.yml").exists()) {
            return (if (root.resolve("config/paper-global.yml").exists()) Platform.PAPER else Platform.SPIGOT) to null
        }
        if (unpacked.any { it.startsWith("server-") }) return Platform.VANILLA to null
        return Platform.UNKNOWN to null
    }

    private val RELEASE = Regex("""^\d+(\.\d+)+$""")

    private fun minecraftVersion(root: Path): String? {
        // Forge and NeoForge: libraries/net/minecraft/server/<minecraft>-<mcp>
        children(root.resolve("libraries/net/minecraft/server")).lastOrNull()?.let { return it.name.substringBefore('-') }
        children(root.resolve("versions")).map { it.name }.lastOrNull { RELEASE.matches(it) || it.contains('w') }?.let { return it }
        return Files.newDirectoryStream(root, "*.jar").use { stream ->
            stream.asSequence().mapNotNull { Regex("""(\d+\.\d+(?:\.\d+)?)""").find(it.name)?.value }.firstOrNull()
        }
    }
}

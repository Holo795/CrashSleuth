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
            side = if (root.resolve("server.properties").exists() || root.resolve("eula.txt").exists() || root.resolve("velocity.toml").exists()) Side.SERVER else Side.UNKNOWN,
            jars = jars,
            files = ServerFilesScanner.scan(root),
        )
    }

    private fun children(path: Path): List<Path> = if (path.isDirectory()) path.listDirectoryEntries().sorted() else emptyList()

    private fun platform(root: Path): Pair<Platform, String?> {
        // Proxies: Velocity writes velocity.toml, BungeeCord and Waterfall ship their own jar.
        if (root.resolve("velocity.toml").exists()) return Platform.VELOCITY to null
        val rootJars = runCatching { root.listDirectoryEntries("*.jar").map { it.name.lowercase() } }.getOrDefault(emptyList())
        if (rootJars.any { it.startsWith("bungeecord") || it.startsWith("waterfall") }) return Platform.BUNGEECORD to null
        val libraries = root.resolve("libraries")
        children(libraries.resolve("net/neoforged/neoforge")).lastOrNull()?.let { return Platform.NEOFORGE to it.name }
        children(libraries.resolve("net/neoforged/forge")).lastOrNull()?.let { return Platform.NEOFORGE to it.name.substringAfter('-') }
        children(libraries.resolve("net/minecraftforge/forge")).lastOrNull()?.let { return Platform.FORGE to it.name.substringAfter('-') }
        children(libraries.resolve("org/quiltmc/quilt-loader")).lastOrNull()?.let { return Platform.QUILT to it.name }
        children(libraries.resolve("net/fabricmc/fabric-loader")).lastOrNull()?.let { return Platform.FABRIC to it.name }
        if (root.resolve(".fabric").isDirectory() || root.resolve("fabric-server-launch.jar").exists()) return Platform.FABRIC to null
        // Paper and its forks unpack the server into versions/<minecraft>/<software>-<minecraft>.jar
        val unpacked = children(root.resolve("versions")).flatMap(::children).map { it.name.lowercase() }
        // Never started: the Paperclip jar lists the server it holds in META-INF/versions.list.
        val bundled = unpacked + runCatching { root.listDirectoryEntries("*.jar") }.getOrDefault(emptyList()).flatMap(::paperclipContent)
        for ((prefix, platform) in listOf("folia" to Platform.FOLIA, "purpur" to Platform.PURPUR, "paper" to Platform.PAPER)) {
            if (bundled.any { it.startsWith(prefix) }) return platform to null
        }
        if (root.resolve("plugins").isDirectory() || root.resolve("bukkit.yml").exists()) {
            return (if (root.resolve("config/paper-global.yml").exists()) Platform.PAPER else Platform.SPIGOT) to null
        }
        if (unpacked.any { it.startsWith("server-") }) return Platform.VANILLA to null
        return Platform.UNKNOWN to null
    }

    /** "hash\t1.21.1\t1.21.1/paper-1.21.1.jar" lines of a Paperclip jar: the file names, lower case. */
    private fun paperclipContent(jar: Path): List<String> = runCatching {
        java.util.zip.ZipFile(jar.toFile()).use { zip ->
            val entry = zip.getEntry("META-INF/versions.list") ?: return emptyList()
            zip.getInputStream(entry).use { it.readBytes().decodeToString() }.lines()
                .mapNotNull { line -> line.split('\t').getOrNull(2)?.substringAfterLast('/')?.lowercase() }
        }
    }.getOrDefault(emptyList())

    private val RELEASE = Regex("""^\d+(\.\d+)+$""")

    private fun minecraftVersion(root: Path): String? {
        // Forge and NeoForge: libraries/net/minecraft/server/<minecraft>-<mcp>
        children(root.resolve("libraries/net/minecraft/server")).lastOrNull()?.let { return it.name.substringBefore('-') }
        children(root.resolve("versions")).map { it.name }.lastOrNull { RELEASE.matches(it) || it.contains('w') }?.let { return it }
        val jars = Files.newDirectoryStream(root, "*.jar").use { it.toList() }
        return jars.firstNotNullOfOrNull { Regex("""(\d+\.\d+(?:\.\d+)?)""").find(it.name)?.value }
            ?: jars.firstNotNullOfOrNull(::bundledVersion)
    }

    /** A server.jar that was never started: vanilla and Paper jars carry the game's version.json at their root. */
    private fun bundledVersion(jar: Path): String? = runCatching {
        java.util.zip.ZipFile(jar.toFile()).use { zip ->
            val entry = zip.getEntry("version.json") ?: return null
            val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
            Regex(""""id"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
        }
    }.getOrNull()
}

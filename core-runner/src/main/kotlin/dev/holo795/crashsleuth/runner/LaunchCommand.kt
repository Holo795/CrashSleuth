package dev.holo795.crashsleuth.runner

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Finds how to start a server folder, the way its own start script would. */
object LaunchCommand {
    private val WINDOWS = System.getProperty("os.name").lowercase().contains("win")

    /**
     * NeoForge and Forge: `@user_jvm_args.txt @libraries/.../unix_args.txt`; others: `-jar <server jar>`.
     * [memory] (for example "4G") is added unless the server's own JVM arguments already set it.
     */
    fun detect(server: Path, java: String = "java", memory: String? = null): List<String> {
        val userArgs = server.resolve("user_jvm_args.txt").takeIf { it.exists() }
        val setsMemory = userArgs?.let { file -> file.toFile().readLines().any { it.trim().startsWith("-Xmx") } } == true
        val heap = if (memory != null) listOf("-Xmx$memory") else if (setsMemory) emptyList() else listOf("-Xmx4G")
        val argsFile = if (WINDOWS) "win_args.txt" else "unix_args.txt"
        val loaderArgs = listOf("net/neoforged/neoforge", "net/neoforged/forge", "net/minecraftforge/forge").firstNotNullOfOrNull { group ->
            val versions = server.resolve("libraries").resolve(group)
            if (!versions.isDirectory()) null else versions.listDirectoryEntries().sorted().lastOrNull { it.resolve(argsFile).exists() }?.resolve(argsFile)
        }
        if (loaderArgs != null) {
            return listOf(java) + listOfNotNull(userArgs?.let { "@${it.name}" }) + heap + "@${server.relativize(loaderArgs)}" + "nogui"
        }
        return listOf(java) + heap + listOf("-jar", serverJar(server).name, "nogui")
    }

    /** Name prefixes of server jars, most specific first. */
    private val PREFERRED = listOf(
        "fabric-server-launch", "quilt-server-launch", "paper", "purpur", "folia", "pufferfish", "spigot",
        "fabric", "quilt", "minecraft_server", "server",
    )

    fun serverJar(server: Path): Path {
        val jars = server.listDirectoryEntries("*.jar").filterNot { it.name.contains("installer", ignoreCase = true) }.sorted()
        return PREFERRED.firstNotNullOfOrNull { prefix -> jars.firstOrNull { it.name.startsWith(prefix, ignoreCase = true) } }
            ?: jars.singleOrNull()
            ?: error("No server jar found in $server: give the start command with --command")
    }
}

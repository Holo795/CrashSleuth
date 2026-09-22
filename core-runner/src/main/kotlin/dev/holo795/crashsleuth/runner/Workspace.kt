package dev.holo795.crashsleuth.runner

import java.net.ServerSocket
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A working copy of a server, so test launches never touch the user's files. The copy is made once;
 * each launch gets a fresh run folder where the big read-mostly folders (libraries, versions...) are
 * linked instead of copied, and where only the chosen mods or plugins are put.
 */
class Workspace(
    private val source: Path,
    private val work: Path,
    /** Copy the worlds into every run (needed for crashes that happen in a world). */
    private val withWorld: Boolean = false,
) {
    private val base = work.resolve("base")
    private val run = work.resolve("run")

    /** Jar folders whose content is chosen for each launch. */
    val jarFolders = listOf("mods", "plugins")

    private val worlds: Set<String> by lazy {
        val level = properties(source)["level-name"]?.toString()?.ifBlank { null } ?: "world"
        setOf(level, "${level}_nether", "${level}_the_end")
    }

    /** Copies the server once, without its mods, plugins, logs, and worlds unless asked. */
    fun prepare() {
        deleteRecursively(work)
        base.createDirectories()
        copyTree(source, base) { relative ->
            val parts = relative.map { it.toString() }
            val top = parts.first()
            when {
                top in SKIPPED -> false
                top.startsWith("hs_err_pid") -> false
                !withWorld && top in worlds -> false
                top in jarFolders && parts.size == 2 && relative.name.endsWith(".jar", ignoreCase = true) -> false
                top == "plugins" && parts.getOrNull(1) == ".paper-remapped" -> false
                else -> true
            }
        }
    }

    /** A clean run folder containing [jars] (paths inside the source server, keyed by their folder). */
    fun newRun(jars: Map<String, List<Path>>): Path {
        deleteRecursively(run)
        run.createDirectories()
        base.listDirectoryEntries().forEach { entry ->
            val target = run.resolve(entry.name)
            if (entry.name in SHARED && entry.isDirectory()) {
                Files.createSymbolicLink(target, entry.toAbsolutePath())
            } else {
                copyTree(entry, target) { true }
            }
        }
        jars.forEach { (folder, files) ->
            val directory = run.resolve(folder).createDirectories()
            files.forEach { Files.copy(it, directory.resolve(it.name), StandardCopyOption.REPLACE_EXISTING) }
        }
        isolate(run)
        return run
    }

    /** Own port, no remote console or query: a test launch must not collide with a running server. */
    private fun isolate(directory: Path) {
        val file = directory.resolve("server.properties")
        val lines = if (file.exists()) file.readText().lines().filter { it.isNotBlank() } else emptyList()
        val overrides = mapOf(
            "server-port" to freePort().toString(),
            "enable-rcon" to "false",
            "enable-query" to "false",
            "query.port" to freePort().toString(),
        )
        val kept = lines.filterNot { line -> overrides.keys.any { line.startsWith("$it=") } }
        file.writeText((kept + overrides.map { (key, value) -> "$key=$value" }).joinToString("\n") + "\n")
    }

    fun cleanup() = deleteRecursively(work)

    companion object {
        /** Big folders the launches only read (or fill identically): linked, not copied. */
        private val SHARED = setOf("libraries", "versions", "cache", ".fabric", "bundler", ".mixin.out")
        private val SKIPPED = setOf("logs", "crash-reports", "debug", ".crashsleuth-work")

        fun properties(directory: Path): Properties = Properties().also { properties ->
            val file = directory.resolve("server.properties")
            if (file.exists()) Files.newBufferedReader(file).use(properties::load)
        }

        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /** Copies [from] into [to]; [include] sees paths relative to [from] and can prune whole folders. */
        private fun copyTree(from: Path, to: Path, include: (Path) -> Boolean) {
            if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
                to.parent?.createDirectories()
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS)
                return
            }
            Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = from.relativize(dir)
                    if (relative.toString().isNotEmpty() && !include(relative)) return FileVisitResult.SKIP_SUBTREE
                    to.resolve(relative.toString()).createDirectories()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = from.relativize(file)
                    if (include(relative)) {
                        Files.copy(file, to.resolve(relative.toString()), StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS)
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }

        fun deleteRecursively(path: Path) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(path)
                return
            }
            Files.list(path).use { children -> children.toList() }.forEach(::deleteRecursively)
            Files.delete(path)
        }
    }
}

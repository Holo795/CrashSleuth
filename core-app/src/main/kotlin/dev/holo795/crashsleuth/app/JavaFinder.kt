package dev.holo795.crashsleuth.app

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines

/** One Java installation found on the computer. */
@Serializable
data class JavaInstall(val executable: String, val major: Int, val version: String, val vendor: String? = null)

/**
 * Finds the Java installations of the computer, so nobody has to type a path: the usual folders of
 * Windows, macOS and Linux, the ones of the common installers, and JAVA_HOME.
 */
object JavaFinder {
    private val WINDOWS = System.getProperty("os.name").lowercase().contains("win")

    fun find(): List<JavaInstall> {
        val home = Path.of(System.getProperty("user.home"))
        val roots = buildList {
            System.getenv("JAVA_HOME")?.let { add(Path.of(it)) }
            add(Path.of(System.getProperty("java.home")))
            if (WINDOWS) {
                listOf("ProgramFiles", "ProgramFiles(x86)").mapNotNull(System::getenv).forEach { programs ->
                    listOf("Java", "Eclipse Adoptium", "Microsoft", "Zulu", "BellSoft", "Amazon Corretto", "Semeru").forEach { add(Path.of(programs, it)) }
                }
            } else {
                add(Path.of("/Library/Java/JavaVirtualMachines"))
                add(home.resolve("Library/Java/JavaVirtualMachines"))
                add(Path.of("/opt/homebrew/opt"))
                add(Path.of("/usr/local/opt"))
                add(Path.of("/usr/lib/jvm"))
                add(Path.of("/usr/java"))
            }
            add(home.resolve(".sdkman/candidates/java"))
            add(home.resolve(".jdks"))
            // Java bundled by the Minecraft launcher and by Prism.
            add(home.resolve(".minecraft/runtime"))
            add(home.resolve("AppData/Roaming/.minecraft/runtime"))
            add(home.resolve("Library/Application Support/minecraft/runtime"))
            add(home.resolve(".local/share/PrismLauncher/java"))
        }
        return roots.flatMap(::candidates).mapNotNull(::describe).distinctBy { it.executable }.sortedByDescending { it.major }
    }

    /** The newest installation at least [major], else the newest of all. */
    fun best(major: Int?): JavaInstall? = find().let { all -> all.filter { major == null || it.major >= major }.minByOrNull { it.major } ?: all.firstOrNull() }

    /** Java homes up to three levels below [root] (macOS nests them in Contents/Home). */
    private fun candidates(root: Path): List<Path> {
        if (!root.isDirectory()) return emptyList()
        val result = mutableListOf<Path>()
        fun visit(path: Path, depth: Int) {
            if (path.resolve("release").exists() && executable(path).exists()) {
                result.add(path)
                return
            }
            if (depth == 0) return
            runCatching { path.listDirectoryEntries().filter { it.isDirectory() } }.getOrDefault(emptyList()).forEach { visit(it, depth - 1) }
        }
        visit(root, 4)
        return result
    }

    private fun executable(home: Path): Path = home.resolve("bin").resolve(if (WINDOWS) "java.exe" else "java")

    /** Reads the `release` file every JDK and JRE ships, without starting Java. */
    private fun describe(home: Path): JavaInstall? {
        val release = runCatching { home.resolve("release").readLines() }.getOrNull() ?: return null
        fun value(key: String) = release.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim('"')
        val version = value("JAVA_VERSION") ?: return null
        val major = version.split('.', '_', '+', '-').mapNotNull { it.toIntOrNull() }.let { if (it.firstOrNull() == 1) it.getOrNull(1) else it.firstOrNull() } ?: return null
        return JavaInstall(executable(home).toRealPathOrSelf().toString(), major, version, value("IMPLEMENTOR"))
    }

    private fun Path.toRealPathOrSelf(): Path = runCatching { toRealPath() }.getOrDefault(this).takeIf { Files.exists(it) } ?: this
}

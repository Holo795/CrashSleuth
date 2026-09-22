package dev.holo795.crashsleuth.app

import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shows a file or a folder to the person in their own file manager, and opens web pages: the report can then
 * say "here is the culprit" and take them to it. Each system has its own way of selecting a file.
 */
object Reveal {
    private val os = System.getProperty("os.name").lowercase()

    /** The file's folder, with the file itself selected when the system can do it. */
    fun file(path: Path): Boolean {
        val target = path.toAbsolutePath()
        val command = when {
            os.startsWith("mac") -> listOf("open", "-R", target.toString())
            os.startsWith("windows") -> listOf("explorer.exe", "/select,${target}")
            else -> null
        }
        if (command != null && run(command)) return true
        return folder(target.parent ?: target)
    }

    fun folder(path: Path): Boolean {
        val target = path.toAbsolutePath()
        if (!Files.isDirectory(target)) return file(target)
        val command = when {
            os.startsWith("mac") -> listOf("open", target.toString())
            os.startsWith("windows") -> listOf("explorer.exe", target.toString())
            else -> listOf("xdg-open", target.toString())
        }
        if (run(command)) return true
        return runCatching { Desktop.getDesktop().open(target.toFile()); true }.getOrDefault(false)
    }

    fun page(url: String): Boolean {
        val command = when {
            os.startsWith("mac") -> listOf("open", url)
            os.startsWith("windows") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            else -> listOf("xdg-open", url)
        }
        if (run(command)) return true
        return runCatching { Desktop.getDesktop().browse(URI(url)); true }.getOrDefault(false)
    }

    /** Windows' explorer.exe answers 1 even when it worked, so only a failure to start counts. */
    private fun run(command: List<String>): Boolean = runCatching { ProcessBuilder(command).start(); true }.getOrDefault(false)
}

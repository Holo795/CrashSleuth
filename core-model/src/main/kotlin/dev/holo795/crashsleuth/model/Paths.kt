package dev.holo795.crashsleuth.model

import java.io.File
import java.nio.file.Path

/**
 * A file inside a folder, written the way the game and its logs write it: "config/foo.toml", with forward
 * slashes on every system. Windows would otherwise give "config\foo.toml" in reports and share links.
 */
fun Path.insideOf(root: Path): String = root.relativize(this).toString().replace(File.separatorChar, '/')

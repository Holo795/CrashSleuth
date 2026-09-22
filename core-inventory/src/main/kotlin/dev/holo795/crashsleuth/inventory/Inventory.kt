package dev.holo795.crashsleuth.inventory

import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Side
import kotlinx.serialization.Serializable

/** Kind of metadata file a jar declares itself with. */
@Serializable
enum class MetadataFormat(val isPlugin: Boolean) {
    FABRIC(false),
    QUILT(false),
    NEOFORGE(false),
    FORGE(false),
    BUKKIT(true),
    PAPER(true),
    BUNGEE(true),
    VELOCITY(true),
}

@Serializable
data class Dependency(
    val id: String,
    val versionRange: String? = null,
    val required: Boolean = true,
    /** Side the dependency applies to, when the metadata restricts it (CLIENT or SERVER). */
    val side: Side = Side.UNKNOWN,
)

/** One mod or plugin, as declared by its metadata. A jar can declare several (multi-loader jars, nested jars). */
@Serializable
data class ModMetadata(
    val format: MetadataFormat,
    val id: String,
    val name: String? = null,
    val version: String? = null,
    /** Side the mod is made for (Fabric "environment"); UNKNOWN means both. */
    val environment: Side = Side.UNKNOWN,
    val dependencies: List<Dependency> = emptyList(),
    val breaks: List<Dependency> = emptyList(),
    /** Other ids this mod answers to (Fabric "provides", Bukkit "provides"). */
    val provides: List<String> = emptyList(),
    /** Minimum game API a plugin asks for (Bukkit/Paper "api-version"). */
    val apiVersion: String? = null,
)

@Serializable
data class JarEntry(
    /** File name inside its folder. */
    val file: String,
    /** Folder relative to the server root: "mods" or "plugins". */
    val folder: String,
    val size: Long = 0,
    /** Mods declared at the top level of the jar. */
    val mods: List<ModMetadata> = emptyList(),
    /** Mods shipped inside the jar (jar-in-jar); they satisfy dependencies but are not duplicates. */
    val nested: List<ModMetadata> = emptyList(),
    /** Set when the jar cannot be opened at all. */
    val error: String? = null,
    /** Java release its classes are compiled for (class file major version - 44); null without classes. */
    val javaVersion: Int? = null,
) {
    val formats: Set<MetadataFormat> get() = mods.map { it.format }.toSet()
}

/** Everything installed on a server or client instance, without the jars themselves: small enough to share. */
@Serializable
data class Inventory(
    val platform: Platform = Platform.UNKNOWN,
    val minecraftVersion: String? = null,
    val loaderVersion: String? = null,
    val side: Side = Side.UNKNOWN,
    val jars: List<JarEntry> = emptyList(),
    /** Files of a pack that could not be read (CurseForge files, untrusted download hosts). */
    val skipped: List<String> = emptyList(),
)

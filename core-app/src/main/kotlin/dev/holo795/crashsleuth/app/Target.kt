package dev.holo795.crashsleuth.app

import dev.holo795.crashsleuth.inventory.Inventory
import dev.holo795.crashsleuth.inventory.MetadataFormat
import dev.holo795.crashsleuth.inventory.PackScanner
import dev.holo795.crashsleuth.model.Platform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** What the user gave: a server, a game folder, a modpack file, or a single log. */
@Serializable
enum class TargetKind { SERVER, CLIENT, PACK, MODS, LOG }

/** A target and what could be learned about it before any analysis. */
@Serializable
data class Target(
    val path: String,
    val kind: TargetKind,
    /** Game version, when the folder or the launcher says it. */
    val minecraft: String? = null,
    /** "fabric", "neoforge", "paper"... */
    val loader: String? = null,
) {
    val name: String get() = Path.of(path).name

    /** Only folders can be launched; the client search supports vanilla, Fabric and NeoForge. */
    val searchable: Boolean get() = when (kind) {
        TargetKind.SERVER -> true
        TargetKind.CLIENT -> loader == null || loader in setOf("fabric", "vanilla", "neoforge", "forge")
        else -> false
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Recognises the target and reads the versions that launchers write next to the game (Prism, MultiMC, CurseForge). */
        fun of(path: Path): Target {
            if (!path.isDirectory()) {
                return Target(path.toString(), if (PackScanner.isPack(path)) TargetKind.PACK else TargetKind.LOG)
            }
            val server = path.resolve("server.properties").exists() || path.resolve("eula.txt").exists() ||
                path.resolve("run.sh").exists() || path.resolve("run.bat").exists() || path.resolve("plugins").isDirectory()
            if (server) return Target(path.toString(), TargetKind.SERVER)
            // Jars in the folder itself, and nothing else: a list of mods or plugins, for the side the user chose.
            val looseJars = runCatching { path.listDirectoryEntries("*.jar").isNotEmpty() }.getOrDefault(false)
            if (looseJars && !path.resolve("mods").isDirectory() && !path.resolve("config").isDirectory()) return Target(path.toString(), TargetKind.MODS)
            // A Prism or MultiMC instance keeps the game in .minecraft (or minecraft) next to mmc-pack.json.
            val game = listOf(".minecraft", "minecraft").map(path::resolve).firstOrNull { it.resolve("mods").isDirectory() } ?: path
            val (minecraft, loader) = prism(path) ?: curseForge(path) ?: (null to null)
            return Target(game.toString(), TargetKind.CLIENT, minecraft, loader)
        }

        private fun prism(instance: Path): Pair<String?, String?>? {
            val file = instance.resolve("mmc-pack.json").takeIf { it.exists() } ?: return null
            val components = runCatching { json.parseToJsonElement(file.readText()).jsonObject.getValue("components").jsonArray }.getOrNull() ?: return null
            fun version(uid: String) = components.map { it.jsonObject }.firstOrNull { it["uid"]?.jsonPrimitive?.content == uid }?.get("version")?.jsonPrimitive?.content
            val loader = when {
                version("net.fabricmc.fabric-loader") != null -> "fabric"
                version("net.neoforged") != null -> "neoforge"
                version("net.minecraftforge") != null -> "forge"
                version("org.quiltmc.quilt-loader") != null -> "quilt"
                else -> "vanilla"
            }
            return version("net.minecraft") to loader
        }

        private fun curseForge(instance: Path): Pair<String?, String?>? {
            val file = instance.resolve("minecraftinstance.json").takeIf { it.exists() } ?: return null
            val root = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
            val loaderName = root["baseModLoader"]?.jsonObject?.get("name")?.jsonPrimitive?.content?.lowercase()
            return root["gameVersion"]?.jsonPrimitive?.content to loaderName?.substringBefore('-')
        }

        /**
         * What the analysis adds: a client folder that no launcher describes gets its loader from its mods,
         * and its version from what most of them ask for.
         */
        fun refine(target: Target, inventory: Inventory?): Target {
            if (inventory == null || target.kind != TargetKind.CLIENT) return target
            val formats = inventory.jars.flatMap { jar -> jar.mods.map { it.format } }
            val loader = target.loader ?: when {
                formats.isEmpty() -> "vanilla"
                formats.all { it == MetadataFormat.FABRIC } -> "fabric"
                formats.all { it == MetadataFormat.QUILT || it == MetadataFormat.FABRIC } -> "quilt"
                formats.any { it == MetadataFormat.NEOFORGE } -> "neoforge"
                formats.any { it == MetadataFormat.FORGE } -> "forge"
                else -> null
            }
            val minecraft = target.minecraft ?: inventory.minecraftVersion ?: inventory.jars.asSequence()
                .flatMap { jar -> jar.mods.asSequence().flatMap { mod -> mod.dependencies.asSequence() } }
                .filter { it.id == "minecraft" }
                .mapNotNull { dependency -> dependency.versionRange?.let(::exactVersion) }
                // The most precise of the most asked: "1.21.1" beats "1.21".
                .groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.key.count { c -> c == '.' } }.thenByDescending { it.value }).firstOrNull()?.key
            return target.copy(minecraft = minecraft, loader = loader)
        }

        /** "1.21.1", "~1.21.1", ">=1.21.1 <1.21.2", "[1.21.1,1.21.2)" all name 1.21.1. */
        private fun exactVersion(range: String): String? =
            Regex("""\d+\.\d+(?:\.\d+)?""").find(range)?.value

        fun platformLoader(platform: Platform): String? = when (platform) {
            Platform.FABRIC -> "fabric"
            Platform.QUILT -> "quilt"
            Platform.NEOFORGE -> "neoforge"
            Platform.FORGE -> "forge"
            Platform.PAPER, Platform.PURPUR, Platform.SPIGOT, Platform.FOLIA -> platform.name.lowercase()
            Platform.VANILLA -> "vanilla"
            Platform.VELOCITY -> "velocity"
            Platform.BUNGEECORD -> "bungeecord"
            Platform.UNKNOWN -> null
        }
    }
}

package dev.holo795.crashsleuth.model

import kotlinx.serialization.Serializable

/** Software the log or the files come from. */
@Serializable
enum class Platform(val displayName: String, val kind: PlatformKind) {
    VANILLA("Vanilla", PlatformKind.VANILLA),
    PAPER("Paper", PlatformKind.PLUGINS),
    PURPUR("Purpur", PlatformKind.PLUGINS),
    SPIGOT("Spigot", PlatformKind.PLUGINS),
    FOLIA("Folia", PlatformKind.PLUGINS),
    NEOFORGE("NeoForge", PlatformKind.MODS),
    FORGE("Forge", PlatformKind.MODS),
    FABRIC("Fabric", PlatformKind.MODS),
    QUILT("Quilt", PlatformKind.MODS),
    VELOCITY("Velocity", PlatformKind.PROXY),
    BUNGEECORD("BungeeCord", PlatformKind.PROXY),
    // Hybrids: one server that loads mods of a loader and Bukkit plugins at the same time.
    MOHIST("Mohist", PlatformKind.HYBRID),
    YOUER("Youer", PlatformKind.HYBRID),
    ARCLIGHT_FORGE("Arclight (Forge)", PlatformKind.HYBRID),
    ARCLIGHT_NEOFORGE("Arclight (NeoForge)", PlatformKind.HYBRID),
    ARCLIGHT_FABRIC("Arclight (Fabric)", PlatformKind.HYBRID),
    UNKNOWN("Unknown", PlatformKind.UNKNOWN),
    ;

    /** Whether the server reads paper-plugin.yml. The Spigot-based hybrids do not, and skip such a plugin. */
    val readsPaperPlugins: Boolean
        get() = this != MOHIST && this != ARCLIGHT_FORGE && this != ARCLIGHT_NEOFORGE && this != ARCLIGHT_FABRIC

    /** The ids mods use to ask for this platform's loader in their dependencies. */
    val loaderIds: Set<String>
        get() = when (base) {
            FORGE -> setOf("forge")
            NEOFORGE -> setOf("neoforge")
            FABRIC -> setOf("fabricloader", "fabric-loader", "fabric")
            QUILT -> setOf("quilt_loader", "fabricloader")
            else -> emptySet()
        }

    /** The loader whose mods a platform runs: itself for a plain loader, the one underneath for a hybrid. */
    val base: Platform
        get() = when (this) {
            MOHIST, ARCLIGHT_FORGE -> FORGE
            YOUER, ARCLIGHT_NEOFORGE -> NEOFORGE
            ARCLIGHT_FABRIC -> FABRIC
            else -> this
        }
}

@Serializable
enum class PlatformKind {
    VANILLA, PLUGINS, MODS, PROXY, HYBRID, UNKNOWN;

    /** A hybrid is both: its mods folder and its plugins folder are loaded, and both are checked. */
    val loadsMods: Boolean get() = this == MODS || this == HYBRID
    val loadsPlugins: Boolean get() = this == PLUGINS || this == HYBRID
}

@Serializable
enum class Side { CLIENT, SERVER, UNKNOWN }

/** What was detected about the environment that produced the log. */
@Serializable
data class Environment(
    val platform: Platform = Platform.UNKNOWN,
    val minecraftVersion: String? = null,
    val loaderVersion: String? = null,
    val javaVersion: String? = null,
    val side: Side = Side.UNKNOWN,
)

@Serializable
enum class CulpritKind { MOD, PLUGIN, MINECRAFT, JAVA, SYSTEM, UNKNOWN }

/** The mod, plugin or component held responsible for a finding. */
@Serializable
data class Culprit(
    val kind: CulpritKind,
    val id: String,
    val name: String? = null,
    val version: String? = null,
    val file: String? = null,
) {
    val label: String get() = name ?: id
}

@Serializable
enum class Confidence { LOW, MEDIUM, HIGH, CERTAIN }

/** One diagnosed problem: which situation, who is responsible, and the lines that prove it. */
@Serializable
data class Finding(
    val situation: Situation,
    val confidence: Confidence,
    val culprits: List<Culprit> = emptyList(),
    val evidence: List<String> = emptyList(),
    val details: Map<String, String> = emptyMap(),
)

@Serializable
data class Report(
    val environment: Environment,
    val findings: List<Finding>,
    val exceptions: List<String> = emptyList(),
    /** Files the report was built from (logs, crash reports, folders). */
    val sources: List<String> = emptyList(),
) {
    val primary: Finding? get() = findings.firstOrNull()
}

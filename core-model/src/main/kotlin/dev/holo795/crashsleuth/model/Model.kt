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
    UNKNOWN("Unknown", PlatformKind.UNKNOWN),
}

@Serializable
enum class PlatformKind { VANILLA, PLUGINS, MODS, PROXY, UNKNOWN }

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

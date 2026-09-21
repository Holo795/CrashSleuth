package dev.holo795.crashsleuth.inventory

import dev.holo795.crashsleuth.model.Confidence
import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Environment
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.PlatformKind
import dev.holo795.crashsleuth.model.Side
import dev.holo795.crashsleuth.model.Situation

/**
 * Finds problems by reading what is installed, before (or without) any crash: duplicates, jars made for
 * another loader, missing dependencies, wrong Minecraft version, broken files.
 */
object InventoryAnalyzer {
    /** Ids every loader provides by itself. */
    private val BUILT_IN = setOf(
        "minecraft", "java", "fabricloader", "fabric-loader", "quilt_loader", "neoforge", "forge", "fml", "javafml",
        "mixinextras", "mixin",
    )

    /** Old ids still used in dependencies. */
    private val ALIASES = mapOf("fabric" to "fabric-api", "quilted_fabric_api" to "fabric-api")

    fun analyze(inventory: Inventory, known: Environment = Environment()): List<Finding> {
        val platform = if (known.platform != Platform.UNKNOWN) known.platform else inventory.platform
        val minecraft = known.minecraftVersion ?: inventory.minecraftVersion
        val side = if (known.side != Side.UNKNOWN) known.side else inventory.side
        val findings = mutableListOf<Finding>()

        inventory.jars.filter { it.error != null }.forEach { jar ->
            findings += Finding(
                Situation.CORRUPT_JAR, Confidence.HIGH,
                listOf(Culprit(CulpritKind.UNKNOWN, jar.file, file = "${jar.folder}/${jar.file}")),
                evidence = listOf("${jar.folder}/${jar.file}: ${jar.error}"),
            )
        }

        val loaded = inventory.jars.filter { it.error == null }.map { jar -> jar to usable(jar, platform, minecraft) }
        findings += wrongPlace(loaded, platform)

        val all = loaded.flatMap { (jar, mods) -> mods.map { jar to it } }
        findings += duplicates(all)
        // Loaders keep the most recent copy of a duplicate: the others are never loaded, so they are not checked.
        // Fabric and Quilt skip client mods on a server without a word (checked on a 168-mod modpack):
        // they are harmless there, and their dependencies do not matter.
        val active = all.groupBy { (jar, mod) -> jar.folder to mod.id.lowercase() }.values.map { copies ->
            copies.reduce { best, next -> if ((Versions.compare(next.second.version ?: "", best.second.version ?: "") ?: 0) > 0) next else best }
        }.filterNot { (_, mod) -> side == Side.SERVER && mod.environment == Side.CLIENT }

        val provided = buildSet {
            addAll(BUILT_IN)
            loaded.forEach { (jar, mods) ->
                // A jar made for another loader provides nothing; a library jar without metadata of its own
                // (Kotlin for Forge "-all") still provides the mods nested inside it.
                if (mods.isEmpty() && jar.mods.isNotEmpty()) return@forEach
                (mods + jar.nested).forEach { mod ->
                    add(mod.id.lowercase())
                    mod.provides.forEach { add(it.lowercase()) }
                }
            }
        }
        findings += dependencies(active, provided, minecraft, side)
        findings += pluginApi(active, minecraft)
        return findings
    }

    /** Metadata the current platform will actually read; empty when the jar does not belong here. */
    private fun usable(jar: JarEntry, platform: Platform, minecraft: String?): List<ModMetadata> {
        val accepted = when {
            jar.folder == "plugins" -> setOf(MetadataFormat.BUKKIT, MetadataFormat.PAPER)
            else -> when (platform) {
                Platform.FABRIC -> setOf(MetadataFormat.FABRIC)
                Platform.QUILT -> setOf(MetadataFormat.QUILT, MetadataFormat.FABRIC)
                Platform.FORGE -> setOf(MetadataFormat.FORGE)
                // NeoForge 1.20.1 is a Forge fork and still reads mods.toml.
                Platform.NEOFORGE -> if (minecraft == "1.20.1") setOf(MetadataFormat.NEOFORGE, MetadataFormat.FORGE) else setOf(MetadataFormat.NEOFORGE)
                else -> MetadataFormat.entries.filterNot { it.isPlugin }.toSet()
            }
        }
        val usable = jar.mods.filter { it.format in accepted }
        // Paper prefers paper-plugin.yml when a jar has both.
        return if (usable.any { it.format == MetadataFormat.PAPER }) usable.filter { it.format != MetadataFormat.BUKKIT } else usable
    }

    private fun culprit(jar: JarEntry, mod: ModMetadata?) = Culprit(
        kind = if (jar.folder == "plugins") CulpritKind.PLUGIN else CulpritKind.MOD,
        id = mod?.id ?: jar.file.removeSuffix(".jar"),
        name = mod?.name,
        version = mod?.version,
        file = "${jar.folder}/${jar.file}",
    )

    private fun wrongPlace(loaded: List<Pair<JarEntry, List<ModMetadata>>>, platform: Platform): List<Finding> {
        // Sinytra Connector lets NeoForge and Forge load Fabric mods.
        val connector = loaded.any { (_, mods) -> mods.any { it.id == "connectormod" || it.id == "connector" } }
        return loaded.mapNotNull { (jar, usable) ->
            if (usable.isNotEmpty() || jar.mods.isEmpty()) return@mapNotNull null
            if (jar.folder == "mods" && platform.kind != PlatformKind.MODS) return@mapNotNull null
            if (connector && jar.formats == setOf(MetadataFormat.FABRIC)) return@mapNotNull null
            val made = jar.mods.first()
            Finding(
                Situation.WRONG_LOADER, Confidence.HIGH, listOf(culprit(jar, made)),
                evidence = listOf("${jar.folder}/${jar.file}: ${jar.formats.joinToString { it.name.lowercase() }} metadata only"),
                details = mapOf("platform" to if (jar.folder == "plugins") "${platform.displayName} (plugins)" else platform.displayName),
            )
        }
    }

    private fun duplicates(active: List<Pair<JarEntry, ModMetadata>>): List<Finding> =
        active.groupBy { (jar, mod) -> jar.folder to mod.id.lowercase() }
            .filter { (_, copies) -> copies.map { it.first.file }.distinct().size > 1 }
            .map { (_, copies) ->
                val (jar, mod) = copies.first()
                Finding(
                    Situation.DUPLICATE, Confidence.HIGH, listOf(culprit(jar, mod)),
                    evidence = copies.map { (copy, meta) -> "${copy.folder}/${copy.file}: ${meta.id} ${meta.version ?: ""}".trimEnd() },
                    details = mapOf("files" to copies.joinToString { it.first.file }),
                )
            }

    private fun dependencies(
        active: List<Pair<JarEntry, ModMetadata>>,
        provided: Set<String>,
        minecraft: String?,
        side: Side,
    ): List<Finding> = active.flatMap { (jar, mod) ->
        mod.dependencies.filter { it.required }.mapNotNull { dependency ->
            if (dependency.side != Side.UNKNOWN && side != Side.UNKNOWN && dependency.side != side) return@mapNotNull null
            val id = dependency.id.lowercase()
            if (id == "minecraft") {
                if (minecraft == null || Versions.matches(minecraft, dependency.versionRange)) return@mapNotNull null
                return@mapNotNull Finding(
                    Situation.WRONG_MC, Confidence.HIGH, listOf(culprit(jar, mod)),
                    evidence = listOf("${jar.folder}/${jar.file}: requires Minecraft ${dependency.versionRange}"),
                    details = mapOf("expected" to (dependency.versionRange ?: "?"), "actual" to minecraft),
                )
            }
            if (id in provided || ALIASES[id] in provided) return@mapNotNull null
            if (id in BUILT_IN) return@mapNotNull null
            Finding(
                Situation.DEP_MISSING, Confidence.HIGH,
                listOf(culprit(jar, mod), Culprit(if (jar.folder == "plugins") CulpritKind.PLUGIN else CulpritKind.MOD, ALIASES[id] ?: dependency.id)),
                evidence = listOf("${jar.folder}/${jar.file}: ${mod.id} requires ${dependency.id} ${dependency.versionRange ?: ""}".trimEnd()),
                details = mapOf("requester" to (mod.name ?: mod.id), "dependency" to (ALIASES[id] ?: dependency.id)),
            )
        }
    }

    private fun pluginApi(active: List<Pair<JarEntry, ModMetadata>>, minecraft: String?): List<Finding> {
        if (minecraft == null) return emptyList()
        return active.filter { (_, mod) -> mod.format.isPlugin && mod.apiVersion != null }.mapNotNull { (jar, mod) ->
            val order = Versions.compare(mod.apiVersion!!, minecraft) ?: return@mapNotNull null
            if (order <= 0) return@mapNotNull null
            Finding(
                Situation.PLUGIN_API, Confidence.HIGH, listOf(culprit(jar, mod)),
                evidence = listOf("${jar.folder}/${jar.file}: api-version ${mod.apiVersion}, server ${minecraft}"),
                details = mapOf("expected" to mod.apiVersion, "actual" to minecraft),
            )
        }
    }
}

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        if (side == Side.SERVER && platform in setOf(Platform.NEOFORGE, Platform.FORGE)) findings += knownClientOnly(active)
        findings += knownPairs(active)
        findings += javaVersions(loaded.filter { (jar, mods) -> mods.isNotEmpty() || jar.mods.isEmpty() }.map { it.first }, known.javaVersion, minecraft)
        findings += dependencies(active, provided, minecraft, side)
        findings += loaderRequirements(active, platform, known.loaderVersion ?: inventory.loaderVersion)
        findings += declaredConflicts(active)
        findings += serverFiles(inventory.files, minecraft)
        findings += pluginApi(active, minecraft)
        return findings
    }

    /** Metadata the current platform will actually read; empty when the jar does not belong here. */
    private fun usable(jar: JarEntry, platform: Platform, minecraft: String?): List<ModMetadata> {
        val accepted = when {
            jar.folder == "plugins" && platform == Platform.VELOCITY -> setOf(MetadataFormat.VELOCITY)
            jar.folder == "plugins" && platform == Platform.BUNGEECORD -> setOf(MetadataFormat.BUNGEE)
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
        // Its "-full" jar has no metadata of its own: the mod id sits in a nested jar (seen in Create Plus).
        val connector = loaded.any { (jar, mods) -> (mods + jar.nested).any { it.id == "connectormod" || it.id == "connector" } }
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

    private val KNOWN_PAIRS: JsonObject by lazy {
        val text = InventoryAnalyzer::class.java.classLoader.getResourceAsStream("crashsleuth/known-pairs.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        Json.parseToJsonElement(text).jsonObject
    }

    /**
     * Two mods people found out the hard way cannot live together, and mods that need another mod
     * without saying so. Both are said before anything crashes, with where it was established.
     */
    private fun knownPairs(active: List<Pair<JarEntry, ModMetadata>>): List<Finding> {
        val byId = active.associateBy { (_, mod) -> mod.id.lowercase() }
        val findings = mutableListOf<Finding>()
        KNOWN_PAIRS["incompatible"]?.jsonArray?.forEach { entry ->
            val pair = entry.jsonObject
            val first = byId[pair.getValue("a").jsonPrimitive.content] ?: return@forEach
            val second = byId[pair.getValue("b").jsonPrimitive.content] ?: return@forEach
            findings += Finding(
                Situation.MOD_CONFLICT, Confidence.HIGH,
                listOf(culprit(first.first, first.second), culprit(second.first, second.second)),
                evidence = listOf(pair.getValue("why").jsonPrimitive.content, pair.getValue("source").jsonPrimitive.content),
            )
        }
        KNOWN_PAIRS["needs"]?.jsonArray?.forEach { entry ->
            val rule = entry.jsonObject
            val mod = byId[rule.getValue("id").jsonPrimitive.content] ?: return@forEach
            val needed = rule.getValue("needs").jsonPrimitive.content
            if (byId.containsKey(needed)) return@forEach
            findings += Finding(
                Situation.DEP_MISSING, Confidence.HIGH,
                listOf(culprit(mod.first, mod.second), Culprit(CulpritKind.MOD, needed)),
                evidence = listOf(rule.getValue("why").jsonPrimitive.content, rule.getValue("source").jsonPrimitive.content),
                details = mapOf("requester" to (mod.second.name ?: mod.second.id), "dependency" to needed),
            )
        }
        return findings
    }

    private val CLIENT_ONLY: Set<String> by lazy {
        val text = InventoryAnalyzer::class.java.classLoader.getResourceAsStream("crashsleuth/client-only.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        Json.parseToJsonElement(text).jsonObject.getValue("mods").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
    }

    /** NeoForge and Forge cannot mark a mod as client-only: a list confirmed by real crashes fills the gap. */
    private fun knownClientOnly(active: List<Pair<JarEntry, ModMetadata>>): List<Finding> =
        active.filter { (_, mod) -> mod.id.lowercase() in CLIENT_ONLY }.map { (jar, mod) ->
            Finding(
                Situation.CLIENT_ONLY_ON_SERVER, Confidence.HIGH, listOf(culprit(jar, mod)),
                evidence = listOf("${jar.folder}/${jar.file}: ${mod.id} is a known client-only mod"),
            )
        }

    /** Ids of the loaders themselves in dependencies, and the platforms they belong to. */
    private val LOADER_IDS = mapOf(
        "fabricloader" to setOf(Platform.FABRIC, Platform.QUILT), "fabric-loader" to setOf(Platform.FABRIC, Platform.QUILT),
        "neoforge" to setOf(Platform.NEOFORGE), "forge" to setOf(Platform.FORGE), "quilt_loader" to setOf(Platform.QUILT),
    )

    /** A mod that needs a newer loader than the installed one: the loader refuses to start. */
    private fun loaderRequirements(active: List<Pair<JarEntry, ModMetadata>>, platform: Platform, loaderVersion: String?): List<Finding> {
        if (loaderVersion == null) return emptyList()
        return active.flatMap { (jar, mod) ->
            mod.dependencies.filter { it.required }.mapNotNull { dependency ->
                val platforms = LOADER_IDS[dependency.id.lowercase()] ?: return@mapNotNull null
                if (platform !in platforms || Versions.matches(loaderVersion, dependency.versionRange)) return@mapNotNull null
                Finding(
                    Situation.DEP_VERSION, Confidence.HIGH,
                    listOf(culprit(jar, mod), Culprit(CulpritKind.SYSTEM, dependency.id, platform.displayName)),
                    evidence = listOf("${jar.folder}/${jar.file}: ${mod.id} requires ${dependency.id} ${dependency.versionRange}, installed $loaderVersion"),
                    details = mapOf("dependency" to platform.displayName, "requester" to (mod.name ?: mod.id), "expected" to (dependency.versionRange ?: "?"), "actual" to loaderVersion),
                )
            }
        }
    }

    /** Mods that declare they cannot run with another installed mod (Fabric "breaks", NeoForge "incompatible"). */
    private fun declaredConflicts(active: List<Pair<JarEntry, ModMetadata>>): List<Finding> {
        val byId = active.associateBy { it.second.id.lowercase() }
        return active.flatMap { (jar, mod) ->
            mod.breaks.mapNotNull { broken ->
                val (otherJar, other) = byId[broken.id.lowercase()] ?: return@mapNotNull null
                if (other === mod) return@mapNotNull null
                // Without a version range the conflict is with every version; with one, only with the versions in it.
                if (broken.versionRange != null && other.version != null && !Versions.matches(other.version, broken.versionRange)) return@mapNotNull null
                if (broken.versionRange != null && other.version == null) return@mapNotNull null
                Finding(
                    Situation.MOD_CONFLICT, Confidence.HIGH, listOf(culprit(jar, mod), culprit(otherJar, other)),
                    evidence = listOf("${jar.folder}/${jar.file}: ${mod.id} declares it breaks ${broken.id} ${broken.versionRange ?: ""}".trimEnd()),
                )
            }
        }.distinctBy { finding -> finding.culprits.map { it.id }.sorted() }
    }

    private fun serverFiles(files: ServerFiles, minecraft: String?): List<Finding> = buildList {
        // PaperMC ended Waterfall in 2024; in the lab it left a player waiting without a word when its server was down.
        files.rootJars.firstOrNull { it.lowercase().startsWith("waterfall") }?.let { jar ->
            add(Finding(Situation.OUTDATED, Confidence.LOW, listOf(Culprit(CulpritKind.SYSTEM, "Waterfall", file = jar)), listOf(jar), mapOf("adviceKey" to "proxy.waterfall-eol")))
        }
        if (files.eulaAccepted == false) {
            add(Finding(Situation.EULA, Confidence.CERTAIN, evidence = listOf("eula.txt: eula=false")))
        }
        files.worlds.forEach { world ->
            val culprit = listOf(Culprit(CulpritKind.SYSTEM, world.name))
            if (world.unreadable != null) {
                add(Finding(Situation.WORLD_CORRUPT, Confidence.HIGH, culprit, listOf("${world.name}/level.dat: ${world.unreadable}"), mapOf("backup" to world.hasBackup.toString())))
            }
            if (world.locked) {
                add(Finding(Situation.WORLD_LOCKED, Confidence.HIGH, culprit, listOf("${world.name}/session.lock is held by another process")))
            }
            val saved = world.versionName
            if (saved != null && minecraft != null && (Versions.compare(saved, minecraft) ?: 0) > 0) {
                add(Finding(Situation.WORLD_DOWNGRADE, Confidence.CERTAIN, culprit, listOf("${world.name}/level.dat: saved by Minecraft $saved, the server is $minecraft"), mapOf("expected" to saved, "actual" to minecraft)))
            }
            world.damagedPlayers.forEach { player ->
                add(Finding(
                    Situation.CORRUPT_PLAYERDATA, if (player.setAside) Confidence.MEDIUM else Confidence.HIGH,
                    listOf(Culprit(CulpritKind.SYSTEM, player.name ?: player.file.substringAfterLast('/').substringBefore("_corrupted_").removeSuffix(".dat"))),
                    listOf("${world.name}/${player.file}: ${player.reason}"),
                    mapOf("world" to world.name, "file" to player.file, "backup" to player.hasBackup.toString()),
                ))
            }
            // A chunk of entities too big for its region file holds thousands of them in one place: that
            // is what makes a server fall behind long before anything is damaged (lab, 22/09/2026).
            world.oversizedChunks.filter { it.folder == "entities" }.takeIf { it.isNotEmpty() }?.let { crowded ->
                val first = crowded.first()
                add(Finding(
                    Situation.LAG, Confidence.LOW, emptyList(),
                    crowded.take(5).map { "${world.name}/${it.file} [${it.x}, ${it.z}]: ${it.reason}" },
                    mapOf("world" to world.name, "x" to first.x.toString(), "z" to first.z.toString(),
                          "count" to crowded.size.toString(), "adviceKey" to "lag.entity-pileup"),
                ))
            }
            // Blocks and entities apart: they are lost differently. Named by place, like the game's own log says it.
            world.damagedChunks.groupBy { it.folder }.entries.sortedBy { it.key == "entities" }.forEach { (folder, chunks) ->
                val first = chunks.first()
                add(Finding(
                    if (folder == "entities") Situation.CORRUPT_ENTITY else Situation.CORRUPT_CHUNK, Confidence.HIGH, emptyList(),
                    chunks.take(5).map { "${world.name}/${it.file} [${it.x}, ${it.z}]: ${it.reason}" },
                    mapOf("world" to world.name, "x" to first.x.toString(), "z" to first.z.toString(), "file" to first.file, "count" to chunks.size.toString()),
                ))
            }
        }
        files.configErrors.forEach { error ->
            add(
                Finding(
                    Situation.CONFIG_BROKEN, Confidence.MEDIUM, listOf(Culprit(CulpritKind.SYSTEM, error.file)),
                    listOf("${error.file}${error.line?.let { ":$it" } ?: ""}: ${error.message.take(200)}"),
                    mapOf("file" to error.file),
                ),
            )
        }
    }

    /** Java release Minecraft itself needs, the one most servers and launchers run. */
    fun javaFor(minecraft: String): Int? {
        val version = minecraft.split('.').mapNotNull { it.toIntOrNull() }
        val major = version.getOrNull(0) ?: return null
        val minor = version.getOrNull(1) ?: 0
        val patch = version.getOrNull(2) ?: 0
        return when {
            major >= 26 -> 25
            major != 1 -> null
            minor >= 21 || (minor == 20 && patch >= 5) -> 21
            minor >= 18 -> 17
            minor == 17 -> 16
            else -> 8
        }
    }

    private fun javaVersions(jars: List<JarEntry>, runtime: String?, minecraft: String?): List<Finding> {
        // "21.0.12" or "1.8.0_392"
        val current = runtime?.let { text -> text.split('.', '_', '+', '-').mapNotNull { it.toIntOrNull() }.let { if (it.firstOrNull() == 1) it.getOrNull(1) else it.firstOrNull() } }
        val expected = minecraft?.let(::javaFor)
        return jars.mapNotNull { jar ->
            val needed = jar.javaVersion ?: return@mapNotNull null
            val (limit, confidence) = when {
                current != null -> current to Confidence.CERTAIN
                expected != null -> expected to Confidence.MEDIUM
                else -> return@mapNotNull null
            }
            if (needed <= limit) return@mapNotNull null
            val mod = jar.mods.firstOrNull()
            Finding(
                Situation.JAVA_VERSION, confidence, listOf(culprit(jar, mod)),
                evidence = listOf("${jar.folder}/${jar.file}: compiled for Java $needed"),
                details = mapOf("required" to needed.toString(), "current" to (current?.toString() ?: "$limit ?")),
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

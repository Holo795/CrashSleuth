package dev.holo795.crashsleuth.app

import dev.holo795.crashsleuth.model.insideOf
import dev.holo795.crashsleuth.bisect.CulpritSearch
import dev.holo795.crashsleuth.bisect.RunRecord
import dev.holo795.crashsleuth.bisect.SearchResult
import dev.holo795.crashsleuth.bisect.suspectsOf
import dev.holo795.crashsleuth.engine.Diagnoser
import dev.holo795.crashsleuth.inventory.InjectionKind
import dev.holo795.crashsleuth.inventory.InstanceScanner
import dev.holo795.crashsleuth.inventory.JarScanner
import dev.holo795.crashsleuth.inventory.Inventory
import dev.holo795.crashsleuth.inventory.InventoryAnalyzer
import dev.holo795.crashsleuth.inventory.MixinIndex
import dev.holo795.crashsleuth.inventory.ModCompare
import dev.holo795.crashsleuth.inventory.PackScanner
import dev.holo795.crashsleuth.model.Report
import dev.holo795.crashsleuth.model.Side
import dev.holo795.crashsleuth.model.Situation
import dev.holo795.crashsleuth.runner.ClientInstaller
import dev.holo795.crashsleuth.runner.ClientLaunch
import dev.holo795.crashsleuth.runner.LaunchCommand
import dev.holo795.crashsleuth.runner.ServerLauncher
import dev.holo795.crashsleuth.runner.Workspace
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/** The same method changed by several mods (informative: most collisions are handled by the mods themselves). */
@Serializable
data class MixinCollision(val kind: InjectionKind, val targetClass: String, val method: String, val mods: List<String>)

/** Everything one analysis learned, for any interface to show. */
@Serializable
data class Analysis(
    val target: Target,
    val report: Report,
    val inventory: Inventory? = null,
    val mixinCollisions: List<MixinCollision> = emptyList(),
    /** Mods or plugins named by the analysis: the culprit search tests them first. */
    val suspects: List<String> = emptyList(),
    /** For a modpack: the side it was read for (each file of a pack says which sides need it). */
    val side: Side? = null,
    /** The server whose mods were compared with these, when the person asked for it. */
    val comparedWith: String? = null,
    /** Modrinth was asked whether newer versions exist. */
    val updatesChecked: Boolean = false,
    /** Game code names of the evidence were translated into Mojang's names. */
    val readable: Boolean = false,
)

/** How to run a culprit search; everything has a sensible default. */
@Serializable
data class SearchSetup(
    val path: String,
    val client: Boolean,
    val minecraft: String? = null,
    val loader: String? = null,
    val java: String = "java",
    val memory: String? = null,
    val timeoutMinutes: Int = 10,
    val settleSeconds: Int = 10,
    val repeat: Int = 1,
    val parallel: Int = 1,
    val maxRuns: Int = 40,
    val withWorld: Boolean = false,
    val command: List<String>? = null,
)

/** Live news of a culprit search, for screens that follow it. */
interface SearchListener {
    fun message(text: String) {}
    fun run(record: RunRecord) {}
    fun suspects(keys: List<String>) {}
}

class SearchCancelled : RuntimeException("search cancelled")

/** The one entry point of the command line, the desktop app and the shared reports. */
class Workbench(
    private val diagnoser: Diagnoser = Diagnoser(),
    private val packs: PackScanner = PackScanner(),
) {
    fun analyze(path: Path, side: Side = Side.SERVER): Analysis {
        val initial = Target.of(path)
        return when (initial.kind) {
            TargetKind.LOG -> if (path.name.endsWith(".sparkprofile")) {
                Analysis(initial, diagnoser.diagnose(emptyList(), inventory = null, profiles = Diagnoser.profileFile(path)))
            } else {
                Analysis(initial, diagnoser.diagnose(listOf(Diagnoser.Log(path.name, path.readText(Charsets.UTF_8))), inventory = null))
            }
            TargetKind.PACK -> {
                val inventory = packs.scan(path, side)
                val index = MixinIndex.build(inventory.jars)
                val report = diagnoser.diagnose(emptyList(), inventory) { index }
                Analysis(initial.copy(minecraft = inventory.minecraftVersion, loader = Target.platformLoader(inventory.platform)), report, inventory, collisions(index), side = side)
            }
            TargetKind.MODS -> {
                val folder = Path.of(initial.path)
                val jars = JarScanner.jarsIn(folder).map { JarScanner.scan(it, "mods") }
                // Plugins dropped as a list are read as plugins.
                val asPlugins = jars.isNotEmpty() && jars.all { jar -> jar.mods.isNotEmpty() && jar.formats.all { it.isPlugin } }
                val scanned = if (asPlugins) jars.map { it.copy(folder = "plugins") } else jars
                val platform = platformOf(scanned)
                val inventory = Inventory(platform = platform, side = side, jars = scanned)
                val index = MixinIndex.build(scanned)
                val report = diagnoser.diagnose(emptyList(), inventory) { index }
                val target = Target.refine(initial.copy(kind = TargetKind.CLIENT), inventory).copy(kind = TargetKind.MODS)
                Analysis(target, report, inventory, collisions(index), side = side)
            }
            TargetKind.SERVER, TargetKind.CLIENT -> {
                val folder = Path.of(initial.path)
                val inventory = InstanceScanner.scan(folder)
                val index = lazy { MixinIndex.build(inventory.jars) }
                val logs = Diagnoser.recentLogs(folder).map { Diagnoser.Log(it.insideOf(folder), it.readText(Charsets.UTF_8)) }
                val report = diagnoser.diagnose(logs, inventory, Diagnoser.profileFindings(folder)) { index.value }
                // The game's own log names its exact version; mods only give ranges (">=1.21").
                val refined = Target.refine(initial.copy(minecraft = initial.minecraft ?: report.environment.minecraftVersion), inventory)
                val target = if (refined.kind == TargetKind.SERVER) {
                    refined.copy(minecraft = report.environment.minecraftVersion ?: inventory.minecraftVersion, loader = Target.platformLoader(inventory.platform))
                } else {
                    refined
                }
                Analysis(target, report, inventory, collisions(index.value), suspectsOf(report))
            }
        }
    }

    /** Adds what differs between these mods (the player's) and those of the server the player joins. */
    fun compare(analysis: Analysis, server: Path): Analysis {
        val player = analysis.inventory ?: return analysis
        val theirs = if (server.isDirectory()) InstanceScanner.scan(server) else packs.scan(server, Side.SERVER)
        val differences = ModCompare.compare(player, theirs)
        val kept = analysis.report.findings.filterNot { it.details["adviceKey"]?.startsWith("compare.") == true }
        return analysis.copy(report = analysis.report.copy(findings = (differences + kept).sortedByDescending { it.confidence }), comparedWith = server.toString())
    }

    /** Translates the game code names of the evidence into Mojang's names (mappings downloaded once). */
    fun readable(analysis: Analysis): Analysis {
        val minecraft = analysis.report.environment.minecraftVersion ?: analysis.target.minecraft ?: error("unknown Minecraft version")
        val mappings = Mappings.load(minecraft)
        val report = analysis.report
        return analysis.copy(
            report = report.copy(findings = report.findings.map { it.copy(evidence = it.evidence.map(mappings::translate)) }, exceptions = report.exceptions.map(mappings::translate)),
            readable = true,
        )
    }

    /** Asks Modrinth, by file hash only, which installed mods have a newer version. */
    fun checkUpdates(analysis: Analysis, check: ModrinthCheck = ModrinthCheck()): Analysis {
        val inventory = analysis.inventory ?: return analysis
        val updates = check.findings(check.check(inventory, analysis.target.minecraft ?: analysis.report.environment.minecraftVersion ?: inventory.minecraftVersion))
        val kept = analysis.report.findings.filterNot { it.situation == Situation.OUTDATED }
        return analysis.copy(report = analysis.report.copy(findings = kept + updates), updatesChecked = true)
    }

    /** The loader a list of jars is made for, from their metadata. */
    private fun platformOf(jars: List<dev.holo795.crashsleuth.inventory.JarEntry>): dev.holo795.crashsleuth.model.Platform {
        val formats = jars.flatMap { it.formats }
        fun share(format: dev.holo795.crashsleuth.inventory.MetadataFormat) = formats.count { it == format }
        return listOf(
            dev.holo795.crashsleuth.inventory.MetadataFormat.NEOFORGE to dev.holo795.crashsleuth.model.Platform.NEOFORGE,
            dev.holo795.crashsleuth.inventory.MetadataFormat.FABRIC to dev.holo795.crashsleuth.model.Platform.FABRIC,
            dev.holo795.crashsleuth.inventory.MetadataFormat.FORGE to dev.holo795.crashsleuth.model.Platform.FORGE,
            dev.holo795.crashsleuth.inventory.MetadataFormat.QUILT to dev.holo795.crashsleuth.model.Platform.QUILT,
            dev.holo795.crashsleuth.inventory.MetadataFormat.PAPER to dev.holo795.crashsleuth.model.Platform.PAPER,
            dev.holo795.crashsleuth.inventory.MetadataFormat.BUKKIT to dev.holo795.crashsleuth.model.Platform.PAPER,
        ).maxByOrNull { share(it.first) }?.takeIf { share(it.first) > 0 }?.second ?: dev.holo795.crashsleuth.model.Platform.UNKNOWN
    }

    private fun collisions(index: MixinIndex): List<MixinCollision> =
        (index.overwriteConflicts() + index.redirectConflicts()).map { group ->
            MixinCollision(group.first().kind, group.first().targetClass, group.first().method, group.map { it.modId }.distinct())
        }

    /** A sensible search setup for an analysed folder: the right Java, found on this computer. */
    fun defaultSetup(analysis: Analysis): SearchSetup {
        val needed = analysis.target.minecraft?.let(InventoryAnalyzer::javaFor)
        return SearchSetup(
            path = analysis.target.path,
            client = analysis.target.kind == TargetKind.CLIENT,
            minecraft = analysis.target.minecraft,
            loader = analysis.target.loader,
            java = JavaFinder.best(needed)?.executable ?: "java",
        )
    }

    /**
     * Launches copies of the server or game until the culprit is proven. Setting [cancel] closes the
     * current launch and stops the search.
     */
    fun search(
        setup: SearchSetup,
        suspects: List<String>,
        listener: SearchListener = object : SearchListener {},
        cancel: AtomicBoolean = AtomicBoolean(false),
    ): SearchResult {
        val folder = Path.of(setup.path)
        val inventory = InstanceScanner.scan(folder)
        val workspace = Workspace(folder, Files.createTempDirectory("crashsleuth-search"), setup.withWorld)
        listener.message("preparing")
        workspace.prepare()
        val timeout = Duration.ofMinutes(setup.timeoutMinutes.toLong())
        val settle = Duration.ofSeconds(setup.settleSeconds.toLong())
        val installer = ClientInstaller()
        val profile = if (setup.client) {
            listener.message("installing")
            installer.install(setup.minecraft ?: error("The Minecraft version of this game folder is unknown"), setup.loader)
        } else {
            null
        }
        val launcher = if (profile != null) {
            ClientLaunch.launcher(installer, profile, setup.java, timeout, settle)
        } else {
            ServerLauncher(setup.command ?: LaunchCommand.detect(folder, setup.java, setup.memory), timeout, settle)
        }
        val slots = ArrayBlockingQueue<Int>(setup.parallel).apply { (0 until setup.parallel).forEach(::add) }
        val search = CulpritSearch(
            inventory,
            launch = { jars ->
                if (cancel.get()) throw SearchCancelled()
                val slot = slots.take()
                try {
                    val directory = workspace.newRun(jars.groupBy { it.folder }.mapValues { (name, entries) -> entries.map { folder.resolve(name).resolve(it.file) } }, slot)
                    val runLauncher = if (profile == null) launcher else launcher.withCommand(installer.command(profile, directory.toAbsolutePath(), setup.java, setup.memory ?: "4G"))
                    runLauncher.run(directory) { cancel.get() }.also { if (cancel.get()) throw SearchCancelled() }
                } finally {
                    slots.put(slot)
                }
            },
            maxRuns = setup.maxRuns,
            repeat = setup.repeat,
            parallel = setup.parallel,
            progress = listener::message,
            onRun = listener::run,
            onSuspects = listener::suspects,
        )
        try {
            return search.search(suspects)
        } finally {
            workspace.cleanup()
        }
    }
}

package dev.holo795.crashsleuth.engine

import dev.holo795.crashsleuth.inventory.InstanceScanner
import dev.holo795.crashsleuth.inventory.Inventory
import dev.holo795.crashsleuth.inventory.InventoryAnalyzer
import dev.holo795.crashsleuth.inventory.MixinIndex
import dev.holo795.crashsleuth.logs.LogAnalyzer
import dev.holo795.crashsleuth.logs.LogDocument
import dev.holo795.crashsleuth.logs.SparkProfile
import dev.holo795.crashsleuth.logs.UncaughtExceptionDetector
import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Environment
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Report
import dev.holo795.crashsleuth.model.Side
import dev.holo795.crashsleuth.model.Situation
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/** Combines what the logs say happened with what the installed files say is wrong. */
class Diagnoser(private val logAnalyzer: LogAnalyzer = LogAnalyzer()) {
    /** A named log text, so reports can say where each piece of evidence comes from. */
    data class Log(val name: String, val text: String)

    fun diagnose(logs: List<Log>, inventory: Inventory?, profiles: List<Finding> = emptyList(), mixins: () -> MixinIndex? = { null }): Report {
        val reports = logs.map { logAnalyzer.analyze(it.text) }
        val environment = reports.map { it.environment }.fold(Environment(), ::merge)
            .let { if (inventory == null) it else merge(it, Environment(inventory.platform, inventory.minecraftVersion, inventory.loaderVersion, side = inventory.side)) }
        val fromLogs = preferNamed(dedup(profiles + reports.flatMap { it.findings })).filterNot { ownRecipe(it, inventory) }
        val fromFiles = inventory?.let { InventoryAnalyzer.analyze(it, environment) }.orEmpty()
            .filterNot { finding -> fromLogs.any { same(it, finding) } }
        val attributed = if (fromLogs.none(::needsMixinSuspects)) fromLogs else mixins()?.let { index -> fromLogs.map { suspectsByMixins(it, logs, index) } } ?: fromLogs
        return Report(
            environment = environment,
            // What the log proves comes first; installed-file warnings follow, unless they are more certain.
            findings = (attributed + dedup(fromFiles)).sortedWith(compareByDescending<Finding> { it.confidence }.thenBy { if (it in attributed) 0 else 1 }),
            exceptions = reports.flatMap { it.exceptions }.distinct().take(10),
            sources = logs.map { it.name },
        )
    }

    /** Scans a server or instance folder and analyses its most recent logs. */
    fun diagnoseFolder(root: Path): Pair<Report, Inventory> {
        val inventory = InstanceScanner.scan(root)
        val logs = recentLogs(root).map { Log(root.relativize(it).toString(), it.readText(Charsets.UTF_8)) }
        return diagnose(logs, inventory, profileFindings(root)) { MixinIndex.build(inventory.jars) } to inventory
    }

    /**
     * A mod that ships recipes for items of an optional dependency logs a parsing error for each one and the game
     * skips them: that is expected. Only data whose namespace belongs to no installed mod comes from a datapack.
     */
    private fun ownRecipe(finding: Finding, inventory: Inventory?): Boolean {
        val namespace = finding.details["namespace"] ?: return false
        if (finding.situation != Situation.DATAPACK_BROKEN || inventory == null) return false
        return inventory.jars.any { jar -> jar.mods.any { it.id == namespace } }
    }

    private fun needsMixinSuspects(finding: Finding) =
        finding.situation in MIXIN_SITUATIONS && finding.culprits.none { it.kind == CulpritKind.MOD || it.kind == CulpritKind.PLUGIN }

    /**
     * A crash whose trace only shows game code: the mods that change those very methods through mixins
     * become the suspects (low confidence, to be confirmed by the culprit search).
     */
    private fun suspectsByMixins(finding: Finding, logs: List<Log>, index: MixinIndex): Finding {
        if (!needsMixinSuspects(finding)) return finding
        val frames = logs.asSequence().mapNotNull { UncaughtExceptionDetector.mainTrace(LogDocument(it.text)) }.firstOrNull()
            ?.chain()?.asReversed()?.flatMap { it.frames.take(12) }.orEmpty()
        val mods = frames.flatMap { index.modsTouching(it.className, it.method) }.distinct()
            .ifEmpty { frames.take(4).flatMap { index.modsTouching(it.className) }.distinct() }
        if (mods.isEmpty()) return finding
        return finding.copy(
            culprits = finding.culprits + mods.take(3).map { Culprit(CulpritKind.MOD, it) },
            details = finding.details + mapOf("adviceKey" to "mixin.suspects.advice", "via" to "mixins"),
        )
    }

    private fun merge(a: Environment, b: Environment) = Environment(
        platform = if (a.platform != Platform.UNKNOWN) a.platform else b.platform,
        minecraftVersion = a.minecraftVersion ?: b.minecraftVersion,
        loaderVersion = a.loaderVersion ?: b.loaderVersion,
        javaVersion = a.javaVersion ?: b.javaVersion,
        side = if (a.side != Side.UNKNOWN) a.side else b.side,
    )

    private fun ids(finding: Finding) = finding.culprits.map { it.id.lowercase() }.toSet()

    /** The same situation about the same culprit, or about nobody in particular (EULA, full disk). */
    private fun same(a: Finding, b: Finding) =
        a.situation == b.situation && (ids(a).intersect(ids(b)).isNotEmpty() || (ids(a).isEmpty() && ids(b).isEmpty()))

    /** A lag or a broken file said without a culprit is dropped when another finding names who it is. */
    private fun preferNamed(findings: List<Finding>): List<Finding> {
        val named = findings.filter { it.culprits.isNotEmpty() }.map { it.situation }.toSet()
        return findings.filterNot { it.culprits.isEmpty() && it.situation in named && it.situation in NAMED_WINS }
    }

    private fun dedup(findings: List<Finding>): List<Finding> =
        findings.fold(mutableListOf()) { kept, finding -> kept.apply { if (none { same(it, finding) || it == finding }) add(finding) } }

    companion object {
        private val NAMED_WINS = setOf(Situation.LAG, Situation.CONFIG_BROKEN, Situation.DEADLOCK)

        /** The newest spark profile of a server or game folder, read into findings (nothing of it is kept). */
        fun profileFindings(root: Path): List<Finding> {
            val newest = listOf("plugins/spark", "config/spark").map(root::resolve).filter { it.isDirectory() }
                .flatMap { folder -> folder.listDirectoryEntries("*.sparkprofile") }
                .maxByOrNull { it.getLastModifiedTime().toMillis() } ?: return emptyList()
            return profileFile(newest, root.relativize(newest).toString())
        }

        /** One spark profile file, read into findings. */
        fun profileFile(file: Path, name: String = file.fileName.toString()): List<Finding> =
            runCatching { SparkProfile.read(java.nio.file.Files.readAllBytes(file))?.let { SparkProfile.findings(it, name) } }.getOrNull().orEmpty()

        private val MIXIN_SITUATIONS = setOf(
            Situation.UNCAUGHT_EXCEPTION, Situation.TICK_ENTITY, Situation.TICK_BLOCK_ENTITY, Situation.STACK_OVERFLOW, Situation.HANG,
        )

        /** latest.log, plus the crash reports and JVM error files written during the same run. */
        fun recentLogs(root: Path): List<Path> {
            val latest = root.resolve("logs/latest.log").takeIf { it.exists() }
            val since = latest?.getLastModifiedTime()?.toMillis()?.minus(30 * 60 * 1000) ?: 0
            val crashReports = root.resolve("crash-reports").takeIf { it.isDirectory() }
                ?.listDirectoryEntries("*.txt").orEmpty()
            val jvmErrors = root.listDirectoryEntries("hs_err_pid*.log")
            val recent = (crashReports + jvmErrors).filter { it.getLastModifiedTime().toMillis() >= since }
                .sortedByDescending { it.getLastModifiedTime() }.take(2)
            return listOfNotNull(latest) + recent
        }
    }
}

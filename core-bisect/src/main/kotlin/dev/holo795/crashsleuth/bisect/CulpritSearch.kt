package dev.holo795.crashsleuth.bisect

import dev.holo795.crashsleuth.engine.Diagnoser
import dev.holo795.crashsleuth.inventory.Inventory
import dev.holo795.crashsleuth.inventory.JarEntry
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Report
import dev.holo795.crashsleuth.model.Situation
import dev.holo795.crashsleuth.runner.Outcome
import dev.holo795.crashsleuth.runner.RunResult
import kotlinx.serialization.Serializable

/** One mod or plugin jar that the search can keep or leave out. */
data class Candidate(
    val jar: JarEntry,
    /** Ids this jar answers to: its mods, their "provides", its nested mods. */
    val ids: Set<String>,
    /** Ids it cannot start without. */
    val requires: Set<String>,
) {
    val key: String get() = "${jar.folder}/${jar.file}"
    val label: String get() = jar.mods.firstOrNull()?.let { it.name ?: it.id } ?: jar.file.removeSuffix(".jar")
}

/** What a failure looks like, to tell "the same crash" from "another problem caused by the test itself". */
@Serializable
data class Fingerprint(val outcome: Outcome, val situation: Situation?, val exception: String?) {
    fun sameAs(other: Fingerprint): Boolean =
        other.outcome == outcome && other.situation == situation &&
            // Without a known situation, the root exception type is what identifies the crash.
            (situation != null || exception == null || other.exception == exception)
}

@Serializable
data class RunRecord(val index: Int, val kept: List<String>, val outcome: Outcome, val seconds: Double, val sameFailure: Boolean, val note: String? = null)

@Serializable
data class SearchResult(
    /** False when the full set did not crash: nothing to search. */
    val reproduced: Boolean,
    /** Jars that, together, are enough to cause the crash, and all needed for it (one of them alone for a single culprit). */
    val culprits: List<String>,
    val labels: List<String>,
    /** False when the run budget ran out before the smallest set was proven. */
    val complete: Boolean,
    val reference: Fingerprint?,
    val runs: List<RunRecord>,
)

/**
 * Finds which mods or plugins cause a crash by launching the server with fewer of them, without
 * asking anyone whether it crashed.
 *
 * 1. The full set is launched to record what the crash looks like.
 * 2. Mods named by the log analysis are tested first: removed (with the mods that need them), then alone.
 * 3. Otherwise, or for crashes that need several mods together, delta debugging (ddmin) reduces the set
 *    to a smallest combination that still crashes the same way.
 *
 * Every tested set is completed with the dependencies it needs, so a test never fails for a missing library.
 */
class CulpritSearch(
    inventory: Inventory,
    /** Launches the server with exactly these jars and reports how it went. */
    private val launch: (List<JarEntry>) -> RunResult,
    private val maxRuns: Int = 40,
    private val progress: (String) -> kotlin.Unit = {},
    private val diagnoser: Diagnoser = Diagnoser(),
) {
    val units: List<Candidate> = inventory.jars.filter { it.error == null }.map { jar ->
        val mods = jar.mods
        Candidate(
            jar = jar,
            ids = (mods + jar.nested).flatMap { listOf(it.id) + it.provides }.map { it.lowercase() }.toSet(),
            requires = mods.flatMap { mod -> mod.dependencies.filter { it.required }.map { it.id.lowercase() } }.toSet(),
        )
    }

    private val providers: Map<String, List<Candidate>> =
        units.flatMap { unit -> unit.ids.map { it to unit } }.groupBy({ it.first }, { it.second }) +
            // Old id of Fabric API, still used in dependencies.
            ("fabric" to units.filter { "fabric-api" in it.ids })

    private val runs = mutableListOf<RunRecord>()
    private val cache = mutableMapOf<Set<String>, Boolean>()
    private var reference: Fingerprint? = null

    /** [kept] plus everything it needs, transitively. */
    fun closure(kept: Collection<Candidate>): Set<Candidate> {
        val result = LinkedHashSet(kept)
        val queue = ArrayDeque(kept)
        while (queue.isNotEmpty()) {
            val unit = queue.removeFirst()
            unit.requires.forEach { id ->
                val provider = providers[id]?.firstOrNull { it in result } ?: providers[id]?.firstOrNull()
                if (provider != null && result.add(provider)) queue += provider
            }
        }
        return result
    }

    /** [removed] and everything that cannot start without it, transitively. */
    fun dependents(removed: Candidate): Set<Candidate> {
        val result = linkedSetOf(removed)
        var changed = true
        while (changed) {
            changed = false
            units.filter { it !in result }.forEach { unit ->
                val broken = unit.requires.any { id ->
                    val candidates = providers[id].orEmpty()
                    candidates.isNotEmpty() && candidates.all { it in result }
                }
                if (broken && result.add(unit)) changed = true
            }
        }
        return result
    }

    /**
     * Identity of a crash, taken only from what ended the run: a crash report or a fatal startup error.
     * Servers log plenty of errors that stop nothing (seen on Paper: a plugin that fails to remap and is
     * skipped, while the real crash left no trace at all), and they must not tell two runs apart.
     */
    fun fingerprint(result: RunResult): Fingerprint {
        val fatalLogs = result.logs.filter { isCrashReport(it) }.ifEmpty { result.logs.filter { FATAL.containsMatchIn(it.text) } }
        if (result.outcome == Outcome.READY || fatalLogs.isEmpty()) return Fingerprint(result.outcome, null, null)
        val report: Report = diagnoser.diagnose(fatalLogs, inventory = null)
        val situation = report.primary?.situation?.takeIf { it != Situation.UNCAUGHT_EXCEPTION }
        return Fingerprint(result.outcome, situation, report.exceptions.firstOrNull()?.substringBefore(':'))
    }

    private fun isCrashReport(log: Diagnoser.Log) = log.text.contains("---- Minecraft Crash Report ----") || log.name.startsWith("hs_err_pid")

    private class BudgetExhausted : RuntimeException()

    /** True when the set crashes the same way as the full set. */
    private fun fails(kept: Collection<Candidate>, note: String? = null): Boolean {
        val full = closure(kept)
        val key = full.map { it.key }.toSet()
        cache[key]?.let { return it }
        if (runs.size >= maxRuns) throw BudgetExhausted()
        val result = launch(full.map { it.jar })
        val print = fingerprint(result)
        val same = reference?.let { it.sameAs(print) } ?: false
        runs += RunRecord(runs.size + 1, full.map { it.key }, result.outcome, result.seconds, same, note)
        progress("run ${runs.size}: ${full.size} jar(s), ${result.outcome.name.lowercase()}${if (same) ", same crash" else ""} (${"%.0f".format(result.seconds)} s)")
        cache[key] = same
        return same
    }

    fun search(suspects: List<String> = emptyList()): SearchResult {
        progress("run 1: all ${units.size} jars, to record the crash")
        val baseline = launch(units.map { it.jar })
        val print = fingerprint(baseline)
        runs += RunRecord(1, units.map { it.key }, baseline.outcome, baseline.seconds, baseline.outcome != Outcome.READY, "reference")
        if (baseline.outcome == Outcome.READY) {
            return SearchResult(false, emptyList(), emptyList(), true, print, runs.toList())
        }
        reference = print
        cache[units.map { it.key }.toSet()] = true

        var found: List<Candidate> = units
        var complete = true
        try {
            found = fromSuspects(suspects) ?: ddmin(units, forced = emptyList())
        } catch (_: BudgetExhausted) {
            complete = false
        }
        return SearchResult(true, found.map { it.key }, found.map { it.label }, complete, reference, runs.toList())
    }

    private fun matching(name: String): Candidate? {
        val needle = name.lowercase()
        return units.firstOrNull { needle in it.ids } ?: units.firstOrNull { it.jar.file.lowercase().startsWith(needle) }
    }

    /** Suspects named by the analysis: proven necessary, then tested alone. */
    private fun fromSuspects(suspects: List<String>): List<Candidate>? {
        for (suspect in suspects.mapNotNull(::matching).distinct().take(3)) {
            val without = units - dependents(suspect)
            if (fails(without, "without suspect ${suspect.label}")) continue
            if (fails(listOf(suspect), "suspect ${suspect.label} alone")) return listOf(suspect)
            // Needed, but not enough alone: find what it conflicts with.
            return listOf(suspect) + ddmin(units - suspect, forced = listOf(suspect))
        }
        return null
    }

    /**
     * Delta debugging (Zeller): the smallest subset of [candidates] that, with [forced], still fails.
     * Finds a single culprit in about log2(n) launches and conflicts between several mods as well.
     */
    private fun ddmin(candidates: List<Candidate>, forced: List<Candidate>): List<Candidate> {
        var current = candidates
        var parts = 2
        while (current.size >= 2) {
            val chunks = current.chunked((current.size + parts - 1) / parts)
            val subset = chunks.firstOrNull { fails(it + forced) }
            if (subset != null) {
                current = subset
                parts = 2
                continue
            }
            val complement = if (chunks.size > 2) chunks.map { chunk -> current - chunk.toSet() }.firstOrNull { fails(it + forced) } else null
            if (complement != null) {
                current = complement
                parts = maxOf(parts - 1, 2)
                continue
            }
            if (parts >= current.size) break
            parts = minOf(current.size, parts * 2)
        }
        // A single remaining candidate may still not be needed (the forced ones fail alone).
        if (current.size == 1 && forced.isNotEmpty() && fails(forced)) return emptyList()
        return current
    }
}

private val FATAL = Regex(
    """Exception in thread "(?:main|Server thread)"|Encountered an unexpected exception|Failed to start the minecraft server|""" +
        """Incompatible mods found!|Mod loading has failed|This crash report has been saved to|Crash report saved to""",
)

/** Culprits the log analysis names: tested first. */
fun suspectsOf(report: Report): List<String> =
    report.findings.flatMap { finding -> finding.culprits.filter { it.kind == CulpritKind.MOD || it.kind == CulpritKind.PLUGIN }.map { it.id } }.distinct()

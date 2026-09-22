package dev.holo795.crashsleuth.logs

import dev.holo795.crashsleuth.model.Confidence
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Report
import dev.holo795.crashsleuth.model.Situation
import java.nio.file.Files
import java.nio.file.Path

/** Reads a log or crash report and returns what went wrong and who is to blame. */
class LogAnalyzer(
    private val detectors: List<Detector> = DEFAULT_DETECTORS,
) {
    fun analyze(path: Path): Report = analyze(Files.readString(path))

    private val EXPLAINS_DISCONNECTION = setOf(
        Situation.PROXY_FORWARDING, Situation.PROXY_BACKEND, Situation.REGISTRY_MISMATCH, Situation.MOD_MISMATCH, Situation.WRONG_MC,
    )

    fun analyze(text: String): Report {
        val document = LogDocument(text)
        val environment = EnvironmentDetector.detect(document)
        val specific = detectors.flatMap { it.detect(document, environment) }
        val findings = if (specific.any { it.situation != Situation.UNCAUGHT_EXCEPTION || it.confidence >= Confidence.HIGH }) {
            specific
        } else {
            // Outside a real crash, an exception nobody can be blamed for is only listed, not reported:
            // healthy modpacks log plenty of harmless ones (seen on a 106-mod NeoForge pack).
            val fatal = FATAL.containsMatchIn(document.text)
            specific + UncaughtExceptionDetector.detect(document, environment)
                .filter { fatal || it.confidence > Confidence.LOW || it.culprits.isNotEmpty() }
        }
        // "Client disconnected with reason: ..." only repeats what a precise finding already explains.
        val explained = findings.any { it.situation in EXPLAINS_DISCONNECTION }
        val deadlocked = findings.any { it.situation == Situation.DEADLOCK }
        val kept = findings.filterNot {
            (explained && it.situation == Situation.CONNECTION_LOST) || (deadlocked && it.situation == Situation.HANG)
        }
        return Report(
            environment = environment,
            findings = enrich(rank(deduplicate(kept))),
            exceptions = document.stackTraces.map { it.root.headline }.distinct().take(10),
        )
    }

    private fun deduplicate(findings: List<Finding>): List<Finding> =
        findings.distinctBy { finding -> finding.situation to finding.culprits.map { it.id.lowercase() } }

    /** Gives every culprit the best name and version found anywhere in the report. */
    private fun enrich(findings: List<Finding>): List<Finding> {
        val best = findings.flatMap { it.culprits }
            .groupBy { it.id.lowercase() }
            .mapValues { (_, culprits) -> culprits.maxBy { (if (it.name != null) 2 else 0) + (if (it.version != null) 1 else 0) } }
        return findings.map { finding ->
            finding.copy(culprits = finding.culprits.map { culprit ->
                val known = best[culprit.id.lowercase()] ?: culprit
                culprit.copy(name = culprit.name ?: known.name, version = culprit.version ?: known.version, file = culprit.file ?: known.file)
            })
        }
    }

    /** Most certain first; a precise situation beats the generic "unhandled error". */
    private fun rank(findings: List<Finding>): List<Finding> =
        findings.sortedWith(
            compareByDescending<Finding> { it.confidence }
                .thenBy { if (it.situation == Situation.UNCAUGHT_EXCEPTION) 1 else 0 }
                .thenBy { it.situation.stage.ordinal },
        )

    companion object {
        private val FATAL = Regex(
            """---- Minecraft Crash Report ----|Exception in thread "(?:main|Server thread)"|Encountered an unexpected exception|""" +
                """Failed to start the minecraft server|A fatal error has been detected by the Java Runtime Environment""",
        )

        val DEFAULT_DETECTORS: List<Detector> = listOf(
            FmlDependencyDetector,
            FmlFailureMessageDetector,
            FabricDependencyDetector,
            WrongLoaderDetector,
            ClientOnlyDetector,
            VersionedInternalsDetector,
            PluginLoadDetector,
            PluginRuntimeDetector,
            NotAPluginDetector,
            DuplicateDetector,
            DeadlockDetector,
            HangDetector,
            LagDetector,
            ResourcePackDetector,
            ShaderPackDetector,
            SignatureDetector.BUILT_IN,
            JavaVersionDetector,
            OutOfMemoryDetector,
            StackOverflowDetector,
            MixinDetector,
            TickingDetector,
            NativeCrashDetector,
            SuspectedModsDetector,
        )
    }
}

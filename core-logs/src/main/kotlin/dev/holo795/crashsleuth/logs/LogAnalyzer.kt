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

    fun analyze(text: String): Report {
        val document = LogDocument(text)
        val environment = EnvironmentDetector.detect(document)
        val specific = detectors.flatMap { it.detect(document, environment) }
        val findings = if (specific.any { it.situation != Situation.UNCAUGHT_EXCEPTION || it.confidence >= Confidence.HIGH }) {
            specific
        } else {
            specific + UncaughtExceptionDetector.detect(document, environment)
        }
        return Report(
            environment = environment,
            findings = enrich(rank(deduplicate(findings))),
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
        val DEFAULT_DETECTORS: List<Detector> = listOf(
            FmlDependencyDetector,
            FabricDependencyDetector,
            PluginLoadDetector,
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

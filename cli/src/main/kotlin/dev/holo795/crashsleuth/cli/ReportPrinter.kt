package dev.holo795.crashsleuth.cli

import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Messages
import dev.holo795.crashsleuth.model.Report
import dev.holo795.crashsleuth.model.Situation

/** Plain text report, readable by a beginner, with the technical evidence underneath. */
class ReportPrinter(private val messages: Messages) {
    fun render(report: Report): String = buildString {
        val environment = report.environment
        val unknown = messages.get("report.unknown")
        appendLine(
            "${messages.get("report.environment")}: ${environment.platform.displayName}" +
                " ${environment.loaderVersion ?: ""}".trimEnd() +
                " | Minecraft ${environment.minecraftVersion ?: unknown}" +
                " | Java ${environment.javaVersion ?: unknown}",
        )
        appendLine()
        val primary = report.primary
        if (primary == null) {
            appendLine(messages.get("report.noFinding"))
            appendLine(messages.get("report.noFindingHint"))
            if (report.exceptions.isNotEmpty()) {
                appendLine()
                appendLine("${messages.get("report.exceptions")}:")
                report.exceptions.forEach { appendLine("  - $it") }
            }
            return@buildString
        }
        appendFinding(primary)
        val others = report.findings.drop(1)
        if (others.isNotEmpty()) {
            appendLine()
            appendLine("${messages.get("report.otherFindings")}:")
            others.forEach { finding ->
                appendLine("  - ${messages.title(finding.situation)}${culpritList(finding).let { if (it.isEmpty()) "" else " ($it)" }}")
            }
        }
    }

    private fun StringBuilder.appendFinding(finding: Finding) {
        appendLine(messages.title(finding.situation))
        val culprits = culpritList(finding)
        if (culprits.isNotEmpty()) {
            val label = if (finding.culprits.size > 1) messages.get("report.culprits") else messages.get("report.culprit")
            appendLine("$label: $culprits")
        }
        appendLine("${messages.get("report.confidence")}: ${messages.get("confidence.${finding.confidence.name}")}")
        appendLine()
        appendLine("${messages.get("report.advice")}: ${advice(finding)}")
        if (finding.evidence.isNotEmpty()) {
            appendLine()
            appendLine("${messages.get("report.evidence")}:")
            finding.evidence.forEach { appendLine("  > $it") }
        }
    }

    private fun culpritList(finding: Finding): String =
        finding.culprits.filter { it.kind != CulpritKind.JAVA }.joinToString(", ") { describe(it) }

    private fun describe(culprit: Culprit): String =
        buildString {
            append(culprit.label)
            if (culprit.name != null && culprit.name != culprit.id) append(" (${culprit.id})")
            culprit.version?.let { append(" $it") }
        }

    private fun advice(finding: Finding): String {
        val details = finding.details
        val first = finding.culprits.firstOrNull { it.kind != CulpritKind.JAVA }?.let { describe(it) } ?: "?"
        details["adviceKey"]?.let { return messages.get(it, first) }
        return when (finding.situation) {
            Situation.DEP_MISSING -> messages.advice(finding.situation, details["dependency"], details["requester"])
            Situation.DEP_VERSION -> messages.advice(finding.situation, details["dependency"], details["requester"], details["expected"], details["actual"])
            Situation.JAVA_VERSION -> messages.advice(finding.situation, details["required"], details["current"])
            Situation.TICK_ENTITY, Situation.TICK_BLOCK_ENTITY ->
                messages.advice(finding.situation, details["object"], details["location"] ?: "?", first)
            Situation.NATIVE_CRASH -> messages.advice(finding.situation, details["library"])
            Situation.WRONG_LOADER -> messages.advice(finding.situation, first, details["platform"])
            Situation.WRONG_MC -> messages.advice(finding.situation, first, details["actual"] ?: "?", details["expected"] ?: "?")
            Situation.SILENT_ERROR -> messages.advice(finding.situation, first, details["count"] ?: "1")
            Situation.DUPLICATE -> messages.advice(finding.situation, first, details["files"] ?: "")
            Situation.MOD_CONFLICT -> messages.advice(finding.situation, first, finding.culprits.getOrNull(1)?.let { describe(it) } ?: "?")
            Situation.WORLD_DOWNGRADE -> messages.advice(finding.situation, details["expected"], details["actual"])
            else -> messages.advice(finding.situation, first)
        }
    }
}

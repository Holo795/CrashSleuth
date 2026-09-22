package dev.holo795.crashsleuth.cli

import dev.holo795.crashsleuth.app.ReportText
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Messages
import dev.holo795.crashsleuth.model.Report

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
            ReportText(messages).noFindingHints(report).forEach { appendLine(it) }
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
                appendLine("  - ${text.title(finding)}${culpritList(finding).let { if (it.isEmpty()) "" else " ($it)" }}")
            }
        }
    }

    private fun StringBuilder.appendFinding(finding: Finding) {
        appendLine(text.title(finding))
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

    private val text = ReportText(messages)

    private fun culpritList(finding: Finding): String = text.culpritList(finding)

    private fun advice(finding: Finding): String = text.advice(finding)
}

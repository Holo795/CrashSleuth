package dev.holo795.crashsleuth.app

import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Messages
import dev.holo795.crashsleuth.model.Situation

/** The words of a finding, the same in the command line, the desktop app and shared reports. */
class ReportText(val messages: Messages) {
    fun title(finding: Finding): String = messages.title(finding.situation)

    fun describe(culprit: Culprit): String =
        buildString {
            append(culprit.label)
            if (culprit.name != null && culprit.name != culprit.id) append(" (${culprit.id})")
            culprit.version?.let { append(" $it") }
        }

    /** The culprits a person can act on (Java itself is named in the advice instead). */
    fun culprits(finding: Finding): List<Culprit> = finding.culprits.filter { it.kind != CulpritKind.JAVA }

    fun culpritList(finding: Finding): String = culprits(finding).joinToString(", ") { describe(it) }

    fun advice(finding: Finding): String {
        val details = finding.details
        val first = culprits(finding).firstOrNull()?.let { describe(it) } ?: "?"
        details["adviceKey"]?.let { return messages.get(it, first, details["player"], details["server"]) }
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
            Situation.OUTDATED -> messages.advice(finding.situation, first, details["latest"])
            Situation.WORLD_DOWNGRADE -> messages.advice(finding.situation, details["expected"], details["actual"])
            Situation.CONNECTION_LOST -> messages.advice(finding.situation, first, details["reason"] ?: "?")
            Situation.PROXY_BACKEND -> messages.advice(finding.situation, details["server"] ?: first)
            else -> messages.advice(finding.situation, first)
        }
    }
}

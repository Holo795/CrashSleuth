package dev.holo795.crashsleuth.app

import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Messages
import dev.holo795.crashsleuth.model.Situation

/** The words of a finding, the same in the command line, the desktop app and shared reports. */
class ReportText(val messages: Messages) {
    /** The heading of a finding; a few cases say it better than their situation does. */
    fun title(finding: Finding): String =
        finding.details["titleKey"]?.let { messages.get(it) } ?: messages.title(finding.situation)

    fun describe(culprit: Culprit): String =
        buildString {
            append(culprit.label)
            if (culprit.name != null && culprit.name != culprit.id) append(" (${culprit.id})")
            culprit.version?.let { append(" $it") }
        }

    /** The culprits a person can act on (Java itself is named in the advice instead). */
    fun culprits(finding: Finding): List<Culprit> = finding.culprits.filter { it.kind != CulpritKind.JAVA }

    fun culpritList(finding: Finding): String = culprits(finding).joinToString(", ") { describe(it) }

    /**
     * What is worth saying when nothing was found. Checked in the lab on 22/09/2026: a plugin eating
     * 70 ms of every tick makes Paper 1.21.11 write nothing at all, where 1.21.1 still wrote
     * "Can't keep up!". On those servers a slow tick leaves no trace, and only a profile shows it.
     */
    fun noFindingHints(report: dev.holo795.crashsleuth.model.Report): List<String> {
        val environment = report.environment
        val silent = environment.platform.kind == dev.holo795.crashsleuth.model.PlatformKind.PLUGINS &&
            atLeast(environment.minecraftVersion, 21, 9)
        return if (silent) listOf(messages.get("report.hint.silentLag")) else emptyList()
    }

    /** True for Minecraft 1.x.y at or above the given minor and patch, and for anything newer (26.x). */
    private fun atLeast(version: String?, minor: Int, patch: Int): Boolean {
        val parts = version?.split('.')?.mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() } ?: return false
        if (parts.isEmpty()) return false
        if (parts[0] != 1) return parts[0] > 1
        val (thisMinor, thisPatch) = (parts.getOrElse(1) { 0 }) to (parts.getOrElse(2) { 0 })
        return thisMinor > minor || (thisMinor == minor && thisPatch >= patch)
    }

    fun advice(finding: Finding): String {
        val details = finding.details
        val first = culprits(finding).firstOrNull()?.let { describe(it) } ?: "?"
        // Advice of a signature or a special case: {0} culprit, {1} player, {2} server, {3} percent, {4} method, {5} behindMs, {6} count.
        details["adviceKey"]?.let {
            val second = culprits(finding).getOrNull(1)?.let { culprit -> describe(culprit) }
            return messages.get(it, first, details["player"] ?: second, details["server"], details["percent"], details["method"] ?: details["drawing"]?.let { messages.get("render.what.$it") }, details["behindMs"], details["count"])
        }
        // Naming nobody is better than naming "?": say what is known and what to do next instead.
        if (culprits(finding).isEmpty() && finding.situation == Situation.UNCAUGHT_EXCEPTION) {
            return messages.get("uncaught.no-culprit")
        }
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
            Situation.CORRUPT_CHUNK, Situation.CORRUPT_ENTITY -> {
                val x = details["x"]?.toIntOrNull()
                val z = details["z"]?.toIntOrNull()
                val region = details["region"] ?: if (x != null && z != null) "r.${Math.floorDiv(x, 32)}.${Math.floorDiv(z, 32)}.mca" else "?"
                val folder = if (finding.situation == Situation.CORRUPT_ENTITY) "entities" else "region"
                messages.advice(finding.situation, if (x != null && z != null) "[$x, $z]" else "?", details["world"] ?: "world", details["file"] ?: "$folder/$region")
            }
            Situation.CONNECTION_LOST -> messages.advice(finding.situation, first, details["reason"] ?: "?")
            Situation.PROXY_BACKEND -> messages.advice(finding.situation, details["server"] ?: first)
            else -> messages.advice(finding.situation, first)
        }
    }
}

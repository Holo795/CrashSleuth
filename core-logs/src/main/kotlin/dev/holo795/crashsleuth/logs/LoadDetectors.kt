package dev.holo795.crashsleuth.logs

import dev.holo795.crashsleuth.model.Confidence
import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.Environment
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Situation

/**
 * The server falls behind: `Can't keep up! Is the server overloaded? Running 2518ms or 50 ticks behind`.
 * One warning while the world loads is normal; repeated warnings once the server is ready are lag. The log
 * cannot say who is slow; a spark profile can (see [SparkProfile]).
 */
object LagDetector : Detector {
    private val BEHIND = Regex("""Can't keep up! Is the server overloaded\? Running (\d+)ms or (\d+) ticks behind""")
    private val READY = Regex("""Done \([\d.,]+s\)!""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        val ready = document.find(READY)?.range?.last ?: return emptyList()
        val warnings = document.findAll(BEHIND).filter { it.range.first > ready }.toList()
        val worst = warnings.maxByOrNull { it.groupValues[1].toLong() } ?: return emptyList()
        // Two warnings, or a single big one: a server 16 000 chickens behind only wrote the line once
        // (lab, 22/09/2026, after a report on the Paper forums).
        if (warnings.size < 2 && worst.groupValues[1].toLong() < 2000) return emptyList()
        return listOf(
            Finding(
                situation = Situation.LAG,
                confidence = Confidence.MEDIUM,
                evidence = listOfNotNull(document.lineContaining(worst.value)),
                details = mapOf("count" to warnings.size.toString(), "behindMs" to worst.groupValues[1], "adviceKey" to "lag.no-profile"),
            ),
        )
    }
}

/**
 * Two threads each holding the lock the other waits for. The JVM names them in thread dumps (jstack,
 * `kill -3`): `Found one Java-level deadlock`. Paper's watchdog does not say who holds a lock, but its dump
 * shows the main thread BLOCKED in a plugin while another thread of the same plugin is BLOCKED too.
 */
object DeadlockDetector : Detector {
    private val JVM = Regex("""Found (?:one|\d+) Java-level deadlocks?:""")
    private val FRAME = Regex("""^at ([\w$.]+)\.[\w$<>]+\(""")
    private val PAPER_FRAME = Regex("""^((?:[^\s/()]+/+)*)([\w$]+(?:\.[\w$<>]+)+)\((.*)\)$""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> =
        jvmReport(document, environment) ?: paperWatchdog(document, environment) ?: emptyList()

    private fun jvmReport(document: LogDocument, environment: Environment): List<Finding>? {
        val anchor = document.find(JVM) ?: return null
        val start = document.text.substring(0, anchor.range.first).count { it == '\n' }
        val block = document.lines.drop(start).take(200).map { it.trim() }
        val threads = block.takeWhile { !it.startsWith("Java stack information") }
            .mapNotNull { Regex("""^"(.+)":$""").matchEntire(it)?.groupValues?.get(1) }.distinct()
        val classes = block.dropWhile { !it.startsWith("Java stack information") }
            .takeWhile { !it.startsWith("Found ") }
            .mapNotNull { FRAME.find(it)?.groupValues?.get(1) }
            .filterNot { Attribution.isPlatformClass(it) }
        val culprit = classes.firstOrNull()?.let { Culprit(Attribution.kindFor(environment), it.split('.').take(3).joinToString(".")) }
        return listOf(finding(culprit, threads, anchor.value, Confidence.CERTAIN))
    }

    private class DumpedThread(val name: String, val blocked: Boolean, val jars: List<String>)

    private fun paperWatchdog(document: LogDocument, environment: Environment): List<Finding>? {
        if (!document.text.contains("Entire Thread Dump")) return null
        val threads = mutableListOf<DumpedThread>()
        var name: String? = null
        var blocked = false
        val jars = mutableListOf<String>()
        fun flush() { name?.let { threads += DumpedThread(it, blocked, jars.toList()) }; name = null; jars.clear() }
        document.lines.map { document.stripPrefix(it) }.forEach { line ->
            when {
                line.startsWith("Current Thread: ") -> { flush(); name = line.removePrefix("Current Thread: ").trim(); blocked = false }
                name != null && line.contains("State: ") -> blocked = line.substringAfter("State: ").trim() == "BLOCKED"
                name != null && line.startsWith("---") -> flush()
                name != null -> PAPER_FRAME.matchEntire(line)?.groupValues?.get(1)?.split('/')?.firstOrNull { it.endsWith(".jar") }
                    ?.takeUnless { Attribution.isPlatformJar(it) }?.let(jars::add)
            }
        }
        flush()
        val server = threads.firstOrNull { it.name == "Server thread" && it.blocked } ?: return null
        val jar = server.jars.firstOrNull() ?: return null
        val partner = threads.firstOrNull { it.name != "Server thread" && it.blocked && jar in it.jars } ?: return null
        val culprit = Culprit(Attribution.kindFor(environment), Attribution.idFromJar(jar), file = jar)
        return listOf(finding(culprit, listOf(server.name, partner.name), "Server thread BLOCKED in $jar, ${partner.name} BLOCKED in $jar", Confidence.HIGH))
    }

    private fun finding(culprit: Culprit?, threads: List<String>, evidence: String, confidence: Confidence) = Finding(
        situation = Situation.DEADLOCK,
        confidence = if (culprit == null) Confidence.MEDIUM else confidence,
        culprits = listOfNotNull(culprit),
        evidence = listOf(evidence),
        details = mapOf("threads" to threads.joinToString(", ")),
    )
}

package dev.holo795.crashsleuth.logs

import dev.holo795.crashsleuth.model.Confidence
import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Environment
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Situation

/**
 * The main thread stopped ticking. Paper's watchdog prints `The server has not responded for 15 seconds!`
 * then a dump of the server thread; the vanilla watchdog says `A single server tick took 60.00 seconds`.
 * Whoever sits at the top of the server thread, below the JDK, is blocking it.
 */
object HangDetector : Detector {
    private val ANCHOR = Regex("""The server has (?:not responded for \d+ seconds|stopped responding)|A single server tick took [\d.]+ seconds""")
    private val SERVER_THREAD = Regex("""(?:Current Thread|Thread): Server thread|"Server thread"""")
    /** `MyPlugin-1.0.jar//com.foo.Bar.baz(Bar.java:3)`, `java.base@21/java.lang.Thread.sleep0(Native Method)` or `at com.foo...`. */
    private val DUMP_FRAME = Regex("""^(?:at\s+)?((?:[^\s/()]+/+)*)([\w$]+(?:\.[\w$<>]+)+)\((.*)\)$""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        val anchor = document.find(ANCHOR) ?: return emptyList()
        val start = document.text.substring(0, anchor.range.first).count { it == '\n' }
        val threadLine = (start until minOf(document.lines.size, start + 400)).firstOrNull { SERVER_THREAD.containsMatchIn(document.lines[it]) }
        val frames = threadLine?.let { first ->
            document.lines.drop(first + 1).take(80)
                .map { document.stripPrefix(it) }
                .dropWhile { !DUMP_FRAME.matches(it) }
                .takeWhile { DUMP_FRAME.matches(it) }
                .mapNotNull { DUMP_FRAME.matchEntire(it) }
        }.orEmpty()
        val blocker = frames.firstNotNullOfOrNull { frame ->
            val prefix = frame.groupValues[1].split('/').filter { it.isNotEmpty() }
            val jar = prefix.firstOrNull { it.endsWith(".jar") }
            val className = frame.groupValues[2].substringBeforeLast('.')
            when {
                jar != null && !Attribution.isPlatformJar(jar) -> Culprit(Attribution.kindFor(environment), Attribution.idFromJar(jar), file = jar)
                jar == null && !Attribution.isPlatformClass(className) && prefix.none { it.startsWith("java.") } ->
                    Culprit(Attribution.kindFor(environment), className.split('.').take(3).joinToString("."))
                else -> null
            }
        }
        return listOf(
            Finding(
                situation = Situation.HANG,
                confidence = if (blocker != null) Confidence.HIGH else Confidence.MEDIUM,
                culprits = listOfNotNull(blocker),
                evidence = listOfNotNull(document.lineContaining(anchor.value), frames.firstOrNull { blocker?.file?.let(it.value::contains) == true }?.value),
            ),
        )
    }
}

/**
 * Paper, Spigot and Purpur keep running when a plugin fails in a scheduled task, an event listener or a
 * command, so nothing crashes but a feature is broken: `Task #2 for MyPlugin v1.0 generated an exception`,
 * `Could not pass event PlayerJoinEvent to MyPlugin v1.0`, `Unhandled exception executing command 'x' in plugin MyPlugin v1.0`.
 */
object PluginRuntimeDetector : Detector {
    private val PATTERNS = listOf(
        "task" to Regex("""Task #\d+ for (\S+) v(\S+) generated an exception"""),
        "event" to Regex("""Could not pass event (\S+) to (\S+) v(\S+)"""),
        "command" to Regex("""Unhandled exception executing command '([^']*)' in plugin (\S+) v(\S+)"""),
    )

    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        data class Hit(val plugin: String, val version: String, val kind: String, val line: String, val offset: Int)
        val hits = PATTERNS.flatMap { (kind, regex) ->
            document.findAll(regex).map { match ->
                val g = match.groupValues
                val (plugin, version) = if (kind == "task") g[1] to g[2] else g[2] to g[3]
                Hit(plugin, version, kind, match.value, match.range.last)
            }.toList()
        }
        return hits.groupBy { it.plugin }.map { (plugin, occurrences) ->
            val first = occurrences.minBy { it.offset }
            val lineIndex = document.text.substring(0, first.offset).count { it == '\n' }
            val trace = document.stackTraces.firstOrNull { it.lineIndex > lineIndex && it.lineIndex <= lineIndex + 2 }
            Finding(
                situation = Situation.SILENT_ERROR,
                confidence = Confidence.HIGH,
                culprits = listOf(Culprit(CulpritKind.PLUGIN, plugin, version = first.version)),
                evidence = listOfNotNull(document.lineContaining(first.line), trace?.root?.headline),
                details = mapOf("count" to occurrences.size.toString(), "where" to occurrences.map { it.kind }.distinct().joinToString()),
            )
        }
    }
}

/**
 * The same mod or plugin installed twice. Paper: `Ambiguous plugin name 'Chunky' for files 'a.jar' and 'b.jar'`;
 * Forge: `Found a duplicate mod jei at [...]` or `Mod ID: 'jei' from mod files: a.jar, b.jar`;
 * NeoForge (debug.log): `Found 2 mods for first modid jei, selecting most recent`.
 */
object DuplicateDetector : Detector {
    private val PATTERNS = listOf(
        Regex("""Ambiguous plugin name [`']([^`']+)' for files [`']([^`']+)' and [`']([^`']+)'"""),
        Regex("""Found a duplicate mod (\S+) at \[([^\]]+)]"""),
        Regex("""Mod ID: '([^']+)' from mod files: ([^\n]+)"""),
        Regex("""Found \d+ mods for first modid (\S+), selecting most recent"""),
        Regex("""Duplicate (?:versions for )?mod ID '([^']+)'"""),
    )

    override fun detect(document: LogDocument, environment: Environment): List<Finding> =
        PATTERNS.flatMap { regex ->
            document.findAll(regex).map { match ->
                val files = match.groupValues.drop(2).flatMap { it.split(',') }.map { it.trim().substringAfterLast('/') }.filter { it.isNotEmpty() }
                Finding(
                    situation = Situation.DUPLICATE,
                    // Paper and NeoForge keep one copy and start anyway; Forge refuses to start.
                    confidence = if (match.value.startsWith("Mod ID") || match.value.startsWith("Found a duplicate")) Confidence.CERTAIN else Confidence.HIGH,
                    culprits = listOf(Culprit(Attribution.kindFor(environment), match.groupValues[1])),
                    evidence = listOf(match.value.trim()),
                    details = if (files.isEmpty()) emptyMap() else mapOf("files" to files.joinToString()),
                )
            }.toList()
        }
}

/** Paper finds a jar in plugins/ that is not a plugin: `... does not contain a paper-plugin.yml or plugin.yml!`. */
object NotAPluginDetector : Detector {
    private val LINE = Regex("""(?:Directory|File) '(?:[^']*/)?([^'/]+\.jar)' does not contain a paper-plugin\.yml or plugin\.yml""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> =
        document.findAll(LINE).distinctBy { it.groupValues[1] }.map { match ->
            val jar = match.groupValues[1]
            Finding(
                situation = Situation.WRONG_LOADER,
                confidence = Confidence.HIGH,
                culprits = listOf(Culprit(CulpritKind.PLUGIN, Attribution.idFromJar(jar), file = jar)),
                evidence = listOf(match.value),
                details = mapOf("platform" to "${environment.platform.displayName} (plugins)"),
            )
        }.toList()
}

/**
 * A plugin built against the internals of one server version: `NoClassDefFoundError:
 * org/bukkit/craftbukkit/v1_20_R3/...` or `net/minecraft/server/v1_16_R3/...`. Paper stopped
 * relocating these packages in 1.20.5, so such plugins break on every other version.
 */
object VersionedInternalsDetector : Detector {
    private val MISSING = Regex("""(?:NoClassDefFoundError|ClassNotFoundException):?\s*((?:org[/.]bukkit[/.]craftbukkit|net[/.]minecraft[/.]server)[/.](v(\d+)_(\d+)_R\d+))""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> =
        document.stackTraces.mapNotNull { trace ->
            val match = trace.chain().firstNotNullOfOrNull { MISSING.find(it.headline) } ?: return@mapNotNull null
            val culprits = Attribution.culprits(trace, environment, 1).ifEmpty { return@mapNotNull null }
            val (_, _, major, minor) = match.destructured
            Finding(
                situation = Situation.WRONG_MC,
                confidence = Confidence.CERTAIN,
                culprits = culprits.map { it.copy(kind = CulpritKind.PLUGIN) },
                evidence = listOf(match.value),
                details = mapOf("expected" to "$major.$minor", "actual" to (environment.minecraftVersion ?: "?"), "internals" to match.groupValues[2]),
            )
        }.distinctBy { it.culprits.first().id }
}

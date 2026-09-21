package dev.holo795.crashsleuth.logs

import dev.holo795.crashsleuth.model.Confidence
import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Environment
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Situation

/** Recognises one family of problems in a log. */
fun interface Detector {
    fun detect(document: LogDocument, environment: Environment): List<Finding>
}

/** NeoForge and Forge: `Mod ID: 'x', Requested by: 'y', Expected range: '[1,)', Actual version: '[MISSING]'`. */
object FmlDependencyDetector : Detector {
    private val LINE = Regex("""Mod ID: '([^']+)', Requested by: '([^']+)', Expected range: '([^']*)', Actual version: '([^']*)'""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> =
        document.findAll(LINE).map { match ->
            val (dependency, requester, range, actual) = match.destructured
            val missing = actual.equals("[MISSING]", ignoreCase = true)
            Finding(
                situation = if (missing) Situation.DEP_MISSING else Situation.DEP_VERSION,
                confidence = Confidence.CERTAIN,
                culprits = listOf(Culprit(CulpritKind.MOD, requester), Culprit(CulpritKind.MOD, dependency)),
                evidence = listOf(match.value),
                details = mapOf("dependency" to dependency, "requester" to requester, "expected" to range, "actual" to actual),
            )
        }.toList()
}

/** Fabric and Quilt: `Mod 'Iris' (iris) 1.6.4 requires version 0.5.0 or later of mod 'Sodium' (sodium), ...`. */
object FabricDependencyDetector : Detector {
    private val LINE = Regex(
        """Mod '([^']+)' \(([\w.-]+)\) (\S+) requires (?:version (.+?)|any version|any version between (.+?)) of (?:mod '([^']+)' \(([\w.-]+)\)|([\w.-]+))(?:,| ) ?(which is missing!|but only the wrong version is present: ([^!\n]+)!)""",
    )

    override fun detect(document: LogDocument, environment: Environment): List<Finding> =
        document.findAll(LINE).map { match ->
            val g = match.groupValues
            val requesterName = g[1]
            val requesterId = g[2]
            val expected = g[4].ifEmpty { g[5] }.ifEmpty { "any" }
            val dependencyName = g[6].ifEmpty { g[8] }
            val dependencyId = g[7].ifEmpty { g[8] }
            val missing = g[9].startsWith("which is missing")
            Finding(
                situation = if (missing) Situation.DEP_MISSING else Situation.DEP_VERSION,
                confidence = Confidence.CERTAIN,
                culprits = listOf(
                    Culprit(CulpritKind.MOD, requesterId, requesterName, g[3]),
                    Culprit(CulpritKind.MOD, dependencyId, dependencyName),
                ),
                evidence = listOf(match.value.trim()),
                details = mapOf(
                    "dependency" to dependencyId,
                    "requester" to requesterId,
                    "expected" to expected,
                    "actual" to (g[10].ifEmpty { "[MISSING]" }),
                ),
            )
        }.toList()
}

/** Paper, Spigot and Purpur: plugins that fail to load or to enable. */
object PluginLoadDetector : Detector {
    private val COULD_NOT_LOAD = Regex("""Could not load (?:plugin )?'(?:plugins/)?([^']+?\.jar)' in folder '[^']*'""")
    private val UNKNOWN_DEPENDENCY = Regex("""UnknownDependencyException: (?:Unknown/missing dependency plugins: \[([^\]]+)]\. Please download and install these plugins to run '([^']+)'|(.+))""")
    private val UNSUPPORTED_API = Regex("""(?:Unsupported API version|compiled against a newer API version|newer API version)\s*([\w.]*)""", RegexOption.IGNORE_CASE)
    private val INVALID_DESCRIPTION = Regex("""InvalidDescriptionException: (.+)""")
    private val ENABLING = Regex("""Error occurred while enabling (\S+) v?(\S+) \(Is it up to date\?\)""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        val findings = mutableListOf<Finding>()
        document.findAll(UNKNOWN_DEPENDENCY).forEach { match ->
            val missing = (match.groupValues[1].ifEmpty { match.groupValues[3] })
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val requester = match.groupValues[2].ifEmpty { jarBefore(document, match.range.first)?.let(Attribution::idFromJar) ?: "?" }
            missing.forEach { dependency ->
                findings += Finding(
                    situation = Situation.DEP_MISSING,
                    confidence = Confidence.CERTAIN,
                    culprits = listOf(Culprit(CulpritKind.PLUGIN, requester), Culprit(CulpritKind.PLUGIN, dependency)),
                    evidence = listOf(match.value.trim()),
                    details = mapOf("dependency" to dependency, "requester" to requester, "expected" to "any", "actual" to "[MISSING]"),
                )
            }
        }
        document.findAll(COULD_NOT_LOAD).forEach { match ->
            val jar = match.groupValues[1]
            val following = document.text.substring(match.range.last, minOf(document.text.length, match.range.last + 600))
            if (UNKNOWN_DEPENDENCY.containsMatchIn(following)) return@forEach
            val reason = UNSUPPORTED_API.find(following)?.value ?: INVALID_DESCRIPTION.find(following)?.groupValues?.get(1)
            findings += Finding(
                situation = Situation.PLUGIN_API,
                confidence = if (reason != null) Confidence.CERTAIN else Confidence.HIGH,
                culprits = listOf(Culprit(CulpritKind.PLUGIN, Attribution.idFromJar(jar), file = jar)),
                evidence = listOfNotNull(match.value, reason?.trim()),
                details = mapOfNotNull("reason" to reason?.trim()),
            )
        }
        document.findAll(ENABLING).forEach { match ->
            val trace = document.stackTraces.firstOrNull { it.lineIndex > lineOf(document, match.range.first) }
            findings += Finding(
                situation = Situation.UNCAUGHT_EXCEPTION,
                confidence = Confidence.CERTAIN,
                culprits = listOf(Culprit(CulpritKind.PLUGIN, match.groupValues[1], version = match.groupValues[2])),
                evidence = listOfNotNull(match.value, trace?.root?.headline),
                details = mapOf("phase" to "enable"),
            )
        }
        return findings
    }

    private fun jarBefore(document: LogDocument, offset: Int): String? =
        COULD_NOT_LOAD.findAll(document.text.substring(0, offset)).lastOrNull()?.groupValues?.get(1)
}

/** `UnsupportedClassVersionError: ... class file version 65.0 ... up to 61.0`. */
object JavaVersionDetector : Detector {
    private val LINE = Regex("""UnsupportedClassVersionError: (\S+) has been compiled by a more recent version of the Java Runtime \(class file version (\d+)(?:\.\d+)?\), this version of the Java Runtime only recognizes class file versions up to (\d+)""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        val match = document.find(LINE) ?: return emptyList()
        val required = match.groupValues[2].toInt() - 44
        val current = match.groupValues[3].toInt() - 44
        val trace = document.stackTraces.firstOrNull { it.chain().any { e -> e.type.endsWith("UnsupportedClassVersionError") } }
        val owner = match.groupValues[1].replace('/', '.')
        val culprits = trace?.let { Attribution.culprits(it, environment, 1) }.orEmpty().ifEmpty {
            if (Attribution.isPlatformClass(owner)) emptyList() else listOf(Culprit(Attribution.kindFor(environment), owner.substringBeforeLast('.')))
        }
        return listOf(
            Finding(
                situation = Situation.JAVA_VERSION,
                confidence = Confidence.CERTAIN,
                culprits = listOf(Culprit(CulpritKind.JAVA, "java", "Java $current")) + culprits,
                evidence = listOf(match.value),
                details = mapOf("required" to required.toString(), "current" to current.toString(), "class" to owner),
            ),
        )
    }
}

/** `java.lang.OutOfMemoryError: Java heap space`, `GC overhead limit exceeded`, `Metaspace`. */
object OutOfMemoryDetector : Detector {
    private val LINE = Regex("""java\.lang\.OutOfMemoryError(?::\s*(.+))?""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        val match = document.find(LINE) ?: return emptyList()
        return listOf(
            Finding(
                situation = Situation.OUT_OF_MEMORY,
                confidence = Confidence.CERTAIN,
                culprits = listOf(Culprit(CulpritKind.JAVA, "memory", "Java memory")),
                evidence = listOf(match.value.trim()),
                details = mapOfNotNull("kind" to match.groupValues[1].trim().ifEmpty { null }),
            ),
        )
    }
}

/** `java.lang.StackOverflowError`: the repeating frames tell who loops. */
object StackOverflowDetector : Detector {
    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        val trace = document.stackTraces.firstOrNull { it.chain().any { e -> e.type == "java.lang.StackOverflowError" } } ?: return emptyList()
        return listOf(
            Finding(
                situation = Situation.STACK_OVERFLOW,
                confidence = Confidence.HIGH,
                culprits = Attribution.culprits(trace, environment, 2),
                evidence = listOf(trace.root.headline),
            ),
        )
    }
}

/** Mixin failures: `Mixin [create.mixins.json:SomeMixin] from mod create failed`, injection errors. */
object MixinDetector : Detector {
    private val FROM_MOD = Regex("""([\w.-]+\.mixins?\.json):([\w.$]+)(?: from mod ([\w.-]+))?""")
    private val FAILURE = Regex("""(MixinApplyError|MixinTransformerError|InvalidInjectionException|InvalidMixinException|MixinPreProcessorException|Mixin apply for mod [\w.-]+ failed|Mixin prepare failed|Critical injection failure)""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        val failure = document.find(FAILURE) ?: return emptyList()
        val around = document.text.substring(maxOf(0, failure.range.first - 400), minOf(document.text.length, failure.range.last + 1500))
        val mixins = FROM_MOD.findAll(around).toList()
        val direct = Regex("""Mixin apply for mod ([\w.-]+) failed""").find(around)?.groupValues?.get(1)
        val mods = (listOfNotNull(direct) + mixins.mapNotNull { it.groupValues[3].ifEmpty { null } } +
            mixins.map { it.groupValues[1].substringBefore(".mixins").substringBefore(".mixin") }).distinct()
        return listOf(
            Finding(
                situation = Situation.MIXIN_CONFLICT,
                confidence = if (mods.isNotEmpty()) Confidence.HIGH else Confidence.MEDIUM,
                culprits = mods.take(2).map { Culprit(CulpritKind.MOD, it) },
                evidence = listOf(failure.value) + mixins.take(2).map { it.value },
                details = mapOfNotNull("mixin" to mixins.firstOrNull()?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }),
            ),
        )
    }
}

/** Crash report sections `-- Entity being ticked --` and `-- Block entity being ticked --`. */
object TickingDetector : Detector {
    private val ENTITY_TYPE = Regex("""Entity Type:\s*([\w.-]+:[\w./-]+)""")
    private val ENTITY_LOCATION = Regex("""Entity's Exact location:\s*([-\d.]+),\s*([-\d.]+),\s*([-\d.]+)""")
    private val BLOCK_NAME = Regex("""Name:\s*([\w.-]+:[\w./-]+)""")
    private val BLOCK_LOCATION = Regex("""Block location:\s*World:\s*\((-?\d+),\s*(-?\d+),\s*(-?\d+)\)""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        val findings = mutableListOf<Finding>()
        val entity = document.section("Entity being ticked").joinToString("\n")
        ENTITY_TYPE.find(entity)?.let { type ->
            val location = ENTITY_LOCATION.find(entity)?.let { "${it.groupValues[1]}, ${it.groupValues[2]}, ${it.groupValues[3]}" }
            findings += ticking(Situation.TICK_ENTITY, type.groupValues[1], location, document, environment)
        }
        val block = document.section("Block entity being ticked").joinToString("\n")
        BLOCK_NAME.find(block)?.let { name ->
            val location = BLOCK_LOCATION.find(block)?.let { "${it.groupValues[1]}, ${it.groupValues[2]}, ${it.groupValues[3]}" }
            findings += ticking(Situation.TICK_BLOCK_ENTITY, name.groupValues[1], location, document, environment)
        }
        return findings
    }

    private fun ticking(situation: Situation, id: String, location: String?, document: LogDocument, environment: Environment): Finding {
        val namespace = id.substringBefore(':')
        val fromStack = document.stackTraces.firstOrNull()?.let { Attribution.culprits(it, environment, 1) }.orEmpty()
        val culprits = if (namespace != "minecraft") listOf(Culprit(CulpritKind.MOD, namespace)) + fromStack.filter { it.id != namespace } else fromStack
        return Finding(
            situation = situation,
            confidence = if (namespace != "minecraft" || fromStack.isNotEmpty()) Confidence.HIGH else Confidence.MEDIUM,
            culprits = culprits.take(2),
            evidence = listOfNotNull("$id${location?.let { " @ $it" } ?: ""}", document.stackTraces.firstOrNull()?.root?.headline),
            details = mapOfNotNull("object" to id, "location" to location),
        )
    }
}

/** NeoForge and Forge crash reports list the mods found in the stack under `Suspected Mod(s):`. */
object SuspectedModsDetector : Detector {
    private val HEADER = Regex("""^\s*Suspected Mods?:\s*(.*)$""")
    private val ENTRY = Regex("""^\s*([^(\n]+?)\s*\(([\w.-]+)\),\s*Version:\s*(\S+)""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        val start = document.lines.indexOfFirst { HEADER.containsMatchIn(it) }
        if (start < 0) return emptyList()
        val inline = HEADER.find(document.lines[start])!!.groupValues[1].trim()
        if (inline.equals("NONE", ignoreCase = true)) return emptyList()
        val candidates = (listOf(inline) + document.lines.drop(start + 1).take(6)).mapNotNull { ENTRY.find(it) }
        if (candidates.isEmpty()) return emptyList()
        val root = document.stackTraces.firstOrNull()?.root
        return listOf(
            Finding(
                situation = Situation.UNCAUGHT_EXCEPTION,
                confidence = Confidence.HIGH,
                culprits = candidates.map { Culprit(CulpritKind.MOD, it.groupValues[2], it.groupValues[1].trim(), it.groupValues[3]) },
                evidence = listOfNotNull(root?.headline) + candidates.map { it.value.trim() },
            ),
        )
    }
}

/** A crash report of the Java runtime itself (`hs_err_pid*.log`). */
object NativeCrashDetector : Detector {
    private val HEADER = Regex("""A fatal error has been detected by the Java Runtime Environment""")
    private val FRAME = Regex("""#\s*[CjJV]\s+\[([^\]+]+)""")
    private val GPU = Regex("""(atio6axx|atioglxx|amdxx|nvoglv|nvd3dum|ig\w*icd|ig75icd|libGL|libnvidia|mesa|opengl32)""", RegexOption.IGNORE_CASE)

    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        if (!HEADER.containsMatchIn(document.text)) return emptyList()
        val library = document.find(FRAME)?.groupValues?.get(1)?.trim() ?: "?"
        val graphics = GPU.containsMatchIn(library)
        return listOf(
            Finding(
                situation = if (graphics) Situation.RENDER else Situation.NATIVE_CRASH,
                confidence = Confidence.HIGH,
                culprits = listOf(Culprit(CulpritKind.SYSTEM, library)),
                evidence = listOfNotNull(document.lineContaining("Problematic frame"), document.lineContaining(library)),
                details = mapOf("library" to library),
            ),
        )
    }
}

/** Fallback: the main exception, attributed from its frames. */
object UncaughtExceptionDetector : Detector {
    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        val trace = mainTrace(document) ?: return emptyList()
        val culprits = Attribution.culprits(trace, environment)
        return listOf(
            Finding(
                situation = Situation.UNCAUGHT_EXCEPTION,
                confidence = if (culprits.isEmpty()) Confidence.LOW else Confidence.MEDIUM,
                culprits = culprits,
                evidence = trace.chain().map { it.headline }.distinct().take(3),
            ),
        )
    }

    /** In a crash report the first trace is the crash; in a log, the last error is usually the fatal one. */
    fun mainTrace(document: LogDocument): StackTrace? =
        if (document.isCrashReport) document.stackTraces.firstOrNull() else document.stackTraces.lastOrNull()
}

private fun lineOf(document: LogDocument, offset: Int): Int = document.text.substring(0, offset).count { it == '\n' }

private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

package dev.holo795.crashsleuth.logs

import dev.holo795.crashsleuth.model.Confidence
import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Environment
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Side
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
    /**
     * Every form Fabric prints: "requires version 1.2", "requires any version", "requires any 0.6.x version",
     * "requires version 2.0 or later", "requires any version before 1.8.13", "requires any version between ...".
     */
    private val LINE = Regex(
        """Mod '([^']+)' \(([\w.-]+)\) (\S+) requires (.+?) of (?:mod '([^']+)' \(([\w.-]+)\)|([\w.-]+))(?:,| ) ?(which is missing!|but only the wrong version is present: ([^!\n]+)!)""",
    )

    override fun detect(document: LogDocument, environment: Environment): List<Finding> =
        document.findAll(LINE).map { match ->
            val g = match.groupValues
            val requesterName = g[1]
            val requesterId = g[2]
            // "version 1.2" gives "1.2", "any 0.6.x version" gives "0.6.x", "any version" gives "any".
            val expected = g[4].removePrefix("version ").removePrefix("any ").removeSuffix(" version").trim()
                .let { if (it == "version" || it.isEmpty()) "any" else it }
            val dependencyId = g[6].ifEmpty { g[7] }
            val dependencyName = g[5].ifEmpty { KNOWN_NAMES[dependencyId] ?: g[7] }
            val missing = g[8].startsWith("which is missing")
            if (dependencyId == "minecraft" && !missing) {
                return@map wrongMinecraft(Culprit(CulpritKind.MOD, requesterId, requesterName, g[3]), expected, g[9], match.value.trim())
            }
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
                    "actual" to (g[9].ifEmpty { "[MISSING]" }),
                ),
            )
        }.toList()
}

/** Identifiers whose readable name is not in the message: `fabric` is the legacy id of Fabric API. */
private val KNOWN_NAMES = mapOf(
    "fabric" to "Fabric API",
    "fabric-api" to "Fabric API",
    "fabricloader" to "Fabric Loader",
    "quilted_fabric_api" to "Quilted Fabric API",
    "neoforge" to "NeoForge",
    "forge" to "Forge",
    "minecraft" to "Minecraft",
    "java" to "Java",
)

/**
 * NeoForge and Forge mod loading crash reports:
 * `Failure message: Mod supplementaries requires moonlight 1.21-3.6.4 or above` then
 * `Currently, moonlight is not installed` (or `is 1.21-3.5.0`).
 */
object FmlFailureMessageDetector : Detector {
    private val FAILURE = Regex("""Mod ([\w.-]+) requires ([\w.-]+) (.+?)\s*\n\s*Currently, ([\w.-]+) is ([^\n]+)""")
    private val SECTION_FILE = Regex("""Mod [Ff]ile: (\S+\.jar)""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> =
        document.findAll(FAILURE).map { match ->
            val (requester, dependency, expected, _, current) = match.destructured
            val missing = current.trim().startsWith("not installed")
            val file = SECTION_FILE.findAll(document.text.substring(0, match.range.first)).lastOrNull()?.groupValues?.get(1)
            if (dependency == "minecraft" && !missing) {
                return@map wrongMinecraft(
                    Culprit(CulpritKind.MOD, requester, file = file?.substringAfterLast('/')), expected.trim(), current.trim(),
                    match.value.lines().joinToString(" ") { it.trim() }.trim(),
                )
            }
            Finding(
                situation = if (missing) Situation.DEP_MISSING else Situation.DEP_VERSION,
                confidence = Confidence.CERTAIN,
                culprits = listOf(
                    Culprit(CulpritKind.MOD, requester, file = file?.substringAfterLast('/')),
                    Culprit(CulpritKind.MOD, dependency, KNOWN_NAMES[dependency]),
                ),
                evidence = match.value.lines().map { it.trim() }.filter { it.isNotEmpty() },
                details = mapOf(
                    "dependency" to dependency,
                    "requester" to requester,
                    "expected" to expected.trim(),
                    "actual" to if (missing) "[MISSING]" else current.trim(),
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
        val match = document.find(LINE) ?: return moduleFormat(document, environment)
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

/** Module loader wording, used when the loader itself needs a newer Java: `Unsupported major.minor version 65.0`. */
private fun moduleFormat(document: LogDocument, environment: Environment): List<Finding> {
    val match = document.find(Regex("""Unsupported major\.minor version (\d+)(?:\.\d+)?""")) ?: return emptyList()
    val required = match.groupValues[1].toInt() - 44
    val current = environment.javaVersion?.substringBefore('.') ?: "?"
    return listOf(
        Finding(
            situation = Situation.JAVA_VERSION,
            confidence = Confidence.CERTAIN,
            culprits = listOf(Culprit(CulpritKind.JAVA, "java", "Java $current")),
            evidence = listOfNotNull(document.lineContaining("FindException"), match.value),
            details = mapOf("required" to required.toString(), "current" to current),
        ),
    )
}

/** `java.lang.OutOfMemoryError: Java heap space`, `GC overhead limit exceeded`, `Metaspace`. */
object OutOfMemoryDetector : Detector {
    private val LINE = Regex("""java\.lang\.OutOfMemoryError(?::\s*(.+))?""")

    // A damaged chunk can announce a huge size and exhaust memory in the threads that read chunks from disk:
    // there the error is a consequence, the damaged chunk is the cause.
    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        val match = document.findAll(LINE).firstOrNull { !whileReadingChunks(document, it.range.first) } ?: return emptyList()
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

/** The log entry an error belongs to (its line with the time and thread) was written while reading chunks. */
private fun whileReadingChunks(document: LogDocument, offset: Int): Boolean {
    val owner = document.text.substring(0, offset).split('\n').takeLast(80).lastOrNull { it.startsWith("[") } ?: return false
    return Regex("""IO-Worker-\d+|RegionFile I/O Thread|Failed to (?:read|load) (?:entity )?chunk""").containsMatchIn(owner)
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

/**
 * A client-only mod on a server: it needs classes that only exist in the game client (rendering,
 * LWJGL, `net.minecraft.client`), or the loader refuses to load a client class on a dedicated server.
 */
object ClientOnlyDetector : Detector {
    private val CLIENT_CLASS = Regex("""(?:NoClassDefFoundError|ClassNotFoundException):\s*((?:org[/.]lwjgl|net[/.]minecraft[/.]client|com[/.]mojang[/.]blaze3d)[\w/.$]*)""")
    private val INVALID_DIST = Regex("""Attempted to load class (\S+) for invalid dist DEDICATED_SERVER""")
    /** NeoForge/Forge crash report block: the mod is named, with its version and file. */
    private val FML_FAILURE = Regex(
        """Failure message: (.+?) \(([\w.-]+)\) has failed to load correctly\s*\n\s*(.*(?:invalid dist DEDICATED_SERVER|NoClassDefFoundError: (?:org/lwjgl|net/minecraft/client|com/mojang/blaze3d)).*)""",
    )
    private val MOD_VERSION = Regex("""^\s*Mod version: (\S+)""", RegexOption.MULTILINE)
    private val MOD_FILE = Regex("""Mod file: (\S+\.jar)""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        if (environment.side == Side.CLIENT) return emptyList()
        val failures = document.findAll(FML_FAILURE).map { match ->
            val following = document.text.substring(match.range.last, minOf(document.text.length, match.range.last + 400))
            val preceding = document.text.substring(maxOf(0, match.range.first - 300), match.range.first)
            val file = MOD_FILE.findAll(preceding).lastOrNull()?.groupValues?.get(1)?.substringAfterLast('/')
            Finding(
                situation = Situation.CLIENT_ONLY_ON_SERVER,
                confidence = Confidence.CERTAIN,
                culprits = listOf(Culprit(CulpritKind.MOD, match.groupValues[2], match.groupValues[1], MOD_VERSION.find(following)?.groupValues?.get(1), file)),
                evidence = listOf(match.value.lines().joinToString(" ") { it.trim() }),
                details = mapOfNotNull("class" to INVALID_DIST.find(match.groupValues[3])?.groupValues?.get(1)?.replace('/', '.')),
            )
        }.distinctBy { it.culprits.first().id }.toList()
        if (failures.isNotEmpty()) return failures

        // Loose "invalid dist" lines are often harmless (mixin probing optional client classes):
        // only an exception that actually stopped something counts.
        val trace = document.stackTraces.firstOrNull { trace ->
            trace.chain().any { CLIENT_CLASS.containsMatchIn(it.headline) || INVALID_DIST.containsMatchIn(it.headline) }
        } ?: return emptyList()
        val headline = trace.chain().map { it.headline }.first { CLIENT_CLASS.containsMatchIn(it) || INVALID_DIST.containsMatchIn(it) }
        val match = CLIENT_CLASS.find(headline) ?: INVALID_DIST.find(headline)!!
        val culprits = Attribution.culprits(trace, environment, 2)
        val version = trace.chain().flatMap { it.frames }.firstOrNull { it.module?.removeSuffix("_service") == culprits.firstOrNull()?.id }?.moduleVersion
        return listOf(
            Finding(
                situation = Situation.CLIENT_ONLY_ON_SERVER,
                confidence = if (culprits.isNotEmpty()) Confidence.HIGH else Confidence.MEDIUM,
                culprits = culprits.mapIndexed { index, culprit -> if (index == 0 && version != null) culprit.copy(version = version) else culprit },
                evidence = listOf(match.value),
                details = mapOf("class" to match.groupValues[1].replace('/', '.')),
            ),
        )
    }
}

/** A mod made for another loader: NeoForge skips it with a warning and the server may still start. */
object WrongLoaderDetector : Detector {
    private val SKIPPED = Regex("""File (\S+?\.jar) is a (Fabric|Quilt|Forge|NeoForge|LiteLoader|Rift|Bukkit|Paper) (?:mod|plugin) and cannot be loaded""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> =
        document.findAll(SKIPPED).map { match ->
            val jar = match.groupValues[1].substringAfterLast('/')
            Finding(
                situation = Situation.WRONG_LOADER,
                confidence = Confidence.CERTAIN,
                culprits = listOf(Culprit(CulpritKind.MOD, Attribution.idFromJar(jar), file = jar)),
                evidence = listOf(match.value),
                details = mapOf("madeFor" to match.groupValues[2], "platform" to environment.platform.displayName),
            )
        }.toList()
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

private fun wrongMinecraft(culprit: Culprit, expected: String, actual: String, evidence: String) = Finding(
    situation = Situation.WRONG_MC,
    confidence = Confidence.CERTAIN,
    culprits = listOf(culprit),
    evidence = listOf(evidence),
    details = mapOf("expected" to expected, "actual" to actual),
)

private fun lineOf(document: LogDocument, offset: Int): Int = document.text.substring(0, offset).count { it == '\n' }

internal fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

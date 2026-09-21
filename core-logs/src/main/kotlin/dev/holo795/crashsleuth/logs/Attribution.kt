package dev.holo795.crashsleuth.logs

import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Environment
import dev.holo795.crashsleuth.model.PlatformKind

/**
 * Finds which mod or plugin a stack trace comes from, using the jar names printed by the log
 * and, when there are none, the package names of the frames.
 */
object Attribution {
    /** Jars that belong to the game, the loader or common libraries: never a culprit. */
    private val PLATFORM_JAR = Regex(
        """^(minecraft|client|server|paper|purpur|folia|spigot|craftbukkit|bukkit|patched|forge|neoforge|fmlloader|fmlcore|""" +
            """javafmllanguage|lowcodelanguage|mclanguage|modlauncher|bootstraplauncher|securejarhandler|eventbus|coremods|""" +
            """mixin|sponge-mixin|mixinextras|fabric-loader|quilt-loader|intermediary|datafixerupper|netty|log4j|guava|gson|""" +
            """jopt|lwjgl|authlib|brigadier|fastutil|commons|slf4j|asm|jna|oshi|icu4j|kotlin|java|jdk|srgutils|terminalconsoleappender|""" +
            """jline|snakeyaml|adventure|examination|joml|night-config|typetools|unsafe|nashorn|accesstransformers|mergetool)""" +
            """([-_.\d].*)?\.jar$""",
        RegexOption.IGNORE_CASE,
    )

    /** Packages of the game, loaders, the JDK and common libraries. */
    private val PLATFORM_PACKAGES = listOf(
        "net.minecraft.", "com.mojang.", "java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "org.spongepowered.",
        "net.fabricmc.", "org.quiltmc.", "net.neoforged.", "net.minecraftforge.", "cpw.mods.", "io.netty.", "org.bukkit.",
        "org.spigotmc.", "io.papermc.", "com.destroystokyo.", "org.apache.", "com.google.", "it.unimi.", "com.llamalad7.",
        "org.objectweb.", "joptsimple.", "org.slf4j.", "com.electronwill.", "net.kyori.", "org.lwjgl.", "oshi.", "co.aikar.",
        "ca.spottedleaf.", "org.purpurmc.", "gg.pufferfish.", "org.yaml.", "org.jline.", "net.minecrell.", "java.base/",
        "net.md_5.", "com.velocitypowered.",
    )

    private val VERSION_SUFFIX = Regex("""[-_+](?:mc)?v?\d.*$""", RegexOption.IGNORE_CASE)
    private val LOADER_TAG = Regex("""[-_](?:fabric|neoforge|forge|quilt|paper|bukkit|spigot|mc)$""", RegexOption.IGNORE_CASE)

    fun isPlatformJar(jar: String): Boolean = PLATFORM_JAR.matches(jar.substringAfterLast('/'))

    fun isPlatformClass(className: String): Boolean = PLATFORM_PACKAGES.any { className.startsWith(it) }

    /** Readable identifier from a jar name: `create-1.21.1-6.0.4.jar` gives `create`. */
    fun idFromJar(jar: String): String {
        val base = jar.substringAfterLast('/').removeSuffix(".jar").substringBefore('%')
        val withoutVersion = VERSION_SUFFIX.replace(base, "").ifEmpty { base }
        return LOADER_TAG.replace(withoutVersion, "").ifEmpty { withoutVersion }
    }

    fun kindFor(environment: Environment): CulpritKind = when (environment.platform.kind) {
        PlatformKind.PLUGINS -> CulpritKind.PLUGIN
        PlatformKind.MODS -> CulpritKind.MOD
        else -> CulpritKind.UNKNOWN
    }

    /**
     * Culprits of a stack trace chain, most likely first. Frames closer to the top of the deepest
     * cause weigh more: that is where the error was thrown.
     */
    fun culprits(trace: StackTrace, environment: Environment, limit: Int = 3): List<Culprit> {
        val scores = linkedMapOf<String, Double>()
        val files = mutableMapOf<String, String?>()
        trace.chain().asReversed().forEachIndexed { depth, exception ->
            exception.frames.forEachIndexed { position, frame ->
                val key: String
                val file: String?
                if (frame.jar != null && !isPlatformJar(frame.jar)) {
                    key = idFromJar(frame.jar)
                    file = frame.jar
                } else if (frame.jar == null && !isPlatformClass(frame.className)) {
                    key = packageRoot(frame.className)
                    file = null
                } else {
                    return@forEachIndexed
                }
                val weight = 1.0 / (1 + position) / (1 + depth)
                scores[key] = (scores[key] ?: 0.0) + weight
                files.putIfAbsent(key, file)
            }
        }
        val kind = kindFor(environment)
        return scores.entries.sortedByDescending { it.value }.take(limit).map { (key, _) ->
            Culprit(kind = kind, id = key, file = files[key])
        }
    }

    /** `com.simibubi.create.content.Foo` gives `com.simibubi.create`. */
    private fun packageRoot(className: String): String {
        val parts = className.split('.')
        return parts.take(minOf(3, (parts.size - 1).coerceAtLeast(1))).joinToString(".")
    }
}

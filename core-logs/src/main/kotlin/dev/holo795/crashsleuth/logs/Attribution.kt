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
        """^(minecraft|client|server|loader|earlydisplay|bootstrap|securemodules|forgespi|jarjar\w*|paper|purpur|folia|spigot|velocity|bungeecord|waterfall|craftbukkit|bukkit|patched|forge|neoforge|fmlloader|fmlcore|""" +
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
        "net.md_5.", "com.velocitypowered.", "io.github.waterfallmc.",
    )

    /** Java modules of the game, loaders and libraries (NeoForge and Forge print them in frames). */
    private val PLATFORM_MODULE = Regex(
        """^(java\..*|jdk\..*|minecraft|neoforge|forge|fml_loader|fmlloader|fmlcore|javafmllanguage|mclanguage|""" +
            """lowcodelanguage|cpw\.mods\..*|bootstraplauncher|securejarhandler|modlauncher|org\..*|com\.google\..*|""" +
            """io\.netty\..*|net\.neoforged\..*|net\.minecraftforge\..*|mixinextras.*|coremods|accesstransformers|eventbus)$""",
    )

    /**
     * Mixin names the methods it injects after the mod: `handler$zza000$lithium$onTick`,
     * `redirect$bcd000$sodium$...`, `wrapOperation$abc000$create$...` (MixinExtras).
     */
    private val MIXIN_HANDLER = Regex("""^(?:handler|redirect|modify|localvar|constant|args|wrapOperation|wrapWithCondition|modifyExpressionValue|modifyReturnValue|wrapMethod)\$[0-9a-z]+\$([a-z][a-z0-9_]*)\$""")

    private val VERSION_SUFFIX = Regex("""[-_+](?:mc)?v?\d.*$""", RegexOption.IGNORE_CASE)
    private val LOADER_TAG = Regex("""[-_](?:fabric|neoforge|forge|quilt|paper|bukkit|spigot|mc)$""", RegexOption.IGNORE_CASE)

    fun isPlatformJar(jar: String): Boolean = jar.substringAfterLast('/').let { PLATFORM_JAR.matches(it) || GAME_JAR.matches(it) }

    /** The game itself as launchers install it: versions/1.21.1/1.21.1.jar, 24w14a.jar, 1.21.1-fabric.jar. */
    private val GAME_JAR = Regex("""^(\d+\.\d+(?:\.\d+)?|\d{2}w\d{2}[a-z])(?:[-_].*)?\.jar$""")

    /**
     * Game, loader, JDK or library code. A class without a package ("azu", "gfj$1") is the obfuscated game itself:
     * mods and plugins always have one, and from 1.21.11 the game's frames no longer name their jar.
     */
    fun isPlatformClass(className: String): Boolean =
        PLATFORM_PACKAGES.any { className.startsWith(it) } || OBFUSCATED_GAME.matches(className.substringAfterLast('/'))

    private val OBFUSCATED_GAME = Regex("""[a-z]{1,4}(?:\$[\w$]+)?""")

    /**
     * The class behind a lambda frame. The JVM writes them as
     * `net.minecraft.world.entity.Entity$$Lambda$7808/0x000000800222db68.accept`, where the part before
     * the slash is the class that made the lambda and the part after is an address. Without this, a
     * server held up by the game's own code was blamed on "0x000000800222db68" (lab, 22/09/2026).
     */
    private const val LAMBDA = "${'$'}${'$'}Lambda"

    fun lambdaOwner(text: String?): String? =
        text?.takeIf { it.contains(LAMBDA) }?.substringBefore(LAMBDA)?.trimEnd('.', '/')

    /** Readable identifier from a jar name: `create-1.21.1-6.0.4.jar` gives `create`. */
    fun idFromJar(jar: String): String {
        val base = jar.substringAfterLast('/').removeSuffix(".jar").substringBefore('%')
        val withoutVersion = VERSION_SUFFIX.replace(base, "").ifEmpty { base }
        return LOADER_TAG.replace(withoutVersion, "").ifEmpty { withoutVersion }
    }

    fun kindFor(environment: Environment): CulpritKind = when (environment.platform.kind) {
        PlatformKind.PLUGINS, PlatformKind.PROXY -> CulpritKind.PLUGIN
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
                val injectedBy = MIXIN_HANDLER.find(frame.method)?.groupValues?.get(1)
                if (injectedBy != null) {
                    // Code a mod injected into a game method: the frame shows the game class, the name shows the mod.
                    key = injectedBy
                    file = null
                } else if (lambdaOwner(frame.module) != null) {
                    // A lambda of the game or of a mod: what counts is the class that made it.
                    val owner = lambdaOwner(frame.module)!!
                    if (isPlatformClass(owner)) return@forEachIndexed
                    key = packageRoot(owner)
                    file = null
                } else if (frame.module != null && !PLATFORM_MODULE.matches(frame.module) && !isPlatformClass(frame.className)) {
                    key = frame.module.removeSuffix("_service")
                    file = null
                } else if (frame.jar != null && !isPlatformJar(frame.jar)) {
                    key = idFromJar(frame.jar)
                    file = frame.jar
                } else if (frame.jar == null && frame.module == null && !isPlatformClass(frame.className)) {
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

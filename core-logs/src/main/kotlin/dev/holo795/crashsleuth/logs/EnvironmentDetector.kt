package dev.holo795.crashsleuth.logs

import dev.holo795.crashsleuth.model.Environment
import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Side

/** Works out the platform, versions and side from the content of a log or crash report. */
object EnvironmentDetector {
    // Old builds: "version git-Paper-196 (MC: 1.20.1)"; recent ones: "version 1.21.1-133-master@a1b2c3d (date) (Implementing API version 1.21.1-R0.1-SNAPSHOT)".
    private val PAPER = Regex("""This server is running (Paper|Purpur|Folia|Pufferfish) version (\S+)""")
    /** Hybrids name themselves late, once everything loaded; the early markers catch a start that died first. */
    private val HYBRID = Regex("""This server is running (Mohist|Youer|Arclight) version (\S+)""")
    private val HYBRID_EARLY = Regex("""com\.mohistmc\.youer|youer\.mixins\.json|Thanks for using Youer|com\.mohistmc|Mohist mod loading|Thanks for using Mohist|\[Arclight|io\.izzel\.arclight|Minecraft [\w.]+ Arclight""")
    private val VELOCITY = Regex("""Booting up Velocity (\S+)""")
    private val BUNGEE = Regex("""Enabled (?:BungeeCord|Waterfall) version (\S+)""")
    private val CRAFTBUKKIT = Regex("""This server is running (?:CraftBukkit|Spigot) version (\S+)""")
    private val MC_TAG = Regex("""\(MC: ([\w.-]+)\)|Implementing API version (\d[\w.]*?)-R""")
    private val VANILLA_SERVER = Regex("""Starting minecraft server version ([\w.-]+)""")
    // Not inside another jar's name ("sodium-neoforge-0.8.13+mc1.21.1.jar" is Sodium's version).
    private val NEOFORGE = Regex("""(?<![\w-])(?:NeoForge|neoforge|net\.neoforged)[ :-]+(?:version\s+|net\.neoforged:)?(\d+\.\d+\.\d+(?:\.\d+)?(?:-beta[\w.]*)?)""")
    // Forge's own version, never the Minecraft part of "forge-1.21.1-52.1.16": "Forge mod loading, version 47.4.23",
    // "--fml.forgeVersion, 52.1.16", "net.minecraftforge.forge@52.1.16", "forge-1.21.1-52.1.16-universal.jar".
    private val FORGE = Regex(
        """Forge mod loading, version ((?:\d+\.){1,3}\d+)""" +
            """|forgeVersion[,:=]?\s*((?:\d+\.){1,3}\d+)""" +
            """|net\.minecraftforge\.forge@((?:\d+\.){1,3}\d+)""" +
            """|forge-\d+\.\d+(?:\.\d+)?-((?:\d+\.){1,3}\d+)""",
    )
    private val FABRIC_LOADER = Regex("""(?:Fabric Loader|fabricloader)[ :]+(?:version\s+)?(\d+\.\d+\.\d+)""")
    private val QUILT_LOADER = Regex("""(?:Quilt Loader|quilt_loader)[ :]+(?:version\s+)?(\d+\.\d+\.\d+[\w.-]*)""")
    private val LOADING_MINECRAFT = Regex("""Loading Minecraft ([\w.-]+) with (Fabric|Quilt) Loader ([\w.+-]+)""")
    // A crash report of a modded game says nothing else about its loader: "Is Modded: Definitely;
    // Server brand changed to 'fabric'".
    private val BRAND = Regex("""(?:Server|Client) brand changed to '([\w.-]+)'""")
    private val JAVA_FIELD = Regex("""^\s*Java Version:\s*([\d._]+)""", RegexOption.MULTILINE)
    private val JAVA_RUNTIME = Regex("""(?:Java|JVM)[^\n]*?\b(\d{2})\.(\d+)\.(\d+)""")

    /** Minecraft version of a Bukkit-family server, read after its "This server is running" line. */
    private fun serverVersion(text: String, line: MatchResult): String? {
        val rest = text.substring(line.range.first, minOf(text.length, line.range.last + 200)).substringBefore('\n')
        return MC_TAG.find(rest)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            ?: Regex("""^(\d+\.\d+(?:\.\d+)?)-""").find(line.groupValues[2])?.groupValues?.get(1)
    }

    /**
     * Mohist runs Forge and Youer NeoForge. Arclight exists for all three loaders and does not say which in
     * its own line, so the loader's own words decide; measured on real starts, they never overlap.
     */
    private fun hybrid(family: String, text: String): Platform = when (family) {
        "Mohist" -> Platform.MOHIST
        "Youer" -> Platform.YOUER
        else -> when {
            text.contains("Fabric Loader") || text.contains("fabricloader") -> Platform.ARCLIGHT_FABRIC
            text.contains("neoforged", ignoreCase = true) -> Platform.ARCLIGHT_NEOFORGE
            else -> Platform.ARCLIGHT_FORGE
        }
    }

    /** The platform a crash report names as the brand of the game or server, when it names one. */
    private fun brand(text: String): Platform? = when (BRAND.find(text)?.groupValues?.get(1)?.lowercase()) {
        "fabric" -> Platform.FABRIC
        "quilt" -> Platform.QUILT
        "forge" -> Platform.FORGE
        "neoforge" -> Platform.NEOFORGE
        "paper" -> Platform.PAPER
        "purpur" -> Platform.PURPUR
        "folia" -> Platform.FOLIA
        "spigot", "craftbukkit", "bukkit" -> Platform.SPIGOT
        else -> null
    }

    fun detect(document: LogDocument): Environment {
        val text = document.text
        var platform = Platform.UNKNOWN
        var minecraft: String? = document.field("Minecraft Version")
        var loader: String? = null
        var side = Side.UNKNOWN

        VELOCITY.find(text)?.let {
            platform = Platform.VELOCITY
            loader = it.groupValues[1]
            side = Side.SERVER
        }
        if (platform == Platform.UNKNOWN) BUNGEE.find(text)?.let {
            platform = Platform.BUNGEECORD
            loader = it.groupValues[1]
            side = Side.SERVER
        }
        if (platform == Platform.UNKNOWN) {
            val named = HYBRID.find(text)
            val early = if (named == null) HYBRID_EARLY.find(text) else null
            val family = named?.groupValues?.get(1) ?: early?.value?.let { marker ->
                when {
                    marker.contains("youer", ignoreCase = true) -> "Youer"
                    marker.contains("mohist", ignoreCase = true) -> "Mohist"
                    else -> "Arclight"
                }
            }
            if (family != null) {
                platform = hybrid(family, text)
                loader = named?.groupValues?.get(2)
                minecraft = named?.let { serverVersion(text, it) } ?: minecraft
                side = Side.SERVER
            }
        }
        if (platform == Platform.UNKNOWN) PAPER.find(text)?.let {
            platform = Platform.valueOf(it.groupValues[1].uppercase().let { name -> if (name == "PUFFERFISH") "PAPER" else name })
            loader = it.groupValues[2]
            minecraft = serverVersion(text, it)
            side = Side.SERVER
        }
        if (platform == Platform.UNKNOWN) {
            CRAFTBUKKIT.find(text)?.let {
                platform = Platform.SPIGOT
                loader = it.groupValues[1]
                minecraft = serverVersion(text, it)
                side = Side.SERVER
            }
        }
        if (platform == Platform.UNKNOWN) {
            LOADING_MINECRAFT.find(text)?.let {
                minecraft = minecraft ?: it.groupValues[1]
                platform = if (it.groupValues[2] == "Quilt") Platform.QUILT else Platform.FABRIC
                loader = it.groupValues[3]
            }
        }
        if (platform == Platform.UNKNOWN) {
            platform = when {
                text.contains("neoforged", ignoreCase = true) || NEOFORGE.containsMatchIn(text) -> Platform.NEOFORGE
                text.contains("net.minecraftforge") || text.contains("MinecraftForge") || text.contains("fml.loading") -> Platform.FORGE
                text.contains("quilt_loader") || text.contains("org.quiltmc") -> Platform.QUILT
                text.contains("fabricloader") || text.contains("net.fabricmc") -> Platform.FABRIC
                brand(text) != null -> brand(text)!!
                VANILLA_SERVER.containsMatchIn(text) || document.isCrashReport -> Platform.VANILLA
                else -> Platform.UNKNOWN
            }
            loader = when (platform) {
                Platform.NEOFORGE -> NEOFORGE.find(text)?.groupValues?.get(1)
                Platform.FORGE -> FORGE.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                Platform.FABRIC -> FABRIC_LOADER.find(text)?.groupValues?.get(1)
                Platform.QUILT -> QUILT_LOADER.find(text)?.groupValues?.get(1)
                else -> null
            }
        }
        minecraft = minecraft ?: VANILLA_SERVER.find(text)?.groupValues?.get(1)

        if (side == Side.UNKNOWN) {
            side = when {
                text.contains("Dedicated Server") || text.contains("DedicatedServer") ||
                    Regex("""launchTarget, (?:neo)?forgeserver|--launchTarget (?:neo)?forgeserver|forgeserveruserdev""").containsMatchIn(text) ||
                    VANILLA_SERVER.containsMatchIn(text) || text.contains("Is Modded: Probably not. Server") -> Side.SERVER
                text.contains("Launched Version:") || text.contains("LWJGL") || text.contains("Render thread") -> Side.CLIENT
                else -> Side.UNKNOWN
            }
        }

        val java = JAVA_FIELD.find(text)?.groupValues?.get(1)
            ?: JAVA_RUNTIME.find(text)?.let { "${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}" }

        return Environment(
            platform = platform,
            minecraftVersion = minecraft,
            loaderVersion = loader,
            javaVersion = java,
            side = side,
        )
    }
}

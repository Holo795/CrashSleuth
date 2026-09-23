package dev.holo795.crashsleuth.logs

import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Servers that run mods and plugins at once. Every line below is taken as it is from a real start in the
 * lab: before this, Mohist was read as NeoForge and its plugins folder was never looked at.
 */
class HybridDetectionTest {
    private val analyzer = LogAnalyzer()

    private fun environment(log: String) = analyzer.analyze(log).environment

    @Test
    fun `each hybrid is named by its own line, with the Minecraft version`() {
        val cases = listOf(
            Triple(
                "[10:03:08 INFO]: This server is running Mohist version 1.20.1-46ca7304 (MC: 1.20.1) (Implementing API " +
                    "version 1.20.1-R0.1-SNAPSHOT, Forge version 47.4.13, NeoForge version 47.1.106)\n" +
                    "[10:03:07 INFO]: MinecraftForge v47.4.13 Initialized\n",
                Platform.MOHIST, "1.20.1",
            ),
            // Youer gives no "(MC: ...)": the API version says which Minecraft it is.
            Triple(
                "[10:04:05 INFO]: This server is running Youer version 1.21.1-84641e3c (2026-09-21T08:46:49Z) (Implementing " +
                    "API version 1.21.1-R0.1-SNAPSHOT, NeoForge version 21.1.251)\n" +
                    "[10:04:03 INFO] [cpw.mods.modlauncher.Launcher]: ModLauncher running: args [--launchTarget, forgeserver, " +
                    "--fml.neoForgeVersion, 21.1.251, --fml.fmlVersion, 4.0.42, --fml.mcVersion, 1.21.1, --fml.neoFormVersion]\n",
                Platform.YOUER, "1.21.1",
            ),
            Triple(
                "[10:05:30 INFO]: This server is running Arclight version arclight-1.20.1-1.0.6-4e94eb76 (MC: 1.20.1) " +
                    "(Implementing API version 1.20.1-R0.1-SNAPSHOT)\n" +
                    "[10:05:23 INFO] [net.minecraftforge.fml.loading.moddiscovery.ModDiscoverer]: Found mod file\n",
                Platform.ARCLIGHT_FORGE, "1.20.1",
            ),
            Triple(
                "[10:04:40 INFO]: This server is running Arclight version arclight-1.21.1-1.0.1-8ec9529 (MC: 1.21.1) " +
                    "(Implementing API version 1.21.1-R0.1-SNAPSHOT)\n" +
                    "[10:04:35 INFO] [net.neoforged.fml.loading.moddiscovery.ModDiscoverer]: Found mod file\n",
                Platform.ARCLIGHT_NEOFORGE, "1.21.1",
            ),
            Triple(
                "[10:04:52 INFO] [FabricLoader/GameProvider]: Loading Minecraft 1.21.1 Arclight arclight-1.21.1-1.0.1-8ec9529 " +
                    "with Fabric Loader 0.16.14\n" +
                    "[10:04:58 INFO]: This server is running Arclight version arclight-1.21.1-1.0.1-8ec9529 (MC: 1.21.1) " +
                    "(Implementing API version 1.21.1-R0.1-SNAPSHOT)\n",
                Platform.ARCLIGHT_FABRIC, "1.21.1",
            ),
        )
        for ((log, platform, minecraft) in cases) {
            val found = environment(log)
            assertEquals(platform, found.platform, log.lineSequence().first())
            assertEquals(minecraft, found.minecraftVersion, log.lineSequence().first())
            assertEquals(Side.SERVER, found.side)
        }
    }

    /** A hybrid that dies while loading its mods never reaches its own line: the early markers still tell. */
    @Test
    fun `a hybrid that died before naming itself is still recognised`() {
        assertEquals(
            Platform.MOHIST,
            environment("[10:03:07] [modloading-worker-0/INFO] [com.mohistmc.MohistMC]: Mohist mod loading.....\n").platform,
        )
        assertEquals(
            Platform.YOUER,
            environment("[10:04:03] [main/WARN] [mixin]: @Mixin target com.simibubi.create.Foo was not found youer.mixins.json:compat.create.MixinFoo\n").platform,
        )
        assertEquals(
            Platform.ARCLIGHT_NEOFORGE,
            environment("[23Sep2026 10:04:35.128] [main/INFO] [Arclight/]: \n[main/INFO] [net.neoforged.fml.loading.FMLLoader]: loading\n").platform,
        )
    }

    /** Mohist writes "NeoForge v47.1.106 Initialized" too: it must not become NeoForge for that. */
    @Test
    fun `mohist is not read as neoforge`() {
        val found = environment(
            "[10:03:07 INFO]: NeoForge v47.1.106 Initialized\n" +
                "[10:03:08 INFO]: This server is running Mohist version 1.20.1-46ca7304 (MC: 1.20.1) (Implementing API version 1.20.1-R0.1-SNAPSHOT)\n",
        )
        assertEquals(Platform.MOHIST, found.platform)
        assertEquals(Platform.FORGE, found.platform.base)
    }
}

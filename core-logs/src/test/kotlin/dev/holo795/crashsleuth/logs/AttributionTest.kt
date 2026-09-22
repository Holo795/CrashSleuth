package dev.holo795.crashsleuth.logs

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttributionTest {
    @Test
    fun `obfuscated game classes without a package are never culprits`() {
        listOf("azu", "gfj", "fzz\$1", "ub").forEach { assertTrue(Attribution.isPlatformClass(it), it) }
        listOf("dev.holo795.shop.Shop", "com.example.Mod").forEach { assertFalse(Attribution.isPlatformClass(it), it) }
    }

    @Test
    fun `the game jar installed by a launcher is never a culprit`() {
        listOf("1.21.1.jar", "versions/1.21.1/1.21.1.jar", "24w14a.jar", "1.20.1-forge-47.3.0.jar", "velocity-3.4.0-566.jar", "velocity.jar", "bootstrap-2.1.8.jar", "securemodules-2.2.21.jar").forEach { assertTrue(Attribution.isPlatformJar(it), it) }
        listOf("sodium-fabric-0.6.13+mc1.21.1.jar", "create-1.21.1-6.0.4.jar").forEach { assertFalse(Attribution.isPlatformJar(it), it) }
    }
}

class NeoForgeVersionTest {
    @Test
    fun `the NeoForge version is not taken from another jar's name`() {
        val crash = "\t\tsodium-neoforge-0.8.13+mc1.21.1.jar |Sodium |sodium |0.8.13+mc1.21.1 |ERROR\n" +
            "\t\tneoforge-21.1.251-universal.jar |NeoForge |neoforge |21.1.251 |ERROR\n"
        kotlin.test.assertEquals("21.1.251", EnvironmentDetector.detect(LogDocument(crash)).loaderVersion)
    }
}

class ForgeVersionTest {
    @Test
    fun `the Forge version is its own, not the Minecraft one`() {
        val cases = mapOf(
            "[main/INFO]: Forge mod loading, version 47.4.23" to "47.4.23",
            "[main/INFO]: args [--fml.forgeVersion, 52.1.16, --fml.mcVersion, 1.21.1]" to "52.1.16",
            "\tat TRANSFORMER/net.minecraftforge.forge@52.1.16/net.minecraftforge.Foo.bar(Foo.java:1)" to "52.1.16",
            "\tat net.minecraft.server.Main.main(Main.java:129) ~[forge-1.21.1-52.1.16-server.jar:?]" to "52.1.16",
        )
        cases.forEach { (line, expected) ->
            val header = "[main/INFO] [net.minecraftforge.fml.loading.FMLLoader/]: Loading Minecraft with MinecraftForge\n"
            kotlin.test.assertEquals(expected, EnvironmentDetector.detect(LogDocument(header + line + "\n")).loaderVersion, line)
        }
    }
}

class NeoForge26VersionTest {
    @Test
    fun `NeoForge 26 versions have four numbers`() {
        val log = "[main/INFO] [net.neoforged.fml.ModSorter/]: Loading 4 mods:\n\t\tNeoForge 26.2.0.88 (neoforge)\n"
        kotlin.test.assertEquals("26.2.0.88", EnvironmentDetector.detect(LogDocument(log)).loaderVersion)
    }
}

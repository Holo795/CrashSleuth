package dev.holo795.crashsleuth.logs

import dev.holo795.crashsleuth.model.Situation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Each shipped signature must load and recognise the message it was written for. */
class SignatureTest {
    private val analyzer = LogAnalyzer()

    private fun primary(log: String) = analyzer.analyze(log).primary!!.let { it.situation to it.culprits.map { c -> c.id } }

    @Test
    fun `all signatures load`() {
        assertEquals(90, SignatureDetector.load(javaClass.classLoader.getResource("crashsleuth/signatures.json")!!.readText()).size)
    }

    @Test
    fun `known messages`() {
        assertEquals(
            Situation.WRONG_MC to listOf("examplemod"),
            primary("[main/FATAL]: The mod examplemod does not wish to run in Minecraft version Minecraft 1.12.2. You will have to remove it to play."),
        )
        assertEquals(
            Situation.MOD_CONFLICT to listOf("optifabric", "sodium"),
            primary("Incompatible mods found!\n\t - Mod 'OptiFabric' (optifabric) 1.14.3 is incompatible with any version of mod 'Sodium' (sodium), but a matching version is present: 0.5.8!"),
        )
        assertEquals(
            Situation.MOD_CONFLICT to listOf("foo", "bar"),
            primary("Exception in thread \"main\" java.lang.module.ResolutionException: Modules foo and bar export package com.example.shared to module baz"),
        )
        assertEquals(
            Situation.WORLD_DOWNGRADE to emptyList(),
            primary("java.lang.RuntimeException: Server attempted to load chunk saved with newer version of minecraft! 3955 > 3700"),
        )
        assertEquals(
            Situation.MOD_MISMATCH to listOf("create"),
            primary("[Server thread/WARN]: This world was saved with mod create which appears to be missing, things may not work well"),
        )
        assertEquals(
            Situation.DEP_MISSING to listOf("mymod", "kotlinforforge"),
            primary("Mod File mymod-1.0.jar needs language provider kotlinforforge:4.0 to load\nWe have found 0"),
        )
    }

    /** A plugin whose database never answered: the lines are verbatim from the two reports. */
    @Test
    fun `a plugin that gave up on its database says so quietly`() {
        // https://github.com/PlayPro/CoreProtect/issues/476
        assertEquals(
            Situation.SILENT_ERROR to listOf("CoreProtect"),
            primary(
                "[11:00:51] [Server thread/INFO]: [CoreProtect] Database is already in use. Please try again.\n" +
                    "[11:00:51] [Server thread/INFO]: [CoreProtect] To disable database locking, set \"database-lock: false\".\n" +
                    "[11:00:51] [Server thread/INFO]: [CoreProtect] CoreProtect was unable to start.\n" +
                    "[11:00:51] [Server thread/INFO]: [CoreProtect] Disabling CoreProtect v22.2\n",
            ),
        )
        // https://github.com/LuckPerms/LuckPerms/issues/4019
        assertEquals(
            Situation.SILENT_ERROR to listOf("LuckPerms"),
            primary(
                "[12:08:02 ERROR]: [LuckPerms] Failed to init storage implementation\n" +
                    "java.sql.SQLTransientConnectionException: luckperms-hikari - Connection is not available, " +
                    "request timed out after 5001ms.\n" +
                    "\tat me.lucko.luckperms.lib.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696) ~[?:?]\n",
            ),
        )
    }

    /** Four reports where the answer was somewhere other than where the log pointed. */
    @Test
    fun `the line that names the cause is not always the loudest one`() {
        // https://github.com/PaperMC/Paper/issues/10909 : a settings file kept a block name from before 1.13.
        assertEquals(
            Situation.CONFIG_BROKEN to emptyList(),
            primary(
                "[21:21:11] [Server thread/ERROR]: Encountered an unexpected exception\n" +
                    "org.spongepowered.configurate.serialize.SerializationException: " +
                    "[anticheat, anti-xray, hidden-blocks, 9] of type java.util.List<net.minecraft.world.level.block.Block>: " +
                    "Missing value in Registry[ResourceKey[minecraft:root / minecraft:block] (Stable)] with key minecraft:lit_redstone_ore\n",
            ),
        )
        // https://github.com/Archy-X/AuraSkills/issues/407 : the first plugin of the loop is the one to change.
        assertEquals(
            Situation.DEP_CYCLE to listOf("AuraSkills"),
            primary(
                "[21:49:43 ERROR]: [LoadOrderTree] Circular plugin loading detected:\n" +
                    "[21:49:43 ERROR]: [LoadOrderTree] 1) AuraSkills -> Nexo -> MMOItems -> AuraSkills\n",
            ),
        )
        // https://github.com/GrimAnticheat/Grim/issues/2745 : a shaded library cannot read "26.2" and falls back to 1.8.8.
        assertEquals(
            Situation.PLUGIN_API to listOf("packetevents"),
            primary(
                "[19:17:12 INFO]: [packetevents] Your server software is preventing us from checking the " +
                    "Minecraft Server version. This is what we found: 26.2.build.24-alpha. " +
                    "We will assume the Server version is V_1_8_8...\n",
            ),
        )
        // https://github.com/TuxCoding/FastLogin/issues/1329 : the port was left inside the address field.
        assertEquals(
            Situation.CONFIG_BROKEN to emptyList(),
            primary(
                "[02:38:35 ERROR]: Error occurred while enabling FastLogin v1.12-SNAPSHOT-65a379c (Is it up to date?)\n" +
                    "fastlogin.hikari.pool.HikariPool${'$'}PoolInitializationException: " +
                    "Failed to initialize pool: Communications link failure\n" +
                    "Caused by: java.net.UnknownHostException: localhost:3306: Name or service not known\n",
            ),
        )
    }

    /** https://github.com/PaperMC/Paper/issues/11152 : one jar Paper cannot rewrite stops every plugin. */
    @Test
    fun `one jar with a class twice and no plugin loads`() {
        assertEquals(
            Situation.CORRUPT_JAR to listOf("IslandsWorldGen"),
            primary(
                "[20:31:06] [ServerMain/ERROR]: [PluginRemapper] Encountered exception remapping plugins\n" +
                    "java.util.concurrent.CompletionException: java.lang.RuntimeException: " +
                    "Failed to remap plugin jar 'plugins\\IslandsWorldGen.jar'\n" +
                    "Caused by: java.lang.IllegalStateException: Duplicate entries detected: " +
                    "me/ryanhamshire/BigScaryIslands/EmptyWorldGenerator.class\n" +
                    "[20:31:06] [ServerMain/INFO]: [PluginInitializerManager] Initialized 0 plugins\n",
            ),
        )
    }

    /** https://github.com/GStefanowich/MC-Server-Protection/issues/41 : the log names the mod, so we do too. */
    @Test
    fun `a registry mismatch names the mod the other side is missing`() {
        assertEquals(
            Situation.REGISTRY_MISMATCH to listOf("sewing-machine"),
            primary(
                "[14:38:17] [Render thread/ERROR]: Registry remapping failed!\n" +
                    "net.fabricmc.fabric.impl.registry.sync.RemapException: Received ID map for " +
                    "minecraft:block_entity_type contains IDs unknown to the receiver!\n" +
                    " - sewing-machine:warps_lectern\n" +
                    " - sewing-machine:guide_lectern\n",
            ),
        )
    }

    /** https://github.com/Glytch10/noxlights-1.21.1/issues/3 : the loader names the mod, not the class. */
    @Test
    fun `a mod that reached for the client half of the game on a server`() {
        assertEquals(
            Situation.CLIENT_ONLY_ON_SERVER to listOf("noxlights"),
            primary(
                "[19:07:34] [main/ERROR] [minecraft/Main]: Failed to start the minecraft server\n" +
                    "net.neoforged.fml.ModLoadingException: Loading errors encountered:\n" +
                    "\t- Nox Lights (noxlights) encountered an error while dispatching the " +
                    "net.neoforged.neoforge.registries.RegisterEvent event\n" +
                    "\t  java.lang.BootstrapMethodError: java.lang.RuntimeException: Attempted to load class " +
                    "net/minecraft/client/particle/ParticleRenderType for invalid dist DEDICATED_SERVER\n",
            ),
        )
        // https://github.com/andersblomqvist/enhanced-mob-spawners/issues/123 : one entry, same answer.
        assertEquals(
            Situation.REGISTRY_MISMATCH to listOf("spawnermod"),
            primary(
                "[11:03:30] [Render thread/ERROR]: Registry remapping failed!\n" +
                    "net.fabricmc.fabric.impl.registry.sync.RemapException: Received a registry entry that is " +
                    "unknown to this client.\n" +
                    "This is usually caused by a mismatched mod set between the client and server.\n" +
                    "The following registry entry namespaces may be related:\n" +
                    "spawnermod\n",
            ),
        )
    }
}

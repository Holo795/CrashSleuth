package dev.holo795.crashsleuth.logs

import dev.holo795.crashsleuth.model.Confidence
import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Report
import dev.holo795.crashsleuth.model.Side
import dev.holo795.crashsleuth.model.Situation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogAnalyzerTest {
    private val analyzer = LogAnalyzer()

    private fun sample(name: String): Report {
        val text = requireNotNull(javaClass.getResource("/samples/$name")) { "missing sample $name" }.readText()
        return analyzer.analyze(text)
    }

    private fun Report.find(situation: Situation) =
        assertNotNull(findings.firstOrNull { it.situation == situation }, "no $situation in ${findings.map { it.situation }}")

    @Test
    fun `neoforge missing and outdated dependencies`() {
        val report = sample("neoforge-missing-dependency.log")
        assertEquals(Platform.NEOFORGE, report.environment.platform)
        assertEquals("21.1.209", report.environment.loaderVersion)
        val missing = report.find(Situation.DEP_MISSING)
        assertEquals("geckolib", missing.details["dependency"])
        assertEquals("alexsmobs", missing.details["requester"])
        val outdated = report.find(Situation.DEP_VERSION)
        assertEquals("create", outdated.details["dependency"])
        assertEquals("6.0.1", outdated.details["actual"])
    }

    @Test
    fun `fabric missing and wrong version dependencies`() {
        val report = sample("fabric-dependencies.log")
        assertEquals(Platform.FABRIC, report.environment.platform)
        assertEquals("1.21.1", report.environment.minecraftVersion)
        assertEquals("fabric-api", report.find(Situation.DEP_MISSING).details["dependency"])
        val wrong = report.find(Situation.DEP_VERSION)
        assertEquals("sodium", wrong.details["dependency"])
        assertEquals("0.5.8", wrong.details["actual"])
    }

    @Test
    fun `paper plugin with a missing dependency and a plugin for a newer api`() {
        val report = sample("paper-unknown-dependency.log")
        assertEquals(Platform.PAPER, report.environment.platform)
        assertEquals(Side.SERVER, report.environment.side)
        val missing = report.find(Situation.DEP_MISSING)
        assertEquals("Essentials", missing.details["dependency"])
        assertEquals("EssentialsChat", missing.details["requester"])
        val api = report.find(Situation.PLUGIN_API)
        assertEquals("OldShop", api.culprits.first().id)
    }

    @Test
    fun `paper plugin failing on enable`() {
        val report = sample("paper-enable-error.log")
        val primary = assertNotNull(report.primary)
        assertEquals(Situation.UNCAUGHT_EXCEPTION, primary.situation)
        assertEquals("WorldGuardExtra", primary.culprits.first().id)
        assertEquals(Confidence.CERTAIN, primary.confidence)
    }

    @Test
    fun `java too old for a mod`() {
        val finding = sample("java-version.log").find(Situation.JAVA_VERSION)
        assertEquals("21", finding.details["required"])
        assertEquals("17", finding.details["current"])
        assertTrue(finding.culprits.any { it.id == "net.example.fancymod" })
    }

    @Test
    fun `neoforge crash on a ticking block entity`() {
        val report = sample("neoforge-block-entity-crash.txt")
        assertEquals(Platform.NEOFORGE, report.environment.platform)
        assertEquals("1.21.1", report.environment.minecraftVersion)
        assertEquals("21.0.4", report.environment.javaVersion)
        assertEquals("21.1.209", report.environment.loaderVersion)
        val primary = assertNotNull(report.primary)
        assertEquals(Situation.TICK_BLOCK_ENTITY, primary.situation)
        assertEquals("create", primary.culprits.first().id)
        assertEquals("Create", primary.culprits.first().name)
        assertEquals("6.0.4", primary.culprits.first().version)
        assertEquals("120, 64, -35", primary.details["location"])
    }

    @Test
    fun `fabric mixin failure`() {
        val finding = sample("fabric-mixin-failure.log").find(Situation.MIXIN_CONFLICT)
        assertEquals("betterclouds", finding.culprits.first().id)
    }

    @Test
    fun `server out of memory`() {
        val report = sample("out-of-memory-crash.txt")
        assertEquals(Situation.OUT_OF_MEMORY, report.primary?.situation)
        assertEquals("1.20.1", report.environment.minecraftVersion)
    }

    @Test
    fun `native crash in the amd graphics driver`() {
        val finding = sample("hs_err_pid4242.log").find(Situation.RENDER)
        assertEquals("atio6axx.dll", finding.details["library"])
    }

    @Test
    fun `vanilla crash without a third party culprit`() {
        val report = sample("vanilla-generic.txt")
        assertEquals(Platform.VANILLA, report.environment.platform)
        val primary = assertNotNull(report.primary)
        assertEquals(Situation.UNCAUGHT_EXCEPTION, primary.situation)
        assertTrue(primary.culprits.isEmpty())
        assertEquals(Confidence.LOW, primary.confidence)
    }

    @Test
    fun `jar names are turned into identifiers`() {
        assertEquals("create", Attribution.idFromJar("create-1.21.1-6.0.4.jar"))
        assertEquals("Essentials", Attribution.idFromJar("Essentials-2.21.0.jar"))
        assertEquals("sodium", Attribution.idFromJar("sodium-fabric-0.6.0+mc1.21.1.jar"))
        assertTrue(Attribution.isPlatformJar("server-1.21.1-20240808.144430-srg.jar"))
        assertTrue(Attribution.isPlatformJar("paper-api-1.21.1-R0.1-SNAPSHOT.jar"))
    }

    @Test
    fun `code injected by a mixin is blamed on its mod`() {
        val log = """
            ---- Minecraft Crash Report ----
            Description: Ticking entity

            java.lang.NullPointerException: Cannot invoke "Object.hashCode()" because "key" is null
            	at net.minecraft.world.entity.Entity.handler${'$'}zza000${'$'}examplemod${'$'}onTick(Entity.java:512)
            	at net.minecraft.world.entity.Entity.tick(Entity.java:500)
            	at net.minecraft.server.level.ServerLevel.tickNonPassenger(ServerLevel.java:800)
        """.trimIndent()
        val report = LogAnalyzer().analyze(log)
        kotlin.test.assertEquals("examplemod", report.primary?.culprits?.firstOrNull()?.id)
    }

    @Test
    fun `a crash report names its loader only as the brand of the server`() {
        val log = """
            ---- Minecraft Crash Report ----
            Description: Ticking block entity

            java.lang.IllegalStateException: Shop furnace lost its recipe
            	at knot//net.minecraft.class_2609.handler${'$'}zzb001${'$'}shopmod${'$'}onTick(class_2609.java:595)
            	at knot//net.minecraft.class_2818${'$'}class_5563.method_31703(class_2818.java:691)

            -- Block entity being ticked --
            Details:
            	Name: minecraft:furnace // net.minecraft.class_3866
            	Block location: World: (0,100,0), Section: (at 0,4,0 in 0,6,0; chunk contains blocks 0,-64,0 to 15,319,15)

            -- System Details --
            Details:
            	Minecraft Version: 1.21.1
            	Is Modded: Definitely; Server brand changed to 'fabric'
            	Type: Dedicated Server (map_server.txt)
        """.trimIndent()
        val report = LogAnalyzer().analyze(log)
        assertEquals(Platform.FABRIC, report.environment.platform)
        assertEquals(Side.SERVER, report.environment.side)
        val finding = report.find(Situation.TICK_BLOCK_ENTITY)
        assertEquals("minecraft:furnace", finding.details["object"])
        assertEquals("0, 100, 0", finding.details["location"])
        assertEquals("shopmod", finding.culprits.firstOrNull()?.id)
    }

    @Test
    fun `a few lines pasted from a forum name the plugin, not its package`() {
        val log = """
            [12:01:02] [main/ERROR]: Error occurred while enabling Essentials v2.20.1 (Is it up to date?)
            java.lang.UnsupportedClassVersionError: com/earth2me/essentials/Essentials has been compiled by a more recent version of the Java Runtime (class file version 65.0), this version of the Java Runtime only recognizes class file versions up to 61.0
        """.trimIndent()
        val report = LogAnalyzer().analyze(log)
        val finding = assertNotNull(report.primary)
        assertEquals(Situation.JAVA_VERSION, finding.situation)
        assertEquals(listOf("java", "Essentials"), finding.culprits.map { it.id })
        assertEquals("21", finding.details["required"])
    }

    @Test
    fun `connections that never say hello are scanners, not a problem`() {
        // Velocity logs every failed connection. A maintainer's own reading of these lines:
        // "[initial connection]" with no player name is a scanner, "[connected player]" is a real session.
        // https://github.com/PaperMC/Velocity/issues/1650
        val log = """
            [20:14:30 INFO]: Booting up Velocity 3.4.0-SNAPSHOT (git-81deb1ff-b521)...
            [20:14:31 INFO]: Done (1.23s)!
            [20:14:31 ERROR]: [initial connection] /176.65.148.127:61138: read timed out
            [20:29:21 ERROR]: [initial connection] /176.65.148.103:65100: read timed out
        """.trimIndent()
        assertTrue(LogAnalyzer().analyze(log).findings.isEmpty(), "a port scanner is not a problem to report")
    }

    @Test
    fun `models a pack or a mod does not ship are not a problem to report`() {
        // Perfectly healthy mods log these while the game starts: a mod author had to say so himself,
        // https://github.com/SashaKYotoz/Unusual-End/issues/21 ("Unable to load model can't cause the
        // game to crash"), and the lab's own clean baseline is full of them.
        val log = """
            [15:47:54] [Worker-Main-2/ERROR] [minecraft/Util]: Invalid path in pack: glowroot:textures/block/x - Shortcut.lnk, ignoring
            [15:47:54] [Worker-Main-11/WARN] [minecraft/ModelBakery]: Unable to load model: 'minecraft:glowbulb' referenced from: glowroot:glow_bulb#: java.io.FileNotFoundException
            [15:47:55] [Render thread/INFO]: Loaded 7 recipes
        """.trimIndent()
        assertTrue(LogAnalyzer().analyze(log).findings.isEmpty(), "a missing model is not what breaks a game")
    }

    @Test
    fun `the plugin named by a failed event is not always the one whose code failed`() {
        // https://github.com/mewin/WorldGuard-Region-Events/issues/20 : WorldGuard is named because the
        // listener was registered under its name, and appears in none of the frames.
        val log = """
            [05:17:26 ERROR]: Could not pass event PlayerJoinEvent to WorldGuard v7.0.4+f7ff984
            java.lang.NoSuchMethodError: 'com.sk89q.worldguard.protection.managers.RegionManager com.sk89q.worldguard.bukkit.WorldGuardPlugin.getRegionManager(org.bukkit.World)'
            	at com.mewin.WGRegionEvents.WGRegionEventsListener.updateRegions(WGRegionEventsListener.java:113) ~[?:?]
            	at com.mewin.WGRegionEvents.WGRegionEventsListener.onPlayerJoin(WGRegionEventsListener.java:71) ~[?:?]
            	at io.papermc.paper.plugin.manager.PaperEventManager.callEvent(PaperEventManager.java:54) ~[paper-1.16.5.jar:git-Paper-505]
        """.trimIndent()
        val finding = LogAnalyzer().analyze(log).find(Situation.SILENT_ERROR)
        assertEquals("com.mewin.WGRegionEvents", finding.culprits.first().id)
        assertEquals("WorldGuard", finding.culprits[1].id, "the one that registered it is still said, second")
    }

    @Test
    fun `loud lines that break nothing are left alone`() {
        // All four are verbatim from real reports, and all four are harmless: ProtocolLib warns about an
        // untested Minecraft, WorldEdit about a server it cannot fully drive, Multiverse about an optional
        // script engine, and the JDK about spark loading its profiler. The last one is even logged at ERROR.
        val log = """
            [09:47:00] [Server thread/WARN]: [ProtocolLib] Version (MC: 1.20.1) has not yet been tested! Proceed with caution.
            [17:27:02] [Server thread/WARN]: ** This WorldEdit version does not fully support your version of Bukkit.
            [19:31:05] [Server thread/WARN]: [Multiverse-Core] Buscript failed to load! The script command will be disabled!
            [19:31:06] [Server thread/ERROR]: [STDERR] WARNING: A Java agent has been loaded dynamically (/tmp/byteBuddyAgent1675.jar)
            [19:31:07] [Server thread/INFO]: Done (12.3s)! For help, type "help"
        """.trimIndent()
        assertTrue(LogAnalyzer().analyze(log).findings.isEmpty(), "none of these stop anything")
    }

    @Test
    fun `a machine that cannot give the memory asked for is not a server that was killed`() {
        // https://github.com/itzg/docker-minecraft-server/issues/3368 : the JVM refused to start, exit 1,
        // nothing was killed. A container that kills the server looks completely different (no Java output).
        val log = """
            [init] Setting initial memory to 4G and max to 4G
            [init] Starting the Minecraft server...
            #
            # There is insufficient memory for the Java Runtime Environment to continue.
            # Native memory allocation (mmap) failed to map 4294967296 bytes. Error detail: committing reserved memory.
            OpenJDK 64-Bit Server VM warning: INFO: os::commit_memory(0x0000000700000000, 4294967296, 0) failed; error='Not enough space' (errno=12)
        """.trimIndent()
        val finding = LogAnalyzer().analyze(log).find(Situation.JVM_OPTIONS)
        assertTrue(finding.evidence.any { it.contains("insufficient memory") || it.contains("mmap") }, "${finding.evidence}")
    }
}

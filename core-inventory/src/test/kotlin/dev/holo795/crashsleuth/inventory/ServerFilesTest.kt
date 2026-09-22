package dev.holo795.crashsleuth.inventory

import dev.holo795.crashsleuth.model.Situation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerFilesTest {
    @TempDir
    lateinit var root: Path

    /** A level.dat as the game writes it: gzipped NBT, Data.Version.Name and Data.DataVersion. */
    private fun levelDat(versionName: String, dataVersion: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            fun compound(name: String) { out.writeByte(10); out.writeUTF(name) }
            fun end() = out.writeByte(0)
            compound("")
            compound("Data")
            out.writeByte(3); out.writeUTF("DataVersion"); out.writeInt(dataVersion)
            compound("Version")
            out.writeByte(8); out.writeUTF("Name"); out.writeUTF(versionName)
            out.writeByte(3); out.writeUTF("Id"); out.writeInt(dataVersion)
            end()
            out.writeByte(8); out.writeUTF("LevelName"); out.writeUTF("world")
            end()
            end()
        }
        return bytes.toByteArray()
    }

    private fun server(minecraft: String) {
        root.resolve("versions/$minecraft").createDirectories()
        root.resolve("versions/$minecraft/paper-$minecraft.jar").writeText("")
        root.resolve("server.properties").writeText("level-name=world\n")
    }

    private fun findings() = InventoryAnalyzer.analyze(InstanceScanner.scan(root)).map { it.situation }

    @Test
    fun `world saved by a newer version`() {
        server("1.21.1")
        root.resolve("world").createDirectories().resolve("level.dat").writeBytes(levelDat("1.21.4", 4189))
        val world = InstanceScanner.scan(root).files.worlds.single()
        assertEquals("1.21.4", world.versionName)
        assertEquals(4189, world.dataVersion)
        assertEquals(listOf(Situation.WORLD_DOWNGRADE), findings())
    }

    @Test
    fun `server jar never started, the version comes from its version json`() {
        java.util.zip.ZipOutputStream(root.resolve("server.jar").toFile().outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("version.json"))
            zip.write("""{"id": "1.21.1", "world_version": 3955}""".toByteArray())
        }
        root.resolve("server.properties").writeText("level-name=world\n")
        root.resolve("eula.txt").writeText("eula=true\n")
        root.resolve("world").createDirectories().resolve("level.dat").writeBytes(levelDat("1.21.4", 4189))
        assertEquals("1.21.1", InstanceScanner.scan(root).minecraftVersion)
        assertEquals(listOf(Situation.WORLD_DOWNGRADE), findings())
    }

    @Test
    fun `same version, nothing to say`() {
        server("1.21.1")
        root.resolve("world").createDirectories().resolve("level.dat").writeBytes(levelDat("1.21.1", 3955))
        root.resolve("eula.txt").writeText("eula=true\n")
        assertEquals(emptyList(), findings())
    }

    @Test
    fun `damaged level dat, EULA refused, locked world`() {
        server("1.21.1")
        val world = root.resolve("world").createDirectories()
        world.resolve("level.dat").writeBytes(levelDat("1.21.1", 3955).copyOf(20))
        world.resolve("level.dat_old").writeBytes(levelDat("1.21.1", 3955))
        root.resolve("eula.txt").writeText("#By changing the setting below to TRUE you are indicating your agreement\neula=false\n")
        val lock = world.resolve("session.lock").also { it.writeText("☃") }
        FileChannel.open(lock, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val found = findings()
                assertTrue(Situation.WORLD_CORRUPT in found, "$found")
                assertTrue(Situation.EULA in found, "$found")
                // Inside one JVM the lock shows as overlapping: the check sees it held.
                assertTrue(Situation.WORLD_LOCKED in found, "$found")
            }
        }
    }

    @Test
    fun `configuration files the loaders refuse`() {
        server("1.21.1")
        root.resolve("config").createDirectories()
        root.resolve("config/good.toml").writeText("[general]\nenabled = true\n")
        root.resolve("config/bad.toml").writeText("[general]\nenabled = = true\n")
        root.resolve("config/comments.json").writeText("{\n  // fabric configs often have comments\n  \"a\": 1,\n}\n")
        root.resolve("config/bad.json").writeText("{\n  \"a\": [1, 2\n")
        root.resolve("plugins/LuckPerms").createDirectories()
        root.resolve("plugins/LuckPerms/config.yml").writeText("server: global\nstorage-method: h2\n  bad: indent: here\n")
        val errors = InstanceScanner.scan(root).files.configErrors.map { it.file }.sorted()
        assertEquals(listOf("config/bad.json", "config/bad.toml", "plugins/LuckPerms/config.yml"), errors)
    }
}

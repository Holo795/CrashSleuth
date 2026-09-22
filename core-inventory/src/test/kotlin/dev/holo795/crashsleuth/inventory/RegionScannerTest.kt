package dev.holo795.crashsleuth.inventory

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.zip.Deflater
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegionScannerTest {
    @TempDir
    lateinit var world: Path

    private fun chunkNbt(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeByte(10); out.writeUTF("")
            out.writeByte(3); out.writeUTF("xPos"); out.writeInt(0)
            out.writeByte(9); out.writeUTF("sections"); out.writeByte(10); out.writeInt(0)
            out.writeByte(0)
        }
    }.toByteArray()

    private fun zlib(bytes: ByteArray): ByteArray {
        val deflater = Deflater().apply { setInput(bytes); finish() }
        val out = ByteArray(bytes.size + 64)
        return out.copyOf(deflater.deflate(out)).also { deflater.end() }
    }

    /** A region file with the given chunk payloads (index to compressed bytes and compression type). */
    private fun region(chunks: Map<Int, Pair<ByteArray, Int>>): ByteArray {
        val file = ByteBuffer.allocate(8192 + chunks.size * 4096)
        chunks.entries.forEachIndexed { n, (index, chunk) ->
            val offset = 2 + n
            file.putInt(index * 4, (offset shl 8) or 1)
            file.position(offset * 4096)
            file.putInt(chunk.first.size + 1)
            file.put(chunk.second.toByte())
            file.put(chunk.first)
        }
        return file.array()
    }

    @Test
    fun `healthy chunks give nothing`() {
        world.resolve("region").createDirectories().resolve("r.0.0.mca").writeBytes(region(mapOf(0 to (zlib(chunkNbt()) to 2), 33 to (zlib(chunkNbt()) to 2))))
        val result = RegionScanner.scan(world)
        assertEquals(emptyList(), result.damaged)
        assertEquals(2, result.checked)
    }

    @Test
    fun `the last chunk of a file is not padded to a full sector, as the game writes it`() {
        val full = region(mapOf(0 to (zlib(chunkNbt()) to 2)))
        val used = 2 * 4096 + 5 + zlib(chunkNbt()).size
        world.resolve("region").createDirectories().resolve("r.0.0.mca").writeBytes(full.copyOf(used))
        assertEquals(emptyList(), RegionScanner.scan(world).damaged)
    }

    @Test
    fun `garbage, unknown compression and a location past the end are named with their chunk`() {
        val garbage = zlib(chunkNbt()).also { for (i in 4 until it.size - 4) it[i] = (i * 37).toByte() }
        world.resolve("region").createDirectories().resolve("r.-1.0.mca").writeBytes(region(mapOf(0 to (garbage to 2), 1 to (zlib(chunkNbt()) to 42))))
        val entities = region(mapOf(5 to (zlib(chunkNbt()) to 2))).also { ByteBuffer.wrap(it).putInt(5 * 4, (99 shl 8) or 1) }
        world.resolve("entities").createDirectories().resolve("r.0.0.mca").writeBytes(entities)
        val damaged = RegionScanner.scan(world).damaged.associateBy { it.folder to (it.x to it.z) }
        assertTrue(("region" to (-32 to 0)) in damaged, "$damaged")
        assertTrue(damaged.getValue("region" to (-31 to 0)).reason.contains("compression type 42"))
        assertTrue(damaged.getValue("entities" to (5 to 0)).reason.contains("past the end"))
    }
}

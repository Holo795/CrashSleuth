package dev.holo795.crashsleuth.inventory

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** A chunk of a region file the game cannot read. */
@Serializable
data class DamagedChunk(
    /** "region" (blocks) or "entities". */
    val folder: String,
    /** Region file, relative to the world folder: "region/r.0.-1.mca", "DIM-1/region/r.0.0.mca". */
    val file: String,
    val x: Int,
    val z: Int,
    val reason: String,
)

/**
 * Reads the Anvil region files of a world the way the game does: the header, then each chunk's length,
 * compression and NBT. Damage is found before the server starts, while the game would only log it
 * (or run out of memory) when a player walks there. Large worlds are read within a time budget.
 */
object RegionScanner {
    class Result(val damaged: List<DamagedChunk>, val checked: Int, val complete: Boolean)

    private const val SECTOR = 4096
    private const val MAX_CHUNK = 16 * 1024 * 1024
    private const val MAX_REPORTED = 50
    private val REGION = Regex("""r\.(-?\d+)\.(-?\d+)\.mca""")

    fun scan(world: Path, budgetMillis: Long = 10_000): Result {
        val deadline = System.currentTimeMillis() + budgetMillis
        val damaged = mutableListOf<DamagedChunk>()
        var checked = 0
        var complete = true
        for (folder in folders(world)) {
            val kind = folder.name
            val files = Files.list(folder).use { stream -> stream.filter { REGION.matches(it.name) }.sorted().toList() }
            for (file in files) {
                if (System.currentTimeMillis() > deadline) { complete = false; break }
                val (rx, rz) = REGION.matchEntire(file.name)!!.destructured
                val relative = world.relativize(file).toString()
                checked += check(file, relative, kind, rx.toInt(), rz.toInt(), deadline, damaged)
            }
        }
        return Result(damaged.take(MAX_REPORTED), checked, complete)
    }

    /** region/ and entities/ of the overworld, DIM-1, DIM1 and datapack dimensions. */
    private fun folders(world: Path): List<Path> = Files.walk(world, 5).use { stream ->
        stream.filter { it.isDirectory() && (it.name == "region" || it.name == "entities") }.sorted().toList()
    }

    private fun check(file: Path, relative: String, kind: String, rx: Int, rz: Int, deadline: Long, damaged: MutableList<DamagedChunk>): Int {
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            if (size == 0L) return 0
            fun damage(index: Int, reason: String) {
                damaged += DamagedChunk(kind, relative, rx * 32 + (index and 31), rz * 32 + (index shr 5), reason)
            }
            if (size < 2 * SECTOR) {
                damage(0, "the region header is cut ($size bytes)")
                return 0
            }
            val header = ByteBuffer.allocate(SECTOR)
            channel.read(header, 0)
            header.flip()
            val used = HashMap<Int, Int>()
            var checked = 0
            for (index in 0 until 1024) {
                val entry = header.getInt(index * 4)
                if (entry == 0) continue
                val offset = entry ushr 8
                val sectors = entry and 0xFF
                checked++
                when {
                    offset < 2 -> { damage(index, "its location points inside the header"); continue }
                    (offset.toLong() + sectors) * SECTOR > size -> { damage(index, "its location points past the end of the file"); continue }
                }
                val overlap = (offset until offset + sectors).firstOrNull { it in used }
                if (overlap != null) { damage(index, "it shares its sectors with another chunk"); continue }
                (offset until offset + sectors).forEach { used[it] = index }
                val head = ByteBuffer.allocate(5)
                channel.read(head, offset.toLong() * SECTOR)
                head.flip()
                val length = head.getInt()
                val compression = head.get().toInt() and 0xFF
                if (length <= 1 || length > sectors * SECTOR) { damage(index, "its length ($length bytes) does not fit its ${sectors * 4} KB"); continue }
                if (compression and 0x80 != 0) {
                    // Oversized chunk kept in its own c.<x>.<z>.mcc file next to the region.
                    val x = rx * 32 + (index and 31)
                    val z = rz * 32 + (index shr 5)
                    if (!file.resolveSibling("c.$x.$z.mcc").exists()) damage(index, "its data file c.$x.$z.mcc is missing")
                    continue
                }
                if (compression !in 1..4) { damage(index, "unknown compression type $compression"); continue }
                if (compression == 4 || System.currentTimeMillis() > deadline) continue
                val data = ByteBuffer.allocate(length - 1)
                channel.read(data, offset.toLong() * SECTOR + 5)
                val problem = runCatching { Nbt.read(decompress(data.array(), compression)) }.exceptionOrNull()
                if (problem != null) damage(index, problem.message ?: problem.javaClass.simpleName)
            }
            return checked
        }
    }

    private fun decompress(bytes: ByteArray, compression: Int): ByteArray = when (compression) {
        1 -> GZIPInputStream(bytes.inputStream()).use { it.readNBytes(MAX_CHUNK) }
        2 -> {
            val inflater = Inflater()
            try {
                inflater.setInput(bytes)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw Nbt.Malformed("compressed data ends too early")
                    out.write(buffer, 0, count)
                    if (out.size() > MAX_CHUNK) throw Nbt.Malformed("chunk larger than ${MAX_CHUNK / 1024 / 1024} MB once decompressed")
                }
                out.toByteArray()
            } catch (error: java.util.zip.DataFormatException) {
                throw Nbt.Malformed("compressed data is damaged (${error.message})")
            } finally {
                inflater.end()
            }
        }
        else -> bytes
    }
}

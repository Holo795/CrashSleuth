package dev.holo795.crashsleuth.inventory

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal reader of Minecraft's NBT format (level.dat, player data, chunks), enough to read values
 * without depending on the game. Compounds become maps, lists become lists.
 */
object Nbt {
    class Malformed(message: String) : Exception(message)

    /** Reads a gzipped NBT file such as level.dat; the root compound is returned. */
    fun readGzip(input: InputStream): Map<String, Any?> = read(GZIPInputStream(input))

    fun read(input: InputStream): Map<String, Any?> {
        val data = DataInputStream(input.buffered())
        val type = data.readByte().toInt()
        if (type != 10) throw Malformed("root is not a compound (tag $type)")
        data.readUTF()
        @Suppress("UNCHECKED_CAST")
        return payload(data, 10, 0) as Map<String, Any?>
    }

    private const val MAX_DEPTH = 512
    private const val MAX_ARRAY = 64 * 1024 * 1024

    private fun payload(data: DataInputStream, type: Int, depth: Int): Any? {
        if (depth > MAX_DEPTH) throw Malformed("nested too deep")
        return try {
            when (type) {
                1 -> data.readByte()
                2 -> data.readShort()
                3 -> data.readInt()
                4 -> data.readLong()
                5 -> data.readFloat()
                6 -> data.readDouble()
                7 -> ByteArray(size(data)).also(data::readFully)
                8 -> data.readUTF()
                9 -> {
                    val element = data.readByte().toInt()
                    List(size(data)) { payload(data, element, depth + 1) }
                }
                10 -> buildMap {
                    while (true) {
                        val child = data.readByte().toInt()
                        if (child == 0) break
                        put(data.readUTF(), payload(data, child, depth + 1))
                    }
                }
                11 -> IntArray(size(data)) { data.readInt() }
                12 -> LongArray(size(data)) { data.readLong() }
                else -> throw Malformed("unknown tag $type")
            }
        } catch (_: EOFException) {
            throw Malformed("file ends in the middle of a value")
        }
    }

    private fun size(data: DataInputStream): Int = data.readInt().also { if (it < 0 || it > MAX_ARRAY) throw Malformed("impossible size $it") }

    /** `path("Data", "Version", "Name")` on a root compound. */
    fun path(root: Map<String, Any?>, vararg keys: String): Any? =
        keys.fold(root as Any?) { current, key -> (current as? Map<*, *>)?.get(key) }
}

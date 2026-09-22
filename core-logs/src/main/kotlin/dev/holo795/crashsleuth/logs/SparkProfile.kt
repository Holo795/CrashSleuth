package dev.holo795.crashsleuth.logs

import dev.holo795.crashsleuth.model.Confidence
import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Situation
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a spark profile (`.sparkprofile`, saved with `spark profiler --save-to-file`): the sampled call tree
 * of each thread and the table saying which plugin or mod each class belongs to. Only these two are read;
 * the rest of the file (server configuration, player names) is never kept.
 */
object SparkProfile {
    /** Share of a thread's time spent inside one plugin or mod, callees included. */
    data class Share(val source: String, val percent: Double, val topMethod: String)

    data class Profile(val thread: String, val totalMillis: Double, val shares: List<Share>)

    private class Node(val className: String, val method: String, val time: Double, val children: IntArray)

    fun read(bytes: ByteArray, thread: String = "Server thread"): Profile? {
        val top = Proto(bytes)
        val sources = HashMap<String, String>()
        var tree: Proto? = null
        while (top.more()) {
            when (val field = top.tag()) {
                2 -> Proto(top.bytes()).let { candidate -> if (tree == null && threadName(candidate) == thread) tree = candidate }
                3, 4 -> Proto(top.bytes()).let { entry ->
                    var key: String? = null
                    var value: String? = null
                    while (entry.more()) when (entry.tag()) { 1 -> key = entry.string(); 2 -> value = entry.string(); else -> entry.skip() }
                    if (key != null && value != null && field == 3) sources[key] = value
                }
                else -> top.skip()
            }
        }
        val threadNode = tree ?: return null
        threadNode.reset()
        val nodes = mutableListOf<Node>()
        var roots = IntArray(0)
        var times = DoubleArray(0)
        while (threadNode.more()) {
            when (threadNode.tag()) {
                3 -> nodes += node(Proto(threadNode.bytes()))
                4 -> times = threadNode.doubles()
                5 -> roots = threadNode.ints()
                else -> threadNode.skip()
            }
        }
        val total = times.sum().takeIf { it > 0 } ?: roots.sumOf { nodes.getOrNull(it)?.time ?: 0.0 }
        if (total <= 0) return null
        // Time entering each plugin: a frame of a plugin whose callers belong to no plugin (or another one).
        val bySource = HashMap<String, Double>()
        val topMethod = HashMap<String, Pair<String, Double>>()
        fun visit(index: Int, owner: String?, depth: Int) {
            val node = nodes.getOrNull(index) ?: return
            if (depth > 4096) return
            val source = sources[node.className] ?: sources[node.className.substringBefore('$')]
            val entering = source != null && source != owner
            if (entering) {
                bySource.merge(source!!, node.time, Double::plus)
                if ((topMethod[source]?.second ?: -1.0) < node.time) topMethod[source] = "${node.className.substringAfterLast('.')}.${node.method}" to node.time
            }
            node.children.forEach { visit(it, source ?: owner, depth + 1) }
        }
        roots.forEach { visit(it, null, 0) }
        val shares = bySource.map { (source, time) -> Share(source, 100.0 * time / total, topMethod[source]?.first ?: "?") }.sortedByDescending { it.percent }
        return Profile(thread, total, shares)
    }

    /** A plugin or mod taking a fifth of the main thread or more is named; below, the profile only lists shares. */
    fun findings(profile: Profile, file: String): List<Finding> {
        val top = profile.shares.firstOrNull() ?: return emptyList()
        if (top.percent < 20) return emptyList()
        return listOf(
            Finding(
                situation = Situation.LAG,
                confidence = if (top.percent >= 40) Confidence.HIGH else Confidence.MEDIUM,
                culprits = listOf(Culprit(CulpritKind.PLUGIN, top.source)),
                evidence = profile.shares.take(3).map { "$file: ${it.source} ${"%.0f".format(it.percent)} % of the ${profile.thread} (${it.topMethod})" },
                details = mapOf("percent" to "%.0f".format(top.percent), "method" to top.topMethod, "adviceKey" to "lag.profile"),
            ),
        )
    }

    private fun threadName(thread: Proto): String? {
        while (thread.more()) { if (thread.tag() == 1) return thread.string() else thread.skip() }
        return null
    }

    private fun node(proto: Proto): Node {
        var className = ""
        var method = ""
        var time = 0.0
        var children = IntArray(0)
        while (proto.more()) {
            when (proto.tag()) {
                3 -> className = proto.string()
                4 -> method = proto.string()
                8 -> time = proto.doubles().sum()
                9 -> children = proto.ints()
                else -> proto.skip()
            }
        }
        return Node(className, method, time, children)
    }

    /** Just enough of the protobuf wire format: varints, lengths, packed doubles and ints. */
    private class Proto(private val data: ByteArray) {
        private var position = 0
        private var wire = 0

        fun more() = position < data.size
        fun reset() { position = 0 }

        fun tag(): Int = varint().toInt().also { wire = it and 7 }.ushr(3)

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (position >= data.size || shift > 63) throw IllegalArgumentException("not a spark profile")
                val byte = data[position++].toInt()
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }

        fun bytes(): ByteArray {
            val length = varint().toInt()
            if (length < 0 || position + length > data.size) throw IllegalArgumentException("not a spark profile")
            return data.copyOfRange(position, position + length).also { position += length }
        }

        fun string() = bytes().decodeToString()

        fun doubles(): DoubleArray = when (wire) {
            2 -> ByteBuffer.wrap(bytes()).order(ByteOrder.LITTLE_ENDIAN).let { buffer -> DoubleArray(buffer.remaining() / 8) { buffer.getDouble() } }
            1 -> doubleArrayOf(ByteBuffer.wrap(data, position, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble()).also { position += 8 }
            else -> { skip(); DoubleArray(0) }
        }

        fun ints(): IntArray = when (wire) {
            2 -> Proto(bytes()).let { inner -> buildList { while (inner.more()) add(inner.varint().toInt()) }.toIntArray() }
            0 -> intArrayOf(varint().toInt())
            else -> { skip(); IntArray(0) }
        }

        fun skip() {
            when (wire) {
                0 -> varint()
                1 -> position += 8
                2 -> bytes()
                5 -> position += 4
                else -> throw IllegalArgumentException("not a spark profile")
            }
        }
    }
}

package com.andrerinas.openheadunit.utils

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * Makes the file the panel driver's `load_lcddtb` node reads: the panel's own node, cut out of the unit's device tree,
 * with another pixel clock and link speed. Everything else, the panel's start-up commands included, stays the unit's.
 *
 * Both the tree and the file are flattened device trees. The file holds `/ { lcds { <panel> { ... } } }`.
 */
object PanelDescription {

    private const val MAGIC = 0xd00dfeed.toInt()
    private const val BEGIN_NODE = 1
    private const val END_NODE = 2
    private const val PROPERTY = 3
    private const val NOP = 4
    private const val END = 9

    private const val HEADER_BYTES = 40
    private const val EMPTY_RESERVE_MAP_BYTES = 16

    private const val CLOCK = "clock-frequency"
    private const val LINK = "sprd,phy-bit-clock"

    /** The timings the panel has to have of its own for the other rates to be known to suit it. */
    private val TIMINGS = mapOf(
        "hactive" to 1024, "hfront-porch" to 160, "hback-porch" to 136, "hsync-len" to 24,
        "vactive" to 600, "vfront-porch" to 12, "vback-porch" to 21, "vsync-len" to 2,
    )

    private class Node(val name: String) {
        val properties = LinkedHashMap<String, ByteArray>()
        val children = mutableListOf<Node>()

        fun child(name: String): Node? = children.firstOrNull { it.name == name }

        fun number(name: String): Int? = properties[name]?.takeIf { it.size == 4 }?.let { ByteBuffer.wrap(it).int }
    }

    /**
     * The file for [panel] at [pixelClock] and [linkSpeed], or null when [tree] does not hold that panel with the stock
     * clock, link speed and timings it is expected to have.
     */
    fun build(
        tree: ByteArray,
        panel: String,
        stockClock: Int,
        stockLinkSpeed: Int,
        pixelClock: Int,
        linkSpeed: Int,
    ): ByteArray? {
        val node = try {
            parse(tree).child("lcds")?.child(panel) ?: return null
        } catch (_: RuntimeException) {
            return null
        }
        val timing = node.child("display-timings")?.children?.singleOrNull() ?: return null
        if (node.number(LINK) != stockLinkSpeed || timing.number(CLOCK) != stockClock) return null
        if (TIMINGS.any { (name, value) -> timing.number(name) != value }) return null
        node.properties[LINK] = bytes(linkSpeed)
        timing.properties[CLOCK] = bytes(pixelClock)
        val lcds = Node("lcds").apply { children.add(node) }
        return write(Node("").apply { children.add(lcds) })
    }

    private fun bytes(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    private fun aligned(at: Int): Int = (at + 3) and 3.inv()

    private fun parse(blob: ByteArray): Node {
        val data = ByteBuffer.wrap(blob)
        require(data.getInt(0) == MAGIC && data.getInt(4) <= blob.size) { "not a device tree" }
        var at = data.getInt(8)
        val strings = data.getInt(12)
        val open = mutableListOf<Node>()
        var root: Node? = null

        fun end(from: Int): Int {
            var to = from
            while (blob[to] != 0.toByte()) to++
            return to
        }

        while (true) {
            val token = data.getInt(at)
            at += 4
            when (token) {
                BEGIN_NODE -> {
                    val to = end(at)
                    val node = Node(String(blob, at, to - at, Charsets.UTF_8))
                    at = aligned(to + 1)
                    if (open.isEmpty()) root = node else open.last().children.add(node)
                    open.add(node)
                }
                END_NODE -> open.removeAt(open.lastIndex)
                PROPERTY -> {
                    val size = data.getInt(at)
                    val from = strings + data.getInt(at + 4)
                    val name = String(blob, from, end(from) - from, Charsets.UTF_8)
                    at += 8
                    open.last().properties[name] = blob.copyOfRange(at, at + size)
                    at = aligned(at + size)
                }
                NOP -> Unit
                END -> return requireNotNull(root) { "an empty device tree" }
                else -> throw IllegalArgumentException("unknown token $token")
            }
        }
    }

    private fun write(root: Node): ByteArray {
        val strings = ByteArrayOutputStream()
        val offsets = HashMap<String, Int>()
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)

        fun padded(value: ByteArray) {
            out.write(value)
            repeat(aligned(value.size) - value.size) { out.write(0) }
        }

        fun emit(node: Node) {
            out.writeInt(BEGIN_NODE)
            padded(node.name.toByteArray(Charsets.UTF_8) + 0.toByte())
            for ((name, value) in node.properties) {
                val offset = offsets.getOrPut(name) {
                    strings.size().also {
                        strings.write(name.toByteArray(Charsets.UTF_8))
                        strings.write(0)
                    }
                }
                out.writeInt(PROPERTY)
                out.writeInt(value.size)
                out.writeInt(offset)
                padded(value)
            }
            for (child in node.children) emit(child)
            out.writeInt(END_NODE)
        }

        emit(root)
        out.writeInt(END)

        val structAt = HEADER_BYTES + EMPTY_RESERVE_MAP_BYTES
        val stringsAt = structAt + body.size()
        val total = stringsAt + strings.size()
        return ByteBuffer.allocate(total).apply {
            // magic, size, structure, strings, reserve map, version, oldest compatible, boot CPU, the two block sizes
            intArrayOf(MAGIC, total, structAt, stringsAt, HEADER_BYTES, 17, 16, 0, strings.size(), body.size())
                .forEach { putInt(it) }
            position(structAt)
            put(body.toByteArray())
            put(strings.toByteArray())
        }.array()
    }
}

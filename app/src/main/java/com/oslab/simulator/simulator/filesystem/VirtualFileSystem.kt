package com.oslab.simulator.simulator.filesystem

import com.oslab.simulator.security.ResourceLimits
import com.oslab.simulator.simulator.memory.VirtualRam

/**
 * The ONLY object an update package's simulated `delete`, `write`, `read`,
 * etc. may ever resolve to. A simulated call such as
 * `delete("/system/Launcher")` always means
 * `VirtualFileSystem.delete("/system/Launcher")` against the in-memory tree
 * below — never `java.io.File("/system/Launcher").delete()` against the
 * real device. There is no code path in this class that touches
 * `java.io.File`, `Context.getFilesDir()`, or any real path outside the
 * app's own process memory.
 *
 * Paths are virtual and root-relative ("system/Launcher", not tied to any
 * real mount point). ".." segments are always rejected so a path can never
 * climb out of this tree — there is nothing to climb out *into* since the
 * tree isn't backed by the real filesystem at all.
 */
sealed class VNode {
    class Dir(val children: MutableMap<String, VNode> = mutableMapOf()) : VNode()
    class File(var content: ByteArray) : VNode()
}

class VirtualFileSystem(private val ram: VirtualRam = VirtualRam()) {

    private var root = VNode.Dir().apply {
        children["system"] = VNode.Dir()
        children["apps"] = VNode.Dir()
        children["config"] = VNode.Dir()
        children["resources"] = VNode.Dir()
        children["data"] = VNode.Dir()
    }

    // ---- path handling -----------------------------------------------

    /** Splits a virtual path into segments, rejecting traversal attempts. */
    private fun segments(path: String): List<String>? {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
        if (parts.any { it == ".." || it == "." }) return null
        return parts
    }

    private fun resolveDir(segs: List<String>, createMissing: Boolean): VNode.Dir? {
        var cur: VNode.Dir = root
        for (seg in segs) {
            val next = cur.children[seg]
            when {
                next is VNode.Dir -> cur = next
                next == null && createMissing -> {
                    val d = VNode.Dir()
                    cur.children[seg] = d
                    cur = d
                }
                else -> return null // hit a file where a dir was expected, or missing
            }
        }
        return cur
    }

    // ---- public API -----------------------------------------------------

    fun exists(path: String): Boolean {
        val segs = segments(path) ?: return false
        if (segs.isEmpty()) return true
        val parent = resolveDir(segs.dropLast(1), createMissing = false) ?: return false
        return parent.children.containsKey(segs.last())
    }

    fun mkdir(path: String): Boolean {
        val segs = segments(path) ?: return false
        if (segs.isEmpty()) return true
        return resolveDir(segs, createMissing = true) != null
    }

    fun write(path: String, data: ByteArray): Boolean {
        val segs = segments(path) ?: return false
        if (segs.isEmpty()) return false
        if (data.size > ResourceLimits.MAX_FILE_BYTES) return false

        val parent = resolveDir(segs.dropLast(1), createMissing = true) ?: return false
        val name = segs.last()

        val existing = parent.children[name] as? VNode.File
        val delta = data.size - (existing?.content?.size ?: 0)
        if (delta > 0 && !ram.allocate(delta.toLong())) return false // out of virtual RAM
        if (delta < 0) ram.free((-delta).toLong())

        parent.children[name] = VNode.File(data)
        return true
    }

    fun read(path: String): ByteArray? {
        val segs = segments(path) ?: return null
        if (segs.isEmpty()) return null
        val parent = resolveDir(segs.dropLast(1), createMissing = false) ?: return null
        return (parent.children[segs.last()] as? VNode.File)?.content
    }

    fun delete(path: String): Boolean {
        val segs = segments(path) ?: return false
        if (segs.isEmpty()) return false
        val parent = resolveDir(segs.dropLast(1), createMissing = false) ?: return false
        val removed = parent.children.remove(segs.last()) ?: return false
        if (removed is VNode.File) ram.free(removed.content.size.toLong())
        return true
    }

    /** Lists entry names directly under [path] ("" for root). */
    fun list(path: String = ""): List<String> {
        val segs = segments(path) ?: return emptyList()
        val dir = resolveDir(segs, createMissing = false) ?: return emptyList()
        return dir.children.keys.sorted()
    }

    fun isDirectory(path: String): Boolean {
        val segs = segments(path) ?: return false
        if (segs.isEmpty()) return true
        val parent = resolveDir(segs.dropLast(1), createMissing = false) ?: return false
        return parent.children[segs.last()] is VNode.Dir
    }

    fun summary(): String = "in-memory, ${root.children.size} root entries, ${ram.summary()}"

    fun listRoot(): List<String> = list("")

    /** Deep-copies the whole tree — used by UpdateEngine before applying an update. */
    fun snapshot(): FsSnapshot = FsSnapshot(deepCopy(root), ram.usedBytes())

    fun restore(snapshot: FsSnapshot) {
        root = deepCopy(snapshot.root)
        ram.setUsedBytes(snapshot.usedRam)
    }

    /** Resets to a fresh empty layout (used on `reboot`, not on rollback). */
    fun reset() {
        root = VNode.Dir().apply {
            children["system"] = VNode.Dir()
            children["apps"] = VNode.Dir()
            children["config"] = VNode.Dir()
            children["resources"] = VNode.Dir()
            children["data"] = VNode.Dir()
        }
        ram.setUsedBytes(0)
    }

    private fun deepCopy(dir: VNode.Dir): VNode.Dir {
        val copy = VNode.Dir()
        for ((name, node) in dir.children) {
            copy.children[name] = when (node) {
                is VNode.Dir -> deepCopy(node)
                is VNode.File -> VNode.File(node.content.copyOf())
            }
        }
        return copy
    }
}

/** An immutable snapshot of the filesystem tree, for UpdateEngine rollback. */
class FsSnapshot internal constructor(internal val root: VNode.Dir, internal val usedRam: Long)

package com.oslab.simulator.simulator.memory

/**
 * A fixed, bounded byte arena. This is pure accounting — no real JVM heap
 * is exposed to uploaded code, and nothing here can pressure the host
 * app's actual memory beyond the ceiling below: every allocation is a
 * counter increment that gets rejected once the ceiling is hit.
 */
class VirtualRam(
    private val totalBytes: Long = 64L * 1024 * 1024 // 64 MB virtual RAM ceiling
) {
    private var usedBytes: Long = 0

    /** Returns false (and allocates nothing) if this would exceed the ceiling. */
    fun allocate(bytes: Long): Boolean {
        if (bytes < 0) return false
        if (usedBytes + bytes > totalBytes) return false
        usedBytes += bytes
        return true
    }

    fun free(bytes: Long) {
        usedBytes = (usedBytes - bytes).coerceAtLeast(0)
    }

    fun usedBytes(): Long = usedBytes

    internal fun setUsedBytes(bytes: Long) {
        usedBytes = bytes.coerceIn(0, totalBytes)
    }

    fun totalBytesCapacity(): Long = totalBytes

    fun summary(): String {
        val usedKb = usedBytes / 1024
        val totalKb = totalBytes / 1024
        return "${usedKb}KB / ${totalKb}KB"
    }
}

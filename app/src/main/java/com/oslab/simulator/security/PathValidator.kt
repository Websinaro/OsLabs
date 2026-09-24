package com.oslab.simulator.security

/**
 * Path validation for ZIP entries and simulated filesystem paths
 * (Stage 4). Rejects path traversal, absolute real-device paths, and other
 * suspicious entries before anything is ever extracted or mapped into the
 * VirtualFileSystem. No ZIP entry is extracted without passing this check.
 */
object PathValidator {

    private val deniedPrefixes = listOf(
        "/system", "/vendor", "/data", "/proc", "/dev", "/sys",
        "C:\\", "\\\\"
    )

    /** True if [path] is safe to map into the virtual filesystem. */
    fun isSafe(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        if (normalized.contains("..")) return false
        if (normalized.startsWith("/")) return false
        if (deniedPrefixes.any { normalized.startsWith(it, ignoreCase = true) }) return false
        return true
    }
}

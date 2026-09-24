package com.oslab.simulator.security

/**
 * Central place for every hard cap the simulator enforces (Stage 4/7).
 * These bound ZIP packages on import and the interpreter at execution time,
 * so a malicious or buggy update can only ever exhaust its own sandboxed
 * budget — never freeze or crash the host Android app.
 */
object ResourceLimits {
    const val MAX_PACKAGE_BYTES = 64L * 1024 * 1024          // 64 MB compressed
    const val MAX_UNCOMPRESSED_BYTES = 256L * 1024 * 1024    // 256 MB, guards zip bombs
    const val MAX_FILE_BYTES = 16L * 1024 * 1024              // 16 MB per file
    const val MAX_FILE_COUNT = 5000
    const val MAX_INSTRUCTIONS = 2_000_000
    const val MAX_EXECUTION_MILLIS = 5_000L
    const val MAX_RECURSION_DEPTH = 256
    const val MAX_PROCESSES = 64
    const val VIRTUAL_STACK_BASE = 0xFF00
}

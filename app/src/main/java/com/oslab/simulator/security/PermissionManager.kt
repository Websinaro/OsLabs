package com.oslab.simulator.security

/**
 * Virtual permission model (Stage 3). Permissions such as
 * "filesystem.read", "process.create", "display.render" exist ONLY inside
 * the simulation and are checked against simulated capabilities — they
 * never translate into a real Android permission grant or a call into the
 * Android permission system.
 */
class PermissionManager {

    private val granted = mutableSetOf(
        "filesystem.read",
        "filesystem.write",
        "process.create",
        "display.render",
        "input.read"
    )

    fun has(permission: String): Boolean = permission in granted
}

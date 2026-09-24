package com.oslab.simulator.simulator.display

/**
 * Placeholder for the virtual framebuffer (Stage 6). The simulated OS will
 * eventually render into this object's framebuffer, and the Android UI
 * (VirtualDisplayPanel) will simply paint that framebuffer — it never
 * gains a back door into real Android windowing/display APIs.
 */
class VirtualDisplay(
    val width: Int = 480,
    val height: Int = 800,
    val refreshHz: Int = 60
) {
    fun summary(): String = "${width}x${height} @ ${refreshHz}Hz"
}

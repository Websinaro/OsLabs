package com.oslab.simulator.simulator.input

/**
 * Virtual input events (Stage 8). Taps on the simulated display are
 * queued here and drained by VirtualDevice — never dispatched as real
 * Android input events, and this class has no reference to any Android
 * view, window, or input framework class.
 */
class VirtualInput {
    private val queue = ArrayDeque<String>()

    fun enqueue(event: String) {
        queue.addLast(event)
    }

    fun pending(): Int = queue.size

    /** Removes and returns every queued event, oldest first. */
    fun drain(): List<String> {
        val events = queue.toList()
        queue.clear()
        return events
    }
}

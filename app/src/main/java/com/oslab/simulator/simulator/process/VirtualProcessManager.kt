package com.oslab.simulator.simulator.process

import com.oslab.simulator.security.ResourceLimits

/** A process exists only inside the simulated machine. */
enum class ProcessState { READY, RUNNING, SLEEPING, TERMINATED }

data class VProcess(
    val pid: Int,
    val name: String,
    var state: ProcessState = ProcessState.READY,
    var ticks: Long = 0
)

/**
 * Small round-robin scheduler for the virtual OS. It never maps to an Android
 * process/thread; it only advances bookkeeping for virtual processes.
 */
class VirtualProcessManager {
    private var nextPid = 2
    private var currentPid = 0
    private val processes = linkedMapOf(
        0 to VProcess(0, "kernel", ProcessState.RUNNING),
        1 to VProcess(1, "init", ProcessState.READY)
    )

    fun summary(): String = "${processes.size} virtual process(es), current pid $currentPid"

    fun list(): List<String> = processes.values
        .sortedBy { it.pid }
        .map { "${it.pid}\t${it.name}\t${it.state.name.lowercase()}\tticks=${it.ticks}" }

    fun create(name: String): Int? {
        val clean = name.trim().take(32)
        if (clean.isEmpty() || processes.size >= ResourceLimits.MAX_PROCESSES) return null
        val pid = nextPid++
        processes[pid] = VProcess(pid, clean, ProcessState.READY)
        return pid
    }

    fun kill(pid: Int): Boolean {
        if (pid == 0 || pid == 1) return false
        return processes.remove(pid)?.let {
            if (currentPid == pid) currentPid = 0
            true
        } ?: false
    }

    fun exists(pid: Int): Boolean = processes.containsKey(pid)

    /** Advance the virtual scheduler by one tick. */
    fun tick(): String {
        val runnable = processes.values.filter { it.state == ProcessState.READY || it.state == ProcessState.RUNNING }
        if (runnable.isEmpty()) return "scheduler: idle"

        val currentIndex = runnable.indexOfFirst { it.pid == currentPid }
        val next = runnable[(currentIndex + 1).mod(runnable.size)]
        processes.values.forEach { if (it.state == ProcessState.RUNNING) it.state = ProcessState.READY }
        next.state = ProcessState.RUNNING
        next.ticks++
        currentPid = next.pid
        return "scheduler: pid ${next.pid} (${next.name}) running"
    }

    fun current(): VProcess? = processes[currentPid]

    fun reset() {
        processes.clear()
        processes[0] = VProcess(0, "kernel", ProcessState.RUNNING)
        processes[1] = VProcess(1, "init", ProcessState.READY)
        nextPid = 2
        currentPid = 0
    }
}

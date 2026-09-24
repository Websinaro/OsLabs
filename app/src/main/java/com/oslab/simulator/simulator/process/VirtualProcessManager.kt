package com.oslab.simulator.simulator.process

import com.oslab.simulator.security.ResourceLimits

/**
 * The virtual process table. Entries here are bookkeeping records the
 * interpreter (VirtualCpu) associates its bounded runs with — never real
 * Android processes, and nothing here calls ProcessBuilder, Runtime.exec,
 * or android.os.Process.
 */
data class VProcess(val pid: Int, val name: String, val state: String = "running")

class VirtualProcessManager {

    private var nextPid = 2 // 0 = kernel, 1 = init, both seeded below
    private val processes = linkedMapOf(
        0 to VProcess(0, "kernel"),
        1 to VProcess(1, "init")
    )

    fun summary(): String = "${processes.size} virtual process(es) running"

    fun list(): List<String> = processes.values
        .sortedBy { it.pid }
        .map { "${it.pid}\t${it.name}\t${it.state}" }

    /** Returns the new pid, or null if the virtual process table is full. */
    fun create(name: String): Int? {
        if (processes.size >= ResourceLimits.MAX_PROCESSES) return null
        val pid = nextPid++
        processes[pid] = VProcess(pid, name)
        return pid
    }

    fun kill(pid: Int): Boolean {
        if (pid == 0 || pid == 1) return false // kernel / init are not killable
        return processes.remove(pid) != null
    }

    fun exists(pid: Int): Boolean = processes.containsKey(pid)

    fun reset() {
        processes.clear()
        processes[0] = VProcess(0, "kernel")
        processes[1] = VProcess(1, "init")
        nextPid = 2
    }
}

package com.oslab.simulator.simulator.device

import com.oslab.simulator.security.SandboxController
import com.oslab.simulator.simulator.cpu.Instruction
import com.oslab.simulator.simulator.cpu.VirtualAssembly
import com.oslab.simulator.simulator.cpu.VirtualCpu
import com.oslab.simulator.simulator.display.VirtualDisplay
import com.oslab.simulator.simulator.filesystem.VirtualFileSystem
import com.oslab.simulator.simulator.input.VirtualInput
import com.oslab.simulator.simulator.memory.VirtualRam
import com.oslab.simulator.simulator.process.VirtualProcessManager
import com.oslab.simulator.security.PermissionManager

/**
 * The root simulation object. Everything an uploaded update package can
 * possibly touch hangs off this tree, and every child is a *virtual*
 * resource with no direct path to a real Android API:
 *
 *   VirtualDevice
 *   ├── CPU               (VirtualCpu)
 *   ├── RAM                (VirtualRam)
 *   ├── FileSystem         (VirtualFileSystem, backed by the same RAM)
 *   ├── ProcessManager     (VirtualProcessManager)
 *   ├── PermissionManager  (security.PermissionManager)
 *   ├── Display            (VirtualDisplay)
 *   └── Input              (VirtualInput)
 *
 * This is the single object graph every stage builds on: the update engine
 * mutates `fileSystem`, the "desktop" the UI renders is read from
 * `fileSystem.list("apps")`, and `launchApp` is the only way a simulated
 * app's code (a small VirtualAssembly program) ever runs, always through
 * `cpu.execute` wrapped in `SandboxController.runContained`.
 */
class VirtualDevice {

    private var osName = "MyOS"
    private var osVersionNumber = "1.0"
    private var bootCount = 0

    val cpu = VirtualCpu()
    val ram = VirtualRam()
    val fileSystem = VirtualFileSystem(ram)
    val processManager = VirtualProcessManager()
    val permissionManager = PermissionManager()
    val display = VirtualDisplay()
    val input = VirtualInput()

    init {
        // Seed a couple of built-in demo apps so the desktop isn't empty
        // before any update package has ever been imported.
        fileSystem.mkdir("apps/Notes")
        fileSystem.write(
            "apps/Notes/main.vasm",
            """
            PUSH 2
            PUSH 3
            ADD
            STORE total
            LOAD total
            WRITE resources/notes-total.txt
            EXIT
            """.trimIndent().toByteArray()
        )
    }

    fun bootLog(): List<String> {
        bootCount++
        return listOf(
            "[OK] Virtual CPU initialized (${cpu.summary()})",
            "[OK] Virtual RAM initialized (${ram.summary()})",
            "[OK] Virtual filesystem initialized (${fileSystem.summary()})",
            "[OK] Virtual process manager initialized (${processManager.summary()})",
            "[OK] Virtual permissions initialized",
            "[OK] Virtual display initialized (${display.summary()})",
            "[OK] Virtual input initialized",
            "[OK] ${osVersion()} ready. Boot #$bootCount."
        )
    }

    /** OS identity, e.g. "MyOS" — checked against manifest.name during an update. */
    fun osName(): String = osName

    /** OS version number alone, e.g. "1.0" — checked against manifest.targetVersion. */
    fun osVersionNumber(): String = osVersionNumber

    /** Combined display label, e.g. "MyOS 1.0". Used for UI/log output only. */
    fun osVersion(): String = "$osName $osVersionNumber"

    /** Called only by UpdateEngine after a successful, validated update. */
    internal fun setOsVersion(versionNumber: String) {
        osVersionNumber = versionNumber
    }

    fun reboot(): String {
        processManager.reset()
        bootLog()
        return osVersion()
    }

    fun memorySummary(): List<String> = listOf(ram.summary())
    fun processSummary(): List<String> = processManager.list()
    fun filesystemSummary(): List<String> = fileSystem.listRoot()
    fun kernelSummary(): List<String> = listOf(
        "Kernel: MyOS kernel 0.1",
        "Scheduler: round-robin",
        "Current process: ${processManager.current()?.pid ?: -1}",
        "Process table: ${processManager.summary()}"
    )

    fun deviceSummary(): List<String> = listOf(
        "Device: Virtual (simulation only, no real hardware access)",
        "OS: ${osVersion()}",
        "CPU: ${cpu.summary()}",
        "RAM: ${ram.summary()}",
        "Display: ${display.summary()}"
    )

    /** Built-in shortcuts plus every top-level entry under apps/. */
    fun desktopApps(): List<String> = listOf("Settings", "Terminal") + fileSystem.list("apps")

    /**
     * Launches an app icon tap. Built-ins ("Settings", "Terminal") just
     * return an informational line. Anything else is looked up under
     * apps/<name>/main.vasm and, if present, assembled and run through the
     * bounded interpreter inside a SandboxController boundary so a broken
     * or hostile app can only ever report "crashed", never take the host
     * app down with it.
     */
    fun launchApp(name: String): List<String> {
        when (name) {
            "Settings" -> return deviceSummary()
            "Terminal" -> return listOf("Terminal is already focused below.")
        }

        val programPath = "apps/$name/main.vasm"
        if (!fileSystem.exists(programPath)) {
            return listOf("$name has no main.vasm — nothing to run.")
        }
        val source = fileSystem.read(programPath)?.decodeToString() ?: return listOf("$name: could not read program.")

        val pid = processManager.create(name) ?: return listOf("$name: process table full, cannot launch.")

        val outcome = SandboxController.runContained {
            when (val parsed = VirtualAssembly.parse(source)) {
                is VirtualAssembly.ParseResult.Error -> listOf("$name: parse error — ${parsed.message}")
                is VirtualAssembly.ParseResult.Ok -> {
                    when (val result = cpu.execute(parsed.instructions, fileSystem, processManager)) {
                        is VirtualCpu.ExecutionResult.Completed -> listOf("$name (pid $pid) exited normally.") + result.output
                        is VirtualCpu.ExecutionResult.Halted -> listOf("$name (pid $pid) halted — ${result.reason}") + result.output
                    }
                }
            }
        }

        processManager.kill(pid)

        return when (outcome) {
            is SandboxController.ContainedResult.Success -> outcome.value
            is SandboxController.ContainedResult.Crashed ->
                listOf("$name (pid $pid) crashed inside the simulation: ${outcome.reason}", "Real device unaffected.")
        }
    }
}

package com.oslab.simulator.boot

import com.oslab.simulator.security.SandboxController
import com.oslab.simulator.simulator.cpu.VirtualAssembly
import com.oslab.simulator.simulator.cpu.VirtualCpu
import com.oslab.simulator.simulator.filesystem.VirtualFileSystem
import com.oslab.simulator.simulator.process.VirtualProcessManager

/**
 * Loads the user's entry-point kernel program from the VirtualFileSystem,
 * assembles it, and runs it on the VirtualCpu — inside a SandboxController
 * boundary so a broken or hostile kernel can only ever report a boot
 * failure, never take the host app down with it.
 *
 * Entry point resolution, in order:
 *   1. `system/boot.vasm` — the primary boot entry point.
 *   2. `system/post.vasm` — used only if boot.vasm is missing or empty
 *      (blank/whitespace-only content also counts as empty).
 *   3. Neither present/non-empty -> boot failure.
 *
 * This does not decide *when* to boot (VirtualDevice does that, on startup
 * and on every reboot/update boot test) and it never touches anything
 * outside the virtual objects it's handed — no real file, thread, or
 * process is involved.
 */
object BootManager {

    const val BOOT_PATH = "system/boot.vasm"
    const val POST_PATH = "system/post.vasm"

    sealed class BootResult {
        data class Success(val log: List<String>) : BootResult()
        data class Failed(val log: List<String>, val reason: String) : BootResult()
    }

    /** Picks boot.vasm if it exists and isn't empty, else falls back to post.vasm. */
    private fun resolveEntryPoint(fileSystem: VirtualFileSystem): Pair<String, String>? {
        val bootSource = fileSystem.read(BOOT_PATH)?.decodeToString()
        if (!bootSource.isNullOrBlank()) return BOOT_PATH to bootSource

        val postSource = fileSystem.read(POST_PATH)?.decodeToString()
        if (!postSource.isNullOrBlank()) return POST_PATH to postSource

        return null
    }

    fun boot(
        fileSystem: VirtualFileSystem,
        cpu: VirtualCpu,
        processManager: VirtualProcessManager
    ): BootResult {
        val log = mutableListOf<String>()

        val entry = resolveEntryPoint(fileSystem)
        if (entry == null) {
            log.add("[FAIL] Kernel parsing — neither $BOOT_PATH nor $POST_PATH found (or both empty)")
            log.add("[FAIL] Boot failed")
            return BootResult.Failed(log, "no boot entry point found")
        }
        val (path, source) = entry
        if (path == POST_PATH) {
            log.add("[INFO] $BOOT_PATH missing or empty — falling back to $POST_PATH")
        }

        val parsed = VirtualAssembly.parse(source)
        if (parsed is VirtualAssembly.ParseResult.Error) {
            log.add("[FAIL] Kernel parsing — ${parsed.message}")
            log.add("[FAIL] Boot failed")
            return BootResult.Failed(log, parsed.message)
        }
        val instructions = (parsed as VirtualAssembly.ParseResult.Ok).instructions
        log.add("[OK] Kernel parsed ($path)")
        log.add("[INFO] Starting virtual CPU")

        val outcome = SandboxController.runContained {
            cpu.execute(instructions, fileSystem, processManager)
        }

        return when (outcome) {
            is SandboxController.ContainedResult.Crashed -> {
                log.add("[FAIL] Kernel crashed inside the simulation: ${outcome.reason}")
                log.add("[FAIL] Boot failed")
                BootResult.Failed(log, outcome.reason)
            }
            is SandboxController.ContainedResult.Success -> {
                when (val result = outcome.value) {
                    is VirtualCpu.ExecutionResult.Halted -> {
                        result.output.forEach { log.add("[BOOT] $it") }
                        log.add("[FAIL] Kernel halted early — ${result.reason}")
                        log.add("[FAIL] Boot failed")
                        BootResult.Failed(log, result.reason)
                    }
                    is VirtualCpu.ExecutionResult.Completed -> {
                        result.output.forEach { log.add("[BOOT] $it") }
                        log.add("[OK] Kernel halted")
                        BootResult.Success(log)
                    }
                }
            }
        }
    }
}

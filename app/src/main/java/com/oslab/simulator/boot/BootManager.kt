package com.oslab.simulator.boot

import com.oslab.simulator.security.SandboxController
import com.oslab.simulator.simulator.cpu.VirtualAssembly
import com.oslab.simulator.simulator.cpu.VirtualCpu
import com.oslab.simulator.simulator.filesystem.VirtualFileSystem
import com.oslab.simulator.simulator.process.VirtualProcessManager

/**
 * Loads the user's `system/kernel.vasm` from the VirtualFileSystem, assembles
 * it, and runs it on the VirtualCpu — inside a SandboxController boundary so
 * a broken or hostile kernel can only ever report a boot failure, never take
 * the host app down with it.
 *
 * This does not decide *when* to boot (VirtualDevice does that, on startup
 * and on every reboot/update boot test) and it never touches anything
 * outside the virtual objects it's handed — no real file, thread, or
 * process is involved.
 */
object BootManager {

    const val KERNEL_PATH = "system/kernel.vasm"

    sealed class BootResult {
        data class Success(val log: List<String>) : BootResult()
        data class Failed(val log: List<String>, val reason: String) : BootResult()
    }

    fun boot(
        fileSystem: VirtualFileSystem,
        cpu: VirtualCpu,
        processManager: VirtualProcessManager
    ): BootResult {
        val log = mutableListOf<String>()

        if (!fileSystem.exists(KERNEL_PATH)) {
            log.add("[FAIL] Kernel parsing — $KERNEL_PATH not found")
            log.add("[FAIL] Boot failed")
            return BootResult.Failed(log, "$KERNEL_PATH not found")
        }

        val source = fileSystem.read(KERNEL_PATH)?.decodeToString()
        if (source == null) {
            log.add("[FAIL] Kernel parsing — could not read $KERNEL_PATH")
            log.add("[FAIL] Boot failed")
            return BootResult.Failed(log, "could not read $KERNEL_PATH")
        }

        val parsed = VirtualAssembly.parse(source)
        if (parsed is VirtualAssembly.ParseResult.Error) {
            log.add("[FAIL] Kernel parsing — ${parsed.message}")
            log.add("[FAIL] Boot failed")
            return BootResult.Failed(log, parsed.message)
        }
        val instructions = (parsed as VirtualAssembly.ParseResult.Ok).instructions
        log.add("[OK] Kernel parsed ($KERNEL_PATH)")
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

package com.oslab.simulator.update

import com.oslab.simulator.security.SandboxController
import com.oslab.simulator.simulator.device.VirtualDevice

/**
 * Orchestrates the update simulation flow (Stage 5):
 * snapshot -> validate manifest -> apply to VirtualFileSystem -> boot test
 * -> commit or roll back. Operates entirely on VirtualDevice /
 * VirtualFileSystem objects handed to it by TerminalViewModel after
 * ZipImporter + PackageValidator have already approved the package —
 * nothing here ever writes to a real Android path.
 */
object UpdateEngine {

    sealed class UpdateOutcome {
        data class Success(val log: List<String>) : UpdateOutcome()
        data class Failed(val log: List<String>, val reason: String) : UpdateOutcome()
    }

    fun simulateUpdate(device: VirtualDevice, pkg: ZipImporter.ImportedPackage): UpdateOutcome {
        val log = mutableListOf<String>()

        val manifestBytes = pkg.manifestBytes
        if (manifestBytes == null) {
            log.add("[FAIL] manifest validation — manifest.json missing")
            return UpdateOutcome.Failed(log, "manifest.json missing")
        }

        val parsed = UpdateManifest.parse(manifestBytes)
        val manifest = when (parsed) {
            is ValidationResult.Invalid -> {
                log.add("[FAIL] manifest validation — ${parsed.reason}")
                return UpdateOutcome.Failed(log, parsed.reason)
            }
            is ValidationResult.Valid -> parsed.manifest
        }
        log.add("[OK] Manifest validation — ${manifest.name} ${manifest.version} (targets ${manifest.targetVersion})")

        val compat = ManifestValidator.validateCompatibility(
            manifest,
            device.osName(),
            device.osVersionNumber()
        )
        if (compat is ValidationResult.Invalid) {
            log.add("[FAIL] Compatibility check — ${compat.reason}")
            return UpdateOutcome.Failed(log, compat.reason)
        }
        log.add("[OK] Compatibility check — ${device.osVersion()} → ${device.osName()} ${manifest.version}")

        val snapshot = device.fileSystem.snapshot()
        log.add("[OK] Virtual filesystem snapshot taken")

        for ((path, bytes) in pkg.files) {
            if (path == "manifest.json") continue
            device.fileSystem.write(path, bytes)
        }
        log.add("[OK] Virtual filesystem update applied (${pkg.files.size} files)")

        // reboot() re-runs the full boot sequence, which hands
        // system/kernel.vasm to BootManager and only reaches RUNNING if the
        // kernel actually parses and executes — never just a label change.
        val bootOutcome = SandboxController.runContained {
            val bootLines = device.reboot()
            val coreDirsOk = listOf("system", "apps", "config", "resources").all { device.fileSystem.isDirectory(it) }
            Pair(bootLines, coreDirsOk && device.state() == VirtualDevice.OsState.RUNNING)
        }

        val (bootLines, bootOk) = when (bootOutcome) {
            is SandboxController.ContainedResult.Success -> bootOutcome.value
            is SandboxController.ContainedResult.Crashed -> Pair(listOf("[FAIL] reboot crashed inside the simulation: ${bootOutcome.reason}"), false)
        }
        log.addAll(bootLines)

        if (!bootOk) {
            device.fileSystem.restore(snapshot)
            log.add("[FAIL] Boot test — virtual OS failed to boot on the updated filesystem")
            log.addAll(device.reboot())
            log.add("[OK] Rolled back to the pre-update snapshot")
            log.add("[OK] Previous OS restored")
            return UpdateOutcome.Failed(log, "boot test failed; rolled back")
        }
        log.add("[OK] Boot test — virtual OS booted successfully")

        device.setOsVersion(manifest.version)
        log.add("UPDATE SIMULATION SUCCESSFUL — now running ${device.osVersion()}")
        log.add("The real Android device was not modified.")
        return UpdateOutcome.Success(log)
    }
}

package com.oslab.simulator.terminal

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oslab.simulator.simulator.device.VirtualDevice
import com.oslab.simulator.update.UpdateEngine
import com.oslab.simulator.update.ZipImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Backs the terminal + display panels. Owns the single VirtualDevice
 * instance for this session and is the only place that turns user input
 * (typed commands, tapped app icons, or an imported ZIP) into calls
 * against the virtual machine. Nothing in this class shells out to the
 * real OS: every branch below resolves to a method call on VirtualDevice,
 * ZipImporter, or UpdateEngine — never Runtime.exec, ProcessBuilder, su,
 * or a real filesystem path outside this app's own cache directory (used
 * only as scratch space to open the picked ZIP).
 */
class TerminalViewModel : ViewModel() {

    private val device = VirtualDevice()

    val logLines = mutableStateListOf<String>()
    var inputText by mutableStateOf("")
    var status by mutableStateOf("Idle")
        private set
    var virtualOsLabel by mutableStateOf(device.osVersion())
        private set
    var desktopApps = mutableStateListOf<String>()
        private set

    init {
        boot()
    }

    private fun boot() {
        status = "Booting"
        log("MyOS Kernel v0.1")
        log("")
        device.bootLog().forEach { log(it) }
        refreshDesktop()
        log("")
        log("MyOS>")
        status = "Running"
    }

    private fun refreshDesktop() {
        desktopApps.clear()
        desktopApps.addAll(device.desktopApps())
        virtualOsLabel = device.osVersion()
    }

    fun runCommand(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return
        log("MyOS> $cmd")
        inputText = ""

        val parts = cmd.split(Regex("\\s+"), limit = 2)
        val head = parts[0].lowercase()
        val arg = parts.getOrNull(1)

        val output: List<String>? = when (head) {
            "help" -> listOf(
                "Available commands:",
                "  help              show this message",
                "  clear             clear the terminal",
                "  version           show virtual OS version",
                "  memory            show virtual RAM usage",
                "  cpu               show virtual CPU registers",
                "  tick              advance the virtual scheduler",
                "  kernel            show virtual kernel status",
                "  processes | ps    list virtual processes",
                "  kill <pid>        kill a virtual process",
                "  filesystem [path] list a virtual directory",
                "  devices           show virtual device summary",
                "  apps              list desktop apps",
                "  launch <app>      run an app's main.vasm",
                "  run <path>        assemble + run a .vasm file from the VFS",
                "  reboot            reboot the virtual OS",
                "  shutdown          shut down the virtual OS",
                "  update            import an update ZIP (use the Import button)"
            )
            "clear" -> {
                logLines.clear()
                null
            }
            "version" -> listOf(device.osVersion())
            "memory" -> device.memorySummary()
            "cpu" -> device.cpu.registerSummary()
            "tick" -> listOf(device.processManager.tick())
            "kernel" -> device.kernelSummary()
            "processes", "ps" -> device.processSummary()
            "kill" -> {
                val pid = arg?.toIntOrNull()
                when {
                    pid == null -> listOf("usage: kill <pid>")
                    device.processManager.kill(pid) -> listOf("Killed pid $pid")
                    else -> listOf("Could not kill pid $pid (not found, or protected)")
                }
            }
            "filesystem" -> device.fileSystem.list(arg ?: "").ifEmpty { listOf("(empty)") }
            "devices" -> device.deviceSummary()
            "apps" -> device.desktopApps()
            "launch" -> {
                if (arg == null) listOf("usage: launch <app>")
                else {
                    val result = device.launchApp(arg)
                    refreshDesktop()
                    result
                }
            }
            "run" -> {
                if (arg == null) listOf("usage: run <virtual/path/to/file.vasm>")
                else runVasmFile(arg)
            }
            "reboot" -> {
                status = "Rebooting"
                device.reboot()
                refreshDesktop()
                status = "Running"
                listOf("Virtual OS rebooted.")
            }
            "shutdown" -> {
                status = "Shut down"
                listOf("Virtual OS shut down. (Simulation only — the real device is unaffected.)")
            }
            "update" -> listOf(
                "Use the Import Update button to pick a .zip via the system file picker."
            )
            else -> listOf("Unknown command: $head (type 'help')")
        }
        output?.forEach { log(it) }
        if (output != null) log("MyOS>")
    }

    private fun runVasmFile(path: String): List<String> {
        val source = device.fileSystem.read(path)?.decodeToString()
            ?: return listOf("$path not found in the virtual filesystem.")
        return when (val parsed = com.oslab.simulator.simulator.cpu.VirtualAssembly.parse(source)) {
            is com.oslab.simulator.simulator.cpu.VirtualAssembly.ParseResult.Error ->
                listOf("Parse error: ${parsed.message}")
            is com.oslab.simulator.simulator.cpu.VirtualAssembly.ParseResult.Ok -> {
                when (val result = device.cpu.execute(parsed.instructions, device.fileSystem, device.processManager)) {
                    is com.oslab.simulator.simulator.cpu.VirtualCpu.ExecutionResult.Completed ->
                        listOf("Program completed in ${result.steps} steps.") + result.output
                    is com.oslab.simulator.simulator.cpu.VirtualCpu.ExecutionResult.Halted ->
                        listOf("Program halted after ${result.steps} steps: ${result.reason}") + result.output
                }
            }
        }
    }

    fun launchApp(name: String) {
        log("MyOS> launch $name")
        device.launchApp(name).forEach { log(it) }
        refreshDesktop()
        log("MyOS>")
    }

    /** Triggered from the Import Update button once the user has picked a ZIP via SAF. */
    fun importUpdate(uri: Uri, resolver: ContentResolver, cacheDir: File) {
        status = "Importing update"
        log("MyOS> update --import")
        viewModelScope.launch {
            val importResult = withContext(Dispatchers.IO) {
                ZipImporter.import(resolver, uri, cacheDir)
            }
            when (importResult) {
                is ZipImporter.ImportOutcome.Error -> {
                    importResult.log.forEach { log(it) }
                    log("[FAIL] Import aborted: ${importResult.reason}")
                }
                is ZipImporter.ImportOutcome.Ok -> {
                    importResult.log.forEach { log(it) }
                    val engineResult = withContext(Dispatchers.Default) {
                        UpdateEngine.simulateUpdate(device, importResult.pkg)
                    }
                    when (engineResult) {
                        is UpdateEngine.UpdateOutcome.Success -> engineResult.log.forEach { log(it) }
                        is UpdateEngine.UpdateOutcome.Failed -> engineResult.log.forEach { log(it) }
                    }
                    refreshDesktop()
                }
            }
            status = "Running"
            log("MyOS>")
        }
    }

    private fun log(line: String) {
        logLines.add(line)
    }
}

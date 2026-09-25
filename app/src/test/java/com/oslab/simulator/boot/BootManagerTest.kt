package com.oslab.simulator.boot

import com.oslab.simulator.simulator.cpu.VirtualCpu
import com.oslab.simulator.simulator.filesystem.VirtualFileSystem
import com.oslab.simulator.simulator.memory.VirtualRam
import com.oslab.simulator.simulator.process.VirtualProcessManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootManagerTest {

    private fun freshEnv() = Triple(VirtualFileSystem(VirtualRam()), VirtualCpu(), VirtualProcessManager())

    @Test
    fun missingKernelFailsBoot() {
        val (fs, cpu, pm) = freshEnv()
        val result = BootManager.boot(fs, cpu, pm)
        assertTrue(result is BootManager.BootResult.Failed)
        result as BootManager.BootResult.Failed
        assertTrue(result.log.any { it.startsWith("[FAIL] Kernel parsing") })
    }

    @Test
    fun invalidVasmFailsBoot() {
        val (fs, cpu, pm) = freshEnv()
        fs.write("system/kernel.vasm", "NOT_A_REAL_INSTRUCTION".toByteArray())
        val result = BootManager.boot(fs, cpu, pm)
        assertTrue(result is BootManager.BootResult.Failed)
    }

    @Test
    fun printAndHaltBootSuccessfully() {
        val (fs, cpu, pm) = freshEnv()
        fs.write(
            "system/kernel.vasm",
            """
            PRINT "Boot loading Start...."
            PRINT "SUCCESSFUL"
            HALT
            """.trimIndent().toByteArray()
        )
        val result = BootManager.boot(fs, cpu, pm)
        assertTrue(result is BootManager.BootResult.Success)
        result as BootManager.BootResult.Success
        assertTrue(result.log.contains("[BOOT] Boot loading Start...."))
        assertTrue(result.log.contains("[BOOT] SUCCESSFUL"))
        assertEquals("[OK] Kernel halted", result.log.last())
    }
}

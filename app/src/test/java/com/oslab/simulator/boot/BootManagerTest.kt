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
    fun noEntryPointFailsBoot() {
        val (fs, cpu, pm) = freshEnv()
        val result = BootManager.boot(fs, cpu, pm)
        assertTrue(result is BootManager.BootResult.Failed)
        result as BootManager.BootResult.Failed
        assertTrue(result.log.any { it.startsWith("[FAIL] Kernel parsing") })
    }

    @Test
    fun blankBootVasmFailsJustLikeMissing() {
        val (fs, cpu, pm) = freshEnv()
        fs.write(BootManager.BOOT_PATH, "   \n  \n".toByteArray())
        val result = BootManager.boot(fs, cpu, pm)
        assertTrue(result is BootManager.BootResult.Failed)
    }

    @Test
    fun invalidVasmFailsBoot() {
        val (fs, cpu, pm) = freshEnv()
        fs.write(BootManager.BOOT_PATH, "NOT_A_REAL_INSTRUCTION".toByteArray())
        val result = BootManager.boot(fs, cpu, pm)
        assertTrue(result is BootManager.BootResult.Failed)
    }

    @Test
    fun printAndHaltBootSuccessfullyFromBootVasm() {
        val (fs, cpu, pm) = freshEnv()
        fs.write(
            BootManager.BOOT_PATH,
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

    @Test
    fun emptyBootVasmFallsBackToPostVasm() {
        val (fs, cpu, pm) = freshEnv()
        fs.write(BootManager.BOOT_PATH, "".toByteArray())
        fs.write(
            BootManager.POST_PATH,
            """
            PRINT "post-install entry"
            HALT
            """.trimIndent().toByteArray()
        )
        val result = BootManager.boot(fs, cpu, pm)
        assertTrue(result is BootManager.BootResult.Success)
        result as BootManager.BootResult.Success
        assertTrue(result.log.any { it.contains("falling back to ${BootManager.POST_PATH}") })
        assertTrue(result.log.contains("[BOOT] post-install entry"))
    }

    @Test
    fun nonEmptyBootVasmIsPreferredOverPostVasm() {
        val (fs, cpu, pm) = freshEnv()
        fs.write(BootManager.BOOT_PATH, "PRINT \"from boot\"\nHALT".toByteArray())
        fs.write(BootManager.POST_PATH, "PRINT \"from post\"\nHALT".toByteArray())
        val result = BootManager.boot(fs, cpu, pm)
        assertTrue(result is BootManager.BootResult.Success)
        result as BootManager.BootResult.Success
        assertTrue(result.log.contains("[BOOT] from boot"))
        assertTrue(result.log.none { it.contains("from post") })
    }

    @Test
    fun cmpJzAndLabelsDriveConditionalFlow() {
        val (fs, cpu, pm) = freshEnv()
        fs.write(
            BootManager.BOOT_PATH,
            """
            PUSH 1
            PUSH 1
            CMP
            JZ equal
            PRINT "not equal"
            HALT
            equal:
            PRINT "equal"
            HALT
            """.trimIndent().toByteArray()
        )
        val result = BootManager.boot(fs, cpu, pm)
        assertTrue(result is BootManager.BootResult.Success)
        result as BootManager.BootResult.Success
        assertTrue(result.log.contains("[BOOT] equal"))
        assertTrue(result.log.none { it.contains("not equal") })
    }

    @Test
    fun checkFailureHaltsBoot() {
        val (fs, cpu, pm) = freshEnv()
        fs.write(
            BootManager.BOOT_PATH,
            """
            PUSH 0
            CHECK
            PRINT "unreachable"
            HALT
            """.trimIndent().toByteArray()
        )
        val result = BootManager.boot(fs, cpu, pm)
        assertTrue(result is BootManager.BootResult.Failed)
        result as BootManager.BootResult.Failed
        assertTrue(result.log.any { it.contains("CHECK failed") })
    }
}

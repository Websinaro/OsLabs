package com.oslab.simulator.simulator

import com.oslab.simulator.simulator.cpu.VirtualAssembly
import com.oslab.simulator.simulator.cpu.VirtualCpu
import com.oslab.simulator.simulator.filesystem.VirtualFileSystem
import com.oslab.simulator.simulator.memory.VirtualRam
import com.oslab.simulator.simulator.process.VirtualProcessManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualMachineCoreTest {
    @Test
    fun arithmeticProgramRunsOnlyInVirtualFilesystem() {
        val fs = VirtualFileSystem(VirtualRam())
        val pm = VirtualProcessManager()
        val source = """
            PUSH 6
            PUSH 7
            MUL
            WRITE data/result.txt
            EXIT
        """.trimIndent()
        val parsed = VirtualAssembly.parse(source) as VirtualAssembly.ParseResult.Ok
        val result = VirtualCpu().execute(parsed.instructions, fs, pm) as VirtualCpu.ExecutionResult.Completed
        assertEquals("42", fs.read("data/result.txt")?.decodeToString())
        assertTrue(result.steps <= 10)
    }

    @Test
    fun invalidBranchIsRejectedBeforeExecution() {
        val parsed = VirtualAssembly.parse("JMP 99\nEXIT")
        assertTrue(parsed is VirtualAssembly.ParseResult.Error)
    }

    @Test
    fun schedulerAdvancesVirtualProcess() {
        val pm = VirtualProcessManager()
        val pid = pm.create("demo")!!
        val message = pm.tick()
        assertTrue(message.contains("pid"))
        assertTrue(pm.list().any { it.startsWith("$pid\tdemo") })
    }
}

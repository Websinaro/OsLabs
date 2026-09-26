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
    fun printLiteralAndVariableAreRoutedToOutput() {
        val fs = VirtualFileSystem(VirtualRam())
        val pm = VirtualProcessManager()
        val source = """
            PRINT "hello kernel"
            PUSH 42
            STORE total
            PRINT total
            EXIT
        """.trimIndent()
        val parsed = VirtualAssembly.parse(source) as VirtualAssembly.ParseResult.Ok
        val result = VirtualCpu().execute(parsed.instructions, fs, pm) as VirtualCpu.ExecutionResult.Completed
        assertEquals(listOf("hello kernel", "42"), result.output)
    }

    @Test
    fun cmpJnzAndLabelsBranchOnInequality() {
        val fs = VirtualFileSystem(VirtualRam())
        val pm = VirtualProcessManager()
        val source = """
            PUSH 5
            PUSH 3
            CMP
            JNZ different
            PRINT "same"
            EXIT
            different:
            PRINT "different"
            EXIT
        """.trimIndent()
        val parsed = VirtualAssembly.parse(source) as VirtualAssembly.ParseResult.Ok
        val result = VirtualCpu().execute(parsed.instructions, fs, pm) as VirtualCpu.ExecutionResult.Completed
        assertEquals(listOf("different"), result.output)
    }

    @Test
    fun checkPassesOnTruthyStackValue() {
        val fs = VirtualFileSystem(VirtualRam())
        val pm = VirtualProcessManager()
        val source = """
            PUSH 1
            CHECK
            PRINT "passed"
            EXIT
        """.trimIndent()
        val parsed = VirtualAssembly.parse(source) as VirtualAssembly.ParseResult.Ok
        val result = VirtualCpu().execute(parsed.instructions, fs, pm) as VirtualCpu.ExecutionResult.Completed
        assertEquals(listOf("[CHECK] passed", "passed"), result.output)
    }

    @Test
    fun checkHaltsProgramOnFalsyStackValue() {
        val fs = VirtualFileSystem(VirtualRam())
        val pm = VirtualProcessManager()
        val source = """
            PUSH 0
            CHECK
            PRINT "unreachable"
            EXIT
        """.trimIndent()
        val parsed = VirtualAssembly.parse(source) as VirtualAssembly.ParseResult.Ok
        val result = VirtualCpu().execute(parsed.instructions, fs, pm) as VirtualCpu.ExecutionResult.Halted
        assertTrue(result.reason.contains("CHECK failed"))
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

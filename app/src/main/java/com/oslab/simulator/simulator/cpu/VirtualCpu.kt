package com.oslab.simulator.simulator.cpu

import com.oslab.simulator.security.ResourceLimits
import com.oslab.simulator.simulator.filesystem.VirtualFileSystem
import com.oslab.simulator.simulator.process.VirtualProcessManager

/**
 * The bounded instruction interpreter (Stage 7). Every run is capped on
 * three independent axes — instruction count, wall-clock time, and call
 * depth — so an uploaded program cannot hang or stack-overflow the host
 * Android app no matter what it contains; a runaway `JMP` loop simply hits
 * MAX_INSTRUCTIONS and halts with a reported reason instead of spinning
 * forever. This never touches a real thread, timer, or the Android main
 * loop directly — callers are expected to also wrap execution in
 * security.SandboxController.runContained as a second line of defense.
 */
class VirtualCpu {

    sealed class ExecutionResult {
        data class Halted(val output: List<String>, val reason: String) : ExecutionResult()
        data class Completed(val output: List<String>) : ExecutionResult()
    }

    fun summary(): String = "vCPU-1 (interpreted, single-core, bounded)"

    fun execute(
        program: List<Instruction>,
        fileSystem: VirtualFileSystem,
        processManager: VirtualProcessManager
    ): ExecutionResult {
        if (program.isEmpty()) return ExecutionResult.Completed(emptyList())

        val stack = ArrayDeque<Int>()
        val callStack = ArrayDeque<Int>()
        val vars = mutableMapOf<String, Int>()
        val output = mutableListOf<String>()

        var pc = 0
        var steps = 0
        val startTime = System.currentTimeMillis()

        while (pc in program.indices) {
            steps++
            if (steps > ResourceLimits.MAX_INSTRUCTIONS) {
                return ExecutionResult.Halted(output, "instruction limit exceeded (${ResourceLimits.MAX_INSTRUCTIONS})")
            }
            if (System.currentTimeMillis() - startTime > ResourceLimits.MAX_EXECUTION_MILLIS) {
                return ExecutionResult.Halted(output, "execution time limit exceeded (${ResourceLimits.MAX_EXECUTION_MILLIS}ms)")
            }

            when (val instr = program[pc]) {
                is Instruction.Push -> {
                    stack.addLast(instr.value)
                    pc++
                }
                is Instruction.Load -> {
                    stack.addLast(vars[instr.name] ?: 0)
                    pc++
                }
                is Instruction.Store -> {
                    val v = stack.removeLastOrNull() ?: return ExecutionResult.Halted(output, "STORE on empty stack")
                    vars[instr.name] = v
                    pc++
                }
                Instruction.Add -> {
                    val b = stack.removeLastOrNull()
                    val a = stack.removeLastOrNull()
                    if (a == null || b == null) return ExecutionResult.Halted(output, "ADD on empty stack")
                    stack.addLast(a + b)
                    pc++
                }
                Instruction.Sub -> {
                    val b = stack.removeLastOrNull()
                    val a = stack.removeLastOrNull()
                    if (a == null || b == null) return ExecutionResult.Halted(output, "SUB on empty stack")
                    stack.addLast(a - b)
                    pc++
                }
                is Instruction.Jmp -> pc = instr.target
                is Instruction.Jz -> {
                    val v = stack.removeLastOrNull() ?: return ExecutionResult.Halted(output, "JZ on empty stack")
                    pc = if (v == 0) instr.target else pc + 1
                }
                is Instruction.Call -> {
                    if (callStack.size >= ResourceLimits.MAX_RECURSION_DEPTH) {
                        return ExecutionResult.Halted(output, "recursion limit exceeded (${ResourceLimits.MAX_RECURSION_DEPTH})")
                    }
                    callStack.addLast(pc + 1)
                    pc = instr.target
                }
                Instruction.Return -> {
                    pc = callStack.removeLastOrNull() ?: return ExecutionResult.Halted(output, "RETURN with empty call stack")
                }
                is Instruction.Read -> {
                    stack.addLast(if (fileSystem.exists(instr.path)) 1 else 0)
                    pc++
                }
                is Instruction.Write -> {
                    val v = stack.removeLastOrNull() ?: return ExecutionResult.Halted(output, "WRITE on empty stack")
                    fileSystem.write(instr.path, v.toString().toByteArray())
                    output.add("WRITE ${instr.path} <- $v")
                    pc++
                }
                is Instruction.CreateProcess -> {
                    val pid = processManager.create(instr.name)
                    stack.addLast(pid ?: -1)
                    output.add(if (pid != null) "CREATE_PROCESS ${instr.name} -> pid $pid" else "CREATE_PROCESS ${instr.name} -> failed (process table full)")
                    pc++
                }
                Instruction.Exit -> return ExecutionResult.Completed(output)
            }
        }
        return ExecutionResult.Completed(output)
    }
}

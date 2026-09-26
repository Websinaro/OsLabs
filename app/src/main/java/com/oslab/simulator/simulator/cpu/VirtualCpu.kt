package com.oslab.simulator.simulator.cpu

import com.oslab.simulator.security.ResourceLimits
import com.oslab.simulator.simulator.filesystem.VirtualFileSystem
import com.oslab.simulator.simulator.process.VirtualProcessManager

/** Deterministic, bounded virtual CPU. It executes only Instruction values. */
class VirtualCpu {
    data class Registers(
        var pc: Int = 0,
        var sp: Int = 0,
        var r0: Int = 0,
        var r1: Int = 0,
        var r2: Int = 0,
        var r3: Int = 0,
        var zero: Boolean = false,
        var halted: Boolean = false
    )

    sealed class ExecutionResult {
        data class Halted(val output: List<String>, val reason: String, val steps: Int, val registers: Registers) : ExecutionResult()
        data class Completed(val output: List<String>, val steps: Int, val registers: Registers) : ExecutionResult()
    }

    private var last = Registers()
    fun summary(): String = "vCPU-1 (interpreted, single-core, bounded)"
    fun registers(): Registers = last.copy()

    fun registerSummary(): List<String> = listOf(
        "Architecture: vCPU-1",
        "PC: 0x%04X".format(last.pc),
        "SP: 0x%04X".format(last.sp),
        "R0: ${last.r0}",
        "R1: ${last.r1}",
        "R2: ${last.r2}",
        "R3: ${last.r3}",
        "ZERO: ${last.zero}",
        "State: ${if (last.halted) "HALTED" else "READY"}"
    )

    fun execute(program: List<Instruction>, fileSystem: VirtualFileSystem, processManager: VirtualProcessManager): ExecutionResult {
        if (program.isEmpty()) return ExecutionResult.Completed(emptyList(), 0, last.copy())
        val stack = mutableListOf<Int>()
        val callStack = mutableListOf<Int>()
        val vars = mutableMapOf<String, Int>()
        val output = mutableListOf<String>()
        val regs = Registers(sp = ResourceLimits.VIRTUAL_STACK_BASE)
        var steps = 0
        val startTime = System.currentTimeMillis()

        fun halted(reason: String) = ExecutionResult.Halted(output.toList(), reason, steps, regs.copy())
        fun pop(): Int? = if (stack.isEmpty()) null else stack.removeAt(stack.lastIndex)
        fun binary(name: String, op: (Int, Int) -> Int): String? {
            val b = pop() ?: return "$name on empty stack"
            val a = pop() ?: return "$name on empty stack"
            val result = op(a, b)
            stack.add(result)
            regs.zero = (result == 0)
            regs.sp = ResourceLimits.VIRTUAL_STACK_BASE - stack.size
            return null
        }

        while (regs.pc in program.indices) {
            if (++steps > ResourceLimits.MAX_INSTRUCTIONS) return halted("instruction limit exceeded (${ResourceLimits.MAX_INSTRUCTIONS})")
            if (System.currentTimeMillis() - startTime > ResourceLimits.MAX_EXECUTION_MILLIS) return halted("execution time limit exceeded (${ResourceLimits.MAX_EXECUTION_MILLIS}ms)")
            val instr = program[regs.pc]
            when (instr) {
                is Instruction.Push -> { stack.add(instr.value); regs.pc++ }
                is Instruction.Load -> { stack.add(vars[instr.name] ?: 0); regs.pc++ }
                is Instruction.Store -> { val v = pop() ?: return halted("STORE on empty stack"); vars[instr.name] = v; regs.r0 = v; regs.pc++ }
                Instruction.Add -> { binary("ADD") { a, b -> a + b }?.let { return halted(it) }; regs.pc++ }
                Instruction.Sub -> { binary("SUB") { a, b -> a - b }?.let { return halted(it) }; regs.pc++ }
                Instruction.Mul -> { binary("MUL") { a, b -> a * b }?.let { return halted(it) }; regs.pc++ }
                Instruction.Div -> {
                    val b = pop() ?: return halted("DIV on empty stack")
                    val a = pop() ?: return halted("DIV on empty stack")
                    if (b == 0) return halted("division by zero")
                    stack.add(a / b); regs.zero = (a / b == 0); regs.pc++
                }
                is Instruction.Jmp -> regs.pc = instr.target
                is Instruction.Jz -> regs.pc = if (regs.zero) instr.target else regs.pc + 1
                is Instruction.Jnz -> regs.pc = if (!regs.zero) instr.target else regs.pc + 1
                is Instruction.Call -> { if (callStack.size >= ResourceLimits.MAX_RECURSION_DEPTH) return halted("recursion limit exceeded (${ResourceLimits.MAX_RECURSION_DEPTH})"); callStack.add(regs.pc + 1); regs.pc = instr.target }
                Instruction.Return -> {
                    if (callStack.isEmpty()) return halted("RETURN with empty call stack")
                    regs.pc = callStack.removeAt(callStack.lastIndex)
                }
                is Instruction.Read -> { stack.add(if (fileSystem.exists(instr.path)) 1 else 0); regs.pc++ }
                is Instruction.Write -> { val v = pop() ?: return halted("WRITE on empty stack"); if (!fileSystem.write(instr.path, v.toString().toByteArray())) return halted("WRITE failed: virtual storage limit reached or invalid path"); output.add("WRITE ${instr.path} <- $v"); regs.pc++ }
                is Instruction.CreateProcess -> { val pid = processManager.create(instr.name); stack.add(pid ?: -1); output.add(if (pid != null) "CREATE_PROCESS ${instr.name} -> pid $pid" else "CREATE_PROCESS ${instr.name} -> failed"); regs.pc++ }
                is Instruction.Print -> {
                    val text = instr.literal ?: when (instr.varName?.uppercase()) {
                        "R0" -> regs.r0.toString()
                        "R1" -> regs.r1.toString()
                        "R2" -> regs.r2.toString()
                        "R3" -> regs.r3.toString()
                        else -> (vars[instr.varName] ?: 0).toString()
                    }
                    output.add(text)
                    regs.pc++
                }
                Instruction.Exit -> { regs.halted = true; last = regs.copy(); return ExecutionResult.Completed(output, steps, regs.copy()) }
                Instruction.Cmp -> {
                    val b = pop() ?: return halted("CMP on empty stack")
                    val a = pop() ?: return halted("CMP on empty stack")
                    regs.zero = (a == b)
                    regs.pc++
                }
                Instruction.Check -> {
                    val v = pop() ?: return halted("CHECK on empty stack")
                    if (v == 0) return halted("CHECK failed")
                    output.add("[CHECK] passed")
                    regs.pc++
                }
            }
            regs.sp = ResourceLimits.VIRTUAL_STACK_BASE - stack.size
        }
        regs.halted = true
        last = regs.copy()
        return ExecutionResult.Completed(output, steps, regs.copy())
    }
}

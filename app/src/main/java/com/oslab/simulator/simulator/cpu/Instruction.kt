package com.oslab.simulator.simulator.cpu

/** The only instruction set executable by uploaded virtual programs. */
sealed class Instruction {
    data class Push(val value: Int) : Instruction()
    data class Load(val name: String) : Instruction()
    data class Store(val name: String) : Instruction()
    object Add : Instruction()
    object Sub : Instruction()
    object Mul : Instruction()
    object Div : Instruction()
    data class Jmp(val target: Int) : Instruction()
    /** Branches on the CPU's `zero` flag (set by CMP, or by ADD/SUB/MUL/DIV's result) — does not touch the stack. */
    data class Jz(val target: Int) : Instruction()
    /** Branches when the `zero` flag is false. Pairs with CMP the same way JZ does. */
    data class Jnz(val target: Int) : Instruction()
    data class Call(val target: Int) : Instruction()
    object Return : Instruction()
    data class Read(val path: String) : Instruction()
    data class Write(val path: String) : Instruction()
    data class CreateProcess(val name: String) : Instruction()
    /** literal is set for `PRINT "text"`, varName is set for `PRINT name` / `PRINT R0`. */
    data class Print(val literal: String?, val varName: String?) : Instruction()
    /** Pops b then a, sets the `zero` flag to (a == b). Leaves nothing on the stack. */
    object Cmp : Instruction()
    /** Pops a condition value; halts the program if it is 0, otherwise continues. An assertion, e.g. for a boot-time security check. */
    object Check : Instruction()
    object Exit : Instruction()
}

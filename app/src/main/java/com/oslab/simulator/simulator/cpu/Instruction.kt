package com.oslab.simulator.simulator.cpu

/**
 * The virtual instruction set. This — and only this — is what uploaded
 * "code" ever compiles down to and what VirtualCpu ever executes. There is
 * no path from an Instruction to a JVM classfile, a native call, or
 * reflection: the interpreter below is a plain switch over this sealed
 * class operating on its own stack and variable map.
 */
sealed class Instruction {
    data class Push(val value: Int) : Instruction()
    data class Load(val name: String) : Instruction()
    data class Store(val name: String) : Instruction()
    object Add : Instruction()
    object Sub : Instruction()
    data class Jmp(val target: Int) : Instruction()
    /** Jumps to [target] only if the top of stack is zero (pops it either way). */
    data class Jz(val target: Int) : Instruction()
    data class Call(val target: Int) : Instruction()
    object Return : Instruction()
    /** Pushes 1 if the virtual file at [path] exists, else 0. */
    data class Read(val path: String) : Instruction()
    /** Pops a value and writes it (as decimal text) to the virtual file at [path]. */
    data class Write(val path: String) : Instruction()
    /** Creates a virtual process named [name]; pushes its pid, or -1 if the table is full. */
    data class CreateProcess(val name: String) : Instruction()
    object Exit : Instruction()
}

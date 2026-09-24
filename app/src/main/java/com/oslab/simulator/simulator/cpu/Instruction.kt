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
    data class Jz(val target: Int) : Instruction()
    data class Call(val target: Int) : Instruction()
    object Return : Instruction()
    data class Read(val path: String) : Instruction()
    data class Write(val path: String) : Instruction()
    data class CreateProcess(val name: String) : Instruction()
    object Exit : Instruction()
}

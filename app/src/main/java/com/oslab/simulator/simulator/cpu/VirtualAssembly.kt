package com.oslab.simulator.simulator.cpu

/**
 * A minimal two-pass assembler for the instruction set in Instruction.kt.
 * This is the only thing "source" from an update package is ever turned
 * into — a flat list of Instruction values. There is no eval(), no
 * dynamic classloading, and no reflection anywhere in this path.
 *
 * Syntax (one instruction per line, '#' starts a comment, blank lines OK):
 *   label:
 *   PUSH <int>
 *   LOAD <name>
 *   STORE <name>
 *   ADD
 *   SUB
 *   JMP <label>
 *   JZ <label>
 *   CALL <label>
 *   RETURN
 *   READ <virtual/path>
 *   WRITE <virtual/path>
 *   CREATE_PROCESS <name>
 *   EXIT
 */
object VirtualAssembly {

    sealed class ParseResult {
        data class Ok(val instructions: List<Instruction>) : ParseResult()
        data class Error(val message: String) : ParseResult()
    }

    fun parse(source: String): ParseResult {
        val rawLines = source.lines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }

        // Pass 1: strip label declarations, recording their target index in
        // the *instruction* stream (not the raw line stream).
        val labels = mutableMapOf<String, Int>()
        val opLines = mutableListOf<String>()
        for (line in rawLines) {
            if (line.endsWith(":") && !line.contains(" ")) {
                labels[line.dropLast(1)] = opLines.size
            } else {
                opLines.add(line)
            }
        }

        fun resolve(token: String): Int? =
            labels[token] ?: token.toIntOrNull()

        val instructions = mutableListOf<Instruction>()
        for ((index, line) in opLines.withIndex()) {
            val parts = line.split(Regex("\\s+"), limit = 2)
            val op = parts[0].uppercase()
            val arg = parts.getOrNull(1)

            val instruction: Instruction = when (op) {
                "PUSH" -> Instruction.Push(
                    arg?.toIntOrNull() ?: return ParseResult.Error("line ${index + 1}: PUSH needs an integer")
                )
                "LOAD" -> Instruction.Load(arg ?: return ParseResult.Error("line ${index + 1}: LOAD needs a name"))
                "STORE" -> Instruction.Store(arg ?: return ParseResult.Error("line ${index + 1}: STORE needs a name"))
                "ADD" -> Instruction.Add
                "SUB" -> Instruction.Sub
                "JMP" -> Instruction.Jmp(
                    resolve(arg ?: "") ?: return ParseResult.Error("line ${index + 1}: unknown JMP target '$arg'")
                )
                "JZ" -> Instruction.Jz(
                    resolve(arg ?: "") ?: return ParseResult.Error("line ${index + 1}: unknown JZ target '$arg'")
                )
                "CALL" -> Instruction.Call(
                    resolve(arg ?: "") ?: return ParseResult.Error("line ${index + 1}: unknown CALL target '$arg'")
                )
                "RETURN" -> Instruction.Return
                "READ" -> Instruction.Read(arg ?: return ParseResult.Error("line ${index + 1}: READ needs a path"))
                "WRITE" -> Instruction.Write(arg ?: return ParseResult.Error("line ${index + 1}: WRITE needs a path"))
                "CREATE_PROCESS" -> Instruction.CreateProcess(
                    arg ?: return ParseResult.Error("line ${index + 1}: CREATE_PROCESS needs a name")
                )
                "EXIT" -> Instruction.Exit
                else -> return ParseResult.Error("line ${index + 1}: unknown instruction '$op'")
            }
            instructions.add(instruction)
        }
        return ParseResult.Ok(instructions)
    }
}

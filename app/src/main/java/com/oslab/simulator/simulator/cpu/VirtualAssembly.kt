package com.oslab.simulator.simulator.cpu

/** Two-pass assembler for the safe vCPU instruction set. */
object VirtualAssembly {
    sealed class ParseResult {
        data class Ok(val instructions: List<Instruction>) : ParseResult()
        data class Error(val message: String) : ParseResult()
    }

    fun parse(source: String): ParseResult {
        val rawLines = source.lines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
        val labels = mutableMapOf<String, Int>()
        val opLines = mutableListOf<String>()

        for (line in rawLines) {
            if (line.endsWith(":") && !line.contains(" ")) {
                val label = line.dropLast(1)
                if (label.isBlank() || labels.containsKey(label)) return ParseResult.Error("duplicate/invalid label '$label'")
                labels[label] = opLines.size
            } else opLines.add(line)
        }

        fun resolve(token: String): Int? = labels[token] ?: token.toIntOrNull()
        fun pathArg(arg: String?, line: Int, op: String): String? {
            val value = arg?.trim()
            return if (value.isNullOrEmpty() || value.contains('\\') || value.startsWith('/') || value.contains("..")) {
                null
            } else value
        }

        val instructions = mutableListOf<Instruction>()
        for ((index, line) in opLines.withIndex()) {
            val parts = line.split(Regex("\\s+"), limit = 2)
            val op = parts[0].uppercase()
            val arg = parts.getOrNull(1)?.trim()
            val instruction = when (op) {
                "PUSH" -> Instruction.Push(arg?.toIntOrNull() ?: return ParseResult.Error("line ${index + 1}: PUSH needs an integer"))
                "LOAD" -> Instruction.Load(arg ?: return ParseResult.Error("line ${index + 1}: LOAD needs a name"))
                "STORE" -> Instruction.Store(arg ?: return ParseResult.Error("line ${index + 1}: STORE needs a name"))
                "ADD" -> Instruction.Add
                "SUB" -> Instruction.Sub
                "MUL" -> Instruction.Mul
                "DIV" -> Instruction.Div
                "JMP" -> Instruction.Jmp(resolve(arg ?: "") ?: return ParseResult.Error("line ${index + 1}: unknown JMP target '$arg'"))
                "JZ" -> Instruction.Jz(resolve(arg ?: "") ?: return ParseResult.Error("line ${index + 1}: unknown JZ target '$arg'"))
                "JNZ" -> Instruction.Jnz(resolve(arg ?: "") ?: return ParseResult.Error("line ${index + 1}: unknown JNZ target '$arg'"))
                "CALL" -> Instruction.Call(resolve(arg ?: "") ?: return ParseResult.Error("line ${index + 1}: unknown CALL target '$arg'"))
                "RETURN" -> Instruction.Return
                "READ" -> Instruction.Read(pathArg(arg, index + 1, op) ?: return ParseResult.Error("line ${index + 1}: invalid READ path"))
                "WRITE" -> Instruction.Write(pathArg(arg, index + 1, op) ?: return ParseResult.Error("line ${index + 1}: invalid WRITE path"))
                "CREATE_PROCESS" -> Instruction.CreateProcess(arg ?: return ParseResult.Error("line ${index + 1}: CREATE_PROCESS needs a name"))
                "PRINT" -> {
                    val raw = arg ?: return ParseResult.Error("line ${index + 1}: PRINT needs a string or a name")
                    if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
                        Instruction.Print(literal = raw.substring(1, raw.length - 1), varName = null)
                    } else {
                        Instruction.Print(literal = null, varName = raw)
                    }
                }
                "EXIT", "HALT" -> Instruction.Exit
                "CMP" -> Instruction.Cmp
                "CHECK" -> Instruction.Check
                else -> return ParseResult.Error("line ${index + 1}: unknown instruction '$op'")
            }
            instructions.add(instruction)
        }

        // Validate branch targets before the program can reach the interpreter.
        for ((i, instruction) in instructions.withIndex()) {
            val target = when (instruction) {
                is Instruction.Jmp -> instruction.target
                is Instruction.Jz -> instruction.target
                is Instruction.Jnz -> instruction.target
                is Instruction.Call -> instruction.target
                else -> null
            }
            if (target != null && target !in instructions.indices) {
                return ParseResult.Error("instruction ${i + 1}: branch target $target is outside program")
            }
        }
        return ParseResult.Ok(instructions)
    }
}

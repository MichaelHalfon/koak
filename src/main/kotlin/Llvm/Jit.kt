package Llvm

import org.bytedeco.javacpp.*
import org.bytedeco.javacpp.LLVM.*
import org.bytedeco.javacpp.annotation.Cast
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

fun String.runCommand(workingDir: File): String? {
    try {
        val parts = this.split("\\s".toRegex())
        val proc = ProcessBuilder(*parts.toTypedArray())
                .directory(workingDir)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

        proc.waitFor(60, TimeUnit.MINUTES)
        return proc.inputStream.bufferedReader().readText()
    } catch(e: IOException) {
        e.printStackTrace()
        return null
    }
}

class Jit constructor(val module: Ir.Module, val optLevel: Int = 2, verbose: Boolean = false)
{
    val typeNames : MutableMap<LLVMTypeRef, String> = mutableMapOf()

    var value = "0"
    val error: BytePointer = BytePointer(null as Pointer?)
    val filename = module.identifier
    val engine = LLVMExecutionEngineRef()
    // Only for optimization !

    val targetTriple = LLVMGetDefaultTargetTriple()
    val machineRef = LLVMTargetRef()
    val modulePass = LLVMCreatePassManager()


    init {
        val targetIndex = LLVMGetTargetFromTriple(targetTriple, machineRef, error)
        val myMachine = LLVMCreateTargetMachine(machineRef, targetTriple.string, "generic", "", 0, 0, 0)
        val machineDataLayout = LLVMCreateTargetDataLayout(myMachine)
        LLVMSetModuleDataLayout(module._modLlvm, machineDataLayout)
        var bp = BytePointer(filename + ".s")
        LLVMTargetMachineEmitToFile(myMachine, module._modLlvm, bp, 0, error)
        bp = BytePointer(filename + ".o")
        LLVMTargetMachineEmitToFile(myMachine, module._modLlvm, bp, 1, error)
        var verboseAsString : String = ""
        if (verbose)
            verboseAsString = "--verbose"

        // val llvmconfig = "`llvm-config --ldflags --system-libs --libs all`".runCommand(File("/usr/bin/"))
        val llvmconfig = "`llvm-config --ldflags --system-libs --libs all`"
        val command = "/usr/bin/ld.lld"
        val args =
                " -melf_x86_64" +
                " -o $filename.ravioliravioligivemetheexecutableideservioli " +
                " -dynamic-linker /lib64/ld-linux-x86-64.so.2 /usr/lib/crt1.o /usr/lib/crti.o " +
                " -lc $filename.o /usr/lib/crtn.o " +
                llvmconfig +
                verboseAsString

        val process = ProcessBuilder(command, args).start()

//        println(command)
////        println(Runtime.getRuntime().exec(command))
//        val res = command.runCommand(File("/usr/bin"))
//        if (res != null)
//            println(res)

        if (targetIndex != 0)
            throw Exception(error.string)

        LLVMInitializeAllTargetInfos();
        LLVMInitializeAllTargets();
        LLVMInitializeAllTargetMCs();
        LLVMInitializeAllAsmParsers();
        LLVMInitializeAllAsmPrinters();

        typeNames[LLVMInt32Type()] = "int32"
        typeNames[LLVMInt64Type()] = "int64"
        typeNames[LLVMDoubleType()] = "double"

        println("Creating JIT Engine for Module ${module.identifier}")
        for (f in module.functions)
            println("Containing ${f.key} : ${typeNames[f.value.type]}")

        if (LLVMCreateJITCompilerForModule(engine, module._modLlvm, optLevel, error) != 0) {
            System.err.println(error.string)
            LLVMDisposeMessage(error)
            System.exit(-1)
        }

        LLVMAddConstantPropagationPass(modulePass)
        LLVMAddInstructionCombiningPass(modulePass)
        LLVMAddPromoteMemoryToRegisterPass(modulePass)
        LLVMAddDemoteMemoryToRegisterPass(modulePass); // Demotes every possible value to memory
        LLVMAddGVNPass(modulePass)
        LLVMAddCFGSimplificationPass(modulePass)
        LLVMRunPassManager(modulePass, module._modLlvm)

        // Ready to execute module ?
    }

    class ExecResult constructor(val content: String)
    {
        var source = ""
    }

    // TODO: Il faut utiliser modulePass
    fun execute() : ExecResult
    {
        return ExecResult("Not Implemented Yet")
    }

    // TODO: il faudrait generer une pass fonction et l'utiliser ?
    fun runFunction(functionIdentifier: String, args : Array<Int>) : ExecResult
    {
        var target = functionIdentifier

        val exec_args = args.map { LLVMCreateGenericValueOfInt(LLVMInt32Type(), it.toLong(), 0) }.toTypedArray()
        val exec_res = LLVMRunFunction(engine, module.functions[target]!!._funLlvm, args.size, exec_args[0])
        val res = ExecResult(LLVMGenericValueToInt(exec_res, 0).toString())

        res.source = functionIdentifier
        return res
    }
}
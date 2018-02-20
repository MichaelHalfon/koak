package Llvm

import org.bytedeco.javacpp.*
import org.bytedeco.javacpp.LLVM.*

// TODO: Ne plus avoir un object parce que peut etre c'est mieux de le mettre dans le IR ?
object Builder
{
    val llvm = LLVMCreateBuilder()
}

// TODO: Reflechir a l'interet de IR a PART pour construire l'IR facilement
// Du coup ca pourrait etre un IrBuilder ?
// Du coup pas de notion de compile ou de jit, on jit des modules, pas des FICHIERS, enfin en fait je me rends compte que mono module ca le fait
class Ir
{
    val error: BytePointer = BytePointer(null as Pointer?)

    enum class Instructions
    {
        Condition,
        VarDec,
        VarSet,
        If,
        Else,
        Return
    }

    abstract class Instruction constructor(val type: Instructions)
    {

    }

    class Condition : Instruction(Instructions.Condition)
    {

    }

    // An instruction is an LLVM Block
    class Block constructor (val func: Function, val identifier : String)
    {
        val _blockLlvm : LLVMBasicBlockRef = LLVMAppendBasicBlock(func._funLlvm, identifier)
        val _content : MutableMap<String, LLVMValueRef> = mutableMapOf()

        lateinit var _cond : LLVMValueRef
        val factory : MutableMap<String, (String, Array<String>) -> Boolean> = mutableMapOf()

        private fun placeEditorAtMe()
        {
            LLVMPositionBuilderAtEnd(Builder.llvm, _blockLlvm)
        }

        init {
            factory["compare ints"] =
                    fun(identifier: String, args: Array<String>) : Boolean {
                        if (args.size < 3)
                            return false
                        var first : LLVMValueRef
                        var second : LLVMValueRef
                        if (func.getLocalVar(args[1]) != null)
                            first = func.getLocalVar(args[1])!!
                        else
                            first = LLVMConstInt(LLVMInt32Type(), args[1].toLong(), 0)
                        if (func.getLocalVar(args[2]) != null)
                            second = func.getLocalVar(args[2])!!
                        else
                            second = LLVMConstInt(LLVMInt32Type(), args[2].toLong(), 0)

                        placeEditorAtMe()
                        _content[identifier] = LLVMBuildICmp(Builder.llvm, LLVMIntEQ, first, second, identifier)
                        return true
                    }
            factory["call"] =
                    fun(identifier: String, args: Array<String>) : Boolean {
                        if (args.size < 2)
                            return false

                        // TODO: Find all arguments for the call

                        val call_args = args.filterIndexed({ i, s -> i > 1}).map {
                            func.search(it)
                        }.toTypedArray()

                        placeEditorAtMe()
                        _content[identifier] = LLVMBuildCall(Builder.llvm, func._funLlvm, PointerPointer(*call_args), call_args.size, identifier)
                        return true
                    }
            factory["jump"] =
                    fun(identifier: String, args: Array<String>) : Boolean {
                        if (args.size < 2)
                            return false
                        placeEditorAtMe()
                        _content[identifier] = LLVMBuildBr(Builder.llvm, func.findBlock(args[1])._blockLlvm)
                        return true
                    }
            factory["conditional jump"] =
                    fun(identifier: String, args: Array<String>) : Boolean {
                        if (args.size < 4)
                            return false
                        val conditionalValue = _content[args[1]]

                        placeEditorAtMe()
                        _content[identifier] = LLVMBuildCondBr(Builder.llvm, conditionalValue, func.findBlock(args[2])._blockLlvm, func.findBlock(args[3])._blockLlvm)
                        return true
                    }
            factory["binop"] =
                    fun(identifier: String, args: Array<String>) : Boolean {
                        if (args.size < 4)
                            return false
                        placeEditorAtMe()
                        // TODO: Add other operations
                        if (args[1].compareTo("*") == 0)
                            _content[identifier] = LLVMBuildMul(Builder.llvm,
                                    func.getLocalVar(args[2])?.let { it } ?: func.search(args[2]),
                                    func.getLocalVar(args[3])?.let { it } ?: func.search(args[3]),
                                    identifier)
                        else if (args[1].compareTo("+") == 0)
                            _content[identifier] = LLVMBuildAdd(Builder.llvm,
                                    func.getLocalVar(args[2])?.let { it } ?: func.search(args[2]),
                                    func.getLocalVar(args[3])?.let { it } ?: func.search(args[3]),
                                    identifier)
                        return true
                    }
            factory["return"] =
                    fun(identifier: String, args: Array<String>) : Boolean {
                        if (args.size < 2)
                            return false
                        _content[identifier] = LLVMBuildRet(Builder.llvm, func.search(args[1]))
                        return true
                    }
            factory["phi int"] = fun(identifier: String, args: Array<String>) : Boolean {
                if (args.size < 5)
                    return false

                placeEditorAtMe()
                val res = LLVMBuildPhi(Builder.llvm, LLVMInt32Type(), identifier)
                val phi_blocks = args
                        .filterIndexed({ i, s -> i % 2 == 1 && i > 0})
                        .map { s -> func.findBlock(s)._blockLlvm }
                        .toTypedArray()
                val phi_vals = args
                        .filterIndexed({ i, s -> i % 2 == 0 && i > 0})
                        .map { s -> func.getLocalVar(s)?.let { it } ?: func.search(s)!! } // il faut aller chercher dans les contents de toute la fonction
                        .toTypedArray()
                _content[identifier] = res
                LLVMAddIncoming(res, PointerPointer(*phi_vals), PointerPointer(*phi_blocks), phi_blocks.size)
                return true
            }
        }

        fun append(identifier: String, args: Array<String>) : Boolean {
            if (args.size == 0)
                return false
            val ret = factory[args[0]]?.invoke(identifier, args)
            if (ret == null)
                return false
            return ret
        }
    }

    class Function constructor(val module: Module,
                               val type: LLVMTypeRef, val identifier: String, argTypes: Array<LLVMTypeRef>)
    {
        val _funLlvm : LLVMValueRef = LLVMAddFunction(module._modLlvm, identifier, LLVMFunctionType(type, argTypes[0], argTypes.size, 0))

        var Blocks : MutableMap<String, Block> = mutableMapOf()
        init {
            LLVMSetFunctionCallConv(_funLlvm, LLVMCCallConv)
        }

        fun addBlocks(vararg args: String)
        {
            for (arg in args) {
                addBlock(arg)
            }
        }
        fun createConstInt(value: String) : LLVMValueRef {
            val ref = LLVMConstInt(LLVMInt32Type(), value.toLong(), 0)
            _local[value] = ref
            return ref
        }

        fun findBlock(identifier: String) : Block
        {
            return Blocks[identifier]!!
        }

        fun addBlock(identifier: String) : Block
        {
            val i = Block(this, identifier)
            Blocks[i.identifier] = i
            return i
        }

        var _local : MutableMap<String, LLVMValueRef> = mutableMapOf()
        fun declareParamVar(identifier: String, index: Int) : LLVMValueRef {
            _local[identifier] = LLVMGetParam(_funLlvm, index)
            return _local[identifier]!!
        }
        fun getLocalVar(identifier: String) : LLVMValueRef?
        {
            return _local[identifier]
        }
        // search for instruction in any blocks of the function
        fun search(identifier: String, searchInLocal: Boolean = true) : LLVMValueRef?
        {
            for (block in Blocks) {
                for (inst in block.value._content)
                    if (inst.key == identifier) {
                        return inst.value
                    }
            }
            if (searchInLocal) {
                for (v in _local)
                    if (v.key == identifier)
                        return v.value
            }
            return null
        }
    }

    operator fun Function.get(key: String): Block? {
        return Blocks[key]
    }

    class Module constructor(val identifier: String)
    {
        lateinit var main : String
        val _modLlvm = LLVMModuleCreateWithName(identifier)

        var functions : MutableMap<String, Function> = mutableMapOf()
        fun addFunction(type: LLVMTypeRef,
                        identifier: String, argTypes: Array<LLVMTypeRef>) : Function
        {
            val f = Function(this, type, identifier, argTypes)
            functions[f.identifier] = f
            return f
        }

        fun jit(): Jit
        {
            val jit = Jit(this)
            return jit
        }

        fun setMain(identifier: String) : Boolean
        {
            if (functions.containsKey(identifier)) {
                main = identifier
                return true
            }
            return false
        }

        fun prettyPrint()
        {
            var str = LLVMPrintModuleToString(_modLlvm).string
            str = str.replace("icmp eq i32", "Are Ints Equal")
                    .replace("br i1", "Conditional Jump")
                    .replace("br", "Jump")
                    .replace("call", "Function Call")
                    .replace("mul i32", "Multiply Ints")
                    .replace("i32", "int")
                    .replace("phi int", "Conditional Value")
                    .replace("ret int", "Return Int")
                    .replace("; preds = ", "Incoming From ")
                    .replace("define", "fun")
                    .replace("; ModuleID = ", "Current Module : ")
            println(str)
        }

        fun print()
        {
            val str = LLVMPrintModuleToString(_modLlvm).string
            println(str)
        }
    }

    var modules : MutableMap<String, Module> = mutableMapOf()

    fun createModule(identifier: String) : Module {
        val m = Module(identifier)
        modules[m.identifier] = m
        return m
    }

    fun jit(module: String = "") : MutableList<Jit>
    {
        val list : MutableList<Jit> = mutableListOf()
        for (mod in modules) {
            if (module != "") {
                val res = mod.value.jit()
                println("JIT Of ${mod.value.identifier} is :\n$res")
                list.add(res)
            } else {
                if (module == mod.key) {
                    val res = mod.value.jit()
                    println("JIT Of ${mod.value.identifier} is :\n$res")
                    list.add(res)
                }
            }
        }
        return list
    }

    fun compile(dest: String)
    {
        val jits = jit()
        for (jit in jits) {
            
        }
    }

    fun print()
    {
        for (module in modules) {
            module.value.print()
        }
    }

    fun verify()
    {
        System.err.println("Verify modules...")
        for (module in modules) {
            LLVMVerifyModule(module.value._modLlvm, LLVMAbortProcessAction, error)
        }
        LLVMDisposeMessage(error) // Handler == LLVMAbortProcessAction -> No need to check errors
        System.err.println("Modules verified.")
    }
}
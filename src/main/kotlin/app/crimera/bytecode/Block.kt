package app.crimera.bytecode

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Format
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction12x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21ih
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21lh
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21s
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22b
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction23x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31i
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction32x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction51l
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import com.android.tools.smali.dexlib2.util.ReferenceUtil
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/*
 * morphe-bytecode: typed Dalvik bytecode emission for Morphe patches.
 *
 * The emitter vocabulary (move/iget/iput/sget/sput/moveResult, insertHook) and the
 * type-driven opcode selection follow the Reseam patch API, GPL-3.0-or-later,
 * (C) 2026 AunAli K. <hello@auna.li>. This implementation targets Morphe's dexlib2
 * pipeline instead of Reseam's native engine. See NOTICE.
 */

/**
 * A branch destination inside a [Block].
 *
 * [Original] is the instruction the hook was inserted in front of. After insertion it sits
 * directly after the block, so every path that reached the original instruction runs the
 * block first.
 */
sealed interface Target {
    /** A label declared with [Block.label] inside the block. */
    data class Local(val label: String) : Target

    /** The original instruction at the hook index. */
    data object Original : Target

    /**
     * The instruction [offset] instructions after [Original]. Lets a hook skip over the original
     * instruction (and any result handling attached to it) instead of only falling into it.
     */
    data class AfterOriginal(val offset: Int) : Target {
        init {
            require(offset >= 0) { "Target.AfterOriginal offset must not be negative: $offset" }
        }
    }
}

/** True when [this] forms one ascending register run, which `invoke-kind/range` encodes directly. */
private fun List<Int>.isConsecutive(): Boolean =
    indices.all { position -> position == 0 || this[position] == this[position - 1] + 1 }

/** Smali method descriptor for diagnostics; `MethodReference` itself exposes only its parts. */
internal fun MethodReference.methodDescriptor(): String = ReferenceUtil.getMethodDescriptor(this)

/**
 * Typed Dalvik emission for injected hooks.
 *
 * Emitters pick the encoding from the operand types instead of from a hand written smali
 * string: `move` selects `move`/`move-object`/`move-wide` and their `/from16`/`/16`
 * forms, `iget` selects the field-type variant, `constInt` selects the smallest
 * constant encoding, and `invoke*` lowers itself to `invoke-kind/range` when a call has
 * more than five register words or an operand above `v15`.
 *
 * Every emitted instruction is built through dexlib2's validated constructors, which enforce
 * the Dalvik operand limits per format, so a register that cannot be encoded (for example
 * `iget-object v22`, format `22c`) fails while the block is still being built.
 * [insertHook] rethrows that failure as a `PatchException` naming the method and index.
 */
class Block internal constructor(
    private val scratch: ScratchPool,
) {
    private val instructions = mutableListOf<BuilderInstruction>()
    private val labels = linkedMapOf<String, Int>()
    private val branches = mutableListOf<Branch>()

    internal val emitted: List<BuilderInstruction> get() = instructions
    internal val declaredLabels: Map<String, Int> get() = labels
    internal val branchFixups: List<Branch> get() = branches

    /** Registers reserved from the shared scratch pool by this block. */
    val registers: List<Int> get() = scratch.registers

    /** Allocates one scratch register inside [limit]. */
    fun scratchRegister(limit: RegisterLimit = RegisterLimit.FOUR_BIT): Int = scratch.single(limit)

    /** Declares a branch destination at the current position. */
    fun label(name: String) {
        if (labels.put(name, instructions.size) != null) {
            throw PatchException("Typed hook declares label '$name' twice")
        }
    }

    /** Adds an instruction directly; it receives the same format validation as emitted instructions. */
    fun add(instruction: BuilderInstruction) {
        instructions.add(instruction)
    }

    // Control flow.

    fun goto(target: Target) = branch(Opcode.GOTO, target)

    fun ifEqz(register: Int, target: Target) = branch(Opcode.IF_EQZ, target, register)

    fun ifNez(register: Int, target: Target) = branch(Opcode.IF_NEZ, target, register)

    fun ifLtz(register: Int, target: Target) = branch(Opcode.IF_LTZ, target, register)

    fun ifGez(register: Int, target: Target) = branch(Opcode.IF_GEZ, target, register)

    fun ifGtz(register: Int, target: Target) = branch(Opcode.IF_GTZ, target, register)

    fun ifLez(register: Int, target: Target) = branch(Opcode.IF_LEZ, target, register)

    fun ifEq(
        registerA: Int,
        registerB: Int,
        target: Target,
    ) = branch(Opcode.IF_EQ, target, registerA, registerB)

    fun ifNe(
        registerA: Int,
        registerB: Int,
        target: Target,
    ) = branch(Opcode.IF_NE, target, registerA, registerB)

    fun ifLt(
        registerA: Int,
        registerB: Int,
        target: Target,
    ) = branch(Opcode.IF_LT, target, registerA, registerB)

    fun ifGe(
        registerA: Int,
        registerB: Int,
        target: Target,
    ) = branch(Opcode.IF_GE, target, registerA, registerB)

    fun ifGt(
        registerA: Int,
        registerB: Int,
        target: Target,
    ) = branch(Opcode.IF_GT, target, registerA, registerB)

    fun ifLe(
        registerA: Int,
        registerB: Int,
        target: Target,
    ) = branch(Opcode.IF_LE, target, registerA, registerB)

    private fun branch(
        opcode: Opcode,
        target: Target,
        registerA: Int = -1,
        registerB: Int = -1,
    ) {
        // `if-*z` (21t) encodes one byte register; `if-*` (22t) encodes two four-bit registers.
        val limit = if (opcode.format == Format.Format22t) 15 else 255
        listOf(registerA, registerB).filter { register -> register > limit }.forEach { register ->
            throw PatchException(
                "${opcode.name} cannot encode v$register: format " +
                    "${opcode.format.name.removePrefix("Format")} allows at most v$limit",
            )
        }
        // The placeholder keeps instruction indexes stable; insertHook replaces every
        // branch once the block is in the method and its target locations exist.
        instructions.add(BuilderInstruction10x(Opcode.NOP))
        branches.add(
            Branch(
                index = instructions.lastIndex,
                opcode = opcode,
                registerA = registerA,
                registerB = registerB,
                target = target,
            ),
        )
    }

    // Terminators.

    fun returnVoid() = add(BuilderInstruction10x(Opcode.RETURN_VOID))

    fun returnValue(register: Int) = add(BuilderInstruction11x(Opcode.RETURN, register))

    fun returnWide(register: Int) = add(BuilderInstruction11x(Opcode.RETURN_WIDE, register))

    fun returnObject(register: Int) = add(BuilderInstruction11x(Opcode.RETURN_OBJECT, register))

    fun throwValue(register: Int) = add(BuilderInstruction11x(Opcode.THROW, register))

    fun nop() = add(BuilderInstruction10x(Opcode.NOP))

    fun moveException(destination: Int) = add(BuilderInstruction11x(Opcode.MOVE_EXCEPTION, destination))

    // Constants.

    /**
     * Emits the smallest `const` encoding that holds [value] *and* encodable in [destination]:
     * `const/4` only fits four-bit registers, the other narrow forms only byte registers.
     */
    fun constInt(
        destination: Int,
        value: Int,
    ) {
        when {
            destination <= 15 && value in -8..7 ->
                add(BuilderInstruction11n(Opcode.CONST_4, destination, value))

            destination <= 255 && value in Short.MIN_VALUE..Short.MAX_VALUE ->
                add(BuilderInstruction21s(Opcode.CONST_16, destination, value))

            destination <= 255 && value and 0xFFFF == 0 ->
                add(BuilderInstruction21ih(Opcode.CONST_HIGH16, destination, value))

            else -> add(BuilderInstruction31i(Opcode.CONST, destination, value))
        }
    }

    fun constLong(
        destination: Int,
        value: Long,
    ) {
        when {
            destination <= 255 && value in Short.MIN_VALUE..Short.MAX_VALUE ->
                add(BuilderInstruction21s(Opcode.CONST_WIDE_16, destination, value.toInt()))
            destination <= 255 && value in Int.MIN_VALUE..Int.MAX_VALUE ->
                add(BuilderInstruction31i(Opcode.CONST_WIDE_32, destination, value.toInt()))
            destination <= 255 && value and 0x0000FFFFFFFFFFFFL == 0L ->
                add(BuilderInstruction21lh(Opcode.CONST_WIDE_HIGH16, destination, value))
            else -> add(BuilderInstruction51l(Opcode.CONST_WIDE, destination, value))
        }
    }

    fun constString(
        destination: Int,
        value: String,
    ) = add(BuilderInstruction21c(Opcode.CONST_STRING, destination, ImmutableStringReference(value)))

    fun constClass(
        destination: Int,
        descriptor: String,
    ) = add(BuilderInstruction21c(Opcode.CONST_CLASS, destination, ImmutableTypeReference(descriptor)))

    // Moves.

    /** Moves a value of the smali [type] from [source] to [destination]. */
    fun move(
        destination: Int,
        source: Int,
        type: String,
    ) {
        val wide = registerWords(type) == 2
        val reference = isReferenceType(type)
        when {
            destination <= 15 && source <= 15 ->
                when {
                    wide -> add(BuilderInstruction12x(Opcode.MOVE_WIDE, destination, source))
                    reference -> add(BuilderInstruction12x(Opcode.MOVE_OBJECT, destination, source))
                    else -> add(BuilderInstruction12x(Opcode.MOVE, destination, source))
                }

            destination <= 0xFF && source <= 0xFFFF ->
                when {
                    wide -> add(BuilderInstruction22x(Opcode.MOVE_WIDE_FROM16, destination, source))
                    reference -> add(BuilderInstruction22x(Opcode.MOVE_OBJECT_FROM16, destination, source))
                    else -> add(BuilderInstruction22x(Opcode.MOVE_FROM16, destination, source))
                }

            destination <= 0xFFFF && source <= 0xFFFF ->
                when {
                    wide -> add(BuilderInstruction32x(Opcode.MOVE_WIDE_16, destination, source))
                    reference -> add(BuilderInstruction32x(Opcode.MOVE_OBJECT_16, destination, source))
                    else -> add(BuilderInstruction32x(Opcode.MOVE_16, destination, source))
                }

            else -> throw PatchException("Cannot move a value from v$source to v$destination")
        }
    }

    /** `move-result` of the kind required by the return [type], paired with the previous call. */
    fun moveResult(
        destination: Int,
        type: String,
    ) {
        val opcode =
            when {
                isReferenceType(type) -> Opcode.MOVE_RESULT_OBJECT
                registerWords(type) == 2 -> Opcode.MOVE_RESULT_WIDE
                else -> Opcode.MOVE_RESULT
            }
        val producer = instructions.lastOrNull()
        if (producer != null) {
            if (!producer.opcode.setsResult()) {
                throw PatchException(
                    "${opcode.name} must directly follow a result producing call, " +
                        "but the previous instruction is ${producer.opcode.name}",
                )
            }
            val returnType = producer.referenceReturnType()
            if (returnType != null && !resultKindMatches(returnType, opcode)) {
                throw PatchException(
                    "${opcode.name} does not match the ${returnType} result of " +
                        "${producer.opcode.name}",
                )
            }
        }
        add(BuilderInstruction11x(opcode, destination))
    }

    // Fields.

    fun iget(
        destination: Int,
        instance: Int,
        field: FieldReference,
    ) = add(BuilderInstruction22c(igetOpcode(field.type), destination, instance, field))

    fun iput(
        source: Int,
        instance: Int,
        field: FieldReference,
    ) = add(BuilderInstruction22c(iputOpcode(field.type), source, instance, field))

    fun sget(
        destination: Int,
        field: FieldReference,
    ) = add(BuilderInstruction21c(sgetOpcode(field.type), destination, field))

    fun sput(
        source: Int,
        field: FieldReference,
    ) = add(BuilderInstruction21c(sputOpcode(field.type), source, field))

    // Objects and arrays.

    fun newInstance(
        destination: Int,
        descriptor: String,
    ) = add(BuilderInstruction21c(Opcode.NEW_INSTANCE, destination, ImmutableTypeReference(descriptor)))

    fun newArray(
        destination: Int,
        sizeRegister: Int,
        descriptor: String,
    ) = add(BuilderInstruction22c(Opcode.NEW_ARRAY, destination, sizeRegister, ImmutableTypeReference(descriptor)))

    fun arrayLength(
        destination: Int,
        array: Int,
    ) = add(BuilderInstruction12x(Opcode.ARRAY_LENGTH, destination, array))

    fun checkCast(
        register: Int,
        descriptor: String,
    ) = add(BuilderInstruction21c(Opcode.CHECK_CAST, register, ImmutableTypeReference(descriptor)))

    fun instanceOf(
        destination: Int,
        reference: Int,
        descriptor: String,
    ) = add(BuilderInstruction22c(Opcode.INSTANCE_OF, destination, reference, ImmutableTypeReference(descriptor)))

    fun agetObject(
        destination: Int,
        array: Int,
        index: Int,
    ) = add(BuilderInstruction23x(Opcode.AGET_OBJECT, destination, array, index))

    fun aget(
        destination: Int,
        array: Int,
        index: Int,
    ) = add(BuilderInstruction23x(Opcode.AGET, destination, array, index))

    fun aputObject(
        source: Int,
        array: Int,
        index: Int,
    ) = add(BuilderInstruction23x(Opcode.APUT_OBJECT, source, array, index))

    fun aput(
        source: Int,
        array: Int,
        index: Int,
    ) = add(BuilderInstruction23x(Opcode.APUT, source, array, index))

    fun intAddLiteral8(
        destination: Int,
        source: Int,
        literal: Int,
    ) = add(BuilderInstruction22b(Opcode.ADD_INT_LIT8, destination, source, literal))

    // Invokes.

    fun invokeStatic(
        reference: MethodReference,
        vararg registers: Int,
    ) = invoke(Opcode.INVOKE_STATIC, reference, registers.toList())

    fun invokeVirtual(
        reference: MethodReference,
        vararg registers: Int,
    ) = invoke(Opcode.INVOKE_VIRTUAL, reference, registers.toList())

    fun invokeInterface(
        reference: MethodReference,
        vararg registers: Int,
    ) = invoke(Opcode.INVOKE_INTERFACE, reference, registers.toList())

    fun invokeDirect(
        reference: MethodReference,
        vararg registers: Int,
    ) = invoke(Opcode.INVOKE_DIRECT, reference, registers.toList())

    fun invokeSuper(
        reference: MethodReference,
        vararg registers: Int,
    ) = invoke(Opcode.INVOKE_SUPER, reference, registers.toList())

    /**
     * Emits an invoke, lowering to `invoke-kind/range` when the call has more than five
     * register words or a register above `v15`.
     *
     * [registers] lists one register per argument word (a `J`/`D` argument occupies two
     * consecutive registers, as in smali). Lowering stages the values through a contiguous
     * scratch span reserved from the pool.
     */
    fun invoke(
        opcode: Opcode,
        reference: MethodReference,
        registers: List<Int>,
    ) {
        val argumentTypes = argumentWordTypes(opcode, reference)
        if (registers.size != argumentTypes.size) {
            throw PatchException(
                "${reference.methodDescriptor()} expects ${argumentTypes.size} register words " +
                    "(${argumentTypes.size} argument registers) but got ${registers.size}: $registers",
            )
        }
        registers.forEachIndexed { index, register ->
            if (register > MAXIMUM_DALVIK_REGISTER) {
                throw PatchException("Argument $index of ${reference.methodDescriptor()} uses invalid register v$register")
            }
        }
        // A wide argument occupies two consecutive registers in both encodings. The register list
        // holds one entry per word, so only the first word of a wide argument is checked.
        var argumentIndex = 0
        while (argumentIndex < argumentTypes.size) {
            val type = argumentTypes[argumentIndex]
            if (registerWords(type) == 2 &&
                (argumentIndex + 1 >= registers.size ||
                    registers[argumentIndex + 1] != registers[argumentIndex] + 1)
            ) {
                throw PatchException(
                    "Wide argument $argumentIndex of ${reference.methodDescriptor()} must occupy two " +
                        "consecutive registers, got v${registers[argumentIndex]} and " +
                        "v${registers.getOrElse(argumentIndex + 1) { -1 }}",
                )
            }
            argumentIndex += registerWords(type)
        }
        if (registers.size <= 5 && registers.all { it <= 15 }) {
            add(builder35c(opcode, reference, registers))
            return
        }

        val rangeOpcode =
            rangeVariantOf(opcode)
                ?: throw PatchException("${opcode.name} has no /range variant for ${reference.methodDescriptor()}")
        if (registers.isConsecutive() && registers.size <= 255) {
            add(BuilderInstruction3rc(rangeOpcode, registers.first(), registers.size, reference))
            return
        }

        // Non-consecutive operands: stage the values through a contiguous scratch span. The span may
        // overlap the arguments, so the copies are emitted in an order that never overwrites a value
        // another copy still has to read; a true cycle fails closed instead of corrupting the call.
        val span = scratch.span(registers.size)
        val pending = argumentTypes.indices.toMutableList()
        while (pending.isNotEmpty()) {
            val index =
                pending.firstOrNull { candidate ->
                    pending.none { other -> other != candidate && registers[other] == span + candidate }
                } ?: throw PatchException(
                    "Cannot stage the arguments of ${reference.methodDescriptor()}: the staging span " +
                        "v$span..v${span + registers.size - 1} and $registers form a copy cycle. " +
                        "Move the injection point or pass registers the pool can stage without overlap.",
                )
            move(span + index, registers[index], argumentTypes[index])
            pending.remove(index)
        }
        add(BuilderInstruction3rc(rangeOpcode, span, registers.size, reference))
    }

    private fun builder35c(
        opcode: Opcode,
        reference: MethodReference,
        registers: List<Int>,
    ) = BuilderInstruction35c(
        opcode,
        registers.size,
        registers.getOrElse(0) { 0 },
        registers.getOrElse(1) { 0 },
        registers.getOrElse(2) { 0 },
        registers.getOrElse(3) { 0 },
        registers.getOrElse(4) { 0 },
        reference,
    )

    /**
     * One smali type per argument register word; the receiver of a non-static call counts as
     * the first word.
     */
    private fun argumentWordTypes(
        opcode: Opcode,
        reference: MethodReference,
    ): List<String> {
        val words = mutableListOf<String>()
        if (!isStaticInvoke(opcode)) words.add(RECEIVER_TYPE)
        reference.parameterTypes.forEach { type ->
            val descriptor = type.toString()
            words.add(descriptor)
            if (registerWords(descriptor) == 2) words.add(descriptor)
        }
        return words
    }

    internal class Branch(
        val index: Int,
        val opcode: Opcode,
        val registerA: Int,
        val registerB: Int,
        val target: Target,
    )

    internal companion object {
        private const val RECEIVER_TYPE = "Ljava/lang/Object;"

        private val STATIC_INVOKES = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

        internal fun isStaticInvoke(opcode: Opcode): Boolean = opcode in STATIC_INVOKES

        internal fun rangeVariantOf(opcode: Opcode): Opcode? =
            when (opcode) {
                Opcode.INVOKE_STATIC -> Opcode.INVOKE_STATIC_RANGE
                Opcode.INVOKE_VIRTUAL -> Opcode.INVOKE_VIRTUAL_RANGE
                Opcode.INVOKE_INTERFACE -> Opcode.INVOKE_INTERFACE_RANGE
                Opcode.INVOKE_DIRECT -> Opcode.INVOKE_DIRECT_RANGE
                Opcode.INVOKE_SUPER -> Opcode.INVOKE_SUPER_RANGE
                else -> null
            }

        private fun igetOpcode(type: String): Opcode =
            when (type) {
                "J", "D" -> Opcode.IGET_WIDE
                "Z" -> Opcode.IGET_BOOLEAN
                "B" -> Opcode.IGET_BYTE
                "C" -> Opcode.IGET_CHAR
                "S" -> Opcode.IGET_SHORT
                "I", "F" -> Opcode.IGET
                else -> Opcode.IGET_OBJECT
            }

        private fun iputOpcode(type: String): Opcode =
            when (type) {
                "J", "D" -> Opcode.IPUT_WIDE
                "Z" -> Opcode.IPUT_BOOLEAN
                "B" -> Opcode.IPUT_BYTE
                "C" -> Opcode.IPUT_CHAR
                "S" -> Opcode.IPUT_SHORT
                "I", "F" -> Opcode.IPUT
                else -> Opcode.IPUT_OBJECT
            }

        private fun sgetOpcode(type: String): Opcode =
            when (type) {
                "J", "D" -> Opcode.SGET_WIDE
                "Z" -> Opcode.SGET_BOOLEAN
                "B" -> Opcode.SGET_BYTE
                "C" -> Opcode.SGET_CHAR
                "S" -> Opcode.SGET_SHORT
                "I", "F" -> Opcode.SGET
                else -> Opcode.SGET_OBJECT
            }

        private fun sputOpcode(type: String): Opcode =
            when (type) {
                "J", "D" -> Opcode.SPUT_WIDE
                "Z" -> Opcode.SPUT_BOOLEAN
                "B" -> Opcode.SPUT_BYTE
                "C" -> Opcode.SPUT_CHAR
                "S" -> Opcode.SPUT_SHORT
                "I", "F" -> Opcode.SPUT
                else -> Opcode.SPUT_OBJECT
            }

    }
}

package app.crimera.bytecode

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Format
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.Label
import com.android.tools.smali.dexlib2.builder.MethodLocation
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22t
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/** Where an inserted block landed: instructions `firstIndex..lastIndex`, plus the scratch registers it used. */
data class Insertion(
    val firstIndex: Int,
    val lastIndex: Int,
    val registers: List<Int>,
)

/**
 * Inserts a typed hook in front of the instruction at [index].
 *
 * Unlike a plain [app.morphe.patcher.extensions.InstructionExtensions.addInstructions], the
 * branch-target policy is explicit and checked: when the insertion point carries labels the caller
 * must pass [relocateBranchTargets]. `true` moves them onto the first injected instruction, so
 * every path that used to jump to the original code runs the hook first (leaving them in place is
 * how an injected bridge becomes a silent runtime no-op). `false` keeps them on the original
 * instruction, which is what a loop preheader needs: the back edge must skip the block.
 *
 * Try-block boundaries, exception handlers and switch/fill-array-data payload references are
 * never relocated: those ranges keep covering exactly the code they covered before. A label
 * that is both a protected reference and a branch target cannot satisfy both contracts and
 * fails the patch.
 *
 * Guards:
 * - the block must not be empty;
 * - the instruction at [index] must not be `move-result*` (that would separate it from its
 *   invoke and produce a `VerifyError`);
 * - a block that starts with `move-result*` requires an invoke in front of the hook;
 * - every branch target must resolve to an instruction.
 *
 * @param excludedRegisters registers the injected code must never use, in addition to the
 * scratch registers the shared liveness search reports as free.
 * @return the inserted range and allocated registers, for follow-up edits and assertions.
 */
fun MutableMethod.insertHook(
    index: Int,
    excludedRegisters: Collection<Int> = emptyList(),
    relocateBranchTargets: Boolean? = null,
    block: Block.() -> Unit,
): Insertion {
    val implementation =
        implementation
            ?: throw PatchException("Cannot inject a typed hook into ${methodDescriptor()}: the method has no implementation")
    val instructions = implementation.instructions
    if (index !in 0..instructions.size) {
        throw PatchException(
            "Cannot inject a typed hook into ${methodDescriptor()}: index $index is outside the method body " +
                "(0..${instructions.size})",
        )
    }

    val typedBlock = Block(ScratchPool(this, index, excludedRegisters))
    try {
        typedBlock.block()
    } catch (exception: RuntimeException) {
        // dexlib2's instruction constructors, the scratch pool and the block's own validation
        // reject what they cannot encode; the patch author needs the method and index.
        throw PatchException(
            "insertHook for ${methodDescriptor()} at index $index: ${exception.message}",
            exception,
        )
    }
    if (typedBlock.emitted.isEmpty()) {
        throw PatchException("insertHook for ${methodDescriptor()} at index $index emits no instructions")
    }

    val target = instructions.getOrNull(index)
    requireResultPairing(target, typedBlock, index)
    requireBranchTargets(typedBlock)

    val targetLocation = target?.location
    val branchLabels = targetLocation?.labels?.toList().orEmpty()
    if (branchLabels.isNotEmpty() && relocateBranchTargets == null) {
        throw PatchException(
            "insertHook for ${methodDescriptor()} at index $index: the instruction is a branch target " +
                "or protected reference (${branchLabels.size} label(s)). Choose the policy explicitly: " +
                "relocateBranchTargets = true runs the hook for every path that reaches it, " +
                "relocateBranchTargets = false keeps those branches on the original instruction " +
                "(a loop back edge that must skip a one-time preheader block, for example).",
        )
    }
    val relocated = mutableListOf<Label>()
    if (targetLocation != null && relocateBranchTargets == true) {
        relocated.addAll(labelsToRelocate(implementation, targetLocation, index))
        relocated.forEach(targetLocation.labels::remove)
    }

    typedBlock.emitted.forEachIndexed { offset, instruction ->
        implementation.addInstruction(index + offset, instruction)
    }

    if (relocated.isNotEmpty()) {
        val head = implementation.instructions[index].location
        relocated.forEach { label -> head.labels.add(label) }
    }

    val originalIndex = index + typedBlock.emitted.size
    val labels = resolveBranchLabels(typedBlock, implementation, index, originalIndex)
    typedBlock.branchFixups.forEach { fixup ->
        implementation.replaceInstruction(index + fixup.index, fixup.toInstruction(labels))
    }
    verifyBranchReachability("typed hook at index $index")

    return Insertion(
        firstIndex = index,
        lastIndex = originalIndex - 1,
        registers = typedBlock.registers,
    )
}

private fun MutableMethod.requireResultPairing(
    target: BuilderInstruction?,
    block: Block,
    index: Int,
) {
    if (target != null && target.opcode in MOVE_RESULT_OPCODES) {
        throw PatchException(
            "Cannot inject a typed hook into ${methodDescriptor()} at index $index: " +
                "${target.opcode.name} must stay directly after its invoke. Insert before the invoke " +
                "or after ${target.opcode.name}.",
        )
    }
    val first = block.emitted.first().opcode
    if (first !in MOVE_RESULT_OPCODES) return
    val producer = implementation?.instructions?.getOrNull(index - 1)
    if (producer == null || !producer.opcode.setsResult()) {
        throw PatchException(
            "insertHook for ${methodDescriptor()} at index $index starts with ${first.name}, but the instruction " +
                "in front of the hook is ${producer?.opcode?.name ?: "the start of the method"} and produces no result",
        )
    }
    val returnType = producer.referenceReturnType()
    if (returnType != null && !resultKindMatches(returnType, first)) {
        throw PatchException(
            "insertHook for ${methodDescriptor()} at index $index starts with ${first.name}, but the call in " +
                "front of the hook returns $returnType. Every invoke reports a result slot regardless of its " +
                "return type, so the kind has to be checked explicitly.",
        )
    }
}

/** Declared return type of the call an instruction references, when it references one. */
internal fun BuilderInstruction.referenceReturnType(): String? =
    (this as? ReferenceInstruction)?.reference?.let { reference ->
        (reference as? MethodReference)?.returnType
    }

/** True when [opcode] is the `move-result` form that [returnType] requires. */
internal fun resultKindMatches(
    returnType: String,
    opcode: Opcode,
): Boolean =
    when (opcode) {
        Opcode.MOVE_RESULT_OBJECT -> isReferenceType(returnType)
        Opcode.MOVE_RESULT_WIDE -> returnType == "J" || returnType == "D"
        Opcode.MOVE_RESULT ->
            returnType != "V" &&
                !isReferenceType(returnType) &&
                returnType != "J" &&
                returnType != "D"

        else -> true
    }

private fun MutableMethod.requireBranchTargets(block: Block) {
    block.branchFixups.forEach { fixup ->
        val target = fixup.target
        if (target is Target.Local && target.label !in block.declaredLabels) {
            throw PatchException(
                "insertHook for ${methodDescriptor()} references undeclared label '${target.label}'",
            )
        }
    }
}

/**
 * Labels that must stay on the original instruction: try-block ranges, exception handlers and
 * switch/fill-array-data payload references are offsets into the original code.
 */
internal fun protectedLabels(
    implementation: MutableMethodImplementation,
    location: MethodLocation,
): Set<Label> {
    val protectedLabels = mutableSetOf<Label>()
    implementation.tryBlocks.forEach { tryBlock ->
        protectedLabels.add(tryBlock.start)
        protectedLabels.add(tryBlock.end)
        tryBlock.exceptionHandlers.forEach { handler -> protectedLabels.add(handler.handler) }
    }
    implementation.instructions.filterIsInstance<BuilderOffsetInstruction>().forEach { instruction ->
        if (instruction.opcode.format == Format.Format31t) protectedLabels.add(instruction.target)
    }
    return protectedLabels.intersect(location.labels.toSet())
}

private fun labelsToRelocate(
    implementation: MutableMethodImplementation,
    location: MethodLocation,
    index: Int,
): List<Label> {
    val labels = location.labels.toList()
    if (labels.isEmpty()) return emptyList()

    val protectedLabels = protectedLabels(implementation, location)

    val incomingBranches =
        implementation.instructions
            .filterIsInstance<BuilderOffsetInstruction>()
            .map { instruction -> instruction.target }
            .toSet()

    labels.firstOrNull { label -> label in protectedLabels && label in incomingBranches }?.let { label ->
        throw PatchException(
            "Cannot inject a typed hook before instruction $index: label $label is both a branch " +
                "target and a protected reference (try block, handler or switch payload). Pick another " +
                "insertion point.",
        )
    }

    return labels.filterNot { label -> label in protectedLabels }
}

private fun resolveBranchLabels(
    block: Block,
    implementation: MutableMethodImplementation,
    index: Int,
    originalIndex: Int,
): Map<Target, Label> {
    val resolved = linkedMapOf<Target, Label>()
    block.branchFixups.forEach { fixup ->
        resolved.getOrPut(fixup.target) {
            val absolute =
                when (val target = fixup.target) {
                    is Target.Local ->
                        index + (block.declaredLabels[target.label]
                            ?: throw PatchException("Typed hook references undeclared label '${target.label}'"))

                    Target.Original -> originalIndex

                    is Target.AfterOriginal -> originalIndex + target.offset
                }
            if (absolute !in implementation.instructions.indices) {
                throw PatchException(
                    "Typed hook branch target has no instruction at index $absolute " +
                        "(method body size ${implementation.instructions.size})",
                )
            }
            implementation.newLabelForIndex(absolute)
        }
    }
    return resolved
}

private fun Block.Branch.toInstruction(labels: Map<Target, Label>) =
    when (opcode.format) {
        Format.Format10t -> BuilderInstruction10t(Opcode.GOTO, labels.getValue(target))
        Format.Format21t -> BuilderInstruction21t(opcode, registerA, labels.getValue(target))
        Format.Format22t -> BuilderInstruction22t(opcode, registerA, registerB, labels.getValue(target))
        else -> throw PatchException("${opcode.name} is not a supported branch encoding")
    }

/**
 * Forces dexlib2's fix pass (it widens `goto`/`goto/16` whose target moved and aligns switch
 * payloads) and then verifies every branch in the method still fits its encoding. Inserting a
 * block moves code addresses, so an unrelated branch elsewhere in the method can stop fitting;
 * that must fail this patch with the method name instead of surfacing in the dex writer.
 */
private fun MutableMethod.verifyBranchReachability(context: String) {
    val implementation = implementation ?: return
    implementation.instructions.size
    implementation.instructions.forEachIndexed { index, instruction ->
        if (instruction !is BuilderOffsetInstruction) return@forEachIndexed
        try {
            instruction.codeOffset
        } catch (exception: RuntimeException) {
            throw PatchException(
                "$context: ${instruction.opcode.name} at index $index in ${methodDescriptor()} cannot reach " +
                    "its target after insertion. Use a nearer injection point or route the hook through " +
                    "a stub method.",
                exception,
            )
        }
    }
}
private val MOVE_RESULT_OPCODES = setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_RESULT_OBJECT)

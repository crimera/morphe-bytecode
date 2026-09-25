package app.crimera.bytecode

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.HiddenApiRestriction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.MethodImplementationBuilder
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction12x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Emitted-bytecode coverage for the typed hook helpers. Each test guards a failure mode that
 * only shows up on device: a register the format cannot encode, a broken invoke/`move-result`
 * pair, a branch that bypasses the injected block, or a relocation that moves a try boundary.
 */
/** Move encodings the invoke stager can emit while copying argument words. */
private val STAGING_MOVE_OPCODES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

class BytecodeTest {
    @Test
    fun `non-consecutive invoke words are staged through a contiguous scratch span`() {
        val method = fixtureMethod(registerCount = 12, parameterTypes = List(6) { "I" }) { }
        val hook = hookReference(parameterTypes = List(6) { "I" })
        val sources = listOf(6, 7, 8, 9, 10, 2)

        val insertion = method.insertHook(index = 0) { invokeStatic(hook, *sources.toIntArray()) }

        assertEquals(6, insertion.registers.size)
        assertEquals(sources, simulateStaging(method, sources))
        val invoke = assertIs<RegisterRangeInstruction>(method.implementation!!.instructions[6])
        assertEquals(Opcode.INVOKE_STATIC_RANGE, invoke.opcode)
        assertEquals(insertion.registers.first(), invoke.startRegister)
        assertEquals(6, invoke.registerCount)
    }

    @Test
    fun `wide arguments are listed per word and must stay adjacent`() {
        val hook = hookReference(parameterTypes = listOf("J", "I"))

        val method = fixtureMethod(registerCount = 8) { }
        method.insertHook(0) { invokeStatic(hook, 0, 1, 2) }
        val invoke = assertIs<FiveRegisterInstruction>(method.implementation!!.instructions[0])
        assertEquals(3, invoke.registerCount)
        assertEquals(0, invoke.registerC)
        assertEquals(1, invoke.registerD)
        assertEquals(2, invoke.registerE)

        val exception =
            assertFailsWith<PatchException> {
                fixtureMethod(registerCount = 8) { }.insertHook(0) {
                    invokeStatic(hook, 0, 2, 3)
                }
            }
        assertContains(exception.message.orEmpty(), "two consecutive registers")
    }

    @Test
    fun `invoke staging copies arguments without clobbering them`() {
        val method = fixtureMethod(registerCount = 24, parameterTypes = List(6) { "I" }) { }
        val hook = hookReference(parameterTypes = List(6) { "I" })
        // Five of the six arguments sit exactly where the pool stages, so a naive in-order copy
        // would overwrite an argument before it is read.
        val sources = listOf(0, 1, 2, 3, 4, 20)

        method.insertHook(index = 0) { invokeStatic(hook, *sources.toIntArray()) }

        val staged = simulateStaging(method, sources)
        assertEquals(sources, staged)
    }

    @Test
    fun `invoke staging fails closed when the copies form a cycle`() {
        val builder = MethodImplementationBuilder(12)
        for (register in 0..5) {
            builder.addInstruction(BuilderInstruction12x(Opcode.MOVE, register, register))
        }
        builder.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        val method = methodOf(builder, registerCount = 12)
        val hook = hookReference(parameterTypes = List(6) { "I" })

        // The only free span is v6..v11 and the arguments swap two of its registers.
        val exception =
            assertFailsWith<PatchException> {
                method.insertHook(index = 0) {
                    invokeStatic(hook, 7, 6, 8, 9, 10, 11)
                }
            }

        assertContains(exception.message.orEmpty(), "copy cycle")
    }

    /**
     * Replays the emitted staging moves and returns, for every staged slot, the argument value the
     * invoke will see. A correct staging sequence yields exactly [sources].
     */
    private fun simulateStaging(
        method: MutableMethod,
        sources: List<Int>,
    ): List<Int> {
        val values = (0..63).associateWith { it }.toMutableMap()
        var stagedCount = 0
        for (instruction in method.implementation!!.instructions) {
            if (instruction is RegisterRangeInstruction) break
            if (instruction.opcode !in STAGING_MOVE_OPCODES) continue
            val move = assertIs<TwoRegisterInstruction>(instruction)
            values[move.registerA] = values.getValue(move.registerB)
            stagedCount++
        }
        assertEquals(sources.size, stagedCount)
        val invoke =
            assertIs<RegisterRangeInstruction>(method.implementation!!.instructions[stagedCount])
        return (0 until invoke.registerCount).map { slot -> values.getValue(invoke.startRegister + slot) }
    }

    @Test
    fun `consecutive invoke words use the range form without staging`() {
        val method = fixtureMethod(registerCount = 12, parameterTypes = List(6) { "I" }) { }
        val hook = hookReference(parameterTypes = List(6) { "I" })

        val insertion = method.insertHook(index = 0) { invokeStatic(hook, 6, 7, 8, 9, 10, 11) }

        val invoke = assertIs<RegisterRangeInstruction>(method.implementation!!.instructions[0])
        assertEquals(Opcode.INVOKE_STATIC_RANGE, invoke.opcode)
        assertEquals(6, invoke.startRegister)
        assertEquals(6, invoke.registerCount)
        assertTrue(insertion.registers.isEmpty())
    }

    @Test
    fun `already contiguous high registers use the range form without staging`() {
        val method = fixtureMethod(registerCount = 20, parameterTypes = listOf("I")) { }
        val hook = hookReference(parameterTypes = listOf("I"))

        val insertion = method.insertHook(index = 0) { invokeStatic(hook, 19) }

        val invoke = assertIs<RegisterRangeInstruction>(method.implementation!!.instructions[0])
        assertEquals(Opcode.INVOKE_STATIC_RANGE, invoke.opcode)
        assertEquals(19, invoke.startRegister)
        assertEquals(1, invoke.registerCount)
        assertTrue(insertion.registers.isEmpty())
    }

    @Test
    fun `four-bit field access with a high register fails the patch`() {
        val method = fixtureMethod(registerCount = 24) { }
        val field = ImmutableFieldReference("Lapp/morphe/extension/newx/Fixture;", "value", "Ljava/lang/Object;")

        val exception =
            assertFailsWith<PatchException> {
                method.insertHook(index = 0) { iget(destination = 22, instance = 0, field = field) }
            }

        assertContains(exception.message.orEmpty(), "Invalid register: v22")
        assertContains(exception.message.orEmpty(), "TypedFixture;->fixture()V")
        assertEquals(1, method.implementation!!.instructions.size)
    }

    @Test
    fun `move-result must directly follow a result producing call`() {
        val method = fixtureMethod(registerCount = 2) { }
        val hook = hookReference(returnType = "I")

        assertFailsWith<PatchException> {
            method.insertHook(index = 0) {
                constInt(0, 1)
                moveResult(0, "I")
            }
        }

        method.insertHook(index = 0) {
            invokeStatic(hook)
            moveResult(0, "I")
        }
        assertEquals(Opcode.MOVE_RESULT, method.implementation!!.instructions[1].opcode)
    }

    @Test
    fun `a result move must match the call's return type`() {
        val booleanHook = hookReference(returnType = "Z")

        // In-block pairing: an object move-result after a boolean call is a VerifyError.
        val exception =
            assertFailsWith<PatchException> {
                fixtureMethod(registerCount = 4) { }.insertHook(0) {
                    invokeStatic(booleanHook)
                    moveResult(0, "Ljava/lang/Object;")
                }
            }
        assertContains(exception.message.orEmpty(), "does not match")

        // Hook-level pairing: every invoke reports a result slot, so the kind must be checked
        // against the call in front of the hook, not merely its opcode.
        val builder = MethodImplementationBuilder(4)
        builder.addInstruction(BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, booleanHook))
        builder.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        val hooked = methodOf(builder, registerCount = 4)
        val hookException =
            assertFailsWith<PatchException> {
                hooked.insertHook(1) { moveResult(0, "Ljava/lang/Object;") }
            }
        assertContains(hookException.message.orEmpty(), "returns Z")

        fixtureMethod(registerCount = 4) { }.insertHook(0) {
            invokeStatic(booleanHook)
            moveResult(0, "Z")
        }
    }

    @Test
    fun `insertion in front of move-result is rejected`() {
        val hook = hookReference(returnType = "I")
        val builder = MethodImplementationBuilder(2)
        builder.addInstruction(BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, hook))
        builder.addInstruction(BuilderInstruction11x(Opcode.MOVE_RESULT, 0))
        builder.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        val method = methodOf(builder, registerCount = 2)

        val exception =
            assertFailsWith<PatchException> {
                method.insertHook(index = 1) { constInt(0, 1) }
            }

        assertContains(exception.message.orEmpty(), "move-result")
    }

    @Test
    fun `inserted hook takes over the branch targets of the original instruction`() {
        val builder = MethodImplementationBuilder(3)
        val target = builder.getLabel("target")
        builder.addInstruction(BuilderInstruction21t(Opcode.IF_EQZ, 0, target))
        builder.addInstruction(BuilderInstruction11x(Opcode.RETURN, 0))
        builder.addLabel("target")
        builder.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        val method = methodOf(builder, registerCount = 3)

        method.insertHook(index = 2, relocateBranchTargets = true) {
            constInt(2, 1)
            ifNez(2, Target.Original)
            constInt(2, 2)
        }

        val instructions = method.implementation!!.instructions
        assertEquals(Opcode.CONST_4, instructions[2].opcode)
        assertEquals(Opcode.IF_NEZ, instructions[3].opcode)
        assertEquals(Opcode.RETURN_VOID, instructions[5].opcode)
        // The branch that used to jump to the original instruction now runs the hook first.
        val branch = assertIs<BuilderOffsetInstruction>(instructions[0])
        assertEquals(2, branch.target.location.index)
        // The hook's own guard branches to the original instruction after the block.
        val guard = assertIs<BuilderOffsetInstruction>(instructions[3])
        assertEquals(5, guard.target.location.index)
    }

    @Test
    fun `try block boundaries stay on the original instruction`() {
        val builder = MethodImplementationBuilder(2)
        val start = builder.getLabel("start")
        val end = builder.getLabel("end")
        val handler = builder.getLabel("handler")
        builder.addLabel("start")
        builder.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        builder.addLabel("end")
        builder.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        builder.addLabel("handler")
        builder.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        builder.addCatch("Ljava/lang/Exception;", start, end, handler)
        val method = methodOf(builder, registerCount = 2)

        // The try-block boundary label stays on the original instruction even when relocation is
        // requested, because that label is a protected reference rather than a branch target.
        method.insertHook(index = 0, relocateBranchTargets = true) { constInt(1, 1) }

        val instructions = method.implementation!!.instructions
        assertEquals(Opcode.CONST_4, instructions[0].opcode)
        assertEquals(Opcode.RETURN_VOID, instructions[1].opcode)
        // The protected range still starts at the original instruction, so the hook stays outside it.
        val tryBlock = method.implementation!!.tryBlocks.single()
        assertEquals(1, tryBlock.start.location.index)
        assertEquals(2, tryBlock.end.location.index)
    }

    @Test
    fun `if-eqz encodes byte registers while if-eq needs four-bit pairs`() {
        // `injectReplacementGuard` branches on a byte scratch register: format 21t has an 8-bit
        // register field, while format 22t (if-eq) really is limited to four bits.
        val builder = MethodImplementationBuilder(20)
        for (register in 0..15) {
            builder.addInstruction(BuilderInstruction12x(Opcode.MOVE, register, register))
        }
        builder.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        val method = methodOf(builder, registerCount = 20)

        method.insertHook(index = 0) {
            val workRegister = scratchRegister(RegisterLimit.BYTE)
            ifEqz(workRegister, Target.Original)
        }

        val branch = assertIs<BuilderOffsetInstruction>(method.implementation!!.instructions[0])
        assertEquals(Opcode.IF_EQZ, branch.opcode)
        assertEquals(16, assertIs<OneRegisterInstruction>(branch).registerA)

        val exception =
            assertFailsWith<PatchException> {
                fixtureMethod(registerCount = 20) { }.insertHook(index = 0) {
                    ifEq(16, 16, Target.Original)
                }
            }
        assertContains(exception.message.orEmpty(), "if-eq")
    }

    @Test
    fun `repeated scratch allocations hand out distinct registers`() {
        // Guards the pool handing the same register to two live values: the navbar and inline
        // download ports collapsed three scratch registers into one before this test existed.
        val method = fixtureMethod(registerCount = 8) { }

        method.insertHook(0) {
            val first = scratchRegister()
            val second = scratchRegister()
            constInt(first, 1)
            constInt(second, 2)
        }

        val instructions = method.implementation!!.instructions
        val first = assertIs<OneRegisterInstruction>(instructions[0]).registerA
        val second = assertIs<OneRegisterInstruction>(instructions[1]).registerA
        assertEquals(0, first)
        assertEquals(1, second)
    }

    @Test
    fun `scratch allocation reports the lowest free register when no four-bit one is left`() {
        val builder = MethodImplementationBuilder(20)
        for (register in 0..15) {
            builder.addInstruction(BuilderInstruction12x(Opcode.MOVE, register, register))
        }
        builder.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        val method = methodOf(builder, registerCount = 20)

        val exception =
            assertFailsWith<PatchException> {
                method.insertHook(index = 0) { scratchRegister() }
            }

        assertContains(exception.message.orEmpty(), "4-bit")
        assertContains(exception.message.orEmpty(), "v16")
    }

    @Test
    fun `relocating branch targets leaves every label placed`() {
        // Two branches merge onto the instruction the hook is inserted in front of, and a third
        // branch targets it from below. Every label must survive the relocation onto the block head.
        val builder = MethodImplementationBuilder(4)
        val merge = builder.getLabel("merge")
        builder.addInstruction(BuilderInstruction21t(Opcode.IF_EQZ, 0, merge))
        builder.addInstruction(BuilderInstruction10x(Opcode.NOP))
        builder.addInstruction(BuilderInstruction21t(Opcode.IF_NEZ, 1, merge))
        builder.addInstruction(BuilderInstruction10x(Opcode.NOP))
        builder.addLabel("merge")
        builder.addInstruction(BuilderInstruction11x(Opcode.RETURN_OBJECT, 2))
        builder.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        val method = methodOf(builder, registerCount = 4)

        method.insertHook(index = 4, relocateBranchTargets = true) { returnObject(3) }

        // Reading the code offset is what the dex writer does; an unplaced label throws here.
        method.implementation!!.instructions.forEach { instruction ->
            (instruction as? BuilderOffsetInstruction)?.codeOffset
        }
        assertEquals(Opcode.RETURN_OBJECT, method.implementation!!.instructions[4].opcode)
        assertEquals(4, assertIs<BuilderOffsetInstruction>(method.implementation!!.instructions[0]).target.location.index)
        assertEquals(4, assertIs<BuilderOffsetInstruction>(method.implementation!!.instructions[2]).target.location.index)
    }

    @Test
    fun `a hook can branch past the original instruction`() {
        val builder = MethodImplementationBuilder(3)
        builder.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        builder.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        val method = methodOf(builder, registerCount = 3)

        method.insertHook(0) { goto(Target.AfterOriginal(1)) }

        val branch = assertIs<BuilderOffsetInstruction>(method.implementation!!.instructions[0])
        assertEquals(2, branch.target.location.index)
    }

    @Test
    fun `constInt respects the destination register format`() {
        val method = fixtureMethod(registerCount = 24) { }

        method.insertHook(0) {
            constInt(0, 7)
            constInt(20, 7)
        }

        val instructions = method.implementation!!.instructions
        assertEquals(Opcode.CONST_4, instructions[0].opcode)
        assertEquals(Opcode.CONST_16, instructions[1].opcode)
        assertEquals(20, assertIs<OneRegisterInstruction>(instructions[1]).registerA)
    }

    @Test
    fun `constInt picks the smallest encoding that holds the value`() {
        val method = fixtureMethod(registerCount = 8) { }

        method.insertHook(index = 0) {
            constInt(0, 7)
            constInt(1, 300)
            constInt(2, 0x10000)
            constInt(3, 0x12345678)
        }

        val instructions = method.implementation!!.instructions
        val expected =
            listOf(
                Opcode.CONST_4 to 7,
                Opcode.CONST_16 to 300,
                Opcode.CONST_HIGH16 to 0x10000,
                Opcode.CONST to 0x12345678,
            )
        expected.forEachIndexed { index, (opcode, literal) ->
            assertEquals(opcode, instructions[index].opcode)
            assertEquals(literal, assertIs<NarrowLiteralInstruction>(instructions[index]).narrowLiteral)
        }
    }

    private fun fixtureMethod(
        registerCount: Int,
        parameterTypes: List<String> = emptyList(),
        build: MethodImplementationBuilder.() -> Unit,
    ): MutableMethod {
        val builder = MethodImplementationBuilder(registerCount)
        builder.build()
        builder.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        return methodOf(builder, registerCount, parameterTypes)
    }

    private fun methodOf(
        builder: MethodImplementationBuilder,
        registerCount: Int,
        parameterTypes: List<String> = emptyList(),
    ): MutableMethod {
        val method =
            ImmutableMethod(
                FIXTURE_CLASS,
                "fixture",
                parameterTypes.map { type -> ImmutableMethodParameter(type, emptySet(), null) },
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                emptySet(),
                emptySet<HiddenApiRestriction>(),
                builder.methodImplementation,
            )
        assertEquals(registerCount, method.implementation!!.registerCount)
        return MutableMethod(method)
    }

    private fun hookReference(
        parameterTypes: List<String> = emptyList(),
        returnType: String = "V",
    ) = ImmutableMethodReference(HOOK_CLASS, "hook", parameterTypes, returnType)

    private companion object {
        const val FIXTURE_CLASS = "Lapp/crimera/test/TypedFixture;"
        const val HOOK_CLASS = "Lapp/morphe/extension/newx/Hook;"
    }
}

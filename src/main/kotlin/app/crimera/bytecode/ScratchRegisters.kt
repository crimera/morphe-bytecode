package app.crimera.bytecode

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.findFreeRegister

/** Widest register a Dalvik operand can encode (format `30t`/`32x`/`3rc` fields). */
internal const val MAXIMUM_DALVIK_REGISTER = 0xFFFF

/**
 * Register limits imposed by Dalvik instruction formats.
 *
 * A narrow operand (`12x`, `22c`, ...) must stay inside [FOUR_BIT]; byte operands
 * (`21c`, `11x`, `31i`, ...) inside [BYTE]. Allocating outside the limit is a
 * verifier error that only appears on device, so allocation fails closed instead.
 */
enum class RegisterLimit(
    val maximum: Int,
    val description: String,
) {
    FOUR_BIT(15, "4-bit"),
    BYTE(255, "byte"),
    SHORT(MAXIMUM_DALVIK_REGISTER, "16-bit"),
}

internal fun registerWords(type: String): Int = if (type == "J" || type == "D") 2 else 1

internal fun isReferenceType(type: String): Boolean = type.startsWith("L") || type.startsWith("[")

/**
 * Fail-closed scratch registers for one injection point.
 *
 * Registers come from the shared branch-following liveness search
 * ([findFreeRegister]): a register is only handed out when the original code
 * writes it before reading it on every path followed from [index], so a value
 * written by the injected block cannot clobber a live local.
 *
 * Every allocation is bounded by a [RegisterLimit]. A request that cannot be
 * satisfied fails with the lowest available register in the message instead of
 * emitting instructions the verifier rejects on device - that failure is the
 * signal that the method needs real frame growth rather than scratch reuse.
 */
internal class ScratchPool internal constructor(
    private val method: MutableMethod,
    private val index: Int,
    excludedRegisters: Collection<Int>,
) {
    private val excluded = excludedRegisters.toMutableList()
    private val discovered = mutableListOf<Int>()
    private val allocated = linkedSetOf<Int>()
    private var exhausted = false

    /** Registers handed out by this pool, in allocation order. */
    val registers: List<Int> get() = allocated.toList()

    /**
     * Reserves the lowest available register inside [limit].
     *
     * @throws PatchException when every remaining register exceeds the limit.
     */
    fun single(limit: RegisterLimit = RegisterLimit.FOUR_BIT): Int {
        lowestWithin(limit)?.let { return reserve(it) }
        while (true) {
            nextFreeRegister() ?: break
            lowestWithin(limit)?.let { return reserve(it) }
        }
        throw exhaustedException("register", limit)
    }

    /**
     * Reserves the lowest contiguous run of [words] registers inside [limit].
     *
     * Contiguous runs are required for `invoke-kind/range` staging. Registers the
     * pool already handed out stay excluded, so repeated calls never overlap.
     */
    fun span(
        words: Int,
        limit: RegisterLimit = RegisterLimit.SHORT,
    ): Int {
        require(words >= 1) { "A scratch span needs at least one register, got $words" }
        while (true) {
            lowestRunWithin(words, limit)?.let { start ->
                for (register in start until start + words) reserve(register)
                return start
            }
            nextFreeRegister() ?: break
        }
        throw exhaustedException("$words-register contiguous span", limit)
    }

    /** Marks [register] as used so later allocations cannot hand it out again. */
    fun reserve(register: Int): Int {
        require(register in 0..MAXIMUM_DALVIK_REGISTER) {
            "Register v$register is outside the Dalvik register range"
        }
        if (allocated.add(register)) excluded.add(register)
        return register
    }

    private fun lowestWithin(limit: RegisterLimit): Int? =
        discovered.filter { it <= limit.maximum && it !in allocated }.minOrNull()

    private fun lowestRunWithin(
        words: Int,
        limit: RegisterLimit,
    ): Int? {
        val bound = minOf(limit.maximum, method.implementation!!.registerCount - 1)
        if (bound < words - 1) return null
        val free = discovered.toSet()
        for (start in 0..bound - words + 1) {
            if ((start until start + words).all { it in free && it !in allocated }) return start
        }
        return null
    }

    /** Appending hooks search from the last real instruction; the search needs an existing index. */
    private fun searchIndex(): Int =
        minOf(index, (method.implementation?.instructions?.size ?: 1) - 1).coerceAtLeast(0)

    /** Adds the next register the app's own liveness search reports as free, or null when exhausted. */
    private fun nextFreeRegister(): Int? {
        if (exhausted) return null
        val register =
            try {
                method.findFreeRegister(searchIndex(), excluded + discovered)
            } catch (exception: IllegalArgumentException) {
                exhausted = true
                return null
            }
        if (register in discovered || register in excluded) {
            // Defensive: a non-progressing search must not spin forever.
            exhausted = true
            return null
        }
        discovered.add(register)
        return register
    }

    private fun exhaustedException(what: String, limit: RegisterLimit): PatchException {
        val lowest = discovered.filter { it !in allocated }.minOrNull() ?: discovered.minOrNull()
        val available =
            when {
                discovered.isEmpty() -> "no register is free at this index"
                lowest == null -> "no register is free at this index"
                else -> "lowest free register is v$lowest"
            }
        return PatchException(
            "insertHook at index $index of ${method.methodDescriptor()}: no ${limit.description} scratch " +
                "$what available ($available). " +
                "Reserve a register below the parameter block instead of relying on free locals, " +
                "or move the injection point.",
        )
    }
}

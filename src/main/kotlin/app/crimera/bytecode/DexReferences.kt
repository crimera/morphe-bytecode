package app.crimera.bytecode

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

/**
 * Parses smali descriptors into dexlib2 references, so typed emission can use the descriptor
 * constants patch code already keeps (`Lowner;->name(args)ret`, `Lowner;->name:type`).
 *
 * A malformed descriptor fails the patch here instead of producing a reference the dex writer
 * rejects later.
 */
fun methodReference(descriptor: String): ImmutableMethodReference {
    val arrow = descriptor.indexOf("->")
    if (arrow <= 0) throw PatchException("Not a smali method descriptor: $descriptor")
    val open = descriptor.indexOf('(', arrow + 2)
    val close = descriptor.lastIndexOf(')')
    if (open <= arrow + 2 || close <= open) {
        throw PatchException("Not a smali method descriptor: $descriptor")
    }
    val returnType = descriptor.substring(close + 1)
    if (returnType.isEmpty()) throw PatchException("Method descriptor has no return type: $descriptor")
    return ImmutableMethodReference(
        descriptor.substring(0, arrow),
        descriptor.substring(arrow + 2, open),
        parameterTypes(descriptor.substring(open + 1, close), descriptor),
        returnType,
    )
}

fun fieldReference(descriptor: String): ImmutableFieldReference {
    val arrow = descriptor.indexOf("->")
    val colon = descriptor.indexOf(':', arrow + 2)
    if (arrow <= 0 || colon <= arrow + 2 || colon == descriptor.lastIndex) {
        throw PatchException("Not a smali field descriptor: $descriptor")
    }
    return ImmutableFieldReference(
        descriptor.substring(0, arrow),
        descriptor.substring(arrow + 2, colon),
        descriptor.substring(colon + 1),
    )
}

/** Splits an argument list into one smali type per argument (arrays included, wide types unsplit). */
private fun parameterTypes(arguments: String, descriptor: String): List<String> {
    val types = mutableListOf<String>()
    var index = 0
    while (index < arguments.length) {
        val start = index
        while (index < arguments.length && arguments[index] == '[') index++
        if (index >= arguments.length) throw PatchException("Malformed parameter list in $descriptor")
        index =
            if (arguments[index] == 'L') {
                val end = arguments.indexOf(';', index)
                if (end < 0) throw PatchException("Unterminated parameter type in $descriptor")
                end + 1
            } else {
                index + 1
            }
        types.add(arguments.substring(start, index))
    }
    return types
}

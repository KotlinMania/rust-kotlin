package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.SynType

internal val SIZED_TYPE_PROPERTY_NAMES: Set<String> =
    setOf("SIZE", "ALIGN", "ALIGNMENT", "IS_ZST", "LAYOUT", "MAX_SLICE_LEN")

private val ALLOC_SINGLE_DEFAULT_GLOBAL_TYPES: Set<String> =
    setOf(
        "Box",
        "Vec",
        "Arc",
        "Rc",
        "RawVec",
        "VecDeque",
        "LinkedList",
        "BinaryHeap",
        "BTreeSet",
        "BoxedArrayIntoIter",
    )

private val ALLOC_INTO_ITER_MODULE_HINTS: Set<String> =
    setOf("vec", "binary_heap", "linked_list", "vec_deque", "btree", "boxed", "alloc")

private val NONZERO_INNER_BY_RAW_PRIM: Map<String, String> =
    mapOf(
        "u8" to "NonZeroU8Inner",
        "u16" to "NonZeroU16Inner",
        "u32" to "NonZeroU32Inner",
        "u64" to "NonZeroU64Inner",
        "u128" to "NonZeroU128Inner",
        "usize" to "NonZeroUsizeInner",
        "i8" to "NonZeroI8Inner",
        "i16" to "NonZeroI16Inner",
        "i32" to "NonZeroI32Inner",
        "i64" to "NonZeroI64Inner",
        "i128" to "NonZeroI128Inner",
        "isize" to "NonZeroIsizeInner",
        "char" to "NonZeroCharInner",
    )

private val NONZERO_INNER_BY_KT_TYPE: Map<String, String> =
    mapOf(
        "UByte" to "NonZeroU8Inner",
        "UShort" to "NonZeroU16Inner",
        "UInt" to "NonZeroU32Inner",
        "ULong" to "NonZeroU64Inner",
        "U128" to "NonZeroU128Inner",
        "Byte" to "NonZeroI8Inner",
        "Short" to "NonZeroI16Inner",
        "Int" to "NonZeroI32Inner",
        "Long" to "NonZeroI64Inner",
        "I128" to "NonZeroI128Inner",
        "RustChar" to "NonZeroCharInner",
        "Char" to "NonZeroCharInner",
    )

private val ATOMIC_STORAGE_BY_RAW_PRIM: Map<String, Pair<String, String>> =
    mapOf(
        "bool" to ("Align1" to "UByte"),
        "u8" to ("Align1" to "UByte"),
        "i8" to ("Align1" to "Byte"),
        "u16" to ("Align2" to "UShort"),
        "i16" to ("Align2" to "Short"),
        "u32" to ("Align4" to "UInt"),
        "i32" to ("Align4" to "Int"),
        "u64" to ("Align8" to "ULong"),
        "i64" to ("Align8" to "Long"),
        "usize" to ("Align8" to "Int"),
        "isize" to ("Align8" to "Int"),
    )

private val ATOMIC_STORAGE_BY_KT_TYPE: Map<String, Pair<String, String>> =
    mapOf(
        "Boolean" to ("Align1" to "UByte"),
        "UByte" to ("Align1" to "UByte"),
        "Byte" to ("Align1" to "Byte"),
        "UShort" to ("Align2" to "UShort"),
        "Short" to ("Align2" to "Short"),
        "UInt" to ("Align4" to "UInt"),
        "Int" to ("Align4" to "Int"),
        "ULong" to ("Align8" to "ULong"),
        "Long" to ("Align8" to "Long"),
        "MutPtr" to ("Align8" to "MutPtr"),
        "ConstPtr" to ("Align8" to "ConstPtr"),
    )

private val NONZERO_INNER_REGEX = Regex("""\bNonZero\s*<\s*([^,<>\s]+(?:\s*<[^<>]*>)?)\s*,\s*(NonZero[A-Za-z0-9_]+Inner)\s*>""")

private val STDLIB_CONST_PTR_CALL_PARAM_INDICES: Map<String, Set<Int>> =
    mapOf(
        "copy_nonoverlapping" to setOf(0),
        "copy" to setOf(0),
        "read" to setOf(0),
        "read_unaligned" to setOf(0),
        "read_volatile" to setOf(0),
        "slice_from_raw_parts" to setOf(0),
        "align_offset" to setOf(0),
        "ptr::eq" to setOf(0, 1),
        "ptr::addr_eq" to setOf(0, 1),
        "ptr::hash" to setOf(0),
    )

private val STDLIB_CONST_PTR_METHOD_PARAM_INDICES: Map<String, Set<Int>> =
    mapOf(
        "with_metadata_of" to setOf(0),
        "offset_from" to setOf(0),
        "byte_offset_from" to setOf(0),
        "offset_from_unsigned" to setOf(0),
        "byte_offset_from_unsigned" to setOf(0),
        "sub_ptr" to setOf(0),
        "copy_from" to setOf(0),
        "copy_from_nonoverlapping" to setOf(0),
        "addr_eq" to setOf(0),
    )

/** True when a Result scrutinee has an erased or unknown Kotlin type that still needs `<*, *>` casts. */
internal fun isUntypedResultScrutinee(renderedType: String?): Boolean {
    val clean = renderedType?.removeSuffix("?")?.trim() ?: return false
    if (clean == "Any" || clean == "RustAny" || clean == "*" || clean == "Nothing") return true
    if (clean.length == 1 && clean[0] in 'A'..'Z') return true
    val simpleBase = clean.substringBefore('<').substringAfterLast('.').trim()
    return simpleBase != "Result" && simpleBase != "RustResult"
}

/** True when a rendered Result type argument can be emitted explicitly on `Result.Ok<T, E>` or `Result.Err<T, E>`. */
internal fun isUsableExplicitResultTypeArg(typeArg: String?): Boolean {
    val clean = typeArg?.trim() ?: return false
    if (clean.isEmpty() || clean == "*" || clean == "_") return false
    return !clean.contains("uninferred", ignoreCase = true) && !clean.contains("RustSelf")
}

/** Formats `<T, E>` for tagged `Result.Ok`/`Result.Err` constructors when both bounds are concrete and usable. */
internal fun buildExplicitResultTypeArgsSuffix(okArg: String?, errArg: String?): String =
    if (isUsableExplicitResultTypeArg(okArg) && isUsableExplicitResultTypeArg(errArg)) {
        "<${okArg!!.trim()}, ${errArg!!.trim()}>"
    } else {
        ""
    }

/** Emits a `core::mem::SizedTypeProperties` constant expression backed by a resolved `TypeLayout` dictionary. */
internal fun KotlinJ2KEmitter.renderSizedTypePropertyConst(
    ownerSynType: SynType,
    constName: String,
    selfTypeName: String?,
): String? {
    if (constName !in SIZED_TYPE_PROPERTY_NAMES) return null
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val layoutExpr = CoreIntrinsicLowering.resolveTypeLayoutExpression(ownerSynType, selfTypeName, this)
    val rawAbi = headerContext.rawAbiMode
    return when (constName) {
        "SIZE" -> if (rawAbi) "($layoutExpr).sizeBytes.toULong()" else "($layoutExpr).sizeBytes.toInt()"
        "ALIGN" -> if (rawAbi) "($layoutExpr).alignBytes.toULong()" else "($layoutExpr).alignBytes.toInt()"
        "ALIGNMENT" -> {
            val alignRef = bindOrQualifyResolvedCrossSymbol("$coreNs.mem.alignment.Alignment")
            "$alignRef.newUnchecked(($layoutExpr).alignBytes.toInt())"
        }
        "IS_ZST" -> "(($layoutExpr).sizeBytes == 0L)"
        "LAYOUT" -> {
            val layoutRef = bindOrQualifyResolvedCrossSymbol("$coreNs.alloc.layout.Layout")
            "$layoutRef.fromSizeAlignUnchecked(($layoutExpr).sizeBytes.toInt(), ($layoutExpr).alignBytes.toInt())"
        }
        "MAX_SLICE_LEN" ->
            if (rawAbi) {
                "(if (($layoutExpr).sizeBytes == 0L) ULong.MAX_VALUE else (Long.MAX_VALUE.toULong() / ($layoutExpr).sizeBytes.toULong()))"
            } else {
                "(if (($layoutExpr).sizeBytes == 0L) Int.MAX_VALUE else (Int.MAX_VALUE / ($layoutExpr).sizeBytes.toInt()))"
            }
        else -> null
    }
}

/** Appends omitted trailing default generic type arguments from local AST declarations and stdlib `core`/`alloc` defaults. */
internal fun KotlinJ2KEmitter.appendStdlibDefaultTypeArgsIfNeeded(
    baseName: String,
    rawName: String,
    rawSegNames: List<String>,
    existingArgs: List<String>,
    selfTypeName: String?,
): List<String> {
    val simpleBase = baseName.substringAfterLast('.')
    val astDefaultSpecs = inspector.typeDefaultArgsByType[simpleBase] ?: inspector.typeDefaultArgsByType[rawName]
    if (astDefaultSpecs != null && existingArgs.size < astDefaultSpecs.size && rawSegNames.size <= 1) {
        val appended = existingArgs.toMutableList()
        val subs = linkedMapOf<String, String>()
        astDefaultSpecs.forEachIndexed { idx, (paramKt, _) ->
            appended.getOrNull(idx)?.let { subs[paramKt] = it }
        }
        for (idx in existingArgs.size until astDefaultSpecs.size) {
            val defSyn = astDefaultSpecs[idx].second ?: break
            val renderedDef =
                (
                    (unwrapSynType(defSyn) as? SynType.Path)?.takeIf { it.qself == null && it.path.segments.size == 1 }?.let { p ->
                        val segIdent =
                            p.path.segments
                                .first()
                                ?.ident
                                ?.toString()
                        segIdent?.let { subs[rustTypeIdentToKotlin(it)] }
                    }
                ) ?: renderType(defSyn, selfTypeName)
            appended += renderedDef
            subs[astDefaultSpecs[idx].first] = renderedDef
        }
        return appended
    }
    if (headerContext.rawAbiMode) return existingArgs
    if (simpleBase == "ControlFlow" && existingArgs.size == 1 && ("ControlFlow" !in inspector.declaredTypeNames || rawSegNames.size >= 2)) {
        return existingArgs + "Unit"
    }
    val allocNs = headerContext.crateNamespaces["alloc"] ?: return existingArgs
    val inAllocCrate = headerContext.packageName?.startsWith("$allocNs.") == true || headerContext.packageName == allocNs
    val fromAllocOrExternal = simpleBase !in inspector.declaredTypeNames || inAllocCrate || rawSegNames.firstOrNull() in setOf("alloc", "std")
    if (!fromAllocOrExternal) return existingArgs
    val needsGlobalDefault =
        when {
            existingArgs.size == 1 && simpleBase in ALLOC_SINGLE_DEFAULT_GLOBAL_TYPES -> true
            existingArgs.size == 2 && simpleBase == "BTreeMap" -> true
            existingArgs.size == 1 && simpleBase == "Weak" && (rawName !in inspector.declaredTypeNames || rawSegNames.any { it in setOf("sync", "rc", "rcs", "alloc") }) -> true
            existingArgs.size == 1 && simpleBase == "IntoIter" && rawSegNames.any { it in ALLOC_INTO_ITER_MODULE_HINTS } -> true
            else -> false
        }
    if (!needsGlobalDefault) return existingArgs
    val globalRef = if ("Global" in inspector.declaredTypeNames) "Global" else bindOrQualifyResolvedCrossSymbol("$allocNs.alloc.Global")
    return existingArgs + globalRef
}

/** Resolves cross-module hidden associated generic arguments for `core::num::NonZero` and `core::sync::atomic::Atomic`. */
internal fun KotlinJ2KEmitter.resolveStdlibAssociatedArgs(
    baseName: String,
    rawFirstArgSyn: SynType?,
    renderedFirstArg: String?,
): List<String> {
    val simpleBase = baseName.substringAfterLast('.')
    if (simpleBase != "NonZero" && simpleBase != "Atomic") return emptyList()
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val rawPrimIdent =
        rawFirstArgSyn
            ?.let(::unwrapSynType)
            ?.let { it as? SynType.Path }
            ?.takeIf { it.qself == null && it.path.segments.size == 1 }
            ?.path
            ?.segments
            ?.first()
            ?.ident
            ?.toString()
            ?.removePrefix("r#")
    val cleanRendered = renderedFirstArg?.removeSuffix("?")?.trim()
    if (simpleBase == "NonZero") {
        val innerSimple =
            (rawPrimIdent?.let { NONZERO_INNER_BY_RAW_PRIM[it] })
                ?: (cleanRendered?.let { NONZERO_INNER_BY_KT_TYPE[it] })
                ?: return emptyList()
        return listOf(bindOrQualifyResolvedCrossSymbol("$coreNs.num.nichetypes.$innerSimple"))
    }
    val storageSpec =
        (rawPrimIdent?.let { ATOMIC_STORAGE_BY_RAW_PRIM[it] })
            ?: (rawFirstArgSyn?.let(::unwrapSynType) as? SynType.Ptr)?.let { "Align8" to if (cleanRendered == "ConstPtr") "ConstPtr" else "MutPtr" }
            ?: (cleanRendered?.let { ATOMIC_STORAGE_BY_KT_TYPE[it] })
            ?: return emptyList()
    val alignRef = bindOrQualifyResolvedCrossSymbol("$coreNs.sync.atomic.${storageSpec.first}")
    return listOf("$alignRef<${storageSpec.second}>")
}

/** Infers explicit `<NonZeroInner, T>` type arguments for `NonZero.new` and `NonZero.newUnchecked` when omitted at call sites. */
internal fun KotlinJ2KEmitter.resolveNonZeroNewExplicitTypeArgs(
    calleeTail: String,
    args: List<Expr>,
    selfTypeName: String?,
): Pair<String, String>? {
    if (calleeTail !in setOf("NonZero.new", "NonZero.newUnchecked") || args.size != 1) return null
    val hintSource = listOfNotNull(currentExpectedParseTargetType, currentEnclosingReturnRenderedType).firstNotNullOfOrNull { NONZERO_INNER_REGEX.find(it) }
    if (hintSource != null) {
        return hintSource.groupValues[2] to hintSource.groupValues[1].trim()
    }
    if ("NonZeroInner" in currentTraitAssocParamNames) {
        val tArg = inferExprRenderedType(args[0], selfTypeName)?.removeSuffix("?")?.trim() ?: "T"
        return "NonZeroInner" to tArg
    }
    val argExpr = unwrapParenOrGroupExpr(args[0])
    val fieldPrim =
        (argExpr as? Expr.Field)?.let { f ->
            val recvRendered = inferExprRenderedType(f.base, selfTypeName)?.substringBefore('<')?.removeSuffix("?")?.trim()
            val ownerSimple = recvRendered?.substringAfterLast('.') ?: selfTypeName?.substringBefore('<')?.trim()
            val fieldsByName = ownerSimple?.let { inspector.structFieldSynTypesByType[it] }
            val candidateNames =
                when (val mem = f.member) {
                    is Member.Named -> listOf(rustFieldIdentToKotlin(mem.ident.toString()))
                    is Member.Unnamed -> {
                        val idx = mem.index.index.toInt()
                        val arity = ownerSimple?.let { inspector.tupleStructArityByType[it] } ?: 1
                        listOf(tupleFieldPropertyName(idx, arity), "value", "field$idx")
                    }
                }
            candidateNames.firstNotNullOfOrNull { fieldsByName?.get(it) }
        }
    val rawFieldIdent =
        (fieldPrim?.let(::unwrapSynType) as? SynType.Path)
            ?.takeIf { it.qself == null && it.path.segments.size == 1 }
            ?.path
            ?.segments
            ?.first()
            ?.ident
            ?.toString()
            ?.removePrefix("r#")
    val inferredArg = inferExprRenderedType(args[0], selfTypeName)?.removeSuffix("?")?.trim()
    val innerSimple =
        rawFieldIdent?.let { NONZERO_INNER_BY_RAW_PRIM[it] }
            ?: inferredArg?.let { NONZERO_INNER_BY_KT_TYPE[it] }
            ?: return null
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val innerRef = if (innerSimple in inspector.declaredTypeNames) innerSimple else bindOrQualifyResolvedCrossSymbol("$coreNs.num.nichetypes.$innerSimple")
    return innerRef to (inferredArg ?: "Int")
}

/** Resolves cross-crate verified zero-arg unit structs that do not appear in single-file `inspector.unitStructNames`. */
internal fun KotlinJ2KEmitter.resolveStdlibUnitStructValueExpr(rawSegNames: List<String>): String? {
    val leaf = rawSegNames.lastOrNull()?.removePrefix("r#") ?: return null
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val fq =
        when (leaf) {
            "AllocError" -> "$coreNs.alloc.AllocError"
            "LayoutError" -> "$coreNs.alloc.layout.LayoutError"
            "ParseBoolError" -> "$coreNs.str.error.ParseBoolError"
            "Global" -> headerContext.crateNamespaces["alloc"]?.let { "$it.alloc.Global" }
            "System" -> headerContext.crateNamespaces["std"]?.let { "$it.alloc.System" }
            "Error" -> if (rawSegNames.size >= 2 && rawSegNames[rawSegNames.lastIndex - 1].removePrefix("r#") == "fmt") "$coreNs.fmt.Error" else null
            else -> null
        } ?: return null
    return "${bindOrQualifyResolvedCrossSymbol(fq)}()"
}

/** True when a call argument expects `ConstPtr` (either from resolved callee AST `*const T` or known `core::ptr` API). */
internal fun isStdlibConstPtrCallParam(rawSegs: List<String>, paramSyn: SynType?, argIdx: Int): Boolean {
    val unwrapped = paramSyn?.let(::unwrapSynType)
    if (unwrapped is SynType.Ptr && unwrapped.mutability is PointerMutability.Const) return true
    val leaf = rawSegs.lastOrNull() ?: return false
    val key = if (rawSegs.size >= 2 && rawSegs[rawSegs.lastIndex - 1] == "ptr") "ptr::$leaf" else leaf
    val indices =
        STDLIB_CONST_PTR_CALL_PARAM_INDICES[key]
            ?: if (rawSegs.size >= 2 && rawSegs[rawSegs.lastIndex - 1] == "ptr") STDLIB_CONST_PTR_CALL_PARAM_INDICES[leaf] else null
    return argIdx in indices.orEmpty()
}

/** True when a raw pointer method parameter expects `ConstPtr`. */
internal fun isStdlibConstPtrMethodParam(rawMethod: String, paramSyn: SynType?, argIdx: Int): Boolean {
    val unwrapped = paramSyn?.let(::unwrapSynType)
    if (unwrapped is SynType.Ptr && unwrapped.mutability is PointerMutability.Const) return true
    return argIdx in STDLIB_CONST_PTR_METHOD_PARAM_INDICES[rawMethod].orEmpty()
}

/** Coerces a rendered `MutPtr` expression to `ConstPtr` using `asConstPtr()`. */
internal fun coerceMutPtrArgToConstPtr(renderedArg: String, inferredArgType: String?): String {
    val clean = inferredArgType?.removeSuffix("?")?.substringBefore('<')?.trim() ?: return renderedArg
    if (clean != "MutPtr" && !clean.endsWith(".MutPtr")) return renderedArg
    val trimmed = renderedArg.trim()
    if (trimmed.endsWith(".asConstPtr()") || trimmed.startsWith("ConstPtr(")) return renderedArg
    return "($trimmed).asConstPtr()"
}

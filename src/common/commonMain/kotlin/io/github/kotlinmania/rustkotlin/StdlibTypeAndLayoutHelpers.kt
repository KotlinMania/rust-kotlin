package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.SynType

internal val SIZED_TYPE_PROPERTY_NAMES: Set<String> =
    YamlRuleCatalogs.StdlibSynAndFfi.sizedTypePropertyNames

private val ALLOC_SINGLE_DEFAULT_GLOBAL_TYPES: Set<String> =
    YamlRuleCatalogs.StdlibSynAndFfi.allocSingleDefaultGlobalTypes

private val ALLOC_INTO_ITER_MODULE_HINTS: Set<String> =
    YamlRuleCatalogs.StdlibSynAndFfi.allocIntoIterModuleHints

private val STDLIB_CONST_PTR_FIRST_ARG_CALLS: Set<String> =
    YamlRuleCatalogs.StdlibSynAndFfi.stdlibConstPtrFirstArgCalls

private val STDLIB_CONST_PTR_TWO_ARG_CALLS: Set<String> =
    YamlRuleCatalogs.StdlibSynAndFfi.stdlibConstPtrTwoArgCalls

private val STDLIB_CONST_PTR_FIRST_ARG_METHODS: Set<String> =
    YamlRuleCatalogs.StdlibSynAndFfi.stdlibConstPtrFirstArgMethods

private object StdlibSpecialTypeCatalog {
    data class AtomicSpec(
        val ownerSimple: String,
        val rawPrim: String?,
        val ktValueType: String,
        val numericKind: NumericKind? = null,
        val storage: Pair<String, String>? = null,
    )

    val nonZeroByRawPrim: Map<String, String> = YamlRuleCatalogs.StdlibSynAndFfi.nonZeroByRawPrim
    val nonZeroByKtType: Map<String, String> = YamlRuleCatalogs.StdlibSynAndFfi.nonZeroByKtType

    private val atomicSpecs: List<AtomicSpec> =
        YamlRuleCatalogs.StdlibSynAndFfi.atomicSpecNodes.map { node ->
            val alignType = node.optionalString("alignType")
            val storageType = node.optionalString("storageType")
            AtomicSpec(
                ownerSimple = node.string("owner"),
                rawPrim = node.optionalString("rawPrim"),
                ktValueType = node.string("ktValueType"),
                numericKind = node.optionalString("numericKind")?.let(NumericKind::valueOf),
                storage = if (alignType != null && storageType != null) alignType to storageType else null,
            )
        }

    val atomicByOwner: Map<String, AtomicSpec> = atomicSpecs.associateBy { it.ownerSimple }
    val atomicByRawPrim: Map<String, AtomicSpec> =
        buildMap {
            for (spec in atomicSpecs) {
                spec.rawPrim?.let { put(it, spec) }
            }
        }
    val atomicStorageByKtType: Map<String, Pair<String, String>> =
        buildMap {
            for (spec in atomicSpecs) {
                if (spec.storage != null && spec.ktValueType !in this) {
                    put(spec.ktValueType, spec.storage)
                }
            }
            put("ConstPtr", "Align8" to "ConstPtr")
        }
    val atomicOwnerByNonIntKtType: Map<String, String> =
        buildMap {
            for (spec in atomicSpecs) {
                if (spec.ktValueType != "Int") put(spec.ktValueType, spec.ownerSimple)
            }
            put("ConstPtr", "AtomicPtr")
        }

    fun lookupNonZeroInner(rawPrim: String?, ktType: String?): String? =
        rawPrim?.let(nonZeroByRawPrim::get) ?: ktType?.let(nonZeroByKtType::get)
}

private fun SynType.singleCleanPathIdent(): String? =
    unwrapSynType(this).unqualifiedPath()?.singleSegment()?.cleanIdent()

/** True when a Result or ControlFlow scrutinee has an erased or unknown Kotlin type that still needs `<*, *>` casts. */
internal fun isUntypedTaggedSumScrutinee(
    renderedType: String?,
    isControlFlow: Boolean = false,
): Boolean {
    val clean = renderedType?.removeSuffix("?")?.trim() ?: return false
    if (clean == "Any" || clean == "RustAny" || clean == "*" || clean == "Nothing") return true
    if (clean.length == 1 && clean[0] in 'A'..'Z') return true
    val simpleBase = clean.substringBefore('<').substringAfterLast('.').trim()
    return if (isControlFlow) simpleBase != "ControlFlow" else simpleBase != "Result" && simpleBase != "RustResult"
}

internal fun isUntypedResultScrutinee(renderedType: String?): Boolean =
    isUntypedTaggedSumScrutinee(renderedType, isControlFlow = false)

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
            val segIdent = unwrapSynType(defSyn).unqualifiedPath()?.singleSegment()?.identText()
            val renderedDef = segIdent?.let { subs[rustTypeIdentToKotlin(it)] } ?: renderType(defSyn, selfTypeName)
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
            existingArgs.size == 1 &&
                simpleBase == "Weak" &&
                (rawName !in inspector.declaredTypeNames || rawSegNames.any { it in setOf("sync", "rc", "rcs", "alloc") }) -> true
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
    val rawPrimIdent = rawFirstArgSyn?.singleCleanPathIdent()
    val cleanRendered = renderedFirstArg?.removeSuffix("?")?.trim()
    if (simpleBase == "NonZero") {
        val innerSimple = StdlibSpecialTypeCatalog.lookupNonZeroInner(rawPrimIdent, cleanRendered) ?: return emptyList()
        return listOf(bindOrQualifyResolvedCrossSymbol("$coreNs.num.nichetypes.$innerSimple"))
    }
    val storageSpec =
        rawPrimIdent?.let { StdlibSpecialTypeCatalog.atomicByRawPrim[it]?.storage }
            ?: (rawFirstArgSyn?.let(::unwrapSynType) as? SynType.Ptr)?.let {
                "Align8" to if (cleanRendered == "ConstPtr") "ConstPtr" else "MutPtr"
            }
            ?: cleanRendered?.let(StdlibSpecialTypeCatalog.atomicStorageByKtType::get)
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
    val hintCandidates = listOfNotNull(currentExpectedParseTargetType, currentEnclosingReturnRenderedType)
    val hintParsed =
        hintCandidates.firstNotNullOfOrNull { candidate ->
            KotlinTokenWalker(candidate).firstGenericApplication { owner, topArgs ->
                val secondArg = topArgs.getOrNull(1)?.substringAfterLast('.')
                if (owner == "NonZero" &&
                    topArgs.size == 2 &&
                    secondArg != null &&
                    secondArg.startsWith("NonZero") &&
                    secondArg.endsWith("Inner")
                ) {
                    topArgs[1] to topArgs[0]
                } else {
                    null
                }
            }
        }
    if (hintParsed != null) {
        return hintParsed
    }
    if ("NonZeroInner" in currentTraitAssocParamNames) {
        val tArg = inferExprRenderedType(args[0], selfTypeName).asKotlinRenderedType()?.nonNullText ?: "T"
        return "NonZeroInner" to tArg
    }
    val argExpr = unwrapParenOrGroupExpr(args[0])
    val fieldPrim =
        (argExpr as? Expr.Field)?.let { f ->
            val ownerSimple =
                inferExprRenderedType(f.base, selfTypeName).asKotlinRenderedType()?.simpleName
                    ?: selfTypeName.asKotlinRenderedType()?.simpleName
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
    val rawFieldIdent = fieldPrim?.singleCleanPathIdent()
    val inferredArg = inferExprRenderedType(args[0], selfTypeName)?.removeSuffix("?")?.trim()
    val innerSimple = StdlibSpecialTypeCatalog.lookupNonZeroInner(rawFieldIdent, inferredArg) ?: return null
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val innerRef =
        if (innerSimple in inspector.declaredTypeNames) {
            innerSimple
        } else {
            bindOrQualifyResolvedCrossSymbol("$coreNs.num.nichetypes.$innerSimple")
        }
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
            "Error" ->
                if (rawSegNames.size >= 2 && rawSegNames[rawSegNames.lastIndex - 1].removePrefix("r#") == "fmt") {
                    "$coreNs.fmt.Error"
                } else {
                    null
                }
            else -> null
        } ?: return null
    return "${bindOrQualifyResolvedCrossSymbol(fq)}()"
}

/** True when a call argument expects `ConstPtr` (either from resolved callee AST `*const T` or known `core::ptr` API). */
internal fun isStdlibConstPtrCallParam(rawSegs: List<String>, paramSyn: SynType?, argIdx: Int): Boolean {
    val unwrapped = paramSyn?.let(::unwrapSynType)
    if (unwrapped is SynType.Ptr && unwrapped.mutability is PointerMutability.Const) return true
    val leaf = rawSegs.lastOrNull() ?: return false
    val qualKey = if (rawSegs.size >= 2) "${rawSegs[rawSegs.lastIndex - 1]}::$leaf" else leaf
    if (qualKey in STDLIB_CONST_PTR_TWO_ARG_CALLS) return argIdx == 0 || argIdx == 1
    return argIdx == 0 && (qualKey in STDLIB_CONST_PTR_FIRST_ARG_CALLS || ('<' !in leaf && leaf in STDLIB_CONST_PTR_FIRST_ARG_CALLS))
}

/** True when a raw pointer method parameter expects `ConstPtr`. */
internal fun isStdlibConstPtrMethodParam(rawMethod: String, paramSyn: SynType?, argIdx: Int): Boolean {
    val unwrapped = paramSyn?.let(::unwrapSynType)
    if (unwrapped is SynType.Ptr && unwrapped.mutability is PointerMutability.Const) return true
    return argIdx == 0 && rawMethod in STDLIB_CONST_PTR_FIRST_ARG_METHODS
}

/** Coerces a rendered `MutPtr` expression to `ConstPtr` using `asConstPtr()`. */
internal fun coerceMutPtrArgToConstPtr(renderedArg: String, inferredArgType: String?): String {
    val clean = inferredArgType?.removeSuffix("?")?.substringBefore('<')?.trim() ?: return renderedArg
    if (clean != "MutPtr" && !clean.endsWith(".MutPtr")) return renderedArg
    val trimmed = renderedArg.trim()
    if (trimmed.endsWith(".asConstPtr()") || trimmed.startsWith("ConstPtr(")) return renderedArg
    return "($trimmed).asConstPtr()"
}

internal fun resolveConcreteStdlibAtomicSimpleName(
    rawFirstArgSyn: SynType?,
    renderedFirstArg: String?,
): String? {
    val unwrapped = rawFirstArgSyn?.let(::unwrapSynType)
    if (unwrapped is SynType.Ptr) return "AtomicPtr"
    val rawPrimIdent = rawFirstArgSyn?.singleCleanPathIdent()
    StdlibSpecialTypeCatalog.atomicByRawPrim[rawPrimIdent]?.let { return it.ownerSimple }
    val cleanRendered = renderedFirstArg?.removeSuffix("?")?.trim() ?: return null
    if (cleanRendered == "Int") return if (rawPrimIdent == null) "AtomicUsize" else null
    return StdlibSpecialTypeCatalog.atomicOwnerByNonIntKtType[cleanRendered]
}

internal fun atomicOwnerValueRenderedType(ownerSimple: String): String? =
    StdlibSpecialTypeCatalog.atomicByOwner[ownerSimple]?.ktValueType

internal fun atomicOwnerValueNumericKind(ownerSimple: String): NumericKind? =
    StdlibSpecialTypeCatalog.atomicByOwner[ownerSimple]?.numericKind

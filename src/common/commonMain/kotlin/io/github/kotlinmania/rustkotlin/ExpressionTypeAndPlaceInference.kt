package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.BinOp
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Fields
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.ForeignItem
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PathSegment
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.ReturnType
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.gen.Visit

internal val RAW_POINTER_ARITH_METHODS: Set<String> =
    wordSet(
        "offset add sub wrapping_offset wrapping_add wrapping_sub",
        "byte_offset byte_add byte_sub wrapping_byte_offset wrapping_byte_add wrapping_byte_sub",
    )

internal fun Expr.MethodCall.firstTurbofishTypeArg(): SynType? {
    val argList = turbofish?.args?.toList() ?: return null
    return argList.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
}

internal fun PathArguments.firstAngleTypeArg(): SynType? {
    val ab = this as? PathArguments.AngleBracketed ?: return null
    return ab.args.toList().firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
}

internal fun PathArguments.angleTypeArgs(): List<SynType> {
    val ab = this as? PathArguments.AngleBracketed ?: return emptyList()
    val argList = ab.args.toList()
    return argList.mapNotNull { (it as? GenericArgument.TypeArg)?.type }
}

internal fun Path.lastAngleTypeArgs(): List<SynType> {
    val lastSeg = segments.last() ?: return emptyList()
    return lastSeg.arguments.angleTypeArgs()
}

internal fun SynType.unqualifiedPath(): Path? {
    val tp = this as? SynType.Path ?: return null
    return if (tp.qself == null) tp.path else null
}

internal fun Expr.unqualifiedPath(): Path? {
    val ep = this as? Expr.Path ?: return null
    return if (ep.qself == null) ep.path else null
}

internal fun Generics.typeParamIdentNames(): List<String> {
    val typeParams = params.toList().filterIsInstance<GenericParam.TypeParam>()
    return typeParams.map { it.ident.toString() }
}

internal object PrimitiveArrayTypeCatalog {
    private data class Spec(
        val arrayType: String,
        val elemType: String,
        val numericKind: NumericKind?,
        val layoutFactory: String,
    )

    private val SPECS: List<Spec> =
        listOf(
            Spec("BooleanArray", "Boolean", null, "booleanArray"),
            Spec("ByteArray", "Byte", NumericKind.I8, "byteArray"),
            Spec("UByteArray", "UByte", NumericKind.U8, "uByteArray"),
            Spec("ShortArray", "Short", NumericKind.I16, "shortArray"),
            Spec("UShortArray", "UShort", NumericKind.U16, "uShortArray"),
            Spec("IntArray", "Int", NumericKind.I32, "intArray"),
            Spec("UIntArray", "UInt", NumericKind.U32, "uIntArray"),
            Spec("LongArray", "Long", NumericKind.I64, "longArray"),
            Spec("ULongArray", "ULong", NumericKind.U64, "uLongArray"),
            Spec("FloatArray", "Float", NumericKind.F32, "floatArray"),
            Spec("DoubleArray", "Double", NumericKind.F64, "doubleArray"),
        )

    private val BY_ARRAY_TYPE: Map<String, Spec> = SPECS.associateBy { it.arrayType }
    private val FACTORY_BY_KIND: Map<NumericKind, String> =
        SPECS
            .mapNotNull { spec -> spec.numericKind?.let { it to spec.layoutFactory } }
            .toMap()
    private val ARRAY_BY_ELEM: Map<String, String> =
        SPECS.associate { it.elemType to it.arrayType } + ("kotlin.Float" to "FloatArray")

    private val NUMERIC_KIND_BY_ELEM: Map<String, NumericKind> =
        mapOf(
            "Byte" to NumericKind.I8,
            "UByte" to NumericKind.U8,
            "Short" to NumericKind.I16,
            "UShort" to NumericKind.U16,
            "Int" to NumericKind.I32,
            "UInt" to NumericKind.U32,
            "Long" to NumericKind.I64,
            "ULong" to NumericKind.U64,
            "I128" to NumericKind.I128,
            "U128" to NumericKind.U128,
            "F16" to NumericKind.F16,
            "Float" to NumericKind.F32,
            "kotlin.Float" to NumericKind.F32,
            "Double" to NumericKind.F64,
            "F128" to NumericKind.F128,
        )

    fun elemKind(containerType: String, coreNs: String): NumericKind? {
        BY_ARRAY_TYPE[containerType]?.numericKind?.let { return it }
        val listElem =
            when {
                containerType.startsWith("List<") && containerType.endsWith(">") ->
                    containerType.removePrefix("List<").dropLast(1).trim()
                containerType.startsWith("MutableList<") && containerType.endsWith(">") ->
                    containerType.removePrefix("MutableList<").dropLast(1).trim()
                else -> return null
            }
        val normalizedElem = if (listElem.startsWith("$coreNs.")) listElem.removePrefix("$coreNs.") else listElem
        return NUMERIC_KIND_BY_ELEM[normalizedElem]
    }

    fun elemType(arrayType: String, f32Name: String): String? {
        val spec = BY_ARRAY_TYPE[arrayType] ?: return null
        return if (spec.arrayType == "FloatArray") f32Name else spec.elemType
    }

    fun arrayTypeForElem(elemTy: String?): String? =
        elemTy?.trim()?.let(ARRAY_BY_ELEM::get)

    fun toArrayMethodName(arrayType: String): String? =
        if (arrayType in BY_ARRAY_TYPE) "to$arrayType" else null

    fun isPrimitiveArrayType(type: String): Boolean =
        type in BY_ARRAY_TYPE

    fun layoutFactoryForKind(kind: NumericKind?): String? =
        kind?.let(FACTORY_BY_KIND::get)
}

internal fun KotlinJ2KEmitter.resolveUnnamedTupleFieldSynType(
    baseExpr: Expr,
    index: Int,
): SynType? {
    val unwrappedBase = unwrapParenOrGroupExpr(baseExpr) as? Expr.Path ?: return null
    if (unwrappedBase.qself != null || unwrappedBase.path.segments.len() != 1) return null
    val raw = unwrappedBase.path.firstCleanIdent() ?: return null
    val baseSynTy =
        when (raw) {
            "self" -> currentStandaloneImplSelfSynType
            else -> {
                val ktLocal = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
                currentSourceParameterTypes[ktLocal] ?: currentSourceParameterTypes[raw]
            }
        } ?: return null
    val unwrappedRef = unwrapSynType(baseSynTy).let { (it as? SynType.Reference)?.elem ?: it }
    val tupleTy = unwrapSynType(unwrappedRef) as? SynType.Tuple ?: return null
    return tupleTy.elems.toList().getOrNull(index)
}

internal fun KotlinJ2KEmitter.resolveContainerElementTraitCallee(callee: String): String? {
    val standaloneTraitPath = currentStandaloneImplTraitPath ?: return null
    val standaloneSelfSynType = currentStandaloneImplSelfSynType ?: return null
    val traitName = standaloneTraitPath.lastKotlinTypeName() ?: return null
    if (!callee.startsWith("$traitName.")) return null
    val methodPart = callee.removePrefix("$traitName.")
    if (methodPart.isEmpty() || '.' in methodPart) return null
    val selfPath = (unwrapSynType(standaloneSelfSynType) as? SynType.Path)?.takeIf { it.qself == null } ?: return null
    val lastSelfSeg = selfPath.path.segments.last() ?: return null
    if (lastSelfSeg.identText() != "Vec") return null
    val elemSynType = lastSelfSeg.arguments.firstAngleTypeArg() ?: return null
    return disambiguateReceiverlessTraitImplFnName(standaloneTraitPath, methodPart, elemSynType)
}

internal fun KotlinJ2KEmitter.extractOwnerAndFieldName(expr: Expr, selfTypeName: String?): Pair<String?, String>? {
    val core = unwrapTransparentCastExpr(expr) as? Expr.Field ?: return null
    val member = core.member as? Member.Named ?: return null
    val fieldName = rustFieldIdentToKotlin(member.ident.toString())
    val baseCore = unwrapTransparentCastExpr(core.base)

    fun normalizeDeclaredOwnerType(rawRendered: String?): String? {
        val base = rawRendered?.trim()?.removeSuffix("?")?.simpleKotlinTypeName() ?: return null
        return base.takeIf { it.isNotEmpty() && it in inspector.declaredTypeNames }
    }

    val ownerType =
        if (baseCore is Expr.Path && baseCore.qself == null && baseCore.path.segments.len() == 1) {
            val baseRaw = baseCore.path.firstCleanIdent()
            if (baseRaw == "self") {
                selfTypeName?.substringBefore('<')?.trim()
            } else {
                val resolvedLocal = baseRaw?.let { scopeBuilder.resolveLocalKotlinName(it) ?: rustValueIdentToKotlin(it) }
                normalizeDeclaredOwnerType(resolvedLocal?.let { currentTypedLocals[it] })
                    ?: normalizeDeclaredOwnerType(inferExprRenderedType(baseCore, selfTypeName))
                    ?: baseRaw?.let(::rustTypeIdentToKotlin)?.takeIf { it in inspector.declaredTypeNames }
            }
        } else {
            normalizeDeclaredOwnerType(inferExprRenderedType(baseCore, selfTypeName))
        }
    return ownerType to fieldName
}

internal fun KotlinJ2KEmitter.isDequeIterFieldExpr(expr: Expr, selfTypeName: String?): Boolean {
    val (ownerType, fieldName) = extractOwnerAndFieldName(expr, selfTypeName) ?: return false
    return ownerType != null && fieldName in inspector.dequeIterFieldsByType[ownerType].orEmpty()
}

internal fun KotlinJ2KEmitter.isMutableVecExpr(expr: Expr, renderedReceiver: String, selfTypeName: String?): Boolean {
    if (renderedReceiver in currentMutableListLocals) return true
    val (ownerType, fieldName) = extractOwnerAndFieldName(expr, selfTypeName) ?: return false
    if (ownerType != null && fieldName in inspector.vecFieldsByType[ownerType].orEmpty()) return true
    if (ownerType == null && fieldName == "inner" && inspector.vecFieldsByType.values.any { "inner" in it }) {
        val baseCore = unwrapTransparentCastExpr((unwrapTransparentCastExpr(expr) as Expr.Field).base)
        if (baseCore is Expr.Path && baseCore.qself == null && baseCore.path.segments.len() == 1) {
            val rawVar = baseCore.path.firstCleanIdent().orEmpty()
            if (rustTypeIdentToKotlin(rawVar) in inspector.vecFieldsByType) return true
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.isKnownNullableOptionExpr(expr: Expr, renderedReceiver: String, selfTypeName: String?): Boolean =
    ExprTypeInferenceEngine(this, selfTypeName).isKnownNullableOption(expr, renderedReceiver)

internal fun KotlinJ2KEmitter.unwrapZeroArgClosureText(renderedClosure: String): String {
    val trimmed = renderedClosure.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}") && !trimmed.contains("->")) {
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        return if ('\n' !in inner && ';' !in inner) inner else "run $trimmed"
    }
    if (trimmed.startsWith("::")) return "${trimmed.removePrefix("::")}()"
    if ("::" in trimmed) return "${trimmed.replaceFirst("::", ".")}()"
    return "$trimmed()"
}

internal fun KotlinJ2KEmitter.primitiveArrayElementNumericKind(arrayType: String): NumericKind? =
    PrimitiveArrayTypeCatalog.elemKind(arrayType, CoreIntrinsicLowering.resolveCoreNamespace(headerContext))

internal fun KotlinJ2KEmitter.inferArrayElementNumericKind(expr: Expr, selfTypeName: String?): NumericKind? {
    var containerTy = inferExprRenderedType(expr, selfTypeName)?.removeSuffix("?")?.trim() ?: return null
    var depth = 0
    while (depth <= 6) {
        primitiveArrayElementNumericKind(containerTy)?.let { return it }
        extractIndexedElementRenderedType(containerTy, selfTypeName)?.let(this::resolveNumericKind)?.let { return it }
        if (depth == 6) break
        val aliasSyn = inspector.typeAliasTargets[containerTy] ?: break
        val rendered = renderType(aliasSyn, selfTypeName = null).removeSuffix("?").trim()
        if (rendered == containerTy) break
        containerTy = rendered
        depth++
    }
    return null
}

internal fun KotlinJ2KEmitter.extractIndexedElementRenderedType(containerTy: String, selfTypeName: String?): String? {
    var cur = containerTy.removeSuffix("?").trim()
    var depth = 0
    while (depth <= 6) {
        PrimitiveArrayTypeCatalog.elemType(cur, kotlinF32TypeName())?.let { return it }
        when {
            cur.startsWith("List<") && cur.endsWith(">") -> return cur.removePrefix("List<").removeSuffix(">").trim()
            cur.startsWith("MutableList<") && cur.endsWith(">") -> return cur.removePrefix("MutableList<").removeSuffix(">").trim()
            cur.startsWith("Array<") && cur.endsWith(">") -> return cur.removePrefix("Array<").removeSuffix(">").trim()
        }
        val aliasSyn = inspector.typeAliasTargets[cur] ?: break
        val next = renderType(aliasSyn, selfTypeName).removeSuffix("?").trim()
        if (next == cur) break
        cur = next
        depth++
    }
    return null
}

internal fun KotlinJ2KEmitter.isKnownNonNullableNonOptionReceiver(
    recvExpr: Expr,
    renderedReceiver: String,
    selfTypeName: String?,
): Boolean {
    if (renderedReceiver in currentNullableLocals) return false
    fun isNullableOrOptionShape(ty: String): Boolean =
        ty.isEmpty() || ty.endsWith("?") || ty == "Any" || ty == "Option" || ty.startsWith("Option<")
    val rawTy = (currentTypedLocals[renderedReceiver] ?: inferExprRenderedType(recvExpr, selfTypeName))?.trim() ?: return false
    if (isNullableOrOptionShape(rawTy)) return false
    var cur = rawTy
    var depth = 0
    while (depth < 8) {
        val base = cur.simpleKotlinTypeName()
        if (base.isEmpty() || base in activeTypeParamNames) return false
        val next =
            inspector.typeAliasTargets[base]?.let { renderType(it, selfTypeName).trim() }
                ?: headerContext.crateTypeAliasDefaults[base]?.trim()
                ?: break
        if (isNullableOrOptionShape(next)) return false
        if (next == cur) break
        cur = next
        depth++
    }
    return true
}

internal data class InferExprCacheKey(
    val expr: Expr,
    val selfTypeName: String?,
    val expectedParseTarget: String?,
    val enclosingReturn: String?,
    val inExternAbi: Boolean,
    val typedLocals: Map<String, String>,
    val sourceParamTypes: Map<String, SynType>,
)

internal fun KotlinJ2KEmitter.inferExprRenderedType(expr: Expr, selfTypeName: String?): String? {
    val shouldMemoize =
        expr is Expr.MethodCall ||
            expr is Expr.Call ||
            expr is Expr.Binary ||
            expr is Expr.BlockExpr ||
            expr is Expr.If ||
            expr is Expr.Unsafe
    if (!shouldMemoize) return ExprTypeInferenceEngine(this, selfTypeName).inferRenderedType(expr)
    val cacheKey =
        InferExprCacheKey(
            expr = expr,
            selfTypeName = selfTypeName,
            expectedParseTarget = currentExpectedParseTargetType,
            enclosingReturn = currentEnclosingReturnRenderedType,
            inExternAbi = inExternAbiSignature,
            typedLocals = currentTypedLocals,
            sourceParamTypes = currentSourceParameterTypes,
        )
    if (inferExprRenderedTypeCache.containsKey(cacheKey)) {
        return inferExprRenderedTypeCache[cacheKey]
    }
    val computed = ExprTypeInferenceEngine(this, selfTypeName).inferRenderedType(expr)
    inferExprRenderedTypeCache[cacheKey] = computed
    return computed
}

internal fun KotlinJ2KEmitter.inferExprNumericKind(expr: Expr, selfTypeName: String?): NumericKind? =
    ExprTypeInferenceEngine(this, selfTypeName).inferNumericKind(expr)

/**
 * Stateful AST expression type, numeric-kind, and erased-`Option` inference engine for a single
 * `(emitter, selfTypeName)` scope.
 */
private class ExprTypeInferenceEngine(
    private val emitter: KotlinJ2KEmitter,
    private val selfTypeName: String?,
) {
    private val inspector get() = emitter.inspector
    private val headerContext get() = emitter.headerContext
    private val scopeBuilder get() = emitter.scopeBuilder
    private val coreNs: String get() = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)

    private fun isInCoreOrRustLangScope(): Boolean =
        "core" in headerContext.crateNamespaces ||
            headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true

    private fun registerCorePtrType(isMut: Boolean, pointee: String? = null): String {
        val ptrType = if (isMut) "MutPtr" else "ConstPtr"
        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.ptr.$ptrType")
        return if (pointee != null) "$ptrType<$pointee>" else ptrType
    }

    private fun hasOnlyIgnoredPathPrefixes(exprPath: Expr.Path): Boolean {
        val segs = exprPath.path.segments.toList()
        return exprPath.qself == null &&
            segs.isNotEmpty() &&
            segs.dropLast(1).all { it.arguments is PathArguments.None && it.cleanIdent() in IGNORED_PATH_PREFIXES }
    }

    private fun inferBlockTailType(stmts: List<Stmt>): String? {
        val tail = stmts.lastOrNull() as? Stmt.ExprStmt
        return if (tail != null && tail.semiToken == null) emitter.inferExprRenderedType(tail.expr, selfTypeName) else null
    }

    fun isKnownNullableOption(expr: Expr, renderedReceiver: String): Boolean {
        if (emitter.hasDeclaredOptionEnum()) return false
        val core = emitter.unwrapTransparentCastExpr(expr)
        if (emitter.unwrapUnqualifiedOptionSomeArg(core) != null) return true
        if (core is Expr.Path && core.qself == null && core.path.cleanIdentStrings() in NONE_PATH_SHAPES) {
            return true
        }
        if (core is Expr.MethodCall) {
            val m = core.method.toString()
            if (m in NON_OPTION_METHOD_NAMES) return false
            if (m == "ok" &&
                core.args.isEmpty() &&
                "ok" !in inspector.declaredTopLevelFnRawNames &&
                inspector.implMethodReturnSynTypes.keys.none { it.second == "ok" }
            ) {
                return true
            }
            if (m in KNOWN_OPTION_RETURNING_METHODS) return true
            if (m == "fold" || m == "rfold") {
                val firstArg = core.args.first()
                if (firstArg != null) {
                    val unwrapped = emitter.unwrapParenOrGroupExpr(firstArg)
                    if (unwrapped is Expr.Path && unwrapped.qself == null && unwrapped.path.lastIdentText() == "None") {
                        return true
                    }
                }
            }
            if (m in OPTION_CHAIN_PRESERVING_METHODS && isKnownNullableOption(core.receiver, "")) {
                return true
            }
        }
        if (core is Expr.Call) {
            val fnPathExpr = emitter.unwrapParenOrGroupExpr(core.func) as? Expr.Path
            val fnPath = if (fnPathExpr != null && fnPathExpr.qself == null) fnPathExpr.path else null
            val segs = fnPath?.segments?.toList().orEmpty()
            if (segs.size >= 2 && isInCoreOrRustLangScope()) {
                val rawCallNames = segs.map { it.cleanIdent() }
                val owner = rawCallNames[rawCallNames.lastIndex - 1]
                val resolvedOwner = if (owner == "Self") selfTypeName?.simpleKotlinTypeName() else owner
                if (rawCallNames.last() == "new" && resolvedOwner in NULLABLE_NEW_WRAPPER_OWNERS) {
                    return true
                }
            }
            val lastRaw = segs.lastOrNull()?.cleanIdent()
            if (lastRaw != null) {
                if (lastRaw == "memchr" || lastRaw == "memrchr") return true
                val ktFn = rustFunctionIdentToKotlin(lastRaw)
                val localRet = inspector.topLevelFnReturnSynTypes[lastRaw] ?: inspector.topLevelFnReturnSynTypes[ktFn]
                if (localRet != null) {
                    val uRet = unwrapSynType(localRet) as? SynType.Path
                    if (uRet?.path?.lastIdentText() == "Option") return true
                } else if (headerContext.externalCrateNullableReturnFns.isNotEmpty()) {
                    val importedPkg = emitter.importedSimpleNameToPkg[ktFn]
                    if (importedPkg != null && "$importedPkg.$ktFn" in headerContext.externalCrateNullableReturnFns) {
                        return true
                    }
                    if (segs.size == 1 &&
                        headerContext.externalCrateSymbolPackages.values.any { symMap ->
                            symMap[ktFn].orEmpty().any { pkg -> "$pkg.$ktFn" in headerContext.externalCrateNullableReturnFns }
                        }
                    ) {
                        return true
                    }
                }
            }
        }
        val inferred = emitter.inferExprRenderedType(expr, selfTypeName)?.trim()
        if (inferred != null) {
            if (rustResultTypeArguments(inferred) != null || inferred.startsWith("Result<") || inferred.startsWith("RustResult<")) {
                return false
            }
            if (inferred.endsWith("?") && rawPointerPointeeType(inferred) == null) return true
        }
        if (renderedReceiver in emitter.currentNullableLocals || renderedReceiver.contains("?.let(")) return true
        val (ownerType, fieldName) = emitter.extractOwnerAndFieldName(expr, selfTypeName) ?: return false
        return ownerType != null && fieldName in inspector.nullableFieldsByType[ownerType].orEmpty()
    }

    fun inferRenderedType(expr: Expr): String? =
        when (expr) {
            is Expr.Paren -> emitter.inferExprRenderedType(expr.expr, selfTypeName)
            is Expr.Group -> emitter.inferExprRenderedType(expr.expr, selfTypeName)
            is Expr.Reference -> inferReferenceType(expr)
            is Expr.RawAddr ->
                if (headerContext.rawAbiMode) {
                    "Long"
                } else {
                    emitter.inferExprRenderedType(expr.expr, selfTypeName)?.let { pointee ->
                        registerCorePtrType(isMut = expr.mutability is PointerMutability.Mut, pointee = pointee)
                    }
                }
            is Expr.Await -> emitter.inferExprRenderedType(expr.base, selfTypeName)
            is Expr.Try -> inferTryType(expr)
            is Expr.Cast -> emitter.renderType(expr.ty, selfTypeName)
            is Expr.Struct ->
                when (val lastSeg = expr.path.lastIdentText()) {
                    null -> null
                    "Self" -> selfTypeName?.substringBefore('<')?.trim()
                    else -> rustTypeIdentToKotlin(lastSeg)
                }
            is Expr.Path -> inferPathType(expr)
            is Expr.Unary -> inferUnaryType(expr)
            is Expr.Field -> inferFieldType(expr)
            is Expr.Index ->
                if (expr.index is Expr.Range) {
                    emitter.inferExprRenderedType(expr.expr, selfTypeName)
                } else {
                    emitter.inferExprRenderedType(expr.expr, selfTypeName)?.let { emitter.extractIndexedElementRenderedType(it, selfTypeName) }
                }
            is Expr.Call -> inferCallType(expr)
            is Expr.MethodCall -> inferMethodCallType(expr)
            is Expr.BlockExpr -> inferBlockTailType(expr.block.stmts)
            is Expr.Unsafe -> inferBlockTailType(expr.block.stmts)
            is Expr.If -> inferBlockTailType(expr.thenBranch.stmts)
            is Expr.Repeat -> {
                val elemTy = emitter.inferExplicitArrayElementRenderedType(expr.expr, selfTypeName)
                emitter.primitiveArrayTypeForElementRenderedType(elemTy) ?: elemTy?.let { "List<$it>" }
            }
            is Expr.Tuple -> inferTupleType(expr)
            is Expr.Array -> {
                val elemTy = expr.elems.toList().firstNotNullOfOrNull { emitter.inferExplicitArrayElementRenderedType(it, selfTypeName) }
                emitter.primitiveArrayTypeForElementRenderedType(elemTy) ?: elemTy?.let { "List<$it>" }
            }
            else -> null
        }

    private fun inferReferenceType(expr: Expr.Reference): String? {
        val innerSynType = emitter.sourceInitializerType(expr.expr)
        val innerRendered =
            emitter.inferExprRenderedType(expr.expr, selfTypeName) ?: innerSynType?.let { emitter.renderType(it, selfTypeName) }
        return innerRendered?.let { rendered ->
            val local = emitter.extractSingleSegmentLocalKotlinName(expr.expr)
            val isAddrMut =
                expr.mutability != null &&
                    !headerContext.rawAbiMode &&
                    (
                        (local != null && local in emitter.currentAddressableLocals) ||
                            (local == null && innerSynType != null && emitter.shouldUseAddressBackedMutableReference(innerSynType, selfTypeName))
                    )
            if (isAddrMut) {
                CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.mem.RustMutableReference")
                "RustMutableReference<$rendered>"
            } else {
                rendered
            }
        }
    }

    private fun inferTryType(expr: Expr.Try): String? {
        val inner = emitter.unwrapParenOrGroupExpr(expr.expr)
        if (inner is Expr.MethodCall &&
            inner.method.toString().removePrefix("r#") == "parse" &&
            inner.args.isEmpty() &&
            inner.turbofish?.args?.size == 1
        ) {
            return inner.firstTurbofishTypeArg()?.let { emitter.renderType(it, selfTypeName) }
        }
        val innerTy = emitter.inferExprRenderedType(expr.expr, selfTypeName)?.trim() ?: return null
        return rustResultOkPayloadType(innerTy)
            ?: if (innerTy.endsWith("?")) innerTy.removeSuffix("?").trim() else null
    }

    private fun inferPathType(expr: Expr.Path): String? {
        emitter.resolvePrimitiveAssociatedConstKind(expr.path, expr.qself)?.let { return emitter.numericKindToPureTypeName(it) }
        val segs = expr.path.segments.toList()
        if (hasOnlyIgnoredPathPrefixes(expr)) {
            val raw = segs.last().cleanIdent()
            if (raw == "self" && segs.size == 1) return selfTypeName
            val ktConst = inspector.declaredConstNames[raw]
            val constTy = ktConst?.let { inspector.declaredConstTypes[it] }
            if (constTy != null) return emitter.renderType(constTy, selfTypeName)
            if (segs.size == 1) {
                val ktLocal = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
                return emitter.currentTypedLocals[ktLocal]
                    ?: when {
                        raw in inspector.unitStructNames -> raw
                        isInCoreOrRustLangScope() -> emitter.resolveStdlibUnitStructValueExpr(listOf(raw))?.removeSuffix("()")
                        else -> null
                    }
            }
            return null
        }
        return if (expr.qself == null && isInCoreOrRustLangScope()) {
            emitter.resolveStdlibUnitStructValueExpr(segs.map { it.cleanIdent() })?.removeSuffix("()")
        } else {
            null
        }
    }

    private fun inferUnaryType(expr: Expr.Unary): String? =
        when (expr.op) {
            is UnOp.Deref -> {
                val innerTy = emitter.inferExprRenderedType(expr.expr, selfTypeName)
                rawPointerPointeeType(innerTy)
                    ?: mutableReferencePointeeType(innerTy)
                    ?: mutablePlaceValueType(innerTy)
                    ?: refCellGuardPointeeType(innerTy)
                    ?: emitter.borrowedPlaceLocalName(expr.expr)?.let { emitter.currentTypedLocals[it] }
                    ?: innerTy?.removeSuffix("?")
            }
            is UnOp.Neg -> emitter.inferExprRenderedType(expr.expr, selfTypeName)
            is UnOp.NotOp ->
                if (emitter.isKnownIntegerBitmaskExpr(expr.expr) || inferNumericKind(expr.expr)?.isIntegerNumericKind() == true) {
                    emitter.inferExprRenderedType(expr.expr, selfTypeName)
                } else {
                    "Boolean"
                }
        }

    private fun inferFieldType(expr: Expr.Field): String? {
        if (expr.member is Member.Unnamed) {
            val tupleFieldTy = emitter.resolveUnnamedTupleFieldSynType(expr.base, (expr.member as Member.Unnamed).index.index.toInt())
            if (tupleFieldTy != null) return emitter.renderType(tupleFieldTy, selfTypeName)
        }
        val baseReferenceTy = emitter.inferExprRenderedType(expr.base, selfTypeName) ?: return null
        val baseTy =
            (mutableReferencePointeeType(baseReferenceTy) ?: refCellGuardPointeeType(baseReferenceTy) ?: baseReferenceTy)
                .removeSuffix("?")
                .trim()
        val rawOwnerKt = baseTy.simpleKotlinTypeName()
        if (rawOwnerKt.isEmpty()) return null
        val aliasedOwnerKt = inspector.typeAliasTargets[rawOwnerKt]?.let { emitter.renderType(it, null).simpleKotlinTypeName() }
        val ownerKt = aliasedOwnerKt?.takeIf { it.isNotEmpty() && it in inspector.structFieldSynTypesByType } ?: rawOwnerKt
        return when (val member = expr.member) {
            is Member.Named -> {
                val fieldKt = rustFieldIdentToKotlin(member.ident.toString())
                inspector.structFieldSynTypesByType[ownerKt]?.get(fieldKt)?.let { return emitter.renderType(it, ownerKt) }
                lookupScopedCrateFieldType(baseTy, ownerKt, fieldKt)
                    ?: if (aliasedOwnerKt != null && aliasedOwnerKt != rawOwnerKt) {
                        lookupScopedCrateFieldType(aliasedOwnerKt, aliasedOwnerKt, fieldKt)
                    } else {
                        null
                    }
            }
            is Member.Unnamed -> {
                val idx = member.index.index.toInt()
                if (ownerKt == "Pair" || ownerKt == "Triple") {
                    val ltIdx = baseTy.indexOf('<')
                    val gtIdx = baseTy.lastIndexOf('>')
                    if (ltIdx >= 0 && gtIdx > ltIdx) {
                        val typeArgs = PhantomRawPointerTypeStripper.splitTopLevelCommaList(baseTy.substring(ltIdx + 1, gtIdx)).map { it.trim() }
                        typeArgs.getOrNull(idx)?.let { return it }
                    }
                }
                val arity = inspector.tupleStructArityByType[ownerKt]
                val primaryField = if (arity != null) tupleFieldPropertyName(idx, arity) else emitter.tupleAccessMemberName(expr.base, idx, selfTypeName)
                inspector.structFieldSynTypesByType[ownerKt]?.get(primaryField)?.let { return emitter.renderType(it, ownerKt) }
                if (idx == 0 && primaryField != "value") {
                    inspector.structFieldSynTypesByType[ownerKt]?.get("value")?.let { return emitter.renderType(it, ownerKt) }
                    inspector.structFieldSynTypesByType[ownerKt]?.get("field0")?.let { return emitter.renderType(it, ownerKt) }
                }
                val candidateFields = if (idx == 0 && primaryField != "value") listOf(primaryField, "value") else listOf(primaryField)
                candidateFields.firstNotNullOfOrNull { fName -> lookupScopedCrateFieldType(baseTy, ownerKt, fName) }
            }
        }
    }

    private fun lookupScopedCrateFieldType(
        scopedType: String,
        fallbackOwner: String,
        fieldName: String,
    ): String? =
        emitter.scopedAbiKeysForRenderedType(scopedType).firstNotNullOfOrNull { key ->
            headerContext.crateStructFieldTypes["$key.$fieldName"]
        } ?: headerContext.crateStructFieldTypes["$fallbackOwner.$fieldName"]

    private fun inferCallType(expr: Expr.Call): String? {
        emitter.resolvePrimitiveFromBytesSpec(expr)?.let { return it.second }
        if (isInCoreOrRustLangScope()) {
            val fnPath = (emitter.unwrapParenOrGroupExpr(expr.func) as? Expr.Path)?.takeIf { it.qself == null }
            val rawSegs = fnPath?.path?.cleanIdentStrings().orEmpty()
            if (rawSegs.size >= 2) {
                val owner = rawSegs[rawSegs.lastIndex - 1]
                val method = rawSegs.last()
                val resolvedOwner = if (owner == "Self") selfTypeName?.simpleKotlinTypeName() else owner
                when {
                    resolvedOwner == "NonNull" && (method == "new" || method == "new_unchecked" || method == "dangling") -> {
                        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.ptr.nonnull.NonNull")
                        return if (method == "new") "NonNull?" else "NonNull"
                    }
                    resolvedOwner == "Alignment" && (method == "new" || method == "new_unchecked") -> {
                        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.mem.alignment.Alignment")
                        return if (method == "new") "Alignment?" else "Alignment"
                    }
                    resolvedOwner == "NonZero" && (method == "new" || method == "new_unchecked") -> {
                        val kotlinMethod = if (method == "new") "new" else "newUnchecked"
                        val resolvedArgs = emitter.resolveNonZeroNewExplicitTypeArgs("NonZero.$kotlinMethod", expr.args.toList(), selfTypeName)
                        if (resolvedArgs != null) {
                            CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.num.nonzero.NonZero")
                            return "NonZero<${resolvedArgs.second}, ${resolvedArgs.first}>${if (method == "new") "?" else ""}"
                        }
                    }
                    resolvedOwner != null && atomicOwnerValueRenderedType(resolvedOwner) != null && method == "new" -> {
                        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.sync.atomic.$resolvedOwner")
                        return resolvedOwner
                    }
                }
            }
        }
        if ("core" in headerContext.crateNamespaces) {
            val calleePath = expr.func as? Expr.Path
            val rawCalleeSegs = if (calleePath != null && calleePath.qself == null) calleePath.path.cleanIdentStrings() else emptyList()
            val isFromRawPartsCall =
                expr.args.len() == 2 &&
                    (
                        rawCalleeSegs == listOf("from_raw_parts") ||
                            rawCalleeSegs == listOf("from_raw_parts_mut") ||
                            rawCalleeSegs.takeLast(2) == listOf("slice", "from_raw_parts") ||
                            rawCalleeSegs.takeLast(2) == listOf("slice", "from_raw_parts_mut")
                    )
            if (isFromRawPartsCall) {
                val ptrTy = emitter.inferExprRenderedType(expr.args.toList()[0], selfTypeName)
                val elem = rawPointerPointeeType(ptrTy)
                if (elem != null) {
                    return if (rawCalleeSegs.last().endsWith("_mut")) "MutableList<$elem>" else "List<$elem>"
                }
            }
        }
        emitter.parseStaticPrimitiveCallSpec(expr)?.let { spec ->
            when (spec.methodName) {
                "from_bits" -> return emitter.numericKindToPureTypeName(spec.primKind)
                "from_str_radix" ->
                    if ("core" in headerContext.crateNamespaces) {
                        val elem = emitter.numericKindToPureTypeName(spec.primKind)
                        return if (emitter.usesTaggedCoreResult()) "${emitter.sourceResultName()}<$elem, ParseIntError>" else "Result<$elem>"
                    }
                else ->
                    emitter
                        .inferPrimitiveOpReturnRenderedType(spec.primKind, spec.methodName, spec.turbofishType, selfTypeName)
                        ?.let { return it }
            }
        }
        CoreIntrinsicLowering.inferCoreIntrinsicReturnRenderedType(expr, selfTypeName, emitter)?.let { return it }
        val fnPath = (expr.func as? Expr.Path)?.takeIf { it.qself == null } ?: return null
        val lastCallIdent = fnPath.path.lastCleanIdent()
        if (lastCallIdent in LAYOUT_QUERY_FN_IDENTS) {
            return when {
                emitter.currentExpectedNumericKind != null -> null
                headerContext.rawAbiMode -> "ULong"
                else -> "Int"
            }
        }
        if (lastCallIdent == "strlen" &&
            (emitter.currentExpectedNumericKind == NumericKind.I32 || emitter.currentEnclosingReturnRenderedType == "Int")
        ) {
            return "Int"
        }
        if (lastCallIdent == "type_name") return "String"
        if ((lastCallIdent == "memchr" || lastCallIdent == "memrchr") &&
            lastCallIdent !in inspector.declaredTopLevelFnRawNames &&
            "memchr" !in inspector.declaredTypeNames
        ) {
            return "Int?"
        }

        fun resolveDeclaredOrCrateFnRet(ktFn: String, rawFn: String): String? {
            val synRet = inspector.topLevelFnReturnSynTypes[rawFn] ?: inspector.topLevelFnReturnSynTypes[ktFn]
            if (synRet != null) {
                val prevExtern = emitter.inExternAbiSignature
                if (ktFn in inspector.externFnKotlinNames) emitter.inExternAbiSignature = true
                return try {
                    emitter.renderType(synRet, selfTypeName)
                } finally {
                    emitter.inExternAbiSignature = prevExtern
                }
            }
            return emitter.scopedAbiKeysForRenderedType(ktFn).firstNotNullOfOrNull { headerContext.crateFnReturnTypes[it] }
                ?: headerContext.crateFnReturnTypes[ktFn]
        }

        val rawSegList = fnPath.path.segments.toList()
        val segs = rawSegList.filter { it.identText() !in IGNORED_PATH_PREFIXES }
        return when {
            segs.size == 1 -> {
                val raw = segs[0].cleanIdent()
                when {
                    raw == "Self" -> selfTypeName?.substringBefore('<')?.trim()
                    raw.isNotEmpty() && raw.first().isUpperCase() && raw !in inspector.declaredTopLevelFnRawNames -> {
                        val ktType = rustTypeIdentToKotlin(raw)
                        val knownCrateType =
                            emitter.scopedAbiKeysForRenderedType(ktType).any {
                                it in headerContext.crateSymbolSubpackages || "$it.value" in headerContext.crateStructFieldTypes
                            }
                        if (ktType in inspector.declaredTypeNames || knownCrateType) {
                            ktType
                        } else {
                            resolveDeclaredOrCrateFnRet(rustFunctionIdentToKotlin(raw), raw)
                        }
                    }
                    else -> {
                        val localKt = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
                        extractCallableReturnRenderedType(emitter.currentTypedLocals[localKt])
                            ?: resolveDeclaredOrCrateFnRet(emitter.activeLocalForeignFnRenames[raw] ?: rustFunctionIdentToKotlin(raw), raw)
                    }
                }
            }
            segs.size >= 2 -> inferMultiSegmentCallType(segs)
            else -> null
        }
    }

    private fun inferMultiSegmentCallType(segs: List<PathSegment>): String? {
        val penultSeg = segs[segs.size - 2]
        val lastSeg = segs[segs.size - 1]
        val penultRaw = penultSeg.cleanIdent()
        val lastRaw = lastSeg.cleanIdent()
        val penultTypeArgs =
            (penultSeg.arguments as? PathArguments.AngleBracketed)
                ?.args
                ?.toList()
                .orEmpty()
                .filterIsInstance<GenericArgument.TypeArg>()
                .map { emitter.renderType(it.type, selfTypeName) }
        val isFactoryCtor = lastRaw == "new" || lastRaw == "with_capacity" || lastRaw == "from"
        when {
            penultRaw == "String" && isFactoryCtor && "String" !in inspector.declaredTypeNames -> return "String"
            penultRaw == "Vec" && isFactoryCtor && "Vec" !in inspector.declaredTypeNames ->
                return if (penultTypeArgs.size == 1) "MutableList<${penultTypeArgs[0]}>" else "MutableList<*>"
            (penultRaw == "HashMap" || penultRaw == "BTreeMap") && isFactoryCtor && penultRaw !in inspector.declaredTypeNames ->
                return if (penultTypeArgs.size >= 2) "MutableMap<${penultTypeArgs[0]}, ${penultTypeArgs[1]}>" else "MutableMap<*, *>"
            (penultRaw == "HashSet" || penultRaw == "BTreeSet") && isFactoryCtor && penultRaw !in inspector.declaredTypeNames ->
                return if (penultTypeArgs.isNotEmpty()) "MutableSet<${penultTypeArgs[0]}>" else "MutableSet<*>"
            atomicOwnerValueRenderedType(penultRaw) != null && lastRaw == "new" && penultRaw !in inspector.declaredTypeNames ->
                return penultRaw
            headerContext.scalarChar && (penultRaw == "char" || penultRaw == "RustChar") && "Char" !in inspector.declaredTypeNames ->
                when (lastRaw) {
                    "from_u32" -> return "RustChar?"
                    "from_u32_unchecked", "from" -> return "RustChar"
                    "try_from" ->
                        return if (emitter.usesTaggedCoreResult()) {
                            "${emitter.sourceResultName()}<RustChar, CharTryFromError>"
                        } else {
                            "Result<RustChar>"
                        }
                }
        }
        val methodKt = rustFunctionIdentToKotlin(lastRaw)
        val ownerKt =
            when {
                penultRaw == "Self" -> selfTypeName?.substringBefore('<')?.trim()
                penultRaw.isNotEmpty() && penultRaw.first().isUpperCase() -> rustTypeIdentToKotlin(penultRaw)
                else -> null
            }
        if (ownerKt != null) {
            val canonicalOwner = emitter.resolveTypeAliasOwnerName(ownerKt)
            inspector.implMethodReturnSynTypes[canonicalOwner to methodKt]?.let { return emitter.renderType(it, canonicalOwner) }
            inspector.implMethodReturnSynTypes[ownerKt to methodKt]?.let { return emitter.renderType(it, ownerKt) }
            emitter.resolveMethodSignatureForOwner(ownerKt, methodKt)?.let { (resolvedOwner, sig) ->
                return when (val out = sig.output) {
                    is ReturnType.TypeReturn -> emitter.renderType(out.ty, resolvedOwner)
                    is ReturnType.Default -> "Unit"
                }
            }
            val modPrefix = segs.dropLast(2).joinToString(".") { rustModuleIdentToKotlin(it.identText().replace('-', '_')) }
            if (modPrefix.isNotEmpty()) {
                headerContext.crateFnReturnTypes["$modPrefix.$canonicalOwner.$methodKt"]?.let { return it }
                headerContext.crateFnReturnTypes["$modPrefix.$ownerKt.$methodKt"]?.let { return it }
            }
            val ownerKeys = (emitter.scopedAbiKeysForRenderedType(canonicalOwner) + emitter.scopedAbiKeysForRenderedType(ownerKt)).distinct()
            for (ownerKey in ownerKeys) {
                headerContext.crateFnReturnTypes["$ownerKey.$methodKt"]?.let { return it }
            }
            return headerContext.crateFnReturnTypes["$canonicalOwner.$methodKt"]
                ?: headerContext.crateFnReturnTypes["$ownerKt.$methodKt"]
        }
        val modSubpkg = segs.dropLast(1).joinToString(".") { rustModuleIdentToKotlin(it.identText().replace('-', '_')) }
        return headerContext.crateFnReturnTypes["$modSubpkg.$methodKt"] ?: headerContext.crateFnReturnTypes[methodKt]
    }

    private fun inferMethodCallType(expr: Expr.MethodCall): String? {
        val rawMethod = expr.method.toString().removePrefix("r#")
        emitter.sourceReceiverMethodReturn(expr.receiver, rawMethod)?.let { return emitter.renderType(it, selfTypeName) }
        val lazyRecvType by lazy(LazyThreadSafetyMode.NONE) { emitter.inferExprRenderedType(expr.receiver, selfTypeName) }
        if ((rawMethod == "as_ptr" || rawMethod == "as_mut_ptr") && expr.args.isEmpty()) {
            val arrayLocal = emitter.sourceArrayLocal(expr.receiver)
            if (arrayLocal != null) {
                return registerCorePtrType(isMut = rawMethod == "as_mut_ptr", pointee = emitter.renderType(arrayLocal.second, selfTypeName))
            }
            val recvRendered = lazyRecvType
            val recvBase = recvRendered?.removeSuffix("?")?.substringBefore('<')?.trim()
            if (recvBase != null && (recvBase in MUT_PTR_CONTAINER_BASES || MUT_PTR_CONTAINER_BASES.any { recvBase.endsWith(".$it") })) {
                return registerCorePtrType(isMut = true)
            }
            val elemRendered = recvRendered?.let { emitter.extractIndexedElementRenderedType(it, selfTypeName) }
            if (elemRendered != null) {
                return registerCorePtrType(isMut = rawMethod == "as_mut_ptr", pointee = elemRendered)
            }
        }
        if (rawMethod in ENDIAN_INT_METHODS && expr.args.isEmpty()) {
            return lazyRecvType ?: inferNumericKind(expr.receiver)?.let(emitter::numericKindToPureTypeName)
        }
        if (!emitter.hasDeclaredOptionEnum() && expr.turbofish == null) {
            val optRecvTy = lazyRecvType
            val recvIsNullable = optRecvTy != null && optRecvTy.endsWith("?")
            if (recvIsNullable) {
                if ((rawMethod == "unwrap" && expr.args.isEmpty()) ||
                    (rawMethod in OPTION_UNWRAP_SINGLE_ARG_METHODS && expr.args.size == 1)
                ) {
                    return optRecvTy.removeSuffix("?")
                }
            }
            if (recvIsNullable || isKnownNullableOption(expr.receiver, "")) {
                when {
                    (rawMethod == "or" || rawMethod == "or_else" || rawMethod == "filter") && expr.args.size == 1 ->
                        return optRecvTy ?: "Any?"
                    rawMethod in OPTION_ZERO_ARG_IDENTITY_METHODS && expr.args.isEmpty() ->
                        return optRecvTy ?: "Any?"
                    rawMethod == "flatten" && expr.args.isEmpty() ->
                        return optRecvTy?.removeSuffix("?")?.let { inner -> if (inner.endsWith("?")) inner else "$inner?" } ?: "Any?"
                    (rawMethod == "map" || rawMethod == "and_then") && expr.args.size == 1 -> {
                        val firstArg = expr.args.first()
                        val closureExpr = if (firstArg != null) emitter.unwrapParenOrGroupExpr(firstArg) as? Expr.Closure else null
                        val cbTy = closureExpr?.let { emitter.inferExprRenderedType(it.body, selfTypeName)?.removeSuffix("?") } ?: "Any"
                        return "$cbTy?"
                    }
                }
            }
        }
        if ("core" in headerContext.crateNamespaces && expr.turbofish == null) {
            val recvRendered = lazyRecvType?.removeSuffix("?")?.trim()
            when {
                rawMethod == "chars" && expr.args.isEmpty() && (recvRendered == "String" || recvRendered == "CharSequence") ->
                    return "$coreNs.str.iter.Chars"
                (recvRendered == "Chars" || recvRendered?.endsWith(".Chars") == true) && expr.args.isEmpty() ->
                    when (rawMethod) {
                        "next" -> return "Char?"
                        "as_str" -> return "String"
                        "clone" -> return "$coreNs.str.iter.Chars"
                        "enumerate" -> return "Iterator<Pair<Int, Char>>"
                    }
                rawMethod == "to_digit" &&
                    expr.args.size == 1 &&
                    (recvRendered == "Char" || (emitter.unwrapParenOrGroupExpr(expr.receiver) as? Expr.Lit)?.lit is Lit.Char) ->
                    return "UInt?"
            }
            val recvSimple = recvRendered?.simpleKotlinTypeName()
            if (recvSimple != null && rawMethod in ATOMIC_VALUE_RETURNING_METHODS) {
                atomicOwnerValueRenderedType(recvSimple)?.let { return it }
            }
            if (emitter.isFmtStringBuilderReceiver(expr.receiver, "", selfTypeName)) {
                FMT_DEBUG_BUILDER_RETURNS[rawMethod]?.let { return it }
            }
        }
        val recvNumericKind =
            when (expr.receiver) {
                is Expr.Call, is Expr.MethodCall, is Expr.BlockExpr, is Expr.Unsafe, is Expr.If ->
                    lazyRecvType?.let(emitter::resolveNumericKind)
                else -> inferNumericKind(expr.receiver)
            }
        recvNumericKind?.let { concreteRecvKind ->
            emitter
                .inferPrimitiveOpReturnRenderedType(concreteRecvKind, rawMethod, expr.firstTurbofishTypeArg(), selfTypeName)
                ?.let { return it }
        }
        return when {
            rawMethod in RAW_POINTER_ARITH_METHODS && expr.args.size == 1 -> lazyRecvType
            (rawMethod == "cast_mut" || rawMethod == "cast_const") && expr.args.isEmpty() -> {
                val recvTy = lazyRecvType
                val pointee = rawPointerPointeeType(recvTy)
                if (pointee != null) registerCorePtrType(isMut = rawMethod == "cast_mut", pointee = pointee) else recvTy
            }
            rawMethod == "wrapping_mul" && expr.args.size == 1 -> lazyRecvType
            (rawMethod == "to_be_bytes" || rawMethod == "to_le_bytes" || rawMethod == "to_ne_bytes") && expr.args.isEmpty() -> "UByteArray"
            rawMethod == "as_bytes" && expr.args.isEmpty() ->
                if (headerContext.rawAbiMode || headerContext.finalClasses) "UByteArray" else "List<UByte>"
            rawMethod == "len" && expr.args.isEmpty() -> "Int"
            rawMethod == "to_bits" && expr.args.isEmpty() ->
                when (recvNumericKind) {
                    NumericKind.F16 -> "UShort"
                    NumericKind.F32 -> "UInt"
                    NumericKind.F128 -> "U128"
                    else -> "ULong"
                }
            (rawMethod == "to_lowercase" || rawMethod == "to_uppercase") &&
                expr.args.isEmpty() &&
                inspector.instanceMethodsByType.values.none { rustFunctionIdentToKotlin(rawMethod) in it } -> "String"
            (rawMethod == "split_once" || rawMethod == "rsplit_once") && expr.args.size == 1 -> "Pair<String, String>?"
            rawMethod == "as_str" && expr.args.isEmpty() && inspector.instanceMethodsByType.values.none { "asStr" in it } -> "String"
            (rawMethod == "ok_or" || rawMethod == "ok_or_else") && expr.args.size == 1 ->
                if (emitter.usesTaggedCoreResult()) {
                    val okInner = lazyRecvType?.removeSuffix("?")?.trim() ?: "*"
                    val errExpr =
                        if (rawMethod == "ok_or_else") {
                            (emitter.unwrapParenOrGroupExpr(expr.args[0]) as? Expr.Closure)?.body
                        } else {
                            expr.args[0]
                        }
                    val errInner = errExpr?.let { emitter.inferExprRenderedType(it, selfTypeName)?.trim() } ?: "*"
                    "${emitter.sourceResultName()}<$okInner, $errInner>"
                } else {
                    "Result<*>"
                }
            (rawMethod == "cloned" || rawMethod == "copied" || rawMethod == "clone") && expr.args.isEmpty() -> lazyRecvType
            rawMethod == "cast" && expr.args.isEmpty() -> {
                val recvTy = lazyRecvType
                if (rawPointerPointeeType(recvTy) != null) {
                    val isMut = isMutRawPointerRenderedType(recvTy)
                    val destination = expr.firstTurbofishTypeArg()
                    destination?.let { registerCorePtrType(isMut = isMut, pointee = emitter.renderType(it, selfTypeName)) }
                        ?: run {
                            registerCorePtrType(isMut = isMut)
                            emitter.currentExpectedParseTargetType ?: emitter.currentEnclosingReturnRenderedType ?: recvTy
                        }
                } else {
                    recvTy
                }
            }
            else -> inferDeclaredOrCrateMethodReturn(expr, rawMethod, lazyRecvType ?: return null)
        }
    }

    private fun inferDeclaredOrCrateMethodReturn(
        expr: Expr.MethodCall,
        rawMethod: String,
        receiverType: String,
    ): String? {
        val recvTy =
            (mutableReferencePointeeType(receiverType) ?: refCellGuardPointeeType(receiverType) ?: receiverType)
                .removeSuffix("?")
                .trim()
        if (isMapRenderedType(recvTy) &&
            (rawMethod == "get" || rawMethod == "get_mut" || rawMethod == "remove") &&
            expr.args.size == 1 &&
            '<' in recvTy &&
            recvTy.endsWith('>')
        ) {
            val innerArgs = recvTy.substringAfter('<').dropLast(1)
            val comma = findTopLevelGenericCommaIndex(innerArgs)
            if (comma >= 0) {
                val valTy = innerArgs.substring(comma + 1).trim()
                if (valTy.isNotEmpty() && valTy != "*") return "$valTy?"
            }
        }
        val ownerKt = recvTy.simpleKotlinTypeName()
        if (ownerKt.isEmpty()) return null
        val methodKt = rustFunctionIdentToKotlin(rawMethod)
        emitter.mutablePlaceReturnType(ownerKt, rawMethod)?.let { return it }
        inspector.implMethodReturnSynTypes[ownerKt to methodKt]?.let { return emitter.renderType(it, ownerKt) }
        return emitter.scopedAbiKeysForRenderedType(recvTy).firstNotNullOfOrNull { headerContext.crateFnReturnTypes["$it.$methodKt"] }
            ?: headerContext.crateFnReturnTypes["$ownerKt.$methodKt"]
    }

    private fun inferTupleType(expr: Expr.Tuple): String? {
        val elems = expr.elems.toList()
        return when (elems.size) {
            0 -> "Unit"
            1 -> emitter.inferExprRenderedType(elems[0], selfTypeName)
            2 -> {
                val t0 = emitter.inferExprRenderedType(elems[0], selfTypeName)
                val t1 = emitter.inferExprRenderedType(elems[1], selfTypeName)
                val pairType = if ("Pair" in inspector.declaredTypeNames) "kotlin.Pair" else "Pair"
                if (t0 != null || t1 != null) "$pairType<${t0 ?: "Any?"}, ${t1 ?: "Any?"}>" else null
            }
            3 -> {
                val t0 = emitter.inferExprRenderedType(elems[0], selfTypeName)
                val t1 = emitter.inferExprRenderedType(elems[1], selfTypeName)
                val t2 = emitter.inferExprRenderedType(elems[2], selfTypeName)
                val tripleType = if ("Triple" in inspector.declaredTypeNames) "kotlin.Triple" else "Triple"
                if (t0 != null || t1 != null || t2 != null) "$tripleType<${t0 ?: "Any?"}, ${t1 ?: "Any?"}, ${t2 ?: "Any?"}>" else null
            }
            else -> "List<Any?>"
        }
    }

    fun inferNumericKind(expr: Expr): NumericKind? =
        when (expr) {
            is Expr.Paren -> inferNumericKind(expr.expr)
            is Expr.Group -> inferNumericKind(expr.expr)
            is Expr.Cast -> emitter.resolveNumericKind(emitter.renderType(expr.ty, selfTypeName))
            is Expr.Index -> emitter.inferArrayElementNumericKind(expr.expr, selfTypeName)
            is Expr.Field -> emitter.resolveFieldRenderedType(expr, selfTypeName)?.let(emitter::resolveNumericKind)
            is Expr.Call, is Expr.MethodCall, is Expr.BlockExpr, is Expr.Unsafe, is Expr.If ->
                emitter.inferExprRenderedType(expr, selfTypeName)?.let(emitter::resolveNumericKind)
            is Expr.Lit -> inferLitNumericKind(expr.lit)
            is Expr.Path -> inferPathNumericKind(expr)
            is Expr.Unary ->
                when (expr.op) {
                    is UnOp.NotOp -> inferNumericKind(expr.expr)?.takeIf { it.isIntegerNumericKind() }
                    is UnOp.Neg -> inferNumericKind(expr.expr)
                    is UnOp.Deref ->
                        emitter.inferExprRenderedType(expr, selfTypeName)?.let(emitter::resolveNumericKind)
                            ?: inferNumericKind(expr.expr)
                }
            is Expr.Binary ->
                when (expr.op) {
                    is BinOp.Eq, is BinOp.Ne, is BinOp.Lt, is BinOp.Le, is BinOp.Gt, is BinOp.Ge, is BinOp.And, is BinOp.Or -> null
                    is BinOp.Shl, is BinOp.Shr -> inferNumericKind(expr.left)
                    else -> inferNumericKind(expr.left) ?: inferNumericKind(expr.right)
                }
            else -> null
        }

    private fun inferLitNumericKind(lit: Lit): NumericKind? =
        when (lit) {
            is Lit.Byte -> NumericKind.U8
            is Lit.Float -> FLOAT_SUFFIX_NUMERIC_KINDS[lit.value.suffix()]
            is Lit.Int ->
                when (val sfx = lit.value.suffix()) {
                    "isize" -> emitter.resolvePointerSizedNumericKind(signed = true)
                    "usize" -> emitter.resolvePointerSizedNumericKind(signed = false)
                    "" ->
                        if (parseUnsigned128LiteralDigits(lit.value.token().toString())?.exceeds64BitUnsigned == true) {
                            NumericKind.U128
                        } else {
                            null
                        }
                    else -> INT_SUFFIX_NUMERIC_KINDS[sfx]
                }
            else -> null
        }

    private fun inferPathNumericKind(expr: Expr.Path): NumericKind? {
        emitter.resolvePrimitiveAssociatedConstKind(expr.path, expr.qself)?.let { return it }
        if (!hasOnlyIgnoredPathPrefixes(expr)) return null
        val segs = expr.path.segments.toList()
        val raw = segs.last().cleanIdent()
        val ktConst = inspector.declaredConstNames[raw]
        val constTy = ktConst?.let { inspector.declaredConstTypes[it] }
        if (constTy != null) return emitter.resolveNumericKind(emitter.renderType(constTy, selfTypeName))
        val localKind =
            when {
                segs.size == 1 && raw == "self" -> (emitter.currentTypedLocals["this"] ?: selfTypeName)?.let(emitter::resolveNumericKind)
                segs.size == 1 -> {
                    val ktLocal = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
                    emitter.currentTypedLocals[ktLocal]?.let(emitter::resolveNumericKind)
                }
                else -> null
            }
        if (localKind != null) return localKind
        val ktConstIdent = ktConst ?: KotlinNamingPolicy.Default.toConstIdentifier(raw)
        return emitter.scopedAbiKeysForRenderedType(ktConstIdent).firstNotNullOfOrNull {
            emitter.numericKindFromScannerCode(headerContext.crateNumericTypeKinds[it])
        } ?: emitter.resolveBestSubpackageForSymbol(ktConstIdent)?.let { sub ->
            val key = if (sub.isEmpty()) ktConstIdent else "$sub.$ktConstIdent"
            emitter.numericKindFromScannerCode(headerContext.crateNumericTypeKinds[key])
        } ?: emitter.numericKindFromScannerCode(headerContext.crateNumericTypeKinds[ktConstIdent])
    }

    companion object {
        private val NONE_PATH_SHAPES: Set<List<String>> =
            setOf(
                listOf("None"),
                listOf("Option", "None"),
                listOf("core", "option", "Option", "None"),
                listOf("std", "option", "Option", "None"),
            )
        private val NON_OPTION_METHOD_NAMES: Set<String> =
            wordSet("ok_or ok_or_else is_ok is_err is_some is_none map_err to_lowercase to_uppercase")
        private val OPTION_CHAIN_PRESERVING_METHODS: Set<String> =
            wordSet("map and_then flatten or or_else as_ref as_mut cloned copied filter")
        private val NULLABLE_NEW_WRAPPER_OWNERS: Set<String> = wordSet("NonNull NonZero Alignment")
        private val LAYOUT_QUERY_FN_IDENTS: Set<String> = wordSet("size_of size_of_val align_of align_of_val")
        private val MUT_PTR_CONTAINER_BASES: Set<String> = wordSet("NonNull Unique BoxRaw")
        private val ENDIAN_INT_METHODS: Set<String> = wordSet("to_be from_be swap_bytes to_le from_le")
        private val OPTION_UNWRAP_SINGLE_ARG_METHODS: Set<String> = wordSet("expect unwrap_or unwrap_or_else")
        private val OPTION_ZERO_ARG_IDENTITY_METHODS: Set<String> = wordSet("cloned copied as_ref as_mut")
        private val ATOMIC_VALUE_RETURNING_METHODS: Set<String> =
            wordSet("load swap into_inner fetch_add fetch_sub fetch_and fetch_or fetch_xor fetch_max fetch_min")
        private val FMT_DEBUG_BUILDER_RETURNS: Map<String, String> =
            mapOf(
                "debug_struct" to "DebugStruct",
                "debug_tuple" to "DebugTuple",
                "debug_list" to "DebugList",
                "debug_set" to "DebugSet",
                "debug_map" to "DebugMap",
            )
        private val FLOAT_SUFFIX_NUMERIC_KINDS: Map<String, NumericKind> =
            mapOf(
                "f16" to NumericKind.F16,
                "f32" to NumericKind.F32,
                "f64" to NumericKind.F64,
                "f128" to NumericKind.F128,
            )
        private val INT_SUFFIX_NUMERIC_KINDS: Map<String, NumericKind> =
            mapOf(
                "i8" to NumericKind.I8,
                "u8" to NumericKind.U8,
                "i16" to NumericKind.I16,
                "u16" to NumericKind.U16,
                "i32" to NumericKind.I32,
                "u32" to NumericKind.U32,
                "i64" to NumericKind.I64,
                "u64" to NumericKind.U64,
                "i128" to NumericKind.I128,
                "u128" to NumericKind.U128,
            )
    }
}

internal val NUMERIC_COERCE_SUFFIX_BY_KIND: Map<NumericKind, String> =
    mapOf(
        NumericKind.U8 to "toUByte()",
        NumericKind.U16 to "toUShort()",
        NumericKind.U32 to "toUInt()",
        NumericKind.U64 to "toULong()",
        NumericKind.I8 to "toByte()",
        NumericKind.I16 to "toShort()",
        NumericKind.I32 to "toInt()",
        NumericKind.I64 to "toLong()",
        NumericKind.F32 to "toFloat()",
        NumericKind.F64 to "toDouble()",
    )

private val NUMERIC_KIND_BY_NAME: Map<String, NumericKind> = NumericKind.entries.associateBy { it.name }

internal fun NumericKind.isIntegerNumericKind(): Boolean =
    this != NumericKind.F16 && this != NumericKind.F32 && this != NumericKind.F64 && this != NumericKind.F128

internal fun KotlinJ2KEmitter.resolvePointerSizedNumericKind(signed: Boolean): NumericKind =
    if (headerContext.rawAbiMode || inExternAbiSignature) {
        when (activeCfgProfile.pointerWidth) {
            16 -> if (signed) NumericKind.I16 else NumericKind.U16
            32 -> if (signed) NumericKind.I32 else NumericKind.U32
            else -> if (signed) NumericKind.I64 else NumericKind.U64
        }
    } else {
        NumericKind.I32
    }

internal fun unwrapExprParens(expr: Expr): Expr =
    when (expr) {
        is Expr.Paren -> unwrapExprParens(expr.expr)
        is Expr.Group -> unwrapExprParens(expr.expr)
        else -> expr
    }

internal fun KotlinJ2KEmitter.unwrapParenOrGroupExpr(expr: Expr): Expr =
    unwrapExprParens(expr)

internal fun KotlinJ2KEmitter.isNarrowComparisonLiteralOperand(operand: Expr, targetKind: NumericKind): Boolean {
    val isNarrowUnsigned = targetKind == NumericKind.U8 || targetKind == NumericKind.U16
    val isNarrowSigned = targetKind == NumericKind.I8 || targetKind == NumericKind.I16
    if (!isNarrowUnsigned && !isNarrowSigned) return false
    val core = unwrapParenOrGroupExpr(operand)
    if (core is Expr.Lit) {
        val lit = core.lit as? Lit.Int ?: return false
        val suffix = lit.value.suffix()
        return if (isNarrowUnsigned) {
            suffix.isEmpty() || suffix == "u8" || suffix == "u16"
        } else {
            suffix.isEmpty() || suffix == "i8" || suffix == "i16"
        }
    }
    if (isNarrowSigned && core is Expr.Unary && core.op is UnOp.Neg) {
        val innerLit = (unwrapParenOrGroupExpr(core.expr) as? Expr.Lit)?.lit as? Lit.Int ?: return false
        val suffix = innerLit.value.suffix()
        return suffix.isEmpty() || suffix == "i8" || suffix == "i16"
    }
    return false
}

internal fun KotlinJ2KEmitter.coerceRenderedNarrowLiteral(sourceExpr: Expr, rendered: String, kind: NumericKind): String {
    if (isAlreadyRenderedAsNumericKind(rendered, kind)) return rendered
    val recv = if (isAtomicPostfixReceiver(sourceExpr, rendered)) rendered else "($rendered)"
    if (kind != NumericKind.U8 && kind != NumericKind.U16 && kind != NumericKind.I8 && kind != NumericKind.I16) return rendered
    val suffix = NUMERIC_COERCE_SUFFIX_BY_KIND[kind] ?: return rendered
    return if (rendered.endsWith(".$suffix")) rendered else "$recv.$suffix"
}

internal fun KotlinJ2KEmitter.isUnsignedKind(kind: NumericKind): Boolean =
    kind == NumericKind.U8 || kind == NumericKind.U16 || kind == NumericKind.U32 || kind == NumericKind.U64 || kind == NumericKind.U128

internal fun KotlinJ2KEmitter.coerceRenderedNumericExpr(sourceExpr: Expr, rendered: String, kind: NumericKind): String {
    if (isAlreadyRenderedAsNumericKind(rendered, kind)) return rendered
    val recv = if (isAtomicPostfixReceiver(sourceExpr, rendered)) rendered else "($rendered)"
    if (kind == NumericKind.F16 || kind == NumericKind.F128) {
        return convertNumericReceiver(recv, numericKindToPureTypeName(kind), inferExprNumericKind(sourceExpr, selfTypeName = null))
    }
    val suffix = NUMERIC_COERCE_SUFFIX_BY_KIND[kind] ?: return rendered
    return if (rendered.endsWith(".$suffix")) rendered else "$recv.$suffix"
}

internal fun KotlinJ2KEmitter.numericKindToPureTypeName(kind: NumericKind): String =
    when (kind) {
        NumericKind.I8 -> "Byte"
        NumericKind.U8 -> "UByte"
        NumericKind.I16 -> "Short"
        NumericKind.U16 -> "UShort"
        NumericKind.I32 -> "Int"
        NumericKind.U32 -> "UInt"
        NumericKind.I64 -> "Long"
        NumericKind.U64 -> "ULong"
        NumericKind.I128 -> "I128"
        NumericKind.U128 -> "U128"
        NumericKind.F16 -> "F16"
        NumericKind.F32 -> kotlinF32TypeName()
        NumericKind.F64 -> "Double"
        NumericKind.F128 -> "F128"
    }

internal fun KotlinJ2KEmitter.numericKindFromScannerCode(code: String?): NumericKind? =
    code?.let(NUMERIC_KIND_BY_NAME::get)

internal fun KotlinJ2KEmitter.borrowedPlaceLocalName(expr: Expr): String? {
    val path = unwrapParenOrGroupExpr(expr) as? Expr.Path ?: return null
    if (path.qself != null || path.path.segments.size != 1) return null
    val raw = path.path.firstCleanIdent() ?: return null
    val name = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
    return name.takeIf { it in currentBorrowedPlaceLocals }
}

internal fun KotlinJ2KEmitter.renderBorrowedPlace(expr: Expr, indent: String, selfTypeName: String?): String? {
    val (receiver, fields) = borrowedPlaceProjection(expr, selfTypeName) ?: return null
    return "(${renderExpr(receiver, indent, selfTypeName)}).${fields.joinToString(".")}"
}

internal fun KotlinJ2KEmitter.borrowedPlaceProjection(
    expr: Expr,
    selfTypeName: String?,
    addressArgument: Boolean = false,
): Pair<Expr, List<String>>? {
    if (headerContext.rawAbiMode) return null
    var core = unwrapParenOrGroupExpr(expr)
    if (core is Expr.Unsafe) core = (core.block.stmts.singleOrNull() as? Stmt.ExprStmt)?.expr ?: return null
    if (addressArgument && core is Expr.Reference) {
        if (core.mutability == null) return null
        val place = unwrapParenOrGroupExpr(core.expr) as? Expr.Unary ?: return null
        if (place.op !is UnOp.Deref) return null
        core = unwrapParenOrGroupExpr(place.expr)
    }
    val call = core as? Expr.MethodCall ?: return null
    if (!call.args.isEmpty() || call.turbofish != null) return null
    val receiverType = inferExprRenderedType(call.receiver, selfTypeName) ?: return null
    val owner = receiverType.removeSuffix("?").simpleKotlinTypeName()
    val fields = mutableReferenceProjection(owner, call.method.toString(), linkedSetOf()) ?: return null
    return call.receiver to fields
}

private fun KotlinJ2KEmitter.findSingleReceiverInherentImplMethod(owner: String, method: String): ImplItem.Fn? =
    file.items
        .filterIsInstance<Item.Impl>()
        .filter { it.traitPath == null && renderType(it.selfType, owner).simpleKotlinTypeName() == owner }
        .flatMap { it.items.filterIsInstance<ImplItem.Fn>() }
        .singleOrNull {
            it.sig.ident.toString() == method && it.sig.inputs.size == 1 && it.sig.hasSelfReceiver()
        }

internal fun KotlinJ2KEmitter.mutablePlaceAccessorType(owner: String, method: String): String? {
    // A raw Rust address can escape into FFI/atomic operations. It must retain
    // its pointer contract, even when its body projects one transparent field.
    if (inspector.implMethodReturnSynTypes[owner to rustFunctionIdentToKotlin(method)] is SynType.Ptr) return null
    val fields = mutableReferenceProjection(owner, method, linkedSetOf()) ?: return null
    var fieldOwner = owner
    var type: SynType? = null
    for (field in fields) {
        type = inspector.structFieldSynTypesByType[fieldOwner]?.get(field) ?: return null
        fieldOwner = renderType(type, fieldOwner).simpleKotlinTypeName()
    }
    return "kotlin.reflect.KMutableProperty0<${renderType(type ?: return null, owner)}>"
}

/** Infer a transported place from this file's declared return and tail call.
 * Unlike a pure accessor projection, preceding statements must still execute.
 */
internal fun KotlinJ2KEmitter.mutablePlaceReturnType(
    owner: String,
    method: String,
    visiting: MutableSet<Pair<String, String>> = linkedSetOf(),
): String? {
    val transparent = transparentDeclaration(owner)
    val liveField = transparent?.let { sourceTransparentField(it, owner + renderTypeArgumentsFromGenerics(it.generics)) }
    val declaredOutput = inspector.implMethodReturnSynTypes[owner to rustFunctionIdentToKotlin(method)]
    if (liveField != null &&
        declaredOutput is SynType.Reference &&
        declaredOutput.mutability != null &&
        mutableReferenceProjection(owner, method, linkedSetOf()) == listOf(liveField.first)
    ) {
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.RustMutableReference")
        return "RustMutableReference<${liveField.second}>"
    }
    mutablePlaceAccessorType(owner, method)?.let { return it }
    val key = owner to method
    if (!visiting.add(key)) return null
    try {
        val fn = findSingleReceiverInherentImplMethod(owner, method) ?: return null
        val output = (fn.sig.output as? ReturnType.TypeReturn)?.ty ?: return null
        if (output !is SynType.Reference || output.mutability == null) return null
        val tail = fn.block.stmts.lastOrNull() as? Stmt.ExprStmt ?: return null
        if (tail.semiToken != null) return null
        // A second exit would need its own place proof. Do not change the
        // function contract based on only one of multiple return paths.
        var hasExplicitReturn = false
        val returnVisitor =
            object : Visit() {
                override fun visitExprReturn(e: Expr.Return) {
                    hasExplicitReturn = true
                }
            }
        for (stmt in fn.block.stmts) returnVisitor.visitStmt(stmt)
        if (hasExplicitReturn) return null
        val call = unwrapParenOrGroupExpr(tail.expr) as? Expr.MethodCall ?: return null
        if (!call.args.isEmpty() || call.turbofish != null) return null
        val receiver = unwrapParenOrGroupExpr(call.receiver) as? Expr.Path ?: return null
        if (receiver.qself != null || !receiver.path.isIdent("self")) return null
        mutablePlaceReturnType(owner, call.method.toString(), visiting) ?: return null
        return "kotlin.reflect.KMutableProperty0<${renderType(output.elem, owner)}>"
    } finally {
        visiting.remove(key)
    }
}

internal val STR_PRIMITIVE_PARSE_METHODS: Map<String, String> =
    mapOf(
        "Boolean" to "toBooleanStrictOrNull()",
        "Byte" to "toByteOrNull()",
        "UByte" to "toUByteOrNull()",
        "Short" to "toShortOrNull()",
        "UShort" to "toUShortOrNull()",
        "Int" to "toIntOrNull()",
        "UInt" to "toUIntOrNull()",
        "Long" to "toLongOrNull()",
        "ULong" to "toULongOrNull()",
        "Float" to "toFloatOrNull()",
        "kotlin.Float" to "toFloatOrNull()",
        "Double" to "toDoubleOrNull()",
    )

internal fun refCellGuardPointeeType(type: String?): String? {
    val base = type?.trim()?.removeSuffix("?") ?: return null
    if (!base.endsWith('>')) return null
    val simple = base.simpleKotlinTypeName()
    if (simple != "Ref" && simple != "RefMut") return null
    val inner = base.substringAfter('<').dropLast(1).trim()
    return inner.ifEmpty { null }
}

private val MAP_TYPE_PREFIXES = listOf("MutableMap<", "Map<", "LinkedHashMap<", "HashMap<")
private val SET_TYPE_PREFIXES = listOf("MutableSet<", "Set<", "LinkedHashSet<", "HashSet<")
private val INDEXED_SLICE_BASES = wordSet("List MutableList Array ArrayDeque")

internal fun isMapRenderedType(type: String?): Boolean {
    val clean = type?.removeSuffix("?")?.trim() ?: return false
    return MAP_TYPE_PREFIXES.any(clean::startsWith)
}

internal fun isSetRenderedType(type: String?): Boolean {
    val clean = type?.removeSuffix("?")?.trim() ?: return false
    return SET_TYPE_PREFIXES.any(clean::startsWith)
}

internal fun isIndexedSliceOrArrayRenderedType(type: String?): Boolean {
    val base = type?.trim()?.removeSuffix("?")?.simpleKotlinTypeName() ?: return false
    return base in PRIMITIVE_ARRAY_TYPES || base in INDEXED_SLICE_BASES
}

internal fun mutablePlaceValueType(type: String?): String? =
    type
        ?.takeIf { it.startsWith("kotlin.reflect.KMutableProperty0<") && it.endsWith('>') }
        ?.removePrefix("kotlin.reflect.KMutableProperty0<")
        ?.dropLast(1)

internal fun KotlinJ2KEmitter.mutableReferenceProjection(
    owner: String,
    method: String,
    visiting: MutableSet<Pair<String, String>>,
): List<String>? {
    val key = owner to method
    if (!visiting.add(key)) return null
    try {
        val fn = findSingleReceiverInherentImplMethod(owner, method) ?: return null
        val output = (fn.sig.output as? ReturnType.TypeReturn)?.ty ?: return null
        if (output !is SynType.Ptr && (output !is SynType.Reference || output.mutability == null)) return null
        var body = (fn.block.stmts.singleOrNull() as? Stmt.ExprStmt)?.expr ?: return null
        body = unwrapParenOrGroupExpr(body)
        if (body is Expr.Unsafe) body = (body.block.stmts.singleOrNull() as? Stmt.ExprStmt)?.expr ?: return null
        // Pointer-returning wrappers may forward another proven place
        // accessor (Cell::as_ptr -> UnsafeCell::get). The transparent-layout
        // proof is required for reinterpret casts, not for that forwarding.
        if (output is SynType.Ptr && body is Expr.Cast) {
            val struct = file.findStructByKotlinName(owner) ?: return null
            val transparent =
                struct.attrs.any { attr ->
                    val meta = attr.meta as? Meta.List
                    val reprToks = meta?.tokens?.clone()?.toList()
                    meta != null && meta.path.isIdent("repr") &&
                        reprToks?.any { it is TokenTree.Ident && it.value.toString() == "transparent" } == true
                }
            if (!transparent) return null
            val field = inspector.structFieldSynTypesByType[owner]?.entries?.singleOrNull() ?: return null
            if (renderType(field.value, owner) != renderType(output.elem, owner)) return null
            var cast = unwrapParenOrGroupExpr(body)
            var takesOwnerAddress = false
            while (cast is Expr.Cast) {
                val pointer = cast.ty as? SynType.Ptr ?: return null
                if (renderType(pointer.elem, owner).substringBefore('<') == owner) takesOwnerAddress = true
                cast = unwrapParenOrGroupExpr(cast.expr)
            }
            if (!takesOwnerAddress || cast !is Expr.Path || cast.qself != null || !cast.path.isIdent("self")) return null
            return listOf(field.key)
        }
        val borrowsField = body is Expr.Reference && body.mutability != null
        if (borrowsField) body = unwrapParenOrGroupExpr(body.expr)

        fun fieldPath(place: Expr): List<String>? {
            val core = unwrapParenOrGroupExpr(place)
            if (core is Expr.Path && core.qself == null && core.path.isIdent("self")) return emptyList()
            val field = core as? Expr.Field ?: return null
            val member = field.member as? Member.Named ?: return null
            return fieldPath(field.base)?.plus(rustFieldIdentToKotlin(member.ident.toString()))
        }

        if (borrowsField) fieldPath(body)?.takeIf { it.isNotEmpty() }?.let { return it }
        val forwarded = body as? Expr.MethodCall ?: return null
        if (!forwarded.args.isEmpty() || forwarded.turbofish != null) return null
        val prefix = fieldPath(forwarded.receiver) ?: return null
        var fieldOwner = owner
        for (field in prefix) {
            val type = inspector.structFieldSynTypesByType[fieldOwner]?.get(field) ?: return null
            fieldOwner = renderType(type, fieldOwner).simpleKotlinTypeName()
        }
        return prefix + (mutableReferenceProjection(fieldOwner, forwarded.method.toString(), visiting) ?: return null)
    } finally {
        visiting.remove(key)
    }
}

internal fun unwrapMaybeUninitRenderedInner(rendered: String?): String? {
    val clean = rendered?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if ((clean.startsWith("MaybeUninit<") || ".MaybeUninit<" in clean) && clean.endsWith(">")) {
        val inner = clean.substringAfter("MaybeUninit<").removeSuffix(">").trim()
        return inner.ifEmpty { null }
    }
    return null
}

internal fun KotlinJ2KEmitter.resolveMaybeUninitElementRenderedType(
    callExpr: Expr.Call,
    renderedCallee: String,
    selfTypeName: String?,
): String {
    val funcPath = (unwrapParenOrGroupExpr(callExpr.func) as? Expr.Path)?.path
    if (funcPath != null) {
        for (seg in funcPath.segments.toList()) {
            val ab = seg.arguments as? PathArguments.AngleBracketed ?: continue
            val firstTy = ab.args.toList().firstNotNullOfOrNull { renderGenericArgument(it, selfTypeName) }
            if (!firstTy.isNullOrBlank()) {
                return unwrapMaybeUninitRenderedInner(firstTy) ?: firstTy.trim()
            }
        }
    }
    if ('<' in renderedCallee && renderedCallee.endsWith(">")) {
        val tf = renderedCallee.substringAfter('<').removeSuffix(">").trim()
        if (tf.isNotEmpty() && ',' !in tf) {
            return unwrapMaybeUninitRenderedInner(tf) ?: tf
        }
    }
    currentExpectedParseTargetType?.trim()?.takeIf { it.isNotEmpty() && it != "Unit" }?.let { expected ->
        return unwrapMaybeUninitRenderedInner(expected) ?: expected
    }
    val ownerBase = selfTypeName?.substringBefore('<')?.trim()
    if (!ownerBase.isNullOrEmpty()) {
        val singleFieldSyn = inspector.structFieldSynTypesByType[ownerBase]?.values?.singleOrNull()
        if (singleFieldSyn != null) {
            unwrapMaybeUninitRenderedInner(renderType(singleFieldSyn, selfTypeName))?.let { return it }
        }
    }
    currentEnclosingReturnRenderedType?.trim()?.takeIf { it.isNotEmpty() && it != "Unit" }?.let { ret ->
        unwrapMaybeUninitRenderedInner(ret)?.let { return it }
        if (ownerBase.isNullOrEmpty() || ret.substringBefore('<').trim() != ownerBase) return ret
    }
    return "UByte"
}

private fun KotlinJ2KEmitter.isMutSliceSynType(ty: SynType): Boolean {
    val ref = unwrapSynType(ty) as? SynType.Reference ?: return false
    return ref.mutability != null && unwrapSynType(ref.elem) is SynType.Slice
}

internal fun KotlinJ2KEmitter.resolveTypeAliasOwnerName(ownerKt: String): String {
    var cur = ownerKt
    val seen = hashSetOf<String>()
    while (seen.add(cur) && seen.size <= 8) {
        val targetTy = inspector.typeAliasTargets[cur] ?: break
        val unwrapped = unwrapSynType(targetTy) as? SynType.Path ?: break
        if (unwrapped.qself != null) break
        val lastIdent = unwrapped.path.lastCleanIdent() ?: break
        val next = rustTypeIdentToKotlin(lastIdent)
        if (next == cur) break
        cur = next
    }
    return cur
}

internal fun KotlinJ2KEmitter.resolveMethodSignatureForOwner(
    ownerKt: String,
    methodKt: String,
): Pair<String, Signature>? {
    val canonicalOwner = resolveTypeAliasOwnerName(ownerKt)
    val allFilesItems =
        buildList {
            TranslationSession.sharedModuleFile?.items?.let(::addAll)
            if (TranslationSession.sharedModuleFile !== file) addAll(file.items)
        }
    val traitByName = linkedMapOf<String, Item.Trait>()
    traitByName.putAll(inspector.declaredTraitsByName)
    for (item in allFilesItems) {
        if (item is Item.Trait && !itemHasDocOnlyCfg(item)) {
            val tName = rustTypeIdentToKotlin(item.ident.toString())
            if (tName !in traitByName) traitByName[tName] = item
        }
    }
    val candidateImplTraits = linkedSetOf<String>()
    for (item in allFilesItems) {
        if (item is Item.Impl && !itemHasDocOnlyCfg(item)) {
            val implOwner = extractDirectOwnerTypeName(item.selfType, item.generics) ?: continue
            if (implOwner != canonicalOwner) continue
            for (implItem in item.items) {
                if (implItem is ImplItem.Fn &&
                    !implItemHasDocOnlyCfg(implItem) &&
                    rustFunctionIdentToKotlin(implItem.sig.ident.toString()) == methodKt
                ) {
                    return canonicalOwner to implItem.sig
                }
            }
            val traitPath = item.traitPath?.path
            traitPath?.lastKotlinTypeName()?.let { candidateImplTraits += it }
        }
    }
    val candidateTraits = linkedSetOf<String>()
    candidateTraits.addAll(candidateImplTraits)
    candidateTraits.addAll(currentTypeParamTraitBounds[ownerKt].orEmpty())
    if (canonicalOwner in traitByName) candidateTraits += canonicalOwner
    for (traitKt in candidateTraits) {
        val traitItem = traitByName[traitKt] ?: continue
        for (tItem in traitItem.items) {
            if (tItem is TraitItem.Fn &&
                !traitItemHasDocOnlyCfg(tItem) &&
                rustFunctionIdentToKotlin(tItem.sig.ident.toString()) == methodKt
            ) {
                return canonicalOwner to tItem.sig
            }
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.getFnLookupIndex(): EmitterFnLookupIndex {
    val sharedMod = TranslationSession.sharedModuleFile
    val existing = cachedFnLookupIndex
    if (existing != null && existing.sharedModFile === sharedMod) return existing
    val entriesByKey = hashMapOf<String, MutableList<IndexedFnEntry>>()
    val nonForeignByKtName = hashMapOf<String, MutableList<IndexedFnEntry>>()
    var nextOrder = 0

    fun addKey(key: String, entry: IndexedFnEntry) {
        val list = entriesByKey.getOrPut(key) { mutableListOf() }
        if (list.lastOrNull() !== entry) list.add(entry)
    }

    fun registerSig(sig: Signature, isForeign: Boolean) {
        val cleanDecl = sig.cleanFnName()
        val ktDecl = rustFunctionIdentToKotlin(cleanDecl)
        val typedParams = sig.inputs.toList().filterIsInstance<FnArg.Typed>()
        val entry = IndexedFnEntry(nextOrder++, cleanDecl, ktDecl, isForeign = isForeign, typedParams = typedParams)
        addKey(cleanDecl, entry)
        addKey(ktDecl, entry)
        if (!isForeign) {
            nonForeignByKtName.getOrPut(ktDecl) { mutableListOf() } += entry
        } else {
            inspector.collidingTopLevelForeignFnRenames[cleanDecl]?.let { addKey(it, entry) }
            for ((inlineKey, renamed) in inspector.collidingInlineForeignFnRenames) {
                if (inlineKey.second == cleanDecl) addKey(renamed, entry)
            }
        }
    }

    fun scanItems(items: List<Item>) {
        for (item in items) {
            if (item is Item.Fn && !itemHasDocOnlyCfg(item)) {
                registerSig(item.sig, isForeign = false)
            } else if (item is Item.ForeignMod && !itemHasDocOnlyCfg(item)) {
                for (foreign in item.items) {
                    if (foreign is ForeignItem.Fn && !foreignItemHasDocOnlyCfg(foreign)) {
                        registerSig(foreign.sig, isForeign = true)
                    }
                }
            }
        }
    }

    sharedMod?.items?.let(::scanItems)
    if (sharedMod !== file) scanItems(file.items)
    return EmitterFnLookupIndex(sharedMod, entriesByKey, nonForeignByKtName).also {
        cachedFnLookupIndex = it
    }
}

private fun Signature.typedParamSynTypeAt(idx: Int): SynType? {
    val typedParams = inputs.toList().filterIsInstance<FnArg.Typed>()
    return typedParams.getOrNull(idx)?.patType?.ty
}

internal fun KotlinJ2KEmitter.resolveCalleeExprParamSynType(
    calleeExpr: Expr,
    argIdx: Int,
    selfTypeName: String?,
): SynType? {
    val pathExpr = unwrapParenOrGroupExpr(calleeExpr) as? Expr.Path ?: return null
    if (pathExpr.qself != null) return null
    val segs = pathExpr.path.segments.toList()
    if (segs.isEmpty()) return null
    val methodKt = rustFunctionIdentToKotlin(segs.last().cleanIdent())
    if (segs.size == 1) {
        val rawCalleeIdent = segs[0].cleanIdent()
        val preferLocalForeign = rawCalleeIdent in activeLocalForeignFnRenames

        fun matchesFnIdent(rawDeclIdent: String, ktCallee: String, isForeign: Boolean): Boolean {
            val cleanDecl = rawDeclIdent.removePrefix("r#")
            if (cleanDecl == rawCalleeIdent || rustFunctionIdentToKotlin(cleanDecl) == ktCallee) return true
            if (!isForeign) return false
            val renamed =
                activeLocalForeignFnRenames[cleanDecl]
                    ?: inspector.collidingTopLevelForeignFnRenames[cleanDecl]
                    ?: currentModPathPrefix.lastOrNull()?.let { inspector.collidingInlineForeignFnRenames[it to cleanDecl] }
            return renamed == ktCallee
        }

        fun findInBlockItems(includeRegularFns: Boolean, includeForeignFns: Boolean): SynType? {
            for (item in activeLocalBlockItems) {
                if (includeRegularFns && item is Item.Fn && !itemHasDocOnlyCfg(item) && matchesFnIdent(item.sig.ident.toString(), methodKt, isForeign = false)) {
                    item.sig.typedParamSynTypeAt(argIdx)?.let { return it }
                }
                if (includeForeignFns && item is Item.ForeignMod && !itemHasDocOnlyCfg(item)) {
                    for (foreign in item.items) {
                        if (foreign is ForeignItem.Fn &&
                            !foreignItemHasDocOnlyCfg(foreign) &&
                            matchesFnIdent(foreign.sig.ident.toString(), methodKt, isForeign = true)
                        ) {
                            foreign.sig.typedParamSynTypeAt(argIdx)?.let { return it }
                        }
                    }
                }
            }
            return null
        }

        findInBlockItems(includeRegularFns = !preferLocalForeign, includeForeignFns = true)?.let { return it }
        val fnIndex = getFnLookupIndex()
        val candidateSet = linkedSetOf<IndexedFnEntry>()
        fnIndex.entriesByKey[rawCalleeIdent]?.let(candidateSet::addAll)
        fnIndex.entriesByKey[methodKt]?.let(candidateSet::addAll)
        for ((origRaw, renamedKt) in activeLocalForeignFnRenames) {
            if (renamedKt == methodKt) {
                fnIndex.entriesByKey[origRaw]?.let(candidateSet::addAll)
                break
            }
        }
        val orderedCandidates = if (candidateSet.size <= 1) candidateSet.toList() else candidateSet.sortedBy { it.order }
        for (entry in orderedCandidates) {
            if ((!preferLocalForeign || entry.isForeign) && matchesFnIdent(entry.cleanDecl, methodKt, isForeign = entry.isForeign)) {
                entry.typedParams.getOrNull(argIdx)?.let { return it.patType.ty }
            }
        }
        if (preferLocalForeign) {
            findInBlockItems(includeRegularFns = true, includeForeignFns = false)?.let { return it }
            for (entry in orderedCandidates) {
                if (!entry.isForeign && matchesFnIdent(entry.cleanDecl, methodKt, isForeign = false)) {
                    entry.typedParams.getOrNull(argIdx)?.let { return it.patType.ty }
                }
            }
        }
        return null
    }
    val penultRaw = segs[segs.size - 2].cleanIdent()
    val ownerKt =
        when {
            penultRaw == "Self" -> selfTypeName?.substringBefore('<')?.trim()
            penultRaw.isNotEmpty() && penultRaw.first().isUpperCase() -> rustTypeIdentToKotlin(penultRaw)
            else -> null
        } ?: return null
    val (_, sig) = resolveMethodSignatureForOwner(ownerKt, methodKt) ?: return null
    val typedIdx = if (sig.hasSelfReceiver()) argIdx - 1 else argIdx
    return if (typedIdx >= 0) sig.typedParamSynTypeAt(typedIdx) else null
}

internal fun KotlinJ2KEmitter.resolveCallArgExpectedCollectionOrArrayType(
    func: Expr,
    argIdx: Int,
    totalArgs: Int,
    selfTypeName: String?,
    localStructFields: Map<String, List<SynType>> = emptyMap(),
): String? {
    val paramSynTy = resolveCalleeExprParamSynType(func, argIdx, selfTypeName)
    val paramRendered =
        paramSynTy?.let { renderType(it, selfTypeName).removeSuffix("?").trim() }
            ?: run {
                val fnPath = (unwrapParenOrGroupExpr(func) as? Expr.Path)?.takeIf { it.qself == null }?.path
                val rawCtor = fnPath?.lastCleanIdent()
                val ctorKt = rawCtor?.let(::rustTypeIdentToKotlin)
                if (ctorKt != null && rawCtor.firstOrNull()?.isUpperCase() == true) {
                    localStructFields[ctorKt]?.getOrNull(argIdx)?.let { renderType(it, selfTypeName).removeSuffix("?").trim() }
                        ?: run {
                            val fieldKey = if (totalArgs == 1) "value" else "field$argIdx"
                            inspector.structFieldSynTypesByType[ctorKt]?.get(fieldKey)?.let { renderType(it, selfTypeName).removeSuffix("?").trim() }
                                ?: scopedAbiKeysForRenderedType(ctorKt).firstNotNullOfOrNull { headerContext.crateStructFieldTypes["$it.$fieldKey"] }
                                ?: headerContext.crateStructFieldTypes["$ctorKt.$fieldKey"]
                                ?: headerContext.crateStructFieldTypes.entries
                                    .mapNotNull { (k, v) -> if (k.endsWith(".$ctorKt.$fieldKey")) v else null }
                                    .distinct()
                                    .singleOrNull()
                        }?.removeSuffix("?")?.trim()
                } else {
                    null
                }
            }
    return paramRendered?.takeIf {
        it.startsWith("List<") || it.startsWith("MutableList<") || it in PRIMITIVE_ARRAY_TYPES
    }
}

internal fun KotlinJ2KEmitter.inferUntypedArrayLocalTargetTypeFromRemainingStmts(
    pat: Pat,
    remainingStmts: List<Stmt>,
    selfTypeName: String?,
): String? {
    val identPat = unwrapTransparentPat(pat) as? Pat.Ident ?: return null
    val raw = identPat.ident.toString().removePrefix("r#")

    fun unwrapBorrowedSlicePlace(expr: Expr): Expr {
        var cur = unwrapParenOrGroupExpr(expr)
        while (true) {
            cur =
                when (cur) {
                    is Expr.Reference -> unwrapParenOrGroupExpr(cur.expr)
                    is Expr.Index -> {
                        val range = unwrapParenOrGroupExpr(cur.index) as? Expr.Range
                        if (range != null && range.start == null && range.end == null) unwrapParenOrGroupExpr(cur.expr) else return cur
                    }
                    else -> return cur
                }
        }
    }

    var inferredTarget: String? = null
    val localTupleStructFields = linkedMapOf<String, List<SynType>>()
    val visitor =
        object : Visit() {
            override fun visitItemStruct(i: Item.Struct) {
                val fields = i.fields
                if (fields is Fields.Unnamed) {
                    val unnamedList = fields.fields.unnamed.toList()
                    localTupleStructFields[rustTypeIdentToKotlin(i.ident.toString())] = unnamedList.map { it.ty }
                }
                super.visitItemStruct(i)
            }

            override fun visitExprCall(e: Expr.Call) {
                if (inferredTarget != null) return
                val argsList = e.args.toList()
                for ((argIdx, argExpr) in argsList.withIndex()) {
                    val basePlace = unwrapBorrowedSlicePlace(argExpr) as? Expr.Path ?: continue
                    if (basePlace.qself != null || !basePlace.path.isIdent(raw)) continue
                    val target =
                        resolveCallArgExpectedCollectionOrArrayType(
                            func = e.func,
                            argIdx = argIdx,
                            totalArgs = argsList.size,
                            selfTypeName = selfTypeName,
                            localStructFields = localTupleStructFields,
                        ) ?: continue
                    inferredTarget = target
                    return
                }
                super.visitExprCall(e)
            }
        }
    for (stmt in remainingStmts) {
        if (inferredTarget != null) break
        visitor.visitStmt(stmt)
    }
    return inferredTarget
}

internal fun KotlinJ2KEmitter.calleeExpectsMutableListSliceParam(calleeBase: String, argIdx: Int): Boolean {
    val simpleFn = calleeBase.substringAfterLast('.').trim()
    val ownerPrefix = calleeBase.substringBeforeLast('.', missingDelimiterValue = "").substringAfterLast('.').trim()
    if (ownerPrefix.isEmpty()) {
        return getFnLookupIndex().nonForeignByKtName[simpleFn].orEmpty().any { entry ->
            entry.typedParams.getOrNull(argIdx)?.let { isMutSliceSynType(it.patType.ty) } == true
        }
    }
    resolveMethodSignatureForOwner(ownerPrefix, simpleFn)?.let { (_, sig) ->
        val typedIdx = if (sig.hasSelfReceiver()) argIdx - 1 else argIdx
        if (typedIdx >= 0) {
            val paramTy = sig.typedParamSynTypeAt(typedIdx)
            if (paramTy != null && isMutSliceSynType(paramTy)) return true
        }
    }
    return methodExpectsMutableListSliceParam(ownerPrefix, simpleFn, argIdx, includeStaticOnly = true)
}

internal fun KotlinJ2KEmitter.methodExpectsMutableListSliceParam(
    ownerSimple: String?,
    methodKt: String,
    argIdx: Int,
    includeStaticOnly: Boolean = false,
): Boolean {
    fun matchesSig(sig: Signature): Boolean {
        if (rustFunctionIdentToKotlin(sig.ident.toString()) != methodKt) return false
        if (includeStaticOnly && sig.hasSelfReceiver()) return false
        val paramTy = sig.typedParamSynTypeAt(argIdx) ?: return false
        return isMutSliceSynType(paramTy)
    }

    for (item in file.items) {
        when (item) {
            is Item.Impl -> {
                val implOwner = renderType(item.selfType, null).simpleKotlinTypeName()
                if (ownerSimple != null && implOwner != ownerSimple) continue
                if (item.items.any { it is ImplItem.Fn && matchesSig(it.sig) }) return true
            }
            is Item.Trait -> {
                val traitName = rustTypeIdentToKotlin(item.ident.toString())
                if (ownerSimple != null && traitName != ownerSimple) continue
                if (item.items.any { it is TraitItem.Fn && matchesSig(it.sig) }) return true
            }
            else -> Unit
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.primitiveArrayTypeForElementRenderedType(elemTy: String?): String? =
    PrimitiveArrayTypeCatalog.arrayTypeForElem(elemTy)

private fun extractCallableReturnRenderedType(renderedCallableType: String?): String? {
    val raw = renderedCallableType?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return CallableReturnTypeWalker(raw).extractReturnType()
}

private class CallableReturnTypeWalker(
    source: String,
) : KotlinTokenWalker(source) {
    fun extractReturnType(): String? {
        if (punctAt(0) == '(') {
            val closeParenIdx = findMatchingParenToken(0)
            if (closeParenIdx > 0) {
                val nextIdx = nextNonTriviaIndex(closeParenIdx + 1)
                if (tokens.getOrNull(nextIdx) is KotlinLexToken.Arrow) {
                    val retText = source.substring(tokens[nextIdx].end).trim()
                    return retText.ifEmpty { null }
                }
                if (closeParenIdx == tokens.lastIndex - 1 && punctAt(tokens.lastIndex) == '?') {
                    val inner = source.substring(tokens[0].end, tokens[closeParenIdx].start).trim()
                    if ("->" in inner) return CallableReturnTypeWalker(inner).extractReturnType()
                }
                return null
            }
        }
        val base = source.simpleKotlinTypeName()
        if (base in FN_TRAIT_NAMES && '<' in source && source.endsWith('>')) {
            val argsInner = source.substringAfter('<').dropLast(1)
            val comma = findTopLevelCommaIndex(argsInner)
            if (comma >= 0) return argsInner.substring(comma + 1).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    companion object {
        private val FN_TRAIT_NAMES = wordSet("Fn FnMut FnOnce")
    }
}

internal fun KotlinJ2KEmitter.inferExplicitArrayElementRenderedType(
    elem: Expr,
    selfTypeName: String?,
): String? {
    val lit = (unwrapParenOrGroupExpr(elem) as? Expr.Lit)?.lit
    if (lit is Lit.Bool) return "Boolean"
    if (lit is Lit.Byte) return "UByte"
    if (lit is Lit.Float) {
        return when (lit.value.suffix()) {
            "f32" -> kotlinF32TypeName()
            "f64" -> "Double"
            else -> null
        }
    }
    return inferExprRenderedType(elem, selfTypeName)
        ?: inferExprNumericKind(elem, selfTypeName)?.let(this::numericKindToPureTypeName)
}

internal fun KotlinJ2KEmitter.inferPrimitiveArrayTypeForArrayLiteral(
    expr: Expr,
    selfTypeName: String?,
): String? {
    val sampleElems =
        when (expr) {
            is Expr.Repeat -> listOf(expr.expr)
            is Expr.Array -> expr.elems.toList()
            else -> return null
        }
    return sampleElems.firstNotNullOfOrNull { elem ->
        primitiveArrayTypeForElementRenderedType(inferExplicitArrayElementRenderedType(elem, selfTypeName))
    }
}

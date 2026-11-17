package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.BinOp
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Fields
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.ForeignItem
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.ReturnType
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.gen.Visit
internal fun KotlinJ2KEmitter.resolveUnnamedTupleFieldSynType(
    baseExpr: Expr,
    index: Int,
): SynType? {
    val unwrappedBase = unwrapParenOrGroupExpr(baseExpr)
    val baseSynTy =
        when (unwrappedBase) {
            is Expr.Path ->
                if (unwrappedBase.qself == null && unwrappedBase.path.segments.len() == 1) {
                    val raw =
                        unwrappedBase.path.segments
                            .first()
                            ?.ident
                            ?.toString()
                            ?.removePrefix("r#")
                    when (raw) {
                        "self" -> currentStandaloneImplSelfSynType
                        null -> null
                        else -> {
                            val ktLocal = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
                            currentSourceParameterTypes[ktLocal] ?: currentSourceParameterTypes[raw]
                        }
                    }
                } else {
                    null
                }
            else -> null
        } ?: return null
    val unwrappedRef = unwrapSynType(baseSynTy).let { (it as? SynType.Reference)?.elem ?: it }
    val tupleTy = unwrapSynType(unwrappedRef) as? SynType.Tuple ?: return null
    return tupleTy.elems.toList().getOrNull(index)
}

internal fun KotlinJ2KEmitter.resolveContainerElementTraitCallee(callee: String): String? {
    val standaloneTraitPath = currentStandaloneImplTraitPath ?: return null
    val standaloneSelfSynType = currentStandaloneImplSelfSynType ?: return null
    val lastTraitSeg = standaloneTraitPath.segments.last() ?: return null
    val traitName = rustTypeIdentToKotlin(lastTraitSeg.ident.toString())
    if (!callee.startsWith("$traitName.")) return null
    val methodPart = callee.removePrefix("$traitName.")
    if (methodPart.isEmpty() || '.' in methodPart) return null
    val selfPath = (unwrapSynType(standaloneSelfSynType) as? SynType.Path)?.takeIf { it.qself == null } ?: return null
    val lastSelfSeg = selfPath.path.segments.last() ?: return null
    if (lastSelfSeg.ident.toString() != "Vec") return null
    val elemSynType =
        (lastSelfSeg.arguments as? PathArguments.AngleBracketed)
            ?.args
            ?.toList()
            ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
            ?: return null
    return disambiguateReceiverlessTraitImplFnName(standaloneTraitPath, methodPart, elemSynType)
}

internal fun KotlinJ2KEmitter.extractOwnerAndFieldName(expr: Expr, selfTypeName: String?): Pair<String?, String>? {
    val core = unwrapTransparentCastExpr(expr)
    if (core !is Expr.Field) return null
    val member = core.member as? Member.Named ?: return null
    val fieldName = rustFieldIdentToKotlin(member.ident.toString())
    val baseCore = unwrapTransparentCastExpr(core.base)
    val ownerType =
        if (baseCore is Expr.Path && baseCore.qself == null && baseCore.path.segments.len() == 1) {
            val firstSeg = baseCore.path.segments.first()
            val firstIdent = firstSeg?.ident?.toString()
            val baseRaw = firstIdent?.removePrefix("r#")
            if (baseRaw == "self") {
                selfTypeName?.substringBefore('<')?.trim()
            } else {
                baseRaw?.let(::rustTypeIdentToKotlin)?.takeIf { it in inspector.declaredTypeNames }
            }
        } else {
            null
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
    if (ownerType == null && inspector.vecFieldsByType.values.any { fieldName in it } && fieldName == "inner") {
        val baseCore = unwrapTransparentCastExpr((unwrapTransparentCastExpr(expr) as Expr.Field).base)
        if (baseCore is Expr.Path && baseCore.qself == null && baseCore.path.segments.len() == 1) {
            val firstSeg = baseCore.path.segments.first()
            val firstIdent = firstSeg?.ident?.toString()
            val rawVar = firstIdent?.removePrefix("r#").orEmpty()
            if (rustTypeIdentToKotlin(rawVar) in inspector.vecFieldsByType) return true
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.isKnownNullableOptionExpr(expr: Expr, renderedReceiver: String, selfTypeName: String?): Boolean {
    if (hasDeclaredOptionEnum()) return false
    val core = unwrapTransparentCastExpr(expr)
    if (core is Expr.MethodCall) {
        val m = core.method.toString()
        if (m in setOf("ok_or", "ok_or_else", "is_ok", "is_err", "is_some", "is_none", "map_err", "to_lowercase", "to_uppercase")) {
            return false
        }
        if (m == "ok" &&
            core.args.isEmpty() &&
            "ok" !in inspector.declaredTopLevelFnRawNames &&
            inspector.implMethodReturnSynTypes.keys.none { it.second == "ok" }
        ) {
            return true
        }
        if (m in KNOWN_OPTION_RETURNING_METHODS) return true
        if ((m == "fold" || m == "rfold") && core.args.toList().firstOrNull()?.let { firstArg ->
                val unwrapped = unwrapParenOrGroupExpr(firstArg)
                unwrapped is Expr.Path && unwrapped.qself == null && unwrapped.path.segments
                    .last()
                    ?.ident
                    ?.toString() == "None"
            } == true
        ) {
            return true
        }
        if ((m == "map" || m == "and_then" || m == "flatten" || m == "or" || m == "or_else" || m == "as_ref" || m == "as_mut" || m == "cloned" || m == "copied" || m == "filter") &&
            isKnownNullableOptionExpr(core.receiver, "", selfTypeName)
        ) {
            return true
        }
    }
    if (core is Expr.Call) {
        val fnPath = (unwrapParenOrGroupExpr(core.func) as? Expr.Path)?.takeIf { it.qself == null }
        val segs =
            fnPath
                ?.path
                ?.segments
                ?.toList()
                .orEmpty()
        if (segs.size >= 2 && ("core" in headerContext.crateNamespaces || headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true)) {
            val rawCallNames = segs.map { it.ident.toString().removePrefix("r#") }
            val owner = rawCallNames[rawCallNames.lastIndex - 1]
            val leaf = rawCallNames.last()
            val selfBase = selfTypeName?.substringBefore('<')?.substringAfterLast('.')?.trim()
            val resolvedOwner = if (owner == "Self") selfBase else owner
            if (leaf == "new" && resolvedOwner in setOf("NonNull", "NonZero", "Alignment")) {
                return true
            }
        }
        val lastRaw =
            segs
                .lastOrNull()
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
        if (lastRaw != null) {
            if (lastRaw in setOf("memchr", "memrchr")) {
                return true
            }
            val ktFn = rustFunctionIdentToKotlin(lastRaw)
            val localRet =
                inspector.topLevelFnReturnSynTypes[lastRaw]
                    ?: inspector.topLevelFnReturnSynTypes[ktFn]
            if (localRet != null) {
                val uRet = unwrapSynType(localRet) as? SynType.Path
                if (uRet
                        ?.path
                        ?.segments
                        ?.last()
                        ?.ident
                        ?.toString() == "Option"
                ) {
                    return true
                }
            } else if (headerContext.externalCrateNullableReturnFns.isNotEmpty()) {
                val importedPkg = importedSimpleNameToPkg[ktFn]
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
    val inferred = inferExprRenderedType(expr, selfTypeName)?.trim()
    if (inferred != null) {
        if (rustResultTypeArguments(inferred) != null || inferred.startsWith("Result<") || inferred.startsWith("RustResult<")) {
            return false
        }
        if (inferred.endsWith("?") && rawPointerPointeeType(inferred) == null) {
            return true
        }
    }
    if (renderedReceiver in currentNullableLocals || renderedReceiver.contains("?.let(")) return true
    val (ownerType, fieldName) = extractOwnerAndFieldName(expr, selfTypeName) ?: (null to "")
    if (ownerType != null && fieldName in inspector.nullableFieldsByType[ownerType].orEmpty()) return true
    return false
}

internal fun KotlinJ2KEmitter.unwrapZeroArgClosureText(renderedClosure: String): String {
    val trimmed = renderedClosure.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}") && !trimmed.contains("->")) {
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if ('\n' !in inner && ';' !in inner) {
            return inner
        }
        return "run $trimmed"
    }
    if (trimmed.startsWith("::")) {
        return "${trimmed.removePrefix("::")}()"
    }
    if ("::" in trimmed) {
        return "${trimmed.replaceFirst("::", ".")}()"
    }
    return "$trimmed()"
}

internal fun KotlinJ2KEmitter.primitiveArrayElementNumericKind(arrayType: String): NumericKind? {
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    return when (arrayType) {
        "ByteArray", "List<Byte>", "MutableList<Byte>" -> NumericKind.I8
        "UByteArray", "List<UByte>", "MutableList<UByte>" -> NumericKind.U8
        "ShortArray", "List<Short>", "MutableList<Short>" -> NumericKind.I16
        "UShortArray", "List<UShort>", "MutableList<UShort>" -> NumericKind.U16
        "IntArray", "List<Int>", "MutableList<Int>" -> NumericKind.I32
        "UIntArray", "List<UInt>", "MutableList<UInt>" -> NumericKind.U32
        "LongArray", "List<Long>", "MutableList<Long>" -> NumericKind.I64
        "ULongArray", "List<ULong>", "MutableList<ULong>" -> NumericKind.U64
        "List<I128>", "MutableList<I128>", "List<$coreNs.I128>", "MutableList<$coreNs.I128>" -> NumericKind.I128
        "List<U128>", "MutableList<U128>", "List<$coreNs.U128>", "MutableList<$coreNs.U128>" -> NumericKind.U128
        "List<F16>", "MutableList<F16>", "List<$coreNs.F16>", "MutableList<$coreNs.F16>" -> NumericKind.F16
        "FloatArray", "List<Float>", "MutableList<Float>", "List<kotlin.Float>", "MutableList<kotlin.Float>" -> NumericKind.F32
        "DoubleArray", "List<Double>", "MutableList<Double>" -> NumericKind.F64
        "List<F128>", "MutableList<F128>", "List<$coreNs.F128>", "MutableList<$coreNs.F128>" -> NumericKind.F128
        else -> null
    }
}

internal fun KotlinJ2KEmitter.inferArrayElementNumericKind(expr: Expr, selfTypeName: String?): NumericKind? {
    var containerTy = inferExprRenderedType(expr, selfTypeName)?.removeSuffix("?")?.trim() ?: return null
    primitiveArrayElementNumericKind(containerTy)?.let { return it }
    extractIndexedElementRenderedType(containerTy, selfTypeName)?.let(this::resolveNumericKind)?.let { return it }
    var depth = 0
    while (depth < 6) {
        val aliasSyn = inspector.typeAliasTargets[containerTy] ?: break
        val rendered = renderType(aliasSyn, selfTypeName = null).removeSuffix("?").trim()
        if (rendered == containerTy) break
        primitiveArrayElementNumericKind(rendered)?.let { return it }
        extractIndexedElementRenderedType(rendered, selfTypeName)?.let(this::resolveNumericKind)?.let { return it }
        containerTy = rendered
        depth++
    }
    return null
}

internal fun KotlinJ2KEmitter.extractIndexedElementRenderedType(containerTy: String, selfTypeName: String?): String? {
    var cur = containerTy.removeSuffix("?").trim()
    var depth = 0
    while (depth <= 6) {
        when (cur) {
            "ByteArray" -> return "Byte"
            "UByteArray" -> return "UByte"
            "ShortArray" -> return "Short"
            "UShortArray" -> return "UShort"
            "IntArray" -> return "Int"
            "UIntArray" -> return "UInt"
            "LongArray" -> return "Long"
            "ULongArray" -> return "ULong"
            "FloatArray" -> return kotlinF32TypeName()
            "DoubleArray" -> return "Double"
            "BooleanArray" -> return "Boolean"
        }
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
    val rawTy =
        (currentTypedLocals[renderedReceiver] ?: inferExprRenderedType(recvExpr, selfTypeName))
            ?.trim()
            ?: return false
    if (rawTy.isEmpty() ||
        rawTy.endsWith("?") ||
        rawTy == "Any" ||
        rawTy == "Option" ||
        rawTy.startsWith("Option<")
    ) {
        return false
    }
    var cur = rawTy
    var depth = 0
    while (depth < 8) {
        val base = cur.substringBefore('<').substringAfterLast('.').trim()
        if (base.isEmpty() || base in activeTypeParamNames) return false
        val aliasSyn = inspector.typeAliasTargets[base]
        if (aliasSyn != null) {
            val next = renderType(aliasSyn, selfTypeName).trim()
            if (next.endsWith("?") || next == "Any" || next == "Option" || next.startsWith("Option<")) {
                return false
            }
            if (next == cur) break
            cur = next
            depth++
            continue
        }
        val crateAlias = headerContext.crateTypeAliasDefaults[base]?.trim()
        if (crateAlias != null) {
            if (crateAlias.endsWith("?") || crateAlias == "Any" || crateAlias == "Option" || crateAlias.startsWith("Option<")) {
                return false
            }
            if (crateAlias == cur) break
            cur = crateAlias
            depth++
            continue
        }
        break
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
    if (!shouldMemoize) {
        return inferExprRenderedTypeImpl(expr, selfTypeName)
    }
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
    val computed = inferExprRenderedTypeImpl(expr, selfTypeName)
    inferExprRenderedTypeCache[cacheKey] = computed
    return computed
}

private fun KotlinJ2KEmitter.inferExprRenderedTypeImpl(expr: Expr, selfTypeName: String?): String? =
    when (expr) {
        is Expr.Paren -> inferExprRenderedType(expr.expr, selfTypeName)
        is Expr.Group -> inferExprRenderedType(expr.expr, selfTypeName)
        is Expr.Reference -> {
            val innerSynType = sourceInitializerType(expr.expr)
            val innerRendered = inferExprRenderedType(expr.expr, selfTypeName) ?: innerSynType?.let { renderType(it, selfTypeName) }
            innerRendered?.let { rendered ->
                val local = extractSingleSegmentLocalKotlinName(expr.expr)
                val isAddrMut =
                    expr.mutability != null &&
                        !headerContext.rawAbiMode &&
                        (
                            (local != null && local in currentAddressableLocals) ||
                                (local == null && innerSynType != null && shouldUseAddressBackedMutableReference(innerSynType, selfTypeName))
                        )
                if (isAddrMut) {
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.RustMutableReference")
                    "RustMutableReference<$rendered>"
                } else {
                    rendered
                }
            }
        }
        is Expr.RawAddr ->
            if (headerContext.rawAbiMode) {
                "Long"
            } else {
                inferExprRenderedType(expr.expr, selfTypeName)?.let { pointee ->
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    val ptrType = if (expr.mutability is PointerMutability.Mut) "MutPtr" else "ConstPtr"
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.$ptrType")
                    "$ptrType<$pointee>"
                }
            }
        is Expr.Await -> inferExprRenderedType(expr.base, selfTypeName)
        is Expr.Try -> {
            val inner = unwrapParenOrGroupExpr(expr.expr)
            val innerTurbofish = (inner as? Expr.MethodCall)?.turbofish
            if (inner is Expr.MethodCall &&
                inner.method.toString().removePrefix("r#") == "parse" &&
                inner.args.isEmpty() &&
                innerTurbofish != null &&
                innerTurbofish.args.size == 1
            ) {
                val tfGa = innerTurbofish.args.first() as? GenericArgument.TypeArg
                if (tfGa != null) {
                    renderType(tfGa.type, selfTypeName)
                } else {
                    null
                }
            } else {
                val innerTy = inferExprRenderedType(expr.expr, selfTypeName)?.trim()
                when {
                    innerTy == null -> null
                    rustResultOkPayloadType(innerTy) != null -> rustResultOkPayloadType(innerTy)
                    innerTy.endsWith("?") -> innerTy.removeSuffix("?").trim()
                    else -> null
                }
            }
        }
        is Expr.Cast -> renderType(expr.ty, selfTypeName)
        is Expr.Struct -> {
            val lastSeg =
                expr.path.segments
                    .toList()
                    .lastOrNull()
                    ?.ident
                    ?.toString()
            when (lastSeg) {
                null -> null
                "Self" -> selfTypeName?.substringBefore('<')?.trim()
                else -> rustTypeIdentToKotlin(lastSeg)
            }
        }
        is Expr.Path -> {
            val constPrimKind = resolvePrimitiveAssociatedConstKind(expr.path, expr.qself)
            val segs = expr.path.segments.toList()
            val hasOnlyIgnoredPrefixes =
                expr.qself == null &&
                    segs.isNotEmpty() &&
                    segs.dropLast(1).all {
                        it.arguments is PathArguments.None &&
                            it.ident.toString().removePrefix("r#") in IGNORED_PATH_PREFIXES
                    }
            when {
                constPrimKind != null -> numericKindToPureTypeName(constPrimKind)
                hasOnlyIgnoredPrefixes -> {
                    val raw =
                        segs
                            .last()
                            .ident
                            .toString()
                            .removePrefix("r#")
                    when {
                        raw == "self" && segs.size == 1 -> selfTypeName
                        else -> {
                            val ktConst = inspector.declaredConstNames[raw]
                            val constTy = ktConst?.let { inspector.declaredConstTypes[it] }
                            when {
                                constTy != null -> renderType(constTy, selfTypeName)
                                segs.size == 1 -> {
                                    val ktLocal = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
                                    currentTypedLocals[ktLocal]
                                        ?: if (raw in inspector.unitStructNames) {
                                            raw
                                        } else if ("core" in headerContext.crateNamespaces || headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true) {
                                            resolveStdlibUnitStructValueExpr(listOf(raw))?.removeSuffix("()")
                                        } else {
                                            null
                                        }
                                }
                                else -> null
                            }
                        }
                    }
                }
                else ->
                    if (expr.qself == null && ("core" in headerContext.crateNamespaces || headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true)) {
                        resolveStdlibUnitStructValueExpr(segs.map { it.ident.toString().removePrefix("r#") })?.removeSuffix("()")
                    } else {
                        null
                    }
            }
        }
        is Expr.Unary ->
            when (expr.op) {
                is UnOp.Deref ->
                    rawPointerPointeeType(inferExprRenderedType(expr.expr, selfTypeName))
                        ?: mutableReferencePointeeType(inferExprRenderedType(expr.expr, selfTypeName))
                        ?: mutablePlaceValueType(inferExprRenderedType(expr.expr, selfTypeName))
                        ?: refCellGuardPointeeType(inferExprRenderedType(expr.expr, selfTypeName))
                        ?: borrowedPlaceLocalName(expr.expr)?.let { currentTypedLocals[it] }
                        ?: inferExprRenderedType(expr.expr, selfTypeName)?.removeSuffix("?")
                is UnOp.Neg -> inferExprRenderedType(expr.expr, selfTypeName)
                is UnOp.NotOp ->
                    if (isKnownIntegerBitmaskExpr(expr.expr) || inferExprNumericKind(expr.expr, selfTypeName)?.isIntegerNumericKind() == true) {
                        inferExprRenderedType(expr.expr, selfTypeName)
                    } else {
                        "Boolean"
                    }
            }
        is Expr.Field -> {
            if (expr.member is Member.Unnamed) {
                resolveUnnamedTupleFieldSynType(expr.base, (expr.member as Member.Unnamed).index.index.toInt())
                    ?.let { return renderType(it, selfTypeName) }
            }
            val baseReferenceTy = inferExprRenderedType(expr.base, selfTypeName) ?: return null
            val baseTy =
                (
                    mutableReferencePointeeType(baseReferenceTy)
                        ?: refCellGuardPointeeType(baseReferenceTy)
                        ?: baseReferenceTy
                ).removeSuffix("?").trim()
            val rawOwnerKt = baseTy.substringAfterLast('.').substringBefore('<').trim()
            if (rawOwnerKt.isEmpty()) return null
            val aliasedOwnerKt =
                inspector.typeAliasTargets[rawOwnerKt]?.let {
                    renderType(it, null).substringAfterLast('.').substringBefore('<').trim()
                }
            val ownerKt = aliasedOwnerKt?.takeIf { it.isNotEmpty() && it in inspector.structFieldSynTypesByType } ?: rawOwnerKt
            when (val member = expr.member) {
                is Member.Named -> {
                    val fieldKt = rustFieldIdentToKotlin(member.ident.toString())
                    inspector.structFieldSynTypesByType[ownerKt]?.get(fieldKt)?.let { return renderType(it, ownerKt) }
                    for (key in scopedAbiKeysForRenderedType(baseTy)) {
                        headerContext.crateStructFieldTypes["$key.$fieldKt"]?.let { return it }
                    }
                    if (aliasedOwnerKt != null && aliasedOwnerKt != rawOwnerKt) {
                        for (key in scopedAbiKeysForRenderedType(aliasedOwnerKt)) {
                            headerContext.crateStructFieldTypes["$key.$fieldKt"]?.let { return it }
                        }
                    }
                    headerContext.crateStructFieldTypes["$ownerKt.$fieldKt"]
                }
                is Member.Unnamed -> {
                    val idx = member.index.index.toInt()
                    if (ownerKt == "Pair" || ownerKt == "Triple") {
                        val ltIdx = baseTy.indexOf('<')
                        val gtIdx = baseTy.lastIndexOf('>')
                        if (ltIdx >= 0 && gtIdx > ltIdx) {
                            val typeArgs =
                                PhantomRawPointerTypeStripper
                                    .splitTopLevelCommaList(baseTy.substring(ltIdx + 1, gtIdx))
                                    .map { it.trim() }
                            typeArgs.getOrNull(idx)?.let { return it }
                        }
                    }
                    val arity = inspector.tupleStructArityByType[ownerKt]
                    val primaryField =
                        if (arity != null) {
                            tupleFieldPropertyName(idx, arity)
                        } else {
                            tupleAccessMemberName(expr.base, idx, selfTypeName)
                        }
                    inspector.structFieldSynTypesByType[ownerKt]?.get(primaryField)?.let { return renderType(it, ownerKt) }
                    if (idx == 0 && primaryField != "value") {
                        inspector.structFieldSynTypesByType[ownerKt]?.get("value")?.let { return renderType(it, ownerKt) }
                        inspector.structFieldSynTypesByType[ownerKt]?.get("field0")?.let { return renderType(it, ownerKt) }
                    }
                    val candidateFields =
                        if (idx == 0 && primaryField != "value") {
                            listOf(primaryField, "value")
                        } else {
                            listOf(primaryField)
                        }
                    candidateFields.firstNotNullOfOrNull { fName ->
                        scopedAbiKeysForRenderedType(baseTy).firstNotNullOfOrNull { key ->
                            headerContext.crateStructFieldTypes["$key.$fName"]
                        } ?: headerContext.crateStructFieldTypes["$ownerKt.$fName"]
                    }
                }
            }
        }
        is Expr.Index -> {
            if (expr.index is Expr.Range) {
                inferExprRenderedType(expr.expr, selfTypeName)
            } else {
                val baseTy = inferExprRenderedType(expr.expr, selfTypeName) ?: return null
                extractIndexedElementRenderedType(baseTy, selfTypeName)
            }
        }
        is Expr.Call -> {
            resolvePrimitiveFromBytesSpec(expr)?.let { return it.second }
            if ("core" in headerContext.crateNamespaces || headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true) {
                val fnPath = (unwrapParenOrGroupExpr(expr.func) as? Expr.Path)?.takeIf { it.qself == null }
                val rawSegs =
                    fnPath
                        ?.path
                        ?.segments
                        ?.toList()
                        ?.map { it.ident.toString().removePrefix("r#") }
                        .orEmpty()
                if (rawSegs.size >= 2) {
                    val owner = rawSegs[rawSegs.lastIndex - 1]
                    val method = rawSegs.last()
                    val selfBase = selfTypeName?.substringBefore('<')?.substringAfterLast('.')?.trim()
                    val resolvedOwner = if (owner == "Self") selfBase else owner
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    when {
                        resolvedOwner == "NonNull" && method == "new" -> {
                            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.nonnull.NonNull")
                            return "NonNull?"
                        }
                        resolvedOwner == "NonNull" && method in setOf("new_unchecked", "dangling") -> {
                            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.nonnull.NonNull")
                            return "NonNull"
                        }
                        resolvedOwner == "Alignment" && method == "new" -> {
                            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.alignment.Alignment")
                            return "Alignment?"
                        }
                        resolvedOwner == "Alignment" && method == "new_unchecked" -> {
                            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.alignment.Alignment")
                            return "Alignment"
                        }
                        resolvedOwner == "NonZero" && (method == "new" || method == "new_unchecked") -> {
                            val kotlinMethod = if (method == "new") "new" else "newUnchecked"
                            val resolvedArgs = resolveNonZeroNewExplicitTypeArgs("NonZero.$kotlinMethod", expr.args.toList(), selfTypeName)
                            if (resolvedArgs != null) {
                                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.nonzero.NonZero")
                                val suffix = if (method == "new") "?" else ""
                                return "NonZero<${resolvedArgs.second}, ${resolvedArgs.first}>$suffix"
                            }
                        }
                    }
                }
            }
            if ("core" in headerContext.crateNamespaces) {
                val fnPathCall = (expr.func as? Expr.Path)?.takeIf { it.qself == null }
                val rawCalleeSegs =
                    fnPathCall
                        ?.path
                        ?.segments
                        ?.toList()
                        ?.map { it.ident.toString().removePrefix("r#") }
                        .orEmpty()
                val isFromRawPartsCall =
                    expr.args.len() == 2 &&
                        (
                            rawCalleeSegs == listOf("from_raw_parts") ||
                                rawCalleeSegs == listOf("from_raw_parts_mut") ||
                                rawCalleeSegs.takeLast(2) == listOf("slice", "from_raw_parts") ||
                                rawCalleeSegs.takeLast(2) == listOf("slice", "from_raw_parts_mut")
                        )
                if (isFromRawPartsCall) {
                    val ptrTy = inferExprRenderedType(expr.args.toList()[0], selfTypeName)
                    val elem = rawPointerPointeeType(ptrTy)
                    if (elem != null) {
                        val isMut = rawCalleeSegs.last().endsWith("_mut")
                        return if (isMut) "MutableList<$elem>" else "List<$elem>"
                    }
                }
            }
            parseStaticPrimitiveCallSpec(expr)?.let { spec ->
                when (spec.methodName) {
                    "from_bits" -> return numericKindToPureTypeName(spec.primKind)
                    "from_str_radix" ->
                        if ("core" in headerContext.crateNamespaces) {
                            val elem = numericKindToPureTypeName(spec.primKind)
                            return if (usesTaggedCoreResult()) {
                                "${sourceResultName()}<$elem, ParseIntError>"
                            } else {
                                "Result<$elem>"
                            }
                        }
                    else ->
                        inferPrimitiveOpReturnRenderedType(
                            recvKind = spec.primKind,
                            rawMethod = spec.methodName,
                            turbofishType = spec.turbofishType,
                            selfTypeName = selfTypeName,
                        )?.let { return it }
                }
            }
            CoreIntrinsicLowering.inferCoreIntrinsicReturnRenderedType(expr, selfTypeName, this)?.let { return it }
            val fnPath = (expr.func as? Expr.Path)?.takeIf { it.qself == null } ?: return null
            val lastCallIdent =
                fnPath.path.segments
                    .last()
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
            if (lastCallIdent in setOf("size_of", "size_of_val", "align_of", "align_of_val")) {
                return when {
                    currentExpectedNumericKind != null -> null
                    headerContext.rawAbiMode -> "ULong"
                    else -> "Int"
                }
            }
            if (lastCallIdent == "strlen" && (currentExpectedNumericKind == NumericKind.I32 || currentEnclosingReturnRenderedType == "Int")) {
                return "Int"
            }
            if (lastCallIdent == "type_name") {
                return "String"
            }
            if (lastCallIdent in setOf("memchr", "memrchr") &&
                lastCallIdent !in inspector.declaredTopLevelFnRawNames &&
                "memchr" !in inspector.declaredTypeNames
            ) {
                return "Int?"
            }
            fun renderTopLevelFnRet(ktFn: String, rawFn: String? = null): String? {
                val synRet =
                    (rawFn?.let { inspector.topLevelFnReturnSynTypes[it] })
                        ?: inspector.topLevelFnReturnSynTypes[ktFn]
                        ?: return null
                val prevExtern = inExternAbiSignature
                if (ktFn in inspector.externFnKotlinNames) {
                    inExternAbiSignature = true
                }
                return try {
                    renderType(synRet, selfTypeName)
                } finally {
                    inExternAbiSignature = prevExtern
                }
            }
            val segs =
                fnPath.path.segments
                    .toList()
                    .filter { it.ident.toString() !in IGNORED_PATH_PREFIXES }
            when {
                segs.size == 1 -> {
                    val raw = segs[0].ident.toString().removePrefix("r#")
                    if (raw == "Self") {
                        selfTypeName?.substringBefore('<')?.trim()
                    } else if (raw.isNotEmpty() && raw.first().isUpperCase() && raw !in inspector.declaredTopLevelFnRawNames) {
                        val ktType = rustTypeIdentToKotlin(raw)
                        if (ktType in inspector.declaredTypeNames ||
                            scopedAbiKeysForRenderedType(ktType).any { it in headerContext.crateSymbolSubpackages || "$it.value" in headerContext.crateStructFieldTypes }
                        ) {
                            ktType
                        } else {
                            val ktFn = rustFunctionIdentToKotlin(raw)
                            renderTopLevelFnRet(ktFn, raw)
                                ?: scopedAbiKeysForRenderedType(ktFn).firstNotNullOfOrNull { headerContext.crateFnReturnTypes[it] }
                                ?: headerContext.crateFnReturnTypes[ktFn]
                        }
                    } else {
                        val localKt = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
                        extractCallableReturnRenderedType(currentTypedLocals[localKt])
                            ?: run {
                                val ktFn = rustFunctionIdentToKotlin(raw)
                                renderTopLevelFnRet(ktFn, raw)
                                    ?: scopedAbiKeysForRenderedType(ktFn).firstNotNullOfOrNull { headerContext.crateFnReturnTypes[it] }
                                    ?: headerContext.crateFnReturnTypes[ktFn]
                            }
                    }
                }
                segs.size >= 2 -> {
                    val penultSeg = segs[segs.size - 2]
                    val lastSeg = segs[segs.size - 1]
                    val penultRaw = penultSeg.ident.toString().removePrefix("r#")
                    val lastRaw = lastSeg.ident.toString().removePrefix("r#")
                    val penultTypeArgs =
                        (penultSeg.arguments as? PathArguments.AngleBracketed)
                            ?.args
                            ?.toList()
                            .orEmpty()
                            .filterIsInstance<GenericArgument.TypeArg>()
                            .map { renderType(it.type, selfTypeName) }
                    when {
                        penultRaw == "String" &&
                            lastRaw in setOf("new", "with_capacity", "from") &&
                            "String" !in inspector.declaredTypeNames -> return "String"
                        penultRaw == "Vec" &&
                            lastRaw in setOf("new", "with_capacity", "from") &&
                            "Vec" !in inspector.declaredTypeNames ->
                            return if (penultTypeArgs.size == 1) "MutableList<${penultTypeArgs[0]}>" else "MutableList<*>"
                        (penultRaw == "HashMap" || penultRaw == "BTreeMap") &&
                            lastRaw in setOf("new", "with_capacity", "from") &&
                            penultRaw !in inspector.declaredTypeNames ->
                            return if (penultTypeArgs.size >= 2) {
                                "MutableMap<${penultTypeArgs[0]}, ${penultTypeArgs[1]}>"
                            } else {
                                "MutableMap<*, *>"
                            }
                        (penultRaw == "HashSet" || penultRaw == "BTreeSet") &&
                            lastRaw in setOf("new", "with_capacity", "from") &&
                            penultRaw !in inspector.declaredTypeNames ->
                            return if (penultTypeArgs.isNotEmpty()) "MutableSet<${penultTypeArgs[0]}>" else "MutableSet<*>"
                        headerContext.scalarChar &&
                            (penultRaw == "char" || penultRaw == "RustChar") &&
                            "Char" !in inspector.declaredTypeNames ->
                            when (lastRaw) {
                                "from_u32" -> return "RustChar?"
                                "from_u32_unchecked", "from" -> return "RustChar"
                                "try_from" ->
                                    return if (usesTaggedCoreResult()) {
                                        "${sourceResultName()}<RustChar, CharTryFromError>"
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
                        val canonicalOwner = resolveTypeAliasOwnerName(ownerKt)
                        inspector.implMethodReturnSynTypes[canonicalOwner to methodKt]?.let { return renderType(it, canonicalOwner) }
                        inspector.implMethodReturnSynTypes[ownerKt to methodKt]?.let { return renderType(it, ownerKt) }
                        resolveMethodSignatureForOwner(ownerKt, methodKt)?.let { (resolvedOwner, sig) ->
                            when (val out = sig.output) {
                                is ReturnType.TypeReturn -> return renderType(out.ty, resolvedOwner)
                                is ReturnType.Default -> return "Unit"
                            }
                        }
                        val modPrefix =
                            segs
                                .dropLast(2)
                                .joinToString(".") { rustModuleIdentToKotlin(it.ident.toString().replace('-', '_')) }
                        if (modPrefix.isNotEmpty()) {
                            headerContext.crateFnReturnTypes["$modPrefix.$canonicalOwner.$methodKt"]?.let { return it }
                            headerContext.crateFnReturnTypes["$modPrefix.$ownerKt.$methodKt"]?.let { return it }
                        }
                        for (ownerKey in (scopedAbiKeysForRenderedType(canonicalOwner) + scopedAbiKeysForRenderedType(ownerKt)).distinct()) {
                            headerContext.crateFnReturnTypes["$ownerKey.$methodKt"]?.let { return it }
                        }
                        headerContext.crateFnReturnTypes["$canonicalOwner.$methodKt"]
                            ?: headerContext.crateFnReturnTypes["$ownerKt.$methodKt"]
                    } else {
                        val modSubpkg =
                            segs
                                .dropLast(1)
                                .joinToString(".") { rustModuleIdentToKotlin(it.ident.toString().replace('-', '_')) }
                        headerContext.crateFnReturnTypes["$modSubpkg.$methodKt"]
                            ?: headerContext.crateFnReturnTypes[methodKt]
                    }
                }
                else -> null
            }
        }
        is Expr.MethodCall -> {
            val rawMethod = expr.method.toString().removePrefix("r#")
            sourceReceiverMethodReturn(expr.receiver, rawMethod)?.let { return renderType(it, selfTypeName) }
            val lazyRecvType by lazy(LazyThreadSafetyMode.NONE) { inferExprRenderedType(expr.receiver, selfTypeName) }
            if (rawMethod in setOf("as_ptr", "as_mut_ptr") && expr.args.isEmpty()) {
                sourceArrayLocal(expr.receiver)?.second?.let {
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    val ptrType = if (rawMethod == "as_mut_ptr") "MutPtr" else "ConstPtr"
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.$ptrType")
                    return "$ptrType<${renderType(it, selfTypeName)}>"
                }
                val recvRendered = lazyRecvType
                val recvBase = recvRendered?.removeSuffix("?")?.substringBefore('<')?.trim()
                if (recvBase == "NonNull" || recvBase?.endsWith(".NonNull") == true) {
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.MutPtr")
                    return "MutPtr"
                }
                val elemRendered = recvRendered?.let { extractIndexedElementRenderedType(it, selfTypeName) }
                if (elemRendered != null) {
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    val ptrType = if (rawMethod == "as_mut_ptr") "MutPtr" else "ConstPtr"
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.$ptrType")
                    return "$ptrType<$elemRendered>"
                }
            }
            if ((rawMethod == "to_be" || rawMethod == "from_be" || rawMethod == "swap_bytes" || rawMethod == "to_le" || rawMethod == "from_le") &&
                expr.args.isEmpty()
            ) {
                return lazyRecvType
                    ?: inferExprNumericKind(expr.receiver, selfTypeName)?.let(this::numericKindToPureTypeName)
            }
            if (!hasDeclaredOptionEnum() && expr.turbofish == null) {
                val optRecvTy = lazyRecvType
                val recvIsNullable = optRecvTy != null && optRecvTy.endsWith("?")
                if (recvIsNullable) {
                    when {
                        rawMethod == "unwrap" && expr.args.isEmpty() -> return optRecvTy.removeSuffix("?")
                        (rawMethod == "expect" || rawMethod == "unwrap_or" || rawMethod == "unwrap_or_else") && expr.args.size == 1 ->
                            return optRecvTy.removeSuffix("?")
                    }
                }
                if (recvIsNullable || isKnownNullableOptionExpr(expr.receiver, "", selfTypeName)) {
                    when {
                        (rawMethod == "or" || rawMethod == "or_else" || rawMethod == "filter") && expr.args.size == 1 ->
                            return optRecvTy ?: "Any?"
                        (rawMethod == "cloned" || rawMethod == "copied" || rawMethod == "as_ref" || rawMethod == "as_mut") && expr.args.isEmpty() ->
                            return optRecvTy ?: "Any?"
                        rawMethod == "flatten" && expr.args.isEmpty() ->
                            return optRecvTy?.removeSuffix("?")?.let { inner ->
                                if (inner.endsWith("?")) inner else "$inner?"
                            } ?: "Any?"
                        (rawMethod == "map" || rawMethod == "and_then") && expr.args.size == 1 -> {
                            val cbTy =
                                expr.args
                                    .first()
                                    ?.let { unwrapParenOrGroupExpr(it) as? Expr.Closure }
                                    ?.body
                                    ?.let { inferExprRenderedType(it, selfTypeName)?.removeSuffix("?") }
                                    ?: "Any"
                            return "$cbTy?"
                        }
                    }
                }
            }
            if ("core" in headerContext.crateNamespaces && expr.turbofish == null) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
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
                        (recvRendered == "Char" || (unwrapParenOrGroupExpr(expr.receiver) as? Expr.Lit)?.lit is Lit.Char) ->
                        return "UInt?"
                }
            }
            val recvNumericKind =
                when (expr.receiver) {
                    is Expr.Call, is Expr.MethodCall, is Expr.BlockExpr, is Expr.Unsafe, is Expr.If ->
                        lazyRecvType?.let(this::resolveNumericKind)
                    else -> inferExprNumericKind(expr.receiver, selfTypeName)
                }
            recvNumericKind?.let { concreteRecvKind ->
                val tfType =
                    expr.turbofish
                        ?.args
                        ?.toList()
                        ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
                inferPrimitiveOpReturnRenderedType(concreteRecvKind, rawMethod, tfType, selfTypeName)?.let {
                    return it
                }
            }
            when {
                (
                    rawMethod == "offset" ||
                        rawMethod == "add" ||
                        rawMethod == "sub" ||
                        rawMethod == "byte_offset" ||
                        rawMethod == "byte_add" ||
                        rawMethod == "byte_sub" ||
                        rawMethod == "wrapping_offset" ||
                        rawMethod == "wrapping_add" ||
                        rawMethod == "wrapping_sub" ||
                        rawMethod == "wrapping_byte_offset" ||
                        rawMethod == "wrapping_byte_add" ||
                        rawMethod == "wrapping_byte_sub"
                ) && expr.args.size == 1 -> lazyRecvType
                rawMethod == "cast_mut" && expr.args.isEmpty() -> {
                    val recvTy = lazyRecvType
                    val pointee = rawPointerPointeeType(recvTy)
                    if (pointee != null) {
                        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.MutPtr")
                        "MutPtr<$pointee>"
                    } else {
                        recvTy
                    }
                }
                rawMethod == "cast_const" && expr.args.isEmpty() -> {
                    val recvTy = lazyRecvType
                    val pointee = rawPointerPointeeType(recvTy)
                    if (pointee != null) {
                        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.ConstPtr")
                        "ConstPtr<$pointee>"
                    } else {
                        recvTy
                    }
                }
                rawMethod == "wrapping_mul" && expr.args.size == 1 -> lazyRecvType
                (rawMethod == "to_be_bytes" || rawMethod == "to_le_bytes" || rawMethod == "to_ne_bytes") && expr.args.isEmpty() ->
                    "UByteArray"
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
                rawMethod == "as_str" &&
                    expr.args.isEmpty() &&
                    inspector.instanceMethodsByType.values.none { "asStr" in it } -> "String"
                (rawMethod == "ok_or" || rawMethod == "ok_or_else") && expr.args.size == 1 ->
                    if (usesTaggedCoreResult()) {
                        val okInner = lazyRecvType?.removeSuffix("?")?.trim() ?: "*"
                        val errExpr =
                            if (rawMethod == "ok_or_else") {
                                (unwrapParenOrGroupExpr(expr.args[0]) as? Expr.Closure)?.body
                            } else {
                                expr.args[0]
                            }
                        val errInner = errExpr?.let { inferExprRenderedType(it, selfTypeName)?.trim() } ?: "*"
                        "${sourceResultName()}<$okInner, $errInner>"
                    } else {
                        "Result<*>"
                    }
                (rawMethod == "cloned" || rawMethod == "copied") && expr.args.isEmpty() ->
                    lazyRecvType
                rawMethod == "cast" && expr.args.isEmpty() -> {
                    val recvTy = lazyRecvType
                    if (rawPointerPointeeType(recvTy) != null) {
                        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                        val ptrCtor = if (isMutRawPointerRenderedType(recvTy)) "MutPtr" else "ConstPtr"
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.$ptrCtor")
                        val destination =
                            expr.turbofish
                                ?.args
                                ?.toList()
                                ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
                        return destination?.let { "$ptrCtor<${renderType(it, selfTypeName)}>" }
                            ?: currentExpectedParseTargetType
                            ?: currentEnclosingReturnRenderedType
                            ?: recvTy
                    }
                    lazyRecvType
                }
                rawMethod == "clone" && expr.args.isEmpty() -> lazyRecvType
                else -> {
                    val receiverType = lazyRecvType ?: return null
                    val recvTy =
                        (
                            mutableReferencePointeeType(receiverType)
                                ?: refCellGuardPointeeType(receiverType)
                                ?: receiverType
                        ).removeSuffix("?").trim()
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
                    val ownerKt = recvTy.substringAfterLast('.').substringBefore('<').trim()
                    if (ownerKt.isEmpty()) return null
                    val methodKt = rustFunctionIdentToKotlin(rawMethod)
                    mutablePlaceReturnType(ownerKt, rawMethod)?.let { return it }
                    inspector.implMethodReturnSynTypes[ownerKt to methodKt]?.let { return renderType(it, ownerKt) }
                    for (ownerKey in scopedAbiKeysForRenderedType(recvTy)) {
                        headerContext.crateFnReturnTypes["$ownerKey.$methodKt"]?.let { return it }
                    }
                    headerContext.crateFnReturnTypes["$ownerKt.$methodKt"]
                }
            }
        }
        is Expr.BlockExpr -> {
            val tail = expr.block.stmts.lastOrNull() as? Stmt.ExprStmt
            if (tail != null && tail.semiToken == null) inferExprRenderedType(tail.expr, selfTypeName) else null
        }
        is Expr.Unsafe -> {
            val tail = expr.block.stmts.lastOrNull() as? Stmt.ExprStmt
            if (tail != null && tail.semiToken == null) inferExprRenderedType(tail.expr, selfTypeName) else null
        }
        is Expr.If -> {
            val tail = expr.thenBranch.stmts.lastOrNull() as? Stmt.ExprStmt
            if (tail != null && tail.semiToken == null) inferExprRenderedType(tail.expr, selfTypeName) else null
        }
        is Expr.Repeat -> {
            val elemTy = inferExplicitArrayElementRenderedType(expr.expr, selfTypeName)
            primitiveArrayTypeForElementRenderedType(elemTy) ?: elemTy?.let { "List<$it>" }
        }
        is Expr.Array -> {
            val elemTy = expr.elems.toList().firstNotNullOfOrNull { inferExplicitArrayElementRenderedType(it, selfTypeName) }
            primitiveArrayTypeForElementRenderedType(elemTy) ?: elemTy?.let { "List<$it>" }
        }
        else -> null
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

internal fun KotlinJ2KEmitter.numericKindToTypeNameRecordingFlags(kind: NumericKind): String =
    when (kind) {
        NumericKind.I128 -> {
            usesI128 = true
            "I128"
        }
        NumericKind.U128 -> {
            usesU128 = true
            "U128"
        }
        else -> numericKindToPureTypeName(kind)
    }

internal fun KotlinJ2KEmitter.unwrapParenOrGroupExpr(expr: Expr): Expr =
    when (expr) {
        is Expr.Paren -> unwrapParenOrGroupExpr(expr.expr)
        is Expr.Group -> unwrapParenOrGroupExpr(expr.expr)
        else -> expr
    }

internal fun KotlinJ2KEmitter.isNarrowComparisonLiteralOperand(operand: Expr, targetKind: NumericKind): Boolean {
    val isNarrowUnsigned = targetKind == NumericKind.U8 || targetKind == NumericKind.U16
    val isNarrowSigned = targetKind == NumericKind.I8 || targetKind == NumericKind.I16
    if (!isNarrowUnsigned && !isNarrowSigned) return false
    val core = unwrapParenOrGroupExpr(operand)
    if (core is Expr.Lit) {
        val lit = core.lit
        if (lit is Lit.Int) {
            val suffix = lit.value.suffix()
            return if (isNarrowUnsigned) {
                suffix.isEmpty() || suffix == "u8" || suffix == "u16"
            } else {
                suffix.isEmpty() || suffix == "i8" || suffix == "i16"
            }
        }
        return false
    }
    if (isNarrowSigned && core is Expr.Unary && core.op is UnOp.Neg) {
        val inner = unwrapParenOrGroupExpr(core.expr)
        val innerLit = (inner as? Expr.Lit)?.lit as? Lit.Int ?: return false
        val suffix = innerLit.value.suffix()
        return suffix.isEmpty() || suffix == "i8" || suffix == "i16"
    }
    return false
}

internal fun KotlinJ2KEmitter.coerceRenderedNarrowLiteral(sourceExpr: Expr, rendered: String, kind: NumericKind): String {
    if (isAlreadyRenderedAsNumericKind(rendered, kind)) return rendered
    val recv = if (isAtomicPostfixReceiver(sourceExpr, rendered)) rendered else "($rendered)"
    if (kind !in setOf(NumericKind.U8, NumericKind.U16, NumericKind.I8, NumericKind.I16)) return rendered
    val suffix = NUMERIC_COERCE_SUFFIX_BY_KIND[kind] ?: return rendered
    return if (rendered.endsWith(".$suffix")) rendered else "$recv.$suffix"
}

internal fun KotlinJ2KEmitter.isUnsignedKind(kind: NumericKind): Boolean =
    kind == NumericKind.U8 ||
        kind == NumericKind.U16 ||
        kind == NumericKind.U32 ||
        kind == NumericKind.U64 ||
        kind == NumericKind.U128

internal fun KotlinJ2KEmitter.coerceRenderedNumericExpr(sourceExpr: Expr, rendered: String, kind: NumericKind): String {
    if (isAlreadyRenderedAsNumericKind(rendered, kind)) return rendered
    val recv = if (isAtomicPostfixReceiver(sourceExpr, rendered)) rendered else "($rendered)"
    if (kind == NumericKind.F16 || kind == NumericKind.F128) {
        return convertNumericReceiver(recv, numericKindToPureTypeName(kind), inferExprNumericKind(sourceExpr, selfTypeName = null))
    }
    val suffix = NUMERIC_COERCE_SUFFIX_BY_KIND[kind] ?: return rendered
    return if (rendered.endsWith(".$suffix")) rendered else "$recv.$suffix"
}

internal fun KotlinJ2KEmitter.inferExprNumericKind(expr: Expr, selfTypeName: String?): NumericKind? =
    when (expr) {
        is Expr.Paren -> inferExprNumericKind(expr.expr, selfTypeName)
        is Expr.Group -> inferExprNumericKind(expr.expr, selfTypeName)
        is Expr.Cast -> resolveNumericKind(renderType(expr.ty, selfTypeName))
        is Expr.Index -> inferArrayElementNumericKind(expr.expr, selfTypeName)
        is Expr.Field -> resolveFieldRenderedType(expr, selfTypeName)?.let(this::resolveNumericKind)
        is Expr.Call, is Expr.MethodCall, is Expr.BlockExpr, is Expr.Unsafe, is Expr.If ->
            inferExprRenderedType(expr, selfTypeName)?.let(this::resolveNumericKind)
        is Expr.Lit -> {
            val lit = expr.lit
            when (lit) {
                is Lit.Byte -> NumericKind.U8
                is Lit.Float ->
                    when (lit.value.suffix()) {
                        "f16" -> NumericKind.F16
                        "f32" -> NumericKind.F32
                        "f64" -> NumericKind.F64
                        "f128" -> NumericKind.F128
                        else -> null
                    }
                is Lit.Int -> {
                    when (lit.value.suffix()) {
                        "i8" -> NumericKind.I8
                        "u8" -> NumericKind.U8
                        "i16" -> NumericKind.I16
                        "u16" -> NumericKind.U16
                        "i32" -> NumericKind.I32
                        "u32" -> NumericKind.U32
                        "i64" -> NumericKind.I64
                        "u64" -> NumericKind.U64
                        "i128" -> NumericKind.I128
                        "u128" -> NumericKind.U128
                        "isize" -> resolvePointerSizedNumericKind(signed = true)
                        "usize" -> resolvePointerSizedNumericKind(signed = false)
                        "" -> {
                            val parsed = parseUnsigned128LiteralDigits(lit.value.token().toString())
                            if (parsed?.exceeds64BitUnsigned == true) NumericKind.U128 else null
                        }
                        else -> null
                    }
                }
                else -> null
            }
        }
        is Expr.Path -> {
            val constPrimKind = resolvePrimitiveAssociatedConstKind(expr.path, expr.qself)
            val segs = expr.path.segments.toList()
            val hasOnlyIgnoredPrefixes =
                expr.qself == null &&
                    segs.isNotEmpty() &&
                    segs.dropLast(1).all {
                        it.arguments is PathArguments.None &&
                            it.ident.toString().removePrefix("r#") in IGNORED_PATH_PREFIXES
                    }
            when {
                constPrimKind != null -> constPrimKind
                hasOnlyIgnoredPrefixes -> {
                    val raw =
                        segs
                            .last()
                            .ident
                            .toString()
                            .removePrefix("r#")
                    val ktConst = inspector.declaredConstNames[raw]
                    val constTy = ktConst?.let { inspector.declaredConstTypes[it] }
                    if (constTy != null) {
                        resolveNumericKind(renderType(constTy, selfTypeName))
                    } else {
                        val localKind =
                            if (segs.size == 1 && raw == "self") {
                                (currentTypedLocals["this"] ?: selfTypeName)?.let(this::resolveNumericKind)
                            } else if (segs.size == 1) {
                                val ktLocal = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
                                currentTypedLocals[ktLocal]?.let(this::resolveNumericKind)
                            } else {
                                null
                            }
                        if (localKind != null) {
                            localKind
                        } else {
                            val ktConstIdent = ktConst ?: KotlinNamingPolicy.Default.toConstIdentifier(raw)
                            scopedAbiKeysForRenderedType(ktConstIdent).firstNotNullOfOrNull {
                                numericKindFromScannerCode(headerContext.crateNumericTypeKinds[it])
                            } ?: resolveBestSubpackageForSymbol(ktConstIdent)?.let { sub ->
                                val key = if (sub.isEmpty()) ktConstIdent else "$sub.$ktConstIdent"
                                numericKindFromScannerCode(headerContext.crateNumericTypeKinds[key])
                            } ?: numericKindFromScannerCode(headerContext.crateNumericTypeKinds[ktConstIdent])
                        }
                    }
                }
                else -> null
            }
        }
        is Expr.Unary ->
            when (expr.op) {
                is UnOp.NotOp -> inferExprNumericKind(expr.expr, selfTypeName)?.takeIf { it.isIntegerNumericKind() }
                is UnOp.Neg -> inferExprNumericKind(expr.expr, selfTypeName)
                is UnOp.Deref -> inferExprRenderedType(expr, selfTypeName)?.let(this::resolveNumericKind) ?: inferExprNumericKind(expr.expr, selfTypeName)
            }
        is Expr.Binary -> {
            when (expr.op) {
                is BinOp.Eq, is BinOp.Ne, is BinOp.Lt, is BinOp.Le, is BinOp.Gt, is BinOp.Ge, is BinOp.And, is BinOp.Or -> null
                is BinOp.Shl, is BinOp.Shr -> inferExprNumericKind(expr.left, selfTypeName)
                else -> inferExprNumericKind(expr.left, selfTypeName) ?: inferExprNumericKind(expr.right, selfTypeName)
            }
        }
        else -> null
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
    when (code) {
        "I8" -> NumericKind.I8
        "U8" -> NumericKind.U8
        "I16" -> NumericKind.I16
        "U16" -> NumericKind.U16
        "I32" -> NumericKind.I32
        "U32" -> NumericKind.U32
        "I64" -> NumericKind.I64
        "U64" -> NumericKind.U64
        "I128" -> NumericKind.I128
        "U128" -> NumericKind.U128
        "F16" -> NumericKind.F16
        "F32" -> NumericKind.F32
        "F64" -> NumericKind.F64
        "F128" -> NumericKind.F128
        else -> null
    }

internal fun KotlinJ2KEmitter.borrowedPlaceLocalName(expr: Expr): String? {
    val path = unwrapParenOrGroupExpr(expr) as? Expr.Path ?: return null
    if (path.qself != null || path.path.segments.size != 1) return null
    val raw =
        path.path.segments
            .first()
            ?.ident
            ?.toString()
            ?.removePrefix("r#") ?: return null
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
    val owner = receiverType.removeSuffix("?").substringBefore('<').substringAfterLast('.')
    val fields = mutableReferenceProjection(owner, call.method.toString(), linkedSetOf()) ?: return null
    return call.receiver to fields
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
        fieldOwner = renderType(type, fieldOwner).substringBefore('<').substringAfterLast('.')
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
    if (liveField != null && declaredOutput is SynType.Reference && declaredOutput.mutability != null &&
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
        val fn =
            file.items
                .filterIsInstance<Item.Impl>()
                .filter {
                    it.traitPath == null && renderType(it.selfType, owner).substringBefore('<').substringAfterLast('.') == owner
                }.flatMap { it.items.filterIsInstance<ImplItem.Fn>() }
                .singleOrNull {
                    it.sig.ident.toString() == method && it.sig.inputs.size == 1 && it.sig.inputs.firstOrNull() is FnArg.Receiver
                } ?: return null
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
    val simple = base.substringBefore('<').substringAfterLast('.').trim()
    if (simple != "Ref" && simple != "RefMut") return null
    return base
        .substringAfter('<')
        .dropLast(1)
        .trim()
        .takeIf { it.isNotEmpty() }
}

internal fun isMapRenderedType(type: String?): Boolean {
    val clean = type?.removeSuffix("?")?.trim() ?: return false
    return clean.startsWith("MutableMap<") ||
        clean.startsWith("Map<") ||
        clean.startsWith("LinkedHashMap<") ||
        clean.startsWith("HashMap<")
}

internal fun isSetRenderedType(type: String?): Boolean {
    val clean = type?.removeSuffix("?")?.trim() ?: return false
    return clean.startsWith("MutableSet<") ||
        clean.startsWith("Set<") ||
        clean.startsWith("LinkedHashSet<") ||
        clean.startsWith("HashSet<")
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
        val candidates =
            file.items
                .filterIsInstance<Item.Impl>()
                .filter {
                    it.traitPath == null && renderType(it.selfType, owner).substringBefore('<').substringAfterLast('.') == owner
                }.flatMap { it.items.filterIsInstance<ImplItem.Fn>() }
                .filter {
                    it.sig.ident.toString() == method && it.sig.inputs.size == 1 &&
                        it.sig.inputs.firstOrNull() is FnArg.Receiver
                }
        val fn = candidates.singleOrNull() ?: return null
        val output = (fn.sig.output as? ReturnType.TypeReturn)?.ty ?: return null
        if (output !is SynType.Ptr && (output !is SynType.Reference || output.mutability == null)) return null
        var body = (fn.block.stmts.singleOrNull() as? Stmt.ExprStmt)?.expr ?: return null
        body = unwrapParenOrGroupExpr(body)
        if (body is Expr.Unsafe) body = (body.block.stmts.singleOrNull() as? Stmt.ExprStmt)?.expr ?: return null
        // Pointer-returning wrappers may forward another proven place
        // accessor (Cell::as_ptr -> UnsafeCell::get). The transparent-layout
        // proof is required for reinterpret casts, not for that forwarding.
        if (output is SynType.Ptr && body is Expr.Cast) {
            val struct =
                file.items.filterIsInstance<Item.Struct>().singleOrNull {
                    rustTypeIdentToKotlin(it.ident.toString()) == owner
                } ?: return null
            val transparent =
                struct.attrs.any { attr ->
                    val meta = attr.meta as? Meta.List
                    meta != null && meta.path.isIdent("repr") &&
                        meta.tokens.clone().toList().any {
                            it is TokenTree.Ident && it.value.toString() == "transparent"
                        }
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
            fieldOwner = renderType(type, fieldOwner).substringBefore('<').substringAfterLast('.')
        }
        return prefix + (mutableReferenceProjection(fieldOwner, forwarded.method.toString(), visiting) ?: return null)
    } finally {
        visiting.remove(key)
    }
}

internal fun unwrapMaybeUninitRenderedInner(rendered: String?): String? {
    val clean = rendered?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if ((clean.startsWith("MaybeUninit<") || ".MaybeUninit<" in clean) && clean.endsWith(">")) {
        return clean
            .substringAfter("MaybeUninit<")
            .removeSuffix(">")
            .trim()
            .takeIf { it.isNotEmpty() }
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
            val firstTy =
                ab.args
                    .toList()
                    .firstNotNullOfOrNull { renderGenericArgument(it, selfTypeName) }
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
            val renderedField = renderType(singleFieldSyn, selfTypeName)
            unwrapMaybeUninitRenderedInner(renderedField)?.let { return it }
        }
    }
    currentEnclosingReturnRenderedType?.trim()?.takeIf { it.isNotEmpty() && it != "Unit" }?.let { ret ->
        unwrapMaybeUninitRenderedInner(ret)?.let { return it }
        if (ownerBase.isNullOrEmpty() || ret.substringBefore('<').trim() != ownerBase) {
            return ret
        }
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
        val lastIdent =
            unwrapped.path.segments
                .toList()
                .lastOrNull()
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
                ?: break
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
            TranslationSession.sharedModuleFile?.items?.let { addAll(it) }
            if (TranslationSession.sharedModuleFile !== file) {
                addAll(file.items)
            }
        }
    val traitByName = linkedMapOf<String, Item.Trait>()
    traitByName.putAll(inspector.declaredTraitsByName)
    for (item in allFilesItems) {
        if (item is Item.Trait && !itemHasDocOnlyCfg(item)) {
            val tName = rustTypeIdentToKotlin(item.ident.toString())
            if (tName !in traitByName) {
                traitByName[tName] = item
            }
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
            item.traitPath
                ?.path
                ?.segments
                ?.toList()
                ?.lastOrNull()
                ?.ident
                ?.toString()
                ?.let(::rustTypeIdentToKotlin)
                ?.let { candidateImplTraits += it }
        }
    }
    val candidateTraits = linkedSetOf<String>()
    candidateTraits.addAll(candidateImplTraits)
    candidateTraits.addAll(currentTypeParamTraitBounds[ownerKt].orEmpty())
    if (canonicalOwner in traitByName) {
        candidateTraits += canonicalOwner
    }
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

internal fun KotlinJ2KEmitter.resolveCalleeExprParamSynType(
    calleeExpr: Expr,
    argIdx: Int,
    selfTypeName: String?,
): SynType? {
    val pathExpr = unwrapParenOrGroupExpr(calleeExpr) as? Expr.Path ?: return null
    if (pathExpr.qself != null) return null
    val segs = pathExpr.path.segments.toList()
    if (segs.isEmpty()) return null
    val methodKt =
        rustFunctionIdentToKotlin(
            segs
                .last()
                .ident
                .toString()
                .removePrefix("r#"),
        )
    if (segs.size == 1) {
        val rawCalleeIdent =
            segs[0]
                .ident
                .toString()
                .removePrefix("r#")
        val allFnItems =
            buildList {
                addAll(activeLocalBlockItems)
                TranslationSession.sharedModuleFile?.items?.let { addAll(it) }
                if (TranslationSession.sharedModuleFile !== file) {
                    addAll(file.items)
                }
            }
        fun matchesFnIdent(rawDeclIdent: String, ktCallee: String, isForeign: Boolean): Boolean {
            val cleanDecl = rawDeclIdent.removePrefix("r#")
            if (cleanDecl == rawCalleeIdent) return true
            if (rustFunctionIdentToKotlin(cleanDecl) == ktCallee) return true
            if (isForeign) {
                val renamed =
                    inspector.collidingTopLevelForeignFnRenames[cleanDecl]
                        ?: currentModPathPrefix.lastOrNull()?.let { modRaw ->
                            inspector.collidingInlineForeignFnRenames[modRaw to cleanDecl]
                        }
                if (renamed == ktCallee) return true
            }
            return false
        }
        for (item in allFnItems) {
            if (item is Item.Fn && !itemHasDocOnlyCfg(item) && matchesFnIdent(item.sig.ident.toString(), methodKt, isForeign = false)) {
                val typedParams =
                    item.sig.inputs
                        .toList()
                        .filterIsInstance<FnArg.Typed>()
                typedParams.getOrNull(argIdx)?.let { return it.patType.ty }
            }
            if (item is Item.ForeignMod && !itemHasDocOnlyCfg(item)) {
                for (foreign in item.items) {
                    if (foreign is ForeignItem.Fn &&
                        !foreignItemHasDocOnlyCfg(foreign) &&
                        matchesFnIdent(foreign.sig.ident.toString(), methodKt, isForeign = true)
                    ) {
                        val typedParams =
                            foreign.sig.inputs
                                .toList()
                                .filterIsInstance<FnArg.Typed>()
                        typedParams.getOrNull(argIdx)?.let { return it.patType.ty }
                    }
                }
            }
        }
        return null
    }
    val penultRaw = segs[segs.size - 2].ident.toString().removePrefix("r#")
    val ownerKt =
        when {
            penultRaw == "Self" -> selfTypeName?.substringBefore('<')?.trim()
            penultRaw.isNotEmpty() && penultRaw.first().isUpperCase() -> rustTypeIdentToKotlin(penultRaw)
            else -> null
        } ?: return null
    val (_, sig) = resolveMethodSignatureForOwner(ownerKt, methodKt) ?: return null
    val hasReceiver = sig.inputs.firstOrNull() is FnArg.Receiver
    val typedIdx = if (hasReceiver) argIdx - 1 else argIdx
    if (typedIdx < 0) return null
    val typedParams = sig.inputs.toList().filterIsInstance<FnArg.Typed>()
    return typedParams.getOrNull(typedIdx)?.patType?.ty
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
                val rawCtor =
                    fnPath
                        ?.segments
                        ?.toList()
                        ?.lastOrNull()
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                val ctorKt = rawCtor?.let(::rustTypeIdentToKotlin)
                if (ctorKt != null && rawCtor.firstOrNull()?.isUpperCase() == true) {
                    val localTy = localStructFields[ctorKt]?.getOrNull(argIdx)?.let { renderType(it, selfTypeName).removeSuffix("?").trim() }
                    localTy
                        ?: run {
                            val fieldKey = if (totalArgs == 1) "value" else "field$argIdx"
                            val synMap = inspector.structFieldSynTypesByType[ctorKt]
                            synMap?.get(fieldKey)?.let { renderType(it, selfTypeName).removeSuffix("?").trim() }
                                ?: scopedAbiKeysForRenderedType(ctorKt).firstNotNullOfOrNull { scoped ->
                                    headerContext.crateStructFieldTypes["$scoped.$fieldKey"]
                                }
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
        it.startsWith("List<") ||
            it.startsWith("MutableList<") ||
            it in PRIMITIVE_ARRAY_TYPES
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
                        if (range != null && range.start == null && range.end == null) {
                            unwrapParenOrGroupExpr(cur.expr)
                        } else {
                            return cur
                        }
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
                val structKt = rustTypeIdentToKotlin(i.ident.toString())
                val fields = i.fields
                if (fields is Fields.Unnamed) {
                    localTupleStructFields[structKt] =
                        fields.fields.unnamed
                            .toList()
                            .map { it.ty }
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
        val allFnItems =
            buildList {
                TranslationSession.sharedModuleFile?.items?.let { addAll(it) }
                if (TranslationSession.sharedModuleFile !== file) {
                    addAll(file.items)
                }
            }
        for (item in allFnItems) {
            if (item is Item.Fn && !itemHasDocOnlyCfg(item) && rustFunctionIdentToKotlin(item.sig.ident.toString()) == simpleFn) {
                val typedParams =
                    item.sig.inputs
                        .toList()
                        .filterIsInstance<FnArg.Typed>()
                val param = typedParams.getOrNull(argIdx) ?: continue
                if (isMutSliceSynType(param.patType.ty)) return true
            }
        }
        return false
    }
    resolveMethodSignatureForOwner(ownerPrefix, simpleFn)?.let { (_, sig) ->
        val hasReceiver = sig.inputs.firstOrNull() is FnArg.Receiver
        val typedIdx = if (hasReceiver) argIdx - 1 else argIdx
        if (typedIdx >= 0) {
            val param =
                sig.inputs
                    .toList()
                    .filterIsInstance<FnArg.Typed>()
                    .getOrNull(typedIdx)
            if (param != null && isMutSliceSynType(param.patType.ty)) return true
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
    for (item in file.items) {
        when (item) {
            is Item.Impl -> {
                val implOwner = renderType(item.selfType, null).substringBefore('<').substringAfterLast('.').trim()
                if (ownerSimple != null && implOwner != ownerSimple) continue
                for (implItem in item.items) {
                    if (implItem is ImplItem.Fn && rustFunctionIdentToKotlin(implItem.sig.ident.toString()) == methodKt) {
                        val hasReceiver = implItem.sig.inputs.firstOrNull() is FnArg.Receiver
                        if (includeStaticOnly && hasReceiver) continue
                        val typedParams =
                            implItem.sig.inputs
                                .toList()
                                .filterIsInstance<FnArg.Typed>()
                        val param = typedParams.getOrNull(argIdx) ?: continue
                        if (isMutSliceSynType(param.patType.ty)) return true
                    }
                }
            }
            is Item.Trait -> {
                val traitName = rustTypeIdentToKotlin(item.ident.toString())
                if (ownerSimple != null && traitName != ownerSimple) continue
                for (tItem in item.items) {
                    if (tItem is TraitItem.Fn && rustFunctionIdentToKotlin(tItem.sig.ident.toString()) == methodKt) {
                        val hasReceiver = tItem.sig.inputs.firstOrNull() is FnArg.Receiver
                        if (includeStaticOnly && hasReceiver) continue
                        val typedParams =
                            tItem.sig.inputs
                                .toList()
                                .filterIsInstance<FnArg.Typed>()
                        val param = typedParams.getOrNull(argIdx) ?: continue
                        if (isMutSliceSynType(param.patType.ty)) return true
                    }
                }
            }
            else -> {}
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.primitiveArrayTypeForElementRenderedType(elemTy: String?): String? =
    when (elemTy?.trim()) {
        "Boolean" -> "BooleanArray"
        "Byte" -> "ByteArray"
        "UByte" -> "UByteArray"
        "Short" -> "ShortArray"
        "UShort" -> "UShortArray"
        "Int" -> "IntArray"
        "UInt" -> "UIntArray"
        "Long" -> "LongArray"
        "ULong" -> "ULongArray"
        "Float", "kotlin.Float" -> "FloatArray"
        "Double" -> "DoubleArray"
        else -> null
    }

private fun extractCallableReturnRenderedType(renderedCallableType: String?): String? {
    val raw = renderedCallableType?.trim() ?: return null
    val unwrapped =
        if (raw.startsWith('(') && raw.endsWith(")?") && " -> " in raw) {
            raw.substring(1, raw.length - 2).trim()
        } else {
            raw
        }
    if (unwrapped.startsWith('(')) {
        var parenDepth = 0
        var angleDepth = 0
        for (idx in unwrapped.indices) {
            when (unwrapped[idx]) {
                '(' -> parenDepth++
                ')' -> {
                    parenDepth--
                    if (parenDepth == 0 && angleDepth == 0) {
                        val afterParen = unwrapped.substring(idx + 1).trimStart()
                        if (afterParen.startsWith("->")) {
                            return afterParen.removePrefix("->").trim().takeIf { it.isNotEmpty() }
                        }
                        return null
                    }
                }
                '<' -> angleDepth++
                '>' -> if (angleDepth > 0) angleDepth--
            }
        }
    }
    val base = unwrapped.substringBefore('<').substringAfterLast('.').trim()
    if (base in setOf("Fn", "FnMut", "FnOnce") && '<' in unwrapped && unwrapped.endsWith('>')) {
        val argsInner = unwrapped.substringAfter('<').dropLast(1)
        val comma = findTopLevelGenericCommaIndex(argsInner)
        if (comma >= 0) {
            return argsInner.substring(comma + 1).trim().takeIf { it.isNotEmpty() }
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.inferExplicitArrayElementRenderedType(
    elem: Expr,
    selfTypeName: String?,
): String? {
    val unwrapped = unwrapParenOrGroupExpr(elem)
    val lit = (unwrapped as? Expr.Lit)?.lit
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
    for (elem in sampleElems) {
        val elemTy = inferExplicitArrayElementRenderedType(elem, selfTypeName)
        primitiveArrayTypeForElementRenderedType(elemTy)?.let { return it }
    }
    return null
}

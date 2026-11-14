package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Fields
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.MetaParse
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.ReceiverKind
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.Variant
import io.github.kotlinmania.syn.Visibility
import io.github.kotlinmania.syn.WhereClause
import io.github.kotlinmania.syn.WherePredicate
import io.github.kotlinmania.syn.gen.Visit
import io.github.kotlinmania.syn.parse2
import io.github.kotlinmania.syn.span

private data class RustPanicExpectation(
    val message: String?,
)

private fun extractRustPanicExpectation(attrs: List<Attribute>): RustPanicExpectation? {
    val attribute = attrs.firstOrNull { it.path().isIdent("should_panic") } ?: return null
    val meta = attribute.meta
    if (meta is Meta.PathMeta) return RustPanicExpectation(null)
    val value =
        when (meta) {
            is Meta.NameValue -> meta.value
            is Meta.List -> {
                val trees = meta.tokens.clone().toList()
                val last = trees.lastOrNull()
                val arguments = if (last is TokenTree.Punct && last.value.asChar() == ',') trees.dropLast(1) else trees
                val expected = parse2(MetaParse::parse, TokenStream.fromTokenTrees(arguments)).getOrNull()
                if (expected is Meta.NameValue && expected.path.isIdent("expected")) {
                    expected.value
                } else {
                    return RustPanicExpectation(null)
                }
            }
            is Meta.PathMeta -> error("unreachable bare should_panic attribute")
        }
    val literal = (value as? Expr.Lit)?.lit as? Lit.Str ?: return RustPanicExpectation(null)
    return RustPanicExpectation(literal.value.value())
}

private fun wrapRustPanicTestBody(
    body: String,
    indent: String,
    expectation: RustPanicExpectation,
): String =
    buildString {
        append("{\n")
        val expected = expectation.message
        if (expected != null) {
            append(indent)
                .append("    val panic = kotlin.test.assertFails(block = fun() ")
                .append(body)
                .append(")\n")
            // An anonymous function preserves the original function's return semantics:
            // a translated `return` must not escape assertFails and pass the test.
            append(indent).append("    kotlin.test.assertTrue(panic.message?.contains(")
            append(renderKotlinDoubleQuotedString(expected)).append(") == true)\n")
        } else {
            append(indent)
                .append("    kotlin.test.assertFails(block = fun() ")
                .append(body)
                .append(")\n")
        }
        append(indent).append('}')
    }

internal fun KotlinJ2KEmitter.renderItemConst(
    item: Item.Const,
    indent: String,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean = false,
): String {
    val rawIdent = item.ident.toString()
    if (rawIdent == "_") {
        val expr = item.expr
        if (expr is Expr.BlockExpr) {
            val stmts = expr.block.stmts
            val rendered =
                stmts.mapNotNull { stmt ->
                    if (stmt is Stmt.ItemStmt) {
                        renderItem(stmt.item, indent, emptyMap(), isLocalItem = isLocalItem, scopeSignatures = scopeSignatures)
                            .takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
            return rendered.joinToString("\n\n")
        }
        return ""
    }
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val cleanRaw = rawIdent.removePrefix("r#")
    val baseName =
        if (!isLocalItem) {
            inspector.declaredConstNames[cleanRaw] ?: rustConstIdentToKotlin(rawIdent)
        } else {
            rustConstIdentToKotlin(rawIdent)
        }
    val name = disambiguateName(baseName, scopeSignatures)
    val ty = renderType(item.ty, selfTypeName = null)
    val numKind = resolveNumericKind(ty)
    val prevParseTarget = currentExpectedParseTargetType
    currentExpectedParseTargetType = ty
    val initExpr =
        try {
            renderTypedNumericInitializer(item.expr, numKind, indent, selfTypeName = null)
        } finally {
            currentExpectedParseTargetType = prevParseTarget
        }
    sb
        .append(indent)
        .append(vis)
        .append("val ")
        .append(name)
        .append(": ")
        .append(ty)
    if (headerContext.isBatchCrateOutput && initExpr.contains('\n')) {
        sb
            .append(" =\n")
            .append(indent)
            .append("    ")
            .append(initExpr.replace("\n", "\n    "))
    } else {
        sb.append(" = ").append(initExpr)
    }
    return sb.toString()
}

internal fun KotlinJ2KEmitter.renderItemStatic(
    item: Item.Static,
    indent: String,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean = false,
): String {
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val rawIdent = item.ident.toString()
    val cleanRaw = rawIdent.removePrefix("r#")
    val baseName =
        if (!isLocalItem) {
            inspector.declaredConstNames[cleanRaw] ?: rustConstIdentToKotlin(rawIdent)
        } else {
            rustConstIdentToKotlin(rawIdent)
        }
    val name = disambiguateName(baseName, scopeSignatures)
    val ty = renderType(item.ty, selfTypeName = null)
    val numKind = resolveNumericKind(ty)
    val prevParseTarget = currentExpectedParseTargetType
    currentExpectedParseTargetType = ty
    val expr =
        try {
            renderTypedNumericInitializer(item.expr, numKind, indent, selfTypeName = null)
        } finally {
            currentExpectedParseTargetType = prevParseTarget
        }
    sb
        .append(indent)
        .append(vis)
        .append("var ")
        .append(name)
        .append(": ")
        .append(ty)
    if (headerContext.isBatchCrateOutput && expr.contains('\n')) {
        sb
            .append(" =\n")
            .append(indent)
            .append("    ")
            .append(expr.replace("\n", "\n    "))
    } else {
        sb.append(" = ").append(expr)
    }
    return sb.toString()
}

internal fun KotlinJ2KEmitter.renderItemType(
    item: Item.ItemType,
    indent: String,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean = false,
): String {
    if (isLocalItem || isExternalIdentityTypeReexportAlias(item, headerContext.selfCrateAliases)) {
        return ""
    }
    val name = rustTypeIdentToKotlin(item.ident.toString())
    val sigKey = "typealias:$name"
    val seenCount = scopeSignatures[sigKey] ?: 0
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) {
        return ""
    }
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (headerContext.rawAbiMode || headerContext.finalClasses) "public " else renderVisibility(item.vis)
    val typeParams = renderTypeParameters(item.generics, selfTypeName = name)
    sourceStorageAlias(item)?.let { declaration ->
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.MutPtr")
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.TypeLayout")
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.allocateNativeCell")
        val field = requireNotNull(sourceTransparentField(declaration, declaration.ident.toString()))
        val fieldType = renderType(sourceStorageAliasField(item, declaration), name)
        val target = renderType(item.ty, name)
        sb.append("${indent}${vis}class $name$typeParams(fieldStorage: MutPtr<$fieldType>, fieldLayout: TypeLayout<$fieldType>) : $target(fieldStorage, fieldLayout) {\n")
        sb.append("$indent    public constructor(${field.first}: $fieldType, fieldLayout: TypeLayout<$fieldType>) : this(allocateNativeCell(${field.first}, fieldLayout), fieldLayout)\n")
        sb.append("$indent    public companion object\n")
        sb.append("$indent}")
        return sb.toString()
    }
    if (typeParams.isEmpty() && indent.isEmpty()) {
        val unwrappedTy = unwrapSynType(item.ty)
        if (unwrappedTy is SynType.FnPtr) {
            sb.append(renderBareFnSamInterface(vis, name, unwrappedTy, indent))
            return sb.toString()
        }
        val optionBareFn = extractOptionBareFn(unwrappedTy)
        if (optionBareFn != null) {
            val samName = "${name}Fn"
            if (samName !in inspector.declaredTypeNames && samName !in synthesizedSamDeclarations) {
                inspector.declaredTypeNames += samName
                sb.append(renderBareFnSamInterface(vis, samName, optionBareFn, indent)).append("\n\n")
            }
            sb
                .append(indent)
                .append(vis)
                .append("typealias ")
                .append(name)
                .append(" = ")
                .append(samName)
                .append('?')
            return sb.toString()
        }
    }
    val rawIdent = item.ident.toString().removePrefix("r#")
    val cPointerWidthRhs =
        if (typeParams.isEmpty()) {
            val singleSeg = extractSinglePrimitiveTargetIdent(item.ty)
            val isFfiAlias =
                headerContext.rawAbiMode ||
                    name in inspector.ffiReferencedTypeNames ||
                    rawIdent in inspector.ffiReferencedTypeNames
            when {
                singleSeg == "usize" &&
                    (
                        isFfiAlias ||
                            isCPointerWidthUnsignedTypeName(rawIdent) ||
                            scopedAbiKeysForRenderedType(name).any {
                                headerContext.crateNumericTypeKinds[it] in setOf("U64", "U32", "U16")
                            }
                    ) ->
                    when (activeCfgProfile.pointerWidth) {
                        64 -> "ULong"
                        16 -> "UShort"
                        else -> "UInt"
                    }
                singleSeg == "isize" &&
                    (
                        isFfiAlias ||
                            isCPointerWidthSignedTypeName(rawIdent) ||
                            scopedAbiKeysForRenderedType(name).any {
                                headerContext.crateNumericTypeKinds[it] in setOf("I64", "I32", "I16")
                            }
                    ) ->
                    when (activeCfgProfile.pointerWidth) {
                        64 -> "Long"
                        16 -> "Short"
                        else -> "Int"
                    }
                else -> null
            }
        } else {
            null
        }
    val rhs =
        if (cPointerWidthRhs != null) {
            cPointerWidthRhs
        } else {
            val prevHint = currentSamNameHint
            val prevExternAbi = inExternAbiSignature
            currentSamNameHint = "${name}Callback"
            val isFfiAlias =
                headerContext.rawAbiMode ||
                    name in inspector.ffiReferencedTypeNames ||
                    rawIdent in inspector.ffiReferencedTypeNames
            inExternAbiSignature = prevExternAbi || isFfiAlias
            try {
                renderType(item.ty, selfTypeName = name)
            } finally {
                currentSamNameHint = prevHint
                inExternAbiSignature = prevExternAbi
            }
        }
    if (rhs.isEmpty() || rhs == name || rhs == "$name$typeParams") {
        inspector.declaredTypeNames.remove(name)
        return ""
    }
    sb
        .append(indent)
        .append(vis)
        .append("typealias ")
        .append(name)
        .append(typeParams)
        .append(" = ")
        .append(rhs)
    return sb.toString()
}

internal fun KotlinJ2KEmitter.extractFnTraitSubstitutions(
    params: List<GenericParam>,
    whereClause: WhereClause?,
    selfTypeName: String?,
    intoSubstitutedOut: MutableSet<String>? = null,
): Map<String, String> {
    val fnTypeParams =
        params
            .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
            .toSet()
    if (fnTypeParams.isEmpty()) return emptyMap()
    val result = linkedMapOf<String, String>()
    val allBoundsByTp = linkedMapOf<String, MutableList<TypeParamBound.Trait>>()
    fun inspectBounds(tpName: String, bounds: List<TypeParamBound>) {
        for (b in bounds) {
            if (b is TypeParamBound.Trait && b.maybe == null) {
                val lastSeg = b.path.segments.last() ?: continue
                val rawName = lastSeg.ident.toString()
                if ((rawName == "Fn" || rawName == "FnMut" || rawName == "FnOnce") &&
                    lastSeg.arguments is PathArguments.Parenthesized
                ) {
                    result[tpName] = renderPathAsType(b.path, selfTypeName)
                } else if (rawName !in IGNORED_TRAIT_BOUNDS) {
                    allBoundsByTp.getOrPut(tpName) { mutableListOf() }.add(b)
                }
            }
        }
    }
    for (gp in params) {
        if (gp is GenericParam.TypeParam) {
            val tpName = rustTypeIdentToKotlin(gp.ident.toString())
            inspectBounds(tpName, gp.bounds.toList())
        }
    }
    for (pred in whereClause?.predicates?.toList().orEmpty()) {
        if (pred is WherePredicate.TypePredicate) {
            val tpName = renderType(pred.boundedTy, selfTypeName)
            if (tpName in fnTypeParams) {
                inspectBounds(tpName, pred.bounds.toList())
            }
        }
    }
    val convertibleTraits =
        buildSet {
            if ("Into" !in inspector.declaredTypeNames) add("Into")
            if ("AsRef" !in inspector.declaredTypeNames) add("AsRef")
            if ("Borrow" !in inspector.declaredTypeNames) add("Borrow")
        }
    if (convertibleTraits.isNotEmpty()) {
        for ((tpName, traitBounds) in allBoundsByTp) {
            if (tpName in result || traitBounds.size != 1) continue
            val sole = traitBounds[0]
            val lastSeg = sole.path.segments.last() ?: continue
            if (lastSeg.ident.toString() !in convertibleTraits) continue
            val typeArgs =
                (lastSeg.arguments as? PathArguments.AngleBracketed)
                    ?.args
                    ?.toList()
                    ?.filterIsInstance<GenericArgument.TypeArg>()
                    .orEmpty()
            if (typeArgs.size != 1) continue
            val targetRendered = renderType(typeArgs[0].type, selfTypeName).trim()
            if (targetRendered.isNotEmpty() && fnTypeParams.none { containsWholeIdentifier(targetRendered, it) }) {
                result[tpName] = targetRendered
                intoSubstitutedOut?.add(tpName)
            }
        }
    }
    return result
}

internal fun KotlinJ2KEmitter.renderItemFn(
    item: Item.Fn,
    indent: String,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean = false,
): String {
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val prevClosureLabel = currentClosureLabel
    val prevEnclosingFn = currentEnclosingFunctionName
    val prevEnclosingRet = currentEnclosingReturnRenderedType
    val prevEnclosingSynRet = currentEnclosingReturnSynType
    val prevEnclosingFmtResult = currentEnclosingReturnsFmtResult
    val prevTailExpectsUnitResultOk = currentTailExpectsUnitResultOk
    val prevMutableListLocals = currentMutableListLocals
    val prevDequeLocals = currentDequeLocals
    val prevIterableLocals = currentIterableLocals
    val prevSourceParameterTypes = currentSourceParameterTypes
    val prevTypedLocals = currentTypedLocals
    val prevWitnessedBounds = currentWitnessedTraitBounds
    val prevTypeParamBounds = currentTypeParamTraitBounds
    val prevNullableLocals = currentNullableLocals
    val prevSmartCastLocals = currentSmartCastNonNullLocals
    val prevTypeParams = activeTypeParamNames
    val prevSubst = substitutedTypeParamTypes
    val prevIntoSubstTps = currentIntoSubstitutedTypeParams
    val prevIntoSubstParams = currentIntoSubstitutedParams
    val prevWitnesses = currentTypeParamWitnessCalls
    val prevSharedBorrowRecv = currentReceiverIsSharedBorrow
    val prevMutBorrowRecv = currentReceiverIsMutBorrow
    currentClosureLabel = null
    currentEnclosingFunctionName = rustFunctionIdentToKotlin(item.sig.ident.toString())
    currentEnclosingReturnRenderedType = null
    currentEnclosingReturnSynType = null
    val firstReceiverRef =
        (
            item.sig.inputs
                .toList()
                .firstOrNull() as? FnArg.Receiver
        )?.kind as? ReceiverKind.Reference
    currentReceiverIsSharedBorrow = firstReceiverRef?.let { it.mutability == null } == true
    currentReceiverIsMutBorrow = firstReceiverRef?.let { it.mutability != null } == true
    currentMutableListLocals = emptySet()
    currentDequeLocals = emptySet()
    currentIterableLocals = emptySet()
    currentSourceParameterTypes = emptyMap()
    currentTypedLocals = emptyMap()
    currentWitnessedTraitBounds = emptyMap()
    currentTypeParamTraitBounds = emptyMap()
    currentNullableLocals = emptySet()
    currentSmartCastNonNullLocals = emptySet()
    currentIntoSubstitutedParams = emptySet()
    val fnGenerics = item.sig.generics
    val fnParamList = fnGenerics.params.toList()
    val intoSubstTps = linkedSetOf<String>()
    val fnSubsts = extractFnTraitSubstitutions(fnParamList, fnGenerics.whereClause, null, intoSubstTps)
    if (fnSubsts.isNotEmpty()) {
        substitutedTypeParamTypes = prevSubst + fnSubsts
    }
    currentIntoSubstitutedTypeParams = prevIntoSubstTps + intoSubstTps
    activeTypeParamNames = (activeTypeParamNames + collectGenericTypeParamNames(item.sig.generics)) - substitutedTypeParamTypes.keys
    scopeBuilder.enterDef()
    try {
        val sigHeader =
            renderFunctionSignature(
                sig = item.sig,
                visPrefix = vis,
                openPrefix = "",
                selfTypeName = null,
                ownerGenerics = null,
                scopeSignatures = scopeSignatures,
                isInterfaceMethod = false,
                fnBlock = item.block,
            ) ?: return ""
        val sb = StringBuilder()
        sb.append(renderLeadingTrivia(item, indent))
        sb.append(renderKDocFromAttributes(item.attrs, indent))
        if (!isLocalItem && hasTestAttribute(item.attrs)) {
            sb.append(indent).append("@kotlin.test.Test\n")
        }
        sb.append(indent).append(sigHeader)
        val fnBlock = item.block
        val retType = extractReturnSynType(item.sig.output)
        val panicExpectation = if (!isLocalItem && hasTestAttribute(item.attrs)) extractRustPanicExpectation(item.attrs) else null
        val bodyIndent = if (panicExpectation == null) indent else "$indent    "
        val shadows = computeReassignedParamShadows(item.sig, fnBlock, "$bodyIndent    ")
        val body =
            renderBlock(
                fnBlock,
                bodyIndent,
                selfTypeName = null,
                returnType = retType,
                leadingInjectedLines = shadows,
                allowExpressionBody = panicExpectation == null,
            )
        sb.append(" ")
        sb.append(if (panicExpectation == null) body else wrapRustPanicTestBody(body, indent, panicExpectation))
        return sb.toString()
    } finally {
        scopeBuilder.exitDef()
        currentClosureLabel = prevClosureLabel
        currentEnclosingFunctionName = prevEnclosingFn
        currentEnclosingReturnRenderedType = prevEnclosingRet
        currentEnclosingReturnSynType = prevEnclosingSynRet
        currentEnclosingReturnsFmtResult = prevEnclosingFmtResult
        currentTailExpectsUnitResultOk = prevTailExpectsUnitResultOk
        currentMutableListLocals = prevMutableListLocals
        currentDequeLocals = prevDequeLocals
        currentIterableLocals = prevIterableLocals
        currentSourceParameterTypes = prevSourceParameterTypes
        currentTypedLocals = prevTypedLocals
        currentWitnessedTraitBounds = prevWitnessedBounds
        currentTypeParamTraitBounds = prevTypeParamBounds
        currentNullableLocals = prevNullableLocals
        currentSmartCastNonNullLocals = prevSmartCastLocals
        activeTypeParamNames = prevTypeParams
        substitutedTypeParamTypes = prevSubst
        currentIntoSubstitutedTypeParams = prevIntoSubstTps
        currentIntoSubstitutedParams = prevIntoSubstParams
        currentTypeParamWitnessCalls = prevWitnesses
        currentReceiverIsSharedBorrow = prevSharedBorrowRecv
        currentReceiverIsMutBorrow = prevMutBorrowRecv
    }
}

internal fun KotlinJ2KEmitter.unwrapPaddingSynType(ty: SynType): SynType {
    val u = unwrapSynType(ty)
    if (u is SynType.Path && u.qself == null && "Padding" !in inspector.declaredTypeNames) {
        val segs = u.path.segments.toList()
        val last = segs.lastOrNull()
        if (last?.ident?.toString() == "Padding") {
            val angle = last.arguments as? PathArguments.AngleBracketed
            val inner =
                angle
                    ?.args
                    ?.toList()
                    ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
            if (inner != null) return unwrapPaddingSynType(inner)
        }
    }
    return u
}

internal fun KotlinJ2KEmitter.registerPtrDefaultImportIfNeeded(defaultExpr: String): String {
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    when {
        defaultExpr.startsWith("ConstPtr(") -> CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.ConstPtr")
        defaultExpr.startsWith("MutPtr(") -> CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.MutPtr")
    }
    return defaultExpr
}

internal fun KotlinJ2KEmitter.renderFallbackDefaultForRenderedType(renderedType: String): String {
    val clean = renderedType.trim()
    val simpleClean = clean.substringAfterLast('.')
    val aliasTarget = inspector.typeAliasTargets[simpleClean]?.let(this::unwrapPaddingSynType)
    if (aliasTarget is SynType.Array) {
        return renderStructFieldDefault(aliasTarget, renderType(aliasTarget, null), null)
    }
    if (!headerContext.rawAbiMode && aliasTarget is SynType.Ptr) {
        val ptrClass = if (aliasTarget.mutability is PointerMutability.Mut) "MutPtr" else "ConstPtr"
        return registerPtrDefaultImportIfNeeded("$ptrClass(0L)")
    }
    val scopedKeys = scopedAbiKeysForRenderedType(clean)
    if (isAlreadyNullableRenderedType(clean)) return "null"
    synthesizedSamDefaultsByName[clean]?.let { return it }
    for (key in scopedKeys) {
        headerContext.crateTypeAliasDefaults[key]?.let { def ->
            val resolved = if ('.' in clean && def == "$simpleClean()") "$clean()" else def
            return registerPtrDefaultImportIfNeeded(resolved)
        }
    }
    when (resolveNumericKind(clean)) {
        NumericKind.I8, NumericKind.I16, NumericKind.I32 -> return "0"
        NumericKind.I64 -> return "0L"
        NumericKind.I128 -> {
            usesI128 = true
            return "I128()"
        }
        NumericKind.U8, NumericKind.U16, NumericKind.U32 -> return "0u"
        NumericKind.U64 -> return "0uL"
        NumericKind.U128 -> {
            usesU128 = true
            return "U128()"
        }
        NumericKind.F32 -> return "0.0f"
        NumericKind.F64 -> return "0.0"
        null -> {}
    }
    if ((clean.startsWith("Pair<") || clean.startsWith("kotlin.Pair<")) && clean.endsWith(">")) {
        val inner = clean.substringAfter('<').removeSuffix(">")
        val commaIdx = findTopLevelGenericCommaIndex(inner)
        if (commaIdx > 0) {
            val firstTy = inner.substring(0, commaIdx).trim()
            val secondTy = inner.substring(commaIdx + 1).trim()
            return "$clean(${renderFallbackDefaultForRenderedType(firstTy)}, ${renderFallbackDefaultForRenderedType(secondTy)})"
        }
    }
    if ((clean.startsWith("Triple<") || clean.startsWith("kotlin.Triple<")) && clean.endsWith(">")) {
        val inner = clean.substringAfter('<').removeSuffix(">")
        val firstComma = findTopLevelGenericCommaIndex(inner)
        if (firstComma > 0) {
            val rest = inner.substring(firstComma + 1)
            val secondCommaRel = findTopLevelGenericCommaIndex(rest)
            if (secondCommaRel > 0) {
                val firstTy = inner.substring(0, firstComma).trim()
                val secondTy = rest.substring(0, secondCommaRel).trim()
                val thirdTy = rest.substring(secondCommaRel + 1).trim()
                return "$clean(${renderFallbackDefaultForRenderedType(firstTy)}, ${renderFallbackDefaultForRenderedType(secondTy)}, ${renderFallbackDefaultForRenderedType(thirdTy)})"
            }
        }
    }
    if ((clean.startsWith("MaybeUninit<") || ".MaybeUninit<" in clean) && clean.endsWith(">")) {
        val innerTy = clean.substringAfter("MaybeUninit<").removeSuffix(">").trim()
        if (innerTy.isNotEmpty()) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.maybeuninit.MaybeUninit")
            val layoutExpr = resolveRenderedTypeLayoutExpr(innerTy)
            return "MaybeUninit.uninit($layoutExpr)"
        }
    }
    return registerPtrDefaultImportIfNeeded(defaultInitializerForType(clean))
}

internal fun findTopLevelGenericCommaIndex(text: String): Int {
    var depth = 0
    for (i in text.indices) {
        when (text[i]) {
            '<' -> depth++
            '>' -> depth--
            ',' -> if (depth == 0) return i
        }
    }
    return -1
}

internal fun KotlinJ2KEmitter.normalizeArrayLengthToIntExpr(
    rawLen: String,
    lenExpr: Expr? = null,
    selfTypeName: String? = null,
): String {
    val trimmed = rawLen.trim()
    val unsignedStripped =
        when {
            trimmed.endsWith("uL") || trimmed.endsWith("UL") -> trimmed.dropLast(2)
            trimmed.endsWith("u") || trimmed.endsWith("U") || trimmed.endsWith("L") -> trimmed.dropLast(1)
            else -> null
        }
    if (unsignedStripped != null &&
        unsignedStripped.isNotEmpty() &&
        (
            unsignedStripped.all { it.isDigit() || it == '_' } ||
                (
                    (unsignedStripped.startsWith("0x") || unsignedStripped.startsWith("0X")) &&
                        unsignedStripped.drop(2).isNotEmpty() &&
                        unsignedStripped.drop(2).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '_' }
                )
        )
    ) {
        return unsignedStripped
    }
    if (trimmed.isNotEmpty() &&
        (
            trimmed.all { it.isDigit() || it == '_' } ||
                (
                    (trimmed.startsWith("0x") || trimmed.startsWith("0X")) &&
                        trimmed.drop(2).isNotEmpty() &&
                        trimmed.drop(2).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '_' }
                )
        )
    ) {
        return trimmed
    }
    if (trimmed.endsWith(".toInt()") ||
        isAlreadyRenderedAsNumericKind(trimmed, NumericKind.I32) ||
        (lenExpr != null && inferExprNumericKind(lenExpr, selfTypeName) == NumericKind.I32)
    ) {
        return trimmed
    }
    return "($trimmed).toInt()"
}

internal fun KotlinJ2KEmitter.isPrimitiveArrayRenderedType(renderedType: String): Boolean {
    val clean = renderedType.trim().removeSuffix("?")
    if (clean in PRIMITIVE_ARRAY_TYPES) return true
    val simpleClean = clean.substringAfterLast('.')
    if (simpleClean in PRIMITIVE_ARRAY_TYPES) return true
    val aliasTarget = inspector.typeAliasTargets[simpleClean]?.let(this::unwrapPaddingSynType)
    if (aliasTarget != null) {
        val mapped = renderType(aliasTarget, null).trim().removeSuffix("?")
        return mapped in PRIMITIVE_ARRAY_TYPES
    }
    if ('.' !in clean && simpleClean in inspector.declaredTypeNames) return false
    for (key in scopedAbiKeysForRenderedType(clean)) {
        val def = headerContext.crateTypeAliasDefaults[key]?.trim() ?: continue
        return PRIMITIVE_ARRAY_TYPES.any { def.startsWith("$it(") }
    }
    return false
}

internal fun KotlinJ2KEmitter.renderStructFieldDefault(
    fieldSynTy: SynType,
    renderedType: String,
    selfTypeName: String?,
): String {
    val cleanType = renderedType.trim()
    val unwrapped = unwrapPaddingSynType(fieldSynTy)
    if (unwrapped is SynType.Array) {
        val rawLen = renderTypedNumericInitializer(unwrapped.len, NumericKind.I32, "", selfTypeName)
        val lenInt = normalizeArrayLengthToIntExpr(rawLen, unwrapped.len, selfTypeName)
        if (cleanType in PRIMITIVE_ARRAY_TYPES) {
            return "$cleanType($lenInt)"
        }
        if (cleanType.startsWith("List<") && cleanType.endsWith(">")) {
            val elemTy = cleanType.removePrefix("List<").removeSuffix(">").trim()
            val unwrappedElem = unwrapSynType(unwrapped.elem)
            if (unwrappedElem is SynType.FnPtr ||
                extractOptionBareFn(unwrappedElem) != null ||
                (elemTy.endsWith("Fn") && isOptionFnTypeAliasRenderedType(elemTy.removeSuffix("Fn")))
            ) {
                return "emptyList()"
            }
            return "List($lenInt) { ${renderFallbackDefaultForRenderedType(elemTy)} }"
        }
    }
    return renderFallbackDefaultForRenderedType(cleanType)
}

internal fun KotlinJ2KEmitter.renderStructFieldDefaultOrNull(
    fieldSynTy: SynType,
    renderedType: String,
    selfTypeName: String?,
): String? {
    val cleanType = renderedType.trim()
    if (cleanType.isEmpty()) return null
    if (isAlreadyNullableRenderedType(cleanType)) return "null"
    if (cleanType.contains("->")) return null
    if (cleanType in activeTypeParamNames) return null
    if ('.' in cleanType) {
        val prefix = cleanType.substringBefore('.').trim()
        if (prefix in activeTypeParamNames || prefix == "Self") return null
    }
    val unwrapped = unwrapPaddingSynType(fieldSynTy)
    if (unwrapped is SynType.Array && cleanType.startsWith("List<") && cleanType.endsWith(">")) {
        val elemTy = cleanType.removePrefix("List<").removeSuffix(">").trim()
        val unwrappedElem = unwrapSynType(unwrapped.elem)
        val isFnPtrArray =
            unwrappedElem is SynType.FnPtr ||
                extractOptionBareFn(unwrappedElem) != null ||
                (elemTy.endsWith("Fn") && isOptionFnTypeAliasRenderedType(elemTy.removeSuffix("Fn")))
        if (!isFnPtrArray && renderStructFieldDefaultOrNull(unwrapped.elem, elemTy, selfTypeName) == null) {
            return null
        }
    }
    val rawBase = cleanType.substringBefore('<').trim().substringAfterLast('.')
    val selfBase = selfTypeName?.substringBefore('<')?.trim()?.substringAfterLast('.')
    if (rawBase.isEmpty() || rawBase == "Self" || rawBase == selfBase) return null
    if (rawBase in inspector.declaredTraitsByName ||
        rawBase in setOf("Iterator", "Iterable", "Sequence", "CharSequence", "Comparable", "AutoCloseable")
    ) {
        return null
    }
    return renderStructFieldDefault(fieldSynTy, renderedType, selfTypeName)
}

internal fun KotlinJ2KEmitter.buildArrayAwareDataClassMethods(
    typeName: String,
    fields: List<Pair<String, String>>,
    memberIndent: String,
): List<String> {
    val eqLines = mutableListOf<String>()
    eqLines += "${memberIndent}override fun equals(other: Any?): Boolean {"
    eqLines += "$memberIndent    if (this === other) return true"
    eqLines += "$memberIndent    if (other !is $typeName) return false"
    for ((fName, fType) in fields) {
        if (isPrimitiveArrayRenderedType(fType)) {
            eqLines += "$memberIndent    if (!this.$fName.contentEquals(other.$fName)) return false"
        } else {
            eqLines += "$memberIndent    if (this.$fName != other.$fName) return false"
        }
    }
    eqLines += "$memberIndent    return true"
    eqLines += "$memberIndent}"

    val hashLines = mutableListOf<String>()
    if (fields.isEmpty()) {
        hashLines += "${memberIndent}override fun hashCode(): Int = 0"
    } else if (fields.size == 1) {
        val (firstName, firstType) = fields[0]
        val firstHash = if (isPrimitiveArrayRenderedType(firstType)) "this.$firstName.contentHashCode()" else "this.$firstName.hashCode()"
        hashLines += "${memberIndent}override fun hashCode(): Int = $firstHash"
    } else {
        hashLines += "${memberIndent}override fun hashCode(): Int {"
        val (firstName, firstType) = fields[0]
        val firstHash = if (isPrimitiveArrayRenderedType(firstType)) "this.$firstName.contentHashCode()" else "this.$firstName.hashCode()"
        hashLines += "$memberIndent    var result = $firstHash"
        for (i in 1 until fields.size) {
            val (fName, fType) = fields[i]
            val fHash = if (isPrimitiveArrayRenderedType(fType)) "this.$fName.contentHashCode()" else "this.$fName.hashCode()"
            hashLines += "$memberIndent    result = 31 * result + $fHash"
        }
        hashLines += "$memberIndent    return result"
        hashLines += "$memberIndent}"
    }
    return listOf(eqLines.joinToString("\n"), hashLines.joinToString("\n"))
}

internal data class SwiftSafeFieldSpec(
    val name: String,
    val renderedType: String,
    val vis: String,
    val defaultExpr: String?,
    val leadingHeader: String,
    val docPrefix: String,
    val isConcreteGenericForSwift: Boolean,
)

internal fun hasEmittedKotlinTraitBound(bounds: List<TypeParamBound>): Boolean =
    bounds.any { b ->
        b is TypeParamBound.Trait &&
            b.maybe == null &&
            b.path.segments
                .last()
                ?.ident
                ?.toString() !in IGNORED_TRAIT_BOUNDS
    }

internal fun collectUnboundedGenericTypeParamNames(generics: Generics): Set<String> {
    val boundedInWhere = hashSetOf<String>()
    generics.whereClause?.predicates?.toList()?.forEach { pred ->
        if (pred is WherePredicate.TypePredicate && hasEmittedKotlinTraitBound(pred.bounds.toList())) {
            val ty = pred.boundedTy
            if (ty is SynType.Path && ty.qself == null && ty.path.segments.size == 1) {
                ty.path.segments
                    .first()
                    ?.ident
                    ?.toString()
                    ?.let { boundedInWhere += rustTypeIdentToKotlin(it) }
            }
        }
    }
    return generics.params
        .toList()
        .mapNotNull { gp ->
            if (gp is GenericParam.TypeParam && !hasEmittedKotlinTraitBound(gp.bounds.toList())) {
                val ktName = rustTypeIdentToKotlin(gp.ident.toString())
                if (ktName !in boundedInWhere) ktName else null
            } else {
                null
            }
        }.toSet()
}

internal fun KotlinJ2KEmitter.isConcreteGenericTypeForSwiftExport(
    renderedType: String,
    unboundedOwnerTypeParams: Set<String>,
    visitedAliases: Set<String> = emptySet(),
): Boolean {
    val base = renderedType.trim().removeSuffix("?").trim()
    if (base.isEmpty()) return false
    if (scopedAbiKeysForRenderedType(base).any { it in headerContext.cratePointerTypeAliases }) {
        return true
    }
    if (scopedAbiKeysForRenderedType(base).any { key ->
            val def = headerContext.crateTypeAliasDefaults[key]?.trim().orEmpty()
            def.startsWith("MutPtr(") ||
                def.startsWith("ConstPtr(") ||
                def.startsWith("nullMut(") ||
                def.startsWith("nullConst(")
        }
    ) {
        return true
    }
    if ('<' !in base) {
        if (base !in visitedAliases) {
            val localAlias =
                originalFile.items
                    .toList()
                    .filterIsInstance<Item.ItemType>()
                    .firstOrNull {
                        it.generics.params
                            .toList()
                            .isEmpty() && rustTypeIdentToKotlin(it.ident.toString()) == base
                    }
            if (localAlias != null) {
                val expanded = renderType(localAlias.ty, selfTypeName = null)
                return isConcreteGenericTypeForSwiftExport(
                    expanded,
                    unboundedOwnerTypeParams,
                    visitedAliases + base,
                )
            }
        }
        return false
    }
    val stdlibCollectionNames =
        setOf(
            "List",
            "Set",
            "Map",
            "MutableList",
            "MutableSet",
            "MutableMap",
            "Collection",
            "MutableCollection",
            "Iterable",
            "MutableIterable",
            "ArrayDeque",
            "Pair",
            "Triple",
        )
    var i = 0
    val n = base.length
    while (i < n) {
        val ch = base[i]
        if (ch == '<' && (i == 0 || base[i - 1] != '-')) {
            var identStart = i
            while (identStart > 0) {
                val c = base[identStart - 1]
                if (c.isLetterOrDigit() || c == '.') {
                    identStart--
                } else {
                    break
                }
            }
            val rawIdent = base.substring(identStart, i).substringAfterLast('.')
            var depth = 1
            var j = i + 1
            val topArgs = mutableListOf<String>()
            var argStart = j
            while (j < n && depth > 0) {
                val cj = base[j]
                if (cj == '<' && (j == 0 || base[j - 1] != '-')) {
                    depth++
                } else if (cj == '>' && (j == 0 || base[j - 1] != '-')) {
                    depth--
                    if (depth == 0) {
                        topArgs += base.substring(argStart, j).trim()
                        break
                    }
                } else if (cj == ',' && depth == 1) {
                    topArgs += base.substring(argStart, j).trim()
                    argStart = j + 1
                }
                j++
            }
            if (depth == 0) {
                if (rawIdent.isNotEmpty() && rawIdent !in stdlibCollectionNames) {
                    for (rawArg in topArgs) {
                        val cleanArg =
                            rawArg
                                .removePrefix("out ")
                                .removePrefix("in ")
                                .trim()
                                .removeSuffix("?")
                                .trim()
                        if (cleanArg.isNotEmpty() &&
                            cleanArg != "*" &&
                            cleanArg != "Any" &&
                            cleanArg != "kotlin.Any" &&
                            cleanArg !in unboundedOwnerTypeParams
                        ) {
                            return true
                        }
                    }
                }
                for (rawArg in topArgs) {
                    if (isConcreteGenericTypeForSwiftExport(rawArg, unboundedOwnerTypeParams, visitedAliases)) {
                        return true
                    }
                }
                i = j + 1
                continue
            }
        }
        i++
    }
    return false
}

internal fun attachedImplDefinesInstanceMethod(
    attachedImpls: List<Item.Impl>,
    methodName: String,
): Boolean {
    for (impl in attachedImpls) {
        for (sub in impl.items) {
            if (sub is ImplItem.Fn) {
                val firstArg =
                    sub.sig.inputs
                        .toList()
                        .firstOrNull()
                if (firstArg is FnArg.Receiver && rustFunctionIdentToKotlin(sub.sig.ident.toString()) == methodName) {
                    return true
                }
            }
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.buildSwiftSafeSyntheticDataMethods(
    typeName: String,
    typeParamCount: Int,
    selfTypeWithGenerics: String,
    specs: List<SwiftSafeFieldSpec>,
    includeCopyAndComponents: Boolean,
    hasCopyCollision: Boolean,
    memberIndent: String,
): List<String> {
    val result = mutableListOf<String>()
    if (includeCopyAndComponents) {
        for ((idx, spec) in specs.withIndex()) {
            result += "${memberIndent}${spec.vis}operator fun component${idx + 1}(): ${spec.renderedType} = this.${spec.name}"
        }
        if (!hasCopyCollision && specs.isNotEmpty() && specs.all { it.vis == "public " }) {
            val copyParams = specs.joinToString(", ") { "${it.name}: ${it.renderedType} = this.${it.name}" }
            val copyArgs =
                specs.joinToString(", ") {
                    val paramKey = if (it.vis == "public " && it.isConcreteGenericForSwift) "${it.name}CtorArg" else it.name
                    "$paramKey = ${it.name}"
                }
            result += "${memberIndent}public fun copy($copyParams): $selfTypeWithGenerics = $selfTypeWithGenerics($copyArgs)"
        }
    }
    val starSuffix = if (typeParamCount > 0) "<" + List(typeParamCount) { "*" }.joinToString(", ") + ">" else ""
    val eqLines = mutableListOf<String>()
    eqLines += "${memberIndent}override fun equals(other: Any?): Boolean {"
    eqLines += "$memberIndent    if (this === other) return true"
    eqLines += "$memberIndent    if (other !is $typeName$starSuffix) return false"
    for (spec in specs) {
        if (isPrimitiveArrayRenderedType(spec.renderedType)) {
            eqLines += "$memberIndent    if (!this.${spec.name}.contentEquals(other.${spec.name})) return false"
        } else {
            eqLines += "$memberIndent    if (this.${spec.name} != other.${spec.name}) return false"
        }
    }
    eqLines += "$memberIndent    return true"
    eqLines += "$memberIndent}"
    result += eqLines.joinToString("\n")

    val hashLines = mutableListOf<String>()
    if (specs.isEmpty()) {
        hashLines += "${memberIndent}override fun hashCode(): Int = 0"
    } else if (specs.size == 1) {
        val first = specs[0]
        val firstHash = if (isPrimitiveArrayRenderedType(first.renderedType)) "this.${first.name}.contentHashCode()" else "this.${first.name}.hashCode()"
        hashLines += "${memberIndent}override fun hashCode(): Int = $firstHash"
    } else {
        hashLines += "${memberIndent}override fun hashCode(): Int {"
        val first = specs[0]
        val firstHash = if (isPrimitiveArrayRenderedType(first.renderedType)) "this.${first.name}.contentHashCode()" else "this.${first.name}.hashCode()"
        hashLines += "$memberIndent    var result = $firstHash"
        for (i in 1 until specs.size) {
            val spec = specs[i]
            val fHash = if (isPrimitiveArrayRenderedType(spec.renderedType)) "this.${spec.name}.contentHashCode()" else "this.${spec.name}.hashCode()"
            hashLines += "$memberIndent    result = 31 * result + $fHash"
        }
        hashLines += "$memberIndent    return result"
        hashLines += "$memberIndent}"
    }
    result += hashLines.joinToString("\n")
    return result
}

internal fun renderSwiftSafeCompanionInvoke(
    typeParams: String,
    selfTypeWithGenerics: String,
    specs: List<SwiftSafeFieldSpec>,
    memberIndent: String,
): String {
    val fnTypeParams = if (typeParams.isNotEmpty()) "$typeParams " else ""
    val invokeParams =
        specs.joinToString(",\n") { spec ->
            val defPart = spec.defaultExpr?.let { " = $it" }.orEmpty()
            "$memberIndent        ${spec.name}: ${spec.renderedType}$defPart"
        }
    val ctorArgs =
        specs.joinToString(",\n") { spec ->
            val paramKey = if (spec.vis == "public " && spec.isConcreteGenericForSwift) "${spec.name}CtorArg" else spec.name
            "$memberIndent        $paramKey = ${spec.name}"
        }
    return buildString {
        append(memberIndent).append("    public operator fun ").append(fnTypeParams).append("invoke(\n")
        append(invokeParams).append(",\n")
        append(memberIndent)
            .append("    ): ")
            .append(selfTypeWithGenerics)
            .append(" = ")
            .append(selfTypeWithGenerics)
            .append("(\n")
        append(ctorArgs).append(",\n")
        append(memberIndent).append("    )")
    }
}

internal fun KotlinJ2KEmitter.renderItemStruct(
    item: Item.Struct,
    attachedImpls: List<Item.Impl>,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val typeName = rustTypeIdentToKotlin(item.ident.toString())
    val sigKey = "type:$typeName"
    val seenCount = scopeSignatures[sigKey] ?: 0
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) {
        return ""
    }
    val useFinalAbiLayout = headerContext.rawAbiMode || headerContext.finalClasses
    val typeParamCount =
        item.generics.params
            .toList()
            .count { it is GenericParam.TypeParam }
    val effectiveAttachedImpls =
        if (useFinalAbiLayout && typeParamCount == 0) {
            attachedImpls.filterNot { impl ->
                impl.traitPath
                    ?.path
                    ?.segments
                    ?.last()
                    ?.ident
                    ?.toString() == "Default"
            }
        } else {
            attachedImpls
        }
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val selfTypeWithGenerics = typeName + renderTypeArgumentsFromGenerics(item.generics)
    val unboundedOwnerTypeParams = collectUnboundedGenericTypeParamNames(item.generics)
    val prevStructTypeParams = activeTypeParamNames
    activeTypeParamNames = activeTypeParamNames + collectGenericTypeParamNames(item.generics)
    val typeParams: String
    val whereClause: String
    val superTraits = mutableListOf<String>()
    val extraIterableMembers = mutableListOf<String>()
    val ctorParams = mutableListOf<String>()
    val bodyFieldProps = mutableListOf<String>()
    val fieldNameAndTypes = mutableListOf<Pair<String, String>>()
    val fieldSpecs = mutableListOf<SwiftSafeFieldSpec>()
    try {
        typeParams = renderTypeParameters(item.generics, selfTypeName = selfTypeWithGenerics)
        whereClause =
            renderWhereClause(
                item.generics.whereClause,
                selfTypeName = selfTypeWithGenerics,
                ownerGenerics = item.generics,
            )
        superTraits.addAll(renderDistinctSuperTraits(effectiveAttachedImpls, selfTypeWithGenerics))
        val iterElemTy = extractDirectIteratorItemRenderedType(effectiveAttachedImpls, "Iterator", selfTypeWithGenerics, requireOwnedSelf = false)
        val intoIterElemTy =
            if (iterElemTy == null) {
                extractDirectIteratorItemRenderedType(effectiveAttachedImpls, "IntoIterator", selfTypeWithGenerics, requireOwnedSelf = true)
            } else {
                null
            }
        val iterableElem = iterElemTy ?: intoIterElemTy
        if (iterableElem != null && superTraits.none { it.substringBefore('<').trim() == "Iterable" }) {
            superTraits += "Iterable<$iterableElem>"
            val mIndent = "$indent    "
            val kotlinIteratorTy = stdlibIteratorTypeName()
            if (iterElemTy != null) {
                val iterBody =
                    buildString {
                        append(mIndent)
                        append("override fun iterator(): ")
                        append(kotlinIteratorTy)
                        append("<")
                        append(iterElemTy)
                        append("> = object : AbstractIterator<")
                        append(iterElemTy)
                        append(">() {\n")
                        append(mIndent).append("    override fun computeNext() {\n")
                        append(mIndent).append("        when (val v = this@").append(typeName).append(".next()) {\n")
                        append(mIndent).append("            null -> done()\n")
                        append(mIndent).append("            else -> setNext(v)\n")
                        append(mIndent).append("        }\n")
                        append(mIndent).append("    }\n")
                        append(mIndent).append("}")
                    }
                extraIterableMembers += iterBody
            } else if (intoIterElemTy != null) {
                extraIterableMembers +=
                    "${mIndent}override fun iterator(): $kotlinIteratorTy<$intoIterElemTy> = this.intoIter().iterator()"
            }
        }
        when (val fields = item.fields) {
            is Fields.Named -> {
                val fieldList = fields.fields.named.toList()
                val seenFieldNames = hashSetOf<String>()
                for ((idx, field) in fieldList.withIndex()) {
                    if (isDocOnlyCfgAttribute(field.attrs)) continue
                    val rawFieldIdent = field.ident?.toString()?.removePrefix("r#") ?: "field$idx"
                    val fName = rustFieldIdentToKotlin(field.ident?.toString() ?: "field$idx")
                    if (!seenFieldNames.add(fName)) continue
                    val fieldTrivia = renderLeadingTrivia(field, "$indent    ")
                    val fieldDoc = renderKDocFromAttributes(field.attrs, "$indent    ")
                    val fVis = renderVisibility(field.vis)
                    val unwrappedFieldTy = unwrapPaddingSynType(field.ty)
                    if (useFinalAbiLayout && unwrappedFieldTy is SynType.Tuple && unwrappedFieldTy.elems.len() >= 2) {
                        val combinedFieldHeader = fieldTrivia + fieldDoc
                        val docPrefix = if (combinedFieldHeader.isNotEmpty()) "\n$combinedFieldHeader$indent    " else ""
                        for ((tIdx, elemTy) in unwrappedFieldTy.elems.toList().withIndex()) {
                            val subName = "$fName$tIdx"
                            if (!seenFieldNames.add(subName)) continue
                            val subType = renderType(elemTy, selfTypeName = selfTypeWithGenerics)
                            val subDefExpr = renderStructFieldDefaultOrNull(elemTy, subType, selfTypeWithGenerics)
                            val subDefault = subDefExpr?.let { " = $it" }.orEmpty()
                            val curDocPrefix = if (tIdx == 0) docPrefix else ""
                            val curHeader = if (tIdx == 0) combinedFieldHeader else ""
                            val isConcreteGen = !isLocalItem && vis == "public " && isConcreteGenericTypeForSwiftExport(subType, unboundedOwnerTypeParams)
                            val setterLine = if (fVis == "public " && isConcreteGen) "\n$indent        internal set" else ""
                            ctorParams += "$curDocPrefix${fVis}var $subName: $subType$subDefault"
                            bodyFieldProps += "$curHeader$indent    ${fVis}var $subName: $subType$subDefault$setterLine"
                            fieldNameAndTypes += subName to subType
                            fieldSpecs += SwiftSafeFieldSpec(subName, subType, fVis, subDefExpr, curHeader, curDocPrefix, isConcreteGen)
                        }
                        continue
                    }
                    val prevHint = currentSamNameHint
                    currentSamNameHint = "${typeName}${fName.replaceFirstChar { it.uppercase() }}Callback"
                    var fType =
                        try {
                            renderType(field.ty, selfTypeName = selfTypeWithGenerics)
                        } finally {
                            currentSamNameHint = prevHint
                        }
                    if (field.vis !is Visibility.Public &&
                        fType.startsWith("List<") &&
                        structImplsMutateField(effectiveAttachedImpls, rawFieldIdent)
                    ) {
                        fType = "Mutable$fType"
                    }
                    val combinedFieldHeader = fieldTrivia + fieldDoc
                    val docPrefix = if (combinedFieldHeader.isNotEmpty()) "\n$combinedFieldHeader$indent    " else ""
                    val defExpr = if (useFinalAbiLayout) renderStructFieldDefaultOrNull(field.ty, fType, selfTypeWithGenerics) else null
                    val defaultSuffix = defExpr?.let { " = $it" }.orEmpty()
                    val isConcreteGen = !isLocalItem && vis == "public " && isConcreteGenericTypeForSwiftExport(fType, unboundedOwnerTypeParams)
                    val setterLine = if (fVis == "public " && isConcreteGen) "\n$indent        internal set" else ""
                    ctorParams += "${docPrefix}${fVis}var $fName: $fType$defaultSuffix"
                    bodyFieldProps += "${combinedFieldHeader}$indent    ${fVis}var $fName: $fType$defaultSuffix$setterLine"
                    fieldNameAndTypes += fName to fType
                    fieldSpecs += SwiftSafeFieldSpec(fName, fType, fVis, defExpr, combinedFieldHeader, docPrefix, isConcreteGen)
                }
            }
            is Fields.Unnamed -> {
                val fieldList =
                    fields.fields.unnamed
                        .toList()
                        .filterNot { isDocOnlyCfgAttribute(it.attrs) }
                for ((idx, field) in fieldList.withIndex()) {
                    val fVis = renderVisibility(field.vis)
                    val propName = tupleFieldPropertyName(idx, fieldList.size)
                    val prevHint = currentSamNameHint
                    currentSamNameHint = "${typeName}Field${idx}Callback"
                    val fType =
                        try {
                            renderType(field.ty, selfTypeName = selfTypeWithGenerics)
                        } finally {
                            currentSamNameHint = prevHint
                        }
                    val defExpr = if (useFinalAbiLayout) renderStructFieldDefaultOrNull(field.ty, fType, selfTypeWithGenerics) else null
                    val defaultSuffix = defExpr?.let { " = $it" }.orEmpty()
                    val isConcreteGen = !isLocalItem && vis == "public " && isConcreteGenericTypeForSwiftExport(fType, unboundedOwnerTypeParams)
                    val setterLine = if (fVis == "public " && isConcreteGen) "\n$indent        internal set" else ""
                    ctorParams += "${fVis}var $propName: $fType$defaultSuffix"
                    bodyFieldProps += "$indent    ${fVis}var $propName: $fType$defaultSuffix$setterLine"
                    fieldNameAndTypes += propName to fType
                    fieldSpecs += SwiftSafeFieldSpec(propName, fType, fVis, defExpr, "", "", isConcreteGen)
                }
            }
            is Fields.Unit -> {}
        }
    } finally {
        activeTypeParamNames = prevStructTypeParams
    }

    // A repr(transparent) wrapper does not own a detached Kotlin field. Its
    // constructor receives the field's Rust layout explicitly, and every view
    // projects the same allocation. Generic erasure is never a layout proof.
    val liveField = sourceTransparentField(item, selfTypeWithGenerics)
    val extraCompanionMembers = mutableListOf<String>()
    var forceInternalPrimaryCtor = false
    val swiftSafeBodyProps = mutableListOf<String>()
    val useBodyPropertiesForLargeStruct =
        liveField == null &&
            useFinalAbiLayout &&
            ctorParams.size > 64 &&
            fieldSpecs.all { it.defaultExpr != null }
    if (liveField != null) {
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.MutPtr")
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.TypeLayout")
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.TransparentTypeLayout")
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.allocateNativeCell")
        val (fieldName, fieldType) = liveField
        val transparentConcreteForSwift =
            !isLocalItem && vis == "public " && fieldType.trim() !in unboundedOwnerTypeParams
        val fieldIsConcreteGeneric =
            !isLocalItem && vis == "public " && isConcreteGenericTypeForSwiftExport(fieldType, unboundedOwnerTypeParams)
        ctorParams.clear()
        ctorParams += "public val fieldStorage: MutPtr<$fieldType>"
        ctorParams += "public val fieldLayout: TypeLayout<$fieldType>"
        bodyFieldProps.clear()
        val setterPrefix = if (fieldIsConcreteGeneric) "internal set" else "set"
        extraIterableMembers += "$indent    public var $fieldName: $fieldType\n" +
            "$indent        get() = fieldLayout.readNative(fieldStorage.rawAddress)\n" +
            "$indent        $setterPrefix(value) { fieldLayout.writeNative(fieldStorage.rawAddress, value) }"
        val alignedFieldLayout =
            sourceStorageAlignment(item)?.let {
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.AlignedTypeLayout")
                "AlignedTypeLayout(fieldLayout, ${it}L)"
            } ?: "fieldLayout"
        val secCtorVis = if (transparentConcreteForSwift) "internal" else "public"
        extraIterableMembers += "$indent    $secCtorVis constructor($fieldName: $fieldType, fieldLayout: TypeLayout<$fieldType>) : " +
            "this(allocateNativeCell($fieldName, $alignedFieldLayout), $alignedFieldLayout)"
        extraIterableMembers += "$indent    public fun pointerLayout(): TypeLayout<$selfTypeWithGenerics> = " +
            "TransparentTypeLayout(fieldLayout, " +
            "{ ptr, layout -> $selfTypeWithGenerics(ptr, layout) }, { it.$fieldName })"
        if (transparentConcreteForSwift) {
            forceInternalPrimaryCtor = true
            val fnTypeParams = if (typeParams.isNotEmpty()) "$typeParams " else ""
            extraCompanionMembers +=
                "$indent        public operator fun ${fnTypeParams}invoke($fieldName: $fieldType, fieldLayout: TypeLayout<$fieldType>): $selfTypeWithGenerics = " +
                "$selfTypeWithGenerics($fieldName, fieldLayout)"
            extraCompanionMembers +=
                "$indent        public fun ${fnTypeParams}fromFieldStorage(fieldStorage: MutPtr<$fieldType>, fieldLayout: TypeLayout<$fieldType>): $selfTypeWithGenerics = " +
                "$selfTypeWithGenerics(fieldStorage, fieldLayout)"
        }
    } else if (!headerContext.rawAbiMode && !isLocalItem) {
        val computedStructLayout = StructLayoutComputer.computeStructLayout(item, this)
        if (computedStructLayout != null) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            extraCompanionMembers +=
                StructLayoutComputer.renderStructTypeLayoutMethod(
                    layout = computedStructLayout,
                    genericTypeParamsRendered = typeParams,
                    selfTypeWithGenerics = selfTypeWithGenerics,
                    coreNamespace = coreNs,
                    indent = "$indent    ",
                    emittedFieldTypesByName = fieldNameAndTypes.toMap(),
                    useBodyPropertiesCtor = useBodyPropertiesForLargeStruct,
                    emitter = this,
                )
        }
    }

    val needsSwiftSafeStructCtor =
        liveField == null &&
            !useBodyPropertiesForLargeStruct &&
            !isLocalItem &&
            vis == "public " &&
            fieldSpecs.any { it.vis == "public " && it.isConcreteGenericForSwift }
    val wouldHaveBeenDataClass =
        liveField == null && useFinalAbiLayout &&
            ctorParams.size in 1..16 &&
            superTraits.isEmpty() &&
            extraIterableMembers.isEmpty() &&
            typeParams.isEmpty()

    if (needsSwiftSafeStructCtor) {
        forceInternalPrimaryCtor = true
        ctorParams.clear()
        for (spec in fieldSpecs) {
            if (spec.vis == "public " && spec.isConcreteGenericForSwift) {
                ctorParams += "${spec.name}CtorArg: ${spec.renderedType}"
                swiftSafeBodyProps +=
                    "${spec.leadingHeader}$indent    public var ${spec.name}: ${spec.renderedType} = ${spec.name}CtorArg\n$indent        internal set"
            } else {
                ctorParams += "${spec.docPrefix}${spec.vis}var ${spec.name}: ${spec.renderedType}"
            }
        }
        if (fieldSpecs.isNotEmpty() && fieldSpecs.all { it.defaultExpr != null }) {
            val defaultCtorArgs =
                fieldSpecs.joinToString(",\n") { spec ->
                    val paramKey = if (spec.vis == "public " && spec.isConcreteGenericForSwift) "${spec.name}CtorArg" else spec.name
                    "$indent        $paramKey = ${spec.defaultExpr}"
                }
            extraIterableMembers += "$indent    public constructor() : this(\n$defaultCtorArgs,\n$indent    )"
        }
        extraCompanionMembers += renderSwiftSafeCompanionInvoke(typeParams, selfTypeWithGenerics, fieldSpecs, "$indent    ")
        if (wouldHaveBeenDataClass) {
            extraIterableMembers.addAll(
                buildSwiftSafeSyntheticDataMethods(
                    typeName = typeName,
                    typeParamCount = typeParamCount,
                    selfTypeWithGenerics = selfTypeWithGenerics,
                    specs = fieldSpecs,
                    includeCopyAndComponents = true,
                    hasCopyCollision = attachedImplDefinesInstanceMethod(effectiveAttachedImpls, "copy"),
                    memberIndent = "$indent    ",
                ),
            )
        }
    }

    val isDataClass = wouldHaveBeenDataClass && !needsSwiftSafeStructCtor
    if (isDataClass && fieldNameAndTypes.any { isPrimitiveArrayRenderedType(it.second) }) {
        extraIterableMembers.addAll(buildArrayAwareDataClassMethods(typeName, fieldNameAndTypes, "$indent    "))
    }
    if (!isDataClass &&
        !useBodyPropertiesForLargeStruct &&
        !attachedImplDefinesInstanceMethod(effectiveAttachedImpls, "clone") &&
        item.attrs.any { attribute ->
            val derive = attribute.meta as? Meta.List
            derive != null &&
                derive.path.segments
                    .last()
                    ?.ident
                    .toString() in setOf("derive", "derive_const") &&
                derive.tokens
                    .clone()
                    .toList()
                    .any { it is TokenTree.Ident && it.value.toString() == "Clone" }
        }
    ) {
        val cloneOpenMod = if (useFinalAbiLayout) "" else "open "
        if (liveField != null) {
            extraIterableMembers +=
                "$indent    public ${cloneOpenMod}fun clone(): $selfTypeWithGenerics = $selfTypeWithGenerics(${liveField.first}, fieldLayout)"
        } else {
            val cloneArgs = fieldNameAndTypes.joinToString(", ") { it.first }
            extraIterableMembers +=
                "$indent    public ${cloneOpenMod}fun clone(): $selfTypeWithGenerics = $typeName($cloneArgs)"
        }
    }
    val classKeyword =
        when {
            isDataClass -> "data class "
            useFinalAbiLayout -> "class "
            else -> "open class "
        }

    sb
        .append(indent)
        .append(vis)
        .append(classKeyword)
        .append(typeName)
        .append(typeParams)
    if (ctorParams.isNotEmpty() && !useBodyPropertiesForLargeStruct) {
        val ctorHeaderPrefix = if (forceInternalPrimaryCtor) " internal constructor" else ""
        if (headerContext.isBatchCrateOutput || ctorParams.any { it.contains('\n') } || ctorParams.size > 2) {
            sb.append(ctorHeaderPrefix).append("(\n")
            for (p in ctorParams) {
                if (p.startsWith('\n')) {
                    sb.append(p.removePrefix("\n")).append(",\n")
                } else {
                    sb
                        .append(indent)
                        .append("    ")
                        .append(p)
                        .append(",\n")
                }
            }
            sb.append(indent).append(")")
        } else {
            sb
                .append(ctorHeaderPrefix)
                .append("(")
                .append(ctorParams.joinToString(", "))
                .append(")")
        }
    }
    if (superTraits.isNotEmpty()) {
        sb.append(" : ").append(superTraits.joinToString(", "))
    }
    sb.append(whereClause)

    val extraProps =
        when {
            useBodyPropertiesForLargeStruct -> bodyFieldProps + buildTupleStructAliasProperties(item.fields, selfTypeWithGenerics, "$indent    ")
            swiftSafeBodyProps.isNotEmpty() -> swiftSafeBodyProps + buildTupleStructAliasProperties(item.fields, selfTypeWithGenerics, "$indent    ")
            else -> buildTupleStructAliasProperties(item.fields, selfTypeWithGenerics, "$indent    ")
        }
    val prevOverrideNames = currentStructOverrideMethodNames
    currentStructOverrideMethodNames = collectLocalSupertraitMethodNames(superTraits)
    val memberLines =
        try {
            renderAttachedImplMembers(
                typeName = selfTypeWithGenerics,
                ownerGenerics = item.generics,
                attachedImpls = effectiveAttachedImpls,
                indent = "$indent    ",
                extraProperties = extraProps,
                extraMembers = extraIterableMembers,
                extraCompanionMembers = extraCompanionMembers,
                isLocalClass = isLocalItem,
                structFieldNames = fieldNameAndTypes.mapTo(linkedSetOf()) { it.first },
            )
        } finally {
            currentStructOverrideMethodNames = prevOverrideNames
        }
    if (memberLines.isNotEmpty()) {
        sb.append(" {\n")
        sb.append(memberLines)
        if (!memberLines.endsWith('\n')) sb.append('\n')
        sb.append(indent).append("}")
    }
    return sb.toString()
}

internal fun KotlinJ2KEmitter.extractDirectIteratorItemRenderedType(
    attachedImpls: List<Item.Impl>,
    traitRawName: String,
    selfTypeWithGenerics: String,
    requireOwnedSelf: Boolean,
): String? {
    for (impl in attachedImpls) {
        if (isDocOnlyCfgAttribute(impl.attrs)) continue
        if (requireOwnedSelf && unwrapSynType(impl.selfType) is SynType.Reference) continue
        val rawTrait =
            impl.traitPath
                ?.path
                ?.segments
                ?.last()
                ?.ident
                ?.toString() ?: continue
        if (rawTrait != traitRawName) continue
        for (sub in impl.items) {
            if (sub is ImplItem.AssocType && sub.ident.toString() == "Item") {
                return renderType(sub.ty, selfTypeWithGenerics)
            }
        }
    }
    return null
}

internal data class IteratorSupertraitSpec(
    val itemType: String,
    val hasDoubleEnded: Boolean,
    val hasExactSize: Boolean,
)

internal fun KotlinJ2KEmitter.extractIteratorSupertraitSpec(
    bounds: List<TypeParamBound>,
    selfTypeName: String?,
): IteratorSupertraitSpec? {
    var sawIteratorFamily = false
    var hasDoubleEnded = false
    var hasExactSize = false
    var itemRendered: String? = null
    for (b in bounds) {
        if (b !is TypeParamBound.Trait || b.maybe != null) continue
        val lastSeg = b.path.segments.last() ?: continue
        val rawName = lastSeg.ident.toString()
        if (rawName == "Iterator" || rawName == "DoubleEndedIterator" || rawName == "ExactSizeIterator") {
            sawIteratorFamily = true
            if (rawName == "DoubleEndedIterator") hasDoubleEnded = true
            if (rawName == "ExactSizeIterator") hasExactSize = true
            val angle = lastSeg.arguments as? PathArguments.AngleBracketed
            if (angle != null && itemRendered == null) {
                for (ga in angle.args.toList()) {
                    if (ga is GenericArgument.AssocTypeArg && ga.assoc.ident.toString() == "Item") {
                        itemRendered = renderType(ga.assoc.ty, selfTypeName)
                    }
                }
            }
        }
    }
    if (!sawIteratorFamily || itemRendered == null) return null
    return IteratorSupertraitSpec(
        itemType = itemRendered,
        hasDoubleEnded = hasDoubleEnded,
        hasExactSize = hasExactSize,
    )
}

internal fun KotlinJ2KEmitter.collectLocalSupertraitMethodNames(
    superTraits: List<String>,
    includeReceiverlessMethods: Boolean = false,
): Set<String> {
    val names = linkedSetOf<String>()
    val visitedTraits = hashSetOf<String>()
    fun visitTraitByBaseName(baseName: String) {
        if (!visitedTraits.add(baseName)) return
        val traitDecl = lookupDeclaredTraitAst(baseName) ?: return
        for (tItem in traitDecl.items) {
            if (traitItemHasDocOnlyCfg(tItem)) continue
            if (tItem is TraitItem.Fn &&
                (includeReceiverlessMethods || tItem.sig.inputs.firstOrNull() is FnArg.Receiver)
            ) {
                names += rustFunctionIdentToKotlin(tItem.sig.ident.toString())
            }
        }
        val iterSpec = extractIteratorSupertraitSpec(traitDecl.supertraits.toList(), null)
        if (iterSpec != null) {
            names += "next"
            if (iterSpec.hasDoubleEnded) names += "nextBack"
            if (iterSpec.hasExactSize) names += "len"
        }
        for (bound in traitDecl.supertraits) {
            val boundPath = (bound as? TypeParamBound.Trait)?.path ?: continue
            val boundBase =
                boundPath.segments
                    .last()
                    ?.ident
                    ?.toString()
                    ?.let(::rustTypeIdentToKotlin) ?: continue
            visitTraitByBaseName(boundBase)
        }
    }
    for (st in superTraits) {
        visitTraitByBaseName(st.substringBefore('<').trim())
    }
    return names
}

private fun KotlinJ2KEmitter.collectLocalTraitAndSupertraitFnRawNames(traitDecl: Item.Trait): Set<String> {
    val rawNames = linkedSetOf<String>()
    val visited = hashSetOf<String>()
    fun visitDecl(decl: Item.Trait) {
        val ktName = rustTypeIdentToKotlin(decl.ident.toString())
        if (!visited.add(ktName)) return
        for (tItem in decl.items) {
            if (traitItemHasDocOnlyCfg(tItem)) continue
            if (tItem is TraitItem.Fn) {
                rawNames +=
                    tItem.sig.ident
                        .toString()
                        .removePrefix("r#")
            }
        }
        for (bound in decl.supertraits) {
            val boundPath = (bound as? TypeParamBound.Trait)?.path ?: continue
            val boundKt =
                boundPath.segments
                    .last()
                    ?.ident
                    ?.toString()
                    ?.let(::rustTypeIdentToKotlin) ?: continue
            lookupDeclaredTraitAst(boundKt, boundPath)?.let(::visitDecl)
        }
    }
    visitDecl(traitDecl)
    return rawNames
}

internal fun KotlinJ2KEmitter.renderDistinctSuperTraits(
    attachedImpls: List<Item.Impl>,
    selfTypeWithGenerics: String,
): List<String> {
    val seenInterfaces = hashSetOf<String>()
    val result = mutableListOf<String>()
    for (impl in attachedImpls) {
        if (isDocOnlyCfgAttribute(impl.attrs)) continue
        val path = impl.traitPath?.path ?: continue
        val rawTrait =
            path.segments
                .last()
                ?.ident
                ?.toString() ?: continue
        if (rawTrait in IGNORED_TRAIT_BOUNDS) continue
        if (isImplicitStdlibSupertraitPath(path)) continue
        val prevBounded = currentBoundedTypeName
        currentBoundedTypeName = selfTypeWithGenerics
        val rendered =
            try {
                renderPathAsType(path, selfTypeName = selfTypeWithGenerics)
            } finally {
                currentBoundedTypeName = prevBounded
            }
        val selfBase = selfTypeWithGenerics.substringBefore('<').trim()
        val baseIface = rendered.substringBefore('<').trim()
        if (baseIface.isNotEmpty() && baseIface != selfBase && !rendered.contains("->") && seenInterfaces.add(baseIface)) {
            val assocNames = lookupTraitDefaultlessAssocTypes(baseIface, path)
            val fullRendered =
                if (assocNames.isNotEmpty()) {
                    val implAssocByName =
                        impl.items.filterIsInstance<ImplItem.AssocType>().associate {
                            rustTypeIdentToKotlin(it.ident.toString()) to renderType(it.ty, selfTypeWithGenerics)
                        }
                    val assocArgs = assocNames.map { implAssocByName[it] ?: "Any" }
                    if (rendered.endsWith(">")) {
                        "${rendered.dropLast(1)}, ${assocArgs.joinToString(", ")}>"
                    } else {
                        "$rendered<${assocArgs.joinToString(", ")}>"
                    }
                } else {
                    rendered
                }
            result += fullRendered
        }
    }
    return result
}

internal fun KotlinJ2KEmitter.structImplsMutateField(attachedImpls: List<Item.Impl>, rawFieldIdent: String): Boolean {
    var mutated = false
    val visitor =
        object : Visit() {
            override fun visitExprMethodCall(e: Expr.MethodCall) {
                val m = e.method.toString()
                if (m in MUTATING_VEC_METHODS) {
                    val recv = e.receiver
                    if (recv is Expr.Field &&
                        recv.base is Expr.Path &&
                        (recv.base as Expr.Path).path.isIdent("self") &&
                        recv.member is Member.Named &&
                        (recv.member as Member.Named).ident.toString().removePrefix("r#") == rawFieldIdent
                    ) {
                        mutated = true
                        return
                    }
                }
                super.visitExprMethodCall(e)
            }
        }
    for (impl in attachedImpls) {
        visitor.visitItemImpl(impl)
        if (mutated) return true
    }
    return false
}

internal fun KotlinJ2KEmitter.buildTupleStructAliasProperties(
    fields: Fields,
    selfTypeName: String,
    memberIndent: String,
): List<String> {
    if (fields !is Fields.Unnamed) return emptyList()
    val list = fields.fields.unnamed.toList()
    if (list.size != 1) return emptyList()
    val fType = renderType(list[0].ty, selfTypeName)
    return listOf(
        "${memberIndent}public var field0: $fType\n" +
            "$memberIndent    get() = value\n" +
            "$memberIndent    set(v) {\n" +
            "$memberIndent        value = v\n" +
            "$memberIndent    }",
    )
}

internal fun KotlinJ2KEmitter.renderAttachedImplMembers(
    typeName: String,
    ownerGenerics: Generics,
    attachedImpls: List<Item.Impl>,
    indent: String,
    extraProperties: List<String> = emptyList(),
    extraMembers: List<String> = emptyList(),
    extraCompanionMembers: List<String> = emptyList(),
    isLocalClass: Boolean = false,
    structFieldNames: Set<String> = emptySet(),
    isObjectOwner: Boolean = false,
): String {
    val prevObjectOwner = currentAttachedImplIsObjectOwner
    currentAttachedImplIsObjectOwner = isObjectOwner
    try {
        val instanceMembers = mutableListOf<String>()
        val companionMembers = mutableListOf<String>()
        val instanceSignatures = linkedMapOf<String, Int>()
        val companionSignatures = linkedMapOf<String, Int>()

        if (headerContext.rawAbiMode || headerContext.finalClasses) {
            for (fName in structFieldNames) {
                instanceSignatures["$fName()"] = 1
            }
        }

        instanceMembers.addAll(extraProperties)
        instanceMembers.addAll(extraMembers)
        if (!isLocalClass && !isObjectOwner) {
            companionMembers.addAll(extraCompanionMembers)
        } else if (isObjectOwner && extraCompanionMembers.isNotEmpty()) {
            instanceMembers.addAll(extraCompanionMembers)
        }
        if (extraMembers.any { it.contains("fun hashCode(): Int") }) {
            instanceSignatures["hashCode()"] = 1
        }
        if (extraMembers.any { it.contains("fun equals(other: Any?): Boolean") }) {
            instanceSignatures["equals(Any?)"] = 1
        }

        val ownerFallbackAssocTypes =
            buildMap<String, SynType> {
                for (impl in attachedImpls) {
                    if (isDocOnlyCfgAttribute(impl.attrs)) continue
                    for (implItem in impl.items) {
                        if (implItem is ImplItem.AssocType) {
                            val key = implItem.ident.toString()
                            if (key !in this) {
                                put(key, implItem.ty)
                            }
                        }
                    }
                }
            }

        for (impl in attachedImpls) {
            if (isDocOnlyCfgAttribute(impl.attrs)) continue
            val implComments =
                (renderLeadingTrivia(impl, indent) + renderKDocFromAttributes(impl.attrs, indent))
                    .trimEnd()
            val instStart = instanceMembers.size
            val compStart = companionMembers.size
            val prevAssocTypes = currentImplAssocTypes
            val prevAttachedTraitPath = currentAttachedImplTraitPath
            val prevAttachedIsRefSelf = currentAttachedImplIsRefSelf
            val prevSubst = substitutedTypeParamTypes
            currentAttachedImplTraitPath = impl.traitPath?.path
            currentAttachedImplIsRefSelf = unwrapSynType(impl.selfType) is SynType.Reference
            val selfPathParam =
                (unwrapSynType(impl.selfType) as? SynType.Path)
                    ?.takeIf { it.qself == null && it.path.segments.len() == 1 }
                    ?.path
                    ?.segments
                    ?.first()
                    ?.ident
                    ?.toString()
                    ?.let(::rustTypeIdentToKotlin)
            val ownerBaseName = typeName.substringBefore('<').trim()
            if (selfPathParam != null &&
                selfPathParam != ownerBaseName &&
                selfPathParam in collectGenericTypeParamNames(impl.generics)
            ) {
                substitutedTypeParamTypes = prevSubst + (selfPathParam to typeName)
            }
            currentImplAssocTypes =
                buildMap {
                    putAll(prevAssocTypes)
                    putAll(ownerFallbackAssocTypes)
                    for (implItem in impl.items) {
                        if (implItem is ImplItem.AssocType) {
                            put(implItem.ident.toString(), implItem.ty)
                        }
                    }
                }
            try {
                for (implItem in impl.items) {
                    if (implItemHasDocOnlyCfg(implItem)) continue
                    when (implItem) {
                        is ImplItem.Const -> {
                            val routeToInstance = isLocalClass || isObjectOwner
                            val constIndent = if (routeToInstance) indent else "$indent    "
                            val targetList = if (routeToInstance) instanceMembers else companionMembers
                            val targetSigs = if (routeToInstance) instanceSignatures else companionSignatures
                            targetList += renderImplItemConst(implItem, constIndent, typeName, targetSigs)
                        }
                        is ImplItem.Fn -> {
                            val hasReceiver = implItem.sig.inputs.firstOrNull() is FnArg.Receiver
                            if (!hasReceiver && !isLocalClass && !isObjectOwner) {
                                val rendered =
                                    renderImplItemFn(
                                        item = implItem,
                                        indent = "$indent    ",
                                        selfTypeName = typeName,
                                        ownerGenerics = ownerGenerics,
                                        implGenerics = impl.generics,
                                        scopeSignatures = companionSignatures,
                                        isCompanionMember = true,
                                    )
                                if (rendered.isNotBlank()) companionMembers += rendered
                            } else {
                                val rendered =
                                    renderImplItemFn(
                                        item = implItem,
                                        indent = indent,
                                        selfTypeName = typeName,
                                        ownerGenerics = ownerGenerics,
                                        implGenerics = impl.generics,
                                        scopeSignatures = instanceSignatures,
                                    )
                                if (rendered.isNotBlank()) instanceMembers += rendered
                            }
                        }
                        is ImplItem.AssocType -> {}
                        is ImplItem.Macro -> {
                            instanceMembers +=
                                renderImplOrTraitMacroMembers(
                                    mac = implItem.mac,
                                    indent = indent,
                                    selfTypeName = typeName,
                                    ownerGenerics = ownerGenerics,
                                    scopeSignatures = instanceSignatures,
                                    asTopLevelExtension = false,
                                    isInterface = false,
                                )
                        }
                        is ImplItem.Verbatim -> {}
                    }
                }
            } finally {
                currentImplAssocTypes = prevAssocTypes
                currentAttachedImplTraitPath = prevAttachedTraitPath
                currentAttachedImplIsRefSelf = prevAttachedIsRefSelf
                substitutedTypeParamTypes = prevSubst
            }
            if (implComments.isNotBlank()) {
                when {
                    instanceMembers.size > instStart -> {
                        instanceMembers[instStart] = "$implComments\n${instanceMembers[instStart]}"
                    }
                    companionMembers.size > compStart -> {
                        val reindented =
                            implComments.lines().joinToString("\n") { line ->
                                if (line.isBlank()) line else "    $line"
                            }
                        companionMembers[compStart] = "$reindented\n${companionMembers[compStart]}"
                    }
                    else -> {
                        instanceMembers += implComments
                    }
                }
            }
        }

        if (companionMembers.isNotEmpty()) {
            val compSb = StringBuilder()
            compSb.append(indent).append("public companion object {\n")
            compSb.append(companionMembers.joinToString("\n\n"))
            compSb.append('\n').append(indent).append("}")
            instanceMembers += compSb.toString()
        }

        return instanceMembers.filter { it.isNotBlank() }.joinToString("\n\n")
    } finally {
        currentAttachedImplIsObjectOwner = prevObjectOwner
    }
}

internal fun KotlinJ2KEmitter.renderItemUnion(
    item: Item.Union,
    attachedImpls: List<Item.Impl>,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val typeName = rustTypeIdentToKotlin(item.ident.toString())
    val sigKey = "type:$typeName"
    val seenCount = scopeSignatures[sigKey] ?: 0
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) {
        return ""
    }
    val useFinalAbiLayout = headerContext.rawAbiMode || headerContext.finalClasses
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val selfTypeWithGenerics = typeName + renderTypeArgumentsFromGenerics(item.generics)
    val unboundedOwnerTypeParams = collectUnboundedGenericTypeParamNames(item.generics)
    val typeParamCount =
        item.generics.params
            .toList()
            .count { it is GenericParam.TypeParam }
    val typeParams = renderTypeParameters(item.generics, selfTypeName = selfTypeWithGenerics)
    val seenFieldNames = hashSetOf<String>()
    val fieldNameAndTypes = mutableListOf<Pair<String, String>>()
    val bodyFieldProps = mutableListOf<String>()
    val unionSpecs = mutableListOf<SwiftSafeFieldSpec>()
    val ctorParams =
        item.fields.named
            .toList()
            .mapIndexedNotNull { idx, field ->
                if (isDocOnlyCfgAttribute(field.attrs)) return@mapIndexedNotNull null
                val fName = rustFieldIdentToKotlin(field.ident?.toString() ?: "field$idx")
                if (!seenFieldNames.add(fName)) return@mapIndexedNotNull null
                val fieldTrivia = renderLeadingTrivia(field, "$indent    ")
                val fieldDoc = renderKDocFromAttributes(field.attrs, "$indent    ")
                val fVis = renderVisibility(field.vis)
                val prevHint = currentSamNameHint
                currentSamNameHint = "${typeName}${fName.replaceFirstChar { it.uppercase() }}Callback"
                val fType =
                    try {
                        renderType(field.ty, selfTypeName = selfTypeWithGenerics)
                    } finally {
                        currentSamNameHint = prevHint
                    }
                val combinedFieldHeader = fieldTrivia + fieldDoc
                val docPrefix = if (combinedFieldHeader.isNotEmpty()) "\n$combinedFieldHeader$indent    " else ""
                if (useFinalAbiLayout) {
                    val defVal = renderStructFieldDefault(field.ty, fType, selfTypeWithGenerics)
                    val isConcreteGen = !isLocalItem && vis == "public " && isConcreteGenericTypeForSwiftExport(fType, unboundedOwnerTypeParams)
                    val setterLine = if (fVis == "public " && isConcreteGen) "\n$indent        internal set" else ""
                    fieldNameAndTypes += fName to fType
                    bodyFieldProps += "${combinedFieldHeader}$indent    ${fVis}var $fName: $fType = $defVal$setterLine"
                    unionSpecs += SwiftSafeFieldSpec(fName, fType, fVis, defVal, combinedFieldHeader, docPrefix, isConcreteGen)
                    "${docPrefix}${fVis}var $fName: $fType = $defVal"
                } else {
                    val nullableType = if (isAlreadyNullableRenderedType(fType)) fType else "$fType?"
                    val isConcreteGen = !isLocalItem && vis == "public " && isConcreteGenericTypeForSwiftExport(nullableType, unboundedOwnerTypeParams)
                    unionSpecs += SwiftSafeFieldSpec(fName, nullableType, fVis, "null", combinedFieldHeader, docPrefix, isConcreteGen)
                    "${docPrefix}${fVis}var $fName: $nullableType = null"
                }
            }.toMutableList()
    val useBodyPropertiesForLargeUnion = useFinalAbiLayout && ctorParams.size > 64
    val needsSwiftSafeUnionCtor =
        !useBodyPropertiesForLargeUnion &&
            !isLocalItem &&
            vis == "public " &&
            unionSpecs.any { it.vis == "public " && it.isConcreteGenericForSwift }
    val wouldHaveBeenDataClass = useFinalAbiLayout && ctorParams.size in 1..16 && typeParams.isEmpty()
    val swiftSafeUnionProps = mutableListOf<String>()
    val extraUnionMembers = mutableListOf<String>()
    val extraUnionCompanionMembers = mutableListOf<String>()
    val hasExplicitDebug = hasExplicitOriginalDebugImpl(originalFile.items, typeName)
    val filteredImpls =
        attachedImpls.filterNot { impl ->
            val traitIdent =
                impl.traitPath
                    ?.path
                    ?.segments
                    ?.last()
                    ?.ident
                    ?.toString()
            (!hasExplicitDebug && traitIdent == "Debug") || (useFinalAbiLayout && traitIdent == "Default")
        }
    if (needsSwiftSafeUnionCtor) {
        ctorParams.clear()
        for (spec in unionSpecs) {
            if (spec.vis == "public " && spec.isConcreteGenericForSwift) {
                ctorParams += "${spec.name}CtorArg: ${spec.renderedType}"
                swiftSafeUnionProps +=
                    "${spec.leadingHeader}$indent    public var ${spec.name}: ${spec.renderedType} = ${spec.name}CtorArg\n$indent        internal set"
            } else {
                ctorParams += "${spec.docPrefix}${spec.vis}var ${spec.name}: ${spec.renderedType}"
            }
        }
        if (unionSpecs.isNotEmpty() && unionSpecs.all { it.defaultExpr != null }) {
            val defaultCtorArgs =
                unionSpecs.joinToString(",\n") { spec ->
                    val paramKey = if (spec.vis == "public " && spec.isConcreteGenericForSwift) "${spec.name}CtorArg" else spec.name
                    "$indent        $paramKey = ${spec.defaultExpr}"
                }
            extraUnionMembers += "$indent    public constructor() : this(\n$defaultCtorArgs,\n$indent    )"
        }
        extraUnionCompanionMembers += renderSwiftSafeCompanionInvoke(typeParams, selfTypeWithGenerics, unionSpecs, "$indent    ")
        if (wouldHaveBeenDataClass) {
            extraUnionMembers.addAll(
                buildSwiftSafeSyntheticDataMethods(
                    typeName = typeName,
                    typeParamCount = typeParamCount,
                    selfTypeWithGenerics = selfTypeWithGenerics,
                    specs = unionSpecs,
                    includeCopyAndComponents = true,
                    hasCopyCollision = attachedImplDefinesInstanceMethod(filteredImpls, "copy"),
                    memberIndent = "$indent    ",
                ),
            )
        }
    }
    val isDataClass = wouldHaveBeenDataClass && !needsSwiftSafeUnionCtor
    if (isDataClass && fieldNameAndTypes.any { isPrimitiveArrayRenderedType(it.second) }) {
        extraUnionMembers.addAll(buildArrayAwareDataClassMethods(typeName, fieldNameAndTypes, "$indent    "))
    }
    val classKeyword =
        when {
            isDataClass -> "data class "
            useFinalAbiLayout || needsSwiftSafeUnionCtor -> "class "
            else -> "open class "
        }
    sb
        .append(indent)
        .append(vis)
        .append(classKeyword)
        .append(typeName)
        .append(typeParams)
    if (ctorParams.isNotEmpty() && !useBodyPropertiesForLargeUnion) {
        val ctorHeaderPrefix = if (needsSwiftSafeUnionCtor) " internal constructor" else ""
        if (headerContext.isBatchCrateOutput || ctorParams.any { it.contains('\n') } || ctorParams.size > 2) {
            sb.append(ctorHeaderPrefix).append("(\n")
            for (p in ctorParams) {
                if (p.startsWith('\n')) {
                    sb.append(p.removePrefix("\n")).append(",\n")
                } else {
                    sb
                        .append(indent)
                        .append("    ")
                        .append(p)
                        .append(",\n")
                }
            }
            sb.append(indent).append(")")
        } else {
            sb
                .append(ctorHeaderPrefix)
                .append("(")
                .append(ctorParams.joinToString(", "))
                .append(")")
        }
    }
    val memberLines =
        renderAttachedImplMembers(
            selfTypeWithGenerics,
            item.generics,
            filteredImpls,
            "$indent    ",
            extraProperties =
                when {
                    useBodyPropertiesForLargeUnion -> bodyFieldProps
                    swiftSafeUnionProps.isNotEmpty() -> swiftSafeUnionProps
                    else -> emptyList()
                },
            extraMembers = extraUnionMembers,
            extraCompanionMembers = extraUnionCompanionMembers,
            isLocalClass = isLocalItem,
            structFieldNames = fieldNameAndTypes.mapTo(linkedSetOf()) { it.first },
        )
    if (memberLines.isNotEmpty()) {
        sb
            .append(" {\n")
            .append(memberLines)
            .append('\n')
            .append(indent)
            .append("}")
    }
    return sb.toString()
}

internal fun KotlinJ2KEmitter.hasExplicitOriginalDebugImpl(items: List<Item>, ownerTypeName: String): Boolean {
    for (sub in items) {
        when (sub) {
            is Item.Impl -> {
                val lastTraitSeg =
                    sub.traitPath
                        ?.path
                        ?.segments
                        ?.last()
                if (lastTraitSeg?.ident?.toString() == "Debug" &&
                    extractDirectOwnerTypeName(sub.selfType, sub.generics) == ownerTypeName
                ) {
                    return true
                }
            }
            is Item.Mod -> {
                val content = sub.content
                if (content is ModContent.Inline && hasExplicitOriginalDebugImpl(content.items, ownerTypeName)) {
                    return true
                }
            }
            else -> {}
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.formatEnumOffsetLiteral(offset: Int, reprNumKind: NumericKind?): String =
    when (reprNumKind) {
        NumericKind.U8 -> "${offset}u.toUByte()"
        NumericKind.U16 -> "${offset}u.toUShort()"
        NumericKind.U32 -> "${offset}u"
        NumericKind.U64 -> "${offset}uL"
        NumericKind.I8 -> "$offset.toByte()"
        NumericKind.I16 -> "$offset.toShort()"
        NumericKind.I64 -> "${offset}L"
        else -> "$offset"
    }

internal fun KotlinJ2KEmitter.formatEnumSteppedDiscriminant(
    baseExpr: String?,
    offset: Int,
    reprNumKind: NumericKind?,
): String {
    if (baseExpr == null) {
        return formatEnumOffsetLiteral(offset, reprNumKind)
    }
    if (offset == 0) return baseExpr
    val stepLit =
        when (reprNumKind) {
            NumericKind.U8, NumericKind.U16, NumericKind.U32 -> "${offset}u"
            NumericKind.U64 -> "${offset}uL"
            NumericKind.I64 -> "${offset}L"
            else -> "$offset"
        }
    val sumExpr =
        when (reprNumKind) {
            NumericKind.U8, NumericKind.U16 -> "(($baseExpr).toUInt() + $stepLit)"
            NumericKind.I8, NumericKind.I16 -> "(($baseExpr).toInt() + $stepLit)"
            else -> "(($baseExpr) + $stepLit)"
        }
    return when (reprNumKind) {
        NumericKind.U8 -> "$sumExpr.toUByte()"
        NumericKind.U16 -> "$sumExpr.toUShort()"
        NumericKind.I8 -> "$sumExpr.toByte()"
        NumericKind.I16 -> "$sumExpr.toShort()"
        else -> sumExpr
    }
}

internal fun KotlinJ2KEmitter.renderItemEnum(
    item: Item.Enum,
    attachedImpls: List<Item.Impl>,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val typeName = rustTypeIdentToKotlin(item.ident.toString())
    val sigKey = "type:$typeName"
    val seenCount = scopeSignatures[sigKey] ?: 0
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) {
        return ""
    }
    val useFinalAbiLayout = headerContext.rawAbiMode || headerContext.finalClasses
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val typeArgsUse = renderTypeArgumentsFromGenerics(item.generics)
    val selfTypeWithGenerics = typeName + typeArgsUse
    val prevEnumTypeParams = activeTypeParamNames
    activeTypeParamNames = activeTypeParamNames + collectGenericTypeParamNames(item.generics)
    val typeParams: String
    val superTraits: List<String>
    try {
        typeParams = renderTypeParameters(item.generics, selfTypeName = selfTypeWithGenerics)
        superTraits = renderDistinctSuperTraits(attachedImpls, selfTypeWithGenerics)
    } finally {
        activeTypeParamNames = prevEnumTypeParams
    }
    val variants = item.variants.toList().filterNot { isDocOnlyCfgAttribute(it.attrs) }
    if (variants.isEmpty() && typeParams.isEmpty() && superTraits.isEmpty() && attachedImpls.isEmpty()) {
        val emptyClassKw = if (useFinalAbiLayout) "class " else "open class "
        if (useFinalAbiLayout || headerContext.isBatchCrateOutput) {
            sb.append(
                "${indent}${vis}${emptyClassKw}$typeName(\n" +
                    "$indent    public val handle: Long = -1L,\n" +
                    "$indent) {\n" +
                    "$indent    public val ptr: Long get() = handle\n" +
                    "$indent}",
            )
        } else {
            sb.append(
                "${indent}${vis}${emptyClassKw}$typeName(public val handle: Long = -1L) {\n" +
                    "$indent    public val ptr: Long get() = handle\n" +
                    "$indent}",
            )
        }
        return sb.toString()
    }
    if (variants.isEmpty() && typeParams.isEmpty()) {
        val prevOverrideNames = currentStructOverrideMethodNames
        currentStructOverrideMethodNames = collectLocalSupertraitMethodNames(superTraits, includeReceiverlessMethods = true)
        val attachedMembers =
            try {
                renderAttachedImplMembers(
                    typeName = selfTypeWithGenerics,
                    ownerGenerics = item.generics,
                    attachedImpls = attachedImpls,
                    indent = "$indent    ",
                    isLocalClass = isLocalItem,
                    isObjectOwner = true,
                )
            } finally {
                currentStructOverrideMethodNames = prevOverrideNames
            }
        sb
            .append(indent)
            .append(vis)
            .append("object ")
            .append(typeName)
        if (superTraits.isNotEmpty()) {
            sb.append(" : ").append(superTraits.joinToString(", "))
        }
        if (attachedMembers.isNotBlank()) {
            sb
                .append(" {\n")
                .append(attachedMembers)
                .append('\n')
                .append(indent)
                .append('}')
        }
        return sb.toString()
    }
    val isSimpleEnum = variants.isNotEmpty() && variants.all { it.fields is Fields.Unit }
    val hasVariantCollidingWithTypeName =
        isSimpleEnum && variants.any { rustTypeIdentToKotlin(it.ident.toString()) == typeName }
    if ((useFinalAbiLayout || hasVariantCollidingWithTypeName) && isSimpleEnum && typeParams.isEmpty() && superTraits.isEmpty()) {
        val reprKotlinType = extractEnumReprKotlinType(item.attrs) ?: "UInt"
        val reprNumKind = resolveNumericKind(reprKotlinType) ?: NumericKind.U32
        val enumFilteredImpls =
            attachedImpls.filterNot { impl ->
                val traitIdent =
                    impl.traitPath
                        ?.path
                        ?.segments
                        ?.last()
                        ?.ident
                        ?.toString()
                traitIdent == "Clone" || traitIdent == "Default"
            }
        val prevOverrideNames = currentStructOverrideMethodNames
        currentStructOverrideMethodNames = emptySet()
        val attachedMembers =
            try {
                renderAttachedImplMembers(selfTypeWithGenerics, item.generics, enumFilteredImpls, "$indent    ", isLocalClass = isLocalItem)
            } finally {
                currentStructOverrideMethodNames = prevOverrideNames
            }
        if (useFinalAbiLayout || headerContext.isBatchCrateOutput) {
            sb
                .append(indent)
                .append(vis)
                .append("enum class ")
                .append(typeName)
                .append("(\n")
                .append(indent)
                .append("    public val value: ")
                .append(reprKotlinType)
                .append(",\n")
                .append(indent)
                .append(") {\n")
        } else {
            sb
                .append(indent)
                .append(vis)
                .append("enum class ")
                .append(typeName)
                .append("(public val value: ")
                .append(reprKotlinType)
                .append(") {\n")
        }
        var baseDiscExpr: String? = null
        var stepOffset = 0
        val seenEntryNames = hashSetOf<String>()
        val entryLines = mutableListOf<String>()
        for (v in variants) {
            val rawVar = v.ident.toString().removePrefix("r#")
            val rawEntryName =
                if (isScreamingSnakeIdent(rawVar)) {
                    rustConstIdentToKotlin(v.ident.toString())
                } else {
                    rustTypeIdentToKotlin(v.ident.toString())
                }
            val entryName = if (rawEntryName == typeName) "${rawEntryName}Variant" else rawEntryName
            if (!seenEntryNames.add(entryName)) continue
            val vTrivia = renderLeadingTrivia(v, "$indent    ")
            val vDoc = renderKDocFromAttributes(v.attrs, "$indent    ")
            val explicitExpr = v.discriminant?.expr
            val discArg =
                if (explicitExpr != null) {
                    val rendered = renderTypedNumericInitializer(explicitExpr, reprNumKind, "$indent    ", selfTypeName = selfTypeWithGenerics)
                    baseDiscExpr = rendered
                    stepOffset = 1
                    rendered
                } else {
                    val rendered = formatEnumSteppedDiscriminant(baseDiscExpr, stepOffset, reprNumKind)
                    stepOffset += 1
                    rendered
                }
            entryLines += "${vTrivia}${vDoc}$indent    $entryName($discArg)"
        }
        if (attachedMembers.isNotEmpty()) {
            sb.append(entryLines.joinToString(",\n")).append(";\n\n")
            sb.append(attachedMembers).append('\n')
        } else {
            sb.append(entryLines.joinToString(",\n")).append(",\n")
        }
        sb.append(indent).append("}")
        return sb.toString()
    }

    val prevOverrideNames = currentStructOverrideMethodNames
    currentStructOverrideMethodNames = collectLocalSupertraitMethodNames(superTraits)
    val attachedMembers =
        try {
            renderAttachedImplMembers(selfTypeWithGenerics, item.generics, attachedImpls, "$indent    ", isLocalClass = isLocalItem)
        } finally {
            currentStructOverrideMethodNames = prevOverrideNames
        }
    val hasDiscriminants = isSimpleEnum && typeParams.isEmpty() && variants.any { it.discriminant != null }
    val reprKotlinType = if (hasDiscriminants) (extractEnumReprKotlinType(item.attrs) ?: "Int") else null
    val reprNumKind = reprKotlinType?.let(this::resolveNumericKind)

    sb
        .append(indent)
        .append(vis)
        .append("sealed class ")
        .append(typeName)
        .append(typeParams)
    if (reprKotlinType != null) {
        if (useFinalAbiLayout || headerContext.isBatchCrateOutput) {
            sb
                .append("(\n")
                .append(indent)
                .append("    public val value: ")
                .append(reprKotlinType)
                .append(",\n")
                .append(indent)
                .append(')')
        } else {
            sb.append("(public val value: ").append(reprKotlinType).append(')')
        }
    }
    if (superTraits.isNotEmpty()) {
        sb.append(" : ").append(superTraits.joinToString(", "))
    }
    sb.append(" {\n")
    var prevVariantName: String? = null
    activeTypeParamNames = prevEnumTypeParams + collectGenericTypeParamNames(item.generics)
    try {
        for (v in variants) {
            val vName = rustTypeIdentToKotlin(v.ident.toString())
            val discArg =
                if (reprKotlinType != null) {
                    val explicitExpr = v.discriminant?.expr
                    if (explicitExpr != null) {
                        renderTypedNumericInitializer(explicitExpr, reprNumKind, "$indent    ", selfTypeName = selfTypeWithGenerics)
                    } else if (prevVariantName != null) {
                        val stepOne =
                            when (reprNumKind) {
                                NumericKind.U8, NumericKind.U16, NumericKind.U32 -> "1u"
                                NumericKind.U64 -> "1uL"
                                NumericKind.I64 -> "1L"
                                else -> "1"
                            }
                        val rawStep = "$prevVariantName.value + $stepOne"
                        when (reprNumKind) {
                            NumericKind.U8 -> "($rawStep).toUByte()"
                            NumericKind.U16 -> "($rawStep).toUShort()"
                            NumericKind.I8 -> "($rawStep).toByte()"
                            NumericKind.I16 -> "($rawStep).toShort()"
                            else -> rawStep
                        }
                    } else {
                        defaultInitializerForType(reprKotlinType)
                    }
                } else {
                    null
                }
            sb.append(
                renderEnumVariantClass(
                    variant = v,
                    enumName = typeName,
                    enumGenerics = item.generics,
                    enumTypeParams = typeParams,
                    enumTypeArgsUse = typeArgsUse,
                    indent = "$indent    ",
                    discriminantArg = discArg,
                    isPublicEnum = !isLocalItem && vis == "public ",
                ),
            )
            sb.append('\n')
            prevVariantName = vName
        }
    } finally {
        activeTypeParamNames = prevEnumTypeParams
    }
    val derivesEquality =
        item.attrs.any { attribute ->
            val derive = attribute.meta as? Meta.List
            derive != null && derive.path.segments
                .last()
                ?.ident
                .toString() in setOf("derive", "derive_const") &&
                derive.tokens
                    .clone()
                    .toList()
                    .any { it is TokenTree.Ident && it.value.toString() == "PartialEq" }
        }
    if (derivesEquality) {
        sb.append("\n$indent    public fun rustEquals(other: $selfTypeWithGenerics): Boolean = this == other\n")
    }
    if (attachedMembers.isNotEmpty()) {
        sb.append('\n').append(attachedMembers).append('\n')
    }
    sb.append(indent).append("}")
    return sb.toString()
}

internal fun KotlinJ2KEmitter.renderEnumVariantClass(
    variant: Variant,
    enumName: String,
    enumGenerics: Generics,
    enumTypeParams: String,
    enumTypeArgsUse: String,
    indent: String,
    discriminantArg: String? = null,
    isPublicEnum: Boolean = true,
): String {
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(variant, indent))
    sb.append(renderKDocFromAttributes(variant.attrs, indent))
    val vName = rustTypeIdentToKotlin(variant.ident.toString())
    val enumSelfType = enumName + enumTypeArgsUse
    val variantSelfType = vName + enumTypeArgsUse
    val unboundedEnumTypeParams = collectUnboundedGenericTypeParamNames(enumGenerics)
    val typeParamCount = enumGenerics.params.toList().count { it is GenericParam.TypeParam }
    val superCallArgs = discriminantArg.orEmpty()
    when (val fields = variant.fields) {
        is Fields.Unit -> {
            if (enumTypeParams.isEmpty()) {
                sb
                    .append(indent)
                    .append("public data object ")
                    .append(vName)
                    .append(" : ")
                    .append(enumName)
                    .append('(')
                    .append(superCallArgs)
                    .append(')')
            } else {
                sb
                    .append(indent)
                    .append("public class ")
                    .append(vName)
                    .append(enumTypeParams)
                    .append("()")
                    .append(" : ")
                    .append(enumName)
                    .append(enumTypeArgsUse)
                    .append('(')
                    .append(superCallArgs)
                    .append(')')
            }
        }
        is Fields.Unnamed -> {
            val list = fields.fields.unnamed.toList()
            val specs =
                list.mapIndexed { idx, f ->
                    val pName = tupleFieldPropertyName(idx, list.size)
                    val pType = renderType(f.ty, selfTypeName = enumSelfType)
                    val isConcreteGen = isPublicEnum && isConcreteGenericTypeForSwiftExport(pType, unboundedEnumTypeParams)
                    SwiftSafeFieldSpec(pName, pType, "public ", null, "", "", isConcreteGen)
                }
            appendEnumVariantWithFields(
                sb = sb,
                vName = vName,
                variantSelfType = variantSelfType,
                enumName = enumName,
                enumTypeParams = enumTypeParams,
                enumTypeArgsUse = enumTypeArgsUse,
                typeParamCount = typeParamCount,
                superCallArgs = superCallArgs,
                specs = specs,
                indent = indent,
            )
        }
        is Fields.Named -> {
            val list = fields.fields.named.toList()
            val seenFieldNames = hashSetOf<String>()
            val specs =
                list.mapIndexedNotNull { idx, f ->
                    if (isDocOnlyCfgAttribute(f.attrs)) return@mapIndexedNotNull null
                    val pName = rustFieldIdentToKotlin(f.ident?.toString() ?: "field$idx")
                    if (!seenFieldNames.add(pName)) return@mapIndexedNotNull null
                    val pType = renderType(f.ty, selfTypeName = enumSelfType)
                    val isConcreteGen = isPublicEnum && isConcreteGenericTypeForSwiftExport(pType, unboundedEnumTypeParams)
                    SwiftSafeFieldSpec(pName, pType, "public ", null, "", "", isConcreteGen)
                }
            appendEnumVariantWithFields(
                sb = sb,
                vName = vName,
                variantSelfType = variantSelfType,
                enumName = enumName,
                enumTypeParams = enumTypeParams,
                enumTypeArgsUse = enumTypeArgsUse,
                typeParamCount = typeParamCount,
                superCallArgs = superCallArgs,
                specs = specs,
                indent = indent,
            )
        }
    }
    return sb.toString()
}

private fun KotlinJ2KEmitter.appendEnumVariantWithFields(
    sb: StringBuilder,
    vName: String,
    variantSelfType: String,
    enumName: String,
    enumTypeParams: String,
    enumTypeArgsUse: String,
    typeParamCount: Int,
    superCallArgs: String,
    specs: List<SwiftSafeFieldSpec>,
    indent: String,
) {
    if (specs.none { it.isConcreteGenericForSwift }) {
        val params = specs.map { "public var ${it.name}: ${it.renderedType}" }
        sb
            .append(indent)
            .append("public data class ")
            .append(vName)
            .append(enumTypeParams)
            .append("(")
            .append(params.joinToString(", "))
            .append(")")
            .append(" : ")
            .append(enumName)
            .append(enumTypeArgsUse)
            .append("(")
            .append(superCallArgs)
            .append(")")
        return
    }
    val ctorParams =
        specs.map { spec ->
            if (spec.isConcreteGenericForSwift) {
                "${spec.name}CtorArg: ${spec.renderedType}"
            } else {
                "public var ${spec.name}: ${spec.renderedType}"
            }
        }
    sb
        .append(indent)
        .append("public class ")
        .append(vName)
        .append(enumTypeParams)
        .append(" internal constructor(")
        .append(ctorParams.joinToString(", "))
        .append(") : ")
        .append(enumName)
        .append(enumTypeArgsUse)
        .append("(")
        .append(superCallArgs)
        .append(") {\n")
    for (spec in specs) {
        if (spec.isConcreteGenericForSwift) {
            sb
                .append(indent)
                .append("    public var ")
                .append(spec.name)
                .append(": ")
                .append(spec.renderedType)
                .append(" = ")
                .append(spec.name)
                .append("CtorArg\n")
                .append(indent)
                .append("        internal set\n")
        }
    }
    val syntheticMethods =
        buildSwiftSafeSyntheticDataMethods(
            typeName = vName,
            typeParamCount = typeParamCount,
            selfTypeWithGenerics = variantSelfType,
            specs = specs,
            includeCopyAndComponents = true,
            hasCopyCollision = false,
            memberIndent = "$indent    ",
        )
    for (m in syntheticMethods) {
        sb.append('\n').append(m).append('\n')
    }
    sb
        .append('\n')
        .append(indent)
        .append("    public companion object {\n")
        .append(renderSwiftSafeCompanionInvoke(enumTypeParams, variantSelfType, specs, "$indent    "))
        .append('\n')
        .append(indent)
        .append("    }\n")
        .append(indent)
        .append("}")
}

private fun synTypeMentionsAssoc(ty: SynType, rawAssocName: String): Boolean {
    var found = false
    val v =
        object : Visit() {
            override fun visitTypePath(t: SynType.Path) {
                val segs = t.path.segments.toList()
                val q = t.qself
                if (q != null) {
                    val qPath = q.ty as? SynType.Path
                    val isSelf =
                        qPath
                            ?.path
                            ?.segments
                            ?.toList()
                            ?.singleOrNull()
                            ?.ident
                            ?.toString() == "Self"
                    if (isSelf && segs
                            .getOrNull(q.position)
                            ?.ident
                            ?.toString()
                            ?.removePrefix("r#") == rawAssocName
                    ) {
                        found = true
                    }
                } else if (segs.size == 2 &&
                    segs[0].ident.toString() == "Self" &&
                    segs[1].ident.toString().removePrefix("r#") == rawAssocName
                ) {
                    found = true
                } else if (segs.size == 1 && segs[0].ident.toString().removePrefix("r#") == rawAssocName) {
                    found = true
                }
                super.visitTypePath(t)
            }
        }
    v.visitType(ty)
    return found
}

private fun synTypeAllowsOutPosition(ty: SynType, rawAssocName: String): Boolean {
    if (!synTypeMentionsAssoc(ty, rawAssocName)) return true
    return when (ty) {
        is SynType.Group -> synTypeAllowsOutPosition(ty.elem, rawAssocName)
        is SynType.Paren -> synTypeAllowsOutPosition(ty.elem, rawAssocName)
        is SynType.Reference -> ty.mutability == null && synTypeAllowsOutPosition(ty.elem, rawAssocName)
        is SynType.Tuple -> {
            val elems = ty.elems.toList()
            elems.size <= 3 && elems.all { synTypeAllowsOutPosition(it, rawAssocName) }
        }
        is SynType.FnPtr -> false
        is SynType.Path -> {
            val segs = ty.path.segments.toList()
            val q = ty.qself
            if (q != null) {
                val qPath = q.ty as? SynType.Path
                val isSelf =
                    qPath
                        ?.path
                        ?.segments
                        ?.toList()
                        ?.singleOrNull()
                        ?.ident
                        ?.toString() == "Self"
                return isSelf && segs.size == 1 && segs[0].ident.toString().removePrefix("r#") == rawAssocName
            }
            if (segs.size == 2 && segs[0].ident.toString() == "Self" && segs[1].ident.toString().removePrefix("r#") == rawAssocName) {
                return true
            }
            if (segs.size == 1 && segs[0].ident.toString().removePrefix("r#") == rawAssocName) {
                return true
            }
            val lastSeg = segs.lastOrNull() ?: return false
            val rawLast = lastSeg.ident.toString().removePrefix("r#")
            val covariantStdWrappers = setOf("Option", "Result", "Vec", "Box", "Rc", "Arc", "Cow")
            if (rawLast !in covariantStdWrappers) return false
            val args = (lastSeg.arguments as? PathArguments.AngleBracketed)?.args?.toList().orEmpty()
            args.all { ga ->
                when (ga) {
                    is GenericArgument.TypeArg -> synTypeAllowsOutPosition(ga.type, rawAssocName)
                    is GenericArgument.LifetimeArg -> true
                    else -> false
                }
            }
        }
        else -> false
    }
}

private fun isAssocTypeCovariantInTrait(item: Item.Trait, rawAssocName: String): Boolean {
    var mentionedInSuperOrWhere = false
    val checkVisitor =
        object : Visit() {
            override fun visitTypePath(t: SynType.Path) {
                val segs = t.path.segments.toList()
                val q = t.qself
                if (q != null) {
                    val qPath = q.ty as? SynType.Path
                    val isSelf =
                        qPath
                            ?.path
                            ?.segments
                            ?.toList()
                            ?.singleOrNull()
                            ?.ident
                            ?.toString() == "Self"
                    if (isSelf && segs
                            .getOrNull(q.position)
                            ?.ident
                            ?.toString()
                            ?.removePrefix("r#") == rawAssocName
                    ) {
                        mentionedInSuperOrWhere = true
                    }
                } else if (segs.size == 2 &&
                    segs[0].ident.toString() == "Self" &&
                    segs[1].ident.toString().removePrefix("r#") == rawAssocName
                ) {
                    mentionedInSuperOrWhere = true
                } else if (segs.size == 1 && segs[0].ident.toString().removePrefix("r#") == rawAssocName) {
                    mentionedInSuperOrWhere = true
                }
                super.visitTypePath(t)
            }
        }
    for (bound in item.supertraits.toList()) {
        checkVisitor.visitTypeParamBound(bound)
    }
    item.generics.whereClause?.let { checkVisitor.visitWhereClause(it) }
    if (mentionedInSuperOrWhere) return false

    for (tItem in item.items) {
        if (traitItemHasDocOnlyCfg(tItem)) continue
        when (tItem) {
            is TraitItem.AssocType -> {
                if (tItem.ident.toString().removePrefix("r#") != rawAssocName) {
                    for (b in tItem.bounds.toList()) {
                        checkVisitor.visitTypeParamBound(b)
                    }
                    tItem.generics.whereClause?.let { checkVisitor.visitWhereClause(it) }
                    if (mentionedInSuperOrWhere) return false
                }
            }
            is TraitItem.Const -> {
                if (!synTypeAllowsOutPosition(tItem.ty, rawAssocName)) return false
            }
            is TraitItem.Fn -> {
                for (arg in tItem.sig.inputs.toList()) {
                    if (arg is FnArg.Typed && synTypeMentionsAssoc(arg.patType.ty, rawAssocName)) {
                        return false
                    }
                }
                tItem.sig.generics.whereClause
                    ?.let { checkVisitor.visitWhereClause(it) }
                if (mentionedInSuperOrWhere) return false
                val retTy = extractReturnSynType(tItem.sig.output)
                if (retTy != null && !synTypeAllowsOutPosition(retTy, rawAssocName)) {
                    return false
                }
            }
            else -> {}
        }
    }
    return true
}

private fun KotlinJ2KEmitter.hasInvalidDefaultBodyOnErasedInterface(
    defaultBlock: Block,
    sig: Signature,
    body: String,
    selfParamName: String?,
    traitName: String,
    instanceMethodRawNames: Set<String>,
): Boolean {
    var mutatesDerefSelf = false
    var returnsBareSelfOrUnaryOnSelf = false
    var callsStaticSelfOrTraitFn = false
    val blockVisitor =
        object : Visit() {
            override fun visitExprAssign(e: Expr.Assign) {
                val lhs = unwrapParenOrGroupExpr(e.left)
                if (lhs is Expr.Unary && lhs.op is UnOp.Deref) {
                    val inner = unwrapParenOrGroupExpr(lhs.expr) as? Expr.Path
                    if (inner?.qself == null && inner?.path?.isIdent("self") == true) {
                        mutatesDerefSelf = true
                    }
                }
                super.visitExprAssign(e)
            }

            override fun visitExprUnary(e: Expr.Unary) {
                val inner = unwrapParenOrGroupExpr(e.expr) as? Expr.Path
                if (inner?.qself == null && inner?.path?.isIdent("self") == true && e.op !is UnOp.Deref) {
                    returnsBareSelfOrUnaryOnSelf = true
                }
                super.visitExprUnary(e)
            }

            override fun visitExprCall(e: Expr.Call) {
                val fnPath = unwrapParenOrGroupExpr(e.func) as? Expr.Path
                if (fnPath != null && fnPath.qself == null) {
                    val segs = fnPath.path.segments.toList()
                    val head =
                        segs
                            .firstOrNull()
                            ?.ident
                            ?.toString()
                            ?.removePrefix("r#")
                    val tail =
                        segs
                            .lastOrNull()
                            ?.ident
                            ?.toString()
                            ?.removePrefix("r#")
                    val firstArgPath =
                        e.args
                            .toList()
                            .firstOrNull()
                            ?.let { unwrapParenOrGroupExpr(it) as? Expr.Path }
                    val passesSelfFirst =
                        firstArgPath?.qself == null && firstArgPath?.path?.isIdent("self") == true
                    if (segs.size == 2 && head == "Self" && tail != null &&
                        tail !in currentTraitMethodRawNames &&
                        (tail !in instanceMethodRawNames || !passesSelfFirst)
                    ) {
                        callsStaticSelfOrTraitFn = true
                    } else if (segs.size >= 2 && head != null && tail != null &&
                        (head == traitName || rustTypeIdentToKotlin(head) == traitName) &&
                        (tail !in instanceMethodRawNames || !passesSelfFirst)
                    ) {
                        callsStaticSelfOrTraitFn = true
                    } else if (segs.size == 2 &&
                        head != null &&
                        head != "Self" &&
                        head.firstOrNull()?.isUpperCase() == true &&
                        lookupDeclaredTraitAst(rustTypeIdentToKotlin(head)) != null &&
                        !passesSelfFirst
                    ) {
                        callsStaticSelfOrTraitFn = true
                    }
                }
                super.visitExprCall(e)
            }
        }
    blockVisitor.visitBlock(defaultBlock)
    if (mutatesDerefSelf) return true
    if (selfParamName == null) return false

    if (Regex("""\bas\s+${Regex.escape(selfParamName)}\b""").containsMatchIn(body)) return true
    if (Regex("""\bthis\s*=""").containsMatchIn(body)) return true
    if (Regex("""[!+\-*/%]\s*this\b|\bthis\s*(?:\+|-|\*|/|%|shl|shr)\b""").containsMatchIn(body)) return true
    if (callsStaticSelfOrTraitFn || returnsBareSelfOrUnaryOnSelf) return true

    val retSyn = extractReturnSynType(sig.output)
    if (retSyn != null && synTypeReferencesDirectSelf(retSyn)) {
        if (Regex("""(?m)(?:^|\belse\b|return)\s+this\b(?:\s*[;}]|$)""").containsMatchIn(body)) {
            return true
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.pickUnusedTraitSelfParamName(
    generics: Generics,
    assocTypeNames: List<String>,
    traitItem: Item.Trait? = null,
): String {
    val memberGenerics =
        traitItem
            ?.items
            ?.flatMap { tItem ->
                when (tItem) {
                    is TraitItem.Fn -> collectGenericTypeParamNames(tItem.sig.generics)
                    is TraitItem.AssocType -> collectGenericTypeParamNames(tItem.generics)
                    else -> emptySet()
                }
            }.orEmpty()
    val existing = collectGenericTypeParamNames(generics) + assocTypeNames + memberGenerics
    for (candidate in listOf("T", "SelfType", "S", "Target")) {
        if (candidate !in existing) return candidate
    }
    return "SelfType0"
}

internal fun KotlinJ2KEmitter.renderItemTrait(
    item: Item.Trait,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val traitName = rustTypeIdentToKotlin(item.ident.toString())
    val sigKey = "type:$traitName"
    val seenCount = scopeSignatures[sigKey] ?: 0
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) {
        return ""
    }
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val assocTypeNames = lookupTraitDefaultlessAssocTypes(traitName)
    val rawAssocByKtName =
        item.items
            .filterIsInstance<TraitItem.AssocType>()
            .associate { rustTypeIdentToKotlin(it.ident.toString()) to it.ident.toString().removePrefix("r#") }
    val traitInstanceMethodRawNames =
        item.items
            .filterIsInstance<TraitItem.Fn>()
            .filter { fnItem ->
                fnItem.sig.inputs
                    .toList()
                    .firstOrNull() is FnArg.Receiver
            }.mapTo(linkedSetOf()) {
                it.sig.ident
                    .toString()
                    .removePrefix("r#")
            }
    val selfParamName =
        if (isSelfParameterizedTraitName(traitName)) {
            pickUnusedTraitSelfParamName(item.generics, assocTypeNames, item)
        } else {
            null
        }
    val memberSelfType = selfParamName ?: (traitName + renderTypeArgumentsFromGenerics(item.generics))
    val prevTraitAssoc = currentTraitAssocParamNames
    val prevTraitTypeParams = activeTypeParamNames
    val prevTraitMethods = currentTraitMethodRawNames
    currentTraitAssocParamNames = assocTypeNames.toSet()
    currentTraitMethodRawNames = collectLocalTraitAndSupertraitFnRawNames(item)
    activeTypeParamNames =
        prevTraitTypeParams +
        collectGenericTypeParamNames(item.generics) +
        setOfNotNull(selfParamName) +
        assocTypeNames
    return try {
        val baseTypeParams =
            renderTypeParameters(item.generics, selfTypeName = memberSelfType)
                .removePrefix("<")
                .removeSuffix(">")
        val allowOutAssoc = selfParamName != null || usesTaggedCoreResult()
        val assocParamDecls =
            assocTypeNames.map { assocKt ->
                val rawAssoc = rawAssocByKtName[assocKt]
                val isCovariant = allowOutAssoc && rawAssoc != null && isAssocTypeCovariantInTrait(item, rawAssoc)
                if (isCovariant) "out $assocKt" else assocKt
            }
        val combinedTraitParams =
            buildList {
                if (baseTypeParams.isNotEmpty()) add(baseTypeParams)
                if (selfParamName != null) add(selfParamName)
                addAll(assocParamDecls)
            }
        val typeParams =
            if (combinedTraitParams.isEmpty()) {
                ""
            } else {
                "<${combinedTraitParams.joinToString(", ")}>"
            }
        val prevBounded = currentBoundedTypeName
        currentBoundedTypeName = memberSelfType
        val (superBounds, whereClause) =
            try {
                val sbList =
                    item.supertraits
                        .toList()
                        .mapNotNull { renderTypeParamBound(it, selfTypeName = memberSelfType) }
                        .distinct()
                val wc =
                    renderWhereClause(
                        item.generics.whereClause,
                        selfTypeName = memberSelfType,
                        ownerGenerics = item.generics,
                        extraDeclaredNames = setOfNotNull(selfParamName) + assocTypeNames,
                    )
                sbList to wc
            } finally {
                currentBoundedTypeName = prevBounded
            }

        sb
            .append(indent)
            .append(vis)
            .append("interface ")
            .append(traitName)
            .append(typeParams)
        if (superBounds.isNotEmpty()) {
            sb.append(" : ").append(superBounds.joinToString(", "))
        }
        sb.append(whereClause)

        val traitSignatures = linkedMapOf<String, Int>()
        val members = mutableListOf<String>()
        for (tItem in item.items) {
            if (traitItemHasDocOnlyCfg(tItem)) continue
            when (tItem) {
                is TraitItem.Const -> {
                    val cDoc = renderKDocFromAttributes(tItem.attrs, "$indent    ")
                    val cName = disambiguateName(rustConstIdentToKotlin(tItem.ident.toString()), traitSignatures)
                    val cType = renderType(tItem.ty, selfTypeName = memberSelfType)
                    val defaultExpr = tItem.default?.expr
                    if (defaultExpr != null) {
                        val renderedExpr =
                            renderTypedNumericInitializer(
                                defaultExpr,
                                resolveNumericKind(cType),
                                "$indent    ",
                                selfTypeName = memberSelfType,
                            )
                        members += "${cDoc}$indent    public val $cName: $cType\n$indent        get() = $renderedExpr"
                    } else {
                        members += "${cDoc}$indent    public val $cName: $cType"
                    }
                }
                is TraitItem.Fn -> {
                    val prevClosureLabel = currentClosureLabel
                    val prevEnclosingRet = currentEnclosingReturnRenderedType
                    val prevEnclosingSynRet = currentEnclosingReturnSynType
                    val prevEnclosingFmtResult = currentEnclosingReturnsFmtResult
                    val prevTailExpectsUnitResultOk = currentTailExpectsUnitResultOk
                    val prevMutableListLocals = currentMutableListLocals
                    val prevDequeLocals = currentDequeLocals
                    val prevIterableLocals = currentIterableLocals
                    val prevSourceParameterTypes = currentSourceParameterTypes
                    val prevTypedLocals = currentTypedLocals
                    val prevWitnessedBounds = currentWitnessedTraitBounds
                    val prevTypeParamBounds = currentTypeParamTraitBounds
                    val prevNullableLocals = currentNullableLocals
                    val prevSmartCastLocals = currentSmartCastNonNullLocals
                    val prevSubst = substitutedTypeParamTypes
                    val prevIntoSubstTps = currentIntoSubstitutedTypeParams
                    val prevIntoSubstParams = currentIntoSubstitutedParams
                    val prevTraitFnTypeParams = activeTypeParamNames
                    val prevWitnesses = currentTypeParamWitnessCalls
                    val prevSharedBorrowRecv = currentReceiverIsSharedBorrow
                    val prevMutBorrowRecv = currentReceiverIsMutBorrow
                    currentClosureLabel = null
                    currentEnclosingReturnRenderedType = null
                    currentEnclosingReturnSynType = null
                    val traitFirstReceiverRef =
                        (
                            tItem.sig.inputs
                                .toList()
                                .firstOrNull() as? FnArg.Receiver
                        )?.kind as? ReceiverKind.Reference
                    currentReceiverIsSharedBorrow = traitFirstReceiverRef?.let { it.mutability == null } == true
                    currentReceiverIsMutBorrow = traitFirstReceiverRef?.let { it.mutability != null } == true
                    currentMutableListLocals = emptySet()
                    currentDequeLocals = emptySet()
                    currentIterableLocals = emptySet()
                    currentSourceParameterTypes = emptyMap()
                    currentTypedLocals = emptyMap()
                    currentWitnessedTraitBounds = emptyMap()
                    currentTypeParamTraitBounds = emptyMap()
                    currentNullableLocals = emptySet()
                    currentSmartCastNonNullLocals = emptySet()
                    currentIntoSubstitutedParams = emptySet()
                    val traitFnGenerics = tItem.sig.generics
                    val traitFnParams = traitFnGenerics.params.toList()
                    val intoSubstTps = linkedSetOf<String>()
                    val fnSubsts = extractFnTraitSubstitutions(traitFnParams, traitFnGenerics.whereClause, memberSelfType, intoSubstTps)
                    if (fnSubsts.isNotEmpty()) {
                        substitutedTypeParamTypes = prevSubst + fnSubsts
                    }
                    currentIntoSubstitutedTypeParams = prevIntoSubstTps + intoSubstTps
                    activeTypeParamNames = (prevTraitFnTypeParams + collectGenericTypeParamNames(tItem.sig.generics)) - substitutedTypeParamTypes.keys
                    scopeBuilder.enterDef()
                    try {
                        val sigHeader =
                            renderFunctionSignature(
                                sig = tItem.sig,
                                visPrefix = "public ",
                                openPrefix = "",
                                selfTypeName = memberSelfType,
                                ownerGenerics = item.generics,
                                scopeSignatures = traitSignatures,
                                isInterfaceMethod = true,
                                fnBlock = tItem.default,
                            )
                        if (sigHeader != null) {
                            val fnDoc = renderKDocFromAttributes(tItem.attrs, "$indent    ")
                            val defaultBlock = tItem.default
                            if (defaultBlock != null) {
                                val retType = extractReturnSynType(tItem.sig.output)
                                val shadows = computeReassignedParamShadows(tItem.sig, defaultBlock, "$indent        ")
                                val prevInTraitDefault = inTraitDefaultMethodBody
                                inTraitDefaultMethodBody = true
                                val body =
                                    try {
                                        renderBlock(
                                            defaultBlock,
                                            "$indent    ",
                                            selfTypeName = memberSelfType,
                                            returnType = retType,
                                            leadingInjectedLines = shadows,
                                            allowExpressionBody = true,
                                        )
                                    } finally {
                                        inTraitDefaultMethodBody = prevInTraitDefault
                                    }
                                if (hasInvalidDefaultBodyOnErasedInterface(
                                        defaultBlock = defaultBlock,
                                        sig = tItem.sig,
                                        body = body,
                                        selfParamName = selfParamName,
                                        traitName = traitName,
                                        instanceMethodRawNames = traitInstanceMethodRawNames,
                                    )
                                ) {
                                    members += "${fnDoc}$indent    $sigHeader"
                                } else {
                                    members += "${fnDoc}$indent    $sigHeader $body"
                                }
                            } else {
                                members += "${fnDoc}$indent    $sigHeader"
                            }
                        }
                    } finally {
                        scopeBuilder.exitDef()
                        currentClosureLabel = prevClosureLabel
                        currentEnclosingReturnRenderedType = prevEnclosingRet
                        currentEnclosingReturnSynType = prevEnclosingSynRet
                        currentEnclosingReturnsFmtResult = prevEnclosingFmtResult
                        currentTailExpectsUnitResultOk = prevTailExpectsUnitResultOk
                        currentMutableListLocals = prevMutableListLocals
                        currentDequeLocals = prevDequeLocals
                        currentIterableLocals = prevIterableLocals
                        currentSourceParameterTypes = prevSourceParameterTypes
                        currentTypedLocals = prevTypedLocals
                        currentWitnessedTraitBounds = prevWitnessedBounds
                        currentTypeParamTraitBounds = prevTypeParamBounds
                        currentNullableLocals = prevNullableLocals
                        currentSmartCastNonNullLocals = prevSmartCastLocals
                        substitutedTypeParamTypes = prevSubst
                        currentIntoSubstitutedTypeParams = prevIntoSubstTps
                        currentIntoSubstitutedParams = prevIntoSubstParams
                        activeTypeParamNames = prevTraitFnTypeParams
                        currentTypeParamWitnessCalls = prevWitnesses
                        currentReceiverIsSharedBorrow = prevSharedBorrowRecv
                        currentReceiverIsMutBorrow = prevMutBorrowRecv
                    }
                }
                is TraitItem.AssocType -> {}
                is TraitItem.Macro -> {
                    members +=
                        renderImplOrTraitMacroMembers(
                            mac = tItem.mac,
                            indent = "$indent    ",
                            selfTypeName = memberSelfType,
                            ownerGenerics = item.generics,
                            scopeSignatures = traitSignatures,
                            asTopLevelExtension = false,
                            isInterface = true,
                        )
                }
                is TraitItem.Verbatim -> {}
            }
        }
        val iterSuperSpec = extractIteratorSupertraitSpec(item.supertraits.toList(), memberSelfType)
        if (iterSuperSpec != null) {
            if ("next()" !in traitSignatures) {
                traitSignatures["next()"] = 1
                members += "$indent    public fun next(): ${iterSuperSpec.itemType}?"
            }
            if (iterSuperSpec.hasDoubleEnded && "nextBack()" !in traitSignatures) {
                traitSignatures["nextBack()"] = 1
                members += "$indent    public fun nextBack(): ${iterSuperSpec.itemType}?"
            }
            if (iterSuperSpec.hasExactSize && "len()" !in traitSignatures) {
                traitSignatures["len()"] = 1
                members += "$indent    public fun len(): Int"
            }
        }

        if (!isLocalItem && item.generics.params.isEmpty() && assocTypeNames.isEmpty()) {
            val receiverlessTraitFns =
                item.items
                    .filterIsInstance<TraitItem.Fn>()
                    .filter { tFn ->
                        !traitItemHasDocOnlyCfg(tFn) &&
                            tFn.sig.inputs
                                .toList()
                                .firstOrNull() !is FnArg.Receiver
                    }
            if (receiverlessTraitFns.isNotEmpty()) {
                val rawTraitIdent = item.ident.toString()
                val moduleItems = (TranslationSession.sharedModuleFile?.items ?: file.items).toList()
                val uniqueImpl =
                    moduleItems
                        .filterIsInstance<Item.Impl>()
                        .singleOrNull { implItem ->
                            !itemHasDocOnlyCfg(implItem) &&
                                implItem.generics.params.isEmpty() &&
                                implItem.traitPath
                                    ?.path
                                    ?.segments
                                    ?.last()
                                    ?.ident
                                    ?.toString() == rawTraitIdent
                        }
                if (uniqueImpl != null) {
                    val savedTraitAssoc = currentTraitAssocParamNames
                    val savedActiveTypeParams = activeTypeParamNames
                    val companionMethods =
                        try {
                            currentTraitAssocParamNames = prevTraitAssoc
                            activeTypeParamNames = prevTraitTypeParams
                            val concreteSelfType = renderType(uniqueImpl.selfType, selfTypeName = null)
                            val directOwner = extractDirectOwnerTypeName(uniqueImpl.selfType, uniqueImpl.generics)
                            val isLocalAttached =
                                directOwner != null &&
                                    directOwner in inspector.attachableTypeNames &&
                                    TranslationSession.sharedModuleFile == null
                            val implFnsByRawName =
                                uniqueImpl.items
                                    .filterIsInstance<ImplItem.Fn>()
                                    .filter { !implItemHasDocOnlyCfg(it) }
                                    .associateBy {
                                        it.sig.ident
                                            .toString()
                                            .removePrefix("r#")
                                    }
                            val companionSigs = linkedMapOf<String, Int>()
                            val implTraitPath = uniqueImpl.traitPath!!.path
                            receiverlessTraitFns.mapNotNull { tFn ->
                                val rawFnName =
                                    tFn.sig.ident
                                        .toString()
                                        .removePrefix("r#")
                                val implFn = implFnsByRawName[rawFnName] ?: return@mapNotNull null
                                val prevCompTyped = currentTypedLocals
                                val prevCompSourceParams = currentSourceParameterTypes
                                val prevCompIntoParams = currentIntoSubstitutedParams
                                val prevCompIterable = currentIterableLocals
                                val prevCompNullable = currentNullableLocals
                                val prevCompMutList = currentMutableListLocals
                                val prevCompNumKinds = currentFunctionParamNumericKinds
                                scopeBuilder.enterDef()
                                val sigHeader =
                                    try {
                                        renderFunctionSignature(
                                            sig = implFn.sig,
                                            visPrefix = "public ",
                                            openPrefix = "",
                                            selfTypeName = concreteSelfType,
                                            ownerGenerics = null,
                                            scopeSignatures = companionSigs,
                                            isInterfaceMethod = false,
                                            isCompanionMember = true,
                                            fnBlock = implFn.block,
                                        )
                                    } finally {
                                        scopeBuilder.exitDef()
                                        currentTypedLocals = prevCompTyped
                                        currentSourceParameterTypes = prevCompSourceParams
                                        currentIntoSubstitutedParams = prevCompIntoParams
                                        currentIterableLocals = prevCompIterable
                                        currentNullableLocals = prevCompNullable
                                        currentMutableListLocals = prevCompMutList
                                        currentFunctionParamNumericKinds = prevCompNumKinds
                                    }
                                        ?: return@mapNotNull null
                                val ktFnName = rustFunctionIdentToKotlin(implFn.sig.ident.toString())
                                val targetCall =
                                    if (isLocalAttached) {
                                        "$concreteSelfType.$ktFnName"
                                    } else {
                                        disambiguateReceiverlessTraitImplFnName(implTraitPath, ktFnName, uniqueImpl.selfType)
                                    }
                                val forwardArgs = extractSigHeaderForwardArgs(sigHeader)
                                val docAttrs = implFn.attrs.ifEmpty { tFn.attrs }
                                val doc = renderKDocFromAttributes(docAttrs, "$indent        ")
                                "$doc$indent        $sigHeader = $targetCall(${forwardArgs.joinToString(", ")})"
                            }
                        } finally {
                            currentTraitAssocParamNames = savedTraitAssoc
                            activeTypeParamNames = savedActiveTypeParams
                        }
                    if (companionMethods.isNotEmpty()) {
                        members +=
                            "$indent    public companion object {\n" +
                            companionMethods.joinToString("\n") +
                            "\n$indent    }"
                    }
                }
            }
        }

        if (members.isNotEmpty()) {
            sb.append(" {\n")
            sb.append(members.joinToString("\n\n"))
            sb.append('\n').append(indent).append("}")
        }
        sb.toString()
    } finally {
        currentTraitMethodRawNames = prevTraitMethods
        currentTraitAssocParamNames = prevTraitAssoc
        activeTypeParamNames = prevTraitTypeParams
    }
}

private fun extractSigHeaderForwardArgs(sigHeader: String): List<String> {
    val funIdx = sigHeader.indexOf("fun ")
    if (funIdx < 0) return emptyList()
    var i = funIdx + 4
    var angleDepth = 0
    while (i < sigHeader.length) {
        val ch = sigHeader[i]
        when {
            ch == '<' -> angleDepth++
            ch == '>' -> if (angleDepth > 0 && (i == 0 || sigHeader[i - 1] != '-')) angleDepth--
            ch == '(' && angleDepth == 0 -> break
        }
        i++
    }
    if (i >= sigHeader.length || sigHeader[i] != '(') return emptyList()
    val openIdx = i
    var parenDepth = 0
    var closeIdx = -1
    while (i < sigHeader.length) {
        when (sigHeader[i]) {
            '(' -> parenDepth++
            ')' -> {
                parenDepth--
                if (parenDepth == 0) {
                    closeIdx = i
                    break
                }
            }
        }
        i++
    }
    if (closeIdx <= openIdx) return emptyList()
    val body = sigHeader.substring(openIdx + 1, closeIdx).trim()
    if (body.isEmpty()) return emptyList()
    return PhantomRawPointerTypeStripper
        .splitTopLevelCommaList(body)
        .mapNotNull { entry ->
            entry
                .substringBefore(':')
                .trim()
                .substringAfterLast(' ')
                .takeIf { it.isNotEmpty() }
        }
}

private fun KotlinJ2KEmitter.stdlibIteratorTypeName(): String {
    val iteratorShadowed =
        "Iterator" in inspector.declaredTypeNames ||
            "Iterator" in headerContext.moduleDeclaredTypeNames ||
            extraTypePathImports.any { it.endsWith(".Iterator") || it.endsWith(".Iterator as Iterator") } ||
            inspector.allUseItems.any { useItem ->
                val leaves = mutableListOf<UseLeaf>()
                collectUseLeaves(useItem.tree, emptyList(), leaves)
                leaves.any { leaf ->
                    val name = leaf.alias ?: leaf.segments.lastOrNull()
                    name?.removePrefix("r#") == "Iterator"
                }
            }
    return if (iteratorShadowed) "kotlin.collections.Iterator" else "Iterator"
}

internal fun KotlinJ2KEmitter.renderItemTraitAlias(
    item: Item.TraitAlias,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val name = rustTypeIdentToKotlin(item.ident.toString())
    val sigKey = "type:$name"
    val seenCount = scopeSignatures[sigKey] ?: 0
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) {
        return ""
    }
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val aliasSelfType = name + renderTypeArgumentsFromGenerics(item.generics)
    val typeParams = renderTypeParameters(item.generics, selfTypeName = aliasSelfType)
    val prevBounded = currentBoundedTypeName
    currentBoundedTypeName = aliasSelfType
    val bounds =
        try {
            item.bounds
                .toList()
                .mapNotNull { renderTypeParamBound(it, selfTypeName = aliasSelfType) }
                .distinct()
        } finally {
            currentBoundedTypeName = prevBounded
        }
    sb
        .append(indent)
        .append(vis)
        .append("interface ")
        .append(name)
        .append(typeParams)
    if (bounds.isNotEmpty()) {
        sb.append(" : ").append(bounds.joinToString(", "))
    }
    return sb.toString()
}

internal fun KotlinJ2KEmitter.extractFnTraitClosureReceiver(item: Item.Impl): Pair<String, String>? {
    val selfPath = unwrapSynType(item.selfType) as? SynType.Path ?: return null
    if (selfPath.qself != null) return null
    val selfSegs = selfPath.path.segments.toList()
    if (selfSegs.size != 1 || selfSegs[0].arguments !is PathArguments.None) return null
    val selfParamName = rustTypeIdentToKotlin(selfSegs[0].ident.toString())
    val ownerTypeParams = collectGenericTypeParamNames(item.generics)
    if (selfParamName !in ownerTypeParams) return null

    val candidateBounds = mutableListOf<TypeParamBound>()
    for (gp in item.generics.params.toList()) {
        if (gp is GenericParam.TypeParam && rustTypeIdentToKotlin(gp.ident.toString()) == selfParamName) {
            candidateBounds.addAll(gp.bounds.toList())
        }
    }
    val wherePreds = item.generics.whereClause?.predicates
    for (pred in wherePreds?.toList().orEmpty()) {
        if (pred is WherePredicate.TypePredicate) {
            val predTy = unwrapSynType(pred.boundedTy) as? SynType.Path
            if (predTy != null && predTy.qself == null && predTy.path.segments.len() == 1) {
                val firstSeg = predTy.path.segments.first()
                val rawPred = firstSeg?.ident?.toString()
                val predIdent = rawPred?.let(::rustTypeIdentToKotlin)
                if (predIdent == selfParamName) {
                    candidateBounds.addAll(pred.bounds.toList())
                }
            }
        }
    }
    for (bound in candidateBounds) {
        if (bound is TypeParamBound.Trait) {
            val lastSeg = bound.path.segments.last() ?: continue
            val boundIdent = lastSeg.ident.toString()
            if ((boundIdent == "Fn" || boundIdent == "FnMut" || boundIdent == "FnOnce") &&
                lastSeg.arguments is PathArguments.Parenthesized
            ) {
                val fnType = renderPathAsType(bound.path, selfTypeName = null)
                return selfParamName to fnType
            }
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.renderStandaloneItemImpl(
    item: Item.Impl,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val implComments =
        (renderLeadingTrivia(item, indent) + renderKDocFromAttributes(item.attrs, indent))
            .trimEnd()
    val closureRecv = extractFnTraitClosureReceiver(item)
    val prevSubst = substitutedTypeParamTypes
    val prevStandaloneTraitPath = currentStandaloneImplTraitPath
    val prevStandaloneSelfTy = currentStandaloneImplSelfSynType
    val prevAssocTypes = currentImplAssocTypes
    if (closureRecv != null) {
        substitutedTypeParamTypes = prevSubst + (closureRecv.first to closureRecv.second)
    }
    currentStandaloneImplTraitPath = item.traitPath?.path
    currentStandaloneImplSelfSynType = item.selfType
    currentImplAssocTypes =
        buildMap {
            putAll(prevAssocTypes)
            for (implItem in item.items) {
                if (implItem is ImplItem.AssocType) {
                    put(implItem.ident.toString(), implItem.ty)
                }
            }
        }
    return try {
        val selfTypeRendered =
            if (closureRecv != null) {
                "(${closureRecv.second})"
            } else {
                renderType(item.selfType, selfTypeName = null)
            }
        val standaloneTraitKt =
            item.traitPath
                ?.path
                ?.segments
                ?.last()
                ?.ident
                ?.toString()
                ?.let(::rustTypeIdentToKotlin)
        if (standaloneTraitKt != null && lookupDeclaredTraitAst(standaloneTraitKt) != null) {
            val implTypeParams = collectGenericTypeParamNames(item.generics)
            if (selfTypeRendered in implTypeParams) {
                val selfBounds = mutableListOf<TypeParamBound>()
                for (gp in item.generics.params.toList()) {
                    if (gp is GenericParam.TypeParam && rustTypeIdentToKotlin(gp.ident.toString()) == selfTypeRendered) {
                        selfBounds.addAll(gp.bounds.toList())
                    }
                }
                for (pred in item.generics.whereClause
                    ?.predicates
                    ?.toList()
                    .orEmpty()) {
                    if (pred is WherePredicate.TypePredicate && renderType(pred.boundedTy, null) == selfTypeRendered) {
                        selfBounds.addAll(pred.bounds.toList())
                    }
                }
                val alreadyImplementsTrait =
                    selfBounds.any { b ->
                        b is TypeParamBound.Trait &&
                            b.maybe == null &&
                            b.path.segments
                                .last()
                                ?.ident
                                ?.toString()
                                ?.let(::rustTypeIdentToKotlin) == standaloneTraitKt
                    }
                if (alreadyImplementsTrait) {
                    return ""
                }
            }
        }
        val scopeSigs = scopeSignatures
        val renderedMembers = mutableListOf<String>()
        for (implItem in item.items) {
            if (implItemHasDocOnlyCfg(implItem)) continue
            when (implItem) {
                is ImplItem.Const -> {
                    renderedMembers +=
                        renderImplItemConst(
                            item = implItem,
                            indent = indent,
                            selfTypeName = selfTypeRendered,
                            scopeSignatures = scopeSigs,
                            isLocalItem = isLocalItem,
                        )
                }
                is ImplItem.Fn -> {
                    val rendered =
                        renderImplItemFn(
                            item = implItem,
                            indent = indent,
                            selfTypeName = selfTypeRendered,
                            ownerGenerics = item.generics,
                            implGenerics = item.generics,
                            scopeSignatures = scopeSigs,
                            asTopLevelExtension = true,
                            isLocalItem = isLocalItem,
                        )
                    if (rendered.isNotBlank()) renderedMembers += rendered
                }
                is ImplItem.AssocType -> {}
                is ImplItem.Macro -> {
                    renderedMembers +=
                        renderImplOrTraitMacroMembers(
                            mac = implItem.mac,
                            indent = indent,
                            selfTypeName = selfTypeRendered,
                            ownerGenerics = item.generics,
                            scopeSignatures = scopeSigs,
                            asTopLevelExtension = true,
                            isInterface = false,
                            isLocalItem = isLocalItem,
                        )
                }
                is ImplItem.Verbatim -> {}
            }
        }
        if (implComments.isNotBlank()) {
            if (renderedMembers.isNotEmpty()) {
                renderedMembers[0] = "$implComments\n${renderedMembers[0]}"
            } else {
                renderedMembers += implComments
            }
        }
        renderedMembers.joinToString("\n\n")
    } finally {
        currentImplAssocTypes = prevAssocTypes
        currentStandaloneImplTraitPath = prevStandaloneTraitPath
        currentStandaloneImplSelfSynType = prevStandaloneSelfTy
        substitutedTypeParamTypes = prevSubst
    }
}

internal fun KotlinJ2KEmitter.renderImplItemConst(
    item: ImplItem.Const,
    indent: String,
    selfTypeName: String?,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean = false,
): String {
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val name = disambiguateName(rustConstIdentToKotlin(item.ident.toString()), scopeSignatures)
    val ty = renderType(item.ty, selfTypeName)
    val numKind = resolveNumericKind(ty)
    val expr = renderTypedNumericInitializer(item.expr, numKind, indent, selfTypeName)
    sb
        .append(indent)
        .append(vis)
        .append("val ")
        .append(name)
        .append(": ")
        .append(ty)
        .append(" = ")
        .append(expr)
    return sb.toString()
}

internal fun KotlinJ2KEmitter.renderImplItemFn(
    item: ImplItem.Fn,
    indent: String,
    selfTypeName: String?,
    ownerGenerics: Generics?,
    implGenerics: Generics? = null,
    scopeSignatures: MutableMap<String, Int>,
    asTopLevelExtension: Boolean = false,
    isCompanionMember: Boolean = false,
    isLocalItem: Boolean = false,
): String {
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val useFinalAbiLayout = headerContext.rawAbiMode || headerContext.finalClasses
    val openMod =
        if (!useFinalAbiLayout &&
            !asTopLevelExtension &&
            !isCompanionMember &&
            !isLocalItem &&
            !currentAttachedImplIsObjectOwner &&
            indent.isNotEmpty()
        ) {
            "open "
        } else {
            ""
        }
    val prevClosureLabel = currentClosureLabel
    val prevEnclosingFn = currentEnclosingFunctionName
    val prevEnclosingRet = currentEnclosingReturnRenderedType
    val prevEnclosingSynRet = currentEnclosingReturnSynType
    val prevEnclosingFmtResult = currentEnclosingReturnsFmtResult
    val prevTailExpectsUnitResultOk = currentTailExpectsUnitResultOk
    val prevMutableListLocals = currentMutableListLocals
    val prevDequeLocals = currentDequeLocals
    val prevIterableLocals = currentIterableLocals
    val prevSourceParameterTypes = currentSourceParameterTypes
    val prevTypedLocals = currentTypedLocals
    val prevWitnessedBounds = currentWitnessedTraitBounds
    val prevTypeParamBounds = currentTypeParamTraitBounds
    val prevHasherState = currentHasherStateParam
    val prevNullableLocals = currentNullableLocals
    val prevSmartCastLocals = currentSmartCastNonNullLocals
    val prevTypeParams = activeTypeParamNames
    val prevSubst = substitutedTypeParamTypes
    val prevIntoSubstTps = currentIntoSubstitutedTypeParams
    val prevIntoSubstParams = currentIntoSubstitutedParams
    val prevWitnesses = currentTypeParamWitnessCalls
    val prevSharedBorrowRecv = currentReceiverIsSharedBorrow
    val prevMutBorrowRecv = currentReceiverIsMutBorrow
    currentClosureLabel = null
    currentEnclosingFunctionName = rustFunctionIdentToKotlin(item.sig.ident.toString())
    currentEnclosingReturnRenderedType = null
    currentEnclosingReturnSynType = null
    val firstReceiverRef =
        (
            item.sig.inputs
                .toList()
                .firstOrNull() as? FnArg.Receiver
        )?.kind as? ReceiverKind.Reference
    currentReceiverIsSharedBorrow = firstReceiverRef?.let { it.mutability == null } == true
    currentReceiverIsMutBorrow = firstReceiverRef?.let { it.mutability != null } == true
    currentMutableListLocals = emptySet()
    currentDequeLocals = emptySet()
    currentIterableLocals = emptySet()
    currentSourceParameterTypes = emptyMap()
    currentTypedLocals = emptyMap()
    currentWitnessedTraitBounds = emptyMap()
    currentTypeParamTraitBounds = emptyMap()
    currentHasherStateParam = null
    currentNullableLocals = emptySet()
    currentSmartCastNonNullLocals = emptySet()
    currentIntoSubstitutedParams = emptySet()
    val intoSubstTps = linkedSetOf<String>()
    val implFnSubsts =
        buildMap {
            if (implGenerics != null) {
                val implGenParams = implGenerics.params.toList()
                putAll(extractFnTraitSubstitutions(implGenParams, implGenerics.whereClause, selfTypeName, intoSubstTps))
            }
            val itemGenerics = item.sig.generics
            val itemGenParams = itemGenerics.params.toList()
            putAll(extractFnTraitSubstitutions(itemGenParams, itemGenerics.whereClause, selfTypeName, intoSubstTps))
        }
    if (implFnSubsts.isNotEmpty()) {
        substitutedTypeParamTypes = prevSubst + implFnSubsts
    }
    currentIntoSubstitutedTypeParams = prevIntoSubstTps + intoSubstTps
    val combinedActiveParams =
        buildSet {
            addAll(activeTypeParamNames)
            addAll(collectGenericTypeParamNames(ownerGenerics))
            addAll(collectGenericTypeParamNames(implGenerics))
            addAll(collectGenericTypeParamNames(item.sig.generics))
        }
    activeTypeParamNames = combinedActiveParams - substitutedTypeParamTypes.keys
    scopeBuilder.enterDef()
    try {
        val activeTraitLast =
            (currentAttachedImplTraitPath ?: currentStandaloneImplTraitPath)
                ?.segments
                ?.last()
                ?.ident
                ?.toString()
        val inputsList = item.sig.inputs.toList()
        if (activeTraitLast == "Hash" &&
            item.sig.ident.toString() == "hash" &&
            inputsList.size == 2 &&
            inputsList[0] is FnArg.Receiver &&
            inputsList[1] is FnArg.Typed &&
            !asTopLevelExtension &&
            !isCompanionMember
        ) {
            val hashSigKey = "hashCode()"
            val seen = scopeSignatures[hashSigKey] ?: 0
            scopeSignatures[hashSigKey] = seen + 1
            if (seen > 0) return ""
            val stateArg = inputsList[1] as FnArg.Typed
            val rawState = extractRawParamIdent(stateArg.patType.pat) ?: "state"
            val stateKt =
                scopeBuilder
                    .bindLocal(
                        rawName = rawState,
                        baseKotlinName = rustValueIdentToKotlin(rawState),
                        span = stateArg.patType.pat.span(),
                        assignCount = AssignCount.Any,
                    ).kotlinName
            currentHasherStateParam = stateKt
            currentEnclosingReturnRenderedType = "Int"
            val sb = StringBuilder()
            sb.append(renderLeadingTrivia(item, indent))
            sb.append(renderKDocFromAttributes(item.attrs, indent))
            val rawBody =
                renderBlock(
                    item.block,
                    indent,
                    selfTypeName = selfTypeName,
                    returnType = null,
                )
            val body =
                when {
                    containsWholeIdentifier(rawBody, stateKt) -> {
                        val openPrefix = "{\n"
                        val closeSuffix = "\n$indent}"
                        if (rawBody.startsWith(openPrefix) && rawBody.endsWith(closeSuffix)) {
                            val inner = rawBody.substring(openPrefix.length, rawBody.length - closeSuffix.length)
                            "{\n$indent    var $stateKt = 0\n$inner\n$indent    return $stateKt\n$indent}"
                        } else {
                            "{\n$indent    var $stateKt = 0\n$indent    return $stateKt\n$indent}"
                        }
                    }
                    rawBody.contains("error(") -> rawBody
                    rawBody == "{}" && (headerContext.isBatchCrateOutput || TranslationSession.finalStructs) -> "= 0"
                    rawBody == "{}" -> "{\n$indent    return 0\n$indent}"
                    rawBody.endsWith("\n$indent}") ->
                        rawBody.substring(0, rawBody.length - "\n$indent}".length) + "\n$indent    return 0\n$indent}"
                    else -> "{\n$indent    return 0\n$indent}"
                }
            sb.append(indent).append("${vis}override fun hashCode(): Int ").append(body)
            return sb.toString()
        }
        val sigHeader =
            renderFunctionSignature(
                sig = item.sig,
                visPrefix = vis,
                openPrefix = openMod,
                selfTypeName = selfTypeName,
                ownerGenerics = ownerGenerics,
                implGenerics = implGenerics,
                scopeSignatures = scopeSignatures,
                isInterfaceMethod = false,
                asTopLevelExtension = asTopLevelExtension,
                isCompanionMember = isCompanionMember,
                fnBlock = item.block,
            ) ?: return ""
        val sb = StringBuilder()
        sb.append(renderLeadingTrivia(item, indent))
        sb.append(renderKDocFromAttributes(item.attrs, indent))
        if (!isLocalItem && hasTestAttribute(item.attrs)) {
            sb.append(indent).append("@kotlin.test.Test\n")
        }
        val retType = extractReturnSynType(item.sig.output)
        val panicExpectation = if (!isLocalItem && hasTestAttribute(item.attrs)) extractRustPanicExpectation(item.attrs) else null
        val bodyIndent = if (panicExpectation == null) indent else "$indent    "
        val shadows = computeReassignedParamShadows(item.sig, item.block, "$bodyIndent    ")
        val accessorFields =
            if (retType is SynType.Reference && retType.mutability != null) {
                selfTypeName?.substringBefore('<')?.let { owner ->
                    mutableReferenceProjection(owner, item.sig.ident.toString(), linkedSetOf())
                }
            } else {
                null
            }
        val rawStorageProjection =
            if (retType is SynType.Ptr && selfTypeName != null) {
                val owner = selfTypeName.substringBefore('<')
                val declaration =
                    file.items.filterIsInstance<Item.Struct>().singleOrNull {
                        rustTypeIdentToKotlin(it.ident.toString()) == owner
                    }
                val field = declaration?.let { sourceTransparentField(it, selfTypeName) }
                val projection = mutableReferenceProjection(owner, item.sig.ident.toString(), linkedSetOf())
                field != null && projection == listOf(field.first) &&
                    renderType(retType.elem, selfTypeName) == field.second
            } else {
                false
            }
        val body =
            if (rawStorageProjection) {
                val retPtr = retType as? SynType.Ptr
                if (retPtr != null && retPtr.mutability is io.github.kotlinmania.syn.PointerMutability.Const) {
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.ConstPtr")
                    "{\n$bodyIndent    return ConstPtr(this.fieldStorage.rawAddress)\n$bodyIndent}"
                } else {
                    "{\n$bodyIndent    return this.fieldStorage\n$bodyIndent}"
                }
            } else if (accessorFields != null && mutableReferencePointeeType(currentEnclosingReturnRenderedType) != null) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.RustMutableReference")
                "{\n$bodyIndent    return RustMutableReference(this.fieldStorage, this.fieldLayout)\n$bodyIndent}"
            } else if (accessorFields != null) {
                val owner = "this" + accessorFields.dropLast(1).joinToString("") { ".$it" }
                "{\n$bodyIndent    return $owner::${accessorFields.last()}\n$bodyIndent}"
            } else {
                renderBlock(
                    item.block,
                    bodyIndent,
                    selfTypeName = selfTypeName,
                    returnType = retType,
                    leadingInjectedLines = shadows,
                    allowExpressionBody = panicExpectation == null,
                )
            }
        sb
            .append(indent)
            .append(sigHeader)
            .append(" ")
            .append(if (panicExpectation == null) body else wrapRustPanicTestBody(body, indent, panicExpectation))
        return sb.toString()
    } finally {
        scopeBuilder.exitDef()
        currentClosureLabel = prevClosureLabel
        currentEnclosingFunctionName = prevEnclosingFn
        currentEnclosingReturnRenderedType = prevEnclosingRet
        currentEnclosingReturnSynType = prevEnclosingSynRet
        currentEnclosingReturnsFmtResult = prevEnclosingFmtResult
        currentTailExpectsUnitResultOk = prevTailExpectsUnitResultOk
        currentMutableListLocals = prevMutableListLocals
        currentDequeLocals = prevDequeLocals
        currentIterableLocals = prevIterableLocals
        currentSourceParameterTypes = prevSourceParameterTypes
        currentTypedLocals = prevTypedLocals
        currentWitnessedTraitBounds = prevWitnessedBounds
        currentTypeParamTraitBounds = prevTypeParamBounds
        currentHasherStateParam = prevHasherState
        currentNullableLocals = prevNullableLocals
        currentSmartCastNonNullLocals = prevSmartCastLocals
        activeTypeParamNames = prevTypeParams
        substitutedTypeParamTypes = prevSubst
        currentIntoSubstitutedTypeParams = prevIntoSubstTps
        currentIntoSubstitutedParams = prevIntoSubstParams
        currentTypeParamWitnessCalls = prevWitnesses
        currentReceiverIsSharedBorrow = prevSharedBorrowRecv
        currentReceiverIsMutBorrow = prevMutBorrowRecv
    }
}

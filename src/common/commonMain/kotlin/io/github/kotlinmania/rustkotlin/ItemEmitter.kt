package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Fields
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.MetaParse
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.ReceiverKind
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.Variant
import io.github.kotlinmania.syn.Visibility
import io.github.kotlinmania.syn.WherePredicate
import io.github.kotlinmania.syn.parse2

internal data class RustPanicExpectation(
    val message: String?,
)

internal fun extractRustPanicExpectation(attrs: List<Attribute>): RustPanicExpectation? {
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

internal fun wrapRustPanicTestBody(
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

internal fun reserveUniqueSignature(
    scopeSignatures: MutableMap<String, Int>,
    key: String,
): Boolean {
    val seenCount = scopeSignatures[key] ?: 0
    scopeSignatures[key] = seenCount + 1
    return seenCount == 0
}

internal fun hasDeriveTrait(
    attrs: List<Attribute>,
    traitName: String,
): Boolean =
    attrs.any { attribute ->
        val derive = attribute.meta as? Meta.List
        derive != null &&
            derive.path.segments
                .last()
                ?.ident
                .toString() in setOf("derive", "derive_const") &&
            derive.tokens
                .clone()
                .toList()
                .any { it is TokenTree.Ident && it.value.toString() == traitName }
    }

private fun KotlinJ2KEmitter.hasStandaloneCompanionExtensionImpl(typeName: String): Boolean {
    val itemsToCheck = TranslationSession.sharedModuleFile?.items.orEmpty() + file.items
    for (item in itemsToCheck) {
        if (item !is Item.Impl || itemHasDocOnlyCfg(item) || item.traitPath != null) continue
        if (extractAttachableDirectOwnerTypeName(item, itemsToCheck) != null) continue
        val selfTy = unwrapSynType(item.selfType)
        val selfBase =
            (selfTy as? SynType.Path)
                ?.takeIf { it.qself == null && it.path.segments.len() == 1 }
                ?.path
                ?.segments
                ?.first()
                ?.ident
                ?.toString()
                ?.let(::rustTypeIdentToKotlin)
        if (selfBase != typeName) continue
        if (item.items.any { m -> m is ImplItem.Fn && !implItemHasDocOnlyCfg(m) && m.sig.inputs.firstOrNull() !is FnArg.Receiver }) {
            return true
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.beginItemHeaderAndVisibility(
    leadingTrivia: String,
    attrs: List<Attribute>,
    vis: Visibility,
    indent: String,
    isLocalItem: Boolean = false,
): Pair<StringBuilder, String> {
    val sb = StringBuilder()
    sb.append(leadingTrivia)
    sb.append(renderKDocFromAttributes(attrs, indent))
    val visStr = if (isLocalItem) "" else renderVisibility(vis)
    return sb to visStr
}

internal inline fun <R> KotlinJ2KEmitter.withGenericParamScope(
    generics: Generics,
    block: () -> R,
): R {
    val prevTypeParams = activeTypeParamNames
    val prevConstParams = activeConstParamNames
    val prevTraitAssoc = currentTraitAssocParamNames
    activeTypeParamNames = prevTypeParams + collectGenericTypeParamNames(generics)
    activeConstParamNames = prevConstParams + collectGenericConstParamNames(generics)
    return try {
        block()
    } finally {
        activeConstParamNames = prevConstParams
        activeTypeParamNames = prevTypeParams
        currentTraitAssocParamNames = prevTraitAssoc
    }
}

internal inline fun <R> KotlinJ2KEmitter.withFunctionRenderScope(
    sig: Signature,
    selfTypeName: String?,
    ownerGenerics: Generics? = null,
    implGenerics: Generics? = null,
    updateEnclosingFunctionName: Boolean = true,
    resetHasherState: Boolean = false,
    block: () -> R,
): R {
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
    val prevConstParams = activeConstParamNames
    val prevTraitAssoc = currentTraitAssocParamNames
    val prevSubst = substitutedTypeParamTypes
    val prevIntoSubstTps = currentIntoSubstitutedTypeParams
    val prevIntoSubstParams = currentIntoSubstitutedParams
    val prevWitnesses = currentTypeParamWitnessCalls
    val prevSharedBorrowRecv = currentReceiverIsSharedBorrow
    val prevMutBorrowRecv = currentReceiverIsMutBorrow
    currentClosureLabel = null
    if (updateEnclosingFunctionName) {
        currentEnclosingFunctionName = rustFunctionIdentToKotlin(sig.ident.toString())
    }
    currentEnclosingReturnRenderedType = null
    currentEnclosingReturnSynType = null
    val firstReceiverRef =
        (
            sig.inputs
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
    if (resetHasherState) {
        currentHasherStateParam = null
    }
    currentNullableLocals = emptySet()
    currentSmartCastNonNullLocals = emptySet()
    currentIntoSubstitutedParams = emptySet()
    val intoSubstTps = linkedSetOf<String>()
    val mergedSubsts =
        buildMap {
            if (implGenerics != null) {
                putAll(
                    extractFnTraitSubstitutions(
                        implGenerics.params.toList(),
                        implGenerics.whereClause,
                        selfTypeName,
                        intoSubstTps,
                    ),
                )
            }
            val sigGenerics = sig.generics
            putAll(
                extractFnTraitSubstitutions(
                    sigGenerics.params.toList(),
                    sigGenerics.whereClause,
                    selfTypeName,
                    intoSubstTps,
                ),
            )
        }
    if (mergedSubsts.isNotEmpty()) {
        substitutedTypeParamTypes = prevSubst + mergedSubsts
    }
    currentIntoSubstitutedTypeParams = prevIntoSubstTps + intoSubstTps
    val combinedTypeParams =
        buildSet {
            addAll(prevTypeParams)
            addAll(collectGenericTypeParamNames(ownerGenerics))
            addAll(collectGenericTypeParamNames(implGenerics))
            addAll(collectGenericTypeParamNames(sig.generics))
        }
    activeTypeParamNames = combinedTypeParams - substitutedTypeParamTypes.keys
    activeConstParamNames =
        prevConstParams +
        collectGenericConstParamNames(ownerGenerics) +
        collectGenericConstParamNames(implGenerics) +
        collectGenericConstParamNames(sig.generics)
    scopeBuilder.enterDef()
    return try {
        block()
    } finally {
        scopeBuilder.exitDef()
        currentClosureLabel = prevClosureLabel
        if (updateEnclosingFunctionName) {
            currentEnclosingFunctionName = prevEnclosingFn
        }
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
        if (resetHasherState) {
            currentHasherStateParam = prevHasherState
        }
        currentNullableLocals = prevNullableLocals
        currentSmartCastNonNullLocals = prevSmartCastLocals
        activeConstParamNames = prevConstParams
        activeTypeParamNames = prevTypeParams
        currentTraitAssocParamNames = prevTraitAssoc
        substitutedTypeParamTypes = prevSubst
        currentIntoSubstitutedTypeParams = prevIntoSubstTps
        currentIntoSubstitutedParams = prevIntoSubstParams
        currentTypeParamWitnessCalls = prevWitnesses
        currentReceiverIsSharedBorrow = prevSharedBorrowRecv
        currentReceiverIsMutBorrow = prevMutBorrowRecv
    }
}

private fun KotlinJ2KEmitter.renderConstOrStaticItem(
    leadingTrivia: String,
    attrs: List<Attribute>,
    vis: Visibility,
    rawIdent: String,
    ty: SynType,
    expr: Expr,
    declKeyword: String,
    indent: String,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean,
): String {
    val (sb, visStr) = beginItemHeaderAndVisibility(leadingTrivia, attrs, vis, indent, isLocalItem)
    val cleanRaw = rawIdent.removePrefix("r#")
    val baseName =
        if (!isLocalItem) {
            inspector.declaredConstNames[cleanRaw] ?: rustConstIdentToKotlin(rawIdent)
        } else {
            rustConstIdentToKotlin(rawIdent)
        }
    val name = disambiguateName(baseName, scopeSignatures)
    val renderedTy = renderType(ty, selfTypeName = null)
    val numKind = resolveNumericKind(renderedTy)
    val prevParseTarget = currentExpectedParseTargetType
    currentExpectedParseTargetType = renderedTy
    val initExpr =
        try {
            renderTypedNumericInitializer(expr, numKind, indent, selfTypeName = null)
        } finally {
            currentExpectedParseTargetType = prevParseTarget
        }
    sb
        .append(indent)
        .append(visStr)
        .append(declKeyword)
        .append(name)
        .append(": ")
        .append(renderedTy)
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
    return renderConstOrStaticItem(
        leadingTrivia = renderLeadingTrivia(item, indent),
        attrs = item.attrs,
        vis = item.vis,
        rawIdent = rawIdent,
        ty = item.ty,
        expr = item.expr,
        declKeyword = "val ",
        indent = indent,
        scopeSignatures = scopeSignatures,
        isLocalItem = isLocalItem,
    )
}

internal fun KotlinJ2KEmitter.renderItemStatic(
    item: Item.Static,
    indent: String,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean = false,
): String =
    renderConstOrStaticItem(
        leadingTrivia = renderLeadingTrivia(item, indent),
        attrs = item.attrs,
        vis = item.vis,
        rawIdent = item.ident.toString(),
        ty = item.ty,
        expr = item.expr,
        declKeyword = "var ",
        indent = indent,
        scopeSignatures = scopeSignatures,
        isLocalItem = isLocalItem,
    )

internal fun KotlinJ2KEmitter.renderItemType(
    item: Item.ItemType,
    indent: String,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean = false,
): String {
    if (isLocalItem || isExternalIdentityTypeReexportAlias(item, headerContext.selfCrateAliases, inspector.localInlineModuleNames)) {
        return ""
    }
    val name = rustTypeIdentToKotlin(item.ident.toString())
    if (!reserveUniqueSignature(scopeSignatures, "typealias:$name")) {
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
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.UnresolvedTypeLayout")
        sb.append("${indent}${vis}class $name$typeParams(fieldStorage: MutPtr<$fieldType>, fieldLayout: TypeLayout<$fieldType> = UnresolvedTypeLayout()) : $target(fieldStorage, fieldLayout) {\n")
        sb.append("$indent    public constructor(${field.first}: $fieldType, fieldLayout: TypeLayout<$fieldType> = UnresolvedTypeLayout()) : this(allocateNativeCell(${field.first}, fieldLayout), fieldLayout)\n")
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

internal fun KotlinJ2KEmitter.renderItemFn(
    item: Item.Fn,
    indent: String,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean = false,
): String {
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    return withFunctionRenderScope(
        sig = item.sig,
        selfTypeName = null,
    ) {
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
            ) ?: return@withFunctionRenderScope ""
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
        val shadows = computeReassignedParamShadows(item.sig, fnBlock, "$bodyIndent    ", selfTypeName = null)
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
        sb.toString()
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
        NumericKind.F16 -> return "F16()"
        NumericKind.F32 -> return "0.0f"
        NumericKind.F64 -> return "0.0"
        NumericKind.F128 -> return "F128()"
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
    if (unsignedStripped != null && isDecimalOrHexLiteralDigits(unsignedStripped)) {
        return unsignedStripped
    }
    if (isDecimalOrHexLiteralDigits(trimmed)) {
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
        val useFinalAbiLayout = headerContext.rawAbiMode || headerContext.finalClasses
        val usesErasedConstParam =
            !useFinalAbiLayout &&
                activeConstParamNames.isNotEmpty() &&
                collectUsedRawIdentsInExpr(unwrapped.len).any {
                    it in activeConstParamNames || rustValueIdentToKotlin(it) in activeConstParamNames
                }
        if (usesErasedConstParam) {
            if (cleanType in PRIMITIVE_ARRAY_TYPES) {
                return "$cleanType(0)"
            }
            if (cleanType.startsWith("List<") && cleanType.endsWith(">")) {
                return "emptyList()"
            }
        }
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
    val usesErasedConstParamArray =
        unwrapped is SynType.Array &&
            !(headerContext.rawAbiMode || headerContext.finalClasses) &&
            activeConstParamNames.isNotEmpty() &&
            collectUsedRawIdentsInExpr(unwrapped.len).any {
                it in activeConstParamNames || rustValueIdentToKotlin(it) in activeConstParamNames
            }
    if (unwrapped is SynType.Array && !usesErasedConstParamArray && cleanType.startsWith("List<") && cleanType.endsWith(">")) {
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

private fun KotlinJ2KEmitter.buildFieldEqualsAndHashCodeMethods(
    typeCheckTarget: String,
    fields: List<Pair<String, String>>,
    memberIndent: String,
): List<String> {
    val eqLines = mutableListOf<String>()
    eqLines += "${memberIndent}override fun equals(other: Any?): Boolean {"
    eqLines += "$memberIndent    if (this === other) return true"
    eqLines += "$memberIndent    if (other !is $typeCheckTarget) return false"
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
    } else {
        val firstHash =
            if (isPrimitiveArrayRenderedType(fields[0].second)) {
                "this.${fields[0].first}.contentHashCode()"
            } else {
                "this.${fields[0].first}.hashCode()"
            }
        if (fields.size == 1) {
            hashLines += "${memberIndent}override fun hashCode(): Int = $firstHash"
        } else {
            hashLines += "${memberIndent}override fun hashCode(): Int {"
            hashLines += "$memberIndent    var result = $firstHash"
            for (i in 1 until fields.size) {
                val (fName, fType) = fields[i]
                val fHash =
                    if (isPrimitiveArrayRenderedType(fType)) {
                        "this.$fName.contentHashCode()"
                    } else {
                        "this.$fName.hashCode()"
                    }
                hashLines += "$memberIndent    result = 31 * result + $fHash"
            }
            hashLines += "$memberIndent    return result"
            hashLines += "$memberIndent}"
        }
    }
    return listOf(eqLines.joinToString("\n"), hashLines.joinToString("\n"))
}

internal fun KotlinJ2KEmitter.buildArrayAwareDataClassMethods(
    typeName: String,
    fields: List<Pair<String, String>>,
    memberIndent: String,
): List<String> = buildFieldEqualsAndHashCodeMethods(typeName, fields, memberIndent)

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

internal fun collectUnboundedGenericTypeParamNames(
    generics: Generics,
    renamedParams: Map<String, String> = emptyMap(),
): Set<String> {
    val boundedInWhere = hashSetOf<String>()
    generics.whereClause?.predicates?.toList()?.forEach { pred ->
        if (pred is WherePredicate.TypePredicate && hasEmittedKotlinTraitBound(pred.bounds.toList())) {
            val ty = pred.boundedTy
            if (ty is SynType.Path && ty.qself == null && ty.path.segments.size == 1) {
                ty.path.segments
                    .first()
                    ?.ident
                    ?.toString()
                    ?.let {
                        val ktName = rustTypeIdentToKotlin(it)
                        boundedInWhere += (renamedParams[ktName] ?: ktName)
                    }
            }
        }
    }
    return generics.params
        .toList()
        .mapNotNull { gp ->
            if (gp is GenericParam.TypeParam && !hasEmittedKotlinTraitBound(gp.bounds.toList())) {
                val rawKtName = rustTypeIdentToKotlin(gp.ident.toString())
                val ktName = renamedParams[rawKtName] ?: rawKtName
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
    result.addAll(
        buildFieldEqualsAndHashCodeMethods(
            typeCheckTarget = "$typeName$starSuffix",
            fields = specs.map { it.name to it.renderedType },
            memberIndent = memberIndent,
        ),
    )
    return result
}

private fun KotlinJ2KEmitter.populateSwiftSafeCtorAndDataMethods(
    specs: List<SwiftSafeFieldSpec>,
    ctorParams: MutableList<String>,
    swiftSafePropsOut: MutableList<String>,
    extraMembersOut: MutableList<String>,
    extraCompanionMembersOut: MutableList<String>,
    attachedImpls: List<Item.Impl>,
    typeName: String,
    typeParamCount: Int,
    typeParams: String,
    selfTypeWithGenerics: String,
    wouldHaveBeenDataClass: Boolean,
    indent: String,
) {
    ctorParams.clear()
    for (spec in specs) {
        if (spec.vis == "public " && spec.isConcreteGenericForSwift) {
            ctorParams += "${spec.name}CtorArg: ${spec.renderedType}"
            swiftSafePropsOut +=
                "${spec.leadingHeader}$indent    public var ${spec.name}: ${spec.renderedType} = ${spec.name}CtorArg\n$indent        internal set"
        } else {
            ctorParams += "${spec.docPrefix}${spec.vis}var ${spec.name}: ${spec.renderedType}"
        }
    }
    if (specs.isNotEmpty() && specs.all { it.defaultExpr != null }) {
        val defaultCtorArgs =
            specs.joinToString(",\n") { spec ->
                val paramKey = if (spec.vis == "public " && spec.isConcreteGenericForSwift) "${spec.name}CtorArg" else spec.name
                "$indent        $paramKey = ${spec.defaultExpr}"
            }
        extraMembersOut += "$indent    public constructor() : this(\n$defaultCtorArgs,\n$indent    )"
    }
    extraCompanionMembersOut += renderSwiftSafeCompanionInvoke(typeParams, selfTypeWithGenerics, specs, "$indent    ")
    if (wouldHaveBeenDataClass) {
        extraMembersOut.addAll(
            buildSwiftSafeSyntheticDataMethods(
                typeName = typeName,
                typeParamCount = typeParamCount,
                selfTypeWithGenerics = selfTypeWithGenerics,
                specs = specs,
                includeCopyAndComponents = true,
                hasCopyCollision = attachedImplDefinesInstanceMethod(attachedImpls, "copy"),
                memberIndent = "$indent    ",
            ),
        )
    }
}

private fun KotlinJ2KEmitter.appendFormattedCtorParams(
    sb: StringBuilder,
    indent: String,
    ctorHeaderPrefix: String,
    ctorParams: List<String>,
) {
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
    val typeName = effectiveStructKotlinName(rustTypeIdentToKotlin(item.ident.toString()))
    if (!reserveUniqueSignature(scopeSignatures, "type:$typeName")) {
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
    val (sb, vis) = beginItemHeaderAndVisibility(renderLeadingTrivia(item, indent), item.attrs, item.vis, indent, isLocalItem)
    val selfTypeWithGenerics = typeName + renderTypeArgumentsFromGenerics(item.generics, ownerTypeSimpleName = typeName)
    val unboundedOwnerTypeParams = collectUnboundedGenericTypeParamNames(item.generics)
    val prevStructTypeParams = activeTypeParamNames
    val prevStructConstParams = activeConstParamNames
    val prevStructTraitAssoc = currentTraitAssocParamNames
    activeTypeParamNames = activeTypeParamNames + collectGenericTypeParamNames(item.generics)
    activeConstParamNames = activeConstParamNames + collectGenericConstParamNames(item.generics)
    currentTraitAssocParamNames =
        currentTraitAssocParamNames + sourceAssociatedParameters(item.generics, typeName).map { it.associated }
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
        superTraits.addAll(renderDistinctSuperTraits(effectiveAttachedImpls, selfTypeWithGenerics, ownerIsPublic = (vis == "public ")))
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
                    val defExpr =
                        if (useFinalAbiLayout || hasReprSimdAttr(item.attrs)) {
                            renderStructFieldDefaultOrNull(field.ty, fType, selfTypeWithGenerics)
                        } else {
                            null
                        }
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
                    val prevFieldTraitAssoc = currentTraitAssocParamNames
                    currentTraitAssocParamNames =
                        currentTraitAssocParamNames + sourceAssociatedParameters(item.generics).map { it.associated }
                    val fType =
                        try {
                            renderType(field.ty, selfTypeName = selfTypeWithGenerics)
                        } finally {
                            currentTraitAssocParamNames = prevFieldTraitAssoc
                            currentSamNameHint = prevHint
                        }
                    val defExpr =
                        if (useFinalAbiLayout || hasReprSimdAttr(item.attrs)) {
                            renderStructFieldDefaultOrNull(field.ty, fType, selfTypeWithGenerics)
                        } else {
                            null
                        }
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
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.UnresolvedTypeLayout")
            ctorParams += "public val fieldStorage: MutPtr<$fieldType>"
            ctorParams += "public val fieldLayout: TypeLayout<$fieldType> = UnresolvedTypeLayout()"
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
            extraIterableMembers += "$indent    $secCtorVis constructor($fieldName: $fieldType, fieldLayout: TypeLayout<$fieldType> = UnresolvedTypeLayout()) : " +
                "this(allocateNativeCell($fieldName, $alignedFieldLayout), $alignedFieldLayout)"
            extraIterableMembers += "$indent    public fun pointerLayout(): TypeLayout<$selfTypeWithGenerics> = " +
                "TransparentTypeLayout(fieldLayout, " +
                "{ ptr, layout -> $selfTypeWithGenerics(ptr, layout) }, { it.$fieldName })"
            if (transparentConcreteForSwift) {
                forceInternalPrimaryCtor = true
                val fnTypeParams = if (typeParams.isNotEmpty()) "$typeParams " else ""
                extraCompanionMembers +=
                    "$indent        public operator fun ${fnTypeParams}invoke($fieldName: $fieldType, fieldLayout: TypeLayout<$fieldType> = UnresolvedTypeLayout()): $selfTypeWithGenerics = " +
                    "$selfTypeWithGenerics($fieldName, fieldLayout)"
                extraCompanionMembers +=
                    "$indent        public fun ${fnTypeParams}fromFieldStorage(fieldStorage: MutPtr<$fieldType>, fieldLayout: TypeLayout<$fieldType> = UnresolvedTypeLayout()): $selfTypeWithGenerics = " +
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
        val transparentPtrField = liveField ?: fieldNameAndTypes.singleOrNull()
        if (typeName == "NonNull" && transparentPtrField != null &&
            transparentPtrField.second
                .substringBefore('<')
                .substringAfterLast('.')
                .trim() in setOf("ConstPtr", "MutPtr")
        ) {
            val ptrFieldName = transparentPtrField.first
            extraIterableMembers += "$indent    public val rawAddress: Long\n$indent        get() = $ptrFieldName.rawAddress"
            extraIterableMembers += "$indent    public val rawMeta: Long\n$indent        get() = $ptrFieldName.rawMeta"
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
            populateSwiftSafeCtorAndDataMethods(
                specs = fieldSpecs,
                ctorParams = ctorParams,
                swiftSafePropsOut = swiftSafeBodyProps,
                extraMembersOut = extraIterableMembers,
                extraCompanionMembersOut = extraCompanionMembers,
                attachedImpls = effectiveAttachedImpls,
                typeName = typeName,
                typeParamCount = typeParamCount,
                typeParams = typeParams,
                selfTypeWithGenerics = selfTypeWithGenerics,
                wouldHaveBeenDataClass = wouldHaveBeenDataClass,
                indent = indent,
            )
        }

        val isDataClass = wouldHaveBeenDataClass && !needsSwiftSafeStructCtor
        if (isDataClass && fieldNameAndTypes.any { isPrimitiveArrayRenderedType(it.second) }) {
            extraIterableMembers.addAll(buildArrayAwareDataClassMethods(typeName, fieldNameAndTypes, "$indent    "))
        }
        if (!isDataClass &&
            !useBodyPropertiesForLargeStruct &&
            !attachedImplDefinesInstanceMethod(effectiveAttachedImpls, "clone") &&
            hasDeriveTrait(item.attrs, "Clone")
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
            appendFormattedCtorParams(sb, indent, ctorHeaderPrefix, ctorParams)
        }
        if (superTraits.isNotEmpty()) {
            sb.append(" : ").append(superTraits.joinToString(", "))
        }
        sb.append(whereClause)

        val constGenericProps =
            if (!isLocalItem) {
                buildList {
                    val seenConstProps = fieldNameAndTypes.mapTo(hashSetOf()) { it.first }
                    for (gp in item.generics.params.toList()) {
                        if (gp !is GenericParam.ConstParam) continue
                        val cName = rustValueIdentToKotlin(gp.ident.toString().removePrefix("r#"))
                        if (!seenConstProps.add(cName)) continue
                        val cType = renderType(gp.ty, selfTypeWithGenerics)
                        val defVal =
                            gp.default?.expr?.let { renderExpr(it, "", selfTypeWithGenerics) }
                                ?: defaultInitializerForType(cType)
                        add("$indent    public val $cName: $cType = $defVal")
                    }
                }
            } else {
                emptyList()
            }
        val extraProps =
            when {
                useBodyPropertiesForLargeStruct -> bodyFieldProps + buildTupleStructAliasProperties(item.fields, selfTypeWithGenerics, "$indent    ")
                swiftSafeBodyProps.isNotEmpty() -> swiftSafeBodyProps + buildTupleStructAliasProperties(item.fields, selfTypeWithGenerics, "$indent    ")
                else -> buildTupleStructAliasProperties(item.fields, selfTypeWithGenerics, "$indent    ")
            } + constGenericProps
        val prevOverrideNames = currentStructOverrideMethodNames
        currentStructOverrideMethodNames = collectLocalSupertraitMethodNames(superTraits, ownerIsPublic = (vis == "public "))
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
                    emitEmptyCompanion = hasStandaloneCompanionExtensionImpl(typeName),
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
    } finally {
        currentTraitAssocParamNames = prevStructTraitAssoc
        activeConstParamNames = prevStructConstParams
        activeTypeParamNames = prevStructTypeParams
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
    if (!reserveUniqueSignature(scopeSignatures, "type:$typeName")) {
        return ""
    }
    val useFinalAbiLayout = headerContext.rawAbiMode || headerContext.finalClasses
    val (sb, vis) = beginItemHeaderAndVisibility(renderLeadingTrivia(item, indent), item.attrs, item.vis, indent, isLocalItem)
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
        populateSwiftSafeCtorAndDataMethods(
            specs = unionSpecs,
            ctorParams = ctorParams,
            swiftSafePropsOut = swiftSafeUnionProps,
            extraMembersOut = extraUnionMembers,
            extraCompanionMembersOut = extraUnionCompanionMembers,
            attachedImpls = filteredImpls,
            typeName = typeName,
            typeParamCount = typeParamCount,
            typeParams = typeParams,
            selfTypeWithGenerics = selfTypeWithGenerics,
            wouldHaveBeenDataClass = wouldHaveBeenDataClass,
            indent = indent,
        )
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
        appendFormattedCtorParams(sb, indent, ctorHeaderPrefix, ctorParams)
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
    if (!reserveUniqueSignature(scopeSignatures, "type:$typeName")) {
        return ""
    }
    val useFinalAbiLayout = headerContext.rawAbiMode || headerContext.finalClasses
    val (sb, vis) = beginItemHeaderAndVisibility(renderLeadingTrivia(item, indent), item.attrs, item.vis, indent, isLocalItem)
    val variants = item.variants.toList().filterNot { isDocOnlyCfgAttribute(it.attrs) }
    val variantKotlinNames = variants.mapTo(linkedSetOf()) { rustTypeIdentToKotlin(it.ident.toString()) }
    val declaredEnumTpNames = collectGenericTypeParamNames(item.generics)
    val usedTpNames = (declaredEnumTpNames + variantKotlinNames + typeName).toMutableSet()
    val enumParamRenames = linkedMapOf<String, String>()
    for (tp in declaredEnumTpNames) {
        if (tp in variantKotlinNames || tp == typeName) {
            var candidate = "${tp}Param"
            var suffix = 0
            while (!usedTpNames.add(candidate)) {
                candidate = "${tp}Param${suffix++}"
            }
            enumParamRenames[tp] = candidate
        }
    }
    val prevRenamedParams = renamedTypeParamIdentifiers
    val prevEnclosingVariants = activeEnclosingEnumVariantNames
    renamedTypeParamIdentifiers = prevRenamedParams + enumParamRenames
    activeEnclosingEnumVariantNames = prevEnclosingVariants + variantKotlinNames
    return try {
        val typeArgsUse = renderTypeArgumentsFromGenerics(item.generics, ownerTypeSimpleName = typeName)
        val selfTypeWithGenerics = typeName + typeArgsUse
        val prevEnumTypeParams = activeTypeParamNames
        val prevEnumConstParams = activeConstParamNames
        activeTypeParamNames =
            activeTypeParamNames +
            declaredEnumTpNames +
            enumParamRenames.values +
            sourceAssociatedParameters(item.generics, typeName).map { it.associated }
        activeConstParamNames = activeConstParamNames + collectGenericConstParamNames(item.generics)
        val typeParams: String
        val superTraits: List<String>
        try {
            typeParams = renderTypeParameters(item.generics, selfTypeName = selfTypeWithGenerics)
            superTraits = renderDistinctSuperTraits(attachedImpls, selfTypeWithGenerics, ownerIsPublic = (vis == "public "))
        } finally {
            activeConstParamNames = prevEnumConstParams
            activeTypeParamNames = prevEnumTypeParams
        }
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
            currentStructOverrideMethodNames =
                collectLocalSupertraitMethodNames(
                    superTraits,
                    includeReceiverlessMethods = true,
                    ownerIsPublic = (vis == "public "),
                )
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
                    renderAttachedImplMembers(
                        selfTypeWithGenerics,
                        item.generics,
                        enumFilteredImpls,
                        "$indent    ",
                        isLocalClass = isLocalItem,
                        emitEmptyCompanion = hasStandaloneCompanionExtensionImpl(typeName),
                    )
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

        val enumAssocTypeParamNames = sourceAssociatedParameters(item.generics).map { it.associated }
        val prevOverrideNames = currentStructOverrideMethodNames
        currentStructOverrideMethodNames = collectLocalSupertraitMethodNames(superTraits, ownerIsPublic = (vis == "public "))
        activeTypeParamNames = prevEnumTypeParams + declaredEnumTpNames + enumParamRenames.values + enumAssocTypeParamNames
        activeConstParamNames = prevEnumConstParams + collectGenericConstParamNames(item.generics)
        val attachedMembers =
            try {
                renderAttachedImplMembers(
                    selfTypeWithGenerics,
                    item.generics,
                    attachedImpls,
                    "$indent    ",
                    isLocalClass = isLocalItem,
                    emitEmptyCompanion = hasStandaloneCompanionExtensionImpl(typeName),
                )
            } finally {
                activeConstParamNames = prevEnumConstParams
                activeTypeParamNames = prevEnumTypeParams
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
        activeTypeParamNames = prevEnumTypeParams + declaredEnumTpNames + enumParamRenames.values + enumAssocTypeParamNames
        activeConstParamNames = prevEnumConstParams + collectGenericConstParamNames(item.generics)
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
            activeConstParamNames = prevEnumConstParams
            activeTypeParamNames = prevEnumTypeParams
        }
        val enumTypeParamNamesList = declaredEnumTpNames.map { enumParamRenames[it] ?: it }.toList()
        val variantRawNames = variants.map { it.ident.toString().removePrefix("r#") }.toSet()
        if (enumTypeParamNamesList.size == 2) {
            if (typeName in setOf("Result", "RustResult") && "Ok" in variantRawNames && "Err" in variantRawNames) {
                val okTp = enumTypeParamNamesList[0]
                val errTp = enumTypeParamNamesList[1]
                sb
                    .append('\n')
                    .append(indent)
                    .append("    public open fun okValue(): ")
                    .append(okTp)
                    .append(" = error(\"not Ok\")\n")
                    .append(indent)
                    .append("    public open fun errValue(): ")
                    .append(errTp)
                    .append(" = error(\"not Err\")\n")
            } else if (typeName == "ControlFlow" && "Break" in variantRawNames && "Continue" in variantRawNames) {
                val bTp = enumTypeParamNamesList[0]
                val cTp = enumTypeParamNamesList[1]
                sb
                    .append('\n')
                    .append(indent)
                    .append("    public open fun cfBreakValue(): ")
                    .append(bTp)
                    .append(" = error(\"not Break\")\n")
                    .append(indent)
                    .append("    public open fun cfContinueValue(): ")
                    .append(cTp)
                    .append(" = error(\"not Continue\")\n")
            }
        }
        val derivesEquality = hasDeriveTrait(item.attrs, "PartialEq")
        if (derivesEquality) {
            sb.append("\n$indent    public fun rustEquals(other: $selfTypeWithGenerics): Boolean = this == other\n")
        }
        if (isSimpleEnum && typeParams.isEmpty() && variants.isNotEmpty()) {
            val convSpecs =
                listOf(
                    "toByte" to "Byte",
                    "toUByte" to "UByte",
                    "toShort" to "Short",
                    "toUShort" to "UShort",
                    "toInt" to "Int",
                    "toUInt" to "UInt",
                    "toLong" to "Long",
                    "toULong" to "ULong",
                )
            if (reprKotlinType != null) {
                for ((fnName, retType) in convSpecs) {
                    if (!containsWholeIdentifier(attachedMembers, "fun $fnName(")) {
                        val bodyExpr = if (retType == reprKotlinType) "value" else "value.$fnName()"
                        sb.append("\n$indent    public fun $fnName(): $retType = $bodyExpr\n")
                    }
                }
            } else {
                val variantBranches =
                    variants.mapIndexed { idx, v ->
                        val vName = rustTypeIdentToKotlin(v.ident.toString())
                        "$indent        $vName -> $idx"
                    }
                sb
                    .append("\n$indent    private fun kmDiscriminant(): Int =\n")
                    .append("$indent        when (this) {\n")
                    .append(variantBranches.joinToString("\n"))
                    .append('\n')
                    .append("$indent        }\n")
                for ((fnName, retType) in convSpecs) {
                    if (!containsWholeIdentifier(attachedMembers, "fun $fnName(")) {
                        val bodyExpr = if (retType == "Int") "kmDiscriminant()" else "kmDiscriminant().$fnName()"
                        sb.append("\n$indent    public fun $fnName(): $retType = $bodyExpr\n")
                    }
                }
            }
        }
        if (attachedMembers.isNotEmpty()) {
            sb.append('\n').append(attachedMembers).append('\n')
        }
        sb.append(indent).append("}")
        sb.toString()
    } finally {
        activeEnclosingEnumVariantNames = prevEnclosingVariants
        renamedTypeParamIdentifiers = prevRenamedParams
    }
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
    val unboundedEnumTypeParams = collectUnboundedGenericTypeParamNames(enumGenerics, renamedTypeParamIdentifiers)
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
    val isResultVariantAlias =
        enumName in setOf("Result", "RustResult") &&
            typeParamCount == 2 &&
            specs.size == 1 &&
            specs[0].name == "value"
    val addErrAlias = isResultVariantAlias && vName == "Err"
    val overrideMethodLine =
        when {
            isResultVariantAlias && vName == "Ok" ->
                "public override fun okValue(): ${specs[0].renderedType} = value"
            isResultVariantAlias && vName == "Err" ->
                "public override fun errValue(): ${specs[0].renderedType} = value"
            enumName == "ControlFlow" &&
                typeParamCount == 2 &&
                specs.size == 1 &&
                specs[0].name == "value" &&
                vName == "Break" ->
                "public override fun cfBreakValue(): ${specs[0].renderedType} = value"
            enumName == "ControlFlow" &&
                typeParamCount == 2 &&
                specs.size == 1 &&
                specs[0].name == "value" &&
                vName == "Continue" ->
                "public override fun cfContinueValue(): ${specs[0].renderedType} = value"
            else -> null
        }
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
        if (addErrAlias || overrideMethodLine != null) {
            sb.append(" {\n")
            if (addErrAlias) {
                sb
                    .append(indent)
                    .append("    public val error: ")
                    .append(specs[0].renderedType)
                    .append(" get() = value\n")
            }
            if (overrideMethodLine != null) {
                sb
                    .append(indent)
                    .append("    ")
                    .append(overrideMethodLine)
                    .append('\n')
            }
            sb
                .append(indent)
                .append("}")
        }
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
    if (addErrAlias) {
        sb
            .append("\n")
            .append(indent)
            .append("    public val error: ")
            .append(specs[0].renderedType)
            .append(" get() = value\n")
    }
    if (overrideMethodLine != null) {
        sb
            .append("\n")
            .append(indent)
            .append("    ")
            .append(overrideMethodLine)
            .append('\n')
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

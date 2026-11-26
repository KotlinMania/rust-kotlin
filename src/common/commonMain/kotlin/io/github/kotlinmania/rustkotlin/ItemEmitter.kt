package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.TokenStream
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
                val cursor = RustTokenCursor(meta.tokens.clone().toList())
                val arguments =
                    if (cursor.size > 0 && cursor.punctCharAt(cursor.size - 1) == ',') {
                        cursor.trees.dropLast(1)
                    } else {
                        cursor.trees
                    }
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
            append(indent).append("    val panic = kotlin.test.assertFails(block = fun() ").append(body).append(")\n")
            append(indent).append("    kotlin.test.assertTrue(panic.message?.contains(")
            append(renderKotlinDoubleQuotedString(expected)).append(") == true)\n")
        } else {
            append(indent).append("    kotlin.test.assertFails(block = fun() ").append(body).append(")\n")
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
        val derive = attribute.meta as? Meta.List ?: return@any false
        val lastSeg = derive.path.segments.last()
        if (lastSeg?.ident.toString() !in setOf("derive", "derive_const")) return@any false
        val cursor = RustTokenCursor(derive.tokens.clone().toList())
        (0 until cursor.size).any { cursor.identAt(it) == traitName }
    }

private fun KotlinJ2KEmitter.hasStandaloneCompanionExtensionImpl(typeName: String): Boolean {
    val itemsToCheck = TranslationSession.sharedModuleFile?.items.orEmpty() + file.items
    for (item in itemsToCheck) {
        if (item !is Item.Impl || itemHasDocOnlyCfg(item) || item.traitPath != null) continue
        if (extractAttachableDirectOwnerTypeName(item, itemsToCheck) != null) continue
        val selfPath = (unwrapSynType(item.selfType) as? SynType.Path)?.takeIf { it.qself == null && it.path.segments.len() == 1 }?.path
        val selfBase = selfPath?.firstKotlinTypeName()
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

internal inline fun <R> KotlinJ2KEmitter.withSamHintScope(
    hint: String?,
    externAbi: Boolean = inExternAbiSignature,
    extraTraitAssocs: Collection<String>? = null,
    block: () -> R,
): R {
    val prevHint = currentSamNameHint
    val prevExternAbi = inExternAbiSignature
    val prevTraitAssocs = currentTraitAssocParamNames
    currentSamNameHint = hint
    inExternAbiSignature = externAbi
    if (extraTraitAssocs != null) {
        currentTraitAssocParamNames = prevTraitAssocs + extraTraitAssocs
    }
    return try {
        block()
    } finally {
        currentTraitAssocParamNames = prevTraitAssocs
        inExternAbiSignature = prevExternAbi
        currentSamNameHint = prevHint
    }
}

internal inline fun <R> KotlinJ2KEmitter.withBoundedTypeScope(
    boundedType: String?,
    suppressAssocs: Boolean = suppressTraitAssocPlaceholders,
    block: () -> R,
): R {
    val prevBounded = currentBoundedTypeName
    val prevSuppress = suppressTraitAssocPlaceholders
    currentBoundedTypeName = boundedType
    suppressTraitAssocPlaceholders = suppressAssocs
    return try {
        block()
    } finally {
        currentBoundedTypeName = prevBounded
        suppressTraitAssocPlaceholders = prevSuppress
    }
}

internal inline fun <R> KotlinJ2KEmitter.withOwnerSupertraitScope(
    superTraits: List<String>,
    ownerIsPublic: Boolean,
    includeReceiverlessMethods: Boolean = false,
    clearOverrides: Boolean = false,
    block: () -> R,
): R {
    val prevOverrideNames = currentStructOverrideMethodNames
    val prevSuperTraitBases = currentAttachedStructSuperTraitBaseNames
    if (clearOverrides) {
        currentStructOverrideMethodNames = emptySet()
        currentAttachedStructSuperTraitBaseNames = emptySet()
    } else {
        currentStructOverrideMethodNames =
            collectLocalSupertraitMethodNames(
                superTraits,
                includeReceiverlessMethods = includeReceiverlessMethods,
                ownerIsPublic = ownerIsPublic,
            )
        currentAttachedStructSuperTraitBaseNames =
            superTraits.mapTo(linkedSetOf()) { it.simpleKotlinTypeName() }
    }
    return try {
        block()
    } finally {
        currentStructOverrideMethodNames = prevOverrideNames
        currentAttachedStructSuperTraitBaseNames = prevSuperTraitBases
    }
}

internal inline fun <R> KotlinJ2KEmitter.withGenericParamScope(
    generics: Generics,
    extraTypeParams: Collection<String> = emptySet(),
    extraTraitAssocs: Collection<String> = emptySet(),
    block: () -> R,
): R {
    val prevTypeParams = activeTypeParamNames
    val prevConstParams = activeConstParamNames
    val prevTraitAssoc = currentTraitAssocParamNames
    activeTypeParamNames = prevTypeParams + collectGenericTypeParamNames(generics) + extraTypeParams
    activeConstParamNames = prevConstParams + collectGenericConstParamNames(generics)
    if (extraTraitAssocs.isNotEmpty()) {
        currentTraitAssocParamNames = prevTraitAssoc + extraTraitAssocs
    }
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
    val firstInput = sig.inputs.toList().firstOrNull()
    val firstReceiverRef = (firstInput as? FnArg.Receiver)?.kind as? ReceiverKind.Reference
    currentReceiverIsSharedBorrow = firstReceiverRef?.let { it.mutability == null } == true
    currentReceiverIsMutBorrow = firstReceiverRef?.let { it.mutability != null } == true
    currentMutableListLocals = emptySet()
    currentDequeLocals = emptySet()
    currentIterableLocals = emptySet()
    currentSourceParameterTypes = emptyMap()
    currentTypedLocals = emptyMap()
    currentWitnessedTraitBounds = emptyMap()
    currentTypeParamTraitBounds = emptyMap()
    if (resetHasherState) currentHasherStateParam = null
    currentNullableLocals = emptySet()
    currentSmartCastNonNullLocals = emptySet()
    currentIntoSubstitutedParams = emptySet()
    val intoSubstTps = linkedSetOf<String>()
    val mergedSubsts =
        buildMap {
            if (implGenerics != null) {
                putAll(extractFnTraitSubstitutions(implGenerics.params.toList(), implGenerics.whereClause, selfTypeName, intoSubstTps))
            }
            val sigGenerics = sig.generics
            putAll(extractFnTraitSubstitutions(sigGenerics.params.toList(), sigGenerics.whereClause, selfTypeName, intoSubstTps))
        }
    if (mergedSubsts.isNotEmpty()) {
        substitutedTypeParamTypes = prevSubst + mergedSubsts
    }
    currentIntoSubstitutedTypeParams = prevIntoSubstTps + intoSubstTps
    val combinedTypeParams =
        prevTypeParams +
            collectGenericTypeParamNames(ownerGenerics) +
            collectGenericTypeParamNames(implGenerics) +
            collectGenericTypeParamNames(sig.generics)
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
        if (updateEnclosingFunctionName) currentEnclosingFunctionName = prevEnclosingFn
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
        if (resetHasherState) currentHasherStateParam = prevHasherState
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
    sb.append("$indent$visStr$declKeyword$name: $renderedTy")
    if (headerContext.isBatchCrateOutput && initExpr.contains('\n')) {
        sb.append(" =\n$indent    ").append(initExpr.replace("\n", "\n    "))
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
            val blockStmts = expr.block.stmts
            return blockStmts
                .mapNotNull { stmt ->
                    if (stmt is Stmt.ItemStmt) {
                        val rendered = renderItem(stmt.item, indent, emptyMap(), isLocalItem = isLocalItem, scopeSignatures = scopeSignatures)
                        rendered.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }.joinToString("\n\n")
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
    if (!reserveUniqueSignature(scopeSignatures, "typealias:$name")) return ""

    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (headerContext.rawAbiMode || headerContext.finalClasses) "public " else renderVisibility(item.vis)
    val typeParams = renderTypeParameters(item.generics, selfTypeName = name)
    sourceStorageAlias(item)?.let { declaration ->
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        for (sym in listOf("$coreNs.ptr.MutPtr", "$coreNs.mem.TypeLayout", "$coreNs.mem.allocateNativeCell", "$coreNs.mem.UnresolvedTypeLayout")) {
            CoreIntrinsicLowering.registerCoreImport(this, sym)
        }
        val field = requireNotNull(sourceTransparentField(declaration, declaration.ident.toString()))
        val fieldType = renderType(sourceStorageAliasField(item, declaration), name)
        val target = renderType(item.ty, name)
        sb.append("${indent}${vis}class $name$typeParams(fieldStorage: MutPtr<$fieldType>, fieldLayout: TypeLayout<$fieldType> = UnresolvedTypeLayout()) : $target(fieldStorage, fieldLayout) {\n")
        sb.append("$indent    public constructor(${field.first}: $fieldType, fieldLayout: TypeLayout<$fieldType> = UnresolvedTypeLayout()) : this(allocateNativeCell(${field.first}, fieldLayout), fieldLayout)\n")
        sb.append("$indent    public companion object\n$indent}")
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
            sb.append("${indent}${vis}typealias $name = $samName?")
            return sb.toString()
        }
    }
    val rawIdent = item.ident.toString().removePrefix("r#")
    val isFfiAlias =
        headerContext.rawAbiMode ||
            name in inspector.ffiReferencedTypeNames ||
            rawIdent in inspector.ffiReferencedTypeNames
    val cPointerWidthRhs =
        if (typeParams.isEmpty()) {
            when (extractSinglePrimitiveTargetIdent(item.ty)) {
                "usize" ->
                    if (isFfiAlias ||
                        isCPointerWidthUnsignedTypeName(rawIdent) ||
                        scopedAbiKeysForRenderedType(name).any { headerContext.crateNumericTypeKinds[it] in setOf("U64", "U32", "U16") }
                    ) {
                        when (activeCfgProfile.pointerWidth) {
                            64 -> "ULong"
                            16 -> "UShort"
                            else -> "UInt"
                        }
                    } else {
                        null
                    }
                "isize" ->
                    if (isFfiAlias ||
                        isCPointerWidthSignedTypeName(rawIdent) ||
                        scopedAbiKeysForRenderedType(name).any { headerContext.crateNumericTypeKinds[it] in setOf("I64", "I32", "I16") }
                    ) {
                        when (activeCfgProfile.pointerWidth) {
                            64 -> "Long"
                            16 -> "Short"
                            else -> "Int"
                        }
                    } else {
                        null
                    }
                else -> null
            }
        } else {
            null
        }
    val rhs =
        cPointerWidthRhs
            ?: withSamHintScope(hint = "${name}Callback", externAbi = inExternAbiSignature || isFfiAlias) {
                renderType(item.ty, selfTypeName = name)
            }
    if (rhs.isEmpty() || rhs == name || rhs == "$name$typeParams") {
        inspector.declaredTypeNames.remove(name)
        return ""
    }
    sb.append("${indent}${vis}typealias $name$typeParams = $rhs")
    return sb.toString()
}

internal fun KotlinJ2KEmitter.renderItemFn(
    item: Item.Fn,
    indent: String,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean = false,
): String {
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    return withFunctionRenderScope(sig = item.sig, selfTypeName = null) {
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
        val isTest = !isLocalItem && hasTestAttribute(item.attrs)
        if (isTest) sb.append(indent).append("@kotlin.test.Test\n")
        sb.append(indent).append(sigHeader)
        val fnBlock = item.block
        val retType = extractReturnSynType(item.sig.output)
        val panicExpectation = if (isTest) extractRustPanicExpectation(item.attrs) else null
        val bodyIndent = if (panicExpectation == null) indent else "$indent    "
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        val rawFnIdent = item.sig.cleanFnName()
        val memHookParam =
            if (!isLocalItem &&
                !headerContext.rawAbiMode &&
                headerContext.packageName == "$coreNs.mem" &&
                item.sig.inputs.len() == 1 &&
                retType == null &&
                rawFnIdent in setOf("drop", "forget")
            ) {
                sigHeader.substringAfterLast('(').substringBefore(':').trim().takeIf {
                    it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch == '_' }
                }
            } else {
                null
            }
        val body =
            if (memHookParam != null) {
                if (rawFnIdent == "drop") {
                    "{\n$bodyIndent    if ($memHookParam is AutoCloseable) $memHookParam.close()\n$bodyIndent}"
                } else {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.intrinsics.KmForgettableResource")
                    "{\n$bodyIndent    if ($memHookParam is KmForgettableResource) $memHookParam.disarmCleaner()\n$bodyIndent}"
                }
            } else {
                val shadows = computeReassignedParamShadows(item.sig, fnBlock, "$bodyIndent    ", selfTypeName = null)
                renderBlock(
                    fnBlock,
                    bodyIndent,
                    selfTypeName = null,
                    returnType = retType,
                    leadingInjectedLines = shadows,
                    allowExpressionBody = panicExpectation == null,
                )
            }
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
            val inner = angle?.args?.toList()?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
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
        NumericKind.I128 -> return "I128()"
        NumericKind.U8, NumericKind.U16, NumericKind.U32 -> return "0u"
        NumericKind.U64 -> return "0uL"
        NumericKind.U128 -> return "U128()"
        NumericKind.F16 -> return "F16()"
        NumericKind.F32 -> return "0.0f"
        NumericKind.F64 -> return "0.0"
        NumericKind.F128 -> return "F128()"
        null -> {}
    }
    val parsed = clean.asKotlinRenderedType()
    if (parsed != null && !parsed.isNullable) {
        if ((parsed.headPath == "Pair" || parsed.headPath == "kotlin.Pair") && parsed.arity == 2) {
            val (a, b) = parsed.typeArgs.map(::renderFallbackDefaultForRenderedType)
            return "$clean($a, $b)"
        }
        if ((parsed.headPath == "Triple" || parsed.headPath == "kotlin.Triple") && parsed.arity == 3) {
            val (a, b, c) = parsed.typeArgs.map(::renderFallbackDefaultForRenderedType)
            return "$clean($a, $b, $c)"
        }
        val maybeInner = parsed.singleArgOf("MaybeUninit")
        if (maybeInner != null) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.maybeuninit.MaybeUninit")
            return "MaybeUninit.uninit(${resolveRenderedTypeLayoutExpr(maybeInner)})"
        }
    }
    return registerPtrDefaultImportIfNeeded(defaultInitializerForType(clean))
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
    if (unsignedStripped != null && isDecimalOrHexLiteralDigits(unsignedStripped)) return unsignedStripped
    if (isDecimalOrHexLiteralDigits(trimmed)) return trimmed
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
        return renderType(aliasTarget, null).trim().removeSuffix("?") in PRIMITIVE_ARRAY_TYPES
    }
    if ('.' !in clean && simpleClean in inspector.declaredTypeNames) return false
    for (key in scopedAbiKeysForRenderedType(clean)) {
        val def = headerContext.crateTypeAliasDefaults[key]?.trim() ?: continue
        return PRIMITIVE_ARRAY_TYPES.any { def.startsWith("$it(") }
    }
    return false
}

private fun KotlinJ2KEmitter.isErasedConstParamArray(arr: SynType.Array): Boolean =
    !(headerContext.rawAbiMode || headerContext.finalClasses) &&
        activeConstParamNames.isNotEmpty() &&
        collectUsedRawIdentsInExpr(arr.len).any {
            it in activeConstParamNames || rustValueIdentToKotlin(it) in activeConstParamNames
        }

private fun KotlinJ2KEmitter.isFnPtrArrayElement(elemSyn: SynType, renderedElemTy: String): Boolean {
    val unwrappedElem = unwrapSynType(elemSyn)
    return unwrappedElem is SynType.FnPtr ||
        extractOptionBareFn(unwrappedElem) != null ||
        (renderedElemTy.endsWith("Fn") && isOptionFnTypeAliasRenderedType(renderedElemTy.removeSuffix("Fn")))
}

internal fun KotlinJ2KEmitter.renderStructFieldDefault(
    fieldSynTy: SynType,
    renderedType: String,
    selfTypeName: String?,
): String {
    val cleanType = renderedType.trim()
    val unwrapped = unwrapPaddingSynType(fieldSynTy)
    if (unwrapped is SynType.Array) {
        if (isErasedConstParamArray(unwrapped)) {
            if (cleanType in PRIMITIVE_ARRAY_TYPES) return "$cleanType(0)"
            if (cleanType.startsWith("List<") && cleanType.endsWith(">")) return "emptyList()"
        }
        val rawLen = renderTypedNumericInitializer(unwrapped.len, NumericKind.I32, "", selfTypeName)
        val lenInt = normalizeArrayLengthToIntExpr(rawLen, unwrapped.len, selfTypeName)
        if (cleanType in PRIMITIVE_ARRAY_TYPES) return "$cleanType($lenInt)"
        if (cleanType.startsWith("List<") && cleanType.endsWith(">")) {
            val elemTy = cleanType.removePrefix("List<").removeSuffix(">").trim()
            if (isFnPtrArrayElement(unwrapped.elem, elemTy)) return "emptyList()"
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
    if (cleanType.contains("->") || cleanType in activeTypeParamNames) return null
    if ('.' in cleanType) {
        val prefix = cleanType.substringBefore('.').trim()
        if (prefix in activeTypeParamNames || prefix == "Self") return null
    }
    val unwrapped = unwrapPaddingSynType(fieldSynTy)
    if (unwrapped is SynType.Array && !isErasedConstParamArray(unwrapped) && cleanType.startsWith("List<") && cleanType.endsWith(">")) {
        val elemTy = cleanType.removePrefix("List<").removeSuffix(">").trim()
        if (!isFnPtrArrayElement(unwrapped.elem, elemTy) && renderStructFieldDefaultOrNull(unwrapped.elem, elemTy, selfTypeName) == null) {
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
        val cond = if (isPrimitiveArrayRenderedType(fType)) "!this.$fName.contentEquals(other.$fName)" else "this.$fName != other.$fName"
        eqLines += "$memberIndent    if ($cond) return false"
    }
    eqLines += "$memberIndent    return true"
    eqLines += "$memberIndent}"

    fun fieldHashExpr(fName: String, fType: String): String =
        if (isPrimitiveArrayRenderedType(fType)) "this.$fName.contentHashCode()" else "this.$fName.hashCode()"

    val hashMethod =
        when (fields.size) {
            0 -> "${memberIndent}override fun hashCode(): Int = 0"
            1 -> "${memberIndent}override fun hashCode(): Int = ${fieldHashExpr(fields[0].first, fields[0].second)}"
            else ->
                buildString {
                    append("${memberIndent}override fun hashCode(): Int {\n")
                    append("$memberIndent    var result = ${fieldHashExpr(fields[0].first, fields[0].second)}\n")
                    for (i in 1 until fields.size) {
                        append("$memberIndent    result = 31 * result + ${fieldHashExpr(fields[i].first, fields[i].second)}\n")
                    }
                    append("$memberIndent    return result\n$memberIndent}")
                }
        }
    return listOf(eqLines.joinToString("\n"), hashMethod)
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
) {
    val ctorParamKey: String
        get() = if (vis == "public " && isConcreteGenericForSwift) "${name}CtorArg" else name
}

internal fun hasEmittedKotlinTraitBound(bounds: List<TypeParamBound>): Boolean =
    bounds.any { b ->
        b is TypeParamBound.Trait && b.maybe == null && b.path.lastIdentText() !in IGNORED_TRAIT_BOUNDS
    }

internal fun collectUnboundedGenericTypeParamNames(
    generics: Generics,
    renamedParams: Map<String, String> = emptyMap(),
): Set<String> {
    val boundedInWhere = hashSetOf<String>()
    for (pred in generics.wherePredicatesList()) {
        if (pred is WherePredicate.TypePredicate && hasEmittedKotlinTraitBound(pred.bounds.toList())) {
            val ty = pred.boundedTy
            if (ty is SynType.Path && ty.qself == null && ty.path.segments.size == 1) {
                val firstSeg = ty.path.segments.first()
                firstSeg?.ident?.toString()?.let {
                    val ktName = rustTypeIdentToKotlin(it)
                    boundedInWhere += (renamedParams[ktName] ?: ktName)
                }
            }
        }
    }
    val genericParamList = generics.params.toList()
    return genericParamList
        .mapNotNull { gp ->
            if (gp is GenericParam.TypeParam && !hasEmittedKotlinTraitBound(gp.bounds.toList())) {
                val rawKtName = rustTypeIdentToKotlin(gp.ident.toString())
                val ktName = renamedParams[rawKtName] ?: rawKtName
                ktName.takeIf { it !in boundedInWhere }
            } else {
                null
            }
        }.toSet()
}

private val STDLIB_COLLECTION_NAMES_FOR_SWIFT_CHECK =
    "List Set Map MutableList MutableSet MutableMap Collection MutableCollection Iterable MutableIterable ArrayDeque Pair Triple"
        .split(' ')
        .toSet()

internal fun KotlinJ2KEmitter.isConcreteGenericTypeForSwiftExport(
    renderedType: String,
    unboundedOwnerTypeParams: Set<String>,
    visitedAliases: Set<String> = emptySet(),
): Boolean {
    val base = renderedType.trim().removeSuffix("?").trim()
    if (base.isEmpty()) return false
    if (scopedAbiKeysForRenderedType(base).any { it in headerContext.cratePointerTypeAliases }) return true
    if (scopedAbiKeysForRenderedType(base).any { key ->
            val def = headerContext.crateTypeAliasDefaults[key]?.trim().orEmpty()
            def.startsWith("MutPtr(") || def.startsWith("ConstPtr(") || def.startsWith("nullMut(") || def.startsWith("nullConst(")
        }
    ) {
        return true
    }
    if ('<' !in base) {
        if (base !in visitedAliases) {
            val origItems = originalFile.items.toList()
            val localAlias =
                origItems
                    .filterIsInstance<Item.ItemType>()
                    .firstOrNull { it.generics.params.isEmpty() && rustTypeIdentToKotlin(it.ident.toString()) == base }
            if (localAlias != null) {
                val expanded = renderType(localAlias.ty, selfTypeName = null)
                return isConcreteGenericTypeForSwiftExport(expanded, unboundedOwnerTypeParams, visitedAliases + base)
            }
        }
        return false
    }
    return KotlinTokenWalker(base).anyGenericApplication { rawIdent, topArgs ->
        if (rawIdent.isNotEmpty() && rawIdent !in STDLIB_COLLECTION_NAMES_FOR_SWIFT_CHECK) {
            val hasConcreteArg =
                topArgs.any { rawArg ->
                    val withoutVariance = rawArg.removePrefix("out ").removePrefix("in ").trim()
                    val cleanArg = withoutVariance.removeSuffix("?").trim()
                    cleanArg.isNotEmpty() && cleanArg != "*" && cleanArg != "Any" && cleanArg != "kotlin.Any" && cleanArg !in unboundedOwnerTypeParams
                }
            if (hasConcreteArg) return@anyGenericApplication true
        }
        topArgs.any { isConcreteGenericTypeForSwiftExport(it, unboundedOwnerTypeParams, visitedAliases) }
    }
}

internal fun attachedImplDefinesInstanceMethod(
    attachedImpls: List<Item.Impl>,
    methodName: String,
): Boolean =
    attachedImpls.any { impl ->
        impl.items.any { sub ->
            sub is ImplItem.Fn &&
                sub.sig.hasSelfReceiver() &&
                rustFunctionIdentToKotlin(sub.sig.ident.toString()) == methodName
        }
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
            val copyArgs = specs.joinToString(", ") { "${it.ctorParamKey} = ${it.name}" }
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
        val defaultCtorArgs = specs.joinToString(",\n") { "$indent        ${it.ctorParamKey} = ${it.defaultExpr}" }
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
                sb.append("$indent    $p,\n")
            }
        }
        sb.append(indent).append(")")
    } else {
        sb.append("$ctorHeaderPrefix(${ctorParams.joinToString(", ")})")
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
    val ctorArgs = specs.joinToString(",\n") { "$memberIndent        ${it.ctorParamKey} = ${it.name}" }
    return "$memberIndent    public operator fun ${fnTypeParams}invoke(\n$invokeParams,\n$memberIndent    ): $selfTypeWithGenerics = $selfTypeWithGenerics(\n$ctorArgs,\n$memberIndent    )"
}

/**
 * Shared base class for nominal Rust aggregate declarations (`struct`, `union`, and `enum`),
 * centralizing generic parameter metadata, field-level Swift-export concrete-generic checks,
 * `SwiftSafeFieldSpec` + constructor/body property accumulation, and class header/body assembly.
 */
private abstract class NominalTypeDeclarationEmitter(
    protected val emitter: KotlinJ2KEmitter,
    protected val typeName: String,
    protected val generics: Generics,
    protected val indent: String,
    protected val isLocalItem: Boolean,
    protected val vis: String,
    ownerSimpleNameForTypeArgs: String? = typeName,
    renamedParamsForUnbounded: Map<String, String> = emptyMap(),
) {
    protected val useFinalAbiLayout: Boolean = emitter.headerContext.rawAbiMode || emitter.headerContext.finalClasses
    protected val typeParamCount: Int = generics.params.toList().count { it is GenericParam.TypeParam }
    protected val selfTypeWithGenerics: String =
        typeName + emitter.renderTypeArgumentsFromGenerics(generics, ownerTypeSimpleName = ownerSimpleNameForTypeArgs)
    protected val unboundedOwnerTypeParams: Set<String> =
        collectUnboundedGenericTypeParamNames(generics, renamedParamsForUnbounded)
    protected val ctorParams = mutableListOf<String>()
    protected val bodyFieldProps = mutableListOf<String>()
    protected val fieldNameAndTypes = mutableListOf<Pair<String, String>>()
    protected val fieldSpecs = mutableListOf<SwiftSafeFieldSpec>()

    protected fun isFieldConcreteGeneric(renderedType: String): Boolean =
        !isLocalItem && vis == "public " && emitter.isConcreteGenericTypeForSwiftExport(renderedType, unboundedOwnerTypeParams)

    protected fun recordField(
        fName: String,
        fType: String,
        fVis: String,
        defExpr: String?,
        combinedHeader: String,
        docPrefix: String,
        recordBodyPropertyAndNameType: Boolean = true,
    ) {
        val defaultSuffix = defExpr?.let { " = $it" }.orEmpty()
        val isConcreteGen = isFieldConcreteGeneric(fType)
        ctorParams += "$docPrefix${fVis}var $fName: $fType$defaultSuffix"
        if (recordBodyPropertyAndNameType) {
            val setterLine = if (fVis == "public " && isConcreteGen) "\n$indent        internal set" else ""
            bodyFieldProps += "$combinedHeader$indent    ${fVis}var $fName: $fType$defaultSuffix$setterLine"
            fieldNameAndTypes += fName to fType
        }
        fieldSpecs += SwiftSafeFieldSpec(fName, fType, fVis, defExpr, combinedHeader, docPrefix, isConcreteGen)
    }

    protected fun renderFieldTypeWithCallbackHint(
        fieldTy: SynType,
        callbackSuffix: String,
        extraTraitAssocs: Collection<String>? = null,
    ): String =
        emitter.withSamHintScope(hint = "$typeName$callbackSuffix", extraTraitAssocs = extraTraitAssocs) {
            emitter.renderType(fieldTy, selfTypeName = selfTypeWithGenerics)
        }
}

private class StructDeclarationEmitter(
    emitter: KotlinJ2KEmitter,
    private val item: Item.Struct,
    private val attachedImpls: List<Item.Impl>,
    indent: String,
    isLocalItem: Boolean,
    private val sb: StringBuilder,
    vis: String,
    typeName: String,
) : NominalTypeDeclarationEmitter(emitter, typeName, item.generics, indent, isLocalItem, vis) {
    fun emit(): String {
        val effectiveAttachedImpls =
            if (useFinalAbiLayout && typeParamCount == 0) {
                attachedImpls.filterNot { it.traitPath?.path?.lastIdentText() == "Default" }
            } else {
                attachedImpls
            }
        val structAssocs = emitter.sourceAssociatedParameters(item.generics, typeName).map { it.associated }
        return emitter.withGenericParamScope(item.generics, extraTraitAssocs = structAssocs) {
            val typeParams = emitter.renderTypeParameters(item.generics, selfTypeName = selfTypeWithGenerics)
            val whereClause =
                emitter.renderWhereClause(
                    item.generics.whereClause,
                    selfTypeName = selfTypeWithGenerics,
                    ownerGenerics = item.generics,
                )
            val superTraits = mutableListOf<String>()
            val extraMembers = mutableListOf<String>()
            superTraits.addAll(emitter.renderDistinctSuperTraits(effectiveAttachedImpls, selfTypeWithGenerics, ownerIsPublic = (vis == "public ")))
            appendIteratorSupertraitIfApplicable(effectiveAttachedImpls, superTraits, extraMembers)
            collectStructFields(effectiveAttachedImpls)

            val liveField = emitter.sourceTransparentField(item, selfTypeWithGenerics)
            val extraCompanionMembers = mutableListOf<String>()
            var forceInternalPrimaryCtor = false
            val swiftSafeBodyProps = mutableListOf<String>()
            val useBodyPropertiesForLargeStruct =
                liveField == null && useFinalAbiLayout && ctorParams.size > 64 && fieldSpecs.all { it.defaultExpr != null }

            if (liveField != null) {
                forceInternalPrimaryCtor = configureTransparentStructWrapper(liveField, typeParams, extraMembers, extraCompanionMembers)
            } else if (!emitter.headerContext.rawAbiMode && !isLocalItem) {
                StructLayoutComputer.computeStructLayout(item, emitter)?.let { computedStructLayout ->
                    extraCompanionMembers +=
                        StructLayoutComputer.renderStructTypeLayoutMethod(
                            layout = computedStructLayout,
                            genericTypeParamsRendered = typeParams,
                            selfTypeWithGenerics = selfTypeWithGenerics,
                            coreNamespace = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext),
                            indent = "$indent    ",
                            emittedFieldTypesByName = fieldNameAndTypes.toMap(),
                            useBodyPropertiesCtor = useBodyPropertiesForLargeStruct,
                            emitter = emitter,
                        )
                }
            }

            val transparentPtrField = liveField ?: fieldNameAndTypes.singleOrNull()
            val ptrSimpleType = transparentPtrField?.second?.simpleKotlinTypeName()
            if (typeName == "NonNull" && transparentPtrField != null && ptrSimpleType in setOf("ConstPtr", "MutPtr")) {
                val ptrFieldName = transparentPtrField.first
                extraMembers += "$indent    public val rawAddress: Long\n$indent        get() = $ptrFieldName.rawAddress"
                extraMembers += "$indent    public val rawMeta: Long\n$indent        get() = $ptrFieldName.rawMeta"
            }

            val needsSwiftSafeStructCtor =
                liveField == null &&
                    !useBodyPropertiesForLargeStruct &&
                    !isLocalItem &&
                    vis == "public " &&
                    fieldSpecs.any { it.vis == "public " && it.isConcreteGenericForSwift }
            val wouldHaveBeenDataClass =
                liveField == null &&
                    useFinalAbiLayout &&
                    ctorParams.size in 1..16 &&
                    superTraits.isEmpty() &&
                    extraMembers.isEmpty() &&
                    typeParams.isEmpty()

            if (needsSwiftSafeStructCtor) {
                forceInternalPrimaryCtor = true
                emitter.populateSwiftSafeCtorAndDataMethods(
                    specs = fieldSpecs,
                    ctorParams = ctorParams,
                    swiftSafePropsOut = swiftSafeBodyProps,
                    extraMembersOut = extraMembers,
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
            if (isDataClass && fieldNameAndTypes.any { emitter.isPrimitiveArrayRenderedType(it.second) }) {
                extraMembers.addAll(emitter.buildArrayAwareDataClassMethods(typeName, fieldNameAndTypes, "$indent    "))
            }
            if (!isDataClass &&
                !useBodyPropertiesForLargeStruct &&
                !attachedImplDefinesInstanceMethod(effectiveAttachedImpls, "clone") &&
                hasDeriveTrait(item.attrs, "Clone")
            ) {
                val cloneOpenMod = if (useFinalAbiLayout) "" else "open "
                extraMembers +=
                    if (liveField != null) {
                        "$indent    public ${cloneOpenMod}fun clone(): $selfTypeWithGenerics = $selfTypeWithGenerics(${liveField.first}, fieldLayout)"
                    } else {
                        "$indent    public ${cloneOpenMod}fun clone(): $selfTypeWithGenerics = $typeName(${fieldNameAndTypes.joinToString(", ") { it.first }})"
                    }
            }
            appendAutoCloseableDropBridgeIfEligible(effectiveAttachedImpls, liveField, isDataClass, useBodyPropertiesForLargeStruct, superTraits, extraMembers)

            val classKeyword =
                when {
                    isDataClass -> "data class "
                    useFinalAbiLayout -> "class "
                    else -> "open class "
                }
            sb.append("$indent$vis$classKeyword$typeName$typeParams")
            if (ctorParams.isNotEmpty() && !useBodyPropertiesForLargeStruct) {
                emitter.appendFormattedCtorParams(sb, indent, if (forceInternalPrimaryCtor) " internal constructor" else "", ctorParams)
            }
            if (superTraits.isNotEmpty()) sb.append(" : ").append(superTraits.joinToString(", "))
            sb.append(whereClause)

            val constGenericProps = collectConstGenericProperties()
            val tupleAliases = emitter.buildTupleStructAliasProperties(item.fields, selfTypeWithGenerics, "$indent    ")
            val extraProps =
                when {
                    useBodyPropertiesForLargeStruct -> bodyFieldProps + tupleAliases
                    swiftSafeBodyProps.isNotEmpty() -> swiftSafeBodyProps + tupleAliases
                    else -> tupleAliases
                } + constGenericProps
            val isMultiFileCrate = emitter.headerContext.isBatchCrateOutput || emitter.headerContext.crateSymbolSubpackages.isNotEmpty()
            val memberLines =
                emitter.withOwnerSupertraitScope(superTraits, ownerIsPublic = (vis == "public ")) {
                    emitter.renderAttachedImplMembers(
                        typeName = selfTypeWithGenerics,
                        ownerGenerics = item.generics,
                        attachedImpls = effectiveAttachedImpls,
                        indent = "$indent    ",
                        extraProperties = extraProps,
                        extraMembers = extraMembers,
                        extraCompanionMembers = extraCompanionMembers,
                        isLocalClass = isLocalItem,
                        structFieldNames = fieldNameAndTypes.mapTo(linkedSetOf()) { it.first },
                        emitEmptyCompanion =
                            (!isLocalItem && !isDataClass && !useFinalAbiLayout && vis != "private " && isMultiFileCrate) ||
                                emitter.hasStandaloneCompanionExtensionImpl(typeName),
                    )
                }
            if (memberLines.isNotEmpty()) {
                sb.append(" {\n").append(memberLines)
                if (!memberLines.endsWith('\n')) sb.append('\n')
                sb.append(indent).append("}")
            }
            sb.toString()
        }
    }

    private fun appendIteratorSupertraitIfApplicable(
        effectiveAttachedImpls: List<Item.Impl>,
        superTraits: MutableList<String>,
        extraMembers: MutableList<String>,
    ) {
        val iterElemTy = emitter.extractDirectIteratorItemRenderedType(effectiveAttachedImpls, "Iterator", selfTypeWithGenerics, requireOwnedSelf = false)
        val intoIterElemTy =
            if (iterElemTy == null) {
                emitter.extractDirectIteratorItemRenderedType(effectiveAttachedImpls, "IntoIterator", selfTypeWithGenerics, requireOwnedSelf = true)
            } else {
                null
            }
        val iterableElem = iterElemTy ?: intoIterElemTy ?: return
        if (superTraits.any { it.substringBefore('<').trim() == "Iterable" }) return
        superTraits += "Iterable<$iterableElem>"
        val mIndent = "$indent    "
        val kotlinIteratorTy = emitter.stdlibIteratorTypeName()
        extraMembers +=
            if (iterElemTy != null) {
                "${mIndent}override fun iterator(): $kotlinIteratorTy<$iterElemTy> = object : AbstractIterator<$iterElemTy>() {\n" +
                    "$mIndent    override fun computeNext() {\n" +
                    "$mIndent        when (val v = this@$typeName.next()) {\n" +
                    "$mIndent            null -> done()\n" +
                    "$mIndent            else -> setNext(v)\n" +
                    "$mIndent        }\n" +
                    "$mIndent    }\n" +
                    "$mIndent}"
            } else {
                "${mIndent}override fun iterator(): $kotlinIteratorTy<$intoIterElemTy> = this.intoIter().iterator()"
            }
    }

    private fun collectStructFields(effectiveAttachedImpls: List<Item.Impl>) {
        val includeDefaults = useFinalAbiLayout || emitter.hasReprSimdAttr(item.attrs)
        when (val fields = item.fields) {
            is Fields.Named -> {
                val seenFieldNames = hashSetOf<String>()
                val namedList = fields.fields.named.toList()
                for ((idx, field) in namedList.withIndex()) {
                    if (isDocOnlyCfgAttribute(field.attrs)) continue
                    val rawFieldIdent = field.ident?.toString()?.removePrefix("r#") ?: "field$idx"
                    val fName = rustFieldIdentToKotlin(field.ident?.toString() ?: "field$idx")
                    if (!seenFieldNames.add(fName)) continue
                    val combinedFieldHeader = emitter.renderLeadingTrivia(field, "$indent    ") + emitter.renderKDocFromAttributes(field.attrs, "$indent    ")
                    val docPrefix = if (combinedFieldHeader.isNotEmpty()) "\n$combinedFieldHeader$indent    " else ""
                    val fVis = emitter.renderVisibility(field.vis)
                    val unwrappedFieldTy = emitter.unwrapPaddingSynType(field.ty)
                    if (useFinalAbiLayout && unwrappedFieldTy is SynType.Tuple && unwrappedFieldTy.elems.len() >= 2) {
                        for ((tIdx, elemTy) in unwrappedFieldTy.elems.toList().withIndex()) {
                            val subName = "$fName$tIdx"
                            if (!seenFieldNames.add(subName)) continue
                            val subType = emitter.renderType(elemTy, selfTypeName = selfTypeWithGenerics)
                            val subDefExpr = emitter.renderStructFieldDefaultOrNull(elemTy, subType, selfTypeWithGenerics)
                            recordField(subName, subType, fVis, subDefExpr, if (tIdx == 0) combinedFieldHeader else "", if (tIdx == 0) docPrefix else "")
                        }
                        continue
                    }
                    var fType = renderFieldTypeWithCallbackHint(field.ty, "${fName.replaceFirstChar { it.uppercase() }}Callback")
                    if (field.vis !is Visibility.Public && fType.startsWith("List<") && emitter.structImplsMutateField(effectiveAttachedImpls, rawFieldIdent)) {
                        fType = "Mutable$fType"
                    }
                    val defExpr = if (includeDefaults) emitter.renderStructFieldDefaultOrNull(field.ty, fType, selfTypeWithGenerics) else null
                    recordField(fName, fType, fVis, defExpr, combinedFieldHeader, docPrefix)
                }
            }
            is Fields.Unnamed -> {
                val rawUnnamed = fields.fields.unnamed.toList()
                val fieldList = rawUnnamed.filterNot { isDocOnlyCfgAttribute(it.attrs) }
                val unnamedAssocs = emitter.sourceAssociatedParameters(item.generics).map { it.associated }
                for ((idx, field) in fieldList.withIndex()) {
                    val fVis = emitter.renderVisibility(field.vis)
                    val propName = tupleFieldPropertyName(idx, fieldList.size)
                    val fType = renderFieldTypeWithCallbackHint(field.ty, "Field${idx}Callback", extraTraitAssocs = unnamedAssocs)
                    val defExpr = if (includeDefaults) emitter.renderStructFieldDefaultOrNull(field.ty, fType, selfTypeWithGenerics) else null
                    recordField(propName, fType, fVis, defExpr, "", "")
                }
            }
            is Fields.Unit -> {}
        }
    }

    private fun configureTransparentStructWrapper(
        liveField: Pair<String, String>,
        typeParams: String,
        extraMembers: MutableList<String>,
        extraCompanionMembers: MutableList<String>,
    ): Boolean {
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
        for (sym in listOf("$coreNs.ptr.MutPtr", "$coreNs.mem.TypeLayout", "$coreNs.mem.TransparentTypeLayout", "$coreNs.mem.allocateNativeCell", "$coreNs.mem.UnresolvedTypeLayout")) {
            CoreIntrinsicLowering.registerCoreImport(emitter, sym)
        }
        val (fieldName, fieldType) = liveField
        val transparentConcreteForSwift = !isLocalItem && vis == "public " && fieldType.trim() !in unboundedOwnerTypeParams
        val fieldIsConcreteGeneric = isFieldConcreteGeneric(fieldType)
        ctorParams.clear()
        ctorParams += "public val fieldStorage: MutPtr<$fieldType>"
        ctorParams += "public val fieldLayout: TypeLayout<$fieldType> = UnresolvedTypeLayout()"
        bodyFieldProps.clear()
        val setterPrefix = if (fieldIsConcreteGeneric) "internal set" else "set"
        extraMembers +=
            "$indent    public var $fieldName: $fieldType\n" +
            "$indent        get() = fieldLayout.readNative(fieldStorage.rawAddress)\n" +
            "$indent        $setterPrefix(value) { fieldLayout.writeNative(fieldStorage.rawAddress, value) }"
        val alignedFieldLayout =
            sourceStorageAlignment(item)?.let {
                CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.mem.AlignedTypeLayout")
                "AlignedTypeLayout(fieldLayout, ${it}L)"
            } ?: "fieldLayout"
        val secCtorVis = if (transparentConcreteForSwift) "internal" else "public"
        extraMembers +=
            "$indent    $secCtorVis constructor($fieldName: $fieldType, fieldLayout: TypeLayout<$fieldType> = UnresolvedTypeLayout()) : " +
            "this(allocateNativeCell($fieldName, $alignedFieldLayout), $alignedFieldLayout)"
        extraMembers +=
            "$indent    public fun pointerLayout(): TypeLayout<$selfTypeWithGenerics> = " +
            "TransparentTypeLayout(fieldLayout, { ptr, layout -> $selfTypeWithGenerics(ptr, layout) }, { it.$fieldName })"
        if (transparentConcreteForSwift) {
            val fnTypeParams = if (typeParams.isNotEmpty()) "$typeParams " else ""
            extraCompanionMembers +=
                "$indent        public operator fun ${fnTypeParams}invoke($fieldName: $fieldType, fieldLayout: TypeLayout<$fieldType> = UnresolvedTypeLayout()): $selfTypeWithGenerics = " +
                "$selfTypeWithGenerics($fieldName, fieldLayout)"
            extraCompanionMembers +=
                "$indent        public fun ${fnTypeParams}fromFieldStorage(fieldStorage: MutPtr<$fieldType>, fieldLayout: TypeLayout<$fieldType> = UnresolvedTypeLayout()): $selfTypeWithGenerics = " +
                "$selfTypeWithGenerics(fieldStorage, fieldLayout)"
        }
        return transparentConcreteForSwift
    }

    private fun appendAutoCloseableDropBridgeIfEligible(
        effectiveAttachedImpls: List<Item.Impl>,
        liveField: Pair<String, String>?,
        isDataClass: Boolean,
        useBodyPropertiesForLargeStruct: Boolean,
        superTraits: MutableList<String>,
        extraMembers: MutableList<String>,
    ) {
        val dropImplEntry =
            effectiveAttachedImpls.firstNotNullOfOrNull { impl ->
                if (isDocOnlyCfgAttribute(impl.attrs)) return@firstNotNullOfOrNull null
                if (impl.traitPath?.path?.lastCleanIdent() != "Drop") return@firstNotNullOfOrNull null
                val dropFn =
                    impl.items.filterIsInstance<ImplItem.Fn>().firstOrNull { fn ->
                        val fnGenericParams = fn.sig.generics.params
                        !isDocOnlyCfgAttribute(fn.attrs) &&
                            fn.sig.hasSelfReceiver() &&
                            fn.sig.inputs.len() == 1 &&
                            fnGenericParams.isEmpty() &&
                            rustFunctionIdentToKotlin(fn.sig.ident.toString()) == "drop"
                    } ?: return@firstNotNullOfOrNull null
                impl to dropFn
            } ?: return
        val (dropImpl, dropFn) = dropImplEntry
        val allParamsForDrop =
            buildList {
                addAll(emitter.mergeGenericParams(item.generics, dropFn.sig.generics, includeOwnerParams = true))
                if (dropImpl.generics !== item.generics) addAll(dropImpl.generics.params.toList())
            }
        val dropWitnessSpecs =
            emitter.collectTypeParamWitnessSpecs(
                sig = dropFn.sig,
                combinedParams = allParamsForDrop,
                ownerWhere = item.generics.whereClause,
                implWhere = if (dropImpl.generics !== item.generics) dropImpl.generics.whereClause else null,
                fnWhere = dropFn.sig.generics.whereClause,
                block = dropFn.block,
                selfTypeName = selfTypeWithGenerics,
                suppressedBoundsOut = linkedMapOf(),
            )
        val hasWitnessedOwnerOrImplBounds = dropWitnessSpecs.isNotEmpty()
        if (!hasWitnessedOwnerOrImplBounds &&
            !isLocalItem &&
            liveField == null &&
            !useFinalAbiLayout &&
            !isDataClass &&
            !useBodyPropertiesForLargeStruct &&
            fieldNameAndTypes.none { it.first == "close" } &&
            !attachedImplDefinesInstanceMethod(effectiveAttachedImpls, "close") &&
            superTraits.none { it.simpleKotlinTypeName() == "AutoCloseable" }
        ) {
            superTraits += "AutoCloseable"
            extraMembers += "$indent    override fun close() {\n$indent        this.drop()\n$indent    }"
        }
    }

    private fun collectConstGenericProperties(): List<String> {
        if (isLocalItem) return emptyList()
        return buildList {
            val seenConstProps = fieldNameAndTypes.mapTo(hashSetOf()) { it.first }
            for (gp in item.generics.params.toList()) {
                if (gp !is GenericParam.ConstParam) continue
                val cName = rustValueIdentToKotlin(gp.ident.toString().removePrefix("r#"))
                if (!seenConstProps.add(cName)) continue
                val cType = emitter.renderType(gp.ty, selfTypeWithGenerics)
                val defVal = gp.default?.expr?.let { emitter.renderExpr(it, "", selfTypeWithGenerics) } ?: defaultInitializerForType(cType)
                add("$indent    public val $cName: $cType = $defVal")
            }
        }
    }
}

private class UnionDeclarationEmitter(
    emitter: KotlinJ2KEmitter,
    private val item: Item.Union,
    private val attachedImpls: List<Item.Impl>,
    indent: String,
    isLocalItem: Boolean,
    private val sb: StringBuilder,
    vis: String,
    typeName: String,
) : NominalTypeDeclarationEmitter(emitter, typeName, item.generics, indent, isLocalItem, vis, ownerSimpleNameForTypeArgs = null) {
    fun emit(): String {
        val typeParams = emitter.renderTypeParameters(item.generics, selfTypeName = selfTypeWithGenerics)
        val seenFieldNames = hashSetOf<String>()
        val unionFields = item.fields.named.toList()
        for ((idx, field) in unionFields.withIndex()) {
            if (isDocOnlyCfgAttribute(field.attrs)) continue
            val fName = rustFieldIdentToKotlin(field.ident?.toString() ?: "field$idx")
            if (!seenFieldNames.add(fName)) continue
            val combinedHeader = emitter.renderLeadingTrivia(field, "$indent    ") + emitter.renderKDocFromAttributes(field.attrs, "$indent    ")
            val docPrefix = if (combinedHeader.isNotEmpty()) "\n$combinedHeader$indent    " else ""
            val fVis = emitter.renderVisibility(field.vis)
            val fType = renderFieldTypeWithCallbackHint(field.ty, "${fName.replaceFirstChar { it.uppercase() }}Callback")
            if (useFinalAbiLayout) {
                val defVal = emitter.renderStructFieldDefault(field.ty, fType, selfTypeWithGenerics)
                recordField(fName, fType, fVis, defVal, combinedHeader, docPrefix)
            } else {
                val nullableType = if (emitter.isAlreadyNullableRenderedType(fType)) fType else "$fType?"
                recordField(fName, nullableType, fVis, "null", combinedHeader, docPrefix, recordBodyPropertyAndNameType = false)
            }
        }
        val useBodyPropertiesForLargeUnion = useFinalAbiLayout && ctorParams.size > 64
        val needsSwiftSafeUnionCtor =
            !useBodyPropertiesForLargeUnion &&
                !isLocalItem &&
                vis == "public " &&
                fieldSpecs.any { it.vis == "public " && it.isConcreteGenericForSwift }
        val wouldHaveBeenDataClass = useFinalAbiLayout && ctorParams.size in 1..16 && typeParams.isEmpty()
        val swiftSafeUnionProps = mutableListOf<String>()
        val extraUnionMembers = mutableListOf<String>()
        val extraUnionCompanionMembers = mutableListOf<String>()
        val hasExplicitDebug = emitter.hasExplicitOriginalDebugImpl(emitter.originalFile.items, typeName)
        val filteredImpls =
            attachedImpls.filterNot { impl ->
                val traitIdent = impl.traitPath?.path?.lastIdentText()
                (!hasExplicitDebug && traitIdent == "Debug") || (useFinalAbiLayout && traitIdent == "Default")
            }
        if (needsSwiftSafeUnionCtor) {
            emitter.populateSwiftSafeCtorAndDataMethods(
                specs = fieldSpecs,
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
        if (isDataClass && fieldNameAndTypes.any { emitter.isPrimitiveArrayRenderedType(it.second) }) {
            extraUnionMembers.addAll(emitter.buildArrayAwareDataClassMethods(typeName, fieldNameAndTypes, "$indent    "))
        }
        val classKeyword =
            when {
                isDataClass -> "data class "
                useFinalAbiLayout || needsSwiftSafeUnionCtor -> "class "
                else -> "open class "
            }
        sb.append("$indent$vis$classKeyword$typeName$typeParams")
        if (ctorParams.isNotEmpty() && !useBodyPropertiesForLargeUnion) {
            emitter.appendFormattedCtorParams(sb, indent, if (needsSwiftSafeUnionCtor) " internal constructor" else "", ctorParams)
        }
        val memberLines =
            emitter.renderAttachedImplMembers(
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
            sb.append(" {\n$memberLines\n$indent}")
        }
        return sb.toString()
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
    if (!reserveUniqueSignature(scopeSignatures, "type:$typeName")) return ""
    val (sb, vis) = beginItemHeaderAndVisibility(renderLeadingTrivia(item, indent), item.attrs, item.vis, indent, isLocalItem)
    return StructDeclarationEmitter(this, item, attachedImpls, indent, isLocalItem, sb, vis, typeName).emit()
}

internal fun KotlinJ2KEmitter.renderItemUnion(
    item: Item.Union,
    attachedImpls: List<Item.Impl>,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val typeName = rustTypeIdentToKotlin(item.ident.toString())
    if (!reserveUniqueSignature(scopeSignatures, "type:$typeName")) return ""
    val (sb, vis) = beginItemHeaderAndVisibility(renderLeadingTrivia(item, indent), item.attrs, item.vis, indent, isLocalItem)
    return UnionDeclarationEmitter(this, item, attachedImpls, indent, isLocalItem, sb, vis, typeName).emit()
}

internal fun KotlinJ2KEmitter.hasExplicitOriginalDebugImpl(items: List<Item>, ownerTypeName: String): Boolean {
    for (sub in items) {
        when (sub) {
            is Item.Impl -> {
                if (sub.traitPath?.path?.lastIdentText() == "Debug" &&
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
    if (baseExpr == null) return formatEnumOffsetLiteral(offset, reprNumKind)
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

private val ENUM_SCALAR_CONVERSION_SPECS =
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

internal fun KotlinJ2KEmitter.renderItemEnum(
    item: Item.Enum,
    attachedImpls: List<Item.Impl>,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val typeName = rustTypeIdentToKotlin(item.ident.toString())
    if (!reserveUniqueSignature(scopeSignatures, "type:$typeName")) return ""
    val useFinalAbiLayout = headerContext.rawAbiMode || headerContext.finalClasses
    val multilineHeader = useFinalAbiLayout || headerContext.isBatchCrateOutput
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
            while (!usedTpNames.add(candidate)) candidate = "${tp}Param${suffix++}"
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
        val namedEnumAssocs = sourceAssociatedParameters(item.generics, typeName).map { it.associated }
        val (typeParams, superTraits) =
            withGenericParamScope(item.generics, extraTypeParams = enumParamRenames.values + namedEnumAssocs) {
                renderTypeParameters(item.generics, selfTypeName = selfTypeWithGenerics) to
                    renderDistinctSuperTraits(attachedImpls, selfTypeWithGenerics, ownerIsPublic = (vis == "public "))
            }
        if (variants.isEmpty() && typeParams.isEmpty() && superTraits.isEmpty() && attachedImpls.isEmpty()) {
            val emptyClassKw = if (useFinalAbiLayout) "class " else "open class "
            val ctorSig = if (multilineHeader) "(\n$indent    public val handle: Long = -1L,\n$indent)" else "(public val handle: Long = -1L)"
            sb.append("${indent}${vis}${emptyClassKw}$typeName$ctorSig {\n$indent    public val ptr: Long get() = handle\n$indent}")
            return sb.toString()
        }
        if (variants.isEmpty() && typeParams.isEmpty()) {
            val attachedMembers =
                withOwnerSupertraitScope(superTraits, ownerIsPublic = (vis == "public "), includeReceiverlessMethods = true) {
                    renderAttachedImplMembers(
                        typeName = selfTypeWithGenerics,
                        ownerGenerics = item.generics,
                        attachedImpls = attachedImpls,
                        indent = "$indent    ",
                        isLocalClass = isLocalItem,
                        isObjectOwner = true,
                    )
                }
            sb.append("${indent}${vis}object $typeName")
            if (superTraits.isNotEmpty()) sb.append(" : ").append(superTraits.joinToString(", "))
            if (attachedMembers.isNotBlank()) sb.append(" {\n$attachedMembers\n$indent}")
            return sb.toString()
        }
        val isSimpleEnum = variants.isNotEmpty() && variants.all { it.fields is Fields.Unit }
        val hasVariantCollidingWithTypeName = isSimpleEnum && variants.any { rustTypeIdentToKotlin(it.ident.toString()) == typeName }
        if ((useFinalAbiLayout || hasVariantCollidingWithTypeName) && isSimpleEnum && typeParams.isEmpty() && superTraits.isEmpty()) {
            return renderReprKotlinEnumClass(sb, item, variants, attachedImpls, typeName, selfTypeWithGenerics, vis, indent, isLocalItem, multilineHeader)
        }

        val enumAssocTypeParamNames = sourceAssociatedParameters(item.generics).map { it.associated }
        val attachedMembers =
            withOwnerSupertraitScope(superTraits, ownerIsPublic = (vis == "public ")) {
                withGenericParamScope(item.generics, extraTypeParams = enumParamRenames.values + enumAssocTypeParamNames) {
                    renderAttachedImplMembers(
                        selfTypeWithGenerics,
                        item.generics,
                        attachedImpls,
                        "$indent    ",
                        isLocalClass = isLocalItem,
                        emitEmptyCompanion = hasStandaloneCompanionExtensionImpl(typeName),
                    )
                }
            }
        val hasDiscriminants = isSimpleEnum && typeParams.isEmpty() && variants.any { it.discriminant != null }
        val reprKotlinType = if (hasDiscriminants) (extractEnumReprKotlinType(item.attrs) ?: "Int") else null
        val reprNumKind = reprKotlinType?.let(this::resolveNumericKind)

        sb.append("${indent}${vis}sealed class $typeName$typeParams")
        if (reprKotlinType != null) {
            sb.append(
                if (multilineHeader) {
                    "(\n$indent    public val value: $reprKotlinType,\n$indent)"
                } else {
                    "(public val value: $reprKotlinType)"
                },
            )
        }
        if (superTraits.isNotEmpty()) sb.append(" : ").append(superTraits.joinToString(", "))
        sb.append(" {\n")

        withGenericParamScope(item.generics, extraTypeParams = enumParamRenames.values + enumAssocTypeParamNames) {
            var prevVariantName: String? = null
            for (v in variants) {
                val vName = rustTypeIdentToKotlin(v.ident.toString())
                val discArg =
                    if (reprKotlinType != null) {
                        val explicitExpr = v.discriminant?.expr
                        when {
                            explicitExpr != null ->
                                renderTypedNumericInitializer(explicitExpr, reprNumKind, "$indent    ", selfTypeName = selfTypeWithGenerics)
                            prevVariantName != null -> formatSealedVariantStep("$prevVariantName.value", reprNumKind)
                            else -> defaultInitializerForType(reprKotlinType)
                        }
                    } else {
                        null
                    }
                val renderedVariant =
                    renderEnumVariantClass(
                        variant = v,
                        enumName = typeName,
                        enumGenerics = item.generics,
                        enumTypeParams = typeParams,
                        enumTypeArgsUse = typeArgsUse,
                        indent = "$indent    ",
                        discriminantArg = discArg,
                        isPublicEnum = !isLocalItem && vis == "public ",
                    )
                sb.append(renderedVariant).append('\n')
                prevVariantName = vName
            }
        }

        val enumTypeParamNamesList = declaredEnumTpNames.map { enumParamRenames[it] ?: it }
        val variantRawNames = variants.mapTo(hashSetOf()) { it.ident.toString().removePrefix("r#") }
        if (enumTypeParamNamesList.size == 2) {
            val (firstTp, secondTp) = enumTypeParamNamesList[0] to enumTypeParamNamesList[1]
            if (typeName in setOf("Result", "RustResult") && "Ok" in variantRawNames && "Err" in variantRawNames) {
                appendTwoVariantAccessorPair(sb, indent, "okValue", "Ok", firstTp, "errValue", "Err", secondTp)
            } else if (typeName == "ControlFlow" && "Break" in variantRawNames && "Continue" in variantRawNames) {
                appendTwoVariantAccessorPair(sb, indent, "cfBreakValue", "Break", firstTp, "cfContinueValue", "Continue", secondTp)
            }
        }
        if (hasDeriveTrait(item.attrs, "PartialEq")) {
            sb.append("\n$indent    public fun rustEquals(other: $selfTypeWithGenerics): Boolean = this == other\n")
        }
        if (isSimpleEnum && typeParams.isEmpty() && variants.isNotEmpty()) {
            if (reprKotlinType != null) {
                appendDiscriminantConversionMethods(sb, indent, attachedMembers, "value", reprKotlinType)
            } else {
                val variantBranches = variants.mapIndexed { idx, v -> "$indent        ${rustTypeIdentToKotlin(v.ident.toString())} -> $idx" }
                sb.append("\n$indent    private fun kmDiscriminant(): Int =\n$indent        when (this) {\n${variantBranches.joinToString("\n")}\n$indent        }\n")
                appendDiscriminantConversionMethods(sb, indent, attachedMembers, "kmDiscriminant()", "Int")
            }
        }
        if (attachedMembers.isNotEmpty()) sb.append('\n').append(attachedMembers).append('\n')
        sb.append(indent).append("}")
        sb.toString()
    } finally {
        activeEnclosingEnumVariantNames = prevEnclosingVariants
        renamedTypeParamIdentifiers = prevRenamedParams
    }
}

private fun KotlinJ2KEmitter.renderReprKotlinEnumClass(
    sb: StringBuilder,
    item: Item.Enum,
    variants: List<Variant>,
    attachedImpls: List<Item.Impl>,
    typeName: String,
    selfTypeWithGenerics: String,
    vis: String,
    indent: String,
    isLocalItem: Boolean,
    multilineHeader: Boolean,
): String {
    val reprKotlinType = extractEnumReprKotlinType(item.attrs) ?: "UInt"
    val reprNumKind = resolveNumericKind(reprKotlinType) ?: NumericKind.U32
    val enumFilteredImpls =
        attachedImpls.filterNot { it.traitPath?.path?.lastIdentText() in setOf("Clone", "Default") }
    val attachedMembers =
        withOwnerSupertraitScope(emptyList(), ownerIsPublic = (vis == "public "), clearOverrides = true) {
            renderAttachedImplMembers(
                selfTypeWithGenerics,
                item.generics,
                enumFilteredImpls,
                "$indent    ",
                isLocalClass = isLocalItem,
                emitEmptyCompanion = hasStandaloneCompanionExtensionImpl(typeName),
            )
        }
    val ctorSig = if (multilineHeader) "(\n$indent    public val value: $reprKotlinType,\n$indent)" else "(public val value: $reprKotlinType)"
    sb.append("${indent}${vis}enum class $typeName$ctorSig {\n")
    var baseDiscExpr: String? = null
    var stepOffset = 0
    val seenEntryNames = hashSetOf<String>()
    val entryLines = mutableListOf<String>()
    for (v in variants) {
        val rawVar = v.ident.toString().removePrefix("r#")
        val rawEntryName = if (isScreamingSnakeIdent(rawVar)) rustConstIdentToKotlin(v.ident.toString()) else rustTypeIdentToKotlin(v.ident.toString())
        val entryName = if (rawEntryName == typeName) "${rawEntryName}Variant" else rawEntryName
        if (!seenEntryNames.add(entryName)) continue
        val vHeader = renderLeadingTrivia(v, "$indent    ") + renderKDocFromAttributes(v.attrs, "$indent    ")
        val explicitExpr = v.discriminant?.expr
        val discArg =
            if (explicitExpr != null) {
                val rendered = renderTypedNumericInitializer(explicitExpr, reprNumKind, "$indent    ", selfTypeName = selfTypeWithGenerics)
                baseDiscExpr = rendered
                stepOffset = 1
                rendered
            } else {
                formatEnumSteppedDiscriminant(baseDiscExpr, stepOffset++, reprNumKind)
            }
        entryLines += "$vHeader$indent    $entryName($discArg)"
    }
    if (attachedMembers.isNotEmpty()) {
        sb.append(entryLines.joinToString(",\n")).append(";\n\n$attachedMembers\n")
    } else {
        sb.append(entryLines.joinToString(",\n")).append(",\n")
    }
    sb.append(indent).append("}")
    return sb.toString()
}

private fun formatSealedVariantStep(prevValueExpr: String, reprNumKind: NumericKind?): String {
    val stepOne =
        when (reprNumKind) {
            NumericKind.U8, NumericKind.U16, NumericKind.U32 -> "1u"
            NumericKind.U64 -> "1uL"
            NumericKind.I64 -> "1L"
            else -> "1"
        }
    val rawStep = "$prevValueExpr + $stepOne"
    return when (reprNumKind) {
        NumericKind.U8 -> "($rawStep).toUByte()"
        NumericKind.U16 -> "($rawStep).toUShort()"
        NumericKind.I8 -> "($rawStep).toByte()"
        NumericKind.I16 -> "($rawStep).toShort()"
        else -> rawStep
    }
}

private fun appendTwoVariantAccessorPair(
    sb: StringBuilder,
    indent: String,
    firstFn: String,
    firstVariant: String,
    firstTp: String,
    secondFn: String,
    secondVariant: String,
    secondTp: String,
) {
    sb.append("\n$indent    public fun $firstFn(): $firstTp = when (this) { is $firstVariant -> value; is $secondVariant -> error(\"not $firstVariant\") }\n")
    sb.append("$indent    public fun $secondFn(): $secondTp = when (this) { is $secondVariant -> value; is $firstVariant -> error(\"not $secondVariant\") }\n")
}

private fun KotlinJ2KEmitter.appendDiscriminantConversionMethods(
    sb: StringBuilder,
    indent: String,
    attachedMembers: String,
    sourceExpr: String,
    directType: String,
) {
    val memberWalker = KotlinTokenWalker(attachedMembers)
    for ((fnName, retType) in ENUM_SCALAR_CONVERSION_SPECS) {
        if (!memberWalker.declaresFunctionNamed(fnName)) {
            val bodyExpr = if (retType == directType) sourceExpr else "$sourceExpr.$fnName()"
            sb.append("\n$indent    public fun $fnName(): $retType = $bodyExpr\n")
        }
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
                sb.append("${indent}public data object $vName : $enumName($superCallArgs)")
            } else {
                sb.append("${indent}public class $vName$enumTypeParams() : $enumName$enumTypeArgsUse($superCallArgs)")
            }
            return sb.toString()
        }
        is Fields.Unnamed -> {
            val list = fields.fields.unnamed.toList()
            val specs =
                list.mapIndexed { idx, f ->
                    val pName = tupleFieldPropertyName(idx, list.size)
                    val pType = renderType(f.ty, selfTypeName = enumSelfType)
                    SwiftSafeFieldSpec(pName, pType, "public ", null, "", "", isPublicEnum && isConcreteGenericTypeForSwiftExport(pType, unboundedEnumTypeParams))
                }
            appendEnumVariantWithFields(sb, vName, variantSelfType, enumName, enumTypeParams, enumTypeArgsUse, typeParamCount, superCallArgs, specs, indent)
        }
        is Fields.Named -> {
            val seenFieldNames = hashSetOf<String>()
            val namedVariantFields = fields.fields.named.toList()
            val specs =
                namedVariantFields.mapIndexedNotNull { idx, f ->
                    if (isDocOnlyCfgAttribute(f.attrs)) return@mapIndexedNotNull null
                    val pName = rustFieldIdentToKotlin(f.ident?.toString() ?: "field$idx")
                    if (!seenFieldNames.add(pName)) return@mapIndexedNotNull null
                    val pType = renderType(f.ty, selfTypeName = enumSelfType)
                    SwiftSafeFieldSpec(pName, pType, "public ", null, "", "", isPublicEnum && isConcreteGenericTypeForSwiftExport(pType, unboundedEnumTypeParams))
                }
            appendEnumVariantWithFields(sb, vName, variantSelfType, enumName, enumTypeParams, enumTypeArgsUse, typeParamCount, superCallArgs, specs, indent)
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
    val addErrAlias =
        enumName in setOf("Result", "RustResult") &&
            typeParamCount == 2 &&
            specs.size == 1 &&
            specs[0].name == "value" &&
            vName == "Err"
    if (specs.none { it.isConcreteGenericForSwift }) {
        val params = specs.joinToString(", ") { "public var ${it.name}: ${it.renderedType}" }
        sb.append("${indent}public data class $vName$enumTypeParams($params) : $enumName$enumTypeArgsUse($superCallArgs)")
        if (addErrAlias) {
            sb.append(" {\n$indent    public val error: ${specs[0].renderedType} get() = value\n$indent}")
        }
        return
    }
    val ctorParams =
        specs.joinToString(", ") { spec ->
            if (spec.isConcreteGenericForSwift) "${spec.name}CtorArg: ${spec.renderedType}" else "public var ${spec.name}: ${spec.renderedType}"
        }
    sb.append("${indent}public class $vName$enumTypeParams internal constructor($ctorParams) : $enumName$enumTypeArgsUse($superCallArgs) {\n")
    for (spec in specs) {
        if (spec.isConcreteGenericForSwift) {
            sb.append("$indent    public var ${spec.name}: ${spec.renderedType} = ${spec.name}CtorArg\n$indent        internal set\n")
        }
    }
    if (addErrAlias) {
        sb.append("\n$indent    public val error: ${specs[0].renderedType} get() = value\n")
    }
    for (m in buildSwiftSafeSyntheticDataMethods(vName, typeParamCount, variantSelfType, specs, includeCopyAndComponents = true, hasCopyCollision = false, memberIndent = "$indent    ")) {
        sb.append('\n').append(m).append('\n')
    }
    val companionInvoke = renderSwiftSafeCompanionInvoke(enumTypeParams, variantSelfType, specs, "$indent    ")
    sb.append("\n$indent    public companion object {\n$companionInvoke\n$indent    }\n$indent}")
}

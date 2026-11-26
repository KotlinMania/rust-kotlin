package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Span
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.FieldValue
import io.github.kotlinmania.syn.ForeignItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.ReturnType
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.gen.Visit
import io.github.kotlinmania.syn.gen.clone
import io.github.kotlinmania.syn.span

internal fun KotlinJ2KEmitter.singlePlainPathCleanIdent(expr: Expr?): String? {
    val pathExpr = expr?.let(::unwrapParenOrGroupExpr) as? Expr.Path ?: return null
    return if (pathExpr.qself == null && pathExpr.path.segments.size == 1) pathExpr.path.firstCleanIdent() else null
}

private fun KotlinJ2KEmitter.resolveDeclaredOrCrateStructFieldType(
    ownerTypeName: String,
    fieldProp: String?,
    selfTypeName: String?,
    fallbackDefaultValueField: Boolean = false,
): String? {
    val synMap = inspector.structFieldSynTypesByType[ownerTypeName]
    val localRendered =
        if (fieldProp != null) {
            synMap?.get(fieldProp)?.let { renderType(it, selfTypeName) }
        } else {
            synMap?.values?.singleOrNull()?.let { renderType(it, selfTypeName) }
        }
    if (localRendered != null) return localRendered
    val crateScoped =
        scopedAbiKeysForRenderedType(ownerTypeName).firstNotNullOfOrNull { key ->
            if (fieldProp != null) {
                headerContext.crateStructFieldTypes["$key.$fieldProp"]
            } else if (fallbackDefaultValueField) {
                val fieldEntries = headerContext.crateStructFieldTypes.entries
                headerContext.crateStructFieldTypes["$key.value"]
                    ?: fieldEntries.singleOrNull { it.key.startsWith("$key.") }?.value
            } else {
                null
            }
        }
    if (crateScoped != null) return crateScoped
    return when {
        fieldProp != null -> headerContext.crateStructFieldTypes["$ownerTypeName.$fieldProp"]
        fallbackDefaultValueField -> headerContext.crateStructFieldTypes["$ownerTypeName.value"]
        else -> null
    }
}

internal object BorrowedPlacePropertyDelegateTemplate {
    fun renderWithCachedOwner(
        indent: String,
        varName: String,
        ownerExpr: String,
        leafField: String,
    ): String =
        renderBlock(
            indent = indent,
            varName = varName,
            extraSetupLine = "$indent    val owner = $ownerExpr",
            targetAccess = "owner.$leafField",
        )

    fun renderDirectReceiver(
        indent: String,
        varName: String,
        receiverVar: String,
        fieldProp: String,
    ): String =
        renderBlock(
            indent = indent,
            varName = varName,
            extraSetupLine = null,
            targetAccess = "$receiverVar.$fieldProp",
        )

    private fun renderBlock(
        indent: String,
        varName: String,
        extraSetupLine: String?,
        targetAccess: String,
    ): String =
        buildString {
            appendLine("${indent}var $varName by run {")
            appendLine("$indent    fun <V> place(read: () -> V, write: (V) -> Unit): kotlin.properties.ReadWriteProperty<Any?, V> =")
            appendLine("$indent        object : kotlin.properties.ReadWriteProperty<Any?, V> {")
            appendLine("$indent            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): V = read()")
            appendLine("$indent            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: V) { write(value) }")
            appendLine("$indent        }")
            if (extraSetupLine != null) appendLine(extraSetupLine)
            appendLine("$indent    place({ $targetAccess }, { $targetAccess = it })")
            append("$indent}")
        }
}

private fun Stmt.nonDrainingSpan(): Span =
    when (this) {
        is Stmt.Local -> attrs.firstOrNull()?.poundToken?.span ?: letToken.span
        is Stmt.MacroStmt -> attrs.firstOrNull()?.poundToken?.span ?: mac.path.span()
        is Stmt.ItemStmt -> item.clone().span()
        is Stmt.ExprStmt -> expr.deepCopy().span()
    }

internal fun isLeadingCfgStmtDisabled(stmt: Stmt): Boolean =
    stmt.leadingOuterAttributes().any { attr ->
        val meta = attr.meta
        meta is Meta.List && meta.path.isIdent("cfg") && isDocOnlyCfgPredicateTokens(meta.tokens.clone().toList())
    }

private fun extractLeadingCfgSignature(stmt: Stmt): Pair<Boolean, String>? {
    var found: Pair<Boolean, String>? = null
    for (attr in stmt.leadingOuterAttributes()) {
        val meta = attr.meta
        if (meta is Meta.List && meta.path.isIdent("cfg")) {
            if (found != null) return null
            val sig = RustTokenCursor.extractCfgComplementSignature(meta.tokens.clone().toList()) ?: continue
            found = !sig.second to sig.first
        }
    }
    return found
}

internal fun KotlinJ2KEmitter.deduplicateComplementaryCfgStmts(rawStmts: List<Stmt>): List<Stmt> {
    fun localIdentText(s: Stmt): String? {
        val pat = (s as? Stmt.Local)?.pat as? Pat.Ident ?: return null
        return pat.ident.toString()
    }

    fun itemFnIdentText(s: Stmt): String? {
        val fnItem = (s as? Stmt.ItemStmt)?.item as? Item.Fn ?: return null
        return fnItem.sig.ident.toString()
    }

    val surviving = rawStmts.filterNot(::isLeadingCfgStmtDisabled)
    if (surviving.size < 2) return surviving
    val lastRawExecIdx = surviving.indexOfLast { it !is Stmt.ItemStmt }
    val filtered = ArrayList<Stmt>(surviving.size)
    var i = 0
    while (i < surviving.size) {
        val cur = surviving[i]
        if (i + 1 < surviving.size) {
            val next = surviving[i + 1]
            val sig1 = extractLeadingCfgSignature(cur)
            val sig2 = if (sig1 != null) extractLeadingCfgSignature(next) else null
            if (sig1 != null && sig2 != null && sig1.second == sig2.second && sig1.first != sig2.first) {
                val curIdent = localIdentText(cur)
                val sameLocalBinding = curIdent != null && curIdent == localIdentText(next)
                val curFn = itemFnIdentText(cur)
                val sameItemFnName = curFn != null && curFn == itemFnIdentText(next)
                val complementaryTail =
                    (i + 1 == lastRawExecIdx) &&
                        cur !is Stmt.Local &&
                        next !is Stmt.Local &&
                        cur !is Stmt.ItemStmt &&
                        next !is Stmt.ItemStmt
                if (sameLocalBinding || sameItemFnName || complementaryTail) {
                    filtered += if (sig1.first) cur else next
                    i += 2
                    continue
                }
            }
        }
        filtered += cur
        i++
    }
    return filtered
}

private class BlockEmissionScope(
    val emitter: KotlinJ2KEmitter,
    val stmts: List<Stmt>,
    val selfTypeName: String?,
) {
    val lastExecutableIdx: Int = stmts.indexOfLast { it !is Stmt.ItemStmt }
    val localScopeItems: List<Item> = stmts.mapNotNull { (it as? Stmt.ItemStmt)?.item }
    val localAttachableNames: Set<String> =
        localScopeItems
            .mapNotNull { item ->
                when (item) {
                    is Item.Struct -> rustTypeIdentToKotlin(item.ident.toString())
                    is Item.Enum -> rustTypeIdentToKotlin(item.ident.toString())
                    is Item.Union -> rustTypeIdentToKotlin(item.ident.toString())
                    else -> null
                }
            }.toSet()
    val localBlockImpls: Map<String, List<Item.Impl>> =
        buildMap<String, MutableList<Item.Impl>> {
            for (item in localScopeItems) {
                if (item is Item.Impl) {
                    val ownerType = emitter.extractAttachableDirectOwnerTypeName(item, localScopeItems)
                    if (ownerType != null && ownerType in localAttachableNames) {
                        getOrPut(ownerType) { mutableListOf() }.add(item)
                    }
                }
            }
        }
    val localBlockSignatures = linkedMapOf<String, Int>()

    private val savedBlockLocalFnRawNames = hashSetOf<String>()
    private val savedBlockLocalInlineModNames = hashSetOf<String>()
    private val prevBlockLocalFnReturnSynTypes = linkedMapOf<String, SynType?>()
    private val prevBlockLocalTypeAliases = linkedMapOf<String, SynType?>()
    private val savedBlockLocalExternFnNames = linkedSetOf<String>()

    private fun recordLocalCallableReturn(cleanRaw: String, ktName: String, retTy: SynType) {
        val map = emitter.inspector.topLevelFnReturnSynTypes
        if (cleanRaw !in prevBlockLocalFnReturnSynTypes) prevBlockLocalFnReturnSynTypes[cleanRaw] = map[cleanRaw]
        if (ktName !in prevBlockLocalFnReturnSynTypes) prevBlockLocalFnReturnSynTypes[ktName] = map[ktName]
        map[cleanRaw] = retTy
        map[ktName] = retTy
    }

    private fun registerLocalItemsAndScopes(prevLocalForeignRenames: Map<String, String>) {
        val blockLocalForeignRenames = linkedMapOf<String, String>()
        val blockFnParamKinds = linkedMapOf<String, List<NumericKind?>?>()

        for (stmtItem in localScopeItems) {
            if (itemHasDocOnlyCfg(stmtItem)) continue
            when (stmtItem) {
                is Item.Fn -> {
                    val cleanRaw = stmtItem.sig.cleanFnName()
                    if (cleanRaw !in emitter.inspector.declaredTopLevelFnRawNames) {
                        savedBlockLocalFnRawNames += cleanRaw
                        emitter.inspector.declaredTopLevelFnRawNames += cleanRaw
                    }
                    (stmtItem.sig.output as? ReturnType.TypeReturn)?.let { retOut ->
                        recordLocalCallableReturn(cleanRaw, rustFunctionIdentToKotlin(stmtItem.sig.ident.toString()), retOut.ty)
                    }
                    emitter.extractFnParamNumericKinds(stmtItem, selfTypeName)?.let { (fnName, kinds) ->
                        blockFnParamKinds[fnName] = if (fnName in blockFnParamKinds) null else kinds
                    }
                }
                is Item.ForeignMod -> {
                    for (foreign in stmtItem.items) {
                        if (foreignItemHasDocOnlyCfg(foreign) || foreign !is ForeignItem.Fn) continue
                        val cleanRaw = foreign.sig.cleanFnName()
                        if (cleanRaw !in emitter.inspector.declaredTopLevelFnRawNames) {
                            savedBlockLocalFnRawNames += cleanRaw
                            emitter.inspector.declaredTopLevelFnRawNames += cleanRaw
                        }
                        val ktFn = rustFunctionIdentToKotlin(foreign.sig.ident.toString())
                        val effectiveLocalKt =
                            if (ktFn == emitter.currentEnclosingFunctionName) {
                                "extern${ktFn.replaceFirstChar { it.uppercase() }}".also { blockLocalForeignRenames[cleanRaw] = it }
                            } else {
                                ktFn
                            }
                        if (effectiveLocalKt !in emitter.inspector.externFnKotlinNames) {
                            savedBlockLocalExternFnNames += effectiveLocalKt
                            emitter.inspector.externFnKotlinNames += effectiveLocalKt
                        }
                        val retOut = foreign.sig.output as? ReturnType.TypeReturn
                        val foreignGenerics = foreign.sig.generics
                        if (retOut != null && foreignGenerics.params.isEmpty()) {
                            recordLocalCallableReturn(cleanRaw, effectiveLocalKt, retOut.ty)
                        }
                    }
                }
                is Item.Use -> emitter.registerUseItemInScope(stmtItem)
                is Item.ItemType -> {
                    val ktAlias = rustTypeIdentToKotlin(stmtItem.ident.toString())
                    if (ktAlias !in prevBlockLocalTypeAliases) {
                        prevBlockLocalTypeAliases[ktAlias] = emitter.inspector.typeAliasTargets[ktAlias]
                    }
                    emitter.inspector.typeAliasTargets[ktAlias] = stmtItem.ty
                }
                is Item.Mod ->
                    if (stmtItem.content is ModContent.Inline) {
                        val modRaw = stmtItem.ident.toString().removePrefix("r#")
                        if (modRaw !in emitter.inspector.localInlineModuleNames) {
                            savedBlockLocalInlineModNames += modRaw
                            emitter.inspector.localInlineModuleNames += modRaw
                        }
                    }
                else -> {}
            }
        }
        if (blockLocalForeignRenames.isNotEmpty()) {
            emitter.activeLocalForeignFnRenames = prevLocalForeignRenames + blockLocalForeignRenames
        }
        if (blockFnParamKinds.isNotEmpty()) {
            val updated = emitter.currentFunctionParamNumericKinds.toMutableMap()
            for ((fnName, kinds) in blockFnParamKinds) {
                if (kinds == null) updated.remove(fnName) else updated[fnName] = kinds
            }
            emitter.currentFunctionParamNumericKinds = updated
        }
    }

    private fun emitHoistedLocalItems(innerIndent: String, lines: MutableList<String>) {
        val hoistedItems = mutableListOf<Pair<String?, String>>()
        for (stmt in stmts) {
            val itemInStmt = (stmt as? Stmt.ItemStmt)?.item ?: continue
            if (itemHasDocOnlyCfg(itemInStmt) || itemInStmt is Item.Use) continue
            if (itemInStmt is Item.Impl && emitter.extractAttachableDirectOwnerTypeName(itemInStmt, localScopeItems) in localAttachableNames) {
                continue
            }
            val declName =
                when (itemInStmt) {
                    is Item.Struct -> rustTypeIdentToKotlin(itemInStmt.ident.toString())
                    is Item.Enum -> rustTypeIdentToKotlin(itemInStmt.ident.toString())
                    is Item.Union -> rustTypeIdentToKotlin(itemInStmt.ident.toString())
                    is Item.Trait -> rustTypeIdentToKotlin(itemInStmt.ident.toString())
                    is Item.ItemType -> rustTypeIdentToKotlin(itemInStmt.ident.toString())
                    is Item.Fn -> rustFunctionIdentToKotlin(itemInStmt.sig.ident.toString())
                    is Item.Const -> rustConstIdentToKotlin(itemInStmt.ident.toString())
                    else -> null
                }
            val renderedItem =
                emitter.renderStmt(
                    stmt = stmt,
                    indent = innerIndent,
                    selfTypeName = selfTypeName,
                    isTailReturn = false,
                    localTypeImpls = localBlockImpls,
                    localSignatures = localBlockSignatures,
                )
            if (renderedItem.isNotBlank()) hoistedItems += declName to renderedItem
        }
        if (hoistedItems.size <= 1) {
            hoistedItems.forEach { lines += it.second }
            return
        }
        val n = hoistedItems.size
        val deps = Array(n) { hashSetOf<Int>() }
        for (i in 0 until n) {
            val renderedI = hoistedItems[i].second
            for (j in 0 until n) {
                val nameJ = hoistedItems[j].first
                if (i != j && nameJ != null && containsWholeIdentifier(renderedI, nameJ)) deps[i] += j
            }
        }
        val emitted = BooleanArray(n)
        repeat(n) { _ ->
            val picked =
                (0 until n).firstOrNull { idx -> !emitted[idx] && deps[idx].all { d -> emitted[d] } }
                    ?: (0 until n).first { idx -> !emitted[idx] }
            emitted[picked] = true
            lines += hoistedItems[picked].second
        }
    }

    fun executeBlock(
        innerIndent: String,
        returnsValue: Boolean,
        lines: MutableList<String>,
        closeBraceTrivia: String,
        trailingInjectedLines: List<String>,
    ) {
        val prevBorrowedPlaceLocals = emitter.currentBorrowedPlaceLocals
        val prevAddressableLocals = emitter.currentAddressableLocals
        val prevArrayElementTypes = emitter.currentAddressableArrayElementTypes
        val prevSourceParameterTypes = emitter.currentSourceParameterTypes
        val prevFnParamNumericKinds = emitter.currentFunctionParamNumericKinds
        val prevLocalBlockItems = emitter.activeLocalBlockItems
        val prevLocalForeignRenames = emitter.activeLocalForeignFnRenames
        val blockEntryExpectedKind = emitter.currentExpectedNumericKind
        val blockEntryTailExpectsUnitResultOk = emitter.currentTailExpectsUnitResultOk

        emitter.currentExpectedNumericKind = null
        emitter.currentTailExpectsUnitResultOk = false
        emitter.activeLocalBlockItems = localScopeItems + prevLocalBlockItems
        emitter.scopeBuilder.enterCompr()
        try {
            registerLocalItemsAndScopes(prevLocalForeignRenames)
            emitHoistedLocalItems(innerIndent, lines)
            renderStmtsRange(
                startIdx = 0,
                endIdx = stmts.size,
                lines = lines,
                innerIndent = innerIndent,
                returnsValue = returnsValue,
                effectiveLastExecIdx = lastExecutableIdx,
                blockEntryExpectedKind = blockEntryExpectedKind,
                blockEntryTailExpectsUnitResultOk = blockEntryTailExpectsUnitResultOk,
            )
            if (closeBraceTrivia.isNotBlank()) lines += closeBraceTrivia.trimEnd('\n')
            lines.addAll(trailingInjectedLines)
            if (emitter.headerContext.isBatchCrateOutput) emitter.sanitizeBatchBlockLocals(lines)
        } finally {
            emitter.inspector.declaredTopLevelFnRawNames.removeAll(savedBlockLocalFnRawNames)
            emitter.inspector.externFnKotlinNames.removeAll(savedBlockLocalExternFnNames)
            emitter.inspector.localInlineModuleNames.removeAll(savedBlockLocalInlineModNames)
            val retTypesMap = emitter.inspector.topLevelFnReturnSynTypes
            for ((fnKey, prevSyn) in prevBlockLocalFnReturnSynTypes) {
                if (prevSyn == null) retTypesMap.remove(fnKey) else retTypesMap[fnKey] = prevSyn
            }
            val aliasMap = emitter.inspector.typeAliasTargets
            for ((aliasKey, prevSyn) in prevBlockLocalTypeAliases) {
                if (prevSyn == null) aliasMap.remove(aliasKey) else aliasMap[aliasKey] = prevSyn
            }
            emitter.currentExpectedNumericKind = blockEntryExpectedKind
            emitter.currentTailExpectsUnitResultOk = blockEntryTailExpectsUnitResultOk
            emitter.currentBorrowedPlaceLocals = prevBorrowedPlaceLocals
            emitter.currentAddressableLocals = prevAddressableLocals
            emitter.currentAddressableArrayElementTypes = prevArrayElementTypes
            emitter.currentSourceParameterTypes = prevSourceParameterTypes
            emitter.currentFunctionParamNumericKinds = prevFnParamNumericKinds
            emitter.activeLocalBlockItems = prevLocalBlockItems
            emitter.activeLocalForeignFnRenames = prevLocalForeignRenames
            emitter.scopeBuilder.exitCompr()
        }
    }

    fun renderStmtsRange(
        startIdx: Int,
        endIdx: Int,
        lines: MutableList<String>,
        innerIndent: String,
        returnsValue: Boolean,
        effectiveLastExecIdx: Int,
        blockEntryExpectedKind: NumericKind?,
        blockEntryTailExpectsUnitResultOk: Boolean,
    ) {
        var idx = startIdx
        while (idx < endIdx) {
            val stmt = stmts[idx]
            if (stmt is Stmt.ItemStmt) {
                idx++
                continue
            }
            val refCellStep =
                tryRenderRefCellBorrowGuardScope(
                    idx = idx,
                    endIdx = endIdx,
                    innerIndent = innerIndent,
                    returnsValue = returnsValue,
                    effectiveLastExecIdx = effectiveLastExecIdx,
                    blockEntryExpectedKind = blockEntryExpectedKind,
                )
            if (refCellStep != null) {
                if (refCellStep.first.isNotBlank()) lines += refCellStep.first
                idx = refCellStep.second
                continue
            }
            if (stmt is Stmt.Local && stmt.init == null && stmt.pat is Pat.Ident && (stmt.pat as Pat.Ident).subpat == null && idx + 1 < endIdx) {
                if (tryFoldUninitializedLocalWithNext(stmt, stmt.pat as Pat.Ident, stmts[idx + 1], idx, innerIndent, returnsValue, effectiveLastExecIdx, lines)) {
                    idx += 2
                    continue
                }
            }
            val isLastExec = (idx == effectiveLastExecIdx)
            val isTail = isLastExec && returnsValue
            val isValueTailExpr =
                isLastExec &&
                    !returnsValue &&
                    ((stmt is Stmt.ExprStmt && stmt.semiToken == null) || (stmt is Stmt.MacroStmt && stmt.semiToken == null))
            val rendered =
                try {
                    if (isValueTailExpr) {
                        emitter.currentExpectedNumericKind = blockEntryExpectedKind
                        emitter.currentTailExpectsUnitResultOk = blockEntryTailExpectsUnitResultOk
                    } else {
                        emitter.currentExpectedNumericKind = null
                        emitter.currentTailExpectsUnitResultOk = false
                    }
                    emitter.renderStmt(
                        stmt = stmt,
                        indent = innerIndent,
                        selfTypeName = selfTypeName,
                        isTailReturn = isTail,
                        isLastExecutableStmt = isLastExec,
                        localTypeImpls = localBlockImpls,
                        localSignatures = localBlockSignatures,
                        remainingStmts = if (idx + 1 < endIdx) stmts.subList(idx + 1, endIdx) else emptyList(),
                    )
                } finally {
                    emitter.currentExpectedNumericKind = null
                    emitter.currentTailExpectsUnitResultOk = false
                }
            if (rendered.isNotBlank()) lines += rendered
            idx++
        }
    }

    private fun tryFoldUninitializedLocalWithNext(
        stmt: Stmt.Local,
        identPat: Pat.Ident,
        nextStmt: Stmt,
        idx: Int,
        innerIndent: String,
        returnsValue: Boolean,
        effectiveLastExecIdx: Int,
        lines: MutableList<String>,
    ): Boolean {
        val rawVar = identPat.ident.toString().removePrefix("r#")
        val nextMac =
            when {
                nextStmt is Stmt.MacroStmt -> nextStmt.mac
                nextStmt is Stmt.Local && nextStmt.init?.diverge == null && nextStmt.init?.expr is Expr.Macro ->
                    (nextStmt.init!!.expr as Expr.Macro).mac
                else -> null
            }
        if (nextMac != null) {
            val delim = emitter.extractDelimitedMacroParts(nextMac, innerIndent, selfTypeName)
            if (delim != null && delim.second == rawVar) {
                val trivia1 = emitter.renderLeadingTriviaForSpan(stmt.nonDrainingSpan(), innerIndent)
                val trivia2 = emitter.renderLeadingTriviaForSpan(nextStmt.nonDrainingSpan(), innerIndent)
                val ktVar = emitter.bindScopedValueIdent(rawVar, identPat.ident.span(), identPat.mutability != null)
                val tryCall = emitter.renderSourceResultTry("${delim.first}(${delim.third})", "return")
                if (nextStmt is Stmt.MacroStmt) {
                    lines += "${trivia1}${trivia2}${innerIndent}val $ktVar = ($tryCall).content"
                } else {
                    val localNext = nextStmt as Stmt.Local
                    val (tokPat, tokTy) = emitter.renderLocalPattern(localNext.pat, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
                    val tokKeyword = if (emitter.isMutablePattern(localNext.pat)) "var" else "val"
                    val tokSuffix = if (tokTy != null) ": $tokTy" else ""
                    lines += "${trivia1}${trivia2}${innerIndent}val ${ktVar}Group = $tryCall"
                    lines += "${innerIndent}val $ktVar = ${ktVar}Group.content"
                    lines += "${innerIndent}$tokKeyword $tokPat$tokSuffix = ${ktVar}Group.token"
                }
                return true
            }
        }
        val (matchExpr, targetLocalStmt) =
            when {
                nextStmt is Stmt.ExprStmt && nextStmt.expr is Expr.Match && !(idx + 1 == effectiveLastExecIdx && returnsValue) ->
                    (nextStmt.expr as Expr.Match) to null
                nextStmt is Stmt.Local && nextStmt.init?.diverge == null && nextStmt.init?.expr is Expr.Match ->
                    (nextStmt.init!!.expr as Expr.Match) to nextStmt
                else -> return false
            }
        val folded =
            emitter.tryRenderDeferredLocalResultMatchInit(
                identPat = identPat,
                rawVar = rawVar,
                localStmt = stmt,
                matchStmt = nextStmt,
                matchExpr = matchExpr,
                targetLocalStmt = targetLocalStmt,
                innerIndent = innerIndent,
                selfTypeName = selfTypeName,
            ) ?: return false
        lines.addAll(folded)
        return true
    }

    private fun tryRenderRefCellBorrowGuardScope(
        idx: Int,
        endIdx: Int,
        innerIndent: String,
        returnsValue: Boolean,
        effectiveLastExecIdx: Int,
        blockEntryExpectedKind: NumericKind?,
    ): Pair<String, Int>? {
        val stmt = stmts[idx] as? Stmt.Local ?: return null
        val init = stmt.init?.takeIf { it.diverge == null } ?: return null
        val identPat = (emitter.unwrapTransparentPat(stmt.pat) as? Pat.Ident)?.takeIf { it.subpat == null } ?: return null
        val initCall = emitter.unwrapParenOrGroupExpr(init.expr) as? Expr.MethodCall ?: return null
        if (!initCall.args.isEmpty() || initCall.turbofish != null) return null
        val callMethod = initCall.method.toString().removePrefix("r#")
        if (callMethod != "borrow" && callMethod != "borrow_mut") return null
        val innerTy = emitter.inferExprRenderedType(initCall.receiver, selfTypeName).asKotlinRenderedType()?.singleArgOf("RefCell") ?: return null
        val rawVar = identPat.ident.toString().removePrefix("r#")
        val dropIdx = (idx + 1 until endIdx).firstOrNull { emitter.isDropOfLocalStmt(stmts[it], rawVar) }
        val scopeEnd = dropIdx ?: endIdx
        val nextIdx = if (dropIdx != null) dropIdx + 1 else endIdx
        val cellRendered = emitter.renderExpr(initCall.receiver, innerIndent, selfTypeName)
        val cellAtom = if (emitter.isAtomicPostfixReceiver(initCall.receiver, cellRendered)) cellRendered else "($cellRendered)"
        val isMut = (callMethod == "borrow_mut")
        val isStringMut = isMut && innerTy == "String"
        val borrowFn = if (isMut) "borrowMut" else "borrow"
        val useFn = if (isStringMut) "useMut" else "use"
        val guardVarTy = if (isStringMut) "RefMut<String>" else innerTy
        val triviaPrefix = emitter.renderLeadingTriviaForSpan(stmt.nonDrainingSpan(), innerIndent)
        val guardCapturesTail = returnsValue && scopeEnd == endIdx && effectiveLastExecIdx in (idx + 1) until scopeEnd

        val prevTyped = emitter.currentTypedLocals
        val prevMutableLists = emitter.currentMutableListLocals
        val prevSmartCast = emitter.currentSmartCastNonNullLocals
        val scopeLines = mutableListOf<String>()
        emitter.scopeBuilder.enterCompr()
        val boundKtVar =
            try {
                val ktVar = emitter.bindScopedValueIdent(rawVar, identPat.ident.span(), mutable = false)
                emitter.currentTypedLocals = prevTyped + (ktVar to guardVarTy)
                if (!isStringMut && innerTy.startsWith("MutableList<")) {
                    emitter.currentMutableListLocals = prevMutableLists + ktVar
                }
                val capturedTailKind =
                    if (guardCapturesTail) {
                        emitter.currentEnclosingReturnRenderedType?.let(emitter::resolveNumericKind) ?: blockEntryExpectedKind
                    } else {
                        null
                    }
                val capturedTailUnitResultOk =
                    guardCapturesTail && (rustResultOkPayloadType(emitter.currentEnclosingReturnRenderedType) == "Unit")
                renderStmtsRange(
                    startIdx = idx + 1,
                    endIdx = scopeEnd,
                    lines = scopeLines,
                    innerIndent = "$innerIndent    ",
                    returnsValue = false,
                    effectiveLastExecIdx = if (guardCapturesTail) effectiveLastExecIdx else -1,
                    blockEntryExpectedKind = capturedTailKind,
                    blockEntryTailExpectsUnitResultOk = capturedTailUnitResultOk,
                )
                if (emitter.headerContext.isBatchCrateOutput) emitter.sanitizeBatchBlockLocals(scopeLines)
                ktVar
            } finally {
                emitter.currentTypedLocals = prevTyped
                emitter.currentMutableListLocals = prevMutableLists
                emitter.currentSmartCastNonNullLocals = prevSmartCast
                emitter.scopeBuilder.exitCompr()
            }

        val returnPrefix = if (guardCapturesTail && !emitter.stmtAlwaysDiverges(stmts[effectiveLastExecIdx])) "return " else ""
        val sb = StringBuilder()
        sb.append(triviaPrefix).append("$innerIndent$returnPrefix$cellAtom.$borrowFn().$useFn { $boundKtVar ->\n")
        for (line in scopeLines) {
            sb.append(line)
            if (!line.endsWith('\n')) sb.append('\n')
        }
        sb.append(innerIndent).append('}')
        return sb.toString() to nextIdx
    }
}

internal fun KotlinJ2KEmitter.renderBlock(
    block: Block,
    indent: String,
    selfTypeName: String?,
    returnType: SynType? = null,
    leadingInjectedLines: List<String> = emptyList(),
    trailingInjectedLines: List<String> = emptyList(),
    allowExpressionBody: Boolean = false,
): String {
    val stmts = deduplicateComplementaryCfgStmts(block.stmts)
    val innerIndent = "$indent    "
    val closeBraceTrivia = renderLeadingTriviaForSpan(block.braceToken.span.close(), innerIndent)
    if (stmts.isEmpty() && leadingInjectedLines.isEmpty() && trailingInjectedLines.isEmpty() && closeBraceTrivia.isBlank()) {
        return "{}"
    }
    val lines = mutableListOf<String>()
    lines.addAll(leadingInjectedLines)

    val effectiveRenderedRet = if (returnType != null) currentEnclosingReturnRenderedType ?: renderType(returnType, selfTypeName) else null
    val returnsValue = effectiveRenderedRet != null && effectiveRenderedRet != "Unit"

    BlockEmissionScope(this, stmts, selfTypeName).executeBlock(
        innerIndent = innerIndent,
        returnsValue = returnsValue,
        lines = lines,
        closeBraceTrivia = closeBraceTrivia,
        trailingInjectedLines = trailingInjectedLines,
    )

    if (lines.isEmpty()) return "{}"
    if (allowExpressionBody && headerContext.isBatchCrateOutput && returnsValue && lines.size == 1) {
        val single = lines[0]
        val returnPrefix = "${innerIndent}return "
        if (single.startsWith(returnPrefix)) {
            val exprText = single.removePrefix(returnPrefix)
            if (!containsWholeIdentifier(exprText, "return") && !containsWholeIdentifier(exprText, "throw")) {
                return if (exprText.contains('\n')) "=\n$innerIndent$exprText" else "= $exprText"
            }
        }
    }
    val sb = StringBuilder("{\n")
    for (line in lines) {
        sb.append(line)
        if (!line.endsWith('\n')) sb.append('\n')
    }
    return sb.append(indent).append("}").toString()
}

private fun KotlinJ2KEmitter.isDropOfLocalStmt(stmt: Stmt, rawVar: String): Boolean {
    val exprStmt = stmt as? Stmt.ExprStmt ?: return false
    val call = unwrapParenOrGroupExpr(exprStmt.expr) as? Expr.Call ?: return false
    val path = (call.func as? Expr.Path)?.takeIf { it.qself == null }?.path ?: return false
    val pathSegs = path.segments.toList()
    val segs = pathSegs.map { it.ident.toString().removePrefix("r#") }
    if (segs.lastOrNull() != "drop" || segs.dropLast(1).any { it !in setOf("core", "std", "mem") }) return false
    val singleArg = call.args.toList().singleOrNull() ?: return false
    return singlePlainPathCleanIdent(singleArg) == rawVar
}

private fun KotlinJ2KEmitter.stmtAlwaysDiverges(stmt: Stmt): Boolean =
    stmt is Stmt.ExprStmt && exprAlwaysDiverges(stmt.expr)

private fun KotlinJ2KEmitter.exprAlwaysDiverges(expr: Expr): Boolean =
    when (val core = unwrapParenOrGroupExpr(expr)) {
        is Expr.Return, is Expr.Break, is Expr.Continue -> true
        is Expr.Macro -> core.mac.path.lastIdentText() in setOf("panic", "unreachable", "todo", "unimplemented")
        is Expr.BlockExpr -> {
            val blockStmts = core.block.stmts
            blockStmts.lastOrNull()?.let(::stmtAlwaysDiverges) == true
        }
        is Expr.If -> {
            val elseExpr = core.elseBranch?.expr
            val thenStmts = core.thenBranch.stmts
            elseExpr != null && thenStmts.lastOrNull()?.let(::stmtAlwaysDiverges) == true && exprAlwaysDiverges(elseExpr)
        }
        is Expr.Match -> core.arms.isNotEmpty() && core.arms.all { exprAlwaysDiverges(it.body) }
        else -> false
    }

internal fun KotlinJ2KEmitter.sanitizeBatchBlockLocals(lines: MutableList<String>) {
    for (i in lines.indices.reversed()) {
        val entryLines = lines[i].split('\n')
        val firstCodeIdx = entryLines.indexOfFirst { !it.trimStart().startsWith("//") }
        if (firstCodeIdx < 0 || entryLines.size - firstCodeIdx != 1) continue
        val triviaPart = if (firstCodeIdx > 0) entryLines.subList(0, firstCodeIdx).joinToString("\n") else ""
        val decl = KotlinTokenWalker(entryLines[firstCodeIdx]).parseSingleLineLocalDeclaration() ?: continue
        val afterText = if (i + 1 < lines.size) lines.subList(i + 1, lines.size).joinToString("\n") else ""
        val afterWalker = KotlinTokenWalker(afterText)
        if (!afterWalker.containsIdentifier(decl.ident)) {
            if (triviaPart.isNotEmpty()) lines[i] = triviaPart else lines.removeAt(i)
            continue
        }
        if (decl.keyword == "var" && !afterWalker.isIdentifierMutated(decl.ident)) {
            val updatedCode = "${decl.indent}val ${decl.ident}${decl.typeClause} = ${decl.rhs}"
            lines[i] = if (triviaPart.isNotEmpty()) "$triviaPart\n$updatedCode" else updatedCode
        }
    }
}

internal fun KotlinJ2KEmitter.extractDelimitedMacroParts(
    mac: Macro,
    indent: String,
    selfTypeName: String?,
): Triple<String, String, String>? {
    val macroName = mac.path.lastIdentText() ?: return null
    if (macroName != "parenthesized" && macroName != "braced" && macroName != "bracketed") return null
    val cursor = RustTokenCursor(mac.tokens.clone().toList())
    if (cursor.size < 3 || cursor.identAt(1) != "in") return null
    val targetIdent = cursor.cleanIdentAt(0) ?: return null
    val streamTokens = TokenStream.fromTokenTrees(cursor.trees.subList(2, cursor.size))
    return Triple(macroName, targetIdent, parseAndRenderTokenStreamAsExpr(streamTokens, indent, selfTypeName))
}

internal fun KotlinJ2KEmitter.tryRenderDeferredLocalResultMatchInit(
    identPat: Pat.Ident,
    rawVar: String,
    localStmt: Stmt.Local,
    matchStmt: Stmt,
    matchExpr: Expr.Match,
    targetLocalStmt: Stmt.Local?,
    innerIndent: String,
    selfTypeName: String?,
): List<String>? {
    if ("Ok" in inspector.declaredTypeNames || "Err" in inspector.declaredTypeNames) return null
    val arms = matchExpr.arms
    if (arms.size != 2 || arms.any { armGuardExpr(it) != null }) return null
    val firstPat = unwrapTransparentPat(armPattern(arms[0]))
    val secondPat = unwrapTransparentPat(armPattern(arms[1]))
    val (okArm, errArm) =
        when {
            isStdlibResultArmPat(firstPat, "Ok") && isStdlibResultArmPat(secondPat, "Err") -> arms[0] to arms[1]
            isStdlibResultArmPat(secondPat, "Ok") && isStdlibResultArmPat(firstPat, "Err") -> arms[1] to arms[0]
            else -> return null
        }
    val okBlock = (okArm.body as? Expr.BlockExpr)?.block ?: return null
    val errBodyUnwrapped =
        when (val eb = errArm.body) {
            is Expr.BlockExpr -> {
                val singleEbStmt = eb.block.stmts.singleOrNull()
                (singleEbStmt as? Stmt.ExprStmt)?.expr
            }
            else -> eb
        } ?: return null
    if (errBodyUnwrapped !is Expr.Return) return null
    val assignsTarget =
        okBlock.stmts.any { s ->
            s is Stmt.ExprStmt && s.expr is Expr.Assign && extractAssignedRootIdent((s.expr as Expr.Assign).left) == rawVar
        }
    if (!assignsTarget) return null

    val trivia1 = renderLeadingTriviaForSpan(localStmt.nonDrainingSpan(), innerIndent)
    val trivia2 = renderLeadingTriviaForSpan(matchStmt.nonDrainingSpan(), innerIndent)
    val subjectRendered = renderExpr(matchExpr.expr, innerIndent, selfTypeName)
    val okTuple = unwrapTransparentPat(armPattern(okArm)) as Pat.TupleStruct
    val okSubPat = okTuple.elems.toList().firstOrNull()
    val errTuple = unwrapTransparentPat(armPattern(errArm)) as? Pat.TupleStruct
    val errSubPat = errTuple?.elems?.toList()?.firstOrNull()

    val out = mutableListOf<String>()
    val okVar = scopeBuilder.bindLocal("_ok", "matchVal", matchExpr.expr.span()).kotlinName
    val taggedCore = usesTaggedCoreResult()
    val errRender =
        run {
            scopeBuilder.enterCompr()
            try {
                val errParam =
                    if (errSubPat is Pat.Ident && errSubPat.subpat == null && errSubPat.ident.toString() != "_") {
                        val rawErr = errSubPat.ident.toString().removePrefix("r#")
                        bindScopedValueIdent(rawErr, errSubPat.ident.span(), mutable = false)
                    } else if (taggedCore) {
                        "err"
                    } else {
                        null
                    }
                val renderedReturn = renderExpr(errBodyUnwrapped, "$innerIndent    ", selfTypeName)
                when {
                    taggedCore -> "{ val $errParam = matchResult.errValue(); $renderedReturn }"
                    errParam != null -> "{ $errParam -> $renderedReturn }"
                    else -> "{ $renderedReturn }"
                }
            } finally {
                scopeBuilder.exitCompr()
            }
        }
    if (taggedCore) {
        val resultType = sourceResultName()
        out += "${trivia1}${trivia2}${innerIndent}val $okVar = when (val matchResult = $subjectRendered) { is $resultType.Ok<*, *> -> matchResult.okValue(); is $resultType.Err<*, *> -> $errRender }"
    } else {
        out += "${trivia1}${trivia2}${innerIndent}val $okVar = $subjectRendered.getOrElse $errRender"
    }
    if (okSubPat != null) out += extractMatchArmBindings(okSubPat, okVar, innerIndent, selfTypeName, bindToScope = true)

    val isMut = identPat.mutability != null
    val kw = if (isMut) "var" else "val"
    var boundVar = false
    for ((sIdx, s) in okBlock.stmts.withIndex()) {
        if (s is Stmt.ExprStmt && s.expr is Expr.Assign) {
            val assignExpr = s.expr as Expr.Assign
            if (!boundVar && extractAssignedRootIdent(assignExpr.left) == rawVar) {
                val ktVar = bindScopedValueIdent(rawVar, identPat.ident.span(), isMut)
                out += "$innerIndent$kw $ktVar = ${renderExpr(assignExpr.right, innerIndent, selfTypeName)}"
                boundVar = true
                continue
            }
        }
        if (sIdx == okBlock.stmts.lastIndex && s is Stmt.ExprStmt && s.semiToken == null) {
            if (targetLocalStmt != null) {
                val (tokPat, tokTy) = renderLocalPattern(targetLocalStmt.pat, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
                val tokKeyword = if (isMutablePattern(targetLocalStmt.pat)) "var" else "val"
                val tokSuffix = if (tokTy != null) ": $tokTy" else ""
                val tailVal = renderExpr(s.expr, innerIndent, selfTypeName)
                if (tokPat != "_" && tokPat.isNotEmpty()) out += "$innerIndent$tokKeyword $tokPat$tokSuffix = $tailVal"
                continue
            }
            if (isSideEffectFreeExpr(s.expr)) continue
        }
        val renderedS = renderStmt(s, innerIndent, selfTypeName, isTailReturn = false)
        if (renderedS.isNotBlank()) out += renderedS
    }
    return if (boundVar) out else null
}

internal fun KotlinJ2KEmitter.isStdlibResultArmPat(pat: Pat, variant: String): Boolean {
    if (pat !is Pat.TupleStruct) return false
    val segs = pat.path.segments.toList()
    val matchesPath =
        (segs.size == 1 && segs[0].ident.toString() == variant) ||
            (
                segs.size >= 2 &&
                    segs.last().ident.toString() == variant &&
                    segs.dropLast(1).all {
                        val prefix = it.ident.toString()
                        prefix in IGNORED_PATH_PREFIXES || prefix in STDLIB_PRELUDE_REEXPORT_MODULES || prefix == "Result" || prefix == "result"
                    }
            )
    if (!matchesPath) return false
    val elems = pat.elems.toList()
    return elems.size == 1 && !isDiscriminatingSubpattern(elems[0])
}

internal fun KotlinJ2KEmitter.renderStmt(
    stmt: Stmt,
    indent: String,
    selfTypeName: String?,
    isTailReturn: Boolean,
    isLastExecutableStmt: Boolean = false,
    localTypeImpls: Map<String, List<Item.Impl>> = emptyMap(),
    localSignatures: MutableMap<String, Int> = linkedMapOf(),
    remainingStmts: List<Stmt> = emptyList(),
): String {
    val triviaPrefix = renderLeadingTriviaForSpan(stmt.nonDrainingSpan(), indent)
    val body =
        when (stmt) {
            is Stmt.Local -> renderStmtLocal(stmt, indent, selfTypeName, remainingStmts)
            is Stmt.ItemStmt ->
                renderItem(
                    item = stmt.item,
                    indent = indent,
                    localTypeImpls = localTypeImpls,
                    isLocalItem = true,
                    scopeSignatures = localSignatures,
                )
            is Stmt.ExprStmt -> {
                val expr = stmt.expr
                val hasSemi = stmt.semiToken != null
                if (isTailReturn && !hasSemi && !isExplicitControlFlowJump(expr)) {
                    renderTailReturnExpr(expr, indent, selfTypeName)
                } else {
                    val rawRendered = renderExpr(expr, indent, selfTypeName)
                    val rendered =
                        if (currentTailExpectsUnitResultOk && !hasSemi && isLastExecutableStmt) {
                            coerceTailFmtUnitToResultOk(expr, rawRendered, selfTypeName)
                        } else {
                            rawRendered
                        }
                    when {
                        rendered.isBlank() || rendered.trim() == "Unit" -> ""
                        !headerContext.isBatchCrateOutput && isLastExecutableStmt && !hasSemi && rendered.startsWith("error(") -> "${indent}return $rendered"
                        else -> "$indent$rendered"
                    }
                }
            }
            is Stmt.MacroStmt -> {
                val macName = stmt.mac.path.lastCleanIdent()
                val savedMacTarget = currentExpectedParseTargetType
                if (stmt.semiToken == null && isTailReturn && macName == "vec" && currentExpectedParseTargetType == null) {
                    currentExpectedParseTargetType = currentEnclosingReturnRenderedType
                }
                val rawRendered =
                    try {
                        renderMacroInvocationAsStmtOrExpr(stmt.mac, indent, selfTypeName)
                    } finally {
                        currentExpectedParseTargetType = savedMacTarget
                    }
                val isWriteMacTail =
                    stmt.semiToken == null &&
                        macName in setOf("write", "writeln") &&
                        rustResultOkPayloadType(currentEnclosingReturnRenderedType) == "Unit" &&
                        (isTailReturn || (isLastExecutableStmt && currentTailExpectsUnitResultOk))
                val rendered =
                    if (isWriteMacTail && rawRendered.isNotBlank()) {
                        val okVal = enclosingUnitResultOkExpr() ?: if (usesTaggedCoreResult()) "${sourceResultName()}.Ok(Unit)" else "Result.success(Unit)"
                        if (rawRendered.contains('\n')) "run {\n$rawRendered\n$okVal\n}" else "run { $rawRendered; $okVal }"
                    } else {
                        rawRendered
                    }
                when {
                    rendered.isBlank() -> ""
                    stmt.semiToken == null && !headerContext.isBatchCrateOutput && (isTailReturn || (isLastExecutableStmt && rendered.startsWith("error("))) ->
                        "${indent}return $rendered"
                    stmt.semiToken == null && isTailReturn && !rendered.startsWith("error(") -> "${indent}return $rendered"
                    else -> "$indent$rendered"
                }
            }
        }
    return if (triviaPrefix.isEmpty()) body else "$triviaPrefix$body"
}

internal fun KotlinJ2KEmitter.isExplicitControlFlowJump(expr: Expr): Boolean =
    when (expr) {
        is Expr.Return, is Expr.Break, is Expr.Continue,
        is Expr.Loop, is Expr.While, is Expr.ForLoop, is Expr.Assign,
        -> true
        else -> false
    }

internal fun KotlinJ2KEmitter.renderTailReturnExpr(expr: Expr, indent: String, selfTypeName: String?): String {
    val retNumKind = currentEnclosingReturnRenderedType?.let(this::resolveNumericKind)
    val savedTailOk = currentTailExpectsUnitResultOk
    val savedParseTarget = currentExpectedParseTargetType
    currentTailExpectsUnitResultOk = (rustResultOkPayloadType(currentEnclosingReturnRenderedType) == "Unit")
    if (unwrapParenOrGroupExpr(expr) is Expr.Macro && currentExpectedParseTargetType == null) {
        currentExpectedParseTargetType = currentEnclosingReturnRenderedType
    }
    val rawRendered =
        try {
            renderTypedNumericInitializer(expr, retNumKind, indent, selfTypeName)
        } finally {
            currentTailExpectsUnitResultOk = savedTailOk
            currentExpectedParseTargetType = savedParseTarget
        }
    val rendered = if (enclosingUnitResultOkExpr() != null) coerceTailFmtUnitToResultOk(expr, rawRendered, selfTypeName) else rawRendered
    if (rendered.isBlank()) return ""
    if ((headerContext.isBatchCrateOutput && rendered.trimStart().startsWith("error(")) || blockEndsWithUnconditionalDiverge(expr)) {
        return "$indent$rendered"
    }
    return "${indent}return $rendered"
}

private fun KotlinJ2KEmitter.blockEndsWithUnconditionalDiverge(expr: Expr): Boolean {
    val block =
        when (val u = unwrapParenOrGroupExpr(expr)) {
            is Expr.BlockExpr -> u.block
            is Expr.Unsafe -> u.block
            else -> return false
        }
    val lastStmt = block.stmts.lastOrNull { it !is Stmt.ItemStmt } as? Stmt.ExprStmt ?: return false
    return when (val inner = unwrapParenOrGroupExpr(lastStmt.expr)) {
        is Expr.Return, is Expr.Break, is Expr.Continue, is Expr.Loop -> true
        is Expr.BlockExpr, is Expr.Unsafe -> blockEndsWithUnconditionalDiverge(inner)
        else -> false
    }
}

internal fun KotlinJ2KEmitter.recordLocalInferredType(
    patText: String,
    typeAnnotation: String?,
    initExpr: Expr,
    rhs: String,
    selfTypeName: String?,
    inferredLocalNumKind: NumericKind? = null,
    pat: Pat? = null,
) {
    if (patText.startsWith("(")) {
        fun isConcreteTupleElemType(ty: String?): Boolean =
            !ty.isNullOrEmpty() &&
                ty !in setOf("Any?", "Any", "*", "Nothing", "Nothing?") &&
                !ty.contains("uninferred", ignoreCase = true)
        val unwrappedPat = pat?.let(this::unwrapTransparentPat) as? Pat.Tuple ?: return
        val patElems = unwrappedPat.elems.toList()
        val unwrappedInit = unwrapParenOrGroupExpr(initExpr)
        if (unwrappedInit is Expr.Tuple) {
            val initElems = unwrappedInit.elems.toList()
            for (idx in 0 until minOf(patElems.size, initElems.size)) {
                val pIdent = extractRawParamIdent(patElems[idx]) ?: continue
                val ktName = scopeBuilder.resolveLocalKotlinName(pIdent) ?: rustValueIdentToKotlin(pIdent)
                val elemExpr = initElems[idx]
                val elemTy = inferExprRenderedType(elemExpr, selfTypeName) ?: inferExprNumericKind(elemExpr, selfTypeName)?.let(this::numericKindToPureTypeName)
                if (isConcreteTupleElemType(elemTy)) currentTypedLocals = currentTypedLocals + (ktName to elemTy!!)
            }
        } else {
            val elemTypes =
                inferExprRenderedType(initExpr, selfTypeName)
                    .asKotlinRenderedType()
                    ?.takeIf { it.headPath == "Pair" || it.headPath == "Triple" }
                    ?.typeArgs
            if (elemTypes != null) {
                for (idx in 0 until minOf(patElems.size, elemTypes.size)) {
                    val pIdent = extractRawParamIdent(patElems[idx]) ?: continue
                    val ktName = scopeBuilder.resolveLocalKotlinName(pIdent) ?: rustValueIdentToKotlin(pIdent)
                    val elemTy = elemTypes[idx].trim()
                    if (isConcreteTupleElemType(elemTy)) currentTypedLocals = currentTypedLocals + (ktName to elemTy)
                }
            }
        }
        return
    }
    val unwrappedLocalPat = pat?.let(this::unwrapTransparentPat)
    val explicitSynType =
        (pat as? Pat.TypeAscription)?.ty?.takeUnless { it is SynType.Infer }
            ?: (unwrappedLocalPat as? Pat.TypeAscription)?.ty?.takeUnless { it is SynType.Infer }
    val inferredInitSynType = explicitSynType ?: sourceInitializerType(initExpr)
    if (inferredInitSynType != null) {
        currentSourceParameterTypes = currentSourceParameterTypes + (patText to inferredInitSynType)
    }
    if (typeAnnotation != null) {
        currentTypedLocals = currentTypedLocals + (patText to typeAnnotation)
        return
    }
    if (inferredLocalNumKind != null) {
        currentTypedLocals = currentTypedLocals + (patText to numericKindToPureTypeName(inferredLocalNumKind))
        return
    }
    val inferredRenderedTy = inferExprRenderedType(initExpr, selfTypeName)
    if (inferredRenderedTy != null) {
        val isMutListInit =
            rhs.startsWith("MutableList(") ||
                rhs.startsWith("mutableListOf(") ||
                (rhs.startsWith("run { val repeatElem") && "; MutableList(" in rhs)
        val adjustedTy = if (isMutListInit && inferredRenderedTy.startsWith("List<")) "Mutable$inferredRenderedTy" else inferredRenderedTy
        currentTypedLocals = currentTypedLocals + (patText to adjustedTy)
        return
    }
    val inferredKind = inferExprNumericKind(initExpr, selfTypeName)
    if (inferredKind != null) {
        currentTypedLocals = currentTypedLocals + (patText to numericKindToPureTypeName(inferredKind))
    } else if (rhs.endsWith(".encodeToByteArray()")) {
        currentTypedLocals = currentTypedLocals + (patText to "ByteArray")
    }
}

internal fun KotlinJ2KEmitter.inferUntypedLocalNumericKindFromRemainingStmts(
    pat: Pat,
    initExpr: Expr,
    remainingStmts: List<Stmt>,
    selfTypeName: String?,
): NumericKind? {
    if (remainingStmts.isEmpty()) return null
    val unwrappedPat = unwrapTransparentPat(pat) as? Pat.Ident ?: return null
    if (unwrappedPat.mutability == null) return null
    val rawLocalIdent = unwrappedPat.ident.toString().removePrefix("r#")
    val coreInit = unwrapParenOrGroupExpr(initExpr)
    val intLitExpr =
        when {
            coreInit is Expr.Lit -> coreInit
            coreInit is Expr.Unary && coreInit.op is UnOp.Neg -> unwrapParenOrGroupExpr(coreInit.expr) as? Expr.Lit
            else -> null
        }
    if ((intLitExpr?.lit as? Lit.Int)?.value?.suffix()?.isEmpty() != true) return null
    for (stmt in remainingStmts) {
        val expr = (stmt as? Stmt.ExprStmt)?.expr ?: continue
        when {
            expr is Expr.Assign && extractAssignedRootIdent(expr.left) == rawLocalIdent ->
                inferExprNumericKind(expr.right, selfTypeName)?.let { return it }
            expr is Expr.Binary && isCompoundAssignOp(expr.op) && extractAssignedRootIdent(expr.left) == rawLocalIdent ->
                inferExprNumericKind(expr.right, selfTypeName)?.let { return it }
        }
    }
    val lastStmt = remainingStmts.lastOrNull() as? Stmt.ExprStmt
    if (lastStmt != null && lastStmt.semiToken == null && singlePlainPathCleanIdent(lastStmt.expr) == rawLocalIdent) {
        return currentEnclosingReturnRenderedType?.let(this::resolveNumericKind)
    }
    return null
}

private inline fun <R> KotlinJ2KEmitter.withLocalInitRenderScope(
    expectedTarget: String?,
    block: () -> R,
): R {
    val prevParseTarget = currentExpectedParseTargetType
    val prevInLocalInit = inLocalInitializerPosition
    currentExpectedParseTargetType = expectedTarget
    inLocalInitializerPosition = true
    return try {
        block()
    } finally {
        currentExpectedParseTargetType = prevParseTarget
        inLocalInitializerPosition = prevInLocalInit
    }
}

internal fun KotlinJ2KEmitter.renderStmtLocal(
    local: Stmt.Local,
    indent: String,
    selfTypeName: String?,
    remainingStmts: List<Stmt> = emptyList(),
): String {
    val pat = local.pat
    val keyword = if (isMutablePattern(pat)) "var" else "val"
    val init = local.init
    if (init == null) {
        val (patText, typeAnnotation) = renderLocalPattern(pat, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
        if (typeAnnotation != null && !patText.startsWith("(")) {
            currentTypedLocals = currentTypedLocals + (patText to typeAnnotation)
            if (typeAnnotation.endsWith("?")) currentNullableLocals = currentNullableLocals + patText
        }
        val typeSuffix =
            when {
                patText.startsWith("(") -> ""
                typeAnnotation != null -> ": $typeAnnotation"
                else -> ": Any?"
            }
        return "$indent$keyword $patText$typeSuffix"
    }
    val diverge = init.diverge
    val initExpr = init.expr
    LocalBindingLowering.tryLowerSpecialStorage(this, local, indent, selfTypeName, remainingStmts)?.let { return it }
    val explicitLocalAnnotation = extractLocalTypeAnnotation(pat, selfTypeName)
    val unwrappedInitExpr = unwrapParenOrGroupExpr(initExpr)
    val inferredArrayTarget =
        if (explicitLocalAnnotation == null && (unwrappedInitExpr is Expr.Array || unwrappedInitExpr is Expr.Repeat)) {
            inferUntypedArrayLocalTargetTypeFromRemainingStmts(pat, remainingStmts, selfTypeName)
        } else {
            null
        }
    val localNumKind =
        explicitLocalAnnotation?.removeSuffix("?")?.let(this::resolveNumericKind)
            ?: inferUntypedLocalNumericKindFromRemainingStmts(pat, initExpr, remainingStmts, selfTypeName)
    val expectedTarget = explicitLocalAnnotation ?: inferredArrayTarget

    if (diverge == null && initExpr is Expr.BlockExpr) {
        val blockStmts = initExpr.block.stmts
        val singleExpr = (blockStmts.singleOrNull() as? Stmt.ExprStmt)?.expr
        val (recordedExpr, renderedBlockRhs, rawRhsForRecord) =
            withLocalInitRenderScope(expectedTarget) {
                if (singleExpr != null) {
                    val singleRhs = renderTypedNumericInitializer(singleExpr, localNumKind, indent, selfTypeName)
                    Triple(singleExpr, singleRhs, singleRhs)
                } else {
                    val blockBody = renderBlock(initExpr.block, indent, selfTypeName)
                    Triple(initExpr, "run $blockBody", blockBody)
                }
            }
        val (patText, typeAnnotation) = renderLocalPattern(pat, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
        recordLocalInferredType(patText, typeAnnotation, recordedExpr, rawRhsForRecord, selfTypeName, pat = pat)
        val typeSuffix = if (typeAnnotation != null && !patText.startsWith("(")) ": $typeAnnotation" else ""
        return "$indent$keyword $patText$typeSuffix = $renderedBlockRhs"
    }

    var rhs =
        withLocalInitRenderScope(expectedTarget) {
            renderTypedNumericInitializer(initExpr, localNumKind, indent, selfTypeName)
        }
    val expectsMutableList = keyword == "var" || inferredArrayTarget?.startsWith("MutableList<") == true
    if (expectsMutableList && explicitLocalAnnotation == null && diverge == null) {
        if (unwrappedInitExpr is Expr.Repeat && rhs.startsWith("List(")) {
            rhs = "Mutable$rhs"
        } else if (unwrappedInitExpr is Expr.Repeat && rhs.startsWith("run { val repeatElem") && "; List(" in rhs) {
            rhs = rhs.replaceFirst("; List(", "; MutableList(")
        } else if (unwrappedInitExpr is Expr.Array && rhs.startsWith("listOf(")) {
            rhs = "mutableListOf(" + rhs.removePrefix("listOf(")
        }
    }
    if (pat is Pat.Wild) return if (isSideEffectFreeExpr(initExpr)) "" else "$indent$rhs"

    val unwrappedPat = unwrapTransparentPat(pat)
    if (unwrappedPat is Pat.Struct && diverge == null) {
        return renderStructDestructuringLocal(unwrappedPat, rhs, local.nonDrainingSpan(), indent, selfTypeName)
    }
    if (unwrappedPat is Pat.TupleStruct && diverge == null) {
        renderTupleStructDestructuringLocal(unwrappedPat, initExpr, rhs, local.nonDrainingSpan(), indent, selfTypeName)?.let { return it }
    }
    val divergeBody =
        diverge?.expr?.let { divergeExpr ->
            if (divergeExpr is Expr.BlockExpr) {
                val stmts = divergeExpr.block.stmts
                if (stmts.size == 1) {
                    renderStmt(stmts[0], "", selfTypeName, isTailReturn = false).trim()
                } else {
                    "run " + renderBlock(divergeExpr.block, indent, selfTypeName)
                }
            } else {
                renderExpr(divergeExpr, indent, selfTypeName)
            }
        }
    val (patText, rawTypeAnnotation) = renderLocalPattern(pat, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
    var typeAnnotation = rawTypeAnnotation
    if ((rhs == "mutableListOf()" || rhs.startsWith("ArrayList(")) && typeAnnotation == null) {
        val inferredElem =
            inferEmptyListElementTypeFromReturn(currentEnclosingReturnRenderedType)
                ?: EmptyListLocalElemTypeInferrer(this, pat, selfTypeName).infer(remainingStmts)
        if (inferredElem != null) {
            rhs = if (rhs == "mutableListOf()") "mutableListOf<$inferredElem>()" else "ArrayList<$inferredElem>(${rhs.removePrefix("ArrayList(")}"
        }
    } else if (typeAnnotation == null && !patText.startsWith("(")) {
        val unwrappedRet =
            currentEnclosingReturnRenderedType?.let { ret ->
                if (ret.startsWith("Result<") && ret.endsWith(">")) rustResultOkPayloadType(ret) ?: ret else ret
            }
        if (unwrappedRet != null && '<' in unwrappedRet) {
            val baseRet = unwrappedRet.substringBefore('<').trim()
            if (rhs == "$baseRet.new()" || rhs == "$baseRet.default()") typeAnnotation = unwrappedRet
        }
    }
    if (typeAnnotation == "RustAny" && inferExprRenderedType(initExpr, selfTypeName) != "RustAny" && !rhs.startsWith("RustAny.from(")) {
        rhs = "RustAny.from($rhs)"
    }
    if (rhs == "null" && typeAnnotation == null && !patText.startsWith("(")) {
        val inferredNullTy = inferExprRenderedType(initExpr, selfTypeName)?.trim()
        if (!inferredNullTy.isNullOrEmpty() && inferredNullTy !in setOf("Nothing?", "Nothing", "Any?", "Any")) {
            typeAnnotation = if (inferredNullTy.endsWith("?")) inferredNullTy else "$inferredNullTy?"
        }
    }
    if (rhs == "null" && typeAnnotation != null && !typeAnnotation.endsWith("?")) {
        typeAnnotation = "$typeAnnotation?"
    }
    val somePayload = if (!hasDeclaredOptionEnum() && diverge == null) unwrapUnqualifiedOptionSomeArg(unwrappedInitExpr) else null
    if (somePayload != null && typeAnnotation == null && !patText.startsWith("(")) {
        val unwrappedLitExpr =
            when (val corePayload = unwrapParenOrGroupExpr(somePayload)) {
                is Expr.Lit -> corePayload
                is Expr.Unary ->
                    if (corePayload.op is UnOp.Neg || corePayload.op is UnOp.NotOp) unwrapParenOrGroupExpr(corePayload.expr) as? Expr.Lit else null
                else -> null
            }
        val literalFallbackTy =
            when (unwrappedLitExpr?.lit) {
                is Lit.Int ->
                    when {
                        rhs.endsWith("uL") -> "ULong"
                        rhs.endsWith("u") -> "UInt"
                        rhs.endsWith("L") -> "Long"
                        else -> "Int"
                    }
                is Lit.Float -> if (rhs.endsWith("f")) "Float" else "Double"
                is Lit.Bool -> "Boolean"
                is Lit.Str -> "String"
                is Lit.Char -> "Char"
                else -> null
            }
        val inferredSomeTy =
            inferExprRenderedType(somePayload, selfTypeName)?.trim()
                ?: (inferExprNumericKind(somePayload, selfTypeName) ?: localNumKind)?.let(this::numericKindToPureTypeName)
                ?: literalFallbackTy
        if (!inferredSomeTy.isNullOrEmpty() && inferredSomeTy !in setOf("Nothing?", "Nothing", "Any?", "Any")) {
            typeAnnotation = if (inferredSomeTy.endsWith("?")) inferredSomeTy else "$inferredSomeTy?"
        }
    }
    currentAddressableLocals = currentAddressableLocals - patText
    currentAddressableArrayElementTypes = currentAddressableArrayElementTypes - patText
    recordLocalInferredType(patText, typeAnnotation, initExpr, rhs, selfTypeName, localNumKind, pat = pat)
    if (!patText.startsWith("(")) {
        if (rhs.startsWith("ArrayDeque(")) {
            currentDequeLocals = currentDequeLocals + patText
            currentMutableListLocals = currentMutableListLocals - patText
        } else if (rhs.startsWith("mutableListOf") ||
            rhs.startsWith("MutableList(") ||
            (rhs.startsWith("run { val repeatElem") && "; MutableList(" in rhs) ||
            rhs.startsWith("ArrayList(") ||
            rhs.startsWith("ArrayList<") ||
            rhs.endsWith(".toMutableList()") ||
            typeAnnotation?.startsWith("MutableList<") == true
        ) {
            currentMutableListLocals = currentMutableListLocals + patText
            currentDequeLocals = currentDequeLocals - patText
        }
        if (typeAnnotation?.endsWith("?") == true || rhs == "null" || somePayload != null) {
            currentNullableLocals = currentNullableLocals + patText
        }
    }
    val coreResultLetElseInfo =
        if (divergeBody != null && unwrappedPat is Pat.TupleStruct && !patText.startsWith("(")) {
            coreResultVariantMatchInfo(unwrappedPat) ?: controlFlowVariantMatchInfo(unwrappedPat)
        } else {
            null
        }
    if (divergeBody != null && !patText.startsWith("(")) {
        if (coreResultLetElseInfo == null) {
            val prevLocalTy = currentTypedLocals[patText]
            if (prevLocalTy != null && prevLocalTy.endsWith("?")) {
                currentTypedLocals = currentTypedLocals + (patText to prevLocalTy.removeSuffix("?"))
            }
            currentNullableLocals = currentNullableLocals - patText
        } else {
            val resTy = inferExprRenderedType(initExpr, selfTypeName)?.removeSuffix("?")?.trim()
            val payloadTy =
                when {
                    coreResultLetElseInfo.first.endsWith(".Ok") -> rustResultOkPayloadType(resTy)
                    coreResultLetElseInfo.first.endsWith(".Err") -> resolveEnclosingResultTypeArguments(resTy)?.second
                    else -> null
                }
            if (isUsableExplicitResultTypeArg(payloadTy)) currentTypedLocals = currentTypedLocals + (patText to payloadTy!!)
        }
    }
    val typeSuffix = if (typeAnnotation != null && !patText.startsWith("(")) ": $typeAnnotation" else ""
    return when {
        coreResultLetElseInfo != null && divergeBody != null -> {
            val divergeVariant =
                when {
                    coreResultLetElseInfo.first == "ControlFlow.Break" -> "ControlFlow.Continue"
                    coreResultLetElseInfo.first == "ControlFlow.Continue" -> "ControlFlow.Break"
                    coreResultLetElseInfo.first.endsWith(".Ok") -> "${sourceResultName()}.Err"
                    else -> "${sourceResultName()}.Ok"
                }
            val tryVar = scopeBuilder.bindLocal("_try", "tryResult", initExpr.span()).kotlinName
            "$indent$keyword $patText$typeSuffix = when (val $tryVar = $rhs) { is ${coreResultLetElseInfo.first}<*, *> -> $tryVar.${coreResultLetElseInfo.second}; is $divergeVariant<*, *> -> $divergeBody }"
        }
        divergeBody != null -> "$indent$keyword $patText$typeSuffix = $rhs ?: $divergeBody"
        headerContext.isBatchCrateOutput && rhs.contains('\n') ->
            "$indent$keyword $patText$typeSuffix =\n$indent    ${rhs.replace("\n", "\n    ")}"
        else -> "$indent$keyword $patText$typeSuffix = $rhs"
    }
}

internal fun KotlinJ2KEmitter.inferEmptyListElementTypeFromReturn(enclosingRet: String?): String? {
    if (enclosingRet == null) return null
    val unwrappedResult =
        if (enclosingRet.simpleKotlinTypeName() == "Result" && enclosingRet.endsWith(">")) {
            rustResultOkPayloadType(enclosingRet) ?: enclosingRet
        } else {
            enclosingRet
        }
    for (prefix in listOf("List<", "MutableList<", "ArrayList<", "ArrayDeque<", "IntoIter<")) {
        if (unwrappedResult.startsWith(prefix) && unwrappedResult.endsWith(">")) {
            return unwrappedResult.removePrefix(prefix).removeSuffix(">").trim()
        }
    }
    return null
}

private class EmptyListLocalElemTypeInferrer(
    private val emitter: KotlinJ2KEmitter,
    private val pat: Pat,
    private val selfTypeName: String?,
) : Visit() {
    private var rawLocalName: String = ""
    private val ifLetBoundTypes = linkedMapOf<String, String>()
    private var inferred: String? = null

    fun infer(remainingStmts: List<Stmt>): String? {
        if (remainingStmts.isEmpty()) return null
        val identPat = (emitter.unwrapTransparentPat(pat) as? Pat.Ident)?.takeIf { it.subpat == null } ?: return null
        rawLocalName = identPat.ident.toString().removePrefix("r#")
        if (rawLocalName == "_") return null
        for (stmt in remainingStmts) {
            if (inferred != null) break
            visitStmt(stmt)
        }
        return inferred
    }

    private fun isConcreteElemType(ty: String?): Boolean =
        !ty.isNullOrBlank() && ty !in setOf("Any", "Any?", "Nothing", "Unit", "_") && !ty.contains("RustAny")

    private fun resolveOwnerKt(rawName: String?): String? =
        when (rawName) {
            null -> null
            "Self" -> selfTypeName?.substringBefore('<')?.trim()
            else -> rustTypeIdentToKotlin(rawName)
        }

    private fun resolveStructFieldElem(typeName: String, fieldName: String? = null): String? {
        val rendered = emitter.resolveDeclaredOrCrateStructFieldType(typeName, fieldName, selfTypeName, fallbackDefaultValueField = true)
        return rendered?.let(emitter::inferEmptyListElementTypeFromReturn)?.takeIf(::isConcreteElemType)
    }

    override fun visitExprLet(e: Expr.Let) {
        super.visitExprLet(e)
        val tsPat = emitter.unwrapTransparentPat(e.pat) as? Pat.TupleStruct
        val singleSub = tsPat?.elems?.toList()?.singleOrNull()
        val subIdent = (singleSub?.let(emitter::unwrapTransparentPat) as? Pat.Ident)?.takeIf { it.subpat == null }
        val innerIdent =
            if (tsPat != null && emitter.isOptionSomeTupleStructPat(tsPat)) {
                subIdent?.ident?.toString()?.removePrefix("r#")
            } else {
                null
            }
        if (innerIdent != null) {
            val scrutineeTy = emitter.inferExprRenderedType(e.expr, selfTypeName)
            if (scrutineeTy != null && scrutineeTy.endsWith("?")) {
                val unwrapped = scrutineeTy.removeSuffix("?").trim()
                if (isConcreteElemType(unwrapped)) ifLetBoundTypes[innerIdent] = unwrapped
            }
        }
    }

    override fun visitExprMethodCall(e: Expr.MethodCall) {
        if (inferred == null &&
            e.method.toString().removePrefix("r#") == "push" &&
            e.args.size == 1 &&
            emitter.singlePlainPathCleanIdent(e.receiver) == rawLocalName
        ) {
            val arg = e.args[0]
            val argIdent = emitter.singlePlainPathCleanIdent(arg)
            val candidate = argIdent?.let { ifLetBoundTypes[it] } ?: emitter.inferExprRenderedType(arg, selfTypeName)
            if (isConcreteElemType(candidate)) inferred = candidate
        }
        super.visitExprMethodCall(e)
    }

    override fun visitExprCall(e: Expr.Call) {
        if (inferred == null && e.args.size == 1 && emitter.singlePlainPathCleanIdent(e.args[0]) == rawLocalName) {
            val callPath = (emitter.unwrapParenOrGroupExpr(e.func) as? Expr.Path)?.takeIf { it.qself == null }?.path
            val segs = callPath?.segments?.toList().orEmpty()
            val firstRaw = segs.firstOrNull()?.ident?.toString()
            val penultRaw = if (segs.size >= 2) segs[segs.lastIndex - 1].ident.toString() else null
            val ownerRaw =
                when {
                    segs.size == 1 && firstRaw?.firstOrNull()?.isUpperCase() == true -> firstRaw
                    penultRaw?.firstOrNull()?.isUpperCase() == true -> penultRaw
                    else -> null
                }
            resolveOwnerKt(ownerRaw)?.let { inferred = resolveStructFieldElem(it) }
        }
        super.visitExprCall(e)
    }

    override fun visitExprStruct(e: Expr.Struct) {
        if (inferred == null) {
            val ownerKt = resolveOwnerKt(e.path.lastIdentText())
            if (ownerKt != null) {
                val fieldCount = e.fields.len()
                for (fv: FieldValue in e.fields.toList()) {
                    if (emitter.singlePlainPathCleanIdent(fv.expr) != rawLocalName) continue
                    val fieldName =
                        when (val m = fv.member) {
                            is Member.Named -> rustFieldIdentToKotlin(m.ident.toString())
                            is Member.Unnamed -> {
                                val memberIdx = m.index.index.toInt()
                                tupleFieldPropertyName(memberIdx, fieldCount)
                            }
                        }
                    inferred = resolveStructFieldElem(ownerKt, fieldName)
                    if (inferred != null) break
                }
            }
        }
        super.visitExprStruct(e)
    }
}

internal fun KotlinJ2KEmitter.isSideEffectFreeExpr(expr: Expr): Boolean =
    when (expr) {
        is Expr.Path, is Expr.Lit -> true
        is Expr.Reference -> isSideEffectFreeExpr(expr.expr)
        is Expr.Paren -> isSideEffectFreeExpr(expr.expr)
        is Expr.Group -> isSideEffectFreeExpr(expr.expr)
        is Expr.Field -> isSideEffectFreeExpr(expr.base)
        is Expr.Tuple -> expr.elems.toList().all(::isSideEffectFreeExpr)
        else -> false
    }

internal fun KotlinJ2KEmitter.isMutablePattern(pat: Pat): Boolean =
    when (pat) {
        is Pat.Ident -> pat.mutability != null
        is Pat.TypeAscription -> isMutablePattern(pat.pat)
        is Pat.Reference -> pat.mutability != null || isMutablePattern(pat.pat)
        is Pat.Guard -> isMutablePattern(pat.pat)
        is Pat.TupleStruct -> pat.elems.toList().any(::isMutablePattern)
        is Pat.Tuple -> pat.elems.toList().any(::isMutablePattern)
        else -> false
    }

internal fun KotlinJ2KEmitter.renderLocalPattern(
    pat: Pat,
    selfTypeName: String?,
    includeCtorInTupleStruct: Boolean = false,
    bindToScope: Boolean = false,
): Pair<String, String?> =
    when (pat) {
        is Pat.Ident -> {
            val raw = pat.ident.toString().removePrefix("r#")
            val baseName = rustValueIdentToKotlin(raw)
            val emittedName =
                if (bindToScope && raw != "_" && !raw.first().isUpperCase()) {
                    bindScopedValueIdent(raw, pat.ident.span(), pat.mutability != null, baseName)
                } else {
                    baseName
                }
            emittedName to null
        }
        is Pat.TypeAscription -> {
            val innerUnwrapped = unwrapTransparentPat(pat.pat)
            val unwrappedTy = unwrapSynType(pat.ty)
            if (innerUnwrapped is Pat.Tuple && unwrappedTy is SynType.Tuple) {
                val patElems = innerUnwrapped.elems.toList()
                val tyElems = unwrappedTy.elems.toList()
                val allSimpleElems =
                    patElems.size >= 2 &&
                        patElems.size == tyElems.size &&
                        patElems.all { elem ->
                            val p = unwrapTransparentPat(elem)
                            (p is Pat.Ident && p.subpat == null) || p is Pat.Wild
                        }
                if (allSimpleElems) {
                    val typedBindings =
                        patElems.mapIndexed { idx, subPat ->
                            val p = unwrapTransparentPat(subPat)
                            val elemTy = tyElems[idx]
                            val renderedElemTy = if (elemTy is SynType.Infer) null else renderType(elemTy, selfTypeName)
                            val boundName =
                                when (p) {
                                    is Pat.Wild -> "_"
                                    is Pat.Ident -> {
                                        val raw = p.ident.toString().removePrefix("r#")
                                        if (raw == "_") {
                                            "_"
                                        } else {
                                            val baseName = rustValueIdentToKotlin(raw)
                                            val ktName =
                                                if (bindToScope && !raw.first().isUpperCase()) {
                                                    bindScopedValueIdent(raw, p.ident.span(), p.mutability != null, baseName)
                                                } else {
                                                    baseName
                                                }
                                            if (bindToScope && renderedElemTy != null) {
                                                currentTypedLocals = currentTypedLocals + (ktName to renderedElemTy)
                                                if (renderedElemTy.endsWith("?")) currentNullableLocals = currentNullableLocals + ktName
                                            }
                                            ktName
                                        }
                                    }
                                    else -> "unused"
                                }
                            if (renderedElemTy != null && boundName != "_") "$boundName: $renderedElemTy" else boundName
                        }
                    return "(${typedBindings.joinToString(", ")})" to null
                }
            }
            val (innerPat, _) = renderLocalPattern(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope)
            val ty = if (pat.ty is SynType.Infer) null else renderType(pat.ty, selfTypeName)
            if (innerPat.startsWith("(")) innerPat to null else innerPat to ty
        }
        is Pat.Wild -> "unused" to null
        is Pat.Tuple, is Pat.TupleStruct, is Pat.Struct, is Pat.Slice -> {
            val names = extractPatternBindingNames(pat, selfTypeName, includeCtorInTupleStruct, bindToScope)
            when (names.size) {
                0 -> "unused" to null
                1 -> names[0] to null
                else -> "(${names.joinToString(", ")})" to null
            }
        }
        is Pat.Reference -> renderLocalPattern(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope)
        is Pat.PatParen -> renderLocalPattern(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope)
        is Pat.Guard -> renderLocalPattern(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope)
        else -> "localVal" to null
    }

internal fun KotlinJ2KEmitter.extractPatternBindingNames(
    pat: Pat,
    selfTypeName: String?,
    includeCtorInTupleStruct: Boolean = false,
    bindToScope: Boolean = false,
): List<String> {
    val out = mutableListOf<String>()
    collectPatternBindingNames(pat, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
    return out
}

internal fun KotlinJ2KEmitter.collectPatternBindingNames(
    pat: Pat,
    selfTypeName: String?,
    includeCtorInTupleStruct: Boolean,
    bindToScope: Boolean,
    out: MutableList<String>,
) {
    fun appendCtorPathSegments(path: Path) {
        if (!includeCtorInTupleStruct) return
        for (seg in path.segments.toList()) {
            val id = seg.ident.toString()
            if (id !in IGNORED_PATH_PREFIXES) {
                out += if (id.first().isUpperCase()) rustTypeIdentToKotlin(id) else rustValueIdentToKotlin(id)
            }
        }
    }
    when (pat) {
        is Pat.Ident -> {
            val raw = pat.ident.toString().removePrefix("r#")
            if (raw != "_") {
                out +=
                    when {
                        raw.first().isUpperCase() -> rustTypeIdentToKotlin(raw)
                        bindToScope -> bindScopedValueIdent(raw, pat.ident.span(), pat.mutability != null)
                        else -> rustValueIdentToKotlin(raw)
                    }
            }
            pat.subpat?.let { collectPatternBindingNames(it, selfTypeName, includeCtorInTupleStruct, bindToScope, out) }
        }
        is Pat.Wild, is Pat.Rest -> {}
        is Pat.Reference -> collectPatternBindingNames(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
        is Pat.PatParen -> collectPatternBindingNames(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
        is Pat.TypeAscription -> collectPatternBindingNames(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
        is Pat.Guard -> collectPatternBindingNames(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
        is Pat.Tuple -> {
            val elems = pat.elems.toList()
            if (elems.size >= 2) {
                val perElem =
                    elems.map { sub ->
                        mutableListOf<String>().also { collectPatternBindingNames(sub, selfTypeName, includeCtorInTupleStruct, bindToScope, it) }
                    }
                if (perElem.any { it.isNotEmpty() }) {
                    for (subOut in perElem) {
                        if (subOut.isEmpty()) out += "_" else out.addAll(subOut)
                    }
                }
            } else {
                elems.forEach { collectPatternBindingNames(it, selfTypeName, includeCtorInTupleStruct, bindToScope, out) }
            }
        }
        is Pat.TupleStruct -> {
            appendCtorPathSegments(pat.path)
            pat.elems.toList().forEach { collectPatternBindingNames(it, selfTypeName, includeCtorInTupleStruct, bindToScope, out) }
        }
        is Pat.Struct -> {
            appendCtorPathSegments(pat.path)
            pat.fields.toList().forEach { collectPatternBindingNames(it.pat, selfTypeName, includeCtorInTupleStruct, bindToScope, out) }
        }
        is Pat.Slice -> pat.elems.toList().forEach { collectPatternBindingNames(it, selfTypeName, includeCtorInTupleStruct, bindToScope, out) }
        is Pat.Path -> appendCtorPathSegments(pat.path)
        else -> {}
    }
}

private sealed class LocalBindingLowering {
    abstract fun tryRender(
        emitter: KotlinJ2KEmitter,
        local: Stmt.Local,
        indent: String,
        selfTypeName: String?,
        remainingStmts: List<Stmt>,
    ): String?

    companion object {
        private val STORAGE_RULES: List<LocalBindingLowering> =
            listOf(AddressableLocalLowering, ScopedBorrowedPlaceLocalLowering)

        fun tryLowerSpecialStorage(
            emitter: KotlinJ2KEmitter,
            local: Stmt.Local,
            indent: String,
            selfTypeName: String?,
            remainingStmts: List<Stmt>,
        ): String? = STORAGE_RULES.firstNotNullOfOrNull { it.tryRender(emitter, local, indent, selfTypeName, remainingStmts) }
    }
}

/** Keep a non-escaping pointer/reference alias attached to its source storage. */
private object ScopedBorrowedPlaceLocalLowering : LocalBindingLowering() {
    override fun tryRender(
        emitter: KotlinJ2KEmitter,
        local: Stmt.Local,
        indent: String,
        selfTypeName: String?,
        remainingStmts: List<Stmt>,
    ): String? {
        if (emitter.headerContext.rawAbiMode || local.init?.diverge != null) return null
        val binding = emitter.unwrapTransparentPat(local.pat) as? Pat.Ident ?: return null
        if (binding.mutability != null || binding.byRef != null || binding.subpat != null) return null
        val init = local.init?.expr ?: return null
        val projection = emitter.borrowedPlaceProjection(init, selfTypeName, addressArgument = true) ?: return null
        val raw = binding.ident.toString().removePrefix("r#")
        var dereferences = 0
        var escapes = false
        val visitor =
            object : Visit() {
                override fun visitExprUnary(e: Expr.Unary) {
                    if (e.op is UnOp.Deref && emitter.singlePlainPathCleanIdent(e.expr) == raw) {
                        dereferences++
                        return
                    }
                    super.visitExprUnary(e)
                }

                override fun visitExprPath(e: Expr.Path) {
                    if (e.qself == null && e.path.isIdent(raw)) escapes = true
                    super.visitExprPath(e)
                }

                override fun visitExprReference(e: Expr.Reference) {
                    if (raw in emitter.collectUsedRawIdentsInExpr(e.expr)) escapes = true
                    super.visitExprReference(e)
                }

                override fun visitExprRawAddr(e: Expr.RawAddr) {
                    if (raw in emitter.collectUsedRawIdentsInExpr(e.expr)) escapes = true
                    super.visitExprRawAddr(e)
                }

                override fun visitMacro(m: Macro) {
                    // Token-only macro arguments have not been resolved to expressions.
                    // Do not classify a hidden pointer use as a dereference.
                    if (RustTokenCursor.containsAnyIdentRecursive(m.tokens, setOf(raw))) escapes = true
                    super.visitMacro(m)
                }
            }
        remainingStmts.forEach(visitor::visitStmt)
        if (escapes) {
            val call = emitter.unwrapParenOrGroupExpr(init) as? Expr.MethodCall ?: return null
            val recvRendered = emitter.inferExprRenderedType(call.receiver, selfTypeName)?.removeSuffix("?")
            val ownerType = recvRendered?.simpleKotlinTypeName() ?: return null
            val handleType = emitter.mutablePlaceAccessorType(ownerType, call.method.toString()) ?: return null
            val (name, _) = emitter.renderLocalPattern(local.pat, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
            emitter.currentTypedLocals = emitter.currentTypedLocals + (name to handleType)
            return "${indent}val $name = ${emitter.renderExpr(init, indent, selfTypeName)}"
        }
        if (dereferences == 0) return null
        val (receiver, fields) = projection
        val owner =
            "(${emitter.renderExpr(receiver, indent, selfTypeName)})" +
                fields.dropLast(1).joinToString("") { ".$it" }
        val (name, _) = emitter.renderLocalPattern(local.pat, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
        emitter.currentBorrowedPlaceLocals = emitter.currentBorrowedPlaceLocals + name
        var valueType = emitter.inferExprRenderedType(receiver, selfTypeName)
        for (field in fields) {
            val ownerType = valueType?.removeSuffix("?")?.simpleKotlinTypeName()
            val fieldMap = emitter.inspector.structFieldSynTypesByType[ownerType]
            valueType = fieldMap?.get(field)?.let { emitter.renderType(it, ownerType) }
        }
        valueType?.let { emitter.currentTypedLocals = emitter.currentTypedLocals + (name to it) }
        return BorrowedPlacePropertyDelegateTemplate.renderWithCachedOwner(
            indent = indent,
            varName = name,
            ownerExpr = owner,
            leafField = fields.last(),
        )
    }
}

private class AddressableLocalUsageScanner(
    private val emitter: KotlinJ2KEmitter,
    private val raw: String,
    private val selfTypeName: String?,
) : Visit() {
    var rawAddressTaken: Boolean = false
    var mutRefTaken: Boolean = false
    var asPtrMethodCalled: Boolean = false
    var totalPathMentions: Int = 0
    var storageOnlyMentions: Int = 0
    var mentionedInMacro: Boolean = false
    var directReassigned: Boolean = false
    private var currentMatchScrutinee: Expr? = null

    private fun isSelfIdentPath(expr: Expr?): Boolean = emitter.singlePlainPathCleanIdent(expr) == raw

    private fun tokenStreamContainsSelfIdent(stream: TokenStream): Boolean =
        RustTokenCursor.containsAnyIdentRecursive(stream, setOf(raw))

    override fun visitExprPath(e: Expr.Path) {
        if (e.qself == null && e.path.isIdent(raw)) totalPathMentions++
        super.visitExprPath(e)
    }

    override fun visitExprMacro(e: Expr.Macro) {
        if (tokenStreamContainsSelfIdent(e.mac.tokens)) mentionedInMacro = true
        super.visitExprMacro(e)
    }

    override fun visitStmtMacro(s: Stmt.MacroStmt) {
        if (tokenStreamContainsSelfIdent(s.mac.tokens)) mentionedInMacro = true
        super.visitStmtMacro(s)
    }

    override fun visitExprMatch(e: Expr.Match) {
        val prevScrutinee = currentMatchScrutinee
        val subj = emitter.unwrapParenOrGroupExpr(e.expr)
        if (subj is Expr.Reference && subj.mutability != null) {
            currentMatchScrutinee = subj
            if (isSelfIdentPath(subj.expr) && e.arms.any { emitter.matchArmMutatesTupleVariantBinding(it) }) {
                directReassigned = true
            }
        }
        super.visitExprMatch(e)
        currentMatchScrutinee = prevScrutinee
    }

    override fun visitExprAssign(e: Expr.Assign) {
        if (isSelfIdentPath(e.left)) directReassigned = true
        super.visitExprAssign(e)
    }

    override fun visitExprBinary(e: Expr.Binary) {
        if (emitter.isCompoundAssignOp(e.op) && isSelfIdentPath(e.left)) directReassigned = true
        super.visitExprBinary(e)
    }

    override fun visitExprReference(e: Expr.Reference) {
        if (e.mutability != null && e !== currentMatchScrutinee && isSelfIdentPath(e.expr)) {
            mutRefTaken = true
            storageOnlyMentions++
        }
        super.visitExprReference(e)
    }

    override fun visitExprCast(e: Expr.Cast) {
        val reference = emitter.unwrapParenOrGroupExpr(e.expr) as? Expr.Reference
        if (e.ty is SynType.Ptr && isSelfIdentPath(reference?.expr)) {
            rawAddressTaken = true
            if (reference?.mutability == null) storageOnlyMentions++
        }
        super.visitExprCast(e)
    }

    override fun visitExprMethodCall(e: Expr.MethodCall) {
        val unwrappedRecv = emitter.unwrapParenOrGroupExpr(e.receiver)
        val target = (unwrappedRecv as? Expr.Reference)?.expr ?: unwrappedRecv
        if (e.method.toString() in setOf("as_ptr", "as_mut_ptr") && e.args.isEmpty() && isSelfIdentPath(target)) {
            asPtrMethodCalled = true
            if (unwrappedRecv !is Expr.Reference || unwrappedRecv.mutability == null) storageOnlyMentions++
        }
        super.visitExprMethodCall(e)
    }

    override fun visitExprCall(e: Expr.Call) {
        val calleeLeaf = (e.func as? Expr.Path)?.path?.lastCleanIdent()
        val minVarargAddrIdx =
            when (calleeLeaf) {
                "ioctl", "fcntl" -> 2
                "syscall", "prctl" -> 1
                else -> Int.MAX_VALUE
            }
        for ((argIdx, argExpr) in e.args.toList().withIndex()) {
            val reference = emitter.unwrapParenOrGroupExpr(argExpr) as? Expr.Reference
            if (reference != null && isSelfIdentPath(reference.expr)) {
                val isPtrParam =
                    argIdx >= minVarargAddrIdx || emitter.resolveCalleeExprParamSynType(e.func, argIdx, selfTypeName) is SynType.Ptr
                if (isPtrParam) {
                    rawAddressTaken = true
                    if (reference.mutability == null) storageOnlyMentions++
                }
            }
        }
        super.visitExprCall(e)
    }

    override fun visitExprRawAddr(e: Expr.RawAddr) {
        if (isSelfIdentPath(e.expr)) {
            rawAddressTaken = true
            storageOnlyMentions++
        }
        super.visitExprRawAddr(e)
    }
}

/** Address-taken scalar locals have one allocation for direct accesses and escaping borrows. */
private object AddressableLocalLowering : LocalBindingLowering() {
    private val LITERAL_CODEC_TYPES: Map<String, String>
        get() = activeRules().map("literalCodecTypes")

    override fun tryRender(
        emitter: KotlinJ2KEmitter,
        local: Stmt.Local,
        indent: String,
        selfTypeName: String?,
        remainingStmts: List<Stmt>,
    ): String? {
        if (emitter.headerContext.rawAbiMode || local.init?.diverge != null) return null
        val binding = emitter.unwrapTransparentPat(local.pat) as? Pat.Ident ?: return null
        val raw = binding.ident.toString().removePrefix("r#")
        val usage = AddressableLocalUsageScanner(emitter, raw, selfTypeName).also { remainingStmts.forEach(it::visitStmt) }
        if (!usage.rawAddressTaken && !usage.mutRefTaken && !usage.asPtrMethodCalled) return null

        val boundVarUsed = usage.mentionedInMacro || usage.totalPathMentions > usage.storageOnlyMentions
        val boundKeyword = if (usage.mentionedInMacro || usage.directReassigned) "var" else "val"
        val init =
            local.init?.expr ?: if (usage.rawAddressTaken) {
                error("Address-taken uninitialized local $raw needs definite-initialization storage lowering")
            } else {
                return null
            }
        val annotation = (local.pat as? Pat.TypeAscription)?.ty
        val literal = (emitter.unwrapParenOrGroupExpr(init) as? Expr.Lit)?.lit
        val literalCodec =
            when (literal) {
                is Lit.Bool -> "BOOL"
                is Lit.Int ->
                    when (literal.value.suffix()) {
                        "", "i32" -> "I32"
                        "u32" -> "U32"
                        "i8" -> "I8"
                        "u8" -> "U8"
                        "i16" -> "I16"
                        "u16" -> "U16"
                        "i64" -> "I64"
                        "u64" -> "U64"
                        else -> null
                    }
                else -> null
            }
        val sourceType = annotation ?: emitter.sourceInitializerType(init)
        val rawAddressTaken = usage.rawAddressTaken || (usage.asPtrMethodCalled && sourceType is SynType.Array)
        if (!rawAddressTaken && !usage.mutRefTaken) return null
        if (!rawAddressTaken && literalCodec == null && (sourceType == null || !emitter.shouldUseAddressBackedMutableReference(sourceType, selfTypeName))) {
            return null
        }

        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)

        fun unresolvedLayout(renderedTy: String): String {
            CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.mem.UnresolvedTypeLayout")
            return "UnresolvedTypeLayout<$renderedTy>()"
        }

        val arrayElemRendered = if (sourceType is SynType.Array) emitter.renderType(sourceType.elem, selfTypeName) else null
        val arrayPrimFactory =
            if (sourceType is SynType.Array && arrayElemRendered != null) {
                emitter.primitiveArrayLayoutFactory(sourceType.elem, arrayElemRendered, emitter.activeCfgProfile.pointerWidth.toLong())
            } else {
                null
            }
        var arrayElementLayoutExpr: String? = null
        var codec =
            when {
                sourceType is SynType.Array && arrayPrimFactory != null -> ""
                sourceType is SynType.Array ->
                    if (!rawAddressTaken) {
                        null
                    } else {
                        val elemRendered = arrayElemRendered ?: emitter.renderType(sourceType.elem, selfTypeName)
                        arrayElementLayoutExpr = emitter.sourcePointerLayout(sourceType.elem, selfTypeName) ?: unresolvedLayout(elemRendered)
                        ""
                    }
                sourceType != null -> emitter.sourcePointerLayout(sourceType, selfTypeName)
                else ->
                    literalCodec?.let {
                        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.mem.PrimitiveLayouts")
                        "PrimitiveLayouts.$it"
                    }
            }
        if (codec == null) {
            val allowAddressBackedMutRef =
                usage.mutRefTaken && sourceType != null && emitter.shouldUseAddressBackedMutableReference(sourceType, selfTypeName)
            if (!rawAddressTaken && !allowAddressBackedMutRef) return null
            val fallbackRendered = sourceType?.let { emitter.renderType(it, selfTypeName) } ?: emitter.inferExprRenderedType(init, selfTypeName) ?: "Any?"
            codec = unresolvedLayout(fallbackRendered)
        }
        val previousTarget = emitter.currentExpectedParseTargetType
        val type =
            sourceType?.let { emitter.renderType(it, selfTypeName) }
                ?: literalCodec?.let { LITERAL_CODEC_TYPES[it] }
                ?: emitter.inferExprRenderedType(init, selfTypeName)
                ?: "Any?"
        emitter.currentExpectedParseTargetType = type
        val rhs =
            try {
                emitter.renderTypedNumericInitializer(init, emitter.resolveNumericKind(type), indent, selfTypeName)
            } finally {
                emitter.currentExpectedParseTargetType = previousTarget
            }
        val (boundName, _) = emitter.renderLocalPattern(local.pat, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
        val startPos = local.nonDrainingSpan().start()
        val storage = "localStorageL${startPos.line}C${startPos.column}"
        emitter.currentAddressableLocals = emitter.currentAddressableLocals + (boundName to storage)
        emitter.recordLocalInferredType(boundName, type, init, rhs, selfTypeName, null, pat = local.pat)
        val storageDecl =
            if (sourceType is SynType.Array) {
                emitter.currentAddressableArrayElementTypes = emitter.currentAddressableArrayElementTypes + (boundName to sourceType.elem)
                CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.mem.AddressableArrayLocal")
                val storageInit =
                    if (arrayPrimFactory != null) {
                        "AddressableArrayLocal.from${arrayPrimFactory.replaceFirstChar { it.uppercaseChar() }}($rhs)"
                    } else {
                        val elemRendered = arrayElemRendered ?: emitter.renderType(sourceType.elem, selfTypeName)
                        val elemLayout = arrayElementLayoutExpr ?: emitter.sourcePointerLayout(sourceType.elem, selfTypeName) ?: unresolvedLayout(elemRendered)
                        "AddressableArrayLocal.fromList($rhs, $elemLayout)"
                    }
                "${indent}val $storage = $storageInit"
            } else {
                CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.mem.AddressableLocal")
                "${indent}val $storage = AddressableLocal($rhs, $codec)"
            }
        return if (boundVarUsed) "$storageDecl\n${indent}$boundKeyword $boundName by $storage" else storageDecl
    }
}

private fun KotlinJ2KEmitter.bindDestructuringReceiverIfNeeded(
    rhs: String,
    isSimple: Boolean,
    rawPrefix: String,
    baseName: String,
    span: Span,
    indent: String,
    lines: MutableList<String>,
): String =
    if (isSimple) {
        rhs
    } else {
        val tmp = scopeBuilder.bindLocal(rawPrefix, baseName, span).kotlinName
        lines += "${indent}val $tmp = $rhs"
        tmp
    }

internal fun KotlinJ2KEmitter.renderStructDestructuringLocal(
    structPat: Pat.Struct,
    rhs: String,
    span: Span,
    indent: String,
    selfTypeName: String?,
): String {
    val structTypeName =
        structPat.path.lastIdentText()?.let {
            if (it == "Self") selfTypeName?.substringBefore('<')?.trim() else rustTypeIdentToKotlin(it)
        }
    val isSimpleRhs =
        rhs.all { it.isLetterOrDigit() || it == '_' } ||
            (rhs.startsWith("this.") && rhs.removePrefix("this.").all { it.isLetterOrDigit() || it == '_' })
    val lines = mutableListOf<String>()
    val receiverVar = bindDestructuringReceiverIfNeeded(rhs, isSimpleRhs, "_struct", "structVal", span, indent, lines)
    for (fp in structPat.fields.toList()) {
        val fieldProp = renderMemberName(fp.member)
        val subPat = unwrapTransparentPat(fp.pat)
        if (subPat is Pat.Ident && subPat.subpat == null) {
            val raw = subPat.ident.toString().removePrefix("r#")
            if (raw != "_") {
                val isMut = subPat.mutability != null
                val kw = if (isMut) "var" else "val"
                val ktVar = bindScopedValueIdent(raw, subPat.ident.span(), isMut)
                if (structTypeName != null) {
                    val fieldTy =
                        inspector.structFieldSynTypesByType[structTypeName]?.get(fieldProp)?.let { renderType(it, selfTypeName) }
                            ?: headerContext.crateStructFieldTypes["$structTypeName.$fieldProp"]
                    if (fieldTy != null) {
                        currentTypedLocals = currentTypedLocals + (ktVar to fieldTy)
                        if (fieldTy.endsWith("?")) currentNullableLocals = currentNullableLocals + ktVar
                    }
                    if (fieldProp in inspector.vecFieldsByType[structTypeName].orEmpty()) {
                        currentMutableListLocals = currentMutableListLocals + ktVar
                    }
                    if (fieldProp in inspector.nullableFieldsByType[structTypeName].orEmpty()) {
                        currentNullableLocals = currentNullableLocals + ktVar
                    }
                }
                lines += "$indent$kw $ktVar = $receiverVar.$fieldProp"
            }
        } else {
            lines += extractMatchArmBindings(fp.pat, "$receiverVar.$fieldProp", indent, selfTypeName, bindToScope = true)
        }
    }
    return lines.joinToString("\n")
}

internal fun KotlinJ2KEmitter.renderTupleStructDestructuringLocal(
    pat: Pat.TupleStruct,
    initExpr: Expr?,
    rhs: String,
    span: Span,
    indent: String,
    selfTypeName: String?,
): String? {
    if (isOptionSomeTupleStructPat(pat) || coreResultVariantMatchInfo(pat) != null || controlFlowVariantMatchInfo(pat) != null) return null
    val elems = pat.elems.toList()
    val bindings = mutableListOf<Triple<Int, Pat.Ident, Boolean>>()
    for ((idx, elemPat) in elems.withIndex()) {
        if (elemPat is Pat.Rest || elemPat is Pat.Wild) continue
        val inner = unwrapTransparentPat(elemPat)
        if (inner is Pat.Wild) continue
        if (inner is Pat.Ident && inner.subpat == null) {
            if (inner.ident.toString().removePrefix("r#") == "_") continue
            bindings += Triple(idx, inner, isMutablePattern(elemPat))
        } else {
            return null
        }
    }
    val rhsTrimmed = rhs.trim()
    if (bindings.isEmpty()) return if (initExpr != null && !isSideEffectFreeExpr(initExpr)) "$indent$rhsTrimmed" else ""

    val rawPathLast = pat.path.lastIdentText()
    val structTypeName =
        if (pat.path.segments.size == 1 && rawPathLast == "Self" && selfTypeName != null) {
            selfTypeName.substringBefore('<')
        } else {
            renderPathAsType(pat.path, selfTypeName).substringBefore('<')
        }
    val unwrappedInit = initExpr?.let(::unwrapParenOrGroupExpr)
    val isMutBorrow =
        (unwrappedInit is Expr.Reference && unwrappedInit.mutability != null) ||
            (unwrappedInit is Expr.Path && unwrappedInit.qself == null && unwrappedInit.path.isIdent("self") && currentReceiverIsMutBorrow)
    val isSimpleRhs = KotlinTokenWalker(rhsTrimmed).isSingleSimpleIdentifierOrThis()
    val lines = mutableListOf<String>()
    val receiverVar = bindDestructuringReceiverIfNeeded(rhsTrimmed, isSimpleRhs, "_tupleStruct", "tupleStructVal", span, indent, lines)
    val arity = elems.size
    for ((idx, identPat, isMutBinding) in bindings) {
        val raw = identPat.ident.toString().removePrefix("r#")
        val fieldProp = tupleFieldPropertyName(idx, arity)
        val fieldTy = resolveDeclaredOrCrateStructFieldType(structTypeName, fieldProp, selfTypeName)
        val ktVar = bindScopedValueIdent(raw, identPat.ident.span(), isMutBorrow || isMutBinding)
        if (fieldTy != null) {
            currentTypedLocals = currentTypedLocals + (ktVar to fieldTy)
            val fieldHead = fieldTy.substringBefore('<')
            if (fieldHead in setOf("MutableList", "ArrayList", "Vec", "List")) {
                currentMutableListLocals = currentMutableListLocals + ktVar
            } else if (fieldHead in setOf("VecDeque", "ArrayDeque")) {
                currentDequeLocals = currentDequeLocals + ktVar
            }
        }
        if (isMutBorrow) {
            lines += BorrowedPlacePropertyDelegateTemplate.renderDirectReceiver(indent, ktVar, receiverVar, fieldProp)
            currentBorrowedPlaceLocals = currentBorrowedPlaceLocals + ktVar
        } else {
            val kw = if (isMutBinding) "var" else "val"
            lines += "$indent$kw $ktVar = $receiverVar.$fieldProp"
        }
    }
    return lines.joinToString("\n")
}

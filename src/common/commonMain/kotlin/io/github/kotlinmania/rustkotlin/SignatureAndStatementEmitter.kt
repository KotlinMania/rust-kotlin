package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Span
import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
import io.github.kotlinmania.syn.Arm
import io.github.kotlinmania.syn.BinOp
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.ReturnType
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.WhereClause
import io.github.kotlinmania.syn.WherePredicate
import io.github.kotlinmania.syn.span

internal data class TypeParamWitnessSpec(
    val keyTp: String,
    val actualTp: String,
    val rawMethodName: String,
    val paramName: String,
    val kotlinFnType: String,
    val defaultExpr: String? = null,
)

internal val COMPANION_CAPABLE_VALUE_TYPES: Set<String>
    get() = activeRules().words("companionCapableValueTypes")

private val ITERATOR_ASSOC_TRAITS: Set<String>
    get() = activeRules().words("iteratorAssocTraits")

private val WITNESS_MUT_REF_SCALARS: Set<String>
    get() = activeRules().words("witnessMutRefScalars")

private val ZERO_ARG_SELF_PRODUCER_METHODS: Set<String>
    get() = activeRules().words("zeroArgSelfProducerMethods")

private val EXTERNAL_OVERRIDABLE_STDLIB_TRAITS: Set<String>
    get() = activeRules().words("externalOverridableStdlibTraits")

private val CORE_OPS_FORWARD_REF_TRAITS: Set<String>
    get() = activeRules().words("coreOpsForwardRefTraits")

private val KOTLIN_BUILTIN_ARITH_TYPES: Set<String>
    get() = activeRules().words("kotlinBuiltinArithTypes")

private fun collectCombinedWherePredicates(
    ownerWhere: WhereClause?,
    implWhere: WhereClause?,
    fnWhere: WhereClause?,
): List<WherePredicate> =
    listOfNotNull(ownerWhere, implWhere, fnWhere).flatMap { it.predicates.toList() }

private fun GenericParam.asKotlinTypeParamName(): String? =
    (this as? GenericParam.TypeParam)?.let { rustTypeIdentToKotlin(it.ident.toString()) }

internal fun KotlinJ2KEmitter.bindScopedValueIdent(
    rawName: String,
    span: Span,
    mutable: Boolean,
    baseKotlinName: String = rustValueIdentToKotlin(rawName),
): String =
    scopeBuilder
        .bindLocal(
            rawName = rawName,
            baseKotlinName = baseKotlinName,
            span = span,
            assignCount = if (mutable) AssignCount.Any else AssignCount.AtMostOnce,
        ).kotlinName

internal fun KotlinJ2KEmitter.liftDefaultlessAssocTypesFromBounds(
    combinedParams: List<GenericParam>,
    ownerWhere: WhereClause?,
    fnWhere: WhereClause?,
    implWhere: WhereClause? = null,
    ownerTypeSimpleName: String? = null,
): List<String> {
    val existingParams = combinedParams.mapNotNull(GenericParam::asKotlinTypeParamName).toSet()
    val lifted = mutableListOf<String>()

    fun inspectBounds(bounds: List<TypeParamBound>) {
        val allBoundAssocs = linkedSetOf<String>()
        for (b in bounds) {
            if (b is TypeParamBound.Trait) {
                val lastSeg = b.path.segments.last()
                val args = lastSeg?.arguments
                if (args is PathArguments.AngleBracketed) {
                    val gaList = args.args.toList()
                    gaList.filterIsInstance<GenericArgument.AssocTypeArg>().forEach {
                        allBoundAssocs += rustTypeIdentToKotlin(it.assoc.ident.toString())
                    }
                }
            }
        }
        for (b in bounds) {
            if (b is TypeParamBound.Trait) {
                val lastSeg = b.path.segments.last() ?: continue
                val rawIdent = lastSeg.ident.toString().removePrefix("r#")
                if (rawIdent in IGNORED_TRAIT_BOUNDS || rawIdent == "MaskElement") continue
                val ktTrait = rustTypeIdentToKotlin(rawIdent)
                if (ktTrait !in inspector.declaredTypeNames &&
                    rawIdent !in ITERATOR_ASSOC_TRAITS &&
                    (rawIdent == "IntoIterator" || isImplicitStdlibSupertraitPath(b.path))
                ) {
                    continue
                }
                val boundAssocs =
                    when (val args = lastSeg.arguments) {
                        is PathArguments.None -> allBoundAssocs
                        is PathArguments.AngleBracketed -> {
                            val gaList = args.args.toList()
                            allBoundAssocs +
                                gaList.filterIsInstance<GenericArgument.AssocTypeArg>().map {
                                    rustTypeIdentToKotlin(it.assoc.ident.toString())
                                }
                        }
                        else -> continue
                    }
                val ownerSimple = ownerTypeSimpleName ?: currentBoundedTypeName?.headKotlinTypePath()
                for (assoc in lookupTraitDefaultlessAssocTypes(ktTrait, b.path)) {
                    if (assoc !in boundAssocs && assoc != ownerSimple && assoc !in existingParams && assoc !in lifted) {
                        lifted += assoc
                    }
                }
            }
        }
    }
    for (gp in combinedParams) {
        if (gp is GenericParam.TypeParam) inspectBounds(gp.bounds.toList())
    }
    for (pred in collectCombinedWherePredicates(ownerWhere, implWhere, fnWhere)) {
        if (pred is WherePredicate.TypePredicate) inspectBounds(pred.bounds.toList())
    }
    return lifted
}

private class TypeParamWitnessAnalyzer(
    private val emitter: KotlinJ2KEmitter,
    private val sig: Signature,
    private val combinedParams: List<GenericParam>,
    private val ownerWhere: WhereClause?,
    private val implWhere: WhereClause?,
    private val fnWhere: WhereClause?,
    private val block: Block,
    private val selfTypeName: String?,
    private val orderedTypeParams: List<String>,
    private val suppressedBoundsOut: MutableMap<String, MutableSet<String>>,
) {
    private val fnTypeParams: Set<String> = orderedTypeParams.toSet()
    private val boundsByTp = linkedMapOf<String, MutableSet<String>>()
    private val localRawTypes = linkedMapOf<String, String>()
    private val localRenderedTypes = linkedMapOf<String, String>()
    private val discovered = linkedMapOf<Pair<String, String>, String>()
    private val observedCallSites = linkedMapOf<Pair<String, String>, MutableList<Pair<List<Expr>, String?>>>()
    private val fnRetRendered: String? = extractReturnSynType(sig.output)?.let { emitter.renderType(it, selfTypeName) }
    private val fnResultOkPayload: String? = rustResultOkPayloadType(fnRetRendered)

    fun analyze(): List<TypeParamWitnessSpec> {
        collectParamTraitBounds()
        val defaultBoundedParams = orderedTypeParams.filter { "Default" in boundsByTp[it].orEmpty() }
        seedParameterLocalTypes()
        scanTupleStructPatternBindings()
        scanBodyForWitnessCalls(defaultBoundedParams)

        val methodOrder = listOf("peek", "parse", "default", "clone", "from")
        val sortedEntries =
            discovered.entries.sortedWith(
                compareBy<Map.Entry<Pair<String, String>, String>> { entry ->
                    orderedTypeParams.indexOf(entry.value).let { if (it < 0) 999 else it }
                }.thenBy { entry ->
                    methodOrder.indexOf(entry.key.second).let { if (it < 0) 999 else it }
                },
            )

        emitter.currentTypeParamTraitBounds = boundsByTp.mapValues { it.value.toSet() }
        recordSuppressedBounds(sortedEntries)
        return sortedEntries.map { (keyPair, actualTp) -> buildWitnessSpec(keyPair, actualTp) }
    }

    private fun collectParamTraitBounds() {
        fun recordBounds(tpName: String, bounds: List<TypeParamBound>) {
            if (tpName !in fnTypeParams) return
            val set = boundsByTp.getOrPut(tpName) { linkedSetOf() }
            for (b in bounds) {
                if (b is TypeParamBound.Trait && b.maybe == null) {
                    b.path.lastKotlinTypeName()?.let(set::add)
                }
            }
        }
        for (gp in combinedParams) {
            if (gp is GenericParam.TypeParam) {
                recordBounds(rustTypeIdentToKotlin(gp.ident.toString()), gp.bounds.toList())
            }
        }
        for (pred in collectCombinedWherePredicates(ownerWhere, implWhere, fnWhere)) {
            if (pred is WherePredicate.TypePredicate) {
                recordBounds(emitter.renderType(pred.boundedTy, selfTypeName), pred.bounds.toList())
            }
        }
    }

    private fun seedParameterLocalTypes() {
        for (arg in sig.inputs.toList()) {
            if (arg is FnArg.Typed) {
                val rawIdent = emitter.extractRawParamIdent(arg.patType.pat) ?: continue
                val renderedTy = emitter.renderType(arg.patType.ty, selfTypeName)
                localRenderedTypes[rustValueIdentToKotlin(rawIdent)] = renderedTy
                if (renderedTy in fnTypeParams) localRawTypes[rawIdent] = renderedTy
            }
        }
    }

    private fun scanTupleStructPatternBindings() {
        val patScanner =
            object : ScopedBlockAstVisitor() {
                override fun visitPatTupleStruct(p: Pat.TupleStruct) {
                    val segList = p.path.segments.toList()
                    val variantKey =
                        segList
                            .filter { it.ident.toString() !in IGNORED_PATH_PREFIXES }
                            .joinToString(".") { rustTypeIdentToKotlin(it.ident.toString()) }
                    val fieldTys =
                        emitter.inspector.enumVariantFieldTypes[variantKey]
                            ?: emitter.inspector.enumVariantFieldTypes[variantKey.substringAfterLast('.')]
                    if (fieldTys != null) {
                        for ((idx, sub) in p.elems.toList().withIndex()) {
                            val rawSub = emitter.extractRawParamIdent(sub)
                            val fTy = fieldTys.getOrNull(idx)?.let { emitter.renderType(it, selfTypeName) }
                            if (rawSub != null && fTy != null) {
                                localRenderedTypes[rustValueIdentToKotlin(rawSub)] = fTy
                                if (fTy in fnTypeParams) localRawTypes[rawSub] = fTy
                            }
                        }
                    }
                    super.visitPatTupleStruct(p)
                }
            }
        patScanner.visitBlock(block)
    }

    private fun extractWitnessPathKey(pathExpr: Expr.Path): Pair<String, String>? {
        val qself = pathExpr.qself
        if (qself != null) {
            val baseTy = emitter.renderType(qself.ty, selfTypeName)
            val lastRaw = pathExpr.path.lastCleanIdent()
            if (baseTy in fnTypeParams && lastRaw != null && lastRaw.firstOrNull()?.isLowerCase() == true) {
                return baseTy to lastRaw
            }
            return null
        }
        val segs = pathExpr.path.segments.toList()
        if (segs.size == 2) {
            val rawFirst = segs[0].ident.toString()
            val firstKt = rustTypeIdentToKotlin(rawFirst)
            val secondRaw = segs[1].ident.toString().removePrefix("r#")
            if (rawFirst.firstOrNull()?.isUpperCase() == true &&
                firstKt in fnTypeParams &&
                secondRaw.firstOrNull()?.isLowerCase() == true
            ) {
                return firstKt to secondRaw
            }
        }
        return null
    }

    private fun extractWitnessCallKey(funcExpr: Expr): Pair<String, String>? =
        (emitter.unwrapParenOrGroupExpr(funcExpr) as? Expr.Path)?.let(::extractWitnessPathKey)

    private fun scanBodyForWitnessCalls(defaultBoundedParams: List<String>) {
        var usesDefaultForwarder = false
        var usesZeroArgParse = false
        var usesParseForwarder = false
        var usesTokenForwarder = false
        val clonedTypeParams = linkedSetOf<String>()

        val visitor =
            object : ScopedBlockAstVisitor() {
                private var pendingCallReturnHint: Pair<Expr.Call, String>? = null

                override fun onActiveStmt(s: Stmt) {
                    when (s) {
                        is Stmt.Local -> {
                            val rawLocal = emitter.extractRawParamIdent(s.pat)
                            val annTy = emitter.extractLocalTypeAnnotation(s.pat, selfTypeName)
                            val initExpr = s.init?.takeIf { it.diverge == null }?.expr
                            val unwrappedInit = initExpr?.let(emitter::unwrapParenOrGroupExpr)
                            if (unwrappedInit is Expr.Call && annTy != null) {
                                pendingCallReturnHint = unwrappedInit to annTy
                            }
                            super.onActiveStmt(s)
                            if (rawLocal != null) {
                                val ktLocal = rustValueIdentToKotlin(rawLocal)
                                if (annTy != null) {
                                    localRenderedTypes[ktLocal] = annTy
                                } else if (initExpr != null) {
                                    val wKey = (unwrappedInit as? Expr.Call)?.let { extractWitnessCallKey(it.func) }
                                    val zeroArgWitnessRet =
                                        if (wKey != null && unwrappedInit.args.isEmpty()) discovered[wKey] ?: wKey.first else null
                                    val prevLocals = emitter.currentTypedLocals
                                    val inferred =
                                        try {
                                            emitter.currentTypedLocals = prevLocals + localRenderedTypes
                                            zeroArgWitnessRet
                                                ?: emitter.inferExprRenderedType(initExpr, selfTypeName)
                                                ?: emitter.inferExprNumericKind(initExpr, selfTypeName)?.let(emitter::numericKindToPureTypeName)
                                        } finally {
                                            emitter.currentTypedLocals = prevLocals
                                        }
                                    if (inferred != null) localRenderedTypes[ktLocal] = inferred
                                }
                            }
                            return
                        }
                        is Stmt.ExprStmt -> {
                            val unwrapped = emitter.unwrapParenOrGroupExpr(s.expr)
                            if (s.semiToken != null && unwrapped is Expr.Call) {
                                pendingCallReturnHint = unwrapped to "Unit"
                            } else if (s.semiToken == null && unwrapped is Expr.Call && fnRetRendered != null) {
                                pendingCallReturnHint = unwrapped to fnRetRendered
                            }
                        }
                        else -> {}
                    }
                    super.onActiveStmt(s)
                }

                override fun visitExprReturn(e: Expr.Return) {
                    val retExpr = e.expr?.let(emitter::unwrapParenOrGroupExpr)
                    if (retExpr is Expr.Call && fnRetRendered != null) {
                        pendingCallReturnHint = retExpr to fnRetRendered
                    }
                    super.visitExprReturn(e)
                }

                override fun visitExprPath(e: Expr.Path) {
                    val directKey = extractWitnessPathKey(e)
                    if (directKey != null) {
                        discovered.getOrPut(directKey) { directKey.first }
                    } else if (e.qself == null) {
                        val segs = e.path.segments.toList()
                        if (segs.size == 2 &&
                            segs[0].ident.toString() == "Default" &&
                            segs[1].ident.toString().removePrefix("r#") == "default"
                        ) {
                            if (defaultBoundedParams.size == 1) {
                                discovered.getOrPut("*" to "default") { defaultBoundedParams[0] }
                            } else {
                                val hintedTp = pendingCallReturnHint?.second?.trim()
                                if (hintedTp != null && hintedTp in defaultBoundedParams) {
                                    discovered.getOrPut(hintedTp to "default") { hintedTp }
                                }
                            }
                        }
                    }
                    super.visitExprPath(e)
                }

                override fun visitExprCall(e: Expr.Call) {
                    val fnPath = (e.func as? Expr.Path)?.takeIf { it.qself == null }?.path
                    when (fnPath?.lastIdentText()) {
                        "parse_terminated", "parse_terminated_with" -> usesParseForwarder = true
                        "parse_separated_nonempty", "parse_separated_nonempty_with" -> {
                            usesParseForwarder = true
                            usesTokenForwarder = true
                        }
                        "Ok" -> recordWrappedSingleArgCallHint(e, fnResultOkPayload)
                        "Some" -> recordWrappedSingleArgCallHint(e, fnRetRendered?.takeIf { it.endsWith("?") }?.removeSuffix("?"))
                    }
                    val witnessKey = extractWitnessCallKey(e.func)
                    if (witnessKey != null) {
                        val hint = pendingCallReturnHint?.takeIf { it.first === e }?.second
                        if (pendingCallReturnHint?.first === e) pendingCallReturnHint = null
                        val callArgs = e.args.toList().filterNot { it is Expr.Tuple && it.elems.isEmpty() }
                        observedCallSites.getOrPut(witnessKey) { mutableListOf() }.add(callArgs to hint)
                    }
                    super.visitExprCall(e)
                }

                private fun recordWrappedSingleArgCallHint(call: Expr.Call, payloadHint: String?) {
                    if (payloadHint == null) return
                    val singleArg = call.args.toList().singleOrNull() ?: return
                    val innerCall = emitter.unwrapParenOrGroupExpr(singleArg) as? Expr.Call ?: return
                    pendingCallReturnHint = innerCall to payloadHint
                }

                override fun visitExprMethodCall(e: Expr.MethodCall) {
                    val m = e.method.toString()
                    val argCount = e.args.toList().count { !(it is Expr.Tuple && it.elems.isEmpty()) }
                    if (m == "parse" && argCount == 0 && e.turbofish == null) {
                        usesZeroArgParse = true
                    } else if ((m == "push" || m == "insert" || m == "extend") && argCount >= 1) {
                        usesDefaultForwarder = true
                    } else if (m == "clone" && argCount == 0 && e.turbofish == null) {
                        val recvRaw = emitter.singlePlainPathCleanIdent(emitter.unwrapTransparentCastExpr(e.receiver))
                        val tp = recvRaw?.let { localRawTypes[it] }
                        if (tp != null && "Clone" in boundsByTp[tp].orEmpty()) clonedTypeParams += tp
                    }
                    super.visitExprMethodCall(e)
                }
            }
        visitor.visitBlock(block)

        fun ensureDiscovered(candidates: Iterable<String>, method: String, predicate: (String) -> Boolean = { true }) {
            for (tp in candidates) {
                if (predicate(tp) && discovered.none { it.value == tp && it.key.second == method }) {
                    discovered[tp to method] = tp
                }
            }
        }
        if (usesDefaultForwarder) ensureDiscovered(defaultBoundedParams, "default")
        if (usesZeroArgParse || usesParseForwarder) {
            ensureDiscovered(orderedTypeParams, "parse") { tp ->
                val b = boundsByTp[tp].orEmpty()
                "Parse" in b || "ParseQuote" in b
            }
        }
        if (usesTokenForwarder) ensureDiscovered(orderedTypeParams, "peek") { "Token" in boundsByTp[it].orEmpty() }
        ensureDiscovered(orderedTypeParams, "clone") { it in clonedTypeParams }
    }

    private fun recordSuppressedBounds(sortedEntries: List<Map.Entry<Pair<String, String>, String>>) {
        for ((keyPair, actualTp) in sortedEntries) {
            val tpBounds = boundsByTp[actualTp].orEmpty()
            val toSuppress =
                when (keyPair.second) {
                    "parse" -> listOf("Parse", "ParseQuote")
                    "peek" -> listOf("Token")
                    else -> emptyList()
                }
            for (tr in toSuppress) {
                if (tr in tpBounds) {
                    suppressedBoundsOut.getOrPut(actualTp) { linkedSetOf() }.add(tr)
                    emitter.witnessedSuppressedTraitNames += tr
                }
            }
        }
    }

    private fun expandTraitDeclsWithSupertraits(seedTraits: Set<String>): List<Item.Trait> {
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque(seedTraits)
        val out = mutableListOf<Item.Trait>()
        while (queue.isNotEmpty()) {
            val trName = queue.removeFirst()
            if (!visited.add(trName)) continue
            val decl = emitter.lookupDeclaredTraitAst(trName) ?: continue
            out += decl
            for (sup in decl.supertraits.toList()) {
                if (sup is TypeParamBound.Trait && sup.maybe == null) {
                    sup.path.lastKotlinTypeName()?.let(queue::addLast)
                }
            }
        }
        return out
    }

    private fun inferCallSiteWitnessFnType(keyPair: Pair<String, String>, actualTp: String): String? {
        val sites = observedCallSites[keyPair].orEmpty()
        if (sites.isEmpty()) return null
        val arity = sites.first().first.size
        val prevLocals = emitter.currentTypedLocals
        return try {
            emitter.currentTypedLocals = prevLocals + localRenderedTypes
            val paramTypes =
                (0 until arity).map { argIdx ->
                    sites.firstNotNullOfOrNull { (args, _) ->
                        val argExpr = args.getOrNull(argIdx) ?: return@firstNotNullOfOrNull null
                        val unwrappedArg = emitter.unwrapParenOrGroupExpr(argExpr)
                        if (unwrappedArg is Expr.Reference && unwrappedArg.mutability != null && !emitter.headerContext.rawAbiMode) {
                            val innerTy =
                                emitter.inferExprRenderedType(unwrappedArg.expr, selfTypeName)
                                    ?: emitter.inferExprNumericKind(unwrappedArg.expr, selfTypeName)?.let(emitter::numericKindToPureTypeName)
                            if (innerTy != null && innerTy in WITNESS_MUT_REF_SCALARS) {
                                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
                                CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.mem.RustMutableReference")
                                return@firstNotNullOfOrNull "RustMutableReference<$innerTy>"
                            }
                        }
                        emitter.inferExprRenderedType(argExpr, selfTypeName)
                            ?: emitter.inferExprNumericKind(argExpr, selfTypeName)?.let(emitter::numericKindToPureTypeName)
                    } ?: "Any?"
                }
            val retType = sites.firstNotNullOfOrNull { (_, hint) -> hint } ?: if (arity == 0) actualTp else "Any?"
            "(${paramTypes.joinToString(", ")}) -> $retType"
        } finally {
            emitter.currentTypedLocals = prevLocals
        }
    }

    private fun buildWitnessSpec(keyPair: Pair<String, String>, actualTp: String): TypeParamWitnessSpec {
        val rawMethod = keyPair.second
        val ktMethod = rustValueIdentToKotlin(rawMethod)
        val sitesForKey = observedCallSites[keyPair].orEmpty()
        val fnType =
            when {
                rawMethod == "parse" -> "(ParseStream) -> Result<$actualTp>"
                rawMethod == "default" -> "() -> $actualTp"
                rawMethod == "peek" -> "(Cursor) -> Boolean"
                rawMethod == "clone" -> "($actualTp) -> $actualTp"
                rawMethod == "from" && sitesForKey.none { it.first.size != 1 } -> {
                    val inferredFn = inferCallSiteWitnessFnType(keyPair, actualTp)
                    val singleArgTy =
                        inferredFn
                            ?.substringAfter('(')
                            ?.substringBeforeLast(") ->")
                            ?.takeIf { it.isNotBlank() && it != "Any?" }
                            ?: "Any?"
                    "($singleArgTy) -> $actualTp"
                }
                rawMethod in ZERO_ARG_SELF_PRODUCER_METHODS && sitesForKey.all { it.first.isEmpty() } -> "() -> $actualTp"
                else -> {
                    val tpBounds = boundsByTp[actualTp].orEmpty()
                    val resolvedFromTrait =
                        expandTraitDeclsWithSupertraits(tpBounds).firstNotNullOfOrNull { traitDecl ->
                            val matchedFn =
                                traitDecl.items.firstOrNull { tItem ->
                                    tItem is TraitItem.Fn && tItem.sig.cleanFnName() == rawMethod
                                } as? TraitItem.Fn ?: return@firstNotNullOfOrNull null
                            val matchedInputs = matchedFn.sig.inputs.toList()
                            val paramTys =
                                matchedInputs.map { inp ->
                                    when (inp) {
                                        is FnArg.Receiver -> actualTp
                                        is FnArg.Typed -> emitter.renderType(inp.patType.ty, selfTypeName = actualTp)
                                    }
                                }
                            val retTy = extractReturnSynType(matchedFn.sig.output)?.let { emitter.renderType(it, selfTypeName = actualTp) } ?: "Unit"
                            "(${paramTys.joinToString(", ")}) -> $retTy"
                        }
                    resolvedFromTrait ?: inferCallSiteWitnessFnType(keyPair, actualTp) ?: "(Any?) -> Any?"
                }
            }
        return TypeParamWitnessSpec(
            keyTp = keyPair.first,
            actualTp = actualTp,
            rawMethodName = rawMethod,
            paramName = "${ktMethod}${actualTp}Fn",
            kotlinFnType = fnType,
            defaultExpr = if (rawMethod == "clone") "{ it }" else null,
        )
    }
}

internal fun KotlinJ2KEmitter.collectTypeParamWitnessSpecs(
    sig: Signature,
    combinedParams: List<GenericParam>,
    ownerWhere: WhereClause?,
    implWhere: WhereClause?,
    fnWhere: WhereClause?,
    block: Block?,
    selfTypeName: String?,
    suppressedBoundsOut: MutableMap<String, MutableSet<String>>,
): List<TypeParamWitnessSpec> {
    if (block == null) return emptyList()
    val orderedTypeParams =
        combinedParams
            .mapNotNull(GenericParam::asKotlinTypeParamName)
            .filter { it !in substitutedTypeParamTypes }
            .distinct()
    if (orderedTypeParams.isEmpty()) return emptyList()
    return TypeParamWitnessAnalyzer(
        emitter = this,
        sig = sig,
        combinedParams = combinedParams,
        ownerWhere = ownerWhere,
        implWhere = implWhere,
        fnWhere = fnWhere,
        block = block,
        selfTypeName = selfTypeName,
        orderedTypeParams = orderedTypeParams,
        suppressedBoundsOut = suppressedBoundsOut,
    ).analyze()
}

internal fun KotlinJ2KEmitter.describeSynTypeForDisambiguation(ty: SynType?): String {
    if (ty == null) return ""
    return when (val u = unwrapSynType(ty)) {
        is SynType.Reference -> describeSynTypeForDisambiguation(u.elem)
        is SynType.Ptr -> {
            val mutPrefix = if (u.mutability is PointerMutability.Mut) "MutPtr" else "ConstPtr"
            "$mutPrefix${describeSynTypeForDisambiguation(u.elem)}"
        }
        is SynType.Array -> {
            val elemDesc = describeSynTypeForDisambiguation(u.elem)
            val lenDesc = renderExpr(u.len, "", null).filter { it.isLetterOrDigit() }
            "${elemDesc}Array$lenDesc"
        }
        is SynType.Slice -> "${describeSynTypeForDisambiguation(u.elem)}Slice"
        is SynType.Tuple -> u.elems.toList().joinToString("") { describeSynTypeForDisambiguation(it) }
        is SynType.Path -> {
            val lastSeg = u.path.segments.last() ?: return ""
            val base = rustTypeIdentToKotlin(lastSeg.ident.toString())
            val angleList = (lastSeg.arguments as? PathArguments.AngleBracketed)?.args?.toList()
            val argsDesc =
                angleList
                    ?.mapNotNull { (it as? GenericArgument.TypeArg)?.type?.let(this::describeSynTypeForDisambiguation) }
                    ?.joinToString("")
                    .orEmpty()
            "$base$argsDesc"
        }
        else -> renderType(u, null).filter { it.isLetterOrDigit() }
    }
}

internal fun KotlinJ2KEmitter.disambiguateTraitArgCollisionFnName(fnName: String, traitPath: Path): String {
    val lastSeg = traitPath.segments.last() ?: return fnName
    val angleList = (lastSeg.arguments as? PathArguments.AngleBracketed)?.args?.toList()
    val typeArgs = angleList?.mapNotNull { (it as? GenericArgument.TypeArg)?.type }.orEmpty()
    if (typeArgs.isNotEmpty()) {
        val suffix = typeArgs.joinToString("") { describeSynTypeForDisambiguation(it) }
        if (suffix.isNotEmpty()) return "$fnName$suffix"
    }
    val traitKt = rustTypeIdentToKotlin(lastSeg.ident.toString())
    return if (traitKt.isNotEmpty() && !traitKt.equals(fnName, ignoreCase = true)) "$fnName$traitKt" else fnName
}

internal fun KotlinJ2KEmitter.disambiguateReceiverlessTraitImplFnName(
    traitPath: Path,
    fnName: String,
    selfSynType: SynType?,
): String {
    val traitName = traitPath.lastKotlinTypeName().orEmpty()
    val selfSuffix = describeSynTypeForDisambiguation(selfSynType)
    val prefix =
        when {
            traitName.isEmpty() || traitName.equals(fnName, ignoreCase = true) -> fnName
            else -> "${traitName.replaceFirstChar { it.lowercase() }}${fnName.replaceFirstChar { it.uppercase() }}"
        }
    return if (selfSuffix.isEmpty()) prefix else "$prefix$selfSuffix"
}

internal fun KotlinJ2KEmitter.disambiguateReceiverTraitImplFnName(
    traitPath: Path,
    fnName: String,
    selfSynType: SynType?,
): String {
    val lastSeg = traitPath.segments.last()
    val angleList = (lastSeg?.arguments as? PathArguments.AngleBracketed)?.args?.toList()
    val traitArgSuffix =
        angleList
            ?.mapNotNull { (it as? GenericArgument.TypeArg)?.type?.let(this::describeSynTypeForDisambiguation) }
            ?.joinToString("")
            .orEmpty()
    val unwrappedSelf = selfSynType?.let(::unwrapSynType)
    val selfArrSuffix =
        when {
            unwrappedSelf is SynType.Array -> "From${describeSynTypeForDisambiguation(unwrappedSelf)}"
            traitArgSuffix.isEmpty() &&
                (unwrappedSelf is SynType.Ptr || unwrappedSelf is SynType.Tuple || unwrappedSelf is SynType.Slice) ->
                "From${describeSynTypeForDisambiguation(unwrappedSelf)}"
            else -> ""
        }
    return "$fnName$traitArgSuffix$selfArrSuffix"
}

private fun KotlinJ2KEmitter.blockCallsSameAritySelfMethod(
    block: Block,
    rawMethodName: String,
    expectedArgCount: Int,
): Boolean {
    val cleanTarget = rawMethodName.removePrefix("r#")
    val visitor =
        object : ScopedPredicateAstVisitor() {
            override fun visitExprMethodCall(e: Expr.MethodCall) {
                if (e.method.toString().removePrefix("r#") == cleanTarget) {
                    val recv = unwrapParenOrGroupExpr(e.receiver)
                    val nonUnitCount = e.args.toList().count { !(it is Expr.Tuple && it.elems.isEmpty()) }
                    if (recv is Expr.Path && recv.qself == null && recv.path.isIdent("self") && nonUnitCount == expectedArgCount) {
                        markMatched()
                        return
                    }
                }
                super.visitExprMethodCall(e)
            }
        }
    visitor.visitBlock(block)
    return visitor.matched
}

private fun genericsMentionIdent(generics: Generics?, rawIdent: String): Boolean {
    if (generics == null) return false
    for (gp in generics.params.toList()) {
        if (gp is GenericParam.TypeParam) {
            if (synNodeMentionsRawIdent(rawIdent) { v -> gp.bounds.toList().forEach(v::visitTypeParamBound) }) return true
            if (gp.default?.type?.let { synTypeReferencesIdent(it, rawIdent) } == true) return true
        }
    }
    for (pred in generics.wherePredicatesList()) {
        if (pred is WherePredicate.TypePredicate) {
            if (synTypeReferencesIdent(pred.boundedTy, rawIdent)) return true
            if (synNodeMentionsRawIdent(rawIdent) { v -> pred.bounds.toList().forEach(v::visitTypeParamBound) }) return true
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.renderFunctionSignature(
    sig: Signature,
    visPrefix: String,
    openPrefix: String,
    selfTypeName: String?,
    ownerGenerics: Generics?,
    implGenerics: Generics? = null,
    scopeSignatures: MutableMap<String, Int>,
    isInterfaceMethod: Boolean,
    asTopLevelExtension: Boolean = false,
    isCompanionMember: Boolean = false,
    fnBlock: Block? = null,
): String? {
    val rawFnName = sig.ident.toString()
    val fnName = rustFunctionIdentToKotlin(rawFnName)
    val ownerParamsList = ownerGenerics?.params?.toList().orEmpty()
    val ownerTypeParams = ownerParamsList.mapNotNull(GenericParam::asKotlinTypeParamName).toSet()

    val includeOwner = asTopLevelExtension || isCompanionMember || selfTypeName == null
    val combinedParams =
        buildList {
            addAll(mergeGenericParams(ownerGenerics, sig.generics, includeOwnerParams = includeOwner))
            if (includeOwner && implGenerics != null && implGenerics !== ownerGenerics) {
                addAll(implGenerics.params.toList())
            }
        }
    val allParamsForWitness =
        buildList {
            addAll(mergeGenericParams(ownerGenerics, sig.generics, includeOwnerParams = true))
            if (implGenerics != null && implGenerics !== ownerGenerics) {
                addAll(implGenerics.params.toList())
            }
        }
    val suppressedWitnessBounds = linkedMapOf<String, MutableSet<String>>()
    val witnessSpecs =
        collectTypeParamWitnessSpecs(
            sig = sig,
            combinedParams = allParamsForWitness,
            ownerWhere = ownerGenerics?.whereClause,
            implWhere = if (implGenerics !== ownerGenerics) implGenerics?.whereClause else null,
            fnWhere = sig.generics.whereClause,
            block = fnBlock,
            selfTypeName = selfTypeName,
            suppressedBoundsOut = suppressedWitnessBounds,
        )
    if (suppressedWitnessBounds.isNotEmpty()) currentWitnessedTraitBounds = suppressedWitnessBounds
    if (witnessSpecs.isNotEmpty()) {
        currentTypeParamWitnessCalls =
            buildMap {
                for (ws in witnessSpecs) {
                    put(ws.keyTp to ws.rawMethodName, ws.paramName)
                    put(ws.actualTp to ws.rawMethodName, ws.paramName)
                }
            }
    }

    val excludedParams = (if (!includeOwner) ownerTypeParams else emptySet()) + substitutedTypeParamTypes.keys
    val liftedAssocs =
        liftDefaultlessAssocTypesFromBounds(
            combinedParams = combinedParams,
            ownerWhere = if (includeOwner) ownerGenerics?.whereClause else null,
            fnWhere = sig.generics.whereClause,
            implWhere = if (includeOwner && implGenerics !== ownerGenerics) implGenerics?.whereClause else null,
            ownerTypeSimpleName = selfTypeName?.simpleKotlinTypeName(),
        )
    if (liftedAssocs.isNotEmpty()) {
        activeTypeParamNames = activeTypeParamNames + liftedAssocs
        currentTraitAssocParamNames = currentTraitAssocParamNames + liftedAssocs
    }
    val baseTypeParamsRendered =
        renderTypeParametersFromParams(combinedParams, selfTypeName, excludeNames = excludedParams)
            .removePrefix("<")
            .removeSuffix(">")
    val fullTypeParamParts =
        buildList {
            if (liftedAssocs.isNotEmpty()) addAll(liftedAssocs)
            if (baseTypeParamsRendered.isNotEmpty()) add(baseTypeParamsRendered)
        }
    val typeParamsStr = if (fullTypeParamParts.isEmpty()) "" else "<${fullTypeParamParts.joinToString(", ")}>"

    val intoIterBoundedTypeParams = linkedSetOf<String>()

    fun hasIntoIterTraitBound(bounds: List<TypeParamBound>): Boolean =
        bounds.any { (it as? TypeParamBound.Trait)?.path?.lastIdentText() == "IntoIterator" }
    for (gp in combinedParams) {
        if (gp is GenericParam.TypeParam && hasIntoIterTraitBound(gp.bounds.toList())) {
            intoIterBoundedTypeParams += rustTypeIdentToKotlin(gp.ident.toString())
        }
    }
    for (pred in collectCombinedWherePredicates(ownerGenerics?.whereClause, implGenerics?.whereClause, sig.generics.whereClause)) {
        if (pred is WherePredicate.TypePredicate && hasIntoIterTraitBound(pred.bounds.toList())) {
            intoIterBoundedTypeParams += renderType(pred.boundedTy, selfTypeName)
        }
    }

    val isExternCallForwarding = isSingleCallExternForwardingWrapper(sig, fnBlock)
    val params = mutableListOf<String>()
    val paramTypeSignatureKeys = mutableListOf<String>()
    val inputs = sig.inputs.toList()
    var hasReceiver = false
    var paramIdx = 0
    for (arg in inputs) {
        when (arg) {
            is FnArg.Receiver -> {
                hasReceiver = true
                if (selfTypeName != null) currentTypedLocals = currentTypedLocals + ("this" to selfTypeName)
            }
            is FnArg.Typed -> {
                val rawParam = extractRawParamIdent(arg.patType.pat)
                val pName =
                    if (rawParam != null && rawParam != "_") {
                        val baseParamKt =
                            rustValueIdentToKotlin(rawParam).let {
                                if ((headerContext.rawAbiMode || headerContext.finalClasses) && it == "errno") "errnum" else it
                            }
                        bindScopedValueIdent(rawParam, arg.patType.pat.span(), isMutablePattern(arg.patType.pat), baseParamKt)
                    } else {
                        extractParamName(arg.patType.pat, paramIdx)
                    }
                val prevHint = currentSamNameHint
                val prevExternAbi = inExternAbiSignature
                val prevExternParam = inExternParamType
                currentSamNameHint = "${fnName.replaceFirstChar { it.uppercase() }}${pName.replaceFirstChar { it.uppercase() }}Callback"
                if (isExternCallForwarding) {
                    inExternAbiSignature = true
                    inExternParamType = true
                }
                val pType =
                    try {
                        renderType(arg.patType.ty, selfTypeName)
                    } finally {
                        currentSamNameHint = prevHint
                        inExternAbiSignature = prevExternAbi
                        inExternParamType = prevExternParam
                    }
                params += "$pName: $pType"
                currentTypedLocals = currentTypedLocals + (pName to pType)
                currentSourceParameterTypes = currentSourceParameterTypes + (pName to arg.patType.ty)
                val rawArgPath = unwrapSynType(arg.patType.ty) as? SynType.Path
                if (rawArgPath != null && rawArgPath.qself == null && rawArgPath.path.segments.size == 1) {
                    if (rawArgPath.path.firstKotlinTypeName() in currentIntoSubstitutedTypeParams) {
                        currentIntoSubstitutedParams = currentIntoSubstitutedParams + pName
                    }
                }
                if (pType in intoIterBoundedTypeParams || pType.startsWith("Iterable<")) currentIterableLocals = currentIterableLocals + pName
                if (pType.endsWith("?")) currentNullableLocals = currentNullableLocals + pName
                if (pType.startsWith("MutableList<")) currentMutableListLocals = currentMutableListLocals + pName
                paramTypeSignatureKeys += eraseTypeForSignatureKey(pType)
                paramIdx++
            }
        }
    }

    val defaultLayoutParamNames = linkedSetOf<String>()

    fun appendLayoutParam(paramName: String, layoutType: String, importTypeLayout: Boolean = false) {
        if (params.any { it.startsWith("$paramName:") }) return
        if (importTypeLayout) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.TypeLayout")
        }
        params += "$paramName: $layoutType"
        defaultLayoutParamNames += paramName
        paramTypeSignatureKeys += eraseTypeForSignatureKey(layoutType)
        currentTypedLocals = currentTypedLocals + (paramName to layoutType)
    }

    if (!hasReceiver) {
        transparentConstructorField(selfTypeName, fnBlock)?.let { (_, fieldType) ->
            appendLayoutParam("fieldLayout", "TypeLayout<$fieldType>", importTypeLayout = true)
        }
        if (isCompanionMember &&
            selfTypeName?.startsWith("MaybeUninit<") == true &&
            selfTypeName.endsWith(">") &&
            rawFnName in setOf("new", "uninit", "zeroed")
        ) {
            val muElem = selfTypeName.substringAfter('<').removeSuffix(">").trim()
            val hasMuTypeParam = combinedParams.any { it.asKotlinTypeParamName() == muElem }
            if (muElem.isNotEmpty() && hasMuTypeParam) {
                appendLayoutParam("typeLayoutT", "TypeLayout<$muElem>", importTypeLayout = true)
            }
        }
    }

    val rawReturnSynType = extractReturnSynType(sig.output)
    for (gp in combinedParams) {
        if (gp is GenericParam.ConstParam) {
            val rawConst = gp.ident.toString().removePrefix("r#")
            val cName = rustValueIdentToKotlin(rawConst)
            val cType = renderType(gp.ty, selfTypeName)
            if (params.none { it.startsWith("$cName:") }) {
                scopeBuilder.bindLocal(rawConst, cName, gp.ident.span())
                currentTypedLocals = currentTypedLocals + (cName to cType)
                val defaultExpr = gp.default?.expr
                val defaultSuffix =
                    if (defaultExpr != null) {
                        " = ${renderExpr(defaultExpr, "", selfTypeName)}"
                    } else if ("override" !in openPrefix &&
                        "actual" !in openPrefix &&
                        "expect" !in visPrefix &&
                        (
                            inputs.any { it is FnArg.Typed && synTypeReferencesIdent(it.patType.ty, rawConst) } ||
                                synTypeReferencesIdent(rawReturnSynType, rawConst) ||
                                synTypeReferencesIdent(currentStandaloneImplSelfSynType, rawConst) ||
                                genericsMentionIdent(ownerGenerics ?: implGenerics, rawConst)
                        )
                    ) {
                        " = ${defaultInitializerForType(cType)}"
                    } else {
                        ""
                    }
                params += "$cName: $cType$defaultSuffix"
                paramTypeSignatureKeys += eraseTypeForSignatureKey(cType)
            }
        }
    }
    val layoutParams =
        (
            CoreIntrinsicLowering.coreIntrinsicSignatureParameters(sig, fnBlock, ownerGenerics ?: implGenerics, selfTypeName, this) +
                genericFactorySignatureParameters(sig, fnBlock, selfTypeName)
        ).distinctBy { it.first }
    for ((layoutName, layoutType) in layoutParams) {
        appendLayoutParam(layoutName, layoutType)
    }

    val baseParamTypeSignatureKeys = paramTypeSignatureKeys.toList()
    for (ws in witnessSpecs) {
        if (params.none { it.startsWith("${ws.paramName}:") }) {
            val defaultSuffix = ws.defaultExpr?.let { " = $it" }.orEmpty()
            params += "${ws.paramName}: ${ws.kotlinFnType}$defaultSuffix"
            paramTypeSignatureKeys += eraseTypeForSignatureKey(ws.kotlinFnType)
        }
    }
    val topScope = scopeBuilder.scopeData.mutScope(scopeBuilder.topScopeId())
    topScope.setParamCount(params.size)

    var effectiveFnName = fnName
    val standaloneTraitPath = currentStandaloneImplTraitPath
    val standaloneSelfSynType = currentStandaloneImplSelfSynType
    val attachedTraitPath = currentAttachedImplTraitPath
    val isArraySelf = standaloneSelfSynType?.let(::unwrapSynType) is SynType.Array
    val receiverPrefix =
        if (asTopLevelExtension && !selfTypeName.isNullOrBlank()) {
            if (hasReceiver) {
                if (standaloneTraitPath != null && isArraySelf) {
                    effectiveFnName = disambiguateReceiverTraitImplFnName(standaloneTraitPath, fnName, standaloneSelfSynType)
                } else if (standaloneTraitPath != null) {
                    val traitKt = standaloneTraitPath.lastKotlinTypeName()
                    val selfDesc = describeSynTypeForDisambiguation(standaloneSelfSynType)
                    val selfBase = selfTypeName.headKotlinTypePath()
                    if (traitKt != null &&
                        selfDesc == "Char" &&
                        ((traitKt to "U16") in standaloneTraitImplKeys || (traitKt to "UShort") in standaloneTraitImplKeys)
                    ) {
                        effectiveFnName = "${fnName}Char"
                    } else if (traitKt != null &&
                        !traitKt.equals(fnName, ignoreCase = true) &&
                        selfBase !in activeTypeParamNames &&
                        fnBlock != null &&
                        blockCallsSameAritySelfMethod(fnBlock, rawFnName, inputs.size - 1)
                    ) {
                        effectiveFnName = "$fnName$traitKt"
                    }
                }
                "$selfTypeName."
            } else if (standaloneTraitPath != null) {
                val baseType = selfTypeName.headKotlinTypePath()
                val rawStandaloneTrait = standaloneTraitPath.lastCleanIdent()
                if (baseType in COMPANION_CAPABLE_VALUE_TYPES &&
                    rawStandaloneTrait in setOf("From", "TryFrom", "Step") &&
                    inputs.isNotEmpty()
                ) {
                    "$baseType.Companion."
                } else {
                    effectiveFnName = disambiguateReceiverlessTraitImplFnName(standaloneTraitPath, fnName, standaloneSelfSynType)
                    ""
                }
            } else {
                val baseType = selfTypeName.headKotlinTypePath()
                if (baseType.isNotEmpty() &&
                    baseType !in activeTypeParamNames &&
                    baseType !in KOTLIN_COLLECTION_INTERFACES &&
                    baseType.all { it.isLetterOrDigit() || it == '_' || it == '.' }
                ) {
                    "$baseType.Companion."
                } else {
                    ""
                }
            }
        } else {
            ""
        }
    val receiverKey = computeReceiverSignatureKey(receiverPrefix)
    val baseSigKey = "$receiverKey$effectiveFnName(${baseParamTypeSignatureKeys.joinToString(",")})"
    val baseSeenCount = scopeSignatures[baseSigKey] ?: 0
    val isErasedRefOpsForwarding =
        isErasedOpsReferenceForwardingImpl(
            selfSynType = if (asTopLevelExtension) standaloneSelfSynType else null,
            traitPath = if (asTopLevelExtension) standaloneTraitPath else attachedTraitPath,
        )
    if (!asTopLevelExtension && attachedTraitPath != null && !currentAttachedImplIsRefSelf) {
        scopeSignatures[baseSigKey] = baseSeenCount + 1
        if (baseSeenCount > 0 && !isErasedRefOpsForwarding) {
            effectiveFnName = disambiguateTraitArgCollisionFnName(effectiveFnName, attachedTraitPath)
        }
    }
    var sigKey = "$receiverKey$effectiveFnName(${paramTypeSignatureKeys.joinToString(",")})"
    var seenCount = if (sigKey == baseSigKey) baseSeenCount else (scopeSignatures[sigKey] ?: 0)
    if (seenCount > 0 && !isErasedRefOpsForwarding) {
        val disambiguated =
            when {
                asTopLevelExtension && hasReceiver && standaloneTraitPath != null ->
                    disambiguateReceiverTraitImplFnName(standaloneTraitPath, effectiveFnName, standaloneSelfSynType)
                !asTopLevelExtension && attachedTraitPath != null && !currentAttachedImplIsRefSelf ->
                    disambiguateTraitArgCollisionFnName(effectiveFnName, attachedTraitPath)
                else -> effectiveFnName
            }
        if (disambiguated != effectiveFnName) {
            effectiveFnName = disambiguated
            sigKey = "$receiverKey$effectiveFnName(${paramTypeSignatureKeys.joinToString(",")})"
            seenCount = scopeSignatures[sigKey] ?: 0
        }
    }
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) return null

    val retType = extractReturnSynType(sig.output)
    val prevExternRet = inExternAbiSignature
    if (isExternCallForwarding) inExternAbiSignature = true
    val implTraitPathForSig = attachedTraitPath ?: standaloneTraitPath
    val isFmtResultRet = isFmtResultSynType(retType)
    val implTraitResultOverride =
        if (isFmtResultRet && implTraitPathForSig != null) {
            val traitKt = implTraitPathForSig.lastKotlinTypeName()
            val ktFn = rustFunctionIdentToKotlin(rawFnName)
            if (traitKt != null && ktFn in lookupTraitResultMethodNames(traitKt, implTraitPathForSig)) {
                val okSyn = if (ktFn == "finish") currentImplAssocTypes["Output"] else null
                val errSyn = currentImplAssocTypes["Err"] ?: currentImplAssocTypes["Error"]
                val okTy = okSyn?.let { renderType(it, selfTypeName) } ?: "Unit"
                val errTy = errSyn?.let { renderType(it, selfTypeName) } ?: "Unit"
                if (usesTaggedCoreResult()) "${sourceResultName()}<$okTy, $errTy>" else "Result<$okTy>"
            } else {
                null
            }
        } else {
            null
        }
    val renderedReturnType =
        try {
            selfTypeName?.headKotlinTypePath()?.let { owner ->
                if (fnBlock != null) mutablePlaceReturnType(owner, rawFnName) else null
            } ?: implTraitResultOverride ?: retType?.let { renderType(it, selfTypeName) } ?: "Unit"
        } finally {
            inExternAbiSignature = prevExternRet
        }
    currentEnclosingReturnsFmtResult = isFmtResultRet && implTraitResultOverride == null
    currentEnclosingReturnRenderedType = renderedReturnType
    currentEnclosingReturnSynType = retType
    currentTailExpectsUnitResultOk = false
    val retTypeStr = if (renderedReturnType == "Unit") "" else ": $renderedReturnType"

    val effectiveOwnerWhere =
        if (includeOwner) {
            val ownerWhereClause = ownerGenerics?.whereClause
            val implWhereClause = if (implGenerics !== ownerGenerics) implGenerics?.whereClause else null
            val ownerPreds = ownerWhereClause?.predicates?.toList().orEmpty()
            val implPreds = implWhereClause?.predicates?.toList().orEmpty()
            if (ownerPreds.isEmpty() && implPreds.isEmpty()) {
                null
            } else {
                ownerWhereClause?.takeIf { implPreds.isEmpty() } ?: implWhereClause
            }
        } else {
            null
        }
    val liftedAssocNames =
        liftedAssocs.mapNotNullTo(linkedSetOf()) { it.substringBefore(':').trim().takeIf(String::isNotEmpty) }
    val whereClause =
        renderCombinedWhereClause(
            ownerWhere = effectiveOwnerWhere,
            fnWhere = sig.generics.whereClause,
            params = combinedParams,
            selfTypeName = selfTypeName,
            excludeNames = excludedParams,
            extraDeclaredNames = liftedAssocNames,
        )
    val attachedTraitBase = currentAttachedImplTraitPath?.lastCleanIdent()?.let(::rustTypeIdentToKotlin)
    val isKotlinAnyOverride =
        !asTopLevelExtension &&
            !isCompanionMember &&
            !isInterfaceMethod &&
            !selfTypeName.isNullOrBlank() &&
            (hasReceiver || currentAttachedImplIsObjectOwner) &&
            (
                effectiveFnName in currentStructOverrideMethodNames ||
                    (
                        hasReceiver &&
                            attachedTraitBase != null &&
                            attachedTraitBase in currentAttachedStructSuperTraitBaseNames &&
                            attachedTraitBase in EXTERNAL_OVERRIDABLE_STDLIB_TRAITS
                    ) ||
                    (effectiveFnName == "toString" && params.isEmpty()) ||
                    (effectiveFnName == "hashCode" && params.isEmpty()) ||
                    (effectiveFnName == "equals" && params.size == 1 && params[0].endsWith(": Any?"))
            )
    val effectiveOpenPrefix = if (isKotlinAnyOverride) "override " else openPrefix
    val sigGenericParams = sig.generics.params.toList()
    val fnOnlyTypeParamNames =
        sigGenericParams
            .filterIsInstance<GenericParam.TypeParam>()
            .map { rustTypeIdentToKotlin(it.ident.toString()) }
            .filter { it !in excludedParams && it !in ownerTypeParams }
    val reifiedFnTypeParams =
        if (!isInterfaceMethod &&
            effectiveOpenPrefix.isEmpty() &&
            fnBlock != null &&
            fnOnlyTypeParamNames.size == 1 &&
            liftedAssocs.isEmpty() &&
            whereClause.isEmpty()
        ) {
            val paramTypeIdents =
                params.flatMapTo(hashSetOf()) { param ->
                    KotlinTokenWalker(param.substringAfter(": ")).collectFilteredIdentifiers()
                }
            fnOnlyTypeParamNames
                .filter { tpKt ->
                    (renderedReturnType == tpKt || renderedReturnType == "$tpKt?") &&
                        tpKt !in paramTypeIdents
                }.toSet()
        } else {
            emptySet()
        }
    val effectiveTypeParamsStr =
        if (reifiedFnTypeParams.isEmpty() || typeParamsStr.isEmpty()) {
            typeParamsStr
        } else {
            reifiedFnTypeParams.fold(typeParamsStr) { acc, tpKt -> prefixTopLevelTypeParamWithReified(acc, tpKt) }
        }
    val inlinePrefix = if (reifiedFnTypeParams.isNotEmpty()) "inline " else ""
    val selfBaseForOp = selfTypeName?.simpleKotlinTypeName()
    val operatorPrefix =
        if (hasReceiver &&
            (
                (effectiveFnName == "compareTo" && renderedReturnType == "Int" && params.size == 1) ||
                    (effectiveFnName == "not" && params.isEmpty() && selfBaseForOp != "Boolean") ||
                    (
                        (effectiveFnName == "div" || effectiveFnName == "rem") &&
                            params.size == 1 &&
                            !params[0].contains('=') &&
                            !params[0].trimStart().startsWith("vararg ") &&
                            !(selfBaseForOp in KOTLIN_BUILTIN_ARITH_TYPES && baseParamTypeSignatureKeys.singleOrNull() in KOTLIN_BUILTIN_ARITH_TYPES)
                    )
            )
        ) {
            "operator "
        } else {
            ""
        }
    val typeParamsPart = if (effectiveTypeParamsStr.isNotEmpty()) "$effectiveTypeParamsStr " else ""
    val finalParams =
        if (!effectiveOpenPrefix.contains("override") && defaultLayoutParamNames.isNotEmpty()) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.UnresolvedTypeLayout")
            params.map { param ->
                val pName = param.substringBefore(':').trim()
                if (pName in defaultLayoutParamNames && !param.contains('=')) "$param = UnresolvedTypeLayout()" else param
            }
        } else {
            params
        }
    val infixPrefix =
        if (hasReceiver &&
            (effectiveFnName == "shl" || effectiveFnName == "shr") &&
            finalParams.size == 1 &&
            !finalParams[0].contains('=') &&
            !finalParams[0].trimStart().startsWith("vararg ") &&
            !(selfBaseForOp in setOf("Int", "Long", "UInt", "ULong") && baseParamTypeSignatureKeys == listOf("Int"))
        ) {
            "infix "
        } else {
            ""
        }
    return "${visPrefix}${effectiveOpenPrefix}${operatorPrefix}${infixPrefix}${inlinePrefix}fun ${typeParamsPart}${receiverPrefix}$effectiveFnName(${finalParams.joinToString(", ")})${retTypeStr}$whereClause"
}

private fun isErasedOpsReferenceForwardingImpl(
    selfSynType: SynType?,
    traitPath: Path?,
): Boolean {
    if (traitPath == null) return false
    val lastSeg = traitPath.segments.last() ?: return false
    val rawTrait = lastSeg.ident.toString().removePrefix("r#")
    if (rawTrait !in CORE_OPS_FORWARD_REF_TRAITS) return false
    if (selfSynType?.let(::unwrapSynType) is SynType.Reference) return true
    val args = (lastSeg.arguments as? PathArguments.AngleBracketed)?.args?.toList().orEmpty()
    return args.any { it is GenericArgument.TypeArg && unwrapSynType(it.type) is SynType.Reference }
}

private fun prefixTopLevelTypeParamWithReified(typeParamsStr: String, tpKt: String): String {
    if (typeParamsStr.isEmpty() || tpKt.isEmpty()) return typeParamsStr
    return ReifiedTypeParamRewriter(typeParamsStr, tpKt).rewrite()
}

private class ReifiedTypeParamRewriter(
    source: String,
    private val targetParam: String,
) : KotlinTokenRewriter(source) {
    private var angleDepth: Int = 0
    private var atSlotStart: Boolean = false

    override fun emitAt(index: Int, out: StringBuilder): Int {
        val tok = tokens[index]
        when {
            tok is KotlinLexToken.Punct && tok.ch == '<' -> {
                angleDepth++
                atSlotStart = angleDepth == 1
                out.append(tok.rawText)
            }
            tok is KotlinLexToken.Punct && tok.ch == '>' -> {
                angleDepth = (angleDepth - 1).coerceAtLeast(0)
                atSlotStart = false
                out.append(tok.rawText)
            }
            tok is KotlinLexToken.Punct && tok.ch == ',' && angleDepth == 1 -> {
                atSlotStart = true
                out.append(tok.rawText)
            }
            angleDepth == 1 && atSlotStart && (tok is KotlinLexToken.Whitespace || tok is KotlinLexToken.Newline) -> {
                out.append(tok.rawText)
            }
            angleDepth == 1 && atSlotStart -> {
                if (tok.rawText == targetParam) out.append("reified ").append(tok.rawText) else out.append(tok.rawText)
                atSlotStart = false
            }
            else -> out.append(tok.rawText)
        }
        return index + 1
    }
}

internal fun KotlinJ2KEmitter.isSingleCallExternForwardingWrapper(sig: Signature, block: Block?): Boolean {
    if (block == null || block.stmts.size != 1) return false
    val onlyStmt = block.stmts[0] as? Stmt.ExprStmt ?: return false
    var coreExpr =
        when (val e = unwrapTransparentCastExpr(onlyStmt.expr)) {
            is Expr.Return -> e.expr?.let(::unwrapTransparentCastExpr)
            else -> e
        } ?: return false
    while (coreExpr is Expr.Cast) coreExpr = unwrapTransparentCastExpr(coreExpr.expr)
    if (coreExpr !is Expr.Call) return false
    if (sig.abi == null) {
        val calleePath = (unwrapParenOrGroupExpr(coreExpr.func) as? Expr.Path)?.takeIf { it.qself == null } ?: return false
        val pathSegs = calleePath.path.segments.toList()
        val segNames =
            pathSegs.mapNotNull { seg ->
                val clean = seg.ident.toString().removePrefix("r#")
                clean.takeIf(String::isNotEmpty)
            }
        val firstSeg = segNames.firstOrNull() ?: return false
        if (firstSeg == "Self" || firstSeg.firstOrNull()?.isUpperCase() == true) return false
        val lastSeg = segNames.last()
        val calleeKt = rustFunctionIdentToKotlin(lastSeg)
        if (calleeKt !in inspector.externFnKotlinNames && lastSeg in inspector.declaredTopLevelFnRawNames) return false
        if (!headerContext.rawAbiMode &&
            calleeKt !in inspector.externFnKotlinNames &&
            firstSeg !in setOf("libc", "ffi", "sys", "windows_sys")
        ) {
            return false
        }
    }
    val sigInputs = sig.inputs.toList()
    val paramRawNames =
        sigInputs
            .mapNotNull { (it as? FnArg.Typed)?.let { t -> extractRawParamIdent(t.patType.pat) } }
            .filterTo(linkedSetOf()) { it != "_" }
    if (paramRawNames.isEmpty()) return true
    val visitor =
        object : ScopedPredicateAstVisitor() {
            override fun visitExprUnary(e: Expr.Unary) {
                if (e.op is UnOp.Deref && singlePlainPathCleanIdent(e.expr) in paramRawNames) {
                    markMatched()
                    return
                }
                super.visitExprUnary(e)
            }
        }
    visitor.visitBlock(block)
    return !visitor.matched
}

private fun KotlinJ2KEmitter.appendDestructuredParamLines(
    pat: Pat,
    paramKt: String,
    innerIndent: String,
    selfTypeName: String?,
    block: Block,
    out: MutableList<String>,
) {
    fun appendRenderedPatternFallback(targetPat: Pat) {
        val (patText, _) = renderLocalPattern(targetPat, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
        if (patText.isNotEmpty() && patText != "unused") {
            val kw = if (isMutablePattern(targetPat)) "var" else "val"
            out += "$innerIndent$kw $patText = $paramKt"
        }
    }

    val unwrapped = unwrapTransparentPat(pat)
    when (unwrapped) {
        is Pat.Wild, is Pat.Rest -> return
        is Pat.Ident -> {
            val raw = unwrapped.ident.toString().removePrefix("r#")
            if (raw == "_") return
            val isMut = isMutablePattern(pat) || blockReassignsIdent(block, raw)
            val ktName = bindScopedValueIdent(raw, unwrapped.ident.span(), isMut)
            currentTypedLocals[paramKt]?.let { ty ->
                currentTypedLocals = currentTypedLocals + (ktName to ty)
                if (ty.endsWith("?")) currentNullableLocals = currentNullableLocals + ktName
            }
            val kw = if (isMut) "var" else "val"
            out += "$innerIndent$kw $ktName = $paramKt"
        }
        is Pat.Tuple -> {
            val elems = unwrapped.elems.toList()
            if (elems.size == 1) {
                appendDestructuredParamLines(elems[0], paramKt, innerIndent, selfTypeName, block, out)
            } else {
                appendRenderedPatternFallback(unwrapped)
            }
        }
        is Pat.Struct -> {
            renderStructDestructuringLocal(unwrapped, paramKt, pat.span(), innerIndent, selfTypeName)
                .lines()
                .filter(String::isNotBlank)
                .forEach(out::add)
        }
        is Pat.TupleStruct -> {
            val rendered = renderTupleStructDestructuringLocal(unwrapped, initExpr = null, rhs = paramKt, span = pat.span(), indent = innerIndent, selfTypeName = selfTypeName)
            if (rendered != null) {
                rendered.lines().filter(String::isNotBlank).forEach(out::add)
            } else {
                appendRenderedPatternFallback(unwrapped)
            }
        }
        else -> appendRenderedPatternFallback(unwrapped)
    }
}

internal abstract class ScopedMutationAstScanner(
    protected val emitter: KotlinJ2KEmitter,
    private val includeCompoundBinaryAssign: Boolean = true,
) : ScopedPredicateAstVisitor() {
    val mutated: Boolean
        get() = matched

    protected abstract fun matchesLhs(lhs: Expr): Boolean

    override fun visitExprAssign(e: Expr.Assign) {
        if (matched) return
        if (matchesLhs(e.left)) {
            markMatched()
            return
        }
        super.visitExprAssign(e)
    }

    override fun visitExprBinary(e: Expr.Binary) {
        if (matched) return
        if (includeCompoundBinaryAssign && emitter.isCompoundAssignOp(e.op) && matchesLhs(e.left)) {
            markMatched()
            return
        }
        super.visitExprBinary(e)
    }
}

private class DirectSelfReassignmentScanner(
    emitter: KotlinJ2KEmitter,
) : ScopedMutationAstScanner(emitter, includeCompoundBinaryAssign = false) {
    override fun matchesLhs(lhs: Expr): Boolean {
        val unwrapped = emitter.unwrapParenOrGroupExpr(lhs)
        val candidate = if (unwrapped is Expr.Unary && unwrapped.op is UnOp.Deref) emitter.unwrapParenOrGroupExpr(unwrapped.expr) else unwrapped
        return candidate is Expr.Path && candidate.qself == null && candidate.path.isIdent("self")
    }
}

private class IdentReassignmentScanner(
    emitter: KotlinJ2KEmitter,
    private val targetRawIdent: String,
) : ScopedMutationAstScanner(emitter) {
    override fun matchesLhs(lhs: Expr): Boolean =
        emitter.extractAssignedRootIdent(lhs) == targetRawIdent

    override fun visitExprMatch(e: Expr.Match) {
        if (matched) return
        val subj = emitter.unwrapParenOrGroupExpr(e.expr)
        if (subj is Expr.Reference &&
            subj.mutability != null &&
            emitter.extractAssignedRootIdent(subj.expr) == targetRawIdent &&
            e.arms.any { emitter.matchArmMutatesTupleVariantBinding(it) }
        ) {
            markMatched()
            return
        }
        super.visitExprMatch(e)
    }
}

private class BindingSetMutationScanner(
    emitter: KotlinJ2KEmitter,
    private val rawBindings: Set<String>,
) : ScopedMutationAstScanner(emitter) {
    override fun matchesLhs(lhs: Expr): Boolean =
        emitter.extractAssignedRootIdent(lhs) in rawBindings
}

internal fun KotlinJ2KEmitter.blockReassignsDirectSelf(block: Block): Boolean =
    DirectSelfReassignmentScanner(this).also { it.visitBlock(block) }.mutated

internal fun KotlinJ2KEmitter.computeReassignedParamShadows(
    sig: Signature,
    block: Block,
    innerIndent: String,
    selfTypeName: String? = null,
): List<String> {
    val shadows = mutableListOf<String>()
    var paramIdx = 0
    for (arg in sig.inputs) {
        if (arg is FnArg.Receiver) {
            if (blockReassignsDirectSelf(block)) {
                val shadowSelf = bindScopedValueIdent("self", arg.span(), mutable = true, baseKotlinName = "selfMut")
                if (selfTypeName != null) currentTypedLocals = currentTypedLocals + (shadowSelf to selfTypeName)
                shadows += "${innerIndent}var $shadowSelf = this"
            }
            continue
        }
        if (arg is FnArg.Typed) {
            val rawParam = extractRawParamIdent(arg.patType.pat)
            if (rawParam != null && rawParam != "_") {
                if (blockReassignsIdent(block, rawParam)) {
                    val ktName = scopeBuilder.resolveLocalKotlinName(rawParam) ?: rustValueIdentToKotlin(rawParam)
                    val shadowKt =
                        if (headerContext.isBatchCrateOutput) {
                            bindScopedValueIdent(rawParam, arg.patType.pat.span(), mutable = true, baseKotlinName = ktName)
                        } else {
                            ktName
                        }
                    if (shadowKt != ktName) {
                        currentTypedLocals[ktName]?.let { currentTypedLocals = currentTypedLocals + (shadowKt to it) }
                        if (ktName in currentNullableLocals) currentNullableLocals = currentNullableLocals + shadowKt
                    }
                    shadows += "${innerIndent}var $shadowKt = $ktName"
                }
            } else if (rawParam == null) {
                appendDestructuredParamLines(arg.patType.pat, extractParamName(arg.patType.pat, paramIdx), innerIndent, selfTypeName, block, shadows)
            }
            paramIdx++
        }
    }
    return shadows
}

internal fun KotlinJ2KEmitter.blockReassignsIdent(block: Block, targetRawIdent: String): Boolean =
    IdentReassignmentScanner(this, targetRawIdent).also { it.visitBlock(block) }.mutated

internal fun KotlinJ2KEmitter.matchArmMutatesTupleVariantBinding(arm: Arm): Boolean {
    val pat = unwrapTransparentPat(armPattern(arm)) as? Pat.TupleStruct ?: return false
    if (isOptionSomeTupleStructPat(pat)) return false
    val elemList = pat.elems.toList()
    val rawBindings = elemList.mapNotNull(::extractRawParamIdent).filterTo(linkedSetOf()) { it != "_" }
    if (rawBindings.isEmpty()) return false
    return BindingSetMutationScanner(this, rawBindings).also { it.visitExpr(arm.body) }.mutated
}

internal fun KotlinJ2KEmitter.extractAssignedRootIdent(lhs: Expr): String? =
    when (lhs) {
        is Expr.Path -> if (lhs.qself == null && lhs.path.segments.len() == 1) lhs.path.firstCleanIdent() else null
        is Expr.Paren -> extractAssignedRootIdent(lhs.expr)
        is Expr.Group -> extractAssignedRootIdent(lhs.expr)
        is Expr.Unary -> if (lhs.op is UnOp.Deref) extractAssignedRootIdent(lhs.expr) else null
        else -> null
    }

internal fun KotlinJ2KEmitter.isCompoundAssignOp(op: BinOp): Boolean =
    when (op) {
        is BinOp.AddAssign, is BinOp.SubAssign, is BinOp.MulAssign, is BinOp.DivAssign, is BinOp.RemAssign,
        is BinOp.BitXorAssign, is BinOp.BitAndAssign, is BinOp.BitOrAssign, is BinOp.ShlAssign, is BinOp.ShrAssign,
        -> true
        else -> false
    }

internal fun KotlinJ2KEmitter.computeReceiverSignatureKey(receiverPrefix: String): String =
    when {
        receiverPrefix.isEmpty() -> ""
        receiverPrefix.endsWith(".Companion.") ->
            receiverPrefix.removeSuffix(".Companion.").headKotlinTypePath() + ".Companion."
        else ->
            receiverPrefix.removeSuffix(".").headKotlinTypePath() + "."
    }

internal fun KotlinJ2KEmitter.eraseTypeForSignatureKey(renderedType: String): String {
    val parsed = renderedType.asKotlinRenderedType() ?: return renderedType
    val base = parsed.headPath
    if (base in activeTypeParamNames) return "*"
    return if (base in KOTLIN_JVM_VALUE_TYPES && parsed.isNullable) "$base?" else base
}

internal fun KotlinJ2KEmitter.extractRawParamIdent(pat: Pat): String? =
    when (pat) {
        is Pat.Ident -> pat.ident.toString().removePrefix("r#")
        is Pat.Reference -> extractRawParamIdent(pat.pat)
        is Pat.TypeAscription -> extractRawParamIdent(pat.pat)
        is Pat.PatParen -> extractRawParamIdent(pat.pat)
        else -> null
    }

internal fun KotlinJ2KEmitter.mergeGenericParams(
    owner: Generics?,
    fnGenerics: Generics,
    includeOwnerParams: Boolean,
): List<GenericParam> {
    val fnList = fnGenerics.params.toList()
    if (!includeOwnerParams || owner == null) return fnList
    val ownerList = owner.params.toList()
    return if (ownerList.isEmpty()) fnList else ownerList + fnList
}

internal fun KotlinJ2KEmitter.disambiguateName(baseName: String, scopeSignatures: MutableMap<String, Int>): String {
    val count = scopeSignatures[baseName] ?: 0
    scopeSignatures[baseName] = count + 1
    return if (count == 0) baseName else "${baseName}$count"
}

internal fun extractReturnSynType(output: ReturnType): SynType? =
    when (output) {
        is ReturnType.Default -> null
        is ReturnType.TypeReturn -> output.ty
    }

internal fun KotlinJ2KEmitter.extractParamName(pat: Pat, index: Int): String =
    when (pat) {
        is Pat.Ident -> rustValueIdentToKotlin(pat.ident.toString())
        is Pat.Reference -> extractParamName(pat.pat, index)
        is Pat.TypeAscription -> extractParamName(pat.pat, index)
        is Pat.Wild -> "arg$index"
        is Pat.Tuple -> "tupleArg$index"
        is Pat.TupleStruct -> "destructuredArg$index"
        is Pat.Struct -> "structArg$index"
        else -> "arg$index"
    }

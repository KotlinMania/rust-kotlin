package io.github.kotlinmania.rustkotlin

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
import io.github.kotlinmania.syn.gen.Visit
import io.github.kotlinmania.syn.span

internal data class TypeParamWitnessSpec(
    val keyTp: String,
    val actualTp: String,
    val rawMethodName: String,
    val paramName: String,
    val kotlinFnType: String,
    val defaultExpr: String? = null,
)

internal val COMPANION_CAPABLE_VALUE_TYPES: Set<String> =
    setOf(
        "Byte",
        "Short",
        "Int",
        "Long",
        "UByte",
        "UShort",
        "UInt",
        "ULong",
        "Float",
        "Double",
        "Boolean",
        "Char",
        "String",
        "kotlin.Byte",
        "kotlin.Short",
        "kotlin.Int",
        "kotlin.Long",
        "kotlin.UByte",
        "kotlin.UShort",
        "kotlin.UInt",
        "kotlin.ULong",
        "kotlin.Float",
        "kotlin.Double",
        "kotlin.Boolean",
        "kotlin.Char",
        "kotlin.String",
        "I128",
        "U128",
        "F16",
        "F128",
        "RustChar",
    )

internal fun KotlinJ2KEmitter.liftDefaultlessAssocTypesFromBounds(
    combinedParams: List<GenericParam>,
    ownerWhere: WhereClause?,
    fnWhere: WhereClause?,
    implWhere: WhereClause? = null,
    ownerTypeSimpleName: String? = null,
): List<String> {
    val existingParams =
        combinedParams
            .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
            .toSet()
    val lifted = mutableListOf<String>()
    val iteratorAssocTraits =
        setOf("Iterator", "DoubleEndedIterator", "ExactSizeIterator", "FusedIterator", "AsyncIterator")
    fun inspectBounds(bounds: List<TypeParamBound>) {
        val allBoundAssocs = linkedSetOf<String>()
        for (b in bounds) {
            if (b is TypeParamBound.Trait) {
                val args =
                    b.path.segments
                        .last()
                        ?.arguments
                if (args is PathArguments.AngleBracketed) {
                    args.args
                        .toList()
                        .filterIsInstance<GenericArgument.AssocTypeArg>()
                        .forEach { allBoundAssocs += rustTypeIdentToKotlin(it.assoc.ident.toString()) }
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
                    rawIdent !in iteratorAssocTraits &&
                    (rawIdent == "IntoIterator" || isImplicitStdlibSupertraitPath(b.path))
                ) {
                    continue
                }
                val boundAssocs =
                    when (val args = lastSeg.arguments) {
                        is PathArguments.None -> allBoundAssocs
                        is PathArguments.AngleBracketed ->
                            allBoundAssocs +
                                args.args
                                    .toList()
                                    .filterIsInstance<GenericArgument.AssocTypeArg>()
                                    .map { rustTypeIdentToKotlin(it.assoc.ident.toString()) }
                        else -> continue
                    }
                val ownerSimple = ownerTypeSimpleName ?: currentBoundedTypeName?.substringBefore('<')?.trim()
                for (assoc in lookupTraitDefaultlessAssocTypes(ktTrait, b.path)) {
                    if (assoc !in boundAssocs && assoc != ownerSimple && assoc !in existingParams && assoc !in lifted) {
                        lifted += assoc
                    }
                }
            }
        }
    }
    for (gp in combinedParams) {
        if (gp is GenericParam.TypeParam) {
            inspectBounds(gp.bounds.toList())
        }
    }
    val allWherePredicates =
        ownerWhere?.predicates?.toList().orEmpty() +
            implWhere?.predicates?.toList().orEmpty() +
            fnWhere?.predicates?.toList().orEmpty()
    for (pred in allWherePredicates) {
        if (pred is WherePredicate.TypePredicate) {
            inspectBounds(pred.bounds.toList())
        }
    }
    return lifted
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
            .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
            .filter { it !in substitutedTypeParamTypes }
            .distinct()
    val fnTypeParams = orderedTypeParams.toSet()
    if (fnTypeParams.isEmpty()) return emptyList()

    val boundsByTp = linkedMapOf<String, MutableSet<String>>()
    fun recordBounds(tpName: String, bounds: List<TypeParamBound>) {
        if (tpName !in fnTypeParams) return
        val set = boundsByTp.getOrPut(tpName) { linkedSetOf() }
        for (b in bounds) {
            if (b is TypeParamBound.Trait && b.maybe == null) {
                val lastSeg = b.path.segments.last() ?: continue
                val last = lastSeg.ident.toString()
                set += rustTypeIdentToKotlin(last)
            }
        }
    }
    for (gp in combinedParams) {
        if (gp is GenericParam.TypeParam) {
            recordBounds(rustTypeIdentToKotlin(gp.ident.toString()), gp.bounds.toList())
        }
    }
    val allWherePreds =
        ownerWhere?.predicates?.toList().orEmpty() +
            implWhere?.predicates?.toList().orEmpty() +
            fnWhere?.predicates?.toList().orEmpty()
    for (pred in allWherePreds) {
        if (pred is WherePredicate.TypePredicate) {
            recordBounds(renderType(pred.boundedTy, selfTypeName), pred.bounds.toList())
        }
    }
    val defaultBoundedParams = orderedTypeParams.filter { "Default" in boundsByTp[it].orEmpty() }

    val localRawTypes = linkedMapOf<String, String>()
    val localRenderedTypes = linkedMapOf<String, String>()
    for (arg in sig.inputs.toList()) {
        if (arg is FnArg.Typed) {
            val rawIdent = extractRawParamIdent(arg.patType.pat)
            val renderedTy = renderType(arg.patType.ty, selfTypeName)
            if (rawIdent != null) {
                localRenderedTypes[rustValueIdentToKotlin(rawIdent)] = renderedTy
                if (renderedTy in fnTypeParams) {
                    localRawTypes[rawIdent] = renderedTy
                }
            }
        }
    }
    val patScanner =
        object : Visit() {
            override fun visitItemFn(i: Item.Fn) {}

            override fun visitStmt(s: Stmt) {
                if (isLeadingCfgStmtDisabled(s)) return
                super.visitStmt(s)
            }

            override fun visitPatTupleStruct(p: Pat.TupleStruct) {
                val variantKey =
                    p.path.segments
                        .toList()
                        .filter { it.ident.toString() !in IGNORED_PATH_PREFIXES }
                        .joinToString(".") { rustTypeIdentToKotlin(it.ident.toString()) }
                val fieldTys =
                    inspector.enumVariantFieldTypes[variantKey]
                        ?: inspector.enumVariantFieldTypes[variantKey.substringAfterLast('.')]
                if (fieldTys != null) {
                    for ((idx, sub) in p.elems.toList().withIndex()) {
                        val rawSub = extractRawParamIdent(sub)
                        val fTy = fieldTys.getOrNull(idx)?.let { renderType(it, selfTypeName) }
                        if (rawSub != null && fTy != null) {
                            localRenderedTypes[rustValueIdentToKotlin(rawSub)] = fTy
                            if (fTy in fnTypeParams) {
                                localRawTypes[rawSub] = fTy
                            }
                        }
                    }
                }
                super.visitPatTupleStruct(p)
            }
        }
    patScanner.visitBlock(block)

    val discovered = linkedMapOf<Pair<String, String>, String>()
    val observedCallSites = linkedMapOf<Pair<String, String>, MutableList<Pair<List<Expr>, String?>>>()
    val fnRetRendered = extractReturnSynType(sig.output)?.let { renderType(it, selfTypeName) }
    val fnResultOkPayload = rustResultOkPayloadType(fnRetRendered)

    fun extractWitnessCallKey(funcExpr: Expr): Pair<String, String>? {
        val pathExpr = unwrapParenOrGroupExpr(funcExpr) as? Expr.Path ?: return null
        val qself = pathExpr.qself
        if (qself != null) {
            val baseTy = renderType(qself.ty, selfTypeName)
            val lastRaw =
                pathExpr.path.segments
                    .last()
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
            if (baseTy in fnTypeParams && lastRaw != null && lastRaw.firstOrNull()?.isLowerCase() == true) {
                return baseTy to lastRaw
            }
        } else {
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
        }
        return null
    }

    var usesDefaultForwarder = false
    var usesZeroArgParse = false
    var usesParseForwarder = false
    var usesTokenForwarder = false
    val clonedTypeParams = linkedSetOf<String>()

    val visitor =
        object : Visit() {
            private var pendingCallReturnHint: Pair<Expr.Call, String>? = null

            override fun visitItemFn(i: Item.Fn) {}

            override fun visitStmt(s: Stmt) {
                if (isLeadingCfgStmtDisabled(s)) return
                when (s) {
                    is Stmt.Local -> {
                        val rawLocal = extractRawParamIdent(s.pat)
                        val annTy = extractLocalTypeAnnotation(s.pat, selfTypeName)
                        val initExpr = s.init?.takeIf { it.diverge == null }?.expr
                        if (initExpr != null) {
                            val unwrappedInit = unwrapParenOrGroupExpr(initExpr)
                            if (unwrappedInit is Expr.Call && annTy != null) {
                                pendingCallReturnHint = unwrappedInit to annTy
                            }
                        }
                        super.visitStmt(s)
                        if (rawLocal != null) {
                            val ktLocal = rustValueIdentToKotlin(rawLocal)
                            if (annTy != null) {
                                localRenderedTypes[ktLocal] = annTy
                            } else if (initExpr != null) {
                                val unwrappedInit = unwrapParenOrGroupExpr(initExpr)
                                val wKey = (unwrappedInit as? Expr.Call)?.let { extractWitnessCallKey(it.func) }
                                val zeroArgWitnessRet =
                                    if (wKey != null && unwrappedInit.args.isEmpty()) {
                                        discovered[wKey] ?: wKey.first
                                    } else {
                                        null
                                    }
                                val prevLocals = currentTypedLocals
                                val inferred =
                                    try {
                                        currentTypedLocals = prevLocals + localRenderedTypes
                                        zeroArgWitnessRet
                                            ?: inferExprRenderedType(initExpr, selfTypeName)
                                            ?: inferExprNumericKind(initExpr, selfTypeName)?.let { numericKindToPureTypeName(it) }
                                    } finally {
                                        currentTypedLocals = prevLocals
                                    }
                                if (inferred != null) {
                                    localRenderedTypes[ktLocal] = inferred
                                }
                            }
                        }
                        return
                    }
                    is Stmt.ExprStmt -> {
                        val unwrapped = unwrapParenOrGroupExpr(s.expr)
                        if (s.semiToken != null && unwrapped is Expr.Call) {
                            pendingCallReturnHint = unwrapped to "Unit"
                        } else if (s.semiToken == null && unwrapped is Expr.Call && fnRetRendered != null) {
                            pendingCallReturnHint = unwrapped to fnRetRendered
                        }
                    }
                    else -> {}
                }
                super.visitStmt(s)
            }

            override fun visitExprReturn(e: Expr.Return) {
                val retExpr = e.expr?.let { unwrapParenOrGroupExpr(it) }
                if (retExpr is Expr.Call && fnRetRendered != null) {
                    pendingCallReturnHint = retExpr to fnRetRendered
                }
                super.visitExprReturn(e)
            }

            override fun visitExprPath(e: Expr.Path) {
                val qself = e.qself
                if (qself != null) {
                    val baseTy = renderType(qself.ty, selfTypeName)
                    val lastSeg = e.path.segments.last()
                    val lastIdent = lastSeg?.ident?.toString()
                    val lastRaw = lastIdent?.removePrefix("r#")
                    if (baseTy in fnTypeParams && lastRaw != null && lastRaw.firstOrNull()?.isLowerCase() == true) {
                        discovered.getOrPut(baseTy to lastRaw) { baseTy }
                    }
                } else {
                    val segs = e.path.segments.toList()
                    if (segs.size == 2) {
                        val rawFirst = segs[0].ident.toString()
                        val firstKt = rustTypeIdentToKotlin(rawFirst)
                        val secondRaw = segs[1].ident.toString().removePrefix("r#")
                        if (rawFirst.firstOrNull()?.isUpperCase() == true &&
                            firstKt in fnTypeParams &&
                            secondRaw.firstOrNull()?.isLowerCase() == true
                        ) {
                            discovered.getOrPut(firstKt to secondRaw) { firstKt }
                        } else if (rawFirst == "Default" && secondRaw == "default") {
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
                }
                super.visitExprPath(e)
            }

            override fun visitExprCall(e: Expr.Call) {
                val fnPath = (e.func as? Expr.Path)?.takeIf { it.qself == null }?.path
                val lastCallSeg = fnPath?.segments?.last()
                val lastCallIdent = lastCallSeg?.ident?.toString()
                when (lastCallIdent) {
                    "parse_terminated", "parse_terminated_with" -> usesParseForwarder = true
                    "parse_separated_nonempty", "parse_separated_nonempty_with" -> {
                        usesParseForwarder = true
                        usesTokenForwarder = true
                    }
                    "Ok" -> {
                        val innerArg =
                            e.args
                                .toList()
                                .singleOrNull()
                                ?.let { unwrapParenOrGroupExpr(it) }
                        if (innerArg is Expr.Call && fnResultOkPayload != null) {
                            pendingCallReturnHint = innerArg to fnResultOkPayload
                        }
                    }
                    "Some" -> {
                        val innerArg =
                            e.args
                                .toList()
                                .singleOrNull()
                                ?.let { unwrapParenOrGroupExpr(it) }
                        val optPayload = fnRetRendered?.takeIf { it.endsWith("?") }?.removeSuffix("?")
                        if (innerArg is Expr.Call && optPayload != null) {
                            pendingCallReturnHint = innerArg to optPayload
                        }
                    }
                }
                val witnessKey = extractWitnessCallKey(e.func)
                if (witnessKey != null) {
                    val hint = pendingCallReturnHint?.takeIf { it.first === e }?.second
                    if (pendingCallReturnHint?.first === e) {
                        pendingCallReturnHint = null
                    }
                    val callArgs = e.args.toList().filterNot { it is Expr.Tuple && it.elems.isEmpty() }
                    observedCallSites.getOrPut(witnessKey) { mutableListOf() }.add(callArgs to hint)
                }
                super.visitExprCall(e)
            }

            override fun visitExprMethodCall(e: Expr.MethodCall) {
                val m = e.method.toString()
                val argCount = e.args.toList().count { !(it is Expr.Tuple && it.elems.isEmpty()) }
                if (m == "parse" && argCount == 0 && e.turbofish == null) {
                    usesZeroArgParse = true
                } else if ((m == "push" || m == "insert" || m == "extend") && argCount >= 1) {
                    usesDefaultForwarder = true
                } else if (m == "clone" && argCount == 0 && e.turbofish == null) {
                    val recvCore = unwrapTransparentCastExpr(e.receiver)
                    val recvRaw =
                        if (recvCore is Expr.Path && recvCore.qself == null && recvCore.path.segments.len() == 1) {
                            val firstRecvSeg = recvCore.path.segments.first()
                            firstRecvSeg?.ident?.toString()?.removePrefix("r#")
                        } else {
                            null
                        }
                    val tp = recvRaw?.let { localRawTypes[it] }
                    if (tp != null && "Clone" in boundsByTp[tp].orEmpty()) {
                        clonedTypeParams += tp
                    }
                }
                super.visitExprMethodCall(e)
            }
        }
    visitor.visitBlock(block)

    if (usesDefaultForwarder) {
        for (tp in defaultBoundedParams) {
            if (discovered.none { it.value == tp && it.key.second == "default" }) {
                discovered[tp to "default"] = tp
            }
        }
    }
    if (usesZeroArgParse || usesParseForwarder) {
        for (tp in orderedTypeParams) {
            val tpBounds = boundsByTp[tp].orEmpty()
            if (("Parse" in tpBounds || "ParseQuote" in tpBounds) &&
                discovered.none { it.value == tp && it.key.second == "parse" }
            ) {
                discovered[tp to "parse"] = tp
            }
        }
    }
    if (usesTokenForwarder) {
        for (tp in orderedTypeParams) {
            if ("Token" in boundsByTp[tp].orEmpty() &&
                discovered.none { it.value == tp && it.key.second == "peek" }
            ) {
                discovered[tp to "peek"] = tp
            }
        }
    }
    for (tp in orderedTypeParams) {
        if (tp in clonedTypeParams && discovered.none { it.value == tp && it.key.second == "clone" }) {
            discovered[tp to "clone"] = tp
        }
    }

    val methodOrder = listOf("peek", "parse", "default", "clone", "from")
    val sortedEntries =
        discovered.entries.sortedWith(
            compareBy<Map.Entry<Pair<String, String>, String>> { orderedTypeParams.indexOf(it.value).let { i -> if (i < 0) 999 else i } }
                .thenBy { methodOrder.indexOf(it.key.second).let { i -> if (i < 0) 999 else i } },
        )

    currentTypeParamTraitBounds = boundsByTp.mapValues { it.value.toSet() }
    for ((keyPair, actualTp) in sortedEntries) {
        val rawMethod = keyPair.second
        val tpBounds = boundsByTp[actualTp].orEmpty()
        when (rawMethod) {
            "parse" -> {
                for (tr in listOf("Parse", "ParseQuote")) {
                    if (tr in tpBounds) {
                        suppressedBoundsOut.getOrPut(actualTp) { linkedSetOf() }.add(tr)
                        witnessedSuppressedTraitNames += tr
                    }
                }
            }
            "peek" -> {
                if ("Token" in tpBounds) {
                    suppressedBoundsOut.getOrPut(actualTp) { linkedSetOf() }.add("Token")
                    witnessedSuppressedTraitNames += "Token"
                }
            }
        }
    }

    fun expandTraitDeclsWithSupertraits(seedTraits: Set<String>): List<Item.Trait> {
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque(seedTraits)
        val out = mutableListOf<Item.Trait>()
        while (queue.isNotEmpty()) {
            val trName = queue.removeFirst()
            if (!visited.add(trName)) continue
            val decl = lookupDeclaredTraitAst(trName) ?: continue
            out += decl
            for (sup in decl.supertraits.toList()) {
                if (sup is TypeParamBound.Trait && sup.maybe == null) {
                    val last =
                        sup.path.segments
                            .last()
                            ?.ident
                            ?.toString() ?: continue
                    queue.addLast(rustTypeIdentToKotlin(last))
                }
            }
        }
        return out
    }

    fun inferCallSiteWitnessFnType(keyPair: Pair<String, String>, actualTp: String): String? {
        val sites = observedCallSites[keyPair].orEmpty()
        if (sites.isEmpty()) return null
        val arity = sites.first().first.size
        val prevLocals = currentTypedLocals
        return try {
            currentTypedLocals = prevLocals + localRenderedTypes
            val paramTypes =
                (0 until arity).map { argIdx ->
                    sites.firstNotNullOfOrNull { (args, _) ->
                        val argExpr = args.getOrNull(argIdx) ?: return@firstNotNullOfOrNull null
                        val unwrappedArg = unwrapParenOrGroupExpr(argExpr)
                        if (unwrappedArg is Expr.Reference && unwrappedArg.mutability != null && !headerContext.rawAbiMode) {
                            val innerTy =
                                inferExprRenderedType(unwrappedArg.expr, selfTypeName)
                                    ?: inferExprNumericKind(unwrappedArg.expr, selfTypeName)?.let { numericKindToPureTypeName(it) }
                            if (innerTy != null &&
                                innerTy in
                                setOf(
                                    "Byte",
                                    "UByte",
                                    "Short",
                                    "UShort",
                                    "Int",
                                    "UInt",
                                    "Long",
                                    "ULong",
                                    "I128",
                                    "U128",
                                    "Float",
                                    "kotlin.Float",
                                    "Double",
                                    "Char",
                                    "RustChar",
                                    "ConstPtr",
                                    "MutPtr",
                                )
                            ) {
                                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                                CoreIntrinsicLowering.registerCoreImport(this@collectTypeParamWitnessSpecs, "$coreNs.mem.RustMutableReference")
                                return@firstNotNullOfOrNull "RustMutableReference<$innerTy>"
                            }
                        }
                        inferExprRenderedType(argExpr, selfTypeName)
                            ?: inferExprNumericKind(argExpr, selfTypeName)?.let { numericKindToPureTypeName(it) }
                    } ?: "Any?"
                }
            val retType =
                sites.firstNotNullOfOrNull { (_, hint) -> hint }
                    ?: if (arity == 0) actualTp else "Any?"
            "(${paramTypes.joinToString(", ")}) -> $retType"
        } finally {
            currentTypedLocals = prevLocals
        }
    }

    val zeroArgSelfProducerMethods =
        setOf(
            "zero",
            "one",
            "min_value",
            "max_value",
            "nan",
            "infinity",
            "neg_infinity",
            "neg_zero",
            "epsilon",
            "min_positive_value",
            "max_finite",
            "min_finite",
        )

    return sortedEntries.map { (keyPair, actualTp) ->
        val rawMethod = keyPair.second
        val ktMethod = rustValueIdentToKotlin(rawMethod)
        val paramName = "${ktMethod}${actualTp}Fn"
        val sitesForKey = observedCallSites[keyPair].orEmpty()
        val fnType =
            when {
                rawMethod == "parse" -> "(ParseStream) -> Result<$actualTp>"
                rawMethod == "default" -> "() -> $actualTp"
                rawMethod == "peek" -> "(Cursor) -> Boolean"
                rawMethod == "clone" -> "($actualTp) -> $actualTp"
                rawMethod == "from" && sitesForKey.none { it.first.size != 1 } -> {
                    val singleArgTy =
                        inferCallSiteWitnessFnType(keyPair, actualTp)
                            ?.substringAfter('(')
                            ?.substringBeforeLast(") ->")
                            ?.takeIf { it.isNotBlank() && it != "Any?" }
                            ?: "Any?"
                    "($singleArgTy) -> $actualTp"
                }
                rawMethod in zeroArgSelfProducerMethods && sitesForKey.all { it.first.isEmpty() } -> "() -> $actualTp"
                else -> {
                    val tpBounds = boundsByTp[actualTp].orEmpty()
                    val resolvedFromTrait =
                        expandTraitDeclsWithSupertraits(tpBounds).firstNotNullOfOrNull { traitDecl ->
                            val matchedFn =
                                traitDecl.items.firstOrNull { tItem ->
                                    tItem is TraitItem.Fn &&
                                        tItem.sig.ident
                                            .toString()
                                            .removePrefix("r#") == rawMethod
                                } as? TraitItem.Fn ?: return@firstNotNullOfOrNull null
                            val paramTys =
                                matchedFn.sig.inputs.toList().map { inp ->
                                    when (inp) {
                                        is FnArg.Receiver -> actualTp
                                        is FnArg.Typed -> renderType(inp.patType.ty, selfTypeName = actualTp)
                                    }
                                }
                            val retTy = extractReturnSynType(matchedFn.sig.output)?.let { renderType(it, selfTypeName = actualTp) } ?: "Unit"
                            "(${paramTys.joinToString(", ")}) -> $retTy"
                        }
                    resolvedFromTrait
                        ?: inferCallSiteWitnessFnType(keyPair, actualTp)
                        ?: "(Any?) -> Any?"
                }
            }
        val defaultVal = if (rawMethod == "clone") "{ it }" else null
        TypeParamWitnessSpec(
            keyTp = keyPair.first,
            actualTp = actualTp,
            rawMethodName = rawMethod,
            paramName = paramName,
            kotlinFnType = fnType,
            defaultExpr = defaultVal,
        )
    }
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
            val argsDesc =
                (lastSeg.arguments as? PathArguments.AngleBracketed)
                    ?.args
                    ?.toList()
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
    val typeArgs =
        (lastSeg.arguments as? PathArguments.AngleBracketed)
            ?.args
            ?.toList()
            ?.mapNotNull { (it as? GenericArgument.TypeArg)?.type }
            .orEmpty()
    if (typeArgs.isNotEmpty()) {
        val suffix = typeArgs.joinToString("") { describeSynTypeForDisambiguation(it) }
        if (suffix.isNotEmpty()) return "$fnName$suffix"
    }
    val traitKt = rustTypeIdentToKotlin(lastSeg.ident.toString())
    return if (traitKt.isNotEmpty() && !traitKt.equals(fnName, ignoreCase = true)) {
        "$fnName$traitKt"
    } else {
        fnName
    }
}

internal fun KotlinJ2KEmitter.disambiguateReceiverlessTraitImplFnName(
    traitPath: Path,
    fnName: String,
    selfSynType: SynType?,
): String {
    val lastSeg = traitPath.segments.last()
    val rawTrait = lastSeg?.ident?.toString()
    val traitName = rawTrait?.let(::rustTypeIdentToKotlin).orEmpty()
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
    val traitArgSuffix =
        (lastSeg?.arguments as? PathArguments.AngleBracketed)
            ?.args
            ?.toList()
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
    var found = false
    val cleanTarget = rawMethodName.removePrefix("r#")
    val visitor =
        object : Visit() {
            override fun visitExprMethodCall(e: Expr.MethodCall) {
                if (e.method.toString().removePrefix("r#") == cleanTarget) {
                    val recv = unwrapParenOrGroupExpr(e.receiver)
                    val nonUnitCount = e.args.toList().count { !(it is Expr.Tuple && it.elems.isEmpty()) }
                    if (recv is Expr.Path && recv.qself == null && recv.path.isIdent("self") && nonUnitCount == expectedArgCount) {
                        found = true
                    }
                }
                super.visitExprMethodCall(e)
            }
        }
    visitor.visitBlock(block)
    return found
}

private fun visitMatchesRawIdent(
    rawIdent: String,
    drive: (Visit) -> Unit,
): Boolean {
    var found = false
    val visitor =
        object : Visit() {
            override fun visitTypePath(t: SynType.Path) {
                if (t.path.segments
                        .toList()
                        .any { it.ident.toString().removePrefix("r#") == rawIdent }
                ) {
                    found = true
                    return
                }
                super.visitTypePath(t)
            }

            override fun visitExprPath(e: Expr.Path) {
                if (e.path.segments
                        .toList()
                        .any { it.ident.toString().removePrefix("r#") == rawIdent }
                ) {
                    found = true
                    return
                }
                super.visitExprPath(e)
            }
        }
    drive(visitor)
    return found
}

private fun synTypeMentionsIdent(
    ty: SynType?,
    rawIdent: String,
): Boolean = ty != null && visitMatchesRawIdent(rawIdent) { it.visitType(ty) }

private fun genericsMentionIdent(
    generics: Generics?,
    rawIdent: String,
): Boolean {
    if (generics == null) return false
    for (gp in generics.params.toList()) {
        if (gp is GenericParam.TypeParam) {
            if (visitMatchesRawIdent(rawIdent) { v -> gp.bounds.toList().forEach(v::visitTypeParamBound) }) {
                return true
            }
            if (synTypeMentionsIdent(gp.default?.type, rawIdent)) return true
        }
    }
    for (pred in generics.whereClause
        ?.predicates
        ?.toList()
        .orEmpty()) {
        if (pred is WherePredicate.TypePredicate) {
            if (synTypeMentionsIdent(pred.boundedTy, rawIdent)) return true
            if (visitMatchesRawIdent(rawIdent) { v -> pred.bounds.toList().forEach(v::visitTypeParamBound) }) {
                return true
            }
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

    val ownerTypeParams =
        ownerGenerics
            ?.params
            ?.toList()
            ?.mapNotNull { p ->
                (p as? GenericParam.TypeParam)?.ident?.toString()?.let { rustTypeIdentToKotlin(it) }
            }?.toSet()
            .orEmpty()

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
    if (suppressedWitnessBounds.isNotEmpty()) {
        currentWitnessedTraitBounds = suppressedWitnessBounds
    }
    if (witnessSpecs.isNotEmpty()) {
        currentTypeParamWitnessCalls =
            buildMap {
                for (ws in witnessSpecs) {
                    put(ws.keyTp to ws.rawMethodName, ws.paramName)
                    put(ws.actualTp to ws.rawMethodName, ws.paramName)
                }
            }
    }

    val excludedParams =
        (if (!includeOwner) ownerTypeParams else emptySet()) + substitutedTypeParamTypes.keys
    val liftedAssocs =
        liftDefaultlessAssocTypesFromBounds(
            combinedParams = combinedParams,
            ownerWhere = if (includeOwner) ownerGenerics?.whereClause else null,
            fnWhere = sig.generics.whereClause,
            implWhere = if (includeOwner && implGenerics !== ownerGenerics) implGenerics?.whereClause else null,
            ownerTypeSimpleName = selfTypeName?.substringBefore('<')?.substringAfterLast('.')?.trim(),
        )
    if (liftedAssocs.isNotEmpty()) {
        activeTypeParamNames = activeTypeParamNames + liftedAssocs
        currentTraitAssocParamNames = currentTraitAssocParamNames + liftedAssocs
    }
    val baseTypeParamsRendered =
        renderTypeParametersFromParams(
            combinedParams,
            selfTypeName,
            excludeNames = excludedParams,
        ).removePrefix("<").removeSuffix(">")
    val fullTypeParamParts =
        buildList {
            if (liftedAssocs.isNotEmpty()) addAll(liftedAssocs)
            if (baseTypeParamsRendered.isNotEmpty()) add(baseTypeParamsRendered)
        }
    val typeParamsStr =
        if (fullTypeParamParts.isEmpty()) {
            ""
        } else {
            "<${fullTypeParamParts.joinToString(", ")}>"
        }

    val intoIterBoundedTypeParams = linkedSetOf<String>()
    fun hasIntoIterTraitBound(bounds: List<TypeParamBound>): Boolean =
        bounds.any { b ->
            val lastSeg = (b as? TypeParamBound.Trait)?.path?.segments?.last()
            lastSeg?.ident?.toString() == "IntoIterator"
        }
    for (gp in combinedParams) {
        if (gp is GenericParam.TypeParam) {
            val tpKt = rustTypeIdentToKotlin(gp.ident.toString())
            if (hasIntoIterTraitBound(gp.bounds.toList())) {
                intoIterBoundedTypeParams += tpKt
            }
        }
    }
    val combinedWherePreds =
        listOfNotNull(ownerGenerics?.whereClause, implGenerics?.whereClause, sig.generics.whereClause)
            .flatMap { it.predicates.toList() }
    for (pred in combinedWherePreds) {
        if (pred is WherePredicate.TypePredicate) {
            val tpKt = renderType(pred.boundedTy, selfTypeName)
            if (hasIntoIterTraitBound(pred.bounds.toList())) {
                intoIterBoundedTypeParams += tpKt
            }
        }
    }

    val isExternCallForwarding =
        isSingleCallExternForwardingWrapper(sig, fnBlock)
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
                        val assignCount = if (isMutablePattern(arg.patType.pat)) AssignCount.Any else AssignCount.AtMostOnce
                        val baseParamKt =
                            rustValueIdentToKotlin(rawParam).let {
                                if ((headerContext.rawAbiMode || headerContext.finalClasses) && it == "errno") "errnum" else it
                            }
                        scopeBuilder
                            .bindLocal(
                                rawName = rawParam,
                                baseKotlinName = baseParamKt,
                                span = arg.patType.pat.span(),
                                assignCount = assignCount,
                            ).kotlinName
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
                    val origTpKt =
                        rustTypeIdentToKotlin(
                            rawArgPath.path.segments
                                .first()!!
                                .ident
                                .toString(),
                        )
                    if (origTpKt in currentIntoSubstitutedTypeParams) {
                        currentIntoSubstitutedParams = currentIntoSubstitutedParams + pName
                    }
                }
                if (pType in intoIterBoundedTypeParams || pType.startsWith("Iterable<")) {
                    currentIterableLocals = currentIterableLocals + pName
                }
                if (pType.endsWith("?")) {
                    currentNullableLocals = currentNullableLocals + pName
                }
                if (pType.startsWith("MutableList<")) {
                    currentMutableListLocals = currentMutableListLocals + pName
                }
                paramTypeSignatureKeys += eraseTypeForSignatureKey(pType)
                paramIdx++
            }
        }
    }

    val defaultLayoutParamNames = linkedSetOf<String>()
    if (!hasReceiver) {
        transparentConstructorField(selfTypeName, fnBlock)?.let { (_, fieldType) ->
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.TypeLayout")
            val layoutType = "TypeLayout<$fieldType>"
            params += "fieldLayout: $layoutType"
            defaultLayoutParamNames += "fieldLayout"
            paramTypeSignatureKeys += eraseTypeForSignatureKey(layoutType)
            currentTypedLocals = currentTypedLocals + ("fieldLayout" to layoutType)
        }
        if (isCompanionMember &&
            selfTypeName?.startsWith("MaybeUninit<") == true &&
            selfTypeName.endsWith(">") &&
            rawFnName in setOf("new", "uninit", "zeroed") &&
            params.none { it.startsWith("typeLayoutT:") }
        ) {
            val muElem = selfTypeName.substringAfter('<').removeSuffix(">").trim()
            val hasMuTypeParam =
                combinedParams.any {
                    (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) == muElem
                }
            if (muElem.isNotEmpty() && hasMuTypeParam) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.TypeLayout")
                val layoutType = "TypeLayout<$muElem>"
                params += "typeLayoutT: $layoutType"
                defaultLayoutParamNames += "typeLayoutT"
                paramTypeSignatureKeys += eraseTypeForSignatureKey(layoutType)
                currentTypedLocals = currentTypedLocals + ("typeLayoutT" to layoutType)
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
                val defaultSuffix =
                    if (gp.default?.expr != null) {
                        " = ${renderExpr(gp.default!!.expr, "", selfTypeName)}"
                    } else if ("override" !in openPrefix &&
                        "actual" !in openPrefix &&
                        "expect" !in visPrefix &&
                        (
                            inputs.any { it is FnArg.Typed && synTypeMentionsIdent(it.patType.ty, rawConst) } ||
                                synTypeMentionsIdent(rawReturnSynType, rawConst) ||
                                synTypeMentionsIdent(currentStandaloneImplSelfSynType, rawConst) ||
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
        if (params.none { it.startsWith("$layoutName:") }) {
            params += "$layoutName: $layoutType"
            defaultLayoutParamNames += layoutName
            paramTypeSignatureKeys += eraseTypeForSignatureKey(layoutType)
            currentTypedLocals = currentTypedLocals + (layoutName to layoutType)
        }
    }

    val baseParamTypeSignatureKeys = paramTypeSignatureKeys.toList()
    for (ws in witnessSpecs) {
        if (params.none { it.startsWith("${ws.paramName}:") }) {
            val defaultSuffix = ws.defaultExpr?.let { " = $it" }.orEmpty()
            params += "${ws.paramName}: ${ws.kotlinFnType}$defaultSuffix"
            paramTypeSignatureKeys += eraseTypeForSignatureKey(ws.kotlinFnType)
        }
    }
    scopeBuilder.scopeData.mutScope(scopeBuilder.topScopeId()).setParamCount(params.size)

    var effectiveFnName = fnName
    val standaloneTraitPath = currentStandaloneImplTraitPath
    val standaloneSelfSynType = currentStandaloneImplSelfSynType
    val attachedTraitPath = currentAttachedImplTraitPath
    val isArraySelf = standaloneSelfSynType?.let(::unwrapSynType) is SynType.Array
    val receiverPrefix =
        if (asTopLevelExtension && !selfTypeName.isNullOrBlank()) {
            if (hasReceiver) {
                if (standaloneTraitPath != null && isArraySelf) {
                    effectiveFnName =
                        disambiguateReceiverTraitImplFnName(
                            standaloneTraitPath,
                            fnName,
                            standaloneSelfSynType,
                        )
                } else if (standaloneTraitPath != null) {
                    val traitKt =
                        standaloneTraitPath.segments
                            .last()
                            ?.ident
                            ?.toString()
                            ?.let(::rustTypeIdentToKotlin)
                    val selfDesc = describeSynTypeForDisambiguation(standaloneSelfSynType)
                    val selfBase = selfTypeName.substringBefore('<').trim()
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
                val baseType = selfTypeName.substringBefore('<').trim()
                val rawStandaloneTrait =
                    standaloneTraitPath.segments
                        .toList()
                        .lastOrNull()
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                if (baseType in COMPANION_CAPABLE_VALUE_TYPES &&
                    rawStandaloneTrait in setOf("From", "TryFrom", "Step") &&
                    inputs.isNotEmpty()
                ) {
                    "$baseType.Companion."
                } else {
                    effectiveFnName =
                        disambiguateReceiverlessTraitImplFnName(
                            standaloneTraitPath,
                            fnName,
                            standaloneSelfSynType,
                        )
                    ""
                }
            } else {
                val baseType = selfTypeName.substringBefore('<').trim()
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
            val disambiguated = disambiguateTraitArgCollisionFnName(effectiveFnName, attachedTraitPath)
            if (disambiguated != effectiveFnName) {
                effectiveFnName = disambiguated
            }
        }
    }
    var sigKey = "$receiverKey$effectiveFnName(${paramTypeSignatureKeys.joinToString(",")})"
    var seenCount = if (sigKey == baseSigKey) baseSeenCount else (scopeSignatures[sigKey] ?: 0)
    if (seenCount > 0 && asTopLevelExtension && hasReceiver && standaloneTraitPath != null && !isErasedRefOpsForwarding) {
        val disambiguated =
            disambiguateReceiverTraitImplFnName(
                standaloneTraitPath,
                effectiveFnName,
                standaloneSelfSynType,
            )
        if (disambiguated != effectiveFnName) {
            effectiveFnName = disambiguated
            sigKey = "$receiverKey$effectiveFnName(${paramTypeSignatureKeys.joinToString(",")})"
            seenCount = scopeSignatures[sigKey] ?: 0
        }
    } else if (seenCount > 0 &&
        !asTopLevelExtension &&
        attachedTraitPath != null &&
        !currentAttachedImplIsRefSelf &&
        !isErasedRefOpsForwarding
    ) {
        val disambiguated = disambiguateTraitArgCollisionFnName(effectiveFnName, attachedTraitPath)
        if (disambiguated != effectiveFnName) {
            effectiveFnName = disambiguated
            sigKey = "$receiverKey$effectiveFnName(${paramTypeSignatureKeys.joinToString(",")})"
            seenCount = scopeSignatures[sigKey] ?: 0
        }
    }
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) {
        return null
    }

    val retType = extractReturnSynType(sig.output)
    val prevExternRet = inExternAbiSignature
    if (isExternCallForwarding) {
        inExternAbiSignature = true
    }
    val implTraitPathForSig = attachedTraitPath ?: standaloneTraitPath
    val isFmtResultRet = isFmtResultSynType(retType)
    val implTraitResultOverride =
        if (isFmtResultRet && implTraitPathForSig != null) {
            val traitKt =
                implTraitPathForSig.segments
                    .last()
                    ?.ident
                    ?.toString()
                    ?.let(::rustTypeIdentToKotlin)
            val ktFn = rustFunctionIdentToKotlin(rawFnName)
            traitKt?.let { tk ->
                if (ktFn in lookupTraitResultMethodNames(tk, implTraitPathForSig)) {
                    val okSyn = if (ktFn == "finish") currentImplAssocTypes["Output"] else null
                    val errSyn = currentImplAssocTypes["Err"] ?: currentImplAssocTypes["Error"]
                    val okTy = okSyn?.let { renderType(it, selfTypeName) } ?: "Unit"
                    val errTy = errSyn?.let { renderType(it, selfTypeName) } ?: "Unit"
                    if (usesTaggedCoreResult()) "${sourceResultName()}<$okTy, $errTy>" else "Result<$okTy>"
                } else {
                    null
                }
            }
        } else {
            null
        }
    val renderedReturnType =
        try {
            selfTypeName?.substringBefore('<')?.let { owner ->
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
            val combinedPreds = ownerPreds + implPreds
            if (combinedPreds.isEmpty()) {
                null
            } else {
                ownerWhereClause?.takeIf { implPreds.isEmpty() } ?: implWhereClause
            }
        } else {
            null
        }
    val liftedAssocNames =
        liftedAssocs
            .map { it.substringBefore(':').trim() }
            .filter(String::isNotEmpty)
            .toSet()
    val whereClause =
        renderCombinedWhereClause(
            ownerWhere = effectiveOwnerWhere,
            fnWhere = sig.generics.whereClause,
            params = combinedParams,
            selfTypeName = selfTypeName,
            excludeNames = excludedParams,
            extraDeclaredNames = liftedAssocNames,
        )
    val attachedTraitBase =
        currentAttachedImplTraitPath
            ?.segments
            ?.last()
            ?.ident
            ?.toString()
            ?.removePrefix("r#")
            ?.let(::rustTypeIdentToKotlin)
    val externalOverridableStdlibTraits =
        setOf(
            "Read",
            "Write",
            "BufRead",
            "Seek",
            "Clone",
            "Iterator",
            "DoubleEndedIterator",
            "ExactSizeIterator",
            "BitOr",
            "BitAnd",
            "BitXor",
            "Not",
            "Add",
            "Sub",
            "Mul",
            "Div",
            "Rem",
            "Neg",
            "Shl",
            "Shr",
            "PanicPayload",
        )
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
                            attachedTraitBase in externalOverridableStdlibTraits
                    ) ||
                    (effectiveFnName == "toString" && params.isEmpty()) ||
                    (effectiveFnName == "hashCode" && params.isEmpty()) ||
                    (effectiveFnName == "equals" && params.size == 1 && params[0].endsWith(": Any?"))
            )
    val effectiveOpenPrefix = if (isKotlinAnyOverride) "override " else openPrefix
    val fnOnlyTypeParamNames =
        sig.generics.params
            .toList()
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
            fnOnlyTypeParamNames
                .filter { tpKt ->
                    (renderedReturnType == tpKt || renderedReturnType == "$tpKt?") &&
                        params.none { Regex("\\b${Regex.escape(tpKt)}\\b").containsMatchIn(it.substringAfter(": ")) }
                }.toSet()
        } else {
            emptySet()
        }
    val effectiveTypeParamsStr =
        if (reifiedFnTypeParams.isEmpty() || typeParamsStr.isEmpty()) {
            typeParamsStr
        } else {
            reifiedFnTypeParams.fold(typeParamsStr) { acc, tpKt ->
                prefixTopLevelTypeParamWithReified(acc, tpKt)
            }
        }
    val inlinePrefix = if (reifiedFnTypeParams.isNotEmpty()) "inline " else ""
    val selfBaseForOp = selfTypeName?.substringBefore('<')?.substringAfterLast('.')?.trim()
    val kotlinBuiltinArithTypes =
        setOf("Byte", "Short", "Int", "Long", "UByte", "UShort", "UInt", "ULong", "Float", "Double")
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
                            !(selfBaseForOp in kotlinBuiltinArithTypes && baseParamTypeSignatureKeys.singleOrNull() in kotlinBuiltinArithTypes)
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
                if (pName in defaultLayoutParamNames && !param.contains('=')) {
                    "$param = UnresolvedTypeLayout()"
                } else {
                    param
                }
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

private val CORE_OPS_FORWARD_REF_TRAITS =
    setOf(
        "Add",
        "Sub",
        "Mul",
        "Div",
        "Rem",
        "BitAnd",
        "BitOr",
        "BitXor",
        "Shl",
        "Shr",
        "AddAssign",
        "SubAssign",
        "MulAssign",
        "DivAssign",
        "RemAssign",
        "BitAndAssign",
        "BitOrAssign",
        "BitXorAssign",
        "ShlAssign",
        "ShrAssign",
        "Neg",
        "Not",
    )

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
                if (tok.rawText == targetParam) {
                    out.append("reified ").append(tok.rawText)
                } else {
                    out.append(tok.rawText)
                }
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
    while (coreExpr is Expr.Cast) {
        coreExpr = unwrapTransparentCastExpr(coreExpr.expr)
    }
    if (coreExpr !is Expr.Call) return false
    if (sig.abi == null) {
        val calleePath = (unwrapParenOrGroupExpr(coreExpr.func) as? Expr.Path)?.takeIf { it.qself == null } ?: return false
        val segNames =
            calleePath.path.segments
                .toList()
                .mapNotNull { seg ->
                    seg.ident
                        .toString()
                        .removePrefix("r#")
                        .takeIf(String::isNotEmpty)
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
    val paramRawNames =
        sig.inputs
            .mapNotNull { (it as? FnArg.Typed)?.let { t -> extractRawParamIdent(t.patType.pat) } }
            .filter { it != "_" }
            .toSet()
    if (paramRawNames.isEmpty()) return true
    var derefsParam = false
    val visitor =
        object : Visit() {
            override fun visitExprUnary(e: Expr.Unary) {
                if (e.op is UnOp.Deref) {
                    val inner = unwrapParenOrGroupExpr(e.expr)
                    if (inner is Expr.Path && inner.qself == null && inner.path.segments.size == 1) {
                        val raw =
                            inner.path.segments
                                .first()
                                ?.ident
                                ?.toString()
                                ?.removePrefix("r#")
                        if (raw in paramRawNames) {
                            derefsParam = true
                            return
                        }
                    }
                }
                super.visitExprUnary(e)
            }
        }
    visitor.visitBlock(block)
    return !derefsParam
}

private fun KotlinJ2KEmitter.appendDestructuredParamLines(
    pat: Pat,
    paramKt: String,
    innerIndent: String,
    selfTypeName: String?,
    block: Block,
    out: MutableList<String>,
) {
    val unwrapped = unwrapTransparentPat(pat)
    when (unwrapped) {
        is Pat.Wild, is Pat.Rest -> return
        is Pat.Ident -> {
            val raw = unwrapped.ident.toString().removePrefix("r#")
            if (raw == "_") return
            val assignCount = if (isMutablePattern(pat) || blockReassignsIdent(block, raw)) AssignCount.Any else AssignCount.AtMostOnce
            val ktName =
                scopeBuilder
                    .bindLocal(
                        rawName = raw,
                        baseKotlinName = rustValueIdentToKotlin(raw),
                        span = unwrapped.ident.span(),
                        assignCount = assignCount,
                    ).kotlinName
            currentTypedLocals[paramKt]?.let { ty ->
                currentTypedLocals = currentTypedLocals + (ktName to ty)
                if (ty.endsWith("?")) {
                    currentNullableLocals = currentNullableLocals + ktName
                }
            }
            val kw = if (assignCount == AssignCount.Any) "var" else "val"
            out += "$innerIndent$kw $ktName = $paramKt"
        }
        is Pat.Tuple -> {
            val elems = unwrapped.elems.toList()
            if (elems.size == 1) {
                appendDestructuredParamLines(elems[0], paramKt, innerIndent, selfTypeName, block, out)
                return
            }
            val (patText, _) = renderLocalPattern(unwrapped, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
            if (patText.isNotEmpty() && patText != "unused") {
                val kw = if (isMutablePattern(unwrapped)) "var" else "val"
                out += "$innerIndent$kw $patText = $paramKt"
            }
        }
        is Pat.Struct -> {
            renderStructDestructuringLocal(unwrapped, paramKt, pat.span(), innerIndent, selfTypeName)
                .lines()
                .filter { it.isNotBlank() }
                .forEach { out += it }
        }
        is Pat.TupleStruct -> {
            val rendered =
                renderTupleStructDestructuringLocal(unwrapped, initExpr = null, rhs = paramKt, span = pat.span(), indent = innerIndent, selfTypeName = selfTypeName)
            if (rendered != null) {
                rendered.lines().filter { it.isNotBlank() }.forEach { out += it }
            } else {
                val (patText, _) = renderLocalPattern(unwrapped, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
                if (patText.isNotEmpty() && patText != "unused") {
                    val kw = if (isMutablePattern(unwrapped)) "var" else "val"
                    out += "$innerIndent$kw $patText = $paramKt"
                }
            }
        }
        else -> {
            val (patText, _) = renderLocalPattern(unwrapped, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
            if (patText.isNotEmpty() && patText != "unused") {
                val kw = if (isMutablePattern(unwrapped)) "var" else "val"
                out += "$innerIndent$kw $patText = $paramKt"
            }
        }
    }
}

internal abstract class ScopedMutationAstScanner(
    protected val emitter: KotlinJ2KEmitter,
    private val includeCompoundBinaryAssign: Boolean = true,
) : Visit() {
    var mutated: Boolean = false
        protected set

    protected abstract fun matchesLhs(lhs: Expr): Boolean

    final override fun visitItemFn(i: Item.Fn) {
        // Local `fn` items cannot capture or reassign outer bindings in Rust.
    }

    override fun visitExprAssign(e: Expr.Assign) {
        if (mutated) return
        if (matchesLhs(e.left)) {
            mutated = true
            return
        }
        super.visitExprAssign(e)
    }

    override fun visitExprBinary(e: Expr.Binary) {
        if (mutated) return
        if (includeCompoundBinaryAssign && emitter.isCompoundAssignOp(e.op) && matchesLhs(e.left)) {
            mutated = true
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
        if (unwrapped is Expr.Unary && unwrapped.op is UnOp.Deref) {
            val inner = emitter.unwrapParenOrGroupExpr(unwrapped.expr)
            return inner is Expr.Path && inner.qself == null && inner.path.isIdent("self")
        }
        return unwrapped is Expr.Path && unwrapped.qself == null && unwrapped.path.isIdent("self")
    }
}

private class IdentReassignmentScanner(
    emitter: KotlinJ2KEmitter,
    private val targetRawIdent: String,
) : ScopedMutationAstScanner(emitter) {
    override fun matchesLhs(lhs: Expr): Boolean =
        emitter.extractAssignedRootIdent(lhs) == targetRawIdent

    override fun visitExprMatch(e: Expr.Match) {
        if (mutated) return
        val subj = emitter.unwrapParenOrGroupExpr(e.expr)
        if (subj is Expr.Reference &&
            subj.mutability != null &&
            emitter.extractAssignedRootIdent(subj.expr) == targetRawIdent &&
            e.arms.any { emitter.matchArmMutatesTupleVariantBinding(it) }
        ) {
            mutated = true
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
                val shadowSelf =
                    scopeBuilder
                        .bindLocal(
                            rawName = "self",
                            baseKotlinName = "selfMut",
                            span = arg.span(),
                            assignCount = AssignCount.Any,
                        ).kotlinName
                if (selfTypeName != null) {
                    currentTypedLocals = currentTypedLocals + (shadowSelf to selfTypeName)
                }
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
                            scopeBuilder
                                .bindLocal(
                                    rawName = rawParam,
                                    baseKotlinName = ktName,
                                    span = arg.patType.pat.span(),
                                    assignCount = AssignCount.Any,
                                ).kotlinName
                        } else {
                            ktName
                        }
                    if (shadowKt != ktName) {
                        currentTypedLocals[ktName]?.let { currentTypedLocals = currentTypedLocals + (shadowKt to it) }
                        if (ktName in currentNullableLocals) {
                            currentNullableLocals = currentNullableLocals + shadowKt
                        }
                    }
                    shadows += "${innerIndent}var $shadowKt = $ktName"
                }
            } else if (rawParam == null) {
                val paramKt = extractParamName(arg.patType.pat, paramIdx)
                appendDestructuredParamLines(arg.patType.pat, paramKt, innerIndent, selfTypeName, block, shadows)
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
    val rawBindings =
        pat.elems
            .toList()
            .mapNotNull { extractRawParamIdent(it) }
            .filter { it != "_" }
            .toSet()
    if (rawBindings.isEmpty()) return false
    return BindingSetMutationScanner(this, rawBindings).also { it.visitExpr(arm.body) }.mutated
}

internal fun KotlinJ2KEmitter.extractAssignedRootIdent(lhs: Expr): String? =
    when (lhs) {
        is Expr.Path -> {
            if (lhs.qself == null && lhs.path.segments.len() == 1) {
                lhs.path.segments
                    .first()
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
            } else {
                null
            }
        }
        is Expr.Paren -> extractAssignedRootIdent(lhs.expr)
        is Expr.Group -> extractAssignedRootIdent(lhs.expr)
        is Expr.Unary -> if (lhs.op is UnOp.Deref) extractAssignedRootIdent(lhs.expr) else null
        else -> null
    }

internal fun KotlinJ2KEmitter.isCompoundAssignOp(op: BinOp): Boolean =
    when (op) {
        is BinOp.AddAssign,
        is BinOp.SubAssign,
        is BinOp.MulAssign,
        is BinOp.DivAssign,
        is BinOp.RemAssign,
        is BinOp.BitXorAssign,
        is BinOp.BitAndAssign,
        is BinOp.BitOrAssign,
        is BinOp.ShlAssign,
        is BinOp.ShrAssign,
        -> true
        else -> false
    }

internal fun KotlinJ2KEmitter.computeReceiverSignatureKey(receiverPrefix: String): String =
    when {
        receiverPrefix.isEmpty() -> ""
        receiverPrefix.endsWith(".Companion.") ->
            receiverPrefix.removeSuffix(".Companion.").substringBefore('<').trim() + ".Companion."
        else ->
            receiverPrefix.removeSuffix(".").substringBefore('<').trim() + "."
    }

internal fun KotlinJ2KEmitter.eraseTypeForSignatureKey(renderedType: String): String {
    val nonNull = renderedType.removeSuffix("?")
    val base = nonNull.substringBefore('<').trim()
    if (base in activeTypeParamNames) return "*"
    val keepNullability = base in KOTLIN_JVM_VALUE_TYPES
    return if (keepNullability && renderedType.endsWith("?")) "$base?" else base
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
    if (ownerList.isEmpty()) return fnList
    return ownerList + fnList
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

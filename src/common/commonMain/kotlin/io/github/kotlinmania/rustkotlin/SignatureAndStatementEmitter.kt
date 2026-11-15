package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Span
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
import io.github.kotlinmania.syn.Arm
import io.github.kotlinmania.syn.BinOp
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.FieldValue
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.ModContent
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

internal fun KotlinJ2KEmitter.liftDefaultlessAssocTypesFromBounds(
    combinedParams: List<GenericParam>,
    ownerWhere: WhereClause?,
    fnWhere: WhereClause?,
    implWhere: WhereClause? = null,
): List<String> {
    val existingParams =
        combinedParams
            .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
            .toSet()
    val lifted = mutableListOf<String>()
    fun inspectBounds(bounds: List<TypeParamBound>) {
        for (b in bounds) {
            if (b is TypeParamBound.Trait) {
                val lastSeg = b.path.segments.last() ?: continue
                if (lastSeg.arguments is PathArguments.None) {
                    val ktTrait = rustTypeIdentToKotlin(lastSeg.ident.toString())
                    for (assoc in lookupTraitDefaultlessAssocTypes(ktTrait, b.path)) {
                        if (assoc !in existingParams && assoc !in lifted) {
                            lifted += assoc
                        }
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
                        } else if (rawFirst == "Default" && secondRaw == "default" && defaultBoundedParams.size == 1) {
                            discovered.getOrPut("*" to "default") { defaultBoundedParams[0] }
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
        )
    if (liftedAssocs.isNotEmpty()) {
        activeTypeParamNames = activeTypeParamNames + liftedAssocs
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

    if (!hasReceiver) {
        transparentConstructorField(selfTypeName, fnBlock)?.let { (_, fieldType) ->
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.TypeLayout")
            val layoutType = "TypeLayout<$fieldType>"
            params += "fieldLayout: $layoutType"
            paramTypeSignatureKeys += eraseTypeForSignatureKey(layoutType)
            currentTypedLocals = currentTypedLocals + ("fieldLayout" to layoutType)
        }
    }

    for (gp in combinedParams) {
        if (gp is GenericParam.ConstParam) {
            val rawConst = gp.ident.toString().removePrefix("r#")
            val cName = rustValueIdentToKotlin(rawConst)
            val cType = renderType(gp.ty, selfTypeName)
            if (params.none { it.startsWith("$cName:") }) {
                scopeBuilder.bindLocal(rawConst, cName, gp.ident.span())
                currentTypedLocals = currentTypedLocals + (cName to cType)
                val defaultSuffix =
                    gp.default
                        ?.expr
                        ?.let { " = ${renderExpr(it, "", selfTypeName)}" }
                        .orEmpty()
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
                effectiveFnName =
                    disambiguateReceiverlessTraitImplFnName(
                        standaloneTraitPath,
                        fnName,
                        standaloneSelfSynType,
                    )
                ""
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
    if (!asTopLevelExtension && attachedTraitPath != null && !currentAttachedImplIsRefSelf) {
        scopeSignatures[baseSigKey] = baseSeenCount + 1
        if (baseSeenCount > 0) {
            val disambiguated = disambiguateTraitArgCollisionFnName(effectiveFnName, attachedTraitPath)
            if (disambiguated != effectiveFnName) {
                effectiveFnName = disambiguated
            }
        }
    }
    var sigKey = "$receiverKey$effectiveFnName(${paramTypeSignatureKeys.joinToString(",")})"
    var seenCount = if (sigKey == baseSigKey) baseSeenCount else (scopeSignatures[sigKey] ?: 0)
    if (seenCount > 0 && asTopLevelExtension && hasReceiver && standaloneTraitPath != null) {
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
    } else if (seenCount > 0 && !asTopLevelExtension && attachedTraitPath != null && !currentAttachedImplIsRefSelf) {
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
    val isKotlinAnyOverride =
        !asTopLevelExtension &&
            !isCompanionMember &&
            !isInterfaceMethod &&
            !selfTypeName.isNullOrBlank() &&
            (hasReceiver || currentAttachedImplIsObjectOwner) &&
            (
                effectiveFnName in currentStructOverrideMethodNames ||
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
    val operatorPrefix = if (hasReceiver && effectiveFnName == "compareTo" && renderedReturnType == "Int") "operator " else ""
    val typeParamsPart = if (effectiveTypeParamsStr.isNotEmpty()) "$effectiveTypeParamsStr " else ""
    return "${visPrefix}${effectiveOpenPrefix}${operatorPrefix}${inlinePrefix}fun ${typeParamsPart}${receiverPrefix}$effectiveFnName(${params.joinToString(", ")})${retTypeStr}$whereClause"
}

private fun prefixTopLevelTypeParamWithReified(typeParamsStr: String, tpKt: String): String {
    if (typeParamsStr.isEmpty() || tpKt.isEmpty()) return typeParamsStr
    val sb = StringBuilder(typeParamsStr.length + 12)
    var angleDepth = 0
    var atSlotStart = false
    var i = 0
    while (i < typeParamsStr.length) {
        val ch = typeParamsStr[i]
        when {
            ch == '<' -> {
                angleDepth++
                atSlotStart = (angleDepth == 1)
                sb.append(ch)
                i++
            }
            ch == '>' -> {
                angleDepth = (angleDepth - 1).coerceAtLeast(0)
                atSlotStart = false
                sb.append(ch)
                i++
            }
            ch == ',' && angleDepth == 1 -> {
                atSlotStart = true
                sb.append(ch)
                i++
            }
            angleDepth == 1 && atSlotStart && ch.isWhitespace() -> {
                sb.append(ch)
                i++
            }
            angleDepth == 1 && atSlotStart -> {
                val afterIdx = i + tpKt.length
                val matchesParam =
                    typeParamsStr.startsWith(tpKt, i) &&
                        (afterIdx == typeParamsStr.length || (!typeParamsStr[afterIdx].isLetterOrDigit() && typeParamsStr[afterIdx] != '_'))
                if (matchesParam) {
                    sb.append("reified ").append(tpKt)
                    i = afterIdx
                } else {
                    sb.append(ch)
                    i++
                }
                atSlotStart = false
            }
            else -> {
                sb.append(ch)
                i++
            }
        }
    }
    return sb.toString()
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

internal fun KotlinJ2KEmitter.computeReassignedParamShadows(
    sig: Signature,
    block: Block,
    innerIndent: String,
): List<String> {
    val shadows = mutableListOf<String>()
    for (arg in sig.inputs) {
        if (arg is FnArg.Typed) {
            val rawParam = extractRawParamIdent(arg.patType.pat)
            if (rawParam != null && rawParam != "_" && blockReassignsIdent(block, rawParam)) {
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
        }
    }
    return shadows
}

internal fun KotlinJ2KEmitter.blockReassignsIdent(block: Block, targetRawIdent: String): Boolean {
    var reassigned = false
    val visitor =
        object : Visit() {
            override fun visitItemFn(i: Item.Fn) {
                // Local `fn` items cannot capture or reassign outer parameters in Rust.
            }

            override fun visitExprMatch(e: Expr.Match) {
                val subj = unwrapParenOrGroupExpr(e.expr)
                if (subj is Expr.Reference &&
                    subj.mutability != null &&
                    extractAssignedRootIdent(subj.expr) == targetRawIdent &&
                    e.arms.any { matchArmMutatesTupleVariantBinding(it) }
                ) {
                    reassigned = true
                    return
                }
                super.visitExprMatch(e)
            }

            override fun visitExprAssign(e: Expr.Assign) {
                if (extractAssignedRootIdent(e.left) == targetRawIdent) {
                    reassigned = true
                    return
                }
                super.visitExprAssign(e)
            }

            override fun visitExprBinary(e: Expr.Binary) {
                if (isCompoundAssignOp(e.op) && extractAssignedRootIdent(e.left) == targetRawIdent) {
                    reassigned = true
                    return
                }
                super.visitExprBinary(e)
            }
        }
    visitor.visitBlock(block)
    return reassigned
}

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
    var mutates = false
    val visitor =
        object : Visit() {
            override fun visitItemFn(i: Item.Fn) {}

            override fun visitExprAssign(e: Expr.Assign) {
                if (extractAssignedRootIdent(e.left) in rawBindings) {
                    mutates = true
                    return
                }
                super.visitExprAssign(e)
            }

            override fun visitExprBinary(e: Expr.Binary) {
                if (isCompoundAssignOp(e.op) && extractAssignedRootIdent(e.left) in rawBindings) {
                    mutates = true
                    return
                }
                super.visitExprBinary(e)
            }
        }
    visitor.visitExpr(arm.body)
    return mutates
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

internal fun KotlinJ2KEmitter.deduplicateComplementaryCfgStmts(rawStmts: List<Stmt>): List<Stmt> {
    val surviving = rawStmts.filterNot { isLeadingCfgStmtDisabled(it) }
    if (surviving.size < 2) return surviving
    var lastRawExecIdx = -1
    for (i in surviving.indices.reversed()) {
        if (surviving[i] !is Stmt.ItemStmt) {
            lastRawExecIdx = i
            break
        }
    }
    val filtered = ArrayList<Stmt>(surviving.size)
    var i = 0
    while (i < surviving.size) {
        val cur = surviving[i]
        if (i + 1 < surviving.size) {
            val next = surviving[i + 1]
            val sig1 = extractLeadingCfgSignature(cur)
            val sig2 = if (sig1 != null) extractLeadingCfgSignature(next) else null
            if (sig1 != null && sig2 != null && sig1.second == sig2.second && sig1.first != sig2.first) {
                val sameLocalBinding =
                    cur is Stmt.Local &&
                        next is Stmt.Local &&
                        (cur.pat as? Pat.Ident)?.ident?.toString() != null &&
                        (cur.pat as? Pat.Ident)?.ident?.toString() == (next.pat as? Pat.Ident)?.ident?.toString()
                val sameItemFnName =
                    cur is Stmt.ItemStmt &&
                        next is Stmt.ItemStmt &&
                        (cur.item as? Item.Fn)?.sig?.ident?.toString() != null &&
                        (cur.item as? Item.Fn)?.sig?.ident?.toString() == (next.item as? Item.Fn)?.sig?.ident?.toString()
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

internal fun isLeadingCfgStmtDisabled(stmt: Stmt): Boolean {
    val trees = stmt.toTokenStream().toList()
    var idx = 0
    while (idx + 1 < trees.size) {
        val pound = (trees[idx] as? TokenTree.Punct)?.value?.asChar()
        val bracket = (trees[idx + 1] as? TokenTree.Group)?.value
        if (pound != '#' || bracket == null || bracket.delimiter() != Delimiter.Bracket) break
        val attrTrees = bracket.stream().toList()
        if (attrTrees.size == 2 &&
            (attrTrees[0] as? TokenTree.Ident)?.value?.toString() == "cfg" &&
            (attrTrees[1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis
        ) {
            val predTrees = (attrTrees[1] as TokenTree.Group).value.stream().toList()
            if (isDocOnlyCfgPredicateTokens(predTrees)) return true
        }
        idx += 2
    }
    return false
}

internal fun KotlinJ2KEmitter.extractLeadingCfgSignature(stmt: Stmt): Pair<Boolean, String>? {
    val trees = stmt.toTokenStream().toList()
    var found: Pair<Boolean, String>? = null
    var idx = 0
    while (idx + 1 < trees.size) {
        val pound = (trees[idx] as? TokenTree.Punct)?.value?.asChar()
        val bracket = (trees[idx + 1] as? TokenTree.Group)?.value
        if (pound != '#' || bracket == null || bracket.delimiter() != Delimiter.Bracket) break
        val attrTrees = bracket.stream().toList()
        if (attrTrees.size == 2 &&
            (attrTrees[0] as? TokenTree.Ident)?.value?.toString() == "cfg" &&
            (attrTrees[1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis
        ) {
            if (found != null) return null
            val predTrees = (attrTrees[1] as TokenTree.Group).value.stream().toList()
            found =
                if (predTrees.size == 2 &&
                    (predTrees[0] as? TokenTree.Ident)?.value?.toString() == "not" &&
                    (predTrees[1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis
                ) {
                    val innerPred = (predTrees[1] as TokenTree.Group).value.stream().toList()
                    false to canonicalTokenSequenceKey(innerPred)
                } else {
                    true to canonicalTokenSequenceKey(predTrees)
                }
        }
        idx += 2
    }
    return found
}

internal fun KotlinJ2KEmitter.canonicalTokenSequenceKey(trees: List<TokenTree>): String =
    trees.joinToString(" ") { tt ->
        when (tt) {
            is TokenTree.Ident -> "I:${tt.value}"
            is TokenTree.Punct -> "P:${tt.value.asChar()}:${tt.value.spacing()}"
            is TokenTree.Literal -> "L:${tt.value}"
            is TokenTree.Group ->
                "G:${tt.value.delimiter()}(${canonicalTokenSequenceKey(tt.value.stream().toList())})"
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

    val effectiveRenderedRet =
        if (returnType != null) {
            currentEnclosingReturnRenderedType ?: renderType(returnType, selfTypeName)
        } else {
            null
        }
    val returnsValue = effectiveRenderedRet != null && effectiveRenderedRet != "Unit"

    var lastExecutableIdx = -1
    for (i in stmts.indices.reversed()) {
        if (stmts[i] !is Stmt.ItemStmt) {
            lastExecutableIdx = i
            break
        }
    }

    val localBlockImpls = linkedMapOf<String, MutableList<Item.Impl>>()
    val localAttachableNames =
        stmts
            .mapNotNull { stmt ->
                when (val it = (stmt as? Stmt.ItemStmt)?.item) {
                    is Item.Struct -> rustTypeIdentToKotlin(it.ident.toString())
                    is Item.Enum -> rustTypeIdentToKotlin(it.ident.toString())
                    is Item.Union -> rustTypeIdentToKotlin(it.ident.toString())
                    else -> null
                }
            }.toSet()
    for (stmt in stmts) {
        val implItem = (stmt as? Stmt.ItemStmt)?.item as? Item.Impl ?: continue
        val ownerType = extractDirectOwnerTypeName(implItem.selfType, implItem.generics)
        if (ownerType != null && ownerType in localAttachableNames) {
            localBlockImpls.getOrPut(ownerType) { mutableListOf() }.add(implItem)
        }
    }

    val localBlockSignatures = linkedMapOf<String, Int>()
    val prevBorrowedPlaceLocals = currentBorrowedPlaceLocals
    val prevAddressableLocals = currentAddressableLocals
    val prevArrayElementTypes = currentAddressableArrayElementTypes
    val prevSourceParameterTypes = currentSourceParameterTypes
    val prevFnParamNumericKinds = currentFunctionParamNumericKinds
    val blockEntryExpectedKind = currentExpectedNumericKind
    val blockEntryTailExpectsUnitResultOk = currentTailExpectsUnitResultOk
    val savedBlockLocalFnRawNames = hashSetOf<String>()
    val savedBlockLocalInlineModNames = hashSetOf<String>()
    val prevBlockLocalFnReturnSynTypes = linkedMapOf<String, SynType?>()
    currentExpectedNumericKind = null
    currentTailExpectsUnitResultOk = false
    scopeBuilder.enterCompr()
    try {
        for (stmt in stmts) {
            val fnItem = (stmt as? Stmt.ItemStmt)?.item as? Item.Fn ?: continue
            if (itemHasDocOnlyCfg(fnItem)) continue
            val cleanRaw =
                fnItem.sig.ident
                    .toString()
                    .removePrefix("r#")
            if (cleanRaw !in inspector.declaredTopLevelFnRawNames) {
                savedBlockLocalFnRawNames += cleanRaw
                inspector.declaredTopLevelFnRawNames += cleanRaw
            }
            val retOut = fnItem.sig.output as? ReturnType.TypeReturn
            if (retOut != null) {
                val ktFn = rustFunctionIdentToKotlin(fnItem.sig.ident.toString())
                if (cleanRaw !in prevBlockLocalFnReturnSynTypes) {
                    prevBlockLocalFnReturnSynTypes[cleanRaw] = inspector.topLevelFnReturnSynTypes[cleanRaw]
                }
                if (ktFn !in prevBlockLocalFnReturnSynTypes) {
                    prevBlockLocalFnReturnSynTypes[ktFn] = inspector.topLevelFnReturnSynTypes[ktFn]
                }
                inspector.topLevelFnReturnSynTypes[cleanRaw] = retOut.ty
                inspector.topLevelFnReturnSynTypes[ktFn] = retOut.ty
            }
        }
        val blockFnParamKinds = linkedMapOf<String, List<NumericKind?>?>()
        for (stmt in stmts) {
            val fnItem = (stmt as? Stmt.ItemStmt)?.item as? Item.Fn ?: continue
            if (itemHasDocOnlyCfg(fnItem)) continue
            val extracted = extractFnParamNumericKinds(fnItem, selfTypeName)
            if (extracted != null) {
                val (fnName, kinds) = extracted
                blockFnParamKinds[fnName] = if (fnName in blockFnParamKinds) null else kinds
            }
        }
        if (blockFnParamKinds.isNotEmpty()) {
            val updated = currentFunctionParamNumericKinds.toMutableMap()
            for ((fnName, kinds) in blockFnParamKinds) {
                if (kinds == null) {
                    updated.remove(fnName)
                } else {
                    updated[fnName] = kinds
                }
            }
            currentFunctionParamNumericKinds = updated
        }
        // Hoist local item declarations (`fn`, `struct`, `enum`, `type`, `const`, `use`)
        // before executable statements so forward calls/type references resolve in Kotlin,
        // topologically ordering hoisted local types/functions so referenced helpers precede callers.
        for (stmt in stmts) {
            val itemInStmt = (stmt as? Stmt.ItemStmt)?.item ?: continue
            if (itemHasDocOnlyCfg(itemInStmt)) continue
            if (itemInStmt is Item.Use) {
                registerUseItemInScope(itemInStmt)
            } else if (itemInStmt is Item.Mod && itemInStmt.content is ModContent.Inline) {
                val modRaw = itemInStmt.ident.toString().removePrefix("r#")
                if (modRaw !in inspector.localInlineModuleNames) {
                    savedBlockLocalInlineModNames += modRaw
                    inspector.localInlineModuleNames += modRaw
                }
            }
        }
        val hoistedItems = mutableListOf<Pair<String?, String>>()
        for (stmt in stmts) {
            val itemInStmt = (stmt as? Stmt.ItemStmt)?.item ?: continue
            if (itemHasDocOnlyCfg(itemInStmt) || itemInStmt is Item.Use) continue
            if (itemInStmt is Item.Impl) {
                val ownerType = extractDirectOwnerTypeName(itemInStmt.selfType, itemInStmt.generics)
                if (ownerType != null && ownerType in localAttachableNames) {
                    continue
                }
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
                renderStmt(
                    stmt = stmt,
                    indent = innerIndent,
                    selfTypeName = selfTypeName,
                    isTailReturn = false,
                    localTypeImpls = localBlockImpls,
                    localSignatures = localBlockSignatures,
                )
            if (renderedItem.isNotBlank()) {
                hoistedItems += declName to renderedItem
            }
        }
        if (hoistedItems.size <= 1) {
            for ((_, renderedItem) in hoistedItems) {
                lines += renderedItem
            }
        } else {
            val n = hoistedItems.size
            val deps = Array(n) { hashSetOf<Int>() }
            for (i in 0 until n) {
                val (_, renderedI) = hoistedItems[i]
                for (j in 0 until n) {
                    if (i == j) continue
                    val nameJ = hoistedItems[j].first ?: continue
                    if (containsWholeIdentifier(renderedI, nameJ)) {
                        deps[i] += j
                    }
                }
            }
            val emitted = BooleanArray(n)
            var remaining = n
            while (remaining > 0) {
                var picked = -1
                for (i in 0 until n) {
                    if (!emitted[i] && deps[i].all { emitted[it] }) {
                        picked = i
                        break
                    }
                }
                if (picked < 0) {
                    picked = (0 until n).first { !emitted[it] }
                }
                emitted[picked] = true
                remaining--
                lines += hoistedItems[picked].second
            }
        }
        renderBlockStmtsInto(
            stmts = stmts,
            startIdx = 0,
            endIdx = stmts.size,
            lines = lines,
            innerIndent = innerIndent,
            selfTypeName = selfTypeName,
            returnsValue = returnsValue,
            lastExecutableIdx = lastExecutableIdx,
            blockEntryExpectedKind = blockEntryExpectedKind,
            blockEntryTailExpectsUnitResultOk = blockEntryTailExpectsUnitResultOk,
            localBlockImpls = localBlockImpls,
            localBlockSignatures = localBlockSignatures,
        )
        if (closeBraceTrivia.isNotBlank()) {
            lines += closeBraceTrivia.trimEnd('\n')
        }
        lines.addAll(trailingInjectedLines)
        if (headerContext.isBatchCrateOutput) {
            sanitizeBatchBlockLocals(lines)
        }
    } finally {
        inspector.declaredTopLevelFnRawNames.removeAll(savedBlockLocalFnRawNames)
        inspector.localInlineModuleNames.removeAll(savedBlockLocalInlineModNames)
        for ((fnKey, prevSyn) in prevBlockLocalFnReturnSynTypes) {
            if (prevSyn == null) {
                inspector.topLevelFnReturnSynTypes.remove(fnKey)
            } else {
                inspector.topLevelFnReturnSynTypes[fnKey] = prevSyn
            }
        }
        currentExpectedNumericKind = blockEntryExpectedKind
        currentTailExpectsUnitResultOk = blockEntryTailExpectsUnitResultOk
        currentBorrowedPlaceLocals = prevBorrowedPlaceLocals
        currentAddressableLocals = prevAddressableLocals
        currentAddressableArrayElementTypes = prevArrayElementTypes
        currentSourceParameterTypes = prevSourceParameterTypes
        currentFunctionParamNumericKinds = prevFnParamNumericKinds
        scopeBuilder.exitCompr()
    }

    if (lines.isEmpty()) return "{}"
    if (allowExpressionBody && headerContext.isBatchCrateOutput && returnsValue && lines.size == 1) {
        val single = lines[0]
        val returnPrefix = "${innerIndent}return "
        if (single.startsWith(returnPrefix)) {
            val exprText = single.removePrefix(returnPrefix)
            if (!containsWholeIdentifier(exprText, "return") && !containsWholeIdentifier(exprText, "throw")) {
                return if (exprText.contains('\n')) {
                    "=\n$innerIndent$exprText"
                } else {
                    "= $exprText"
                }
            }
        }
    }
    val sb = StringBuilder()
    sb.append("{\n")
    for (line in lines) {
        sb.append(line)
        if (!line.endsWith('\n')) sb.append('\n')
    }
    sb.append(indent).append("}")
    return sb.toString()
}

private fun KotlinJ2KEmitter.renderBlockStmtsInto(
    stmts: List<Stmt>,
    startIdx: Int,
    endIdx: Int,
    lines: MutableList<String>,
    innerIndent: String,
    selfTypeName: String?,
    returnsValue: Boolean,
    lastExecutableIdx: Int,
    blockEntryExpectedKind: NumericKind?,
    blockEntryTailExpectsUnitResultOk: Boolean,
    localBlockImpls: Map<String, List<Item.Impl>>,
    localBlockSignatures: MutableMap<String, Int>,
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
                stmts = stmts,
                idx = idx,
                endIdx = endIdx,
                innerIndent = innerIndent,
                selfTypeName = selfTypeName,
                returnsValue = returnsValue,
                lastExecutableIdx = lastExecutableIdx,
                blockEntryExpectedKind = blockEntryExpectedKind,
                localBlockImpls = localBlockImpls,
                localBlockSignatures = localBlockSignatures,
            )
        if (refCellStep != null) {
            val (renderedScope, nextIdx) = refCellStep
            if (renderedScope.isNotBlank()) {
                lines += renderedScope
            }
            idx = nextIdx
            continue
        }
        if (stmt is Stmt.Local &&
            stmt.init == null &&
            stmt.pat is Pat.Ident &&
            (stmt.pat as Pat.Ident).subpat == null &&
            idx + 1 < endIdx
        ) {
            val identPat = stmt.pat as Pat.Ident
            val rawVar = identPat.ident.toString().removePrefix("r#")
            val nextStmt = stmts[idx + 1]
            if (nextStmt is Stmt.MacroStmt) {
                val delim = extractDelimitedMacroParts(nextStmt.mac, innerIndent, selfTypeName)
                if (delim != null && delim.second == rawVar) {
                    val trivia1 = renderLeadingTriviaForSpan(stmt.span(), innerIndent)
                    val trivia2 = renderLeadingTriviaForSpan(nextStmt.span(), innerIndent)
                    val assignCount = if (identPat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                    val ktVar =
                        scopeBuilder
                            .bindLocal(rawVar, rustValueIdentToKotlin(rawVar), identPat.ident.span(), assignCount = assignCount)
                            .kotlinName
                    lines += "${trivia1}${trivia2}${innerIndent}val $ktVar = (${renderSourceResultTry("${delim.first}(${delim.third})", "return")}).content"
                    idx += 2
                    continue
                }
            } else if (nextStmt is Stmt.Local && nextStmt.init?.diverge == null && nextStmt.init?.expr is Expr.Macro) {
                val delim = extractDelimitedMacroParts((nextStmt.init!!.expr as Expr.Macro).mac, innerIndent, selfTypeName)
                if (delim != null && delim.second == rawVar) {
                    val trivia1 = renderLeadingTriviaForSpan(stmt.span(), innerIndent)
                    val trivia2 = renderLeadingTriviaForSpan(nextStmt.span(), innerIndent)
                    val assignCount = if (identPat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                    val ktVar =
                        scopeBuilder
                            .bindLocal(rawVar, rustValueIdentToKotlin(rawVar), identPat.ident.span(), assignCount = assignCount)
                            .kotlinName
                    val (tokPat, tokTy) = renderLocalPattern(nextStmt.pat, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
                    val tokKeyword = if (isMutablePattern(nextStmt.pat)) "var" else "val"
                    val tokSuffix = if (tokTy != null) ": $tokTy" else ""
                    lines += "${trivia1}${trivia2}${innerIndent}val ${ktVar}Group = ${renderSourceResultTry("${delim.first}(${delim.third})", "return")}"
                    lines += "${innerIndent}val $ktVar = ${ktVar}Group.content"
                    lines += "${innerIndent}$tokKeyword $tokPat$tokSuffix = ${ktVar}Group.token"
                    idx += 2
                    continue
                }
            } else if (nextStmt is Stmt.ExprStmt &&
                nextStmt.expr is Expr.Match &&
                !(idx + 1 == lastExecutableIdx && returnsValue)
            ) {
                val foldedLines =
                    tryRenderDeferredLocalResultMatchInit(
                        identPat = identPat,
                        rawVar = rawVar,
                        localStmt = stmt,
                        matchStmt = nextStmt,
                        matchExpr = nextStmt.expr as Expr.Match,
                        targetLocalStmt = null,
                        innerIndent = innerIndent,
                        selfTypeName = selfTypeName,
                    )
                if (foldedLines != null) {
                    lines.addAll(foldedLines)
                    idx += 2
                    continue
                }
            } else if (nextStmt is Stmt.Local &&
                nextStmt.init?.diverge == null &&
                nextStmt.init?.expr is Expr.Match
            ) {
                val foldedLines =
                    tryRenderDeferredLocalResultMatchInit(
                        identPat = identPat,
                        rawVar = rawVar,
                        localStmt = stmt,
                        matchStmt = nextStmt,
                        matchExpr = nextStmt.init!!.expr as Expr.Match,
                        targetLocalStmt = nextStmt,
                        innerIndent = innerIndent,
                        selfTypeName = selfTypeName,
                    )
                if (foldedLines != null) {
                    lines.addAll(foldedLines)
                    idx += 2
                    continue
                }
            }
        }
        val isLastExec = (idx == lastExecutableIdx)
        val isTail = isLastExec && returnsValue
        val isValueTailExpr =
            isLastExec &&
                !returnsValue &&
                ((stmt is Stmt.ExprStmt && stmt.semiToken == null) || (stmt is Stmt.MacroStmt && stmt.semiToken == null))
        val rendered =
            try {
                if (isValueTailExpr) {
                    currentExpectedNumericKind = blockEntryExpectedKind
                    currentTailExpectsUnitResultOk = blockEntryTailExpectsUnitResultOk
                } else {
                    currentExpectedNumericKind = null
                    currentTailExpectsUnitResultOk = false
                }
                renderStmt(
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
                currentExpectedNumericKind = null
                currentTailExpectsUnitResultOk = false
            }
        if (rendered.isNotBlank()) {
            lines += rendered
        }
        idx++
    }
}

private fun KotlinJ2KEmitter.tryRenderRefCellBorrowGuardScope(
    stmts: List<Stmt>,
    idx: Int,
    endIdx: Int,
    innerIndent: String,
    selfTypeName: String?,
    returnsValue: Boolean,
    lastExecutableIdx: Int,
    blockEntryExpectedKind: NumericKind?,
    localBlockImpls: Map<String, List<Item.Impl>>,
    localBlockSignatures: MutableMap<String, Int>,
): Pair<String, Int>? {
    val stmt = stmts[idx] as? Stmt.Local ?: return null
    val init = stmt.init?.takeIf { it.diverge == null } ?: return null
    val identPat = (unwrapTransparentPat(stmt.pat) as? Pat.Ident)?.takeIf { it.subpat == null } ?: return null
    val initCall = unwrapParenOrGroupExpr(init.expr) as? Expr.MethodCall ?: return null
    if (!initCall.args.isEmpty() || initCall.turbofish != null) return null
    val callMethod = initCall.method.toString().removePrefix("r#")
    if (callMethod != "borrow" && callMethod != "borrow_mut") return null
    val recvTy = inferExprRenderedType(initCall.receiver, selfTypeName)?.removeSuffix("?")?.trim() ?: return null
    if (recvTy.substringBefore('<').substringAfterLast('.') != "RefCell" || '<' !in recvTy || !recvTy.endsWith('>')) {
        return null
    }
    val innerTy = recvTy.substringAfter('<').dropLast(1).trim()
    val rawVar = identPat.ident.toString().removePrefix("r#")
    val dropIdx = (idx + 1 until endIdx).firstOrNull { isDropOfLocalStmt(stmts[it], rawVar) }
    val scopeEnd = dropIdx ?: endIdx
    val nextIdx = if (dropIdx != null) dropIdx + 1 else endIdx
    val cellRendered = renderExpr(initCall.receiver, innerIndent, selfTypeName)
    val cellAtom = if (isAtomicPostfixReceiver(initCall.receiver, cellRendered)) cellRendered else "($cellRendered)"
    val isMut = (callMethod == "borrow_mut")
    val isStringMut = isMut && innerTy == "String"
    val borrowFn = if (isMut) "borrowMut" else "borrow"
    val useFn = if (isStringMut) "useMut" else "use"
    val guardVarTy = if (isStringMut) "RefMut<String>" else innerTy
    val triviaPrefix = renderLeadingTriviaForSpan(stmt.span(), innerIndent)
    val guardCapturesTail = returnsValue && scopeEnd == endIdx && lastExecutableIdx in (idx + 1) until scopeEnd

    val prevTyped = currentTypedLocals
    val prevMutableLists = currentMutableListLocals
    val prevSmartCast = currentSmartCastNonNullLocals
    val scopeLines = mutableListOf<String>()
    val lambdaBodyIndent = "$innerIndent    "
    scopeBuilder.enterCompr()
    val boundKtVar =
        try {
            val ktVar =
                scopeBuilder
                    .bindLocal(
                        rawName = rawVar,
                        baseKotlinName = rustValueIdentToKotlin(rawVar),
                        span = identPat.ident.span(),
                        assignCount = AssignCount.AtMostOnce,
                    ).kotlinName
            currentTypedLocals = prevTyped + (ktVar to guardVarTy)
            if (!isStringMut && innerTy.startsWith("MutableList<")) {
                currentMutableListLocals = prevMutableLists + ktVar
            }
            val capturedTailKind =
                if (guardCapturesTail) {
                    currentEnclosingReturnRenderedType?.let(this::resolveNumericKind) ?: blockEntryExpectedKind
                } else {
                    null
                }
            val capturedTailUnitResultOk =
                guardCapturesTail && (rustResultOkPayloadType(currentEnclosingReturnRenderedType) == "Unit")
            renderBlockStmtsInto(
                stmts = stmts,
                startIdx = idx + 1,
                endIdx = scopeEnd,
                lines = scopeLines,
                innerIndent = lambdaBodyIndent,
                selfTypeName = selfTypeName,
                returnsValue = false,
                lastExecutableIdx = if (guardCapturesTail) lastExecutableIdx else -1,
                blockEntryExpectedKind = capturedTailKind,
                blockEntryTailExpectsUnitResultOk = capturedTailUnitResultOk,
                localBlockImpls = localBlockImpls,
                localBlockSignatures = localBlockSignatures,
            )
            if (headerContext.isBatchCrateOutput) {
                sanitizeBatchBlockLocals(scopeLines)
            }
            ktVar
        } finally {
            currentTypedLocals = prevTyped
            currentMutableListLocals = prevMutableLists
            currentSmartCastNonNullLocals = prevSmartCast
            scopeBuilder.exitCompr()
        }

    val returnPrefix =
        if (guardCapturesTail && !stmtAlwaysDiverges(stmts[lastExecutableIdx])) {
            "return "
        } else {
            ""
        }
    val sb = StringBuilder()
    sb.append(triviaPrefix)
    sb
        .append(innerIndent)
        .append(returnPrefix)
        .append(cellAtom)
        .append('.')
        .append(borrowFn)
        .append("().")
        .append(useFn)
        .append(" { ")
        .append(boundKtVar)
        .append(" ->\n")
    for (line in scopeLines) {
        sb.append(line)
        if (!line.endsWith('\n')) sb.append('\n')
    }
    sb.append(innerIndent).append('}')
    return sb.toString() to nextIdx
}

private fun KotlinJ2KEmitter.isDropOfLocalStmt(stmt: Stmt, rawVar: String): Boolean {
    val exprStmt = stmt as? Stmt.ExprStmt ?: return false
    val call = unwrapParenOrGroupExpr(exprStmt.expr) as? Expr.Call ?: return false
    val path = (call.func as? Expr.Path)?.takeIf { it.qself == null } ?: return false
    val segs =
        path.path.segments
            .toList()
            .map { it.ident.toString().removePrefix("r#") }
    if (segs.lastOrNull() != "drop" || segs.dropLast(1).any { it !in setOf("core", "std", "mem") }) {
        return false
    }
    val arg =
        call.args
            .toList()
            .singleOrNull()
            ?.let { unwrapParenOrGroupExpr(it) } as? Expr.Path ?: return false
    val argSegs = arg.path.segments.toList()
    return arg.qself == null && argSegs.size == 1 && argSegs[0].ident.toString().removePrefix("r#") == rawVar
}

private fun KotlinJ2KEmitter.stmtAlwaysDiverges(stmt: Stmt): Boolean =
    when (stmt) {
        is Stmt.ExprStmt -> exprAlwaysDiverges(stmt.expr)
        else -> false
    }

private fun KotlinJ2KEmitter.exprAlwaysDiverges(expr: Expr): Boolean =
    when (val core = unwrapParenOrGroupExpr(expr)) {
        is Expr.Return, is Expr.Break, is Expr.Continue -> true
        is Expr.Macro ->
            core.mac.path.segments
                .last()
                ?.ident
                ?.toString() in setOf("panic", "unreachable", "todo", "unimplemented")
        is Expr.BlockExpr ->
            core.block.stmts
                .lastOrNull()
                ?.let { stmtAlwaysDiverges(it) } == true
        is Expr.If -> {
            val elseExpr = core.elseBranch?.expr
            elseExpr != null && core.thenBranch.stmts
                .lastOrNull()
                ?.let { stmtAlwaysDiverges(it) } == true && exprAlwaysDiverges(elseExpr)
        }
        is Expr.Match -> core.arms.isNotEmpty() && core.arms.all { exprAlwaysDiverges(it.body) }
        else -> false
    }

internal fun KotlinJ2KEmitter.sanitizeBatchBlockLocals(lines: MutableList<String>) {
    val singleLineDeclRegex = Regex("^(\\s*)(val|var)\\s+([a-zA-Z_][a-zA-Z0-9_]*)(\\s*:\\s*[^=\\n]+)?\\s*=\\s*([^\\n]+)$")
    for (i in lines.indices.reversed()) {
        val rawEntry = lines[i]
        val entryLines = rawEntry.split('\n')
        var firstCodeIdx = 0
        while (firstCodeIdx < entryLines.size && entryLines[firstCodeIdx].trimStart().startsWith("//")) {
            firstCodeIdx++
        }
        if (firstCodeIdx >= entryLines.size) continue
        if (entryLines.size - firstCodeIdx != 1) continue
        val triviaPart = if (firstCodeIdx > 0) entryLines.subList(0, firstCodeIdx).joinToString("\n") else ""
        val codeLine = entryLines[firstCodeIdx]
        val match = singleLineDeclRegex.matchEntire(codeLine) ?: continue
        val lineIndent = match.groupValues[1]
        val kw = match.groupValues[2]
        val ident = match.groupValues[3]
        val typeSuffix = match.groupValues[4]
        val rhs = match.groupValues[5]
        val afterText = if (i + 1 < lines.size) lines.subList(i + 1, lines.size).joinToString("\n") else ""
        if (!containsWholeIdentifier(afterText, ident)) {
            if (triviaPart.isNotEmpty()) {
                lines[i] = triviaPart
            } else {
                lines.removeAt(i)
            }
            continue
        }
        if (kw == "var") {
            val isReassigned =
                Regex("(?<![.\\w])${Regex.escape(ident)}\\s*(?:[+\\-*/%]?=(?![=>])|\\+\\+|--)").containsMatchIn(afterText) ||
                    Regex("(?:\\+\\+|--)\\s*${Regex.escape(ident)}\\b").containsMatchIn(afterText)
            if (!isReassigned) {
                val updatedCode = "${lineIndent}val $ident$typeSuffix = $rhs"
                lines[i] = if (triviaPart.isNotEmpty()) "$triviaPart\n$updatedCode" else updatedCode
            }
        }
    }
}

internal fun KotlinJ2KEmitter.extractDelimitedMacroParts(
    mac: Macro,
    indent: String,
    selfTypeName: String?,
): Triple<String, String, String>? {
    val lastSeg = mac.path.segments.last() ?: return null
    val macroName = lastSeg.ident.toString()
    if (macroName != "parenthesized" && macroName != "braced" && macroName != "bracketed") return null
    val list = mac.tokens.clone().toList()
    if (list.size < 3) return null
    val targetIdent = (list[0] as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#") ?: return null
    val inKeyword = (list[1] as? TokenTree.Ident)?.value?.toString() ?: return null
    if (inKeyword != "in") return null
    val streamTokens = TokenStream.fromTokenTrees(list.subList(2, list.size))
    val renderedStream = parseAndRenderTokenStreamAsExpr(streamTokens, indent, selfTypeName)
    return Triple(macroName, targetIdent, renderedStream)
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
                val onlyStmt = eb.block.stmts.singleOrNull()
                (onlyStmt as? Stmt.ExprStmt)?.expr
            }
            else -> eb
        } ?: return null
    if (errBodyUnwrapped !is Expr.Return) return null
    val assignsTarget =
        okBlock.stmts.any { s ->
            s is Stmt.ExprStmt &&
                s.expr is Expr.Assign &&
                extractAssignedRootIdent((s.expr as Expr.Assign).left) == rawVar
        }
    if (!assignsTarget) return null

    val trivia1 = renderLeadingTriviaForSpan(localStmt.span(), innerIndent)
    val trivia2 = renderLeadingTriviaForSpan(matchStmt.span(), innerIndent)
    val subjectRendered = renderExpr(matchExpr.expr, innerIndent, selfTypeName)
    val okSubPat = (unwrapTransparentPat(armPattern(okArm)) as Pat.TupleStruct).elems.toList().firstOrNull()
    val errSubPat = (unwrapTransparentPat(armPattern(errArm)) as? Pat.TupleStruct)?.elems?.toList()?.firstOrNull()

    val out = mutableListOf<String>()
    val okVar = scopeBuilder.bindLocal("_ok", "matchVal", matchExpr.expr.span()).kotlinName
    if (usesTaggedCoreResult()) {
        val errRender =
            run {
                scopeBuilder.enterCompr()
                try {
                    val errParam =
                        if (errSubPat is Pat.Ident && errSubPat.subpat == null && errSubPat.ident.toString() != "_") {
                            val rawErr = errSubPat.ident.toString().removePrefix("r#")
                            scopeBuilder.bindLocal(rawErr, rustValueIdentToKotlin(rawErr), errSubPat.ident.span()).kotlinName
                        } else {
                            "err"
                        }
                    val renderedReturn = renderExpr(errBodyUnwrapped, "$innerIndent    ", selfTypeName)
                    "{ val $errParam = matchResult.error; $renderedReturn }"
                } finally {
                    scopeBuilder.exitCompr()
                }
            }
        val resultType = sourceResultName()
        out += "${trivia1}${trivia2}${innerIndent}val $okVar = when (val matchResult = $subjectRendered) { is $resultType.Ok<*, *> -> matchResult.value; is $resultType.Err<*, *> -> $errRender }"
    } else {
        val errRender =
            run {
                scopeBuilder.enterCompr()
                try {
                    val errParam =
                        if (errSubPat is Pat.Ident && errSubPat.subpat == null && errSubPat.ident.toString() != "_") {
                            val rawErr = errSubPat.ident.toString().removePrefix("r#")
                            scopeBuilder.bindLocal(rawErr, rustValueIdentToKotlin(rawErr), errSubPat.ident.span()).kotlinName
                        } else {
                            null
                        }
                    val renderedReturn = renderExpr(errBodyUnwrapped, "$innerIndent    ", selfTypeName)
                    if (errParam != null) "{ $errParam -> $renderedReturn }" else "{ $renderedReturn }"
                } finally {
                    scopeBuilder.exitCompr()
                }
            }
        out += "${trivia1}${trivia2}${innerIndent}val $okVar = $subjectRendered.getOrElse $errRender"
    }
    if (okSubPat != null) {
        out += extractMatchArmBindings(okSubPat, okVar, innerIndent, selfTypeName, bindToScope = true)
    }
    val kw = if (identPat.mutability != null) "var" else "val"
    val assignCount = if (identPat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
    var boundVar = false
    for ((sIdx, s) in okBlock.stmts.withIndex()) {
        if (s is Stmt.ExprStmt && s.expr is Expr.Assign) {
            val assignExpr = s.expr as Expr.Assign
            if (!boundVar && extractAssignedRootIdent(assignExpr.left) == rawVar) {
                val ktVar = scopeBuilder.bindLocal(rawVar, rustValueIdentToKotlin(rawVar), identPat.ident.span(), assignCount = assignCount).kotlinName
                val rhsText = renderExpr(assignExpr.right, innerIndent, selfTypeName)
                out += "$innerIndent$kw $ktVar = $rhsText"
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
                if (tokPat != "_" && tokPat.isNotEmpty()) {
                    out += "$innerIndent$tokKeyword $tokPat$tokSuffix = $tailVal"
                }
                continue
            }
            if (isSideEffectFreeExpr(s.expr)) {
                continue
            }
        }
        val renderedS = renderStmt(s, innerIndent, selfTypeName, isTailReturn = false)
        if (renderedS.isNotBlank()) {
            out += renderedS
        }
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
                        prefix in IGNORED_PATH_PREFIXES ||
                            prefix in STDLIB_PRELUDE_REEXPORT_MODULES ||
                            prefix == "Result" ||
                            prefix == "result"
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
    val triviaPrefix = renderLeadingTriviaForSpan(stmt.span(), indent)
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
                    if (rendered.isBlank() || rendered.trim() == "Unit") {
                        ""
                    } else if (!headerContext.isBatchCrateOutput && isLastExecutableStmt && !hasSemi && rendered.startsWith("error(")) {
                        "${indent}return $rendered"
                    } else {
                        "$indent$rendered"
                    }
                }
            }
            is Stmt.MacroStmt -> {
                val rawRendered = renderMacroInvocationAsStmtOrExpr(stmt.mac, indent, selfTypeName)
                val macName =
                    stmt.mac.path.segments
                        .last()
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                val isWriteMacTail =
                    stmt.semiToken == null &&
                        macName in setOf("write", "writeln") &&
                        rustResultOkPayloadType(currentEnclosingReturnRenderedType) == "Unit" &&
                        (isTailReturn || (isLastExecutableStmt && currentTailExpectsUnitResultOk))
                val rendered =
                    if (isWriteMacTail && rawRendered.isNotBlank()) {
                        val okVal = if (usesTaggedCoreResult()) "${sourceResultName()}.Ok(Unit)" else "Result.success(Unit)"
                        if (rawRendered.contains('\n')) "run {\n$rawRendered\n$okVal\n}" else "run { $rawRendered; $okVal }"
                    } else {
                        rawRendered
                    }
                if (rendered.isBlank()) {
                    ""
                } else if (stmt.semiToken == null &&
                    !headerContext.isBatchCrateOutput &&
                    (isTailReturn || (isLastExecutableStmt && rendered.startsWith("error(")))
                ) {
                    "${indent}return $rendered"
                } else if (stmt.semiToken == null && isTailReturn && !rendered.startsWith("error(")) {
                    "${indent}return $rendered"
                } else {
                    "$indent$rendered"
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
    currentTailExpectsUnitResultOk = (rustResultOkPayloadType(currentEnclosingReturnRenderedType) == "Unit")
    val rawRendered =
        try {
            renderTypedNumericInitializer(expr, retNumKind, indent, selfTypeName)
        } finally {
            currentTailExpectsUnitResultOk = savedTailOk
        }
    val rendered =
        if (rustResultOkPayloadType(currentEnclosingReturnRenderedType) == "Unit") {
            coerceTailFmtUnitToResultOk(expr, rawRendered, selfTypeName)
        } else {
            rawRendered
        }
    if (rendered.isBlank()) return ""
    if (headerContext.isBatchCrateOutput && rendered.trimStart().startsWith("error(")) {
        return "$indent$rendered"
    }
    if (blockEndsWithUnconditionalDiverge(expr)) {
        return "$indent$rendered"
    }
    return "${indent}return $rendered"
}

private fun KotlinJ2KEmitter.blockEndsWithUnconditionalDiverge(expr: Expr): Boolean {
    val u = unwrapParenOrGroupExpr(expr)
    val block =
        when (u) {
            is Expr.BlockExpr -> u.block
            is Expr.Unsafe -> u.block
            else -> return false
        }
    val lastStmt = block.stmts.lastOrNull { it !is Stmt.ItemStmt } ?: return false
    if (lastStmt !is Stmt.ExprStmt) return false
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
        val unwrappedPat = pat?.let(this::unwrapTransparentPat)
        val unwrappedInit = unwrapParenOrGroupExpr(initExpr)
        if (unwrappedPat is Pat.Tuple && unwrappedInit is Expr.Tuple) {
            val patElems = unwrappedPat.elems.toList()
            val initElems = unwrappedInit.elems.toList()
            val count = minOf(patElems.size, initElems.size)
            for (idx in 0 until count) {
                val pIdent = (unwrapTransparentPat(patElems[idx]) as? Pat.Ident)?.ident?.toString()?.removePrefix("r#") ?: continue
                val ktName = scopeBuilder.resolveLocalKotlinName(pIdent) ?: rustValueIdentToKotlin(pIdent)
                val elemExpr = initElems[idx]
                val elemTy =
                    inferExprRenderedType(elemExpr, selfTypeName)
                        ?: inferExprNumericKind(elemExpr, selfTypeName)?.let(this::numericKindToPureTypeName)
                if (elemTy != null) {
                    currentTypedLocals = currentTypedLocals + (ktName to elemTy)
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
        val adjustedTy =
            if ((rhs.startsWith("MutableList(") || rhs.startsWith("mutableListOf(")) && inferredRenderedTy.startsWith("List<")) {
                "Mutable$inferredRenderedTy"
            } else {
                inferredRenderedTy
            }
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
    val isUnsuffixedIntLit =
        when (coreInit) {
            is Expr.Lit -> (coreInit.lit as? Lit.Int)?.value?.suffix()?.isEmpty() == true
            is Expr.Unary ->
                coreInit.op is UnOp.Neg &&
                    ((unwrapParenOrGroupExpr(coreInit.expr) as? Expr.Lit)?.lit as? Lit.Int)?.value?.suffix()?.isEmpty() == true
            else -> false
        }
    if (!isUnsuffixedIntLit) return null
    for (stmt in remainingStmts) {
        val expr = (stmt as? Stmt.ExprStmt)?.expr ?: continue
        when (expr) {
            is Expr.Assign -> {
                if (extractAssignedRootIdent(expr.left) == rawLocalIdent) {
                    inferExprNumericKind(expr.right, selfTypeName)?.let { return it }
                }
            }
            is Expr.Binary -> {
                val isCompoundAssign =
                    when (expr.op) {
                        is BinOp.BitOrAssign,
                        is BinOp.BitAndAssign,
                        is BinOp.BitXorAssign,
                        is BinOp.AddAssign,
                        is BinOp.SubAssign,
                        is BinOp.MulAssign,
                        is BinOp.DivAssign,
                        is BinOp.RemAssign,
                        -> true
                        else -> false
                    }
                if (isCompoundAssign && extractAssignedRootIdent(expr.left) == rawLocalIdent) {
                    inferExprNumericKind(expr.right, selfTypeName)?.let { return it }
                }
            }
            else -> {}
        }
    }
    val lastStmt = remainingStmts.lastOrNull() as? Stmt.ExprStmt
    if (lastStmt != null && lastStmt.semiToken == null) {
        val tailCore = unwrapParenOrGroupExpr(lastStmt.expr)
        if (tailCore is Expr.Path && tailCore.qself == null && tailCore.path.segments.size == 1) {
            val tailIdent =
                tailCore.path.segments
                    .first()
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
            if (tailIdent == rawLocalIdent) {
                currentEnclosingReturnRenderedType?.let(this::resolveNumericKind)?.let { return it }
            }
        }
    }
    return null
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
        val (patText, typeAnnotation) =
            renderLocalPattern(
                pat = pat,
                selfTypeName = selfTypeName,
                includeCtorInTupleStruct = false,
                bindToScope = true,
            )
        if (typeAnnotation != null && !patText.startsWith("(")) {
            currentTypedLocals = currentTypedLocals + (patText to typeAnnotation)
            if (typeAnnotation.endsWith("?")) {
                currentNullableLocals = currentNullableLocals + patText
            }
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
    renderAddressableLocal(local, indent, selfTypeName, remainingStmts)?.let { return it }
    renderScopedBorrowedPlaceLocal(local, indent, selfTypeName, remainingStmts)?.let { return it }
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
    val prevParseTarget = currentExpectedParseTargetType
    currentExpectedParseTargetType = explicitLocalAnnotation ?: inferredArrayTarget
    if (diverge == null && initExpr is Expr.BlockExpr) {
        val blockStmts = initExpr.block.stmts
        if (blockStmts.size == 1 && blockStmts[0] is Stmt.ExprStmt) {
            val singleRhs =
                try {
                    renderTypedNumericInitializer(
                        (blockStmts[0] as Stmt.ExprStmt).expr,
                        localNumKind,
                        indent,
                        selfTypeName,
                    )
                } finally {
                    currentExpectedParseTargetType = prevParseTarget
                }
            val (patText, typeAnnotation) =
                renderLocalPattern(
                    pat = pat,
                    selfTypeName = selfTypeName,
                    includeCtorInTupleStruct = false,
                    bindToScope = true,
                )
            recordLocalInferredType(patText, typeAnnotation, (blockStmts[0] as Stmt.ExprStmt).expr, singleRhs, selfTypeName, pat = pat)
            val typeSuffix = if (typeAnnotation != null && !patText.startsWith("(")) ": $typeAnnotation" else ""
            return "$indent$keyword $patText$typeSuffix = $singleRhs"
        }
        val blockBody =
            try {
                renderBlock(initExpr.block, indent, selfTypeName)
            } finally {
                currentExpectedParseTargetType = prevParseTarget
            }
        val (patText, typeAnnotation) =
            renderLocalPattern(
                pat = pat,
                selfTypeName = selfTypeName,
                includeCtorInTupleStruct = false,
                bindToScope = true,
            )
        recordLocalInferredType(patText, typeAnnotation, initExpr, blockBody, selfTypeName, pat = pat)
        val typeSuffix = if (typeAnnotation != null && !patText.startsWith("(")) ": $typeAnnotation" else ""
        return "$indent$keyword $patText$typeSuffix = run $blockBody"
    }
    var rhs =
        try {
            renderTypedNumericInitializer(initExpr, localNumKind, indent, selfTypeName)
        } finally {
            currentExpectedParseTargetType = prevParseTarget
        }
    val expectsMutableList = keyword == "var" || inferredArrayTarget?.startsWith("MutableList<") == true
    if (expectsMutableList && explicitLocalAnnotation == null && diverge == null) {
        val unwrappedInit = unwrapParenOrGroupExpr(initExpr)
        if (unwrappedInit is Expr.Repeat && rhs.startsWith("List(")) {
            rhs = "Mutable$rhs"
        } else if (unwrappedInit is Expr.Array && rhs.startsWith("listOf(")) {
            rhs = "mutableListOf(" + rhs.removePrefix("listOf(")
        }
    }
    if (pat is Pat.Wild) {
        if (isSideEffectFreeExpr(initExpr)) {
            return ""
        }
        return "$indent$rhs"
    }
    val unwrappedPat = unwrapTransparentPat(pat)
    if (unwrappedPat is Pat.Struct && diverge == null) {
        return renderStructDestructuringLocal(unwrappedPat, rhs, local.span(), indent, selfTypeName)
    }
    if (unwrappedPat is Pat.TupleStruct && diverge == null) {
        renderTupleStructDestructuringLocal(unwrappedPat, initExpr, rhs, local.span(), indent, selfTypeName)?.let { return it }
    }
    val divergeBody =
        if (diverge != null) {
            val divergeExpr = diverge.expr
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
        } else {
            null
        }
    val (patText, rawTypeAnnotation) =
        renderLocalPattern(
            pat = pat,
            selfTypeName = selfTypeName,
            includeCtorInTupleStruct = false,
            bindToScope = true,
        )
    var typeAnnotation = rawTypeAnnotation
    if (rhs == "mutableListOf()" && typeAnnotation == null) {
        val inferredElem =
            inferEmptyListElementTypeFromReturn(currentEnclosingReturnRenderedType)
                ?: inferEmptyListElementTypeFromRemainingStmts(pat, remainingStmts, selfTypeName)
        if (inferredElem != null) {
            rhs = "mutableListOf<$inferredElem>()"
        }
    } else if (rhs.startsWith("ArrayList(") && typeAnnotation == null) {
        val inferredElem =
            inferEmptyListElementTypeFromReturn(currentEnclosingReturnRenderedType)
                ?: inferEmptyListElementTypeFromRemainingStmts(pat, remainingStmts, selfTypeName)
        if (inferredElem != null) {
            rhs = "ArrayList<$inferredElem>(${rhs.removePrefix("ArrayList(")}"
        }
    } else if (typeAnnotation == null && !patText.startsWith("(")) {
        val unwrappedRet =
            currentEnclosingReturnRenderedType?.let { ret ->
                if (ret.startsWith("Result<") && ret.endsWith(">")) {
                    rustResultOkPayloadType(ret) ?: ret
                } else {
                    ret
                }
            }
        if (unwrappedRet != null && '<' in unwrappedRet) {
            val baseRet = unwrappedRet.substringBefore('<').trim()
            if (rhs == "$baseRet.new()" || rhs == "$baseRet.default()") {
                typeAnnotation = unwrappedRet
            }
        }
    }
    if (typeAnnotation == "RustAny" && inferExprRenderedType(initExpr, selfTypeName) != "RustAny" && !rhs.startsWith("RustAny.from(")) {
        usesRustAny = true
        rhs = "RustAny.from($rhs)"
    }
    if (rhs == "null" && typeAnnotation == null && !patText.startsWith("(")) {
        val inferredNullTy = inferExprRenderedType(initExpr, selfTypeName)?.trim()
        if (
            !inferredNullTy.isNullOrEmpty() &&
            inferredNullTy != "Nothing?" &&
            inferredNullTy != "Nothing" &&
            inferredNullTy != "Any?" &&
            inferredNullTy != "Any"
        ) {
            typeAnnotation = if (inferredNullTy.endsWith("?")) inferredNullTy else "$inferredNullTy?"
        }
    }
    if (rhs == "null" && typeAnnotation != null && !typeAnnotation.endsWith("?")) {
        typeAnnotation = "$typeAnnotation?"
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
            rhs.startsWith("ArrayList(") ||
            rhs.startsWith("ArrayList<") ||
            rhs.endsWith(".toMutableList()") ||
            typeAnnotation?.startsWith("MutableList<") == true
        ) {
            currentMutableListLocals = currentMutableListLocals + patText
            currentDequeLocals = currentDequeLocals - patText
        }
        if (typeAnnotation?.endsWith("?") == true || rhs == "null") {
            currentNullableLocals = currentNullableLocals + patText
        }
    }
    val typeSuffix = if (typeAnnotation != null && !patText.startsWith("(")) ": $typeAnnotation" else ""
    return if (divergeBody != null) {
        "$indent$keyword $patText$typeSuffix = $rhs ?: $divergeBody"
    } else if (headerContext.isBatchCrateOutput && rhs.contains('\n')) {
        "$indent$keyword $patText$typeSuffix =\n$indent    ${rhs.replace("\n", "\n    ")}"
    } else {
        "$indent$keyword $patText$typeSuffix = $rhs"
    }
}

internal fun KotlinJ2KEmitter.renderStructDestructuringLocal(
    structPat: Pat.Struct,
    rhs: String,
    span: Span,
    indent: String,
    selfTypeName: String?,
): String {
    val structTypeName =
        structPat.path.segments
            .last()
            ?.ident
            ?.toString()
            ?.let { if (it == "Self") selfTypeName?.substringBefore('<')?.trim() else rustTypeIdentToKotlin(it) }
    val isSimpleRhs =
        rhs.all { it.isLetterOrDigit() || it == '_' } ||
            (rhs.startsWith("this.") && rhs.removePrefix("this.").all { it.isLetterOrDigit() || it == '_' })
    val lines = mutableListOf<String>()
    val receiverVar =
        if (isSimpleRhs) {
            rhs
        } else {
            val tmp = scopeBuilder.bindLocal("_struct", "structVal", span).kotlinName
            lines += "${indent}val $tmp = $rhs"
            tmp
        }
    for (fp in structPat.fields.toList()) {
        val fieldProp = renderMemberName(fp.member)
        val subPat = unwrapTransparentPat(fp.pat)
        if (subPat is Pat.Ident && subPat.subpat == null) {
            val raw = subPat.ident.toString().removePrefix("r#")
            if (raw != "_") {
                val kw = if (subPat.mutability != null) "var" else "val"
                val assignCount = if (subPat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                val ktVar = scopeBuilder.bindLocal(raw, rustValueIdentToKotlin(raw), subPat.ident.span(), assignCount = assignCount).kotlinName
                if (structTypeName != null) {
                    val fieldTy =
                        inspector.structFieldSynTypesByType[structTypeName]?.get(fieldProp)?.let { renderType(it, selfTypeName) }
                            ?: headerContext.crateStructFieldTypes["$structTypeName.$fieldProp"]
                    if (fieldTy != null) {
                        currentTypedLocals = currentTypedLocals + (ktVar to fieldTy)
                        if (fieldTy.endsWith("?")) {
                            currentNullableLocals = currentNullableLocals + ktVar
                        }
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

internal fun KotlinJ2KEmitter.inferEmptyListElementTypeFromReturn(enclosingRet: String?): String? {
    if (enclosingRet == null) return null
    val unwrappedResult =
        if (enclosingRet.substringBefore('<').substringAfterLast('.').trim() == "Result" && enclosingRet.endsWith(">")) {
            rustResultOkPayloadType(enclosingRet) ?: enclosingRet
        } else {
            enclosingRet
        }
    return when {
        unwrappedResult.startsWith("List<") && unwrappedResult.endsWith(">") ->
            unwrappedResult.removePrefix("List<").removeSuffix(">").trim()
        unwrappedResult.startsWith("MutableList<") && unwrappedResult.endsWith(">") ->
            unwrappedResult.removePrefix("MutableList<").removeSuffix(">").trim()
        unwrappedResult.startsWith("ArrayList<") && unwrappedResult.endsWith(">") ->
            unwrappedResult.removePrefix("ArrayList<").removeSuffix(">").trim()
        unwrappedResult.startsWith("ArrayDeque<") && unwrappedResult.endsWith(">") ->
            unwrappedResult.removePrefix("ArrayDeque<").removeSuffix(">").trim()
        unwrappedResult.startsWith("IntoIter<") && unwrappedResult.endsWith(">") ->
            unwrappedResult.removePrefix("IntoIter<").removeSuffix(">").trim()
        else -> null
    }
}

private fun KotlinJ2KEmitter.inferEmptyListElementTypeFromRemainingStmts(
    pat: Pat,
    remainingStmts: List<Stmt>,
    selfTypeName: String?,
): String? {
    if (remainingStmts.isEmpty()) return null
    val identPat = unwrapTransparentPat(pat) as? Pat.Ident ?: return null
    if (identPat.subpat != null) return null
    val rawLocalName = identPat.ident.toString().removePrefix("r#")
    if (rawLocalName == "_") return null

    fun isTargetLocalPath(e: Expr): Boolean {
        val u = unwrapParenOrGroupExpr(e) as? Expr.Path ?: return false
        return u.qself == null &&
            u.path.segments.len() == 1 &&
            u.path.segments
                .first()
                ?.ident
                ?.toString()
                ?.removePrefix("r#") == rawLocalName
    }

    fun isConcreteElemType(ty: String?): Boolean =
        ty != null &&
            ty.isNotBlank() &&
            ty != "Any" &&
            ty != "Any?" &&
            ty != "Nothing" &&
            ty != "Unit" &&
            ty != "_" &&
            !ty.contains("RustAny")

    fun resolveStructFieldElem(typeName: String, fieldName: String? = null): String? {
        val synMap = inspector.structFieldSynTypesByType[typeName]
        val localRendered =
            if (fieldName != null) {
                synMap?.get(fieldName)?.let { renderType(it, selfTypeName) }
            } else {
                synMap?.values?.singleOrNull()?.let { renderType(it, selfTypeName) }
            }
        val rendered =
            localRendered
                ?: scopedAbiKeysForRenderedType(typeName).firstNotNullOfOrNull { key ->
                    if (fieldName != null) {
                        headerContext.crateStructFieldTypes["$key.$fieldName"]
                    } else {
                        headerContext.crateStructFieldTypes["$key.value"]
                            ?: headerContext.crateStructFieldTypes.entries
                                .singleOrNull { it.key.startsWith("$key.") }
                                ?.value
                    }
                }
                ?: if (fieldName != null) {
                    headerContext.crateStructFieldTypes["$typeName.$fieldName"]
                } else {
                    headerContext.crateStructFieldTypes["$typeName.value"]
                }
        return rendered?.let(::inferEmptyListElementTypeFromReturn)?.takeIf(::isConcreteElemType)
    }

    val ifLetBoundTypes = linkedMapOf<String, String>()
    var inferred: String? = null
    val visitor =
        object : Visit() {
            override fun visitExprLet(e: Expr.Let) {
                super.visitExprLet(e)
                val tsPat = unwrapTransparentPat(e.pat) as? Pat.TupleStruct
                val innerIdent =
                    if (tsPat != null && isOptionSomeTupleStructPat(tsPat)) {
                        (
                            tsPat.elems
                                .toList()
                                .singleOrNull()
                                ?.let(::unwrapTransparentPat) as? Pat.Ident
                        )?.takeIf { it.subpat == null }
                            ?.ident
                            ?.toString()
                            ?.removePrefix("r#")
                    } else {
                        null
                    }
                if (innerIdent != null) {
                    val scrutineeTy = inferExprRenderedType(e.expr, selfTypeName)
                    if (scrutineeTy != null && scrutineeTy.endsWith("?")) {
                        val unwrapped = scrutineeTy.removeSuffix("?").trim()
                        if (isConcreteElemType(unwrapped)) {
                            ifLetBoundTypes[innerIdent] = unwrapped
                        }
                    }
                }
            }

            override fun visitExprMethodCall(e: Expr.MethodCall) {
                if (inferred == null &&
                    e.method.toString().removePrefix("r#") == "push" &&
                    e.args.size == 1 &&
                    isTargetLocalPath(e.receiver)
                ) {
                    val arg = e.args[0]
                    val argIdent =
                        (unwrapParenOrGroupExpr(arg) as? Expr.Path)
                            ?.takeIf { it.qself == null && it.path.segments.len() == 1 }
                            ?.path
                            ?.segments
                            ?.first()
                            ?.ident
                            ?.toString()
                            ?.removePrefix("r#")
                    val candidate =
                        argIdent?.let { ifLetBoundTypes[it] }
                            ?: inferExprRenderedType(arg, selfTypeName)
                    if (isConcreteElemType(candidate)) {
                        inferred = candidate
                    }
                }
                super.visitExprMethodCall(e)
            }

            override fun visitExprCall(e: Expr.Call) {
                if (inferred == null && e.args.size == 1 && isTargetLocalPath(e.args[0])) {
                    val callPath = (unwrapParenOrGroupExpr(e.func) as? Expr.Path)?.takeIf { it.qself == null }
                    val segs =
                        callPath
                            ?.path
                            ?.segments
                            ?.toList()
                            .orEmpty()
                    val ownerRaw =
                        when {
                            segs.size == 1 && segs[0]
                                .ident
                                .toString()
                                .firstOrNull()
                                ?.isUpperCase() == true ->
                                segs[0].ident.toString()
                            segs.size >= 2 && segs[segs.lastIndex - 1]
                                .ident
                                .toString()
                                .firstOrNull()
                                ?.isUpperCase() == true ->
                                segs[segs.lastIndex - 1].ident.toString()
                            else -> null
                        }
                    val ownerKt =
                        when (ownerRaw) {
                            null -> null
                            "Self" -> selfTypeName?.substringBefore('<')?.trim()
                            else -> rustTypeIdentToKotlin(ownerRaw)
                        }
                    if (ownerKt != null) {
                        inferred = resolveStructFieldElem(ownerKt)
                    }
                }
                super.visitExprCall(e)
            }

            override fun visitExprStruct(e: Expr.Struct) {
                if (inferred == null) {
                    val rawCtor =
                        e.path.segments
                            .last()
                            ?.ident
                            ?.toString()
                    val ownerKt =
                        when (rawCtor) {
                            null -> null
                            "Self" -> selfTypeName?.substringBefore('<')?.trim()
                            else -> rustTypeIdentToKotlin(rawCtor)
                        }
                    if (ownerKt != null) {
                        val fieldCount = e.fields.len()
                        for (fv: FieldValue in e.fields.toList()) {
                            if (!isTargetLocalPath(fv.expr)) continue
                            val fieldName =
                                when (val m = fv.member) {
                                    is Member.Named -> rustFieldIdentToKotlin(m.ident.toString())
                                    is Member.Unnamed -> tupleFieldPropertyName(m.index.index.toInt(), fieldCount)
                                }
                            inferred = resolveStructFieldElem(ownerKt, fieldName)
                            if (inferred != null) break
                        }
                    }
                }
                super.visitExprStruct(e)
            }
        }
    for (stmt in remainingStmts) {
        if (inferred != null) break
        visitor.visitStmt(stmt)
    }
    return inferred
}

internal fun KotlinJ2KEmitter.isSideEffectFreeExpr(expr: Expr): Boolean =
    when (expr) {
        is Expr.Path, is Expr.Lit -> true
        is Expr.Reference -> isSideEffectFreeExpr(expr.expr)
        is Expr.Paren -> isSideEffectFreeExpr(expr.expr)
        is Expr.Group -> isSideEffectFreeExpr(expr.expr)
        is Expr.Field -> isSideEffectFreeExpr(expr.base)
        is Expr.Tuple -> expr.elems.toList().all { isSideEffectFreeExpr(it) }
        else -> false
    }

internal fun KotlinJ2KEmitter.isMutablePattern(pat: Pat): Boolean =
    when (pat) {
        is Pat.Ident -> pat.mutability != null
        is Pat.TypeAscription -> isMutablePattern(pat.pat)
        is Pat.Reference -> pat.mutability != null || isMutablePattern(pat.pat)
        is Pat.Guard -> isMutablePattern(pat.pat)
        is Pat.TupleStruct -> pat.elems.toList().any { isMutablePattern(it) }
        is Pat.Tuple -> pat.elems.toList().any { isMutablePattern(it) }
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
                    val assignCount = if (pat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                    scopeBuilder.bindLocal(raw, baseName, pat.ident.span(), assignCount = assignCount).kotlinName
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
                                                    val assignCount = if (p.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                                                    scopeBuilder.bindLocal(raw, baseName, p.ident.span(), assignCount = assignCount).kotlinName
                                                } else {
                                                    baseName
                                                }
                                            if (bindToScope && renderedElemTy != null) {
                                                currentTypedLocals = currentTypedLocals + (ktName to renderedElemTy)
                                                if (renderedElemTy.endsWith("?")) {
                                                    currentNullableLocals = currentNullableLocals + ktName
                                                }
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
    when (pat) {
        is Pat.Ident -> {
            val raw = pat.ident.toString().removePrefix("r#")
            if (raw != "_") {
                if (raw.first().isUpperCase()) {
                    out += rustTypeIdentToKotlin(raw)
                } else if (bindToScope) {
                    val assignCount = if (pat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                    out += scopeBuilder.bindLocal(raw, rustValueIdentToKotlin(raw), pat.ident.span(), assignCount = assignCount).kotlinName
                } else {
                    out += rustValueIdentToKotlin(raw)
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
                        val subOut = mutableListOf<String>()
                        collectPatternBindingNames(sub, selfTypeName, includeCtorInTupleStruct, bindToScope, subOut)
                        subOut
                    }
                if (perElem.any { it.isNotEmpty() }) {
                    for (subOut in perElem) {
                        if (subOut.isEmpty()) {
                            out += "_"
                        } else {
                            out.addAll(subOut)
                        }
                    }
                }
            } else {
                for (sub in elems) {
                    collectPatternBindingNames(sub, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
                }
            }
        }
        is Pat.TupleStruct -> {
            if (includeCtorInTupleStruct) {
                for (seg in pat.path.segments.toList()) {
                    val id = seg.ident.toString()
                    if (id !in IGNORED_PATH_PREFIXES) {
                        out += if (id.first().isUpperCase()) rustTypeIdentToKotlin(id) else rustValueIdentToKotlin(id)
                    }
                }
            }
            for (sub in pat.elems.toList()) {
                collectPatternBindingNames(sub, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
            }
        }
        is Pat.Struct -> {
            if (includeCtorInTupleStruct) {
                for (seg in pat.path.segments.toList()) {
                    val id = seg.ident.toString()
                    if (id !in IGNORED_PATH_PREFIXES) {
                        out += if (id.first().isUpperCase()) rustTypeIdentToKotlin(id) else rustValueIdentToKotlin(id)
                    }
                }
            }
            for (fp in pat.fields.toList()) {
                collectPatternBindingNames(fp.pat, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
            }
        }
        is Pat.Slice -> {
            for (sub in pat.elems.toList()) {
                collectPatternBindingNames(sub, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
            }
        }
        is Pat.Path -> {
            if (includeCtorInTupleStruct) {
                for (seg in pat.path.segments.toList()) {
                    val id = seg.ident.toString()
                    if (id !in IGNORED_PATH_PREFIXES) {
                        out += if (id.first().isUpperCase()) rustTypeIdentToKotlin(id) else rustValueIdentToKotlin(id)
                    }
                }
            }
        }
        else -> {}
    }
}

/** Keep a non-escaping pointer/reference alias attached to its source storage. */
private fun KotlinJ2KEmitter.renderScopedBorrowedPlaceLocal(
    local: Stmt.Local,
    indent: String,
    selfTypeName: String?,
    remainingStmts: List<Stmt>,
): String? {
    if (headerContext.rawAbiMode || local.init?.diverge != null) return null
    val binding = unwrapTransparentPat(local.pat) as? Pat.Ident ?: return null
    if (binding.mutability != null || binding.byRef != null || binding.subpat != null) return null
    val init = local.init?.expr ?: return null
    val projection = borrowedPlaceProjection(init, selfTypeName, addressArgument = true) ?: return null
    val raw = binding.ident.toString().removePrefix("r#")
    var dereferences = 0
    var escapes = false
    val visitor =
        object : Visit() {
            override fun visitExprUnary(e: Expr.Unary) {
                val operand = unwrapParenOrGroupExpr(e.expr) as? Expr.Path
                if (e.op is UnOp.Deref && operand != null && operand.qself == null && operand.path.isIdent(raw)) {
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
                if (raw in collectUsedRawIdentsInExpr(e.expr)) escapes = true
                super.visitExprReference(e)
            }
            override fun visitExprRawAddr(e: Expr.RawAddr) {
                if (raw in collectUsedRawIdentsInExpr(e.expr)) escapes = true
                super.visitExprRawAddr(e)
            }
            override fun visitMacro(m: Macro) {
                // Token-only macro arguments have not been resolved to expressions.
                // Do not classify a hidden pointer use as a dereference.
                if (containsWholeIdentifier(m.tokens.toString(), raw)) escapes = true
                super.visitMacro(m)
            }
        }
    for (stmt in remainingStmts) visitor.visitStmt(stmt)
    if (escapes) {
        val call = unwrapParenOrGroupExpr(init) as? Expr.MethodCall ?: return null
        val ownerType =
            inferExprRenderedType(call.receiver, selfTypeName)
                ?.removeSuffix("?")
                ?.substringBefore('<')
                ?.substringAfterLast('.') ?: return null
        val handleType = mutablePlaceAccessorType(ownerType, call.method.toString()) ?: return null
        val (name, _) = renderLocalPattern(local.pat, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
        currentTypedLocals = currentTypedLocals + (name to handleType)
        return "${indent}val $name = ${renderExpr(init, indent, selfTypeName)}"
    }
    if (dereferences == 0) return null
    val (receiver, fields) = projection
    val owner =
        "(${renderExpr(receiver, indent, selfTypeName)})" +
            fields.dropLast(1).joinToString(separator = "", transform = { ".$it" })
    val (name, _) = renderLocalPattern(local.pat, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
    currentBorrowedPlaceLocals = currentBorrowedPlaceLocals + name
    var valueType = inferExprRenderedType(receiver, selfTypeName)
    for (field in fields) {
        val ownerType = valueType?.removeSuffix("?")?.substringBefore('<')?.substringAfterLast('.')
        valueType = inspector.structFieldSynTypesByType[ownerType]?.get(field)?.let { renderType(it, ownerType) }
    }
    valueType?.let { currentTypedLocals = currentTypedLocals + (name to it) }
    // Infer V from the actual storage, preserving nullable pointees and generic
    // substitutions instead of guessing it from an erased raw-pointer type.
    return """${indent}var $name by run {
$indent    fun <V> place(read: () -> V, write: (V) -> Unit): kotlin.properties.ReadWriteProperty<Any?, V> =
$indent        object : kotlin.properties.ReadWriteProperty<Any?, V> {
$indent            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): V = read()
$indent            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: V) { write(value) }
$indent        }
$indent    val owner = $owner
$indent    place({ owner.${fields.last()} }, { owner.${fields.last()} = it })
$indent}"""
}

/** Address-taken scalar locals have one allocation for direct accesses and escaping borrows. */
private fun KotlinJ2KEmitter.renderAddressableLocal(
    local: Stmt.Local,
    indent: String,
    selfTypeName: String?,
    remainingStmts: List<Stmt>,
): String? {
    if (headerContext.rawAbiMode || local.init?.diverge != null) return null
    val binding = unwrapTransparentPat(local.pat) as? Pat.Ident ?: return null
    val raw = binding.ident.toString().removePrefix("r#")
    var rawAddressTaken = false
    var mutRefTaken = false
    var asPtrMethodCalled = false
    var totalPathMentions = 0
    var storageOnlyMentions = 0
    var mentionedInMacro = false
    var directReassigned = false
    var currentMatchScrutinee: Expr? = null

    fun isSelfIdentPath(expr: Expr?): Boolean {
        val place = expr?.let { unwrapParenOrGroupExpr(it) } as? Expr.Path
        return place?.qself == null && place?.path?.isIdent(raw) == true
    }

    fun tokenStreamContainsSelfIdent(stream: TokenStream): Boolean {
        for (tt in stream.clone().toList()) {
            when (tt) {
                is TokenTree.Ident -> if (tt.value.toString().removePrefix("r#") == raw) return true
                is TokenTree.Group -> if (tokenStreamContainsSelfIdent(tt.value.stream())) return true
                else -> {}
            }
        }
        return false
    }

    val visitor =
        object : Visit() {
            override fun visitExprPath(e: Expr.Path) {
                if (e.qself == null && e.path.isIdent(raw)) {
                    totalPathMentions++
                }
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
                val subj = unwrapParenOrGroupExpr(e.expr)
                if (subj is Expr.Reference && subj.mutability != null) {
                    currentMatchScrutinee = subj
                    if (isSelfIdentPath(subj.expr) && e.arms.any { matchArmMutatesTupleVariantBinding(it) }) {
                        directReassigned = true
                    }
                }
                super.visitExprMatch(e)
                currentMatchScrutinee = prevScrutinee
            }
            override fun visitExprAssign(e: Expr.Assign) {
                if (isSelfIdentPath(e.left)) {
                    directReassigned = true
                }
                super.visitExprAssign(e)
            }
            override fun visitExprBinary(e: Expr.Binary) {
                if (isCompoundAssignOp(e.op) && isSelfIdentPath(e.left)) {
                    directReassigned = true
                }
                super.visitExprBinary(e)
            }
            override fun visitExprReference(e: Expr.Reference) {
                if (e.mutability != null &&
                    e !== currentMatchScrutinee &&
                    isSelfIdentPath(e.expr)
                ) {
                    mutRefTaken = true
                    storageOnlyMentions++
                }
                super.visitExprReference(e)
            }
            override fun visitExprCast(e: Expr.Cast) {
                val reference = unwrapParenOrGroupExpr(e.expr) as? Expr.Reference
                if (e.ty is SynType.Ptr && isSelfIdentPath(reference?.expr)) {
                    rawAddressTaken = true
                    if (reference?.mutability == null) {
                        storageOnlyMentions++
                    }
                }
                super.visitExprCast(e)
            }
            override fun visitExprMethodCall(e: Expr.MethodCall) {
                val unwrappedRecv = unwrapParenOrGroupExpr(e.receiver)
                val target = (unwrappedRecv as? Expr.Reference)?.expr ?: unwrappedRecv
                if (e.method.toString() in setOf("as_ptr", "as_mut_ptr") && e.args.isEmpty() && isSelfIdentPath(target)) {
                    asPtrMethodCalled = true
                    if (unwrappedRecv !is Expr.Reference || unwrappedRecv.mutability == null) {
                        storageOnlyMentions++
                    }
                }
                super.visitExprMethodCall(e)
            }
            override fun visitExprCall(e: Expr.Call) {
                val calleeLeaf =
                    (e.func as? Expr.Path)
                        ?.path
                        ?.segments
                        ?.toList()
                        ?.lastOrNull()
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                val minVarargAddrIdx =
                    when (calleeLeaf) {
                        "ioctl", "fcntl" -> 2
                        "syscall", "prctl" -> 1
                        else -> Int.MAX_VALUE
                    }
                for ((argIdx, argExpr) in e.args.toList().withIndex()) {
                    val reference = unwrapParenOrGroupExpr(argExpr) as? Expr.Reference
                    if (reference != null && isSelfIdentPath(reference.expr)) {
                        val isPtrParam =
                            argIdx >= minVarargAddrIdx ||
                                resolveCalleeExprParamSynType(e.func, argIdx, selfTypeName) is SynType.Ptr
                        if (isPtrParam) {
                            rawAddressTaken = true
                            if (reference.mutability == null) {
                                storageOnlyMentions++
                            }
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
    for (stmt in remainingStmts) visitor.visitStmt(stmt)
    if (!rawAddressTaken && !mutRefTaken && !asPtrMethodCalled) return null
    val boundVarUsed = mentionedInMacro || totalPathMentions > storageOnlyMentions
    val boundKeyword = if (mentionedInMacro || directReassigned) "var" else "val"
    val init =
        local.init?.expr ?: if (rawAddressTaken) {
            error("Address-taken uninitialized local $raw needs definite-initialization storage lowering")
        } else {
            return null
        }
    val annotation = (local.pat as? Pat.TypeAscription)?.ty
    val literal = (unwrapParenOrGroupExpr(init) as? Expr.Lit)?.lit
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
    val sourceType = annotation ?: sourceInitializerType(init)
    if (asPtrMethodCalled && sourceType is SynType.Array) {
        rawAddressTaken = true
    }
    if (!rawAddressTaken && !mutRefTaken) return null
    if (!rawAddressTaken && literalCodec == null && (sourceType == null || !shouldUseAddressBackedMutableReference(sourceType, selfTypeName))) {
        return null
    }
    // An explicit Rust type wins over the initializer. Never infer pointer width from Long.
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    var arrayElementLayoutExpr: String? = null
    val arrayElemRendered = if (sourceType is SynType.Array) renderType(sourceType.elem, selfTypeName) else null
    val arrayPrimFactory =
        if (sourceType is SynType.Array && arrayElemRendered != null) {
            primitiveArrayLayoutFactory(sourceType.elem, arrayElemRendered, activeCfgProfile.pointerWidth.toLong())
        } else {
            null
        }
    var codec =
        when {
            sourceType is SynType.Array && arrayPrimFactory != null -> ""
            sourceType is SynType.Array -> {
                if (!rawAddressTaken) {
                    null
                } else {
                    val elemRendered = arrayElemRendered ?: renderType(sourceType.elem, selfTypeName)
                    val elemLayout =
                        sourcePointerLayout(sourceType.elem, selfTypeName) ?: run {
                            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.ObjectSlotLayout")
                            "ObjectSlotLayout<$elemRendered>()"
                        }
                    arrayElementLayoutExpr = elemLayout
                    ""
                }
            }
            sourceType != null -> sourcePointerLayout(sourceType, selfTypeName)
            else ->
                literalCodec?.let {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.PrimitiveLayouts")
                    "PrimitiveLayouts.$it"
                }
        }
    if (codec == null) {
        val allowAddressBackedMutRef =
            mutRefTaken && sourceType != null && shouldUseAddressBackedMutableReference(sourceType, selfTypeName)
        if (!rawAddressTaken && !allowAddressBackedMutRef) {
            return null
        }
        val fallbackRendered =
            sourceType?.let { renderType(it, selfTypeName) }
                ?: inferExprRenderedType(init, selfTypeName)
                ?: "Any?"
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.ObjectSlotLayout")
        codec = "ObjectSlotLayout<$fallbackRendered>()"
    }
    val previousTarget = currentExpectedParseTargetType
    val type =
        sourceType?.let { renderType(it, selfTypeName) } ?: when (literalCodec) {
            "BOOL" -> "Boolean"
            "I8" -> "Byte"
            "U8" -> "UByte"
            "I16" -> "Short"
            "U16" -> "UShort"
            "I32" -> "Int"
            "U32" -> "UInt"
            "I64" -> "Long"
            "U64" -> "ULong"
            else -> inferExprRenderedType(init, selfTypeName) ?: "Any?"
        }
    currentExpectedParseTargetType = type
    val rhs =
        try {
            renderTypedNumericInitializer(init, resolveNumericKind(type), indent, selfTypeName)
        } finally {
            currentExpectedParseTargetType = previousTarget
        }
    val (boundName, _) = renderLocalPattern(local.pat, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
    val storage = "localStorageL${local.span().start().line}C${local.span().start().column}"
    currentAddressableLocals = currentAddressableLocals + (boundName to storage)
    recordLocalInferredType(boundName, type, init, rhs, selfTypeName, null, pat = local.pat)
    if (sourceType is SynType.Array) {
        currentAddressableArrayElementTypes = currentAddressableArrayElementTypes + (boundName to sourceType.elem)
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.AddressableArrayLocal")
        val storageInit =
            if (arrayPrimFactory != null) {
                val factoryMethod = "from" + arrayPrimFactory.replaceFirstChar { it.uppercaseChar() }
                "AddressableArrayLocal.$factoryMethod($rhs)"
            } else {
                val elemRendered = arrayElemRendered ?: renderType(sourceType.elem, selfTypeName)
                val elemLayout =
                    arrayElementLayoutExpr ?: sourcePointerLayout(sourceType.elem, selfTypeName) ?: run {
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.ObjectSlotLayout")
                        "ObjectSlotLayout<$elemRendered>()"
                    }
                "AddressableArrayLocal.fromList($rhs, $elemLayout)"
            }
        val storageDecl = "${indent}val $storage = $storageInit"
        return if (boundVarUsed) "$storageDecl\n${indent}$boundKeyword $boundName by $storage" else storageDecl
    }
    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.AddressableLocal")
    val storageDecl = "${indent}val $storage = AddressableLocal($rhs, $codec)"
    return if (boundVarUsed) "$storageDecl\n${indent}$boundKeyword $boundName by $storage" else storageDecl
}

private fun KotlinJ2KEmitter.renderTupleStructDestructuringLocal(
    pat: Pat.TupleStruct,
    initExpr: Expr?,
    rhs: String,
    span: Span,
    indent: String,
    selfTypeName: String?,
): String? {
    if (isOptionSomeTupleStructPat(pat) || coreResultVariantMatchInfo(pat) != null) return null
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
    if (bindings.isEmpty()) {
        return if (initExpr != null && !isSideEffectFreeExpr(initExpr)) "$indent$rhsTrimmed" else ""
    }
    val rawPathLast =
        pat.path.segments
            .last()
            ?.ident
            ?.toString()
    val structTypeName =
        if (pat.path.segments.size == 1 && rawPathLast == "Self" && selfTypeName != null) {
            selfTypeName.substringBefore('<')
        } else {
            renderPathAsType(pat.path, selfTypeName).substringBefore('<')
        }
    val unwrappedInit = initExpr?.let { unwrapParenOrGroupExpr(it) }
    val isMutBorrow =
        when {
            unwrappedInit is Expr.Reference && unwrappedInit.mutability != null -> true
            unwrappedInit is Expr.Path &&
                unwrappedInit.qself == null &&
                unwrappedInit.path.isIdent("self") &&
                currentReceiverIsMutBorrow -> true
            else -> false
        }
    val lines = mutableListOf<String>()
    val receiverVar =
        if (rhsTrimmed == "this" || rhsTrimmed.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            rhsTrimmed
        } else {
            val tmp = scopeBuilder.bindLocal("_tupleStruct", "tupleStructVal", span).kotlinName
            lines += "${indent}val $tmp = $rhsTrimmed"
            tmp
        }
    val arity = elems.size
    for ((idx, identPat, isMutBinding) in bindings) {
        val raw = identPat.ident.toString().removePrefix("r#")
        val fieldProp = tupleFieldPropertyName(idx, arity)
        val fieldTy =
            inspector.structFieldSynTypesByType[structTypeName]?.get(fieldProp)?.let { renderType(it, selfTypeName) }
                ?: scopedAbiKeysForRenderedType(structTypeName).firstNotNullOfOrNull {
                    headerContext.crateStructFieldTypes["$it.$fieldProp"]
                }
                ?: headerContext.crateStructFieldTypes["$structTypeName.$fieldProp"]
        val assignCount = if (isMutBorrow || isMutBinding) AssignCount.Any else AssignCount.AtMostOnce
        val ktVar = scopeBuilder.bindLocal(raw, rustValueIdentToKotlin(raw), identPat.ident.span(), assignCount = assignCount).kotlinName
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
            lines += "${indent}var $ktVar by run {"
            lines += "$indent    fun <V> place(read: () -> V, write: (V) -> Unit): kotlin.properties.ReadWriteProperty<Any?, V> ="
            lines += "$indent        object : kotlin.properties.ReadWriteProperty<Any?, V> {"
            lines += "$indent            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): V = read()"
            lines += "$indent            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: V) = write(value)"
            lines += "$indent        }"
            lines += "$indent    place({ $receiverVar.$fieldProp }, { $receiverVar.$fieldProp = it })"
            lines += "$indent}"
            currentBorrowedPlaceLocals = currentBorrowedPlaceLocals + ktVar
        } else {
            val kw = if (isMutBinding) "var" else "val"
            lines += "$indent$kw $ktVar = $receiverVar.$fieldProp"
        }
    }
    return lines.joinToString("\n")
}

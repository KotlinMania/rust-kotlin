package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.UnOp

private fun <V> KotlinJ2KEmitter.lookupScopedTraitMetadata(
    ktTrait: String,
    traitPath: Path?,
    localMap: Map<String, V>,
    crateMap: Map<String, V>,
    acceptDirectCrateValue: (V) -> Boolean = { true },
): V? {
    localMap[ktTrait]?.let { return it }
    if (crateMap.isEmpty()) return null
    val rawSegs = traitPath?.cleanIdentStrings().orEmpty()
    if (rawSegs.size >= 2) {
        val subHint =
            rawSegs
                .dropLast(1)
                .filterNot { it == "crate" || it == "self" || it == "super" }
                .joinToString(".") { rustModuleIdentToKotlin(it.replace('-', '_')) }
        if (subHint.isNotEmpty()) {
            crateMap["$subHint.$ktTrait"]?.let { return it }
        }
    }
    for (k in scopedAbiKeysForRenderedType(ktTrait)) {
        crateMap[k]?.let { return it }
    }
    return crateMap[ktTrait]?.takeIf(acceptDirectCrateValue)
}

internal fun KotlinJ2KEmitter.lookupDeclaredTraitAst(ktTrait: String, traitPath: Path? = null): Item.Trait? =
    lookupScopedTraitMetadata(
        ktTrait = ktTrait,
        traitPath = traitPath,
        localMap = inspector.declaredTraitsByName,
        crateMap = CrateTraitAstRegistry.lookup(headerContext.crateTraitAstRegistryKey),
    )

internal fun KotlinJ2KEmitter.isSelfParameterizedTraitName(ktTrait: String): Boolean {
    if (ktTrait in inspector.declaredTraitsByName) {
        return ktTrait in inspector.selfParameterizedTraits
    }
    if (ktTrait in inspector.declaredTypeNames) return false
    return ktTrait in inspector.selfParameterizedTraits ||
        ktTrait in headerContext.crateSelfParameterizedTraits ||
        ktTrait in KNOWN_SELF_PARAMETERIZED_TRAITS
}

private val CORE_OPS_OUTPUT_ASSOC_TRAITS =
    wordSet("Add Sub Mul Div Rem BitAnd BitOr BitXor Shl Shr Neg Not")

private val STDLIB_DEFAULTLESS_ASSOC_TYPES: Map<String, List<String>> =
    mapOf(
        "Iterator" to listOf("Item"),
        "IntoIterator" to listOf("Item", "IntoIter"),
        "Fn" to listOf("Output"),
        "FnMut" to listOf("Output"),
        "FnOnce" to listOf("Output"),
        "Try" to listOf("Output", "Residual"),
        "Deref" to listOf("Target"),
        "SliceIndex" to listOf("Output"),
        "Future" to listOf("Output"),
        "IntoFuture" to listOf("Output", "IntoFuture"),
        "FromStr" to listOf("Err"),
        "TryFrom" to listOf("Error"),
        "TryInto" to listOf("Error"),
        "Pattern" to listOf("Searcher"),
        "BuildHasher" to listOf("Hasher"),
        "DoubleEndedIterator" to listOf("Item"),
        "ExactSizeIterator" to listOf("Item"),
        "FusedIterator" to listOf("Item"),
        "AsyncIterator" to listOf("Item"),
    )

internal fun KotlinJ2KEmitter.lookupTraitDefaultlessAssocTypes(ktTrait: String, traitPath: Path? = null): List<String> {
    lookupScopedTraitMetadata(
        ktTrait = ktTrait,
        traitPath = traitPath,
        localMap = inspector.traitDefaultlessAssocTypes,
        crateMap = headerContext.crateTraitDefaultlessAssocTypes,
        acceptDirectCrateValue = List<String>::isNotEmpty,
    )?.let { return it }
    if (ktTrait !in inspector.declaredTypeNames) {
        if (ktTrait in CORE_OPS_OUTPUT_ASSOC_TRAITS) return listOf("Output")
        STDLIB_DEFAULTLESS_ASSOC_TYPES[ktTrait]?.let { return it }
    }
    return emptyList()
}

internal fun KotlinJ2KEmitter.lookupTraitResultMethodNames(ktTrait: String, traitPath: Path? = null): Set<String> {
    inspector.traitResultMethods[ktTrait]?.let { return it }
    return lookupScopedTraitMetadata(
        ktTrait = ktTrait,
        traitPath = traitPath,
        localMap = emptyMap(),
        crateMap = headerContext.crateTraitResultMethods,
    )?.toSet().orEmpty()
}

internal fun KotlinJ2KEmitter.isFmtResultSynType(ty: SynType?): Boolean {
    val rawSegs = ty?.let(::unwrapSynType)?.unqualifiedPath()?.cleanIdentStrings() ?: return false
    return rawSegs.isNotEmpty() && isStdFmtResultSegments(rewriteCrateRootAliasAndReexportSegments(rawSegs))
}

/** Resolve two Result payload types, expanding 1-arg `io::Result<T>` and 0-arg `fmt::Result` aliases when applicable. */
internal fun KotlinJ2KEmitter.resolveEnclosingResultTypeArguments(rendered: String?): Pair<String, String>? {
    rustResultTypeArguments(rendered)?.let { return it }
    val type = rendered?.trim()?.removeSuffix("?")?.trim() ?: return null
    val base = type.substringBefore('<').substringAfterLast('.').trim()
    if (base != "Result" && base != "RustResult") return null
    if ('<' in type && type.endsWith('>')) {
        val singleOk = type.substringAfter('<').dropLast(1).trim()
        if (singleOk.isEmpty()) return null
        val aliasTarget = inspector.typeAliasTargets["Result"]?.let { renderType(it, null) }
        val aliasPair = rustResultTypeArguments(aliasTarget)
        if (aliasPair != null) {
            return singleOk to aliasPair.second
        }
        val importsIoResult =
            importedSimpleNameToPkg["Result"]?.let { it.endsWith(".io") || it.endsWith(".io.error") } == true ||
                extraTypePathImports.any { (".io." in it || it.endsWith(".io.Result")) && it.endsWith(".Result") } ||
                inspector.allUseItems.any { useItem ->
                    val leaves = mutableListOf<UseLeaf>()
                    collectUseLeaves(useItem.tree, emptyList(), leaves)
                    leaves.any { leaf ->
                        leaf.alias == null &&
                            !leaf.isSelfImport &&
                            leaf.segments.lastOrNull()?.removePrefix("r#") == "Result" &&
                            leaf.segments.dropLast(1).any { it.removePrefix("r#") == "io" }
                    }
                }
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        val errName =
            when {
                importsIoResult ->
                    if (headerContext.packageName == "$coreNs.io.error") "Error" else "$coreNs.io.error.Error"
                "Error" in importedSimpleNameToPkg || "Error" in inspector.declaredTypeNames -> "Error"
                else -> return null
            }
        return singleOk to errName
    }
    if (type == "Result") {
        val aliasTarget = inspector.typeAliasTargets["Result"]?.let { renderType(it, null) }
        rustResultTypeArguments(aliasTarget)?.let { return it }
        if ("Error" in importedSimpleNameToPkg || "Error" in inspector.declaredTypeNames) return "Unit" to "Error"
    }
    return null
}

/** Keep both Rust alternatives and their independent payload types when tagged Result is active. */
internal fun rustResultTypeArguments(rendered: String?): Pair<String, String>? {
    val type = rendered?.trim() ?: return null
    val base = type.substringBefore('<').substringAfterLast('.')
    if ((base != "Result" && base != "RustResult") || !type.endsWith('>')) return null
    val arguments = type.substringAfter('<').dropLast(1)
    val comma = findTopLevelGenericCommaIndex(arguments)
    if (comma < 0) return null
    return arguments.substring(0, comma).trim() to arguments.substring(comma + 1).trim()
}

internal fun rustResultOkPayloadType(rendered: String?): String? {
    val type = rendered?.trim() ?: return null
    val base = type.substringBefore('<').substringAfterLast('.')
    if ((base != "Result" && base != "RustResult") || !type.endsWith('>')) return null
    val arguments = type.substringAfter('<').dropLast(1).trim()
    if (arguments.isEmpty()) return null
    val comma = findTopLevelGenericCommaIndex(arguments)
    return if (comma < 0) arguments else arguments.substring(0, comma).trim()
}

internal fun KotlinJ2KEmitter.usesTaggedCoreResult(): Boolean =
    hasDeclaredResultEnum() || "core" in headerContext.crateNamespaces

internal fun KotlinJ2KEmitter.hasConflictingResultBinding(): Boolean {
    if (hasDeclaredResultEnum()) return false
    if ("Result" in inspector.declaredTypeNames || "Result" in inspector.typeAliasTargets) {
        return true
    }
    importedSimpleNameToPkg["Result"]?.let { pkg ->
        if (!pkg.endsWith(".result")) return true
    }
    for (useItem in inspector.allUseItems) {
        val leaves = mutableListOf<UseLeaf>()
        collectUseLeaves(useItem.tree, emptyList(), leaves)
        for (leaf in leaves) {
            val bound = (leaf.alias ?: leaf.segments.lastOrNull())?.removePrefix("r#")
            if (bound == "Result") {
                val cleanSegs = leaf.segments.map { it.removePrefix("r#") }
                val tail2 = cleanSegs.takeLast(2)
                if (tail2 != listOf("result", "Result") && cleanSegs != listOf("Result")) {
                    return true
                }
            }
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.sourceResultName(): String {
    if (!hasDeclaredResultEnum() && usesTaggedCoreResult()) usesSourceResult = true
    return if (hasConflictingResultBinding()) "RustResult" else "Result"
}

internal fun KotlinJ2KEmitter.isSourceResultConstructor(expression: Expr): Boolean {
    if (!usesTaggedCoreResult()) return false
    val call = unwrapParenOrGroupExpr(expression) as? Expr.Call ?: return false
    val path = (call.func as? Expr.Path)?.takeIf { it.qself == null }?.path ?: return false
    val parts = path.segments.toList().map { it.ident.toString() }
    return parts.lastOrNull() in setOf("Ok", "Err") && (parts.size == 1 || parts[parts.lastIndex - 1] == "Result") &&
        parts.last() !in inspector.declaredTypeNames
}

internal fun KotlinJ2KEmitter.renderSourceResultConstructor(call: Expr.Call, variant: String, indent: String, selfType: String?): String {
    val rawArgs = call.args.toList()
    val nonUnitArgs = rawArgs.filter { !(it is Expr.Tuple && it.elems.isEmpty()) }
    if (variant == "Ok" && nonUnitArgs.isEmpty() && currentEnclosingReturnRenderedType == "Unit") {
        return "Unit"
    }
    if (!usesTaggedCoreResult()) {
        if (variant == "Ok") {
            if (nonUnitArgs.isEmpty()) return "Result.success(Unit)"
            val enclosingRes =
                rustResultOkPayloadType(currentExpectedParseTargetType)
                    ?: rustResultOkPayloadType(currentEnclosingReturnRenderedType)
            val okKind = enclosingRes?.let(this::resolveNumericKind) ?: currentExpectedNumericKind
            val previousExpected = currentExpectedParseTargetType
            currentExpectedParseTargetType = enclosingRes
            val renderedOk =
                try {
                    renderTypedNumericInitializer(nonUnitArgs[0], okKind, indent, selfType)
                } finally {
                    currentExpectedParseTargetType = previousExpected
                }
            return "Result.success($renderedOk)"
        } else {
            val errVal = if (nonUnitArgs.isEmpty()) "Unit" else renderExpr(nonUnitArgs[0], indent, selfType)
            return "Result.failure($errVal)"
        }
    }
    val path = call.func as Expr.Path
    val explicit =
        (
            path.path.segments
                .last()
                ?.arguments as? PathArguments.AngleBracketed
        )?.args
            ?.toList()
            .orEmpty()
            .filterIsInstance<GenericArgument.TypeArg>()
            .map { renderType(it.type, selfType) }
    val expected =
        resolveEnclosingResultTypeArguments(currentExpectedParseTargetType)
            ?: resolveEnclosingResultTypeArguments(currentEnclosingReturnRenderedType)
    val typeArguments =
        when {
            explicit.size == 2 ->
                buildExplicitResultTypeArgsSuffix(
                    explicit[0].takeUnless { it.trim() == "_" } ?: expected?.first,
                    explicit[1].takeUnless { it.trim() == "_" } ?: expected?.second,
                )
            explicit.size == 1 ->
                if (variant == "Ok") {
                    buildExplicitResultTypeArgsSuffix(explicit[0].takeUnless { it.trim() == "_" } ?: expected?.first, expected?.second)
                } else {
                    buildExplicitResultTypeArgsSuffix(expected?.first, explicit[0].takeUnless { it.trim() == "_" } ?: expected?.second)
                }
            else -> buildExplicitResultTypeArgsSuffix(expected?.first, expected?.second)
        }
    val payload = rawArgs.singleOrNull()
    val expectedPayload = expected?.let { if (variant == "Ok") it.first else it.second }
    val previousExpected = currentExpectedParseTargetType
    currentExpectedParseTargetType = expectedPayload
    val value =
        try {
            if (payload == null || (payload is Expr.Tuple && payload.elems.isEmpty())) {
                "Unit"
            } else {
                renderTypedNumericInitializer(payload, expectedPayload?.let { resolveNumericKind(it) } ?: currentExpectedNumericKind, indent, selfType)
            }
        } finally {
            currentExpectedParseTargetType = previousExpected
        }
    return "${sourceResultName()}.$variant$typeArguments($value)"
}

internal fun renderTaggedTwoArmTry(
    scrutineeExpr: String,
    returnKeyword: String,
    sumTypeName: String,
    continueArmSimple: String,
    continueAccessor: String,
    earlyReturnArmSimple: String,
    earlyReturnAccessor: String,
    earlyReturnTypeArgs: String,
    castEarlyPayloadTo: String?,
    isUntypedScrutinee: Boolean,
): String {
    val payloadExpr =
        if (isUntypedScrutinee && castEarlyPayloadTo != null) {
            "(tryResult.$earlyReturnAccessor as $castEarlyPayloadTo)"
        } else {
            "tryResult.$earlyReturnAccessor"
        }
    val elseArm =
        if (isUntypedScrutinee) {
            "; else -> error(\"Unexpected $sumTypeName variant: \$tryResult\")"
        } else {
            ""
        }
    return "when (val tryResult = $scrutineeExpr) { is $sumTypeName.$continueArmSimple<*, *> -> tryResult.$continueAccessor; is $sumTypeName.$earlyReturnArmSimple<*, *> -> $returnKeyword $sumTypeName.$earlyReturnArmSimple$earlyReturnTypeArgs($payloadExpr)$elseArm }"
}

/** Lower Rust `?` propagation according to whether tagged core Result or Kotlin Result is active. */
internal fun KotlinJ2KEmitter.renderSourceResultTry(expression: String, returnKeyword: String, sourceType: String? = null): String {
    val cleanSrc = sourceType?.removeSuffix("?")?.trim()
    if (cleanSrc == "Unit" || cleanSrc == "StringBuilder" || cleanSrc == "Appendable") {
        return expression
    }
    if (!usesTaggedCoreResult()) {
        return "$expression.getOrElse { $returnKeyword Result.failure(it) }"
    }
    val enclosingPair =
        if (returnKeyword.startsWith("return")) {
            resolveEnclosingResultTypeArguments(currentEnclosingReturnRenderedType)
        } else {
            null
        }
    return renderTaggedTwoArmTry(
        scrutineeExpr = expression,
        returnKeyword = returnKeyword,
        sumTypeName = sourceResultName(),
        continueArmSimple = "Ok",
        continueAccessor = "okValue()",
        earlyReturnArmSimple = "Err",
        earlyReturnAccessor = "errValue()",
        earlyReturnTypeArgs = buildExplicitResultTypeArgsSuffix(enclosingPair?.first, enclosingPair?.second),
        castEarlyPayloadTo = enclosingPair?.second?.takeIf(::isUsableExplicitResultTypeArg),
        isUntypedScrutinee = sourceType != null && isUntypedResultScrutinee(sourceType),
    )
}

/** The typed derived-equality method preserves Rust's equality type constraints.
 * Kotlin's Any?.equals context would otherwise erase an Ok/Err constructor's
 * other generic parameter before the compiler can infer it from the left side.
 */
internal fun KotlinJ2KEmitter.renderSourceEquality(left: Expr, right: Expr, negate: Boolean, indent: String, selfType: String?): String {
    val leftType = inferExprRenderedType(left, selfType)
    val rightType = inferExprRenderedType(right, selfType)
    val resultEquality =
        usesTaggedCoreResult() && (
            isSourceResultConstructor(left) || isSourceResultConstructor(right) ||
                rustResultTypeArguments(leftType) != null || rustResultTypeArguments(rightType) != null
        )
    if (resultEquality) {
        val lhs =
            withExpectedTargetIfUnconstrained(left, rightType) {
                renderExpr(left, indent, selfType)
            }
        val rhs =
            withExpectedTargetIfUnconstrained(right, leftType) {
                renderExpr(right, indent, selfType)
            }
        sourceResultName()
        val equality = "($lhs).rustEquals($rhs)"
        return if (negate) "!$equality" else equality
    }
    val leftKind = inferExprNumericKind(left, selfType) ?: leftType?.let(this::resolveNumericKind)
    val rightKind = inferExprNumericKind(right, selfType) ?: rightType?.let(this::resolveNumericKind)
    val leftSomeArg = unwrapUnqualifiedOptionSomeArg(left)
    val rightSomeArg = unwrapUnqualifiedOptionSomeArg(right)
    val leftOptElemKind =
        leftType?.takeIf { it.endsWith("?") }?.removeSuffix("?")?.let(this::resolveNumericKind)
            ?: leftSomeArg?.let { inferExprNumericKind(it, selfType) }
    val rightOptElemKind =
        rightType?.takeIf { it.endsWith("?") }?.removeSuffix("?")?.let(this::resolveNumericKind)
            ?: rightSomeArg?.let { inferExprNumericKind(it, selfType) }
    fun renderEqualitySide(
        expr: Expr,
        ownKind: NumericKind?,
        ownSomeArg: Expr?,
        otherType: String?,
        otherKind: NumericKind?,
        otherOptElemKind: NumericKind?,
    ): String =
        when {
            ownKind == null && otherKind != null -> {
                val raw = withExpectedNumericKind(otherKind) { renderExpr(expr, indent, selfType) }
                if (raw != "null" && isNumericLiteralOperand(expr)) coerceRenderedNumericExpr(expr, raw, otherKind) else raw
            }
            ownSomeArg != null && inferExprNumericKind(ownSomeArg, selfType) == null && otherOptElemKind != null -> {
                val raw =
                    withExpectedNumericKind(otherOptElemKind) {
                        withExpectedTargetIfUnconstrained(expr, otherType) { renderExpr(expr, indent, selfType) }
                    }
                if (raw != "null" && isNumericLiteralOperand(ownSomeArg)) {
                    coerceRenderedNumericExpr(ownSomeArg, raw, otherOptElemKind)
                } else {
                    raw
                }
            }
            else -> withExpectedTargetIfUnconstrained(expr, otherType) { renderExpr(expr, indent, selfType) }
        }

    val lhs = renderEqualitySide(left, leftKind, leftSomeArg, rightType, rightKind, rightOptElemKind)
    val rhs = renderEqualitySide(right, rightKind, rightSomeArg, leftType, leftKind, leftOptElemKind)
    val cleanLeftType = leftType?.removeSuffix("?")?.trim()
    val cleanRightType = rightType?.removeSuffix("?")?.trim()
    if (cleanLeftType != null && cleanLeftType == cleanRightType && cleanLeftType in PRIMITIVE_ARRAY_TYPES) {
        val eq = "($lhs).contentEquals($rhs)"
        return if (negate) "!$eq" else eq
    }
    return "$lhs ${if (negate) "!=" else "=="} $rhs"
}

private fun KotlinJ2KEmitter.isNumericLiteralOperand(expression: Expr): Boolean {
    val core = unwrapParenOrGroupExpr(expression)
    if (core is Expr.Lit) {
        return core.lit is Lit.Int || core.lit is Lit.Float
    }
    if (core is Expr.Unary && (core.op is UnOp.Neg || core.op is UnOp.NotOp)) {
        val inner = unwrapParenOrGroupExpr(core.expr)
        return inner is Expr.Lit && (inner.lit is Lit.Int || inner.lit is Lit.Float)
    }
    return false
}

private inline fun KotlinJ2KEmitter.withExpectedNumericKind(
    kind: NumericKind?,
    render: () -> String,
): String {
    val savedKind = currentExpectedNumericKind
    currentExpectedNumericKind = kind
    return try {
        render()
    } finally {
        currentExpectedNumericKind = savedKind
    }
}

internal fun KotlinJ2KEmitter.unwrapUnqualifiedOptionSomeArg(expression: Expr): Expr? {
    if ("Some" in inspector.declaredTypeNames || hasDeclaredOptionEnum()) return null
    val call = unwrapParenOrGroupExpr(expression) as? Expr.Call ?: return null
    val path = (call.func as? Expr.Path)?.takeIf { it.qself == null }?.path ?: return null
    val parts = path.segments.toList().map { it.ident.toString().removePrefix("r#") }
    if (parts != listOf("Some") &&
        parts != listOf("Option", "Some") &&
        parts != listOf("core", "option", "Option", "Some") &&
        parts != listOf("std", "option", "Option", "Some")
    ) {
        return null
    }
    return call.args.toList().singleOrNull { !(it is Expr.Tuple && it.elems.isEmpty()) }
}

private inline fun KotlinJ2KEmitter.withExpectedTargetIfUnconstrained(
    expr: Expr,
    otherRenderedType: String?,
    render: () -> String,
): String {
    if (otherRenderedType == null) return render()
    val core = unwrapParenOrGroupExpr(expr)
    if (core !is Expr.Array && core !is Expr.Repeat && !isSourceResultConstructor(core) && unwrapUnqualifiedOptionSomeArg(core) == null) {
        return render()
    }
    val savedTarget = currentExpectedParseTargetType
    currentExpectedParseTargetType = otherRenderedType
    return try {
        render()
    } finally {
        currentExpectedParseTargetType = savedTarget
    }
}

private val DEBUG_BUILDER_FACTORIES: Set<String> =
    setOf("debug_struct", "debug_tuple", "debug_list", "debug_set", "debug_map")

private val DEBUG_BUILDER_TYPES: Set<String> =
    setOf("DebugStruct", "DebugTuple", "DebugList", "DebugSet", "DebugMap")

private fun KotlinJ2KEmitter.isSynthesizedDebugFinishCall(call: Expr.MethodCall, selfTypeName: String?): Boolean {
    val rawMethod = call.method.toString().removePrefix("r#")
    if (rawMethod != "finish" && rawMethod != "finish_non_exhaustive") return false
    var recv = unwrapParenOrGroupExpr(call.receiver)
    while (recv is Expr.MethodCall) {
        if (recv.method.toString().removePrefix("r#") in DEBUG_BUILDER_FACTORIES) {
            return true
        }
        recv = unwrapParenOrGroupExpr(recv.receiver)
    }
    val recvTypeBase =
        inferExprRenderedType(call.receiver, selfTypeName)
            ?.removeSuffix("?")
            ?.substringBefore('<')
            ?.substringAfterLast('.')
            ?.trim()
    return recvTypeBase in DEBUG_BUILDER_TYPES
}

private fun KotlinJ2KEmitter.singleUnqualifiedCleanIdent(expr: Expr): String {
    val path = unwrapParenOrGroupExpr(expr).unqualifiedPath() ?: return ""
    return path.singleSegment()?.cleanIdent().orEmpty()
}

/** True when a Rust expression renders as Unit, Appendable, or StringBuilder in fmt::Write/fmt::Result positions. */
internal fun KotlinJ2KEmitter.isFmtWriteOrUnitExpr(expr: Expr, selfTypeName: String?): Boolean {
    val core = unwrapParenOrGroupExpr(expr)
    if (core is Expr.Try) {
        return isFmtWriteOrUnitExpr(core.expr, selfTypeName)
    }
    if (core is Expr.BlockExpr && core.block.stmts.isEmpty()) {
        return true
    }
    if (core is Expr.MethodCall) {
        val rawMethod = core.method.toString().removePrefix("r#")
        if (rawMethod in setOf("write_str", "write_char", "pad") && core.args.size == 1 && core.turbofish == null) {
            return true
        }
        if (rawMethod == "write_fmt" && core.args.size == 1 && core.turbofish == null) {
            if (isFmtStringBuilderReceiver(core.receiver, singleUnqualifiedCleanIdent(core.receiver), selfTypeName)) {
                return true
            }
        }
        if (rawMethod == "fmt" && core.args.size == 1 && core.turbofish == null) {
            val isStrRecv =
                unwrapParenOrGroupExpr(core.receiver).let { it is Expr.Lit && it.lit is Lit.Str } ||
                    inferExprRenderedType(core.receiver, selfTypeName)?.removeSuffix("?")?.trim() == "String"
            if (isStrRecv) {
                val arg0 = core.args[0]
                if (isFmtStringBuilderReceiver(arg0, singleUnqualifiedCleanIdent(arg0), selfTypeName)) {
                    return true
                }
            }
        }
        if (isSynthesizedDebugFinishCall(core, selfTypeName)) {
            return true
        }
    }
    if (core is Expr.Macro) {
        if (core.mac.path.lastCleanIdent() in setOf("write", "writeln")) {
            return true
        }
    }
    val callPath = (core as? Expr.Call)?.func?.unqualifiedPath()
    if (core is Expr.Call && callPath != null) {
        val segs = callPath.cleanIdentStrings()
        val tailPair = if (segs.size >= 2) "${segs[segs.lastIndex - 1]}.${segs.last()}" else segs.lastOrNull()
        if (tailPair != null && tailPair.endsWith(".fmt") && tailPair.removeSuffix(".fmt") in FMT_TRAIT_NAMES &&
            core.args.size == 2
        ) {
            return true
        }
        if (tailPair == "Write.write_fmt" && core.args.size == 2) {
            val targetArg = core.args[0]
            if (isFmtStringBuilderReceiver(targetArg, singleUnqualifiedCleanIdent(targetArg), selfTypeName)) {
                return true
            }
        }
    }
    val inferred = inferExprRenderedType(core, selfTypeName)?.removeSuffix("?")?.trim()
    return inferred in setOf("Unit", "Appendable", "StringBuilder")
}

internal fun KotlinJ2KEmitter.enclosingUnitResultOkExpr(): String? {
    val enclosingPair = resolveEnclosingResultTypeArguments(currentEnclosingReturnRenderedType)
    val okPayload = enclosingPair?.first ?: rustResultOkPayloadType(currentEnclosingReturnRenderedType)
    if (okPayload != "Unit") return null
    return if (usesTaggedCoreResult()) {
        val okTypeArgs = buildExplicitResultTypeArgsSuffix("Unit", enclosingPair?.second)
        "${sourceResultName()}.Ok$okTypeArgs(Unit)"
    } else {
        "Result.success(Unit)"
    }
}

/** Coerce a Unit/Appendable/StringBuilder tail expression to Result.Ok(Unit) when returning Result<Unit, E>. */
internal fun KotlinJ2KEmitter.coerceTailFmtUnitToResultOk(
    expr: Expr,
    rendered: String,
    selfTypeName: String?,
): String {
    val okVal = enclosingUnitResultOkExpr() ?: return rendered
    if (isSourceResultConstructor(expr)) {
        return rendered
    }
    val core = unwrapParenOrGroupExpr(expr)
    if (core is Expr.Return || core is Expr.Break || core is Expr.Continue || core is Expr.Match || core is Expr.If) {
        return rendered
    }
    if (!isFmtWriteOrUnitExpr(expr, selfTypeName)) {
        return rendered
    }
    val clean = rendered.trim()
    return when {
        clean == "Unit" || clean == "{}" -> okVal
        clean.contains('\n') -> "run {\n$rendered\n$okVal\n}"
        else -> "run { $clean; $okVal }"
    }
}

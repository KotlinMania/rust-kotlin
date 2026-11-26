package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem

private val HOMOGENEOUS_BINARY_SIMD_INTRINSICS: Set<String>
    get() = activeRules().words("homogeneousBinarySimdIntrinsics")

private val COMPANION_CONVERT_PRIMITIVES: Set<String>
    get() = activeRules().unionWords("integerCompanionMinMaxOwners", "companionConvertPrimitivesExtra")

private val KNOWN_STD_UFCS_RECEIVER_METHODS: Set<String>
    get() = activeRules().words("knownStdUfcsReceiverMethods")

private fun isFmtTraitMethodCallee(calleeBase: String): Boolean {
    if (!calleeBase.endsWith(".fmt")) return false
    val owner = calleeBase.removeSuffix(".fmt").substringAfterLast('.')
    return owner in FMT_TRAIT_NAMES
}

/**
 * Shared state and argument/callee lowering operations for a single `syn.Expr.Call` emission.
 */
internal class CallLoweringContext(
    val emitter: KotlinJ2KEmitter,
    val expr: Expr.Call,
    val indent: String,
    val selfTypeName: String?,
) {
    val rawArgs: List<Expr> = expr.args.toList()
    val nonUnitArgs: List<Expr> = rawArgs.filterNot { it is Expr.Tuple && it.elems.isEmpty() }
    val plainFuncPath: Expr.Path? = (expr.func as? Expr.Path)?.takeIf { it.qself == null }
    val rawFuncPathSegs: List<String> =
        run {
            val path = plainFuncPath?.path ?: return@run emptyList()
            path.segments.toList().map { it.ident.toString().removePrefix("r#") }
        }
    var callee: String = ""
    var calleeBase: String = ""

    val turbofishSuffix: String
        get() = if ('<' in callee) "<${callee.substringAfter('<')}" else ""

    fun renderArg(idx: Int): String = emitter.renderExpr(nonUnitArgs[idx], indent, selfTypeName)

    fun renderNeutralArg(argExpr: Expr): String {
        val savedKind = emitter.currentExpectedNumericKind
        val savedParseTarget = emitter.currentExpectedParseTargetType
        emitter.currentExpectedNumericKind = null
        emitter.currentExpectedParseTargetType = null
        return try {
            emitter.renderExpr(argExpr, indent, selfTypeName)
        } finally {
            emitter.currentExpectedNumericKind = savedKind
            emitter.currentExpectedParseTargetType = savedParseTarget
        }
    }

    fun matchesCalleeOrSuffix(target: String): Boolean =
        calleeBase == target || calleeBase.endsWith(".$target")

    fun renderStandardCall(): String {
        emitter.renderStaticPrimitiveCall(expr, nonUnitArgs, indent, selfTypeName)?.let { return it }
        val plainCalleeBase = callee.substringBefore('<')
        registerPrimitiveConvertImportIfNeeded(plainCalleeBase)
        val calleeParamNumericKinds = resolveCalleeParamNumericKinds(plainCalleeBase)
        val subslicePreStmts = mutableListOf<String>()
        val subslicePostStmts = mutableListOf<String>()
        val savedParseTarget = emitter.currentExpectedParseTargetType
        val renderedArgList = renderCallArguments(plainCalleeBase, calleeParamNumericKinds, subslicePreStmts, subslicePostStmts)
        val args = renderedArgList.joinToString(", ")

        tryRenderSelfOrUfcsTraitCall(plainCalleeBase, renderedArgList)?.let { return it }

        val forwardedWitnesses = resolveForwardedSynWitnesses(renderedArgList)
        val funcLastSeg = plainFuncPath?.path?.segments?.last()
        val explicitGenericArgs = (funcLastSeg?.arguments as? PathArguments.AngleBracketed)?.args?.toList().orEmpty()
        val constValueArgs = resolveConstGenericValueArgs(plainCalleeBase, explicitGenericArgs)
        val layoutArgs = resolveTransparentFieldLayoutArgs(plainCalleeBase)
        val explicitArgs =
            (
                listOf(args).filter(String::isNotEmpty) +
                    constValueArgs +
                    layoutArgs +
                    CoreIntrinsicLowering.coreIntrinsicCallArguments(expr, selfTypeName, emitter) +
                    emitter.genericFactoryCallArguments(expr, selfTypeName)
            ).joinToString(", ")
        val fullArgs =
            when {
                forwardedWitnesses.isEmpty() -> explicitArgs
                explicitArgs.isEmpty() -> forwardedWitnesses.joinToString(", ")
                else -> "$explicitArgs, ${forwardedWitnesses.joinToString(", ")}"
            }
        return renderFinalCallWithAdapters(plainCalleeBase, explicitGenericArgs, fullArgs, savedParseTarget, subslicePreStmts, subslicePostStmts)
    }

    private fun registerPrimitiveConvertImportIfNeeded(plainCalleeBase: String) {
        if (nonUnitArgs.size != 1) return
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
        val hasCore =
            "core" in emitter.headerContext.crateNamespaces ||
                emitter.headerContext.packageName?.startsWith(coreNs) == true
        if (!hasCore) return
        val cbParts = plainCalleeBase.split('.')
        val matchedPrimParts =
            when {
                cbParts.size == 2 -> cbParts[0] to cbParts[1]
                cbParts.size == 3 && cbParts[0] == "kotlin" -> cbParts[1] to cbParts[2]
                else -> null
            } ?: return
        if (matchedPrimParts.first in COMPANION_CONVERT_PRIMITIVES &&
            matchedPrimParts.second in setOf("from", "tryFrom") &&
            (cbParts.size == 3 || matchedPrimParts.first !in emitter.inspector.declaredTypeNames) &&
            emitter.headerContext.packageName != "$coreNs.convert.num"
        ) {
            CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.convert.num.${matchedPrimParts.second}")
        }
    }

    private fun resolveCalleeParamNumericKinds(plainCalleeBase: String): List<NumericKind?>? {
        val calleeLookupKeys =
            buildList {
                for (cand in listOf(callee, plainCalleeBase).distinct()) {
                    add(cand)
                    addAll(emitter.scopedAbiKeysForRenderedType(cand))
                    val nsPrefix = emitter.currentNamespacePrefix()
                    if (nsPrefix != null && cand.startsWith("$nsPrefix.")) {
                        add(cand.removePrefix("$nsPrefix."))
                    }
                    val parts = cand.split('.')
                    if (parts.size >= 2) {
                        add("${parts[parts.size - 2]}.${parts.last()}")
                    }
                    if (parts.isNotEmpty()) {
                        val curSub = emitter.currentSubpackageString()
                        if (curSub.isNotEmpty()) {
                            add("$curSub.$cand")
                        }
                    }
                }
            }.distinct()
        val calleeParts = plainCalleeBase.split('.')
        val leadingPkgSegs = calleeParts.dropLast(1).takeWhile { seg -> seg.firstOrNull()?.isLowerCase() == true }
        val resolvedCalleePkg =
            if (leadingPkgSegs.isNotEmpty()) {
                leadingPkgSegs.joinToString(".")
            } else {
                emitter.importedSimpleNameToPkg[calleeParts.first()]
            }
        val selfNsPrefix = emitter.currentNamespacePrefix()
        val isExternalCalleePkg =
            !resolvedCalleePkg.isNullOrEmpty() &&
                (selfNsPrefix == null || (resolvedCalleePkg != selfNsPrefix && !resolvedCalleePkg.startsWith("$selfNsPrefix.")))
        if (isExternalCalleePkg) return null
        return calleeLookupKeys.firstNotNullOfOrNull { k ->
            emitter.currentFunctionParamNumericKinds["$k#${rawArgs.size}"]
                ?: emitter.currentFunctionParamNumericKinds[k]?.takeIf { it.size == rawArgs.size }
        }
    }

    private fun renderCallArguments(
        plainCalleeBase: String,
        calleeParamNumericKinds: List<NumericKind?>?,
        subslicePreStmts: MutableList<String>,
        subslicePostStmts: MutableList<String>,
    ): List<String> {
        val savedKind = emitter.currentExpectedNumericKind
        val savedParseTarget = emitter.currentExpectedParseTargetType
        emitter.currentExpectedNumericKind = null
        emitter.currentExpectedParseTargetType = null
        emitter.callArgumentNestingDepth++
        val unwrappedFuncPath = (emitter.unwrapParenOrGroupExpr(expr.func) as? Expr.Path)?.takeIf { it.qself == null }
        val rawCallSegs =
            run {
                val path = unwrappedFuncPath?.path ?: return@run emptyList()
                path.segments.toList().map { it.ident.toString().removePrefix("r#") }
            }
        return try {
            rawArgs.mapIndexed { argIdx, argExpr ->
                val subsliceSpec =
                    emitter.tryExtractMutableArraySubsliceArg(
                        argExpr = argExpr,
                        argIndex = argIdx,
                        expectMutableList = { emitter.calleeExpectsMutableListSliceParam(plainCalleeBase, argIdx) },
                        indent = indent,
                        selfTypeName = selfTypeName,
                    )
                if (subsliceSpec != null) {
                    subslicePreStmts += subsliceSpec.first
                    subslicePostStmts += subsliceSpec.third
                    return@mapIndexed subsliceSpec.second
                }
                val paramKind =
                    calleeParamNumericKinds?.get(argIdx)
                        ?: if (argIdx == 0 && rawArgs.size == 1 && plainCalleeBase.substringAfterLast('.') == "new") {
                            val atomicOwner = plainCalleeBase.substringBeforeLast('.').substringAfterLast('.').trim()
                            atomicOwnerValueNumericKind(atomicOwner)
                        } else {
                            null
                        }
                when {
                    (callee == "ioctl" || callee.endsWith(".ioctl")) && argIdx >= 2 ->
                        renderVariadicI64OrPtrArg(argExpr, allowAddressablePtr = true, preferI64Init = false)
                    callee == "syscall" || callee.endsWith(".syscall") ->
                        renderVariadicI64OrPtrArg(argExpr, allowAddressablePtr = argIdx > 0, preferI64Init = argIdx == 0)
                    paramKind != null -> {
                        emitter.currentExpectedParseTargetType = null
                        emitter.renderTypedNumericInitializer(argExpr, paramKind, indent, selfTypeName)
                    }
                    else -> renderGeneralCallArgument(argIdx, argExpr, rawCallSegs)
                }
            }
        } finally {
            emitter.callArgumentNestingDepth--
            emitter.currentExpectedNumericKind = savedKind
            emitter.currentExpectedParseTargetType = savedParseTarget
        }
    }

    private fun renderVariadicI64OrPtrArg(
        argExpr: Expr,
        allowAddressablePtr: Boolean,
        preferI64Init: Boolean,
    ): String {
        val isIntLitArg = (emitter.unwrapParenOrGroupExpr(argExpr) as? Expr.Lit)?.lit is Lit.Int
        val unwrappedRefArg = emitter.unwrapParenOrGroupExpr(argExpr) as? Expr.Reference
        val refArgLocal = unwrappedRefArg?.expr?.let(emitter::extractSingleSegmentLocalKotlinName)
        val refArgStorage = refArgLocal?.let { emitter.currentAddressableLocals[it] }
        return when {
            allowAddressablePtr && unwrappedRefArg != null && refArgStorage != null ->
                if (unwrappedRefArg.mutability != null) "$refArgStorage.asMutPtr().rawAddress" else "$refArgStorage.asConstPtr().rawAddress"
            preferI64Init || isIntLitArg || emitter.inferExprNumericKind(argExpr, selfTypeName) != null ->
                emitter.renderTypedNumericInitializer(argExpr, NumericKind.I64, indent, selfTypeName)
            else -> {
                emitter.currentExpectedNumericKind = null
                val r = emitter.renderExpr(argExpr, indent, selfTypeName)
                emitter.renderCastExpression(r, "Long", argExpr, selfTypeName)
            }
        }
    }

    private fun renderGeneralCallArgument(
        argIdx: Int,
        argExpr: Expr,
        rawCallSegs: List<String>,
    ): String {
        emitter.currentExpectedNumericKind = null
        val paramSynTy = emitter.resolveCalleeExprParamSynType(expr.func, argIdx, selfTypeName)
        val unwrappedArg = emitter.unwrapParenOrGroupExpr(argExpr)
        val innerArgFuncPath = (unwrappedArg as? Expr.Call)?.let { emitter.unwrapParenOrGroupExpr(it.func) as? Expr.Path }
        val innerArgPath = innerArgFuncPath?.path
        val innerArgLastSeg = innerArgPath?.segments?.toList()?.lastOrNull()
        val innerArgLeaf = innerArgLastSeg?.ident?.toString()?.removePrefix("r#")
        val isTransmuteOrDefaultCall = innerArgLeaf in setOf("transmute", "transmute_unchecked", "default")
        val isConversionMethodArg =
            unwrappedArg is Expr.MethodCall &&
                unwrappedArg.method.toString().removePrefix("r#") in setOf("into", "try_into", "sve_into")
        val isTransmuteOrConversionArg = isTransmuteOrDefaultCall || isConversionMethodArg
        val paramParseTarget =
            resolveExplicitParamParseTarget(paramSynTy, unwrappedArg, rawCallSegs, isTransmuteOrConversionArg)
                ?: emitter.resolveCallArgExpectedCollectionOrArrayType(
                    func = expr.func,
                    argIdx = argIdx,
                    totalArgs = rawArgs.size,
                    selfTypeName = selfTypeName,
                )
                ?: resolveSimdPeerParseTarget(argIdx, rawCallSegs, isTransmuteOrConversionArg)
        emitter.currentExpectedParseTargetType = paramParseTarget
        val renderedArg =
            try {
                emitter.renderExpr(argExpr, indent, selfTypeName)
            } finally {
                emitter.currentExpectedParseTargetType = null
            }
        return if (isStdlibConstPtrCallParam(rawCallSegs, paramSynTy, argIdx)) {
            coerceMutPtrArgToConstPtr(renderedArg, emitter.inferExprRenderedType(argExpr, selfTypeName))
        } else {
            renderedArg
        }
    }

    private fun resolveExplicitParamParseTarget(
        paramSynTy: SynType?,
        unwrappedArg: Expr,
        rawCallSegs: List<String>,
        isTransmuteOrConversionArg: Boolean,
    ): String? {
        if (paramSynTy == null) return null
        val renderedParamTy = emitter.renderType(paramSynTy, selfTypeName).removeSuffix("?").trim()
        val castTy = (unwrappedArg as? Expr.Cast)?.ty
        val isInferCastArg = castTy is SynType.Infer || (castTy is SynType.Ptr && castTy.elem is SynType.Infer)
        val isCalleeGenericPlaceholder =
            rawCallSegs.lastOrNull() in HOMOGENEOUS_BINARY_SIMD_INTRINSICS ||
                (
                    renderedParamTy.length == 1 &&
                        renderedParamTy[0] in 'A'..'Z' &&
                        renderedParamTy !in emitter.inspector.declaredTypeNames &&
                        renderedParamTy !in emitter.importedSimpleNameToPkg
                )
        val isConcreteConvTarget =
            isTransmuteOrConversionArg &&
                isConcreteNonErasedTargetType(renderedParamTy) &&
                !isCalleeGenericPlaceholder
        val eligible =
            renderedParamTy.startsWith("List<") ||
                renderedParamTy.startsWith("MutableList<") ||
                renderedParamTy in PRIMITIVE_ARRAY_TYPES ||
                renderedParamTy.startsWith("RangeInclusive<") ||
                (renderedParamTy != "Unit" && isInferCastArg) ||
                isConcreteConvTarget
        return if (eligible) renderedParamTy else null
    }

    private fun resolveSimdPeerParseTarget(
        argIdx: Int,
        rawCallSegs: List<String>,
        isTransmuteOrConversionArg: Boolean,
    ): String? {
        if (!isTransmuteOrConversionArg || rawArgs.size != 2 || rawCallSegs.lastOrNull() !in HOMOGENEOUS_BINARY_SIMD_INTRINSICS) {
            return null
        }
        val rawPeer = emitter.inferExprRenderedType(rawArgs[1 - argIdx], selfTypeName) ?: return null
        val peerTy = rawPeer.removeSuffix("?").trim()
        return peerTy.takeIf(::isConcreteNonErasedTargetType)
    }

    private fun isConcreteNonErasedTargetType(ty: String): Boolean =
        ty.isNotEmpty() &&
            ty != "Unit" &&
            ty != "Any" &&
            ty != "Any?" &&
            '*' !in ty &&
            ty !in emitter.activeTypeParamNames

    private fun tryRenderSelfOrUfcsTraitCall(plainCalleeBase: String, renderedArgList: List<String>): String? {
        if (renderedArgList.size == 1 && renderedArgList[0] == "this" && '.' in callee) {
            val ownerPart = callee.substringBeforeLast('.')
            val methodPart = callee.substringAfterLast('.')
            if (ownerPart in emitter.inspector.attachableTypeNames &&
                methodPart in emitter.inspector.instanceMethodsByType[ownerPart].orEmpty()
            ) {
                return "this.$methodPart()"
            }
        }
        if (renderedArgList.isEmpty() || '.' !in plainCalleeBase) return null
        val ownerSimple = plainCalleeBase.substringBeforeLast('.').substringAfterLast('.').trim()
        val methodPart = callee.substringAfterLast('.')
        val methodBase = methodPart.substringBefore('<').trim()
        val traitDecl = emitter.lookupDeclaredTraitAst(ownerSimple)
        val isUfcsTraitReceiverCall =
            when {
                traitDecl != null -> traitDeclHasReceiverMethod(traitDecl, methodBase, renderedArgList.size)
                ownerSimple in emitter.activeTypeParamNames -> {
                    val boundTraits = emitter.currentTypeParamTraitBounds[ownerSimple].orEmpty()
                    val traitHasReceiverMethod =
                        boundTraits.any { tName ->
                            emitter.lookupDeclaredTraitAst(tName)?.let { traitDeclHasReceiverMethod(it, methodBase, renderedArgList.size) } == true
                        }
                    (traitHasReceiverMethod || methodBase in KNOWN_STD_UFCS_RECEIVER_METHODS) &&
                        emitter.currentTypeParamWitnessCalls.none {
                            it.key.first == ownerSimple && rustFunctionIdentToKotlin(it.key.second) == methodBase
                        }
                }
                else -> {
                    val arg0Ty = inferPointeeNormalizedType(rawArgs[0])
                    arg0Ty != null &&
                        ownerSimple in emitter.currentTypeParamTraitBounds[arg0Ty].orEmpty() &&
                        emitter.currentTypeParamWitnessCalls.none {
                            it.key.first == arg0Ty && rustFunctionIdentToKotlin(it.key.second) == methodBase
                        }
                }
            }
        if (ownerSimple in setOf("PartialOrd", "Ord") && renderedArgList.size == 2) {
            val loweredCmp =
                emitter.tryRenderUnconstrainedCmpLowering(
                    rawMethod = methodBase,
                    cleanRecvTy = inferPointeeNormalizedType(rawArgs[0]),
                    recvRendered = renderedArgList[0],
                    argRendered = renderedArgList[1],
                    allowWhenRecvUninferred = true,
                )
            if (loweredCmp != null) return loweredCmp
        }
        if (isUfcsTraitReceiverCall) {
            val recvAtom = if (emitter.isAtomicPostfixReceiver(rawArgs[0], renderedArgList[0])) renderedArgList[0] else "(${renderedArgList[0]})"
            val restArgs = renderedArgList.drop(1).joinToString(", ")
            return "$recvAtom.$methodPart($restArgs)"
        }
        return null
    }

    private fun traitDeclHasReceiverMethod(traitDecl: Item.Trait, methodBase: String, argCount: Int): Boolean =
        traitDecl.items.any { tItem ->
            tItem is TraitItem.Fn &&
                rustFunctionIdentToKotlin(tItem.sig.ident.toString()) == methodBase &&
                tItem.sig.inputs.firstOrNull() is FnArg.Receiver &&
                tItem.sig.inputs.size == argCount
        }

    private fun inferPointeeNormalizedType(argExpr: Expr): String? {
        val rawTy = emitter.inferExprRenderedType(argExpr, selfTypeName) ?: return null
        val pointeeOrSelf = mutableReferencePointeeType(rawTy) ?: rawTy
        return pointeeOrSelf.removeSuffix("?").trim()
    }

    private fun resolveForwardedSynWitnesses(renderedArgList: List<String>): List<String> {
        for (rule in activeRules().synForwardedWitnessRules) {
            if (callee.endsWith(rule.calleeSuffix) && renderedArgList.size == rule.arity) {
                if (rule.forwardAllWitnesses) {
                    return emitter.currentTypeParamWitnessCalls.values
                        .filter { it !in renderedArgList }
                        .distinct()
                }
                return rule.witnessMethods.flatMap { collectForwardedWitnesses(it, renderedArgList) }
            }
        }
        return emptyList()
    }

    private fun collectForwardedWitnesses(methodKey: String, renderedArgList: List<String>): List<String> =
        emitter.currentTypeParamWitnessCalls.entries
            .filter { it.key.second == methodKey && it.value !in renderedArgList }
            .map { it.value }
            .distinct()

    private fun resolveConstGenericValueArgs(
        plainCalleeBase: String,
        explicitGenericArgs: List<GenericArgument>,
    ): List<String> {
        val explicitConstValueArgs =
            explicitGenericArgs.mapNotNull { arg ->
                when (arg) {
                    is GenericArgument.ConstArg -> emitter.renderExpr(arg.expr, indent, selfTypeName)
                    is GenericArgument.TypeArg -> emitter.runtimeConstTypeArgument(arg)
                    else -> null
                }
            }
        if (explicitConstValueArgs.isNotEmpty() || emitter.activeConstParamNames.isEmpty()) {
            return explicitConstValueArgs
        }
        val calleeSimple = plainCalleeBase.substringAfterLast('.')
        val sameFileOrCrateConsts =
            (
                emitter.inspector.fnConstParamNames[plainCalleeBase]
                    ?: emitter.inspector.fnConstParamNames[calleeSimple]
                    ?: emitter.headerContext.crateFnConstParamNames[plainCalleeBase]
                    ?: emitter.headerContext.crateFnConstParamNames[calleeSimple]
            )?.takeIf { (arity, consts) -> arity == rawArgs.size && consts.all { it in emitter.activeConstParamNames } }
                ?.second
        if (sameFileOrCrateConsts != null) return sameFileOrCrateConsts
        val extKeys =
            buildList {
                if ('.' in plainCalleeBase) {
                    val ownerPart = plainCalleeBase.substringBeforeLast('.')
                    val fnPart = plainCalleeBase.substringAfterLast('.')
                    emitter.importedSimpleNameToPkg[ownerPart]?.let { add("$it.$ownerPart.$fnPart") }
                    add(plainCalleeBase)
                } else {
                    emitter.importedSimpleNameToPkg[plainCalleeBase]?.let { add("$it.$plainCalleeBase") }
                    add(plainCalleeBase)
                }
            }
        return extKeys
            .firstNotNullOfOrNull { key ->
                val shape = emitter.headerContext.externalCrateFnConstParams[key] ?: return@firstNotNullOfOrNull null
                val needed = shape.first - rawArgs.size
                if (needed <= 0 || needed > shape.second.size) return@firstNotNullOfOrNull null
                shape.second.takeLast(needed).takeIf { it.all { c -> c in emitter.activeConstParamNames } }
            }.orEmpty()
    }

    private fun resolveTransparentFieldLayoutArgs(plainCalleeBase: String): List<String> {
        val callOwner = plainCalleeBase.substringBeforeLast('.', missingDelimiterValue = plainCalleeBase)
        val declaration = emitter.transparentDeclaration(callOwner) ?: return emptyList()
        if (emitter.sourceTransparentField(declaration, callOwner) == null) return emptyList()
        val isCtorOrFactory =
            plainCalleeBase == callOwner ||
                emitter.file.items.filterIsInstance<Item.Impl>().any { implementation ->
                    emitter.renderType(implementation.selfType, selfTypeName).substringBefore('<') == callOwner &&
                        implementation.items.filterIsInstance<ImplItem.Fn>().any { function ->
                            rustFunctionIdentToKotlin(function.sig.ident.toString()) == plainCalleeBase.substringAfterLast('.') &&
                                function.sig.inputs.firstOrNull() !is FnArg.Receiver &&
                                emitter.transparentConstructorField(callOwner + emitter.renderTypeArgumentsFromGenerics(declaration.generics), function.block) != null
                        }
                }
        if (!isCtorOrFactory) return emptyList()
        val layoutExpr =
            if (plainCalleeBase == callOwner) {
                emitter.transparentConstructionLayout(declaration, selfTypeName)
            } else {
                emitter.sourceFactoryCallLayout(expr, declaration, selfTypeName)
            }
        return listOf("fieldLayout = $layoutExpr")
    }

    private fun renderFinalCallWithAdapters(
        plainCalleeBase: String,
        explicitGenericArgs: List<GenericArgument>,
        fullArgs: String,
        savedParseTarget: String?,
        subslicePreStmts: List<String>,
        subslicePostStmts: List<String>,
    ): String {
        val rewrittenTraitElemCallee = emitter.resolveContainerElementTraitCallee(callee)
        val nonZeroTail = plainCalleeBase.split('.').takeLast(2).joinToString(".")
        val nonZeroTypeArgs =
            if (rewrittenTraitElemCallee == null &&
                explicitGenericArgs.isEmpty() &&
                '<' !in callee &&
                rawArgs.size == 1 &&
                nonZeroTail in setOf("NonZero.new", "NonZero.newUnchecked")
            ) {
                emitter.resolveNonZeroNewExplicitTypeArgs(nonZeroTail, rawArgs, selfTypeName)
            } else {
                null
            }
        val callExpr =
            when {
                rewrittenTraitElemCallee != null -> "$rewrittenTraitElemCallee($fullArgs)"
                callee.endsWith(".default()") && rawArgs.isNotEmpty() -> "${callee.removeSuffix(".default()")}.from($fullArgs)"
                nonZeroTypeArgs != null -> "$callee<${nonZeroTypeArgs.first}, ${nonZeroTypeArgs.second}>($fullArgs)"
                else -> "$callee($fullArgs)"
            }
        val finalCallExpr =
            if ((callee == "strlen" || callee.endsWith(".strlen")) &&
                (emitter.currentExpectedNumericKind == NumericKind.I32 || emitter.currentEnclosingReturnRenderedType == "Int")
            ) {
                "$callExpr.toInt()"
            } else {
                callExpr
            }
        val resolvedFullCallee =
            if ('.' in plainCalleeBase) {
                plainCalleeBase
            } else {
                emitter.importedSimpleNameToPkg[plainCalleeBase]?.let { "$it.$plainCalleeBase" } ?: plainCalleeBase
            }
        val expectsTwoElemTuple =
            (emitter.currentEnclosingReturnSynType as? SynType.Tuple)?.elems?.len() == 2 ||
                emitter.currentEnclosingReturnRenderedType?.let { it.startsWith("Pair<") || it.startsWith("kotlin.Pair<") } == true ||
                savedParseTarget?.let { it.startsWith("Pair<") || it.startsWith("kotlin.Pair<") } == true
        val pairWrappedCallExpr =
            if (resolvedFullCallee in emitter.headerContext.externalCrateTwoFieldResultFns && expectsTwoElemTuple) {
                val pairCtor = if ("Pair" in emitter.inspector.declaredTypeNames) "kotlin.Pair" else "Pair"
                "($finalCallExpr).let { pairRes0 -> $pairCtor(pairRes0.component1(), pairRes0.component2()) }"
            } else {
                finalCallExpr
            }
        if (subslicePreStmts.isEmpty()) return pairWrappedCallExpr
        val preJoined = subslicePreStmts.joinToString("; ")
        val postJoined = subslicePostStmts.joinToString("; ")
        return "run { $preJoined; val subCall0 = $pairWrappedCallExpr; $postJoined; subCall0 }"
    }
}

internal abstract class PreCalleeCallRule {
    abstract fun tryRender(ctx: CallLoweringContext): String?
}

private object CoreAndPlacePreCallRule : PreCalleeCallRule() {
    override fun tryRender(ctx: CallLoweringContext): String? {
        val emitter = ctx.emitter
        CoreIntrinsicLowering.renderCoreIntrinsicCall(ctx.expr, emitter, ctx.indent, ctx.selfTypeName)?.let { return it }
        emitter.renderPlaceOperation(ctx.expr, ctx.indent, ctx.selfTypeName)?.let { return it }
        conservativeNeedsDropCall(ctx.expr, emitter.inspector.declaredTypeNames, emitter.inspector.declaredTopLevelFnRawNames)?.let {
            return it.toString()
        }
        emitter.tryRenderSimdLaneVectorStaticCall(ctx.expr, ctx.rawArgs, ctx.indent, ctx.selfTypeName)?.let { return it }
        return null
    }
}

private object DeclarativePreCalleeDispatchRule : PreCalleeCallRule() {
    override fun tryRender(ctx: CallLoweringContext): String? {
        val segs = ctx.rawFuncPathSegs
        if (segs.isEmpty()) return null
        val pathText = segs.joinToString(".")
        return tryRenderDeclarativeCalleeRules(
            ctx = ctx,
            rules = activeRules().preCalleeRules,
            callee = pathText,
            calleeBase = pathText,
        )
    }
}

private object BytesAndTransmutePreCallRule : PreCalleeCallRule() {
    override fun tryRender(ctx: CallLoweringContext): String? {
        if (ctx.nonUnitArgs.size != 1) return null
        val emitter = ctx.emitter
        val arg0 = ctx.nonUnitArgs[0]
        emitter.renderPrimitiveFromBytesCall(ctx.expr, arg0, ctx.indent, ctx.selfTypeName)?.let { return it }
        val funcPath = ctx.plainFuncPath?.path ?: return null
        val lastFuncSeg = funcPath.segments.last() ?: return null
        val rawTransmuteIdent = lastFuncSeg.ident.toString()
        if (rawTransmuteIdent == "transmute" || rawTransmuteIdent == "transmute_unchecked") {
            val angleArgs = (lastFuncSeg.arguments as? PathArguments.AngleBracketed)?.args?.toList().orEmpty()
            val explicitTypes = angleArgs.filterIsInstance<GenericArgument.TypeArg>().map { it.type }
            return CoreIntrinsicLowering.renderTransmuteCall(
                rawTransmuteIdent = rawTransmuteIdent,
                explicitTypes = explicitTypes,
                argExpr = arg0,
                indent = ctx.indent,
                selfTypeName = ctx.selfTypeName,
                emitter = emitter,
            )
        }
        return null
    }
}

private val PRE_CALLEE_CALL_RULES: List<PreCalleeCallRule> =
    listOf(
        CoreAndPlacePreCallRule,
        DeclarativePreCalleeDispatchRule,
        BytesAndTransmutePreCallRule,
    )

internal abstract class CalleeDispatchRule {
    abstract fun tryRender(ctx: CallLoweringContext): String?
}

private object DefaultTraitCallRule : CalleeDispatchRule() {
    override fun tryRender(ctx: CallLoweringContext): String? {
        if (ctx.nonUnitArgs.isNotEmpty() || !ctx.matchesCalleeOrSuffix("Default.default")) return null
        val emitter = ctx.emitter
        emitter.currentExpectedParseTargetType?.trim()?.let { targetTp ->
            emitter.currentTypeParamWitnessCalls[targetTp to "default"]?.let { return "$it()" }
        }
        emitter.currentTypeParamWitnessCalls["*" to "default"]?.let { return "$it()" }
        val targetTy = (emitter.currentExpectedParseTargetType ?: emitter.currentEnclosingReturnRenderedType)?.removeSuffix("?")?.trim()
        if (targetTy.isNullOrEmpty()) return "${ctx.callee}()"
        val init = defaultInitializerForType(targetTy)
        val targetBase = targetTy.substringBefore('<').substringAfterLast('.').trim()
        return when {
            init != "null as $targetTy" &&
                init != "$targetTy()" &&
                init in setOf("ArrayDeque()", "I128()", "U128()", "F16()", "F128()") -> init
            init != "null as $targetTy" &&
                init != "$targetTy()" &&
                !init.startsWith("$targetTy(") -> init
            targetBase.isNotEmpty() &&
                targetBase !in setOf("Any", "RustAny", "Unit", "Nothing") &&
                targetBase !in emitter.activeTypeParamNames &&
                (
                    targetBase in emitter.inspector.declaredTypeNames ||
                        targetBase in emitter.headerContext.crateSymbolSubpackages ||
                        emitter.headerContext.externalCrateSymbolPackages
                            .values
                            .any { targetBase in it }
                ) -> "$targetBase.default()"
            else -> "${ctx.callee}()"
        }
    }
}

private object YamlCalleePatternDispatchRule : CalleeDispatchRule() {
    override fun tryRender(ctx: CallLoweringContext): String? =
        tryRenderDeclarativeCalleeRules(
            ctx = ctx,
            rules = activeRules().calleeRules,
            callee = ctx.callee,
            calleeBase = ctx.calleeBase,
        )
}

private fun tryRenderDeclarativeCalleeRules(
    ctx: CallLoweringContext,
    rules: List<DeclarativeCalleePatternRule>,
    callee: String,
    calleeBase: String,
): String? {
    val emitter = ctx.emitter
    val argCount = ctx.nonUnitArgs.size
    val renderedArgs = arrayOfNulls<String>(argCount)
    val neutralArgs = arrayOfNulls<String>(argCount)
    val rawRenderedArgs = arrayOfNulls<String>(ctx.rawArgs.size)

    fun arg(idx: Int): String =
        renderedArgs[idx] ?: ctx.renderArg(idx).also { renderedArgs[idx] = it }

    fun neutralArg(idx: Int): String =
        neutralArgs[idx] ?: ctx.renderNeutralArg(ctx.nonUnitArgs[idx]).also { neutralArgs[idx] = it }

    fun rawArg(idx: Int): String =
        rawRenderedArgs[idx] ?: emitter.renderExpr(ctx.rawArgs[idx], ctx.indent, ctx.selfTypeName).also { rawRenderedArgs[idx] = it }

    fun checkGuard(guard: String): Boolean =
        when (guard) {
            "coreActive" -> "core" in emitter.headerContext.crateNamespaces
            "taggedCoreResult" -> emitter.usesTaggedCoreResult()
            "fmtStringBuilderArg0" ->
                argCount > 0 && emitter.isFmtStringBuilderReceiver(ctx.nonUnitArgs[0], neutralArg(0), ctx.selfTypeName)
            "noCalleeTypeArgs" -> '<' !in callee
            "strArg0" ->
                argCount > 0 &&
                    (
                        (emitter.unwrapParenOrGroupExpr(ctx.nonUnitArgs[0]) as? Expr.Lit)?.lit is Lit.Str ||
                            emitter.inferExprRenderedType(ctx.nonUnitArgs[0], ctx.selfTypeName)?.removeSuffix("?")?.trim() == "String"
                    )
            "fmtTraitMethod" -> isFmtTraitMethodCallee(calleeBase)
            else -> throw IllegalArgumentException("Unknown calleeGuard '$guard'")
        }

    for (rule in rules) {
        if (rule.matches(
                callee = callee,
                calleeBase = calleeBase,
                argCount = argCount,
                declaredTypeNames = emitter.inspector.declaredTypeNames,
                declaredTopLevelFnRawNames = emitter.inspector.declaredTopLevelFnRawNames,
                topLevelSignatureCounts = emitter.topLevelSignatureCounts,
                guardPredicate = ::checkGuard,
            )
        ) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
            return rule.action.render(
                rawAbi = emitter.headerContext.rawAbiMode,
                finalClasses = emitter.headerContext.finalClasses,
                onImport = { CoreIntrinsicLowering.registerCoreImport(emitter, it) },
            ) { key ->
                when (key) {
                    "callee" -> callee
                    "turbofish" -> ctx.turbofishSuffix
                    "coreNs" -> coreNs
                    "resName" -> emitter.sourceResultName()
                    "fmtErr" -> emitter.bindOrQualifyResolvedCrossSymbol("$coreNs.fmt.Error")
                    "arg0" -> arg(0)
                    "arg1" -> arg(1)
                    "arg2" -> arg(2)
                    "rawArg0" -> rawArg(0)
                    "rawArg1" -> rawArg(1)
                    "rawArg2" -> rawArg(2)
                    "neutralArg0" -> neutralArg(0)
                    "neutralArg1" -> neutralArg(1)
                    "neutralAtom0" -> {
                        val n0 = neutralArg(0)
                        if (emitter.isAtomicPostfixReceiver(ctx.nonUnitArgs[0], n0)) n0 else "($n0)"
                    }
                    "arg1I32" -> emitter.coerceRenderedNumericExpr(ctx.nonUnitArgs[1], arg(1), NumericKind.I32)
                    "arg0PointeeLayout" -> emitter.resolveRawPointerPointeeLayout(ctx.nonUnitArgs[0], ctx.selfTypeName)
                    "maybeUninitCallLayout" ->
                        emitter.resolveRenderedTypeLayoutExpr(
                            emitter.resolveMaybeUninitElementRenderedType(ctx.expr, callee, ctx.selfTypeName),
                        )
                    "maybeUninitArg0Layout" -> {
                        val elemTy =
                            emitter.inferExprRenderedType(ctx.nonUnitArgs[0], ctx.selfTypeName)?.takeIf { it.isNotEmpty() && it != "Any?" }
                                ?: emitter.resolveMaybeUninitElementRenderedType(ctx.expr, callee, ctx.selfTypeName)
                        emitter.resolveRenderedTypeLayoutExpr(elemTy)
                    }
                    "quotedSelfType" -> renderKotlinDoubleQuotedString(ctx.selfTypeName?.substringBefore('<')?.trim() ?: "Any")
                    "nullPtrCtor" -> {
                        val wantMut =
                            "Mut" in calleeBase ||
                                "mut" in calleeBase ||
                                isMutRawPointerRenderedType(
                                    emitter.currentExpectedParseTargetType ?: emitter.currentEnclosingReturnRenderedType,
                                )
                        if (wantMut) "MutPtr" else "ConstPtr"
                    }
                    "derefRefArg0" -> {
                        val innerArg = emitter.unwrapParenOrGroupExpr(ctx.nonUnitArgs[0])
                        val targetArg = if (innerArg is Expr.Reference) innerArg.expr else ctx.nonUnitArgs[0]
                        emitter.renderExpr(targetArg, ctx.indent, ctx.selfTypeName)
                    }
                    "byteSwapArg0" -> {
                        val a0 = arg(0)
                        val swapKind = rule.byteSwapKind
                        if (swapKind == null) a0 else renderPrimitiveByteSwapExpr(a0, swapKind)
                    }
                    else -> null
                }
            }
        }
    }
    return null
}

private object ZeroedAndPaddingCallRule : CalleeDispatchRule() {
    override fun tryRender(ctx: CallLoweringContext): String? {
        val emitter = ctx.emitter
        val cb = ctx.calleeBase
        val args = ctx.nonUnitArgs
        return when {
            args.isEmpty() && (cb == "zeroed" || ctx.matchesCalleeOrSuffix("mem.zeroed")) ->
                renderMemZeroedCall(emitter)
            args.isEmpty() && ctx.matchesCalleeOrSuffix("Padding.uninit") ->
                if (emitter.currentExpectedParseTargetType in PRIMITIVE_ARRAY_TYPES) {
                    "${emitter.currentExpectedParseTargetType}(0)"
                } else {
                    emitter.renderIntLiteral("0", "")
                }
            args.size == 1 && ctx.matchesCalleeOrSuffix("Padding.new") ->
                if (emitter.currentExpectedParseTargetType != null && emitter.currentExpectedParseTargetType in PRIMITIVE_ARRAY_TYPES) {
                    emitter.renderStructPrimitiveArrayFieldInit(args[0], emitter.currentExpectedParseTargetType!!, ctx.indent, ctx.selfTypeName)
                } else {
                    emitter.renderTypedNumericInitializer(args[0], emitter.currentExpectedNumericKind, ctx.indent, ctx.selfTypeName)
                }
            else -> null
        }
    }

    private fun renderMemZeroedCall(emitter: KotlinJ2KEmitter): String {
        if (emitter.headerContext.rawAbiMode) {
            val retT = emitter.currentEnclosingReturnRenderedType
            return if (retT != null) emitter.renderFallbackDefaultForRenderedType(retT) else "0L"
        }
        val rawCandidate = emitter.currentExpectedParseTargetType ?: emitter.currentEnclosingReturnRenderedType
        val targetType = rawCandidate?.trim()?.removeSuffix("?")?.takeIf { it.isNotEmpty() && it != "Any" && it != "Unit" } ?: return "null"
        val fallback = emitter.renderFallbackDefaultForRenderedType(targetType)
        if (!fallback.startsWith("null as ") && fallback != "null" && !fallback.startsWith("error(")) {
            return fallback
        }
        val baseSimple = targetType.substringBefore('<').substringAfterLast('.').trim()
        val impPkg = emitter.importedSimpleNameToPkg[baseSimple]
        val libcNs = FfiInteropCollector.resolveLibcNamespace(emitter.headerContext)
        val structDecl = emitter.sameFileUniqueSimpleStruct(baseSimple)
        val computedLayout = structDecl?.let { StructLayoutComputer.computeStructLayout(it, emitter) }
        val isExternalFfiStruct =
            (libcNs != null && (targetType.startsWith("$libcNs.") || impPkg == libcNs || impPkg?.startsWith("$libcNs.") == true)) ||
                impPkg?.contains(".libc") == true ||
                impPkg?.contains(".wasip1") == true ||
                impPkg?.contains(".windowssys") == true ||
                targetType in emitter.inspector.declaredTypeNames ||
                computedLayout?.requiredTypeLayoutParams?.isNotEmpty() == false
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
        return when {
            computedLayout != null && computedLayout.requiredTypeLayoutParams.isEmpty() ->
                CoreIntrinsicLowering.renderZeroedLayoutReadExpression("$baseSimple.typeLayout()", "$coreNs.intrinsics", emitter)
            isExternalFfiStruct && '<' !in targetType -> "$targetType()"
            else -> {
                CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.mem.maybeuninit.MaybeUninit")
                "MaybeUninit.zeroed(${emitter.resolveRenderedTypeLayoutExpr(targetType)}).assumeInit()"
            }
        }
    }
}

private object OptionAndResultCtorCallRule : CalleeDispatchRule() {
    override fun tryRender(ctx: CallLoweringContext): String? {
        val emitter = ctx.emitter
        val callee = ctx.callee
        val args = ctx.nonUnitArgs
        if (callee == "Some" && "Some" !in emitter.inspector.declaredTypeNames && !emitter.hasDeclaredOptionEnum()) {
            if (args.size == 1) {
                val enclosingOpt = emitter.currentEnclosingReturnRenderedType?.takeIf { it.endsWith("?") }?.removeSuffix("?")
                val optKind = enclosingOpt?.let(emitter::resolveNumericKind) ?: emitter.currentExpectedNumericKind
                val savedParseTarget = emitter.currentExpectedParseTargetType
                emitter.currentExpectedParseTargetType = savedParseTarget?.removeSuffix("?")
                return try {
                    emitter.renderTypedNumericInitializer(args[0], optKind, ctx.indent, ctx.selfTypeName)
                } finally {
                    emitter.currentExpectedParseTargetType = savedParseTarget
                }
            }
            if (ctx.rawArgs.size == 1 && args.isEmpty()) return "Unit"
        }
        val calleeTail = callee.substringAfterLast('.')
        if (callee in setOf("Ok", "Result.Ok", "RustResult.Ok", "Err", "Result.Err", "RustResult.Err") &&
            !emitter.hasDeclaredResultEnum() &&
            calleeTail !in emitter.inspector.declaredTypeNames &&
            ctx.rawArgs.size == 1
        ) {
            return emitter.renderSourceResultConstructor(ctx.expr, calleeTail, ctx.indent, ctx.selfTypeName)
        }
        if ((callee == "Ok" || callee.endsWith(".Ok")) && ctx.rawArgs.size == 1 && args.isEmpty()) {
            return if (emitter.currentEnclosingReturnRenderedType == "Unit") "Unit" else "$callee(Unit)"
        }
        return null
    }
}

private object StdlibCollectionFactoryCallRule : CalleeDispatchRule() {
    override fun tryRender(ctx: CallLoweringContext): String? {
        val args = ctx.nonUnitArgs
        if (args.size != 1) return null
        val emitter = ctx.emitter
        val cb = ctx.calleeBase
        if ((cb == "HashMap.from" || cb == "BTreeMap.from") &&
            "HashMap" !in emitter.inspector.declaredTypeNames &&
            "BTreeMap" !in emitter.inspector.declaredTypeNames
        ) {
            return renderMapFromArg(ctx, args[0])
        }
        if ((cb == "HashSet.from" || cb == "BTreeSet.from") &&
            "HashSet" !in emitter.inspector.declaredTypeNames &&
            "BTreeSet" !in emitter.inspector.declaredTypeNames
        ) {
            val arrExpr = emitter.unwrapParenOrGroupExpr(args[0]) as? Expr.Array
            return if (arrExpr != null) {
                val elems = arrExpr.elems.toList().joinToString(", ") { emitter.renderExpr(it, ctx.indent, ctx.selfTypeName) }
                "mutableSetOf${ctx.turbofishSuffix}($elems)"
            } else {
                "(${ctx.renderArg(0)}).toMutableSet()"
            }
        }
        return null
    }

    private fun renderMapFromArg(ctx: CallLoweringContext, argExpr: Expr): String {
        val emitter = ctx.emitter
        val arrExpr = emitter.unwrapParenOrGroupExpr(argExpr) as? Expr.Array
        val arrElems = arrExpr?.elems?.toList()
        val pairEntries =
            arrElems?.mapNotNull { elem ->
                val tup = emitter.unwrapParenOrGroupExpr(elem) as? Expr.Tuple
                val parts = tup?.elems?.toList().orEmpty()
                if (parts.size == 2) parts[0] to parts[1] else null
            }
        if (arrElems != null && pairEntries != null && pairEntries.size == arrElems.size) {
            val entries =
                pairEntries.joinToString(", ") { (kExpr, vExpr) ->
                    "${emitter.renderExpr(kExpr, ctx.indent, ctx.selfTypeName)} to ${emitter.renderExpr(vExpr, ctx.indent, ctx.selfTypeName)}"
                }
            return "mutableMapOf${ctx.turbofishSuffix}($entries)"
        }
        return "(${ctx.renderArg(0)}).toMap(LinkedHashMap())"
    }
}

private object ScalarCharConversionCallRule : CalleeDispatchRule() {
    override fun tryRender(ctx: CallLoweringContext): String? {
        val emitter = ctx.emitter
        if (!emitter.headerContext.scalarChar || ctx.nonUnitArgs.size != 1) return null
        val arg0 = ctx.nonUnitArgs[0]
        val cb = ctx.calleeBase
        if ("Char" !in emitter.inspector.declaredTypeNames && "RustChar" !in emitter.inspector.declaredTypeNames) {
            val charMethod =
                when (cb) {
                    "Char.fromU32", "RustChar.fromU32" -> "fromU32"
                    "Char.fromU32Unchecked", "RustChar.fromU32Unchecked" -> "fromU32Unchecked"
                    "Char.from", "RustChar.from" -> "from"
                    "Char.tryFrom", "RustChar.tryFrom" -> "tryFrom"
                    else -> null
                }
            if (charMethod != null) {
                emitter.usesRustChar = true
                return when (charMethod) {
                    "fromU32", "fromU32Unchecked" ->
                        "RustChar.$charMethod(${emitter.renderTypedNumericInitializer(arg0, NumericKind.U32, ctx.indent, ctx.selfTypeName)})"
                    "from" -> {
                        val isU8 = emitter.inferExprNumericKind(arg0, ctx.selfTypeName) == NumericKind.U8
                        val targetFn = if (isU8) "fromUByte" else "fromU32Unchecked"
                        val targetKind = if (isU8) NumericKind.U8 else NumericKind.U32
                        "RustChar.$targetFn(${emitter.renderTypedNumericInitializer(arg0, targetKind, ctx.indent, ctx.selfTypeName)})"
                    }
                    else -> {
                        val isU8 = emitter.inferExprNumericKind(arg0, ctx.selfTypeName) == NumericKind.U8
                        val targetKind = if (isU8) NumericKind.U8 else NumericKind.U32
                        "RustChar.tryFrom(${emitter.renderTypedNumericInitializer(arg0, targetKind, ctx.indent, ctx.selfTypeName)})"
                    }
                }
            }
        }
        val argRenderedTy = emitter.inferExprRenderedType(arg0, ctx.selfTypeName)?.removeSuffix("?")?.trim()
        if (cb == "UInt.from" && (argRenderedTy == "RustChar" || (emitter.unwrapParenOrGroupExpr(arg0) as? Expr.Lit)?.lit is Lit.Char)) {
            emitter.usesRustChar = true
            return "(${ctx.renderArg(0)}).code"
        }
        if (cb == "UByte.tryFrom" && argRenderedTy == "RustChar") {
            emitter.usesRustChar = true
            return "(${ctx.renderArg(0)}).tryToUByte()"
        }
        return null
    }
}

private val CALLEE_DISPATCH_RULES: List<CalleeDispatchRule> =
    listOf(
        DefaultTraitCallRule,
        YamlCalleePatternDispatchRule,
        ZeroedAndPaddingCallRule,
        OptionAndResultCtorCallRule,
        StdlibCollectionFactoryCallRule,
        ScalarCharConversionCallRule,
    )

internal fun KotlinJ2KEmitter.renderExprCall(expr: Expr.Call, indent: String, selfTypeName: String?): String {
    val ctx = CallLoweringContext(this, expr, indent, selfTypeName)
    for (rule in PRE_CALLEE_CALL_RULES) {
        rule.tryRender(ctx)?.let { return it }
    }
    ctx.callee = renderCalleeExpr(expr.func, indent, selfTypeName)
    ctx.calleeBase = ctx.callee.substringBefore('<').trim()
    for (rule in CALLEE_DISPATCH_RULES) {
        rule.tryRender(ctx)?.let { return it }
    }
    return ctx.renderStandardCall()
}

internal fun KotlinJ2KEmitter.renderPlaceOperation(expr: Expr.Call, indent: String, selfTypeName: String?): String? {
    val path = expr.func as? Expr.Path ?: return null
    if (path.qself != null || expr.args.size != 2) return null
    val rawSegs = path.path.segments.toList()
    val segments = rawSegs.map { it.ident.toString() }
    val operation = segments.lastOrNull()
    if (operation !in setOf("replace", "swap") || segments.size < 2) return null
    val module = segments[segments.lastIndex - 1]
    if (module !in setOf("mem", "ptr") || module in inspector.localInlineModuleNames) return null
    if (segments.dropLast(2).any { it !in setOf("crate", "core", "std") }) return null
    val args = expr.args.toList()
    val (receiver, fields) = borrowedPlaceProjection(args[0], selfTypeName, addressArgument = true) ?: return null
    val receiverText =
        "(${renderExpr(receiver, indent, selfTypeName)})" +
            fields.dropLast(1).joinToString(separator = "", transform = { ".$it" })
    val place = "placeReceiver.${fields.last()}"
    if (operation == "swap") {
        val (otherReceiver, otherFields) = borrowedPlaceProjection(args[1], selfTypeName, addressArgument = true) ?: return null
        val otherReceiverText =
            "(${renderExpr(otherReceiver, indent, selfTypeName)})" +
                otherFields.dropLast(1).joinToString(separator = "", transform = { ".$it" })
        val otherPlace = "placeOther.${otherFields.last()}"
        return "kotlin.Pair($receiverText, $otherReceiverText).let { (placeReceiver, placeOther) -> " +
            "val placePrevious = $place; $place = $otherPlace; $otherPlace = placePrevious }"
    }
    val valueText = renderExpr(args[1], indent, selfTypeName)
    return "kotlin.Pair($receiverText, $valueText).let { (placeReceiver, placeValue) -> " +
        "val placePrevious = $place; $place = placeValue; placePrevious }"
}

/** A proven address-of-storage accessor can escape as a Kotlin property reference.
 * This is deliberately narrower than raw-pointer lowering: no offset, allocation,
 * nullable address, or reinterpretation semantics are inferred here.
 */

internal fun KotlinJ2KEmitter.renderOptionalSynParseExpr(receiver: String, peekTok: String, innerTarget: String): String {
    val noneResult = if (usesTaggedCoreResult()) "${sourceResultName()}.Ok(null)" else "Result.success(null)"
    val isSimpleReceiver = receiver.all { it.isLetterOrDigit() || it == '_' }
    return if (isSimpleReceiver) {
        "(if ($receiver.peek($peekTok)) $receiver.parse($innerTarget::parse) else $noneResult)"
    } else {
        "$receiver.let { s -> if (s.peek($peekTok)) s.parse($innerTarget::parse) else $noneResult }"
    }
}

internal fun KotlinJ2KEmitter.resolveOptionalSynParsePeekToken(innerTarget: String): String? {
    if (innerTarget.isEmpty() || !innerTarget.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
        return null
    }
    val rules = activeRules()
    rules.map("synOptionalParsePeekTokenAliases")[innerTarget]?.let { return it }
    if (innerTarget in rules.words("synSelfPeekingTokenClasses") ||
        innerTarget in SYN_PUNCT_TOKEN_CLASS_NAMES ||
        innerTarget in SYN_KEYWORD_TOKEN_CLASS_NAMES ||
        innerTarget.startsWith("token.") ||
        innerTarget.startsWith("kw.")
    ) {
        return innerTarget
    }
    return null
}

internal fun KotlinJ2KEmitter.resolvePrimitiveFromBytesSpec(expr: Expr.Call): Triple<String, String, Boolean>? {
    val funcPath = unwrapParenOrGroupExpr(expr.func) as? Expr.Path ?: return null
    val segs = funcPath.path.segments.toList()
    if (segs.isEmpty()) return null
    val lastSeg = segs.last()
    if (lastSeg.arguments !is PathArguments.None) return null
    val methodRaw = lastSeg.ident.toString().removePrefix("r#")
    val isBigEndian =
        when (methodRaw) {
            "from_be_bytes" -> true
            "from_le_bytes" -> false
            "from_ne_bytes" -> activeCfgProfile.targetEndian == "big"
            else -> return null
        }
    val primRaw =
        PrimitiveNumericOpCatalog.extractPrimitiveOwnerRawName(
            path = funcPath.path,
            qself = funcPath.qself,
            extraAllowedPrefixes = setOf("half"),
            requirePlainQSelfArgs = true,
        ) ?: return null
    val ktReturnType =
        when (primRaw) {
            "u8" -> "UByte"
            "i8" -> "Byte"
            "u16" -> "UShort"
            "i16" -> "Short"
            "u32" -> "UInt"
            "i32" -> "Int"
            "u64" -> "ULong"
            "i64" -> "Long"
            "f32" -> kotlinF32TypeName()
            "f64" -> "Double"
            "f16", "F16" -> "F16"
            "f128", "F128" -> "F128"
            "u128", "U128" -> "U128"
            "i128", "I128" -> "I128"
            "usize" -> numericKindToPureTypeName(resolvePointerSizedNumericKind(signed = false))
            "isize" -> numericKindToPureTypeName(resolvePointerSizedNumericKind(signed = true))
            else -> return null
        }
    return Triple(primRaw, ktReturnType, isBigEndian)
}

internal fun KotlinJ2KEmitter.renderPrimitiveFromBytesCall(
    expr: Expr.Call,
    argExpr: Expr,
    indent: String,
    selfTypeName: String?,
): String? {
    val (primRaw, _, isBigEndian) = resolvePrimitiveFromBytesSpec(expr) ?: return null
    val savedParseTarget = currentExpectedParseTargetType
    fun renderUByteArrayArg(target: Expr): String {
        currentExpectedParseTargetType = "UByteArray"
        return try {
            renderExpr(target, indent, selfTypeName)
        } finally {
            currentExpectedParseTargetType = savedParseTarget
        }
    }
    if (primRaw in setOf("u128", "U128", "i128", "I128", "f16", "F16", "f128", "F128")) {
        val argRendered = renderUByteArrayArg(argExpr)
        val fn = if (isBigEndian) "fromBeBytes" else "fromLeBytes"
        val owner =
            when (primRaw) {
                "u128", "U128" -> "U128"
                "i128", "I128" -> "I128"
                "f128", "F128" -> {
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.F128")
                    "F128"
                }
                else -> {
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.F16")
                    "F16"
                }
            }
        return "$owner.$fn($argRendered)"
    }
    val effectiveArg = unwrapTryIntoUnwrapExpr(argExpr) ?: argExpr
    val bytesRendered = renderUByteArrayArg(effectiveArg)
    val effectivePrim =
        if (primRaw == "usize" || primRaw == "isize") {
            if (headerContext.rawAbiMode || inExternAbiSignature) {
                val prefix = if (primRaw == "usize") "u" else "i"
                when (activeCfgProfile.pointerWidth) {
                    16 -> "${prefix}16"
                    32 -> "${prefix}32"
                    else -> "${prefix}64"
                }
            } else {
                "ptr32size"
            }
        } else {
            primRaw
        }
    if (effectivePrim == "u8" || effectivePrim == "i8") {
        val atom = if (isAtomicPostfixReceiver(effectiveArg, bytesRendered)) bytesRendered else "($bytesRendered)"
        return if (effectivePrim == "u8") "$atom[0]" else "$atom[0].toByte()"
    }
    val suffix = closureLabelCounter++
    val tmp = "bytesTmp$suffix"
    if (effectivePrim == "ptr32size") {
        if (isBigEndian) {
            val off = "offTmp$suffix"
            return "run { val $tmp = $bytesRendered; val $off = $tmp.size - 4; (($tmp[$off].toInt() shl 24) or ($tmp[$off + 1].toInt() shl 16) or ($tmp[$off + 2].toInt() shl 8) or $tmp[$off + 3].toInt()) }"
        }
        return "run { val $tmp = $bytesRendered; (($tmp[3].toInt() shl 24) or ($tmp[2].toInt() shl 16) or ($tmp[1].toInt() shl 8) or $tmp[0].toInt()) }"
    }
    return when (effectivePrim) {
        "u16", "i16" -> {
            val hi = if (isBigEndian) 0 else 1
            val lo = if (isBigEndian) 1 else 0
            val conv = if (effectivePrim == "u16") "toUShort" else "toShort"
            "run { val $tmp = $bytesRendered; (($tmp[$hi].toInt() shl 8) or $tmp[$lo].toInt()).$conv() }"
        }
        "u32" -> {
            val idx = if (isBigEndian) listOf(0, 1, 2, 3) else listOf(3, 2, 1, 0)
            "run { val $tmp = $bytesRendered; (($tmp[${idx[0]}].toUInt() shl 24) or ($tmp[${idx[1]}].toUInt() shl 16) or ($tmp[${idx[2]}].toUInt() shl 8) or $tmp[${idx[3]}].toUInt()) }"
        }
        "i32", "f32" -> {
            val idx = if (isBigEndian) listOf(0, 1, 2, 3) else listOf(3, 2, 1, 0)
            val bits = "(($tmp[${idx[0]}].toInt() shl 24) or ($tmp[${idx[1]}].toInt() shl 16) or ($tmp[${idx[2]}].toInt() shl 8) or $tmp[${idx[3]}].toInt())"
            if (effectivePrim == "f32") "run { val $tmp = $bytesRendered; ${kotlinF32TypeName()}.fromBits($bits) }" else "run { val $tmp = $bytesRendered; $bits }"
        }
        "u64", "i64", "f64" -> {
            val idx = if (isBigEndian) (0..7).toList() else (7 downTo 0).toList()
            val shifts = listOf(56, 48, 40, 32, 24, 16, 8, 0)
            val conv = if (effectivePrim == "u64") "toULong()" else "toLong()"
            val terms =
                idx.zip(shifts).joinToString(" or ") { (i, s) ->
                    if (s == 0) "$tmp[$i].$conv" else "($tmp[$i].$conv shl $s)"
                }
            if (effectivePrim == "f64") "run { val $tmp = $bytesRendered; Double.fromBits($terms) }" else "run { val $tmp = $bytesRendered; ($terms) }"
        }
        else -> null
    }
}

internal fun KotlinJ2KEmitter.wrapMapCallbackArg(
    argExpr: Expr,
    renderedArg: String,
): String {
    val trimmed = renderedArg.trim()
    if (trimmed.startsWith("{") || "::" in trimmed || trimmed.startsWith("it ->") || "->" in trimmed) {
        return renderedArg
    }
    val unwrapped = unwrapParenOrGroupExpr(argExpr) as? Expr.Path ?: return renderedArg
    if (unwrapped.qself != null || unwrapped.path.segments.isEmpty()) return renderedArg
    val rawSegs = unwrapped.path.segments.toList()
    val segNames = rawSegs.map { it.ident.toString().removePrefix("r#") }
    if ((segNames == listOf("drop") || segNames == listOf("mem", "drop") || segNames == listOf("core", "mem", "drop") || segNames == listOf("std", "mem", "drop")) &&
        scopeBuilder.resolveLocalKotlinName("drop") == null
    ) {
        return "{ Unit }"
    }
    val rawLast = segNames.lastOrNull() ?: return renderedArg
    if (rawLast.isEmpty() || !rawLast.first().isUpperCase()) return renderedArg
    if (rawLast in inspector.declaredConstNames || rawLast in inspector.unitStructNames) return renderedArg
    if (KotlinNamingPolicy.Default.isScreamingSnakeCase(rawLast)) return renderedArg
    if (!hasDeclaredOptionEnum() && rawLast in setOf("Some", "None")) {
        return if (rawLast == "Some") "{ it }" else renderedArg
    }
    return "{ $trimmed(it) }"
}

internal fun KotlinJ2KEmitter.coerceNumericArgIfNeeded(
    argExpr: Expr,
    renderedArg: String,
    targetKind: NumericKind,
    selfTypeName: String?,
): String {
    if (inferExprNumericKind(argExpr, selfTypeName) == targetKind) return renderedArg
    return coerceRenderedNumericExpr(argExpr, renderedArg, targetKind)
}

internal data class SimdLaneVectorSpec(
    val wrapperName: String,
    val elemTy: String,
    val elemKind: NumericKind?,
    val lanes: Int,
)

private val SIMD_LANE_NAME_REGEX = Regex("^([iufm])(8|16|32|64|128|size)x(\\d+)$", RegexOption.IGNORE_CASE)

internal fun parseSimdLaneVectorSpec(
    rawOwner: String,
    emitter: KotlinJ2KEmitter,
    visited: MutableSet<String> = hashSetOf(),
): SimdLaneVectorSpec? {
    val clean = rawOwner.removePrefix("r#").trim()
    val direct = SIMD_LANE_NAME_REGEX.matchEntire(clean)
    if (direct != null) {
        val kindCh = direct.groupValues[1].lowercase()[0]
        val bits = direct.groupValues[2].lowercase()
        val lanes = direct.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val wrapperName = if (kindCh == 'm') "SimdM" else "Simd"
        val elemKind =
            when (kindCh) {
                'f' -> emitter.rustPrimitivePathNameToKind("f$bits")
                'u' -> emitter.rustPrimitivePathNameToKind("u$bits")
                else -> emitter.rustPrimitivePathNameToKind("i$bits")
            } ?: return null
        val elemTy = emitter.numericKindToPureTypeName(elemKind)
        return SimdLaneVectorSpec(wrapperName = wrapperName, elemTy = elemTy, elemKind = elemKind, lanes = lanes)
    }
    if (!visited.add(clean)) return null
    val kt = rustTypeIdentToKotlin(clean)
    val aliasTarget = emitter.inspector.typeAliasTargets[kt] ?: emitter.inspector.typeAliasTargets[clean] ?: return null
    val pathTy = unwrapSynType(aliasTarget) as? SynType.Path ?: return null
    val lastSeg = pathTy.path.segments.last() ?: return null
    val segRaw = lastSeg.ident.toString().removePrefix("r#")
    if ((segRaw == "Simd" || segRaw == "SimdM") && lastSeg.arguments is PathArguments.AngleBracketed) {
        val args = (lastSeg.arguments as PathArguments.AngleBracketed).args.toList()
        if (args.size >= 2) {
            val elemSyn = (args[0] as? GenericArgument.TypeArg)?.type ?: return null
            val laneLit = ((args[1] as? GenericArgument.ConstArg)?.expr as? Expr.Lit)?.lit as? Lit.Int ?: return null
            val laneDigits = laneLit.value.base10Digits()
            val lanes = laneDigits.toIntOrNull()?.takeIf { it > 0 } ?: return null
            val elemTy = emitter.renderType(elemSyn, null)
            return SimdLaneVectorSpec(
                wrapperName = segRaw,
                elemTy = elemTy,
                elemKind = emitter.resolveNumericKind(elemTy),
                lanes = lanes,
            )
        }
    }
    if (lastSeg.arguments is PathArguments.None) {
        return parseSimdLaneVectorSpec(segRaw, emitter, visited)
    }
    return null
}

internal fun registerSimdWrapperImport(emitter: KotlinJ2KEmitter, wrapperName: String) {
    if (wrapperName in emitter.inspector.declaredTypeNames) return
    if (emitter.resolveBestSubpackageForSymbol(wrapperName) != "simd") return
    val ns = emitter.currentNamespacePrefix() ?: return
    val targetPkg = "$ns.simd"
    if (emitter.headerContext.packageName != targetPkg && wrapperName !in emitter.importedSimpleNameToPkg) {
        emitter.importedSimpleNameToPkg[wrapperName] = targetPkg
        emitter.extraTypePathImports += "import $targetPkg.$wrapperName"
    }
}

internal fun simdZeroLiteralExpr(elemTy: String, elemKind: NumericKind?, emitter: KotlinJ2KEmitter): String =
    when (elemKind) {
        NumericKind.I8 -> "0.toByte()"
        NumericKind.U8 -> "0u.toUByte()"
        NumericKind.I16 -> "0.toShort()"
        NumericKind.U16 -> "0u.toUShort()"
        NumericKind.I32 -> "0"
        NumericKind.U32 -> "0u"
        NumericKind.I64 -> "0L"
        NumericKind.U64 -> "0uL"
        NumericKind.I128 -> "I128.ZERO"
        NumericKind.U128 -> "U128.ZERO"
        NumericKind.F16 -> {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
            CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.F16")
            "F16.fromFloat(0.0f)"
        }
        NumericKind.F32 -> "0.0f"
        NumericKind.F64 -> "0.0"
        NumericKind.F128 -> {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
            CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.F128")
            "F128.fromFloat(0.0f)"
        }
        null -> defaultInitializerForType(elemTy)
    }

private fun simdMaskAllOnesLiteral(elemKind: NumericKind?): String =
    when (elemKind) {
        NumericKind.I8 -> "(-1).toByte()"
        NumericKind.I16 -> "(-1).toShort()"
        NumericKind.I64 -> "-1L"
        else -> "-1"
    }

internal fun KotlinJ2KEmitter.tryRenderSimdLaneVectorStaticCall(
    expr: Expr.Call,
    rawArgs: List<Expr>,
    indent: String,
    selfTypeName: String?,
): String? {
    val fnPath = (expr.func as? Expr.Path)?.takeIf { it.qself == null } ?: return null
    val pathSegs = fnPath.path.segments.toList()
    val segs = pathSegs.map { it.ident.toString().removePrefix("r#") }
    if (segs.size < 2) return null
    val methodName = segs.last()
    if (methodName !in setOf("new", "splat", "from_array")) return null
    val ownerRaw = segs[segs.lastIndex - 1]
    val spec = parseSimdLaneVectorSpec(ownerRaw, this) ?: return null
    registerSimdWrapperImport(this, spec.wrapperName)
    fun renderElem(argExpr: Expr): String {
        val kind = spec.elemKind ?: return renderExpr(argExpr, indent, selfTypeName)
        val rawRendered = renderTypedNumericInitializer(argExpr, kind, indent, selfTypeName)
        return coerceNumericArgIfNeeded(argExpr, rawRendered, kind, selfTypeName)
    }
    return when {
        methodName == "new" && rawArgs.isNotEmpty() -> {
            val argsText = rawArgs.joinToString(", ") { renderElem(it) }
            "${spec.wrapperName}<${spec.elemTy}>(listOf($argsText))"
        }
        methodName == "splat" && rawArgs.size == 1 -> {
            if (spec.wrapperName == "SimdM") {
                val cond = renderExpr(rawArgs[0], indent, selfTypeName)
                val ones = simdMaskAllOnesLiteral(spec.elemKind)
                val zero = simdZeroLiteralExpr(spec.elemTy, spec.elemKind, this)
                "SimdM<${spec.elemTy}>(List(${spec.lanes}) { if ($cond) $ones else $zero })"
            } else {
                val elem = renderElem(rawArgs[0])
                "Simd<${spec.elemTy}>(List(${spec.lanes}) { $elem })"
            }
        }
        methodName == "from_array" && rawArgs.size == 1 -> {
            val arr = renderExpr(rawArgs[0], indent, selfTypeName)
            "${spec.wrapperName}<${spec.elemTy}>(($arr).toList())"
        }
        else -> null
    }
}

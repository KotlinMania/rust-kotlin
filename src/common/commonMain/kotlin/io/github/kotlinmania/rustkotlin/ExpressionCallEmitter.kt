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
private val BYTE_SWAP_CALLEE_KIND: Map<String, NumericKind?> =
    mapOf(
        "Byte.toLe" to null,
        "Byte.fromLe" to null,
        "Byte.toBe" to null,
        "Byte.fromBe" to null,
        "Byte.swapBytes" to null,
        "UByte.toLe" to null,
        "UByte.fromLe" to null,
        "UByte.toBe" to null,
        "UByte.fromBe" to null,
        "UByte.swapBytes" to null,
        "Short.toLe" to null,
        "Short.fromLe" to null,
        "UShort.toLe" to null,
        "UShort.fromLe" to null,
        "Int.toLe" to null,
        "Int.fromLe" to null,
        "UInt.toLe" to null,
        "UInt.fromLe" to null,
        "Long.toLe" to null,
        "Long.fromLe" to null,
        "ULong.toLe" to null,
        "ULong.fromLe" to null,
        "UInt.toBe" to NumericKind.U32,
        "UInt.fromBe" to NumericKind.U32,
        "UInt.swapBytes" to NumericKind.U32,
        "Int.toBe" to NumericKind.I32,
        "Int.fromBe" to NumericKind.I32,
        "Int.swapBytes" to NumericKind.I32,
        "UShort.toBe" to NumericKind.U16,
        "UShort.fromBe" to NumericKind.U16,
        "UShort.swapBytes" to NumericKind.U16,
        "Short.toBe" to NumericKind.I16,
        "Short.fromBe" to NumericKind.I16,
        "Short.swapBytes" to NumericKind.I16,
        "ULong.toBe" to NumericKind.U64,
        "ULong.fromBe" to NumericKind.U64,
        "ULong.swapBytes" to NumericKind.U64,
        "Long.toBe" to NumericKind.I64,
        "Long.fromBe" to NumericKind.I64,
        "Long.swapBytes" to NumericKind.I64,
    )

private fun isFmtTraitMethodCallee(calleeBase: String): Boolean {
    if (!calleeBase.endsWith(".fmt")) return false
    val owner = calleeBase.removeSuffix(".fmt").substringAfterLast('.')
    return owner in FMT_TRAIT_NAMES
}

private fun KotlinJ2KEmitter.renderVariadicI64OrPtrArg(
    argExpr: Expr,
    allowAddressablePtr: Boolean,
    preferI64Init: Boolean,
    indent: String,
    selfTypeName: String?,
): String {
    val isIntLitArg = (unwrapParenOrGroupExpr(argExpr) as? Expr.Lit)?.lit is Lit.Int
    val unwrappedRefArg = unwrapParenOrGroupExpr(argExpr) as? Expr.Reference
    val refArgLocal = unwrappedRefArg?.expr?.let(::extractSingleSegmentLocalKotlinName)
    val refArgStorage = refArgLocal?.let { currentAddressableLocals[it] }
    return when {
        allowAddressablePtr && unwrappedRefArg != null && refArgStorage != null ->
            if (unwrappedRefArg.mutability != null) "$refArgStorage.asMutPtr().rawAddress" else "$refArgStorage.asConstPtr().rawAddress"
        preferI64Init || isIntLitArg || inferExprNumericKind(argExpr, selfTypeName) != null ->
            renderTypedNumericInitializer(argExpr, NumericKind.I64, indent, selfTypeName)
        else -> {
            currentExpectedNumericKind = null
            val r = renderExpr(argExpr, indent, selfTypeName)
            renderCastExpression(r, "Long", argExpr, selfTypeName)
        }
    }
}

private fun KotlinJ2KEmitter.collectForwardedWitnesses(
    methodKey: String,
    renderedArgList: List<String>,
): List<String> =
    currentTypeParamWitnessCalls.entries
        .filter { it.key.second == methodKey && it.value !in renderedArgList }
        .map { it.value }
        .distinct()

internal fun KotlinJ2KEmitter.renderExprCall(expr: Expr.Call, indent: String, selfTypeName: String?): String {
    CoreIntrinsicLowering.renderCoreIntrinsicCall(expr, this, indent, selfTypeName)?.let { return it }
    renderPlaceOperation(expr, indent, selfTypeName)?.let { return it }
    conservativeNeedsDropCall(expr, inspector.declaredTypeNames, inspector.declaredTopLevelFnRawNames)?.let {
        return it.toString()
    }
    val rawArgs = expr.args.toList()
    val nonUnitArgs = rawArgs.filterNot { it is Expr.Tuple && it.elems.isEmpty() }
    if (nonUnitArgs.size == 1) {
        renderPrimitiveFromBytesCall(expr, nonUnitArgs[0], indent, selfTypeName)?.let { return it }
    }
    if (expr.func is Expr.Path && (expr.func as Expr.Path).qself == null && nonUnitArgs.size == 1) {
        val lastFuncSeg = (expr.func as Expr.Path).path.segments.last()
        val rawTransmuteIdent = lastFuncSeg?.ident?.toString()
        if (rawTransmuteIdent == "transmute" || rawTransmuteIdent == "transmute_unchecked") {
            val explicitTypes =
                (lastFuncSeg.arguments as? PathArguments.AngleBracketed)
                    ?.args
                    ?.toList()
                    .orEmpty()
                    .filterIsInstance<GenericArgument.TypeArg>()
                    .map { it.type }
            val sourceValueType = explicitTypes.firstOrNull()?.takeUnless { it is SynType.Infer } ?: sourceInitializerType(nonUnitArgs[0])
            val destinationValueType = explicitTypes.getOrNull(1)?.takeUnless { it is SynType.Infer } ?: currentEnclosingReturnSynType
            if (sourceValueType != null && destinationValueType != null) {
                val sourceLayout = sourcePointerLayout(sourceValueType, selfTypeName)
                val destinationLayout = sourcePointerLayout(destinationValueType, selfTypeName)
                if (sourceLayout != null && destinationLayout != null) {
                    val value = renderExpr(nonUnitArgs[0], indent, selfTypeName)
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.transmuteWithLayouts")
                    return "transmuteWithLayouts($value, $sourceLayout, $destinationLayout)"
                }
            }
            val tfTypes =
                (lastFuncSeg.arguments as? PathArguments.AngleBracketed)
                    ?.args
                    ?.toList()
                    ?.mapNotNull { (it as? GenericArgument.TypeArg)?.type }
                    .orEmpty()
            if (tfTypes.size == 2 && renderType(tfTypes[0], selfTypeName) == renderType(tfTypes[1], selfTypeName)) {
                return renderExpr(nonUnitArgs[0], indent, selfTypeName)
            }
            if (tfTypes.size == 2) {
                val from = renderType(tfTypes[0], selfTypeName)
                val to = renderType(tfTypes[1], selfTypeName)
                val fromField = transparentPointerField(from, selfTypeName)
                if (fromField != null && fromField.second == to) {
                    return "(${renderExpr(nonUnitArgs[0], indent, selfTypeName)}).${fromField.first}"
                }
                val toField = transparentPointerField(to, selfTypeName)
                if (toField != null && toField.second == from) {
                    return "$to(${renderExpr(nonUnitArgs[0], indent, selfTypeName)})"
                }
            }
            if ("transmute" !in inspector.declaredTopLevelFnRawNames &&
                "transmute_unchecked" !in inspector.declaredTopLevelFnRawNames
            ) {
                val fnName = if (rawTransmuteIdent == "transmute_unchecked") "transmuteUnchecked" else "transmute"
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                if (headerContext.packageName != "$coreNs.mem") {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.$fnName")
                }
                return "$fnName(${renderExpr(nonUnitArgs[0], indent, selfTypeName)})"
            }
        }
    }
    val callee = renderCalleeExpr(expr.func, indent, selfTypeName)
    val calleeBase = callee.substringBefore('<').trim()
    return when {
        nonUnitArgs.isEmpty() &&
            "null" !in inspector.declaredTopLevelFnRawNames &&
            "null_mut" !in inspector.declaredTopLevelFnRawNames &&
            (
                calleeBase == "null" ||
                    calleeBase == "nullMut" ||
                    calleeBase == "null_mut" ||
                    calleeBase == "nullVal" ||
                    calleeBase == "ptr.null" ||
                    calleeBase == "ptr.nullMut" ||
                    calleeBase == "ptr.null_mut" ||
                    calleeBase.endsWith(".nullVal") ||
                    calleeBase.endsWith(".ptr.null") ||
                    calleeBase.endsWith(".ptr.nullMut") ||
                    calleeBase.endsWith(".ptr.null_mut")
            ) ->
            if (headerContext.rawAbiMode) {
                "0L"
            } else {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                val tfSuffix = if ('<' in callee) "<${callee.substringAfter('<')}" else ""
                val wantMut =
                    "Mut" in calleeBase ||
                        "mut" in calleeBase ||
                        isMutRawPointerRenderedType(currentExpectedParseTargetType ?: currentEnclosingReturnRenderedType)
                if (wantMut) {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.MutPtr")
                    "MutPtr$tfSuffix(0L)"
                } else {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.ConstPtr")
                    "ConstPtr$tfSuffix(0L)"
                }
            }
        nonUnitArgs.size == 1 &&
            "drop" !in inspector.declaredTopLevelFnRawNames &&
            (calleeBase == "drop" || calleeBase == "mem.drop" || calleeBase.endsWith(".mem.drop")) ->
            "(${renderExpr(nonUnitArgs[0], indent, selfTypeName)}).let { }"
        nonUnitArgs.size == 2 &&
            nonUnitArgs.size == 2 &&
            (
                calleeBase == "fromRawParts" ||
                    calleeBase == "from_raw_parts" ||
                    calleeBase == "fromRawPartsMut" ||
                    calleeBase == "from_raw_parts_mut" ||
                    calleeBase == "slice.fromRawParts" ||
                    calleeBase == "slice.from_raw_parts" ||
                    calleeBase == "slice.fromRawPartsMut" ||
                    calleeBase == "slice.from_raw_parts_mut" ||
                    calleeBase.endsWith(".slice.fromRawParts") ||
                    calleeBase.endsWith(".slice.from_raw_parts") ||
                    calleeBase.endsWith(".slice.fromRawPartsMut") ||
                    calleeBase.endsWith(".slice.from_raw_parts_mut")
            ) -> {
            val isMutSlice = calleeBase.endsWith("Mut") || calleeBase.endsWith("_mut")
            if ("core" in headerContext.crateNamespaces) {
                val helper = if (isMutSlice) "linearMutSliceView" else "linearSliceView"
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.$helper")
                val ptrRendered = renderExpr(nonUnitArgs[0], indent, selfTypeName)
                val lenRendered = coerceRenderedNumericExpr(nonUnitArgs[1], renderExpr(nonUnitArgs[1], indent, selfTypeName), NumericKind.I32)
                val elemLayout = resolveRawPointerPointeeLayout(nonUnitArgs[0], selfTypeName)
                "$helper($ptrRendered, $lenRendered, $elemLayout)"
            } else {
                "$callee(${nonUnitArgs.joinToString(", ") { renderExpr(it, indent, selfTypeName) }})"
            }
        }
        nonUnitArgs.isEmpty() &&
            (
                calleeBase == "MaybeUninit.zeroed" ||
                    calleeBase == "MaybeUninit.uninit" ||
                    calleeBase.endsWith(".MaybeUninit.zeroed") ||
                    calleeBase.endsWith(".MaybeUninit.uninit")
            ) -> {
            val muMethod = if (calleeBase.endsWith("zeroed")) "zeroed" else "uninit"
            val elemTy = resolveMaybeUninitElementRenderedType(expr, callee, selfTypeName)
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.maybeuninit.MaybeUninit")
            val layoutExpr = resolveRenderedTypeLayoutExpr(elemTy)
            "MaybeUninit.$muMethod($layoutExpr)"
        }
        (calleeBase == "MaybeUninit.new" || calleeBase.endsWith(".MaybeUninit.new")) && nonUnitArgs.size == 1 -> {
            val argRendered = renderExpr(nonUnitArgs[0], indent, selfTypeName)
            val inferredArgTy = inferExprRenderedType(nonUnitArgs[0], selfTypeName)
            val elemTy =
                inferredArgTy?.takeIf { it.isNotEmpty() && it != "Any?" }
                    ?: resolveMaybeUninitElementRenderedType(expr, callee, selfTypeName)
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.maybeuninit.MaybeUninit")
            val layoutExpr = resolveRenderedTypeLayoutExpr(elemTy)
            "MaybeUninit.new($argRendered, $layoutExpr)"
        }
        nonUnitArgs.isEmpty() &&
            (
                calleeBase == "zeroed" ||
                    calleeBase == "mem.zeroed" ||
                    calleeBase.endsWith(".mem.zeroed")
            ) ->
            if (headerContext.rawAbiMode) {
                val retT = currentEnclosingReturnRenderedType
                if (retT != null) renderFallbackDefaultForRenderedType(retT) else "0L"
            } else {
                val targetType =
                    (currentExpectedParseTargetType ?: currentEnclosingReturnRenderedType)
                        ?.trim()
                        ?.removeSuffix("?")
                        ?.takeIf { it.isNotEmpty() && it != "Any" && it != "Unit" }
                if (targetType == null) {
                    "null"
                } else {
                    val fallback = renderFallbackDefaultForRenderedType(targetType)
                    if (!fallback.startsWith("null as ") && fallback != "null" && !fallback.startsWith("error(")) {
                        fallback
                    } else {
                        val baseSimple = targetType.substringBefore('<').substringAfterLast('.').trim()
                        val impPkg = importedSimpleNameToPkg[baseSimple]
                        val libcNs = FfiInteropCollector.resolveLibcNamespace(headerContext)
                        val isExternalFfiStruct =
                            (libcNs != null && (targetType.startsWith("$libcNs.") || impPkg == libcNs || impPkg?.startsWith("$libcNs.") == true)) ||
                                impPkg?.contains(".libc") == true ||
                                impPkg?.contains(".wasip1") == true ||
                                impPkg?.contains(".windowssys") == true ||
                                (
                                    targetType in inspector.declaredTypeNames ||
                                        sameFileUniqueSimpleStruct(baseSimple)
                                            ?.let { StructLayoutComputer.computeStructLayout(it, this) }
                                            ?.requiredTypeLayoutParams
                                            ?.isNotEmpty() == false
                                )
                        val structDecl = sameFileUniqueSimpleStruct(baseSimple)
                        val computedLayout =
                            if (structDecl != null) {
                                StructLayoutComputer.computeStructLayout(structDecl, this)
                            } else {
                                null
                            }
                        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                        when {
                            computedLayout != null && computedLayout.requiredTypeLayoutParams.isEmpty() ->
                                CoreIntrinsicLowering.renderZeroedLayoutReadExpression(
                                    "$baseSimple.typeLayout()",
                                    "$coreNs.intrinsics",
                                    this,
                                )
                            isExternalFfiStruct && '<' !in targetType -> "$targetType()"
                            else -> {
                                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.maybeuninit.MaybeUninit")
                                val layoutExpr = resolveRenderedTypeLayoutExpr(targetType)
                                "MaybeUninit.zeroed($layoutExpr).assumeInit()"
                            }
                        }
                    }
                }
            }
        (calleeBase == "Padding.uninit" || calleeBase.endsWith(".Padding.uninit")) && nonUnitArgs.isEmpty() ->
            if (currentExpectedParseTargetType in PRIMITIVE_ARRAY_TYPES) {
                "$currentExpectedParseTargetType(0)"
            } else {
                renderIntLiteral("0", "")
            }
        (calleeBase == "Padding.new" || calleeBase.endsWith(".Padding.new")) && nonUnitArgs.size == 1 ->
            if (currentExpectedParseTargetType != null && currentExpectedParseTargetType in PRIMITIVE_ARRAY_TYPES) {
                renderStructPrimitiveArrayFieldInit(nonUnitArgs[0], currentExpectedParseTargetType!!, indent, selfTypeName)
            } else {
                renderTypedNumericInitializer(nonUnitArgs[0], currentExpectedNumericKind, indent, selfTypeName)
            }
        nonUnitArgs.size == 1 &&
            (
                calleeBase == "fromRef" ||
                    calleeBase == "from_ref" ||
                    calleeBase == "fromMut" ||
                    calleeBase == "from_mut" ||
                    calleeBase == "ptr.fromRef" ||
                    calleeBase == "ptr.from_ref" ||
                    calleeBase == "ptr.fromMut" ||
                    calleeBase == "ptr.from_mut" ||
                    calleeBase.endsWith(".ptr.fromRef") ||
                    calleeBase.endsWith(".ptr.from_ref") ||
                    calleeBase.endsWith(".ptr.fromMut") ||
                    calleeBase.endsWith(".ptr.from_mut")
            ) ->
            renderExpr(nonUnitArgs[0], indent, selfTypeName)
        (calleeBase == "typeName" || calleeBase == "type_name" || calleeBase.endsWith(".any.typeName") || calleeBase.endsWith(".any.type_name")) &&
            nonUnitArgs.isEmpty() ->
            renderKotlinDoubleQuotedString(selfTypeName?.substringBefore('<')?.trim() ?: "Any")
        (calleeBase == "TypeId.of" || calleeBase == "RustTypeId.of" || calleeBase.endsWith(".TypeId.of") || calleeBase.endsWith(".RustTypeId.of")) &&
            nonUnitArgs.isEmpty() -> {
            usesRustAny = true
            val tfSuffix = if ('<' in callee) "<${callee.substringAfter('<')}" else ""
            "RustTypeId.of$tfSuffix()"
        }
        callee == "Some" && "Some" !in inspector.declaredTypeNames && !hasDeclaredOptionEnum() && nonUnitArgs.size == 1 -> {
            val enclosingOpt = currentEnclosingReturnRenderedType?.takeIf { it.endsWith("?") }?.removeSuffix("?")
            val optKind = enclosingOpt?.let(this::resolveNumericKind) ?: currentExpectedNumericKind
            val savedParseTarget = currentExpectedParseTargetType
            currentExpectedParseTargetType = savedParseTarget?.removeSuffix("?")
            try {
                renderTypedNumericInitializer(nonUnitArgs[0], optKind, indent, selfTypeName)
            } finally {
                currentExpectedParseTargetType = savedParseTarget
            }
        }
        callee == "Some" && "Some" !in inspector.declaredTypeNames && !hasDeclaredOptionEnum() && rawArgs.size == 1 && nonUnitArgs.isEmpty() ->
            "Unit"
        (callee == "Ok" || callee == "Result.Ok" || callee == "RustResult.Ok" || callee == "Err" || callee == "Result.Err" || callee == "RustResult.Err") &&
            !hasDeclaredResultEnum() && callee.substringAfterLast('.') !in inspector.declaredTypeNames && rawArgs.size == 1 ->
            renderSourceResultConstructor(expr, callee.substringAfterLast('.'), indent, selfTypeName)
        (callee == "Ok" || callee.endsWith(".Ok")) && rawArgs.size == 1 && nonUnitArgs.isEmpty() ->
            if (currentEnclosingReturnRenderedType == "Unit") "Unit" else "$callee(Unit)"
        nonUnitArgs.size == 1 &&
            "Cow" !in inspector.declaredTypeNames &&
            (
                callee == "Cow.Borrowed" ||
                    callee == "Cow.Owned" ||
                    callee.endsWith(".Cow.Borrowed") ||
                    callee.endsWith(".Cow.Owned")
            ) ->
            renderExpr(nonUnitArgs[0], indent, selfTypeName)
        nonUnitArgs.size == 1 &&
            (
                (callee == "Arc.clone" && "Arc" !in inspector.declaredTypeNames) ||
                    (callee == "Rc.clone" && "Rc" !in inspector.declaredTypeNames) ||
                    (callee.endsWith(".Arc.clone") && "Arc" !in inspector.declaredTypeNames) ||
                    (callee.endsWith(".Rc.clone") && "Rc" !in inspector.declaredTypeNames)
            ) -> {
            val innerArg = unwrapParenOrGroupExpr(nonUnitArgs[0])
            val targetArg = if (innerArg is Expr.Reference) innerArg.expr else nonUnitArgs[0]
            renderExpr(targetArg, indent, selfTypeName)
        }
        nonUnitArgs.size == 1 &&
            (
                (callee == "Box.new" && "Box" !in inspector.declaredTypeNames) ||
                    (callee == "Rc.new" && "Rc" !in inspector.declaredTypeNames) ||
                    (callee == "Arc.new" && "Arc" !in inspector.declaredTypeNames) ||
                    (callee == "NoDrop.new" && "NoDrop" !in inspector.declaredTypeNames) ||
                    (callee == "ManuallyDrop.new" && "ManuallyDrop" !in inspector.declaredTypeNames)
            ) ->
            renderExpr(nonUnitArgs[0], indent, selfTypeName)
        callee == "Vec.new" && nonUnitArgs.isEmpty() && "Vec" !in inspector.declaredTypeNames ->
            "mutableListOf()"
        callee == "Vec.withCapacity" && nonUnitArgs.size == 1 && "Vec" !in inspector.declaredTypeNames ->
            "ArrayList(${renderExpr(nonUnitArgs[0], indent, selfTypeName)})"
        callee == "Vec.from" && nonUnitArgs.size == 1 && "Vec" !in inspector.declaredTypeNames ->
            "${renderExpr(nonUnitArgs[0], indent, selfTypeName)}.toMutableList()"
        (calleeBase == "String.new" || calleeBase == "String.withCapacity") &&
            nonUnitArgs.size <= 1 &&
            "String" !in inspector.declaredTypeNames ->
            "\"\""
        calleeBase == "String.from" && nonUnitArgs.size == 1 && "String" !in inspector.declaredTypeNames -> {
            val argExpr = nonUnitArgs[0]
            val renderedArg = renderExpr(argExpr, indent, selfTypeName)
            if ((unwrapParenOrGroupExpr(argExpr) as? Expr.Lit)?.lit is Lit.Str ||
                inferExprRenderedType(argExpr, selfTypeName)?.removeSuffix("?")?.trim() == "String"
            ) {
                renderedArg
            } else {
                "($renderedArg).toString()"
            }
        }
        (
            calleeBase == "HashMap.new" || calleeBase == "BTreeMap.new" ||
                calleeBase == "HashMap.withCapacity" || calleeBase == "BTreeMap.withCapacity"
        ) &&
            nonUnitArgs.size <= 1 &&
            "HashMap" !in inspector.declaredTypeNames &&
            "BTreeMap" !in inspector.declaredTypeNames -> {
            val tfSuffix = if ('<' in callee) "<${callee.substringAfter('<')}" else ""
            "mutableMapOf$tfSuffix()"
        }
        (calleeBase == "HashMap.from" || calleeBase == "BTreeMap.from") &&
            nonUnitArgs.size == 1 &&
            "HashMap" !in inspector.declaredTypeNames &&
            "BTreeMap" !in inspector.declaredTypeNames -> {
            val tfSuffix = if ('<' in callee) "<${callee.substringAfter('<')}" else ""
            val arrExpr = unwrapParenOrGroupExpr(nonUnitArgs[0]) as? Expr.Array
            val pairEntries =
                arrExpr?.elems?.toList()?.mapNotNull { elem ->
                    val tup = unwrapParenOrGroupExpr(elem) as? Expr.Tuple
                    val parts = tup?.elems?.toList().orEmpty()
                    if (parts.size == 2) parts[0] to parts[1] else null
                }
            if (arrExpr != null && pairEntries != null && pairEntries.size == arrExpr.elems.toList().size) {
                val entries =
                    pairEntries.joinToString(", ") { (kExpr, vExpr) ->
                        "${renderExpr(kExpr, indent, selfTypeName)} to ${renderExpr(vExpr, indent, selfTypeName)}"
                    }
                "mutableMapOf$tfSuffix($entries)"
            } else {
                "(${renderExpr(nonUnitArgs[0], indent, selfTypeName)}).toMap(LinkedHashMap())"
            }
        }
        (
            calleeBase == "HashSet.new" || calleeBase == "BTreeSet.new" ||
                calleeBase == "HashSet.withCapacity" || calleeBase == "BTreeSet.withCapacity"
        ) &&
            nonUnitArgs.size <= 1 &&
            "HashSet" !in inspector.declaredTypeNames &&
            "BTreeSet" !in inspector.declaredTypeNames -> {
            val tfSuffix = if ('<' in callee) "<${callee.substringAfter('<')}" else ""
            "mutableSetOf$tfSuffix()"
        }
        (calleeBase == "HashSet.from" || calleeBase == "BTreeSet.from") &&
            nonUnitArgs.size == 1 &&
            "HashSet" !in inspector.declaredTypeNames &&
            "BTreeSet" !in inspector.declaredTypeNames -> {
            val tfSuffix = if ('<' in callee) "<${callee.substringAfter('<')}" else ""
            val arrExpr = unwrapParenOrGroupExpr(nonUnitArgs[0]) as? Expr.Array
            if (arrExpr != null) {
                val elems = arrExpr.elems.toList().joinToString(", ") { renderExpr(it, indent, selfTypeName) }
                "mutableSetOf$tfSuffix($elems)"
            } else {
                "(${renderExpr(nonUnitArgs[0], indent, selfTypeName)}).toMutableSet()"
            }
        }
        headerContext.scalarChar &&
            "Char" !in inspector.declaredTypeNames &&
            "RustChar" !in inspector.declaredTypeNames &&
            (calleeBase == "Char.fromU32" || calleeBase == "RustChar.fromU32") &&
            nonUnitArgs.size == 1 -> {
            usesRustChar = true
            "RustChar.fromU32(${renderTypedNumericInitializer(nonUnitArgs[0], NumericKind.U32, indent, selfTypeName)})"
        }
        headerContext.scalarChar &&
            "Char" !in inspector.declaredTypeNames &&
            "RustChar" !in inspector.declaredTypeNames &&
            (calleeBase == "Char.fromU32Unchecked" || calleeBase == "RustChar.fromU32Unchecked") &&
            nonUnitArgs.size == 1 -> {
            usesRustChar = true
            "RustChar.fromU32Unchecked(${renderTypedNumericInitializer(nonUnitArgs[0], NumericKind.U32, indent, selfTypeName)})"
        }
        headerContext.scalarChar &&
            "Char" !in inspector.declaredTypeNames &&
            "RustChar" !in inspector.declaredTypeNames &&
            (calleeBase == "Char.from" || calleeBase == "RustChar.from") &&
            nonUnitArgs.size == 1 -> {
            usesRustChar = true
            val srcKind = inferExprNumericKind(nonUnitArgs[0], selfTypeName)
            if (srcKind == NumericKind.U8) {
                "RustChar.fromUByte(${renderTypedNumericInitializer(nonUnitArgs[0], NumericKind.U8, indent, selfTypeName)})"
            } else {
                "RustChar.fromU32Unchecked(${renderTypedNumericInitializer(nonUnitArgs[0], NumericKind.U32, indent, selfTypeName)})"
            }
        }
        headerContext.scalarChar &&
            "Char" !in inspector.declaredTypeNames &&
            "RustChar" !in inspector.declaredTypeNames &&
            (calleeBase == "Char.tryFrom" || calleeBase == "RustChar.tryFrom") &&
            nonUnitArgs.size == 1 -> {
            usesRustChar = true
            val srcKind = inferExprNumericKind(nonUnitArgs[0], selfTypeName)
            if (srcKind == NumericKind.U8) {
                "RustChar.tryFrom(${renderTypedNumericInitializer(nonUnitArgs[0], NumericKind.U8, indent, selfTypeName)})"
            } else {
                "RustChar.tryFrom(${renderTypedNumericInitializer(nonUnitArgs[0], NumericKind.U32, indent, selfTypeName)})"
            }
        }
        headerContext.scalarChar &&
            calleeBase == "UInt.from" &&
            nonUnitArgs.size == 1 &&
            (
                inferExprRenderedType(nonUnitArgs[0], selfTypeName)?.removeSuffix("?")?.trim() == "RustChar" ||
                    (unwrapParenOrGroupExpr(nonUnitArgs[0]) as? Expr.Lit)?.lit is Lit.Char
            ) -> {
            usesRustChar = true
            "(${renderExpr(nonUnitArgs[0], indent, selfTypeName)}).code"
        }
        headerContext.scalarChar &&
            calleeBase == "UByte.tryFrom" &&
            nonUnitArgs.size == 1 &&
            inferExprRenderedType(nonUnitArgs[0], selfTypeName)?.removeSuffix("?")?.trim() == "RustChar" -> {
            usesRustChar = true
            "(${renderExpr(nonUnitArgs[0], indent, selfTypeName)}).tryToUByte()"
        }
        callee == "VecDeque.from" && nonUnitArgs.size == 1 && "VecDeque" !in inspector.declaredTypeNames ->
            "ArrayDeque(${renderExpr(nonUnitArgs[0], indent, selfTypeName)})"
        (callee == "empty" || callee == "iter.empty") && nonUnitArgs.isEmpty() && "empty" !in topLevelSignatureCounts ->
            "ArrayDeque()"
        (calleeBase == "once" || calleeBase == "iter.once" || calleeBase.endsWith(".iter.once")) &&
            nonUnitArgs.size == 1 &&
            "once" !in topLevelSignatureCounts &&
            "once" !in inspector.declaredTopLevelFnRawNames -> {
            val tfSuffix = if ('<' in callee) "<${callee.substringAfter('<')}" else ""
            "listOf$tfSuffix(${renderExpr(nonUnitArgs[0], indent, selfTypeName)})"
        }
        (callee == "min" || callee == "cmp.min") && nonUnitArgs.size == 2 && "min" !in inspector.declaredTopLevelFnRawNames ->
            "minOf(${renderExpr(nonUnitArgs[0], indent, selfTypeName)}, ${renderExpr(nonUnitArgs[1], indent, selfTypeName)})"
        isFmtTraitMethodCallee(calleeBase) && nonUnitArgs.size == 2 ->
            "${renderExpr(nonUnitArgs[1], indent, selfTypeName)}.append((${renderExpr(nonUnitArgs[0], indent, selfTypeName)}).toString())"
        callee in BYTE_SWAP_CALLEE_KIND && nonUnitArgs.size == 1 -> {
            val renderedArg = renderExpr(nonUnitArgs[0], indent, selfTypeName)
            val swapKind = BYTE_SWAP_CALLEE_KIND[callee]
            if (swapKind == null) renderedArg else renderPrimitiveByteSwapExpr(renderedArg, swapKind)
        }
        (callee == "Float.min" || callee == "kotlin.Float.min" || callee == "Double.min") && nonUnitArgs.size == 2 -> {
            val a = renderExpr(nonUnitArgs[0], indent, selfTypeName)
            val b = renderExpr(nonUnitArgs[1], indent, selfTypeName)
            "run { val lhsMin0 = ($a); val rhsMin0 = ($b); when { lhsMin0.isNaN() -> rhsMin0; rhsMin0.isNaN() -> lhsMin0; else -> minOf(lhsMin0, rhsMin0) } }"
        }
        (callee == "Float.max" || callee == "kotlin.Float.max" || callee == "Double.max") && nonUnitArgs.size == 2 -> {
            val a = renderExpr(nonUnitArgs[0], indent, selfTypeName)
            val b = renderExpr(nonUnitArgs[1], indent, selfTypeName)
            "run { val lhsMax0 = ($a); val rhsMax0 = ($b); when { lhsMax0.isNaN() -> rhsMax0; rhsMax0.isNaN() -> lhsMax0; else -> maxOf(lhsMax0, rhsMax0) } }"
        }
        callee in setOf("Byte.min", "UByte.min", "Short.min", "UShort.min", "Int.min", "UInt.min", "Long.min", "ULong.min") &&
            nonUnitArgs.size == 2 ->
            "minOf(${renderExpr(nonUnitArgs[0], indent, selfTypeName)}, ${renderExpr(nonUnitArgs[1], indent, selfTypeName)})"
        callee in setOf("Byte.max", "UByte.max", "Short.max", "UShort.max", "Int.max", "UInt.max", "Long.max", "ULong.max") &&
            nonUnitArgs.size == 2 ->
            "maxOf(${renderExpr(nonUnitArgs[0], indent, selfTypeName)}, ${renderExpr(nonUnitArgs[1], indent, selfTypeName)})"
        else -> {
            renderStaticPrimitiveCall(expr, nonUnitArgs, indent, selfTypeName)?.let { return it }
            val calleeBase = callee.substringBefore('<')
            val calleeLookupKeys =
                buildList {
                    for (cand in listOf(callee, calleeBase).distinct()) {
                        add(cand)
                        addAll(scopedAbiKeysForRenderedType(cand))
                        val nsPrefix = currentNamespacePrefix()
                        if (nsPrefix != null && cand.startsWith("$nsPrefix.")) {
                            add(cand.removePrefix("$nsPrefix."))
                        }
                        val parts = cand.split('.')
                        if (parts.size >= 2) {
                            add("${parts[parts.size - 2]}.${parts.last()}")
                        }
                        if (parts.size >= 1) {
                            val curSub = currentSubpackageString()
                            if (curSub.isNotEmpty()) {
                                add("$curSub.$cand")
                            }
                        }
                    }
                }.distinct()
            val calleeParts = calleeBase.split('.')
            val leadingPkgSegs =
                calleeParts
                    .dropLast(1)
                    .takeWhile { seg -> seg.firstOrNull()?.isLowerCase() == true }
            val resolvedCalleePkg =
                if (leadingPkgSegs.isNotEmpty()) {
                    leadingPkgSegs.joinToString(".")
                } else {
                    importedSimpleNameToPkg[calleeParts.first()]
                }
            val selfNsPrefix = currentNamespacePrefix()
            val isExternalCalleePkg =
                !resolvedCalleePkg.isNullOrEmpty() &&
                    (
                        selfNsPrefix == null ||
                            (resolvedCalleePkg != selfNsPrefix && !resolvedCalleePkg.startsWith("$selfNsPrefix."))
                    )
            val calleeParamNumericKinds =
                if (isExternalCalleePkg) {
                    null
                } else {
                    calleeLookupKeys
                        .firstNotNullOfOrNull { k ->
                            currentFunctionParamNumericKinds["$k#${nonUnitArgs.size}"]
                                ?: currentFunctionParamNumericKinds[k]?.takeIf { it.size == nonUnitArgs.size }
                        }
                }
            val subslicePreStmts = mutableListOf<String>()
            val subslicePostStmts = mutableListOf<String>()
            val savedKind = currentExpectedNumericKind
            val savedParseTarget = currentExpectedParseTargetType
            currentExpectedNumericKind = null
            currentExpectedParseTargetType = null
            val renderedArgList =
                try {
                    nonUnitArgs.mapIndexed { argIdx, argExpr ->
                        val subsliceSpec =
                            tryExtractMutableArraySubsliceArg(
                                argExpr = argExpr,
                                argIndex = argIdx,
                                expectMutableList = calleeExpectsMutableListSliceParam(calleeBase, argIdx),
                                indent = indent,
                                selfTypeName = selfTypeName,
                            )
                        if (subsliceSpec != null) {
                            subslicePreStmts += subsliceSpec.first
                            subslicePostStmts += subsliceSpec.third
                            return@mapIndexed subsliceSpec.second
                        }
                        val paramKind = calleeParamNumericKinds?.get(argIdx)
                        if ((callee == "ioctl" || callee.endsWith(".ioctl")) && argIdx >= 2) {
                            renderVariadicI64OrPtrArg(
                                argExpr = argExpr,
                                allowAddressablePtr = true,
                                preferI64Init = false,
                                indent = indent,
                                selfTypeName = selfTypeName,
                            )
                        } else if (callee == "syscall" || callee.endsWith(".syscall")) {
                            renderVariadicI64OrPtrArg(
                                argExpr = argExpr,
                                allowAddressablePtr = argIdx > 0,
                                preferI64Init = argIdx == 0,
                                indent = indent,
                                selfTypeName = selfTypeName,
                            )
                        } else if (paramKind != null) {
                            currentExpectedParseTargetType = null
                            renderTypedNumericInitializer(argExpr, paramKind, indent, selfTypeName)
                        } else {
                            currentExpectedNumericKind = null
                            val paramSynTy = resolveCalleeExprParamSynType(expr.func, argIdx, selfTypeName)
                            val paramParseTarget =
                                paramSynTy
                                    ?.let { synTy ->
                                        val rawRendered = renderType(synTy, selfTypeName).removeSuffix("?").trim()
                                        if (rawRendered.startsWith("List<") ||
                                            rawRendered.startsWith("MutableList<") ||
                                            rawRendered in PRIMITIVE_ARRAY_TYPES
                                        ) {
                                            rawRendered
                                        } else {
                                            null
                                        }
                                    }
                            currentExpectedParseTargetType = paramParseTarget
                            val renderedArg =
                                try {
                                    renderExpr(argExpr, indent, selfTypeName)
                                } finally {
                                    currentExpectedParseTargetType = null
                                }
                            val rawCallSegs =
                                (unwrapParenOrGroupExpr(expr.func) as? Expr.Path)
                                    ?.takeIf { it.qself == null }
                                    ?.path
                                    ?.segments
                                    ?.toList()
                                    ?.map { it.ident.toString().removePrefix("r#") }
                                    .orEmpty()
                            if (isStdlibConstPtrCallParam(rawCallSegs, paramSynTy, argIdx)) {
                                coerceMutPtrArgToConstPtr(renderedArg, inferExprRenderedType(argExpr, selfTypeName))
                            } else {
                                renderedArg
                            }
                        }
                    }
                } finally {
                    currentExpectedNumericKind = savedKind
                    currentExpectedParseTargetType = savedParseTarget
                }
            val args = renderedArgList.joinToString(", ")
            if (renderedArgList.size == 1 && renderedArgList[0] == "this" && '.' in callee) {
                val ownerPart = callee.substringBeforeLast('.')
                val methodPart = callee.substringAfterLast('.')
                if (ownerPart in inspector.attachableTypeNames &&
                    methodPart in inspector.instanceMethodsByType[ownerPart].orEmpty()
                ) {
                    return "this.$methodPart()"
                }
            }
            if (renderedArgList.isNotEmpty() && '.' in calleeBase) {
                val ownerSimple = calleeBase.substringBeforeLast('.').substringAfterLast('.').trim()
                val methodPart = callee.substringAfterLast('.')
                val methodBase = methodPart.substringBefore('<').trim()
                val traitDecl = lookupDeclaredTraitAst(ownerSimple)
                val isUfcsTraitReceiverCall =
                    when {
                        traitDecl != null ->
                            traitDecl.items.any { tItem ->
                                tItem is TraitItem.Fn &&
                                    rustFunctionIdentToKotlin(tItem.sig.ident.toString()) == methodBase &&
                                    tItem.sig.inputs.firstOrNull() is FnArg.Receiver &&
                                    tItem.sig.inputs.size == renderedArgList.size
                            }
                        ownerSimple in activeTypeParamNames -> {
                            val boundTraits = currentTypeParamTraitBounds[ownerSimple].orEmpty()
                            val traitHasReceiverMethod =
                                boundTraits.any { tName ->
                                    lookupDeclaredTraitAst(tName)?.items?.any { tItem ->
                                        tItem is TraitItem.Fn &&
                                            rustFunctionIdentToKotlin(tItem.sig.ident.toString()) == methodBase &&
                                            tItem.sig.inputs.firstOrNull() is FnArg.Receiver &&
                                            tItem.sig.inputs.size == renderedArgList.size
                                    } == true
                                }
                            val isKnownStdReceiverMethod =
                                methodBase in setOf("clone", "cmp", "partialCmp", "eq", "ne", "hash", "fmt", "deref", "derefMut", "asRef", "borrow", "borrowMut", "toString", "toOwned")
                            (traitHasReceiverMethod || isKnownStdReceiverMethod) &&
                                currentTypeParamWitnessCalls.none {
                                    it.key.first == ownerSimple && rustFunctionIdentToKotlin(it.key.second) == methodBase
                                }
                        }
                        else -> {
                            val arg0Ty =
                                inferExprRenderedType(nonUnitArgs[0], selfTypeName)
                                    ?.let { mutableReferencePointeeType(it) ?: it }
                                    ?.removeSuffix("?")
                                    ?.trim()
                            arg0Ty != null &&
                                ownerSimple in currentTypeParamTraitBounds[arg0Ty].orEmpty() &&
                                currentTypeParamWitnessCalls.none {
                                    it.key.first == arg0Ty && rustFunctionIdentToKotlin(it.key.second) == methodBase
                                }
                        }
                    }
                if (isUfcsTraitReceiverCall) {
                    val recvAtom = if (isAtomicPostfixReceiver(nonUnitArgs[0], renderedArgList[0])) renderedArgList[0] else "(${renderedArgList[0]})"
                    val restArgs = renderedArgList.drop(1).joinToString(", ")
                    return "$recvAtom.$methodPart($restArgs)"
                }
            }
            val forwardedWitnesses =
                when {
                    callee.endsWith(".parseTerminatedWith") && renderedArgList.size == 2 ->
                        collectForwardedWitnesses("parse", renderedArgList)
                    callee.endsWith(".parseSeparatedNonemptyWith") && renderedArgList.size == 2 ->
                        collectForwardedWitnesses("peek", renderedArgList) + collectForwardedWitnesses("parse", renderedArgList)
                    callee.endsWith(".parseTerminated") && renderedArgList.size == 1 ->
                        collectForwardedWitnesses("parse", renderedArgList)
                    callee.endsWith(".parseSeparatedNonempty") && renderedArgList.size == 1 ->
                        currentTypeParamWitnessCalls.values
                            .filter { it !in renderedArgList }
                            .distinct()
                    else -> emptyList()
                }
            val explicitGenericArgs =
                (
                    (
                        (expr.func as? Expr.Path)
                            ?.path
                            ?.segments
                            ?.last()
                            ?.arguments
                    ) as? PathArguments.AngleBracketed
                )?.args
                    ?.toList()
                    .orEmpty()
            val constValueArgs =
                explicitGenericArgs.mapNotNull { arg ->
                    when (arg) {
                        is GenericArgument.ConstArg -> renderExpr(arg.expr, indent, selfTypeName)
                        is GenericArgument.TypeArg -> runtimeConstTypeArgument(arg)
                        else -> null
                    }
                }
            // Function signatures lift Rust const parameters before
            // trait witnesses. Preserve actual order/volatile values.
            val callOwner = calleeBase.substringBeforeLast('.', missingDelimiterValue = calleeBase)
            val declaration = transparentDeclaration(callOwner)
            val requiresLayout =
                declaration != null && sourceTransparentField(declaration, callOwner) != null &&
                    (
                        calleeBase == callOwner ||
                            file.items.filterIsInstance<Item.Impl>().any { implementation ->
                                renderType(implementation.selfType, selfTypeName).substringBefore('<') == callOwner &&
                                    implementation.items.filterIsInstance<ImplItem.Fn>().any { function ->
                                        rustFunctionIdentToKotlin(function.sig.ident.toString()) == calleeBase.substringAfterLast('.') &&
                                            function.sig.inputs.firstOrNull() !is FnArg.Receiver &&
                                            transparentConstructorField(callOwner + renderTypeArgumentsFromGenerics(declaration.generics), function.block) != null
                                    }
                            }
                    )
            val layoutArgs =
                if (requiresLayout) {
                    listOf("fieldLayout = ${if (calleeBase == callOwner) transparentConstructionLayout(requireNotNull(declaration), selfTypeName) else sourceFactoryCallLayout(expr, requireNotNull(declaration), selfTypeName)}")
                } else {
                    emptyList()
                }
            val explicitArgs = (listOf(args).filter(String::isNotEmpty) + constValueArgs + layoutArgs + CoreIntrinsicLowering.coreIntrinsicCallArguments(expr, selfTypeName, this) + genericFactoryCallArguments(expr, selfTypeName)).joinToString(", ")
            val fullArgs =
                if (forwardedWitnesses.isEmpty()) {
                    explicitArgs
                } else if (explicitArgs.isEmpty()) {
                    forwardedWitnesses.joinToString(", ")
                } else {
                    "$explicitArgs, ${forwardedWitnesses.joinToString(", ")}"
                }
            val rewrittenTraitElemCallee = resolveContainerElementTraitCallee(callee)
            val nonZeroTail = calleeBase.split('.').takeLast(2).joinToString(".")
            val nonZeroTypeArgs =
                if (rewrittenTraitElemCallee == null &&
                    explicitGenericArgs.isEmpty() &&
                    '<' !in callee &&
                    nonUnitArgs.size == 1 &&
                    nonZeroTail in setOf("NonZero.new", "NonZero.newUnchecked")
                ) {
                    resolveNonZeroNewExplicitTypeArgs(nonZeroTail, nonUnitArgs, selfTypeName)
                } else {
                    null
                }
            val callExpr =
                if (rewrittenTraitElemCallee != null) {
                    "$rewrittenTraitElemCallee($fullArgs)"
                } else if (callee.endsWith(".default()") && nonUnitArgs.isNotEmpty()) {
                    "${callee.removeSuffix(".default()")}.from($fullArgs)"
                } else if (nonZeroTypeArgs != null) {
                    "$callee<${nonZeroTypeArgs.first}, ${nonZeroTypeArgs.second}>($fullArgs)"
                } else {
                    "$callee($fullArgs)"
                }
            val finalCallExpr =
                if ((callee == "strlen" || callee.endsWith(".strlen")) &&
                    (currentExpectedNumericKind == NumericKind.I32 || currentEnclosingReturnRenderedType == "Int")
                ) {
                    "$callExpr.toInt()"
                } else {
                    callExpr
                }
            val resolvedFullCallee =
                if ('.' in calleeBase) {
                    calleeBase
                } else {
                    importedSimpleNameToPkg[calleeBase]?.let { "$it.$calleeBase" } ?: calleeBase
                }
            val expectsTwoElemTuple =
                (currentEnclosingReturnSynType as? SynType.Tuple)?.elems?.len() == 2 ||
                    currentEnclosingReturnRenderedType?.let {
                        it.startsWith("Pair<") || it.startsWith("kotlin.Pair<")
                    } == true ||
                    savedParseTarget?.let {
                        it.startsWith("Pair<") || it.startsWith("kotlin.Pair<")
                    } == true
            val pairWrappedCallExpr =
                if (resolvedFullCallee in headerContext.externalCrateTwoFieldResultFns && expectsTwoElemTuple) {
                    val pairCtor = if ("Pair" in inspector.declaredTypeNames) "kotlin.Pair" else "Pair"
                    "($finalCallExpr).let { pairRes0 -> $pairCtor(pairRes0.component1(), pairRes0.component2()) }"
                } else {
                    finalCallExpr
                }
            if (subslicePreStmts.isEmpty()) {
                pairWrappedCallExpr
            } else {
                val preJoined = subslicePreStmts.joinToString("; ")
                val postJoined = subslicePostStmts.joinToString("; ")
                "run { $preJoined; val subCall0 = $pairWrappedCallExpr; $postJoined; subCall0 }"
            }
        }
    }
}

internal fun KotlinJ2KEmitter.renderPlaceOperation(expr: Expr.Call, indent: String, selfTypeName: String?): String? {
    val path = expr.func as? Expr.Path ?: return null
    if (path.qself != null || expr.args.size != 2) return null
    val segments =
        path.path.segments
            .toList()
            .map { it.ident.toString() }
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
    return when (innerTarget) {
        "Abi" -> "Extern"
        "BoundLifetimes" -> "For"
        "WhereClause" -> "Where"
        "Label" -> "Lifetime"
        "Ident",
        "Lifetime",
        "Lit",
        "LitStr",
        "LitByteStr",
        "LitCStr",
        "LitByte",
        "LitChar",
        "LitInt",
        "LitFloat",
        "LitBool",
        "SelfType",
        "SelfValue",
        "SynTypeToken",
        "Underscore",
        -> innerTarget
        in SYN_PUNCT_TOKEN_CLASS_NAMES -> innerTarget
        in SYN_KEYWORD_TOKEN_CLASS_NAMES -> innerTarget
        else ->
            if (innerTarget.startsWith("token.") || innerTarget.startsWith("kw.")) {
                innerTarget
            } else {
                null
            }
    }
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
    val allowedPrefixes = IGNORED_PATH_PREFIXES + setOf("core", "std", "primitive", "half")
    val primRaw =
        if (funcPath.qself != null) {
            if (segs.size != 1) return null
            val qPath = unwrapSynType(funcPath.qself!!.ty) as? SynType.Path ?: return null
            val qSegs = qPath.path.segments.toList()
            if (qSegs.isEmpty()) return null
            if (qSegs.any { it.arguments !is PathArguments.None }) return null
            if (!qSegs.dropLast(1).all { it.ident.toString().removePrefix("r#") in allowedPrefixes }) return null
            qSegs
                .last()
                .ident
                .toString()
                .removePrefix("r#")
        } else {
            if (segs.size < 2) return null
            if (!segs.dropLast(2).all { it.ident.toString().removePrefix("r#") in allowedPrefixes }) return null
            segs[segs.size - 2].ident.toString().removePrefix("r#")
        }
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
    if (primRaw in setOf("u128", "U128", "i128", "I128", "f16", "F16", "f128", "F128")) {
        currentExpectedParseTargetType = "UByteArray"
        val argRendered =
            try {
                renderExpr(argExpr, indent, selfTypeName)
            } finally {
                currentExpectedParseTargetType = savedParseTarget
            }
        val fn = if (isBigEndian) "fromBeBytes" else "fromLeBytes"
        val owner =
            when (primRaw) {
                "u128", "U128" -> {
                    usesU128 = true
                    "U128"
                }
                "i128", "I128" -> {
                    usesI128 = true
                    "I128"
                }
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
    currentExpectedParseTargetType = "UByteArray"
    val bytesRendered =
        try {
            renderExpr(effectiveArg, indent, selfTypeName)
        } finally {
            currentExpectedParseTargetType = savedParseTarget
        }
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
    val segNames =
        unwrapped.path.segments
            .toList()
            .map { it.ident.toString().removePrefix("r#") }
    if ((segNames == listOf("drop") || segNames == listOf("mem", "drop") || segNames == listOf("core", "mem", "drop") || segNames == listOf("std", "mem", "drop")) &&
        scopeBuilder.resolveLocalKotlinName("drop") == null
    ) {
        return "{ Unit }"
    }
    val rawLast =
        unwrapped.path.segments
            .last()
            ?.ident
            ?.toString()
            ?.removePrefix("r#")
            ?: return renderedArg
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

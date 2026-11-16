package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.BinOp
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.RangeLimits
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.UnOp
private enum class DerefPlaceKind {
    RAW_POINTER,
    MUTABLE_REFERENCE,
    MUTABLE_PLACE,
    REF_CELL_GUARD,
}

private fun KotlinJ2KEmitter.classifyDerefPlaceKind(
    expr: Expr,
    selfTypeName: String?,
    includeRefCellGuard: Boolean,
): DerefPlaceKind? {
    val renderedTy = inferExprRenderedType(expr, selfTypeName)
    return when {
        rawPointerPointeeType(renderedTy) != null -> DerefPlaceKind.RAW_POINTER
        mutableReferencePointeeType(renderedTy) != null -> DerefPlaceKind.MUTABLE_REFERENCE
        mutablePlaceValueType(renderedTy) != null -> DerefPlaceKind.MUTABLE_PLACE
        includeRefCellGuard && refCellGuardPointeeType(renderedTy) != null -> DerefPlaceKind.REF_CELL_GUARD
        else -> null
    }
}

internal fun KotlinJ2KEmitter.renderExprBinary(expr: Expr.Binary, indent: String, selfTypeName: String?): String {
    if ((expr.op is BinOp.Eq || expr.op is BinOp.Ne) &&
        (
            isSourceResultConstructor(expr.left) || isSourceResultConstructor(expr.right) ||
                rustResultTypeArguments(inferExprRenderedType(expr.left, selfTypeName)) != null ||
                rustResultTypeArguments(inferExprRenderedType(expr.right, selfTypeName)) != null
        )
    ) {
        return renderSourceEquality(expr.left, expr.right, expr.op is BinOp.Ne, indent, selfTypeName)
    }
    val leftPointer = rawPointerPointeeType(inferExprRenderedType(expr.left, selfTypeName))
    val rightPointer = rawPointerPointeeType(inferExprRenderedType(expr.right, selfTypeName))
    val pointerComparison =
        when (expr.op) {
            is BinOp.Eq -> "=="
            is BinOp.Ne -> "!="
            is BinOp.Lt -> "<"
            is BinOp.Le -> "<="
            is BinOp.Gt -> ">"
            is BinOp.Ge -> ">="
            else -> null
        }
    if (pointerComparison != null && (leftPointer != null || rightPointer != null)) {
        val left = renderExpr(expr.left, indent, selfTypeName)
        val right = renderExpr(expr.right, indent, selfTypeName)
        return "(($left).rawAddress.toULong() $pointerComparison ($right).rawAddress.toULong())"
    }
    val isBitwiseOp =
        when (expr.op) {
            is BinOp.BitAnd, is BinOp.BitOr, is BinOp.BitXor,
            is BinOp.BitAndAssign, is BinOp.BitOrAssign, is BinOp.BitXorAssign,
            -> true
            else -> false
        }
    val isCmpOp =
        when (expr.op) {
            is BinOp.Eq, is BinOp.Ne, is BinOp.Lt, is BinOp.Le, is BinOp.Gt, is BinOp.Ge -> true
            else -> false
        }
    val isBoolOrCmpOp =
        when (expr.op) {
            is BinOp.Eq, is BinOp.Ne, is BinOp.Lt, is BinOp.Le, is BinOp.Gt, is BinOp.Ge,
            is BinOp.And, is BinOp.Or,
            -> true
            else -> false
        }
    val isShiftOp =
        when (expr.op) {
            is BinOp.Shl, is BinOp.Shr, is BinOp.ShlAssign, is BinOp.ShrAssign -> true
            else -> false
        }
    val rawLeftOperand = expr.left
    val derefCompoundKind =
        if (isCompoundAssignOp(expr.op) && rawLeftOperand is Expr.Unary && rawLeftOperand.op is UnOp.Deref) {
            classifyDerefPlaceKind(rawLeftOperand.expr, selfTypeName, includeRefCellGuard = false)
        } else {
            null
        }
    if (derefCompoundKind != null) {
        val derefInner = (rawLeftOperand as Expr.Unary).expr
        val place = renderExpr(derefInner, indent, selfTypeName)
        val rhs = renderExpr(expr.right, indent, selfTypeName)
        val bitOp =
            when (expr.op) {
                is BinOp.BitAndAssign -> "and"
                is BinOp.BitOrAssign -> "or"
                is BinOp.BitXorAssign -> "xor"
                is BinOp.ShlAssign -> "shl"
                is BinOp.ShrAssign -> "shr"
                else -> null
            }
        val update =
            if (bitOp == null) {
                "currentValue ${renderBinOp(expr.op)} ($rhs)"
            } else {
                "currentValue = currentValue $bitOp ($rhs)"
            }
        return when (derefCompoundKind) {
            DerefPlaceKind.MUTABLE_REFERENCE ->
                "($place).let { targetPlace -> var currentValue = targetPlace.read(); $update; targetPlace.write(currentValue) }"
            DerefPlaceKind.RAW_POINTER -> {
                val pointeeLayout = resolveRawPointerPointeeLayout(derefInner, selfTypeName)
                "($place).let { targetPtr -> val targetLayout = $pointeeLayout; var currentValue = targetLayout.readNative(targetPtr.rawAddress); $update; targetLayout.writeNative(targetPtr.rawAddress, currentValue) }"
            }
            DerefPlaceKind.MUTABLE_PLACE ->
                "($place).let { targetPlace -> var currentValue = targetPlace.get(); $update; targetPlace.set(currentValue) }"
            DerefPlaceKind.REF_CELL_GUARD -> error("unreachable")
        }
    }
    val leftOperand =
        if (isCompoundAssignOp(expr.op) && rawLeftOperand is Expr.Unary && rawLeftOperand.op is UnOp.Deref) {
            if (renderBorrowedPlace(rawLeftOperand.expr, indent, selfTypeName) != null) rawLeftOperand else rawLeftOperand.expr
        } else {
            rawLeftOperand
        }
    val rightOperand = expr.right
    val savedKind = currentExpectedNumericKind
    val leftInferredKind = inferExprNumericKind(leftOperand, selfTypeName)
    val rightInferredKind = inferExprNumericKind(rightOperand, selfTypeName)
    val binarySharedKind = leftInferredKind ?: rightInferredKind ?: savedKind
    val rawLeft =
        try {
            currentExpectedNumericKind =
                when {
                    isCmpOp -> rightInferredKind
                    isBoolOrCmpOp -> null
                    isShiftOp -> leftInferredKind ?: savedKind
                    else -> binarySharedKind
                }
            if (isBitwiseOp && leftOperand is Expr.Unary && leftOperand.op is UnOp.NotOp) {
                renderBitwiseComplementOperand(leftOperand.expr, indent, selfTypeName)
            } else {
                renderExpr(leftOperand, indent, selfTypeName)
            }
        } finally {
            currentExpectedNumericKind = savedKind
        }
    val prevSmartBeforeRhs = currentSmartCastNonNullLocals
    if (expr.op is BinOp.And) {
        extractNullCheckedIdent(rawLeft)?.let { (id, isEqNull) ->
            if (!isEqNull) currentSmartCastNonNullLocals = currentSmartCastNonNullLocals + id
        }
    } else if (expr.op is BinOp.Or) {
        extractNullCheckedIdent(rawLeft)?.let { (id, isEqNull) ->
            if (isEqNull) currentSmartCastNonNullLocals = currentSmartCastNonNullLocals + id
        }
    }
    val rawRight =
        try {
            currentExpectedNumericKind =
                when {
                    isCmpOp -> leftInferredKind
                    isShiftOp -> NumericKind.I32
                    isBoolOrCmpOp -> null
                    else -> binarySharedKind
                }
            if (isBitwiseOp && rightOperand is Expr.Unary && rightOperand.op is UnOp.NotOp) {
                renderBitwiseComplementOperand(rightOperand.expr, indent, selfTypeName)
            } else {
                renderExpr(rightOperand, indent, selfTypeName)
            }
        } finally {
            currentExpectedNumericKind = savedKind
            if (expr.op is BinOp.And || expr.op is BinOp.Or) {
                currentSmartCastNonNullLocals = prevSmartBeforeRhs
            }
        }
    val isEqOp = expr.op is BinOp.Eq || expr.op is BinOp.Ne
    val isMismatchedExplicitKinds =
        !isShiftOp &&
            leftInferredKind != null &&
            rightInferredKind != null &&
            leftInferredKind != rightInferredKind &&
            (isBitwiseOp || isEqOp || isUnsignedKind(leftInferredKind) || isUnsignedKind(rightInferredKind))
    val targetPairKind =
        if (isMismatchedExplicitKinds) {
            savedKind ?: leftInferredKind
        } else {
            null
        }
    val left =
        if ((isCmpOp || isBitwiseOp) && rightInferredKind != null && isNarrowComparisonLiteralOperand(leftOperand, rightInferredKind)) {
            coerceRenderedNarrowLiteral(leftOperand, rawLeft, rightInferredKind)
        } else if (targetPairKind != null && leftInferredKind != targetPairKind) {
            coerceRenderedNumericExpr(leftOperand, rawLeft, targetPairKind)
        } else {
            rawLeft
        }
    val isPlainIntLitRight =
        (unwrapParenOrGroupExpr(rightOperand) as? Expr.Lit)?.let {
            (it.lit as? Lit.Int)?.value?.suffix()?.isEmpty() == true
        } == true
    val right =
        if (isShiftOp && !isPlainIntLitRight && rightInferredKind != NumericKind.I32 && !rawRight.endsWith(".toInt()")) {
            val recv = if (isAtomicPostfixReceiver(rightOperand, rawRight)) rawRight else "($rawRight)"
            "$recv.toInt()"
        } else if ((isCmpOp || isBitwiseOp) && leftInferredKind != null && isNarrowComparisonLiteralOperand(rightOperand, leftInferredKind)) {
            coerceRenderedNarrowLiteral(rightOperand, rawRight, leftInferredKind)
        } else if (targetPairKind != null && rightInferredKind != targetPairKind) {
            coerceRenderedNumericExpr(rightOperand, rawRight, targetPairKind)
        } else {
            rawRight
        }
    val compoundBitOp =
        when (expr.op) {
            is BinOp.BitXorAssign -> "xor"
            is BinOp.BitAndAssign -> "and"
            is BinOp.BitOrAssign -> "or"
            is BinOp.ShlAssign -> "shl"
            is BinOp.ShrAssign -> "shr"
            else -> null
        }
    return if (compoundBitOp != null) {
        val safeRight = if (expr.right is Expr.Binary || expr.right is Expr.Cast) "($right)" else right
        val rhsLeft = left.replace("!!.", ".")
        "$left = $rhsLeft $compoundBitOp $safeRight"
    } else if (isBitwiseOp && (binarySharedKind == NumericKind.I8 || binarySharedKind == NumericKind.I16)) {
        val op = renderBinOp(expr.op)
        val lAtom = if (isAtomicPostfixReceiver(leftOperand, left)) left else "($left)"
        val rAtom = if (isAtomicPostfixReceiver(rightOperand, right)) right else "($right)"
        val conv = if (binarySharedKind == NumericKind.I8) "toByte()" else "toShort()"
        "($lAtom.toInt() $op $rAtom.toInt()).$conv"
    } else if (isShiftOp && (binarySharedKind == NumericKind.U8 || binarySharedKind == NumericKind.U16)) {
        val op = renderBinOp(expr.op)
        val lAtom = if (isAtomicPostfixReceiver(leftOperand, left)) left else "($left)"
        val rAtom = if (isAtomicPostfixReceiver(rightOperand, right)) right else "($right)"
        val conv = if (binarySharedKind == NumericKind.U8) "toUByte()" else "toUShort()"
        "($lAtom.toUInt() $op $rAtom).$conv"
    } else if (isEqOp &&
        (
            inferExprRenderedType(leftOperand, selfTypeName)?.let { isPrimitiveArrayRenderedType(it) } == true ||
                inferExprRenderedType(rightOperand, selfTypeName)?.let { isPrimitiveArrayRenderedType(it) } == true
        )
    ) {
        val lAtom = if (isAtomicPostfixReceiver(leftOperand, left)) left else "($left)"
        if (expr.op is BinOp.Eq) "$lAtom.contentEquals($right)" else "!$lAtom.contentEquals($right)"
    } else if ("core" in headerContext.crateNamespaces &&
        (expr.op is BinOp.Lt || expr.op is BinOp.Le || expr.op is BinOp.Gt || expr.op is BinOp.Ge) &&
        run {
            val lBase =
                inferExprRenderedType(leftOperand, selfTypeName)
                    ?.removeSuffix("?")
                    ?.substringAfterLast('.')
                    ?.substringBefore('<')
                    ?.trim()
            val rBase =
                inferExprRenderedType(rightOperand, selfTypeName)
                    ?.removeSuffix("?")
                    ?.substringAfterLast('.')
                    ?.substringBefore('<')
                    ?.trim()
            lBase == "Wrapping" ||
                rBase == "Wrapping" ||
                (
                    leftInferredKind == null &&
                        rightInferredKind == null &&
                        (lBase in activeTypeParamNames || rBase in activeTypeParamNames)
                )
        }
    ) {
        registerCoreMemberImport("mem", "kmCompareValues")
        val op = renderBinOp(expr.op)
        "(kmCompareValues($left, $right) $op 0)"
    } else {
        val op = renderBinOp(expr.op)
        if (headerContext.isBatchCrateOutput && right.contains('\n')) {
            "$left $op\n$indent    ${right.replace("\n", "\n    ")}"
        } else {
            "$left $op $right"
        }
    }
}

internal fun KotlinJ2KEmitter.renderExprUnary(expr: Expr.Unary, indent: String, selfTypeName: String?): String {
    return when (expr.op) {
        is UnOp.NotOp -> {
            if (isKnownIntegerBitmaskExpr(expr.expr)) {
                renderBitwiseComplementOperand(expr.expr, indent, selfTypeName)
            } else {
                val savedKind = currentExpectedNumericKind
                currentExpectedNumericKind = null
                try {
                    "!${renderExpr(expr.expr, indent, selfTypeName)}"
                } finally {
                    currentExpectedNumericKind = savedKind
                }
            }
        }
        is UnOp.Neg -> {
            val savedKind = currentExpectedNumericKind
            if (savedKind == NumericKind.U8 ||
                savedKind == NumericKind.U16 ||
                savedKind == NumericKind.U32 ||
                savedKind == NumericKind.U64 ||
                savedKind == NumericKind.U128 ||
                savedKind == NumericKind.I8 ||
                savedKind == NumericKind.I16
            ) {
                currentExpectedNumericKind = null
            }
            try {
                "-${renderExpr(expr.expr, indent, selfTypeName)}"
            } finally {
                currentExpectedNumericKind = savedKind
            }
        }
        is UnOp.Deref -> {
            renderUnOpDerefPointerPunning(expr, indent, selfTypeName)?.let { return it }
            val inner = expr.expr
            when (classifyDerefPlaceKind(inner, selfTypeName, includeRefCellGuard = true)) {
                DerefPlaceKind.RAW_POINTER -> {
                    val pointeeLayout = resolveRawPointerPointeeLayout(inner, selfTypeName)
                    return "($pointeeLayout).readNative((${renderExpr(inner, indent, selfTypeName)}).rawAddress)"
                }
                DerefPlaceKind.MUTABLE_REFERENCE -> return "(${renderExpr(inner, indent, selfTypeName)}).read()"
                DerefPlaceKind.MUTABLE_PLACE -> return "(${renderExpr(inner, indent, selfTypeName)}).get()"
                DerefPlaceKind.REF_CELL_GUARD -> return "(${renderExpr(inner, indent, selfTypeName)}).value"
                null -> Unit
            }
            borrowedPlaceLocalName(inner)?.let { return it }
            renderBorrowedPlace(inner, indent, selfTypeName)?.let { return it }
            if (inner is Expr.Path &&
                inner.qself == null &&
                inner.path.isIdent("self") &&
                selfTypeName != null
            ) {
                val baseSelf = selfTypeName.substringBefore('<').trim()
                if (currentEnclosingFunctionName == "clone") {
                    val fields = inspector.structFieldNamesByType[baseSelf]
                    if (fields != null) {
                        return "$baseSelf(${fields.joinToString(", ") { "this.$it" }})"
                    }
                } else if (currentReceiverIsSharedBorrow &&
                    currentEnclosingReturnSynType != null &&
                    currentEnclosingReturnSynType !is SynType.Reference &&
                    currentEnclosingReturnSynType !is SynType.Ptr &&
                    currentEnclosingReturnRenderedType == selfTypeName &&
                    baseSelf.substringAfterLast('.') == "Span"
                ) {
                    return "this.clone()"
                }
            }
            val renderedInner = renderExpr(inner, indent, selfTypeName)
            val innerUnwrapped = unwrapParenOrGroupExpr(inner)
            if (renderedInner in currentNullableLocals ||
                renderedInner.endsWith("?)") ||
                renderedInner.endsWith("?))") ||
                (innerUnwrapped is Expr.Cast && renderType(innerUnwrapped.ty, selfTypeName).endsWith("?")) ||
                inferExprRenderedType(innerUnwrapped, selfTypeName)?.endsWith("?") == true
            ) {
                markNonNullChecked(inner, renderedInner, wrapNonAtomic = false)
            } else {
                renderedInner
            }
        }
    }
}

internal fun KotlinJ2KEmitter.renderExprReference(expr: Expr.Reference, indent: String, selfTypeName: String?): String {
    return if (expr.mutability == null || headerContext.rawAbiMode) {
        renderExpr(expr.expr, indent, selfTypeName)
    } else {
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        val place = unwrapParenOrGroupExpr(expr.expr)
        if (place is Expr.Index) {
            val rangeExpr = unwrapParenOrGroupExpr(place.index) as? Expr.Range
            if (rangeExpr != null) {
                val base = renderExpr(place.expr, indent, selfTypeName)
                val baseTy = inferExprRenderedType(place.expr, selfTypeName)?.removeSuffix("?")?.trim()
                if (base in currentMutableListLocals ||
                    baseTy?.startsWith("MutableList<") == true ||
                    baseTy?.startsWith("ArrayList<") == true
                ) {
                    val start = rangeExpr.start?.let { renderTypedNumericInitializer(it, NumericKind.I32, indent, selfTypeName) }
                    val end = rangeExpr.end?.let { renderTypedNumericInitializer(it, NumericKind.I32, indent, selfTypeName) }
                    if (start == null && end == null) {
                        return base
                    }
                    val baseAtom = if (isAtomicPostfixReceiver(place.expr, base)) base else "($base)"
                    val fromIdx = start ?: "0"
                    val toIdx =
                        when {
                            end == null -> "$baseAtom.size"
                            rangeExpr.limits is RangeLimits.Closed -> "($end + 1)"
                            else -> end
                        }
                    return "$baseAtom.subList($fromIdx, $toIdx)"
                }
            }
        }
        val local = extractSingleSegmentLocalKotlinName(place)
        local?.let { currentAddressableLocals[it] }?.let {
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.RustMutableReference")
            return "RustMutableReference($it.asMutPtr(), $it.layout)"
        }
        val deref = place as? Expr.Unary
        if (deref?.op is UnOp.Deref) {
            val sourceType = inferExprRenderedType(deref.expr, selfTypeName)
            val source = renderExpr(deref.expr, indent, selfTypeName)
            when {
                mutableReferencePointeeType(sourceType) != null -> "($source).reborrow()"
                rawPointerPointeeType(sourceType) != null -> {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.RustMutableReference")
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.MutPtr")
                    val pointeeLayout = resolveRawPointerPointeeLayout(deref.expr, selfTypeName)
                    "RustMutableReference(MutPtr(($source).rawAddress), $pointeeLayout)"
                }
                else -> renderExpr(expr.expr, indent, selfTypeName)
            }
        } else {
            if (local == null) {
                val temporaryType = sourceInitializerType(place)
                if (temporaryType != null && shouldUseAddressBackedMutableReference(temporaryType, selfTypeName)) {
                    val layout = sourcePointerLayout(temporaryType, selfTypeName)
                    if (layout != null) {
                        val value = renderExpr(place, indent, selfTypeName)
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.RustMutableReference")
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.allocateNativeCell")
                        return "RustMutableReference(allocateNativeCell($value, $layout), $layout)"
                    }
                }
            }
            renderExpr(expr.expr, indent, selfTypeName)
        }
    }
}

internal fun KotlinJ2KEmitter.renderExprRawAddr(expr: Expr.RawAddr, indent: String, selfTypeName: String?): String {
    val local = extractSingleSegmentLocalKotlinName(expr.expr)
    local?.let { currentAddressableLocals[it] }?.let {
        return if (expr.mutability is PointerMutability.Mut) "$it.asMutPtr()" else "$it.asConstPtr()"
    }
    // `&raw const/mut *p` computes the original pointer without reading
    // the pointee or forming a reference, even for uninitialized memory.
    val place = unwrapParenOrGroupExpr(expr.expr)
    return if (place is Expr.Unary && place.op is UnOp.Deref) {
        renderExpr(place.expr, indent, selfTypeName)
    } else {
        // Keep raw address-taking distinct from evaluating a place's
        // value, just as for the addr_of!/addr_of_mut! macro forms.
        val callee = if (expr.mutability is PointerMutability.Mut) "addrOfMut" else "addrOf"
        "$callee(${renderExpr(expr.expr, indent, selfTypeName)})"
    }
}

internal fun KotlinJ2KEmitter.renderExprCast(expr: Expr.Cast, indent: String, selfTypeName: String?): String {
    val savedKind = currentExpectedNumericKind
    val savedParseTarget = currentExpectedParseTargetType
    currentExpectedNumericKind = null
    currentExpectedParseTargetType = null
    val inner =
        try {
            renderExpr(expr.expr, indent, selfTypeName)
        } finally {
            currentExpectedNumericKind = savedKind
            currentExpectedParseTargetType = savedParseTarget
        }
    val isInferPtrTarget =
        !headerContext.rawAbiMode &&
            expr.ty is SynType.Ptr &&
            (expr.ty as SynType.Ptr).elem is SynType.Infer
    val isInferTarget =
        expr.ty is SynType.Infer ||
            (headerContext.rawAbiMode && expr.ty is SynType.Ptr && (expr.ty as SynType.Ptr).elem is SynType.Infer)
    val target =
        if (isInferPtrTarget) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            val isMut = (expr.ty as SynType.Ptr).mutability is PointerMutability.Mut
            val ptrCtor = if (isMut) "MutPtr" else "ConstPtr"
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.$ptrCtor")
            val inferredSourceTy = inferExprRenderedType(expr.expr, selfTypeName)
            val inferredPointee =
                rawPointerPointeeType(savedParseTarget)
                    ?: rawPointerPointeeType(inferredSourceTy)
                    ?: mutableReferencePointeeType(inferredSourceTy)
                    ?: (unwrapParenOrGroupExpr(expr.expr) as? Expr.Reference)?.let { ref ->
                        inferExprRenderedType(ref.expr, selfTypeName)
                    }
                    ?: "UByte"
            "$ptrCtor<$inferredPointee>"
        } else if (isInferTarget && savedKind != null) {
            numericKindToTypeNameRecordingFlags(savedKind)
        } else if (isInferTarget) {
            savedParseTarget ?: currentEnclosingReturnRenderedType ?: renderType(expr.ty, selfTypeName)
        } else {
            renderType(expr.ty, selfTypeName)
        }
    return renderCastExpression(inner, target, expr.expr, selfTypeName, (expr.ty as? SynType.Ptr)?.elem)
}

internal fun KotlinJ2KEmitter.renderExprAssign(expr: Expr.Assign, indent: String, selfTypeName: String?): String {
    val leftExpr = expr.left
    if (leftExpr is Expr.Infer) {
        return if (isSideEffectFreeExpr(expr.right)) "" else renderExpr(expr.right, indent, selfTypeName)
    }
    if (leftExpr is Expr.Unary && leftExpr.op is UnOp.Deref) {
        when (classifyDerefPlaceKind(leftExpr.expr, selfTypeName, includeRefCellGuard = false)) {
            DerefPlaceKind.RAW_POINTER -> {
                val pointeeLayout = resolveRawPointerPointeeLayout(leftExpr.expr, selfTypeName)
                return "($pointeeLayout).writeNative((${renderExpr(leftExpr.expr, indent, selfTypeName)}).rawAddress, ${renderExpr(expr.right, indent, selfTypeName)})"
            }
            DerefPlaceKind.MUTABLE_REFERENCE ->
                return "(${renderExpr(leftExpr.expr, indent, selfTypeName)}).write(${renderExpr(expr.right, indent, selfTypeName)})"
            DerefPlaceKind.MUTABLE_PLACE ->
                return "(${renderExpr(leftExpr.expr, indent, selfTypeName)}).set(${renderExpr(expr.right, indent, selfTypeName)})"
            DerefPlaceKind.REF_CELL_GUARD, null -> Unit
        }
    }
    val borrowedPlace =
        (leftExpr as? Expr.Unary)
            ?.takeIf { it.op is UnOp.Deref }
            ?.let { renderBorrowedPlace(it.expr, indent, selfTypeName) }
    if (borrowedPlace != null) {
        val right = renderExpr(expr.right, indent, selfTypeName)
        return "$borrowedPlace = $right"
    }
    val targetLeftExpr =
        if (leftExpr is Expr.Unary && leftExpr.op is UnOp.Deref) {
            leftExpr.expr
        } else {
            leftExpr
        }
    val left = renderExpr(targetLeftExpr, indent, selfTypeName)
    val leftTargetTy = currentTypedLocals[left] ?: inferExprRenderedType(targetLeftExpr, selfTypeName)
    val prevParseTarget = currentExpectedParseTargetType
    currentExpectedParseTargetType = leftTargetTy
    val leftKind = inferExprNumericKind(targetLeftExpr, selfTypeName)
    val right =
        try {
            if (leftKind != null) {
                renderTypedNumericInitializer(expr.right, leftKind, indent, selfTypeName)
            } else {
                renderExpr(expr.right, indent, selfTypeName)
            }
        } finally {
            currentExpectedParseTargetType = prevParseTarget
        }
    if (left in currentSmartCastNonNullLocals) {
        currentSmartCastNonNullLocals = currentSmartCastNonNullLocals - left
    }
    return "$left = $right"
}

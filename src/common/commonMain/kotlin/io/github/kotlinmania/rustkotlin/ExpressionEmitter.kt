package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Ident
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
import io.github.kotlinmania.syn.BinOp
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.FieldValue
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.QSelf
import io.github.kotlinmania.syn.RangeLimits
import io.github.kotlinmania.syn.ReturnType
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.gen.Visit

internal fun KotlinJ2KEmitter.renderExpr(expr: Expr, indent: String, selfTypeName: String?): String {
    return when (expr) {
        is Expr.Lit -> renderLit(expr.lit)
        is Expr.Path -> renderExprPath(expr, selfTypeName)
        is Expr.Binary -> {
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
            if (isCompoundAssignOp(expr.op) && rawLeftOperand is Expr.Unary && rawLeftOperand.op is UnOp.Deref &&
                (
                    mutablePlaceValueType(inferExprRenderedType(rawLeftOperand.expr, selfTypeName)) != null ||
                        rawPointerPointeeType(inferExprRenderedType(rawLeftOperand.expr, selfTypeName)) != null ||
                        mutableReferencePointeeType(inferExprRenderedType(rawLeftOperand.expr, selfTypeName)) != null
                )
            ) {
                val rawPointer = rawPointerPointeeType(inferExprRenderedType(rawLeftOperand.expr, selfTypeName)) != null
                val place = renderExpr(rawLeftOperand.expr, indent, selfTypeName)
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
                return if (mutableReferencePointeeType(inferExprRenderedType(rawLeftOperand.expr, selfTypeName)) != null) {
                    "($place).let { targetPlace -> var currentValue = targetPlace.read(); $update; targetPlace.write(currentValue) }"
                } else if (rawPointer) {
                    val pointeeLayout = resolveRawPointerPointeeLayout(rawLeftOperand.expr, selfTypeName)
                    "($place).let { targetPtr -> val targetLayout = $pointeeLayout; var currentValue = targetLayout.readNative(targetPtr.rawAddress); $update; targetLayout.writeNative(targetPtr.rawAddress, currentValue) }"
                } else {
                    "($place).let { targetPlace -> var currentValue = targetPlace.get(); $update; targetPlace.set(currentValue) }"
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
            if (compoundBitOp != null) {
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
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.cmp.compareNaturalOrder")
                val op = renderBinOp(expr.op)
                "(compareNaturalOrder($left, $right) $op 0)"
            } else {
                val op = renderBinOp(expr.op)
                if (headerContext.isBatchCrateOutput && right.contains('\n')) {
                    "$left $op\n$indent    ${right.replace("\n", "\n    ")}"
                } else {
                    "$left $op $right"
                }
            }
        }
        is Expr.Unary -> {
            when (expr.op) {
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
                    if (rawPointerPointeeType(inferExprRenderedType(inner, selfTypeName)) != null) {
                        val pointeeLayout = resolveRawPointerPointeeLayout(inner, selfTypeName)
                        return "($pointeeLayout).readNative((${renderExpr(inner, indent, selfTypeName)}).rawAddress)"
                    }
                    if (mutableReferencePointeeType(inferExprRenderedType(inner, selfTypeName)) != null) {
                        return "(${renderExpr(inner, indent, selfTypeName)}).read()"
                    }
                    if (mutablePlaceValueType(inferExprRenderedType(inner, selfTypeName)) != null) {
                        return "(${renderExpr(inner, indent, selfTypeName)}).get()"
                    }
                    if (refCellGuardPointeeType(inferExprRenderedType(inner, selfTypeName)) != null) {
                        return "(${renderExpr(inner, indent, selfTypeName)}).value"
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
                        val isSimpleLocal = renderedInner.isNotEmpty() && renderedInner.all { it.isLetterOrDigit() || it == '_' }
                        if (isSimpleLocal && renderedInner in currentSmartCastNonNullLocals) {
                            renderedInner
                        } else {
                            if (isSimpleLocal) currentSmartCastNonNullLocals = currentSmartCastNonNullLocals + renderedInner
                            "$renderedInner!!"
                        }
                    } else {
                        renderedInner
                    }
                }
            }
        }
        is Expr.Reference -> {
            if (expr.mutability == null || headerContext.rawAbiMode) {
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
                val local =
                    (place as? Expr.Path)
                        ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                        ?.path
                        ?.segments
                        ?.first()
                        ?.ident
                        ?.toString()
                        ?.let { scopeBuilder.resolveLocalKotlinName(it.removePrefix("r#")) ?: rustValueIdentToKotlin(it) }
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
        is Expr.RawAddr -> {
            val local =
                (unwrapParenOrGroupExpr(expr.expr) as? Expr.Path)
                    ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                    ?.path
                    ?.segments
                    ?.first()
                    ?.ident
                    ?.toString()
                    ?.let { scopeBuilder.resolveLocalKotlinName(it.removePrefix("r#")) ?: rustValueIdentToKotlin(it) }
            local?.let { currentAddressableLocals[it] }?.let {
                return if (expr.mutability is PointerMutability.Mut) "$it.asMutPtr()" else "$it.asConstPtr()"
            }
            // `&raw const/mut *p` computes the original pointer without reading
            // the pointee or forming a reference, even for uninitialized memory.
            val place = unwrapParenOrGroupExpr(expr.expr)
            if (place is Expr.Unary && place.op is UnOp.Deref) {
                renderExpr(place.expr, indent, selfTypeName)
            } else {
                // Keep raw address-taking distinct from evaluating a place's
                // value, just as for the addr_of!/addr_of_mut! macro forms.
                val callee = if (expr.mutability is PointerMutability.Mut) "addrOfMut" else "addrOf"
                "$callee(${renderExpr(expr.expr, indent, selfTypeName)})"
            }
        }
        is Expr.Paren -> {
            val innerExpr = expr.expr
            val rendered = renderExpr(innerExpr, indent, selfTypeName)
            if (innerExpr is Expr.Unary && innerExpr.op is UnOp.Deref) {
                rendered
            } else {
                "($rendered)"
            }
        }
        is Expr.Group -> renderExpr(expr.expr, indent, selfTypeName)
        is Expr.Try -> {
            val inner = renderExpr(expr.expr, indent, selfTypeName)
            val retKw = currentClosureLabel?.let { "return@$it" } ?: "return"
            val enclosingRet = currentEnclosingReturnRenderedType
            if (enclosingRet == "Unit" || isFmtWriteOrUnitExpr(expr.expr, selfTypeName)) {
                inner
            } else if (enclosingRet != null &&
                enclosingRet.endsWith("?") &&
                !enclosingRet.startsWith("Result<") &&
                !enclosingRet.startsWith("RustResult<")
            ) {
                "($inner ?: $retKw null)"
            } else {
                renderSourceResultTry(inner, retKw, inferExprRenderedType(expr.expr, selfTypeName))
            }
        }
        is Expr.Await -> renderExpr(expr.base, indent, selfTypeName)
        is Expr.Cast -> {
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
                    when (savedKind) {
                        NumericKind.I8 -> "Byte"
                        NumericKind.U8 -> "UByte"
                        NumericKind.I16 -> "Short"
                        NumericKind.U16 -> "UShort"
                        NumericKind.I32 -> "Int"
                        NumericKind.U32 -> "UInt"
                        NumericKind.I64 -> "Long"
                        NumericKind.U64 -> "ULong"
                        NumericKind.I128 -> {
                            usesI128 = true
                            "I128"
                        }
                        NumericKind.U128 -> {
                            usesU128 = true
                            "U128"
                        }
                        NumericKind.F32 -> kotlinF32TypeName()
                        NumericKind.F64 -> "Double"
                    }
                } else if (isInferTarget) {
                    savedParseTarget ?: currentEnclosingReturnRenderedType ?: renderType(expr.ty, selfTypeName)
                } else {
                    renderType(expr.ty, selfTypeName)
                }
            renderCastExpression(inner, target, expr.expr, selfTypeName, (expr.ty as? SynType.Ptr)?.elem)
        }
        is Expr.Call -> {
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
                if (lastFuncSeg?.ident?.toString() == "transmute") {
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
                }
            }
            val callee = renderCalleeExpr(expr.func, indent, selfTypeName)
            val calleeBase = callee.substringBefore('<').trim()
            when {
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
                        val helper = if (isMutSlice) "rawMutSliceList" else "rawSliceList"
                        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.slice.$helper")
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
                        "null"
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
                (
                    calleeBase == "Debug.fmt" || calleeBase.endsWith(".Debug.fmt") ||
                        calleeBase == "Display.fmt" || calleeBase.endsWith(".Display.fmt") ||
                        calleeBase == "Octal.fmt" || calleeBase.endsWith(".Octal.fmt") ||
                        calleeBase == "LowerHex.fmt" || calleeBase.endsWith(".LowerHex.fmt") ||
                        calleeBase == "UpperHex.fmt" || calleeBase.endsWith(".UpperHex.fmt") ||
                        calleeBase == "Binary.fmt" || calleeBase.endsWith(".Binary.fmt")
                ) && nonUnitArgs.size == 2 ->
                    "${renderExpr(nonUnitArgs[1], indent, selfTypeName)}.append((${renderExpr(nonUnitArgs[0], indent, selfTypeName)}).toString())"
                callee in
                    setOf(
                        "Byte.toLe",
                        "Byte.fromLe",
                        "Byte.toBe",
                        "Byte.fromBe",
                        "Byte.swapBytes",
                        "UByte.toLe",
                        "UByte.fromLe",
                        "UByte.toBe",
                        "UByte.fromBe",
                        "UByte.swapBytes",
                        "Short.toLe",
                        "Short.fromLe",
                        "UShort.toLe",
                        "UShort.fromLe",
                        "Int.toLe",
                        "Int.fromLe",
                        "UInt.toLe",
                        "UInt.fromLe",
                        "Long.toLe",
                        "Long.fromLe",
                        "ULong.toLe",
                        "ULong.fromLe",
                    ) && nonUnitArgs.size == 1 -> renderExpr(nonUnitArgs[0], indent, selfTypeName)
                (callee == "UInt.toBe" || callee == "UInt.fromBe" || callee == "UInt.swapBytes") && nonUnitArgs.size == 1 ->
                    renderPrimitiveByteSwapExpr(renderExpr(nonUnitArgs[0], indent, selfTypeName), NumericKind.U32)
                (callee == "Int.toBe" || callee == "Int.fromBe" || callee == "Int.swapBytes") && nonUnitArgs.size == 1 ->
                    renderPrimitiveByteSwapExpr(renderExpr(nonUnitArgs[0], indent, selfTypeName), NumericKind.I32)
                (callee == "UShort.toBe" || callee == "UShort.fromBe" || callee == "UShort.swapBytes") && nonUnitArgs.size == 1 ->
                    renderPrimitiveByteSwapExpr(renderExpr(nonUnitArgs[0], indent, selfTypeName), NumericKind.U16)
                (callee == "Short.toBe" || callee == "Short.fromBe" || callee == "Short.swapBytes") && nonUnitArgs.size == 1 ->
                    renderPrimitiveByteSwapExpr(renderExpr(nonUnitArgs[0], indent, selfTypeName), NumericKind.I16)
                (callee == "ULong.toBe" || callee == "ULong.fromBe" || callee == "ULong.swapBytes") && nonUnitArgs.size == 1 ->
                    renderPrimitiveByteSwapExpr(renderExpr(nonUnitArgs[0], indent, selfTypeName), NumericKind.U64)
                (callee == "Long.toBe" || callee == "Long.fromBe" || callee == "Long.swapBytes") && nonUnitArgs.size == 1 ->
                    renderPrimitiveByteSwapExpr(renderExpr(nonUnitArgs[0], indent, selfTypeName), NumericKind.I64)
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
                                val isIntLitArg = (unwrapParenOrGroupExpr(argExpr) as? Expr.Lit)?.lit is Lit.Int
                                if ((callee == "ioctl" || callee.endsWith(".ioctl")) && argIdx >= 2) {
                                    if (isIntLitArg || inferExprNumericKind(argExpr, selfTypeName) != null) {
                                        renderTypedNumericInitializer(argExpr, NumericKind.I64, indent, selfTypeName)
                                    } else {
                                        currentExpectedNumericKind = null
                                        val r = renderExpr(argExpr, indent, selfTypeName)
                                        renderCastExpression(r, "Long", argExpr, selfTypeName)
                                    }
                                } else if (callee == "syscall" || callee.endsWith(".syscall")) {
                                    if (argIdx == 0 || isIntLitArg || inferExprNumericKind(argExpr, selfTypeName) != null) {
                                        renderTypedNumericInitializer(argExpr, NumericKind.I64, indent, selfTypeName)
                                    } else {
                                        currentExpectedNumericKind = null
                                        val r = renderExpr(argExpr, indent, selfTypeName)
                                        renderCastExpression(r, "Long", argExpr, selfTypeName)
                                    }
                                } else if (paramKind != null) {
                                    currentExpectedParseTargetType = null
                                    renderTypedNumericInitializer(argExpr, paramKind, indent, selfTypeName)
                                } else {
                                    currentExpectedNumericKind = null
                                    val paramParseTarget =
                                        resolveCalleeExprParamSynType(expr.func, argIdx, selfTypeName)
                                            ?.let { paramSynTy ->
                                                val rawRendered = renderType(paramSynTy, selfTypeName).removeSuffix("?").trim()
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
                                    try {
                                        renderExpr(argExpr, indent, selfTypeName)
                                    } finally {
                                        currentExpectedParseTargetType = null
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
                            callee.endsWith(".parseTerminatedWith") && renderedArgList.size == 2 -> {
                                currentTypeParamWitnessCalls.entries
                                    .filter { it.key.second == "parse" && it.value !in renderedArgList }
                                    .map { it.value }
                                    .distinct()
                            }
                            callee.endsWith(".parseSeparatedNonemptyWith") && renderedArgList.size == 2 -> {
                                val peeks =
                                    currentTypeParamWitnessCalls.entries
                                        .filter { it.key.second == "peek" && it.value !in renderedArgList }
                                        .map { it.value }
                                        .distinct()
                                val parses =
                                    currentTypeParamWitnessCalls.entries
                                        .filter { it.key.second == "parse" && it.value !in renderedArgList }
                                        .map { it.value }
                                        .distinct()
                                peeks + parses
                            }
                            callee.endsWith(".parseTerminated") && renderedArgList.size == 1 -> {
                                currentTypeParamWitnessCalls.entries
                                    .filter { it.key.second == "parse" && it.value !in renderedArgList }
                                    .map { it.value }
                                    .distinct()
                            }
                            callee.endsWith(".parseSeparatedNonempty") && renderedArgList.size == 1 -> {
                                currentTypeParamWitnessCalls.values
                                    .filter { it !in renderedArgList }
                                    .distinct()
                            }
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
                    val callExpr =
                        if (rewrittenTraitElemCallee != null) {
                            "$rewrittenTraitElemCallee($fullArgs)"
                        } else if (callee.endsWith(".default()") && nonUnitArgs.isNotEmpty()) {
                            "${callee.removeSuffix(".default()")}.from($fullArgs)"
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
        is Expr.MethodCall -> {
            val methodEntryParseTarget = currentExpectedParseTargetType
            val prevRecvParseTarget = currentExpectedParseTargetType
            val rawMethod = expr.method.toString()
            val cleanMethod = rawMethod.removePrefix("r#")
            unwrapTryIntoUnwrapExpr(expr)?.let { innerTryRecv ->
                val tryIntoTargetTy =
                    (methodEntryParseTarget ?: currentEnclosingReturnRenderedType)
                        ?.removeSuffix("?")
                        ?.trim()
                if (tryIntoTargetTy != null && tryIntoTargetTy in PRIMITIVE_ARRAY_TYPES) {
                    currentExpectedParseTargetType = null
                    return try {
                        renderPrimitiveArrayTryIntoConversion(innerTryRecv, tryIntoTargetTy, indent, selfTypeName)
                    } finally {
                        currentExpectedParseTargetType = prevRecvParseTarget
                    }
                }
            }
            if (cleanMethod == "copy_from_slice" && expr.args.toList().size == 1 && expr.turbofish == null) {
                currentExpectedParseTargetType = null
                val renderedCopy =
                    try {
                        renderCopyFromSliceCall(expr.receiver, expr.args.toList()[0], indent, selfTypeName)
                    } finally {
                        currentExpectedParseTargetType = prevRecvParseTarget
                    }
                if (renderedCopy != null) {
                    return renderedCopy
                }
            }
            currentExpectedParseTargetType =
                when (cleanMethod) {
                    "map_err", "or_else", "or", "unwrap", "expect", "ok", "assume_init", "assume_init_read" ->
                        methodEntryParseTarget ?: currentEnclosingReturnRenderedType
                    else -> null
                }
            if (cleanMethod == "for_each" && expr.turbofish == null && expr.args.len() == 1) {
                tryRenderZippedForEachLoop(expr, indent, selfTypeName)?.let {
                    currentExpectedParseTargetType = prevRecvParseTarget
                    return it
                }
            }
            if (cleanMethod in setOf("as_ptr", "as_mut_ptr") && expr.args.isEmpty()) {
                sourceArrayLocal(expr.receiver)?.let { (storage, _) ->
                    currentExpectedParseTargetType = prevRecvParseTarget
                    return if (cleanMethod == "as_mut_ptr") "$storage.elementMutPtr(0)" else "$storage.elementConstPtr(0)"
                }
            }
            val effectiveReceiverExpr =
                if (cleanMethod in setOf("zip", "any", "all", "map", "filter", "filter_map", "flat_map", "fold", "for_each", "forEach", "enumerate", "count", "sum", "find", "position") &&
                    expr.receiver is Expr.MethodCall &&
                    (expr.receiver as Expr.MethodCall).args.isEmpty() &&
                    (expr.receiver as Expr.MethodCall).turbofish == null &&
                    (expr.receiver as Expr.MethodCall).method.toString().removePrefix("r#") in setOf("iter", "iter_mut", "into_iter") &&
                    (
                        inferArrayElementNumericKind((expr.receiver as Expr.MethodCall).receiver, selfTypeName) != null ||
                            inferExprRenderedType((expr.receiver as Expr.MethodCall).receiver, selfTypeName)?.let {
                                extractIndexedElementRenderedType(it, selfTypeName)
                            } != null ||
                            ((expr.receiver as Expr.MethodCall).receiver is Expr.Index && ((expr.receiver as Expr.MethodCall).receiver as Expr.Index).index is Expr.Range)
                    )
                ) {
                    (expr.receiver as Expr.MethodCall).receiver
                } else {
                    expr.receiver
                }
            val receiver =
                try {
                    val value = renderExpr(effectiveReceiverExpr, indent, selfTypeName)
                    if (mutableReferencePointeeType(inferExprRenderedType(effectiveReceiverExpr, selfTypeName)) != null) {
                        "($value).read()"
                    } else {
                        value
                    }
                } finally {
                    currentExpectedParseTargetType = prevRecvParseTarget
                }
            val nonUnitArgs =
                expr.args
                    .toList()
                    .filterNot { it is Expr.Tuple && it.elems.isEmpty() }
                    .map { arg ->
                        if (cleanMethod == "zip" &&
                            arg is Expr.MethodCall &&
                            arg.args.isEmpty() &&
                            arg.turbofish == null &&
                            arg.method.toString().removePrefix("r#") in setOf("iter", "iter_mut", "into_iter") &&
                            (
                                inferArrayElementNumericKind(arg.receiver, selfTypeName) != null ||
                                    inferExprRenderedType(arg.receiver, selfTypeName)?.let {
                                        extractIndexedElementRenderedType(it, selfTypeName)
                                    } != null ||
                                    (arg.receiver is Expr.Index && (arg.receiver as Expr.Index).index is Expr.Range)
                            )
                        ) {
                            arg.receiver
                        } else {
                            arg
                        }
                    }
            val recvRawPtrRenderedTy = inferExprRenderedType(expr.receiver, selfTypeName)
            if (rawPointerPointeeType(recvRawPtrRenderedTy) != null) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                val ptrPkg = "$coreNs.ptr"
                val intrPkg = "$coreNs.intrinsics"
                val isMutRecv = isMutRawPointerRenderedType(recvRawPtrRenderedTy)
                val ptrCtor = if (isMutRecv) "MutPtr" else "ConstPtr"
                if (nonUnitArgs.size == 1 &&
                    rawMethod in
                    setOf(
                        "offset",
                        "add",
                        "sub",
                        "wrapping_offset",
                        "wrapping_add",
                        "wrapping_sub",
                        "byte_offset",
                        "byte_add",
                        "byte_sub",
                        "wrapping_byte_offset",
                        "wrapping_byte_add",
                        "wrapping_byte_sub",
                    )
                ) {
                    CoreIntrinsicLowering.registerCoreImport(this, "$ptrPkg.$ptrCtor")
                    val argument = renderExpr(nonUnitArgs.single(), indent, selfTypeName)
                    val deltaSign = if (rawMethod.endsWith("sub")) "-" else "+"
                    val byteDelta =
                        if ("byte_" in rawMethod) {
                            "($argument).toLong()"
                        } else {
                            val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
                            "($argument).toLong() * ($pointeeLayout).strideBytes"
                        }
                    return "$ptrCtor(($receiver).rawAddress $deltaSign ($byteDelta))"
                }
                if (rawMethod == "is_null" && nonUnitArgs.isEmpty()) {
                    return "(($receiver).rawAddress == 0L)"
                }
                if (rawMethod == "cast_mut" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                    val pointee = rawPointerPointeeType(recvRawPtrRenderedTy) ?: "Any"
                    CoreIntrinsicLowering.registerCoreImport(this, "$ptrPkg.MutPtr")
                    return "MutPtr<$pointee>(($receiver).rawAddress)"
                }
                if (rawMethod == "cast_const" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                    val pointee = rawPointerPointeeType(recvRawPtrRenderedTy) ?: "Any"
                    CoreIntrinsicLowering.registerCoreImport(this, "$ptrPkg.ConstPtr")
                    return "ConstPtr<$pointee>(($receiver).rawAddress)"
                }
                if (rawMethod == "cast" && nonUnitArgs.isEmpty()) {
                    val destination =
                        expr.turbofish
                            ?.args
                            ?.toList()
                            ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
                    val sourcePointee = rawPointerPointeeType(recvRawPtrRenderedTy)
                    val returnDestination =
                        when (val returnType = currentEnclosingReturnSynType) {
                            is SynType.Ptr -> returnType.elem
                            is SynType.Reference -> (returnType.elem as? SynType.Slice)?.elem ?: returnType.elem
                            else -> null
                        }
                    val destinationPointee =
                        destination?.let { renderType(it, selfTypeName) }
                            ?: rawPointerPointeeType(methodEntryParseTarget)
                            ?: rawPointerPointeeType(currentEnclosingReturnRenderedType)
                            ?: returnDestination?.let { renderType(it, selfTypeName) }
                            ?: sourcePointee
                            ?: "Any"
                    if (sourcePointee == destinationPointee) return receiver
                    CoreIntrinsicLowering.registerCoreImport(this, "$ptrPkg.$ptrCtor")
                    return "$ptrCtor<$destinationPointee>(($receiver).rawAddress)"
                }
                if ((rawMethod == "read" || rawMethod == "read_unaligned") && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                    val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
                    return "($pointeeLayout).readNative(($receiver).rawAddress)"
                }
                if (rawMethod == "read_volatile" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                    val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
                    return "($pointeeLayout).readVolatileNative(($receiver).rawAddress)"
                }
                if ((rawMethod == "write" || rawMethod == "write_unaligned") && nonUnitArgs.size == 1 && expr.turbofish == null) {
                    val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
                    val valExpr = renderExpr(nonUnitArgs[0], indent, selfTypeName)
                    return "($pointeeLayout).writeNative(($receiver).rawAddress, $valExpr)"
                }
                if (rawMethod == "write_volatile" && nonUnitArgs.size == 1 && expr.turbofish == null) {
                    val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
                    val valExpr = renderExpr(nonUnitArgs[0], indent, selfTypeName)
                    return "($pointeeLayout).writeVolatileNative(($receiver).rawAddress, $valExpr)"
                }
                if ((rawMethod == "copy_to" || rawMethod == "copy_to_nonoverlapping") && nonUnitArgs.size == 2 && expr.turbofish == null) {
                    val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
                    val dstExpr = renderExpr(nonUnitArgs[0], indent, selfTypeName)
                    val countExpr = renderExpr(nonUnitArgs[1], indent, selfTypeName)
                    val fnName = if (rawMethod == "copy_to") "kmCopy" else "kmCopyNonoverlapping"
                    CoreIntrinsicLowering.registerCoreImport(this, "$intrPkg.$fnName")
                    return "$fnName(($receiver).rawAddress, ($dstExpr).rawAddress, ($countExpr).toLong() * ($pointeeLayout).sizeBytes)"
                }
                if ((rawMethod == "copy_from" || rawMethod == "copy_from_nonoverlapping") && nonUnitArgs.size == 2 && expr.turbofish == null) {
                    val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
                    val srcExpr = renderExpr(nonUnitArgs[0], indent, selfTypeName)
                    val countExpr = renderExpr(nonUnitArgs[1], indent, selfTypeName)
                    val fnName = if (rawMethod == "copy_from") "kmCopy" else "kmCopyNonoverlapping"
                    CoreIntrinsicLowering.registerCoreImport(this, "$intrPkg.$fnName")
                    return "$fnName(($srcExpr).rawAddress, ($receiver).rawAddress, ($countExpr).toLong() * ($pointeeLayout).sizeBytes)"
                }
                if (rawMethod == "write_bytes" && nonUnitArgs.size == 2 && expr.turbofish == null) {
                    val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
                    val byteExpr = renderExpr(nonUnitArgs[0], indent, selfTypeName)
                    val countExpr = renderExpr(nonUnitArgs[1], indent, selfTypeName)
                    CoreIntrinsicLowering.registerCoreImport(this, "$intrPkg.kmWriteBytes")
                    return "kmWriteBytes(($receiver).rawAddress, ($byteExpr).toUByte(), ($countExpr).toLong() * ($pointeeLayout).sizeBytes)"
                }
                if (rawMethod == "offset_from" && nonUnitArgs.size == 1 && expr.turbofish == null) {
                    val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
                    val originExpr = renderExpr(nonUnitArgs[0], indent, selfTypeName)
                    val diffExpr = "((($receiver).rawAddress - ($originExpr).rawAddress) / ($pointeeLayout).strideBytes)"
                    return if (headerContext.rawAbiMode) diffExpr else "$diffExpr.toInt()"
                }
                if (rawMethod == "addr" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                    return if (headerContext.rawAbiMode) "($receiver).rawAddress.toULong()" else "($receiver).rawAddress.toInt()"
                }
                if (rawMethod == "with_addr" && nonUnitArgs.size == 1 && expr.turbofish == null) {
                    val addrExpr = renderExpr(nonUnitArgs[0], indent, selfTypeName)
                    CoreIntrinsicLowering.registerCoreImport(this, "$ptrPkg.$ptrCtor")
                    return "$ptrCtor(($addrExpr).toLong())"
                }
            }
            if (rawMethod == "is_null" && nonUnitArgs.isEmpty() && headerContext.rawAbiMode) {
                return "($receiver == 0L)"
            }
            if ((rawMethod == "cast_mut" || rawMethod == "cast_const") && nonUnitArgs.isEmpty() && expr.turbofish == null && headerContext.rawAbiMode) {
                return receiver
            }
            if (rawMethod == "count_ones" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                val recvKind = inferExprNumericKind(expr.receiver, selfTypeName)
                if (recvKind != null && recvKind != NumericKind.F32 && recvKind != NumericKind.F64) {
                    val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    if ("core" in headerContext.crateNamespaces) {
                        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.rustCountOnes")
                        return "rustCountOnes($receiver)"
                    }
                    return "$recvAtom.countOneBits().toUInt()"
                }
            }
            if (rawMethod == "to_bits" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                val recvKind = inferExprNumericKind(expr.receiver, selfTypeName)
                if (recvKind == NumericKind.F32 || recvKind == NumericKind.F64) {
                    val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    return if (recvKind == NumericKind.F32) {
                        "$recvAtom.toRawBits().toUInt()"
                    } else {
                        "$recvAtom.toRawBits().toULong()"
                    }
                }
            }
            if (rawMethod == "cast" && nonUnitArgs.isEmpty() && headerContext.rawAbiMode) {
                return receiver
            }
            // Dereference/volatile reads retain their canonical method call;
            // the pointer object itself is not the value stored at its address.
            if (rawMethod == "len" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                val recvTy =
                    (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                        ?.removeSuffix("?")
                        ?.trim()
                val refCellInner = refCellGuardPointeeType(recvTy)
                val effectiveLenTy = refCellInner ?: recvTy
                val recvTarget =
                    if (refCellInner != null) {
                        val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                        "$recvAtom.value"
                    } else {
                        if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    }
                if (effectiveLenTy != null) {
                    if (effectiveLenTy == "String" || effectiveLenTy == "CharSequence" || effectiveLenTy == "StringBuilder") {
                        return "$recvTarget.length"
                    }
                    if (effectiveLenTy.startsWith("List<") ||
                        effectiveLenTy.startsWith("MutableList<") ||
                        effectiveLenTy.startsWith("Set<") ||
                        effectiveLenTy.startsWith("MutableSet<") ||
                        effectiveLenTy.startsWith("Map<") ||
                        effectiveLenTy.startsWith("MutableMap<") ||
                        effectiveLenTy.startsWith("Collection<") ||
                        effectiveLenTy.startsWith("ArrayDeque<") ||
                        effectiveLenTy in PRIMITIVE_ARRAY_TYPES
                    ) {
                        return "$recvTarget.size"
                    }
                }
                if (inferArrayElementNumericKind(expr.receiver, selfTypeName) != null) {
                    return "$receiver.size"
                }
            }
            if (rawMethod == "is_empty" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                val recvTy =
                    (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                        ?.removeSuffix("?")
                        ?.trim()
                if (refCellGuardPointeeType(recvTy) != null) {
                    val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    return "$recvAtom.value.isEmpty()"
                }
            }
            if (rawMethod == "is_none" && !hasDeclaredOptionEnum() && nonUnitArgs.isEmpty() && !isKnownNonNullableNonOptionReceiver(expr.receiver, receiver, selfTypeName)) {
                return "$receiver == null"
            }
            if (rawMethod == "is_some" && !hasDeclaredOptionEnum() && nonUnitArgs.isEmpty() && !isKnownNonNullableNonOptionReceiver(expr.receiver, receiver, selfTypeName)) {
                return "$receiver != null"
            }
            if (rawMethod == "as_bytes" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                val recvTy =
                    (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                        ?.removeSuffix("?")
                        ?.trim()
                val refCellInner = refCellGuardPointeeType(recvTy)
                val effectiveBytesTy = refCellInner ?: recvTy
                val isKnownStringReceiver =
                    effectiveBytesTy == null ||
                        effectiveBytesTy in setOf("String", "CharSequence", "StringBuilder")
                if (isKnownStringReceiver) {
                    val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    val strExpr = if (refCellInner != null) "$recvAtom.value" else recvAtom
                    return if (headerContext.rawAbiMode || headerContext.finalClasses) {
                        "$strExpr.encodeToByteArray().asUByteArray()"
                    } else {
                        "$strExpr.encodeToByteArray().map { it.toUByte() }"
                    }
                }
            }
            if (rawMethod == "as_str" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                val recvTy =
                    (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                        ?.removeSuffix("?")
                        ?.trim()
                if (refCellGuardPointeeType(recvTy) == "String") {
                    val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    return "$recvAtom.value"
                }
                if (recvTy == "String") {
                    return receiver
                }
                if (recvTy == null &&
                    inspector.instanceMethodsByType.values.none { "asStr" in it } &&
                    headerContext.crateFnReturnTypes.keys.none { it.endsWith(".asStr") }
                ) {
                    return receiver
                }
            }
            if ((rawMethod == "as_ref" || rawMethod == "borrow") && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                val recvTy =
                    (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                        ?.removeSuffix("?")
                        ?.trim()
                if (receiver in currentIntoSubstitutedParams ||
                    recvTy == "String" ||
                    recvTy?.startsWith("List<") == true ||
                    recvTy?.startsWith("MutableList<") == true
                ) {
                    return receiver
                }
            }
            if ((rawMethod == "to_be_bytes" || rawMethod == "to_le_bytes" || rawMethod == "to_ne_bytes") &&
                nonUnitArgs.isEmpty() &&
                expr.turbofish == null
            ) {
                val recvKind = inferExprNumericKind(expr.receiver, selfTypeName)
                if (recvKind != null) {
                    return renderIntToBytesMethodCall(receiver, expr.receiver, recvKind, rawMethod, selfTypeName)
                }
            }
            if ((rawMethod == "to_be" || rawMethod == "from_be" || rawMethod == "swap_bytes" || rawMethod == "to_le" || rawMethod == "from_le") &&
                nonUnitArgs.isEmpty() &&
                expr.turbofish == null
            ) {
                val recvKind = inferExprNumericKind(expr.receiver, selfTypeName)
                if (recvKind != null && recvKind != NumericKind.F32 && recvKind != NumericKind.F64) {
                    if ((recvKind == NumericKind.U128 || recvKind == NumericKind.I128) && "core" in headerContext.crateNamespaces) {
                        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                        val fnName =
                            when (rawMethod) {
                                "to_be" -> "rustToBe"
                                "from_be" -> "rustFromBe"
                                "swap_bytes" -> "rustSwapBytes"
                                "to_le" -> "rustToLe"
                                else -> "rustFromLe"
                            }
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.$fnName")
                        return "$fnName($receiver)"
                    }
                    if (recvKind != NumericKind.U128 && recvKind != NumericKind.I128) {
                        return if (rawMethod == "to_le" || rawMethod == "from_le" || recvKind == NumericKind.I8 || recvKind == NumericKind.U8) {
                            receiver
                        } else {
                            renderPrimitiveByteSwapExpr(receiver, recvKind)
                        }
                    }
                }
            }
            val isThreadCurrentCall =
                when (val recvCore = unwrapParenOrGroupExpr(expr.receiver)) {
                    is Expr.Call -> {
                        val fnPath = (unwrapParenOrGroupExpr(recvCore.func) as? Expr.Path)?.takeIf { it.qself == null }
                        val segs =
                            fnPath
                                ?.path
                                ?.segments
                                ?.toList()
                                ?.map { it.ident.toString().removePrefix("r#") }
                        recvCore.args.isEmpty() && (
                            segs == listOf("thread", "current") ||
                                segs == listOf("std", "thread", "current") ||
                                (segs == listOf("current") && scopeBuilder.resolveModuleBinding("current")?.importedPath == "std.thread.current")
                        )
                    }
                    else -> false
                }
            if (rawMethod == "id" &&
                nonUnitArgs.isEmpty() &&
                (receiver == "thread.current()" || receiver.endsWith(".thread.current()") || isThreadCurrentCall)
            ) {
                usesCurrentThreadToken = true
                return "currentThreadBoundToken()"
            }
            if (rawMethod == "debug_list" && nonUnitArgs.isEmpty()) {
                return "object { val entries = mutableListOf<Any?>(); fun entry(v: Any?) = apply { entries.add(v) }; fun entries(iter: Iterable<*>) = apply { for (e in iter) entries.add(e) }; fun finish() { $receiver.append(entries.joinToString(\", \", \"[\", \"]\")) } }"
            }
            if (rawMethod == "debug_set" && nonUnitArgs.isEmpty()) {
                return "object { val entries = mutableListOf<Any?>(); fun entry(v: Any?) = apply { entries.add(v) }; fun entries(iter: Iterable<*>) = apply { for (e in iter) entries.add(e) }; fun finish() { $receiver.append(entries.joinToString(\", \", \"{\", \"}\")) } }"
            }
            if (rawMethod == "debug_map" && nonUnitArgs.isEmpty()) {
                return "object { val entries = mutableListOf<String>(); fun entry(k: Any?, v: Any?) = apply { entries.add(\"\$k: \$v\") }; fun finish() { $receiver.append(entries.joinToString(\", \", \"{\", \"}\")) } }"
            }
            if (rawMethod == "pop_front" && nonUnitArgs.isEmpty()) {
                return "$receiver.removeFirstOrNull()"
            }
            if ((rawMethod == "as_deref" || rawMethod == "as_deref_mut" || rawMethod == "as_ref" || rawMethod == "as_mut") &&
                nonUnitArgs.isEmpty() &&
                expr.turbofish == null &&
                isKnownNullableOptionExpr(expr.receiver, receiver, selfTypeName)
            ) {
                // Only erased Option borrows are identity operations. In particular,
                // NonNull::as_ref/as_mut must load the pointee rather than return
                // the pointer wrapper, and user-defined methods must remain calls.
                return receiver
            }
            val isDequeIterReceiver = isDequeIterFieldExpr(expr.receiver, selfTypeName) || receiver in currentDequeLocals
            if (isDequeIterReceiver && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                when (rawMethod) {
                    "next" -> return "$receiver.removeFirstOrNull()"
                    "next_back" -> return "$receiver.removeLastOrNull()"
                    "len" -> return "$receiver.size"
                    "clone" -> return "ArrayDeque($receiver)"
                }
            }
            val isVecReceiver = isMutableVecExpr(expr.receiver, receiver, selfTypeName)
            if (isVecReceiver && expr.turbofish == null) {
                when {
                    rawMethod == "pop" && nonUnitArgs.isEmpty() -> return "$receiver.removeLastOrNull()"
                    rawMethod == "len" && nonUnitArgs.isEmpty() -> return "$receiver.size"
                    (rawMethod == "into_iter" || rawMethod == "iter" || rawMethod == "iter_mut") && nonUnitArgs.isEmpty() ->
                        return "ArrayDeque($receiver)"
                    rawMethod == "clone" && nonUnitArgs.isEmpty() -> return "$receiver.toMutableList()"
                    rawMethod == "last_mut" && nonUnitArgs.isEmpty() -> return "$receiver.lastOrNull()"
                    rawMethod == "first_mut" && nonUnitArgs.isEmpty() -> return "$receiver.firstOrNull()"
                }
            }
            if (rawMethod == "into" && nonUnitArgs.isEmpty() && expr.turbofish == null && receiver in currentIntoSubstitutedParams) {
                return receiver
            }
            if (rawMethod == "into_iter" && nonUnitArgs.isEmpty() && expr.turbofish == null && receiver in currentIterableLocals) {
                return "$receiver.iterator()"
            }
            if (rawMethod == "into_iter" &&
                nonUnitArgs.isEmpty() &&
                expr.turbofish == null &&
                !isKnownNullableOptionExpr(expr.receiver, receiver, selfTypeName) &&
                !hasDeclaredOptionEnum() &&
                "IntoIterator" !in inspector.declaredTraitsByName
            ) {
                val recvTy = inferExprRenderedType(expr.receiver, selfTypeName)?.removeSuffix("?")?.trim()
                if (recvTy != null &&
                    (
                        recvTy == "TokenStream" ||
                            recvTy.endsWith(".TokenStream") ||
                            recvTy.startsWith("Iterable<") ||
                            recvTy.startsWith("Collection<") ||
                            recvTy.startsWith("List<") ||
                            recvTy.startsWith("MutableList<") ||
                            recvTy.startsWith("Set<") ||
                            recvTy.startsWith("MutableSet<") ||
                            recvTy.endsWith("Array")
                    )
                ) {
                    return "ArrayDeque(($receiver).toList())"
                }
            }
            if (rawMethod == "clone" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                val recvTy = currentTypedLocals[receiver]
                if (recvTy != null) {
                    currentTypeParamWitnessCalls[recvTy to "clone"]?.let { cloneFn ->
                        return "$cloneFn($receiver)"
                    }
                }
            }
            val turbofish = expr.turbofish
            if (rawMethod == "parse" && nonUnitArgs.isEmpty() && turbofish != null && turbofish.args.size == 1) {
                val tfGa = turbofish.args.first() as? GenericArgument.TypeArg
                if (tfGa != null) {
                    val renderedTfTy = renderType(tfGa.type, selfTypeName).trim()
                    val parseRecvTy =
                        (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                            ?.removeSuffix("?")
                            ?.trim()
                    val parseRecvBase = parseRecvTy?.substringAfterLast('.')?.trim()
                    val strParseMethod = STR_PRIMITIVE_PARSE_METHODS[renderedTfTy]
                    if (strParseMethod != null &&
                        parseRecvBase !in setOf("ParseStream", "ParseBuffer") &&
                        (
                            parseRecvTy == "String" ||
                                parseRecvTy == "CharSequence" ||
                                refCellGuardPointeeType(parseRecvTy) == "String" ||
                                (parseRecvTy == null && inspector.instanceMethodsByType.values.none { "parse" in it } && "parse" !in inspector.declaredTopLevelFnRawNames)
                        )
                    ) {
                        val strTarget =
                            if (refCellGuardPointeeType(parseRecvTy) == "String") {
                                val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                                "$recvAtom.value"
                            } else {
                                if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                            }
                        return if (usesTaggedCoreResult()) {
                            val res = sourceResultName()
                            "($strTarget.$strParseMethod?.let { $res.Ok(it) } ?: $res.Err(\"failed to parse $renderedTfTy\"))"
                        } else {
                            "($strTarget.$strParseMethod?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException(\"failed to parse $renderedTfTy\")))"
                        }
                    }
                    if (renderedTfTy.endsWith("?")) {
                        val optInner = renderedTfTy.removeSuffix("?").trim()
                        val peekTok = resolveOptionalSynParsePeekToken(optInner)
                        if (peekTok != null &&
                            optInner !in activeTypeParamNames &&
                            optInner !in KOTLIN_STDLIB_SHADOWED_IMPORTS
                        ) {
                            return renderOptionalSynParseExpr(receiver, peekTok, optInner)
                        }
                    } else {
                        val recvTyBase =
                            (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                                ?.removeSuffix("?")
                                ?.substringAfterLast('.')
                                ?.trim()
                        if ((recvTyBase == "ParseStream" || recvTyBase == "ParseBuffer") &&
                            renderedTfTy.isNotEmpty() &&
                            renderedTfTy != "TokenStream" &&
                            renderedTfTy !in activeTypeParamNames &&
                            renderedTfTy !in KOTLIN_STDLIB_SHADOWED_IMPORTS &&
                            renderedTfTy.all { it.isLetterOrDigit() || it == '_' || it == '.' }
                        ) {
                            return "$receiver.parse($renderedTfTy::parse)"
                        }
                    }
                }
            }
            if (rawMethod == "parse" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                val parseRecvTy =
                    (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                        ?.removeSuffix("?")
                        ?.trim()
                val parseRecvBase = parseRecvTy?.substringAfterLast('.')?.trim()
                val inferredPrimitiveTarget =
                    (methodEntryParseTarget ?: currentEnclosingReturnRenderedType)
                        ?.let { rustResultOkPayloadType(it) ?: it }
                        ?.removeSuffix("?")
                        ?.trim()
                val strParseMethod = inferredPrimitiveTarget?.let { STR_PRIMITIVE_PARSE_METHODS[it] }
                if (strParseMethod != null &&
                    parseRecvBase !in setOf("ParseStream", "ParseBuffer") &&
                    (
                        parseRecvTy == "String" ||
                            parseRecvTy == "CharSequence" ||
                            refCellGuardPointeeType(parseRecvTy) == "String" ||
                            (parseRecvTy == null && inspector.instanceMethodsByType.values.none { "parse" in it } && "parse" !in inspector.declaredTopLevelFnRawNames)
                    )
                ) {
                    val strTarget =
                        if (refCellGuardPointeeType(parseRecvTy) == "String") {
                            val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                            "$recvAtom.value"
                        } else {
                            if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                        }
                    return if (usesTaggedCoreResult()) {
                        val res = sourceResultName()
                        "($strTarget.$strParseMethod?.let { $res.Ok(it) } ?: $res.Err(\"failed to parse $inferredPrimitiveTarget\"))"
                    } else {
                        "($strTarget.$strParseMethod?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException(\"failed to parse $inferredPrimitiveTarget\")))"
                    }
                }
                if (methodEntryParseTarget?.endsWith("?") == true) {
                    val optInner = methodEntryParseTarget.removeSuffix("?").trim()
                    val peekTok = resolveOptionalSynParsePeekToken(optInner)
                    if (peekTok != null &&
                        optInner !in activeTypeParamNames &&
                        optInner !in KOTLIN_STDLIB_SHADOWED_IMPORTS
                    ) {
                        return renderOptionalSynParseExpr(receiver, peekTok, optInner)
                    }
                }
                val nonNullParseTarget = methodEntryParseTarget?.takeIf { !it.endsWith("?") }
                val matchedParseWitness =
                    (nonNullParseTarget?.let { currentTypeParamWitnessCalls[it to "parse"] })
                        ?: currentTypeParamWitnessCalls.entries
                            .filter { it.key.second == "parse" }
                            .map { it.value }
                            .distinct()
                            .singleOrNull()
                if (matchedParseWitness != null) {
                    return "$receiver.parse($matchedParseWitness)"
                }
                val concreteTarget =
                    nonNullParseTarget?.takeIf { t ->
                        t.isNotEmpty() &&
                            t != "TokenStream" &&
                            t !in activeTypeParamNames &&
                            t !in KOTLIN_STDLIB_SHADOWED_IMPORTS &&
                            t.all { it.isLetterOrDigit() || it == '_' || it == '.' }
                    }
                if (concreteTarget != null) {
                    return "$receiver.parse($concreteTarget::parse)"
                }
            }
            if (rawMethod == "into_token_stream" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                val unwrappedRecv = unwrapParenOrGroupExpr(expr.receiver)
                val isSharedBorrowRecv =
                    (unwrappedRecv is Expr.Reference && unwrappedRecv.mutability == null) ||
                        (currentReceiverIsSharedBorrow && unwrappedRecv is Expr.Path && unwrappedRecv.qself == null && unwrappedRecv.path.isIdent("self"))
                if (isSharedBorrowRecv) {
                    val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    return "TokenStream.new().also { tokens -> $recvAtom.toTokens(tokens) }"
                }
            }
            val isEscapedDoubleUnderscoreSelfCall =
                rawMethod.startsWith("__") && rustMethodIdentToKotlin(rawMethod) == currentEnclosingFunctionName
            // quote's `__span` is emitted as `span` on the definition side, which collides with the
            // enclosing `span`. Call it through a camelCase import alias (never `__<name>`).
            val method =
                when {
                    isEscapedDoubleUnderscoreSelfCall && rawMethod == "__span" -> {
                        usesQuoteUnderlyingSpan = true
                        "quoteSpan"
                    }
                    isEscapedDoubleUnderscoreSelfCall -> {
                        val underlyingKt = rustMethodIdentToKotlin(rawMethod.removePrefix("__"))
                        val aliasKt = "underlying${underlyingKt.replaceFirstChar { it.uppercase() }}"
                        aliasedUnderlyingSelfCalls[underlyingKt] = aliasKt
                        aliasKt
                    }
                    cleanMethod == "assume_init" -> "assumeInit"
                    cleanMethod == "assume_init_read" -> "assumeInitRead"
                    (cleanMethod == "as_ptr" || cleanMethod == "as_mut_ptr") &&
                        (
                            receiver.startsWith("MaybeUninit.") ||
                                unwrapMaybeUninitRenderedInner(inferExprRenderedType(effectiveReceiverExpr, selfTypeName)) != null
                        ) ->
                        if (cleanMethod == "as_mut_ptr") "asMutPtr" else "asPtr"
                    else -> rustMethodIdentToKotlin(rawMethod)
                }
            val macroTypeArgs = mutableListOf<String>()
            val typeArgs =
                expr.turbofish
                    ?.let { tf ->
                        val renderedArgs = mutableListOf<String>()
                        for (ga in tf.args.toList()) {
                            val unwrappedMacro = (ga as? GenericArgument.TypeArg)?.type?.let { unwrapSynType(it) } as? SynType.Macro
                            if (unwrappedMacro != null) {
                                val renderedMac = renderMacroInvocationAsStmtOrExpr(unwrappedMacro.mac, indent, selfTypeName)
                                if (rawMethod == "parse" && nonUnitArgs.isEmpty() && renderedMac.endsWith(".default()")) {
                                    macroTypeArgs += "${renderedMac.removeSuffix(".default()")}::parse"
                                } else {
                                    macroTypeArgs += renderedMac
                                }
                            } else {
                                renderGenericArgument(ga, selfTypeName)?.let { renderedArgs += it }
                            }
                        }
                        if (renderedArgs.isNotEmpty()) "<${renderedArgs.joinToString(", ")}>" else ""
                    }.orEmpty()
            val savedKind = currentExpectedNumericKind
            val savedTupleElemType = currentClosureTupleElemType
            val zipElemType =
                if ((cleanMethod == "all" || cleanMethod == "any") &&
                    effectiveReceiverExpr is Expr.MethodCall &&
                    effectiveReceiverExpr.method.toString().removePrefix("r#") == "zip"
                ) {
                    val zipBase =
                        if (effectiveReceiverExpr.receiver is Expr.MethodCall &&
                            (effectiveReceiverExpr.receiver as Expr.MethodCall).args.isEmpty() &&
                            (effectiveReceiverExpr.receiver as Expr.MethodCall).turbofish == null &&
                            (effectiveReceiverExpr.receiver as Expr.MethodCall).method.toString().removePrefix("r#") in setOf("iter", "iter_mut", "into_iter")
                        ) {
                            (effectiveReceiverExpr.receiver as Expr.MethodCall).receiver
                        } else {
                            effectiveReceiverExpr.receiver
                        }
                    inferExprRenderedType(zipBase, selfTypeName)?.let { extractIndexedElementRenderedType(it, selfTypeName) }
                } else {
                    null
                }
            val recvNumericKindForMethod =
                if (typeArgs.isEmpty() &&
                    rawMethod in
                    setOf(
                        "wrapping_add",
                        "wrapping_sub",
                        "wrapping_mul",
                        "wrapping_div",
                        "wrapping_rem",
                        "wrapping_shl",
                        "wrapping_shr",
                        "checked_add",
                        "checked_sub",
                        "checked_mul",
                        "checked_div",
                        "checked_rem",
                        "checked_div_euclid",
                        "checked_rem_euclid",
                        "checked_shl",
                        "checked_shr",
                        "checked_pow",
                        "saturating_add",
                        "saturating_sub",
                        "saturating_mul",
                        "overflowing_add",
                        "overflowing_sub",
                        "overflowing_mul",
                        "overflowing_shl",
                        "overflowing_shr",
                        "div_euclid",
                        "rem_euclid",
                        "rotate_left",
                        "rotate_right",
                        "pow",
                        "powf",
                        "powi",
                        "min",
                        "max",
                        "atan2",
                        "hypot",
                        "log",
                        "mul_add",
                        "to_digit",
                    )
                ) {
                    inferExprNumericKind(expr.receiver, selfTypeName)
                } else {
                    null
                }
            val wrappingNumericKind =
                if (rawMethod in setOf("wrapping_add", "wrapping_sub", "wrapping_mul") &&
                    nonUnitArgs.size == 1 &&
                    typeArgs.isEmpty()
                ) {
                    recvNumericKindForMethod?.takeUnless {
                        it == NumericKind.F32 || it == NumericKind.F64 || it == NumericKind.I128 || it == NumericKind.U128
                    }
                } else {
                    null
                }
            currentExpectedNumericKind =
                when {
                    rawMethod == "to_digit" -> NumericKind.U32
                    recvNumericKindForMethod == null -> null
                    rawMethod in setOf("wrapping_shl", "wrapping_shr", "checked_shl", "checked_shr", "overflowing_shl", "overflowing_shr", "rotate_left", "rotate_right", "pow", "checked_pow") ->
                        NumericKind.U32
                    rawMethod == "powi" -> NumericKind.I32
                    else -> recvNumericKindForMethod
                }
            currentExpectedParseTargetType =
                if (cleanMethod in setOf("and_then", "or_else")) {
                    methodEntryParseTarget ?: currentEnclosingReturnRenderedType
                } else {
                    null
                }
            currentClosureTupleElemType = zipElemType
            val methodSubslicePre = mutableListOf<String>()
            val methodSubslicePost = mutableListOf<String>()
            val recvOwnerSimple =
                inferExprRenderedType(expr.receiver, selfTypeName)
                    ?.removeSuffix("?")
                    ?.substringAfterLast('.')
                    ?.substringBefore('<')
                    ?.trim()
            val renderedValueArgs =
                try {
                    nonUnitArgs.mapIndexed { argIdx, argExpr ->
                        val subsliceSpec =
                            tryExtractMutableArraySubsliceArg(
                                argExpr = argExpr,
                                argIndex = argIdx,
                                expectMutableList = methodExpectsMutableListSliceParam(recvOwnerSimple, method, argIdx),
                                indent = indent,
                                selfTypeName = selfTypeName,
                            )
                        if (subsliceSpec != null) {
                            methodSubslicePre += subsliceSpec.first
                            methodSubslicePost += subsliceSpec.third
                            subsliceSpec.second
                        } else {
                            renderExpr(argExpr, indent, selfTypeName)
                        }
                    }
                } finally {
                    currentExpectedNumericKind = savedKind
                    currentExpectedParseTargetType = methodEntryParseTarget
                    currentClosureTupleElemType = savedTupleElemType
                }
            if (wrappingNumericKind != null) {
                val op =
                    when (rawMethod) {
                        "wrapping_add" -> "+"
                        "wrapping_sub" -> "-"
                        else -> "*"
                    }
                val coercedRhs = coerceRenderedNumericExpr(nonUnitArgs[0], renderedValueArgs[0], wrappingNumericKind)
                val rhs = if (isAtomicPostfixReceiver(nonUnitArgs[0], coercedRhs)) coercedRhs else "($coercedRhs)"
                val lhs = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                val arithmetic = "($lhs $op $rhs)"
                // Kotlin promotes byte/short arithmetic; explicitly truncate back to Rust's width.
                return when (wrappingNumericKind) {
                    NumericKind.I8 -> "$arithmetic.toByte()"
                    NumericKind.U8 -> "$arithmetic.toUByte()"
                    NumericKind.I16 -> "$arithmetic.toShort()"
                    NumericKind.U16 -> "$arithmetic.toUShort()"
                    else -> arithmetic
                }
            }
            val hasherState = currentHasherStateParam
            if (hasherState != null &&
                rawMethod == "hash" &&
                renderedValueArgs.size == 1 &&
                renderedValueArgs[0] == hasherState &&
                typeArgs.isEmpty()
            ) {
                return "$hasherState = 31 * $hasherState + ($receiver).hashCode()"
            }
            if ("core" in headerContext.crateNamespaces && typeArgs.isEmpty()) {
                val recvTyForStr =
                    (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                        ?.removeSuffix("?")
                        ?.trim()
                val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                when {
                    rawMethod == "chars" &&
                        renderedValueArgs.isEmpty() &&
                        (recvTyForStr == "String" || recvTyForStr == "CharSequence") &&
                        "chars" !in inspector.declaredTopLevelFnRawNames &&
                        inspector.instanceMethodsByType.values.none { "chars" in it } -> {
                        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.str.rustStrChars")
                        return "$atomicRecv.rustStrChars()"
                    }
                    recvTyForStr == "RustStrChars" -> {
                        when {
                            rawMethod == "next" && renderedValueArgs.isEmpty() -> return "$atomicRecv.nextCodeOrNull()?.toChar()"
                            rawMethod == "as_str" && renderedValueArgs.isEmpty() -> return "$atomicRecv.asStr()"
                            rawMethod == "clone" && renderedValueArgs.isEmpty() -> return "$atomicRecv.clone()"
                            rawMethod == "enumerate" && renderedValueArgs.isEmpty() -> return "$atomicRecv.enumerate()"
                        }
                    }
                    rawMethod == "to_digit" &&
                        renderedValueArgs.size == 1 &&
                        (recvTyForStr == "Char" || (unwrapParenOrGroupExpr(expr.receiver) as? Expr.Lit)?.lit is Lit.Char) -> {
                        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.str.rustToDigit")
                        val radixArg = coerceNumericArgIfNeeded(nonUnitArgs[0], renderedValueArgs[0], NumericKind.U32, selfTypeName)
                        return "$atomicRecv.rustToDigit($radixArg)"
                    }
                }
            }
            renderConcreteNumericMethodCall(
                expr = expr,
                receiver = receiver,
                rawMethod = rawMethod,
                nonUnitArgs = nonUnitArgs,
                renderedValueArgs = renderedValueArgs,
                typeArgs = typeArgs,
                indent = indent,
                selfTypeName = selfTypeName,
            )?.let { return it }
            if (rawMethod == "append_all" && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
                return "for (item in ${renderedValueArgs[0]}) item.toTokens($receiver)"
            }
            if ((rawMethod == "write_str" || rawMethod == "write_char" || rawMethod == "pad") &&
                renderedValueArgs.size == 1 &&
                typeArgs.isEmpty()
            ) {
                return "$receiver.append(${renderedValueArgs[0]})"
            }
            if (rawMethod == "find" && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
                return "$receiver.indexOf(${renderedValueArgs[0]}).takeIf { it >= 0 }"
            }
            if ((rawMethod == "position" || rawMethod == "rposition") && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
                val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                val idxFn = if (rawMethod == "position") "indexOfFirst" else "indexOfLast"
                return "$recvAtom.$idxFn(${renderedValueArgs[0]}).takeIf { it >= 0 }"
            }
            if (rawMethod == "enumerate" && renderedValueArgs.isEmpty() && typeArgs.isEmpty()) {
                val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                return "$recvAtom.mapIndexed { index, item -> Pair(index, item) }"
            }
            if (rawMethod == "take" && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
                val recvCall = unwrapParenOrGroupExpr(expr.receiver) as? Expr.Call
                val recvCallee = recvCall?.let { renderCalleeExpr(it.func, indent, selfTypeName).substringBefore('<').trim() }
                val recvArgs =
                    recvCall
                        ?.args
                        ?.toList()
                        ?.filterNot { it is Expr.Tuple && it.elems.isEmpty() }
                        .orEmpty()
                if (recvArgs.size == 1 &&
                    "repeat" !in inspector.declaredTopLevelFnRawNames &&
                    (recvCallee == "repeat" || recvCallee == "iter.repeat" || recvCallee?.endsWith(".iter.repeat") == true)
                ) {
                    val elemRendered = renderExpr(recvArgs[0], indent, selfTypeName)
                    return "List(${renderedValueArgs[0]}) { $elemRendered }"
                }
            }
            if (rawMethod == "collect" && renderedValueArgs.isEmpty()) {
                val explicitCollectTarget = typeArgs.removePrefix("<").removeSuffix(">").trim()
                val effectiveCollectTarget =
                    explicitCollectTarget.ifEmpty {
                        (methodEntryParseTarget ?: currentEnclosingReturnRenderedType)?.trim().orEmpty()
                    }
                if (effectiveCollectTarget == "String") {
                    val recvTake = unwrapParenOrGroupExpr(expr.receiver) as? Expr.MethodCall
                    if (recvTake != null && recvTake.method.toString() == "take" && recvTake.args.len() == 1) {
                        val innerRepeat = unwrapParenOrGroupExpr(recvTake.receiver) as? Expr.Call
                        val repCallee = innerRepeat?.let { renderCalleeExpr(it.func, indent, selfTypeName).substringBefore('<').trim() }
                        val repArgs =
                            innerRepeat
                                ?.args
                                ?.toList()
                                ?.filterNot { it is Expr.Tuple && it.elems.isEmpty() }
                                .orEmpty()
                        if (repArgs.size == 1 &&
                            "repeat" !in inspector.declaredTopLevelFnRawNames &&
                            (repCallee == "repeat" || repCallee == "iter.repeat" || repCallee?.endsWith(".iter.repeat") == true)
                        ) {
                            val elemRendered = renderExpr(repArgs[0], indent, selfTypeName)
                            val countRendered = renderExpr(recvTake.args.first()!!, indent, selfTypeName)
                            return "($elemRendered).toString().repeat($countRendered)"
                        }
                    }
                    val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    return "$recvAtom.joinToString(\"\")"
                }
                if (explicitCollectTarget == "List<*>" ||
                    effectiveCollectTarget.startsWith("List<") ||
                    effectiveCollectTarget.startsWith("MutableList<")
                ) {
                    val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    return "$recvAtom.toMutableList()"
                }
                if (isMapRenderedType(effectiveCollectTarget)) {
                    val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    return "$recvAtom.toMap(LinkedHashMap())"
                }
                if (isSetRenderedType(effectiveCollectTarget)) {
                    val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    return "$recvAtom.toMutableSet()"
                }
            }
            if (rawMethod == "push_str" && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
                val recvTy =
                    (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                        ?.removeSuffix("?")
                        ?.trim()
                if (recvTy == "StringBuilder") {
                    return "$receiver.append(${renderedValueArgs[0]})"
                }
                if (recvTy == "String") {
                    return "$receiver += ${renderedValueArgs[0]}"
                }
                if (refCellGuardPointeeType(recvTy) == "String") {
                    val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    return "$recvAtom.value += ${renderedValueArgs[0]}"
                }
            }
            if (rawMethod == "push" && renderedValueArgs.size == 1 && typeArgs.isEmpty() && !isVecReceiver) {
                val recvTy =
                    (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                        ?.removeSuffix("?")
                        ?.trim()
                if (recvTy == "StringBuilder") {
                    return "$receiver.append(${renderedValueArgs[0]})"
                }
                if (recvTy == "String") {
                    return "$receiver += ${renderedValueArgs[0]}"
                }
                if (refCellGuardPointeeType(recvTy) == "String") {
                    val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    return "$recvAtom.value += ${renderedValueArgs[0]}"
                }
            }
            if (rawMethod == "clear" && renderedValueArgs.isEmpty() && typeArgs.isEmpty()) {
                val recvTy =
                    (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                        ?.removeSuffix("?")
                        ?.trim()
                if (refCellGuardPointeeType(recvTy) == "String") {
                    val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    return "$recvAtom.value = \"\""
                }
                if (recvTy == "String" && receiver.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
                    return "$receiver = \"\""
                }
            }
            if (rawMethod == "extend_from_slice" && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
                val recvTy =
                    (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                        ?.removeSuffix("?")
                        ?.trim()
                if (isVecReceiver || recvTy?.startsWith("MutableList<") == true) {
                    return "$receiver.addAll(${renderedValueArgs[0]})"
                }
            }
            if ((
                    rawMethod == "byte_offset" ||
                        rawMethod == "byte_add" ||
                        rawMethod == "byte_sub" ||
                        rawMethod == "wrapping_byte_offset" ||
                        rawMethod == "wrapping_byte_add" ||
                        rawMethod == "wrapping_byte_sub"
                ) &&
                renderedValueArgs.size == 1 &&
                typeArgs.isEmpty()
            ) {
                val deltaSign =
                    if (rawMethod == "sub" || rawMethod == "byte_sub" || rawMethod == "wrapping_sub" || rawMethod == "wrapping_byte_sub") {
                        "-"
                    } else {
                        "+"
                    }
                val deltaArg = renderedValueArgs[0]
                val deltaRecv = if (isAtomicPostfixReceiver(nonUnitArgs[0], deltaArg)) deltaArg else "($deltaArg)"
                val deltaExpr = convertNumericReceiver(deltaRecv, "Long", inferExprNumericKind(nonUnitArgs[0], selfTypeName))
                if (headerContext.rawAbiMode) {
                    return "($receiver $deltaSign $deltaExpr)"
                }
            }
            if (rawMethod == "debug_struct" && renderedValueArgs.size == 1 && typeArgs.isEmpty() && "DebugStruct" !in inspector.declaredTypeNames) {
                usesDebugStruct = true
                return "DebugStruct($receiver, ${renderedValueArgs[0]})"
            }
            if (rawMethod == "debug_tuple" && renderedValueArgs.size == 1 && typeArgs.isEmpty() && "DebugTuple" !in inspector.declaredTypeNames) {
                usesDebugTuple = true
                return "DebugTuple($receiver, ${renderedValueArgs[0]})"
            }
            if (rawMethod == "push_back" && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
                return "$receiver.addLast(${renderedValueArgs[0]})"
            }
            if (isVecReceiver && typeArgs.isEmpty()) {
                when {
                    rawMethod == "push" && renderedValueArgs.size == 1 ->
                        return "$receiver.add(${renderedValueArgs[0]})"
                    rawMethod == "insert" && renderedValueArgs.size == 2 ->
                        return "$receiver.add(${renderedValueArgs[0]}, ${renderedValueArgs[1]})"
                    rawMethod == "clone_from" && renderedValueArgs.size == 1 ->
                        return "run { $receiver.clear(); $receiver.addAll(${renderedValueArgs[0]}) }"
                    (rawMethod == "get" || rawMethod == "get_mut") && renderedValueArgs.size == 1 ->
                        return "$receiver.getOrNull(${renderedValueArgs[0]})"
                }
            }
            val recvCollectionTy = (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
            if (isMapRenderedType(recvCollectionTy) && typeArgs.isEmpty()) {
                val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                when {
                    rawMethod == "insert" && renderedValueArgs.size == 2 ->
                        return "$recvAtom.put(${renderedValueArgs[0]}, ${renderedValueArgs[1]})"
                    rawMethod == "clone" && renderedValueArgs.isEmpty() ->
                        return "$recvAtom.toMutableMap()"
                    (rawMethod == "get" || rawMethod == "get_mut") && renderedValueArgs.size == 1 ->
                        return "$recvAtom.get(${renderedValueArgs[0]})"
                }
            }
            if (isSetRenderedType(recvCollectionTy) && typeArgs.isEmpty()) {
                val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                when {
                    rawMethod == "insert" && renderedValueArgs.size == 1 ->
                        return "$recvAtom.add(${renderedValueArgs[0]})"
                    rawMethod == "clone" && renderedValueArgs.isEmpty() ->
                        return "$recvAtom.toMutableSet()"
                }
            }
            if (rawMethod == "clone_from" &&
                renderedValueArgs.size == 1 &&
                typeArgs.isEmpty() &&
                (expr.receiver is Expr.Field || receiver in currentNullableLocals)
            ) {
                return "$receiver = ${renderedValueArgs[0]}"
            }
            if ((rawMethod == "push" || rawMethod == "extend") &&
                !isVecReceiver &&
                renderedValueArgs.size == 1 &&
                typeArgs.isEmpty()
            ) {
                val defaultWitness =
                    currentTypeParamWitnessCalls.entries
                        .filter { it.key.second == "default" && it.value !in renderedValueArgs }
                        .map { it.value }
                        .distinct()
                        .singleOrNull()
                if (defaultWitness != null) {
                    return "$receiver.$method(${renderedValueArgs[0]}, $defaultWitness)"
                }
            }
            if (rawMethod == "map" && nonUnitArgs.size == 1 && renderedValueArgs.size == 1) {
                if (isTransparentWrapperOrIdentityMapArg(nonUnitArgs[0])) {
                    return receiver
                }
                if (typeArgs.isEmpty() && (receiver.startsWith("ArrayDeque(") || isDequeIterReceiver)) {
                    val mappedArg = wrapMapCallbackArg(nonUnitArgs[0], renderedValueArgs[0])
                    return "ArrayDeque(($receiver).map($mappedArg))"
                }
            }
            if ((rawMethod == "strip_prefix" || rawMethod == "strip_suffix") &&
                renderedValueArgs.size == 1 &&
                typeArgs.isEmpty()
            ) {
                val recvTy = inferExprRenderedType(expr.receiver, selfTypeName)?.removeSuffix("?")?.trim()
                if (recvTy == null || recvTy == "String") {
                    val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    val pred = if (rawMethod == "strip_prefix") "startsWith" else "endsWith"
                    val rem = if (rawMethod == "strip_prefix") "removePrefix" else "removeSuffix"
                    return "$atomicRecv.takeIf { it.$pred(${renderedValueArgs[0]}) }?.$rem(${renderedValueArgs[0]})"
                }
            }
            if ((rawMethod == "split_once" || rawMethod == "rsplit_once") &&
                renderedValueArgs.size == 1 &&
                typeArgs.isEmpty()
            ) {
                val recvTy =
                    (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                        ?.removeSuffix("?")
                        ?.trim()
                if (recvTy == null || recvTy == "String" || recvTy == "CharSequence" || refCellGuardPointeeType(recvTy) == "String") {
                    val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    val strExpr = if (refCellGuardPointeeType(recvTy) == "String") "$atomicRecv.value" else atomicRecv
                    val findFn = if (rawMethod == "split_once") "indexOf" else "lastIndexOf"
                    val argExpr = nonUnitArgs[0]
                    val unwrappedArg = unwrapParenOrGroupExpr(argExpr)
                    val argTy = inferExprRenderedType(argExpr, selfTypeName)?.removeSuffix("?")?.trim()
                    val isCharPat = (unwrappedArg as? Expr.Lit)?.lit is Lit.Char || argTy in setOf("Char", "RustChar")
                    return if (isCharPat) {
                        val charVal = if (argTy == "RustChar") "(${renderedValueArgs[0]}).toInt().toChar()" else renderedValueArgs[0]
                        "$strExpr.let { s -> val idx = s.$findFn($charVal); if (idx < 0) null else Pair(s.substring(0, idx), s.substring(idx + 1)) }"
                    } else {
                        "$strExpr.let { s -> val d = ${renderedValueArgs[0]}; val idx = s.$findFn(d); if (idx < 0) null else Pair(s.substring(0, idx), s.substring(idx + d.length)) }"
                    }
                }
            }
            if ((rawMethod == "to_lowercase" || rawMethod == "to_uppercase") &&
                renderedValueArgs.isEmpty() &&
                typeArgs.isEmpty()
            ) {
                val recvTy =
                    (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                        ?.removeSuffix("?")
                        ?.trim()
                if (refCellGuardPointeeType(recvTy) == "String") {
                    val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    val ktFn = if (rawMethod == "to_lowercase") "lowercase" else "uppercase"
                    return "$recvAtom.value.$ktFn()"
                }
                if (recvTy == "String" || recvTy == "CharSequence" ||
                    (recvTy == null && inspector.instanceMethodsByType.values.none { rustFunctionIdentToKotlin(rawMethod) in it })
                ) {
                    val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    val ktFn = if (rawMethod == "to_lowercase") "lowercase" else "uppercase"
                    return "$atomicRecv.$ktFn()"
                }
            }
            if (rawMethod == "filter_map" &&
                renderedValueArgs.size == 1 &&
                typeArgs.isEmpty() &&
                inspector.instanceMethodsByType.values.none { "filterMap" in it }
            ) {
                val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                val mappedArg = wrapMapCallbackArg(nonUnitArgs[0], renderedValueArgs[0])
                return "$atomicRecv.mapNotNull($mappedArg)"
            }
            val inferredMethodRecvTy = inferExprRenderedType(expr.receiver, selfTypeName)?.removeSuffix("?")?.trim()
            if (rawMethod == "to_vec" && renderedValueArgs.isEmpty() && typeArgs.isEmpty()) {
                val unwrappedRecvExpr = unwrapTransparentCastExpr(expr.receiver)
                val isRangeSliceExpr = unwrappedRecvExpr is Expr.Index && unwrappedRecvExpr.index is Expr.Range
                val isListOrByteSlice =
                    inferredMethodRecvTy?.startsWith("List<") == true ||
                        inferredMethodRecvTy?.startsWith("MutableList<") == true ||
                        inferredMethodRecvTy?.startsWith("ArrayList<") == true ||
                        isRangeSliceExpr ||
                        receiver.endsWith(".toBytes()") ||
                        receiver.endsWith(".asBytes()") ||
                        receiver.endsWith(".asSlice()")
                if (isListOrByteSlice) {
                    val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                    return "$atomicRecv.toMutableList()"
                }
            }
            if ((rawMethod == "as_slice" || rawMethod == "size_hint") &&
                renderedValueArgs.isEmpty() &&
                typeArgs.isEmpty() &&
                (receiver in currentDequeLocals || isDequeIterReceiver || inferredMethodRecvTy?.startsWith("ArrayDeque<") == true)
            ) {
                val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                if (rawMethod == "as_slice") {
                    return "$atomicRecv.toList()"
                }
                if (rawMethod == "size_hint") {
                    return "run { val n = $atomicRecv.size; Pair(n, n) }"
                }
            }
            if (inferredMethodRecvTy == "RustAny" && renderedValueArgs.isEmpty() && typeArgs.isNotEmpty()) {
                val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                when (rawMethod) {
                    "is" -> return "$atomicRecv.isType$typeArgs()"
                    "downcast_ref" -> return "$atomicRecv.downcastRef$typeArgs()"
                    "downcast_mut" -> return "$atomicRecv.downcastMut$typeArgs()"
                    "downcast" -> return "$atomicRecv.downcast$typeArgs()"
                }
            }
            if (rawMethod == "type_id" &&
                renderedValueArgs.isEmpty() &&
                typeArgs.isEmpty() &&
                "TypeId" !in inspector.declaredTypeNames &&
                "Any" !in inspector.declaredTraitsByName
            ) {
                usesRustAny = true
                val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                return if (inferredMethodRecvTy == "RustAny") {
                    "$atomicRecv.typeId"
                } else {
                    "RustAny.from($receiver).typeId"
                }
            }
            if (rawMethod == "take" &&
                nonUnitArgs.isEmpty() &&
                typeArgs.isEmpty() &&
                receiver.all { it.isLetterOrDigit() || it == '_' || it == '.' } &&
                (expr.receiver is Expr.Field || (expr.receiver is Expr.Path && receiver != "cell"))
            ) {
                return "$receiver.also { $receiver = null }"
            }
            if (!hasDeclaredOptionEnum() && isKnownNullableOptionExpr(expr.receiver, receiver, selfTypeName) && typeArgs.isEmpty()) {
                val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                when {
                    rawMethod == "unwrap" && renderedValueArgs.isEmpty() -> return "$atomicRecv!!"
                    rawMethod == "expect" && renderedValueArgs.size == 1 -> return "($receiver ?: error(${renderedValueArgs[0]}))"
                    (rawMethod == "clone" || rawMethod == "cloned" || rawMethod == "copied") && renderedValueArgs.isEmpty() ->
                        return receiver
                    rawMethod == "ok_or" && renderedValueArgs.size == 1 ->
                        return if (usesTaggedCoreResult()) {
                            val res = sourceResultName()
                            "($atomicRecv?.let { $res.Ok(it) } ?: $res.Err(${renderedValueArgs[0]}))"
                        } else {
                            "($atomicRecv?.let { Result.success(it) } ?: Result.failure(${renderedValueArgs[0]}))"
                        }
                    rawMethod == "ok_or_else" && renderedValueArgs.size == 1 -> {
                        val errExpr = unwrapZeroArgClosureText(renderedValueArgs[0])
                        return if (usesTaggedCoreResult()) {
                            val res = sourceResultName()
                            "($atomicRecv?.let { $res.Ok(it) } ?: $res.Err($errExpr))"
                        } else {
                            "($atomicRecv?.let { Result.success(it) } ?: Result.failure($errExpr))"
                        }
                    }
                    rawMethod == "into_iter" && renderedValueArgs.isEmpty() -> return "ArrayDeque(listOfNotNull($receiver))"
                    rawMethod == "flatten" && renderedValueArgs.isEmpty() -> return receiver
                    (rawMethod == "map" || rawMethod == "and_then") && renderedValueArgs.size == 1 ->
                        return "$atomicRecv?.let(${wrapMapCallbackArg(nonUnitArgs[0], renderedValueArgs[0])})"
                    rawMethod == "map_or" && renderedValueArgs.size == 2 ->
                        return "($atomicRecv?.let(${wrapMapCallbackArg(nonUnitArgs[1], renderedValueArgs[1])}) ?: ${renderedValueArgs[0]})"
                    rawMethod == "map_or_else" && renderedValueArgs.size == 2 ->
                        return "($atomicRecv?.let(${wrapMapCallbackArg(nonUnitArgs[1], renderedValueArgs[1])}) ?: ${unwrapZeroArgClosureText(renderedValueArgs[0])})"
                    (rawMethod == "or_else" || rawMethod == "unwrap_or_else") && renderedValueArgs.size == 1 -> {
                        val optionElemKind = inferredMethodRecvTy?.let(this::resolveNumericKind)
                        val renderedClosure =
                            if (optionElemKind != null) {
                                val prevKind = currentExpectedNumericKind
                                currentExpectedNumericKind = optionElemKind
                                try {
                                    renderExpr(nonUnitArgs[0], indent, selfTypeName)
                                } finally {
                                    currentExpectedNumericKind = prevKind
                                }
                            } else {
                                renderedValueArgs[0]
                            }
                        return "($receiver ?: ${unwrapZeroArgClosureText(renderedClosure)})"
                    }
                    rawMethod == "unwrap_or" && renderedValueArgs.size == 1 -> {
                        val optionElemKind = inferredMethodRecvTy?.let(this::resolveNumericKind)
                        val fallbackArg =
                            if (optionElemKind != null) {
                                renderTypedNumericInitializer(nonUnitArgs[0], optionElemKind, indent, selfTypeName)
                            } else {
                                renderedValueArgs[0]
                            }
                        return "($receiver ?: $fallbackArg)"
                    }
                    rawMethod == "or" && renderedValueArgs.size == 1 -> {
                        val optionElemKind = inferredMethodRecvTy?.let(this::resolveNumericKind)
                        val fallbackOptArg =
                            if (optionElemKind != null) {
                                val prevKind = currentExpectedNumericKind
                                currentExpectedNumericKind = optionElemKind
                                try {
                                    renderExpr(nonUnitArgs[0], indent, selfTypeName)
                                } finally {
                                    currentExpectedNumericKind = prevKind
                                }
                            } else {
                                renderedValueArgs[0]
                            }
                        return "($receiver ?: $fallbackOptArg)"
                    }
                }
            }
            if ((cleanMethod == "fold" || cleanMethod == "rfold") &&
                typeArgs.isEmpty() &&
                nonUnitArgs.size == 2 &&
                renderedValueArgs.size == 2 &&
                renderedValueArgs[0] == "null"
            ) {
                val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                val closureExpr = nonUnitArgs[1] as? Expr.Closure
                val inferredBodyTy =
                    closureExpr
                        ?.let { inferExprRenderedType(it.body, selfTypeName) }
                        ?.removeSuffix("?")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() && it != "Nothing" && it != "Any" && !it.startsWith("Pair<Nothing") }
                val inferredRecvElemTy =
                    inferExprRenderedType(effectiveReceiverExpr, selfTypeName)
                        ?.let { extractIndexedElementRenderedType(it, selfTypeName) }
                        ?.removeSuffix("?")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() && it != "Nothing" && it != "Any" }
                val renderedClosure = renderedValueArgs[1].trim()
                if (renderedClosure.startsWith("{") && renderedClosure.endsWith("}") && "->" in renderedClosure) {
                    val innerClosure = renderedClosure.substring(1, renderedClosure.length - 1).trim()
                    val header = innerClosure.substringBefore("->").trim()
                    val body = innerClosure.substringAfter("->").trim()
                    if ('<' !in header && ':' !in header && '{' !in header && '(' !in header) {
                        val headerParams = header.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        if (headerParams.size == 2) {
                            val accParam = headerParams[0]
                            val itemParam = headerParams[1]
                            val accUsed =
                                accParam != "_" &&
                                    Regex("\\b${Regex.escape(accParam)}\\b").containsMatchIn(body)
                            if (!accUsed) {
                                if (body == itemParam) {
                                    return if (cleanMethod == "fold") "$atomicRecv.lastOrNull()" else "$atomicRecv.firstOrNull()"
                                }
                                if (inferredBodyTy == null) {
                                    return if (cleanMethod == "fold") {
                                        "$atomicRecv.map({ $itemParam -> $body }).lastOrNull()"
                                    } else {
                                        "$atomicRecv.asReversed().map({ $itemParam -> $body }).lastOrNull()"
                                    }
                                }
                            }
                        }
                    }
                }
                val accTy = inferredBodyTy ?: inferredRecvElemTy
                if (accTy != null) {
                    val typedArgs = (macroTypeArgs + listOf("null as $accTy?", renderedValueArgs[1])).joinToString(", ")
                    return "$receiver.$method($typedArgs)"
                }
            }
            val adjustedRenderedValueArgs =
                if ((rawMethod == "peek" || rawMethod == "peek2" || rawMethod == "peek3") &&
                    nonUnitArgs.size == 1 &&
                    nonUnitArgs[0] is Expr.Macro &&
                    renderedValueArgs.size == 1
                ) {
                    listOf(renderedValueArgs[0].removeSuffix(".default()"))
                } else if (rawMethod in setOf("map", "map_err", "and_then", "or_else") &&
                    nonUnitArgs.size == 1 &&
                    renderedValueArgs.size == 1
                ) {
                    listOf(wrapMapCallbackArg(nonUnitArgs[0], renderedValueArgs[0]))
                } else {
                    renderedValueArgs
                }
            val valueArgs = macroTypeArgs + adjustedRenderedValueArgs
            val args = valueArgs.joinToString(", ")
            if ("core" in headerContext.crateNamespaces &&
                typeArgs.isEmpty() &&
                method in setOf("asPtr", "asMutPtr", "chunksMut", "chunks")
            ) {
                val recvCheckExpr =
                    (unwrapParenOrGroupExpr(expr.receiver) as? Expr.Reference)?.let { unwrapParenOrGroupExpr(it.expr) }
                        ?: expr.receiver
                val recvType = inferExprRenderedType(recvCheckExpr, selfTypeName)?.removeSuffix("?")?.trim()
                val recvOwnerSimple = recvType?.substringBefore('<')?.substringAfterLast('.')?.trim()
                val hasUserMethod = recvOwnerSimple != null && method in inspector.instanceMethodsByType[recvOwnerSimple].orEmpty()
                val isStorageOrMaybeUninit =
                    receiver.startsWith("MaybeUninit.") ||
                        unwrapMaybeUninitRenderedInner(recvType) != null ||
                        receiver in currentAddressableLocals.values
                val isNonSliceCoreType =
                    recvOwnerSimple in setOf("MaybeUninit", "ConstPtr", "MutPtr", "AddressableLocal", "AddressableArrayLocal")
                val isKnownSliceOrArrayType =
                    recvType != null &&
                        (
                            recvType.startsWith("List<") ||
                                recvType.startsWith("MutableList<") ||
                                recvType.startsWith("Slice<") ||
                                recvType.startsWith("FixedArray<") ||
                                recvOwnerSimple in PRIMITIVE_ARRAY_TYPES ||
                                recvOwnerSimple?.endsWith("Slice") == true
                        )
                if (!isStorageOrMaybeUninit &&
                    !hasUserMethod &&
                    !isNonSliceCoreType &&
                    (recvType == null || isKnownSliceOrArrayType)
                ) {
                    CoreIntrinsicLowering.registerCoreImport(
                        this,
                        "${CoreIntrinsicLowering.resolveCoreNamespace(headerContext)}.slice.$method",
                    )
                }
            }
            val callRendered = "$receiver.$method$typeArgs($args)"
            val finalMethodCall = if (method == "zip") "($callRendered)" else callRendered
            if (methodSubslicePre.isEmpty()) {
                finalMethodCall
            } else {
                val preJoined = methodSubslicePre.joinToString("; ")
                val postJoined = methodSubslicePost.joinToString("; ")
                "run { $preJoined; val subCall0 = $finalMethodCall; $postJoined; subCall0 }"
            }
        }
        is Expr.Field -> {
            val savedKind = currentExpectedNumericKind
            currentExpectedNumericKind = null
            val base =
                try {
                    renderExpr(expr.base, indent, selfTypeName)
                } finally {
                    currentExpectedNumericKind = savedKind
                }
            val baseRenderedTy = inferExprRenderedType(expr.base, selfTypeName)
            val isSimpleBaseLocal = base.isNotEmpty() && base.all { it.isLetterOrDigit() || it == '_' }
            val safeBase =
                if (mutableReferencePointeeType(baseRenderedTy) != null) {
                    "($base).read()"
                } else if (refCellGuardPointeeType(baseRenderedTy) != null) {
                    "($base).value"
                } else if (baseRenderedTy?.endsWith("?") == true && !base.endsWith("!!")) {
                    if (isSimpleBaseLocal && base in currentSmartCastNonNullLocals) {
                        base
                    } else {
                        if (isSimpleBaseLocal) currentSmartCastNonNullLocals = currentSmartCastNonNullLocals + base
                        val recv = if (isAtomicPostfixReceiver(expr.base, base)) base else "($base)"
                        "$recv!!"
                    }
                } else {
                    base
                }
            when (val m = expr.member) {
                is Member.Named -> "$safeBase.${rustFieldIdentToKotlin(m.ident.toString())}"
                is Member.Unnamed -> "$safeBase.${tupleAccessMemberName(expr.base, m.index.index.toInt(), selfTypeName)}"
            }
        }
        is Expr.Index -> {
            val savedKind = currentExpectedNumericKind
            currentExpectedNumericKind = null
            try {
                val base = renderExpr(expr.expr, indent, selfTypeName)
                val indexExpr = expr.index
                if (indexExpr is Expr.Range) {
                    val start = indexExpr.start?.let { renderExpr(it, indent, selfTypeName) }
                    val end = indexExpr.end?.let { renderExpr(it, indent, selfTypeName) }
                    val op =
                        when (indexExpr.limits) {
                            is RangeLimits.HalfOpen -> " until "
                            is RangeLimits.Closed -> ".."
                        }
                    val baseTy = inferExprRenderedType(expr.expr, selfTypeName)?.removeSuffix("?")?.trim()
                    when {
                        baseTy == "String" ->
                            when {
                                start == null && end == null -> base
                                start != null && end != null -> "$base.substring($start$op$end)"
                                start != null -> "$base.substring($start)"
                                else -> "$base.substring(0$op$end)"
                            }
                        baseTy != null && baseTy in PRIMITIVE_ARRAY_TYPES ->
                            when {
                                start == null && end == null -> base
                                start != null && end != null -> "(($base).sliceArray($start$op$end))"
                                start != null -> "(($base).sliceArray($start until $base.size))"
                                else -> "(($base).sliceArray(0$op$end))"
                            }
                        headerContext.rawAbiMode ||
                            headerContext.finalClasses ||
                            baseTy?.startsWith("List<") == true ||
                            baseTy?.startsWith("MutableList<") == true ->
                            when {
                                start == null && end == null -> base
                                start != null && end != null -> "(($base).slice($start$op$end))"
                                start != null -> "(($base).slice($start until $base.size))"
                                else -> "(($base).slice(0$op$end))"
                            }
                        else ->
                            when {
                                start == null && end == null -> base
                                start != null && end != null -> "$base[$start$op$end]"
                                start != null -> "$base[$start until $base.size]"
                                else -> "$base[0$op$end]"
                            }
                    }
                } else {
                    val idx = renderExpr(indexExpr, indent, selfTypeName)
                    "$base[$idx]"
                }
            } finally {
                currentExpectedNumericKind = savedKind
            }
        }
        is Expr.Tuple -> {
            val elems = expr.elems.toList()
            val pairCtor = if ("Pair" in inspector.declaredTypeNames) "kotlin.Pair" else "Pair"
            val tripleCtor = if ("Triple" in inspector.declaredTypeNames) "kotlin.Triple" else "Triple"
            when (elems.size) {
                0 -> "Unit"
                1 -> renderExpr(elems[0], indent, selfTypeName)
                2 -> "$pairCtor(${renderExpr(elems[0], indent, selfTypeName)}, ${renderExpr(elems[1], indent, selfTypeName)})"
                3 -> "$tripleCtor(${renderExpr(elems[0], indent, selfTypeName)}, ${renderExpr(elems[1], indent, selfTypeName)}, ${renderExpr(elems[2], indent, selfTypeName)})"
                else -> "listOf(${elems.joinToString(", ") { renderExpr(it, indent, selfTypeName) }})"
            }
        }
        is Expr.Array -> {
            val targetTy = currentExpectedParseTargetType?.removeSuffix("?")?.trim()
            val resolvedArrayTy =
                targetTy?.takeIf { it in PRIMITIVE_ARRAY_TYPES }
                    ?: if (targetTy == null || (!targetTy.startsWith("List<") && !targetTy.startsWith("MutableList<") && !targetTy.startsWith("FixedArray<") && !targetTy.startsWith("Slice<"))) {
                        inferPrimitiveArrayTypeForArrayLiteral(expr, selfTypeName)
                    } else {
                        null
                    }
            if (resolvedArrayTy != null) {
                renderStructPrimitiveArrayFieldInit(expr, resolvedArrayTy, indent, selfTypeName)
            } else {
                val elemKind = targetTy?.let(::primitiveArrayElementNumericKind)
                val elemParseTarget = targetTy?.let { extractIndexedElementRenderedType(it, selfTypeName) }
                val prevParseTarget = currentExpectedParseTargetType
                currentExpectedParseTargetType = elemParseTarget
                val elems =
                    try {
                        expr.elems.toList().joinToString(", ") { elem ->
                            if (elemKind != null) {
                                val renderedElem = renderTypedNumericInitializer(elem, elemKind, indent, selfTypeName)
                                val alreadyConverted =
                                    when (elemKind) {
                                        NumericKind.U8 -> renderedElem.endsWith(".toUByte()")
                                        NumericKind.U16 -> renderedElem.endsWith(".toUShort()")
                                        NumericKind.I8 -> renderedElem.endsWith(".toByte()")
                                        NumericKind.I16 -> renderedElem.endsWith(".toShort()")
                                        else -> true
                                    }
                                if (!alreadyConverted &&
                                    elemKind in setOf(NumericKind.U8, NumericKind.U16, NumericKind.I8, NumericKind.I16) &&
                                    inferExprNumericKind(elem, selfTypeName) != elemKind
                                ) {
                                    coerceRenderedNumericExpr(elem, renderedElem, elemKind)
                                } else {
                                    renderedElem
                                }
                            } else {
                                renderExpr(elem, indent, selfTypeName)
                            }
                        }
                    } finally {
                        currentExpectedParseTargetType = prevParseTarget
                    }
                if (targetTy?.startsWith("MutableList<") == true) "mutableListOf($elems)" else "listOf($elems)"
            }
        }
        is Expr.Repeat -> {
            val targetTy = currentExpectedParseTargetType?.removeSuffix("?")?.trim()
            val resolvedArrayTy =
                targetTy?.takeIf { it in PRIMITIVE_ARRAY_TYPES }
                    ?: if (targetTy == null || (!targetTy.startsWith("List<") && !targetTy.startsWith("MutableList<") && !targetTy.startsWith("FixedArray<") && !targetTy.startsWith("Slice<"))) {
                        inferPrimitiveArrayTypeForArrayLiteral(expr, selfTypeName)
                    } else {
                        null
                    }
            if (resolvedArrayTy != null) {
                renderStructPrimitiveArrayFieldInit(expr, resolvedArrayTy, indent, selfTypeName)
            } else {
                val elemKind = targetTy?.let(::primitiveArrayElementNumericKind)
                val elemParseTarget = targetTy?.let { extractIndexedElementRenderedType(it, selfTypeName) }
                val prevParseTarget = currentExpectedParseTargetType
                currentExpectedParseTargetType = elemParseTarget
                val elem =
                    try {
                        if (elemKind != null) {
                            val renderedElem = renderTypedNumericInitializer(expr.expr, elemKind, indent, selfTypeName)
                            val alreadyConverted =
                                when (elemKind) {
                                    NumericKind.U8 -> renderedElem.endsWith(".toUByte()")
                                    NumericKind.U16 -> renderedElem.endsWith(".toUShort()")
                                    NumericKind.I8 -> renderedElem.endsWith(".toByte()")
                                    NumericKind.I16 -> renderedElem.endsWith(".toShort()")
                                    else -> true
                                }
                            if (!alreadyConverted &&
                                elemKind in setOf(NumericKind.U8, NumericKind.U16, NumericKind.I8, NumericKind.I16) &&
                                inferExprNumericKind(expr.expr, selfTypeName) != elemKind
                            ) {
                                coerceRenderedNumericExpr(expr.expr, renderedElem, elemKind)
                            } else {
                                renderedElem
                            }
                        } else {
                            renderExpr(expr.expr, indent, selfTypeName)
                        }
                    } finally {
                        currentExpectedParseTargetType = prevParseTarget
                    }
                val len = normalizeArrayLengthToIntExpr(renderTypedNumericInitializer(expr.len, NumericKind.I32, indent, selfTypeName), expr.len, selfTypeName)
                if (targetTy?.startsWith("MutableList<") == true) "MutableList($len) { $elem }" else "List($len) { $elem }"
            }
        }
        is Expr.Struct -> renderExprStruct(expr, indent, selfTypeName)
        is Expr.Assign -> {
            val leftExpr = expr.left
            if (leftExpr is Expr.Infer) {
                return if (isSideEffectFreeExpr(expr.right)) "" else renderExpr(expr.right, indent, selfTypeName)
            }
            if (leftExpr is Expr.Unary && leftExpr.op is UnOp.Deref &&
                rawPointerPointeeType(inferExprRenderedType(leftExpr.expr, selfTypeName)) != null
            ) {
                val pointeeLayout = resolveRawPointerPointeeLayout(leftExpr.expr, selfTypeName)
                return "($pointeeLayout).writeNative((${renderExpr(leftExpr.expr, indent, selfTypeName)}).rawAddress, ${renderExpr(expr.right, indent, selfTypeName)})"
            }
            if (leftExpr is Expr.Unary && leftExpr.op is UnOp.Deref &&
                mutableReferencePointeeType(inferExprRenderedType(leftExpr.expr, selfTypeName)) != null
            ) {
                return "(${renderExpr(leftExpr.expr, indent, selfTypeName)}).write(${renderExpr(expr.right, indent, selfTypeName)})"
            }
            if (leftExpr is Expr.Unary && leftExpr.op is UnOp.Deref &&
                mutablePlaceValueType(inferExprRenderedType(leftExpr.expr, selfTypeName)) != null
            ) {
                return "(${renderExpr(leftExpr.expr, indent, selfTypeName)}).set(${renderExpr(expr.right, indent, selfTypeName)})"
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
            "$left = $right"
        }
        is Expr.Range -> {
            val start = expr.start?.let { renderExpr(it, indent, selfTypeName) }
            val end = expr.end?.let { renderExpr(it, indent, selfTypeName) }
            val op =
                when (expr.limits) {
                    is RangeLimits.HalfOpen -> " until "
                    is RangeLimits.Closed -> ".."
                }
            when {
                start != null && end != null -> "$start$op$end"
                start != null -> "$start${op}Int.MAX_VALUE"
                end != null -> "0$op$end"
                else -> "0 until 0"
            }
        }
        is Expr.Closure -> renderExprClosure(expr, indent, selfTypeName)
        is Expr.If -> renderExprIf(expr, indent, selfTypeName)
        is Expr.While -> renderExprWhile(expr, indent, selfTypeName)
        is Expr.ForLoop -> renderExprForLoop(expr, indent, selfTypeName)
        is Expr.Loop -> {
            val body = renderBlock(expr.body, indent, selfTypeName)
            "while (true) $body"
        }
        is Expr.Match -> renderExprMatch(expr, indent, selfTypeName)
        is Expr.BlockExpr -> {
            renderBlockAsExpression(expr.block, indent, selfTypeName)
        }
        is Expr.Unsafe -> {
            renderBlockAsExpression(expr.block, indent, selfTypeName)
        }
        is Expr.Async -> {
            renderBlockAsExpression(expr.block, indent, selfTypeName)
        }
        is Expr.Const -> {
            renderBlockAsExpression(expr.block, indent, selfTypeName)
        }
        is Expr.Return -> {
            val retNumKind = currentEnclosingReturnRenderedType?.let(this::resolveNumericKind)
            val valExpr =
                expr.expr?.let { retArg ->
                    val rawRendered = renderTypedNumericInitializer(retArg, retNumKind, indent, selfTypeName)
                    coerceTailFmtUnitToResultOk(retArg, rawRendered, selfTypeName)
                }
            val retKw = currentClosureLabel?.let { "return@$it" } ?: "return"
            if (valExpr != null) "$retKw $valExpr" else retKw
        }
        is Expr.Break -> {
            val valExpr = expr.expr?.let { renderExpr(it, indent, selfTypeName) }
            if (valExpr != null) "break" else "break"
        }
        is Expr.Continue -> "continue"
        is Expr.Let -> {
            val patCond = renderIfLetCondition(expr.pat, expr.expr, indent, selfTypeName)
            patCond
        }
        is Expr.Macro -> {
            CoreIntrinsicLowering.renderConstEvalSelectMacro(expr.mac, this, indent, selfTypeName)
                ?: renderMacroInvocationAsStmtOrExpr(expr.mac, indent, selfTypeName)
        }
        is Expr.TryBlock -> {
            renderBlockAsExpression(expr.block, indent, selfTypeName)
        }
        is Expr.Yield -> {
            val valExpr = expr.expr?.let { renderExpr(it, indent, selfTypeName) }
            if (valExpr != null) "yield($valExpr)" else "yield()"
        }
        is Expr.Infer -> "_"
        is Expr.Verbatim -> ""
    }
}

internal fun KotlinJ2KEmitter.renderBlockAsExpression(block: Block, indent: String, selfTypeName: String?): String {
    val stmts = block.stmts
    if (stmts.isEmpty()) return "Unit"
    if (stmts.size == 1) {
        val only = stmts[0]
        if (only is Stmt.ExprStmt && only.semiToken == null) {
            return renderExpr(only.expr, indent, selfTypeName)
        }
        if (only is Stmt.MacroStmt && only.semiToken == null) {
            return renderMacroInvocationAsStmtOrExpr(only.mac, indent, selfTypeName)
        }
    }
    val simplifiedOverwrite = tryExtractPureOverwrittenTailExpr(stmts)
    if (simplifiedOverwrite != null) {
        return renderExpr(simplifiedOverwrite, indent, selfTypeName)
    }
    return "run ${renderBlock(block, indent, selfTypeName)}"
}

internal fun KotlinJ2KEmitter.tryExtractPureOverwrittenTailExpr(stmts: List<Stmt>): Expr? {
    if (stmts.size < 2) return null
    val tailStmt = stmts.last() as? Stmt.ExprStmt ?: return null
    if (tailStmt.semiToken != null) return null
    val tailPath = tailStmt.expr as? Expr.Path ?: return null
    if (tailPath.qself != null || tailPath.path.segments.len() != 1) return null
    val tailIdent =
        tailPath.path.segments
            .first()
            ?.ident
            ?.toString()
            ?.removePrefix("r#") ?: return null

    var lastInitExpr: Expr? = null
    for (i in 0 until stmts.size - 1) {
        val local = stmts[i] as? Stmt.Local ?: return null
        val patIdent = local.pat as? Pat.Ident ?: return null
        if (patIdent.subpat != null) return null
        if (patIdent.ident.toString().removePrefix("r#") != tailIdent) return null
        val init = local.init ?: return null
        if (init.diverge != null) return null
        if (i < stmts.size - 2 && !isPureArithmeticOrSideEffectFreeExpr(init.expr)) {
            return null
        }
        lastInitExpr = init.expr
    }
    val finalExpr = lastInitExpr ?: return null
    if (exprReferencesIdent(finalExpr, tailIdent)) return null
    return finalExpr
}

internal fun KotlinJ2KEmitter.isPureArithmeticOrSideEffectFreeExpr(expr: Expr): Boolean =
    when (expr) {
        is Expr.Lit, is Expr.Path -> true
        is Expr.Paren -> isPureArithmeticOrSideEffectFreeExpr(expr.expr)
        is Expr.Group -> isPureArithmeticOrSideEffectFreeExpr(expr.expr)
        is Expr.Reference -> isPureArithmeticOrSideEffectFreeExpr(expr.expr)
        is Expr.Cast -> isPureArithmeticOrSideEffectFreeExpr(expr.expr)
        is Expr.Unary -> isPureArithmeticOrSideEffectFreeExpr(expr.expr)
        is Expr.Binary ->
            !isCompoundAssignOp(expr.op) &&
                isPureArithmeticOrSideEffectFreeExpr(expr.left) &&
                isPureArithmeticOrSideEffectFreeExpr(expr.right)
        else -> false
    }

internal fun KotlinJ2KEmitter.exprReferencesIdent(expr: Expr, identName: String): Boolean {
    var found = false
    val visitor =
        object : Visit() {
            override fun visitExprPath(e: Expr.Path) {
                if (e.qself == null &&
                    e.path.segments.len() == 1 &&
                    e.path.segments
                        .first()
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#") == identName
                ) {
                    found = true
                    return
                }
                super.visitExprPath(e)
            }
        }
    visitor.visitExpr(expr)
    return found
}

internal fun KotlinJ2KEmitter.tupleAccessMemberName(baseExpr: Expr, index: Int, selfTypeName: String?): String {
    val ownerBase = selfTypeName?.substringBefore('<')?.trim()
    if (baseExpr is Expr.Path && baseExpr.path.isIdent("self")) {
        val arity = ownerBase?.let { inspector.tupleStructArityByType[it] } ?: 1
        return tupleFieldPropertyName(index, arity)
    }
    val baseTy =
        inferExprRenderedType(baseExpr, selfTypeName)
            ?.removeSuffix("?")
            ?.substringBefore('<')
            ?.substringAfterLast('.')
            ?.trim()
    val hasExternalSingleTupleValueField =
        baseTy != null &&
            scopedAbiKeysForRenderedType(baseTy).any { key ->
                "$key.value" in headerContext.crateStructFieldTypes &&
                    "$key.field1" !in headerContext.crateStructFieldTypes
            }
    if (baseTy == "Wrapping" ||
        (baseTy != null && inspector.tupleStructArityByType[baseTy] == 1) ||
        hasExternalSingleTupleValueField ||
        (
            ownerBase != null &&
                inspector.tupleStructArityByType[ownerBase] == 1 &&
                baseExpr is Expr.Path &&
                baseExpr.qself == null &&
                baseExpr.path.segments.len() == 1
        )
    ) {
        return if (index == 0) "value" else "field$index"
    }
    return when (index) {
        0 -> "first"
        1 -> "second"
        2 -> "third"
        else -> "field$index"
    }
}

internal fun KotlinJ2KEmitter.resolveContainerElementTraitCallee(callee: String): String? {
    val standaloneTraitPath = currentStandaloneImplTraitPath ?: return null
    val standaloneSelfSynType = currentStandaloneImplSelfSynType ?: return null
    val lastTraitSeg = standaloneTraitPath.segments.last() ?: return null
    val traitName = rustTypeIdentToKotlin(lastTraitSeg.ident.toString())
    if (!callee.startsWith("$traitName.")) return null
    val methodPart = callee.removePrefix("$traitName.")
    if (methodPart.isEmpty() || '.' in methodPart) return null
    val selfPath = (unwrapSynType(standaloneSelfSynType) as? SynType.Path)?.takeIf { it.qself == null } ?: return null
    val lastSelfSeg = selfPath.path.segments.last() ?: return null
    if (lastSelfSeg.ident.toString() != "Vec") return null
    val elemSynType =
        (lastSelfSeg.arguments as? PathArguments.AngleBracketed)
            ?.args
            ?.toList()
            ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
            ?: return null
    return disambiguateReceiverlessTraitImplFnName(standaloneTraitPath, methodPart, elemSynType)
}

internal fun KotlinJ2KEmitter.extractOwnerAndFieldName(expr: Expr, selfTypeName: String?): Pair<String?, String>? {
    val core = unwrapTransparentCastExpr(expr)
    if (core !is Expr.Field) return null
    val member = core.member as? Member.Named ?: return null
    val fieldName = rustFieldIdentToKotlin(member.ident.toString())
    val baseCore = unwrapTransparentCastExpr(core.base)
    val ownerType =
        if (baseCore is Expr.Path && baseCore.qself == null && baseCore.path.segments.len() == 1) {
            val firstSeg = baseCore.path.segments.first()
            val firstIdent = firstSeg?.ident?.toString()
            val baseRaw = firstIdent?.removePrefix("r#")
            if (baseRaw == "self") {
                selfTypeName?.substringBefore('<')?.trim()
            } else {
                baseRaw?.let(::rustTypeIdentToKotlin)?.takeIf { it in inspector.declaredTypeNames }
            }
        } else {
            null
        }
    return ownerType to fieldName
}

internal fun KotlinJ2KEmitter.isDequeIterFieldExpr(expr: Expr, selfTypeName: String?): Boolean {
    val (ownerType, fieldName) = extractOwnerAndFieldName(expr, selfTypeName) ?: return false
    return ownerType != null && fieldName in inspector.dequeIterFieldsByType[ownerType].orEmpty()
}

internal fun KotlinJ2KEmitter.isMutableVecExpr(expr: Expr, renderedReceiver: String, selfTypeName: String?): Boolean {
    if (renderedReceiver in currentMutableListLocals) return true
    val (ownerType, fieldName) = extractOwnerAndFieldName(expr, selfTypeName) ?: return false
    if (ownerType != null && fieldName in inspector.vecFieldsByType[ownerType].orEmpty()) return true
    if (ownerType == null && inspector.vecFieldsByType.values.any { fieldName in it } && fieldName == "inner") {
        val baseCore = unwrapTransparentCastExpr((unwrapTransparentCastExpr(expr) as Expr.Field).base)
        if (baseCore is Expr.Path && baseCore.qself == null && baseCore.path.segments.len() == 1) {
            val firstSeg = baseCore.path.segments.first()
            val firstIdent = firstSeg?.ident?.toString()
            val rawVar = firstIdent?.removePrefix("r#").orEmpty()
            if (rustTypeIdentToKotlin(rawVar) in inspector.vecFieldsByType) return true
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.isKnownNullableOptionExpr(expr: Expr, renderedReceiver: String, selfTypeName: String?): Boolean {
    if (hasDeclaredOptionEnum()) return false
    val core = unwrapTransparentCastExpr(expr)
    if (core is Expr.MethodCall) {
        val m = core.method.toString()
        if (m in setOf("ok_or", "ok_or_else", "is_ok", "is_err", "is_some", "is_none", "map_err", "to_lowercase", "to_uppercase")) {
            return false
        }
        if (m == "ok" &&
            core.args.isEmpty() &&
            "ok" !in inspector.declaredTopLevelFnRawNames &&
            inspector.implMethodReturnSynTypes.keys.none { it.second == "ok" }
        ) {
            return true
        }
        if (m in KNOWN_OPTION_RETURNING_METHODS) return true
        if ((m == "fold" || m == "rfold") && core.args.toList().firstOrNull()?.let { firstArg ->
                val unwrapped = unwrapParenOrGroupExpr(firstArg)
                unwrapped is Expr.Path && unwrapped.qself == null && unwrapped.path.segments
                    .last()
                    ?.ident
                    ?.toString() == "None"
            } == true
        ) {
            return true
        }
        if ((m == "map" || m == "and_then" || m == "flatten" || m == "or" || m == "or_else" || m == "as_ref" || m == "as_mut" || m == "cloned" || m == "copied" || m == "filter") &&
            isKnownNullableOptionExpr(core.receiver, "", selfTypeName)
        ) {
            return true
        }
    }
    if (core is Expr.Call) {
        val fnPath = (unwrapParenOrGroupExpr(core.func) as? Expr.Path)?.takeIf { it.qself == null }
        val segs =
            fnPath
                ?.path
                ?.segments
                ?.toList()
                .orEmpty()
        val lastRaw =
            segs
                .lastOrNull()
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
        if (lastRaw != null) {
            if (lastRaw in setOf("memchr", "memrchr")) {
                return true
            }
            val ktFn = rustFunctionIdentToKotlin(lastRaw)
            val localRet =
                inspector.topLevelFnReturnSynTypes[lastRaw]
                    ?: inspector.topLevelFnReturnSynTypes[ktFn]
            if (localRet != null) {
                val uRet = unwrapSynType(localRet) as? SynType.Path
                if (uRet
                        ?.path
                        ?.segments
                        ?.last()
                        ?.ident
                        ?.toString() == "Option"
                ) {
                    return true
                }
            } else if (headerContext.externalCrateNullableReturnFns.isNotEmpty()) {
                val importedPkg = importedSimpleNameToPkg[ktFn]
                if (importedPkg != null && "$importedPkg.$ktFn" in headerContext.externalCrateNullableReturnFns) {
                    return true
                }
                if (segs.size == 1 &&
                    headerContext.externalCrateSymbolPackages.values.any { symMap ->
                        symMap[ktFn].orEmpty().any { pkg -> "$pkg.$ktFn" in headerContext.externalCrateNullableReturnFns }
                    }
                ) {
                    return true
                }
            }
        }
    }
    val inferred = inferExprRenderedType(expr, selfTypeName)?.trim()
    if (inferred != null) {
        if (rustResultTypeArguments(inferred) != null || inferred.startsWith("Result<") || inferred.startsWith("RustResult<")) {
            return false
        }
        if (inferred.endsWith("?") && rawPointerPointeeType(inferred) == null) {
            return true
        }
    }
    if (renderedReceiver in currentNullableLocals || renderedReceiver.contains("?.let(")) return true
    val (ownerType, fieldName) = extractOwnerAndFieldName(expr, selfTypeName) ?: (null to "")
    if (ownerType != null && fieldName in inspector.nullableFieldsByType[ownerType].orEmpty()) return true
    return false
}

internal fun KotlinJ2KEmitter.unwrapZeroArgClosureText(renderedClosure: String): String {
    val trimmed = renderedClosure.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}") && !trimmed.contains("->")) {
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if ('\n' !in inner && ';' !in inner) {
            return inner
        }
        return "run $trimmed"
    }
    if ("::" in trimmed) {
        return "${trimmed.replace("::", ".")}()"
    }
    return "$trimmed()"
}

internal fun KotlinJ2KEmitter.renderExprStruct(expr: Expr.Struct, indent: String, selfTypeName: String?): String {
    val pathSegs = expr.path.segments
    val allSegStrings = pathSegs.toList().map { it.ident.toString() }
    val resolvedCrossTarget = resolveCrossModulePathTarget(allSegStrings, inTypePosition = true)
    val ctorPath =
        if (resolvedCrossTarget != null) {
            val (resolvedCrossPkg, trailing) = resolvedCrossTarget
            val boundHead = bindOrQualifyResolvedCrossSymbol(resolvedCrossPkg)
            if (trailing.isEmpty()) {
                boundHead
            } else {
                (listOf(boundHead) + trailing.map(::rustTypeIdentToKotlin)).joinToString(".")
            }
        } else {
            val rawSegments =
                expr.path.segments
                    .toList()
                    .filter { it.ident.toString() !in IGNORED_PATH_PREFIXES }
            val typeSegments =
                if (rawSegments.isEmpty()) {
                    emptyList()
                } else {
                    rawSegments.dropLast(1).dropWhile {
                        val s = it.ident.toString()
                        s.isNotEmpty() &&
                            s.first().isLowerCase() &&
                            s != "self" &&
                            !scopeBuilder.isImportedModuleInScope(s) &&
                            resolveImportedUppercaseTypeBinding(s) == null
                    } + rawSegments.last()
                }
            if (typeSegments.isEmpty()) {
                selfTypeName?.substringBefore('<')?.trim() ?: "Any"
            } else {
                val mappedSegs =
                    typeSegments.mapIndexed { idx, seg ->
                        val raw = seg.ident.toString()
                        val isLastSeg = (idx == typeSegments.lastIndex)
                        val importedTypeName = resolveImportedUppercaseTypeBinding(raw)
                        when {
                            raw == "Self" -> selfTypeName?.substringBefore('<')?.trim() ?: "Self"
                            importedTypeName != null -> importedTypeName
                            isLastSeg || raw.first().isUpperCase() -> rustTypeIdentToKotlin(raw)
                            scopeBuilder.isImportedModuleInScope(raw) -> scopeBuilder.resolveModuleBinding(raw)?.kotlinName ?: rustModuleIdentToKotlin(raw)
                            else -> rustValueIdentToKotlin(raw)
                        }
                    }
                mappedSegs.joinToString(".")
            }
        }
    val ctorBase = ctorPath.substringAfterLast('.').substringBefore('<').trim()
    if (!ctorPath.contains('.') &&
        ctorBase !in inspector.declaredTypeNames &&
        headerContext.crateSymbolSubpackages.isNotEmpty()
    ) {
        val bestSubpkg = resolveBestSubpackageForSymbol(ctorBase)
        val nsPrefix = currentNamespacePrefix()
        if (bestSubpkg != null && nsPrefix != null) {
            val targetPkg = if (bestSubpkg.isEmpty()) nsPrefix else "$nsPrefix.$bestSubpkg"
            if (targetPkg.isNotEmpty() && targetPkg != headerContext.packageName) {
                val existingPkg = importedSimpleNameToPkg[ctorBase]
                if (existingPkg == null || existingPkg == targetPkg) {
                    importedSimpleNameToPkg[ctorBase] = targetPkg
                    extraTypePathImports += "import $targetPkg.$ctorBase"
                }
            }
        }
    }
    val seenFieldNames = hashSetOf<String>()
    val fieldArgs =
        expr.fields.toList().mapNotNull { fv: FieldValue ->
            val fieldName =
                when (val m = fv.member) {
                    is Member.Named -> rustFieldIdentToKotlin(m.ident.toString())
                    is Member.Unnamed -> tupleFieldPropertyName(m.index.index.toInt(), expr.fields.len())
                }
            if (!seenFieldNames.add(fieldName)) return@mapNotNull null
            val fieldSynTy = inspector.structFieldSynTypesByType[ctorBase]?.get(fieldName)
            val renderedFieldTy =
                fieldSynTy?.let { renderType(it, selfTypeName) }
                    ?: scopedAbiKeysForRenderedType(ctorPath).firstNotNullOfOrNull { headerContext.crateStructFieldTypes["$it.$fieldName"] }
                    ?: headerContext.crateStructFieldTypes["$ctorBase.$fieldName"]
            val prevFieldParseTarget = currentExpectedParseTargetType
            currentExpectedParseTargetType = renderedFieldTy
            var valText =
                try {
                    if (renderedFieldTy != null && renderedFieldTy in PRIMITIVE_ARRAY_TYPES) {
                        renderStructPrimitiveArrayFieldInit(fv.expr, renderedFieldTy, indent, selfTypeName)
                    } else if (renderedFieldTy != null) {
                        renderTypedNumericInitializer(fv.expr, resolveNumericKind(renderedFieldTy), indent, selfTypeName)
                    } else {
                        renderExpr(fv.expr, indent, selfTypeName)
                    }
                } finally {
                    currentExpectedParseTargetType = prevFieldParseTarget
                }
            if (valText == "ArrayDeque()" && fieldSynTy != null && renderedFieldTy != null) {
                val traitBase = renderedFieldTy.substringBefore('<').trim()
                val traitDecl = lookupDeclaredTraitAst(traitBase)
                val iterSpec = traitDecl?.let { extractIteratorSupertraitSpec(it.supertraits.toList(), null) }
                if (traitDecl != null && iterSpec != null) {
                    val elemTy =
                        if ('<' in renderedFieldTy && renderedFieldTy.endsWith(">")) {
                            renderedFieldTy.substringAfter('<').removeSuffix(">").trim()
                        } else {
                            iterSpec.itemType
                        }
                    val methodStubs = mutableListOf<String>()
                    methodStubs += "override fun next(): $elemTy? = null"
                    if (iterSpec.hasDoubleEnded) {
                        methodStubs += "override fun nextBack(): $elemTy? = null"
                    }
                    if (iterSpec.hasExactSize) {
                        methodStubs += "override fun len(): Int = 0"
                    }
                    for (tItem in traitDecl.items) {
                        if (traitItemHasDocOnlyCfg(tItem)) continue
                        if (tItem is TraitItem.Fn && tItem.default == null) {
                            val mName = rustFunctionIdentToKotlin(tItem.sig.ident.toString())
                            if (mName == "cloneBox") {
                                methodStubs += "override fun cloneBox(): $renderedFieldTy = this"
                            }
                        }
                    }
                    valText = "object : $renderedFieldTy { ${methodStubs.joinToString("; ")} }"
                }
            }
            "$fieldName = $valText"
        }
    val declaration = transparentDeclaration(ctorBase)
    val layoutArgs =
        if (declaration != null && sourceTransparentField(declaration, ctorPath) != null) {
            listOf("fieldLayout = ${transparentConstructionLayout(declaration, selfTypeName)}")
        } else {
            emptyList()
        }
    val constructorArgs = fieldArgs + layoutArgs
    val restExpr = expr.rest
    return if (restExpr != null) {
        val baseText = renderExpr(restExpr, indent, selfTypeName)
        "$baseText.copy(${fieldArgs.joinToString(", ")})"
    } else {
        "$ctorPath(${constructorArgs.joinToString(", ")})"
    }
}

internal fun KotlinJ2KEmitter.renderStructPrimitiveArrayFieldInit(
    initExpr: Expr,
    arrayType: String,
    indent: String,
    selfTypeName: String?,
): String {
    val elemKind = primitiveArrayElementNumericKind(arrayType)
    return when (initExpr) {
        is Expr.Array -> {
            val fnName =
                when (arrayType) {
                    "ByteArray" -> "byteArrayOf"
                    "UByteArray" -> "ubyteArrayOf"
                    "ShortArray" -> "shortArrayOf"
                    "UShortArray" -> "ushortArrayOf"
                    "IntArray" -> "intArrayOf"
                    "UIntArray" -> "uintArrayOf"
                    "LongArray" -> "longArrayOf"
                    "ULongArray" -> "ulongArrayOf"
                    "FloatArray" -> "floatArrayOf"
                    "DoubleArray" -> "doubleArrayOf"
                    "BooleanArray" -> "booleanArrayOf"
                    else -> "listOf"
                }
            val elems =
                initExpr.elems.toList().joinToString(", ") { elem ->
                    renderTypedNumericInitializer(elem, elemKind, indent, selfTypeName)
                }
            "$fnName($elems)"
        }
        is Expr.Repeat -> {
            val lenText = normalizeArrayLengthToIntExpr(renderTypedNumericInitializer(initExpr.len, NumericKind.I32, indent, selfTypeName), initExpr.len, selfTypeName)
            val rawElem = renderTypedNumericInitializer(initExpr.expr, elemKind, indent, selfTypeName)
            val alreadyElemKind =
                elemKind != null &&
                    (isAlreadyRenderedAsNumericKind(rawElem, elemKind) || inferExprNumericKind(initExpr.expr, selfTypeName) == elemKind)
            val elemText =
                if (alreadyElemKind) {
                    rawElem
                } else {
                    when (arrayType) {
                        "ByteArray" -> if (rawElem.endsWith(".toByte()")) rawElem else "$rawElem.toByte()"
                        "UByteArray" -> if (rawElem.endsWith(".toUByte()")) rawElem else "$rawElem.toUByte()"
                        "ShortArray" -> if (rawElem.endsWith(".toShort()")) rawElem else "$rawElem.toShort()"
                        "UShortArray" -> if (rawElem.endsWith(".toUShort()")) rawElem else "$rawElem.toUShort()"
                        else -> rawElem
                    }
                }
            "$arrayType($lenText) { $elemText }"
        }
        else -> renderExpr(initExpr, indent, selfTypeName)
    }
}

internal fun KotlinJ2KEmitter.primitiveArrayElementNumericKind(arrayType: String): NumericKind? {
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    return when (arrayType) {
        "ByteArray", "List<Byte>", "MutableList<Byte>" -> NumericKind.I8
        "UByteArray", "List<UByte>", "MutableList<UByte>" -> NumericKind.U8
        "ShortArray", "List<Short>", "MutableList<Short>" -> NumericKind.I16
        "UShortArray", "List<UShort>", "MutableList<UShort>" -> NumericKind.U16
        "IntArray", "List<Int>", "MutableList<Int>" -> NumericKind.I32
        "UIntArray", "List<UInt>", "MutableList<UInt>" -> NumericKind.U32
        "LongArray", "List<Long>", "MutableList<Long>" -> NumericKind.I64
        "ULongArray", "List<ULong>", "MutableList<ULong>" -> NumericKind.U64
        "List<I128>", "MutableList<I128>", "List<$coreNs.I128>", "MutableList<$coreNs.I128>" -> NumericKind.I128
        "List<U128>", "MutableList<U128>", "List<$coreNs.U128>", "MutableList<$coreNs.U128>" -> NumericKind.U128
        "FloatArray", "List<Float>", "MutableList<Float>", "List<kotlin.Float>", "MutableList<kotlin.Float>" -> NumericKind.F32
        "DoubleArray", "List<Double>", "MutableList<Double>" -> NumericKind.F64
        else -> null
    }
}

internal fun KotlinJ2KEmitter.inferArrayElementNumericKind(expr: Expr, selfTypeName: String?): NumericKind? {
    var containerTy = inferExprRenderedType(expr, selfTypeName)?.removeSuffix("?")?.trim() ?: return null
    primitiveArrayElementNumericKind(containerTy)?.let { return it }
    extractIndexedElementRenderedType(containerTy, selfTypeName)?.let(this::resolveNumericKind)?.let { return it }
    var depth = 0
    while (depth < 6) {
        val aliasSyn = inspector.typeAliasTargets[containerTy] ?: break
        val rendered = renderType(aliasSyn, selfTypeName = null).removeSuffix("?").trim()
        if (rendered == containerTy) break
        primitiveArrayElementNumericKind(rendered)?.let { return it }
        extractIndexedElementRenderedType(rendered, selfTypeName)?.let(this::resolveNumericKind)?.let { return it }
        containerTy = rendered
        depth++
    }
    return null
}

internal fun KotlinJ2KEmitter.extractIndexedElementRenderedType(containerTy: String, selfTypeName: String?): String? {
    var cur = containerTy.removeSuffix("?").trim()
    var depth = 0
    while (depth <= 6) {
        when (cur) {
            "ByteArray" -> return "Byte"
            "UByteArray" -> return "UByte"
            "ShortArray" -> return "Short"
            "UShortArray" -> return "UShort"
            "IntArray" -> return "Int"
            "UIntArray" -> return "UInt"
            "LongArray" -> return "Long"
            "ULongArray" -> return "ULong"
            "FloatArray" -> return kotlinF32TypeName()
            "DoubleArray" -> return "Double"
            "BooleanArray" -> return "Boolean"
        }
        when {
            cur.startsWith("List<") && cur.endsWith(">") -> return cur.removePrefix("List<").removeSuffix(">").trim()
            cur.startsWith("MutableList<") && cur.endsWith(">") -> return cur.removePrefix("MutableList<").removeSuffix(">").trim()
            cur.startsWith("Array<") && cur.endsWith(">") -> return cur.removePrefix("Array<").removeSuffix(">").trim()
        }
        val aliasSyn = inspector.typeAliasTargets[cur] ?: break
        val next = renderType(aliasSyn, selfTypeName).removeSuffix("?").trim()
        if (next == cur) break
        cur = next
        depth++
    }
    return null
}

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

internal fun KotlinJ2KEmitter.isKnownNonNullableNonOptionReceiver(
    recvExpr: Expr,
    renderedReceiver: String,
    selfTypeName: String?,
): Boolean {
    if (renderedReceiver in currentNullableLocals) return false
    val rawTy =
        (currentTypedLocals[renderedReceiver] ?: inferExprRenderedType(recvExpr, selfTypeName))
            ?.trim()
            ?: return false
    if (rawTy.isEmpty() ||
        rawTy.endsWith("?") ||
        rawTy == "Any" ||
        rawTy == "Option" ||
        rawTy.startsWith("Option<")
    ) {
        return false
    }
    var cur = rawTy
    var depth = 0
    while (depth < 8) {
        val base = cur.substringBefore('<').substringAfterLast('.').trim()
        if (base.isEmpty() || base in activeTypeParamNames) return false
        val aliasSyn = inspector.typeAliasTargets[base]
        if (aliasSyn != null) {
            val next = renderType(aliasSyn, selfTypeName).trim()
            if (next.endsWith("?") || next == "Any" || next == "Option" || next.startsWith("Option<")) {
                return false
            }
            if (next == cur) break
            cur = next
            depth++
            continue
        }
        val crateAlias = headerContext.crateTypeAliasDefaults[base]?.trim()
        if (crateAlias != null) {
            if (crateAlias.endsWith("?") || crateAlias == "Any" || crateAlias == "Option" || crateAlias.startsWith("Option<")) {
                return false
            }
            if (crateAlias == cur) break
            cur = crateAlias
            depth++
            continue
        }
        break
    }
    return true
}

internal data class InferExprCacheKey(
    val expr: Expr,
    val selfTypeName: String?,
    val expectedParseTarget: String?,
    val enclosingReturn: String?,
    val inExternAbi: Boolean,
    val typedLocals: Map<String, String>,
    val sourceParamTypes: Map<String, SynType>,
)

internal fun KotlinJ2KEmitter.inferExprRenderedType(expr: Expr, selfTypeName: String?): String? {
    val shouldMemoize =
        expr is Expr.MethodCall ||
            expr is Expr.Call ||
            expr is Expr.Binary ||
            expr is Expr.BlockExpr ||
            expr is Expr.If ||
            expr is Expr.Unsafe
    if (!shouldMemoize) {
        return inferExprRenderedTypeImpl(expr, selfTypeName)
    }
    val cacheKey =
        InferExprCacheKey(
            expr = expr,
            selfTypeName = selfTypeName,
            expectedParseTarget = currentExpectedParseTargetType,
            enclosingReturn = currentEnclosingReturnRenderedType,
            inExternAbi = inExternAbiSignature,
            typedLocals = currentTypedLocals,
            sourceParamTypes = currentSourceParameterTypes,
        )
    if (inferExprRenderedTypeCache.containsKey(cacheKey)) {
        return inferExprRenderedTypeCache[cacheKey]
    }
    val computed = inferExprRenderedTypeImpl(expr, selfTypeName)
    inferExprRenderedTypeCache[cacheKey] = computed
    return computed
}

private fun KotlinJ2KEmitter.inferExprRenderedTypeImpl(expr: Expr, selfTypeName: String?): String? =
    when (expr) {
        is Expr.Paren -> inferExprRenderedType(expr.expr, selfTypeName)
        is Expr.Group -> inferExprRenderedType(expr.expr, selfTypeName)
        is Expr.Reference -> {
            val innerSynType = sourceInitializerType(expr.expr)
            val innerRendered = inferExprRenderedType(expr.expr, selfTypeName) ?: innerSynType?.let { renderType(it, selfTypeName) }
            innerRendered?.let { rendered ->
                val place = unwrapParenOrGroupExpr(expr.expr)
                val local =
                    (place as? Expr.Path)
                        ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                        ?.path
                        ?.segments
                        ?.first()
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                        ?.let { scopeBuilder.resolveLocalKotlinName(it) ?: rustValueIdentToKotlin(it) }
                val isAddrMut =
                    expr.mutability != null &&
                        !headerContext.rawAbiMode &&
                        (
                            (local != null && local in currentAddressableLocals) ||
                                (local == null && innerSynType != null && shouldUseAddressBackedMutableReference(innerSynType, selfTypeName))
                        )
                if (isAddrMut) {
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.RustMutableReference")
                    "RustMutableReference<$rendered>"
                } else {
                    rendered
                }
            }
        }
        is Expr.RawAddr ->
            if (headerContext.rawAbiMode) {
                "Long"
            } else {
                inferExprRenderedType(expr.expr, selfTypeName)?.let { pointee ->
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    val ptrType = if (expr.mutability is PointerMutability.Mut) "MutPtr" else "ConstPtr"
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.$ptrType")
                    "$ptrType<$pointee>"
                }
            }
        is Expr.Await -> inferExprRenderedType(expr.base, selfTypeName)
        is Expr.Try -> {
            val inner = unwrapParenOrGroupExpr(expr.expr)
            val innerTurbofish = (inner as? Expr.MethodCall)?.turbofish
            if (inner is Expr.MethodCall &&
                inner.method.toString().removePrefix("r#") == "parse" &&
                inner.args.isEmpty() &&
                innerTurbofish != null &&
                innerTurbofish.args.size == 1
            ) {
                val tfGa = innerTurbofish.args.first() as? GenericArgument.TypeArg
                if (tfGa != null) {
                    renderType(tfGa.type, selfTypeName)
                } else {
                    null
                }
            } else {
                val innerTy = inferExprRenderedType(expr.expr, selfTypeName)?.trim()
                when {
                    innerTy == null -> null
                    rustResultOkPayloadType(innerTy) != null -> rustResultOkPayloadType(innerTy)
                    innerTy.endsWith("?") -> innerTy.removeSuffix("?").trim()
                    else -> null
                }
            }
        }
        is Expr.Cast -> renderType(expr.ty, selfTypeName)
        is Expr.Struct -> {
            val lastSeg =
                expr.path.segments
                    .toList()
                    .lastOrNull()
                    ?.ident
                    ?.toString()
            when (lastSeg) {
                null -> null
                "Self" -> selfTypeName?.substringBefore('<')?.trim()
                else -> rustTypeIdentToKotlin(lastSeg)
            }
        }
        is Expr.Path -> {
            val constPrimKind = resolvePrimitiveAssociatedConstKind(expr.path, expr.qself)
            val segs = expr.path.segments.toList()
            val hasOnlyIgnoredPrefixes =
                expr.qself == null &&
                    segs.isNotEmpty() &&
                    segs.dropLast(1).all {
                        it.arguments is PathArguments.None &&
                            it.ident.toString().removePrefix("r#") in IGNORED_PATH_PREFIXES
                    }
            when {
                constPrimKind != null -> numericKindToPureTypeName(constPrimKind)
                hasOnlyIgnoredPrefixes -> {
                    val raw =
                        segs
                            .last()
                            .ident
                            .toString()
                            .removePrefix("r#")
                    when {
                        raw == "self" && segs.size == 1 -> selfTypeName
                        else -> {
                            val ktConst = inspector.declaredConstNames[raw]
                            val constTy = ktConst?.let { inspector.declaredConstTypes[it] }
                            if (constTy != null) {
                                renderType(constTy, selfTypeName)
                            } else if (segs.size == 1) {
                                val ktLocal = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
                                currentTypedLocals[ktLocal]
                            } else {
                                null
                            }
                        }
                    }
                }
                else -> null
            }
        }
        is Expr.Unary ->
            when (expr.op) {
                is UnOp.Deref ->
                    rawPointerPointeeType(inferExprRenderedType(expr.expr, selfTypeName))
                        ?: mutableReferencePointeeType(inferExprRenderedType(expr.expr, selfTypeName))
                        ?: mutablePlaceValueType(inferExprRenderedType(expr.expr, selfTypeName))
                        ?: refCellGuardPointeeType(inferExprRenderedType(expr.expr, selfTypeName))
                        ?: borrowedPlaceLocalName(expr.expr)?.let { currentTypedLocals[it] }
                        ?: inferExprRenderedType(expr.expr, selfTypeName)?.removeSuffix("?")
                is UnOp.Neg -> inferExprRenderedType(expr.expr, selfTypeName)
                is UnOp.NotOp -> if (isKnownIntegerBitmaskExpr(expr.expr)) inferExprRenderedType(expr.expr, selfTypeName) else "Boolean"
            }
        is Expr.Field -> {
            val baseReferenceTy = inferExprRenderedType(expr.base, selfTypeName) ?: return null
            val baseTy =
                (
                    mutableReferencePointeeType(baseReferenceTy)
                        ?: refCellGuardPointeeType(baseReferenceTy)
                        ?: baseReferenceTy
                ).removeSuffix("?").trim()
            val rawOwnerKt = baseTy.substringAfterLast('.').substringBefore('<').trim()
            if (rawOwnerKt.isEmpty()) return null
            val aliasedOwnerKt =
                inspector.typeAliasTargets[rawOwnerKt]?.let {
                    renderType(it, null).substringAfterLast('.').substringBefore('<').trim()
                }
            val ownerKt = aliasedOwnerKt?.takeIf { it.isNotEmpty() && it in inspector.structFieldSynTypesByType } ?: rawOwnerKt
            when (val member = expr.member) {
                is Member.Named -> {
                    val fieldKt = rustFieldIdentToKotlin(member.ident.toString())
                    inspector.structFieldSynTypesByType[ownerKt]?.get(fieldKt)?.let { return renderType(it, ownerKt) }
                    for (key in scopedAbiKeysForRenderedType(baseTy)) {
                        headerContext.crateStructFieldTypes["$key.$fieldKt"]?.let { return it }
                    }
                    if (aliasedOwnerKt != null && aliasedOwnerKt != rawOwnerKt) {
                        for (key in scopedAbiKeysForRenderedType(aliasedOwnerKt)) {
                            headerContext.crateStructFieldTypes["$key.$fieldKt"]?.let { return it }
                        }
                    }
                    headerContext.crateStructFieldTypes["$ownerKt.$fieldKt"]
                }
                is Member.Unnamed -> {
                    val idx = member.index.index.toInt()
                    val arity = inspector.tupleStructArityByType[ownerKt]
                    val primaryField =
                        if (arity != null) {
                            tupleFieldPropertyName(idx, arity)
                        } else {
                            tupleAccessMemberName(expr.base, idx, selfTypeName)
                        }
                    inspector.structFieldSynTypesByType[ownerKt]?.get(primaryField)?.let { return renderType(it, ownerKt) }
                    val candidateFields =
                        if (idx == 0 && primaryField != "value") {
                            listOf(primaryField, "value")
                        } else {
                            listOf(primaryField)
                        }
                    candidateFields.firstNotNullOfOrNull { fName ->
                        scopedAbiKeysForRenderedType(baseTy).firstNotNullOfOrNull { key ->
                            headerContext.crateStructFieldTypes["$key.$fName"]
                        } ?: headerContext.crateStructFieldTypes["$ownerKt.$fName"]
                    }
                }
            }
        }
        is Expr.Index -> {
            if (expr.index is Expr.Range) {
                inferExprRenderedType(expr.expr, selfTypeName)
            } else {
                val baseTy = inferExprRenderedType(expr.expr, selfTypeName) ?: return null
                extractIndexedElementRenderedType(baseTy, selfTypeName)
            }
        }
        is Expr.Call -> {
            resolvePrimitiveFromBytesSpec(expr)?.let { return it.second }
            if ("core" in headerContext.crateNamespaces) {
                val fnPathCall = (expr.func as? Expr.Path)?.takeIf { it.qself == null }
                val rawCalleeSegs =
                    fnPathCall
                        ?.path
                        ?.segments
                        ?.toList()
                        ?.map { it.ident.toString().removePrefix("r#") }
                        .orEmpty()
                val isFromRawPartsCall =
                    expr.args.len() == 2 &&
                        (
                            rawCalleeSegs == listOf("from_raw_parts") ||
                                rawCalleeSegs == listOf("from_raw_parts_mut") ||
                                rawCalleeSegs.takeLast(2) == listOf("slice", "from_raw_parts") ||
                                rawCalleeSegs.takeLast(2) == listOf("slice", "from_raw_parts_mut")
                        )
                if (isFromRawPartsCall) {
                    val ptrTy = inferExprRenderedType(expr.args.toList()[0], selfTypeName)
                    val elem = rawPointerPointeeType(ptrTy)
                    if (elem != null) {
                        val isMut = rawCalleeSegs.last().endsWith("_mut")
                        return if (isMut) "MutableList<$elem>" else "List<$elem>"
                    }
                }
            }
            parseStaticPrimitiveCallSpec(expr)?.let { spec ->
                when (spec.methodName) {
                    "from_bits" -> return numericKindToPureTypeName(spec.primKind)
                    "from_str_radix" ->
                        if ("core" in headerContext.crateNamespaces) {
                            val elem = numericKindToPureTypeName(spec.primKind)
                            return if (usesTaggedCoreResult()) {
                                "${sourceResultName()}<$elem, ParseIntError>"
                            } else {
                                "Result<$elem>"
                            }
                        }
                    else ->
                        inferPrimitiveOpReturnRenderedType(
                            recvKind = spec.primKind,
                            rawMethod = spec.methodName,
                            turbofishType = spec.turbofishType,
                            selfTypeName = selfTypeName,
                        )?.let { return it }
                }
            }
            val fnPath = (expr.func as? Expr.Path)?.takeIf { it.qself == null } ?: return null
            val lastCallIdent =
                fnPath.path.segments
                    .last()
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
            if (lastCallIdent in setOf("size_of", "size_of_val", "align_of", "align_of_val")) {
                return when {
                    currentExpectedNumericKind != null -> null
                    headerContext.rawAbiMode -> "ULong"
                    else -> "Int"
                }
            }
            if (lastCallIdent == "strlen" && (currentExpectedNumericKind == NumericKind.I32 || currentEnclosingReturnRenderedType == "Int")) {
                return "Int"
            }
            if (lastCallIdent == "type_name") {
                return "String"
            }
            if (lastCallIdent in setOf("memchr", "memrchr") &&
                lastCallIdent !in inspector.declaredTopLevelFnRawNames &&
                "memchr" !in inspector.declaredTypeNames
            ) {
                return "Int?"
            }
            fun renderTopLevelFnRet(ktFn: String, rawFn: String? = null): String? {
                val synRet =
                    (rawFn?.let { inspector.topLevelFnReturnSynTypes[it] })
                        ?: inspector.topLevelFnReturnSynTypes[ktFn]
                        ?: return null
                val prevExtern = inExternAbiSignature
                if (ktFn in inspector.externFnKotlinNames) {
                    inExternAbiSignature = true
                }
                return try {
                    renderType(synRet, selfTypeName)
                } finally {
                    inExternAbiSignature = prevExtern
                }
            }
            val segs =
                fnPath.path.segments
                    .toList()
                    .filter { it.ident.toString() !in IGNORED_PATH_PREFIXES }
            when {
                segs.size == 1 -> {
                    val raw = segs[0].ident.toString().removePrefix("r#")
                    if (raw == "Self") {
                        selfTypeName?.substringBefore('<')?.trim()
                    } else if (raw.isNotEmpty() && raw.first().isUpperCase() && raw !in inspector.declaredTopLevelFnRawNames) {
                        val ktType = rustTypeIdentToKotlin(raw)
                        if (ktType in inspector.declaredTypeNames ||
                            scopedAbiKeysForRenderedType(ktType).any { it in headerContext.crateSymbolSubpackages || "$it.value" in headerContext.crateStructFieldTypes }
                        ) {
                            ktType
                        } else {
                            val ktFn = rustFunctionIdentToKotlin(raw)
                            renderTopLevelFnRet(ktFn, raw)
                                ?: scopedAbiKeysForRenderedType(ktFn).firstNotNullOfOrNull { headerContext.crateFnReturnTypes[it] }
                                ?: headerContext.crateFnReturnTypes[ktFn]
                        }
                    } else {
                        val ktFn = rustFunctionIdentToKotlin(raw)
                        renderTopLevelFnRet(ktFn, raw)
                            ?: scopedAbiKeysForRenderedType(ktFn).firstNotNullOfOrNull { headerContext.crateFnReturnTypes[it] }
                            ?: headerContext.crateFnReturnTypes[ktFn]
                    }
                }
                segs.size >= 2 -> {
                    val penultSeg = segs[segs.size - 2]
                    val lastSeg = segs[segs.size - 1]
                    val penultRaw = penultSeg.ident.toString().removePrefix("r#")
                    val lastRaw = lastSeg.ident.toString().removePrefix("r#")
                    val penultTypeArgs =
                        (penultSeg.arguments as? PathArguments.AngleBracketed)
                            ?.args
                            ?.toList()
                            .orEmpty()
                            .filterIsInstance<GenericArgument.TypeArg>()
                            .map { renderType(it.type, selfTypeName) }
                    when {
                        penultRaw == "String" &&
                            lastRaw in setOf("new", "with_capacity", "from") &&
                            "String" !in inspector.declaredTypeNames -> return "String"
                        penultRaw == "Vec" &&
                            lastRaw in setOf("new", "with_capacity", "from") &&
                            "Vec" !in inspector.declaredTypeNames ->
                            return if (penultTypeArgs.size == 1) "MutableList<${penultTypeArgs[0]}>" else "MutableList<*>"
                        (penultRaw == "HashMap" || penultRaw == "BTreeMap") &&
                            lastRaw in setOf("new", "with_capacity", "from") &&
                            penultRaw !in inspector.declaredTypeNames ->
                            return if (penultTypeArgs.size >= 2) {
                                "MutableMap<${penultTypeArgs[0]}, ${penultTypeArgs[1]}>"
                            } else {
                                "MutableMap<*, *>"
                            }
                        (penultRaw == "HashSet" || penultRaw == "BTreeSet") &&
                            lastRaw in setOf("new", "with_capacity", "from") &&
                            penultRaw !in inspector.declaredTypeNames ->
                            return if (penultTypeArgs.isNotEmpty()) "MutableSet<${penultTypeArgs[0]}>" else "MutableSet<*>"
                        headerContext.scalarChar &&
                            (penultRaw == "char" || penultRaw == "RustChar") &&
                            "Char" !in inspector.declaredTypeNames ->
                            when (lastRaw) {
                                "from_u32" -> return "RustChar?"
                                "from_u32_unchecked", "from" -> return "RustChar"
                                "try_from" ->
                                    return if (usesTaggedCoreResult()) {
                                        "${sourceResultName()}<RustChar, CharTryFromError>"
                                    } else {
                                        "Result<RustChar>"
                                    }
                            }
                    }
                    val methodKt = rustFunctionIdentToKotlin(lastRaw)
                    val ownerKt =
                        when {
                            penultRaw == "Self" -> selfTypeName?.substringBefore('<')?.trim()
                            penultRaw.isNotEmpty() && penultRaw.first().isUpperCase() -> rustTypeIdentToKotlin(penultRaw)
                            else -> null
                        }
                    if (ownerKt != null) {
                        val canonicalOwner = resolveTypeAliasOwnerName(ownerKt)
                        inspector.implMethodReturnSynTypes[canonicalOwner to methodKt]?.let { return renderType(it, canonicalOwner) }
                        inspector.implMethodReturnSynTypes[ownerKt to methodKt]?.let { return renderType(it, ownerKt) }
                        resolveMethodSignatureForOwner(ownerKt, methodKt)?.let { (resolvedOwner, sig) ->
                            when (val out = sig.output) {
                                is ReturnType.TypeReturn -> return renderType(out.ty, resolvedOwner)
                                is ReturnType.Default -> return "Unit"
                            }
                        }
                        val modPrefix =
                            segs
                                .dropLast(2)
                                .joinToString(".") { rustModuleIdentToKotlin(it.ident.toString().replace('-', '_')) }
                        if (modPrefix.isNotEmpty()) {
                            headerContext.crateFnReturnTypes["$modPrefix.$canonicalOwner.$methodKt"]?.let { return it }
                            headerContext.crateFnReturnTypes["$modPrefix.$ownerKt.$methodKt"]?.let { return it }
                        }
                        for (ownerKey in (scopedAbiKeysForRenderedType(canonicalOwner) + scopedAbiKeysForRenderedType(ownerKt)).distinct()) {
                            headerContext.crateFnReturnTypes["$ownerKey.$methodKt"]?.let { return it }
                        }
                        headerContext.crateFnReturnTypes["$canonicalOwner.$methodKt"]
                            ?: headerContext.crateFnReturnTypes["$ownerKt.$methodKt"]
                    } else {
                        val modSubpkg =
                            segs
                                .dropLast(1)
                                .joinToString(".") { rustModuleIdentToKotlin(it.ident.toString().replace('-', '_')) }
                        headerContext.crateFnReturnTypes["$modSubpkg.$methodKt"]
                            ?: headerContext.crateFnReturnTypes[methodKt]
                    }
                }
                else -> null
            }
        }
        is Expr.MethodCall -> {
            val rawMethod = expr.method.toString().removePrefix("r#")
            sourceReceiverMethodReturn(expr.receiver, rawMethod)?.let { return renderType(it, selfTypeName) }
            val lazyRecvType by lazy(LazyThreadSafetyMode.NONE) { inferExprRenderedType(expr.receiver, selfTypeName) }
            if (rawMethod in setOf("as_ptr", "as_mut_ptr") && expr.args.isEmpty()) {
                sourceArrayLocal(expr.receiver)?.second?.let {
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    val ptrType = if (rawMethod == "as_mut_ptr") "MutPtr" else "ConstPtr"
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.$ptrType")
                    return "$ptrType<${renderType(it, selfTypeName)}>"
                }
                val recvRendered = lazyRecvType
                val elemRendered = recvRendered?.let { extractIndexedElementRenderedType(it, selfTypeName) }
                if (elemRendered != null) {
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    val ptrType = if (rawMethod == "as_mut_ptr") "MutPtr" else "ConstPtr"
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.$ptrType")
                    return "$ptrType<$elemRendered>"
                }
            }
            if ((rawMethod == "to_be" || rawMethod == "from_be" || rawMethod == "swap_bytes" || rawMethod == "to_le" || rawMethod == "from_le") &&
                expr.args.isEmpty()
            ) {
                return lazyRecvType
                    ?: inferExprNumericKind(expr.receiver, selfTypeName)?.let(this::numericKindToPureTypeName)
            }
            if (!hasDeclaredOptionEnum() && expr.turbofish == null) {
                val optRecvTy = lazyRecvType
                val recvIsNullable = optRecvTy != null && optRecvTy.endsWith("?")
                if (recvIsNullable) {
                    when {
                        rawMethod == "unwrap" && expr.args.isEmpty() -> return optRecvTy.removeSuffix("?")
                        (rawMethod == "expect" || rawMethod == "unwrap_or" || rawMethod == "unwrap_or_else") && expr.args.size == 1 ->
                            return optRecvTy.removeSuffix("?")
                    }
                }
                if (recvIsNullable || isKnownNullableOptionExpr(expr.receiver, "", selfTypeName)) {
                    when {
                        (rawMethod == "or" || rawMethod == "or_else" || rawMethod == "filter") && expr.args.size == 1 ->
                            return optRecvTy ?: "Any?"
                        (rawMethod == "cloned" || rawMethod == "copied" || rawMethod == "as_ref" || rawMethod == "as_mut") && expr.args.isEmpty() ->
                            return optRecvTy ?: "Any?"
                        rawMethod == "flatten" && expr.args.isEmpty() ->
                            return optRecvTy?.removeSuffix("?")?.let { inner ->
                                if (inner.endsWith("?")) inner else "$inner?"
                            } ?: "Any?"
                        (rawMethod == "map" || rawMethod == "and_then") && expr.args.size == 1 -> {
                            val cbTy =
                                expr.args
                                    .first()
                                    ?.let { unwrapParenOrGroupExpr(it) as? Expr.Closure }
                                    ?.body
                                    ?.let { inferExprRenderedType(it, selfTypeName)?.removeSuffix("?") }
                                    ?: "Any"
                            return "$cbTy?"
                        }
                    }
                }
            }
            if ("core" in headerContext.crateNamespaces && expr.turbofish == null) {
                val recvRendered = lazyRecvType?.removeSuffix("?")?.trim()
                when {
                    rawMethod == "chars" && expr.args.isEmpty() && (recvRendered == "String" || recvRendered == "CharSequence") ->
                        return "RustStrChars"
                    recvRendered == "RustStrChars" && expr.args.isEmpty() ->
                        when (rawMethod) {
                            "next" -> return "Char?"
                            "as_str" -> return "String"
                            "clone" -> return "RustStrChars"
                            "enumerate" -> return "Iterator<Pair<Int, Char>>"
                        }
                    rawMethod == "to_digit" &&
                        expr.args.size == 1 &&
                        (recvRendered == "Char" || (unwrapParenOrGroupExpr(expr.receiver) as? Expr.Lit)?.lit is Lit.Char) ->
                        return "UInt?"
                }
            }
            val recvNumericKind =
                when (expr.receiver) {
                    is Expr.Call, is Expr.MethodCall, is Expr.BlockExpr, is Expr.Unsafe, is Expr.If ->
                        lazyRecvType?.let(this::resolveNumericKind)
                    else -> inferExprNumericKind(expr.receiver, selfTypeName)
                }
            recvNumericKind?.let { concreteRecvKind ->
                val tfType =
                    expr.turbofish
                        ?.args
                        ?.toList()
                        ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
                inferPrimitiveOpReturnRenderedType(concreteRecvKind, rawMethod, tfType, selfTypeName)?.let {
                    return it
                }
            }
            when {
                (
                    rawMethod == "offset" ||
                        rawMethod == "add" ||
                        rawMethod == "sub" ||
                        rawMethod == "byte_offset" ||
                        rawMethod == "byte_add" ||
                        rawMethod == "byte_sub" ||
                        rawMethod == "wrapping_offset" ||
                        rawMethod == "wrapping_add" ||
                        rawMethod == "wrapping_sub" ||
                        rawMethod == "wrapping_byte_offset" ||
                        rawMethod == "wrapping_byte_add" ||
                        rawMethod == "wrapping_byte_sub"
                ) && expr.args.size == 1 -> lazyRecvType
                rawMethod == "cast_mut" && expr.args.isEmpty() -> {
                    val recvTy = lazyRecvType
                    val pointee = rawPointerPointeeType(recvTy)
                    if (pointee != null) {
                        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.MutPtr")
                        "MutPtr<$pointee>"
                    } else {
                        recvTy
                    }
                }
                rawMethod == "cast_const" && expr.args.isEmpty() -> {
                    val recvTy = lazyRecvType
                    val pointee = rawPointerPointeeType(recvTy)
                    if (pointee != null) {
                        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.ConstPtr")
                        "ConstPtr<$pointee>"
                    } else {
                        recvTy
                    }
                }
                rawMethod == "wrapping_mul" && expr.args.size == 1 -> lazyRecvType
                (rawMethod == "to_be_bytes" || rawMethod == "to_le_bytes" || rawMethod == "to_ne_bytes") && expr.args.isEmpty() ->
                    "UByteArray"
                rawMethod == "as_bytes" && expr.args.isEmpty() ->
                    if (headerContext.rawAbiMode || headerContext.finalClasses) "UByteArray" else "List<UByte>"
                rawMethod == "len" && expr.args.isEmpty() -> "Int"
                rawMethod == "to_bits" && expr.args.isEmpty() ->
                    if (recvNumericKind == NumericKind.F32) "UInt" else "ULong"
                (rawMethod == "to_lowercase" || rawMethod == "to_uppercase") &&
                    expr.args.isEmpty() &&
                    inspector.instanceMethodsByType.values.none { rustFunctionIdentToKotlin(rawMethod) in it } -> "String"
                (rawMethod == "split_once" || rawMethod == "rsplit_once") && expr.args.size == 1 -> "Pair<String, String>?"
                rawMethod == "as_str" &&
                    expr.args.isEmpty() &&
                    inspector.instanceMethodsByType.values.none { "asStr" in it } -> "String"
                (rawMethod == "ok_or" || rawMethod == "ok_or_else") && expr.args.size == 1 ->
                    if (usesTaggedCoreResult()) {
                        val okInner = lazyRecvType?.removeSuffix("?")?.trim() ?: "*"
                        "${sourceResultName()}<$okInner, String>"
                    } else {
                        "Result<*>"
                    }
                (rawMethod == "cloned" || rawMethod == "copied") && expr.args.isEmpty() ->
                    lazyRecvType
                rawMethod == "cast" && expr.args.isEmpty() -> {
                    val recvTy = lazyRecvType
                    if (rawPointerPointeeType(recvTy) != null) {
                        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                        val ptrCtor = if (isMutRawPointerRenderedType(recvTy)) "MutPtr" else "ConstPtr"
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.$ptrCtor")
                        val destination =
                            expr.turbofish
                                ?.args
                                ?.toList()
                                ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
                        return destination?.let { "$ptrCtor<${renderType(it, selfTypeName)}>" }
                            ?: currentExpectedParseTargetType
                            ?: currentEnclosingReturnRenderedType
                            ?: recvTy
                    }
                    lazyRecvType
                }
                rawMethod == "clone" && expr.args.isEmpty() -> lazyRecvType
                else -> {
                    val receiverType = lazyRecvType ?: return null
                    val recvTy =
                        (
                            mutableReferencePointeeType(receiverType)
                                ?: refCellGuardPointeeType(receiverType)
                                ?: receiverType
                        ).removeSuffix("?").trim()
                    if (isMapRenderedType(recvTy) &&
                        (rawMethod == "get" || rawMethod == "get_mut" || rawMethod == "remove") &&
                        expr.args.size == 1 &&
                        '<' in recvTy &&
                        recvTy.endsWith('>')
                    ) {
                        val innerArgs = recvTy.substringAfter('<').dropLast(1)
                        val comma = findTopLevelGenericCommaIndex(innerArgs)
                        if (comma >= 0) {
                            val valTy = innerArgs.substring(comma + 1).trim()
                            if (valTy.isNotEmpty() && valTy != "*") return "$valTy?"
                        }
                    }
                    val ownerKt = recvTy.substringAfterLast('.').substringBefore('<').trim()
                    if (ownerKt.isEmpty()) return null
                    val methodKt = rustFunctionIdentToKotlin(rawMethod)
                    mutablePlaceReturnType(ownerKt, rawMethod)?.let { return it }
                    inspector.implMethodReturnSynTypes[ownerKt to methodKt]?.let { return renderType(it, ownerKt) }
                    for (ownerKey in scopedAbiKeysForRenderedType(recvTy)) {
                        headerContext.crateFnReturnTypes["$ownerKey.$methodKt"]?.let { return it }
                    }
                    headerContext.crateFnReturnTypes["$ownerKt.$methodKt"]
                }
            }
        }
        is Expr.BlockExpr -> {
            val tail = expr.block.stmts.lastOrNull() as? Stmt.ExprStmt
            if (tail != null && tail.semiToken == null) inferExprRenderedType(tail.expr, selfTypeName) else null
        }
        is Expr.Unsafe -> {
            val tail = expr.block.stmts.lastOrNull() as? Stmt.ExprStmt
            if (tail != null && tail.semiToken == null) inferExprRenderedType(tail.expr, selfTypeName) else null
        }
        is Expr.If -> {
            val tail = expr.thenBranch.stmts.lastOrNull() as? Stmt.ExprStmt
            if (tail != null && tail.semiToken == null) inferExprRenderedType(tail.expr, selfTypeName) else null
        }
        is Expr.Repeat -> {
            val elemTy = inferExplicitArrayElementRenderedType(expr.expr, selfTypeName)
            primitiveArrayTypeForElementRenderedType(elemTy) ?: elemTy?.let { "List<$it>" }
        }
        is Expr.Array -> {
            val elemTy = expr.elems.toList().firstNotNullOfOrNull { inferExplicitArrayElementRenderedType(it, selfTypeName) }
            primitiveArrayTypeForElementRenderedType(elemTy) ?: elemTy?.let { "List<$it>" }
        }
        else -> null
    }

internal fun KotlinJ2KEmitter.unwrapParenOrGroupExpr(expr: Expr): Expr =
    when (expr) {
        is Expr.Paren -> unwrapParenOrGroupExpr(expr.expr)
        is Expr.Group -> unwrapParenOrGroupExpr(expr.expr)
        else -> expr
    }

internal fun KotlinJ2KEmitter.isNarrowComparisonLiteralOperand(operand: Expr, targetKind: NumericKind): Boolean {
    val isNarrowUnsigned = targetKind == NumericKind.U8 || targetKind == NumericKind.U16
    val isNarrowSigned = targetKind == NumericKind.I8 || targetKind == NumericKind.I16
    if (!isNarrowUnsigned && !isNarrowSigned) return false
    val core = unwrapParenOrGroupExpr(operand)
    if (core is Expr.Lit) {
        val lit = core.lit
        if (lit is Lit.Int) {
            val suffix = lit.value.suffix()
            return if (isNarrowUnsigned) {
                suffix.isEmpty() || suffix == "u8" || suffix == "u16"
            } else {
                suffix.isEmpty() || suffix == "i8" || suffix == "i16"
            }
        }
        return false
    }
    if (isNarrowSigned && core is Expr.Unary && core.op is UnOp.Neg) {
        val inner = unwrapParenOrGroupExpr(core.expr)
        val innerLit = (inner as? Expr.Lit)?.lit as? Lit.Int ?: return false
        val suffix = innerLit.value.suffix()
        return suffix.isEmpty() || suffix == "i8" || suffix == "i16"
    }
    return false
}

internal fun KotlinJ2KEmitter.coerceRenderedNarrowLiteral(sourceExpr: Expr, rendered: String, kind: NumericKind): String {
    if (isAlreadyRenderedAsNumericKind(rendered, kind)) return rendered
    val recv = if (isAtomicPostfixReceiver(sourceExpr, rendered)) rendered else "($rendered)"
    return when (kind) {
        NumericKind.U8 -> if (rendered.endsWith(".toUByte()")) rendered else "$recv.toUByte()"
        NumericKind.U16 -> if (rendered.endsWith(".toUShort()")) rendered else "$recv.toUShort()"
        NumericKind.I8 -> if (rendered.endsWith(".toByte()")) rendered else "$recv.toByte()"
        NumericKind.I16 -> if (rendered.endsWith(".toShort()")) rendered else "$recv.toShort()"
        else -> rendered
    }
}

internal fun KotlinJ2KEmitter.isUnsignedKind(kind: NumericKind): Boolean =
    kind == NumericKind.U8 ||
        kind == NumericKind.U16 ||
        kind == NumericKind.U32 ||
        kind == NumericKind.U64 ||
        kind == NumericKind.U128

internal fun KotlinJ2KEmitter.coerceRenderedNumericExpr(sourceExpr: Expr, rendered: String, kind: NumericKind): String {
    if (isAlreadyRenderedAsNumericKind(rendered, kind)) return rendered
    val recv = if (isAtomicPostfixReceiver(sourceExpr, rendered)) rendered else "($rendered)"
    return when (kind) {
        NumericKind.U8 -> if (rendered.endsWith(".toUByte()")) rendered else "$recv.toUByte()"
        NumericKind.U16 -> if (rendered.endsWith(".toUShort()")) rendered else "$recv.toUShort()"
        NumericKind.U32 -> if (rendered.endsWith(".toUInt()")) rendered else "$recv.toUInt()"
        NumericKind.U64 -> if (rendered.endsWith(".toULong()")) rendered else "$recv.toULong()"
        NumericKind.I8 -> if (rendered.endsWith(".toByte()")) rendered else "$recv.toByte()"
        NumericKind.I16 -> if (rendered.endsWith(".toShort()")) rendered else "$recv.toShort()"
        NumericKind.I32 -> if (rendered.endsWith(".toInt()")) rendered else "$recv.toInt()"
        NumericKind.I64 -> if (rendered.endsWith(".toLong()")) rendered else "$recv.toLong()"
        NumericKind.F32 -> if (rendered.endsWith(".toFloat()")) rendered else "$recv.toFloat()"
        NumericKind.F64 -> if (rendered.endsWith(".toDouble()")) rendered else "$recv.toDouble()"
        else -> rendered
    }
}

internal fun KotlinJ2KEmitter.inferExprNumericKind(expr: Expr, selfTypeName: String?): NumericKind? =
    when (expr) {
        is Expr.Paren -> inferExprNumericKind(expr.expr, selfTypeName)
        is Expr.Group -> inferExprNumericKind(expr.expr, selfTypeName)
        is Expr.Cast -> resolveNumericKind(renderType(expr.ty, selfTypeName))
        is Expr.Index -> inferArrayElementNumericKind(expr.expr, selfTypeName)
        is Expr.Field -> resolveFieldRenderedType(expr, selfTypeName)?.let(this::resolveNumericKind)
        is Expr.Call, is Expr.MethodCall, is Expr.BlockExpr, is Expr.Unsafe, is Expr.If ->
            inferExprRenderedType(expr, selfTypeName)?.let(this::resolveNumericKind)
        is Expr.Lit -> {
            val lit = expr.lit
            when (lit) {
                is Lit.Byte -> NumericKind.U8
                is Lit.Float ->
                    when (lit.value.suffix()) {
                        "f32" -> NumericKind.F32
                        "f64" -> NumericKind.F64
                        else -> null
                    }
                is Lit.Int -> {
                    when (lit.value.suffix()) {
                        "i8" -> NumericKind.I8
                        "u8" -> NumericKind.U8
                        "i16" -> NumericKind.I16
                        "u16" -> NumericKind.U16
                        "i32" -> NumericKind.I32
                        "u32" -> NumericKind.U32
                        "i64" -> NumericKind.I64
                        "u64" -> NumericKind.U64
                        "i128" -> NumericKind.I128
                        "u128" -> NumericKind.U128
                        "isize" ->
                            if (headerContext.rawAbiMode || inExternAbiSignature) {
                                when (activeCfgProfile.pointerWidth) {
                                    16 -> NumericKind.I16
                                    32 -> NumericKind.I32
                                    else -> NumericKind.I64
                                }
                            } else {
                                NumericKind.I32
                            }
                        "usize" ->
                            if (headerContext.rawAbiMode || inExternAbiSignature) {
                                when (activeCfgProfile.pointerWidth) {
                                    16 -> NumericKind.U16
                                    32 -> NumericKind.U32
                                    else -> NumericKind.U64
                                }
                            } else {
                                NumericKind.I32
                            }
                        "" -> {
                            val parsed = parseUnsigned128LiteralDigits(lit.value.token().toString())
                            if (parsed?.exceeds64BitUnsigned == true) NumericKind.U128 else null
                        }
                        else -> null
                    }
                }
                else -> null
            }
        }
        is Expr.Path -> {
            val constPrimKind = resolvePrimitiveAssociatedConstKind(expr.path, expr.qself)
            val segs = expr.path.segments.toList()
            val hasOnlyIgnoredPrefixes =
                expr.qself == null &&
                    segs.isNotEmpty() &&
                    segs.dropLast(1).all {
                        it.arguments is PathArguments.None &&
                            it.ident.toString().removePrefix("r#") in IGNORED_PATH_PREFIXES
                    }
            when {
                constPrimKind != null -> constPrimKind
                hasOnlyIgnoredPrefixes -> {
                    val raw =
                        segs
                            .last()
                            .ident
                            .toString()
                            .removePrefix("r#")
                    val ktConst = inspector.declaredConstNames[raw]
                    val constTy = ktConst?.let { inspector.declaredConstTypes[it] }
                    if (constTy != null) {
                        resolveNumericKind(renderType(constTy, selfTypeName))
                    } else {
                        val localKind =
                            if (segs.size == 1) {
                                val ktLocal = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
                                currentTypedLocals[ktLocal]?.let(this::resolveNumericKind)
                            } else {
                                null
                            }
                        if (localKind != null) {
                            localKind
                        } else {
                            val ktConstIdent = ktConst ?: KotlinNamingPolicy.Default.toConstIdentifier(raw)
                            scopedAbiKeysForRenderedType(ktConstIdent).firstNotNullOfOrNull {
                                numericKindFromScannerCode(headerContext.crateNumericTypeKinds[it])
                            } ?: resolveBestSubpackageForSymbol(ktConstIdent)?.let { sub ->
                                val key = if (sub.isEmpty()) ktConstIdent else "$sub.$ktConstIdent"
                                numericKindFromScannerCode(headerContext.crateNumericTypeKinds[key])
                            } ?: numericKindFromScannerCode(headerContext.crateNumericTypeKinds[ktConstIdent])
                        }
                    }
                }
                else -> null
            }
        }
        is Expr.Unary ->
            when (expr.op) {
                is UnOp.NotOp -> if (isKnownIntegerBitmaskExpr(expr.expr)) inferExprNumericKind(expr.expr, selfTypeName) else null
                is UnOp.Neg -> inferExprNumericKind(expr.expr, selfTypeName)
                is UnOp.Deref -> inferExprRenderedType(expr, selfTypeName)?.let(this::resolveNumericKind) ?: inferExprNumericKind(expr.expr, selfTypeName)
            }
        is Expr.Binary -> {
            when (expr.op) {
                is BinOp.Eq, is BinOp.Ne, is BinOp.Lt, is BinOp.Le, is BinOp.Gt, is BinOp.Ge, is BinOp.And, is BinOp.Or -> null
                is BinOp.Shl, is BinOp.Shr -> inferExprNumericKind(expr.left, selfTypeName)
                else -> inferExprNumericKind(expr.left, selfTypeName) ?: inferExprNumericKind(expr.right, selfTypeName)
            }
        }
        else -> null
    }

internal fun KotlinJ2KEmitter.numericKindToPureTypeName(kind: NumericKind): String =
    when (kind) {
        NumericKind.I8 -> "Byte"
        NumericKind.U8 -> "UByte"
        NumericKind.I16 -> "Short"
        NumericKind.U16 -> "UShort"
        NumericKind.I32 -> "Int"
        NumericKind.U32 -> "UInt"
        NumericKind.I64 -> "Long"
        NumericKind.U64 -> "ULong"
        NumericKind.I128 -> "I128"
        NumericKind.U128 -> "U128"
        NumericKind.F32 -> kotlinF32TypeName()
        NumericKind.F64 -> "Double"
    }

internal fun KotlinJ2KEmitter.numericKindFromScannerCode(code: String?): NumericKind? =
    when (code) {
        "I8" -> NumericKind.I8
        "U8" -> NumericKind.U8
        "I16" -> NumericKind.I16
        "U16" -> NumericKind.U16
        "I32" -> NumericKind.I32
        "U32" -> NumericKind.U32
        "I64" -> NumericKind.I64
        "U64" -> NumericKind.U64
        "I128" -> NumericKind.I128
        "U128" -> NumericKind.U128
        "F32" -> NumericKind.F32
        "F64" -> NumericKind.F64
        else -> null
    }

internal fun KotlinJ2KEmitter.renderIntToBytesMethodCall(
    receiver: String,
    sourceReceiver: Expr,
    recvKind: NumericKind,
    rawMethod: String,
    selfTypeName: String? = null,
): String {
    val isBigEndian =
        rawMethod == "to_be_bytes" ||
            (rawMethod == "to_ne_bytes" && activeCfgProfile.targetEndian == "big")
    if (recvKind == NumericKind.U128 || recvKind == NumericKind.I128) {
        if (recvKind == NumericKind.U128) usesU128 = true else usesI128 = true
        val recvAtom = if (isAtomicPostfixReceiver(sourceReceiver, receiver)) receiver else "($receiver)"
        val fn = if (isBigEndian) "toBeBytes" else "toLeBytes"
        return "$recvAtom.$fn()"
    }
    val inferredReceiverType = inferExprRenderedType(sourceReceiver, selfTypeName)
    val ctor = "ubyteArrayOf"
    return when (recvKind) {
        NumericKind.U8, NumericKind.I8 -> {
            val byteExpr =
                if (inferredReceiverType == "UByte") {
                    receiver
                } else {
                    coerceRenderedNumericExpr(sourceReceiver, receiver, NumericKind.U8)
                }
            "$ctor($byteExpr)"
        }
        NumericKind.U16, NumericKind.I16 -> {
            val u32Expr = coerceRenderedNumericExpr(sourceReceiver, receiver, NumericKind.U32)
            val shifts = if (isBigEndian) listOf(8, 0) else listOf(0, 8)
            val elems = shifts.joinToString(", ") { s -> if (s == 0) "rawBits.toUByte()" else "(rawBits shr $s).toUByte()" }
            "run { val rawBits = $u32Expr; $ctor($elems) }"
        }
        NumericKind.U32, NumericKind.I32 -> {
            val u32Expr =
                if (inferredReceiverType == "UInt") {
                    receiver
                } else {
                    coerceRenderedNumericExpr(sourceReceiver, receiver, NumericKind.U32)
                }
            val shifts = if (isBigEndian) listOf(24, 16, 8, 0) else listOf(0, 8, 16, 24)
            val elems = shifts.joinToString(", ") { s -> if (s == 0) "rawBits.toUByte()" else "(rawBits shr $s).toUByte()" }
            "run { val rawBits = $u32Expr; $ctor($elems) }"
        }
        NumericKind.U64, NumericKind.I64 -> {
            val u64Expr =
                if (inferredReceiverType == "ULong") {
                    receiver
                } else {
                    coerceRenderedNumericExpr(sourceReceiver, receiver, NumericKind.U64)
                }
            val shifts =
                if (isBigEndian) {
                    listOf(56, 48, 40, 32, 24, 16, 8, 0)
                } else {
                    listOf(0, 8, 16, 24, 32, 40, 48, 56)
                }
            val elems = shifts.joinToString(", ") { s -> if (s == 0) "rawBits.toUByte()" else "(rawBits shr $s).toUByte()" }
            "run { val rawBits = $u64Expr; $ctor($elems) }"
        }
        NumericKind.F32 -> {
            val shifts = if (isBigEndian) listOf(24, 16, 8, 0) else listOf(0, 8, 16, 24)
            val elems = shifts.joinToString(", ") { s -> if (s == 0) "rawBits.toUByte()" else "(rawBits shr $s).toUByte()" }
            "run { val rawBits = ($receiver).toRawBits().toUInt(); $ctor($elems) }"
        }
        NumericKind.F64 -> {
            val shifts =
                if (isBigEndian) {
                    listOf(56, 48, 40, 32, 24, 16, 8, 0)
                } else {
                    listOf(0, 8, 16, 24, 32, 40, 48, 56)
                }
            val elems = shifts.joinToString(", ") { s -> if (s == 0) "rawBits.toUByte()" else "(rawBits shr $s).toUByte()" }
            "run { val rawBits = ($receiver).toRawBits().toULong(); $ctor($elems) }"
        }
        NumericKind.U128, NumericKind.I128 -> error("handled above")
    }
}

internal fun KotlinJ2KEmitter.renderExprClosure(expr: Expr.Closure, indent: String, selfTypeName: String?): String {
    val hasDirectReturn = exprContainsDirectClosureReturn(expr.body)
    val prevLabel = currentClosureLabel
    val prevEnclosingRet = currentEnclosingReturnRenderedType
    val prevEnclosingSynRet = currentEnclosingReturnSynType
    val prevExpectedKind = currentExpectedNumericKind
    val prevExpectedParseTarget = currentExpectedParseTargetType
    val prevTypedLocals = currentTypedLocals
    val prevTupleElemType = currentClosureTupleElemType
    currentClosureTupleElemType = null
    val assignedLabel =
        if (hasDirectReturn) {
            val idx = closureLabelCounter++
            if (idx == 0) "closure" else "closure$idx"
        } else {
            null
        }
    currentClosureLabel = assignedLabel
    val explicitClosureRet = extractReturnSynType(expr.output)?.let { renderType(it, selfTypeName) }
    val closureRetKind = explicitClosureRet?.let(this::resolveNumericKind)
    currentEnclosingReturnRenderedType = explicitClosureRet
    currentEnclosingReturnSynType = extractReturnSynType(expr.output)
    currentExpectedNumericKind = null
    scopeBuilder.enterDef()
    return try {
        val hoistedDestructurings = mutableListOf<String>()
        var nestedTupleCounter = 0
        val params =
            expr.inputs.toList().mapIndexedNotNull { idx, pat ->
                val renderedParam =
                    renderClosureParam(
                        pat = pat,
                        idx = idx,
                        selfTypeName = selfTypeName,
                        nestedTuple = false,
                        hoistedDestructurings = hoistedDestructurings,
                        allocateTupleTemp = { "tuple${nestedTupleCounter++}" },
                    ).takeIf { it.isNotEmpty() && it != "_" }
                if (prevTupleElemType != null && pat is Pat.Tuple) {
                    val boundNames =
                        pat.elems
                            .toList()
                            .mapNotNull { elemPat ->
                                (elemPat as? Pat.Ident)?.ident?.toString()?.removePrefix("r#")?.takeIf { it != "_" }?.let {
                                    scopeBuilder.resolveLocalKotlinName(it) ?: rustValueIdentToKotlin(it)
                                }
                            }
                    if (boundNames.isNotEmpty()) {
                        currentTypedLocals = currentTypedLocals + boundNames.associateWith { prevTupleElemType }
                    }
                }
                renderedParam
            }
        scopeBuilder.scopeData.mutScope(scopeBuilder.topScopeId()).setParamCount(params.size)
        val body = expr.body
        val innerIndent = "$indent    "
        val bodyText =
            if (body is Expr.BlockExpr) {
                val stmts = body.block.stmts
                if (stmts.isEmpty() && hoistedDestructurings.isEmpty()) {
                    ""
                } else {
                    val renderedStmts =
                        stmts.mapIndexed { stmtIdx, stmt ->
                            val isLastValueExpr =
                                stmtIdx == stmts.lastIndex &&
                                    stmt is Stmt.ExprStmt &&
                                    stmt.semiToken == null &&
                                    !isExplicitControlFlowJump(stmt.expr)
                            currentExpectedNumericKind = if (isLastValueExpr) closureRetKind else null
                            currentExpectedParseTargetType = if (isLastValueExpr) prevExpectedParseTarget else null
                            try {
                                renderStmt(stmt, innerIndent, selfTypeName, isTailReturn = false)
                            } finally {
                                currentExpectedNumericKind = null
                                currentExpectedParseTargetType = null
                            }
                        }
                    val allLines = hoistedDestructurings.map { "$innerIndent$it" } + renderedStmts
                    "\n" + allLines.joinToString("\n") + "\n$indent"
                }
            } else {
                val bodyIndent = if (hoistedDestructurings.isEmpty()) indent else innerIndent
                val renderedBody =
                    if (closureRetKind != null) {
                        renderTypedNumericInitializer(body, closureRetKind, bodyIndent, selfTypeName)
                    } else {
                        renderExpr(body, bodyIndent, selfTypeName)
                    }
                if (hoistedDestructurings.isEmpty()) {
                    " $renderedBody "
                } else {
                    val allLines = hoistedDestructurings.map { "$innerIndent$it" } + "$innerIndent$renderedBody"
                    "\n" + allLines.joinToString("\n") + "\n$indent"
                }
            }
        val labelPrefix = assignedLabel?.let { "$it@" }.orEmpty()
        if (params.isEmpty()) {
            "$labelPrefix{$bodyText}"
        } else {
            "$labelPrefix{ ${params.joinToString(", ")} ->$bodyText}"
        }
    } finally {
        scopeBuilder.exitDef()
        currentClosureLabel = prevLabel
        currentEnclosingReturnRenderedType = prevEnclosingRet
        currentEnclosingReturnSynType = prevEnclosingSynRet
        currentExpectedNumericKind = prevExpectedKind
        currentExpectedParseTargetType = prevExpectedParseTarget
        currentTypedLocals = prevTypedLocals
        currentClosureTupleElemType = prevTupleElemType
    }
}

internal fun KotlinJ2KEmitter.exprContainsDirectClosureReturn(body: Expr): Boolean {
    var found = false
    val visitor =
        object : Visit() {
            override fun visitExprClosure(e: Expr.Closure) {
                // Nested closures have their own return target.
            }

            override fun visitItemFn(i: Item.Fn) {
                // Nested functions have their own return target.
            }

            override fun visitExprReturn(e: Expr.Return) {
                found = true
            }

            override fun visitExprTry(e: Expr.Try) {
                found = true
            }
        }
    visitor.visitExpr(body)
    return found
}

internal fun KotlinJ2KEmitter.renderClosureParam(
    pat: Pat,
    idx: Int,
    selfTypeName: String?,
    nestedTuple: Boolean = false,
    hoistedDestructurings: MutableList<String> = mutableListOf(),
    allocateTupleTemp: () -> String = { "tuple$idx" },
): String =
    when (pat) {
        is Pat.Ident -> {
            val raw = pat.ident.toString().removePrefix("r#")
            if (raw == "_") {
                ""
            } else {
                val assignCount = if (pat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                scopeBuilder.bindLocal(raw, rustValueIdentToKotlin(raw), pat.ident.span(), assignCount = assignCount).kotlinName
            }
        }
        is Pat.TypeAscription -> {
            val name = renderClosureParam(pat.pat, idx, selfTypeName, nestedTuple, hoistedDestructurings, allocateTupleTemp)
            val ty = renderType(pat.ty, selfTypeName)
            if (name.isEmpty()) "" else "$name: $ty"
        }
        is Pat.Wild -> ""
        is Pat.Tuple -> {
            val childDestructurings = mutableListOf<String>()
            val rawElems =
                pat.elems.toList().mapIndexed { i, p ->
                    renderClosureParam(
                        pat = p,
                        idx = i,
                        selfTypeName = selfTypeName,
                        nestedTuple = true,
                        hoistedDestructurings = childDestructurings,
                        allocateTupleTemp = allocateTupleTemp,
                    )
                }
            val destructuredPattern =
                if (rawElems.size >= 2 && rawElems.any { it.isNotEmpty() && it != "_" }) {
                    "(${rawElems.joinToString(", ") { if (it.isEmpty()) "_" else it }})"
                } else {
                    val inner = rawElems.filter { it.isNotEmpty() && it != "_" }
                    when (inner.size) {
                        0 -> ""
                        1 -> inner[0]
                        else -> "(${inner.joinToString(", ")})"
                    }
                }
            if (nestedTuple && destructuredPattern.startsWith("(")) {
                val tempName = allocateTupleTemp()
                hoistedDestructurings += "val $destructuredPattern = $tempName"
                hoistedDestructurings.addAll(childDestructurings)
                tempName
            } else {
                hoistedDestructurings.addAll(childDestructurings)
                destructuredPattern
            }
        }
        is Pat.Reference -> renderClosureParam(pat.pat, idx, selfTypeName, nestedTuple, hoistedDestructurings, allocateTupleTemp)
        else -> "param$idx"
    }

internal fun KotlinJ2KEmitter.collectUsedRawIdentsInExpr(expr: Expr): Set<String> {
    val used = linkedSetOf<String>()
    val visitor =
        object : Visit() {
            override fun visitExprPath(e: Expr.Path) {
                if (e.qself == null && e.path.segments.len() == 1) {
                    val firstSeg = e.path.segments.first()
                    val raw = firstSeg?.ident?.toString()?.removePrefix("r#")
                    if (raw != null) used += raw
                }
                super.visitExprPath(e)
            }
        }
    visitor.visitExpr(expr)
    return used
}

internal fun KotlinJ2KEmitter.collectUsedRawIdentsInBlock(block: Block): Set<String> {
    val used = linkedSetOf<String>()
    val visitor =
        object : Visit() {
            override fun visitExprPath(e: Expr.Path) {
                if (e.qself == null && e.path.segments.len() == 1) {
                    val firstSeg = e.path.segments.first()
                    val raw = firstSeg?.ident?.toString()?.removePrefix("r#")
                    if (raw != null) used += raw
                }
                super.visitExprPath(e)
            }
        }
    visitor.visitBlock(block)
    return used
}

private fun KotlinJ2KEmitter.borrowedPlaceLocalName(expr: Expr): String? {
    val path = unwrapParenOrGroupExpr(expr) as? Expr.Path ?: return null
    if (path.qself != null || path.path.segments.size != 1) return null
    val raw =
        path.path.segments
            .first()
            ?.ident
            ?.toString()
            ?.removePrefix("r#") ?: return null
    val name = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
    return name.takeIf { it in currentBorrowedPlaceLocals }
}

internal fun KotlinJ2KEmitter.renderBorrowedPlace(expr: Expr, indent: String, selfTypeName: String?): String? {
    val (receiver, fields) = borrowedPlaceProjection(expr, selfTypeName) ?: return null
    return "(${renderExpr(receiver, indent, selfTypeName)}).${fields.joinToString(".")}"
}

internal fun KotlinJ2KEmitter.borrowedPlaceProjection(
    expr: Expr,
    selfTypeName: String?,
    addressArgument: Boolean = false,
): Pair<Expr, List<String>>? {
    if (headerContext.rawAbiMode) return null
    var core = unwrapParenOrGroupExpr(expr)
    if (core is Expr.Unsafe) core = (core.block.stmts.singleOrNull() as? Stmt.ExprStmt)?.expr ?: return null
    if (addressArgument && core is Expr.Reference) {
        if (core.mutability == null) return null
        val place = unwrapParenOrGroupExpr(core.expr) as? Expr.Unary ?: return null
        if (place.op !is UnOp.Deref) return null
        core = unwrapParenOrGroupExpr(place.expr)
    }
    val call = core as? Expr.MethodCall ?: return null
    if (!call.args.isEmpty() || call.turbofish != null) return null
    val receiverType = inferExprRenderedType(call.receiver, selfTypeName) ?: return null
    val owner = receiverType.removeSuffix("?").substringBefore('<').substringAfterLast('.')
    val fields = mutableReferenceProjection(owner, call.method.toString(), linkedSetOf()) ?: return null
    return call.receiver to fields
}

private fun KotlinJ2KEmitter.renderPlaceOperation(expr: Expr.Call, indent: String, selfTypeName: String?): String? {
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
internal fun KotlinJ2KEmitter.mutablePlaceAccessorType(owner: String, method: String): String? {
    // A raw Rust address can escape into FFI/atomic operations. It must retain
    // its pointer contract, even when its body projects one transparent field.
    if (inspector.implMethodReturnSynTypes[owner to rustFunctionIdentToKotlin(method)] is SynType.Ptr) return null
    val fields = mutableReferenceProjection(owner, method, linkedSetOf()) ?: return null
    var fieldOwner = owner
    var type: SynType? = null
    for (field in fields) {
        type = inspector.structFieldSynTypesByType[fieldOwner]?.get(field) ?: return null
        fieldOwner = renderType(type, fieldOwner).substringBefore('<').substringAfterLast('.')
    }
    return "kotlin.reflect.KMutableProperty0<${renderType(type ?: return null, owner)}>"
}

/** Infer a transported place from this file's declared return and tail call.
 * Unlike a pure accessor projection, preceding statements must still execute.
 */
internal fun KotlinJ2KEmitter.mutablePlaceReturnType(
    owner: String,
    method: String,
    visiting: MutableSet<Pair<String, String>> = linkedSetOf(),
): String? {
    val transparent = transparentDeclaration(owner)
    val liveField = transparent?.let { sourceTransparentField(it, owner + renderTypeArgumentsFromGenerics(it.generics)) }
    val declaredOutput = inspector.implMethodReturnSynTypes[owner to rustFunctionIdentToKotlin(method)]
    if (liveField != null && declaredOutput is SynType.Reference && declaredOutput.mutability != null &&
        mutableReferenceProjection(owner, method, linkedSetOf()) == listOf(liveField.first)
    ) {
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.RustMutableReference")
        return "RustMutableReference<${liveField.second}>"
    }
    mutablePlaceAccessorType(owner, method)?.let { return it }
    val key = owner to method
    if (!visiting.add(key)) return null
    try {
        val fn =
            file.items
                .filterIsInstance<Item.Impl>()
                .filter {
                    it.traitPath == null && renderType(it.selfType, owner).substringBefore('<').substringAfterLast('.') == owner
                }.flatMap { it.items.filterIsInstance<ImplItem.Fn>() }
                .singleOrNull {
                    it.sig.ident.toString() == method && it.sig.inputs.size == 1 && it.sig.inputs.firstOrNull() is FnArg.Receiver
                } ?: return null
        val output = (fn.sig.output as? ReturnType.TypeReturn)?.ty ?: return null
        if (output !is SynType.Reference || output.mutability == null) return null
        val tail = fn.block.stmts.lastOrNull() as? Stmt.ExprStmt ?: return null
        if (tail.semiToken != null) return null
        // A second exit would need its own place proof. Do not change the
        // function contract based on only one of multiple return paths.
        var hasExplicitReturn = false
        val returnVisitor =
            object : Visit() {
                override fun visitExprReturn(e: Expr.Return) {
                    hasExplicitReturn = true
                }
            }
        for (stmt in fn.block.stmts) returnVisitor.visitStmt(stmt)
        if (hasExplicitReturn) return null
        val call = unwrapParenOrGroupExpr(tail.expr) as? Expr.MethodCall ?: return null
        if (!call.args.isEmpty() || call.turbofish != null) return null
        val receiver = unwrapParenOrGroupExpr(call.receiver) as? Expr.Path ?: return null
        if (receiver.qself != null || !receiver.path.isIdent("self")) return null
        mutablePlaceReturnType(owner, call.method.toString(), visiting) ?: return null
        return "kotlin.reflect.KMutableProperty0<${renderType(output.elem, owner)}>"
    } finally {
        visiting.remove(key)
    }
}

internal val STR_PRIMITIVE_PARSE_METHODS: Map<String, String> =
    mapOf(
        "Boolean" to "toBooleanStrictOrNull()",
        "Byte" to "toByteOrNull()",
        "UByte" to "toUByteOrNull()",
        "Short" to "toShortOrNull()",
        "UShort" to "toUShortOrNull()",
        "Int" to "toIntOrNull()",
        "UInt" to "toUIntOrNull()",
        "Long" to "toLongOrNull()",
        "ULong" to "toULongOrNull()",
        "Float" to "toFloatOrNull()",
        "kotlin.Float" to "toFloatOrNull()",
        "Double" to "toDoubleOrNull()",
    )

internal fun refCellGuardPointeeType(type: String?): String? {
    val base = type?.trim()?.removeSuffix("?") ?: return null
    if (!base.endsWith('>')) return null
    val simple = base.substringBefore('<').substringAfterLast('.').trim()
    if (simple != "Ref" && simple != "RefMut") return null
    return base
        .substringAfter('<')
        .dropLast(1)
        .trim()
        .takeIf { it.isNotEmpty() }
}

internal fun isMapRenderedType(type: String?): Boolean {
    val clean = type?.removeSuffix("?")?.trim() ?: return false
    return clean.startsWith("MutableMap<") ||
        clean.startsWith("Map<") ||
        clean.startsWith("LinkedHashMap<") ||
        clean.startsWith("HashMap<")
}

internal fun isSetRenderedType(type: String?): Boolean {
    val clean = type?.removeSuffix("?")?.trim() ?: return false
    return clean.startsWith("MutableSet<") ||
        clean.startsWith("Set<") ||
        clean.startsWith("LinkedHashSet<") ||
        clean.startsWith("HashSet<")
}

internal fun mutablePlaceValueType(type: String?): String? =
    type
        ?.takeIf { it.startsWith("kotlin.reflect.KMutableProperty0<") && it.endsWith('>') }
        ?.removePrefix("kotlin.reflect.KMutableProperty0<")
        ?.dropLast(1)

internal fun KotlinJ2KEmitter.mutableReferenceProjection(
    owner: String,
    method: String,
    visiting: MutableSet<Pair<String, String>>,
): List<String>? {
    val key = owner to method
    if (!visiting.add(key)) return null
    try {
        val candidates =
            file.items
                .filterIsInstance<Item.Impl>()
                .filter {
                    it.traitPath == null && renderType(it.selfType, owner).substringBefore('<').substringAfterLast('.') == owner
                }.flatMap { it.items.filterIsInstance<ImplItem.Fn>() }
                .filter {
                    it.sig.ident.toString() == method && it.sig.inputs.size == 1 &&
                        it.sig.inputs.firstOrNull() is FnArg.Receiver
                }
        val fn = candidates.singleOrNull() ?: return null
        val output = (fn.sig.output as? ReturnType.TypeReturn)?.ty ?: return null
        if (output !is SynType.Ptr && (output !is SynType.Reference || output.mutability == null)) return null
        var body = (fn.block.stmts.singleOrNull() as? Stmt.ExprStmt)?.expr ?: return null
        body = unwrapParenOrGroupExpr(body)
        if (body is Expr.Unsafe) body = (body.block.stmts.singleOrNull() as? Stmt.ExprStmt)?.expr ?: return null
        // Pointer-returning wrappers may forward another proven place
        // accessor (Cell::as_ptr -> UnsafeCell::get). The transparent-layout
        // proof is required for reinterpret casts, not for that forwarding.
        if (output is SynType.Ptr && body is Expr.Cast) {
            val struct =
                file.items.filterIsInstance<Item.Struct>().singleOrNull {
                    rustTypeIdentToKotlin(it.ident.toString()) == owner
                } ?: return null
            val transparent =
                struct.attrs.any { attr ->
                    val meta = attr.meta as? Meta.List
                    meta != null && meta.path.isIdent("repr") &&
                        meta.tokens.clone().toList().any {
                            it is TokenTree.Ident && it.value.toString() == "transparent"
                        }
                }
            if (!transparent) return null
            val field = inspector.structFieldSynTypesByType[owner]?.entries?.singleOrNull() ?: return null
            if (renderType(field.value, owner) != renderType(output.elem, owner)) return null
            var cast = unwrapParenOrGroupExpr(body)
            var takesOwnerAddress = false
            while (cast is Expr.Cast) {
                val pointer = cast.ty as? SynType.Ptr ?: return null
                if (renderType(pointer.elem, owner).substringBefore('<') == owner) takesOwnerAddress = true
                cast = unwrapParenOrGroupExpr(cast.expr)
            }
            if (!takesOwnerAddress || cast !is Expr.Path || cast.qself != null || !cast.path.isIdent("self")) return null
            return listOf(field.key)
        }
        val borrowsField = body is Expr.Reference && body.mutability != null
        if (borrowsField) body = unwrapParenOrGroupExpr(body.expr)
        fun fieldPath(place: Expr): List<String>? {
            val core = unwrapParenOrGroupExpr(place)
            if (core is Expr.Path && core.qself == null && core.path.isIdent("self")) return emptyList()
            val field = core as? Expr.Field ?: return null
            val member = field.member as? Member.Named ?: return null
            return fieldPath(field.base)?.plus(rustFieldIdentToKotlin(member.ident.toString()))
        }
        if (borrowsField) fieldPath(body)?.takeIf { it.isNotEmpty() }?.let { return it }
        val forwarded = body as? Expr.MethodCall ?: return null
        if (!forwarded.args.isEmpty() || forwarded.turbofish != null) return null
        val prefix = fieldPath(forwarded.receiver) ?: return null
        var fieldOwner = owner
        for (field in prefix) {
            val type = inspector.structFieldSynTypesByType[fieldOwner]?.get(field) ?: return null
            fieldOwner = renderType(type, fieldOwner).substringBefore('<').substringAfterLast('.')
        }
        return prefix + (mutableReferenceProjection(fieldOwner, forwarded.method.toString(), visiting) ?: return null)
    } finally {
        visiting.remove(key)
    }
}

internal fun KotlinJ2KEmitter.transparentPointerField(type: String, selfTypeName: String?): Pair<String, String>? {
    val owner = type.substringBefore('<').substringAfterLast('.')
    val struct =
        file.items.filterIsInstance<Item.Struct>().singleOrNull {
            rustTypeIdentToKotlin(it.ident.toString()) == owner
        } ?: return null
    if (!struct.attrs.any { attr ->
            val meta = attr.meta as? Meta.List
            meta != null && meta.path.isIdent("repr") &&
                meta.tokens.clone().toList().any {
                    it is TokenTree.Ident && it.value.toString() == "transparent"
                }
        }
    ) {
        return null
    }
    val params = renderTypeArgumentsFromGenerics(struct.generics)
    if (type.substringAfterLast('.') != owner + params) return null
    val field = inspector.structFieldSynTypesByType[owner]?.entries?.singleOrNull() ?: return null
    if (field.value !is SynType.Ptr) return null
    return rustFieldIdentToKotlin(field.key) to renderType(field.value, selfTypeName)
}

internal fun KotlinJ2KEmitter.resolveRawPointerPointeeLayout(ptrExpr: Expr, selfTypeName: String?): String {
    (sourceInitializerType(ptrExpr) as? SynType.Ptr)?.elem?.let { pointeeSyn ->
        return CoreIntrinsicLowering.resolveTypeLayoutExpression(pointeeSyn, selfTypeName, this)
    }
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val memPkg = "$coreNs.mem"
    val pointeeKt =
        rawPointerPointeeType(inferExprRenderedType(ptrExpr, selfTypeName))?.trim()
            ?: run {
                CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
                return "PrimitiveLayouts.U8"
            }
    return resolveRenderedTypeLayoutExpr(pointeeKt)
}

internal fun KotlinJ2KEmitter.resolveRenderedTypeLayoutExpr(targetKtRaw: String): String {
    val pointeeKt = targetKtRaw.trim().ifEmpty { "UByte" }
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val memPkg = "$coreNs.mem"
    val expectedLayoutTypes = setOf("TypeLayout<$pointeeKt>", "$memPkg.TypeLayout<$pointeeKt>")
    currentTypedLocals
        .filterValues { it in expectedLayoutTypes }
        .keys
        .singleOrNull()
        ?.let { return it }
    val candidateParam = StructLayoutComputer.typeLayoutParamName(pointeeKt)
    if (candidateParam in currentTypedLocals) return candidateParam
    when (pointeeKt) {
        "Boolean" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.BOOL"
        }
        "Byte" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.I8"
        }
        "UByte", "CVoid" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.U8"
        }
        "Short" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.I16"
        }
        "UShort" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.U16"
        }
        "Int" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.I32"
        }
        "UInt" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.U32"
        }
        "Long" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.I64"
        }
        "ULong" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.U64"
        }
        "Float", "kotlin.Float" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.F32"
        }
        "Double" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.F64"
        }
        "F16" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.F16_LAYOUT"
        }
        "Char" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.CHAR"
        }
        "Unit" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.UNIT"
        }
    }
    rawPointerPointeeType(pointeeKt)?.let { innerPointee ->
        CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
        val factory = if (isMutRawPointerRenderedType(pointeeKt)) "mutPtrLayout" else "constPtrLayout"
        return "PrimitiveLayouts.$factory<$innerPointee>(${activeCfgProfile.pointerWidth}L)"
    }
    val structSimple = pointeeKt.substringBefore('<').substringAfterLast('.')
    val structDecl =
        sameFileLayoutItems()
            .filterIsInstance<Item.Struct>()
            .singleOrNull { rustTypeIdentToKotlin(it.ident.toString()) == structSimple }
    if (structDecl != null) {
        val computed = StructLayoutComputer.computeStructLayout(structDecl, this)
        if (computed != null && computed.requiredTypeLayoutParams.isEmpty()) {
            return "$structSimple.typeLayout()"
        }
    }
    CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.ObjectSlotLayout")
    return "ObjectSlotLayout<$pointeeKt>()"
}

private fun unwrapMaybeUninitRenderedInner(rendered: String?): String? {
    val clean = rendered?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if ((clean.startsWith("MaybeUninit<") || ".MaybeUninit<" in clean) && clean.endsWith(">")) {
        return clean
            .substringAfter("MaybeUninit<")
            .removeSuffix(">")
            .trim()
            .takeIf { it.isNotEmpty() }
    }
    return null
}

private fun KotlinJ2KEmitter.resolveMaybeUninitElementRenderedType(
    callExpr: Expr.Call,
    renderedCallee: String,
    selfTypeName: String?,
): String {
    val funcPath = (unwrapParenOrGroupExpr(callExpr.func) as? Expr.Path)?.path
    if (funcPath != null) {
        for (seg in funcPath.segments.toList()) {
            val ab = seg.arguments as? PathArguments.AngleBracketed ?: continue
            val firstTy =
                ab.args
                    .toList()
                    .firstNotNullOfOrNull { renderGenericArgument(it, selfTypeName) }
            if (!firstTy.isNullOrBlank()) {
                return unwrapMaybeUninitRenderedInner(firstTy) ?: firstTy.trim()
            }
        }
    }
    if ('<' in renderedCallee && renderedCallee.endsWith(">")) {
        val tf = renderedCallee.substringAfter('<').removeSuffix(">").trim()
        if (tf.isNotEmpty() && ',' !in tf) {
            return unwrapMaybeUninitRenderedInner(tf) ?: tf
        }
    }
    currentExpectedParseTargetType?.trim()?.takeIf { it.isNotEmpty() && it != "Unit" }?.let { expected ->
        return unwrapMaybeUninitRenderedInner(expected) ?: expected
    }
    val ownerBase = selfTypeName?.substringBefore('<')?.trim()
    if (!ownerBase.isNullOrEmpty()) {
        val singleFieldSyn = inspector.structFieldSynTypesByType[ownerBase]?.values?.singleOrNull()
        if (singleFieldSyn != null) {
            val renderedField = renderType(singleFieldSyn, selfTypeName)
            unwrapMaybeUninitRenderedInner(renderedField)?.let { return it }
        }
    }
    currentEnclosingReturnRenderedType?.trim()?.takeIf { it.isNotEmpty() && it != "Unit" }?.let { ret ->
        unwrapMaybeUninitRenderedInner(ret)?.let { return it }
        if (ownerBase.isNullOrEmpty() || ret.substringBefore('<').trim() != ownerBase) {
            return ret
        }
    }
    return "UByte"
}

private data class ZippedSliceLeaf(
    val sliceExpr: Expr,
    val rawIdent: String?,
    val patIdent: Ident?,
)

private fun KotlinJ2KEmitter.stripSliceIterMethodCall(expr: Expr): Expr {
    val unwrapped = unwrapParenOrGroupExpr(expr)
    if (unwrapped is Expr.MethodCall &&
        unwrapped.args.isEmpty() &&
        unwrapped.turbofish == null &&
        unwrapped.method.toString().removePrefix("r#") in setOf("iter", "iter_mut", "into_iter")
    ) {
        return unwrapParenOrGroupExpr(unwrapped.receiver)
    }
    return unwrapped
}

private fun unwrapTupleClosurePat(pat: Pat): Pat =
    when (pat) {
        is Pat.Reference -> unwrapTupleClosurePat(pat.pat)
        is Pat.TypeAscription -> unwrapTupleClosurePat(pat.pat)
        else -> pat
    }

private fun KotlinJ2KEmitter.collectZippedSliceLeaves(
    chainExpr: Expr,
    pat: Pat,
    out: MutableList<ZippedSliceLeaf>,
): Boolean {
    val cleanChain = stripSliceIterMethodCall(chainExpr)
    val cleanPat = unwrapTupleClosurePat(pat)
    if (cleanChain is Expr.MethodCall &&
        cleanChain.method.toString().removePrefix("r#") == "zip" &&
        cleanChain.turbofish == null &&
        cleanChain.args.len() == 1 &&
        cleanPat is Pat.Tuple &&
        cleanPat.elems.len() == 2
    ) {
        val patElems = cleanPat.elems.toList()
        val rhsArg = cleanChain.args.first() ?: return false
        return collectZippedSliceLeaves(cleanChain.receiver, patElems[0], out) &&
            collectZippedSliceLeaves(rhsArg, patElems[1], out)
    }
    return when (cleanPat) {
        is Pat.Ident -> {
            if (cleanPat.subpat != null) return false
            out += ZippedSliceLeaf(cleanChain, cleanPat.ident.toString().removePrefix("r#"), cleanPat.ident)
            true
        }
        is Pat.Wild -> {
            out += ZippedSliceLeaf(cleanChain, null, null)
            true
        }
        else -> false
    }
}

private fun KotlinJ2KEmitter.unwrapSingleExprClosureBody(body: Expr): Expr {
    val unwrapped = unwrapParenOrGroupExpr(body)
    if (unwrapped is Expr.BlockExpr && unwrapped.label == null && unwrapped.block.stmts.size == 1) {
        val single = unwrapped.block.stmts[0]
        if (single is Stmt.ExprStmt && single.semiToken == null) {
            return unwrapSingleExprClosureBody(single.expr)
        }
    }
    return unwrapped
}

private fun KotlinJ2KEmitter.extractDerefPathTargetIdent(lhs: Expr): String? {
    val u = unwrapParenOrGroupExpr(lhs)
    if (u is Expr.Unary && u.op is UnOp.Deref) {
        val inner = unwrapParenOrGroupExpr(u.expr)
        if (inner is Expr.Path && inner.qself == null && inner.path.segments.len() == 1) {
            return inner.path.segments
                .first()
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
        }
    }
    return null
}

private fun KotlinJ2KEmitter.tryRenderZippedForEachLoop(
    expr: Expr.MethodCall,
    indent: String,
    selfTypeName: String?,
): String? {
    val rawFirstArg = expr.args.first() ?: return null
    val closure = unwrapParenOrGroupExpr(rawFirstArg) as? Expr.Closure ?: return null
    val closureInputs = closure.inputs.toList()
    if (closureInputs.size != 1) return null
    val leaves = mutableListOf<ZippedSliceLeaf>()
    if (!collectZippedSliceLeaves(expr.receiver, closureInputs[0], leaves) || leaves.size < 2) {
        return null
    }
    val bodyExpr = unwrapSingleExprClosureBody(closure.body)
    val derefAssignTargetRaw: String? =
        when (bodyExpr) {
            is Expr.Assign -> extractDerefPathTargetIdent(bodyExpr.left)
            is Expr.Binary ->
                if (bodyExpr.op is BinOp.AddAssign ||
                    bodyExpr.op is BinOp.SubAssign ||
                    bodyExpr.op is BinOp.MulAssign ||
                    bodyExpr.op is BinOp.DivAssign ||
                    bodyExpr.op is BinOp.RemAssign ||
                    bodyExpr.op is BinOp.BitAndAssign ||
                    bodyExpr.op is BinOp.BitOrAssign ||
                    bodyExpr.op is BinOp.BitXorAssign
                ) {
                    extractDerefPathTargetIdent(bodyExpr.left)
                } else {
                    null
                }
            else -> null
        }
    val leafIdents = leaves.mapNotNull { it.rawIdent }.toSet()
    val mutatesZippedLeaf = derefAssignTargetRaw != null && derefAssignTargetRaw in leafIdents
    if (leaves.size < 3 && !mutatesZippedLeaf) {
        return null
    }
    val prepLines = mutableListOf<String>()
    val sliceRefs =
        leaves.mapIndexed { idx, leaf ->
            val rendered = renderExpr(leaf.sliceExpr, indent, selfTypeName)
            val isReusablePath =
                leaf.sliceExpr is Expr.Path &&
                    leaf.sliceExpr.qself == null &&
                    leaf.sliceExpr.path.segments
                        .len() == 1
            if (isReusablePath) {
                rendered
            } else {
                val tmp = "zipSlice$idx"
                prepLines += "val $tmp = $rendered"
                tmp
            }
        }
    val idxCandidates = listOf("i", "zipIdx", "zipIndex")
    val idxVar =
        idxCandidates.firstOrNull { cand ->
            cand !in leafIdents && scopeBuilder.resolveLocalKotlinName(cand) == null
        } ?: "zipIdx0"
    val bodyIndent = "$indent    "
    scopeBuilder.enterDef()
    val loopLines =
        try {
            val stmts = mutableListOf<String>()
            var targetSliceRef: String? = null
            for ((idx, leaf) in leaves.withIndex()) {
                val raw = leaf.rawIdent ?: continue
                val patIdent = leaf.patIdent ?: continue
                if (mutatesZippedLeaf && raw == derefAssignTargetRaw) {
                    targetSliceRef = sliceRefs[idx]
                    continue
                }
                val ktLocal =
                    scopeBuilder
                        .bindLocal(
                            rawName = raw,
                            baseKotlinName = rustValueIdentToKotlin(raw),
                            span = patIdent.span(),
                            assignCount = AssignCount.AtMostOnce,
                        ).kotlinName
                stmts += "${bodyIndent}val $ktLocal = ${sliceRefs[idx]}[$idxVar]"
            }
            if (mutatesZippedLeaf && targetSliceRef != null) {
                when (bodyExpr) {
                    is Expr.Assign -> {
                        val rhs = renderExpr(bodyExpr.right, bodyIndent, selfTypeName)
                        stmts += "$bodyIndent$targetSliceRef[$idxVar] = $rhs"
                    }
                    is Expr.Binary -> {
                        val opStr =
                            when (bodyExpr.op) {
                                is BinOp.AddAssign -> "+="
                                is BinOp.SubAssign -> "-="
                                is BinOp.MulAssign -> "*="
                                is BinOp.DivAssign -> "/="
                                is BinOp.RemAssign -> "%="
                                is BinOp.BitAndAssign -> "and"
                                is BinOp.BitOrAssign -> "or"
                                is BinOp.BitXorAssign -> "xor"
                                else -> "="
                            }
                        val rhs = renderExpr(bodyExpr.right, bodyIndent, selfTypeName)
                        if (opStr in setOf("and", "or", "xor")) {
                            stmts += "$bodyIndent$targetSliceRef[$idxVar] = $targetSliceRef[$idxVar] $opStr ($rhs)"
                        } else {
                            stmts += "$bodyIndent$targetSliceRef[$idxVar] $opStr $rhs"
                        }
                    }
                    else -> {}
                }
            } else {
                val renderedBody = renderExpr(closure.body, bodyIndent, selfTypeName)
                stmts += "$bodyIndent$renderedBody"
            }
            stmts
        } finally {
            scopeBuilder.exitDef()
        }
    val sizeArgs = sliceRefs.joinToString(", ") { "$it.size" }
    val forBlock =
        buildString {
            append("for (")
            append(idxVar)
            append(" in 0 until minOf(")
            append(sizeArgs)
            append(")) {\n")
            append(loopLines.joinToString("\n"))
            append('\n')
            append(indent)
            append('}')
        }
    return if (prepLines.isEmpty()) {
        forBlock
    } else {
        buildString {
            append("run {\n")
            for (p in prepLines) {
                append(bodyIndent).append(p).append('\n')
            }
            append(bodyIndent).append(forBlock.replace("\n$indent", "\n$bodyIndent")).append('\n')
            append(indent).append('}')
        }
    }
}

internal fun renderPrimitiveByteSwapExpr(renderedOperand: String, kind: NumericKind): String {
    val isSimpleLocal = renderedOperand.isNotEmpty() && renderedOperand.all { it.isLetterOrDigit() }
    return if (isSimpleLocal) {
        renderPrimitiveByteSwapBody(renderedOperand, kind)
    } else {
        "run { val swapVal0 = ($renderedOperand); ${renderPrimitiveByteSwapBody("swapVal0", kind)} }"
    }
}

private fun renderPrimitiveByteSwapBody(v: String, kind: NumericKind): String =
    when (kind) {
        NumericKind.I8, NumericKind.U8 -> v
        NumericKind.U16 ->
            "((((($v).toUInt() and 0xFFu) shl 8) or ((($v).toUInt() shr 8) and 0xFFu)).toUShort())"
        NumericKind.I16 ->
            "((((($v).toInt() and 0xFF) shl 8) or ((($v).toInt() ushr 8) and 0xFF)).toShort())"
        NumericKind.U32 ->
            "(((($v) shl 24)) or ((($v) and 0x0000FF00u) shl 8) or ((($v) shr 8) and 0x0000FF00u) or (($v) shr 24))"
        NumericKind.I32 ->
            "(((($v) shl 24)) or ((($v) and 0x0000FF00) shl 8) or ((($v) ushr 8) and 0x0000FF00) or (($v) ushr 24))"
        NumericKind.U64 ->
            "(((($v) shl 56)) or ((($v) and 0x000000000000FF00uL) shl 40) or ((($v) and 0x0000000000FF0000uL) shl 24) or ((($v) and 0x00000000FF000000uL) shl 8) or (((($v) shr 8) and 0x00000000FF000000uL)) or (((($v) shr 24) and 0x0000000000FF0000uL)) or (((($v) shr 40) and 0x000000000000FF00uL)) or (($v) shr 56))"
        NumericKind.I64 ->
            "((((($v).toULong() shl 56)) or ((($v).toULong() and 0x000000000000FF00uL) shl 40) or ((($v).toULong() and 0x0000000000FF0000uL) shl 24) or ((($v).toULong() and 0x00000000FF000000uL) shl 8) or (((($v).toULong() shr 8) and 0x00000000FF000000uL)) or (((($v).toULong() shr 24) and 0x0000000000FF0000uL)) or (((($v).toULong() shr 40) and 0x000000000000FF00uL)) or (($v).toULong() shr 56)).toLong())"
        else -> v
    }

internal fun KotlinJ2KEmitter.tryExtractMutableArraySubsliceArg(
    argExpr: Expr,
    argIndex: Int,
    expectMutableList: Boolean,
    indent: String,
    selfTypeName: String?,
): Triple<String, String, String>? {
    if (headerContext.rawAbiMode) return null
    val refExpr = unwrapParenOrGroupExpr(argExpr) as? Expr.Reference ?: return null
    if (refExpr.mutability == null) return null
    val place = unwrapParenOrGroupExpr(refExpr.expr) as? Expr.Index ?: return null
    val rangeExpr = unwrapParenOrGroupExpr(place.index) as? Expr.Range ?: return null
    if (rangeExpr.start == null && rangeExpr.end == null) return null
    val baseTy = inferExprRenderedType(place.expr, selfTypeName)?.removeSuffix("?")?.trim() ?: return null
    val isKotlinArray =
        baseTy in PRIMITIVE_ARRAY_TYPES ||
            baseTy == "CharArray" ||
            baseTy.startsWith("Array<")
    if (!isKotlinArray) return null
    val base = renderExpr(place.expr, indent, selfTypeName)
    val baseAtom = if (isAtomicPostfixReceiver(place.expr, base)) base else "($base)"
    val start = rangeExpr.start?.let { renderTypedNumericInitializer(it, NumericKind.I32, indent, selfTypeName) }
    val end = rangeExpr.end?.let { renderTypedNumericInitializer(it, NumericKind.I32, indent, selfTypeName) }
    val op = if (rangeExpr.limits is RangeLimits.Closed) ".." else " until "
    val rangeStr =
        when {
            start != null && end != null -> "$start$op$end"
            start != null -> "$start until $baseAtom.size"
            else -> "0$op$end"
        }
    val startOffset = start ?: "0"
    val subVar = "subSlice$argIndex"
    return if (expectMutableList) {
        val idxVar = "subIdx$argIndex"
        val destIdx = if (startOffset == "0") idxVar else "($startOffset) + $idxVar"
        Triple(
            "val $subVar = $baseAtom.slice($rangeStr).toMutableList()",
            subVar,
            "for ($idxVar in $subVar.indices) $baseAtom[$destIdx] = $subVar[$idxVar]",
        )
    } else {
        Triple(
            "val $subVar = $baseAtom.sliceArray($rangeStr)",
            subVar,
            "$subVar.copyInto($baseAtom, destinationOffset = $startOffset)",
        )
    }
}

private fun KotlinJ2KEmitter.isMutSliceSynType(ty: SynType): Boolean {
    val ref = unwrapSynType(ty) as? SynType.Reference ?: return false
    return ref.mutability != null && unwrapSynType(ref.elem) is SynType.Slice
}

private fun KotlinJ2KEmitter.resolveTypeAliasOwnerName(ownerKt: String): String {
    var cur = ownerKt
    val seen = hashSetOf<String>()
    while (seen.add(cur) && seen.size <= 8) {
        val targetTy = inspector.typeAliasTargets[cur] ?: break
        val unwrapped = unwrapSynType(targetTy) as? SynType.Path ?: break
        if (unwrapped.qself != null) break
        val lastIdent =
            unwrapped.path.segments
                .toList()
                .lastOrNull()
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
                ?: break
        val next = rustTypeIdentToKotlin(lastIdent)
        if (next == cur) break
        cur = next
    }
    return cur
}

private fun KotlinJ2KEmitter.resolveMethodSignatureForOwner(
    ownerKt: String,
    methodKt: String,
): Pair<String, Signature>? {
    val canonicalOwner = resolveTypeAliasOwnerName(ownerKt)
    val allFilesItems =
        buildList {
            TranslationSession.sharedModuleFile?.items?.let { addAll(it) }
            if (TranslationSession.sharedModuleFile !== file) {
                addAll(file.items)
            }
        }
    val traitByName = linkedMapOf<String, Item.Trait>()
    traitByName.putAll(inspector.declaredTraitsByName)
    for (item in allFilesItems) {
        if (item is Item.Trait && !itemHasDocOnlyCfg(item)) {
            val tName = rustTypeIdentToKotlin(item.ident.toString())
            if (tName !in traitByName) {
                traitByName[tName] = item
            }
        }
    }
    val candidateImplTraits = linkedSetOf<String>()
    for (item in allFilesItems) {
        if (item is Item.Impl && !itemHasDocOnlyCfg(item)) {
            val implOwner = extractDirectOwnerTypeName(item.selfType, item.generics) ?: continue
            if (implOwner != canonicalOwner) continue
            for (implItem in item.items) {
                if (implItem is ImplItem.Fn &&
                    !implItemHasDocOnlyCfg(implItem) &&
                    rustFunctionIdentToKotlin(implItem.sig.ident.toString()) == methodKt
                ) {
                    return canonicalOwner to implItem.sig
                }
            }
            item.traitPath
                ?.path
                ?.segments
                ?.toList()
                ?.lastOrNull()
                ?.ident
                ?.toString()
                ?.let(::rustTypeIdentToKotlin)
                ?.let { candidateImplTraits += it }
        }
    }
    val candidateTraits = linkedSetOf<String>()
    candidateTraits.addAll(candidateImplTraits)
    candidateTraits.addAll(currentTypeParamTraitBounds[ownerKt].orEmpty())
    if (canonicalOwner in traitByName) {
        candidateTraits += canonicalOwner
    }
    for (traitKt in candidateTraits) {
        val traitItem = traitByName[traitKt] ?: continue
        for (tItem in traitItem.items) {
            if (tItem is TraitItem.Fn &&
                !traitItemHasDocOnlyCfg(tItem) &&
                rustFunctionIdentToKotlin(tItem.sig.ident.toString()) == methodKt
            ) {
                return canonicalOwner to tItem.sig
            }
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.resolveCalleeExprParamSynType(
    calleeExpr: Expr,
    argIdx: Int,
    selfTypeName: String?,
): SynType? {
    val pathExpr = unwrapParenOrGroupExpr(calleeExpr) as? Expr.Path ?: return null
    if (pathExpr.qself != null) return null
    val segs = pathExpr.path.segments.toList()
    if (segs.isEmpty()) return null
    val methodKt =
        rustFunctionIdentToKotlin(
            segs
                .last()
                .ident
                .toString()
                .removePrefix("r#"),
        )
    if (segs.size == 1) {
        val allFnItems =
            buildList {
                TranslationSession.sharedModuleFile?.items?.let { addAll(it) }
                if (TranslationSession.sharedModuleFile !== file) {
                    addAll(file.items)
                }
            }
        for (item in allFnItems) {
            if (item is Item.Fn && !itemHasDocOnlyCfg(item) && rustFunctionIdentToKotlin(item.sig.ident.toString()) == methodKt) {
                val typedParams =
                    item.sig.inputs
                        .toList()
                        .filterIsInstance<FnArg.Typed>()
                typedParams.getOrNull(argIdx)?.let { return it.patType.ty }
            }
        }
        return null
    }
    val penultRaw = segs[segs.size - 2].ident.toString().removePrefix("r#")
    val ownerKt =
        when {
            penultRaw == "Self" -> selfTypeName?.substringBefore('<')?.trim()
            penultRaw.isNotEmpty() && penultRaw.first().isUpperCase() -> rustTypeIdentToKotlin(penultRaw)
            else -> null
        } ?: return null
    val (_, sig) = resolveMethodSignatureForOwner(ownerKt, methodKt) ?: return null
    val hasReceiver = sig.inputs.firstOrNull() is FnArg.Receiver
    val typedIdx = if (hasReceiver) argIdx - 1 else argIdx
    if (typedIdx < 0) return null
    val typedParams = sig.inputs.toList().filterIsInstance<FnArg.Typed>()
    return typedParams.getOrNull(typedIdx)?.patType?.ty
}

internal fun KotlinJ2KEmitter.inferUntypedArrayLocalTargetTypeFromRemainingStmts(
    pat: Pat,
    remainingStmts: List<Stmt>,
    selfTypeName: String?,
): String? {
    val identPat = unwrapTransparentPat(pat) as? Pat.Ident ?: return null
    val raw = identPat.ident.toString().removePrefix("r#")

    fun unwrapBorrowedSlicePlace(expr: Expr): Expr {
        var cur = unwrapParenOrGroupExpr(expr)
        while (true) {
            cur =
                when (cur) {
                    is Expr.Reference -> unwrapParenOrGroupExpr(cur.expr)
                    is Expr.Index -> {
                        val range = unwrapParenOrGroupExpr(cur.index) as? Expr.Range
                        if (range != null && range.start == null && range.end == null) {
                            unwrapParenOrGroupExpr(cur.expr)
                        } else {
                            return cur
                        }
                    }
                    else -> return cur
                }
        }
    }

    var inferredTarget: String? = null
    val visitor =
        object : Visit() {
            override fun visitExprCall(e: Expr.Call) {
                if (inferredTarget != null) return
                val argsList = e.args.toList()
                for ((argIdx, argExpr) in argsList.withIndex()) {
                    val basePlace = unwrapBorrowedSlicePlace(argExpr) as? Expr.Path ?: continue
                    if (basePlace.qself != null || !basePlace.path.isIdent(raw)) continue
                    val paramTy = resolveCalleeExprParamSynType(e.func, argIdx, selfTypeName) ?: continue
                    val rendered = renderType(paramTy, selfTypeName).removeSuffix("?").trim()
                    if (rendered.startsWith("List<") ||
                        rendered.startsWith("MutableList<") ||
                        rendered in PRIMITIVE_ARRAY_TYPES
                    ) {
                        inferredTarget = rendered
                        return
                    }
                }
                super.visitExprCall(e)
            }
        }
    for (stmt in remainingStmts) {
        if (inferredTarget != null) break
        visitor.visitStmt(stmt)
    }
    return inferredTarget
}

internal fun KotlinJ2KEmitter.calleeExpectsMutableListSliceParam(calleeBase: String, argIdx: Int): Boolean {
    val simpleFn = calleeBase.substringAfterLast('.').trim()
    val ownerPrefix = calleeBase.substringBeforeLast('.', missingDelimiterValue = "").substringAfterLast('.').trim()
    if (ownerPrefix.isEmpty()) {
        val allFnItems =
            buildList {
                TranslationSession.sharedModuleFile?.items?.let { addAll(it) }
                if (TranslationSession.sharedModuleFile !== file) {
                    addAll(file.items)
                }
            }
        for (item in allFnItems) {
            if (item is Item.Fn && !itemHasDocOnlyCfg(item) && rustFunctionIdentToKotlin(item.sig.ident.toString()) == simpleFn) {
                val typedParams =
                    item.sig.inputs
                        .toList()
                        .filterIsInstance<FnArg.Typed>()
                val param = typedParams.getOrNull(argIdx) ?: continue
                if (isMutSliceSynType(param.patType.ty)) return true
            }
        }
        return false
    }
    resolveMethodSignatureForOwner(ownerPrefix, simpleFn)?.let { (_, sig) ->
        val hasReceiver = sig.inputs.firstOrNull() is FnArg.Receiver
        val typedIdx = if (hasReceiver) argIdx - 1 else argIdx
        if (typedIdx >= 0) {
            val param =
                sig.inputs
                    .toList()
                    .filterIsInstance<FnArg.Typed>()
                    .getOrNull(typedIdx)
            if (param != null && isMutSliceSynType(param.patType.ty)) return true
        }
    }
    return methodExpectsMutableListSliceParam(ownerPrefix, simpleFn, argIdx, includeStaticOnly = true)
}

internal fun KotlinJ2KEmitter.methodExpectsMutableListSliceParam(
    ownerSimple: String?,
    methodKt: String,
    argIdx: Int,
    includeStaticOnly: Boolean = false,
): Boolean {
    for (item in file.items) {
        when (item) {
            is Item.Impl -> {
                val implOwner = renderType(item.selfType, null).substringBefore('<').substringAfterLast('.').trim()
                if (ownerSimple != null && implOwner != ownerSimple) continue
                for (implItem in item.items) {
                    if (implItem is ImplItem.Fn && rustFunctionIdentToKotlin(implItem.sig.ident.toString()) == methodKt) {
                        val hasReceiver = implItem.sig.inputs.firstOrNull() is FnArg.Receiver
                        if (includeStaticOnly && hasReceiver) continue
                        val typedParams =
                            implItem.sig.inputs
                                .toList()
                                .filterIsInstance<FnArg.Typed>()
                        val param = typedParams.getOrNull(argIdx) ?: continue
                        if (isMutSliceSynType(param.patType.ty)) return true
                    }
                }
            }
            is Item.Trait -> {
                val traitName = rustTypeIdentToKotlin(item.ident.toString())
                if (ownerSimple != null && traitName != ownerSimple) continue
                for (tItem in item.items) {
                    if (tItem is TraitItem.Fn && rustFunctionIdentToKotlin(tItem.sig.ident.toString()) == methodKt) {
                        val hasReceiver = tItem.sig.inputs.firstOrNull() is FnArg.Receiver
                        if (includeStaticOnly && hasReceiver) continue
                        val typedParams =
                            tItem.sig.inputs
                                .toList()
                                .filterIsInstance<FnArg.Typed>()
                        val param = typedParams.getOrNull(argIdx) ?: continue
                        if (isMutSliceSynType(param.patType.ty)) return true
                    }
                }
            }
            else -> {}
        }
    }
    return false
}

private fun KotlinJ2KEmitter.primitiveArrayTypeForElementRenderedType(elemTy: String?): String? =
    when (elemTy?.trim()) {
        "Boolean" -> "BooleanArray"
        "Byte" -> "ByteArray"
        "UByte" -> "UByteArray"
        "Short" -> "ShortArray"
        "UShort" -> "UShortArray"
        "Int" -> "IntArray"
        "UInt" -> "UIntArray"
        "Long" -> "LongArray"
        "ULong" -> "ULongArray"
        "Float", "kotlin.Float" -> "FloatArray"
        "Double" -> "DoubleArray"
        else -> null
    }

private fun KotlinJ2KEmitter.inferExplicitArrayElementRenderedType(
    elem: Expr,
    selfTypeName: String?,
): String? {
    val unwrapped = unwrapParenOrGroupExpr(elem)
    val lit = (unwrapped as? Expr.Lit)?.lit
    if (lit is Lit.Bool) return "Boolean"
    if (lit is Lit.Byte) return "UByte"
    if (lit is Lit.Float) {
        return when (lit.value.suffix()) {
            "f32" -> kotlinF32TypeName()
            "f64" -> "Double"
            else -> null
        }
    }
    return inferExprRenderedType(elem, selfTypeName)
        ?: inferExprNumericKind(elem, selfTypeName)?.let(this::numericKindToPureTypeName)
}

private fun KotlinJ2KEmitter.inferPrimitiveArrayTypeForArrayLiteral(
    expr: Expr,
    selfTypeName: String?,
): String? {
    val sampleElems =
        when (expr) {
            is Expr.Repeat -> listOf(expr.expr)
            is Expr.Array -> expr.elems.toList()
            else -> return null
        }
    for (elem in sampleElems) {
        val elemTy = inferExplicitArrayElementRenderedType(elem, selfTypeName)
        primitiveArrayTypeForElementRenderedType(elemTy)?.let { return it }
    }
    return null
}

private fun KotlinJ2KEmitter.unwrapTryIntoUnwrapExpr(expr: Expr): Expr? {
    val outer = unwrapParenOrGroupExpr(expr) as? Expr.MethodCall ?: return null
    val outerMethod = outer.method.toString().removePrefix("r#")
    val outerArgsOk =
        (outerMethod == "unwrap" && outer.args.isEmpty()) ||
            (outerMethod == "expect" && outer.args.toList().size == 1)
    if (!outerArgsOk) return null
    val inner = unwrapParenOrGroupExpr(outer.receiver) as? Expr.MethodCall ?: return null
    val innerMethod = inner.method.toString().removePrefix("r#")
    if (innerMethod != "try_into" || !inner.args.isEmpty()) return null
    return inner.receiver
}

private fun KotlinJ2KEmitter.primitiveArrayToMethodName(arrayType: String): String? =
    when (arrayType) {
        "BooleanArray" -> "toBooleanArray"
        "ByteArray" -> "toByteArray"
        "UByteArray" -> "toUByteArray"
        "ShortArray" -> "toShortArray"
        "UShortArray" -> "toUShortArray"
        "IntArray" -> "toIntArray"
        "UIntArray" -> "toUIntArray"
        "LongArray" -> "toLongArray"
        "ULongArray" -> "toULongArray"
        "FloatArray" -> "toFloatArray"
        "DoubleArray" -> "toDoubleArray"
        else -> null
    }

private fun KotlinJ2KEmitter.renderPrimitiveArrayTryIntoConversion(
    innerExpr: Expr,
    targetArrayType: String,
    indent: String,
    selfTypeName: String?,
): String {
    val innerTy = inferExprRenderedType(innerExpr, selfTypeName)?.removeSuffix("?")?.trim()
    val rendered = renderExpr(innerExpr, indent, selfTypeName)
    if (innerTy == targetArrayType) return rendered
    val convFn = primitiveArrayToMethodName(targetArrayType) ?: return rendered
    val atom = if (isAtomicPostfixReceiver(innerExpr, rendered)) rendered else "($rendered)"
    return "$atom.$convFn()"
}

private fun KotlinJ2KEmitter.renderCopyFromSliceCall(
    receiverExpr: Expr,
    rawArgExpr: Expr,
    indent: String,
    selfTypeName: String?,
): String? {
    val unwrappedArg = unwrapParenOrGroupExpr(rawArgExpr)
    val srcExpr = (unwrappedArg as? Expr.Reference)?.let { unwrapParenOrGroupExpr(it.expr) } ?: unwrappedArg
    val recvUnwrapped =
        unwrapParenOrGroupExpr(receiverExpr).let { node ->
            (node as? Expr.Reference)?.let { unwrapParenOrGroupExpr(it.expr) } ?: node
        }
    val rangePlace = recvUnwrapped as? Expr.Index
    val rangeExpr = rangePlace?.let { unwrapParenOrGroupExpr(it.index) as? Expr.Range }
    val dstExpr = if (rangeExpr != null) rangePlace.expr else recvUnwrapped
    val dstTy = inferExprRenderedType(dstExpr, selfTypeName)?.removeSuffix("?")?.trim()
    val dstIsPrimArray = dstTy in PRIMITIVE_ARRAY_TYPES
    val dstIsListLike = dstTy?.startsWith("MutableList<") == true || dstTy?.startsWith("List<") == true
    val dstRendered = renderExpr(dstExpr, indent, selfTypeName)
    val dstAtom = if (isAtomicPostfixReceiver(dstExpr, dstRendered)) dstRendered else "($dstRendered)"
    val prevParseTarget = currentExpectedParseTargetType
    if (dstIsPrimArray) {
        currentExpectedParseTargetType = dstTy
    }
    val srcRendered =
        try {
            renderExpr(srcExpr, indent, selfTypeName)
        } finally {
            currentExpectedParseTargetType = prevParseTarget
        }
    val srcTy =
        inferExprRenderedType(srcExpr, selfTypeName)?.removeSuffix("?")?.trim()
            ?: if (dstIsPrimArray && unwrapParenOrGroupExpr(srcExpr).let { it is Expr.Array || it is Expr.Repeat }) {
                dstTy
            } else {
                null
            }
    val srcIsPrimArray = srcTy in PRIMITIVE_ARRAY_TYPES
    val srcAtom = if (isAtomicPostfixReceiver(srcExpr, srcRendered)) srcRendered else "($srcRendered)"
    if (rangeExpr != null) {
        val startOffset = rangeExpr.start?.let { renderTypedNumericInitializer(it, NumericKind.I32, indent, selfTypeName) } ?: "0"
        if (dstIsPrimArray && srcIsPrimArray) {
            return "$srcAtom.copyInto($dstAtom, destinationOffset = $startOffset)"
        }
        val suffix = closureLabelCounter++
        val dstVar = "dstTmp$suffix"
        val srcVar = "srcTmp$suffix"
        val idxVar = "idxTmp$suffix"
        val destIdx = if (startOffset == "0") idxVar else "($startOffset) + $idxVar"
        return "run { val $dstVar = $dstAtom; val $srcVar = $srcAtom; for ($idxVar in $srcVar.indices) $dstVar[$destIdx] = $srcVar[$idxVar] }"
    }
    if (dstIsPrimArray && srcIsPrimArray) {
        return "$srcAtom.copyInto($dstAtom)"
    }
    if (dstIsPrimArray || dstIsListLike) {
        val suffix = closureLabelCounter++
        val dstVar = "dstTmp$suffix"
        val srcVar = "srcTmp$suffix"
        val idxVar = "idxTmp$suffix"
        return "run { val $dstVar = $dstAtom; val $srcVar = $srcAtom; for ($idxVar in $srcVar.indices) $dstVar[$idxVar] = $srcVar[$idxVar] }"
    }
    return null
}

private fun KotlinJ2KEmitter.resolvePrimitiveFromBytesSpec(expr: Expr.Call): Triple<String, String, Boolean>? {
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
            "u128", "U128" -> "U128"
            "i128", "I128" -> "I128"
            "usize" ->
                if (headerContext.rawAbiMode || inExternAbiSignature) {
                    when (activeCfgProfile.pointerWidth) {
                        16 -> "UShort"
                        32 -> "UInt"
                        else -> "ULong"
                    }
                } else {
                    "Int"
                }
            "isize" ->
                if (headerContext.rawAbiMode || inExternAbiSignature) {
                    when (activeCfgProfile.pointerWidth) {
                        16 -> "Short"
                        32 -> "Int"
                        else -> "Long"
                    }
                } else {
                    "Int"
                }
            else -> return null
        }
    return Triple(primRaw, ktReturnType, isBigEndian)
}

private fun KotlinJ2KEmitter.renderPrimitiveFromBytesCall(
    expr: Expr.Call,
    argExpr: Expr,
    indent: String,
    selfTypeName: String?,
): String? {
    val (primRaw, _, isBigEndian) = resolvePrimitiveFromBytesSpec(expr) ?: return null
    val savedParseTarget = currentExpectedParseTargetType
    if (primRaw in setOf("u128", "U128", "i128", "I128", "f16", "F16")) {
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
        when (primRaw) {
            "usize" ->
                if (headerContext.rawAbiMode || inExternAbiSignature) {
                    when (activeCfgProfile.pointerWidth) {
                        16 -> "u16"
                        32 -> "u32"
                        else -> "u64"
                    }
                } else {
                    "ptr32size"
                }
            "isize" ->
                if (headerContext.rawAbiMode || inExternAbiSignature) {
                    when (activeCfgProfile.pointerWidth) {
                        16 -> "i16"
                        32 -> "i32"
                        else -> "i64"
                    }
                } else {
                    "ptr32size"
                }
            else -> primRaw
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
        "u64" -> {
            val idx = if (isBigEndian) (0..7).toList() else (7 downTo 0).toList()
            val shifts = listOf(56, 48, 40, 32, 24, 16, 8, 0)
            val terms =
                idx.zip(shifts).joinToString(" or ") { (i, s) ->
                    if (s == 0) "$tmp[$i].toULong()" else "($tmp[$i].toULong() shl $s)"
                }
            "run { val $tmp = $bytesRendered; ($terms) }"
        }
        "i64", "f64" -> {
            val idx = if (isBigEndian) (0..7).toList() else (7 downTo 0).toList()
            val shifts = listOf(56, 48, 40, 32, 24, 16, 8, 0)
            val terms =
                idx.zip(shifts).joinToString(" or ") { (i, s) ->
                    if (s == 0) "$tmp[$i].toLong()" else "($tmp[$i].toLong() shl $s)"
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

private fun KotlinJ2KEmitter.unwrapPtrCastChainToInnerRef(
    expr: Expr,
    selfTypeName: String?,
): Expr? {
    var cur = unwrapParenOrGroupExpr(expr)
    var ptrCastCount = 0
    var innermostPtrElem: SynType? = null
    while (cur is Expr.Cast) {
        val castTy = unwrapSynType(cur.ty)
        if (castTy !is SynType.Ptr) return null
        ptrCastCount++
        innermostPtrElem = unwrapSynType(castTy.elem)
        cur = unwrapParenOrGroupExpr(cur.expr)
    }
    (cur as? Expr.Reference)?.let { return unwrapParenOrGroupExpr(it.expr) }
    if (ptrCastCount >= 2 &&
        innermostPtrElem != null &&
        rawPointerPointeeType(inferExprRenderedType(cur, selfTypeName)) == null
    ) {
        val innermostKind = resolveNumericKind(renderType(innermostPtrElem, selfTypeName))
        val curKind = inferExprNumericKind(cur, selfTypeName)
        if (innermostKind != null && curKind == innermostKind) {
            return cur
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.renderUnOpDerefPointerPunning(
    expr: Expr.Unary,
    indent: String,
    selfTypeName: String?,
): String? {
    val outerCast = unwrapParenOrGroupExpr(expr.expr) as? Expr.Cast ?: return null
    val dstPtr = unwrapSynType(outerCast.ty) as? SynType.Ptr ?: return null
    val dstPointee = unwrapSynType(dstPtr.elem)
    val srcInner = unwrapPtrCastChainToInnerRef(outerCast, selfTypeName) ?: return null
    if (dstPointee is SynType.Array) {
        val elemPath = unwrapSynType(dstPointee.elem) as? SynType.Path ?: return null
        val elemRaw =
            elemPath.path.segments
                .last()
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
        if ((elemRaw == "u8" || elemRaw == "c_uchar") && "core" in headerContext.crateNamespaces) {
            val srcKind = inferExprNumericKind(srcInner, selfTypeName)
            if (srcKind != null) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.rustToNeBytes")
                val rval = renderExpr(srcInner, indent, selfTypeName)
                return "rustToNeBytes($rval)"
            }
        }
        return null
    }
    val dstKind = resolveNumericKind(renderType(dstPointee, selfTypeName)) ?: return null
    val srcKind = inferExprNumericKind(srcInner, selfTypeName) ?: return null
    val rval = renderExpr(srcInner, indent, selfTypeName)
    val atom = if (isAtomicPostfixReceiver(srcInner, rval)) rval else "($rval)"
    return when {
        srcKind == dstKind -> rval
        srcKind == NumericKind.F32 && dstKind == NumericKind.U32 -> "$atom.toRawBits().toUInt()"
        srcKind == NumericKind.F32 && dstKind == NumericKind.I32 -> "$atom.toRawBits()"
        srcKind == NumericKind.F64 && dstKind == NumericKind.U64 -> "$atom.toRawBits().toULong()"
        srcKind == NumericKind.F64 && dstKind == NumericKind.I64 -> "$atom.toRawBits()"
        srcKind == NumericKind.U32 && dstKind == NumericKind.F32 -> "${kotlinF32TypeName()}.fromBits($atom.toInt())"
        srcKind == NumericKind.I32 && dstKind == NumericKind.F32 -> "${kotlinF32TypeName()}.fromBits($rval)"
        srcKind == NumericKind.U64 && dstKind == NumericKind.F64 -> "Double.fromBits($atom.toLong())"
        srcKind == NumericKind.I64 && dstKind == NumericKind.F64 -> "Double.fromBits($rval)"
        else -> null
    }
}

private data class StaticPrimitiveCallSpec(
    val primKind: NumericKind,
    val methodName: String,
    val turbofishType: SynType?,
)

private fun KotlinJ2KEmitter.rustPrimitivePathNameToKind(raw: String): NumericKind? =
    when (raw.removePrefix("r#")) {
        "i8" -> NumericKind.I8
        "u8" -> NumericKind.U8
        "i16" -> NumericKind.I16
        "u16" -> NumericKind.U16
        "i32" -> NumericKind.I32
        "u32" -> NumericKind.U32
        "i64" -> NumericKind.I64
        "u64" -> NumericKind.U64
        "i128" -> NumericKind.I128
        "u128" -> NumericKind.U128
        "f32" -> NumericKind.F32
        "f64" -> NumericKind.F64
        "isize" ->
            if (headerContext.rawAbiMode || inExternAbiSignature) {
                when (activeCfgProfile.pointerWidth) {
                    16 -> NumericKind.I16
                    32 -> NumericKind.I32
                    else -> NumericKind.I64
                }
            } else {
                NumericKind.I32
            }
        "usize" ->
            if (headerContext.rawAbiMode || inExternAbiSignature) {
                when (activeCfgProfile.pointerWidth) {
                    16 -> NumericKind.U16
                    32 -> NumericKind.U32
                    else -> NumericKind.U64
                }
            } else {
                NumericKind.I32
            }
        else -> null
    }

internal fun KotlinJ2KEmitter.resolvePrimitiveAssociatedConstKind(
    path: Path,
    qself: QSelf?,
): NumericKind? {
    val segs = path.segments.toList().map { it.ident.toString().removePrefix("r#") }
    if (segs.isEmpty()) return null
    val allowedPrefixes = IGNORED_PATH_PREFIXES + setOf("core", "std", "primitive")
    val primKind =
        when {
            qself != null -> {
                if (segs.size != 1) return null
                val qPath = unwrapSynType(qself.ty) as? SynType.Path ?: return null
                val qSegs =
                    qPath.path.segments
                        .toList()
                        .map { it.ident.toString().removePrefix("r#") }
                if (qSegs.isEmpty() || !qSegs.dropLast(1).all { it in allowedPrefixes }) return null
                rustPrimitivePathNameToKind(qSegs.last())
            }
            segs.size >= 3 && segs[segs.size - 2] == "consts" && segs.dropLast(3).all { it in allowedPrefixes } ->
                rustPrimitivePathNameToKind(segs[segs.size - 3])?.takeIf {
                    it == NumericKind.F32 || it == NumericKind.F64
                }
            segs.size >= 2 && segs.dropLast(2).all { it in allowedPrefixes } ->
                rustPrimitivePathNameToKind(segs[segs.size - 2])
            else -> null
        } ?: return null
    return when (segs.last()) {
        "BITS", "RADIX", "MANTISSA_DIGITS", "DIGITS" -> NumericKind.U32
        "MIN_EXP", "MAX_EXP", "MIN_10_EXP", "MAX_10_EXP" -> NumericKind.I32
        else -> primKind
    }
}

private fun KotlinJ2KEmitter.parseStaticPrimitiveCallSpec(expr: Expr.Call): StaticPrimitiveCallSpec? {
    val fnPath = expr.func as? Expr.Path ?: return null
    val segs = fnPath.path.segments.toList()
    if (segs.isEmpty()) return null
    val allowedPrefixes = IGNORED_PATH_PREFIXES + setOf("core", "std", "primitive")
    val turbofishType =
        (segs.last().arguments as? PathArguments.AngleBracketed)
            ?.args
            ?.toList()
            ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
    val rawSegs = segs.map { it.ident.toString().removePrefix("r#") }
    val methodName = rawSegs.last()
    val primKind =
        if (fnPath.qself != null) {
            if (rawSegs.size != 1) return null
            val qPath = unwrapSynType(fnPath.qself!!.ty) as? SynType.Path ?: return null
            val qSegs =
                qPath.path.segments
                    .toList()
                    .map { it.ident.toString().removePrefix("r#") }
            if (qSegs.isEmpty() || !qSegs.dropLast(1).all { it in allowedPrefixes }) return null
            rustPrimitivePathNameToKind(qSegs.last()) ?: return null
        } else {
            if (rawSegs.size < 2 || !rawSegs.dropLast(2).all { it in allowedPrefixes }) return null
            rustPrimitivePathNameToKind(rawSegs[rawSegs.size - 2]) ?: return null
        }
    return StaticPrimitiveCallSpec(primKind = primKind, methodName = methodName, turbofishType = turbofishType)
}

private fun KotlinJ2KEmitter.inferPrimitiveOpReturnRenderedType(
    recvKind: NumericKind,
    rawMethod: String,
    turbofishType: SynType?,
    selfTypeName: String?,
): String? {
    val isCoreAvailable = "core" in headerContext.crateNamespaces
    if (!isCoreAvailable) {
        return if (recvKind == NumericKind.I32 &&
            rawMethod in setOf("checked_add", "checked_sub", "checked_mul") &&
            turbofishType == null
        ) {
            "Int?"
        } else {
            null
        }
    }
    val primTy = numericKindToPureTypeName(recvKind)
    val isFloat = recvKind == NumericKind.F32 || recvKind == NumericKind.F64
    return when {
        !isFloat &&
            rawMethod in
            setOf(
                "checked_add",
                "checked_sub",
                "checked_mul",
                "checked_div",
                "checked_rem",
                "checked_div_euclid",
                "checked_rem_euclid",
                "checked_shl",
                "checked_shr",
                "checked_pow",
                "checked_neg",
                "checked_abs",
            ) -> "$primTy?"
        !isFloat &&
            rawMethod in
            setOf(
                "overflowing_add",
                "overflowing_sub",
                "overflowing_mul",
                "overflowing_shl",
                "overflowing_shr",
                "overflowing_neg",
                "overflowing_abs",
            ) -> "OverflowingResult<$primTy>"
        !isFloat &&
            rawMethod in
            setOf(
                "wrapping_add",
                "wrapping_sub",
                "wrapping_mul",
                "wrapping_div",
                "wrapping_rem",
                "wrapping_shl",
                "wrapping_shr",
                "wrapping_neg",
                "wrapping_abs",
                "saturating_add",
                "saturating_sub",
                "saturating_mul",
                "saturating_neg",
                "saturating_abs",
                "div_euclid",
                "rem_euclid",
                "pow",
                "reverse_bits",
                "rotate_left",
                "rotate_right",
            ) -> primTy
        !isFloat &&
            rawMethod in
            setOf(
                "count_ones",
                "count_zeros",
                "leading_zeros",
                "trailing_zeros",
                "leading_ones",
                "trailing_ones",
            ) -> "UInt"
        isFloat &&
            rawMethod in
            setOf(
                "is_nan",
                "is_infinite",
                "is_finite",
                "is_normal",
                "is_subnormal",
                "is_sign_positive",
                "is_sign_negative",
            ) -> "Boolean"
        isFloat && rawMethod == "classify" -> "FpCategory"
        isFloat && rawMethod == "to_int_unchecked" ->
            turbofishType?.let { renderType(it, selfTypeName) }
        isFloat &&
            rawMethod in
            setOf(
                "abs",
                "floor",
                "ceil",
                "round",
                "trunc",
                "sqrt",
                "exp",
                "exp2",
                "ln",
                "log",
                "log2",
                "log10",
                "cbrt",
                "sin",
                "cos",
                "tan",
                "asin",
                "acos",
                "atan",
                "atan2",
                "hypot",
                "sinh",
                "cosh",
                "tanh",
                "asinh",
                "acosh",
                "atanh",
                "powf",
                "powi",
                "min",
                "max",
                "fract",
                "mul_add",
                "recip",
                "to_degrees",
                "to_radians",
                "signum",
            ) -> primTy
        else -> null
    }
}

internal fun KotlinJ2KEmitter.renderStaticPrimitiveCall(
    expr: Expr.Call,
    nonUnitArgs: List<Expr>,
    indent: String,
    selfTypeName: String?,
): String? {
    val spec = parseStaticPrimitiveCallSpec(expr) ?: return null
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    if (spec.methodName == "from_bits" && spec.turbofishType == null && nonUnitArgs.size == 1) {
        if (spec.primKind == NumericKind.F32) {
            val saved = currentExpectedNumericKind
            currentExpectedNumericKind = NumericKind.U32
            val argRendered =
                try {
                    renderExpr(nonUnitArgs[0], indent, selfTypeName)
                } finally {
                    currentExpectedNumericKind = saved
                }
            val bitsExpr =
                if (inferExprNumericKind(nonUnitArgs[0], selfTypeName) == NumericKind.I32) {
                    argRendered
                } else {
                    coerceRenderedNumericExpr(nonUnitArgs[0], argRendered, NumericKind.I32)
                }
            return "${kotlinF32TypeName()}.fromBits($bitsExpr)"
        }
        if (spec.primKind == NumericKind.F64) {
            val saved = currentExpectedNumericKind
            currentExpectedNumericKind = NumericKind.U64
            val argRendered =
                try {
                    renderExpr(nonUnitArgs[0], indent, selfTypeName)
                } finally {
                    currentExpectedNumericKind = saved
                }
            val bitsExpr =
                if (inferExprNumericKind(nonUnitArgs[0], selfTypeName) == NumericKind.I64) {
                    argRendered
                } else {
                    coerceRenderedNumericExpr(nonUnitArgs[0], argRendered, NumericKind.I64)
                }
            return "Double.fromBits($bitsExpr)"
        }
    }
    if (spec.methodName == "from_str_radix" &&
        spec.turbofishType == null &&
        nonUnitArgs.size == 2 &&
        "core" in headerContext.crateNamespaces
    ) {
        val fnName =
            when (spec.primKind) {
                NumericKind.I8 -> "rustFromStrRadixByte"
                NumericKind.I16 -> "rustFromStrRadixShort"
                NumericKind.I32 -> "rustFromStrRadixInt"
                NumericKind.I64 -> "rustFromStrRadixLong"
                NumericKind.U8 -> "rustFromStrRadixUByte"
                NumericKind.U16 -> "rustFromStrRadixUShort"
                NumericKind.U32 -> "rustFromStrRadixUInt"
                NumericKind.U64 -> "rustFromStrRadixULong"
                NumericKind.I128 -> "rustFromStrRadixI128"
                NumericKind.U128 -> "rustFromStrRadixU128"
                else -> return null
            }
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.$fnName")
        usesSourceResult = true
        val savedKind = currentExpectedNumericKind
        val savedParse = currentExpectedParseTargetType
        val srcRendered =
            try {
                currentExpectedNumericKind = null
                currentExpectedParseTargetType = "String"
                renderExpr(nonUnitArgs[0], indent, selfTypeName)
            } finally {
                currentExpectedNumericKind = savedKind
                currentExpectedParseTargetType = savedParse
            }
        val radixRendered =
            try {
                currentExpectedNumericKind = NumericKind.U32
                currentExpectedParseTargetType = null
                renderExpr(nonUnitArgs[1], indent, selfTypeName)
            } finally {
                currentExpectedNumericKind = savedKind
                currentExpectedParseTargetType = savedParse
            }
        val radixArg = coerceNumericArgIfNeeded(nonUnitArgs[1], radixRendered, NumericKind.U32, selfTypeName)
        return "$fnName($srcRendered, $radixArg)"
    }
    if (nonUnitArgs.isEmpty()) return null
    val savedKind = currentExpectedNumericKind
    val recvRendered =
        try {
            currentExpectedNumericKind = spec.primKind
            renderExpr(nonUnitArgs[0], indent, selfTypeName)
        } finally {
            currentExpectedNumericKind = savedKind
        }
    val extraExprs = nonUnitArgs.drop(1)
    val extraExpectedKind =
        when {
            spec.methodName in setOf("wrapping_shl", "wrapping_shr", "checked_shl", "checked_shr", "overflowing_shl", "overflowing_shr", "rotate_left", "rotate_right", "pow", "checked_pow") ->
                NumericKind.U32
            spec.methodName == "powi" -> NumericKind.I32
            else -> spec.primKind
        }
    val extraRendered =
        try {
            currentExpectedNumericKind = extraExpectedKind
            extraExprs.map { renderExpr(it, indent, selfTypeName) }
        } finally {
            currentExpectedNumericKind = savedKind
        }
    if (spec.methodName == "to_bits" && spec.turbofishType == null && extraExprs.isEmpty()) {
        val recvAtom = if (isAtomicPostfixReceiver(nonUnitArgs[0], recvRendered)) recvRendered else "($recvRendered)"
        if (spec.primKind == NumericKind.F32) return "$recvAtom.toRawBits().toUInt()"
        if (spec.primKind == NumericKind.F64) return "$recvAtom.toRawBits().toULong()"
    }
    if (spec.methodName in setOf("to_be", "from_be", "swap_bytes", "to_le", "from_le") &&
        spec.turbofishType == null &&
        extraExprs.isEmpty() &&
        (spec.primKind == NumericKind.U128 || spec.primKind == NumericKind.I128) &&
        "core" in headerContext.crateNamespaces
    ) {
        val fnName =
            when (spec.methodName) {
                "to_be" -> "rustToBe"
                "from_be" -> "rustFromBe"
                "swap_bytes" -> "rustSwapBytes"
                "to_le" -> "rustToLe"
                else -> "rustFromLe"
            }
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.$fnName")
        return "$fnName($recvRendered)"
    }
    return renderConcretePrimitiveOp(
        recvKind = spec.primKind,
        rawMethod = spec.methodName,
        recvExpr = nonUnitArgs[0],
        renderedRecv = recvRendered,
        extraExprs = extraExprs,
        renderedExtras = extraRendered,
        turbofishType = spec.turbofishType,
        selfTypeName = selfTypeName,
    )
}

internal fun KotlinJ2KEmitter.renderConcreteNumericMethodCall(
    expr: Expr.MethodCall,
    receiver: String,
    rawMethod: String,
    nonUnitArgs: List<Expr>,
    renderedValueArgs: List<String>,
    typeArgs: String,
    indent: String,
    selfTypeName: String?,
): String? {
    val recvKind = inferExprNumericKind(expr.receiver, selfTypeName) ?: return null
    val tfType =
        expr.turbofish
            ?.args
            ?.toList()
            ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
    if (typeArgs.isNotEmpty() && rawMethod != "to_int_unchecked") return null
    return renderConcretePrimitiveOp(
        recvKind = recvKind,
        rawMethod = rawMethod,
        recvExpr = expr.receiver,
        renderedRecv = receiver,
        extraExprs = nonUnitArgs,
        renderedExtras = renderedValueArgs,
        turbofishType = tfType,
        selfTypeName = selfTypeName,
    )
}

private fun KotlinJ2KEmitter.renderConcretePrimitiveOp(
    recvKind: NumericKind,
    rawMethod: String,
    recvExpr: Expr,
    renderedRecv: String,
    extraExprs: List<Expr>,
    renderedExtras: List<String>,
    turbofishType: SynType?,
    selfTypeName: String?,
): String? {
    val isCoreAvailable = "core" in headerContext.crateNamespaces
    if (!isCoreAvailable) {
        if (recvKind == NumericKind.I32 &&
            rawMethod in setOf("checked_add", "checked_sub", "checked_mul") &&
            extraExprs.size == 1 &&
            renderedExtras.size == 1 &&
            turbofishType == null
        ) {
            val opSym =
                when (rawMethod) {
                    "checked_add" -> "+"
                    "checked_sub" -> "-"
                    else -> "*"
                }
            return "(($renderedRecv).toLong() $opSym (${renderedExtras[0]}).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()"
        }
        return null
    }
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val isFloat = recvKind == NumericKind.F32 || recvKind == NumericKind.F64
    val recvAtom = if (isAtomicPostfixReceiver(recvExpr, renderedRecv)) renderedRecv else "($renderedRecv)"
    if (isFloat) {
        val isF32 = recvKind == NumericKind.F32
        if (extraExprs.isEmpty() && rawMethod == "to_int_unchecked") {
            val dstType = turbofishType?.let { renderType(it, selfTypeName) } ?: currentExpectedParseTargetType ?: currentEnclosingReturnRenderedType
            val dstKind = dstType?.let(this::resolveNumericKind) ?: return null
            return when (dstKind) {
                NumericKind.I8 -> "$recvAtom.toInt().toByte()"
                NumericKind.I16 -> "$recvAtom.toInt().toShort()"
                NumericKind.I32 -> "$recvAtom.toInt()"
                NumericKind.I64 -> "$recvAtom.toLong()"
                NumericKind.U8 -> "$recvAtom.toInt().toUByte()"
                NumericKind.U16 -> "$recvAtom.toInt().toUShort()"
                NumericKind.U32 -> "$recvAtom.toUInt()"
                NumericKind.U64 -> {
                    val fn = if (isF32) "rustFloatToULongUnchecked" else "rustDoubleToULongUnchecked"
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.$fn")
                    "$fn($renderedRecv)"
                }
                NumericKind.U128 -> {
                    val fn = if (isF32) "rustFloatToULongUnchecked" else "rustDoubleToULongUnchecked"
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.$fn")
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.U128")
                    "U128.fromULong($fn($renderedRecv))"
                }
                NumericKind.I128 -> {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.I128")
                    "I128.fromLong($recvAtom.toLong())"
                }
                else -> null
            }
        }
        if (turbofishType != null) return null
        if (extraExprs.isEmpty()) {
            return when (rawMethod) {
                "is_nan" -> "$recvAtom.isNaN()"
                "is_infinite" -> "$recvAtom.isInfinite()"
                "is_finite" -> "$recvAtom.isFinite()"
                "is_sign_positive" -> {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.rustIsSignPositive")
                    "rustIsSignPositive($renderedRecv)"
                }
                "is_sign_negative" -> {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.rustIsSignNegative")
                    "rustIsSignNegative($renderedRecv)"
                }
                "classify" -> {
                    val fn = if (isF32) "rustClassifyFloat" else "rustClassifyDouble"
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.$fn")
                    "$fn($renderedRecv)"
                }
                "is_normal" -> {
                    val fn = if (isF32) "rustClassifyFloat" else "rustClassifyDouble"
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.$fn")
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.FpCategory")
                    "($fn($renderedRecv) == FpCategory.Normal)"
                }
                "is_subnormal" -> {
                    val fn = if (isF32) "rustClassifyFloat" else "rustClassifyDouble"
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.$fn")
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.FpCategory")
                    "($fn($renderedRecv) == FpCategory.Subnormal)"
                }
                "abs", "floor", "ceil", "round", "sqrt", "exp", "ln", "log2", "log10", "cbrt", "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh", "asinh", "acosh", "atanh" -> {
                    CoreIntrinsicLowering.registerCoreImport(this, "kotlin.math.$rawMethod")
                    "$rawMethod($renderedRecv)"
                }
                "trunc" -> {
                    CoreIntrinsicLowering.registerCoreImport(this, "kotlin.math.truncate")
                    "truncate($renderedRecv)"
                }
                "exp2" -> {
                    CoreIntrinsicLowering.registerCoreImport(this, "kotlin.math.pow")
                    val twoLit = if (isF32) "2.0f" else "2.0"
                    "$twoLit.pow($renderedRecv)"
                }
                "fract" -> {
                    CoreIntrinsicLowering.registerCoreImport(this, "kotlin.math.truncate")
                    "$recvAtom.let { v -> v - truncate(v) }"
                }
                "recip" -> {
                    val oneLit = if (isF32) "1.0f" else "1.0"
                    "($oneLit / $recvAtom)"
                }
                "to_degrees" -> {
                    val scale = if (isF32) "(180.0f / kotlin.math.PI.toFloat())" else "(180.0 / kotlin.math.PI)"
                    "($recvAtom * $scale)"
                }
                "to_radians" -> {
                    val scale = if (isF32) "(kotlin.math.PI.toFloat() / 180.0f)" else "(kotlin.math.PI / 180.0)"
                    "($recvAtom * $scale)"
                }
                "signum" -> {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.rustIsSignNegative")
                    val negOne = if (isF32) "-1.0f" else "-1.0"
                    val posOne = if (isF32) "1.0f" else "1.0"
                    "$recvAtom.let { v -> when { v.isNaN() -> v; rustIsSignNegative(v) -> $negOne; else -> $posOne } }"
                }
                else -> null
            }
        }
        if (extraExprs.size == 1 && renderedExtras.size == 1) {
            return when (rawMethod) {
                "atan2", "hypot", "log" -> {
                    val arg0 = coerceNumericArgIfNeeded(extraExprs[0], renderedExtras[0], recvKind, selfTypeName)
                    CoreIntrinsicLowering.registerCoreImport(this, "kotlin.math.$rawMethod")
                    "$rawMethod($renderedRecv, $arg0)"
                }
                "powf" -> {
                    val arg0 = coerceNumericArgIfNeeded(extraExprs[0], renderedExtras[0], recvKind, selfTypeName)
                    CoreIntrinsicLowering.registerCoreImport(this, "kotlin.math.pow")
                    "$recvAtom.pow($arg0)"
                }
                "powi" -> {
                    val intArg = coerceNumericArgIfNeeded(extraExprs[0], renderedExtras[0], NumericKind.I32, selfTypeName)
                    CoreIntrinsicLowering.registerCoreImport(this, "kotlin.math.pow")
                    "$recvAtom.pow($intArg)"
                }
                "min", "max" -> {
                    val arg0 = coerceNumericArgIfNeeded(extraExprs[0], renderedExtras[0], recvKind, selfTypeName)
                    val tag = if (rawMethod == "min") "Min" else "Max"
                    "run { val lhs${tag}0 = ($renderedRecv); val rhs${tag}0 = ($arg0); when { lhs${tag}0.isNaN() -> rhs${tag}0; rhs${tag}0.isNaN() -> lhs${tag}0; else -> ${rawMethod}Of(lhs${tag}0, rhs${tag}0) } }"
                }
                else -> null
            }
        }
        if (extraExprs.size == 2 && renderedExtras.size == 2 && rawMethod == "mul_add") {
            val arg0 = coerceNumericArgIfNeeded(extraExprs[0], renderedExtras[0], recvKind, selfTypeName)
            val arg1 = coerceNumericArgIfNeeded(extraExprs[1], renderedExtras[1], recvKind, selfTypeName)
            val arg0Atom = if (isAtomicPostfixReceiver(extraExprs[0], arg0)) arg0 else "($arg0)"
            return "(($recvAtom * $arg0Atom) + ($arg1))"
        }
        return null
    }
    if (turbofishType != null) return null
    if (extraExprs.isEmpty()) {
        val fnName =
            when (rawMethod) {
                "checked_neg" -> "rustCheckedNeg"
                "checked_abs" -> "rustCheckedAbs"
                "wrapping_neg" -> "rustWrappingNeg"
                "wrapping_abs" -> "rustWrappingAbs"
                "saturating_neg" -> "rustSaturatingNeg"
                "saturating_abs" -> "rustSaturatingAbs"
                "overflowing_neg" -> "rustOverflowingNeg"
                "overflowing_abs" -> "rustOverflowingAbs"
                "reverse_bits" -> "rustReverseBits"
                "count_ones" -> "rustCountOnes"
                "count_zeros" -> "rustCountZeros"
                "leading_zeros" -> "rustLeadingZeros"
                "trailing_zeros" -> "rustTrailingZeros"
                "leading_ones" -> "rustLeadingOnes"
                "trailing_ones" -> "rustTrailingOnes"
                else -> return null
            }
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.$fnName")
        return "$fnName($renderedRecv)"
    }
    if (extraExprs.size == 1 && renderedExtras.size == 1) {
        val shiftOrPowFn =
            when (rawMethod) {
                "checked_shl" -> "rustCheckedShl"
                "checked_shr" -> "rustCheckedShr"
                "checked_pow" -> "rustCheckedPow"
                "wrapping_shl" -> "rustWrappingShl"
                "wrapping_shr" -> "rustWrappingShr"
                "overflowing_shl" -> "rustOverflowingShl"
                "overflowing_shr" -> "rustOverflowingShr"
                "rotate_left" -> "rustRotateLeft"
                "rotate_right" -> "rustRotateRight"
                "pow" -> "rustPow"
                else -> null
            }
        if (shiftOrPowFn != null) {
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.$shiftOrPowFn")
            val rhs = coerceNumericArgIfNeeded(extraExprs[0], renderedExtras[0], NumericKind.U32, selfTypeName)
            return "$shiftOrPowFn($renderedRecv, $rhs)"
        }
        val binFn =
            when (rawMethod) {
                "checked_add" -> "rustCheckedAdd"
                "checked_sub" -> "rustCheckedSub"
                "checked_mul" -> "rustCheckedMul"
                "checked_div" -> "rustCheckedDiv"
                "checked_rem" -> "rustCheckedRem"
                "checked_div_euclid" -> "rustCheckedDivEuclid"
                "checked_rem_euclid" -> "rustCheckedRemEuclid"
                "wrapping_add" -> "rustWrappingAdd"
                "wrapping_sub" -> "rustWrappingSub"
                "wrapping_mul" -> "rustWrappingMul"
                "wrapping_div" -> "rustWrappingDiv"
                "wrapping_rem" -> "rustWrappingRem"
                "saturating_add" -> "rustSaturatingAdd"
                "saturating_sub" -> "rustSaturatingSub"
                "saturating_mul" -> "rustSaturatingMul"
                "overflowing_add" -> "rustOverflowingAdd"
                "overflowing_sub" -> "rustOverflowingSub"
                "overflowing_mul" -> "rustOverflowingMul"
                "div_euclid" -> "rustDivEuclid"
                "rem_euclid" -> "rustRemEuclid"
                else -> null
            }
        if (binFn != null) {
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.$binFn")
            val rhs = coerceNumericArgIfNeeded(extraExprs[0], renderedExtras[0], recvKind, selfTypeName)
            return "$binFn($renderedRecv, $rhs)"
        }
    }
    return null
}

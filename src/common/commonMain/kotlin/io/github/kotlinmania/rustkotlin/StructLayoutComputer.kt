package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Fields
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.gen.Visit

/**
 * Parsed `#[repr(...)]` layout directives on a Rust `struct`.
 *
 * When neither `isReprC` nor `isTransparent` is specified, `rust-kotlin.kexe` acts as the
 * Rust compiler for `repr(Rust)` and picks deterministic declaration-order natural alignment.
 */
public data class StructReprSpec(
    public val isReprC: Boolean = false,
    public val isTransparent: Boolean = false,
    public val packedAlign: Long? = null,
    public val explicitAlign: Long? = null,
)

/**
 * Static and symbolic field layout metadata computed by [StructLayoutComputer].
 */
public data class ComputedStructFieldLayout(
    public val kotlinFieldName: String,
    public val kotlinFieldType: String,
    public val staticOffsetBytes: Long?,
    public val staticSizeBytes: Long?,
    public val staticAlignBytes: Long?,
    public val offsetExpr: String,
    public val layoutExpr: String,
)

/**
 * Required runtime `TypeLayout<T>` dictionary parameter specification.
 */
public data class TypeLayoutParamSpec(
    public val paramName: String,
    public val paramType: String,
)

/**
 * Flat sequential helper offset property emitted inside a generated `TypeLayout<T>`.
 */
public data class HelperOffsetDecl(
    public val helperName: String,
    public val helperInitExpr: String,
)

/**
 * Deterministic struct layout computed at translation time.
 */
public data class ComputedStructLayout(
    public val structKotlinName: String,
    public val repr: StructReprSpec,
    public val staticSizeBytes: Long?,
    public val staticAlignBytes: Long?,
    public val sizeExpr: String,
    public val alignExpr: String,
    public val fields: List<ComputedStructFieldLayout>,
    public val requiredTypeLayoutParams: List<TypeLayoutParamSpec>,
    public val helperOffsetDecls: List<HelperOffsetDecl> = emptyList(),
    public val useBodyPropertiesCtor: Boolean = false,
)

public object StructLayoutComputer {
    public fun alignUp(offset: Long, align: Long): Long {
        if (align <= 1L) return offset
        val rem = offset % align
        return if (rem == 0L) offset else offset + (align - rem)
    }

    public fun parseStructRepr(attrs: List<Attribute>): StructReprSpec {
        var isReprC = false
        var isTransparent = false
        var packedAlign: Long? = null
        var explicitAlign: Long? = null

        for (attr in attrs) {
            val repr = attr.meta as? Meta.List ?: continue
            if (!repr.path.isIdent("repr")) continue
            val tokens = repr.tokens.clone().toList()
            var index = 0
            while (index < tokens.size) {
                val ident = (tokens[index] as? TokenTree.Ident)?.value?.toString()
                when (ident) {
                    "C" -> isReprC = true
                    "transparent" -> isTransparent = true
                    "packed" -> {
                        val nextGroup = tokens.getOrNull(index + 1) as? TokenTree.Group
                        val parsedN =
                            nextGroup
                                ?.value
                                ?.stream()
                                ?.toString()
                                ?.trim()
                                ?.toLongOrNull()
                        val packVal =
                            if (parsedN != null && parsedN > 0L && (parsedN and (parsedN - 1L)) == 0L) {
                                index++
                                parsedN
                            } else {
                                1L
                            }
                        packedAlign = packedAlign?.let { minOf(it, packVal) } ?: packVal
                    }
                    "align" -> {
                        val nextGroup = tokens.getOrNull(index + 1) as? TokenTree.Group
                        val parsedN =
                            nextGroup
                                ?.value
                                ?.stream()
                                ?.toString()
                                ?.trim()
                                ?.toLongOrNull()
                        if (parsedN != null && parsedN > 0L && (parsedN and (parsedN - 1L)) == 0L) {
                            explicitAlign = explicitAlign?.let { maxOf(it, parsedN) } ?: parsedN
                            index++
                        }
                    }
                }
                index++
            }
        }
        return StructReprSpec(
            isReprC = isReprC,
            isTransparent = isTransparent,
            packedAlign = packedAlign,
            explicitAlign = explicitAlign,
        )
    }

    public fun typeLayoutParamName(typeParamKotlinName: String): String =
        "typeLayout$typeParamKotlinName"

    private fun qualOrImportMemSymbol(
        symbol: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        val shadowed =
            emitter.headerContext.packageName != memPkg &&
                (symbol in emitter.inspector.declaredTypeNames || symbol in emitter.headerContext.moduleDeclaredTypeNames)
        if (shadowed) return "$memPkg.$symbol"
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.$symbol")
        return symbol
    }

    internal fun computeStructLayout(
        item: Item.Struct,
        emitter: KotlinJ2KEmitter,
        visiting: Set<String> = emptySet(),
    ): ComputedStructLayout? {
        val structKtName = emitter.effectiveStructKotlinName(rustTypeIdentToKotlin(item.ident.toString()))
        if (structKtName in visiting) return null
        if (emitter.computedStructLayoutCache.containsKey(structKtName)) {
            return emitter.computedStructLayoutCache[structKtName]
        }
        val computed = computeStructLayoutUncached(item, structKtName, emitter, visiting)
        if (computed != null || visiting.isEmpty()) {
            emitter.computedStructLayoutCache[structKtName] = computed
        }
        return computed
    }

    private val PRIMITIVE_KOTLIN_ARRAY_TYPES: Set<String> =
        setOf(
            "BooleanArray",
            "ByteArray",
            "UByteArray",
            "ShortArray",
            "UShortArray",
            "IntArray",
            "UIntArray",
            "LongArray",
            "ULongArray",
            "FloatArray",
            "DoubleArray",
        )

    private fun computeStructLayoutUncached(
        item: Item.Struct,
        structKtName: String,
        emitter: KotlinJ2KEmitter,
        visiting: Set<String>,
    ): ComputedStructLayout? {
        val repr = parseStructRepr(item.attrs)
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
        val memPkg = "$coreNs.mem"
        val ptrBytes = (emitter.activeCfgProfile.pointerWidth / 8).toLong()
        val useFinalAbiLayout = emitter.headerContext.rawAbiMode || emitter.headerContext.finalClasses

        val genericParams =
            item.generics.params
                .toList()
                .filterIsInstance<GenericParam.TypeParam>()
                .map { rustTypeIdentToKotlin(it.ident.toString()) }
        val genericParamLayouts =
            genericParams.associateWith { paramKt ->
                typeLayoutParamName(paramKt)
            }
        val selfWithGenerics =
            if (genericParams.isEmpty()) {
                structKtName
            } else {
                "$structKtName<${genericParams.joinToString(", ")}>"
            }

        val prevStructTypeParams = emitter.activeTypeParamNames
        val prevStructConstParams = emitter.activeConstParamNames
        emitter.activeTypeParamNames = prevStructTypeParams + emitter.collectGenericTypeParamNames(item.generics)
        emitter.activeConstParamNames = prevStructConstParams + emitter.collectGenericConstParamNames(item.generics)
        return try {
            if (emitter.sourceTransparentField(item, selfWithGenerics) != null) {
                return null
            }

            val rawFields: List<Pair<String, SynType>> =
                when (val f = item.fields) {
                    is Fields.Unit -> emptyList()
                    is Fields.Named -> {
                        val seenFieldNames = hashSetOf<String>()
                        buildList {
                            for ((idx, field) in f.fields.named
                                .toList()
                                .withIndex()) {
                                if (isDocOnlyCfgAttribute(field.attrs)) continue
                                val fName = rustFieldIdentToKotlin(field.ident?.toString() ?: "field$idx")
                                if (!seenFieldNames.add(fName)) continue
                                val unwrappedFieldTy = emitter.unwrapPaddingSynType(field.ty)
                                if (useFinalAbiLayout && unwrappedFieldTy is SynType.Tuple && unwrappedFieldTy.elems.len() >= 2) {
                                    for ((tIdx, elemTy) in unwrappedFieldTy.elems.toList().withIndex()) {
                                        val subName = "$fName$tIdx"
                                        if (!seenFieldNames.add(subName)) continue
                                        add(subName to elemTy)
                                    }
                                } else {
                                    add(fName to field.ty)
                                }
                            }
                        }
                    }
                    is Fields.Unnamed -> {
                        val activeUnnamed =
                            f.fields.unnamed
                                .toList()
                                .filterNot { isDocOnlyCfgAttribute(it.attrs) }
                        val arity = activeUnnamed.size
                        activeUnnamed.mapIndexed { idx, field ->
                            tupleFieldPropertyName(idx, arity) to field.ty
                        }
                    }
                }

            if (rawFields.isEmpty()) {
                val align = repr.explicitAlign ?: 1L
                return ComputedStructLayout(
                    structKotlinName = structKtName,
                    repr = repr,
                    staticSizeBytes = 0L,
                    staticAlignBytes = align,
                    sizeExpr = "0L",
                    alignExpr = "${align}L",
                    fields = emptyList(),
                    requiredTypeLayoutParams = emptyList(),
                )
            }

            val usedTypeParams = linkedSetOf<String>()
            val computedFields = mutableListOf<ComputedStructFieldLayout>()
            val helperOffsetDecls = mutableListOf<HelperOffsetDecl>()
            var allStatic = true
            var allAlignsStatic = true
            var runningOffset = 0L
            var maxEffAlign = repr.explicitAlign ?: 1L
            var runningSymbolicEndExpr = "0L"
            val dynamicEffAlignExprs = mutableListOf<String>()

            for ((fieldIndex, entry) in rawFields.withIndex()) {
                val (ktFieldName, fieldSynType) = entry
                val ktFieldType = emitter.renderType(fieldSynType, selfWithGenerics)
                val resolvedField =
                    resolveFieldLayoutSpec(
                        rawType = fieldSynType,
                        renderedFieldKt = ktFieldType,
                        selfTypeName = selfWithGenerics,
                        genericParamLayouts = genericParamLayouts,
                        usedTypeParams = usedTypeParams,
                        ptrBytes = ptrBytes,
                        emitter = emitter,
                        visiting = visiting + structKtName,
                    ) ?: return null
                val effStaticAlign =
                    resolvedField.staticAlign?.let { fAlign ->
                        repr.packedAlign?.let { minOf(fAlign, it) } ?: fAlign
                    }
                val effAlignExpr =
                    if (effStaticAlign != null) {
                        maxEffAlign = maxOf(maxEffAlign, effStaticAlign)
                        "${effStaticAlign}L"
                    } else {
                        allAlignsStatic = false
                        val dynAlign =
                            if (repr.packedAlign != null) {
                                "minOf(${resolvedField.alignExpr}, ${repr.packedAlign}L)"
                            } else {
                                resolvedField.alignExpr
                            }
                        dynamicEffAlignExprs += dynAlign
                        dynAlign
                    }

                val fieldStaticOffset: Long?
                val fieldOffsetExpr: String
                if (allStatic && effStaticAlign != null) {
                    val aligned = alignUp(runningOffset, effStaticAlign)
                    fieldStaticOffset = aligned
                    fieldOffsetExpr = "${aligned}L"
                    if (resolvedField.staticSize != null) {
                        runningOffset = aligned + resolvedField.staticSize
                        runningSymbolicEndExpr = "${runningOffset}L"
                    } else {
                        allStatic = false
                        runningSymbolicEndExpr =
                            if (aligned == 0L) {
                                resolvedField.sizeExpr
                            } else {
                                "(${aligned}L + ${resolvedField.sizeExpr})"
                            }
                    }
                } else {
                    allStatic = false
                    fieldStaticOffset = null
                    if (runningSymbolicEndExpr == "0L") {
                        fieldOffsetExpr = "0L"
                        runningSymbolicEndExpr = resolvedField.sizeExpr
                    } else {
                        val helperName = "off$fieldIndex"
                        val alignUpRef = qualOrImportMemSymbol("alignUpBytes", memPkg, emitter)
                        helperOffsetDecls +=
                            HelperOffsetDecl(
                                helperName = helperName,
                                helperInitExpr = "$alignUpRef($runningSymbolicEndExpr, $effAlignExpr)",
                            )
                        fieldOffsetExpr = helperName
                        runningSymbolicEndExpr = "($helperName + ${resolvedField.sizeExpr})"
                    }
                }

                computedFields +=
                    ComputedStructFieldLayout(
                        kotlinFieldName = ktFieldName,
                        kotlinFieldType = ktFieldType,
                        staticOffsetBytes = fieldStaticOffset,
                        staticSizeBytes = resolvedField.staticSize,
                        staticAlignBytes = effStaticAlign,
                        offsetExpr = fieldOffsetExpr,
                        layoutExpr = resolvedField.layoutExpr,
                    )
            }

            val finalStaticAlign: Long?
            val finalAlignExpr: String
            if (allAlignsStatic) {
                finalStaticAlign = maxEffAlign
                finalAlignExpr = "${maxEffAlign}L"
            } else {
                finalStaticAlign = null
                val alignCandidates =
                    buildList {
                        add("${maxEffAlign}L")
                        addAll(dynamicEffAlignExprs.distinct())
                    }
                finalAlignExpr =
                    if (alignCandidates.size == 1) {
                        alignCandidates[0]
                    } else {
                        "listOf(${alignCandidates.joinToString(", ")}).max()"
                    }
            }

            val finalStaticSize: Long?
            val finalSizeExpr: String
            if (allStatic) {
                finalStaticSize = alignUp(runningOffset, maxEffAlign)
                finalSizeExpr = "${finalStaticSize}L"
            } else {
                finalStaticSize = null
                val alignRef = if (allAlignsStatic) "${maxEffAlign}L" else "alignBytes"
                finalSizeExpr = "${qualOrImportMemSymbol("alignUpBytes", memPkg, emitter)}($runningSymbolicEndExpr, $alignRef)"
            }

            val typeLayoutRef = qualOrImportMemSymbol("TypeLayout", memPkg, emitter)
            val requiredParams =
                usedTypeParams.map { paramKt ->
                    TypeLayoutParamSpec(typeLayoutParamName(paramKt), "$typeLayoutRef<$paramKt>")
                }
            val constGenericSpecs =
                item.generics.params
                    .toList()
                    .filterIsInstance<GenericParam.ConstParam>()
                    .map { cp ->
                        val raw = cp.ident.toString().removePrefix("r#")
                        raw to rustValueIdentToKotlin(cp.ident.toString())
                    }
            var adjustedSizeExpr = finalSizeExpr
            var adjustedAlignExpr = finalAlignExpr
            var adjustedHelperOffsetDecls = helperOffsetDecls.toList()
            var adjustedComputedFields = computedFields.toList()
            val constLayoutParams = mutableListOf<TypeLayoutParamSpec>()
            val existingParamNames = usedTypeParams.mapTo(hashSetOf()) { typeLayoutParamName(it) }
            if (constGenericSpecs.isNotEmpty()) {
                val allLayoutExprs =
                    buildList {
                        add(adjustedSizeExpr)
                        add(adjustedAlignExpr)
                        adjustedHelperOffsetDecls.forEach { add(it.helperInitExpr) }
                        adjustedComputedFields.forEach {
                            add(it.offsetExpr)
                            add(it.layoutExpr)
                        }
                    }
                for ((raw, ktParam) in constGenericSpecs) {
                    val detectRegex = Regex("(?<!\\.)\\b(?:${Regex.escape(raw)}|${Regex.escape(ktParam)})\\b")
                    if (allLayoutExprs.any { detectRegex.containsMatchIn(it) }) {
                        if (raw != ktParam) {
                            val rewriteRegex = Regex("(?<!\\.)\\b${Regex.escape(raw)}\\b")
                            adjustedSizeExpr = rewriteRegex.replace(adjustedSizeExpr, ktParam)
                            adjustedAlignExpr = rewriteRegex.replace(adjustedAlignExpr, ktParam)
                            adjustedHelperOffsetDecls =
                                adjustedHelperOffsetDecls.map { decl ->
                                    decl.copy(helperInitExpr = rewriteRegex.replace(decl.helperInitExpr, ktParam))
                                }
                            adjustedComputedFields =
                                adjustedComputedFields.map { field ->
                                    field.copy(
                                        offsetExpr = rewriteRegex.replace(field.offsetExpr, ktParam),
                                        layoutExpr = rewriteRegex.replace(field.layoutExpr, ktParam),
                                    )
                                }
                        }
                        if (existingParamNames.add(ktParam)) {
                            constLayoutParams += TypeLayoutParamSpec(ktParam, "Int = 0")
                        }
                    }
                }
            }

            ComputedStructLayout(
                structKotlinName = structKtName,
                repr = repr,
                staticSizeBytes = finalStaticSize,
                staticAlignBytes = finalStaticAlign,
                sizeExpr = adjustedSizeExpr,
                alignExpr = adjustedAlignExpr,
                fields = adjustedComputedFields,
                requiredTypeLayoutParams = requiredParams + constLayoutParams,
                helperOffsetDecls = adjustedHelperOffsetDecls,
                useBodyPropertiesCtor = useFinalAbiLayout && computedFields.size > 64,
            )
        } finally {
            emitter.activeTypeParamNames = prevStructTypeParams
            emitter.activeConstParamNames = prevStructConstParams
        }
    }

    private data class FieldLayoutSpec(
        val staticSize: Long?,
        val staticAlign: Long?,
        val sizeExpr: String,
        val alignExpr: String,
        val layoutExpr: String,
    )

    private fun resolveFieldLayoutSpec(
        rawType: SynType,
        renderedFieldKt: String,
        selfTypeName: String?,
        genericParamLayouts: Map<String, String>,
        usedTypeParams: MutableSet<String>,
        ptrBytes: Long,
        emitter: KotlinJ2KEmitter,
        visiting: Set<String>,
    ): FieldLayoutSpec? {
        val cleanRenderedKt = renderedFieldKt.trim()
        if (cleanRenderedKt.endsWith("?")) return null
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
        val memPkg = "$coreNs.mem"
        val unwrappedRaw = emitter.unwrapPaddingSynType(rawType)
        val resolved = emitter.resolveLayoutType(unwrappedRaw, emptyMap())
        if (resolved is SynType.Tuple && resolved.elems.isEmpty() && cleanRenderedKt == "Unit") {
            val primitiveLayoutsRef = qualOrImportMemSymbol("PrimitiveLayouts", memPkg, emitter)
            return FieldLayoutSpec(0L, 1L, "0L", "1L", "$primitiveLayoutsRef.UNIT")
        }
        if (resolved is SynType.Ptr) {
            val pointeeKt = emitter.renderType(resolved.elem, selfTypeName)
            val isMut = resolved.mutability is io.github.kotlinmania.syn.PointerMutability.Mut
            val factory = if (isMut) "mutPtrLayout" else "constPtrLayout"
            val primitiveLayoutsRef = qualOrImportMemSymbol("PrimitiveLayouts", memPkg, emitter)
            return FieldLayoutSpec(
                staticSize = ptrBytes,
                staticAlign = ptrBytes,
                sizeExpr = "${ptrBytes}L",
                alignExpr = "${ptrBytes}L",
                layoutExpr = "$primitiveLayoutsRef.$factory<$pointeeKt>(${ptrBytes * 8L})",
            )
        }
        if (resolved is SynType.Array) {
            val elemRendered = emitter.renderType(resolved.elem, selfTypeName).trim()
            val elemSpec =
                resolveFieldLayoutSpec(
                    rawType = resolved.elem,
                    renderedFieldKt = elemRendered,
                    selfTypeName = selfTypeName,
                    genericParamLayouts = genericParamLayouts,
                    usedTypeParams = usedTypeParams,
                    ptrBytes = ptrBytes,
                    emitter = emitter,
                    visiting = visiting,
                ) ?: return null
            val staticLen = extractStaticIntLiteral(resolved.len)
            val lenExpr = staticLen?.toString() ?: emitter.renderExpr(resolved.len, "", selfTypeName)
            val elemStaticStride =
                if (elemSpec.staticSize != null && elemSpec.staticAlign != null) {
                    alignUp(elemSpec.staticSize, elemSpec.staticAlign)
                } else {
                    null
                }
            val staticArrSize = if (staticLen != null && elemStaticStride != null) staticLen * elemStaticStride else null
            val lenIntArg = staticLen?.toString() ?: "($lenExpr).toInt()"
            val primitiveFactory =
                emitter.primitiveArrayLayoutFactory(
                    elemSynType = resolved.elem,
                    elemRenderedType = elemRendered,
                    pointerWidthBits = ptrBytes * 8L,
                )
            val layoutExpr =
                if (primitiveFactory != null) {
                    val primitiveArrayLayoutsRef = qualOrImportMemSymbol("PrimitiveArrayLayouts", memPkg, emitter)
                    "$primitiveArrayLayoutsRef.$primitiveFactory($lenIntArg)"
                } else {
                    val fixedListRef = qualOrImportMemSymbol("fixedListTypeLayout", memPkg, emitter)
                    "$fixedListRef(${elemSpec.layoutExpr}, $lenIntArg)"
                }
            return FieldLayoutSpec(
                staticSize = staticArrSize,
                staticAlign = elemSpec.staticAlign,
                sizeExpr = staticArrSize?.let { "${it}L" } ?: "(($lenExpr).toLong() * (${elemSpec.layoutExpr}).strideBytes)",
                alignExpr = elemSpec.alignExpr,
                layoutExpr = layoutExpr,
            )
        }
        val path = (resolved as? SynType.Path)?.takeIf { it.qself == null }?.path ?: return null
        if (path.segments.last()?.arguments !is PathArguments.None) return null
        val singleIdent =
            path.segments
                .toList()
                .singleOrNull()
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
        if (singleIdent != null) {
            val ktParam = rustTypeIdentToKotlin(singleIdent)
            val paramLayoutVar = genericParamLayouts[ktParam]
            if (paramLayoutVar != null) {
                usedTypeParams += ktParam
                return FieldLayoutSpec(
                    staticSize = null,
                    staticAlign = null,
                    sizeExpr = "$paramLayoutVar.sizeBytes",
                    alignExpr = "$paramLayoutVar.alignBytes",
                    layoutExpr = paramLayoutVar,
                )
            }
        }
        val targetLeaf =
            path.segments
                .last()
                ?.ident
                ?.toString()
                ?.removePrefix("r#") ?: return null
        scalarLayoutSpec(targetLeaf, cleanRenderedKt, ptrBytes, memPkg, emitter)?.let { return it }
        val targetKt = rustTypeIdentToKotlin(targetLeaf)
        val nestedDecl = emitter.sameFileUniqueSimpleStruct(targetKt)
        if (nestedDecl != null &&
            nestedDecl.generics.params.none { it is GenericParam.TypeParam } &&
            emitter.sourceTransparentField(nestedDecl, targetKt) == null
        ) {
            val nestedLayout = computeStructLayout(nestedDecl, emitter, visiting) ?: return null
            if (nestedLayout.requiredTypeLayoutParams.isNotEmpty()) return null
            return FieldLayoutSpec(
                staticSize = nestedLayout.staticSizeBytes,
                staticAlign = nestedLayout.staticAlignBytes,
                sizeExpr = nestedLayout.staticSizeBytes?.let { "${it}L" } ?: "$targetKt.typeLayout().sizeBytes",
                alignExpr = nestedLayout.staticAlignBytes?.let { "${it}L" } ?: "$targetKt.typeLayout().alignBytes",
                layoutExpr = "$targetKt.typeLayout()",
            )
        }
        return null
    }

    private fun scalarLayoutSpec(
        rustLeafIdent: String,
        renderedFieldKt: String,
        ptrBytes: Long,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): FieldLayoutSpec? {
        val primitiveLayoutsRef = qualOrImportMemSymbol("PrimitiveLayouts", memPkg, emitter)
        if (renderedFieldKt == "Boolean") {
            return FieldLayoutSpec(1L, 1L, "1L", "1L", "$primitiveLayoutsRef.BOOL")
        }
        if (renderedFieldKt == "Char") {
            return FieldLayoutSpec(4L, 4L, "4L", "4L", "$primitiveLayoutsRef.CHAR")
        }
        if (renderedFieldKt == "F16") {
            return FieldLayoutSpec(2L, 2L, "2L", "2L", "$primitiveLayoutsRef.F16_LAYOUT")
        }
        if (renderedFieldKt == "F128") {
            return FieldLayoutSpec(16L, 16L, "16L", "16L", "$primitiveLayoutsRef.F128_LAYOUT")
        }
        val numKind = emitter.resolveNumericKind(renderedFieldKt) ?: return null
        if (numKind == NumericKind.I32 && rustLeafIdent == "isize") {
            val layoutConst = if (ptrBytes == 8L) "ISIZE64" else "ISIZE32"
            return FieldLayoutSpec(ptrBytes, ptrBytes, "${ptrBytes}L", "${ptrBytes}L", "$primitiveLayoutsRef.$layoutConst")
        }
        if (numKind == NumericKind.I32 && rustLeafIdent == "usize") {
            val layoutConst = if (ptrBytes == 8L) "USIZE64" else "USIZE32"
            return FieldLayoutSpec(ptrBytes, ptrBytes, "${ptrBytes}L", "${ptrBytes}L", "$primitiveLayoutsRef.$layoutConst")
        }
        return when (numKind) {
            NumericKind.I8 -> FieldLayoutSpec(1L, 1L, "1L", "1L", "$primitiveLayoutsRef.I8")
            NumericKind.U8 -> FieldLayoutSpec(1L, 1L, "1L", "1L", "$primitiveLayoutsRef.U8")
            NumericKind.I16 -> FieldLayoutSpec(2L, 2L, "2L", "2L", "$primitiveLayoutsRef.I16")
            NumericKind.U16 -> FieldLayoutSpec(2L, 2L, "2L", "2L", "$primitiveLayoutsRef.U16")
            NumericKind.I32 -> FieldLayoutSpec(4L, 4L, "4L", "4L", "$primitiveLayoutsRef.I32")
            NumericKind.U32 -> FieldLayoutSpec(4L, 4L, "4L", "4L", "$primitiveLayoutsRef.U32")
            NumericKind.F16 -> FieldLayoutSpec(2L, 2L, "2L", "2L", "$primitiveLayoutsRef.F16_LAYOUT")
            NumericKind.F32 -> FieldLayoutSpec(4L, 4L, "4L", "4L", "$primitiveLayoutsRef.F32")
            NumericKind.I64 -> FieldLayoutSpec(8L, 8L, "8L", "8L", "$primitiveLayoutsRef.I64")
            NumericKind.U64 -> FieldLayoutSpec(8L, 8L, "8L", "8L", "$primitiveLayoutsRef.U64")
            NumericKind.F64 -> FieldLayoutSpec(8L, 8L, "8L", "8L", "$primitiveLayoutsRef.F64")
            NumericKind.F128 -> FieldLayoutSpec(16L, 16L, "16L", "16L", "$primitiveLayoutsRef.F128_LAYOUT")
            NumericKind.I128 -> {
                emitter.usesI128 = true
                FieldLayoutSpec(
                    16L,
                    16L,
                    "16L",
                    "16L",
                    "$primitiveLayoutsRef.i128Layout(::I128, { it.high }, { it.low })",
                )
            }
            NumericKind.U128 -> {
                emitter.usesU128 = true
                FieldLayoutSpec(
                    16L,
                    16L,
                    "16L",
                    "16L",
                    "$primitiveLayoutsRef.u128Layout(::U128, { it.high }, { it.low })",
                )
            }
        }
    }

    private fun extractStaticIntLiteral(expr: Expr): Long? {
        var cur = expr
        while (true) {
            cur =
                when (cur) {
                    is Expr.Paren -> cur.expr
                    is Expr.Group -> cur.expr
                    else -> break
                }
        }
        val lit = (cur as? Expr.Lit)?.lit as? Lit.Int ?: return null
        val digits = lit.value.base10Digits()
        return digits.toLongOrNull()
    }

    private fun adaptReadExprForFieldType(
        rawReadExpr: String,
        ktFieldType: String,
        fieldLayoutExpr: String,
    ): String {
        val clean = ktFieldType.trim()
        if (fieldLayoutExpr.contains("PrimitiveArrayLayouts.")) {
            return if (clean.startsWith("MutableList<")) "$rawReadExpr.toMutableList()" else rawReadExpr
        }
        return when (clean) {
            "BooleanArray" -> "$rawReadExpr.toBooleanArray()"
            "ByteArray" -> "$rawReadExpr.toByteArray()"
            "UByteArray" -> "$rawReadExpr.toUByteArray()"
            "ShortArray" -> "$rawReadExpr.toShortArray()"
            "UShortArray" -> "$rawReadExpr.toUShortArray()"
            "IntArray" -> "$rawReadExpr.toIntArray()"
            "UIntArray" -> "$rawReadExpr.toUIntArray()"
            "LongArray" -> "$rawReadExpr.toLongArray()"
            "ULongArray" -> "$rawReadExpr.toULongArray()"
            "FloatArray" -> "$rawReadExpr.toFloatArray()"
            "DoubleArray" -> "$rawReadExpr.toDoubleArray()"
            else -> {
                val inner2d =
                    if (clean.startsWith("List<") && clean.endsWith(">")) {
                        clean.removePrefix("List<").removeSuffix(">").trim()
                    } else {
                        null
                    }
                if (inner2d != null && inner2d in PRIMITIVE_KOTLIN_ARRAY_TYPES) {
                    "$rawReadExpr.map { ${adaptReadExprForFieldType("it", inner2d, "")} }"
                } else if (clean.startsWith("MutableList<")) {
                    val innerMut = clean.removePrefix("MutableList<").removeSuffix(">").trim()
                    if (innerMut in PRIMITIVE_KOTLIN_ARRAY_TYPES) {
                        "$rawReadExpr.map { ${adaptReadExprForFieldType("it", innerMut, "")} }.toMutableList()"
                    } else {
                        "$rawReadExpr.toMutableList()"
                    }
                } else {
                    rawReadExpr
                }
            }
        }
    }

    private fun adaptWriteValueExprForFieldType(
        rawValueExpr: String,
        ktFieldType: String,
        fieldLayoutExpr: String,
    ): String {
        if (fieldLayoutExpr.contains("PrimitiveArrayLayouts.")) {
            return rawValueExpr
        }
        val clean = ktFieldType.trim()
        if (clean in PRIMITIVE_KOTLIN_ARRAY_TYPES) {
            return "$rawValueExpr.toList()"
        }
        val innerList =
            when {
                clean.startsWith("List<") && clean.endsWith(">") ->
                    clean.removePrefix("List<").removeSuffix(">").trim()
                clean.startsWith("MutableList<") && clean.endsWith(">") ->
                    clean.removePrefix("MutableList<").removeSuffix(">").trim()
                else -> null
            }
        if (innerList != null && innerList in PRIMITIVE_KOTLIN_ARRAY_TYPES) {
            return "$rawValueExpr.map { it.toList() }"
        }
        return rawValueExpr
    }

    private fun toByteOffsetExpr(offsetExpr: String): String =
        when {
            offsetExpr == "0L" -> "offset"
            offsetExpr.endsWith("L") && offsetExpr.dropLast(1).all { it.isDigit() } ->
                "offset + ${offsetExpr.dropLast(1)}"
            offsetExpr.all { it.isLetterOrDigit() || it == '_' } ->
                "offset + $offsetExpr.toInt()"
            else -> "offset + ($offsetExpr).toInt()"
        }

    internal fun renderStructTypeLayoutMethod(
        layout: ComputedStructLayout,
        genericTypeParamsRendered: String,
        selfTypeWithGenerics: String,
        coreNamespace: String,
        indent: String,
        emittedFieldTypesByName: Map<String, String> = emptyMap(),
        useBodyPropertiesCtor: Boolean = layout.useBodyPropertiesCtor,
        emitter: KotlinJ2KEmitter? = null,
    ): String {
        val memPkg = "$coreNamespace.mem"
        val typeLayoutRef =
            if (emitter != null) {
                qualOrImportMemSymbol("TypeLayout", memPkg, emitter)
            } else {
                "$memPkg.TypeLayout"
            }
        val paramsSig = layout.requiredTypeLayoutParams.joinToString(", ") { "${it.paramName}: ${it.paramType}" }
        val fnTypeParams = if (genericTypeParamsRendered.isNotEmpty()) "$genericTypeParamsRendered " else ""
        val sb = StringBuilder()
        sb.append("$indent    public fun ${fnTypeParams}typeLayout($paramsSig): $typeLayoutRef<$selfTypeWithGenerics> =\n")
        sb.append("$indent        object : $typeLayoutRef<$selfTypeWithGenerics> {\n")
        sb.append("$indent            override val alignBytes: Long = ${layout.alignExpr}\n")
        for ((helperName, helperInit) in layout.helperOffsetDecls) {
            sb.append("$indent            private val $helperName: Long = $helperInit\n")
        }
        sb.append("$indent            override val sizeBytes: Long = ${layout.sizeExpr}\n")
        fun appendStructReadBody(
            methodSig: String,
            buildReadCall: (ComputedStructFieldLayout) -> String,
        ) {
            sb.append("$indent            override fun $methodSig: $selfTypeWithGenerics =\n")
            if (layout.fields.isEmpty()) {
                sb.append("$indent                ${layout.structKotlinName}()\n")
            } else if (useBodyPropertiesCtor) {
                sb.append("$indent                ${layout.structKotlinName}().apply {\n")
                for (field in layout.fields) {
                    val effectiveType = emittedFieldTypesByName[field.kotlinFieldName] ?: field.kotlinFieldType
                    val readExpr = adaptReadExprForFieldType(buildReadCall(field), effectiveType, field.layoutExpr)
                    sb.append("$indent                    this.${field.kotlinFieldName} = $readExpr\n")
                }
                sb.append("$indent                }\n")
            } else {
                sb.append("$indent                ${layout.structKotlinName}(\n")
                for (field in layout.fields) {
                    val effectiveType = emittedFieldTypesByName[field.kotlinFieldName] ?: field.kotlinFieldType
                    val readExpr = adaptReadExprForFieldType(buildReadCall(field), effectiveType, field.layoutExpr)
                    sb.append("$indent                    $readExpr,\n")
                }
                sb.append("$indent                )\n")
            }
        }
        appendStructReadBody("readNative(addr: Long)") { field ->
            val addrExpr = if (field.offsetExpr == "0L") "addr" else "addr + ${field.offsetExpr}"
            "(${field.layoutExpr}).readNative($addrExpr)"
        }
        sb.append("$indent            override fun writeNative(addr: Long, value: $selfTypeWithGenerics) {\n")
        for (field in layout.fields) {
            val effectiveType = emittedFieldTypesByName[field.kotlinFieldName] ?: field.kotlinFieldType
            val addrExpr = if (field.offsetExpr == "0L") "addr" else "addr + ${field.offsetExpr}"
            val writeValExpr = adaptWriteValueExprForFieldType("value.${field.kotlinFieldName}", effectiveType, field.layoutExpr)
            sb.append("$indent                (${field.layoutExpr}).writeNative($addrExpr, $writeValExpr)\n")
        }
        sb.append("$indent            }\n")
        appendStructReadBody("readBytes(buf: ByteArray, offset: Int)") { field ->
            val byteOff = toByteOffsetExpr(field.offsetExpr)
            "(${field.layoutExpr}).readBytes(buf, $byteOff)"
        }
        sb.append("$indent            override fun writeBytes(buf: ByteArray, offset: Int, value: $selfTypeWithGenerics) {\n")
        for (field in layout.fields) {
            val effectiveType = emittedFieldTypesByName[field.kotlinFieldName] ?: field.kotlinFieldType
            val byteOff = toByteOffsetExpr(field.offsetExpr)
            val writeValExpr = adaptWriteValueExprForFieldType("value.${field.kotlinFieldName}", effectiveType, field.layoutExpr)
            sb.append("$indent                (${field.layoutExpr}).writeBytes(buf, $byteOff, $writeValExpr)\n")
        }
        sb.append("$indent            }\n")
        sb.append("$indent        }")
        return sb.toString()
    }
}

private val KNOWN_STDLIB_FIRST_TYPE_LAYOUT_FUNCTIONS: Set<String> =
    setOf(
        "size_of",
        "align_of",
        "min_align_of",
        "pref_align_of",
        "zeroed",
        "read",
        "read_unaligned",
        "read_volatile",
        "write",
        "write_unaligned",
        "write_volatile",
        "write_bytes",
        "copy",
        "copy_nonoverlapping",
        "dangling",
        "dangling_mut",
    )

private val POINTER_STRIDE_OR_ACCESS_METHODS: Set<String> =
    setOf(
        "offset",
        "add",
        "sub",
        "wrapping_offset",
        "wrapping_add",
        "wrapping_sub",
        "offset_from",
        "offset_from_unsigned",
        "sub_ptr",
        "read",
        "read_volatile",
        "read_unaligned",
        "write",
        "write_volatile",
        "write_unaligned",
        "write_bytes",
        "copy_to",
        "copy_to_nonoverlapping",
        "copy_from",
        "copy_from_nonoverlapping",
        "as_ref",
        "as_mut",
    )

/**
 * Discovers which generic type parameters of [sig] (plus any enclosing `impl<T>` [ownerGenerics])
 * require runtime `typeLayout<T>: TypeLayout<T>` dictionaries because [block] invokes layout-bearing
 * intrinsics, pointer arithmetic/dereferences, or layout-bearing generic callees.
 */
internal fun KotlinJ2KEmitter.computeRequiredTypeLayoutParameters(
    sig: Signature,
    block: Block?,
    ownerGenerics: Generics? = null,
    selfTypeName: String? = null,
    visitingFnKeys: Set<String> = emptySet(),
): List<Pair<String, String>> {
    if (block == null || headerContext.rawAbiMode) return emptyList()
    val rawTypeParams =
        buildList {
            ownerGenerics?.params?.toList()?.filterIsInstance<GenericParam.TypeParam>()?.forEach {
                add(it.ident.toString().removePrefix("r#"))
            }
            sig.generics.params.toList().filterIsInstance<GenericParam.TypeParam>().forEach {
                val raw = it.ident.toString().removePrefix("r#")
                if (raw !in this) add(raw)
            }
        }
    if (rawTypeParams.isEmpty()) return emptyList()
    val identStart = sig.ident.span().start()
    val cacheKey = "${selfTypeName.orEmpty()}::${sig.ident}@${identStart.line}:${identStart.column}"
    requiredTypeLayoutParamsCache[cacheKey]?.let { return it }
    val rawTypeParamSet = rawTypeParams.toSet()

    val localFnItems = mutableMapOf<String, Item.Fn>()
    object : Visit() {
        override fun visitItemFn(i: Item.Fn) {
            localFnItems[
                i.sig.ident
                    .toString()
                    .removePrefix("r#"),
            ] = i
        }
    }.visitBlock(block)

    val paramSynTypes = linkedMapOf<String, SynType>()
    if (sig.inputs.firstOrNull() is FnArg.Receiver && currentStandaloneImplSelfSynType != null) {
        paramSynTypes["self"] = requireNotNull(currentStandaloneImplSelfSynType)
    }
    for (input in sig.inputs.toList()) {
        if (input is FnArg.Typed) {
            val rawName = (input.patType.pat as? Pat.Ident)?.ident?.toString()?.removePrefix("r#")
            if (rawName != null) {
                paramSynTypes[rawName] = input.patType.ty
            }
        }
    }

    val requiredRawParams = linkedSetOf<String>()

    fun recordRequiredFromSynType(ty: SynType?) {
        if (ty == null) return
        object : Visit() {
            override fun visitTypePath(t: SynType.Path) {
                if (t.qself == null && t.path.segments.size == 1) {
                    val raw =
                        t.path.segments
                            .last()
                            ?.ident
                            ?.toString()
                            ?.removePrefix("r#")
                    if (raw != null && raw in rawTypeParamSet) {
                        requiredRawParams += raw
                    }
                }
                super.visitTypePath(t)
            }
        }.visitType(ty)
    }

    fun inferLocalSynType(expr: Expr): SynType? {
        val unwrapped = unwrapParenOrGroupExpr(expr)
        if (unwrapped is Expr.Path && unwrapped.qself == null && unwrapped.path.segments.size == 1) {
            val raw =
                unwrapped.path.segments
                    .last()
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
            paramSynTypes[raw]?.let { return it }
        }
        if (unwrapped is Expr.Cast) return unwrapped.ty
        return null
    }

    object : Visit() {
        override fun visitItemFn(i: Item.Fn) {
            // Do not attribute nested fn's own type parameters to the outer function unless called.
        }

        override fun visitExprUnary(e: Expr.Unary) {
            if (e.op is UnOp.Deref) {
                val innerTy = inferLocalSynType(e.expr)
                if (innerTy is SynType.Ptr) {
                    recordRequiredFromSynType(innerTy.elem)
                }
            }
            super.visitExprUnary(e)
        }

        override fun visitExprMethodCall(e: Expr.MethodCall) {
            val methodRaw = e.method.toString().removePrefix("r#")
            if (methodRaw in POINTER_STRIDE_OR_ACCESS_METHODS) {
                val recvTy = inferLocalSynType(e.receiver)
                if (recvTy is SynType.Ptr) {
                    recordRequiredFromSynType(recvTy.elem)
                }
            }
            super.visitExprMethodCall(e)
        }

        override fun visitExprPath(e: Expr.Path) {
            val constIdent =
                e.path.segments
                    .toList()
                    .lastOrNull()
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
            if (constIdent in SIZED_TYPE_PROPERTY_NAMES) {
                val qself = e.qself
                if (qself != null) {
                    val segs = e.path.segments.toList()
                    val traitSegName =
                        if (qself.position > 0) {
                            segs
                                .getOrNull(qself.position - 1)
                                ?.ident
                                ?.toString()
                                ?.removePrefix("r#")
                        } else {
                            null
                        }
                    if (qself.position == 0 ||
                        traitSegName == "SizedTypeProperties" ||
                        constIdent in setOf("IS_ZST", "LAYOUT", "MAX_SLICE_LEN", "ALIGNMENT")
                    ) {
                        recordRequiredFromSynType(qself.ty)
                    }
                } else {
                    val segs = e.path.segments.toList()
                    if (segs.size == 2) {
                        val ownerRaw = segs[0].ident.toString().removePrefix("r#")
                        val ownerKt = rustTypeIdentToKotlin(ownerRaw)
                        val isSizedProp =
                            ownerRaw != "Self" &&
                                (
                                    constIdent in setOf("IS_ZST", "LAYOUT", "MAX_SLICE_LEN", "ALIGNMENT") ||
                                        !isScreamingSnakeIdent(ownerRaw)
                                ) &&
                                ownerKt !in inspector.unitStructNames &&
                                constIdent !in inspector.declaredConstNames
                        if (isSizedProp) {
                            recordRequiredFromSynType(
                                SynType.Path(
                                    null,
                                    io.github.kotlinmania.syn.Path
                                        .from(segs[0].ident),
                                ),
                            )
                            (segs[0].arguments as? PathArguments.AngleBracketed)
                                ?.args
                                ?.toList()
                                .orEmpty()
                                .filterIsInstance<GenericArgument.TypeArg>()
                                .forEach { recordRequiredFromSynType(it.type) }
                        }
                    } else if (segs.size >= 3 && constIdent in setOf("IS_ZST", "LAYOUT", "MAX_SLICE_LEN", "ALIGNMENT")) {
                        recordRequiredFromSynType(
                            SynType.Path(
                                null,
                                io.github.kotlinmania.syn.Path
                                    .from(segs[0].ident),
                            ),
                        )
                    }
                }
            }
            super.visitExprPath(e)
        }

        override fun visitExprCall(e: Expr.Call) {
            val callArgsList = e.args.toList()
            val path = (e.func as? Expr.Path)?.takeIf { it.qself == null }?.path
            if (path != null) {
                val segs = path.segments.toList()
                val lastSeg = segs.lastOrNull()
                val lastName = lastSeg?.ident?.toString()?.removePrefix("r#")
                val penultName =
                    segs
                        .getOrNull(segs.size - 2)
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                val explicitTypeArgs =
                    ((lastSeg?.arguments as? PathArguments.AngleBracketed)?.args?.toList().orEmpty())
                        .filterIsInstance<GenericArgument.TypeArg>()
                        .map { it.type }

                if (penultName == "intrinsics" && lastName != null) {
                    val consumedIndices = CoreIntrinsicLowering.layoutConsumingTypeArgIndices(lastName, explicitTypeArgs.size)
                    for (idx in consumedIndices) {
                        explicitTypeArgs.getOrNull(idx)?.let(::recordRequiredFromSynType)
                    }
                    if (consumedIndices.contains(0) && explicitTypeArgs.isEmpty() && callArgsList.isNotEmpty()) {
                        val firstArgTy = inferLocalSynType(callArgsList[0])
                        if (firstArgTy is SynType.Ptr) {
                            recordRequiredFromSynType(firstArgTy.elem)
                        } else {
                            recordRequiredFromSynType(firstArgTy)
                        }
                    }
                } else if (penultName == "Layout" && (lastName == "new" || lastName == "array")) {
                    explicitTypeArgs.firstOrNull()?.let(::recordRequiredFromSynType)
                } else if (lastName in KNOWN_STDLIB_FIRST_TYPE_LAYOUT_FUNCTIONS &&
                    (segs.size == 1 || penultName in setOf("mem", "ptr"))
                ) {
                    if (explicitTypeArgs.isNotEmpty()) {
                        recordRequiredFromSynType(explicitTypeArgs.first())
                    } else if (callArgsList.isNotEmpty()) {
                        val firstArgTy = inferLocalSynType(callArgsList[0])
                        if (firstArgTy is SynType.Ptr) {
                            recordRequiredFromSynType(firstArgTy.elem)
                        }
                    }
                } else if (segs.size == 1 && lastName != null) {
                    val targetFn = localFnItems[lastName] ?: sameFileUniqueTopFn(lastName)
                    val targetStart =
                        targetFn
                            ?.sig
                            ?.ident
                            ?.span()
                            ?.start()
                    val targetKey = "::${targetFn?.sig?.ident}@${targetStart?.line}:${targetStart?.column}"
                    if (targetFn != null && targetKey !in visitingFnKeys &&
                        targetFn.sig.generics.params
                            .any { it is GenericParam.TypeParam }
                    ) {
                        val calleeReqs =
                            computeRequiredTypeLayoutParameters(
                                sig = targetFn.sig,
                                block = targetFn.block,
                                ownerGenerics = null,
                                selfTypeName = null,
                                visitingFnKeys = visitingFnKeys + cacheKey + targetKey,
                            )
                        if (calleeReqs.isNotEmpty()) {
                            val calleeParams =
                                targetFn.sig.generics.params
                                    .toList()
                                    .filterIsInstance<GenericParam.TypeParam>()
                                    .map { it.ident.toString().removePrefix("r#") }
                            for ((reqParamName, _) in calleeReqs) {
                                val calleeKtParam = reqParamName.removePrefix("typeLayout")
                                val paramIdx = calleeParams.indexOfFirst { rustTypeIdentToKotlin(it) == calleeKtParam }
                                if (paramIdx >= 0) {
                                    explicitTypeArgs.getOrNull(paramIdx)?.let(::recordRequiredFromSynType)
                                }
                            }
                        }
                    }
                }
            }
            super.visitExprCall(e)
        }
    }.visitBlock(block)

    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val existingTransparentLeaf = transparentConstructorField(selfTypeName, block)?.second
    val computed =
        requiredRawParams
            .map { rustTypeIdentToKotlin(it) }
            .filter { it != existingTransparentLeaf }
            .map { ktParam ->
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.TypeLayout")
                StructLayoutComputer.typeLayoutParamName(ktParam) to "TypeLayout<$ktParam>"
            }
    if (visitingFnKeys.isEmpty() || computed.isNotEmpty()) {
        requiredTypeLayoutParamsCache[cacheKey] = computed
    }
    return computed
}

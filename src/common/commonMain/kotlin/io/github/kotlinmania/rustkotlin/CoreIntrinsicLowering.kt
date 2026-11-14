package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.ExprParse
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.ItemParse
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.parse2

/**
 * Lowers Rust compiler-supplied `core::intrinsics`, `core::arch`, and `alloc` global allocator
 * boundaries into:
 * - Tier A: Pure `Long` address arithmetic and compile-time/runtime `TypeLayout<T>` queries.
 * - Tier B: `static inline __attribute__((always_inline))` C11/SIMD intrinsics (`kmLoad*`, `kmStore*`,
 *   `kmVolatile*`, `kmCopy*`, `kmWriteBytes`, `kmCompareBytes`, `kmAtomic*`, bit/math builtins).
 * - Tier C: Architecture-specific `.S` assembly routines (`kmSpinLoopHint`, `kmBlackBoxU64`,
 *   `kmUmulhU64`, `kmSmulhI64`, `kmAtomicCxchgU128Pair`).
 */
public object CoreIntrinsicLowering {
    public const val DEFAULT_CORE_NAMESPACE: String = "io.github.kotlinmania.rustlang.core"
    public const val DEFAULT_ALLOC_NAMESPACE: String = "io.github.kotlinmania.rustlang.alloc"

    public val STDLIB_CORE_FFI_SYMBOLS: Map<String, String> =
        mapOf(
            "c_void" to "CVoid",
            "c_char" to "CChar",
            "c_schar" to "CSchar",
            "c_uchar" to "CUchar",
            "c_short" to "CShort",
            "c_ushort" to "CUshort",
            "c_int" to "CInt",
            "c_uint" to "CUint",
            "c_long" to "CLong",
            "c_ulong" to "CUlong",
            "c_longlong" to "CLonglong",
            "c_ulonglong" to "CUlonglong",
            "c_float" to "CFloat",
            "c_double" to "CDouble",
            "c_size_t" to "CSizeT",
            "c_ptrdiff_t" to "CPtrdiffT",
            "c_ssize_t" to "CSsizeT",
            "CStr" to "CStr",
        )

    private val ATOMIC_RMW_OP_CODES: Map<String, Int> =
        mapOf(
            "atomic_xadd" to 1,
            "atomic_xsub" to 2,
            "atomic_and" to 3,
            "atomic_nand" to 4,
            "atomic_or" to 5,
            "atomic_xor" to 6,
            "atomic_max" to 7,
            "atomic_min" to 8,
            "atomic_umax" to 9,
            "atomic_umin" to 10,
        )

    private val FIRST_TYPE_ARG_LAYOUT_INTRINSICS: Set<String> =
        setOf(
            "size_of",
            "align_of",
            "min_align_of",
            "pref_align_of",
            "offset",
            "arith_offset",
            "ptr_offset_from",
            "ptr_offset_from_unsigned",
            "read_via_copy",
            "write_via_move",
            "volatile_load",
            "volatile_store",
            "unaligned_volatile_load",
            "unaligned_volatile_store",
            "nontemporal_store",
            "copy",
            "copy_nonoverlapping",
            "volatile_copy_memory",
            "volatile_copy_nonoverlapping_memory",
            "write_bytes",
            "volatile_set_memory",
            "typed_swap_nonoverlapping",
            "raw_eq",
            "atomic_load",
            "atomic_store",
            "atomic_xchg",
            "atomic_cxchg",
            "atomic_cxchgweak",
            "atomic_max",
            "atomic_min",
            "atomic_umax",
            "atomic_umin",
        )

    private val TWO_TYPE_ARG_LAYOUT_INTRINSICS: Set<String> =
        setOf(
            "transmute",
            "transmute_unchecked",
            "atomic_xadd",
            "atomic_xsub",
            "atomic_and",
            "atomic_nand",
            "atomic_or",
            "atomic_xor",
        )

    private val KNOWN_STDLIB_LAYOUT_FUNCTIONS: Set<String> =
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

    public fun resolveCoreNamespace(headerContext: FileHeaderContext): String {
        val pkg = headerContext.packageName?.trim().orEmpty()
        if (pkg == DEFAULT_CORE_NAMESPACE || pkg.startsWith("$DEFAULT_CORE_NAMESPACE.")) {
            return DEFAULT_CORE_NAMESPACE
        }
        val configured = headerContext.crateNamespaces["core"]?.trim()?.removeSuffix(".")
        if (!configured.isNullOrEmpty()) {
            return configured
        }
        return DEFAULT_CORE_NAMESPACE
    }

    public fun resolveAllocNamespace(headerContext: FileHeaderContext): String {
        val pkg = headerContext.packageName?.trim().orEmpty()
        if (pkg == DEFAULT_ALLOC_NAMESPACE || pkg.startsWith("$DEFAULT_ALLOC_NAMESPACE.")) {
            return DEFAULT_ALLOC_NAMESPACE
        }
        val configured = headerContext.crateNamespaces["alloc"]?.trim()?.removeSuffix(".")
        if (!configured.isNullOrEmpty()) {
            return configured
        }
        return DEFAULT_ALLOC_NAMESPACE
    }

    public fun layoutConsumingTypeArgIndices(
        intrinsicName: String,
        explicitTypeArgCount: Int,
    ): List<Int> {
        val canonical = parseLegacyAtomicOrderingSuffix(intrinsicName).baseName
        return when {
            canonical in TWO_TYPE_ARG_LAYOUT_INTRINSICS ->
                if (explicitTypeArgCount >= 2) listOf(0, 1) else listOf(0)
            canonical in FIRST_TYPE_ARG_LAYOUT_INTRINSICS -> listOf(0)
            else -> emptyList()
        }
    }

    private data class ParsedIntrinsicName(
        val baseName: String,
        val ord0: String? = null,
        val ord1: String? = null,
    )

    private val LEGACY_ORDERING_CODES: Map<String, String> =
        mapOf(
            "relaxed" to "0",
            "release" to "1",
            "acquire" to "2",
            "acqrel" to "3",
            "seqcst" to "4",
            "ord" to "4",
        )

    private fun parseLegacyAtomicOrderingSuffix(rawName: String): ParsedIntrinsicName {
        if (!rawName.startsWith("atomic_")) return ParsedIntrinsicName(rawName)
        for ((suf2, code2) in LEGACY_ORDERING_CODES) {
            if (rawName.endsWith("_$suf2")) {
                val withoutLast = rawName.removeSuffix("_$suf2")
                for ((suf1, code1) in LEGACY_ORDERING_CODES) {
                    if (withoutLast.endsWith("_$suf1")) {
                        val base2 = withoutLast.removeSuffix("_$suf1")
                        if (base2 == "atomic_cxchg" || base2 == "atomic_cxchgweak") {
                            return ParsedIntrinsicName(base2, code1, code2)
                        }
                    }
                }
                if (withoutLast in ATOMIC_RMW_OP_CODES ||
                    withoutLast in
                    setOf(
                        "atomic_load",
                        "atomic_store",
                        "atomic_xchg",
                        "atomic_fence",
                        "atomic_singlethreadfence",
                        "atomic_cxchg",
                        "atomic_cxchgweak",
                    )
                ) {
                    return ParsedIntrinsicName(withoutLast, code2, code2)
                }
            }
        }
        return ParsedIntrinsicName(rawName)
    }

    internal var activeRustcIntrinsicDeclarationName: String? = null

    internal fun registerCoreImport(emitter: KotlinJ2KEmitter, fqSymbol: String) {
        val targetPkg = fqSymbol.substringBeforeLast('.', missingDelimiterValue = "")
        if (targetPkg.isNotEmpty() && targetPkg != emitter.headerContext.packageName) {
            emitter.extraTypePathImports += "import $fqSymbol"
        }
    }

    internal fun coreIntrinsicSignatureParameters(
        sig: Signature,
        block: Block?,
        ownerGenerics: Generics?,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): List<Pair<String, String>> {
        val rawFnName = sig.ident.toString().removePrefix("r#")
        if (activeRustcIntrinsicDeclarationName == rawFnName && !emitter.headerContext.rawAbiMode) {
            val typeParams =
                sig.generics.params
                    .toList()
                    .filterIsInstance<GenericParam.TypeParam>()
                    .map { rustTypeIdentToKotlin(it.ident.toString()) }
            val indices = layoutConsumingTypeArgIndices(rawFnName, typeParams.size)
            if (indices.isNotEmpty()) {
                val coreNs = resolveCoreNamespace(emitter.headerContext)
                registerCoreImport(emitter, "$coreNs.mem.TypeLayout")
                return indices.mapNotNull { idx ->
                    val ktParam = typeParams.getOrNull(idx) ?: return@mapNotNull null
                    StructLayoutComputer.typeLayoutParamName(ktParam) to "TypeLayout<$ktParam>"
                }
            }
        }
        return emitter.computeRequiredTypeLayoutParameters(
            sig = sig,
            block = block,
            ownerGenerics = ownerGenerics,
            selfTypeName = selfTypeName,
        )
    }

    internal fun coreIntrinsicCallArguments(
        call: Expr.Call,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): List<String> {
        if (emitter.headerContext.rawAbiMode) return emptyList()
        val path = (call.func as? Expr.Path)?.takeIf { it.qself == null }?.path ?: return emptyList()
        val segs = path.segments.toList()
        val lastSeg = segs.lastOrNull() ?: return emptyList()
        val rawName = lastSeg.ident.toString().removePrefix("r#")
        val penultName =
            segs
                .getOrNull(segs.size - 2)
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
        val explicitTypes =
            ((lastSeg.arguments as? PathArguments.AngleBracketed)?.args?.toList().orEmpty())
                .filterIsInstance<GenericArgument.TypeArg>()
                .map { it.type }

        // 1. Same-file free or local function requiring `typeLayout<P>`
        if (segs.size == 1) {
            val sameFileFn = emitter.sameFileUniqueTopFn(rawName)
            if (sameFileFn != null) {
                val required =
                    emitter.computeRequiredTypeLayoutParameters(
                        sig = sameFileFn.sig,
                        block = sameFileFn.block,
                        ownerGenerics = null,
                        selfTypeName = null,
                    )
                if (required.isNotEmpty()) {
                    val fnTypeParams =
                        sameFileFn.sig.generics.params
                            .toList()
                            .filterIsInstance<GenericParam.TypeParam>()
                            .map { it.ident.toString().removePrefix("r#") }
                    val typedInputs =
                        sameFileFn.sig.inputs
                            .toList()
                            .filterIsInstance<FnArg.Typed>()
                    return required.mapNotNull { (reqLayoutParamName, _) ->
                        val calleeKtParam = reqLayoutParamName.removePrefix("typeLayout")
                        val paramIdx = fnTypeParams.indexOfFirst { rustTypeIdentToKotlin(it) == calleeKtParam }
                        val rawParamName = fnTypeParams.getOrNull(paramIdx) ?: return@mapNotNull null
                        val specified = explicitTypes.getOrNull(paramIdx)?.takeUnless { it is SynType.Infer }
                        val inferredFromArg =
                            if (specified == null) {
                                val argIdx =
                                    typedInputs.indexOfFirst { input ->
                                        val ty = input.patType.ty
                                        (ty is SynType.Path && ty.path.isIdent(rawParamName)) ||
                                            (ty is SynType.Ptr && (ty.elem as? SynType.Path)?.path?.isIdent(rawParamName) == true)
                                    }
                                val callArg = call.args.toList().getOrNull(argIdx)
                                val initTy = callArg?.let { emitter.sourceInitializerType(it) }
                                if (initTy is SynType.Ptr && typedInputs.getOrNull(argIdx)?.patType?.ty is SynType.Ptr) {
                                    initTy.elem
                                } else {
                                    initTy
                                }
                            } else {
                                null
                            }
                        val actualTy = specified ?: inferredFromArg ?: return@mapNotNull null
                        val layoutExpr = resolveTypeLayoutExpression(actualTy, selfTypeName, emitter)
                        "$reqLayoutParamName = $layoutExpr"
                    }
                }
            }
        }

        // 2. Stdlib layout-consuming calls (`size_of::<T>()`, `align_of::<T>()`, `ptr::read(p)`, `Layout::new::<T>()`, etc.)
        val isLayoutNewOrArray = penultName == "Layout" && (rawName == "new" || rawName == "array")
        val isStdlibMemOrPtrFn =
            rawName in KNOWN_STDLIB_LAYOUT_FUNCTIONS &&
                (segs.size == 1 || penultName in setOf("mem", "ptr")) &&
                rawName !in emitter.inspector.declaredTopLevelFnRawNames
        if (isLayoutNewOrArray || isStdlibMemOrPtrFn) {
            if (isStdlibMemOrPtrFn &&
                "core" in emitter.headerContext.crateNamespaces &&
                penultName != "ptr" &&
                rawName in setOf("size_of", "align_of", "size_of_val", "align_of_val")
            ) {
                registerCoreImport(
                    emitter,
                    "${resolveCoreNamespace(emitter.headerContext)}.mem.${rustFunctionIdentToKotlin(rawName)}",
                )
            }
            val callArgsList = call.args.toList()
            val targetSynType =
                explicitTypes.firstOrNull()?.takeUnless { it is SynType.Infer }
                    ?: callArgsList.firstOrNull()?.let { firstArg ->
                        val argTy = emitter.sourceInitializerType(firstArg)
                        if (argTy is SynType.Ptr) argTy.elem else argTy
                    }
            if (targetSynType != null) {
                val layoutExpr = resolveTypeLayoutExpression(targetSynType, selfTypeName, emitter)
                return listOf("typeLayoutT = $layoutExpr")
            }
        }
        return emptyList()
    }

    internal fun resolveTypeLayoutExpression(
        type: SynType,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): String {
        emitter.sourcePointerLayout(type, selfTypeName)?.let { return it }
        val coreNs = resolveCoreNamespace(emitter.headerContext)
        val memPkg = "$coreNs.mem"
        val resolved = emitter.resolveLayoutType(type, emptyMap())
        val rendered = emitter.renderType(resolved, selfTypeName)

        // Check in-scope `typeLayout<T>` dictionary parameters
        val expectedLayoutType = "$memPkg.TypeLayout<$rendered>"
        emitter.currentTypedLocals
            .filterValues { it == expectedLayoutType }
            .keys
            .singleOrNull()
            ?.let { return it }
        val candidateParam = StructLayoutComputer.typeLayoutParamName(rendered)
        if (candidateParam in emitter.currentTypedLocals) {
            return candidateParam
        }

        // Same-file struct with synthesized companion `typeLayout(...)`
        val structSimple = rendered.substringBefore('<').substringAfterLast('.')
        val structDecl = emitter.sameFileUniqueSimpleStruct(structSimple)
        if (structDecl != null) {
            val computed = StructLayoutComputer.computeStructLayout(structDecl, emitter)
            if (computed != null && computed.requiredTypeLayoutParams.isEmpty()) {
                return "$structSimple.typeLayout()"
            }
        }

        // Heap / erased object fallback: 64-bit stable object-handle slot in linear memory
        registerCoreImport(emitter, "$memPkg.ObjectSlotLayout")
        return "ObjectSlotLayout<$rendered>()"
    }

    internal fun renderConstEvalSelectMacro(
        mac: Macro,
        emitter: KotlinJ2KEmitter,
        indent: String,
        selfTypeName: String?,
    ): String? {
        val macroName =
            mac.path.segments
                .last()
                ?.ident
                ?.toString()
                ?.removePrefix("r#") ?: return null
        if (macroName == "assert_unsafe_precondition") {
            return "Unit"
        }
        if (macroName != "const_eval_select") {
            return null
        }
        val tokens = mac.tokens.clone().toList()
        // Form 1: `const_eval_select!(@capture { ... } -> Ret: if const { ... } else { runtime_body })`
        for (idx in tokens.indices) {
            val ident = (tokens[idx] as? TokenTree.Ident)?.value?.toString()
            if (ident == "else") {
                val elseGroup = tokens.subList(idx + 1, tokens.size).firstOrNull { it is TokenTree.Group && it.value.delimiter() == Delimiter.Brace } as? TokenTree.Group
                if (elseGroup != null) {
                    val blockSrc = "{ ${elseGroup.value.stream()} }"
                    val parsedExpr = lexTokenStreamOrNull(blockSrc)?.let { parse2(ExprParse::parse, it).getOrNull() }
                    if (parsedExpr != null) {
                        return emitter.renderExpr(parsedExpr, indent, selfTypeName)
                    }
                }
            }
        }
        // Form 2: `const_eval_select!((arg0, arg1), const_fn, runtime_fn)`
        val topArgs = splitMacroTopLevelCommaArgs(tokens)
        if (topArgs.size >= 3) {
            val tupleTree = topArgs[0].singleOrNull() as? TokenTree.Group
            val runtimeFnSrc = topArgs[2].joinToString("") { it.toString() }.trim()
            val argTupleSrc =
                tupleTree
                    ?.value
                    ?.stream()
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            val callSrc = "$runtimeFnSrc($argTupleSrc)"
            val parsedCall = lexTokenStreamOrNull(callSrc)?.let { parse2(ExprParse::parse, it).getOrNull() }
            if (parsedCall != null) {
                return emitter.renderExpr(parsedCall, indent, selfTypeName)
            }
        }
        return null
    }

    private fun splitMacroTopLevelCommaArgs(tokens: List<TokenTree>): List<List<TokenTree>> {
        val result = mutableListOf<List<TokenTree>>()
        val current = mutableListOf<TokenTree>()
        for (tok in tokens) {
            if (tok is TokenTree.Punct && tok.value.asChar() == ',') {
                result += current.toList()
                current.clear()
            } else {
                current += tok
            }
        }
        if (current.isNotEmpty()) result += current.toList()
        return result
    }

    internal fun renderRustcIntrinsicVerbatimItem(
        item: Item.Verbatim,
        emitter: KotlinJ2KEmitter,
        indent: String,
    ): String? {
        val rawSrc = item.tokens.toString().trim()
        if (!rawSrc.contains("rustc_intrinsic") || !rawSrc.endsWith(";")) {
            return null
        }
        val withEmptyBody = rawSrc.dropLast(1) + " {}"
        val parsedFn = (lexTokenStreamOrNull(withEmptyBody)?.let { parse2(ItemParse::parse, it).getOrNull() } as? Item.Fn) ?: return null
        val rawFnName =
            parsedFn.sig.ident
                .toString()
                .removePrefix("r#")
        val synthesizedBody = renderSynthesizedIntrinsicFnBody(parsedFn, rawFnName, emitter, indent) ?: return null
        val prevIntrinsicDecl = activeRustcIntrinsicDeclarationName
        activeRustcIntrinsicDeclarationName = rawFnName
        val fnWithStubBody =
            try {
                emitter.renderItemFn(parsedFn, indent, mutableMapOf())
            } finally {
                activeRustcIntrinsicDeclarationName = prevIntrinsicDecl
            }
        val emptyBraceIdx = fnWithStubBody.lastIndexOf("{}")
        if (emptyBraceIdx >= 0) {
            return fnWithStubBody.substring(0, emptyBraceIdx) + "{\n$indent    return $synthesizedBody\n$indent}"
        }
        return fnWithStubBody
    }

    private fun renderSynthesizedIntrinsicFnBody(
        fnItem: Item.Fn,
        rawName: String,
        emitter: KotlinJ2KEmitter,
        indent: String,
    ): String? {
        val paramIdents =
            fnItem.sig.inputs.toList().filterIsInstance<FnArg.Typed>().mapIndexed { idx, input ->
                val raw = (input.patType.pat as? Pat.Ident)?.ident?.toString()?.removePrefix("r#")
                if (raw == null || raw == "_") "arg$idx" else rustValueIdentToKotlin(raw)
            }
        val typeParams =
            fnItem.sig.generics.params
                .toList()
                .filterIsInstance<GenericParam.TypeParam>()
                .map { rustTypeIdentToKotlin(it.ident.toString()) }
        val constParams =
            fnItem.sig.generics.params
                .toList()
                .filterIsInstance<GenericParam.ConstParam>()
                .map { rustValueIdentToKotlin(it.ident.toString()) }
        val coreNs = resolveCoreNamespace(emitter.headerContext)
        val intrPkg = "$coreNs.intrinsics"
        val memPkg = "$coreNs.mem"
        val ptrPkg = "$coreNs.ptr"
        val t0 = typeParams.getOrNull(0) ?: "Any"
        val t1 = typeParams.getOrNull(1) ?: t0
        val layout0 = StructLayoutComputer.typeLayoutParamName(t0)
        val layout1 = StructLayoutComputer.typeLayoutParamName(t1)
        val p0 = paramIdents.getOrNull(0) ?: "arg0"
        val p1 = paramIdents.getOrNull(1) ?: "arg1"
        val p2 = paramIdents.getOrNull(2) ?: "arg2"
        val parsedAtomic = parseLegacyAtomicOrderingSuffix(rawName)
        val canonicalIntrinsicName = parsedAtomic.baseName
        val ord0 = constParams.getOrNull(0)?.let { "($it).ordinal" } ?: parsedAtomic.ord0 ?: "4"
        val ord1 = constParams.getOrNull(1)?.let { "($it).ordinal" } ?: parsedAtomic.ord1 ?: "4"

        return when (canonicalIntrinsicName) {
            "abort" -> {
                registerCoreImport(emitter, "$intrPkg.kmAbort")
                "kmAbort()"
            }
            "unreachable" -> "error(\"Entered Rust intrinsics::unreachable\")"
            "assume", "breakpoint", "forget", "assert_inhabited", "assert_zero_valid", "assert_mem_uninitialized_valid" -> "Unit"
            "likely", "unlikely" -> p0
            "cold_path" -> "Unit"
            "rustc_peek" -> p0
            "black_box" -> p0
            "size_of" -> if (emitter.headerContext.rawAbiMode) "$layout0.sizeBytes.toULong()" else "$layout0.sizeBytes.toInt()"
            "align_of", "min_align_of", "pref_align_of" ->
                if (emitter.headerContext.rawAbiMode) "$layout0.alignBytes.toULong()" else "$layout0.alignBytes.toInt()"
            "needs_drop" -> "true"
            "type_name" -> t0.let { "\"$it\"" }
            "arith_offset" -> {
                registerCoreImport(emitter, "$ptrPkg.ConstPtr")
                "ConstPtr($p0.rawAddress + ($p1).toLong() * $layout0.strideBytes)"
            }
            "ptr_offset_from" ->
                if (emitter.headerContext.rawAbiMode) {
                    "(($p0.rawAddress - $p1.rawAddress) / maxOf(1L, $layout0.sizeBytes))"
                } else {
                    "(($p0.rawAddress - $p1.rawAddress) / maxOf(1L, $layout0.sizeBytes)).toInt()"
                }
            "ptr_offset_from_unsigned" ->
                if (emitter.headerContext.rawAbiMode) {
                    "((($p0.rawAddress - $p1.rawAddress) / maxOf(1L, $layout0.sizeBytes)).toULong())"
                } else {
                    "(($p0.rawAddress - $p1.rawAddress) / maxOf(1L, $layout0.sizeBytes)).toInt()"
                }
            "ptr_mask" -> {
                registerCoreImport(emitter, "$ptrPkg.ConstPtr")
                "ConstPtr($p0.rawAddress and ($p1).toLong())"
            }
            "read_via_copy" -> "$layout0.readNative($p0.rawAddress)"
            "write_via_move", "nontemporal_store" -> "$layout0.writeNative($p0.rawAddress, $p1)"
            "volatile_load", "unaligned_volatile_load" -> "$layout0.readVolatileNative($p0.rawAddress)"
            "volatile_store", "unaligned_volatile_store" -> "$layout0.writeVolatileNative($p0.rawAddress, $p1)"
            "copy_nonoverlapping", "volatile_copy_nonoverlapping_memory" -> {
                registerCoreImport(emitter, "$intrPkg.kmCopyNonoverlapping")
                "kmCopyNonoverlapping($p1.rawAddress, $p0.rawAddress, ($p2).toLong() * $layout0.sizeBytes)"
            }
            "copy", "volatile_copy_memory" -> {
                registerCoreImport(emitter, "$intrPkg.kmCopy")
                "kmCopy($p1.rawAddress, $p0.rawAddress, ($p2).toLong() * $layout0.sizeBytes)"
            }
            "write_bytes", "volatile_set_memory" -> {
                registerCoreImport(emitter, "$intrPkg.kmWriteBytes")
                "kmWriteBytes($p0.rawAddress, $p1, ($p2).toLong() * $layout0.sizeBytes)"
            }
            "compare_bytes" -> {
                registerCoreImport(emitter, "$intrPkg.kmCompareBytes")
                "kmCompareBytes($p0.rawAddress, $p1.rawAddress, ($p2).toLong())"
            }
            "transmute", "transmute_unchecked" -> {
                registerCoreImport(emitter, "$memPkg.transmuteWithLayouts")
                "transmuteWithLayouts($p0, $layout0, $layout1)"
            }
            "atomic_fence" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicFence")
                "kmAtomicFence($ord0)"
            }
            "atomic_singlethreadfence" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicSignalFence")
                "kmAtomicSignalFence($ord0)"
            }
            "atomic_load" -> {
                registerCoreImport(emitter, "$memPkg.atomicLoadWithLayout")
                "atomicLoadWithLayout($p0.rawAddress, $ord0, $layout0)"
            }
            "atomic_store" -> {
                registerCoreImport(emitter, "$memPkg.atomicStoreWithLayout")
                "atomicStoreWithLayout($p0.rawAddress, $p1, $ord0, $layout0)"
            }
            "atomic_xchg" -> {
                registerCoreImport(emitter, "$memPkg.atomicXchgWithLayout")
                "atomicXchgWithLayout($p0.rawAddress, $p1, $ord0, $layout0)"
            }
            "atomic_cxchg", "atomic_cxchgweak" -> {
                registerCoreImport(emitter, "$memPkg.atomicCxchgWithLayout")
                "atomicCxchgWithLayout($p0.rawAddress, $p1, $p2, $ord0, $ord1, $layout0)"
            }
            in ATOMIC_RMW_OP_CODES -> {
                val opCode = ATOMIC_RMW_OP_CODES.getValue(canonicalIntrinsicName)
                registerCoreImport(emitter, "$memPkg.atomicRmwWithLayout")
                val opLayout = if (typeParams.size >= 2) layout1 else layout0
                "atomicRmwWithLayout($p0.rawAddress, $p1, $opCode, $ord0, $layout0, $opLayout)"
            }
            "sqrtf32" -> {
                registerCoreImport(emitter, "$intrPkg.kmSqrtf32")
                "kmSqrtf32($p0)"
            }
            "sqrtf64" -> {
                registerCoreImport(emitter, "$intrPkg.kmSqrtf64")
                "kmSqrtf64($p0)"
            }
            "fmaf32" -> {
                registerCoreImport(emitter, "$intrPkg.kmFmaf32")
                "kmFmaf32($p0, $p1, $p2)"
            }
            "fmaf64" -> {
                registerCoreImport(emitter, "$intrPkg.kmFmaf64")
                "kmFmaf64($p0, $p1, $p2)"
            }
            "ctpop" -> {
                registerCoreImport(emitter, "$intrPkg.kmCtpopU64")
                "kmCtpopU64(($p0).toULong())"
            }
            "ctlz", "ctlz_nonzero" -> {
                registerCoreImport(emitter, "$intrPkg.kmCtlzU64")
                "kmCtlzU64(($p0).toULong())"
            }
            "cttz", "cttz_nonzero" -> {
                registerCoreImport(emitter, "$intrPkg.kmCttzU64")
                "kmCttzU64(($p0).toULong())"
            }
            "bswap" -> {
                registerCoreImport(emitter, "$intrPkg.kmBswapU64")
                "kmBswapU64(($p0).toULong())"
            }
            "bitreverse" -> {
                registerCoreImport(emitter, "$intrPkg.kmBitreverseU64")
                "kmBitreverseU64(($p0).toULong())"
            }
            else -> null
        }
    }

    internal fun renderCoreIntrinsicCall(
        call: Expr.Call,
        emitter: KotlinJ2KEmitter,
        indent: String,
        selfTypeName: String?,
    ): String? {
        val path = (call.func as? Expr.Path)?.takeIf { it.qself == null }?.path ?: return null
        val segments = path.segments.toList()
        if (segments.isEmpty()) return null
        val lastSeg = segments.last()
        val rawName = lastSeg.ident.toString().removePrefix("r#")
        val penultName =
            segments
                .getOrNull(segments.size - 2)
                ?.ident
                ?.toString()
                ?.removePrefix("r#")

        // Support `alloc::alloc::__rust_alloc` / `__rust_dealloc` / `__rust_realloc` / `__rust_alloc_zeroed`
        if (rawName in setOf("__rust_alloc", "__rust_dealloc", "__rust_realloc", "__rust_alloc_zeroed")) {
            val coreNs = resolveCoreNamespace(emitter.headerContext)
            val intrPkg = "$coreNs.intrinsics"
            val ptrPkg = "$coreNs.ptr"
            val args = call.args.toList().map { emitter.renderExpr(it, indent, selfTypeName) }
            registerCoreImport(emitter, "$ptrPkg.MutPtr")
            return when (rawName) {
                "__rust_alloc" -> {
                    registerCoreImport(emitter, "$intrPkg.kmAlloc")
                    "MutPtr(kmAlloc((${args[0]}).toLong(), (${args[1]}).toLong()))"
                }
                "__rust_alloc_zeroed" -> {
                    registerCoreImport(emitter, "$intrPkg.kmAllocZeroed")
                    "MutPtr(kmAllocZeroed((${args[0]}).toLong(), (${args[1]}).toLong()))"
                }
                "__rust_realloc" -> {
                    registerCoreImport(emitter, "$intrPkg.kmRealloc")
                    "MutPtr(kmRealloc((${args[0]}).rawAddress, (${args[1]}).toLong(), (${args[2]}).toLong(), (${args[3]}).toLong()))"
                }
                "__rust_dealloc" -> {
                    registerCoreImport(emitter, "$intrPkg.kmDealloc")
                    "kmDealloc((${args[0]}).rawAddress, (${args[1]}).toLong(), (${args[2]}).toLong())"
                }
                else -> null
            }
        }

        val callArgsList = call.args.toList()

        // Support `core::hint::spin_loop()` / `hint::black_box(...)`
        if (penultName == "hint" && rawName == "spin_loop" && callArgsList.isEmpty()) {
            val coreNs = resolveCoreNamespace(emitter.headerContext)
            registerCoreImport(emitter, "$coreNs.intrinsics.kmSpinLoopHint")
            return "kmSpinLoopHint()"
        }
        if (penultName == "hint" && rawName == "black_box" && callArgsList.size == 1) {
            val firstArgExpr = callArgsList[0]
            val argRendered = emitter.renderExpr(firstArgExpr, indent, selfTypeName)
            val argKind = emitter.inferExprNumericKind(firstArgExpr, selfTypeName)
            if (argKind == NumericKind.U64) {
                val coreNs = resolveCoreNamespace(emitter.headerContext)
                registerCoreImport(emitter, "$coreNs.intrinsics.kmBlackBoxU64")
                return "kmBlackBoxU64($argRendered)"
            }
            if (argKind == NumericKind.I64) {
                val coreNs = resolveCoreNamespace(emitter.headerContext)
                registerCoreImport(emitter, "$coreNs.intrinsics.kmBlackBoxU64")
                return "kmBlackBoxU64(($argRendered).toULong()).toLong()"
            }
            return argRendered
        }

        val isIntrinsicsModuleCall =
            penultName == "intrinsics" ||
                (segments.size == 1 && emitter.headerContext.packageName?.endsWith(".intrinsics") == true)
        if (!isIntrinsicsModuleCall) {
            return null
        }

        val coreNs = resolveCoreNamespace(emitter.headerContext)
        val intrPkg = "$coreNs.intrinsics"
        val memPkg = "$coreNs.mem"
        val ptrPkg = "$coreNs.ptr"
        val genericArgs = (lastSeg.arguments as? PathArguments.AngleBracketed)?.args?.toList().orEmpty()
        val explicitTypes = genericArgs.filterIsInstance<GenericArgument.TypeArg>().map { it.type }
        val explicitConsts = genericArgs.filterIsInstance<GenericArgument.ConstArg>().map { it.expr }
        val renderedArgs = callArgsList.map { emitter.renderExpr(it, indent, selfTypeName) }

        val parsedAtomic = parseLegacyAtomicOrderingSuffix(rawName)
        val canonicalIntrinsicName = parsedAtomic.baseName

        fun orderingCode(constIdx: Int): String {
            val legacyOrd = if (constIdx == 0) parsedAtomic.ord0 else parsedAtomic.ord1
            if (legacyOrd != null && constIdx >= explicitConsts.size) return legacyOrd
            var expr = explicitConsts.getOrNull(constIdx) ?: return legacyOrd ?: "4"
            while (true) {
                expr =
                    when (val unwrapped = emitter.unwrapParenOrGroupExpr(expr)) {
                        is Expr.BlockExpr -> (unwrapped.block.stmts.singleOrNull() as? Stmt.ExprStmt)?.expr ?: break
                        else -> unwrapped
                    }
                if (expr !is Expr.BlockExpr) break
            }
            val variant =
                (expr as? Expr.Path)
                    ?.path
                    ?.segments
                    ?.last()
                    ?.ident
                    ?.toString()
            return when (variant) {
                "Relaxed" -> "0"
                "Release" -> "1"
                "Acquire" -> "2"
                "AcqRel" -> "3"
                "SeqCst" -> "4"
                else -> "(${emitter.renderExpr(expr, indent, selfTypeName)}).ordinal"
            }
        }

        fun rawAddrOf(argIdx: Int): String {
            val argExpr = callArgsList.getOrNull(argIdx)
            val rendered = renderedArgs[argIdx]
            if (emitter.headerContext.rawAbiMode) return rendered
            val inferred = argExpr?.let { emitter.inferExprRenderedType(it, selfTypeName) }
            val atom = if (argExpr != null && emitter.isAtomicPostfixReceiver(argExpr, rendered)) rendered else "($rendered)"
            return if (inferred == "Long") rendered else "$atom.rawAddress"
        }

        return when (canonicalIntrinsicName) {
            "abort" -> {
                registerCoreImport(emitter, "$intrPkg.kmAbort")
                "kmAbort()"
            }
            "unreachable" -> "error(\"Entered Rust intrinsics::unreachable\")"
            "assume", "breakpoint", "forget", "cold_path", "assert_inhabited", "assert_zero_valid", "assert_mem_uninitialized_valid",
            "prefetch_read_data", "prefetch_write_data", "prefetch_read_instruction", "prefetch_write_instruction",
            -> "Unit"
            "likely", "unlikely", "rustc_peek" -> renderedArgs.firstOrNull() ?: "Unit"
            "ub_checks", "overflow_checks", "is_val_statically_known" -> "false"
            "black_box" -> {
                val arg0 = renderedArgs.firstOrNull() ?: return "Unit"
                val kind = callArgsList.firstOrNull()?.let { emitter.inferExprNumericKind(it, selfTypeName) }
                if (kind == NumericKind.U64) {
                    registerCoreImport(emitter, "$intrPkg.kmBlackBoxU64")
                    "kmBlackBoxU64($arg0)"
                } else {
                    arg0
                }
            }
            "size_of" -> {
                val ty = explicitTypes.firstOrNull() ?: return null
                val layout = resolveTypeLayoutExpression(ty, selfTypeName, emitter)
                if (emitter.headerContext.rawAbiMode) "($layout).sizeBytes.toULong()" else "($layout).sizeBytes.toInt()"
            }
            "align_of", "min_align_of", "pref_align_of" -> {
                val ty = explicitTypes.firstOrNull() ?: return null
                val layout = resolveTypeLayoutExpression(ty, selfTypeName, emitter)
                if (emitter.headerContext.rawAbiMode) "($layout).alignBytes.toULong()" else "($layout).alignBytes.toInt()"
            }
            "needs_drop" -> {
                val ty = explicitTypes.firstOrNull() ?: return "true"
                val primName =
                    (ty as? SynType.Path)
                        ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                        ?.path
                        ?.segments
                        ?.last()
                        ?.ident
                        ?.toString()
                val isTrivial =
                    ty is SynType.Ptr ||
                        ty is SynType.Reference ||
                        primName in setOf("bool", "char", "i8", "u8", "i16", "u16", "i32", "u32", "i64", "u64", "i128", "u128", "isize", "usize", "f32", "f64")
                if (isTrivial) "false" else "true"
            }
            "type_name" -> {
                val ty = explicitTypes.firstOrNull()
                val label = ty?.let { emitter.renderType(it, selfTypeName) } ?: "Any"
                "\"$label\""
            }
            "offset", "arith_offset" -> {
                val firstArg = callArgsList.firstOrNull() ?: return null
                val ptrSynType =
                    (explicitTypes.firstOrNull() as? SynType.Ptr)
                        ?: (emitter.sourceInitializerType(firstArg) as? SynType.Ptr)
                val pointeeSynType =
                    ptrSynType?.elem
                        ?: explicitTypes.firstOrNull()?.takeUnless { it is SynType.Ptr }
                val inferredPtrRendered = emitter.inferExprRenderedType(firstArg, selfTypeName)
                val isMut =
                    (ptrSynType?.mutability is PointerMutability.Mut) ||
                        inferredPtrRendered?.substringAfterLast('.')?.startsWith("MutPtr<") == true
                val ctor = if (isMut) "MutPtr" else "ConstPtr"
                registerCoreImport(emitter, "$ptrPkg.$ctor")
                val strideExpr =
                    if (pointeeSynType != null) {
                        val layout = resolveTypeLayoutExpression(pointeeSynType, selfTypeName, emitter)
                        "($layout).strideBytes"
                    } else {
                        "1L"
                    }
                "$ctor(${rawAddrOf(0)} + (${renderedArgs[1]}).toLong() * $strideExpr)"
            }
            "ptr_offset_from", "ptr_offset_from_unsigned" -> {
                val pointeeSynType =
                    explicitTypes.firstOrNull()
                        ?: (callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) } as? SynType.Ptr)?.elem
                        ?: (callArgsList.getOrNull(1)?.let { emitter.sourceInitializerType(it) } as? SynType.Ptr)?.elem
                val sizeExpr =
                    if (pointeeSynType != null) {
                        val layout = resolveTypeLayoutExpression(pointeeSynType, selfTypeName, emitter)
                        "maxOf(1L, ($layout).sizeBytes)"
                    } else {
                        "1L"
                    }
                val diffDiv = "((${rawAddrOf(0)} - ${rawAddrOf(1)}) / $sizeExpr)"
                when {
                    canonicalIntrinsicName == "ptr_offset_from_unsigned" && emitter.headerContext.rawAbiMode -> "$diffDiv.toULong()"
                    emitter.headerContext.rawAbiMode -> diffDiv
                    else -> "$diffDiv.toInt()"
                }
            }
            "ptr_mask" -> {
                registerCoreImport(emitter, "$ptrPkg.ConstPtr")
                "ConstPtr(${rawAddrOf(0)} and (${renderedArgs[1]}).toLong())"
            }
            "aggregate_raw_ptr" -> {
                val isMut = emitter.currentEnclosingReturnRenderedType?.substringAfterLast('.')?.startsWith("MutPtr<") == true
                val ctor = if (isMut) "MutPtr" else "ConstPtr"
                registerCoreImport(emitter, "$ptrPkg.$ctor")
                "$ctor(${rawAddrOf(0)}, (${renderedArgs[1]}).toLong())"
            }
            "ptr_metadata" -> {
                val base = "${renderedArgs[0]}.rawMeta"
                if (emitter.headerContext.rawAbiMode) "$base.toULong()" else "$base.toInt()"
            }
            "read_via_copy", "volatile_load", "unaligned_volatile_load" -> {
                val isVolatile = rawName != "read_via_copy"
                val pointee =
                    explicitTypes.firstOrNull()
                        ?: (callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) } as? SynType.Ptr)?.elem
                val directLoad = pointee?.let { renderDirectScalarLoad(it, rawAddrOf(0), isVolatile, intrPkg, emitter) }
                if (directLoad != null) {
                    directLoad
                } else if (pointee != null) {
                    val layout = resolveTypeLayoutExpression(pointee, selfTypeName, emitter)
                    val method = if (isVolatile) "readVolatileNative" else "readNative"
                    "($layout).$method(${rawAddrOf(0)})"
                } else {
                    null
                }
            }
            "write_via_move", "volatile_store", "unaligned_volatile_store", "nontemporal_store" -> {
                val isVolatile = rawName == "volatile_store" || rawName == "unaligned_volatile_store"
                val pointee =
                    explicitTypes.firstOrNull()
                        ?: (callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) } as? SynType.Ptr)?.elem
                val directStore = pointee?.let { renderDirectScalarStore(it, rawAddrOf(0), renderedArgs[1], isVolatile, intrPkg, emitter) }
                if (directStore != null) {
                    directStore
                } else if (pointee != null) {
                    val layout = resolveTypeLayoutExpression(pointee, selfTypeName, emitter)
                    val method = if (isVolatile) "writeVolatileNative" else "writeNative"
                    "($layout).$method(${rawAddrOf(0)}, ${renderedArgs[1]})"
                } else {
                    null
                }
            }
            "copy_nonoverlapping", "volatile_copy_nonoverlapping_memory", "copy", "volatile_copy_memory" -> {
                val isNonOverlapping = rawName.contains("nonoverlapping")
                val fnName = if (isNonOverlapping) "kmCopyNonoverlapping" else "kmCopy"
                registerCoreImport(emitter, "$intrPkg.$fnName")
                val srcIdx = if (rawName.startsWith("volatile_")) 1 else 0
                val dstIdx = if (rawName.startsWith("volatile_")) 0 else 1
                val pointee =
                    explicitTypes.firstOrNull()
                        ?: (callArgsList.getOrNull(srcIdx)?.let { emitter.sourceInitializerType(it) } as? SynType.Ptr)?.elem
                val elemSize = pointee?.let { staticScalarByteWidth(it, emitter) }
                val bytesExpr =
                    when {
                        elemSize == 1L -> "(${renderedArgs[2]}).toLong()"
                        elemSize != null -> "(${renderedArgs[2]}).toLong() * ${elemSize}L"
                        pointee != null -> {
                            val layout = resolveTypeLayoutExpression(pointee, selfTypeName, emitter)
                            "(${renderedArgs[2]}).toLong() * ($layout).sizeBytes"
                        }
                        else -> "(${renderedArgs[2]}).toLong()"
                    }
                "$fnName(${rawAddrOf(dstIdx)}, ${rawAddrOf(srcIdx)}, $bytesExpr)"
            }
            "write_bytes", "volatile_set_memory" -> {
                registerCoreImport(emitter, "$intrPkg.kmWriteBytes")
                val pointee =
                    explicitTypes.firstOrNull()
                        ?: (callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) } as? SynType.Ptr)?.elem
                val elemSize = pointee?.let { staticScalarByteWidth(it, emitter) }
                val bytesExpr =
                    when {
                        elemSize == 1L -> "(${renderedArgs[2]}).toLong()"
                        elemSize != null -> "(${renderedArgs[2]}).toLong() * ${elemSize}L"
                        pointee != null -> {
                            val layout = resolveTypeLayoutExpression(pointee, selfTypeName, emitter)
                            "(${renderedArgs[2]}).toLong() * ($layout).sizeBytes"
                        }
                        else -> "(${renderedArgs[2]}).toLong()"
                    }
                "kmWriteBytes(${rawAddrOf(0)}, (${renderedArgs[1]}).toUByte(), $bytesExpr)"
            }
            "compare_bytes" -> {
                registerCoreImport(emitter, "$intrPkg.kmCompareBytes")
                "kmCompareBytes(${rawAddrOf(0)}, ${rawAddrOf(1)}, (${renderedArgs[2]}).toLong())"
            }
            "atomic_fence" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicFence")
                "kmAtomicFence(${orderingCode(0)})"
            }
            "atomic_singlethreadfence" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicSignalFence")
                "kmAtomicSignalFence(${orderingCode(0)})"
            }
            "atomic_load" -> {
                val pointee =
                    explicitTypes.firstOrNull()
                        ?: (callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) } as? SynType.Ptr)?.elem
                        ?: return null
                val direct = renderDirectAtomicLoad(pointee, rawAddrOf(0), orderingCode(0), intrPkg, ptrPkg, emitter)
                if (direct != null) {
                    direct
                } else {
                    registerCoreImport(emitter, "$memPkg.atomicLoadWithLayout")
                    val layout = resolveTypeLayoutExpression(pointee, selfTypeName, emitter)
                    "atomicLoadWithLayout(${rawAddrOf(0)}, ${orderingCode(0)}, $layout)"
                }
            }
            "atomic_store" -> {
                val pointee =
                    explicitTypes.firstOrNull()
                        ?: (callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) } as? SynType.Ptr)?.elem
                        ?: return null
                val direct = renderDirectAtomicStore(pointee, rawAddrOf(0), renderedArgs[1], orderingCode(0), intrPkg, emitter)
                if (direct != null) {
                    direct
                } else {
                    registerCoreImport(emitter, "$memPkg.atomicStoreWithLayout")
                    val layout = resolveTypeLayoutExpression(pointee, selfTypeName, emitter)
                    "atomicStoreWithLayout(${rawAddrOf(0)}, ${renderedArgs[1]}, ${orderingCode(0)}, $layout)"
                }
            }
            "atomic_xchg" -> {
                val pointee =
                    explicitTypes.firstOrNull()
                        ?: (callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) } as? SynType.Ptr)?.elem
                        ?: return null
                val direct = renderDirectAtomicXchg(pointee, rawAddrOf(0), renderedArgs[1], orderingCode(0), intrPkg, ptrPkg, emitter)
                if (direct != null) {
                    direct
                } else {
                    registerCoreImport(emitter, "$memPkg.atomicXchgWithLayout")
                    val layout = resolveTypeLayoutExpression(pointee, selfTypeName, emitter)
                    "atomicXchgWithLayout(${rawAddrOf(0)}, ${renderedArgs[1]}, ${orderingCode(0)}, $layout)"
                }
            }
            "atomic_cxchg", "atomic_cxchgweak" -> {
                val pointee =
                    explicitTypes.firstOrNull()
                        ?: (callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) } as? SynType.Ptr)?.elem
                        ?: return null
                val direct =
                    renderDirectAtomicCxchg(
                        pointee = pointee,
                        addrExpr = rawAddrOf(0),
                        expectedExpr = renderedArgs[1],
                        desiredExpr = renderedArgs[2],
                        succOrd = orderingCode(0),
                        failOrd = orderingCode(1),
                        intrPkg = intrPkg,
                        ptrPkg = ptrPkg,
                        emitter = emitter,
                    )
                if (direct != null) {
                    direct
                } else {
                    registerCoreImport(emitter, "$memPkg.atomicCxchgWithLayout")
                    val layout = resolveTypeLayoutExpression(pointee, selfTypeName, emitter)
                    "atomicCxchgWithLayout(${rawAddrOf(0)}, ${renderedArgs[1]}, ${renderedArgs[2]}, ${orderingCode(0)}, ${orderingCode(1)}, $layout)"
                }
            }
            in ATOMIC_RMW_OP_CODES -> {
                val opCode = ATOMIC_RMW_OP_CODES.getValue(canonicalIntrinsicName)
                val pointee =
                    explicitTypes.firstOrNull()
                        ?: (callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) } as? SynType.Ptr)?.elem
                        ?: return null
                val operandType = explicitTypes.getOrNull(1) ?: pointee
                val direct =
                    if (explicitTypes.size <= 1) {
                        renderDirectAtomicRmw(pointee, rawAddrOf(0), renderedArgs[1], opCode, orderingCode(0), intrPkg, ptrPkg, emitter)
                    } else {
                        null
                    }
                if (direct != null) {
                    direct
                } else {
                    registerCoreImport(emitter, "$memPkg.atomicRmwWithLayout")
                    val dstLayout = resolveTypeLayoutExpression(pointee, selfTypeName, emitter)
                    val opLayout = resolveTypeLayoutExpression(operandType, selfTypeName, emitter)
                    "atomicRmwWithLayout(${rawAddrOf(0)}, ${renderedArgs[1]}, $opCode, ${orderingCode(0)}, $dstLayout, $opLayout)"
                }
            }
            "ctpop" -> {
                registerCoreImport(emitter, "$intrPkg.kmCtpopU64")
                val maskExpr = bitIntrinsicUnsigned64Arg(explicitTypes.firstOrNull(), renderedArgs[0], emitter)
                "kmCtpopU64($maskExpr)"
            }
            "ctlz", "ctlz_nonzero" -> {
                registerCoreImport(emitter, "$intrPkg.kmCtlzU64")
                val bitWidth = (explicitTypes.firstOrNull()?.let { staticScalarByteWidth(it, emitter) } ?: 8L) * 8L
                val arg64 = bitIntrinsicUnsigned64Arg(explicitTypes.firstOrNull(), renderedArgs[0], emitter)
                if (bitWidth < 64L) {
                    "(kmCtlzU64($arg64) - ${(64L - bitWidth)}u)"
                } else {
                    "kmCtlzU64($arg64)"
                }
            }
            "cttz", "cttz_nonzero" -> {
                registerCoreImport(emitter, "$intrPkg.kmCttzU64")
                val bitWidth = (explicitTypes.firstOrNull()?.let { staticScalarByteWidth(it, emitter) } ?: 8L) * 8L
                val arg64 = bitIntrinsicUnsigned64Arg(explicitTypes.firstOrNull(), renderedArgs[0], emitter)
                if (bitWidth < 64L) {
                    "minOf(${bitWidth}u, kmCttzU64($arg64))"
                } else {
                    "kmCttzU64($arg64)"
                }
            }
            "bswap" -> {
                val width = explicitTypes.firstOrNull()?.let { staticScalarByteWidth(it, emitter) } ?: 8L
                val prim = explicitScalarRustName(explicitTypes.firstOrNull(), emitter)
                when (width) {
                    1L -> renderedArgs[0]
                    2L -> {
                        registerCoreImport(emitter, "$intrPkg.kmBswapU16")
                        val raw = "kmBswapU16((${renderedArgs[0]}).toUShort())"
                        if (prim == "i16") "$raw.toShort()" else raw
                    }
                    4L -> {
                        registerCoreImport(emitter, "$intrPkg.kmBswapU32")
                        val raw = "kmBswapU32((${renderedArgs[0]}).toUInt())"
                        if (prim == "i32" || (prim in setOf("isize", "usize") && !emitter.headerContext.rawAbiMode)) "$raw.toInt()" else raw
                    }
                    else -> {
                        registerCoreImport(emitter, "$intrPkg.kmBswapU64")
                        val raw = "kmBswapU64((${renderedArgs[0]}).toULong())"
                        if (prim == "i64" || (prim == "isize" && emitter.headerContext.rawAbiMode)) "$raw.toLong()" else raw
                    }
                }
            }
            "bitreverse" -> {
                registerCoreImport(emitter, "$intrPkg.kmBitreverseU64")
                val bitWidth = (explicitTypes.firstOrNull()?.let { staticScalarByteWidth(it, emitter) } ?: 8L) * 8L
                val prim = explicitScalarRustName(explicitTypes.firstOrNull(), emitter)
                val arg64 = bitIntrinsicUnsigned64Arg(explicitTypes.firstOrNull(), renderedArgs[0], emitter)
                val shifted = if (bitWidth < 64L) "(kmBitreverseU64($arg64) shr ${(64L - bitWidth).toInt()})" else "kmBitreverseU64($arg64)"
                castUnsigned64BackToRustScalar(shifted, prim, emitter)
            }
            "fmaf32", "fmuladdf32" -> {
                registerCoreImport(emitter, "$intrPkg.kmFmaf32")
                "kmFmaf32(${renderedArgs[0]}, ${renderedArgs[1]}, ${renderedArgs[2]})"
            }
            "fmaf64", "fmuladdf64" -> {
                registerCoreImport(emitter, "$intrPkg.kmFmaf64")
                "kmFmaf64(${renderedArgs[0]}, ${renderedArgs[1]}, ${renderedArgs[2]})"
            }
            "sqrtf32" -> {
                registerCoreImport(emitter, "$intrPkg.kmSqrtf32")
                "kmSqrtf32(${renderedArgs[0]})"
            }
            "sqrtf64" -> {
                registerCoreImport(emitter, "$intrPkg.kmSqrtf64")
                "kmSqrtf64(${renderedArgs[0]})"
            }
            "wrapping_add" -> "(${renderedArgs[0]} + ${renderedArgs[1]})"
            "wrapping_sub" -> "(${renderedArgs[0]} - ${renderedArgs[1]})"
            "wrapping_mul" -> "(${renderedArgs[0]} * ${renderedArgs[1]})"
            "transmute", "transmute_unchecked" -> {
                val srcTy = explicitTypes.getOrNull(0)?.takeUnless { it is SynType.Infer } ?: callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) }
                val dstTy = explicitTypes.getOrNull(1)?.takeUnless { it is SynType.Infer } ?: emitter.currentEnclosingReturnSynType
                if (srcTy != null && dstTy != null) {
                    renderFastScalarTransmute(srcTy, dstTy, renderedArgs[0], ptrPkg, emitter)?.let { return it }
                    registerCoreImport(emitter, "$memPkg.transmuteWithLayouts")
                    val srcLayout = resolveTypeLayoutExpression(srcTy, selfTypeName, emitter)
                    val dstLayout = resolveTypeLayoutExpression(dstTy, selfTypeName, emitter)
                    "transmuteWithLayouts(${renderedArgs[0]}, $srcLayout, $dstLayout)"
                } else {
                    null
                }
            }
            else -> null
        }
    }

    internal fun renderFastScalarTransmute(
        srcType: SynType,
        dstType: SynType,
        valueExpr: String,
        ptrPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val srcResolved = emitter.resolveLayoutType(srcType, emptyMap())
        val dstResolved = emitter.resolveLayoutType(dstType, emptyMap())
        val srcPrim = explicitScalarRustName(srcResolved, emitter)
        val dstPrim = explicitScalarRustName(dstResolved, emitter)
        if (srcPrim != null && srcPrim == dstPrim) return valueExpr
        if (srcResolved is SynType.Ptr && dstResolved is SynType.Ptr) {
            if (emitter.headerContext.rawAbiMode) return valueExpr
            val isMut = dstResolved.mutability is PointerMutability.Mut
            val ctor = if (isMut) "MutPtr" else "ConstPtr"
            registerCoreImport(emitter, "$ptrPkg.$ctor")
            return "$ctor(($valueExpr).rawAddress)"
        }
        return when (srcPrim to dstPrim) {
            "f32" to "u32" -> "($valueExpr).toRawBits().toUInt()"
            "f32" to "i32" -> "($valueExpr).toRawBits()"
            "u32" to "f32" -> "${emitter.kotlinF32TypeName()}.fromBits(($valueExpr).toInt())"
            "i32" to "f32" -> "${emitter.kotlinF32TypeName()}.fromBits($valueExpr)"
            "f64" to "u64" -> "($valueExpr).toRawBits().toULong()"
            "f64" to "i64" -> "($valueExpr).toRawBits()"
            "u64" to "f64" -> "Double.fromBits(($valueExpr).toLong())"
            "i64" to "f64" -> "Double.fromBits($valueExpr)"
            "u8" to "i8" -> "($valueExpr).toByte()"
            "i8" to "u8" -> "($valueExpr).toUByte()"
            "u16" to "i16" -> "($valueExpr).toShort()"
            "i16" to "u16" -> "($valueExpr).toUShort()"
            "u32" to "i32" -> "($valueExpr).toInt()"
            "i32" to "u32" -> "($valueExpr).toUInt()"
            "u64" to "i64" -> "($valueExpr).toLong()"
            "i64" to "u64" -> "($valueExpr).toULong()"
            else -> null
        }
    }

    private fun explicitScalarRustName(type: SynType?, emitter: KotlinJ2KEmitter): String? {
        if (type == null) return null
        val resolved = emitter.resolveLayoutType(type, emptyMap())
        val path = (resolved as? SynType.Path)?.takeIf { it.qself == null && it.path.segments.size == 1 }?.path ?: return null
        val ident =
            path.segments
                .last()
                ?.ident
                ?.toString()
                ?.removePrefix("r#") ?: return null
        return ident.takeIf {
            it in setOf("bool", "char", "i8", "u8", "i16", "u16", "i32", "u32", "i64", "u64", "i128", "u128", "isize", "usize", "f32", "f64")
        }
    }

    private fun staticScalarByteWidth(type: SynType, emitter: KotlinJ2KEmitter): Long? {
        val resolved = emitter.resolveLayoutType(type, emptyMap())
        if (resolved is SynType.Ptr) return (emitter.activeCfgProfile.pointerWidth / 8).toLong()
        return when (explicitScalarRustName(resolved, emitter)) {
            "bool", "i8", "u8" -> 1L
            "i16", "u16" -> 2L
            "i32", "u32", "f32", "char" -> 4L
            "i64", "u64", "f64" -> 8L
            "isize", "usize" -> (emitter.activeCfgProfile.pointerWidth / 8).toLong()
            "i128", "u128" -> 16L
            else -> null
        }
    }

    private fun bitIntrinsicUnsigned64Arg(type: SynType?, valueExpr: String, emitter: KotlinJ2KEmitter): String =
        when (explicitScalarRustName(type, emitter)) {
            "i8" -> "($valueExpr).toUByte().toULong()"
            "u8" -> "($valueExpr).toULong()"
            "i16" -> "($valueExpr).toUShort().toULong()"
            "u16" -> "($valueExpr).toULong()"
            "i32" -> "($valueExpr).toUInt().toULong()"
            "u32" -> "($valueExpr).toULong()"
            "isize" -> if (emitter.headerContext.rawAbiMode) "($valueExpr).toULong()" else "($valueExpr).toLong().toULong()"
            "usize" -> if (emitter.headerContext.rawAbiMode) "($valueExpr).toULong()" else "($valueExpr).toUInt().toULong()"
            else -> "($valueExpr).toULong()"
        }

    private fun castUnsigned64BackToRustScalar(u64Expr: String, prim: String?, emitter: KotlinJ2KEmitter): String =
        when (prim) {
            "i8" -> "($u64Expr).toUByte().toByte()"
            "u8" -> "($u64Expr).toUByte()"
            "i16" -> "($u64Expr).toUShort().toShort()"
            "u16" -> "($u64Expr).toUShort()"
            "i32" -> "($u64Expr).toUInt().toInt()"
            "u32" -> "($u64Expr).toUInt()"
            "i64" -> "($u64Expr).toLong()"
            "isize" -> if (emitter.headerContext.rawAbiMode) "($u64Expr).toLong()" else "($u64Expr).toLong().toInt()"
            "usize" -> if (emitter.headerContext.rawAbiMode) u64Expr else "($u64Expr).toInt()"
            else -> u64Expr
        }

    private fun renderDirectScalarLoad(
        pointee: SynType,
        addrExpr: String,
        isVolatile: Boolean,
        intrPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val resolved = emitter.resolveLayoutType(pointee, emptyMap())
        val ptrBits = emitter.activeCfgProfile.pointerWidth
        val prefix = if (isVolatile) "kmVolatileLoad" else "kmLoad"
        if (resolved is SynType.Ptr && !emitter.headerContext.rawAbiMode) {
            val isMut = resolved.mutability is PointerMutability.Mut
            val ctor = if (isMut) "MutPtr" else "ConstPtr"
            val coreNs = resolveCoreNamespace(emitter.headerContext)
            registerCoreImport(emitter, "$coreNs.ptr.$ctor")
            val loadFn = if (ptrBits == 64) "${prefix}U64" else "${prefix}U32"
            registerCoreImport(emitter, "$intrPkg.$loadFn")
            return "$ctor($loadFn($addrExpr).toLong())"
        }
        return when (explicitScalarRustName(resolved, emitter)) {
            "bool" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U8")
                "(${prefix}U8($addrExpr) != 0u.toUByte())"
            }
            "u8" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U8")
                "${prefix}U8($addrExpr)"
            }
            "i8" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U8")
                "${prefix}U8($addrExpr).toByte()"
            }
            "u16" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U16")
                "${prefix}U16($addrExpr)"
            }
            "i16" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U16")
                "${prefix}U16($addrExpr).toShort()"
            }
            "u32" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U32")
                "${prefix}U32($addrExpr)"
            }
            "i32" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U32")
                "${prefix}U32($addrExpr).toInt()"
            }
            "char" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U32")
                "${prefix}U32($addrExpr).toInt().toChar()"
            }
            "u64" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U64")
                "${prefix}U64($addrExpr)"
            }
            "i64" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U64")
                "${prefix}U64($addrExpr).toLong()"
            }
            "usize" -> {
                val fn = if (ptrBits == 64) "${prefix}U64" else "${prefix}U32"
                registerCoreImport(emitter, "$intrPkg.$fn")
                if (emitter.headerContext.rawAbiMode) "$fn($addrExpr)" else "$fn($addrExpr).toInt()"
            }
            "isize" -> {
                val fn = if (ptrBits == 64) "${prefix}U64" else "${prefix}U32"
                registerCoreImport(emitter, "$intrPkg.$fn")
                if (emitter.headerContext.rawAbiMode) "$fn($addrExpr).toLong()" else "$fn($addrExpr).toLong().toInt()"
            }
            "f32" ->
                if (!isVolatile) {
                    registerCoreImport(emitter, "$intrPkg.kmLoadF32")
                    "kmLoadF32($addrExpr)"
                } else {
                    registerCoreImport(emitter, "$intrPkg.kmVolatileLoadU32")
                    "Float.fromBits(kmVolatileLoadU32($addrExpr).toInt())"
                }
            "f64" ->
                if (!isVolatile) {
                    registerCoreImport(emitter, "$intrPkg.kmLoadF64")
                    "kmLoadF64($addrExpr)"
                } else {
                    registerCoreImport(emitter, "$intrPkg.kmVolatileLoadU64")
                    "Double.fromBits(kmVolatileLoadU64($addrExpr).toLong())"
                }
            else -> null
        }
    }

    private fun renderDirectScalarStore(
        pointee: SynType,
        addrExpr: String,
        valExpr: String,
        isVolatile: Boolean,
        intrPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val resolved = emitter.resolveLayoutType(pointee, emptyMap())
        val ptrBits = emitter.activeCfgProfile.pointerWidth
        val prefix = if (isVolatile) "kmVolatileStore" else "kmStore"
        if (resolved is SynType.Ptr && !emitter.headerContext.rawAbiMode) {
            val fn = if (ptrBits == 64) "${prefix}U64" else "${prefix}U32"
            registerCoreImport(emitter, "$intrPkg.$fn")
            val conv = if (ptrBits == 64) "($valExpr).rawAddress.toULong()" else "($valExpr).rawAddress.toUInt()"
            return "$fn($addrExpr, $conv)"
        }
        return when (explicitScalarRustName(resolved, emitter)) {
            "bool" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U8")
                "${prefix}U8($addrExpr, if ($valExpr) 1u else 0u)"
            }
            "u8" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U8")
                "${prefix}U8($addrExpr, $valExpr)"
            }
            "i8" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U8")
                "${prefix}U8($addrExpr, ($valExpr).toUByte())"
            }
            "u16" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U16")
                "${prefix}U16($addrExpr, $valExpr)"
            }
            "i16" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U16")
                "${prefix}U16($addrExpr, ($valExpr).toUShort())"
            }
            "u32" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U32")
                "${prefix}U32($addrExpr, $valExpr)"
            }
            "i32" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U32")
                "${prefix}U32($addrExpr, ($valExpr).toUInt())"
            }
            "char" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U32")
                "${prefix}U32($addrExpr, ($valExpr).code.toUInt())"
            }
            "u64" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U64")
                "${prefix}U64($addrExpr, $valExpr)"
            }
            "i64" -> {
                registerCoreImport(emitter, "$intrPkg.${prefix}U64")
                "${prefix}U64($addrExpr, ($valExpr).toULong())"
            }
            "usize" -> {
                val fn = if (ptrBits == 64) "${prefix}U64" else "${prefix}U32"
                registerCoreImport(emitter, "$intrPkg.$fn")
                val v = if (ptrBits == 64) "($valExpr).toUInt().toULong()" else "($valExpr).toUInt()"
                val rawV = if (emitter.headerContext.rawAbiMode) valExpr else v
                "$fn($addrExpr, $rawV)"
            }
            "isize" -> {
                val fn = if (ptrBits == 64) "${prefix}U64" else "${prefix}U32"
                registerCoreImport(emitter, "$intrPkg.$fn")
                val v = if (ptrBits == 64) "($valExpr).toLong().toULong()" else "($valExpr).toUInt()"
                "$fn($addrExpr, $v)"
            }
            "f32" ->
                if (!isVolatile) {
                    registerCoreImport(emitter, "$intrPkg.kmStoreF32")
                    "kmStoreF32($addrExpr, $valExpr)"
                } else {
                    registerCoreImport(emitter, "$intrPkg.kmVolatileStoreU32")
                    "kmVolatileStoreU32($addrExpr, ($valExpr).toRawBits().toUInt())"
                }
            "f64" ->
                if (!isVolatile) {
                    registerCoreImport(emitter, "$intrPkg.kmStoreF64")
                    "kmStoreF64($addrExpr, $valExpr)"
                } else {
                    registerCoreImport(emitter, "$intrPkg.kmVolatileStoreU64")
                    "kmVolatileStoreU64($addrExpr, ($valExpr).toRawBits().toULong())"
                }
            else -> null
        }
    }

    private fun renderDirectAtomicLoad(
        pointee: SynType,
        addrExpr: String,
        ordExpr: String,
        intrPkg: String,
        ptrPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val resolved = emitter.resolveLayoutType(pointee, emptyMap())
        val ptrBits = emitter.activeCfgProfile.pointerWidth
        if (resolved is SynType.Ptr && !emitter.headerContext.rawAbiMode) {
            val ctor = if (resolved.mutability is PointerMutability.Mut) "MutPtr" else "ConstPtr"
            registerCoreImport(emitter, "$ptrPkg.$ctor")
            val fn = if (ptrBits == 64) "kmAtomicLoadU64" else "kmAtomicLoadU32"
            registerCoreImport(emitter, "$intrPkg.$fn")
            return "$ctor($fn($addrExpr, $ordExpr).toLong())"
        }
        return when (explicitScalarRustName(resolved, emitter)) {
            "bool" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicLoadU8")
                "(kmAtomicLoadU8($addrExpr, $ordExpr) != 0u.toUByte())"
            }
            "u8" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicLoadU8")
                "kmAtomicLoadU8($addrExpr, $ordExpr)"
            }
            "i8" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicLoadU8")
                "kmAtomicLoadU8($addrExpr, $ordExpr).toByte()"
            }
            "u16" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicLoadU16")
                "kmAtomicLoadU16($addrExpr, $ordExpr)"
            }
            "i16" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicLoadU16")
                "kmAtomicLoadU16($addrExpr, $ordExpr).toShort()"
            }
            "u32" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicLoadU32")
                "kmAtomicLoadU32($addrExpr, $ordExpr)"
            }
            "i32" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicLoadU32")
                "kmAtomicLoadU32($addrExpr, $ordExpr).toInt()"
            }
            "u64" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicLoadU64")
                "kmAtomicLoadU64($addrExpr, $ordExpr)"
            }
            "i64" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicLoadU64")
                "kmAtomicLoadU64($addrExpr, $ordExpr).toLong()"
            }
            "usize" -> {
                val fn = if (ptrBits == 64) "kmAtomicLoadU64" else "kmAtomicLoadU32"
                registerCoreImport(emitter, "$intrPkg.$fn")
                if (emitter.headerContext.rawAbiMode) "$fn($addrExpr, $ordExpr)" else "$fn($addrExpr, $ordExpr).toInt()"
            }
            "isize" -> {
                val fn = if (ptrBits == 64) "kmAtomicLoadU64" else "kmAtomicLoadU32"
                registerCoreImport(emitter, "$intrPkg.$fn")
                if (emitter.headerContext.rawAbiMode) "$fn($addrExpr, $ordExpr).toLong()" else "$fn($addrExpr, $ordExpr).toLong().toInt()"
            }
            else -> null
        }
    }

    private fun renderDirectAtomicStore(
        pointee: SynType,
        addrExpr: String,
        valExpr: String,
        ordExpr: String,
        intrPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val resolved = emitter.resolveLayoutType(pointee, emptyMap())
        val ptrBits = emitter.activeCfgProfile.pointerWidth
        if (resolved is SynType.Ptr && !emitter.headerContext.rawAbiMode) {
            val fn = if (ptrBits == 64) "kmAtomicStoreU64" else "kmAtomicStoreU32"
            registerCoreImport(emitter, "$intrPkg.$fn")
            val conv = if (ptrBits == 64) "($valExpr).rawAddress.toULong()" else "($valExpr).rawAddress.toUInt()"
            return "$fn($addrExpr, $conv, $ordExpr)"
        }
        return when (explicitScalarRustName(resolved, emitter)) {
            "bool" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicStoreU8")
                "kmAtomicStoreU8($addrExpr, if ($valExpr) 1u else 0u, $ordExpr)"
            }
            "u8" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicStoreU8")
                "kmAtomicStoreU8($addrExpr, $valExpr, $ordExpr)"
            }
            "i8" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicStoreU8")
                "kmAtomicStoreU8($addrExpr, ($valExpr).toUByte(), $ordExpr)"
            }
            "u16" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicStoreU16")
                "kmAtomicStoreU16($addrExpr, $valExpr, $ordExpr)"
            }
            "i16" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicStoreU16")
                "kmAtomicStoreU16($addrExpr, ($valExpr).toUShort(), $ordExpr)"
            }
            "u32" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicStoreU32")
                "kmAtomicStoreU32($addrExpr, $valExpr, $ordExpr)"
            }
            "i32" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicStoreU32")
                "kmAtomicStoreU32($addrExpr, ($valExpr).toUInt(), $ordExpr)"
            }
            "u64" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicStoreU64")
                "kmAtomicStoreU64($addrExpr, $valExpr, $ordExpr)"
            }
            "i64" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicStoreU64")
                "kmAtomicStoreU64($addrExpr, ($valExpr).toULong(), $ordExpr)"
            }
            "usize" -> {
                val fn = if (ptrBits == 64) "kmAtomicStoreU64" else "kmAtomicStoreU32"
                registerCoreImport(emitter, "$intrPkg.$fn")
                val v =
                    if (emitter.headerContext.rawAbiMode) {
                        valExpr
                    } else if (ptrBits == 64) {
                        "($valExpr).toUInt().toULong()"
                    } else {
                        "($valExpr).toUInt()"
                    }
                "$fn($addrExpr, $v, $ordExpr)"
            }
            "isize" -> {
                val fn = if (ptrBits == 64) "kmAtomicStoreU64" else "kmAtomicStoreU32"
                registerCoreImport(emitter, "$intrPkg.$fn")
                val v = if (ptrBits == 64) "($valExpr).toLong().toULong()" else "($valExpr).toUInt()"
                "$fn($addrExpr, $v, $ordExpr)"
            }
            else -> null
        }
    }

    private fun renderDirectAtomicXchg(
        pointee: SynType,
        addrExpr: String,
        valExpr: String,
        ordExpr: String,
        intrPkg: String,
        ptrPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val resolved = emitter.resolveLayoutType(pointee, emptyMap())
        val ptrBits = emitter.activeCfgProfile.pointerWidth
        if (resolved is SynType.Ptr && !emitter.headerContext.rawAbiMode) {
            val ctor = if (resolved.mutability is PointerMutability.Mut) "MutPtr" else "ConstPtr"
            registerCoreImport(emitter, "$ptrPkg.$ctor")
            val fn = if (ptrBits == 64) "kmAtomicXchgU64" else "kmAtomicXchgU32"
            registerCoreImport(emitter, "$intrPkg.$fn")
            val conv = if (ptrBits == 64) "($valExpr).rawAddress.toULong()" else "($valExpr).rawAddress.toUInt()"
            return "$ctor($fn($addrExpr, $conv, $ordExpr).toLong())"
        }
        return when (explicitScalarRustName(resolved, emitter)) {
            "bool" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicXchgU8")
                "(kmAtomicXchgU8($addrExpr, if ($valExpr) 1u else 0u, $ordExpr) != 0u.toUByte())"
            }
            "u8" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicXchgU8")
                "kmAtomicXchgU8($addrExpr, $valExpr, $ordExpr)"
            }
            "i8" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicXchgU8")
                "kmAtomicXchgU8($addrExpr, ($valExpr).toUByte(), $ordExpr).toByte()"
            }
            "u16" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicXchgU16")
                "kmAtomicXchgU16($addrExpr, $valExpr, $ordExpr)"
            }
            "i16" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicXchgU16")
                "kmAtomicXchgU16($addrExpr, ($valExpr).toUShort(), $ordExpr).toShort()"
            }
            "u32" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicXchgU32")
                "kmAtomicXchgU32($addrExpr, $valExpr, $ordExpr)"
            }
            "i32" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicXchgU32")
                "kmAtomicXchgU32($addrExpr, ($valExpr).toUInt(), $ordExpr).toInt()"
            }
            "u64" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicXchgU64")
                "kmAtomicXchgU64($addrExpr, $valExpr, $ordExpr)"
            }
            "i64" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicXchgU64")
                "kmAtomicXchgU64($addrExpr, ($valExpr).toULong(), $ordExpr).toLong()"
            }
            "usize" -> {
                val fn = if (ptrBits == 64) "kmAtomicXchgU64" else "kmAtomicXchgU32"
                registerCoreImport(emitter, "$intrPkg.$fn")
                val v =
                    if (emitter.headerContext.rawAbiMode) {
                        valExpr
                    } else if (ptrBits == 64) {
                        "($valExpr).toUInt().toULong()"
                    } else {
                        "($valExpr).toUInt()"
                    }
                if (emitter.headerContext.rawAbiMode) "$fn($addrExpr, $v, $ordExpr)" else "$fn($addrExpr, $v, $ordExpr).toInt()"
            }
            "isize" -> {
                val fn = if (ptrBits == 64) "kmAtomicXchgU64" else "kmAtomicXchgU32"
                registerCoreImport(emitter, "$intrPkg.$fn")
                val v = if (ptrBits == 64) "($valExpr).toLong().toULong()" else "($valExpr).toUInt()"
                if (emitter.headerContext.rawAbiMode) "$fn($addrExpr, $v, $ordExpr).toLong()" else "$fn($addrExpr, $v, $ordExpr).toLong().toInt()"
            }
            else -> null
        }
    }

    private fun renderDirectAtomicCxchg(
        pointee: SynType,
        addrExpr: String,
        expectedExpr: String,
        desiredExpr: String,
        succOrd: String,
        failOrd: String,
        intrPkg: String,
        ptrPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val resolved = emitter.resolveLayoutType(pointee, emptyMap())
        val ptrBits = emitter.activeCfgProfile.pointerWidth
        val pairCtor = if ("Pair" in emitter.inspector.declaredTypeNames) "kotlin.Pair" else "Pair"
        if (resolved is SynType.Ptr && !emitter.headerContext.rawAbiMode) {
            val ctor = if (resolved.mutability is PointerMutability.Mut) "MutPtr" else "ConstPtr"
            registerCoreImport(emitter, "$ptrPkg.$ctor")
            val fn = if (ptrBits == 64) "kmAtomicCxchgU64" else "kmAtomicCxchgU32"
            registerCoreImport(emitter, "$intrPkg.$fn")
            val expConv = if (ptrBits == 64) "($expectedExpr).rawAddress.toULong()" else "($expectedExpr).rawAddress.toUInt()"
            val desConv = if (ptrBits == 64) "($desiredExpr).rawAddress.toULong()" else "($desiredExpr).rawAddress.toUInt()"
            return "$fn($addrExpr, $expConv, $desConv, $succOrd, $failOrd).let { cxchgRes -> $pairCtor($ctor(cxchgRes.previous.toLong()), cxchgRes.succeeded) }"
        }
        return when (explicitScalarRustName(resolved, emitter)) {
            "bool" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicCxchgU8")
                "kmAtomicCxchgU8($addrExpr, if ($expectedExpr) 1u else 0u, if ($desiredExpr) 1u else 0u, $succOrd, $failOrd).let { cxchgRes -> $pairCtor(cxchgRes.previous != 0u.toUByte(), cxchgRes.succeeded) }"
            }
            "u8" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicCxchgU8")
                "kmAtomicCxchgU8($addrExpr, $expectedExpr, $desiredExpr, $succOrd, $failOrd).let { cxchgRes -> $pairCtor(cxchgRes.previous, cxchgRes.succeeded) }"
            }
            "i8" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicCxchgU8")
                "kmAtomicCxchgU8($addrExpr, ($expectedExpr).toUByte(), ($desiredExpr).toUByte(), $succOrd, $failOrd).let { cxchgRes -> $pairCtor(cxchgRes.previous.toByte(), cxchgRes.succeeded) }"
            }
            "u16" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicCxchgU16")
                "kmAtomicCxchgU16($addrExpr, $expectedExpr, $desiredExpr, $succOrd, $failOrd).let { cxchgRes -> $pairCtor(cxchgRes.previous, cxchgRes.succeeded) }"
            }
            "i16" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicCxchgU16")
                "kmAtomicCxchgU16($addrExpr, ($expectedExpr).toUShort(), ($desiredExpr).toUShort(), $succOrd, $failOrd).let { cxchgRes -> $pairCtor(cxchgRes.previous.toShort(), cxchgRes.succeeded) }"
            }
            "u32" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicCxchgU32")
                "kmAtomicCxchgU32($addrExpr, $expectedExpr, $desiredExpr, $succOrd, $failOrd).let { cxchgRes -> $pairCtor(cxchgRes.previous, cxchgRes.succeeded) }"
            }
            "i32" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicCxchgU32")
                "kmAtomicCxchgU32($addrExpr, ($expectedExpr).toUInt(), ($desiredExpr).toUInt(), $succOrd, $failOrd).let { cxchgRes -> $pairCtor(cxchgRes.previous.toInt(), cxchgRes.succeeded) }"
            }
            "u64" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicCxchgU64")
                "kmAtomicCxchgU64($addrExpr, $expectedExpr, $desiredExpr, $succOrd, $failOrd).let { cxchgRes -> $pairCtor(cxchgRes.previous, cxchgRes.succeeded) }"
            }
            "i64" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicCxchgU64")
                "kmAtomicCxchgU64($addrExpr, ($expectedExpr).toULong(), ($desiredExpr).toULong(), $succOrd, $failOrd).let { cxchgRes -> $pairCtor(cxchgRes.previous.toLong(), cxchgRes.succeeded) }"
            }
            "u128" -> {
                emitter.usesU128 = true
                registerCoreImport(emitter, "$intrPkg.kmAtomicCxchgU128Pair")
                "kmAtomicCxchgU128Pair($addrExpr, ($expectedExpr).low, ($expectedExpr).high, ($desiredExpr).low, ($desiredExpr).high).let { cxchgRes -> $pairCtor(U128(cxchgRes.prevHi, cxchgRes.prevLo), cxchgRes.succeeded) }"
            }
            "i128" -> {
                emitter.usesI128 = true
                registerCoreImport(emitter, "$intrPkg.kmAtomicCxchgU128Pair")
                "kmAtomicCxchgU128Pair($addrExpr, ($expectedExpr).low, ($expectedExpr).high.toULong(), ($desiredExpr).low, ($desiredExpr).high.toULong()).let { cxchgRes -> $pairCtor(I128(cxchgRes.prevHi.toLong(), cxchgRes.prevLo), cxchgRes.succeeded) }"
            }
            else -> null
        }
    }

    private fun renderDirectAtomicRmw(
        pointee: SynType,
        addrExpr: String,
        valExpr: String,
        opCode: Int,
        ordExpr: String,
        intrPkg: String,
        ptrPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val resolved = emitter.resolveLayoutType(pointee, emptyMap())
        val ptrBits = emitter.activeCfgProfile.pointerWidth
        if (resolved is SynType.Ptr && !emitter.headerContext.rawAbiMode) {
            val ctor = if (resolved.mutability is PointerMutability.Mut) "MutPtr" else "ConstPtr"
            registerCoreImport(emitter, "$ptrPkg.$ctor")
            val fn = if (ptrBits == 64) "kmAtomicRmwU64" else "kmAtomicRmwU32"
            registerCoreImport(emitter, "$intrPkg.$fn")
            val conv = if (ptrBits == 64) "($valExpr).rawAddress.toULong()" else "($valExpr).rawAddress.toUInt()"
            return "$ctor($fn($addrExpr, $conv, $opCode, $ordExpr).toLong())"
        }
        return when (explicitScalarRustName(resolved, emitter)) {
            "bool" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicRmwU8")
                "(kmAtomicRmwU8($addrExpr, if ($valExpr) 1u else 0u, $opCode, $ordExpr) != 0u.toUByte())"
            }
            "u8" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicRmwU8")
                "kmAtomicRmwU8($addrExpr, $valExpr, $opCode, $ordExpr)"
            }
            "i8" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicRmwU8")
                "kmAtomicRmwU8($addrExpr, ($valExpr).toUByte(), $opCode, $ordExpr).toByte()"
            }
            "u16" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicRmwU16")
                "kmAtomicRmwU16($addrExpr, $valExpr, $opCode, $ordExpr)"
            }
            "i16" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicRmwU16")
                "kmAtomicRmwU16($addrExpr, ($valExpr).toUShort(), $opCode, $ordExpr).toShort()"
            }
            "u32" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicRmwU32")
                "kmAtomicRmwU32($addrExpr, $valExpr, $opCode, $ordExpr)"
            }
            "i32" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicRmwU32")
                "kmAtomicRmwU32($addrExpr, ($valExpr).toUInt(), $opCode, $ordExpr).toInt()"
            }
            "u64" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicRmwU64")
                "kmAtomicRmwU64($addrExpr, $valExpr, $opCode, $ordExpr)"
            }
            "i64" -> {
                registerCoreImport(emitter, "$intrPkg.kmAtomicRmwU64")
                "kmAtomicRmwU64($addrExpr, ($valExpr).toULong(), $opCode, $ordExpr).toLong()"
            }
            "usize" -> {
                val fn = if (ptrBits == 64) "kmAtomicRmwU64" else "kmAtomicRmwU32"
                registerCoreImport(emitter, "$intrPkg.$fn")
                val v =
                    if (emitter.headerContext.rawAbiMode) {
                        valExpr
                    } else if (ptrBits == 64) {
                        "($valExpr).toUInt().toULong()"
                    } else {
                        "($valExpr).toUInt()"
                    }
                if (emitter.headerContext.rawAbiMode) "$fn($addrExpr, $v, $opCode, $ordExpr)" else "$fn($addrExpr, $v, $opCode, $ordExpr).toInt()"
            }
            "isize" -> {
                val fn = if (ptrBits == 64) "kmAtomicRmwU64" else "kmAtomicRmwU32"
                registerCoreImport(emitter, "$intrPkg.$fn")
                val v = if (ptrBits == 64) "($valExpr).toLong().toULong()" else "($valExpr).toUInt()"
                if (emitter.headerContext.rawAbiMode) "$fn($addrExpr, $v, $opCode, $ordExpr).toLong()" else "$fn($addrExpr, $v, $opCode, $ordExpr).toLong().toInt()"
            }
            else -> null
        }
    }
}

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
import io.github.kotlinmania.syn.ReturnType
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

    public fun defaultStdlibFfiSymbolPackage(
        ktSym: String,
        coreNs: String,
        allocNs: String,
    ): String =
        when (ktSym) {
            "CString" -> "$allocNs.ffi.cstr"
            "CStr" -> "$coreNs.ffi.cstr"
            "CVoid" -> "$coreNs.ffi"
            else -> "$coreNs.ffi.primitives"
        }

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

    private val CORE_SIMD_INTRINSIC_NAMES: Set<String> =
        setOf(
            "simd_insert",
            "simd_extract",
            "simd_insert_dyn",
            "simd_extract_dyn",
            "simd_splat",
            "simd_add",
            "simd_sub",
            "simd_mul",
            "simd_div",
            "simd_rem",
            "simd_shl",
            "simd_shr",
            "simd_funnel_shl",
            "simd_funnel_shr",
            "simd_carryless_mul",
            "simd_and",
            "simd_or",
            "simd_xor",
            "simd_cast",
            "simd_as",
            "simd_neg",
            "simd_fabs",
            "simd_minimum_number_nsz",
            "simd_maximum_number_nsz",
            "simd_eq",
            "simd_ne",
            "simd_lt",
            "simd_le",
            "simd_gt",
            "simd_ge",
            "simd_shuffle",
            "simd_gather",
            "simd_scatter",
            "simd_masked_load",
            "simd_masked_store",
            "simd_saturating_add",
            "simd_saturating_sub",
            "simd_reduce_add_ordered",
            "simd_reduce_add_unordered",
            "simd_reduce_mul_ordered",
            "simd_reduce_mul_unordered",
            "simd_reduce_all",
            "simd_reduce_any",
            "simd_reduce_max",
            "simd_reduce_min",
            "simd_reduce_and",
            "simd_reduce_or",
            "simd_reduce_xor",
            "simd_bitmask",
            "simd_select",
            "simd_select_bitmask",
            "simd_arith_offset",
            "simd_cast_ptr",
            "simd_expose_provenance",
            "simd_with_exposed_provenance",
            "simd_bswap",
            "simd_bitreverse",
            "simd_ctlz",
            "simd_ctpop",
            "simd_cttz",
            "simd_ceil",
            "simd_floor",
            "simd_round",
            "simd_round_ties_even",
            "simd_trunc",
            "simd_fsqrt",
            "simd_fma",
            "simd_relaxed_fma",
            "simd_fsin",
            "simd_fcos",
            "simd_fexp",
            "simd_fexp2",
            "simd_flog10",
            "simd_flog2",
            "simd_flog",
        )

    private val SIMD_BOOL_RETURN_INTRINSICS: Set<String> = setOf("simd_reduce_all", "simd_reduce_any")
    private val SIMD_UNIT_RETURN_INTRINSICS: Set<String> = setOf("simd_scatter", "simd_masked_store")
    private val SIMD_SAME_ARG0_INTRINSICS: Set<String> =
        setOf(
            "simd_insert",
            "simd_insert_dyn",
            "simd_add",
            "simd_sub",
            "simd_mul",
            "simd_div",
            "simd_rem",
            "simd_shl",
            "simd_shr",
            "simd_funnel_shl",
            "simd_funnel_shr",
            "simd_carryless_mul",
            "simd_and",
            "simd_or",
            "simd_xor",
            "simd_neg",
            "simd_fabs",
            "simd_minimum_number_nsz",
            "simd_maximum_number_nsz",
            "simd_gather",
            "simd_saturating_add",
            "simd_saturating_sub",
            "simd_arith_offset",
            "simd_bswap",
            "simd_bitreverse",
            "simd_ctlz",
            "simd_ctpop",
            "simd_cttz",
            "simd_ceil",
            "simd_floor",
            "simd_round",
            "simd_round_ties_even",
            "simd_trunc",
            "simd_fsqrt",
            "simd_fma",
            "simd_relaxed_fma",
            "simd_fsin",
            "simd_fcos",
            "simd_fexp",
            "simd_fexp2",
            "simd_flog10",
            "simd_flog2",
            "simd_flog",
        )
    private val SIMD_SAME_ARG1_INTRINSICS: Set<String> =
        setOf(
            "simd_reduce_add_ordered",
            "simd_reduce_mul_ordered",
            "simd_select",
            "simd_select_bitmask",
        )
    private val SIMD_SAME_ARG2_INTRINSICS: Set<String> = setOf("simd_masked_load")

    private val FIRST_TYPE_ARG_LAYOUT_INTRINSICS: Set<String> =
        setOf(
            "size_of",
            "size_of_val",
            "align_of",
            "align_of_val",
            "min_align_of",
            "min_align_of_val",
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
            "ctpop",
            "ctlz",
            "ctlz_nonzero",
            "cttz",
            "cttz_nonzero",
            "bswap",
            "bitreverse",
            "rotate_left",
            "rotate_right",
            "wrapping_add",
            "wrapping_sub",
            "wrapping_mul",
            "unchecked_add",
            "unchecked_sub",
            "unchecked_mul",
            "saturating_add",
            "saturating_sub",
            "add_with_overflow",
            "sub_with_overflow",
            "mul_with_overflow",
            "unchecked_div",
            "unchecked_rem",
            "exact_div",
            "disjoint_bitor",
            "three_way_compare",
            "sqrt",
            "sin",
            "cos",
            "exp",
            "exp2",
            "log",
            "log2",
            "log10",
            "trunc",
            "round",
            "round_ties_even",
            "ceil",
            "floor",
            "fabs",
            "powf",
            "powi",
            "fma",
            "fmuladd",
            "copysign",
            "minnum",
            "maxnum",
            "fadd_fast",
            "fsub_fast",
            "fmul_fast",
            "fdiv_fast",
            "frem_fast",
            "fadd_algebraic",
            "fsub_algebraic",
            "fmul_algebraic",
            "fdiv_algebraic",
            "frem_algebraic",
        )

    private val TWO_TYPE_ARG_LAYOUT_INTRINSICS: Set<String> =
        setOf(
            "transmute",
            "transmute_unchecked",
            "unchecked_shl",
            "unchecked_shr",
            "float_to_int_unchecked",
            "atomic_xadd",
            "atomic_xsub",
            "atomic_and",
            "atomic_nand",
            "atomic_or",
            "atomic_xor",
        )

    internal val GENERIC_FLOAT_UNARY_KM: Map<String, Pair<String, String>> =
        mapOf(
            "sqrt" to ("kmSqrtf32" to "kmSqrtf64"),
            "sin" to ("kmSinf32" to "kmSinf64"),
            "cos" to ("kmCosf32" to "kmCosf64"),
            "exp" to ("kmExpf32" to "kmExpf64"),
            "exp2" to ("kmExp2f32" to "kmExp2f64"),
            "log" to ("kmLogf32" to "kmLogf64"),
            "log2" to ("kmLog2f32" to "kmLog2f64"),
            "log10" to ("kmLog10f32" to "kmLog10f64"),
            "trunc" to ("kmTruncf32" to "kmTruncf64"),
            "round" to ("kmRoundf32" to "kmRoundf64"),
            "round_ties_even" to ("kmRoundTiesEvenf32" to "kmRoundTiesEvenf64"),
            "ceil" to ("kmCeilf32" to "kmCeilf64"),
            "floor" to ("kmFloorf32" to "kmFloorf64"),
            "fabs" to ("kmFabsf32" to "kmFabsf64"),
        )

    internal val SUFFIXED_FLOAT_UNARY_KM: Map<String, String> =
        buildMap {
            for ((op, fns) in GENERIC_FLOAT_UNARY_KM) {
                if (op != "round_ties_even") {
                    put("${op}f32", fns.first)
                    put("${op}f64", fns.second)
                }
            }
            put("round_ties_even_f32", "kmRoundTiesEvenf32")
            put("round_ties_even_f64", "kmRoundTiesEvenf64")
            put("rintf32", "kmRoundTiesEvenf32")
            put("rintf64", "kmRoundTiesEvenf64")
        }

    internal val SUFFIXED_FLOAT_BINARY_KM: Map<String, String> =
        mapOf(
            "powf32" to "kmPowf32",
            "powf64" to "kmPowf64",
            "powif32" to "kmPowif32",
            "powif64" to "kmPowif64",
            "copysignf32" to "kmCopysignf32",
            "copysignf64" to "kmCopysignf64",
            "minnumf32" to "kmMinnumf32",
            "minnumf64" to "kmMinnumf64",
            "minimum_number_nsz_f32" to "kmMinnumf32",
            "minimum_number_nsz_f64" to "kmMinnumf64",
            "maxnumf32" to "kmMaxnumf32",
            "maxnumf64" to "kmMaxnumf64",
            "maximum_number_nsz_f32" to "kmMaxnumf32",
            "maximum_number_nsz_f64" to "kmMaxnumf64",
        )

    internal val GENERIC_FLOAT_BINARY_OP_NAMES: Map<String, String> =
        mapOf(
            "powf" to "powf",
            "copysign" to "copysign",
            "minnum" to "minnum",
            "maxnum" to "maxnum",
            "minimum" to "minimum",
            "maximum" to "maximum",
            "fadd_fast" to "fadd",
            "fadd_algebraic" to "fadd",
            "fsub_fast" to "fsub",
            "fsub_algebraic" to "fsub",
            "fmul_fast" to "fmul",
            "fmul_algebraic" to "fmul",
            "fdiv_fast" to "fdiv",
            "fdiv_algebraic" to "fdiv",
            "frem_fast" to "frem",
            "frem_algebraic" to "frem",
        )

    private val KNOWN_STDLIB_LAYOUT_FUNCTIONS: Set<String> =
        setOf(
            "size_of",
            "size_of_val",
            "align_of",
            "align_of_val",
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

    private fun isSimdIntrinsicName(name: String): Boolean =
        name in CORE_SIMD_INTRINSIC_NAMES || name.startsWith("sve_tuple_")

    public fun layoutConsumingTypeArgIndices(
        intrinsicName: String,
        explicitTypeArgCount: Int,
    ): List<Int> {
        val canonical = parseLegacyAtomicOrderingSuffix(intrinsicName).baseName
        return when {
            canonical in SIMD_BOOL_RETURN_INTRINSICS || canonical in SIMD_UNIT_RETURN_INTRINSICS -> emptyList()
            isSimdIntrinsicName(canonical) -> (0 until explicitTypeArgCount).toList()
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
        if (emitter.headerContext.isDocExample) return
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
        val rewrittenSegNames =
            emitter.rewriteCrateRootAliasAndReexportSegments(
                segs.map { it.ident.toString().removePrefix("r#") },
            )
        val penultName =
            rewrittenSegNames
                .getOrNull(rewrittenSegNames.size - 2)
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
                        when (val argTy = emitter.sourceInitializerType(firstArg)) {
                            is SynType.Ptr -> argTy.elem
                            is SynType.Reference -> argTy.elem
                            else -> argTy
                        }
                    }
            if (targetSynType != null) {
                val layoutExpr = resolveTypeLayoutExpression(targetSynType, selfTypeName, emitter)
                return listOf("typeLayoutT = $layoutExpr")
            }
        }

        // 3. Cross-crate / local calls to synthesized `core::intrinsics` layout-consuming functions
        val isIntrinsicsModuleCall =
            penultName == "intrinsics" ||
                "intrinsics" in rewrittenSegNames.dropLast(1) ||
                (segs.size == 1 && emitter.headerContext.packageName?.endsWith(".intrinsics") == true)
        if (isIntrinsicsModuleCall && rawName !in emitter.inspector.declaredTopLevelFnRawNames) {
            val canonical = parseLegacyAtomicOrderingSuffix(rawName).baseName
            val minTypeArgs =
                when {
                    canonical in TWO_TYPE_ARG_LAYOUT_INTRINSICS -> maxOf(explicitTypes.size, 2)
                    canonical in FIRST_TYPE_ARG_LAYOUT_INTRINSICS -> maxOf(explicitTypes.size, 1)
                    else -> explicitTypes.size
                }
            val indices = layoutConsumingTypeArgIndices(rawName, minTypeArgs)
            if (indices.isNotEmpty()) {
                val callArgsList = call.args.toList()
                val argSynType =
                    callArgsList.firstOrNull()?.let { firstArg ->
                        when (val argTy = emitter.sourceInitializerType(firstArg)) {
                            is SynType.Ptr -> argTy.elem
                            is SynType.Reference -> argTy.elem
                            else -> argTy
                        }
                    }
                return indices.mapNotNull { idx ->
                    val synTy =
                        explicitTypes.getOrNull(idx)?.takeUnless { it is SynType.Infer }
                            ?: (if (idx == 0) argSynType else null)
                            ?: return@mapNotNull null
                    val layoutExpr = resolveTypeLayoutExpression(synTy, selfTypeName, emitter)
                    val paramName =
                        when {
                            idx == 0 && canonical in setOf("transmute", "transmute_unchecked") -> "typeLayoutSrc"
                            idx == 1 && canonical in setOf("transmute", "transmute_unchecked") -> "typeLayoutDst"
                            idx == 0 && canonical == "float_to_int_unchecked" -> "typeLayoutFloat"
                            idx == 1 && canonical == "float_to_int_unchecked" -> "typeLayoutInt"
                            idx == 0 -> "typeLayoutT"
                            idx == 1 -> "typeLayoutU"
                            else -> "typeLayoutT$idx"
                        }
                    "$paramName = $layoutExpr"
                }
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

        // Unresolved generic / unsized fallback: register explicit layout boundary for caller monomorphization
        registerCoreImport(emitter, "$memPkg.UnresolvedTypeLayout")
        return "UnresolvedTypeLayout<$rendered>()"
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
        val topArgs = RustTokenCursor(tokens).splitByTopLevelPunct(',')
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
            "abort", "abort_immediate" -> {
                registerCoreImport(emitter, "$intrPkg.kmAbort")
                "kmAbort()"
            }
            "unreachable" -> "error(\"Entered Rust intrinsics::unreachable\")"
            "assume", "breakpoint", "forget", "assert_inhabited", "assert_zero_valid", "assert_mem_uninitialized_valid" -> "Unit"
            "likely", "unlikely" -> p0
            "cold_path" -> "Unit"
            "rustc_peek" -> p0
            "black_box" -> p0
            "select_unpredictable" -> "if ($p0) $p1 else $p2"
            "size_of" -> if (emitter.headerContext.rawAbiMode) "$layout0.sizeBytes.toULong()" else "$layout0.sizeBytes.toInt()"
            "size_of_val" ->
                if (emitter.headerContext.rawAbiMode) {
                    "$layout0.sizeBytes.toULong()"
                } else {
                    "(if ($p0.rawMeta > 0L) ($p0.rawMeta * $layout0.sizeBytes).toInt() else $layout0.sizeBytes.toInt())"
                }
            "align_of", "align_of_val", "min_align_of", "min_align_of_val", "pref_align_of" ->
                if (emitter.headerContext.rawAbiMode) "$layout0.alignBytes.toULong()" else "$layout0.alignBytes.toInt()"
            "vtable_size", "vtable_align" -> if (emitter.headerContext.rawAbiMode) "8uL" else "8"
            "variant_count" -> if (emitter.headerContext.rawAbiMode) "1uL" else "1"
            "raw_eq" -> {
                registerCoreImport(emitter, "$memPkg.rawEqWithLayout")
                "rawEqWithLayout($p0, $p1, $layout0)"
            }
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
            in INT_INTRINSIC_RULES_BY_NAME ->
                INT_INTRINSIC_RULES_BY_NAME
                    .getValue(canonicalIntrinsicName)
                    .renderSynthesizedBody(p0, p1, typeParams.size, layout0, layout1, memPkg, emitter)
            in FLOAT_INTRINSIC_RULES_BY_NAME ->
                FLOAT_INTRINSIC_RULES_BY_NAME
                    .getValue(canonicalIntrinsicName)
                    .renderSynthesizedBody(canonicalIntrinsicName, p0, p1, p2, layout0, layout1, intrPkg, memPkg, emitter)
            else ->
                if (isSimdIntrinsicName(canonicalIntrinsicName)) {
                        renderSynthesizedSimdIntrinsicFnBody(fnItem, canonicalIntrinsicName, typeParams, intrPkg, emitter)
                    } else {
                        null
                    }
        }
    }

    internal fun renderZeroedLayoutReadExpression(
        layoutExpr: String,
        intrPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        registerCoreImport(emitter, "$intrPkg.kmAllocZeroed")
        registerCoreImport(emitter, "$intrPkg.kmDealloc")
        return "run { val ptr = kmAllocZeroed(maxOf(1L, ($layoutExpr).sizeBytes), maxOf(1L, ($layoutExpr).alignBytes)); " +
            "val res = ($layoutExpr).readNative(ptr); " +
            "kmDealloc(ptr, maxOf(1L, ($layoutExpr).sizeBytes), maxOf(1L, ($layoutExpr).alignBytes)); res }"
    }

    private fun renderSynthesizedSimdIntrinsicFnBody(
        fnItem: Item.Fn,
        canonicalName: String,
        typeParams: List<String>,
        intrPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        if (canonicalName in SIMD_BOOL_RETURN_INTRINSICS) return "false"
        if (canonicalName in SIMD_UNIT_RETURN_INTRINSICS) return "Unit"
        val retSynTy = (fnItem.sig.output as? ReturnType.TypeReturn)?.ty
        val retKtIdent =
            retSynTy
                ?.let { unwrapSynType(it) as? SynType.Path }
                ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                ?.path
                ?.segments
                ?.toList()
                ?.firstOrNull()
                ?.ident
                ?.toString()
                ?.let(::rustTypeIdentToKotlin)
        val layoutIdent =
            if (retKtIdent != null && retKtIdent in typeParams) {
                StructLayoutComputer.typeLayoutParamName(retKtIdent)
            } else if (typeParams.isNotEmpty()) {
                StructLayoutComputer.typeLayoutParamName(typeParams.last())
            } else {
                null
            }
        if (layoutIdent != null) {
            return renderZeroedLayoutReadExpression(layoutIdent, intrPkg, emitter)
        }
        return if (retSynTy != null) {
            emitter.renderFallbackDefaultForRenderedType(emitter.renderType(retSynTy, null))
        } else {
            "Unit"
        }
    }

    private fun renderSimdCallSiteLowering(
        canonicalName: String,
        renderedArgs: List<String>,
        explicitTypes: List<SynType>,
        selfTypeName: String?,
        intrPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        fun wrapWithSideEffects(resultExpr: String): String {
            if (renderedArgs.isEmpty()) return resultExpr
            val evalPrefix = renderedArgs.mapIndexed { idx, arg -> "val simdArg$idx = $arg" }.joinToString("; ")
            return "run { $evalPrefix; $resultExpr }"
        }
        if (canonicalName in SIMD_BOOL_RETURN_INTRINSICS) {
            return wrapWithSideEffects("false")
        }
        if (canonicalName in SIMD_UNIT_RETURN_INTRINSICS) {
            return wrapWithSideEffects("Unit")
        }
        val sameArgIndex =
            when (canonicalName) {
                in SIMD_SAME_ARG0_INTRINSICS -> 0
                in SIMD_SAME_ARG1_INTRINSICS -> 1
                in SIMD_SAME_ARG2_INTRINSICS -> 2
                else -> -1
            }
        if (sameArgIndex in renderedArgs.indices) {
            val valBind = renderedArgs.mapIndexed { idx, arg -> "val simdArg$idx = $arg" }.joinToString("; ")
            return "run { $valBind; simdArg$sameArgIndex }"
        }
        val explicitRetSlot =
            when (canonicalName) {
                "simd_splat" -> explicitTypes.getOrNull(0)
                "simd_shuffle" -> explicitTypes.getOrNull(2)
                else -> explicitTypes.getOrNull(1)
            }?.takeUnless { it is SynType.Infer }
        if (explicitRetSlot != null) {
            val renderedRet = emitter.renderType(explicitRetSlot, selfTypeName)
            val fallback = emitter.renderFallbackDefaultForRenderedType(renderedRet)
            if (!fallback.startsWith("null as ") && !fallback.startsWith("error(")) {
                return wrapWithSideEffects(fallback)
            }
            val layoutExpr = resolveTypeLayoutExpression(explicitRetSlot, selfTypeName, emitter)
            return wrapWithSideEffects(renderZeroedLayoutReadExpression(layoutExpr, intrPkg, emitter))
        }
        val expectedRendered = (emitter.currentExpectedParseTargetType ?: emitter.currentEnclosingReturnRenderedType)?.trim()?.takeIf { it.isNotEmpty() && it != "Unit" }
        if (expectedRendered != null) {
            val fallback = emitter.renderFallbackDefaultForRenderedType(expectedRendered)
            if (!fallback.startsWith("null as ") && !fallback.startsWith("error(")) {
                return wrapWithSideEffects(fallback)
            }
            val expectedBase = expectedRendered.substringBefore('<').substringAfterLast('.').trim()
            val expectedDecl = emitter.sameFileUniqueSimpleStruct(expectedBase)
            if (expectedDecl != null) {
                val computed = StructLayoutComputer.computeStructLayout(expectedDecl, emitter)
                if (computed != null && computed.requiredTypeLayoutParams.isEmpty()) {
                    return wrapWithSideEffects(renderZeroedLayoutReadExpression("$expectedBase.typeLayout()", intrPkg, emitter))
                }
            }
            return wrapWithSideEffects(renderZeroedLayoutReadExpression(resolveRenderedTypeLayoutExpression(expectedRendered, emitter), intrPkg, emitter))
        }
        if (renderedArgs.isNotEmpty()) {
            val valBind = renderedArgs.mapIndexed { idx, arg -> "val simdArg$idx = $arg" }.joinToString("; ")
            return "run { $valBind; simdArg0 }"
        }
        return "Unit"
    }

    private data class CoreIntrinsicCallSpec(
        val rawName: String,
        val canonicalName: String,
        val ord0: String?,
        val ord1: String?,
        val isIntrinsicsModuleCall: Boolean,
        val penultName: String?,
        val rewrittenSegNames: List<String>,
        val explicitTypes: List<SynType>,
        val explicitConsts: List<Expr>,
        val callArgsList: List<Expr>,
    )

    private fun resolveCoreIntrinsicCallSpec(
        call: Expr.Call,
        emitter: KotlinJ2KEmitter,
    ): CoreIntrinsicCallSpec? {
        val path = (call.func as? Expr.Path)?.takeIf { it.qself == null }?.path ?: return null
        val segments = path.segments.toList()
        if (segments.isEmpty()) return null
        val lastSeg = segments.last()
        val rawName = lastSeg.ident.toString().removePrefix("r#")
        val rewrittenSegNames =
            emitter.rewriteCrateRootAliasAndReexportSegments(
                segments.map { it.ident.toString().removePrefix("r#") },
            )
        val penultName =
            rewrittenSegNames
                .getOrNull(rewrittenSegNames.size - 2)
                ?.removePrefix("r#")
        val isIntrinsicsModuleCall =
            penultName == "intrinsics" ||
                "intrinsics" in rewrittenSegNames.dropLast(1) ||
                (segments.size == 1 && emitter.headerContext.packageName?.endsWith(".intrinsics") == true)
        val genericArgs = (lastSeg.arguments as? PathArguments.AngleBracketed)?.args?.toList().orEmpty()
        val explicitTypes = genericArgs.filterIsInstance<GenericArgument.TypeArg>().map { it.type }
        val explicitConsts = genericArgs.filterIsInstance<GenericArgument.ConstArg>().map { it.expr }
        val parsedAtomic = parseLegacyAtomicOrderingSuffix(rawName)
        return CoreIntrinsicCallSpec(
            rawName = rawName,
            canonicalName = parsedAtomic.baseName,
            ord0 = parsedAtomic.ord0,
            ord1 = parsedAtomic.ord1,
            isIntrinsicsModuleCall = isIntrinsicsModuleCall,
            penultName = penultName,
            rewrittenSegNames = rewrittenSegNames,
            explicitTypes = explicitTypes,
            explicitConsts = explicitConsts,
            callArgsList = call.args.toList(),
        )
    }

    internal fun inferCoreIntrinsicReturnSynType(
        call: Expr.Call,
        emitter: KotlinJ2KEmitter,
    ): SynType? {
        val spec = resolveCoreIntrinsicCallSpec(call, emitter) ?: return null
        if (!spec.isIntrinsicsModuleCall) return null
        return when (spec.canonicalName) {
            "volatile_load",
            "unaligned_volatile_load",
            "read_via_copy",
            "atomic_load",
            "atomic_xchg",
            "atomic_xadd",
            "atomic_xsub",
            "atomic_and",
            "atomic_nand",
            "atomic_or",
            "atomic_xor",
            "atomic_max",
            "atomic_min",
            "atomic_umax",
            "atomic_umin",
            ->
                spec.explicitTypes.firstOrNull()?.takeUnless { it is SynType.Infer }
                    ?: (
                        spec.callArgsList
                            .firstOrNull()
                            ?.let { emitter.sourceInitializerType(it) }
                            ?.let(::unwrapSynType) as? SynType.Ptr
                    )?.elem
            "offset", "arith_offset" ->
                spec.callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) }
            "bswap",
            "bitreverse",
            "rotate_left",
            "rotate_right",
            "wrapping_add",
            "wrapping_sub",
            "wrapping_mul",
            "unchecked_add",
            "unchecked_sub",
            "unchecked_mul",
            "saturating_add",
            "saturating_sub",
            "unchecked_div",
            "unchecked_rem",
            "exact_div",
            "unchecked_shl",
            "unchecked_shr",
            "disjoint_bitor",
            "select_unpredictable",
            "powi",
            "fma",
            "fmuladd",
            in GENERIC_FLOAT_UNARY_KM,
            in GENERIC_FLOAT_BINARY_OP_NAMES,
            ->
                spec.explicitTypes.firstOrNull()?.takeUnless { it is SynType.Infer }
                    ?: spec.callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) }
            "transmute", "transmute_unchecked", "float_to_int_unchecked" ->
                spec.explicitTypes.getOrNull(1)?.takeUnless { it is SynType.Infer }
            else -> null
        }
    }

    private fun isUsableRenderedTypeArg(ty: String?): Boolean {
        val clean = ty?.trim() ?: return false
        if (clean.isEmpty()) return false
        if (clean in setOf("Any", "Any?", "Nothing", "RustAny", "_")) return false
        if ('*' in clean) return false
        if ("->" in clean && !clean.startsWith("(")) return false
        return !clean.startsWith("Result.Err")
    }

    internal fun resolveRenderedTypeLayoutExpression(
        renderedType: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        val coreNs = resolveCoreNamespace(emitter.headerContext)
        val memPkg = "$coreNs.mem"
        val clean = renderedType.trim()
        val expectedLayoutType = "$memPkg.TypeLayout<$clean>"
        emitter.currentTypedLocals
            .filterValues { it == expectedLayoutType }
            .keys
            .singleOrNull()
            ?.let { return it }
        val candidateParam = StructLayoutComputer.typeLayoutParamName(clean)
        if (candidateParam in emitter.currentTypedLocals) {
            return candidateParam
        }
        val structSimple = clean.substringBefore('<').substringAfterLast('.')
        val structDecl = emitter.sameFileUniqueSimpleStruct(structSimple)
        if (structDecl != null) {
            val computed = StructLayoutComputer.computeStructLayout(structDecl, emitter)
            if (computed != null && computed.requiredTypeLayoutParams.isEmpty()) {
                return "$structSimple.typeLayout()"
            }
        }
        registerCoreImport(emitter, "$memPkg.UnresolvedTypeLayout")
        return "UnresolvedTypeLayout<$clean>()"
    }

    internal fun renderTransmuteCall(
        rawTransmuteIdent: String,
        explicitTypes: List<SynType>,
        argExpr: Expr,
        indent: String,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val srcExplicitSyn = explicitTypes.getOrNull(0)?.takeUnless { it is SynType.Infer }
        val dstExplicitSyn = explicitTypes.getOrNull(1)?.takeUnless { it is SynType.Infer }
        val expectedTarget = emitter.currentExpectedParseTargetType?.trim()?.takeIf { it.isNotEmpty() }
        val srcSyn = srcExplicitSyn ?: emitter.sourceInitializerType(argExpr)
        val dstSyn =
            dstExplicitSyn
                ?: if (expectedTarget == null && emitter.callArgumentNestingDepth == 0 && !emitter.inLocalInitializerPosition) {
                    emitter.currentEnclosingReturnSynType
                } else {
                    null
                }
        val prevParseTarget = emitter.currentExpectedParseTargetType
        emitter.currentExpectedParseTargetType = srcExplicitSyn?.let { emitter.renderType(it, selfTypeName) }
        val renderedArg =
            try {
                emitter.renderExpr(argExpr, indent, selfTypeName)
            } finally {
                emitter.currentExpectedParseTargetType = prevParseTarget
            }
        val srcRendered =
            (
                srcSyn?.let { emitter.renderType(it, selfTypeName) }
                    ?: emitter.inferExprRenderedType(argExpr, selfTypeName)
            )?.takeIf(::isUsableRenderedTypeArg)
        val dstRendered =
            (
                dstSyn?.let { emitter.renderType(it, selfTypeName) }
                    ?: expectedTarget
                    ?: if (emitter.callArgumentNestingDepth == 0 && !emitter.inLocalInitializerPosition) {
                        emitter.currentEnclosingReturnRenderedType
                    } else {
                        null
                    }
            )?.takeIf(::isUsableRenderedTypeArg)
        if (srcRendered != null && dstRendered != null) {
            if (srcRendered == dstRendered && srcRendered != "Unit") {
                return renderedArg
            }
            val coreNs = resolveCoreNamespace(emitter.headerContext)
            val ptrPkg = "$coreNs.ptr"
            val memPkg = "$coreNs.mem"
            if (srcSyn != null && dstSyn != null) {
                renderFastScalarTransmute(srcSyn, dstSyn, renderedArg, ptrPkg, emitter)?.let { return it }
            }
            val fromField = emitter.transparentPointerField(srcRendered, selfTypeName)
            if (fromField != null && fromField.second == dstRendered) {
                return "($renderedArg).${fromField.first}"
            }
            val toField = emitter.transparentPointerField(dstRendered, selfTypeName)
            if (toField != null && toField.second == srcRendered) {
                return "$dstRendered($renderedArg)"
            }
            val srcLayout =
                srcSyn?.let { resolveTypeLayoutExpression(it, selfTypeName, emitter) }
                    ?: resolveRenderedTypeLayoutExpression(srcRendered, emitter)
            val dstLayout =
                dstSyn?.let { resolveTypeLayoutExpression(it, selfTypeName, emitter) }
                    ?: resolveRenderedTypeLayoutExpression(dstRendered, emitter)
            registerCoreImport(emitter, "$memPkg.transmuteWithLayouts")
            return "transmuteWithLayouts<$srcRendered, $dstRendered>($renderedArg, $srcLayout, $dstLayout)"
        }
        if ("transmute" !in emitter.inspector.declaredTopLevelFnRawNames &&
            "transmute_unchecked" !in emitter.inspector.declaredTopLevelFnRawNames
        ) {
            val coreNs = resolveCoreNamespace(emitter.headerContext)
            val memPkg = "$coreNs.mem"
            if (srcRendered == null && dstRendered != null) {
                val dstLayout =
                    dstSyn?.let { resolveTypeLayoutExpression(it, selfTypeName, emitter) }
                        ?: resolveRenderedTypeLayoutExpression(dstRendered, emitter)
                registerCoreImport(emitter, "$memPkg.transmuteWithLayouts")
                registerCoreImport(emitter, "$memPkg.UnresolvedTypeLayout")
                return "transmuteWithLayouts<Any?, $dstRendered>($renderedArg, UnresolvedTypeLayout<Any?>(), $dstLayout)"
            }
            val fnName = if (rawTransmuteIdent == "transmute_unchecked") "transmuteUnchecked" else "transmute"
            if (emitter.headerContext.packageName != memPkg) {
                registerCoreImport(emitter, "$memPkg.$fnName")
            }
            return "$fnName($renderedArg)"
        }
        return null
    }

    internal fun inferCoreIntrinsicReturnRenderedType(
        call: Expr.Call,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val spec = resolveCoreIntrinsicCallSpec(call, emitter) ?: return null
        if (spec.rawName in setOf("__rust_alloc", "__rust_alloc_zeroed", "__rust_realloc")) {
            return "MutPtr"
        }
        val isCoreSimdCall =
            isSimdIntrinsicName(spec.canonicalName) &&
                (
                    spec.isIntrinsicsModuleCall ||
                        spec.penultName == "simd" ||
                        spec.penultName == "scalable" ||
                        ((call.func as? Expr.Path)?.path?.segments?.size == 1 && spec.rawName !in emitter.inspector.declaredTopLevelFnRawNames)
                )
        if (!spec.isIntrinsicsModuleCall && !isCoreSimdCall) return null
        val canonical = spec.canonicalName
        if (canonical in setOf("ctpop", "ctlz", "ctlz_nonzero", "cttz", "cttz_nonzero")) {
            return "UInt"
        }
        if (canonical in setOf("needs_drop", "raw_eq", "likely", "unlikely")) {
            return "Boolean"
        }
        if (canonical in
            setOf(
                "ptr_offset_from",
                "ptr_offset_from_unsigned",
                "size_of",
                "size_of_val",
                "align_of",
                "align_of_val",
                "min_align_of_val",
                "vtable_size",
                "vtable_align",
                "variant_count",
            )
        ) {
            return when {
                emitter.currentExpectedNumericKind != null -> null
                emitter.headerContext.rawAbiMode -> "ULong"
                else -> "Int"
            }
        }
        if (canonical in setOf("add_with_overflow", "sub_with_overflow", "mul_with_overflow")) {
            val elemTy =
                spec.explicitTypes
                    .firstOrNull()
                    ?.takeUnless { it is SynType.Infer }
                    ?.let { emitter.renderType(it, selfTypeName) }
                    ?: spec.callArgsList.firstOrNull()?.let { emitter.inferExprRenderedType(it, selfTypeName) }
                    ?: resolveIntrinsicNumericKind(spec.explicitTypes.firstOrNull(), spec.callArgsList.take(2), selfTypeName, emitter)
                        ?.let { emitter.numericKindToPureTypeName(it) }
                    ?: return null
            return "Pair<$elemTy, Boolean>"
        }
        if (canonical in setOf("atomic_cxchg", "atomic_cxchgweak")) {
            val pointeeTy =
                spec.explicitTypes.firstOrNull()?.takeUnless { it is SynType.Infer }
                    ?: (
                        spec.callArgsList
                            .firstOrNull()
                            ?.let { emitter.sourceInitializerType(it) }
                            ?.let(::unwrapSynType) as? SynType.Ptr
                    )?.elem
            val elemTy =
                pointeeTy?.let { emitter.renderType(it, selfTypeName) }
                    ?: spec.callArgsList.getOrNull(1)?.let { emitter.inferExprRenderedType(it, selfTypeName) }
                    ?: return null
            return "Pair<$elemTy, Boolean>"
        }
        val suffixedKm = SUFFIXED_FLOAT_UNARY_KM[canonical] ?: SUFFIXED_FLOAT_BINARY_KM[canonical]
        if (suffixedKm != null) {
            return if (suffixedKm.endsWith("f32")) "Float" else "Double"
        }
        if (canonical in setOf("fmaf32", "fmuladdf32", "minimumf32", "maximumf32")) {
            return "Float"
        }
        if (canonical in setOf("fmaf64", "fmuladdf64", "minimumf64", "maximumf64")) {
            return "Double"
        }
        inferCoreIntrinsicReturnSynType(call, emitter)?.let { return emitter.renderType(it, selfTypeName) }
        if (canonical in
            setOf(
                "bswap",
                "bitreverse",
                "rotate_left",
                "rotate_right",
                "wrapping_add",
                "wrapping_sub",
                "wrapping_mul",
                "unchecked_add",
                "unchecked_sub",
                "unchecked_mul",
                "saturating_add",
                "saturating_sub",
                "unchecked_div",
                "unchecked_rem",
                "exact_div",
                "unchecked_shl",
                "unchecked_shr",
                "disjoint_bitor",
                "select_unpredictable",
                "powi",
                "fma",
                "fmuladd",
            ) ||
            canonical in GENERIC_FLOAT_UNARY_KM ||
            canonical in GENERIC_FLOAT_BINARY_OP_NAMES
        ) {
            return spec.callArgsList.firstOrNull()?.let { emitter.inferExprRenderedType(it, selfTypeName) }
        }
        if (canonical in setOf("transmute", "transmute_unchecked", "float_to_int_unchecked")) {
            return spec.explicitTypes
                .getOrNull(1)
                ?.takeUnless { it is SynType.Infer }
                ?.let { emitter.renderType(it, selfTypeName) }
        }
        if (canonical.startsWith("sve_tuple_")) {
            return null
        }
        if (canonical in CORE_SIMD_INTRINSIC_NAMES) {
            when (canonical) {
                "simd_splat" ->
                    return spec.explicitTypes
                        .firstOrNull()
                        ?.takeUnless { it is SynType.Infer }
                        ?.let { emitter.renderType(it, selfTypeName) }
                "simd_shuffle" ->
                    return spec.explicitTypes
                        .getOrNull(2)
                        ?.takeUnless { it is SynType.Infer }
                        ?.let { emitter.renderType(it, selfTypeName) }
                "simd_cast", "simd_as", "simd_bitmask" ->
                    return spec.explicitTypes
                        .getOrNull(1)
                        ?.takeUnless { it is SynType.Infer }
                        ?.let { emitter.renderType(it, selfTypeName) }
                "simd_select" ->
                    return spec.explicitTypes
                        .getOrNull(1)
                        ?.takeUnless { it is SynType.Infer }
                        ?.let { emitter.renderType(it, selfTypeName) }
                        ?: spec.callArgsList.getOrNull(1)?.let { emitter.inferExprRenderedType(it, selfTypeName) }
                in SIMD_BOOL_RETURN_INTRINSICS -> return "Boolean"
                in SIMD_UNIT_RETURN_INTRINSICS -> return "Unit"
                in SIMD_SAME_ARG0_INTRINSICS ->
                    return spec.explicitTypes
                        .firstOrNull()
                        ?.takeUnless { it is SynType.Infer }
                        ?.let { emitter.renderType(it, selfTypeName) }
                        ?.takeIf(::isUsableRenderedTypeArg)
                        ?: run {
                            val canInferFromPeerArgs =
                                canonical !in
                                    setOf(
                                        "simd_insert",
                                        "simd_insert_dyn",
                                        "simd_gather",
                                        "simd_arith_offset",
                                        "simd_select_bitmask",
                                        "simd_scatter",
                                        "simd_masked_load",
                                        "simd_masked_store",
                                    )
                            if (canInferFromPeerArgs) {
                                spec.callArgsList.firstNotNullOfOrNull { argExpr ->
                                    val callArg = emitter.unwrapParenOrGroupExpr(argExpr) as? Expr.Call
                                    val rawCallName =
                                        (callArg?.func as? Expr.Path)
                                            ?.path
                                            ?.segments
                                            ?.toList()
                                            ?.lastOrNull()
                                            ?.ident
                                            ?.toString()
                                            ?.removePrefix("r#")
                                    if (rawCallName in setOf("transmute", "transmute_unchecked")) {
                                        null
                                    } else {
                                        emitter
                                            .inferExprRenderedType(argExpr, selfTypeName)
                                            ?.takeIf(::isUsableRenderedTypeArg)
                                    }
                                }
                            } else {
                                null
                            }
                        }
                        ?: spec.callArgsList.firstOrNull()?.let { arg0 ->
                            emitter.inferExprRenderedType(arg0, selfTypeName)?.takeIf(::isUsableRenderedTypeArg)
                                ?: run {
                                    val callArg = emitter.unwrapParenOrGroupExpr(arg0) as? Expr.Call
                                    val callPath = (callArg?.func as? Expr.Path)?.takeIf { it.qself == null }?.path
                                    val callName =
                                        callPath
                                            ?.segments
                                            ?.last()
                                            ?.ident
                                            ?.toString()
                                            ?.removePrefix("r#")
                                    if (callName in setOf("transmute", "transmute_unchecked") && callArg?.args?.len() == 1) {
                                        emitter
                                            .inferExprRenderedType(callArg.args.first()!!, selfTypeName)
                                            ?.takeIf(::isUsableRenderedTypeArg)
                                    } else {
                                        null
                                    }
                                }
                        }
                in SIMD_SAME_ARG1_INTRINSICS ->
                    return spec.callArgsList.getOrNull(1)?.let { emitter.inferExprRenderedType(it, selfTypeName) }
                in SIMD_SAME_ARG2_INTRINSICS ->
                    return spec.callArgsList.getOrNull(2)?.let { emitter.inferExprRenderedType(it, selfTypeName) }
                else ->
                    return spec.explicitTypes
                        .firstOrNull()
                        ?.takeUnless { it is SynType.Infer }
                        ?.let { emitter.renderType(it, selfTypeName) }
                        ?: spec.callArgsList.firstOrNull()?.let { emitter.inferExprRenderedType(it, selfTypeName) }
            }
        }
        return null
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
        val rewrittenSegNames =
            emitter.rewriteCrateRootAliasAndReexportSegments(
                segments.map { it.ident.toString().removePrefix("r#") },
            )
        val penultName =
            rewrittenSegNames
                .getOrNull(rewrittenSegNames.size - 2)
                ?.removePrefix("r#")
        val spec = resolveCoreIntrinsicCallSpec(call, emitter) ?: return null
        val rawName = spec.rawName

        // Support `alloc::alloc::__rust_alloc` / `__rust_dealloc` / `__rust_realloc` / `__rust_alloc_zeroed`
        if (rawName in setOf("__rust_alloc", "__rust_dealloc", "__rust_realloc", "__rust_alloc_zeroed")) {
            val coreNs = resolveCoreNamespace(emitter.headerContext)
            val intrPkg = "$coreNs.intrinsics"
            val ptrPkg = "$coreNs.ptr"
            val args = spec.callArgsList.map { emitter.renderExpr(it, indent, selfTypeName) }
            registerCoreImport(emitter, "$ptrPkg.MutPtr")
            fun alignArgExpr(argIdx: Int): String {
                val alignSyn = spec.callArgsList.getOrNull(argIdx)
                val alignTy = alignSyn?.let { emitter.inferExprRenderedType(it, selfTypeName) }?.removeSuffix("?")?.trim()
                val isAlignmentMethodCall =
                    (alignSyn?.let { emitter.unwrapParenOrGroupExpr(it) } as? Expr.MethodCall)
                        ?.method
                        ?.toString()
                        ?.removePrefix("r#") == "alignment"
                val rawArg = args[argIdx]
                return if (alignTy == "Alignment" || alignTy?.endsWith(".Alignment") == true || (alignTy == null && isAlignmentMethodCall)) {
                    "($rawArg).asUsize().toLong()"
                } else {
                    "($rawArg).toLong()"
                }
            }
            return when (rawName) {
                "__rust_alloc" -> {
                    registerCoreImport(emitter, "$intrPkg.kmAlloc")
                    "MutPtr(kmAlloc((${args[0]}).toLong(), ${alignArgExpr(1)}))"
                }
                "__rust_alloc_zeroed" -> {
                    registerCoreImport(emitter, "$intrPkg.kmAllocZeroed")
                    "MutPtr(kmAllocZeroed((${args[0]}).toLong(), ${alignArgExpr(1)}))"
                }
                "__rust_realloc" -> {
                    registerCoreImport(emitter, "$intrPkg.kmRealloc")
                    "MutPtr(kmRealloc((${args[0]}).rawAddress, (${args[1]}).toLong(), ${alignArgExpr(2)}, (${args[3]}).toLong()))"
                }
                "__rust_dealloc" -> {
                    registerCoreImport(emitter, "$intrPkg.kmDealloc")
                    "kmDealloc((${args[0]}).rawAddress, (${args[1]}).toLong(), ${alignArgExpr(2)})"
                }
                else -> null
            }
        }

        val callArgsList = spec.callArgsList

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

        val isIntrinsicsModuleCall = spec.isIntrinsicsModuleCall
        val isCoreSimdCall =
            (
                isSimdIntrinsicName(spec.canonicalName) &&
                    (
                        isIntrinsicsModuleCall ||
                            penultName == "simd" ||
                            penultName == "scalable" ||
                            (segments.size == 1 && rawName !in emitter.inspector.declaredTopLevelFnRawNames)
                    )
            ) ||
                (isIntrinsicsModuleCall && "simd" in rewrittenSegNames.dropLast(1))
        if (!isIntrinsicsModuleCall && !isCoreSimdCall) {
            return null
        }

        val coreNs = resolveCoreNamespace(emitter.headerContext)
        val intrPkg = "$coreNs.intrinsics"
        val memPkg = "$coreNs.mem"
        val ptrPkg = "$coreNs.ptr"
        val explicitTypes = spec.explicitTypes
        val explicitConsts = spec.explicitConsts
        val simdExpectedArgTarget =
            if (isCoreSimdCall) {
                val canInferFromPeerArgs =
                    spec.canonicalName !in
                        setOf("simd_select_bitmask", "simd_gather", "simd_scatter", "simd_masked_load", "simd_masked_store")
                explicitTypes
                    .firstOrNull()
                    ?.takeUnless { it is SynType.Infer }
                    ?.let { emitter.renderType(it, selfTypeName) }
                    ?.takeIf(::isUsableRenderedTypeArg)
                    ?: if (canInferFromPeerArgs) {
                        callArgsList.firstNotNullOfOrNull { argExpr ->
                            val rawCallName =
                                ((argExpr as? Expr.Call)?.func as? Expr.Path)
                                    ?.path
                                    ?.segments
                                    ?.toList()
                                    ?.lastOrNull()
                                    ?.ident
                                    ?.toString()
                                    ?.removePrefix("r#")
                            if (rawCallName in setOf("transmute", "transmute_unchecked")) {
                                null
                            } else {
                                emitter
                                    .inferExprRenderedType(argExpr, selfTypeName)
                                    ?.takeIf(::isUsableRenderedTypeArg)
                            }
                        }
                    } else {
                        null
                    }
                    ?: if (spec.canonicalName in SIMD_SAME_ARG0_INTRINSICS &&
                        spec.canonicalName !in setOf("simd_insert", "simd_insert_dyn", "simd_gather", "simd_arith_offset")
                    ) {
                        (
                            emitter.currentExpectedParseTargetType
                                ?: if (emitter.callArgumentNestingDepth == 0) emitter.currentEnclosingReturnRenderedType else null
                        )?.takeIf(::isUsableRenderedTypeArg)
                    } else {
                        null
                    }
                    ?: if (canInferFromPeerArgs) {
                        callArgsList.firstNotNullOfOrNull { argExpr ->
                            val callArg = emitter.unwrapParenOrGroupExpr(argExpr) as? Expr.Call
                            val rawCallName =
                                (callArg?.func as? Expr.Path)
                                    ?.takeIf { it.qself == null }
                                    ?.path
                                    ?.segments
                                    ?.toList()
                                    ?.lastOrNull()
                                    ?.ident
                                    ?.toString()
                                    ?.removePrefix("r#")
                            if (rawCallName in setOf("transmute", "transmute_unchecked") && callArg?.args?.len() == 1) {
                                emitter
                                    .inferExprRenderedType(callArg.args.first()!!, selfTypeName)
                                    ?.takeIf(::isUsableRenderedTypeArg)
                            } else {
                                null
                            }
                        }
                    } else {
                        null
                    }
            } else {
                null
            }
        emitter.callArgumentNestingDepth++
        val renderedArgs =
            try {
                if (simdExpectedArgTarget != null) {
                    callArgsList.mapIndexed { argIdx, argExpr ->
                        val shouldPropagate =
                            when (spec.canonicalName) {
                                "simd_shuffle" -> argIdx < 2
                                "simd_extract" -> argIdx == 0
                                "simd_insert" -> argIdx == 0
                                else -> true
                            }
                        val prevTarget = emitter.currentExpectedParseTargetType
                        emitter.currentExpectedParseTargetType = if (shouldPropagate) simdExpectedArgTarget else null
                        try {
                            emitter.renderExpr(argExpr, indent, selfTypeName)
                        } finally {
                            emitter.currentExpectedParseTargetType = prevTarget
                        }
                    }
                } else {
                    val prevTarget = emitter.currentExpectedParseTargetType
                    emitter.currentExpectedParseTargetType = null
                    try {
                        callArgsList.map { emitter.renderExpr(it, indent, selfTypeName) }
                    } finally {
                        emitter.currentExpectedParseTargetType = prevTarget
                    }
                }
            } finally {
                emitter.callArgumentNestingDepth--
            }

        val canonicalIntrinsicName = spec.canonicalName

        fun orderingCode(constIdx: Int): String {
            val legacyOrd = if (constIdx == 0) spec.ord0 else spec.ord1
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
            "abort", "abort_immediate" -> {
                registerCoreImport(emitter, "$intrPkg.kmAbort")
                "kmAbort()"
            }
            "unreachable" -> "error(\"Entered Rust intrinsics::unreachable\")"
            "assume", "breakpoint", "forget", "cold_path", "assert_inhabited", "assert_zero_valid", "assert_mem_uninitialized_valid",
            "prefetch_read_data", "prefetch_write_data", "prefetch_read_instruction", "prefetch_write_instruction",
            -> "Unit"
            "likely", "unlikely", "rustc_peek" -> renderedArgs.firstOrNull() ?: "Unit"
            "select_unpredictable" ->
                if (renderedArgs.size >= 3) "if (${renderedArgs[0]}) ${renderedArgs[1]} else ${renderedArgs[2]}" else null
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
            "size_of_val" -> {
                val ptrExpr = renderedArgs.firstOrNull() ?: return null
                val firstArg = callArgsList.firstOrNull()
                val pointeeTy =
                    explicitTypes.firstOrNull()?.takeUnless { it is SynType.Infer }
                        ?: when (val argTy = firstArg?.let { emitter.sourceInitializerType(it) }) {
                            is SynType.Ptr -> argTy.elem
                            is SynType.Reference -> argTy.elem
                            else -> argTy
                        }
                        ?: return null
                val layout = resolveTypeLayoutExpression(pointeeTy, selfTypeName, emitter)
                if (emitter.headerContext.rawAbiMode) {
                    "($layout).sizeBytes.toULong()"
                } else {
                    val isPtrArg =
                        firstArg?.let { emitter.inferExprRenderedType(it, selfTypeName) }?.let {
                            it.startsWith("ConstPtr") || it.startsWith("MutPtr") || ".ptr." in it
                        } == true
                    if (isPtrArg) {
                        val atomPtr = if (emitter.isAtomicPostfixReceiver(firstArg, ptrExpr)) ptrExpr else "($ptrExpr)"
                        "(if ($atomPtr.rawMeta > 0L) ($atomPtr.rawMeta * ($layout).sizeBytes).toInt() else ($layout).sizeBytes.toInt())"
                    } else {
                        "($layout).sizeBytes.toInt()"
                    }
                }
            }
            "align_of", "align_of_val", "min_align_of", "min_align_of_val", "pref_align_of" -> {
                val firstArg = callArgsList.firstOrNull()
                val ty =
                    explicitTypes.firstOrNull()?.takeUnless { it is SynType.Infer }
                        ?: when (val argTy = firstArg?.let { emitter.sourceInitializerType(it) }) {
                            is SynType.Ptr -> argTy.elem
                            is SynType.Reference -> argTy.elem
                            else -> argTy
                        }
                        ?: return null
                val layout = resolveTypeLayoutExpression(ty, selfTypeName, emitter)
                if (emitter.headerContext.rawAbiMode) "($layout).alignBytes.toULong()" else "($layout).alignBytes.toInt()"
            }
            "vtable_size", "vtable_align" -> if (emitter.headerContext.rawAbiMode) "8uL" else "8"
            "variant_count" -> if (emitter.headerContext.rawAbiMode) "1uL" else "1"
            "raw_eq" -> {
                if (renderedArgs.size < 2) return null
                val targetTy =
                    explicitTypes.firstOrNull()?.takeUnless { it is SynType.Infer }
                        ?: callArgsList.firstOrNull()?.let { firstArg ->
                            when (val argTy = emitter.sourceInitializerType(firstArg)) {
                                is SynType.Ptr -> argTy.elem
                                is SynType.Reference -> argTy.elem
                                else -> argTy
                            }
                        }
                        ?: return null
                registerCoreImport(emitter, "$memPkg.rawEqWithLayout")
                val layout = resolveTypeLayoutExpression(targetTy, selfTypeName, emitter)
                "rawEqWithLayout(${renderedArgs[0]}, ${renderedArgs[1]}, $layout)"
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
                        primName in setOf("bool", "char", "i8", "u8", "i16", "u16", "i32", "u32", "i64", "u64", "i128", "u128", "isize", "usize", "f16", "f32", "f64", "f128")
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
                val pointee = resolvePointeeTypeFromArg(explicitTypes, callArgsList, 0, emitter)
                pointee?.let {
                    DirectLoadIntrinsicEmitter(
                        fnPrefix = if (isVolatile) "kmVolatileLoad" else "kmLoad",
                        allowChar = true,
                        allowFloat = true,
                        isVolatile = isVolatile,
                        trailingArgs = emptyList(),
                        intrPkg = intrPkg,
                        ptrPkg = ptrPkg,
                        emitter = emitter,
                    ).render(it, rawAddrOf(0)) ?: run {
                        val layout = resolveTypeLayoutExpression(it, selfTypeName, emitter)
                        val method = if (isVolatile) "readVolatileNative" else "readNative"
                        "($layout).$method(${rawAddrOf(0)})"
                    }
                }
            }
            "write_via_move", "volatile_store", "unaligned_volatile_store", "nontemporal_store" -> {
                val isVolatile = rawName == "volatile_store" || rawName == "unaligned_volatile_store"
                val pointee = resolvePointeeTypeFromArg(explicitTypes, callArgsList, 0, emitter)
                pointee?.let {
                    DirectStoreIntrinsicEmitter(
                        fnPrefix = if (isVolatile) "kmVolatileStore" else "kmStore",
                        allowChar = true,
                        allowFloat = true,
                        isVolatile = isVolatile,
                        trailingArgs = emptyList(),
                        intrPkg = intrPkg,
                        ptrPkg = ptrPkg,
                        emitter = emitter,
                    ).render(it, rawAddrOf(0), renderedArgs[1]) ?: run {
                        val layout = resolveTypeLayoutExpression(it, selfTypeName, emitter)
                        val method = if (isVolatile) "writeVolatileNative" else "writeNative"
                        "($layout).$method(${rawAddrOf(0)}, ${renderedArgs[1]})"
                    }
                }
            }
            "copy_nonoverlapping", "volatile_copy_nonoverlapping_memory", "copy", "volatile_copy_memory" -> {
                val fnName = if (rawName.contains("nonoverlapping")) "kmCopyNonoverlapping" else "kmCopy"
                registerCoreImport(emitter, "$intrPkg.$fnName")
                val srcIdx = if (rawName.startsWith("volatile_")) 1 else 0
                val dstIdx = if (rawName.startsWith("volatile_")) 0 else 1
                val pointee = resolvePointeeTypeFromArg(explicitTypes, callArgsList, srcIdx, emitter)
                val bytesExpr = renderScaledByteCountExpr(pointee, renderedArgs[2], selfTypeName, emitter)
                "$fnName(${rawAddrOf(dstIdx)}, ${rawAddrOf(srcIdx)}, $bytesExpr)"
            }
            "write_bytes", "volatile_set_memory" -> {
                registerCoreImport(emitter, "$intrPkg.kmWriteBytes")
                val pointee = resolvePointeeTypeFromArg(explicitTypes, callArgsList, 0, emitter)
                val bytesExpr = renderScaledByteCountExpr(pointee, renderedArgs[2], selfTypeName, emitter)
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
                val pointee = resolvePointeeTypeFromArg(explicitTypes, callArgsList, 0, emitter) ?: return null
                DirectLoadIntrinsicEmitter(
                    fnPrefix = "kmAtomicLoad",
                    allowChar = false,
                    allowFloat = false,
                    isVolatile = false,
                    trailingArgs = listOf(orderingCode(0)),
                    intrPkg = intrPkg,
                    ptrPkg = ptrPkg,
                    emitter = emitter,
                ).render(pointee, rawAddrOf(0)) ?: run {
                    registerCoreImport(emitter, "$memPkg.atomicLoadWithLayout")
                    val layout = resolveTypeLayoutExpression(pointee, selfTypeName, emitter)
                    "atomicLoadWithLayout(${rawAddrOf(0)}, ${orderingCode(0)}, $layout)"
                }
            }
            "atomic_store" -> {
                val pointee = resolvePointeeTypeFromArg(explicitTypes, callArgsList, 0, emitter) ?: return null
                DirectStoreIntrinsicEmitter(
                    fnPrefix = "kmAtomicStore",
                    allowChar = false,
                    allowFloat = false,
                    isVolatile = false,
                    trailingArgs = listOf(orderingCode(0)),
                    intrPkg = intrPkg,
                    ptrPkg = ptrPkg,
                    emitter = emitter,
                ).render(pointee, rawAddrOf(0), renderedArgs[1]) ?: run {
                    registerCoreImport(emitter, "$memPkg.atomicStoreWithLayout")
                    val layout = resolveTypeLayoutExpression(pointee, selfTypeName, emitter)
                    "atomicStoreWithLayout(${rawAddrOf(0)}, ${renderedArgs[1]}, ${orderingCode(0)}, $layout)"
                }
            }
            "atomic_xchg" -> {
                val pointee = resolvePointeeTypeFromArg(explicitTypes, callArgsList, 0, emitter) ?: return null
                DirectReadModifyWriteIntrinsicEmitter(
                    fnPrefix = "kmAtomicXchg",
                    trailingArgs = listOf(orderingCode(0)),
                    intrPkg = intrPkg,
                    ptrPkg = ptrPkg,
                    emitter = emitter,
                ).render(pointee, rawAddrOf(0), renderedArgs[1]) ?: run {
                    registerCoreImport(emitter, "$memPkg.atomicXchgWithLayout")
                    val layout = resolveTypeLayoutExpression(pointee, selfTypeName, emitter)
                    "atomicXchgWithLayout(${rawAddrOf(0)}, ${renderedArgs[1]}, ${orderingCode(0)}, $layout)"
                }
            }
            "atomic_cxchg", "atomic_cxchgweak" -> {
                val pointee = resolvePointeeTypeFromArg(explicitTypes, callArgsList, 0, emitter) ?: return null
                DirectCompareExchangeIntrinsicEmitter(intrPkg, ptrPkg, emitter)
                    .render(pointee, rawAddrOf(0), renderedArgs[1], renderedArgs[2], orderingCode(0), orderingCode(1))
                    ?: run {
                        registerCoreImport(emitter, "$memPkg.atomicCxchgWithLayout")
                        val layout = resolveTypeLayoutExpression(pointee, selfTypeName, emitter)
                        "atomicCxchgWithLayout(${rawAddrOf(0)}, ${renderedArgs[1]}, ${renderedArgs[2]}, ${orderingCode(0)}, ${orderingCode(1)}, $layout)"
                    }
            }
            in ATOMIC_RMW_OP_CODES -> {
                val opCode = ATOMIC_RMW_OP_CODES.getValue(canonicalIntrinsicName)
                val pointee = resolvePointeeTypeFromArg(explicitTypes, callArgsList, 0, emitter) ?: return null
                val operandType = explicitTypes.getOrNull(1) ?: pointee
                val direct =
                    if (explicitTypes.size <= 1) {
                        DirectReadModifyWriteIntrinsicEmitter(
                            fnPrefix = "kmAtomicRmw",
                            trailingArgs = listOf(opCode.toString(), orderingCode(0)),
                            intrPkg = intrPkg,
                            ptrPkg = ptrPkg,
                            emitter = emitter,
                        ).render(pointee, rawAddrOf(0), renderedArgs[1])
                    } else {
                        null
                    }
                direct ?: run {
                    registerCoreImport(emitter, "$memPkg.atomicRmwWithLayout")
                    val dstLayout = resolveTypeLayoutExpression(pointee, selfTypeName, emitter)
                    val opLayout = resolveTypeLayoutExpression(operandType, selfTypeName, emitter)
                    "atomicRmwWithLayout(${rawAddrOf(0)}, ${renderedArgs[1]}, $opCode, ${orderingCode(0)}, $dstLayout, $opLayout)"
                }
            }
            in INT_INTRINSIC_RULES_BY_NAME ->
                renderIntOrBitIntrinsicCall(
                    canonicalName = canonicalIntrinsicName,
                    renderedArgs = renderedArgs,
                    callArgsList = callArgsList,
                    explicitTypes = explicitTypes,
                    selfTypeName = selfTypeName,
                    coreNs = coreNs,
                    intrPkg = intrPkg,
                    memPkg = memPkg,
                    emitter = emitter,
                )
            in FLOAT_INTRINSIC_RULES_BY_NAME ->
                renderFloatIntrinsicCall(
                    canonicalName = canonicalIntrinsicName,
                    renderedArgs = renderedArgs,
                    callArgsList = callArgsList,
                    explicitTypes = explicitTypes,
                    selfTypeName = selfTypeName,
                    coreNs = coreNs,
                    intrPkg = intrPkg,
                    memPkg = memPkg,
                    emitter = emitter,
                )
            "transmute", "transmute_unchecked" ->
                callArgsList.singleOrNull()?.let { arg0 ->
                    renderTransmuteCall(
                        rawTransmuteIdent = rawName,
                        explicitTypes = explicitTypes,
                        argExpr = arg0,
                        indent = indent,
                        selfTypeName = selfTypeName,
                        emitter = emitter,
                    )
                }
            else ->
                if (isCoreSimdCall) {
                    renderSimdCallSiteLowering(
                        canonicalName = canonicalIntrinsicName,
                        renderedArgs = renderedArgs,
                        explicitTypes = explicitTypes,
                        selfTypeName = selfTypeName,
                        intrPkg = intrPkg,
                        emitter = emitter,
                    )
                } else {
                    null
                }
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

    internal fun explicitScalarRustName(type: SynType?, emitter: KotlinJ2KEmitter): String? {
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
            it in setOf("bool", "char", "i8", "u8", "i16", "u16", "i32", "u32", "i64", "u64", "i128", "u128", "isize", "usize", "f16", "f32", "f64", "f128")
        }
    }

    private fun staticScalarByteWidth(type: SynType, emitter: KotlinJ2KEmitter): Long? {
        val resolved = emitter.resolveLayoutType(type, emptyMap())
        if (resolved is SynType.Ptr) return (emitter.activeCfgProfile.pointerWidth / 8).toLong()
        return when (explicitScalarRustName(resolved, emitter)) {
            "bool", "i8", "u8" -> 1L
            "i16", "u16", "f16" -> 2L
            "i32", "u32", "f32", "char" -> 4L
            "i64", "u64", "f64" -> 8L
            "isize", "usize" -> (emitter.activeCfgProfile.pointerWidth / 8).toLong()
            "i128", "u128", "f128" -> 16L
            else -> null
        }
    }

    internal fun rustScalarToNumericKind(prim: String, emitter: KotlinJ2KEmitter): NumericKind? =
        when (prim) {
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
            "isize" -> if (emitter.headerContext.rawAbiMode) NumericKind.I64 else NumericKind.I32
            "usize" -> if (emitter.headerContext.rawAbiMode) NumericKind.U64 else NumericKind.U32
            "f16" -> NumericKind.F16
            "f32" -> NumericKind.F32
            "f64" -> NumericKind.F64
            "f128" -> NumericKind.F128
            else -> null
        }

    internal fun resolveIntrinsicNumericKind(
        explicitType: SynType?,
        argExprs: List<Expr>,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): NumericKind? {
        explicitType?.takeUnless { it is SynType.Infer }?.let { ty ->
            explicitScalarRustName(ty, emitter)?.let { rustScalarToNumericKind(it, emitter) }?.let { return it }
        }
        val firstNonLit = argExprs.firstOrNull { emitter.unwrapParenOrGroupExpr(it) !is Expr.Lit }
        if (firstNonLit != null) {
            emitter.inferExprNumericKind(firstNonLit, selfTypeName)?.let { return it }
        }
        for (arg in argExprs) {
            emitter.inferExprNumericKind(arg, selfTypeName)?.let { return it }
        }
        return null
    }


    private fun resolvePointeeTypeFromArg(
        explicitTypes: List<SynType>,
        callArgsList: List<Expr>,
        argIdx: Int,
        emitter: KotlinJ2KEmitter,
    ): SynType? =
        explicitTypes.firstOrNull()
            ?: (callArgsList.getOrNull(argIdx)?.let { emitter.sourceInitializerType(it) } as? SynType.Ptr)?.elem

    private fun renderScaledByteCountExpr(
        pointee: SynType?,
        countExpr: String,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): String {
        val elemSize = pointee?.let { staticScalarByteWidth(it, emitter) }
        return when {
            elemSize == 1L -> "($countExpr).toLong()"
            elemSize != null -> "($countExpr).toLong() * ${elemSize}L"
            pointee != null -> {
                val layout = resolveTypeLayoutExpression(pointee, selfTypeName, emitter)
                "($countExpr).toLong() * ($layout).sizeBytes"
            }
            else -> "($countExpr).toLong()"
        }
    }

}

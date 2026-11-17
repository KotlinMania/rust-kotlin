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

    private val GENERIC_FLOAT_UNARY_KM: Map<String, Pair<String, String>> =
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

    private val SUFFIXED_FLOAT_UNARY_KM: Map<String, String> =
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

    private val SUFFIXED_FLOAT_BINARY_KM: Map<String, String> =
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

    private val GENERIC_FLOAT_BINARY_OP_NAMES: Map<String, String> =
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
            in SUFFIXED_FLOAT_UNARY_KM -> {
                val kmFn = SUFFIXED_FLOAT_UNARY_KM.getValue(canonicalIntrinsicName)
                registerCoreImport(emitter, "$intrPkg.$kmFn")
                "$kmFn($p0)"
            }
            in SUFFIXED_FLOAT_BINARY_KM -> {
                val kmFn = SUFFIXED_FLOAT_BINARY_KM.getValue(canonicalIntrinsicName)
                registerCoreImport(emitter, "$intrPkg.$kmFn")
                "$kmFn($p0, $p1)"
            }
            "fmaf32", "fmuladdf32" -> {
                registerCoreImport(emitter, "$intrPkg.kmFmaf32")
                "kmFmaf32($p0, $p1, $p2)"
            }
            "fmaf64", "fmuladdf64" -> {
                registerCoreImport(emitter, "$intrPkg.kmFmaf64")
                "kmFmaf64($p0, $p1, $p2)"
            }
            "minimumf32", "maximumf32" -> {
                val op = if (canonicalIntrinsicName.startsWith("min")) "minimum" else "maximum"
                registerCoreImport(emitter, "$memPkg.floatBinaryWithLayout")
                registerCoreImport(emitter, "$memPkg.PrimitiveLayouts")
                "floatBinaryWithLayout(\"$op\", $p0, $p1, PrimitiveLayouts.F32)"
            }
            "minimumf64", "maximumf64" -> {
                val op = if (canonicalIntrinsicName.startsWith("min")) "minimum" else "maximum"
                registerCoreImport(emitter, "$memPkg.floatBinaryWithLayout")
                registerCoreImport(emitter, "$memPkg.PrimitiveLayouts")
                "floatBinaryWithLayout(\"$op\", $p0, $p1, PrimitiveLayouts.F64)"
            }
            in GENERIC_FLOAT_UNARY_KM -> {
                registerCoreImport(emitter, "$memPkg.floatUnaryWithLayout")
                "floatUnaryWithLayout(\"$canonicalIntrinsicName\", $p0, $layout0)"
            }
            in GENERIC_FLOAT_BINARY_OP_NAMES -> {
                val op = GENERIC_FLOAT_BINARY_OP_NAMES.getValue(canonicalIntrinsicName)
                registerCoreImport(emitter, "$memPkg.floatBinaryWithLayout")
                "floatBinaryWithLayout(\"$op\", $p0, $p1, $layout0)"
            }
            "powi" -> {
                registerCoreImport(emitter, "$memPkg.floatPowiWithLayout")
                "floatPowiWithLayout($p0, $p1, $layout0)"
            }
            "fma", "fmuladd" -> {
                registerCoreImport(emitter, "$memPkg.floatFmaWithLayout")
                "floatFmaWithLayout($p0, $p1, $p2, $layout0)"
            }
            "float_to_int_unchecked" -> {
                registerCoreImport(emitter, "$memPkg.floatToIntUncheckedWithLayout")
                "floatToIntUncheckedWithLayout($p0, $layout0, $layout1)"
            }
            "ctpop" -> {
                registerCoreImport(emitter, "$memPkg.ctpopWithLayout")
                "ctpopWithLayout($p0, $layout0)"
            }
            "ctlz", "ctlz_nonzero" -> {
                registerCoreImport(emitter, "$memPkg.ctlzWithLayout")
                "ctlzWithLayout($p0, $layout0)"
            }
            "cttz", "cttz_nonzero" -> {
                registerCoreImport(emitter, "$memPkg.cttzWithLayout")
                "cttzWithLayout($p0, $layout0)"
            }
            "bswap" -> {
                registerCoreImport(emitter, "$memPkg.bswapWithLayout")
                "bswapWithLayout($p0, $layout0)"
            }
            "bitreverse" -> {
                registerCoreImport(emitter, "$memPkg.bitreverseWithLayout")
                "bitreverseWithLayout($p0, $layout0)"
            }
            "rotate_left" -> {
                registerCoreImport(emitter, "$memPkg.rotateLeftWithLayout")
                "rotateLeftWithLayout($p0, ($p1).toUInt(), $layout0)"
            }
            "rotate_right" -> {
                registerCoreImport(emitter, "$memPkg.rotateRightWithLayout")
                "rotateRightWithLayout($p0, ($p1).toUInt(), $layout0)"
            }
            "wrapping_add", "unchecked_add" -> {
                registerCoreImport(emitter, "$memPkg.wrappingAddWithLayout")
                "wrappingAddWithLayout($p0, $p1, $layout0)"
            }
            "wrapping_sub", "unchecked_sub" -> {
                registerCoreImport(emitter, "$memPkg.wrappingSubWithLayout")
                "wrappingSubWithLayout($p0, $p1, $layout0)"
            }
            "wrapping_mul", "unchecked_mul" -> {
                registerCoreImport(emitter, "$memPkg.wrappingMulWithLayout")
                "wrappingMulWithLayout($p0, $p1, $layout0)"
            }
            "saturating_add" -> {
                registerCoreImport(emitter, "$memPkg.saturatingAddWithLayout")
                "saturatingAddWithLayout($p0, $p1, $layout0)"
            }
            "saturating_sub" -> {
                registerCoreImport(emitter, "$memPkg.saturatingSubWithLayout")
                "saturatingSubWithLayout($p0, $p1, $layout0)"
            }
            "add_with_overflow" -> {
                registerCoreImport(emitter, "$memPkg.addWithOverflowWithLayout")
                "addWithOverflowWithLayout($p0, $p1, $layout0)"
            }
            "sub_with_overflow" -> {
                registerCoreImport(emitter, "$memPkg.subWithOverflowWithLayout")
                "subWithOverflowWithLayout($p0, $p1, $layout0)"
            }
            "mul_with_overflow" -> {
                registerCoreImport(emitter, "$memPkg.mulWithOverflowWithLayout")
                "mulWithOverflowWithLayout($p0, $p1, $layout0)"
            }
            "unchecked_div" -> {
                registerCoreImport(emitter, "$memPkg.uncheckedDivWithLayout")
                "uncheckedDivWithLayout($p0, $p1, $layout0)"
            }
            "unchecked_rem" -> {
                registerCoreImport(emitter, "$memPkg.uncheckedRemWithLayout")
                "uncheckedRemWithLayout($p0, $p1, $layout0)"
            }
            "exact_div" -> {
                registerCoreImport(emitter, "$memPkg.exactDivWithLayout")
                "exactDivWithLayout($p0, $p1, $layout0)"
            }
            "unchecked_shl" -> {
                registerCoreImport(emitter, "$memPkg.uncheckedShlWithLayout")
                if (typeParams.size >= 2) {
                    "uncheckedShlWithLayout($p0, $p1, $layout0, $layout1)"
                } else {
                    "uncheckedShlWithLayout($p0, ($p1).toUInt(), $layout0)"
                }
            }
            "unchecked_shr" -> {
                registerCoreImport(emitter, "$memPkg.uncheckedShrWithLayout")
                if (typeParams.size >= 2) {
                    "uncheckedShrWithLayout($p0, $p1, $layout0, $layout1)"
                } else {
                    "uncheckedShrWithLayout($p0, ($p1).toUInt(), $layout0)"
                }
            }
            "disjoint_bitor" -> {
                registerCoreImport(emitter, "$memPkg.disjointBitorWithLayout")
                "disjointBitorWithLayout($p0, $p1, $layout0)"
            }
            "three_way_compare" -> {
                registerCoreImport(emitter, "$memPkg.threeWayCompareWithLayout")
                "threeWayCompareWithLayout($p0, $p1, $layout0)"
            }
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
        val dstSyn = dstExplicitSyn ?: if (expectedTarget == null) emitter.currentEnclosingReturnSynType else null
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
                    ?: emitter.currentEnclosingReturnRenderedType
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
            val fnName = if (rawTransmuteIdent == "transmute_unchecked") "transmuteUnchecked" else "transmute"
            val coreNs = resolveCoreNamespace(emitter.headerContext)
            val memPkg = "$coreNs.mem"
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
        if (!spec.isIntrinsicsModuleCall) return null
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
                        ?: spec.callArgsList.firstOrNull()?.let { emitter.inferExprRenderedType(it, selfTypeName) }
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
                explicitTypes
                    .firstOrNull()
                    ?.takeUnless { it is SynType.Infer }
                    ?.let { emitter.renderType(it, selfTypeName) }
                    ?.takeIf(::isUsableRenderedTypeArg)
                    ?: if (spec.canonicalName !in setOf("simd_select_bitmask", "simd_gather", "simd_scatter", "simd_masked_load", "simd_masked_store")) {
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
            } else {
                null
            }
        val renderedArgs =
            if (simdExpectedArgTarget != null) {
                callArgsList.mapIndexed { argIdx, argExpr ->
                    val shouldPropagate =
                        when (spec.canonicalName) {
                            "simd_shuffle" -> argIdx < 2
                            "simd_extract" -> argIdx == 0
                            "simd_insert" -> argIdx == 0
                            else -> true
                        }
                    if (shouldPropagate) {
                        val prevTarget = emitter.currentExpectedParseTargetType
                        emitter.currentExpectedParseTargetType = simdExpectedArgTarget
                        try {
                            emitter.renderExpr(argExpr, indent, selfTypeName)
                        } finally {
                            emitter.currentExpectedParseTargetType = prevTarget
                        }
                    } else {
                        emitter.renderExpr(argExpr, indent, selfTypeName)
                    }
                }
            } else {
                callArgsList.map { emitter.renderExpr(it, indent, selfTypeName) }
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
            "ctpop", "ctlz", "ctlz_nonzero", "cttz", "cttz_nonzero", "bswap", "bitreverse",
            "rotate_left", "rotate_right",
            "wrapping_add", "wrapping_sub", "wrapping_mul",
            "unchecked_add", "unchecked_sub", "unchecked_mul",
            "saturating_add", "saturating_sub",
            "add_with_overflow", "sub_with_overflow", "mul_with_overflow",
            "unchecked_div", "unchecked_rem", "exact_div",
            "unchecked_shl", "unchecked_shr",
            "disjoint_bitor", "three_way_compare",
            ->
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
            in SUFFIXED_FLOAT_UNARY_KM,
            in SUFFIXED_FLOAT_BINARY_KM,
            "fmaf32", "fmuladdf32", "fmaf64", "fmuladdf64",
            "minimumf32", "maximumf32", "minimumf64", "maximumf64",
            in GENERIC_FLOAT_UNARY_KM,
            in GENERIC_FLOAT_BINARY_OP_NAMES,
            "powi", "fma", "fmuladd", "float_to_int_unchecked",
            ->
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

    private fun rustScalarToNumericKind(prim: String, emitter: KotlinJ2KEmitter): NumericKind? =
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

    private fun resolveIntrinsicNumericKind(
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

    private fun numericKindWidthTag(kind: NumericKind): String? =
        when (kind) {
            NumericKind.I8 -> "I8"
            NumericKind.U8 -> "U8"
            NumericKind.I16 -> "I16"
            NumericKind.U16 -> "U16"
            NumericKind.I32 -> "I32"
            NumericKind.U32 -> "U32"
            NumericKind.I64 -> "I64"
            NumericKind.U64 -> "U64"
            NumericKind.I128 -> "I128"
            NumericKind.U128 -> "U128"
            else -> null
        }

    private fun numericKindUnsignedBitTag(kind: NumericKind): String? =
        when (kind) {
            NumericKind.I8, NumericKind.U8 -> "U8"
            NumericKind.I16, NumericKind.U16 -> "U16"
            NumericKind.I32, NumericKind.U32 -> "U32"
            NumericKind.I64, NumericKind.U64 -> "U64"
            NumericKind.I128, NumericKind.U128 -> "U128"
            else -> null
        }

    private fun toExactWidthUnsignedExpr(expr: String, kind: NumericKind): String =
        when (kind) {
            NumericKind.I8 -> "($expr).toUByte()"
            NumericKind.I16 -> "($expr).toUShort()"
            NumericKind.I32 -> "($expr).toUInt()"
            NumericKind.I64 -> "($expr).toULong()"
            else -> expr
        }

    private fun fromExactWidthUnsignedExpr(expr: String, kind: NumericKind): String =
        when (kind) {
            NumericKind.I8 -> "($expr).toByte()"
            NumericKind.I16 -> "($expr).toShort()"
            NumericKind.I32 -> "($expr).toInt()"
            NumericKind.I64 -> "($expr).toLong()"
            else -> expr
        }

    private fun reconstruct128Expr(
        kind: NumericKind,
        loField: String,
        hiField: String,
        coreNs: String,
        emitter: KotlinJ2KEmitter,
    ): String =
        if (kind == NumericKind.I128) {
            registerCoreImport(emitter, "$coreNs.I128")
            "I128($hiField.toLong(), $loField)"
        } else {
            registerCoreImport(emitter, "$coreNs.U128")
            "U128($hiField, $loField)"
        }

    private fun layoutExprForNumericKind(
        kind: NumericKind,
        coreNs: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        registerCoreImport(emitter, "$memPkg.PrimitiveLayouts")
        return when (kind) {
            NumericKind.I8 -> "PrimitiveLayouts.I8"
            NumericKind.U8 -> "PrimitiveLayouts.U8"
            NumericKind.I16 -> "PrimitiveLayouts.I16"
            NumericKind.U16 -> "PrimitiveLayouts.U16"
            NumericKind.I32 -> "PrimitiveLayouts.I32"
            NumericKind.U32 -> "PrimitiveLayouts.U32"
            NumericKind.I64 -> "PrimitiveLayouts.I64"
            NumericKind.U64 -> "PrimitiveLayouts.U64"
            NumericKind.I128 -> {
                registerCoreImport(emitter, "$coreNs.I128")
                "PrimitiveLayouts.i128Layout(::I128, { it.high }, { it.low })"
            }
            NumericKind.U128 -> {
                registerCoreImport(emitter, "$coreNs.U128")
                "PrimitiveLayouts.u128Layout(::U128, { it.high }, { it.low })"
            }
            NumericKind.F16 -> "PrimitiveLayouts.F16_LAYOUT"
            NumericKind.F32 -> "PrimitiveLayouts.F32"
            NumericKind.F64 -> "PrimitiveLayouts.F64"
            NumericKind.F128 -> "PrimitiveLayouts.F128_LAYOUT"
        }
    }

    private fun renderIntOrBitIntrinsicCall(
        canonicalName: String,
        renderedArgs: List<String>,
        callArgsList: List<Expr>,
        explicitTypes: List<SynType>,
        selfTypeName: String?,
        coreNs: String,
        intrPkg: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val symmetricCount =
            when (canonicalName) {
                "ctpop", "ctlz", "ctlz_nonzero", "cttz", "cttz_nonzero", "bswap", "bitreverse",
                "rotate_left", "rotate_right", "unchecked_shl", "unchecked_shr",
                -> 1
                else -> 2
            }
        val kind = resolveIntrinsicNumericKind(explicitTypes.firstOrNull(), callArgsList.take(symmetricCount), selfTypeName, emitter)
        val firstTargetTy =
            explicitTypes.firstOrNull()?.takeUnless { it is SynType.Infer }
                ?: callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) }
        fun fallbackWithLayout(fnName: String, argsPart: String): String? {
            val ty = firstTargetTy ?: return null
            val layout = resolveTypeLayoutExpression(ty, selfTypeName, emitter)
            registerCoreImport(emitter, "$memPkg.$fnName")
            return "$fnName($argsPart, $layout)"
        }
        return when (canonicalName) {
            "ctpop", "ctlz", "ctlz_nonzero", "cttz", "cttz_nonzero" -> {
                val arg0 = renderedArgs.firstOrNull() ?: return null
                val uTag = kind?.let { numericKindUnsignedBitTag(it) }
                val stem =
                    when (canonicalName) {
                        "ctpop" -> "kmCtpop"
                        "ctlz", "ctlz_nonzero" -> "kmCtlz"
                        else -> "kmCttz"
                    }
                when {
                    uTag == "U128" -> {
                        val kmFn = "${stem}U128"
                        registerCoreImport(emitter, "$intrPkg.$kmFn")
                        val hiExpr = if (kind == NumericKind.I128) "v.high.toULong()" else "v.high"
                        "($arg0).let { v -> $kmFn(v.low, $hiExpr) }"
                    }
                    uTag != null -> {
                        val kmFn = "$stem$uTag"
                        registerCoreImport(emitter, "$intrPkg.$kmFn")
                        "$kmFn(${toExactWidthUnsignedExpr(arg0, kind)})"
                    }
                    else -> {
                        val withLayout =
                            when (canonicalName) {
                                "ctpop" -> "ctpopWithLayout"
                                "ctlz", "ctlz_nonzero" -> "ctlzWithLayout"
                                else -> "cttzWithLayout"
                            }
                        fallbackWithLayout(withLayout, arg0)
                    }
                }
            }
            "bswap", "bitreverse" -> {
                val arg0 = renderedArgs.firstOrNull() ?: return null
                if (canonicalName == "bswap" && (kind == NumericKind.I8 || kind == NumericKind.U8)) {
                    return arg0
                }
                val uTag = kind?.let { numericKindUnsignedBitTag(it) }
                val stem = if (canonicalName == "bswap") "kmBswap" else "kmBitreverse"
                when {
                    uTag == "U128" -> {
                        val kmFn = "${stem}U128"
                        registerCoreImport(emitter, "$intrPkg.$kmFn")
                        val hiExpr = if (kind == NumericKind.I128) "v.high.toULong()" else "v.high"
                        val built = reconstruct128Expr(kind, "r.lo", "r.hi", coreNs, emitter)
                        "($arg0).let { v -> $kmFn(v.low, $hiExpr).let { r -> $built } }"
                    }
                    uTag != null -> {
                        val kmFn = "$stem$uTag"
                        registerCoreImport(emitter, "$intrPkg.$kmFn")
                        fromExactWidthUnsignedExpr("$kmFn(${toExactWidthUnsignedExpr(arg0, kind)})", kind)
                    }
                    else -> fallbackWithLayout(if (canonicalName == "bswap") "bswapWithLayout" else "bitreverseWithLayout", arg0)
                }
            }
            "rotate_left", "rotate_right" -> {
                if (renderedArgs.size < 2) return null
                val arg0 = renderedArgs[0]
                val shift = "(${renderedArgs[1]}).toUInt()"
                val uTag = kind?.let { numericKindUnsignedBitTag(it) }
                val stem = if (canonicalName == "rotate_left") "kmRotateLeft" else "kmRotateRight"
                when {
                    uTag == "U128" -> {
                        val kmFn = "${stem}U128"
                        registerCoreImport(emitter, "$intrPkg.$kmFn")
                        val hiExpr = if (kind == NumericKind.I128) "v.high.toULong()" else "v.high"
                        val built = reconstruct128Expr(kind, "r.lo", "r.hi", coreNs, emitter)
                        "($arg0).let { v -> $kmFn(v.low, $hiExpr, $shift).let { r -> $built } }"
                    }
                    uTag != null -> {
                        val kmFn = "$stem$uTag"
                        registerCoreImport(emitter, "$intrPkg.$kmFn")
                        fromExactWidthUnsignedExpr("$kmFn(${toExactWidthUnsignedExpr(arg0, kind)}, $shift)", kind)
                    }
                    else -> {
                        val fn = if (canonicalName == "rotate_left") "rotateLeftWithLayout" else "rotateRightWithLayout"
                        fallbackWithLayout(fn, "$arg0, $shift")
                    }
                }
            }
            "wrapping_add", "wrapping_sub", "wrapping_mul", "unchecked_add", "unchecked_sub", "unchecked_mul" -> {
                if (renderedArgs.size < 2) return null
                val a = renderedArgs[0]
                val b = renderedArgs[1]
                val op =
                    when {
                        canonicalName.endsWith("_add") -> "+"
                        canonicalName.endsWith("_sub") -> "-"
                        else -> "*"
                    }
                when (kind) {
                    NumericKind.I8 -> "((($a) $op ($b))).toByte()"
                    NumericKind.U8 -> "((($a) $op ($b))).toUByte()"
                    NumericKind.I16 -> "((($a) $op ($b))).toShort()"
                    NumericKind.U16 -> "((($a) $op ($b))).toUShort()"
                    NumericKind.I32, NumericKind.U32, NumericKind.I64, NumericKind.U64 -> "(($a) $op ($b))"
                    NumericKind.I128, NumericKind.U128 -> {
                        val kmFn =
                            when {
                                canonicalName.endsWith("_add") -> "kmU128Add"
                                canonicalName.endsWith("_sub") -> "kmU128Sub"
                                else -> "kmU128Mul"
                            }
                        registerCoreImport(emitter, "$intrPkg.$kmFn")
                        val hiL = if (kind == NumericKind.I128) "lhs.high.toULong()" else "lhs.high"
                        val hiR = if (kind == NumericKind.I128) "rhs.high.toULong()" else "rhs.high"
                        val built = reconstruct128Expr(kind, "it.lo", "it.hi", coreNs, emitter)
                        "run { val lhs = $a; val rhs = $b; $kmFn(lhs.low, $hiL, rhs.low, $hiR).let { $built } }"
                    }
                    else -> {
                        val fn =
                            when {
                                canonicalName.endsWith("_add") -> "wrappingAddWithLayout"
                                canonicalName.endsWith("_sub") -> "wrappingSubWithLayout"
                                else -> "wrappingMulWithLayout"
                            }
                        fallbackWithLayout(fn, "$a, $b")
                    }
                }
            }
            "saturating_add", "saturating_sub" -> {
                if (renderedArgs.size < 2) return null
                val a = renderedArgs[0]
                val b = renderedArgs[1]
                val stem = if (canonicalName == "saturating_add") "kmSaturatingAdd" else "kmSaturatingSub"
                val wTag = kind?.let { numericKindWidthTag(it) }
                when {
                    (kind == NumericKind.I128 || kind == NumericKind.U128) && wTag != null -> {
                        val kmFn = "$stem$wTag"
                        registerCoreImport(emitter, "$intrPkg.$kmFn")
                        val hiL = if (kind == NumericKind.I128) "lhs.high.toULong()" else "lhs.high"
                        val hiR = if (kind == NumericKind.I128) "rhs.high.toULong()" else "rhs.high"
                        val built = reconstruct128Expr(kind, "it.lo", "it.hi", coreNs, emitter)
                        "run { val lhs = $a; val rhs = $b; $kmFn(lhs.low, $hiL, rhs.low, $hiR).let { $built } }"
                    }
                    wTag != null -> {
                        val kmFn = "$stem$wTag"
                        registerCoreImport(emitter, "$intrPkg.$kmFn")
                        "$kmFn($a, $b)"
                    }
                    else -> fallbackWithLayout(if (canonicalName == "saturating_add") "saturatingAddWithLayout" else "saturatingSubWithLayout", "$a, $b")
                }
            }
            "add_with_overflow", "sub_with_overflow", "mul_with_overflow" -> {
                if (renderedArgs.size < 2) return null
                val a = renderedArgs[0]
                val b = renderedArgs[1]
                val stem =
                    when (canonicalName) {
                        "add_with_overflow" -> "Add"
                        "sub_with_overflow" -> "Sub"
                        else -> "Mul"
                    }
                val wTag = kind?.let { numericKindWidthTag(it) }
                when {
                    (kind == NumericKind.I128 || kind == NumericKind.U128) && wTag != null -> {
                        val kmFn = "km${stem}WithOverflow$wTag"
                        registerCoreImport(emitter, "$intrPkg.$kmFn")
                        val hiL = if (kind == NumericKind.I128) "lhs.high.toULong()" else "lhs.high"
                        val hiR = if (kind == NumericKind.I128) "rhs.high.toULong()" else "rhs.high"
                        val built = reconstruct128Expr(kind, "it.lo", "it.hi", coreNs, emitter)
                        "run { val lhs = $a; val rhs = $b; $kmFn(lhs.low, $hiL, rhs.low, $hiR).let { Pair($built, it.overflowed) } }"
                    }
                    wTag != null -> {
                        val kmFn = "km${stem}WithOverflow$wTag"
                        registerCoreImport(emitter, "$intrPkg.$kmFn")
                        "$kmFn($a, $b).let { Pair(it.value, it.overflowed) }"
                    }
                    else -> {
                        val fn =
                            when (canonicalName) {
                                "add_with_overflow" -> "addWithOverflowWithLayout"
                                "sub_with_overflow" -> "subWithOverflowWithLayout"
                                else -> "mulWithOverflowWithLayout"
                            }
                        fallbackWithLayout(fn, "$a, $b")
                    }
                }
            }
            "unchecked_div", "unchecked_rem", "exact_div" -> {
                if (renderedArgs.size < 2) return null
                val a = renderedArgs[0]
                val b = renderedArgs[1]
                val op = if (canonicalName == "unchecked_rem") "%" else "/"
                when (kind) {
                    NumericKind.I8 -> "((($a) $op ($b))).toByte()"
                    NumericKind.U8 -> "((($a) $op ($b))).toUByte()"
                    NumericKind.I16 -> "((($a) $op ($b))).toShort()"
                    NumericKind.U16 -> "((($a) $op ($b))).toUShort()"
                    NumericKind.I32, NumericKind.U32, NumericKind.I64, NumericKind.U64, NumericKind.I128, NumericKind.U128 -> "(($a) $op ($b))"
                    else -> {
                        val fn =
                            when (canonicalName) {
                                "unchecked_rem" -> "uncheckedRemWithLayout"
                                "exact_div" -> "exactDivWithLayout"
                                else -> "uncheckedDivWithLayout"
                            }
                        fallbackWithLayout(fn, "$a, $b")
                    }
                }
            }
            "unchecked_shl", "unchecked_shr" -> {
                if (renderedArgs.size < 2) return null
                val x = renderedArgs[0]
                val shift = renderedArgs[1]
                val isLeft = canonicalName == "unchecked_shl"
                val op = if (isLeft) "shl" else "shr"
                when (kind) {
                    NumericKind.I8 -> "((($x).toInt() $op ($shift).toInt()).toByte())"
                    NumericKind.U8 -> "(((($x).toUInt() and 0xFFu) $op ($shift).toInt()).toUByte())"
                    NumericKind.I16 -> "((($x).toInt() $op ($shift).toInt()).toShort())"
                    NumericKind.U16 -> "(((($x).toUInt() and 0xFFFFu) $op ($shift).toInt()).toUShort())"
                    NumericKind.I32, NumericKind.U32, NumericKind.I64, NumericKind.U64 -> "(($x) $op ($shift).toInt())"
                    NumericKind.U128 -> {
                        val kmFn = if (isLeft) "kmU128Shl" else "kmU128Shr"
                        registerCoreImport(emitter, "$intrPkg.$kmFn")
                        registerCoreImport(emitter, "$coreNs.U128")
                        "($x).let { v -> $kmFn(v.low, v.high, ($shift).toUInt()).let { r -> U128(r.hi, r.lo) } }"
                    }
                    NumericKind.I128 -> {
                        val kmFn = if (isLeft) "kmU128Shl" else "kmI128Shr"
                        registerCoreImport(emitter, "$intrPkg.$kmFn")
                        registerCoreImport(emitter, "$coreNs.I128")
                        "($x).let { v -> $kmFn(v.low, v.high.toULong(), ($shift).toUInt()).let { r -> I128(r.hi.toLong(), r.lo) } }"
                    }
                    else -> {
                        val ty0 = firstTargetTy ?: return null
                        val layout0 = resolveTypeLayoutExpression(ty0, selfTypeName, emitter)
                        val fn = if (isLeft) "uncheckedShlWithLayout" else "uncheckedShrWithLayout"
                        registerCoreImport(emitter, "$memPkg.$fn")
                        val shiftTy =
                            explicitTypes.getOrNull(1)?.takeUnless { it is SynType.Infer }
                                ?: callArgsList.getOrNull(1)?.let { emitter.sourceInitializerType(it) }
                        if (shiftTy != null && explicitTypes.size >= 2) {
                            val layout1 = resolveTypeLayoutExpression(shiftTy, selfTypeName, emitter)
                            "$fn($x, $shift, $layout0, $layout1)"
                        } else {
                            "$fn($x, ($shift).toUInt(), $layout0)"
                        }
                    }
                }
            }
            "disjoint_bitor" -> {
                if (renderedArgs.size < 2) return null
                val a = renderedArgs[0]
                val b = renderedArgs[1]
                if (kind != null && numericKindWidthTag(kind) != null) {
                    "(($a) or ($b))"
                } else {
                    fallbackWithLayout("disjointBitorWithLayout", "$a, $b")
                }
            }
            "three_way_compare" -> {
                if (renderedArgs.size < 2) return null
                val a = renderedArgs[0]
                val b = renderedArgs[1]
                if (kind != null && numericKindWidthTag(kind) != null) {
                    registerCoreImport(emitter, "$coreNs.cmp.Ordering")
                    "run { val lhs = $a; val rhs = $b; when { lhs < rhs -> Ordering.Less; lhs > rhs -> Ordering.Greater; else -> Ordering.Equal } }"
                } else {
                    fallbackWithLayout("threeWayCompareWithLayout", "$a, $b")
                }
            }
            else -> null
        }
    }

    private fun renderFloatIntrinsicCall(
        canonicalName: String,
        renderedArgs: List<String>,
        callArgsList: List<Expr>,
        explicitTypes: List<SynType>,
        selfTypeName: String?,
        coreNs: String,
        intrPkg: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String? {
        SUFFIXED_FLOAT_UNARY_KM[canonicalName]?.let { kmFn ->
            val arg0 = renderedArgs.firstOrNull() ?: return null
            registerCoreImport(emitter, "$intrPkg.$kmFn")
            return "$kmFn($arg0)"
        }
        SUFFIXED_FLOAT_BINARY_KM[canonicalName]?.let { kmFn ->
            if (renderedArgs.size < 2) return null
            registerCoreImport(emitter, "$intrPkg.$kmFn")
            return "$kmFn(${renderedArgs[0]}, ${renderedArgs[1]})"
        }
        when (canonicalName) {
            "fmaf32", "fmuladdf32" -> {
                if (renderedArgs.size < 3) return null
                registerCoreImport(emitter, "$intrPkg.kmFmaf32")
                return "kmFmaf32(${renderedArgs[0]}, ${renderedArgs[1]}, ${renderedArgs[2]})"
            }
            "fmaf64", "fmuladdf64" -> {
                if (renderedArgs.size < 3) return null
                registerCoreImport(emitter, "$intrPkg.kmFmaf64")
                return "kmFmaf64(${renderedArgs[0]}, ${renderedArgs[1]}, ${renderedArgs[2]})"
            }
            "minimumf32", "maximumf32", "minimumf64", "maximumf64" -> {
                if (renderedArgs.size < 2) return null
                val op = if (canonicalName.startsWith("min")) "minimum" else "maximum"
                val layoutName = if (canonicalName.endsWith("f32")) "PrimitiveLayouts.F32" else "PrimitiveLayouts.F64"
                registerCoreImport(emitter, "$memPkg.floatBinaryWithLayout")
                registerCoreImport(emitter, "$memPkg.PrimitiveLayouts")
                return "floatBinaryWithLayout(\"$op\", ${renderedArgs[0]}, ${renderedArgs[1]}, $layoutName)"
            }
        }

        if (canonicalName == "float_to_int_unchecked") {
            val arg0 = renderedArgs.firstOrNull() ?: return null
            val srcTy =
                explicitTypes.getOrNull(0)?.takeUnless { it is SynType.Infer }
                    ?: callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) }
            val dstTy =
                explicitTypes.getOrNull(1)?.takeUnless { it is SynType.Infer }
                    ?: emitter.currentEnclosingReturnSynType
            val srcKind = resolveIntrinsicNumericKind(srcTy, callArgsList.take(1), selfTypeName, emitter)
            val dstKind =
                dstTy?.let { explicitScalarRustName(it, emitter)?.let { s -> rustScalarToNumericKind(s, emitter) } }
                    ?: emitter.currentExpectedNumericKind
            if (srcKind == NumericKind.F32 || srcKind == NumericKind.F64) {
                val isF32 = srcKind == NumericKind.F32
                val u64Km = if (isF32) "kmFloatToU64Unchecked" else "kmDoubleToU64Unchecked"
                val u128Km = if (isF32) "kmFloatToU128Unchecked" else "kmDoubleToU128Unchecked"
                when (dstKind) {
                    NumericKind.I8 -> return "($arg0).toInt().toByte()"
                    NumericKind.I16 -> return "($arg0).toInt().toShort()"
                    NumericKind.I32 -> return "($arg0).toInt()"
                    NumericKind.I64 -> return "($arg0).toLong()"
                    NumericKind.U8 -> {
                        registerCoreImport(emitter, "$intrPkg.$u64Km")
                        return "$u64Km($arg0).toUByte()"
                    }
                    NumericKind.U16 -> {
                        registerCoreImport(emitter, "$intrPkg.$u64Km")
                        return "$u64Km($arg0).toUShort()"
                    }
                    NumericKind.U32 -> {
                        registerCoreImport(emitter, "$intrPkg.$u64Km")
                        return "$u64Km($arg0).toUInt()"
                    }
                    NumericKind.U64 -> {
                        registerCoreImport(emitter, "$intrPkg.$u64Km")
                        return "$u64Km($arg0)"
                    }
                    NumericKind.U128 -> {
                        registerCoreImport(emitter, "$intrPkg.$u128Km")
                        registerCoreImport(emitter, "$coreNs.U128")
                        return "$u128Km($arg0).let { U128(it.hi, it.lo) }"
                    }
                    NumericKind.I128 -> {
                        registerCoreImport(emitter, "$memPkg.floatToIntUncheckedWithLayout")
                        val srcLayout = layoutExprForNumericKind(srcKind, coreNs, memPkg, emitter)
                        val dstLayout = layoutExprForNumericKind(NumericKind.I128, coreNs, memPkg, emitter)
                        return "floatToIntUncheckedWithLayout($arg0, $srcLayout, $dstLayout)"
                    }
                    else -> Unit
                }
            }
            val srcLayout =
                srcKind?.let { layoutExprForNumericKind(it, coreNs, memPkg, emitter) }
                    ?: srcTy?.let { resolveTypeLayoutExpression(it, selfTypeName, emitter) }
                    ?: return null
            val dstLayout =
                dstKind?.let { layoutExprForNumericKind(it, coreNs, memPkg, emitter) }
                    ?: dstTy?.let { resolveTypeLayoutExpression(it, selfTypeName, emitter) }
                    ?: return null
            registerCoreImport(emitter, "$memPkg.floatToIntUncheckedWithLayout")
            return "floatToIntUncheckedWithLayout($arg0, $srcLayout, $dstLayout)"
        }

        val arity =
            when {
                canonicalName in GENERIC_FLOAT_UNARY_KM -> 1
                canonicalName == "powi" -> 1
                canonicalName in GENERIC_FLOAT_BINARY_OP_NAMES -> 2
                else -> 3
            }
        val kind = resolveIntrinsicNumericKind(explicitTypes.firstOrNull(), callArgsList.take(arity), selfTypeName, emitter)
        val targetTy =
            explicitTypes.firstOrNull()?.takeUnless { it is SynType.Infer }
                ?: callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) }
        fun floatLayoutExpr(): String? =
            when {
                kind != null && kind in setOf(NumericKind.F16, NumericKind.F32, NumericKind.F64, NumericKind.F128) ->
                    layoutExprForNumericKind(kind, coreNs, memPkg, emitter)
                targetTy != null -> resolveTypeLayoutExpression(targetTy, selfTypeName, emitter)
                else -> null
            }

        GENERIC_FLOAT_UNARY_KM[canonicalName]?.let { (f32Km, f64Km) ->
            val arg0 = renderedArgs.firstOrNull() ?: return null
            return when (kind) {
                NumericKind.F32 -> {
                    registerCoreImport(emitter, "$intrPkg.$f32Km")
                    "$f32Km($arg0)"
                }
                NumericKind.F64 -> {
                    registerCoreImport(emitter, "$intrPkg.$f64Km")
                    "$f64Km($arg0)"
                }
                else -> {
                    val layout = floatLayoutExpr() ?: return null
                    registerCoreImport(emitter, "$memPkg.floatUnaryWithLayout")
                    "floatUnaryWithLayout(\"$canonicalName\", $arg0, $layout)"
                }
            }
        }

        GENERIC_FLOAT_BINARY_OP_NAMES[canonicalName]?.let { op ->
            if (renderedArgs.size < 2) return null
            val a = renderedArgs[0]
            val b = renderedArgs[1]
            if (kind == NumericKind.F32 || kind == NumericKind.F64) {
                val isF32 = kind == NumericKind.F32
                when (op) {
                    "fadd" -> return "(($a) + ($b))"
                    "fsub" -> return "(($a) - ($b))"
                    "fmul" -> return "(($a) * ($b))"
                    "fdiv" -> return "(($a) / ($b))"
                    "frem" -> return "(($a) % ($b))"
                    "powf", "copysign", "minnum", "maxnum" -> {
                        val kmFn =
                            when (op) {
                                "powf" -> if (isF32) "kmPowf32" else "kmPowf64"
                                "copysign" -> if (isF32) "kmCopysignf32" else "kmCopysignf64"
                                "minnum" -> if (isF32) "kmMinnumf32" else "kmMinnumf64"
                                else -> if (isF32) "kmMaxnumf32" else "kmMaxnumf64"
                            }
                        registerCoreImport(emitter, "$intrPkg.$kmFn")
                        return "$kmFn($a, $b)"
                    }
                }
            }
            val layout = floatLayoutExpr() ?: return null
            registerCoreImport(emitter, "$memPkg.floatBinaryWithLayout")
            return "floatBinaryWithLayout(\"$op\", $a, $b, $layout)"
        }

        if (canonicalName == "powi") {
            if (renderedArgs.size < 2) return null
            val a = renderedArgs[0]
            val x = renderedArgs[1]
            return when (kind) {
                NumericKind.F32 -> {
                    registerCoreImport(emitter, "$intrPkg.kmPowif32")
                    "kmPowif32($a, $x)"
                }
                NumericKind.F64 -> {
                    registerCoreImport(emitter, "$intrPkg.kmPowif64")
                    "kmPowif64($a, $x)"
                }
                else -> {
                    val layout = floatLayoutExpr() ?: return null
                    registerCoreImport(emitter, "$memPkg.floatPowiWithLayout")
                    "floatPowiWithLayout($a, $x, $layout)"
                }
            }
        }

        if (canonicalName == "fma" || canonicalName == "fmuladd") {
            if (renderedArgs.size < 3) return null
            val a = renderedArgs[0]
            val b = renderedArgs[1]
            val c = renderedArgs[2]
            return when (kind) {
                NumericKind.F32 -> {
                    registerCoreImport(emitter, "$intrPkg.kmFmaf32")
                    "kmFmaf32($a, $b, $c)"
                }
                NumericKind.F64 -> {
                    registerCoreImport(emitter, "$intrPkg.kmFmaf64")
                    "kmFmaf64($a, $b, $c)"
                }
                else -> {
                    val layout = floatLayoutExpr() ?: return null
                    registerCoreImport(emitter, "$memPkg.floatFmaWithLayout")
                    "floatFmaWithLayout($a, $b, $c, $layout)"
                }
            }
        }
        return null
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

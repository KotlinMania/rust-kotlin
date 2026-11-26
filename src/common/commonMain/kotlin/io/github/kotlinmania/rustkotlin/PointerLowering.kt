package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Ident
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Fields
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.LitInt
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PathSegment
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.UseTree
import io.github.kotlinmania.syn.WherePredicate
import io.github.kotlinmania.syn.gen.Visit
import io.github.kotlinmania.syn.span
import io.github.kotlinmania.syn.token.Const
import io.github.kotlinmania.syn.token.Mut
import io.github.kotlinmania.syn.token.Star

private fun synPtrType(
    elem: SynType,
    isMut: Boolean,
    star: Star = Star.default(),
): SynType.Ptr =
    SynType.Ptr(
        star,
        if (isMut) PointerMutability.Mut(Mut.default()) else PointerMutability.Const(Const.default()),
        elem,
    )

private fun singleUnqualifiedPathIdent(type: SynType?): String? =
    (type as? SynType.Path)
        ?.takeIf { it.qself == null && it.path.segments.size == 1 }
        ?.path
        ?.lastIdentText()

private val FIXED_SCALAR_CODECS: Map<String, String> =
    mapOf(
        "i8" to "I8",
        "u8" to "U8",
        "i16" to "I16",
        "u16" to "U16",
        "i32" to "I32",
        "u32" to "U32",
        "i64" to "I64",
        "u64" to "U64",
        "bool" to "BOOL",
    )

private val EXTENDED_SCALAR_POINTED_PRIMITIVES: Set<String> =
    wordSet("isize usize i128 u128 f16 f32 f64 f128 char")

private val ADDRESS_BACKED_SYS_ROOTS: Set<String> = wordSet("libc wasip1 windows_sys wasi")

/** Keep pointer identity distinct from Kotlin's nullable pointee/value types. */
internal fun rawPointerPointeeType(type: String?): String? {
    val base = type?.trim()?.removeSuffix("?") ?: return null
    if (!base.endsWith('>')) return null
    return when {
        base.startsWith("ConstPtr<") -> base.removePrefix("ConstPtr<").dropLast(1)
        base.startsWith("MutPtr<") -> base.removePrefix("MutPtr<").dropLast(1)
        ".ptr.ConstPtr<" in base -> base.substringAfter(".ptr.ConstPtr<").dropLast(1)
        ".ptr.MutPtr<" in base -> base.substringAfter(".ptr.MutPtr<").dropLast(1)
        else -> null
    }
}

internal fun isMutRawPointerRenderedType(type: String?): Boolean {
    val base = type?.trim()?.removeSuffix("?") ?: return false
    return base.endsWith('>') && (base.startsWith("MutPtr<") || ".ptr.MutPtr<" in base)
}

internal fun isConstRawPointerRenderedType(type: String?): Boolean {
    val base = type?.trim()?.removeSuffix("?") ?: return false
    return base.endsWith('>') && (base.startsWith("ConstPtr<") || ".ptr.ConstPtr<" in base)
}

/** Only fixed-width Rust primitive representations are established here.
 * Generic T, aggregates, and pointer-sized words require an explicit layout
 * dictionary; neither a Kotlin value nor an erased cast supplies that evidence.
 */
internal fun fixedScalarPointerCodec(pointee: SynType?): String? {
    val ident = singleUnqualifiedPathIdent(pointee) ?: return null
    val codec = FIXED_SCALAR_CODECS[ident] ?: return null
    return "PrimitiveLayouts.$codec"
}

/** A mutable borrow transports an address, including for an erased generic T. */
internal fun mutableReferencePointeeType(type: String?): String? {
    val base = type?.trim()?.removeSuffix("?") ?: return null
    if (!base.endsWith('>')) return null
    return when {
        base.startsWith("RustMutableReference<") -> base.removePrefix("RustMutableReference<").dropLast(1)
        ".mem.RustMutableReference<" in base -> base.substringAfter(".mem.RustMutableReference<").dropLast(1)
        else -> null
    }
}

/** Explicit source alignment; single-field callers separately validate size and padding. */
internal fun sourceStorageAlignment(item: Item.Struct): Int? =
    StructLayoutComputer.parseStructRepr(item.attrs).explicitAlign?.toInt()

/** Whether `&mut elemTy` requires an address-backed `RustMutableReference` carrier
 * rather than Kotlin's direct object/reference passing.
 */
internal fun KotlinJ2KEmitter.shouldUseAddressBackedMutableReference(
    elemTy: SynType,
    selfTypeName: String? = null,
): Boolean {
    if (headerContext.rawAbiMode) return false
    val resolved = resolveLayoutType(elemTy, emptyMap())
    if (fixedScalarPointerCodec(resolved) != null) return true
    if (singleUnqualifiedPathIdent(resolved) in EXTENDED_SCALAR_POINTED_PRIMITIVES) return true
    if (resolved is SynType.Ptr || runtimeCellField(resolved) != null) return true
    val resolvedPath = (resolved as? SynType.Path)?.path
    if (resolvedPath?.firstCleanIdent() in ADDRESS_BACKED_SYS_ROOTS) return true
    val rendered =
        resolvedPath?.lastIdentText()?.let {
            if (it == "Self") selfTypeName else rustTypeIdentToKotlin(it)
        }
    val renderedPkg = rendered?.let { importedSimpleNameToPkg[it] }
    if (renderedPkg != null && (".libc" in renderedPkg || ".wasip1" in renderedPkg || ".windowssys" in renderedPkg)) {
        return true
    }
    val decl = transparentDeclaration(rendered)
    return decl != null && sourceTransparentField(decl, rendered ?: decl.ident.toString()) != null
}

/** A same-file single-field representation proof. Explicit aligned wrappers
 * require a checked runtime layout, which rejects unsupported tail padding.
 */
internal fun KotlinJ2KEmitter.sourceTransparentField(
    item: Item.Struct,
    selfType: String,
): Pair<String, String>? {
    if (headerContext.rawAbiMode || headerContext.finalClasses) return null
    val repr = StructLayoutComputer.parseStructRepr(item.attrs)
    val field = sourceSingleField(item) ?: return null
    val needsCellOrAssocWrapper =
        runtimeCellField(field.ty) != null || sourceAssociatedParameters(item.generics).isNotEmpty()
    val hasExplicitWrapperRepr = repr.isTransparent || (repr.isReprC && repr.explicitAlign != null)
    if (!needsCellOrAssocWrapper && !(hasExplicitWrapperRepr && sameFileLayoutIndex().hasRawMemoryOrFfiUsage)) {
        return null
    }
    // Pointer-valued wrappers already preserve their address as a RustPointer
    // field. They must not allocate an unrelated pointer-sized storage object.
    if (field.ty is SynType.Ptr || field.ty is SynType.Reference) return null
    val name = field.ident?.toString()?.let(::rustFieldIdentToKotlin) ?: tupleFieldPropertyName(0, 1)
    val associated = sourceAssociatedParameters(item.generics)
    val fieldType =
        object : SynTypeProjectionFolder() {
            override fun rewriteUnqualifiedTypePath(type: SynType, segments: List<PathSegment>): SynType? {
                val parameter =
                    associated.singleOrNull {
                        segments.size == 2 &&
                            segments.first().identText() == it.owner &&
                            segments.last().identText() == it.associated
                    } ?: return null
                return SynType.Path(null, Path.from(Ident.new(parameter.associated, segments.last().ident.span())))
            }
        }.foldType(field.ty.deepCopy())
    return name to renderType(fieldType, selfType)
}

private val RAW_MEMORY_AND_LAYOUT_IDENTS: Set<String> =
    wordSet(
        "size_of size_of_val align_of align_of_val offset_of transmute transmute_copy transmute_unchecked",
        "zeroed MaybeUninit ManuallyDrop UnsafeCell SyncUnsafeCell from_raw_parts from_raw_parts_mut",
        "read_unaligned write_unaligned read_volatile write_volatile addr_of addr_of_mut",
        "copy_nonoverlapping NonNull TypeLayout PrimitiveLayouts",
    )

private val MANAGED_SLICE_BASES: Set<String> =
    wordSet("List MutableList ArrayList String CharSequence LinearSliceView LinearMutSliceView ByteSlice MutByteSlice")

private val VIEW_OFFSET_SLICE_BASES: Set<String> =
    wordSet("LinearSliceView LinearMutSliceView ByteSlice MutByteSlice")

private fun KotlinJ2KEmitter.extractManagedSliceAsPtrTarget(
    expr: Expr,
    selfTypeName: String?,
): Pair<Expr, String>? {
    var cur = unwrapParenOrGroupExpr(expr)
    if (cur is Expr.Cast) {
        val rawCast = singleUnqualifiedPathIdent(unwrapSynType(cur.ty))?.removePrefix("r#")
        if (rawCast != "usize" && rawCast != "isize") return null
        cur = unwrapParenOrGroupExpr(cur.expr)
    } else if (cur is Expr.MethodCall &&
        cur.method.cleanIdent() == "addr" &&
        cur.args.isEmpty() &&
        cur.turbofish == null
    ) {
        cur = unwrapParenOrGroupExpr(cur.receiver)
    }
    val mc = cur as? Expr.MethodCall ?: return null
    val mcMethod = mc.method.cleanIdent()
    if ((mcMethod != "as_ptr" && mcMethod != "as_mut_ptr") || !mc.args.isEmpty() || mc.turbofish != null) {
        return null
    }
    val recvTy = inferExprRenderedType(mc.receiver, selfTypeName)?.removeSuffix("?")?.trim() ?: return null
    val rawBase = recvTy.simpleKotlinTypeName()
    val isManagedSlice =
        rawBase in MANAGED_SLICE_BASES ||
            isPrimitiveArrayRenderedType(recvTy) ||
            (rawBase.endsWith("Slice") && rawBase != "Slice")
    return if (isManagedSlice) mc.receiver to rawBase else null
}

internal fun KotlinJ2KEmitter.tryRenderManagedSlicePointerDistance(
    subPtrExpr: Expr,
    basePtrExpr: Expr,
    mode: String,
    indent: String,
    selfTypeName: String?,
): String? {
    val (subSliceExpr, subBase) = extractManagedSliceAsPtrTarget(subPtrExpr, selfTypeName) ?: return null
    val (baseSliceExpr, baseBase) = extractManagedSliceAsPtrTarget(basePtrExpr, selfTypeName) ?: return null
    val subRendered = renderExpr(subSliceExpr, indent, selfTypeName)
    val baseRendered = renderExpr(baseSliceExpr, indent, selfTypeName)
    val isViewOffsetType = subBase in VIEW_OFFSET_SLICE_BASES || (subBase.endsWith("Slice") && subBase != "Slice")
    val diffExpr =
        when {
            isViewOffsetType && baseBase == subBase -> "(($subRendered).offset - ($baseRendered).offset)"
            subBase == "String" || subBase == "CharSequence" -> "(($baseRendered).length - ($subRendered).length)"
            else -> "(($baseRendered).size - ($subRendered).size)"
        }
    return when (mode) {
        "sub", "wrapping_sub", "unchecked_sub", "offset_from", "offset_from_unsigned", "sub_ptr", "byte_offset_from" -> diffExpr
        "saturating_sub" -> "($diffExpr).coerceAtLeast(0)"
        "checked_sub" -> "run { val kmSliceDiff = $diffExpr; if (kmSliceDiff >= 0) kmSliceDiff else null }"
        else -> null
    }
}

internal fun sameFileNestedItems(items: List<Item>): List<Item> =
    items.flatMap { item ->
        listOf(item) + if (item is Item.Mod) (item.content as? ModContent.Inline)?.let { sameFileNestedItems(it.items) }.orEmpty() else emptyList()
    }

internal class SameFileLayoutIndex(
    root: io.github.kotlinmania.syn.File,
) {
    val items: List<Item>
    val hasRawMemoryOrFfiUsage: Boolean
    val structsByKtName: Map<String, List<Item.Struct>>
    val simpleStructsByKtName: Map<String, Item.Struct?>
    val topFnsByRawName: Map<String, Item.Fn?>
    val typeAliasesByIdent: Map<String, List<Item.ItemType>>
    val typeAliasesByKtName: Map<String, List<Item.ItemType>>
    val traitsByIdent: Map<String, List<Item.Trait>>
    val traitImpls: List<Item.Impl>
    val implsBySelfIdent: Map<String, List<Item.Impl>>
    val useItems: List<Item.Use>
    val nonInlineModuleKtNames: Set<String>

    init {
        val collected = mutableListOf<Item>()
        var foundRawMemoryOrFfi = false
        val visitor =
            object : Visit() {
                override fun visitItem(i: Item) {
                    if (itemHasDocOnlyCfg(i)) return
                    collected.add(i)
                    super.visitItem(i)
                }

                override fun visitItemForeignMod(i: Item.ForeignMod) {
                    foundRawMemoryOrFfi = true
                    super.visitItemForeignMod(i)
                }

                override fun visitTypePtr(t: SynType.Ptr) {
                    foundRawMemoryOrFfi = true
                    super.visitTypePtr(t)
                }

                override fun visitPath(p: Path) {
                    if (p.segments.toList().any { it.ident.toString().removePrefix("r#") in RAW_MEMORY_AND_LAYOUT_IDENTS }) {
                        foundRawMemoryOrFfi = true
                    }
                    super.visitPath(p)
                }
            }
        visitor.visitFile(root)
        items = collected
        hasRawMemoryOrFfiUsage = foundRawMemoryOrFfi

        val structs = linkedMapOf<String, MutableList<Item.Struct>>()
        val simpleStructs = linkedMapOf<String, Item.Struct?>()
        val topFns = linkedMapOf<String, Item.Fn?>()
        val aliases = linkedMapOf<String, MutableList<Item.ItemType>>()
        val aliasesKt = linkedMapOf<String, MutableList<Item.ItemType>>()
        val traits = linkedMapOf<String, MutableList<Item.Trait>>()
        val tImpls = mutableListOf<Item.Impl>()
        val selfImpls = linkedMapOf<String, MutableList<Item.Impl>>()
        val uses = mutableListOf<Item.Use>()
        val extMods = linkedSetOf<String>()

        for (item in collected) {
            when (item) {
                is Item.Struct -> {
                    val ktName = rustTypeIdentToKotlin(item.ident.toString())
                    structs.getOrPut(ktName) { mutableListOf() }.add(item)
                    if (item.generics.params.isEmpty()) {
                        simpleStructs[ktName] = if (ktName in simpleStructs) null else item
                    }
                }
                is Item.Fn -> {
                    val rawKey =
                        item.sig.ident
                            .toString()
                            .removePrefix("r#")
                    topFns[rawKey] = if (rawKey in topFns) null else item
                }
                is Item.ItemType -> {
                    val rawIdent = item.ident.toString()
                    aliases.getOrPut(rawIdent) { mutableListOf() }.add(item)
                    val ktIdent = rustTypeIdentToKotlin(rawIdent)
                    aliasesKt.getOrPut(ktIdent) { mutableListOf() }.add(item)
                }
                is Item.Trait -> {
                    traits.getOrPut(item.ident.toString()) { mutableListOf() }.add(item)
                }
                is Item.Impl -> {
                    if (item.traitPath != null) {
                        tImpls.add(item)
                    }
                    val selfPath = (item.selfType as? SynType.Path)?.takeIf { it.qself == null }
                    val selfIdent =
                        selfPath
                            ?.path
                            ?.segments
                            ?.last()
                            ?.ident
                            ?.toString()
                    if (selfIdent != null) {
                        selfImpls.getOrPut(selfIdent) { mutableListOf() }.add(item)
                    }
                }
                is Item.Use -> uses.add(item)
                is Item.Mod -> {
                    if (item.content !is ModContent.Inline) {
                        extMods.add(rustModuleIdentToKotlin(item.ident.toString().replace('-', '_')))
                    }
                }
                else -> {}
            }
        }

        structsByKtName = structs
        simpleStructsByKtName = simpleStructs
        topFnsByRawName = topFns
        typeAliasesByIdent = aliases
        typeAliasesByKtName = aliasesKt
        traitsByIdent = traits
        traitImpls = tImpls
        implsBySelfIdent = selfImpls
        useItems = uses
        nonInlineModuleKtNames = extMods
    }
}

private object SameFileLayoutIndexCache {
    var cachedRoot: io.github.kotlinmania.syn.File? = null
    var cachedIndex: SameFileLayoutIndex? = null

    fun get(root: io.github.kotlinmania.syn.File): SameFileLayoutIndex {
        val existingRoot = cachedRoot
        val existingIndex = cachedIndex
        if (existingRoot === root && existingIndex != null) {
            return existingIndex
        }
        val built = SameFileLayoutIndex(root)
        cachedRoot = root
        cachedIndex = built
        return built
    }
}

internal fun KotlinJ2KEmitter.sameFileLayoutIndex(): SameFileLayoutIndex {
    val root = TranslationSession.sourceLayoutFile ?: file
    return SameFileLayoutIndexCache.get(root)
}

internal fun KotlinJ2KEmitter.sameFileLayoutItems(): List<Item> =
    sameFileLayoutIndex().items

internal fun KotlinJ2KEmitter.sameFileUniqueTopFn(rawName: String): Item.Fn? =
    sameFileLayoutIndex().topFnsByRawName[rawName]

internal fun KotlinJ2KEmitter.sameFileUniqueSimpleStruct(ktName: String): Item.Struct? =
    sameFileLayoutIndex().simpleStructsByKtName[ktName]

internal fun KotlinJ2KEmitter.transparentDeclaration(owner: String?): Item.Struct? {
    val trimmed = owner?.substringBefore('<')?.trim() ?: return null
    val idx = sameFileLayoutIndex()
    if ('.' in trimmed) {
        val prefix = trimmed.substringBeforeLast('.')
        val pkg = headerContext.packageName?.trim()
        val lastModSeg = prefix.substringAfterLast('.')
        if (prefix != pkg && lastModSeg in idx.nonInlineModuleKtNames) {
            return null
        }
    }
    val name = trimmed.substringAfterLast('.')
    return idx.structsByKtName[name]?.singleOrNull()
}

private val UNSAFE_CELL_PREFIXES: List<List<String>> =
    listOf(listOf("crate", "cell"), listOf("core", "cell"), listOf("std", "cell"))

private fun KotlinJ2KEmitter.runtimeCellField(type: SynType): SynType? {
    val path = (type as? SynType.Path)?.takeIf { it.qself == null }?.path ?: return null
    val segments = path.segments.toList()
    val owner = segments.lastOrNull()?.identText() ?: return null
    if (transparentDeclaration(owner) != null) return null

    fun imported(tree: UseTree, prefix: List<String>): Boolean =
        when (tree) {
            is UseTree.Path -> tree.tree?.let { imported(it, prefix + tree.ident.toString()) } ?: false
            is UseTree.Group -> tree.items.toList().any { imported(it, prefix) }
            is UseTree.Name ->
                prefix in UNSAFE_CELL_PREFIXES &&
                    tree.ident.toString() == "UnsafeCell" &&
                    (tree.rename?.ident?.toString() ?: tree.ident.toString()) == owner
            is UseTree.Glob -> false
        }

    val qualified = segments.dropLast(1).map { it.identText() }
    val supported =
        (owner == "UnsafeCell" && qualified in UNSAFE_CELL_PREFIXES) ||
            (segments.size == 1 && sameFileLayoutIndex().useItems.any { imported(it.tree, emptyList()) })
    if (!supported) return null
    val typeArgs = segments.last().arguments.angleTypeArgs()
    return typeArgs.singleOrNull()
}

/** Receiverless functions which construct a transparent wrapper need layout
 * evidence as part of their public contract, not inferred from a Kotlin value.
 */
internal fun KotlinJ2KEmitter.transparentConstructorField(
    selfType: String?,
    block: Block?,
    visiting: MutableSet<String> = linkedSetOf(),
): Pair<String, String>? {
    if (selfType == null || block == null) return null
    val declaration = transparentDeclaration(selfType) ?: return null
    val field = sourceTransparentField(declaration, selfType) ?: return null
    val owner = declaration.ident.toString()
    if (!visiting.add("$owner:${block.hashCode()}")) return null
    var constructs = false
    val visitor =
        object : Visit() {
            override fun visitExprStruct(e: Expr.Struct) {
                val lastIdent = e.path.lastIdentText()
                if (lastIdent == "Self" || lastIdent == owner) constructs = true
                super.visitExprStruct(e)
            }

            override fun visitExprCall(e: Expr.Call) {
                val fnPath = (e.func as? Expr.Path)?.path
                val segments = fnPath?.segments?.toList().orEmpty()
                val lastIdent = segments.lastOrNull()?.identText()
                if (lastIdent == "Self" || lastIdent == owner) constructs = true
                if (segments.size == 2 && (segments.first().identText() == "Self" || segments.first().identText() == owner)) {
                    val method = segments.last().identText()
                    val called =
                        sameFileLayoutIndex()
                            .implsBySelfIdent[owner]
                            .orEmpty()
                            .flatMap { it.items.filterIsInstance<ImplItem.Fn>() }
                            .filter { it.sig.ident.toString() == method && it.sig.inputs.firstOrNull() !is FnArg.Receiver }
                    if (called.any { transparentConstructorField(selfType, it.block, visiting.toMutableSet()) != null }) {
                        constructs = true
                    }
                }
                super.visitExprCall(e)
            }
        }
    visitor.visitBlock(block)
    return if (constructs) field.first to transparentFactoryLayoutType(declaration, selfType) else null
}

private fun sourceSingleField(item: Item.Struct) =
    when (val fields = item.fields) {
        is Fields.Named -> {
            val named = fields.fields.named.toList()
            named.singleOrNull()
        }
        is Fields.Unnamed -> {
            val unnamed = fields.fields.unnamed.toList()
            unnamed.singleOrNull()
        }
        is Fields.Unit -> null
    }

internal data class SourceAssociatedParameter(
    val owner: String,
    val trait: String,
    val associated: String,
)

internal fun KotlinJ2KEmitter.sourceAssociatedParameters(
    generics: Generics,
    ownerTypeSimpleName: String? = null,
): List<SourceAssociatedParameter> {
    val typeParams = generics.params.toList().filterIsInstance<GenericParam.TypeParam>()
    val declaredTypeParams = typeParams.map { it.ident.toString() }
    val declaredTypeParamSet = declaredTypeParams.toSet()
    val boundsByParam = linkedMapOf<String, MutableList<TypeParamBound>>()
    for (parameter in typeParams) {
        val list = boundsByParam.getOrPut(parameter.ident.toString()) { mutableListOf() }
        list.addAll(parameter.bounds.toList())
    }
    val whereClause = generics.whereClause
    val wherePreds = whereClause?.predicates?.toList().orEmpty()
    for (pred in wherePreds) {
        val tp = pred as? WherePredicate.TypePredicate ?: continue
        val paramIdent = singleUnqualifiedPathIdent(unwrapSynType(tp.boundedTy)) ?: continue
        if (paramIdent in declaredTypeParamSet) {
            val list = boundsByParam.getOrPut(paramIdent) { mutableListOf() }
            list.addAll(tp.bounds.toList())
        }
    }
    val ownerSimple = ownerTypeSimpleName ?: currentBoundedTypeName?.substringBefore('<')?.trim()
    return declaredTypeParams
        .flatMap { rawParamIdent ->
            boundsByParam[rawParamIdent]
                .orEmpty()
                .filterIsInstance<TypeParamBound.Trait>()
                .filter { bound ->
                    val bIdent = bound.path.lastIdentText()
                    bIdent != "SimdElement" && bIdent != "MaskElement"
                }.flatMap { bound ->
                    val trait = bound.path.lastIdentText().orEmpty()
                    val sameFileAssocs =
                        sameFileLayoutIndex()
                            .traitsByIdent[trait]
                            ?.singleOrNull()
                            ?.items
                            ?.filterIsInstance<TraitItem.AssocType>()
                            .orEmpty()
                            .mapNotNull { if (it.default == null) rustTypeIdentToKotlin(it.ident.toString()) else null }
                    val assocNames = sameFileAssocs.ifEmpty { lookupTraitDefaultlessAssocTypes(rustTypeIdentToKotlin(trait), bound.path) }
                    assocNames.mapNotNull { if (it != ownerSimple) SourceAssociatedParameter(rawParamIdent, trait, it) else null }
                }
        }.distinct()
}

internal fun KotlinJ2KEmitter.sourceAssociatedArguments(path: Path, selfType: String?): List<String> {
    val segment = path.segments.last() ?: return emptyList()
    val rawIdent = segment.cleanIdent()
    val declaration = transparentDeclaration(rawIdent)
    val enumDecl =
        if (declaration == null) {
            sameFileNestedItems(file.items)
                .filterIsInstance<Item.Enum>()
                .singleOrNull { rustTypeIdentToKotlin(it.ident.toString()) == rustTypeIdentToKotlin(rawIdent) }
        } else {
            null
        }
    val declGenerics = declaration?.generics ?: enumDecl?.generics
    if (declGenerics == null) {
        val rawFirstArgSyn = segment.arguments.firstAngleTypeArg()
        val renderedFirst = rawFirstArgSyn?.let { renderType(it, selfType) }
        return resolveStdlibAssociatedArgs(rustTypeIdentToKotlin(rawIdent), rawFirstArgSyn, renderedFirst)
    }
    val parameters = declGenerics.typeParamIdentNames()
    val substitutions = parameters.zip(segment.arguments.angleTypeArgs()).toMap()
    return sourceAssociatedParameters(declGenerics).map { parameter ->
        val actual = substitutions[parameter.owner]
        val binding = actual?.let { sourceAssociatedBinding(it, parameter.associated, parameter.trait) }
        when {
            binding != null -> renderType(binding, selfType)
            parameter.trait == "ToOwned" && parameter.associated == "Owned" && actual != null -> {
                val unwrappedActual = unwrapSynType(actual)
                val pathIdent = singleUnqualifiedPathIdent(unwrappedActual)?.removePrefix("r#")
                when {
                    pathIdent == "str" -> "String"
                    unwrappedActual is SynType.Slice -> "MutableList<${renderType(unwrappedActual.elem, selfType)}>"
                    pathIdent != null && rustTypeIdentToKotlin(pathIdent) !in activeTypeParamNames ->
                        renderType(unwrappedActual, selfType)
                    else -> parameter.associated
                }
            }
            else -> parameter.associated
        }
    }
}

internal fun substituteSourceType(type: SynType, substitutions: Map<String, SynType>): SynType =
    object : SynTypeProjectionFolder() {
        override fun rewriteUnqualifiedTypePath(type: SynType, segments: List<PathSegment>): SynType? =
            singleUnqualifiedPathIdent(type)?.let(substitutions::get)?.deepCopy()
    }.foldType(type.deepCopy())

internal fun KotlinJ2KEmitter.sourceAssociatedBinding(
    owner: SynType,
    associated: String,
    trait: String? = null,
): SynType? {
    fun match(pattern: SynType, actual: SynType, parameters: Set<String>, bindings: MutableMap<String, SynType>): Boolean {
        val singlePatName = singleUnqualifiedPathIdent(pattern)
        if (singlePatName != null && singlePatName in parameters) {
            val previous = bindings[singlePatName]
            if (previous != null) return previous.toTokenStream().toString() == actual.toTokenStream().toString()
            bindings[singlePatName] = actual
            return true
        }
        if (pattern is SynType.Ptr && actual is SynType.Ptr) {
            return pattern.mutability.toTokenStream().toString() == actual.mutability.toTokenStream().toString() &&
                match(pattern.elem, actual.elem, parameters, bindings)
        }
        val p = pattern as? SynType.Path
        val a = actual as? SynType.Path
        if (p != null && a != null && p.qself == null && a.qself == null) {
            if (p.path.identStrings() != a.path.identStrings()) return false
            val pa = p.path.lastAngleTypeArgs()
            val aa = a.path.lastAngleTypeArgs()
            return pa.size == aa.size && pa.zip(aa).all { (l, r) -> match(l, r, parameters, bindings) }
        }
        return pattern.toTokenStream().toString() == actual.toTokenStream().toString()
    }
    val candidates =
        sameFileLayoutIndex()
            .traitImpls
            .mapNotNull { implementation ->
                if (trait != null && implementation.traitPath?.path?.lastIdentText() != trait) return@mapNotNull null
                val binding =
                    implementation.items
                        .filterIsInstance<ImplItem.AssocType>()
                        .singleOrNull { it.ident.toString() == associated }
                        ?: return@mapNotNull null
                val substitutions = linkedMapOf<String, SynType>()
                val parameters = implementation.generics.typeParamIdentNames().toSet()
                if (!match(implementation.selfType, owner, parameters, substitutions)) return@mapNotNull null
                substituteSourceType(binding.ty, substitutions)
            }.distinctBy { it.toTokenStream().toString() }
    return candidates.singleOrNull()
}

internal fun KotlinJ2KEmitter.resolveLayoutType(
    type: SynType,
    substitutions: Map<String, SynType>,
    visiting: Set<String> = emptySet(),
): SynType {
    fun resolveProjectionBinding(firstIdent: String, assocIdent: String): SynType? {
        val owner = substitutions[firstIdent] ?: return null
        return sourceAssociatedBinding(substituteSourceType(owner, substitutions - firstIdent), assocIdent)
    }

    val substituted =
        object : SynTypeProjectionFolder() {
            override val foldReplacementResult: Boolean = true

            override fun rewriteUnqualifiedTypePath(type: SynType, segments: List<PathSegment>): SynType? =
                if (segments.size == 2) resolveProjectionBinding(segments.first().identText(), segments.last().identText()) else null
        }.foldType(substituteSourceType(type, substitutions))
    val path = substituted.unqualifiedPath() ?: return substituted
    val key = substituted.toTokenStream().toString()
    if (key in visiting) return substituted
    val segments = path.segments.toList()
    if (segments.size == 2) {
        resolveProjectionBinding(segments.first().identText(), segments.last().identText())?.let {
            return resolveLayoutType(it, substitutions, visiting + key)
        }
    }
    val alias = sameFileLayoutIndex().typeAliasesByIdent[segments.last().identText()]?.singleOrNull() ?: return substituted
    val parameters = alias.generics.typeParamIdentNames()
    return resolveLayoutType(alias.ty, substitutions + parameters.zip(segments.last().arguments.angleTypeArgs()), visiting + key)
}

internal fun KotlinJ2KEmitter.sourceStorageAlias(item: Item.ItemType): Item.Struct? {
    val path = item.ty.unqualifiedPath() ?: return null
    val declaration = transparentDeclaration(path.lastIdentText()) ?: return null
    if (sourceAssociatedParameters(declaration.generics).isEmpty()) return null
    return declaration.takeIf { sourceTransparentField(declaration, declaration.ident.toString()) != null }
}

internal fun KotlinJ2KEmitter.sourceStorageAliasField(item: Item.ItemType, declaration: Item.Struct): SynType {
    val substitutions = layoutSubstitutions(item.ty, declaration, emptyMap())
    return resolveLayoutType(requireNotNull(sourceSingleField(declaration)).ty, substitutions)
}

private fun KotlinJ2KEmitter.layoutSubstitutions(
    type: SynType,
    declaration: Item.Struct,
    substitutions: Map<String, SynType>,
): Map<String, SynType> {
    val path = (type as? SynType.Path)?.path ?: return substitutions
    val args = path.lastAngleTypeArgs().map { resolveLayoutType(it, substitutions) }
    val params = declaration.generics.typeParamIdentNames()
    return params.zip(args).toMap()
}

private fun KotlinJ2KEmitter.layoutRenderedType(type: SynType, selfType: String?, substitutions: Map<String, SynType>): String =
    renderType(resolveLayoutType(type, substitutions), selfType)

internal fun KotlinJ2KEmitter.sourcePointerLayout(
    type: SynType,
    selfType: String?,
    available: Map<String, String> = emptyMap(),
    substitutions: Map<String, SynType> = emptyMap(),
    visiting: Set<String> = emptySet(),
): String? = SourcePointerLayoutResolver(this).resolve(type, selfType, available, substitutions, visiting)

internal fun KotlinJ2KEmitter.transparentFactoryLeaf(
    declaration: Item.Struct,
    selfType: String?,
): Pair<SynType, Map<String, SynType>> = SourcePointerLayoutResolver(this).transparentFactoryLeaf(declaration, selfType)

internal fun KotlinJ2KEmitter.transparentFactoryLayoutType(declaration: Item.Struct, selfType: String?): String =
    SourcePointerLayoutResolver(this).transparentFactoryLayoutType(declaration, selfType)

internal fun KotlinJ2KEmitter.transparentFactoryLayout(declaration: Item.Struct, selfType: String?): String =
    SourcePointerLayoutResolver(this).transparentFactoryLayout(declaration, selfType)

internal fun KotlinJ2KEmitter.transparentConstructionLayout(declaration: Item.Struct, selfType: String?): String =
    SourcePointerLayoutResolver(this).transparentConstructionLayout(declaration, selfType)

/**
 * Resolves `TypeLayout<T>` expressions for raw pointers, `#[repr(transparent)]` wrappers,
 * `UnsafeCell<T>` interiors, fixed-size arrays, and `StructLayoutComputer`-backed structs.
 */
private class SourcePointerLayoutResolver(
    private val emitter: KotlinJ2KEmitter,
) {
    private val headerContext get() = emitter.headerContext
    private val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    private val memPkg = "$coreNs.mem"

    private fun importMem(symbol: String, expr: String): String {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.$symbol")
        return expr
    }

    private fun primitiveLayouts(memberExpr: String): String =
        importMem("PrimitiveLayouts", "PrimitiveLayouts.$memberExpr")

    private fun receiverPointerLayout(type: String, selfType: String?): String? {
        if (selfType == null || emitter.currentTypedLocals["this"] != selfType) return null
        val selfSimple = selfType.simpleKotlinTypeName()
        val alias = emitter.sameFileLayoutIndex().typeAliasesByKtName[selfSimple]?.singleOrNull()
        val declaration = emitter.transparentDeclaration(selfType) ?: alias?.let(emitter::sourceStorageAlias) ?: return null
        var fieldName = emitter.sourceTransparentField(declaration, selfType)?.first ?: return null
        var fieldType =
            if (alias != null) {
                emitter.sourceStorageAliasField(alias, declaration)
            } else {
                sourceSingleField(declaration)?.ty ?: return null
            }
        var owner = "this"
        val visited = linkedSetOf<String>()
        while (visited.add(fieldType.toTokenStream().toString())) {
            val rendered = emitter.renderType(fieldType, selfType)
            if (rendered == type) return "$owner.fieldLayout"
            owner += ".$fieldName"
            val runtimeField = emitter.runtimeCellField(fieldType)
            if (runtimeField != null) {
                fieldName = "value"
                fieldType = runtimeField
                continue
            }
            val nested = emitter.transparentDeclaration(rendered) ?: return null
            fieldName = emitter.sourceTransparentField(nested, rendered)?.first ?: return null
            val nestedFieldTy = sourceSingleField(nested)?.ty ?: return null
            fieldType = emitter.resolveLayoutType(nestedFieldTy, emitter.layoutSubstitutions(fieldType, nested, emptyMap()))
        }
        return null
    }

    fun resolve(
        type: SynType,
        selfType: String?,
        available: Map<String, String> = emptyMap(),
        substitutions: Map<String, SynType> = emptyMap(),
        visiting: Set<String> = emptySet(),
    ): String? {
        val layoutIdx = emitter.sameFileLayoutIndex()
        val sourcePath = type.unqualifiedPath()
        val sourceType =
            if (sourcePath?.isIdent("Self") == true) {
                emitter.currentStandaloneImplSelfSynType
                    ?: selfType
                        ?.simpleKotlinTypeName()
                        ?.let { layoutIdx.typeAliasesByKtName[it]?.singleOrNull() }
                        ?.let { SynType.Path(null, Path.from(it.ident)) }
                    ?: type
            } else {
                type
            }
        val original = substituteSourceType(sourceType, substitutions)
        val aliasPath = original as? SynType.Path
        val alias = aliasPath?.path?.lastIdentText()?.let { layoutIdx.typeAliasesByIdent[it]?.singleOrNull() }
        if (alias != null) {
            val declaration = emitter.sourceStorageAlias(alias)
            if (declaration != null) {
                val aliasRendered = emitter.renderType(original, selfType)
                if (aliasRendered in visiting) return null
                val fieldType = emitter.sourceStorageAliasField(alias, declaration)
                val aliasArguments = aliasPath.path.lastAngleTypeArgs()
                val aliasParameters = alias.generics.typeParamIdentNames()
                val fieldLayout =
                    resolve(fieldType, selfType, available, substitutions + aliasParameters.zip(aliasArguments), visiting + aliasRendered)
                        ?: return null
                val fieldName = requireNotNull(emitter.sourceTransparentField(declaration, declaration.ident.toString())).first
                return importMem(
                    "TransparentTypeLayout",
                    "TransparentTypeLayout($fieldLayout, { ptr, layout -> $aliasRendered(ptr, layout) }, { it.$fieldName })",
                )
            }
        }
        val resolved = emitter.resolveLayoutType(sourceType, substitutions)
        val rendered = emitter.renderType(resolved, selfType)
        available[rendered]?.let { return it }
        if (resolved is SynType.Tuple && resolved.elems.isEmpty()) return primitiveLayouts("UNIT")
        if (resolved is SynType.Array) {
            val elemRendered = emitter.renderType(resolved.elem, selfType)
            val length = emitter.renderExpr(resolved.len, "", selfType)
            val primFactory = emitter.primitiveArrayLayoutFactory(resolved.elem, elemRendered, emitter.activeCfgProfile.pointerWidth.toLong())
            if (primFactory != null) {
                return importMem("PrimitiveArrayLayouts", "PrimitiveArrayLayouts.$primFactory(($length).toInt())")
            }
            val elementLayout = resolve(resolved.elem, selfType, available, substitutions, visiting + rendered) ?: return null
            return importMem("fixedListTypeLayout", "fixedListTypeLayout($elementLayout, ($length).toInt())")
        }
        if (resolved is SynType.Slice) {
            val elementLayout = resolve(resolved.elem, selfType, available, substitutions, visiting + rendered) ?: return null
            val sourceLayout = available.values.singleOrNull() ?: return null
            return importMem(
                "fixedListTypeLayout",
                "fixedListTypeLayout($elementLayout, (($sourceLayout).sizeBytes / ($elementLayout).strideBytes).toInt())",
            )
        }
        val expectedLayoutTypes = setOf("TypeLayout<$rendered>", "$memPkg.TypeLayout<$rendered>")
        if (emitter.currentTypedLocals["fieldLayout"] in expectedLayoutTypes) return "fieldLayout"
        val matchingDict = emitter.currentTypedLocals.entries.singleOrNull { it.value in expectedLayoutTypes }
        if (matchingDict != null) return matchingDict.key
        fixedScalarPointerCodec(resolved)?.let { return importMem("PrimitiveLayouts", it) }
        when (val primitive = singleUnqualifiedPathIdent(resolved)) {
            "f16", "F16" -> return primitiveLayouts("F16_LAYOUT")
            "f32" -> return primitiveLayouts("F32")
            "f64" -> return primitiveLayouts("F64")
            "f128", "F128" -> return primitiveLayouts("F128_LAYOUT")
            "char" -> return primitiveLayouts("CHAR")
            "isize", "usize" -> {
                val width = emitter.activeCfgProfile.pointerWidth
                val prefix =
                    if (headerContext.rawAbiMode) {
                        if (primitive == "isize") "I" else "U"
                    } else {
                        if (primitive == "isize") "ISIZE" else "USIZE"
                    }
                return primitiveLayouts("$prefix$width")
            }
            "i128" -> return primitiveLayouts("i128Layout(::I128, { it.high }, { it.low })")
            "u128" -> return primitiveLayouts("u128Layout(::U128, { it.high }, { it.low })")
        }
        receiverPointerLayout(rendered, selfType)?.let { return it }
        if (resolved is SynType.Ptr) {
            val factory = if (resolved.mutability is PointerMutability.Mut) "mutPtrLayout" else "constPtrLayout"
            return primitiveLayouts("$factory<${emitter.renderType(resolved.elem, selfType)}>(${emitter.activeCfgProfile.pointerWidth}L)")
        }
        if (resolved is SynType.Reference && resolved.mutability != null && !headerContext.rawAbiMode) {
            val referent = emitter.resolveLayoutType(resolved.elem, substitutions)
            val scalarName = singleUnqualifiedPathIdent(referent)
            if (fixedScalarPointerCodec(referent) != null || scalarName in EXTENDED_SCALAR_POINTED_PRIMITIVES) {
                val innerLayout = resolve(referent, selfType, available, substitutions, visiting + rendered) ?: return null
                val ptrWidth = emitter.activeCfgProfile.pointerWidth
                return primitiveLayouts("mutableReferenceLayout<${emitter.renderType(referent, selfType)}>($innerLayout, ${ptrWidth}L)")
            }
            return null
        }
        if (rendered in visiting) return null
        emitter.runtimeCellField(resolved)?.let { fieldType ->
            val fieldLayout = resolve(fieldType, selfType, available, substitutions, visiting + rendered) ?: return null
            return importMem(
                "TransparentTypeLayout",
                "TransparentTypeLayout($fieldLayout, { ptr, layout -> $rendered(ptr, layout) }, { it.value })",
            )
        }
        val declaration = emitter.transparentDeclaration(rendered) ?: return null
        val field = emitter.sourceTransparentField(declaration, rendered)
        if (field != null) {
            val nested = emitter.layoutSubstitutions(resolved, declaration, emptyMap())
            val singleTy = sourceSingleField(declaration)?.ty ?: return null
            val fieldLayout = resolve(singleTy, selfType, available, nested, visiting + rendered) ?: return null
            val wrapper =
                importMem(
                    "TransparentTypeLayout",
                    "TransparentTypeLayout($fieldLayout, { ptr, layout -> $rendered(ptr, layout) }, { it.${field.first} })",
                )
            return sourceStorageAlignment(declaration)?.let { importMem("AlignedTypeLayout", "AlignedTypeLayout($wrapper, ${it}L)") }
                ?: wrapper
        }
        if (!headerContext.rawAbiMode) {
            val structLayout = StructLayoutComputer.computeStructLayout(declaration, emitter) ?: return null
            val ownerBase = rustTypeIdentToKotlin(declaration.ident.toString())
            val typeArgs = (resolved as? SynType.Path)?.path?.lastAngleTypeArgs().orEmpty()
            val declParams = declaration.generics.typeParamIdentNames().map(::rustTypeIdentToKotlin)
            val argExprs =
                structLayout.requiredTypeLayoutParams.map { spec ->
                    val paramLayoutName = spec.paramName
                    val idx = declParams.indexOf(paramLayoutName.removePrefix("typeLayout"))
                    val argSyn = typeArgs.getOrNull(idx) ?: return null
                    val argLayout = resolve(argSyn, selfType, available, substitutions, visiting + rendered) ?: return null
                    "$paramLayoutName = $argLayout"
                }
            val renderedTypeArgs = if (typeArgs.isEmpty()) "" else "<${typeArgs.joinToString(", ") { emitter.renderType(it, selfType) }}>"
            return "$ownerBase.typeLayout$renderedTypeArgs(${argExprs.joinToString(", ")})"
        }
        return null
    }

    private fun initialOwnerSubstitutions(declaration: Item.Struct, selfType: String?): Map<String, SynType> {
        val resolvedOwner = emitter.currentStandaloneImplSelfSynType?.let { emitter.resolveLayoutType(it, emptyMap()) } ?: return emptyMap()
        return if (emitter.transparentDeclaration(emitter.renderType(resolvedOwner, selfType)) == declaration) {
            emitter.layoutSubstitutions(resolvedOwner, declaration, emptyMap())
        } else {
            emptyMap()
        }
    }

    fun transparentFactoryLeaf(declaration: Item.Struct, selfType: String?): Pair<SynType, Map<String, SynType>> {
        var field = requireNotNull(sourceSingleField(declaration)).ty
        var substitutions = initialOwnerSubstitutions(declaration, selfType)
        val visiting = linkedSetOf<String>()
        while (true) {
            field = emitter.resolveLayoutType(field, substitutions)
            val rendered = emitter.renderType(field, selfType)
            if (!visiting.add(rendered)) return field to substitutions
            val runtimeField = emitter.runtimeCellField(field)
            if (runtimeField != null) {
                field = runtimeField
                continue
            }
            val nested = emitter.transparentDeclaration(rendered) ?: return field to emptyMap()
            if (emitter.sourceTransparentField(nested, rendered) == null) return field to emptyMap()
            substitutions = emitter.layoutSubstitutions(field, nested, emptyMap())
            field = requireNotNull(sourceSingleField(nested)).ty
        }
    }

    fun transparentFactoryLayoutType(declaration: Item.Struct, selfType: String?): String {
        val (leaf, substitutions) = transparentFactoryLeaf(declaration, selfType)
        return emitter.layoutRenderedType(leaf, selfType, substitutions)
    }

    fun transparentFactoryLayout(declaration: Item.Struct, selfType: String?): String {
        val (leaf, substitutions) = transparentFactoryLeaf(declaration, selfType)
        resolve(leaf, selfType, substitutions = substitutions)?.let { return it }
        return importMem("UnresolvedTypeLayout", "UnresolvedTypeLayout<${emitter.layoutRenderedType(leaf, selfType, substitutions)}>()")
    }

    fun transparentConstructionLayout(declaration: Item.Struct, selfType: String?): String {
        val declKt = rustTypeIdentToKotlin(declaration.ident.toString())
        val field =
            emitter.sourceTransparentField(declaration, selfType ?: declKt)
                ?: error("Transparent construction requires a source-proven single field")
        val substitutions = initialOwnerSubstitutions(declaration, selfType)
        resolve(requireNotNull(sourceSingleField(declaration)).ty, selfType, substitutions = substitutions)?.let { return it }
        if (selfType?.substringBefore('<') == declKt &&
            emitter.currentTypedLocals["this"] == selfType &&
            emitter.currentTypedLocals["fieldLayout"] == null
        ) {
            return "this.fieldLayout"
        }
        return importMem("UnresolvedTypeLayout", "UnresolvedTypeLayout<${field.second}>()")
    }
}

internal fun KotlinJ2KEmitter.sourceInitializerType(expression: Expr): SynType? =
    SourceAstTypeInferrer(this).inferInitializer(expression)

internal fun KotlinJ2KEmitter.sourceReceiverMethodReturn(receiver: Expr, method: String): SynType? =
    SourceAstTypeInferrer(this).inferReceiverMethodReturn(receiver, method)

/**
 * Infers unlowered `SynType` shapes directly from Rust AST expressions for pointer dereferences,
 * raw address-of (`&raw const`/`&raw mut`), pointer casts/arithmetic, and `UnsafeCell::get`.
 */
private class SourceAstTypeInferrer(
    private val emitter: KotlinJ2KEmitter,
) {
    fun inferInitializer(expression: Expr): SynType? {
        val source = unwrapExprParens(expression)
        if (source is Expr.Path && source.qself == null && source.path.segments.size == 1) {
            val raw = source.path.lastIdentText().orEmpty()
            val local = emitter.scopeBuilder.resolveLocalKotlinName(raw.removePrefix("r#")) ?: rustValueIdentToKotlin(raw)
            emitter.currentSourceParameterTypes[local]?.let { return it }
        }
        val block =
            when (source) {
                is Expr.Const -> source.block
                is Expr.BlockExpr -> source.block
                is Expr.Unsafe -> source.block
                else -> null
            }
        if (block != null) {
            return (block.stmts.lastOrNull() as? Stmt.ExprStmt)?.expr?.let(::inferInitializer)
        }
        return when (source) {
            is Expr.Reference -> inferInitializer(source.expr)?.let { SynType.Reference(source.andToken, null, source.mutability, it) }
            is Expr.RawAddr -> {
                val place = unwrapExprParens(source.expr)
                val elemType =
                    if (place is Expr.Unary && place.op is UnOp.Deref) {
                        (inferInitializer(place.expr) as? SynType.Ptr)?.elem
                    } else {
                        inferInitializer(place)
                    }
                elemType?.let { SynType.Ptr(Star.default(), source.mutability, it) }
            }
            is Expr.Unary if source.op is UnOp.Deref -> {
                when (val innerTy = inferInitializer(source.expr)) {
                    is SynType.Ptr -> innerTy.elem
                    is SynType.Reference -> innerTy.elem
                    else -> null
                }
            }
            is Expr.Field -> inferFieldInitializer(source)
            is Expr.Call -> CoreIntrinsicLowering.inferCoreIntrinsicReturnSynType(source, emitter) ?: inferFnCallInitializer(source)
            is Expr.MethodCall -> inferMethodCallInitializer(source)
            is Expr.Cast -> source.ty
            is Expr.Repeat -> inferInitializer(source.expr)?.let { SynType.Array(it, source.len) }
            is Expr.Array -> inferArrayLiteralInitializer(source, expression)
            is Expr.Lit -> inferLiteralInitializer(source.lit, expression)
            else -> null
        }
    }

    private fun inferFieldInitializer(source: Expr.Field): SynType? {
        val baseSyn = inferInitializer(source.base) ?: inferReceiverExpression(source.base)
        val ownerRaw = (baseSyn as? SynType.Path)?.path?.lastIdentText() ?: return null
        val ownerKt =
            if (ownerRaw == "Self") {
                emitter.currentStandaloneImplSelfSynType?.let { emitter.renderType(it, null).substringBefore('<') }
            } else {
                rustTypeIdentToKotlin(ownerRaw)
            } ?: return null
        val fieldKt =
            when (val m = source.member) {
                is Member.Named -> rustFieldIdentToKotlin(m.ident.toString())
                is Member.Unnamed -> tupleFieldPropertyName(m.index.index.toInt(), emitter.inspector.tupleStructArityByType[ownerKt] ?: 1)
            }
        return emitter.inspector.structFieldSynTypesByType[ownerKt]?.get(fieldKt)
    }

    private fun inferMethodCallInitializer(source: Expr.MethodCall): SynType? {
        val rawMethod = source.method.cleanIdent()
        if (rawMethod in POINTER_CAST_METHODS && source.args.isEmpty()) {
            val baseRecvTy = inferInitializer(source.receiver)
            val basePtr = baseRecvTy as? SynType.Ptr
            val targetElem = source.firstTurbofishTypeArg() ?: basePtr?.elem
            if (targetElem != null) {
                val star = basePtr?.starToken ?: Star.default()
                return when {
                    rawMethod == "cast_mut" -> synPtrType(targetElem, isMut = true, star = star)
                    rawMethod == "cast_const" -> synPtrType(targetElem, isMut = false, star = star)
                    basePtr != null -> SynType.Ptr(star, basePtr.mutability, targetElem)
                    else -> synPtrType(targetElem, isMut = true, star = star)
                }
            }
        }
        if ((rawMethod == "as_ptr" || rawMethod == "as_mut_ptr") && source.args.isEmpty()) {
            val inferredElem =
                emitter.sourceArrayLocal(source.receiver)?.second
                    ?: unwrapPointerContainerElem(inferInitializer(source.receiver))
            if (inferredElem != null) {
                return synPtrType(inferredElem, isMut = rawMethod == "as_mut_ptr")
            }
        }
        if (rawMethod in RAW_POINTER_ARITH_METHODS && source.args.size == 1) {
            inferInitializer(source.receiver)?.let { return it }
        }
        return inferReceiverMethodReturn(source.receiver, rawMethod)
    }

    private fun unwrapPointerContainerElem(ty: SynType?): SynType? {
        val u = ty?.let(::unwrapSynType) ?: return null
        return when (u) {
            is SynType.Reference -> unwrapPointerContainerElem(u.elem) ?: u.elem
            is SynType.Slice -> u.elem
            is SynType.Array -> u.elem
            is SynType.Path -> {
                val lastSeg = u.path.segments.last() ?: return null
                val ident = lastSeg.cleanIdent()
                if (ident == "str") return parseU8SynType()
                if (ident !in POINTER_CONTAINER_WRAPPERS) return null
                val firstArg = lastSeg.arguments.firstAngleTypeArg() ?: return null
                when (val inner = unwrapSynType(firstArg)) {
                    is SynType.Slice -> inner.elem
                    is SynType.Array -> inner.elem
                    is SynType.Path if inner.path.lastIdentText() == "str" -> parseU8SynType()
                    else -> firstArg
                }
            }
            else -> null
        }
    }

    private fun inferFnCallInitializer(call: Expr.Call): SynType? {
        val rawPath = call.func.unqualifiedPath() ?: return null
        val path = rawPath.segments.toList()
        val last = path.lastOrNull()
        val lastIdent = last?.identText()
        if ((lastIdent == "null_mut" || lastIdent == "null") && lastIdent !in emitter.inspector.declaredTopLevelFnRawNames) {
            val element = last.arguments.angleTypeArgs().singleOrNull()
            if (element != null) return synPtrType(element, isMut = lastIdent == "null_mut")
        }
        if (path.size == 1) {
            val declaration = emitter.transparentDeclaration(path.single().identText())
            if (declaration != null && declaration.generics.params.isEmpty()) {
                return SynType.Path(null, Path.from(declaration.ident))
            }
        }
        if (path.size == 2) {
            val owner = path.first().identText()
            val method = path.last().identText()
            val returns =
                emitter
                    .sameFileLayoutIndex()
                    .implsBySelfIdent[owner]
                    .orEmpty()
                    .flatMap { implementation ->
                        implementation.items
                            .filterIsInstance<ImplItem.Fn>()
                            .filter { it.sig.ident.toString() == method && it.sig.inputs.firstOrNull() !is FnArg.Receiver }
                            .mapNotNull { function ->
                                inferAssociatedFnReturn(implementation, function, call)
                            }
                    }.distinctBy { it.toTokenStream().toString() }
            return returns.singleOrNull()
        }
        return null
    }

    private fun inferAssociatedFnReturn(
        implementation: Item.Impl,
        function: ImplItem.Fn,
        call: Expr.Call,
    ): SynType? {
        val parameters = (implementation.generics.typeParamIdentNames() + function.sig.generics.typeParamIdentNames()).toSet()
        val bindings = linkedMapOf<String, SynType>()

        fun bind(pattern: SynType, actual: SynType) {
            val parameter = singleUnqualifiedPathIdent(pattern)
            if (parameter != null && parameter in parameters) {
                bindings[parameter] = actual
                return
            }
            if (pattern is SynType.Ptr && actual is SynType.Ptr) bind(pattern.elem, actual.elem)
            if (pattern is SynType.Reference && actual is SynType.Reference) bind(pattern.elem, actual.elem)
        }

        val inputList = function.sig.inputs.toList()
        val inputs = inputList.filterIsInstance<FnArg.Typed>()
        inputs.zip(call.args.toList()).forEach { (input, argument) ->
            inferInitializer(argument)?.let { bind(input.patType.ty, it) }
        }
        val result = extractReturnSynType(function.sig.output)
        val returned = if ((result as? SynType.Path)?.path?.isIdent("Self") == true) implementation.selfType else result
        return returned?.let { substituteSourceType(it, bindings) }
    }

    private fun inferArrayLiteralInitializer(source: Expr.Array, expression: Expr): SynType? {
        val elements = source.elems.toList()
        val types = elements.map(::inferInitializer)
        val element = types.firstOrNull() ?: return null
        val elemTokens = element.toTokenStream().toString()
        if (!types.all { it?.toTokenStream()?.toString() == elemTokens }) return null
        val length = Expr.Lit(emptyList(), Lit.Int(LitInt.new(elements.size.toString(), "", expression.span())))
        return SynType.Array(element, length)
    }

    private fun inferLiteralInitializer(value: Lit, expression: Expr): SynType? {
        val name =
            when (value) {
                is Lit.Bool -> "bool"
                is Lit.Int -> {
                    val effectiveSuffix = value.value.suffix().ifEmpty { "i32" }
                    effectiveSuffix.takeIf { it in INTEGER_LITERAL_SUFFIXES }
                }
                else -> null
            } ?: return null
        return SynType.Path(null, Path.from(Ident.new(name, expression.span())))
    }

    private fun inferReceiverExpression(expression: Expr): SynType? {
        val source = unwrapExprParens(expression)
        if (source is Expr.Path && source.qself == null && source.path.isIdent("self")) {
            return emitter.currentStandaloneImplSelfSynType
        }
        val field = source as? Expr.Field ?: return null
        val base = inferReceiverExpression(field.base) ?: return null
        val ownerName = (base as? SynType.Path)?.path?.lastIdentText() ?: return null
        val alias = emitter.sameFileLayoutIndex().typeAliasesByIdent[ownerName]?.singleOrNull()
        val declaration = emitter.transparentDeclaration(ownerName) ?: alias?.let(emitter::sourceStorageAlias)
        if (declaration != null) {
            val expected = emitter.sourceTransparentField(declaration, ownerName)?.first ?: return null
            val name =
                when (val member = field.member) {
                    is Member.Named -> rustFieldIdentToKotlin(member.ident.toString())
                    is Member.Unnamed -> tupleFieldPropertyName(member.index.index.toInt(), 1)
                }
            if (expected != name) return null
            return if (alias != null) {
                emitter.sourceStorageAliasField(alias, declaration)
            } else {
                val singleTy = sourceSingleField(declaration)?.ty ?: return null
                emitter.resolveLayoutType(singleTy, emitter.layoutSubstitutions(base, declaration, emptyMap()))
            }
        }
        return emitter.runtimeCellField(base)?.takeIf { (field.member as? Member.Named)?.ident?.toString() == "value" }
    }

    fun inferReceiverMethodReturn(receiver: Expr, method: String): SynType? {
        if (method == "get") {
            val cell = inferReceiverExpression(receiver)?.let(emitter::runtimeCellField)
            if (cell != null) return synPtrType(cell, isMut = true)
        }
        val path = unwrapExprParens(receiver).unqualifiedPath() ?: return null
        if (!path.isIdent("self")) return null
        val owner = emitter.currentStandaloneImplSelfSynType as? SynType.Path ?: return null
        val ownerSegment = owner.path.segments.last() ?: return null
        val arguments = ownerSegment.arguments.angleTypeArgs()
        val results =
            emitter
                .sameFileLayoutIndex()
                .implsBySelfIdent[ownerSegment.identText()]
                .orEmpty()
                .flatMap { implementation ->
                    val parameters = implementation.generics.typeParamIdentNames()
                    val substitutions = parameters.zip(arguments).toMap() + ("Self" to owner)
                    implementation.items
                        .filterIsInstance<ImplItem.Fn>()
                        .filter { it.sig.ident.toString() == method && it.sig.inputs.firstOrNull() is FnArg.Receiver }
                        .mapNotNull { fn -> extractReturnSynType(fn.sig.output)?.let { substituteSourceType(it, substitutions) } }
                }.distinctBy { it.toTokenStream().toString() }
        return results.singleOrNull()
    }

    companion object {
        private val POINTER_CAST_METHODS = wordSet("cast cast_mut cast_const")
        private val POINTER_CONTAINER_WRAPPERS = wordSet("NonNull Vec Box Unique")
        private val INTEGER_LITERAL_SUFFIXES = wordSet("i8 u8 i16 u16 i32 u32 i64 u64 i128 u128 isize usize")

        private fun parseU8SynType(): SynType = SynAstFragmentParser.u8SynType()
    }
}

internal fun KotlinJ2KEmitter.primitiveArrayLayoutFactory(
    elemSynType: SynType,
    elemRenderedType: String,
    pointerWidthBits: Long,
): String? {
    val clean = elemRenderedType.trim()
    if (clean == "Boolean") return "booleanArray"
    val elemPath = unwrapSynType(elemSynType).unqualifiedPath()
    val rawIdent =
        if (elemPath != null && elemPath.segments.size == 1 && elemPath.segments.last()?.arguments is PathArguments.None) {
            elemPath.lastIdentText()
        } else {
            null
        }
    if (!headerContext.rawAbiMode && pointerWidthBits == 64L) {
        if (rawIdent == "usize") return "usize64Array"
        if (rawIdent == "isize") return "isize64Array"
    }
    return PrimitiveArrayTypeCatalog.layoutFactoryForKind(resolveNumericKind(clean))
}

internal fun KotlinJ2KEmitter.sourceArrayLocal(receiver: Expr): Pair<String, SynType>? {
    val unwrappedOuter = unwrapParenOrGroupExpr(receiver)
    val unwrapped = if (unwrappedOuter is Expr.Reference) unwrapParenOrGroupExpr(unwrappedOuter.expr) else unwrappedOuter
    val path = unwrapped.unqualifiedPath() ?: return null
    if (path.segments.size != 1) return null
    val raw = path.lastCleanIdent() ?: return null
    val name = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
    val storage = currentAddressableLocals[name] ?: return null
    val element = currentAddressableArrayElementTypes[name] ?: return null
    return storage to element
}

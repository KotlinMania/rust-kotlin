package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Fields
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.UseTree
import io.github.kotlinmania.syn.gen.Fold
import io.github.kotlinmania.syn.gen.Visit
import io.github.kotlinmania.syn.span

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
    val path = (pointee as? SynType.Path)?.takeIf { it.qself == null }?.path ?: return null
    // Do not recover Rust widths from rendered Kotlin types: isize and i64
    // may both render as Long but have different layouts on a 32-bit target.
    if (path.segments.size != 1) return null
    val name =
        when (
            path.segments
                .last()
                ?.ident
                .toString()
        ) {
            "i8" -> "I8"
            "u8" -> "U8"
            "i16" -> "I16"
            "u16" -> "U16"
            "i32" -> "I32"
            "u32" -> "U32"
            "i64" -> "I64"
            "u64" -> "U64"
            "bool" -> "BOOL"
            else -> return null
        }
    return "PrimitiveLayouts.$name"
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
internal fun sourceStorageAlignment(item: Item.Struct): Int? {
    for (attribute in item.attrs) {
        val repr = attribute.meta as? Meta.List ?: continue
        if (!repr.path.isIdent("repr")) continue
        val tokens = repr.tokens.clone().toList()
        for (index in 0 until tokens.size - 1) {
            val name = tokens[index] as? TokenTree.Ident ?: continue
            if (name.value.toString() != "align") continue
            val group = tokens[index + 1] as? TokenTree.Group ?: return null
            val alignment =
                group.value
                    .stream()
                    .toString()
                    .toIntOrNull() ?: return null
            if (alignment > 0 && alignment and (alignment - 1) == 0) return alignment
        }
    }
    return null
}

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
    val primitive =
        (resolved as? SynType.Path)
            ?.takeIf { it.qself == null && it.path.segments.size == 1 }
            ?.path
            ?.segments
            ?.last()
            ?.ident
            ?.toString()
    if (primitive in setOf("isize", "usize", "i128", "u128", "f16", "f32", "f64", "f128", "char")) return true
    if (resolved is SynType.Ptr) return true
    if (runtimeCellField(resolved) != null) return true
    val firstRawSeg =
        (resolved as? SynType.Path)
            ?.path
            ?.segments
            ?.toList()
            ?.firstOrNull()
            ?.ident
            ?.toString()
            ?.removePrefix("r#")
    if (firstRawSeg in setOf("libc", "wasip1", "windows_sys", "wasi")) return true
    val rendered =
        (resolved as? SynType.Path)
            ?.path
            ?.segments
            ?.last()
            ?.ident
            ?.toString()
            ?.let { if (it == "Self") selfTypeName else rustTypeIdentToKotlin(it) }
    val renderedPkg = rendered?.let { importedSimpleNameToPkg[it] }
    if (renderedPkg != null && (".libc" in renderedPkg || ".wasip1" in renderedPkg || ".windowssys" in renderedPkg)) {
        return true
    }
    val decl = transparentDeclaration(rendered)
    if (decl != null && sourceTransparentField(decl, rendered ?: decl.ident.toString()) != null) {
        return true
    }
    return false
}

/** A same-file single-field representation proof. Explicit aligned wrappers
 * require a checked runtime layout, which rejects unsupported tail padding.
 */
internal fun KotlinJ2KEmitter.sourceTransparentField(
    item: Item.Struct,
    selfType: String,
): Pair<String, String>? {
    if (headerContext.rawAbiMode || headerContext.finalClasses) return null
    val transparent =
        item.attrs.any { attribute ->
            val repr = attribute.meta as? Meta.List
            repr != null && repr.path.isIdent("repr") &&
                repr.tokens.clone().toList().any {
                    it is TokenTree.Ident && it.value.toString() == "transparent"
                }
        }
    val cLayout =
        item.attrs.any { attribute ->
            val repr = attribute.meta as? Meta.List
            repr != null && repr.path.isIdent("repr") &&
                repr.tokens.clone().toList().any {
                    it is TokenTree.Ident && it.value.toString() == "C"
                }
        }
    val field =
        when (val fields = item.fields) {
            is Fields.Named ->
                fields.fields.named
                    .toList()
                    .singleOrNull()
            is Fields.Unnamed ->
                fields.fields.unnamed
                    .toList()
                    .singleOrNull()
            is Fields.Unit -> null
        } ?: return null
    val isAddressCellCWrapper =
        cLayout && (runtimeCellField(field.ty) != null || sourceAssociatedParameters(item.generics).isNotEmpty())
    if (!transparent && !isAddressCellCWrapper && sourceStorageAlignment(item) == null) return null
    // Pointer-valued wrappers already preserve their address as a RustPointer
    // field. They must not allocate an unrelated pointer-sized storage object.
    if (field.ty is SynType.Ptr || field.ty is SynType.Reference) return null
    val name =
        field.ident?.toString()?.let(::rustFieldIdentToKotlin)
            ?: tupleFieldPropertyName(0, 1)
    val associated = sourceAssociatedParameters(item.generics)
    val fieldType =
        object : Fold() {
            override fun foldType(t: SynType): SynType {
                val projection =
                    (t as? SynType.Path)
                        ?.takeIf { it.qself == null }
                        ?.path
                        ?.segments
                        ?.toList()
                val parameter =
                    associated.singleOrNull {
                        projection?.size == 2 && projection.first().ident.toString() == it.owner && projection.last().ident.toString() == it.associated
                    }
                if (parameter != null) {
                    return SynType.Path(
                        null,
                        Path.from(
                            io.github.kotlinmania.procmacro2.Ident
                                .new(parameter.associated, requireNotNull(projection).last().ident.span()),
                        ),
                    )
                }
                return super.foldType(t)
            }
        }.foldType(field.ty.deepCopy())
    return name to renderType(fieldType, selfType)
}

internal fun sameFileNestedItems(items: List<Item>): List<Item> =
    items.flatMap { item ->
        listOf(item) + if (item is Item.Mod) (item.content as? ModContent.Inline)?.let { sameFileNestedItems(it.items) }.orEmpty() else emptyList()
    }

internal class SameFileLayoutIndex(
    root: io.github.kotlinmania.syn.File,
) {
    val items: List<Item>
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
        val visitor =
            object : Visit() {
                override fun visitItem(i: Item) {
                    if (itemHasDocOnlyCfg(i)) return
                    collected.add(i)
                    super.visitItem(i)
                }
            }
        visitor.visitFile(root)
        items = collected

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

private fun KotlinJ2KEmitter.runtimeCellField(type: SynType): SynType? {
    val path = (type as? SynType.Path)?.takeIf { it.qself == null }?.path ?: return null
    val segments = path.segments.toList()
    val owner = segments.lastOrNull()?.ident?.toString() ?: return null
    if (transparentDeclaration(owner) != null) return null
    fun imported(tree: UseTree, prefix: List<String>): Boolean =
        when (tree) {
            is UseTree.Path -> tree.tree?.let { imported(it, prefix + tree.ident.toString()) } ?: false
            is UseTree.Group -> tree.items.toList().any { imported(it, prefix) }
            is UseTree.Name ->
                prefix in listOf(listOf("crate", "cell"), listOf("core", "cell"), listOf("std", "cell")) &&
                    tree.ident.toString() == "UnsafeCell" && (tree.rename?.ident?.toString() ?: tree.ident.toString()) == owner
            is UseTree.Glob -> false
        }
    val qualified = segments.dropLast(1).map { it.ident.toString() }
    val supported =
        (owner == "UnsafeCell" && qualified in listOf(listOf("crate", "cell"), listOf("core", "cell"), listOf("std", "cell"))) ||
            (segments.size == 1 && sameFileLayoutIndex().useItems.any { imported(it.tree, emptyList()) })
    if (!supported) return null
    return (segments.last().arguments as? PathArguments.AngleBracketed)
        ?.args
        ?.toList()
        ?.filterIsInstance<GenericArgument.TypeArg>()
        ?.singleOrNull()
        ?.type
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
                if (e.path.segments
                        .last()
                        ?.ident
                        .toString() in setOf("Self", owner)
                ) {
                    constructs = true
                }
                super.visitExprStruct(e)
            }
            override fun visitExprCall(e: Expr.Call) {
                val path = e.func as? Expr.Path
                val segments =
                    path
                        ?.path
                        ?.segments
                        ?.toList()
                        .orEmpty()
                if (segments.lastOrNull()?.ident.toString() in setOf("Self", owner)) constructs = true
                if (segments.size == 2 && segments.first().ident.toString() in setOf("Self", owner)) {
                    val method = segments.last().ident.toString()
                    val called =
                        sameFileLayoutIndex().implsBySelfIdent[owner].orEmpty().flatMap { it.items.filterIsInstance<ImplItem.Fn>() }.filter {
                            it.sig.ident.toString() == method && it.sig.inputs.firstOrNull() !is FnArg.Receiver
                        }
                    if (called.any { transparentConstructorField(selfType, it.block, visiting.toMutableSet()) != null }) constructs = true
                }
                super.visitExprCall(e)
            }
        }
    visitor.visitBlock(block)
    return field.first.let { name -> name to transparentFactoryLayoutType(declaration, selfType) }.takeIf { constructs }
}

private fun sourceSingleField(item: Item.Struct) =
    when (val fields = item.fields) {
        is Fields.Named ->
            fields.fields.named
                .toList()
                .singleOrNull()
        is Fields.Unnamed ->
            fields.fields.unnamed
                .toList()
                .singleOrNull()
        is Fields.Unit -> null
    }

internal data class SourceAssociatedParameter(
    val owner: String,
    val trait: String,
    val associated: String,
)

internal fun KotlinJ2KEmitter.sourceAssociatedParameters(generics: Generics): List<SourceAssociatedParameter> =
    generics.params
        .toList()
        .filterIsInstance<GenericParam.TypeParam>()
        .flatMap { parameter ->
            parameter.bounds.toList().filterIsInstance<TypeParamBound.Trait>().flatMap { bound ->
                val trait =
                    bound.path.segments
                        .last()
                        ?.ident
                        .toString()
                sameFileLayoutIndex()
                    .traitsByIdent[trait]
                    ?.singleOrNull()
                    ?.items
                    ?.filterIsInstance<TraitItem.AssocType>()
                    .orEmpty()
                    .filter { it.default == null }
                    .map {
                        SourceAssociatedParameter(parameter.ident.toString(), trait, rustTypeIdentToKotlin(it.ident.toString()))
                    }
            }
        }.distinct()

internal fun KotlinJ2KEmitter.sourceAssociatedArguments(path: io.github.kotlinmania.syn.Path, selfType: String?): List<String> {
    val segment = path.segments.last() ?: return emptyList()
    val declaration = transparentDeclaration(segment.ident.toString()) ?: return emptyList()
    val parameters =
        declaration.generics.params
            .toList()
            .filterIsInstance<GenericParam.TypeParam>()
            .map { it.ident.toString() }
    val arguments =
        (segment.arguments as? PathArguments.AngleBracketed)
            ?.args
            ?.toList()
            .orEmpty()
            .filterIsInstance<GenericArgument.TypeArg>()
            .map { it.type }
    val substitutions = parameters.zip(arguments).toMap()
    return sourceAssociatedParameters(declaration.generics).map { parameter ->
        val actual = substitutions[parameter.owner]
        val binding = actual?.let { sourceAssociatedBinding(it, parameter.associated, parameter.trait) }
        if (binding != null) renderType(binding, selfType) else parameter.associated
    }
}

internal fun substituteSourceType(type: SynType, substitutions: Map<String, SynType>): SynType =
    object : Fold() {
        override fun foldType(t: SynType): SynType {
            val path = (t as? SynType.Path)?.takeIf { it.qself == null }?.path
            if (path != null && path.segments.size == 1) {
                val name =
                    path.segments
                        .last()
                        ?.ident
                        .toString()
                substitutions[name]?.let { replacement ->
                    return replacement.deepCopy()
                }
            }
            return super.foldType(t)
        }
    }.foldType(type.deepCopy())

internal fun KotlinJ2KEmitter.sourceAssociatedBinding(
    owner: SynType,
    associated: String,
    trait: String? = null,
): SynType? {
    fun match(pattern: SynType, actual: SynType, parameters: Set<String>, bindings: MutableMap<String, SynType>): Boolean {
        val p = pattern as? SynType.Path
        val a = actual as? SynType.Path
        if (p != null && p.qself == null && p.path.segments.size == 1) {
            val name =
                p.path.segments
                    .last()
                    ?.ident
                    .toString()
            if (name in parameters) {
                val previous = bindings[name]
                if (previous != null) return previous.toTokenStream().toString() == actual.toTokenStream().toString()
                bindings[name] = actual
                return true
            }
        }
        if (pattern is SynType.Ptr && actual is SynType.Ptr) {
            return pattern.mutability.toTokenStream().toString() == actual.mutability.toTokenStream().toString() && match(pattern.elem, actual.elem, parameters, bindings)
        }
        if (p != null && a != null && p.qself == null && a.qself == null) {
            if (p.path.segments
                    .toList()
                    .map { it.ident.toString() } !=
                a.path.segments
                    .toList()
                    .map { it.ident.toString() }
            ) {
                return false
            }
            val pa =
                (
                    p.path.segments
                        .last()
                        ?.arguments as? PathArguments.AngleBracketed
                )?.args?.toList().orEmpty().filterIsInstance<GenericArgument.TypeArg>()
            val aa =
                (
                    a.path.segments
                        .last()
                        ?.arguments as? PathArguments.AngleBracketed
                )?.args?.toList().orEmpty().filterIsInstance<GenericArgument.TypeArg>()
            return pa.size == aa.size && pa.zip(aa).all { (left, right) -> match(left.type, right.type, parameters, bindings) }
        }
        return pattern.toTokenStream().toString() == actual.toTokenStream().toString()
    }
    val candidates =
        sameFileLayoutIndex()
            .traitImpls
            .mapNotNull { implementation ->
                if (trait != null && implementation.traitPath
                        ?.path
                        ?.segments
                        ?.last()
                        ?.ident
                        ?.toString() != trait
                ) {
                    return@mapNotNull null
                }
                val binding =
                    implementation.items.filterIsInstance<ImplItem.AssocType>().singleOrNull {
                        it.ident.toString() == associated
                    } ?: return@mapNotNull null
                val substitutions = linkedMapOf<String, SynType>()
                val parameters =
                    implementation.generics.params
                        .toList()
                        .filterIsInstance<GenericParam.TypeParam>()
                        .map { it.ident.toString() }
                        .toSet()
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
    val substituted =
        object : Fold() {
            override fun foldType(t: SynType): SynType {
                val projection =
                    (t as? SynType.Path)
                        ?.takeIf { it.qself == null }
                        ?.path
                        ?.segments
                        ?.toList()
                if (projection?.size == 2) {
                    val owner = substitutions[projection.first().ident.toString()]
                    if (owner != null) {
                        sourceAssociatedBinding(substituteSourceType(owner, substitutions - projection.first().ident.toString()), projection.last().ident.toString())?.let {
                            return super.foldType(it)
                        }
                    }
                }
                return super.foldType(t)
            }
        }.foldType(substituteSourceType(type, substitutions))
    val path = (substituted as? SynType.Path)?.takeIf { it.qself == null }?.path ?: return substituted
    val key = substituted.toTokenStream().toString()
    if (key in visiting) return substituted
    val segments = path.segments.toList()
    if (segments.size == 2) {
        val owner = substitutions[segments.first().ident.toString()]
        if (owner != null) {
            sourceAssociatedBinding(substituteSourceType(owner, substitutions - segments.first().ident.toString()), segments.last().ident.toString())?.let {
                return resolveLayoutType(it, substitutions, visiting + key)
            }
        }
    }
    val alias =
        sameFileLayoutIndex().typeAliasesByIdent[segments.last().ident.toString()]?.singleOrNull()
            ?: return substituted
    val arguments =
        (segments.last().arguments as? PathArguments.AngleBracketed)
            ?.args
            ?.toList()
            .orEmpty()
            .filterIsInstance<GenericArgument.TypeArg>()
            .map { it.type }
    val parameters =
        alias.generics.params
            .toList()
            .filterIsInstance<GenericParam.TypeParam>()
            .map { it.ident.toString() }
    return resolveLayoutType(alias.ty, substitutions + parameters.zip(arguments), visiting + key)
}

internal fun KotlinJ2KEmitter.sourceStorageAlias(item: Item.ItemType): Item.Struct? {
    val path = (item.ty as? SynType.Path)?.takeIf { it.qself == null }?.path ?: return null
    val declaration =
        transparentDeclaration(
            path.segments
                .last()
                ?.ident
                .toString(),
        ) ?: return null
    if (sourceAssociatedParameters(declaration.generics).isEmpty()) return null
    if (sourceTransparentField(declaration, declaration.ident.toString()) == null) return null
    return declaration
}

internal fun KotlinJ2KEmitter.sourceStorageAliasField(item: Item.ItemType, declaration: Item.Struct): SynType {
    val substitutions = layoutSubstitutions(item.ty, declaration, emptyMap())
    return resolveLayoutType(requireNotNull(sourceSingleField(declaration)).ty, substitutions)
}

private fun KotlinJ2KEmitter.layoutSubstitutions(type: SynType, declaration: Item.Struct, substitutions: Map<String, SynType>): Map<String, SynType> {
    val path = (type as? SynType.Path)?.path ?: return substitutions
    val args =
        (path.segments.last()?.arguments as? PathArguments.AngleBracketed)
            ?.args
            ?.toList()
            .orEmpty()
            .mapNotNull { (it as? GenericArgument.TypeArg)?.type?.let { t -> resolveLayoutType(t, substitutions) } }
    val params =
        declaration.generics.params
            .toList()
            .filterIsInstance<GenericParam.TypeParam>()
            .map { it.ident.toString() }
    return params.zip(args).toMap()
}

private fun KotlinJ2KEmitter.layoutRenderedType(type: SynType, selfType: String?, substitutions: Map<String, SynType>): String =
    renderType(resolveLayoutType(type, substitutions), selfType)

private fun KotlinJ2KEmitter.receiverPointerLayout(type: String, selfType: String?): String? {
    if (selfType == null || currentTypedLocals["this"] != selfType) return null
    val alias = sameFileLayoutIndex().typeAliasesByKtName[selfType.substringBefore('<').substringAfterLast('.')]?.singleOrNull()
    val declaration = transparentDeclaration(selfType) ?: alias?.let { sourceStorageAlias(it) } ?: return null
    var fieldName = sourceTransparentField(declaration, selfType)?.first ?: return null
    var fieldType = if (alias != null) sourceStorageAliasField(alias, declaration) else sourceSingleField(declaration)?.ty ?: return null
    var owner = "this"
    val visited = linkedSetOf<String>()
    while (visited.add(fieldType.toTokenStream().toString())) {
        val rendered = renderType(fieldType, selfType)
        if (rendered == type) return "$owner.fieldLayout"
        owner += ".$fieldName"
        val runtimeField = runtimeCellField(fieldType)
        if (runtimeField != null) {
            fieldName = "value"
            fieldType = runtimeField
            continue
        }
        val nested = transparentDeclaration(rendered) ?: return null
        fieldName = sourceTransparentField(nested, rendered)?.first ?: return null
        fieldType = resolveLayoutType(sourceSingleField(nested)?.ty ?: return null, layoutSubstitutions(fieldType, nested, emptyMap()))
    }
    return null
}

internal fun KotlinJ2KEmitter.sourcePointerLayout(
    type: SynType,
    selfType: String?,
    available: Map<String, String> = emptyMap(),
    substitutions: Map<String, SynType> = emptyMap(),
    visiting: Set<String> = emptySet(),
): String? {
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val memPkg = "$coreNs.mem"
    val layoutIdx = sameFileLayoutIndex()
    val sourcePath = (type as? SynType.Path)?.takeIf { it.qself == null }?.path
    val sourceType =
        if (sourcePath?.isIdent("Self") == true) {
            currentStandaloneImplSelfSynType ?: selfType
                ?.substringBefore('<')
                ?.substringAfterLast('.')
                ?.let {
                    layoutIdx.typeAliasesByKtName[it]?.singleOrNull()
                }?.let {
                    SynType.Path(
                        null,
                        io.github.kotlinmania.syn.Path
                            .from(it.ident),
                    )
                } ?: type
        } else {
            type
        }
    val original = substituteSourceType(sourceType, substitutions)
    val aliasPath = original as? SynType.Path
    val aliasName =
        aliasPath
            ?.path
            ?.segments
            ?.last()
            ?.ident
            ?.toString()
    val alias = aliasName?.let { layoutIdx.typeAliasesByIdent[it]?.singleOrNull() }
    if (alias != null) {
        val declaration = sourceStorageAlias(alias)
        if (declaration != null) {
            val aliasRendered = renderType(original, selfType)
            if (aliasRendered in visiting) return null
            val fieldType = sourceStorageAliasField(alias, declaration)
            val aliasArguments =
                (
                    aliasPath.path.segments
                        .last()
                        ?.arguments as? PathArguments.AngleBracketed
                )?.args?.toList().orEmpty().filterIsInstance<GenericArgument.TypeArg>().map { it.type }
            val aliasParameters =
                alias.generics.params
                    .toList()
                    .filterIsInstance<GenericParam.TypeParam>()
                    .map { it.ident.toString() }
            val fieldLayout = sourcePointerLayout(fieldType, selfType, available, substitutions + aliasParameters.zip(aliasArguments), visiting + aliasRendered) ?: return null
            val fieldName = requireNotNull(sourceTransparentField(declaration, declaration.ident.toString())).first
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.TransparentTypeLayout")
            return "TransparentTypeLayout($fieldLayout, { ptr, layout -> $aliasRendered(ptr, layout) }, { it.$fieldName })"
        }
    }
    val resolved = resolveLayoutType(sourceType, substitutions)
    val rendered = renderType(resolved, selfType)
    available[rendered]?.let { return it }
    if (resolved is SynType.Tuple && resolved.elems.isEmpty()) {
        CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
        return "PrimitiveLayouts.UNIT"
    }
    if (resolved is SynType.Array) {
        val elemRendered = renderType(resolved.elem, selfType)
        val length = renderExpr(resolved.len, "", selfType)
        val primFactory = primitiveArrayLayoutFactory(resolved.elem, elemRendered, activeCfgProfile.pointerWidth.toLong())
        if (primFactory != null) {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveArrayLayouts")
            return "PrimitiveArrayLayouts.$primFactory(($length).toInt())"
        }
        val elementLayout = sourcePointerLayout(resolved.elem, selfType, available, substitutions, visiting + rendered) ?: return null
        CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.fixedListTypeLayout")
        return "fixedListTypeLayout($elementLayout, ($length).toInt())"
    }
    if (resolved is SynType.Slice) {
        val elementLayout = sourcePointerLayout(resolved.elem, selfType, available, substitutions, visiting + rendered) ?: return null
        val sourceLayout = available.values.singleOrNull() ?: return null
        CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.fixedListTypeLayout")
        return "fixedListTypeLayout($elementLayout, (($sourceLayout).sizeBytes / ($elementLayout).strideBytes).toInt())"
    }
    val expectedLayoutTypes = setOf("TypeLayout<$rendered>", "$memPkg.TypeLayout<$rendered>")
    if (currentTypedLocals["fieldLayout"] in expectedLayoutTypes) return "fieldLayout"
    // A typed dictionary is explicit layout evidence even when it came from a
    // generic call contract rather than a transparent field constructor. Do not
    // select an arbitrary dictionary if the scope contains competing evidence.
    currentTypedLocals
        .filterValues { it in expectedLayoutTypes }
        .keys
        .singleOrNull()
        ?.let { return it }
    fixedScalarPointerCodec(resolved)?.let {
        CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
        return it
    }
    val primitive =
        (resolved as? SynType.Path)
            ?.takeIf { it.qself == null && it.path.segments.size == 1 }
            ?.path
            ?.segments
            ?.last()
            ?.ident
            ?.toString()
    when (primitive) {
        "f16", "F16" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.F16_LAYOUT"
        }
        "f32" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.F32"
        }
        "f64" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.F64"
        }
        "f128", "F128" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.F128_LAYOUT"
        }
        "char" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.CHAR"
        }
        "isize", "usize" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            val width = activeCfgProfile.pointerWidth
            val constName =
                if (headerContext.rawAbiMode) {
                    "${if (primitive == "isize") "I" else "U"}$width"
                } else {
                    "${if (primitive == "isize") "ISIZE" else "USIZE"}$width"
                }
            return "PrimitiveLayouts.$constName"
        }
        "i128" -> {
            usesI128 = true
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.i128Layout(::I128, { it.high }, { it.low })"
        }
        "u128" -> {
            usesU128 = true
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.u128Layout(::U128, { it.high }, { it.low })"
        }
    }
    receiverPointerLayout(rendered, selfType)?.let { return it }
    if (resolved is SynType.Ptr) {
        CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
        val factory = if (resolved.mutability is io.github.kotlinmania.syn.PointerMutability.Mut) "mutPtrLayout" else "constPtrLayout"
        return "PrimitiveLayouts.$factory<${renderType(resolved.elem, selfType)}>(${activeCfgProfile.pointerWidth}L)"
    }
    if (resolved is SynType.Reference && resolved.mutability != null && !headerContext.rawAbiMode) {
        // Only select a thin reference representation for source-proven Sized
        // scalar referents. Slices, str, trait objects, and ?Sized parameters
        // require metadata as well as the address and must not use this codec.
        val referent = resolveLayoutType(resolved.elem, substitutions)
        val scalarName =
            (referent as? SynType.Path)
                ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                ?.path
                ?.segments
                ?.last()
                ?.ident
                ?.toString()
        if (fixedScalarPointerCodec(referent) != null || scalarName in setOf("isize", "usize", "i128", "u128", "f16", "f32", "f64", "f128", "char")) {
            val innerLayout = sourcePointerLayout(referent, selfType, available, substitutions, visiting + rendered) ?: return null
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
            return "PrimitiveLayouts.mutableReferenceLayout<${renderType(referent, selfType)}>($innerLayout, ${activeCfgProfile.pointerWidth}L)"
        }
        return null
    }
    if (rendered in visiting) return null
    runtimeCellField(resolved)?.let { fieldType ->
        val fieldLayout = sourcePointerLayout(fieldType, selfType, available, substitutions, visiting + rendered) ?: return null
        CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.TransparentTypeLayout")
        return "TransparentTypeLayout($fieldLayout, { ptr, layout -> $rendered(ptr, layout) }, { it.value })"
    }
    val declaration = transparentDeclaration(rendered)
    if (declaration != null) {
        val field = sourceTransparentField(declaration, rendered)
        if (field != null) {
            val nested = layoutSubstitutions(resolved, declaration, emptyMap())
            val fieldLayout = sourcePointerLayout(sourceSingleField(declaration)?.ty ?: return null, selfType, available, nested, visiting + rendered) ?: return null
            CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.TransparentTypeLayout")
            val wrapper = "TransparentTypeLayout($fieldLayout, { ptr, layout -> $rendered(ptr, layout) }, { it.${field.first} })"
            return sourceStorageAlignment(declaration)?.let {
                CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.AlignedTypeLayout")
                "AlignedTypeLayout($wrapper, ${it}L)"
            } ?: wrapper
        }
        if (!headerContext.rawAbiMode && StructLayoutComputer.computeStructLayout(declaration, this) != null) {
            val ownerBase = rustTypeIdentToKotlin(declaration.ident.toString())
            val typeArgs =
                (
                    (resolved as? SynType.Path)
                        ?.path
                        ?.segments
                        ?.last()
                        ?.arguments as? PathArguments.AngleBracketed
                )?.args
                    ?.toList()
                    .orEmpty()
                    .filterIsInstance<GenericArgument.TypeArg>()
                    .map { it.type }
            val declParams =
                declaration.generics.params
                    .toList()
                    .filterIsInstance<GenericParam.TypeParam>()
                    .map { rustTypeIdentToKotlin(it.ident.toString()) }
            val structLayout = StructLayoutComputer.computeStructLayout(declaration, this) ?: return null
            val argExprs =
                structLayout.requiredTypeLayoutParams.map { spec ->
                    val paramLayoutName = spec.paramName
                    val paramKt = paramLayoutName.removePrefix("typeLayout")
                    val idx = declParams.indexOf(paramKt)
                    val argSyn = typeArgs.getOrNull(idx) ?: return null
                    val argLayout = sourcePointerLayout(argSyn, selfType, available, substitutions, visiting + rendered) ?: return null
                    "$paramLayoutName = $argLayout"
                }
            val renderedTypeArgs = if (typeArgs.isEmpty()) "" else "<${typeArgs.joinToString(", ") { renderType(it, selfType) }}>"
            return "$ownerBase.typeLayout$renderedTypeArgs(${argExprs.joinToString(", ")})"
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.transparentFactoryLeaf(
    declaration: Item.Struct,
    selfType: String?,
): Pair<SynType, Map<String, SynType>> {
    var field = requireNotNull(sourceSingleField(declaration)).ty
    val resolvedOwner = currentStandaloneImplSelfSynType?.let { resolveLayoutType(it, emptyMap()) }
    var substitutions: Map<String, SynType> =
        if (resolvedOwner != null && transparentDeclaration(renderType(resolvedOwner, selfType)) == declaration) {
            layoutSubstitutions(resolvedOwner, declaration, emptyMap())
        } else {
            emptyMap()
        }
    val visiting = linkedSetOf<String>()
    while (true) {
        field = resolveLayoutType(field, substitutions)
        val rendered = renderType(field, selfType)
        if (!visiting.add(rendered)) return field to substitutions
        val runtimeField = runtimeCellField(field)
        if (runtimeField != null) {
            field = runtimeField
            continue
        }
        val nested = transparentDeclaration(rendered) ?: return field to emptyMap()
        if (sourceTransparentField(nested, rendered) == null) return field to emptyMap()
        substitutions = layoutSubstitutions(field, nested, emptyMap())
        field = requireNotNull(sourceSingleField(nested)).ty
    }
}

internal fun KotlinJ2KEmitter.transparentFactoryLayoutType(declaration: Item.Struct, selfType: String?): String {
    val (leaf, substitutions) = transparentFactoryLeaf(declaration, selfType)
    return layoutRenderedType(leaf, selfType, substitutions)
}

internal fun KotlinJ2KEmitter.transparentFactoryLayout(declaration: Item.Struct, selfType: String?): String {
    val (leaf, substitutions) = transparentFactoryLeaf(declaration, selfType)
    sourcePointerLayout(leaf, selfType, substitutions = substitutions)?.let { return it }
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.UnresolvedTypeLayout")
    return "UnresolvedTypeLayout<${layoutRenderedType(leaf, selfType, substitutions)}>()"
}

internal fun KotlinJ2KEmitter.transparentConstructionLayout(
    declaration: Item.Struct,
    selfType: String?,
): String {
    val field =
        sourceTransparentField(declaration, selfType ?: rustTypeIdentToKotlin(declaration.ident.toString()))
            ?: error("Transparent construction requires a source-proven single field")
    val ownerType = currentStandaloneImplSelfSynType
    val resolvedOwner = ownerType?.let { resolveLayoutType(it, emptyMap()) }
    val substitutions =
        if (resolvedOwner != null && transparentDeclaration(renderType(resolvedOwner, selfType)) == declaration) {
            layoutSubstitutions(resolvedOwner, declaration, emptyMap())
        } else {
            emptyMap()
        }
    sourcePointerLayout(requireNotNull(sourceSingleField(declaration)).ty, selfType, substitutions = substitutions)?.let { return it }
    if (selfType?.substringBefore('<') == rustTypeIdentToKotlin(declaration.ident.toString()) && currentTypedLocals["this"] == selfType && currentTypedLocals["fieldLayout"] == null) {
        return "this.fieldLayout"
    }
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.UnresolvedTypeLayout")
    return "UnresolvedTypeLayout<${field.second}>()"
}

internal fun KotlinJ2KEmitter.sourceInitializerType(expression: Expr): SynType? {
    val source = unwrapParenOrGroupExpr(expression)
    if (source is Expr.Path && source.qself == null && source.path.segments.size == 1) {
        val raw =
            source.path.segments
                .last()
                ?.ident
                .toString()
        val local = scopeBuilder.resolveLocalKotlinName(raw.removePrefix("r#")) ?: rustValueIdentToKotlin(raw)
        currentSourceParameterTypes[local]?.let { return it }
    }
    val block =
        when (source) {
            is Expr.Const -> source.block
            is Expr.BlockExpr -> source.block
            is Expr.Unsafe -> source.block
            else -> null
        }
    if (block != null) {
        val tail = block.stmts.lastOrNull() as? Stmt.ExprStmt
        return tail?.expr?.let { sourceInitializerType(it) }
    }
    if (source is Expr.Reference) {
        return sourceInitializerType(source.expr)?.let {
            SynType.Reference(source.andToken, null, source.mutability, it)
        }
    }
    if (source is Expr.RawAddr) {
        val place = unwrapParenOrGroupExpr(source.expr)
        val elemType =
            if (place is Expr.Unary && place.op is io.github.kotlinmania.syn.UnOp.Deref) {
                (sourceInitializerType(place.expr) as? SynType.Ptr)?.elem
            } else {
                sourceInitializerType(place)
            }
        if (elemType != null) {
            return SynType.Ptr(
                io.github.kotlinmania.syn.token.Star
                    .default(),
                source.mutability,
                elemType,
            )
        }
    }
    if (source is Expr.Unary && source.op is io.github.kotlinmania.syn.UnOp.Deref) {
        val innerTy = sourceInitializerType(source.expr)
        if (innerTy is SynType.Ptr) return innerTy.elem
        if (innerTy is SynType.Reference) return innerTy.elem
    }
    if (source is Expr.Field) {
        val baseSyn = sourceInitializerType(source.base) ?: sourceReceiverExpressionType(source.base)
        val ownerRaw =
            (baseSyn as? SynType.Path)
                ?.path
                ?.segments
                ?.last()
                ?.ident
                ?.toString()
        val ownerKt = ownerRaw?.let { if (it == "Self") currentStandaloneImplSelfSynType?.let { s -> renderType(s, null).substringBefore('<') } else rustTypeIdentToKotlin(it) }
        if (ownerKt != null) {
            val fieldKt =
                when (val m = source.member) {
                    is Member.Named -> rustFieldIdentToKotlin(m.ident.toString())
                    is Member.Unnamed -> tupleFieldPropertyName(m.index.index.toInt(), inspector.tupleStructArityByType[ownerKt] ?: 1)
                }
            inspector.structFieldSynTypesByType[ownerKt]?.get(fieldKt)?.let { return it }
        }
    }
    if (source is Expr.Call) {
        CoreIntrinsicLowering.inferCoreIntrinsicReturnSynType(source, this)?.let { return it }
    }
    if (source is Expr.MethodCall) {
        val rawMethod = source.method.toString().removePrefix("r#")
        if (rawMethod in setOf("as_ptr", "as_mut_ptr") && source.args.isEmpty()) {
            sourceArrayLocal(source.receiver)?.second?.let { elem ->
                val mutability =
                    if (rawMethod == "as_mut_ptr") {
                        io.github.kotlinmania.syn.PointerMutability
                            .Mut(
                                io.github.kotlinmania.syn.token.Mut
                                    .default(),
                            )
                    } else {
                        io.github.kotlinmania.syn.PointerMutability
                            .Const(
                                io.github.kotlinmania.syn.token.Const
                                    .default(),
                            )
                    }
                return SynType.Ptr(
                    io.github.kotlinmania.syn.token.Star
                        .default(),
                    mutability,
                    elem,
                )
            }
        }
        if (rawMethod in
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
            ) && source.args.size == 1
        ) {
            sourceInitializerType(source.receiver)?.let { return it }
        }
        if (rawMethod == "cast_mut" && source.args.isEmpty()) {
            (sourceInitializerType(source.receiver) as? SynType.Ptr)?.let { ptr ->
                return SynType.Ptr(
                    ptr.starToken,
                    io.github.kotlinmania.syn.PointerMutability
                        .Mut(
                            io.github.kotlinmania.syn.token.Mut
                                .default(),
                        ),
                    ptr.elem,
                )
            }
        }
        if (rawMethod == "cast_const" && source.args.isEmpty()) {
            (sourceInitializerType(source.receiver) as? SynType.Ptr)?.let { ptr ->
                return SynType.Ptr(
                    ptr.starToken,
                    io.github.kotlinmania.syn.PointerMutability
                        .Const(
                            io.github.kotlinmania.syn.token.Const
                                .default(),
                        ),
                    ptr.elem,
                )
            }
        }
        if (rawMethod == "cast" && source.args.isEmpty()) {
            val dest =
                source.turbofish
                    ?.args
                    ?.toList()
                    ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
            val recvPtr = sourceInitializerType(source.receiver) as? SynType.Ptr
            if (dest != null && recvPtr != null) {
                return SynType.Ptr(recvPtr.starToken, recvPtr.mutability, dest)
            }
        }
        sourceReceiverMethodReturn(source.receiver, rawMethod)?.let { return it }
    }
    if (source is Expr.Cast) return source.ty
    val call = source as? Expr.Call
    val path =
        (call?.func as? Expr.Path)
            ?.takeIf { it.qself == null }
            ?.path
            ?.segments
            ?.toList()
    val last = path?.lastOrNull()
    if (last?.ident?.toString() in setOf("null_mut", "null") &&
        last?.ident?.toString() !in inspector.declaredTopLevelFnRawNames
    ) {
        val element =
            (last?.arguments as? PathArguments.AngleBracketed)
                ?.args
                ?.toList()
                ?.filterIsInstance<GenericArgument.TypeArg>()
                ?.singleOrNull()
                ?.type
        if (element != null) {
            val mutability =
                if (requireNotNull(last).ident.toString() == "null_mut") {
                    io.github.kotlinmania.syn.PointerMutability
                        .Mut(
                            io.github.kotlinmania.syn.token.Mut
                                .default(),
                        )
                } else {
                    io.github.kotlinmania.syn.PointerMutability
                        .Const(
                            io.github.kotlinmania.syn.token.Const
                                .default(),
                        )
                }
            return SynType.Ptr(
                io.github.kotlinmania.syn.token.Star
                    .default(),
                mutability,
                element,
            )
        }
    }
    if (path?.size == 1) {
        // Tuple-struct construction, including function-local declarations, carries
        // its source type even when the local binding has no annotation.
        val declaration = transparentDeclaration(path.single().ident.toString())
        if (declaration != null && declaration.generics.params.isEmpty()) {
            return SynType.Path(null, Path.from(declaration.ident))
        }
    }
    if (path?.size == 2) {
        val owner = path.first().ident.toString()
        val method = path.last().ident.toString()
        val returns =
            sameFileLayoutIndex()
                .implsBySelfIdent[owner]
                .orEmpty()
                .flatMap { implementation ->
                    implementation.items
                        .filterIsInstance<io.github.kotlinmania.syn.ImplItem.Fn>()
                        .filter {
                            it.sig.ident.toString() == method && it.sig.inputs.firstOrNull() !is FnArg.Receiver
                        }.mapNotNull { function ->
                            val parameters =
                                (
                                    implementation.generics.params.toList() +
                                        function.sig.generics.params
                                            .toList()
                                ).filterIsInstance<GenericParam.TypeParam>().map { it.ident.toString() }.toSet()
                            val bindings = linkedMapOf<String, SynType>()
                            fun bind(pattern: SynType, actual: SynType) {
                                val parameter =
                                    (pattern as? SynType.Path)
                                        ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                                        ?.path
                                        ?.segments
                                        ?.last()
                                        ?.ident
                                        ?.toString()
                                if (parameter in parameters) {
                                    bindings[requireNotNull(parameter)] = actual
                                    return
                                }
                                if (pattern is SynType.Ptr && actual is SynType.Ptr) bind(pattern.elem, actual.elem)
                                if (pattern is SynType.Reference && actual is SynType.Reference) bind(pattern.elem, actual.elem)
                            }
                            val inputs =
                                function.sig.inputs
                                    .toList()
                                    .filterIsInstance<FnArg.Typed>()
                            inputs.zip(requireNotNull(call).args.toList()).forEach { (input, argument) ->
                                sourceInitializerType(argument)?.let { bind(input.patType.ty, it) }
                            }
                            val result = extractReturnSynType(function.sig.output)
                            val returned = if ((result as? SynType.Path)?.path?.isIdent("Self") == true) implementation.selfType else result
                            returned?.let { substituteSourceType(it, bindings) }
                        }
                }.distinctBy { it.toTokenStream().toString() }
        returns.singleOrNull()?.let { return it }
    }
    if (source is Expr.Repeat) return sourceInitializerType(source.expr)?.let { SynType.Array(it, source.len) }
    if (source is Expr.Array) {
        val elements = source.elems.toList()
        val types = elements.map { sourceInitializerType(it) }
        val element = types.firstOrNull()
        if (element != null && types.all { it?.toTokenStream().toString() == element.toTokenStream().toString() }) {
            val length =
                Expr.Lit(
                    emptyList(),
                    Lit.Int(
                        io.github.kotlinmania.syn.LitInt
                            .new(elements.size.toString(), "", expression.span()),
                    ),
                )
            return SynType.Array(element, length)
        }
        return null
    }
    val value = (source as? Expr.Lit)?.lit
    val name =
        when (value) {
            is Lit.Bool -> "bool"
            is Lit.Int ->
                value.value
                    .suffix()
                    .ifEmpty { "i32" }
                    .takeIf { it in setOf("i8", "u8", "i16", "u16", "i32", "u32", "i64", "u64", "i128", "u128", "isize", "usize") }
            else -> null
        } ?: return null
    return SynType.Path(
        null,
        Path.from(
            io.github.kotlinmania.procmacro2.Ident
                .new(name, expression.span()),
        ),
    )
}

private fun KotlinJ2KEmitter.sourceReceiverExpressionType(expression: Expr): SynType? {
    val source = unwrapParenOrGroupExpr(expression)
    if (source is Expr.Path && source.qself == null && source.path.isIdent("self")) return currentStandaloneImplSelfSynType
    val field = source as? Expr.Field ?: return null
    val base = sourceReceiverExpressionType(field.base) ?: return null
    val ownerName =
        (base as? SynType.Path)
            ?.path
            ?.segments
            ?.last()
            ?.ident
            ?.toString() ?: return null
    val alias = sameFileLayoutIndex().typeAliasesByIdent[ownerName]?.singleOrNull()
    val declaration = transparentDeclaration(ownerName) ?: alias?.let { sourceStorageAlias(it) }
    if (declaration != null) {
        val expected = sourceTransparentField(declaration, ownerName)?.first ?: return null
        val name =
            when (val member = field.member) {
                is Member.Named -> rustFieldIdentToKotlin(member.ident.toString())
                is Member.Unnamed -> tupleFieldPropertyName(member.index.index.toInt(), 1)
            }
        if (expected != name) return null
        return if (alias != null) {
            sourceStorageAliasField(alias, declaration)
        } else {
            resolveLayoutType(sourceSingleField(declaration)?.ty ?: return null, layoutSubstitutions(base, declaration, emptyMap()))
        }
    }
    return runtimeCellField(base)?.takeIf { (field.member as? Member.Named)?.ident?.toString() == "value" }
}

internal fun KotlinJ2KEmitter.sourceReceiverMethodReturn(receiver: Expr, method: String): SynType? {
    if (method == "get") {
        val cell = sourceReceiverExpressionType(receiver)?.let { runtimeCellField(it) }
        if (cell != null) {
            return SynType.Ptr(
                io.github.kotlinmania.syn.token.Star
                    .default(),
                io.github.kotlinmania.syn.PointerMutability
                    .Mut(
                        io.github.kotlinmania.syn.token.Mut
                            .default(),
                    ),
                cell,
            )
        }
    }
    val path = (unwrapParenOrGroupExpr(receiver) as? Expr.Path)?.takeIf { it.qself == null }?.path ?: return null
    if (!path.isIdent("self")) return null
    val owner = currentStandaloneImplSelfSynType as? SynType.Path ?: return null
    val ownerSegment = owner.path.segments.last() ?: return null
    val arguments =
        (ownerSegment.arguments as? PathArguments.AngleBracketed)
            ?.args
            ?.toList()
            .orEmpty()
            .filterIsInstance<GenericArgument.TypeArg>()
            .map { it.type }
    val results =
        sameFileLayoutIndex()
            .implsBySelfIdent[ownerSegment.ident.toString()]
            .orEmpty()
            .flatMap { implementation ->
                val parameters =
                    implementation.generics.params
                        .toList()
                        .filterIsInstance<GenericParam.TypeParam>()
                        .map { it.ident.toString() }
                val substitutions = parameters.zip(arguments).toMap() + ("Self" to owner)
                implementation.items
                    .filterIsInstance<ImplItem.Fn>()
                    .filter {
                        it.sig.ident.toString() == method && it.sig.inputs.firstOrNull() is FnArg.Receiver
                    }.mapNotNull { function ->
                        extractReturnSynType(function.sig.output)?.let { substituteSourceType(it, substitutions) }
                    }
            }.distinctBy { it.toTokenStream().toString() }
    return results.singleOrNull()
}

internal fun KotlinJ2KEmitter.primitiveArrayLayoutFactory(
    elemSynType: SynType,
    elemRenderedType: String,
    pointerWidthBits: Long,
): String? {
    val clean = elemRenderedType.trim()
    if (clean == "Boolean") return "booleanArray"
    val unwrappedElem = unwrapSynType(elemSynType)
    val rawIdent =
        (unwrappedElem as? SynType.Path)
            ?.takeIf {
                it.qself == null && it.path.segments.size == 1 &&
                    it.path.segments
                        .last()
                        ?.arguments is PathArguments.None
            }?.path
            ?.segments
            ?.last()
            ?.ident
            ?.toString()
    if (!headerContext.rawAbiMode && pointerWidthBits == 64L) {
        if (rawIdent == "usize") return "usize64Array"
        if (rawIdent == "isize") return "isize64Array"
    }
    return when (resolveNumericKind(clean)) {
        NumericKind.I8 -> "byteArray"
        NumericKind.U8 -> "uByteArray"
        NumericKind.I16 -> "shortArray"
        NumericKind.U16 -> "uShortArray"
        NumericKind.I32 -> "intArray"
        NumericKind.U32 -> "uIntArray"
        NumericKind.I64 -> "longArray"
        NumericKind.U64 -> "uLongArray"
        NumericKind.F32 -> "floatArray"
        NumericKind.F64 -> "doubleArray"
        NumericKind.I128, NumericKind.U128, NumericKind.F16, NumericKind.F128, null -> null
    }
}

internal fun KotlinJ2KEmitter.sourceArrayLocal(receiver: Expr): Pair<String, SynType>? {
    val unwrapped =
        unwrapParenOrGroupExpr(receiver).let { node ->
            if (node is Expr.Reference) unwrapParenOrGroupExpr(node.expr) else node
        }
    val path = (unwrapped as? Expr.Path)?.takeIf { it.qself == null && it.path.segments.size == 1 }?.path ?: return null
    val raw =
        path.segments
            .last()
            ?.ident
            .toString()
            .removePrefix("r#")
    val name = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
    val storage = currentAddressableLocals[name] ?: return null
    val element = currentAddressableArrayElementTypes[name] ?: return null
    return storage to element
}

package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Group
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Fields
import io.github.kotlinmania.syn.File
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.ForeignItem
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.ReturnType
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.UseTree
import io.github.kotlinmania.syn.Variant
import io.github.kotlinmania.syn.gen.Visit

internal data class AssocTypeDecl(
    val ty: SynType,
    val generics: Generics,
)

internal fun isSynResultReturnType(output: ReturnType): Boolean {
    val ty = extractReturnSynType(output) as? SynType.Path ?: return false
    if (ty.qself != null) return false
    val segs =
        ty.path.segments
            .toList()
            .map { it.ident.toString().removePrefix("r#") }
    if (segs.lastOrNull() != "Result") return false
    val isStdFmt =
        (segs.size == 2 && segs[0] == "fmt") ||
            (segs.size >= 3 && segs[0] in setOf("core", "std", "alloc") && segs[1] == "fmt")
    return !isStdFmt
}

internal fun itemHasDocOnlyCfg(item: Item): Boolean =
    when (item) {
        is Item.Const -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Enum -> isDocOnlyCfgAttribute(item.attrs)
        is Item.ExternCrate -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Fn -> isDocOnlyCfgAttribute(item.attrs)
        is Item.ForeignMod -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Impl -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Macro -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Mod -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Static -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Struct -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Trait -> isDocOnlyCfgAttribute(item.attrs)
        is Item.TraitAlias -> isDocOnlyCfgAttribute(item.attrs)
        is Item.ItemType -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Union -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Use -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Verbatim -> false
    }

internal fun implItemHasDocOnlyCfg(item: ImplItem): Boolean =
    when (item) {
        is ImplItem.Const -> isDocOnlyCfgAttribute(item.attrs)
        is ImplItem.Fn -> isDocOnlyCfgAttribute(item.attrs)
        is ImplItem.AssocType -> isDocOnlyCfgAttribute(item.attrs)
        is ImplItem.Macro -> isDocOnlyCfgAttribute(item.attrs)
        is ImplItem.Verbatim -> false
    }

internal fun traitItemHasDocOnlyCfg(item: TraitItem): Boolean =
    when (item) {
        is TraitItem.Const -> isDocOnlyCfgAttribute(item.attrs)
        is TraitItem.Fn -> isDocOnlyCfgAttribute(item.attrs)
        is TraitItem.AssocType -> isDocOnlyCfgAttribute(item.attrs)
        is TraitItem.Macro -> isDocOnlyCfgAttribute(item.attrs)
        is TraitItem.Verbatim -> false
    }

internal fun foreignItemHasDocOnlyCfg(item: ForeignItem): Boolean =
    when (item) {
        is ForeignItem.Fn -> isDocOnlyCfgAttribute(item.attrs)
        is ForeignItem.Static -> isDocOnlyCfgAttribute(item.attrs)
        is ForeignItem.ItemType -> isDocOnlyCfgAttribute(item.attrs)
        is ForeignItem.Macro -> isDocOnlyCfgAttribute(item.attrs)
        is ForeignItem.Verbatim -> false
    }

internal enum class NumericKind {
    I8,
    I16,
    I32,
    I64,
    I128,
    U8,
    U16,
    U32,
    U64,
    U128,
    F16,
    F32,
    F64,
    F128,
}

internal fun unwrapSynType(ty: SynType): SynType =
    when (ty) {
        is SynType.Group -> unwrapSynType(ty.elem)
        is SynType.Paren -> unwrapSynType(ty.elem)
        else -> ty
    }

internal fun isExternalIdentityTypeReexportAlias(
    i: Item.ItemType,
    selfCrateAliases: Set<String>,
    localInlineModNames: Set<String> = emptySet(),
): Boolean {
    val genericParams = i.generics.params.toList()
    if (genericParams.isNotEmpty()) return false
    val rhsPath = unwrapSynType(i.ty) as? SynType.Path ?: return false
    if (rhsPath.qself != null) return false
    val segs = rhsPath.path.segments.toList()
    if (segs.size < 2) return false
    val lastSeg = segs.last()
    if (lastSeg.arguments !is PathArguments.None) return false
    val firstIdent = segs.first().ident.toString()
    val firstRaw = firstIdent.removePrefix("r#")
    if (firstRaw == "crate" ||
        firstRaw == "super" ||
        firstRaw == "self" ||
        firstRaw in selfCrateAliases ||
        firstRaw in localInlineModNames
    ) {
        return false
    }
    val lhsKt = rustTypeIdentToKotlin(i.ident.toString())
    val rhsKt = rustTypeIdentToKotlin(lastSeg.ident.toString())
    return lhsKt == rhsKt
}

internal class CrateSymbolInspector(
    private val selfCrateAliases: Set<String> = emptySet(),
) : Visit() {
    val declaredTypeNames: MutableSet<String> = linkedSetOf()
    val attachableTypeNames: MutableSet<String> = linkedSetOf()
    val unitStructNames: MutableSet<String> = linkedSetOf()
    val enumTypeNames: MutableSet<String> = linkedSetOf()
    val unionTypeNames: MutableSet<String> = linkedSetOf()
    val associatedTypeDecls: MutableMap<String, AssocTypeDecl> = linkedMapOf()
    val ownerAssocTypes: MutableMap<String, MutableMap<String, SynType>> = linkedMapOf()
    val typeAliasTargets: MutableMap<String, SynType> = linkedMapOf()
    val declaredConstNames: MutableMap<String, String> = linkedMapOf()
    val declaredConstTypes: MutableMap<String, SynType> = linkedMapOf()
    val tupleStructArityByType: MutableMap<String, Int> = linkedMapOf()
    val tupleEnumVariantArity: MutableMap<Pair<String, String>, Int> = linkedMapOf()
    val enumTypeParamsByVariant: MutableMap<String, List<String>> = linkedMapOf()
    val enumVariantFieldTypes: MutableMap<String, List<SynType>> = linkedMapOf()
    val structFieldNamesByType: MutableMap<String, List<String>> = linkedMapOf()
    val structFieldSynTypesByType: MutableMap<String, Map<String, SynType>> = linkedMapOf()
    val declaredTraitsByName: MutableMap<String, Item.Trait> = linkedMapOf()
    val typeDefaultArgsByType: MutableMap<String, List<Pair<String, SynType?>>> = linkedMapOf()

    private fun recordTypeDefaultArgs(ktName: String, generics: Generics) {
        val typeParams = generics.params.toList().filterIsInstance<GenericParam.TypeParam>()
        if (typeParams.any { it.default != null } && ktName !in typeDefaultArgsByType) {
            typeDefaultArgsByType[ktName] = typeParams.map { rustTypeIdentToKotlin(it.ident.toString()) to it.default?.type }
        }
    }
    val instanceMethodsByType: MutableMap<String, MutableSet<String>> = linkedMapOf()
    val companionMethodsByType: MutableMap<String, MutableSet<String>> = linkedMapOf()
    val nullableFieldsByType: MutableMap<String, MutableSet<String>> = linkedMapOf()
    val dequeIterFieldsByType: MutableMap<String, MutableSet<String>> = linkedMapOf()
    val vecFieldsByType: MutableMap<String, MutableSet<String>> = linkedMapOf()
    val selfParameterizedTraits: MutableSet<String> = linkedSetOf()
    val traitSupertraits: MutableMap<String, List<String>> = linkedMapOf()
    val traitSupertraitBindings: MutableMap<String, List<Pair<String, Set<String>>>> = linkedMapOf()
    val traitDefaultlessAssocTypes: MutableMap<String, List<String>> = linkedMapOf()
    val traitResultMethods: MutableMap<String, MutableSet<String>> = linkedMapOf()
    val localInlineModuleNames: MutableSet<String> = linkedSetOf()
    val localExternalModuleNames: MutableSet<String> = linkedSetOf()
    val declaredTopLevelFnRawNames: MutableSet<String> = linkedSetOf()
    val externFnKotlinNames: MutableSet<String> = linkedSetOf()
    val fnConstParamNames: MutableMap<String, Pair<Int, List<String>>> = linkedMapOf()
    val methodConstParamNames: MutableMap<String, Pair<Int, List<String>>> = linkedMapOf()
    val collidingInlineForeignFnRenames: MutableMap<Pair<String, String>, String> = linkedMapOf()
    val collidingTopLevelForeignFnRenames: MutableMap<String, String> = linkedMapOf()
    private val inlineModStack: ArrayDeque<String> = ArrayDeque()
    private val outerTopLevelFnArities: MutableSet<Pair<String, Int>> = linkedSetOf()
    private val inlineModForeignFns: MutableList<Triple<String, String, Int>> = mutableListOf()
    private val topLevelDefinedFnRawsByKt: MutableMap<String, MutableSet<String>> = linkedMapOf()
    private val topLevelForeignFns: MutableList<Pair<String, Int>> = mutableListOf()
    private val pendingImplMethodConsts: MutableList<QuadImplConstSpec> = mutableListOf()
    val topLevelFnReturnSynTypes: MutableMap<String, SynType> = linkedMapOf()
    val implMethodReturnSynTypes: MutableMap<Pair<String, String>, SynType> = linkedMapOf()
    val referencedUnqualifiedTypeIdents: MutableSet<String> = linkedSetOf()
    val referencedPathPrefixTypeIdents: MutableSet<String> = linkedSetOf()
    val ffiReferencedTypeNames: MutableSet<String> = linkedSetOf()
    val allUseItems: MutableList<Item.Use> = mutableListOf()
    val enumVariantSimpleNames: MutableSet<String> = linkedSetOf()
    val importedEnumVariantSimpleNames: MutableSet<String> = linkedSetOf()
    var hasImportedEnumGlob: Boolean = false
    var hasLocalNoneVariant: Boolean = false
    private var blockDepth: Int = 0

    private fun recordFfiReferencedType(ty: SynType) {
        val collector =
            object : Visit() {
                override fun visitPath(p: Path) {
                    val segs = p.segments.toList()
                    if (segs.isNotEmpty()) {
                        val raw =
                            segs
                                .last()
                                .ident
                                .toString()
                                .removePrefix("r#")
                        ffiReferencedTypeNames += raw
                        ffiReferencedTypeNames += rustTypeIdentToKotlin(raw)
                    }
                    super.visitPath(p)
                }
            }
        collector.visitType(ty)
    }

    fun closeFfiReferencedTypeAliases() {
        var changed = true
        var rounds = 0
        while (changed && rounds < 12) {
            changed = false
            rounds++
            for ((aliasName, targetTy) in typeAliasTargets) {
                if (aliasName in ffiReferencedTypeNames) {
                    val before = ffiReferencedTypeNames.size
                    recordFfiReferencedType(targetTy)
                    if (ffiReferencedTypeNames.size != before) {
                        changed = true
                    }
                }
            }
        }
    }

    override fun visitType(t: SynType) {
        if (t is SynType.FnPtr && t.abi != null) {
            for (arg in t.inputs.toList()) {
                recordFfiReferencedType(arg.ty)
            }
            val out = t.output
            if (out is ReturnType.TypeReturn) {
                recordFfiReferencedType(out.ty)
            }
        }
        if (t is SynType.Path && t.qself == null) {
            val segs = t.path.segments.toList()
            if (segs.size == 1) {
                referencedUnqualifiedTypeIdents += segs[0].ident.toString().removePrefix("r#")
            } else if (segs.size >= 2) {
                referencedPathPrefixTypeIdents += segs[0].ident.toString().removePrefix("r#")
            }
        }
        super.visitType(t)
    }

    override fun visitExprPath(e: Expr.Path) {
        if (e.qself == null) {
            val segs = e.path.segments.toList()
            if (segs.size >= 2) {
                referencedPathPrefixTypeIdents += segs[0].ident.toString().removePrefix("r#")
            }
        }
        super.visitExprPath(e)
    }

    private val attachableGenericsByName: MutableMap<String, Generics> = linkedMapOf()
    private val pendingImplChecks: MutableList<Pair<Item.Impl, QuadImplConstSpec>> = mutableListOf()

    override fun visitFile(f: File) {
        if (blockDepth == 0) {
            for (item in f.items) {
                if (itemHasDocOnlyCfg(item)) continue
                when (item) {
                    is Item.Struct -> attachableGenericsByName[rustTypeIdentToKotlin(item.ident.toString())] = item.generics
                    is Item.Enum -> attachableGenericsByName[rustTypeIdentToKotlin(item.ident.toString())] = item.generics
                    is Item.Union -> attachableGenericsByName[rustTypeIdentToKotlin(item.ident.toString())] = item.generics
                    is Item.Mod -> {
                        val modRaw = item.ident.toString().removePrefix("r#")
                        if (item.content is ModContent.Inline) {
                            localInlineModuleNames += modRaw
                        } else if (item.content == null) {
                            localExternalModuleNames += modRaw
                        }
                    }
                    else -> {}
                }
            }
        }
        super.visitFile(f)
        if (blockDepth == 0) {
            for ((modRaw, rawFn, arity) in inlineModForeignFns) {
                val ktFn = rustFunctionIdentToKotlin(rawFn)
                if ((ktFn to arity) in outerTopLevelFnArities) {
                    collidingInlineForeignFnRenames[modRaw to rawFn] = rustFunctionIdentToKotlin("${modRaw}_$rawFn")
                }
            }
            for ((rawForeign, _) in topLevelForeignFns) {
                val ktFn = rustFunctionIdentToKotlin(rawForeign)
                val definedRaws = topLevelDefinedFnRawsByKt[ktFn]
                if (!definedRaws.isNullOrEmpty()) {
                    val cap = ktFn.replaceFirstChar { it.uppercaseChar() }
                    collidingTopLevelForeignFnRenames[rawForeign] = "extern$cap"
                }
            }
            for ((implItem, spec) in pendingImplChecks) {
                val ownerDeclGenerics = attachableGenericsByName[spec.ownerName]
                val direct =
                    spec.ownerName in attachableTypeNames &&
                        (
                            ownerDeclGenerics == null ||
                                (
                                    matchesInspectorDirectImplSelf(implItem.selfType, ownerDeclGenerics) &&
                                        !(
                                            implItem.traitPath != null &&
                                                declaresExtraNonImplicitImplTraitBounds(implItem, ownerDeclGenerics) { path ->
                                                    val raw =
                                                        path.segments
                                                            .last()
                                                            ?.ident
                                                            ?.toString()
                                                            ?.removePrefix("r#") ?: return@declaresExtraNonImplicitImplTraitBounds false
                                                    val kt = rustTypeIdentToKotlin(raw)
                                                    (kt in RUST_STD_IMPLICIT_SUPERTRAITS && kt !in declaredTypeNames) ||
                                                        (kt == "Error" && kt !in declaredTraitsByName)
                                                }
                                        )
                                )
                        )
                val combinedConsts =
                    if (direct) {
                        spec.fnConsts
                    } else {
                        (spec.implConsts + spec.fnConsts).distinct()
                    }
                if (combinedConsts.isNotEmpty()) {
                    val key = "${spec.ownerName}.${spec.fnKt}"
                    if (key !in methodConstParamNames) {
                        methodConstParamNames[key] = spec.valueArity to combinedConsts
                    }
                }
            }
            pendingImplMethodConsts.clear()
        }
    }

    private fun matchesInspectorDirectImplSelf(selfTy: SynType, ownerDeclGenerics: Generics): Boolean =
        when (val u = unwrapSynType(selfTy)) {
            is SynType.Reference -> matchesInspectorDirectImplSelf(u.elem, ownerDeclGenerics)
            is SynType.Path -> {
                val seg = u.path.segments.last() ?: return true
                matchesUnspecializedOwnerGenerics(seg.arguments, ownerDeclGenerics)
            }
            else -> true
        }

    private data class QuadImplConstSpec(
        val ownerName: String,
        val fnKt: String,
        val valueArity: Int,
        val fnConsts: List<String>,
        val implConsts: List<String>,
    )

    override fun visitItem(i: Item) {
        if (itemHasDocOnlyCfg(i)) return
        if (i is Item.ForeignMod) {
            for (foreign in i.items) {
                if (foreignItemHasDocOnlyCfg(foreign)) continue
                when (foreign) {
                    is ForeignItem.Fn -> {
                        val rawFnIdent = foreign.sig.ident.toString()
                        val cleanRawFnIdent = rawFnIdent.removePrefix("r#")
                        if (blockDepth == 0) {
                            declaredTopLevelFnRawNames += cleanRawFnIdent
                            externFnKotlinNames += rustFunctionIdentToKotlin(rawFnIdent)
                            val activeMod = inlineModStack.lastOrNull()
                            val foreignArity = foreign.sig.inputs.count { it is FnArg.Typed }
                            if (activeMod != null) {
                                if (foreignArity == 0) {
                                    inlineModForeignFns += Triple(activeMod, cleanRawFnIdent, foreignArity)
                                }
                            } else {
                                topLevelForeignFns += cleanRawFnIdent to foreignArity
                            }
                        }
                        for (arg in foreign.sig.inputs.toList()) {
                            if (arg is FnArg.Typed) {
                                recordFfiReferencedType(arg.patType.ty)
                            }
                        }
                        val out = foreign.sig.output
                        if (out is ReturnType.TypeReturn) {
                            recordFfiReferencedType(out.ty)
                            val genParams = foreign.sig.generics.params
                            if (blockDepth == 0 && genParams.isEmpty()) {
                                val cleanRawFn = rawFnIdent.removePrefix("r#")
                                if (cleanRawFn !in topLevelFnReturnSynTypes) {
                                    topLevelFnReturnSynTypes[cleanRawFn] = out.ty
                                }
                                val ktFn = rustFunctionIdentToKotlin(rawFnIdent)
                                if (ktFn !in topLevelFnReturnSynTypes) {
                                    topLevelFnReturnSynTypes[ktFn] = out.ty
                                }
                            }
                        }
                    }
                    is ForeignItem.Static -> recordFfiReferencedType(foreign.ty)
                    else -> {}
                }
            }
        } else if (i is Item.Macro) {
            val pathSegs = i.mac.path.segments
            val segs = pathSegs.toList().map { it.ident.toString() }
            val isWinLink =
                (segs.size == 2 && (segs[0] == "windows_link" || segs[0] == "windows_targets") && segs[1] == "link") ||
                    segs.lastOrNull() == "link"
            if (isWinLink) {
                val macTokens = i.mac.tokens
                val trees = macTokens.clone().toList()
                val fnIdx = trees.indexOfFirst { tt -> tt is TokenTree.Ident && tt.value.toString() == "fn" }
                if (fnIdx >= 0) {
                    val fnTokens = trees.subList(fnIdx, trees.size)
                    val pubTokens = TokenStream.fromString("pub").getOrThrow().toList()
                    val hasTrailingSemi = (fnTokens.lastOrNull() as? TokenTree.Punct)?.value?.asChar() == ';'
                    val semiTokens = if (hasTrailingSemi) emptyList() else TokenStream.fromString(";").getOrThrow().toList()
                    val braceBody = TokenTree.Group(Group(Delimiter.Brace, TokenStream.fromTokenTrees(pubTokens + fnTokens + semiTokens)))
                    val externPrefix = TokenStream.fromString("extern \"C\"").getOrThrow().toList()
                    val wrappedStream = TokenStream.fromTokenTrees(externPrefix + braceBody)
                    val parsedFile =
                        io.github.kotlinmania.syn
                            .parse2(io.github.kotlinmania.syn.FileParse::parse, wrappedStream)
                            .getOrNull()
                    val foreignMod = parsedFile?.items?.firstOrNull() as? Item.ForeignMod
                    val foreignFn = foreignMod?.items?.firstOrNull() as? ForeignItem.Fn
                    if (foreignFn != null) {
                        val rawFnIdent = foreignFn.sig.ident.toString()
                        if (blockDepth == 0) {
                            declaredTopLevelFnRawNames += rawFnIdent.removePrefix("r#")
                            externFnKotlinNames += rustFunctionIdentToKotlin(rawFnIdent)
                        }
                        for (arg in foreignFn.sig.inputs.toList()) {
                            if (arg is FnArg.Typed) {
                                recordFfiReferencedType(arg.patType.ty)
                            }
                        }
                        val out = foreignFn.sig.output
                        if (out is ReturnType.TypeReturn) {
                            recordFfiReferencedType(out.ty)
                        }
                    }
                }
            }
        }
        super.visitItem(i)
    }

    override fun visitItemFn(i: Item.Fn) {
        if (blockDepth == 0) {
            val rawIdent = i.sig.ident.toString()
            val cleanRaw = rawIdent.removePrefix("r#")
            val ktFn = rustFunctionIdentToKotlin(rawIdent)
            declaredTopLevelFnRawNames += cleanRaw
            if (inlineModStack.isEmpty()) {
                val arity = i.sig.inputs.count { it is FnArg.Typed }
                topLevelDefinedFnRawsByKt.getOrPut(ktFn) { linkedSetOf() }.add(cleanRaw)
                if (arity == 0) {
                    outerTopLevelFnArities += ktFn to arity
                }
            }
            val constParams =
                i.sig.generics.params
                    .toList()
                    .mapNotNull {
                        (it as? GenericParam.ConstParam)
                            ?.ident
                            ?.toString()
                            ?.removePrefix("r#")
                            ?.let(::rustValueIdentToKotlin)
                    }
            if (constParams.isNotEmpty()) {
                val valueArity = i.sig.inputs.count { it is FnArg.Typed }
                fnConstParamNames.putIfMissingKey(cleanRaw, ktFn, valueArity, constParams)
            }
            val genParams = i.sig.generics.params
            if (genParams.isEmpty()) {
                val out = i.sig.output
                if (out is ReturnType.TypeReturn) {
                    if (cleanRaw !in topLevelFnReturnSynTypes) {
                        topLevelFnReturnSynTypes[cleanRaw] = out.ty
                    }
                    if (ktFn !in topLevelFnReturnSynTypes) {
                        topLevelFnReturnSynTypes[ktFn] = out.ty
                    }
                }
            }
        }
        super.visitItemFn(i)
    }

    private fun MutableMap<String, Pair<Int, List<String>>>.putIfMissingKey(
        cleanRaw: String,
        ktFn: String,
        arity: Int,
        consts: List<String>,
    ) {
        if (cleanRaw !in this) this[cleanRaw] = arity to consts
        if (ktFn !in this) this[ktFn] = arity to consts
    }

    override fun visitBlock(b: Block) {
        blockDepth++
        super.visitBlock(b)
        blockDepth--
    }

    override fun visitStmt(s: Stmt) {
        if (isLeadingCfgStmtDisabled(s)) return
        super.visitStmt(s)
    }

    override fun visitItemUse(i: Item.Use) {
        allUseItems += i
        if (useTreeImportsNonOptionNone(i.tree, emptyList())) {
            hasLocalNoneVariant = true
        }
        collectImportedEnumVariants(i.tree, emptyList())
        super.visitItemUse(i)
    }

    private fun collectImportedEnumVariants(tree: UseTree, prefix: List<String>) {
        when (tree) {
            is UseTree.Path -> {
                val child = tree.tree
                if (child != null) {
                    collectImportedEnumVariants(child, prefix + tree.ident.toString().removePrefix("r#"))
                }
            }
            is UseTree.Name -> {
                val parent = prefix.lastOrNull()
                if (parent != null && parent.firstOrNull()?.isUpperCase() == true) {
                    val seg = tree.ident.toString().removePrefix("r#")
                    val rename = tree.rename
                    val bound = if (rename != null) rename.ident.toString().removePrefix("r#") else seg
                    if (bound != "self" && bound.firstOrNull()?.isUpperCase() == true) {
                        importedEnumVariantSimpleNames += rustTypeIdentToKotlin(bound)
                    }
                }
            }
            is UseTree.Glob -> {
                val parent = prefix.lastOrNull()
                if (parent != null && parent.firstOrNull()?.isUpperCase() == true) {
                    hasImportedEnumGlob = true
                }
            }
            is UseTree.Group -> tree.items.toList().forEach { collectImportedEnumVariants(it, prefix) }
        }
    }

    private fun useTreeImportsNonOptionNone(tree: UseTree, prefix: List<String>): Boolean =
        when (tree) {
            is UseTree.Path -> {
                val child = tree.tree
                child != null && useTreeImportsNonOptionNone(child, prefix + tree.ident.toString())
            }
            is UseTree.Name -> {
                val seg = tree.ident.toString()
                val renameIdent = tree.rename?.ident?.toString()
                val bound = renameIdent?.removePrefix("r#") ?: seg
                bound == "None" && prefix.lastOrNull() != "Option"
            }
            is UseTree.Glob -> false
            is UseTree.Group -> tree.items.toList().any { useTreeImportsNonOptionNone(it, prefix) }
        }

    private fun registerTopLevelConstOrStatic(raw: String, ty: SynType) {
        if (raw == "_" || blockDepth != 0) return
        val existingKt = declaredConstNames[raw]
        if (existingKt != null) {
            declaredConstTypes[existingKt] = ty
            return
        }
        val baseKt = rustConstIdentToKotlin(raw)
        val collidingRaw = declaredConstNames.entries.firstOrNull { it.key != raw && it.value == baseKt }?.key
        if (collidingRaw == null) {
            declaredConstNames[raw] = baseKt
            declaredConstTypes[baseKt] = ty
        } else if (collidingRaw.startsWith("_") && !raw.startsWith("_")) {
            var idx = 1
            var alt = "$baseKt$idx"
            while (alt in declaredConstNames.values) {
                idx++
                alt = "$baseKt$idx"
            }
            declaredConstNames[collidingRaw] = alt
            declaredConstTypes[baseKt]?.let { declaredConstTypes[alt] = it }
            declaredConstNames[raw] = baseKt
            declaredConstTypes[baseKt] = ty
        } else {
            var idx = 1
            var alt = "$baseKt$idx"
            while (alt in declaredConstNames.values) {
                idx++
                alt = "$baseKt$idx"
            }
            declaredConstNames[raw] = alt
            declaredConstTypes[alt] = ty
        }
    }

    override fun visitItemConst(i: Item.Const) {
        val raw = i.ident.toString().removePrefix("r#")
        registerTopLevelConstOrStatic(raw, i.ty)
        super.visitItemConst(i)
    }

    override fun visitItemStatic(i: Item.Static) {
        val raw = i.ident.toString().removePrefix("r#")
        registerTopLevelConstOrStatic(raw, i.ty)
        super.visitItemStatic(i)
    }

    override fun visitForeignItemStatic(i: ForeignItem.Static) {
        if (foreignItemHasDocOnlyCfg(i)) return
        val raw = i.ident.toString().removePrefix("r#")
        registerTopLevelConstOrStatic(raw, i.ty)
        super.visitForeignItemStatic(i)
    }

    override fun visitExprStruct(e: Expr.Struct) {
        if (e.qself == null) {
            val segs = e.path.segments.toList()
            if (segs.size == 1) {
                val raw = segs[0].ident.toString().removePrefix("r#")
                if (raw != "Self") {
                    referencedUnqualifiedTypeIdents += raw
                }
            }
        }
        super.visitExprStruct(e)
    }

    override fun visitItemStruct(i: Item.Struct) {
        val name = rustTypeIdentToKotlin(i.ident.toString())
        if (blockDepth == 0) {
            declaredTypeNames += name
            attachableTypeNames += name
            recordTypeDefaultArgs(name, i.generics)
        }
        when (val fields = i.fields) {
            is Fields.Unnamed -> {
                val list = fields.fields.unnamed.toList()
                tupleStructArityByType[name] = list.size
                val fieldNames = list.indices.map { idx -> tupleFieldPropertyName(idx, list.size) }
                structFieldNamesByType[name] = fieldNames
                val fieldTyMap = linkedMapOf<String, SynType>()
                for ((idx, f) in list.withIndex()) {
                    fieldTyMap[fieldNames[idx]] = f.ty
                }
                structFieldSynTypesByType[name] = fieldTyMap
            }
            is Fields.Named -> {
                val names = mutableListOf<String>()
                val fieldTyMap = linkedMapOf<String, SynType>()
                val seen = hashSetOf<String>()
                val namedFields = fields.fields.named.toList()
                for ((idx, f) in namedFields.withIndex()) {
                    if (isDocOnlyCfgAttribute(f.attrs)) continue
                    val fName = rustFieldIdentToKotlin(f.ident?.toString() ?: "field$idx")
                    if (seen.add(fName)) {
                        names += fName
                        fieldTyMap[fName] = f.ty
                        classifyStructFieldType(name, fName, f.ty)
                    }
                }
                structFieldNamesByType[name] = names
                structFieldSynTypesByType[name] = fieldTyMap
            }
            is Fields.Unit -> {
                unitStructNames += name
                structFieldNamesByType[name] = emptyList()
            }
        }
        super.visitItemStruct(i)
    }

    private fun classifyStructFieldType(ownerType: String, ktFieldName: String, ty: SynType) {
        val u = unwrapSynType(ty)
        val path = (u as? SynType.Path)?.takeIf { it.qself == null }?.path ?: return
        val segs = path.segments.toList()
        if (segs.isEmpty()) return
        val lastIdent = segs.last().ident.toString()
        val hasSlicePrefix = segs.any { it.ident.toString() == "slice" }
        val hasVecOrOptionPrefix = segs.any { it.ident.toString() == "vec" || it.ident.toString() == "option" }
        when {
            lastIdent == "Option" -> nullableFieldsByType.getOrPut(ownerType) { linkedSetOf() }.add(ktFieldName)
            lastIdent == "Vec" -> vecFieldsByType.getOrPut(ownerType) { linkedSetOf() }.add(ktFieldName)
            (lastIdent == "Iter" || lastIdent == "IterMut") && hasSlicePrefix ->
                dequeIterFieldsByType.getOrPut(ownerType) { linkedSetOf() }.add(ktFieldName)
            lastIdent == "IntoIter" && hasVecOrOptionPrefix ->
                dequeIterFieldsByType.getOrPut(ownerType) { linkedSetOf() }.add(ktFieldName)
        }
    }

    override fun visitItemEnum(i: Item.Enum) {
        val name = rustTypeIdentToKotlin(i.ident.toString())
        enumTypeNames += name
        if (blockDepth == 0) {
            declaredTypeNames += name
            attachableTypeNames += name
            recordTypeDefaultArgs(name, i.generics)
        }
        val enumTypeParams =
            i.generics.params
                .toList()
                .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
        for (v in i.variants.toList()) {
            val vName = rustTypeIdentToKotlin(v.ident.toString())
            enumVariantSimpleNames += vName
            if (enumTypeParams.isNotEmpty()) {
                enumTypeParamsByVariant["$name.$vName"] = enumTypeParams
                enumTypeParamsByVariant[vName] = enumTypeParams
            }
            val vFields = v.fields
            if (vFields is Fields.Unnamed) {
                val fieldTys =
                    vFields.fields.unnamed
                        .toList()
                        .map { it.ty }
                tupleEnumVariantArity[name to vName] = fieldTys.size
                enumVariantFieldTypes["$name.$vName"] = fieldTys
                enumVariantFieldTypes[vName] = fieldTys
            }
        }
        super.visitItemEnum(i)
    }

    override fun visitItemTrait(i: Item.Trait) {
        val ktTraitName = rustTypeIdentToKotlin(i.ident.toString())
        if (blockDepth == 0) {
            declaredTypeNames += ktTraitName
            declaredTraitsByName[ktTraitName] = i
        }
        val defaultlessAssocs = mutableListOf<String>()
        val resultFnNames = mutableSetOf<String>()
        for (item in i.items) {
            if (item is TraitItem.AssocType) {
                val assocName = rustTypeIdentToKotlin(item.ident.toString())
                val defaultTy = item.default?.type
                if (defaultTy != null) {
                    if (assocName !in associatedTypeDecls) {
                        associatedTypeDecls[assocName] = AssocTypeDecl(defaultTy, i.generics)
                    }
                } else {
                    defaultlessAssocs += assocName
                }
            } else if (item is TraitItem.Fn && !isDocOnlyCfgAttribute(item.attrs)) {
                val fnName = rustFunctionIdentToKotlin(item.sig.ident.toString())
                if (isSynResultReturnType(item.sig.output)) {
                    resultFnNames += fnName
                }
            }
        }
        if (defaultlessAssocs.isNotEmpty() && blockDepth == 0) {
            traitDefaultlessAssocTypes[ktTraitName] = defaultlessAssocs.distinct()
        }
        if (resultFnNames.isNotEmpty() && blockDepth == 0) {
            traitResultMethods[ktTraitName] = resultFnNames
        }
        if (blockDepth == 0 && traitReferencesSelfOutsideReceiver(i)) {
            selfParameterizedTraits += ktTraitName
        }
        if (blockDepth == 0) {
            val superEntries =
                i.supertraits
                    .toList()
                    .mapNotNull { bound ->
                        val path =
                            (bound as? TypeParamBound.Trait)
                                ?.takeIf { it.maybe == null }
                                ?.path
                                ?: return@mapNotNull null
                        val lastSeg = path.segments.last() ?: return@mapNotNull null
                        val raw =
                            lastSeg.ident
                                .toString()
                                .removePrefix("r#")
                        if (raw in IGNORED_TRAIT_BOUNDS) return@mapNotNull null
                        val kt = rustTypeIdentToKotlin(raw)
                        if (kt in RUST_STD_IMPLICIT_SUPERTRAITS && kt !in declaredTypeNames) return@mapNotNull null
                        val boundAssocs =
                            (lastSeg.arguments as? PathArguments.AngleBracketed)
                                ?.args
                                ?.toList()
                                ?.filterIsInstance<GenericArgument.AssocTypeArg>()
                                ?.map { rustTypeIdentToKotlin(it.assoc.ident.toString()) }
                                ?.toSet()
                                .orEmpty()
                        kt to boundAssocs
                    }
            if (superEntries.isNotEmpty()) {
                traitSupertraits[ktTraitName] = superEntries.map { it.first }.distinct()
                traitSupertraitBindings[ktTraitName] = superEntries
            }
        }
        super.visitItemTrait(i)
    }

    override fun visitItemUnion(i: Item.Union) {
        val name = rustTypeIdentToKotlin(i.ident.toString())
        if (blockDepth == 0) {
            declaredTypeNames += name
            attachableTypeNames += name
            unionTypeNames += name
        }
        val names = mutableListOf<String>()
        val fieldTyMap = linkedMapOf<String, SynType>()
        val seen = hashSetOf<String>()
        val namedFields = i.fields.named.toList()
        for ((idx, f) in namedFields.withIndex()) {
            if (isDocOnlyCfgAttribute(f.attrs)) continue
            val fName = rustFieldIdentToKotlin(f.ident?.toString() ?: "field$idx")
            if (seen.add(fName)) {
                names += fName
                fieldTyMap[fName] = f.ty
                classifyStructFieldType(name, fName, f.ty)
            }
        }
        structFieldNamesByType[name] = names
        structFieldSynTypesByType[name] = fieldTyMap
        super.visitItemUnion(i)
    }

    override fun visitItemType(i: Item.ItemType) {
        val name = rustTypeIdentToKotlin(i.ident.toString())
        if (blockDepth > 0) {
            if (name !in associatedTypeDecls) {
                associatedTypeDecls[name] = AssocTypeDecl(i.ty, i.generics)
            }
        } else if (!isExternalIdentityTypeReexportAlias(i, selfCrateAliases, localInlineModuleNames)) {
            declaredTypeNames += name
            recordTypeDefaultArgs(name, i.generics)
            if (name !in typeAliasTargets) {
                typeAliasTargets[name] = i.ty
            }
        }
        super.visitItemType(i)
    }

    override fun visitItemMod(i: Item.Mod) {
        val rawMod = i.ident.toString().removePrefix("r#")
        if (blockDepth == 0) {
            if (i.content is ModContent.Inline) {
                localInlineModuleNames += rawMod
            } else {
                localExternalModuleNames += rawMod
            }
        }
        val pushedMod = blockDepth == 0 && i.content is ModContent.Inline
        if (pushedMod) {
            inlineModStack.addLast(rawMod)
        }
        try {
            super.visitItemMod(i)
        } finally {
            if (pushedMod && inlineModStack.isNotEmpty()) {
                inlineModStack.removeLast()
            }
        }
    }

    override fun visitItemImpl(i: Item.Impl) {
        val ownerName = extractInspectorOwnerName(i.selfType, i.generics)
        val implHasTypeParams =
            i.generics.params
                .toList()
                .any { it is GenericParam.TypeParam }
        val implConsts =
            i.generics.params
                .toList()
                .mapNotNull {
                    (it as? GenericParam.ConstParam)
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                        ?.let(::rustValueIdentToKotlin)
                }
        for (implItem in i.items) {
            if (implItem is ImplItem.AssocType) {
                val assocName = rustTypeIdentToKotlin(implItem.ident.toString())
                if (ownerName != null) {
                    val assocMap = ownerAssocTypes.getOrPut(ownerName) { linkedMapOf() }
                    val assocKey = implItem.ident.toString()
                    if (assocKey !in assocMap) {
                        assocMap[assocKey] = implItem.ty
                    }
                }
                val traitName =
                    i.traitPath
                        ?.path
                        ?.segments
                        ?.last()
                        ?.ident
                        ?.toString()
                // IntoIterator's implementation-specific iterator is already
                // lowered on its owning class. It must not shadow an imported
                // IntoIter type with a package-global alias from another owner.
                val isBuiltinAssocType =
                    (traitName == "IntoIterator" && assocName == "IntoIter") ||
                        (traitName in setOf("Iterator", "DoubleEndedIterator") && assocName == "Item") ||
                        (traitName in setOf("Deref", "DerefMut") && assocName == "Target") ||
                        (traitName in setOf("Fn", "FnMut", "FnOnce", "Future", "Index", "IndexMut") && assocName == "Output") ||
                        (traitName in setOf("TryFrom", "TryInto", "FromStr") && (assocName == "Err" || assocName == "Error"))
                if (isBuiltinAssocType) continue
                if (assocName !in associatedTypeDecls) {
                    associatedTypeDecls[assocName] = AssocTypeDecl(implItem.ty, i.generics)
                }
            } else if (implItem is ImplItem.Fn && ownerName != null) {
                if (isDocOnlyCfgAttribute(implItem.attrs)) continue
                val fnKt = rustFunctionIdentToKotlin(implItem.sig.ident.toString())
                val valueArity = implItem.sig.inputs.count { it is FnArg.Typed }
                val fnConsts =
                    implItem.sig.generics.params
                        .toList()
                        .mapNotNull {
                            (it as? GenericParam.ConstParam)
                                ?.ident
                                ?.toString()
                                ?.removePrefix("r#")
                                ?.let(::rustValueIdentToKotlin)
                        }
                if (implItem.sig.inputs.firstOrNull() is FnArg.Receiver) {
                    instanceMethodsByType.getOrPut(ownerName) { linkedSetOf() }.add(fnKt)
                    if (fnConsts.isNotEmpty() || implConsts.isNotEmpty()) {
                        val spec = QuadImplConstSpec(ownerName, fnKt, valueArity, fnConsts, implConsts)
                        pendingImplMethodConsts += spec
                        pendingImplChecks += i to spec
                    }
                } else {
                    companionMethodsByType.getOrPut(ownerName) { linkedSetOf() }.add(fnKt)
                    val combinedConsts = (implConsts + fnConsts).distinct()
                    if (combinedConsts.isNotEmpty()) {
                        val key = "$ownerName.$fnKt"
                        if (key !in fnConstParamNames) {
                            fnConstParamNames[key] = valueArity to combinedConsts
                        }
                    }
                }
                val fnHasTypeParams =
                    implItem.sig.generics.params
                        .toList()
                        .any { it is GenericParam.TypeParam }
                if (!implHasTypeParams && !fnHasTypeParams) {
                    val out = implItem.sig.output
                    if (out is ReturnType.TypeReturn) {
                        val key = ownerName to fnKt
                        if (key !in implMethodReturnSynTypes) {
                            implMethodReturnSynTypes[key] = out.ty
                        }
                    }
                }
            }
        }
        super.visitItemImpl(i)
    }

    private fun extractInspectorOwnerName(selfTy: SynType, generics: Generics): String? {
        val typeParams =
            generics.params
                .toList()
                .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
                .toSet()
        return when (val u = unwrapSynType(selfTy)) {
            is SynType.Reference -> extractInspectorOwnerName(u.elem, generics)
            is SynType.Path -> {
                if (u.qself != null) return null
                val segs = u.path.segments.toList()
                if (segs.size != 1) return null
                val name = rustTypeIdentToKotlin(segs[0].ident.toString())
                if (name in typeParams) null else name
            }
            else -> null
        }
    }
}

internal fun synTypeReferencesDirectSelf(ty: SynType): Boolean {
    var found = false
    val visitor =
        object : Visit() {
            override fun visitPath(p: Path) {
                val segs = p.segments.toList()
                if (segs.size == 1 && segs[0].ident.toString() == "Self") {
                    found = true
                    return
                }
                super.visitPath(p)
            }
        }
    visitor.visitType(ty)
    return found
}

internal fun traitReferencesSelfOutsideReceiver(traitItem: Item.Trait): Boolean {
    for (member in traitItem.items) {
        if (traitItemHasDocOnlyCfg(member)) continue
        when (member) {
            is TraitItem.Const -> {
                if (synTypeReferencesDirectSelf(member.ty)) return true
            }
            is TraitItem.Fn -> {
                for (arg in member.sig.inputs.toList()) {
                    if (arg is FnArg.Typed && synTypeReferencesDirectSelf(arg.patType.ty)) {
                        return true
                    }
                }
                val retTy =
                    when (val out = member.sig.output) {
                        is ReturnType.Default -> null
                        is ReturnType.TypeReturn -> out.ty
                    }
                if (retTy != null && synTypeReferencesDirectSelf(retTy)) return true
            }
            else -> {}
        }
    }
    return false
}

internal fun synTypeReferencesIdent(ty: SynType, target: String): Boolean {
    var found = false
    val visitor =
        object : Visit() {
            override fun visitPath(p: Path) {
                for (seg in p.segments.toList()) {
                    if (seg.ident.toString().removePrefix("r#") == target) {
                        found = true
                        return
                    }
                }
                super.visitPath(p)
            }
        }
    visitor.visitType(ty)
    return found
}

internal fun fieldsReferenceIdent(fields: Fields, target: String): Boolean =
    when (fields) {
        is Fields.Named -> {
            val named = fields.fields.named.toList()
            named.any { synTypeReferencesIdent(it.ty, target) }
        }
        is Fields.Unnamed -> {
            val unnamed = fields.fields.unnamed.toList()
            unnamed.any { synTypeReferencesIdent(it.ty, target) }
        }
        is Fields.Unit -> false
    }

/**
 * `core::mem::needs_drop` is an optimization hint: core/src/mem/mod.rs explicitly
 * permits conservative true results. Only prove false for built-in non-owning
 * types; unresolved types must never suppress destruction.
 */
internal fun conservativeNeedsDropCall(
    expr: Expr.Call,
    localTypes: Set<String>,
    localFunctions: Set<String>,
): Boolean? {
    if (!expr.args.isEmpty()) return null
    val function = expr.func as? Expr.Path ?: return null
    if (function.qself != null) return null
    val segments = function.path.segments.toList()
    val names = segments.map { it.ident.toString().removePrefix("r#") }
    if (names != listOf("core", "mem", "needs_drop") &&
        names != listOf("std", "mem", "needs_drop") &&
        !(names == listOf("needs_drop") && "needs_drop" !in localFunctions)
    ) {
        return null
    }
    val args = (segments.last().arguments as? PathArguments.AngleBracketed)?.args?.toList() ?: return null
    val type = (args.singleOrNull() as? GenericArgument.TypeArg)?.type ?: return null
    fun cannotNeedDrop(ty: SynType): Boolean =
        when (val unwrapped = unwrapSynType(ty)) {
            is SynType.Reference, is SynType.Ptr, is SynType.FnPtr -> true
            is SynType.Tuple -> unwrapped.elems.toList().all { cannotNeedDrop(it) }
            is SynType.Array -> cannotNeedDrop(unwrapped.elem)
            is SynType.Path -> {
                val path = unwrapped.path.segments.toList()
                val name = path.singleOrNull()?.ident?.toString()
                val primitiveTypes =
                    setOf(
                        "bool",
                        "char",
                        "u8",
                        "u16",
                        "u32",
                        "u64",
                        "u128",
                        "usize",
                        "i8",
                        "i16",
                        "i32",
                        "i64",
                        "i128",
                        "isize",
                        "f32",
                        "f64",
                    )
                unwrapped.qself == null && name !in localTypes && name in primitiveTypes
            }
            else -> false
        }
    return !cannotNeedDrop(type)
}

internal fun validateSupportedRustSemantics(
    file: File,
    allowStdlibDestructors: Boolean = false,
) {
    if (allowStdlibDestructors) return
    val unsupportedDestructorSymbols = linkedSetOf<String>()
    val symbols = CrateSymbolInspector().also { it.visitFile(file) }
    val localTypes = symbols.declaredTypeNames
    // A marker impl has no executable destructor-suppression behavior. Core's
    // cell.rs mentions ManuallyDrop only in such an impl and its import. Keep
    // rejecting value/type uses rather than mistaking a name import for a drop.
    val markerTraits =
        file.items
            .filterIsInstance<Item.Trait>()
            .filter { trait ->
                trait.items.isEmpty() && trait.attrs.any { (it.meta as? Meta.PathMeta)?.path?.isIdent("marker") == true }
            }.map { it.ident.toString().removePrefix("r#") }
            .toSet()

    val visitor =
        object : Visit() {
            override fun visitItem(i: Item) {
                if (!itemHasDocOnlyCfg(i)) super.visitItem(i)
            }

            override fun visitExpr(e: Expr) {
                if (e is Expr.Call && conservativeNeedsDropCall(e, localTypes, symbols.declaredTopLevelFnRawNames) != null) return
                super.visitExpr(e)
            }

            override fun visitItemStruct(i: Item.Struct) {
                if (fieldsReferenceIdent(i.fields, "ManuallyDrop")) {
                    unsupportedDestructorSymbols += "ManuallyDrop"
                }
                super.visitItemStruct(i)
            }

            override fun visitVariant(v: Variant) {
                if (fieldsReferenceIdent(v.fields, "ManuallyDrop")) {
                    unsupportedDestructorSymbols += "ManuallyDrop"
                }
                super.visitVariant(v)
            }

            override fun visitItemUnion(i: Item.Union) {
                val unionFields = i.fields.named.toList()
                if (unionFields.any { synTypeReferencesIdent(it.ty, "ManuallyDrop") }) {
                    unsupportedDestructorSymbols += "ManuallyDrop"
                }
                super.visitItemUnion(i)
            }

            override fun visitItemImpl(i: Item.Impl) {
                val traitName =
                    i.traitPath
                        ?.path
                        ?.segments
                        ?.last()
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                if (i.items.isEmpty() && traitName in markerTraits &&
                    i.traitPath?.path?.isIdent(traitName.orEmpty()) == true &&
                    synTypeReferencesIdent(i.selfType, "ManuallyDrop")
                ) {
                    // Visit bounds and the trait itself: only the impl target
                    // is a compile-time assertion, not a stored ManuallyDrop.
                    visitGenerics(i.generics)
                    i.traitPath?.path?.let { visitPath(it) }
                    return
                }
                super.visitItemImpl(i)
            }

            override fun visitPath(p: Path) {
                val segments = p.segments.toList().map { it.ident.toString().removePrefix("r#") }
                for (name in segments) {
                    if (name == "needs_drop" || name == "ManuallyDrop") {
                        unsupportedDestructorSymbols += name
                    }
                }
                if (segments.size >= 3 && segments[0] in setOf("core", "std") &&
                    segments[1] == "cell" && segments[2] == "UnsafeCell" && "UnsafeCell" in localTypes
                ) {
                    throw IllegalArgumentException(
                        "Unsupported shadowed standard-library cell: ${segments.joinToString("::")} " +
                            "must not resolve to the same-file UnsafeCell wrapper",
                    )
                }
                super.visitPath(p)
            }
        }
    visitor.visitFile(file)
    if (unsupportedDestructorSymbols.isNotEmpty()) {
        throw IllegalArgumentException(
            "Unsupported Rust destructor-suppression semantics (${unsupportedDestructorSymbols.joinToString(", ")}): " +
                "Kotlin has no deterministic Drop or ManuallyDrop/needs_drop runtime equivalent",
        )
    }
}

internal fun describeUnemittedSubstantiveItems(
    originalFile: File,
    expandedFile: File = originalFile,
): List<String> {
    val labels = mutableListOf<String>()
    fun collectMacroRulesDefinitions(ast: File) {
        for (item in ast.items) {
            if (itemHasDocOnlyCfg(item)) continue
            when (item) {
                is Item.Macro -> {
                    if (item.mac.path.isIdent("macro_rules")) {
                        val macroName = item.ident?.toString()?.removePrefix("r#") ?: "macro_rules"
                        labels += "$macroName!"
                    }
                }
                is Item.Mod -> {
                    val content = item.content
                    if (content is ModContent.Inline) {
                        collectMacroRulesDefinitions(File(shebang = null, attrs = emptyList(), items = content.items))
                    }
                }
                else -> {}
            }
        }
    }
    fun collectUnexpandedMacroInvocations(ast: File) {
        for (item in ast.items) {
            if (itemHasDocOnlyCfg(item)) continue
            when (item) {
                is Item.Macro -> {
                    if (!item.mac.path.isIdent("macro_rules")) {
                        val macPath = item.mac.path
                        val pathSegs = macPath.segments.toList()
                        val pathText = pathSegs.joinToString("::") { it.ident.toString() }
                        if (pathText != "windows_link::link" && pathText != "include") {
                            labels += "$pathText!"
                        }
                    }
                }
                is Item.Mod -> {
                    val content = item.content
                    if (content is ModContent.Inline) {
                        collectUnexpandedMacroInvocations(File(shebang = null, attrs = emptyList(), items = content.items))
                    }
                }
                else -> {}
            }
        }
    }
    collectMacroRulesDefinitions(originalFile)
    collectUnexpandedMacroInvocations(expandedFile)
    return labels.distinct()
}

// A type alias named Option/Result is not itself a newly declared enum.
// Inspect actual variant ownership rather than the shared type-name set.
internal fun KotlinJ2KEmitter.hasDeclaredOptionEnum(): Boolean =
    ("Option" to "Some") in inspector.tupleEnumVariantArity

internal fun KotlinJ2KEmitter.hasDeclaredResultEnum(): Boolean =
    ("Result" to "Ok") in inspector.tupleEnumVariantArity &&
        ("Result" to "Err") in inspector.tupleEnumVariantArity

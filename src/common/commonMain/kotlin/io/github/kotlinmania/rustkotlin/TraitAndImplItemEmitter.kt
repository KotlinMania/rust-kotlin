package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Fields
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.WhereClause
import io.github.kotlinmania.syn.WherePredicate
import io.github.kotlinmania.syn.gen.Visit
import io.github.kotlinmania.syn.span

internal fun KotlinJ2KEmitter.extractFnTraitSubstitutions(
    params: List<GenericParam>,
    whereClause: WhereClause?,
    selfTypeName: String?,
    intoSubstitutedOut: MutableSet<String>? = null,
): Map<String, String> {
    val fnTypeParams =
        params
            .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
            .toSet()
    if (fnTypeParams.isEmpty()) return emptyMap()
    val result = linkedMapOf<String, String>()
    val allBoundsByTp = linkedMapOf<String, MutableList<TypeParamBound.Trait>>()
    fun inspectBounds(tpName: String, bounds: List<TypeParamBound>) {
        for (b in bounds) {
            if (b is TypeParamBound.Trait && b.maybe == null) {
                val lastSeg = b.path.segments.last() ?: continue
                val rawName = lastSeg.ident.toString()
                if ((rawName == "Fn" || rawName == "FnMut" || rawName == "FnOnce") &&
                    lastSeg.arguments is PathArguments.Parenthesized
                ) {
                    result[tpName] = renderPathAsType(b.path, selfTypeName)
                } else if (rawName !in IGNORED_TRAIT_BOUNDS) {
                    allBoundsByTp.getOrPut(tpName) { mutableListOf() }.add(b)
                }
            }
        }
    }
    for (gp in params) {
        if (gp is GenericParam.TypeParam) {
            inspectBounds(rustTypeIdentToKotlin(gp.ident.toString()), gp.bounds.toList())
        }
    }
    for (pred in whereClause?.predicates?.toList().orEmpty()) {
        if (pred is WherePredicate.TypePredicate) {
            val tpName = renderType(pred.boundedTy, selfTypeName)
            if (tpName in fnTypeParams) inspectBounds(tpName, pred.bounds.toList())
        }
    }
    val convertibleTraits = setOf("Into", "AsRef", "Borrow").filterTo(hashSetOf()) { it !in inspector.declaredTypeNames }
    if (convertibleTraits.isNotEmpty()) {
        for ((tpName, traitBounds) in allBoundsByTp) {
            if (tpName in result || traitBounds.size != 1) continue
            val lastSeg = traitBounds[0].path.segments.last() ?: continue
            if (lastSeg.ident.toString() !in convertibleTraits) continue
            val angleArgs = (lastSeg.arguments as? PathArguments.AngleBracketed)?.args?.toList()
            val typeArgs = angleArgs?.filterIsInstance<GenericArgument.TypeArg>().orEmpty()
            if (typeArgs.size != 1) continue
            val targetRendered = renderType(typeArgs[0].type, selfTypeName).trim()
            if (targetRendered.isNotEmpty() && fnTypeParams.none { containsWholeIdentifier(targetRendered, it) }) {
                result[tpName] = targetRendered
                intoSubstitutedOut?.add(tpName)
            }
        }
    }
    return result
}

internal fun KotlinJ2KEmitter.extractDirectIteratorItemRenderedType(
    attachedImpls: List<Item.Impl>,
    traitRawName: String,
    selfTypeWithGenerics: String,
    requireOwnedSelf: Boolean,
): String? {
    for (impl in attachedImpls) {
        if (isDocOnlyCfgAttribute(impl.attrs)) continue
        if (requireOwnedSelf && unwrapSynType(impl.selfType) is SynType.Reference) continue
        val rawTrait = impl.traitPath?.path?.lastIdentText() ?: continue
        if (rawTrait != traitRawName) continue
        for (sub in impl.items) {
            if (sub is ImplItem.AssocType && sub.ident.toString() == "Item") {
                return renderType(sub.ty, selfTypeWithGenerics)
            }
        }
    }
    return null
}

internal data class IteratorSupertraitSpec(
    val itemType: String,
    val hasDoubleEnded: Boolean,
    val hasExactSize: Boolean,
)

internal fun KotlinJ2KEmitter.extractIteratorSupertraitSpec(
    bounds: List<TypeParamBound>,
    selfTypeName: String?,
): IteratorSupertraitSpec? {
    var sawIteratorFamily = false
    var hasDoubleEnded = false
    var hasExactSize = false
    var itemRendered: String? = null
    for (b in bounds) {
        if (b !is TypeParamBound.Trait || b.maybe != null) continue
        val lastSeg = b.path.segments.last() ?: continue
        val rawName = lastSeg.ident.toString()
        if (rawName in setOf("Iterator", "DoubleEndedIterator", "ExactSizeIterator")) {
            sawIteratorFamily = true
            if (rawName == "DoubleEndedIterator") hasDoubleEnded = true
            if (rawName == "ExactSizeIterator") hasExactSize = true
            val angle = lastSeg.arguments as? PathArguments.AngleBracketed
            if (angle != null && itemRendered == null) {
                for (ga in angle.args.toList()) {
                    if (ga is GenericArgument.AssocTypeArg && ga.assoc.ident.toString() == "Item") {
                        itemRendered = renderType(ga.assoc.ty, selfTypeName)
                    }
                }
            }
        }
    }
    if (!sawIteratorFamily || itemRendered == null) return null
    return IteratorSupertraitSpec(itemRendered, hasDoubleEnded, hasExactSize)
}

internal fun KotlinJ2KEmitter.collectLocalSupertraitMethodNames(
    superTraits: List<String>,
    includeReceiverlessMethods: Boolean = false,
    ownerIsPublic: Boolean = true,
): Set<String> {
    val names = linkedSetOf<String>()
    val visitedTraits = hashSetOf<String>()
    fun visitTraitByBaseName(baseName: String, traitPath: Path? = null) {
        if (!visitedTraits.add(baseName)) return
        val traitDecl = lookupDeclaredTraitAst(baseName, traitPath) ?: return
        if (ownerIsPublic && renderVisibility(traitDecl.vis) != "public ") return
        for (tItem in traitDecl.items) {
            if (traitItemHasDocOnlyCfg(tItem)) continue
            if (tItem is TraitItem.Fn && (includeReceiverlessMethods || tItem.sig.inputs.firstOrNull() is FnArg.Receiver)) {
                names += rustFunctionIdentToKotlin(tItem.sig.ident.toString())
            }
        }
        extractIteratorSupertraitSpec(traitDecl.supertraits.toList(), null)?.let { iterSpec ->
            names += "next"
            if (iterSpec.hasDoubleEnded) names += "nextBack"
            if (iterSpec.hasExactSize) names += "len"
        }
        for (bound in traitDecl.supertraits) {
            val boundPath = (bound as? TypeParamBound.Trait)?.path ?: continue
            if (isImplicitStdlibSupertraitPath(boundPath)) continue
            val boundBase = boundPath.lastKotlinTypeName() ?: continue
            visitTraitByBaseName(boundBase, boundPath)
        }
    }
    for (st in superTraits) visitTraitByBaseName(st.substringBefore('<').trim())
    return names
}

private fun KotlinJ2KEmitter.collectLocalTraitAndSupertraitFnRawNames(traitDecl: Item.Trait): Set<String> {
    val rawNames = linkedSetOf<String>()
    val visited = hashSetOf<String>()
    fun visitDecl(decl: Item.Trait) {
        if (!visited.add(rustTypeIdentToKotlin(decl.ident.toString()))) return
        for (tItem in decl.items) {
            if (!traitItemHasDocOnlyCfg(tItem) && tItem is TraitItem.Fn) {
                rawNames += tItem.sig.cleanFnName()
            }
        }
        for (bound in decl.supertraits) {
            val boundPath = (bound as? TypeParamBound.Trait)?.path ?: continue
            val boundKt = boundPath.lastKotlinTypeName() ?: continue
            lookupDeclaredTraitAst(boundKt, boundPath)?.let(::visitDecl)
        }
    }
    visitDecl(traitDecl)
    return rawNames
}

private val CORE_OPS_OUTPUT_TRAIT_NAMES: Set<String>
    get() = YamlRuleCatalogs.TypeAndMethodPatterns.coreOpsOutputAssocTraits

internal fun KotlinJ2KEmitter.renderDistinctSuperTraits(
    attachedImpls: List<Item.Impl>,
    selfTypeWithGenerics: String,
    ownerIsPublic: Boolean = true,
): List<String> {
    val seenInterfaces = hashSetOf<String>()
    val result = mutableListOf<String>()
    val selfBase = selfTypeWithGenerics.substringBefore('<').trim()
    for (impl in attachedImpls) {
        if (isDocOnlyCfgAttribute(impl.attrs)) continue
        val path = impl.traitPath?.path ?: continue
        val rawTrait = path.lastIdentText() ?: continue
        if (rawTrait in IGNORED_TRAIT_BOUNDS || isImplicitStdlibSupertraitPath(path)) continue
        val rendered =
            withBoundedTypeScope(boundedType = selfTypeWithGenerics, suppressAssocs = true) {
                renderPathAsType(path, selfTypeName = selfTypeWithGenerics)
            }
        val baseIface = rendered.substringBefore('<').trim()
        if (baseIface.isEmpty() || baseIface == selfBase || rendered.contains("->")) continue
        if (ownerIsPublic) {
            val traitDecl = lookupDeclaredTraitAst(baseIface, path)
            if (traitDecl != null && renderVisibility(traitDecl.vis) != "public ") continue
        }
        if (!seenInterfaces.add(baseIface)) continue
        val assocNames =
            lookupTraitDefaultlessAssocTypes(baseIface, path).ifEmpty {
                if (baseIface !in inspector.declaredTypeNames && baseIface in CORE_OPS_OUTPUT_TRAIT_NAMES) listOf("Output") else emptyList()
            }
        result +=
            if (assocNames.isNotEmpty()) {
                val implAssocByName =
                    impl.items.filterIsInstance<ImplItem.AssocType>().associate {
                        rustTypeIdentToKotlin(it.ident.toString()) to renderType(it.ty, selfTypeWithGenerics)
                    }
                val assocArgs = assocNames.joinToString(", ") { implAssocByName[it] ?: "Any" }
                if (rendered.endsWith(">")) "${rendered.dropLast(1)}, $assocArgs>" else "$rendered<$assocArgs>"
            } else {
                rendered
            }
    }
    return result
}

internal fun KotlinJ2KEmitter.structImplsMutateField(attachedImpls: List<Item.Impl>, rawFieldIdent: String): Boolean {
    val visitor =
        object : SynPathPredicateVisitor() {
            override fun visitExprMethodCall(e: Expr.MethodCall) {
                if (e.method.toString() in MUTATING_VEC_METHODS) {
                    val recv = e.receiver
                    if (recv is Expr.Field &&
                        recv.base is Expr.Path &&
                        (recv.base as Expr.Path).path.isIdent("self") &&
                        recv.member is Member.Named &&
                        (recv.member as Member.Named).ident.cleanIdent() == rawFieldIdent
                    ) {
                        markMatched()
                        return
                    }
                }
                super.visitExprMethodCall(e)
            }
        }
    for (impl in attachedImpls) {
        visitor.visitItemImpl(impl)
        if (visitor.matched) return true
    }
    return false
}

internal fun KotlinJ2KEmitter.buildTupleStructAliasProperties(
    fields: Fields,
    selfTypeName: String,
    memberIndent: String,
): List<String> {
    if (fields !is Fields.Unnamed) return emptyList()
    val list = fields.fields.unnamed.toList()
    if (list.size != 1) return emptyList()
    val fType = renderType(list[0].ty, selfTypeName)
    return listOf(
        "${memberIndent}public var field0: $fType\n" +
            "$memberIndent    get() = value\n" +
            "$memberIndent    set(v) {\n" +
            "$memberIndent        value = v\n" +
            "$memberIndent    }",
    )
}

/**
 * Shared `ImplItem` dispatcher across both attached class `impl` blocks and standalone top-level `impl` blocks.
 */
private class ImplBlockMemberEmitter(
    private val emitter: KotlinJ2KEmitter,
    private val selfTypeName: String,
    private val ownerGenerics: Generics,
    private val implGenerics: Generics,
    private val indent: String,
    private val isLocalItem: Boolean,
    private val asTopLevelExtension: Boolean,
    private val routeStaticsAndConstsToCompanion: Boolean,
    private val instanceMembers: MutableList<String>,
    private val instanceSignatures: MutableMap<String, Int>,
    private val companionMembers: MutableList<String> = instanceMembers,
    private val companionSignatures: MutableMap<String, Int> = instanceSignatures,
) {
    fun emitItems(items: Iterable<ImplItem>) {
        val companionIndent = if (routeStaticsAndConstsToCompanion) "$indent    " else indent
        for (implItem in items) {
            if (implItemHasDocOnlyCfg(implItem)) continue
            when (implItem) {
                is ImplItem.Const -> {
                    val targetList = if (routeStaticsAndConstsToCompanion) companionMembers else instanceMembers
                    val targetSigs = if (routeStaticsAndConstsToCompanion) companionSignatures else instanceSignatures
                    targetList += emitter.renderImplItemConst(implItem, companionIndent, selfTypeName, targetSigs, isLocalItem = isLocalItem)
                }
                is ImplItem.Fn -> {
                    val hasReceiver = implItem.sig.inputs.firstOrNull() is FnArg.Receiver
                    val toCompanion = !hasReceiver && routeStaticsAndConstsToCompanion
                    val rendered =
                        emitter.renderImplItemFn(
                            item = implItem,
                            indent = if (toCompanion) companionIndent else indent,
                            selfTypeName = selfTypeName,
                            ownerGenerics = ownerGenerics,
                            implGenerics = implGenerics,
                            scopeSignatures = if (toCompanion) companionSignatures else instanceSignatures,
                            asTopLevelExtension = asTopLevelExtension,
                            isCompanionMember = toCompanion,
                            isLocalItem = isLocalItem,
                        )
                    if (rendered.isNotBlank()) {
                        if (toCompanion) companionMembers += rendered else instanceMembers += rendered
                    }
                }
                is ImplItem.Macro -> {
                    instanceMembers +=
                        emitter.renderImplOrTraitMacroMembers(
                            mac = implItem.mac,
                            indent = indent,
                            selfTypeName = selfTypeName,
                            ownerGenerics = ownerGenerics,
                            scopeSignatures = instanceSignatures,
                            asTopLevelExtension = asTopLevelExtension,
                            isInterface = false,
                            isLocalItem = isLocalItem,
                        )
                }
                is ImplItem.AssocType, is ImplItem.Verbatim -> {}
            }
        }
    }
}

internal fun KotlinJ2KEmitter.renderAttachedImplMembers(
    typeName: String,
    ownerGenerics: Generics,
    attachedImpls: List<Item.Impl>,
    indent: String,
    extraProperties: List<String> = emptyList(),
    extraMembers: List<String> = emptyList(),
    extraCompanionMembers: List<String> = emptyList(),
    isLocalClass: Boolean = false,
    structFieldNames: Set<String> = emptySet(),
    isObjectOwner: Boolean = false,
    emitEmptyCompanion: Boolean = false,
): String {
    val prevObjectOwner = currentAttachedImplIsObjectOwner
    currentAttachedImplIsObjectOwner = isObjectOwner
    try {
        val instanceMembers = mutableListOf<String>()
        val companionMembers = mutableListOf<String>()
        val instanceSignatures = linkedMapOf<String, Int>()
        val companionSignatures = linkedMapOf<String, Int>()
        val routeToCompanion = !isLocalClass && !isObjectOwner

        if (headerContext.rawAbiMode || headerContext.finalClasses) {
            for (fName in structFieldNames) instanceSignatures["$fName()"] = 1
        }
        instanceMembers.addAll(extraProperties)
        instanceMembers.addAll(extraMembers)
        if (routeToCompanion) {
            companionMembers.addAll(extraCompanionMembers)
        } else if (isObjectOwner && extraCompanionMembers.isNotEmpty()) {
            instanceMembers.addAll(extraCompanionMembers)
        }
        if (extraMembers.any { it.contains("fun hashCode(): Int") }) instanceSignatures["hashCode()"] = 1
        if (extraMembers.any { it.contains("fun equals(other: Any?): Boolean") }) instanceSignatures["equals(Any?)"] = 1

        val ownerFallbackAssocTypes =
            buildMap<String, SynType> {
                for (impl in attachedImpls) {
                    if (isDocOnlyCfgAttribute(impl.attrs)) continue
                    for (implItem in impl.items) {
                        if (implItem is ImplItem.AssocType) {
                            val assocName = implItem.ident.toString()
                            if (!containsKey(assocName)) put(assocName, implItem.ty)
                        }
                    }
                }
            }

        val ownerBaseName = typeName.substringBefore('<').trim()
        for (impl in attachedImpls) {
            if (isDocOnlyCfgAttribute(impl.attrs)) continue
            val implComments = (renderLeadingTrivia(impl, indent) + renderKDocFromAttributes(impl.attrs, indent)).trimEnd()
            val instStart = instanceMembers.size
            val compStart = companionMembers.size
            val prevAssocTypes = currentImplAssocTypes
            val prevAttachedTraitPath = currentAttachedImplTraitPath
            val prevAttachedIsRefSelf = currentAttachedImplIsRefSelf
            val prevSubst = substitutedTypeParamTypes
            currentAttachedImplTraitPath = impl.traitPath?.path
            currentAttachedImplIsRefSelf = unwrapSynType(impl.selfType) is SynType.Reference
            val selfPath = (unwrapSynType(impl.selfType) as? SynType.Path)?.takeIf { it.qself == null && it.path.segments.len() == 1 }?.path
            val selfPathParam = selfPath?.firstKotlinTypeName()
            if (selfPathParam != null && selfPathParam != ownerBaseName && selfPathParam in collectGenericTypeParamNames(impl.generics)) {
                substitutedTypeParamTypes = prevSubst + (selfPathParam to typeName)
            }
            currentImplAssocTypes =
                buildMap {
                    putAll(prevAssocTypes)
                    putAll(ownerFallbackAssocTypes)
                    for (implItem in impl.items) {
                        if (implItem is ImplItem.AssocType) put(implItem.ident.toString(), implItem.ty)
                    }
                }
            try {
                ImplBlockMemberEmitter(
                    emitter = this,
                    selfTypeName = typeName,
                    ownerGenerics = ownerGenerics,
                    implGenerics = impl.generics,
                    indent = indent,
                    isLocalItem = false,
                    asTopLevelExtension = false,
                    routeStaticsAndConstsToCompanion = routeToCompanion,
                    instanceMembers = instanceMembers,
                    instanceSignatures = instanceSignatures,
                    companionMembers = companionMembers,
                    companionSignatures = companionSignatures,
                ).emitItems(impl.items)
            } finally {
                currentImplAssocTypes = prevAssocTypes
                currentAttachedImplTraitPath = prevAttachedTraitPath
                currentAttachedImplIsRefSelf = prevAttachedIsRefSelf
                substitutedTypeParamTypes = prevSubst
            }
            if (implComments.isNotBlank()) {
                when {
                    instanceMembers.size > instStart -> instanceMembers[instStart] = "$implComments\n${instanceMembers[instStart]}"
                    companionMembers.size > compStart -> {
                        val reindented = implComments.lines().joinToString("\n") { if (it.isBlank()) it else "    $it" }
                        companionMembers[compStart] = "$reindented\n${companionMembers[compStart]}"
                    }
                    else -> instanceMembers += implComments
                }
            }
        }

        if (companionMembers.isNotEmpty()) {
            instanceMembers += "${indent}public companion object {\n${companionMembers.joinToString("\n\n")}\n$indent}"
        } else if (emitEmptyCompanion && routeToCompanion) {
            instanceMembers += "${indent}public companion object"
        }
        return instanceMembers.filter { it.isNotBlank() }.joinToString("\n\n")
    } finally {
        currentAttachedImplIsObjectOwner = prevObjectOwner
    }
}

private fun matchesSelfAssocTypePath(t: SynType.Path, rawAssocName: String): Boolean {
    val segs = t.path.segments.toList()
    val q = t.qself
    if (q != null) {
        val qPath = (q.ty as? SynType.Path)?.path
        val qSeg = qPath?.segments?.toList()?.singleOrNull()
        val targetSeg = segs.getOrNull(q.position)
        return qSeg?.ident?.toString() == "Self" && targetSeg?.ident?.toString()?.removePrefix("r#") == rawAssocName
    }
    if (segs.size == 2 && segs[0].ident.toString() == "Self" && segs[1].ident.toString().removePrefix("r#") == rawAssocName) {
        return true
    }
    return segs.size == 1 && segs[0].ident.toString().removePrefix("r#") == rawAssocName
}

private class AssocTypeMentionVisitor(
    private val rawAssocName: String,
) : Visit() {
    var mentioned: Boolean = false

    override fun visitTypePath(t: SynType.Path) {
        if (matchesSelfAssocTypePath(t, rawAssocName)) mentioned = true
        super.visitTypePath(t)
    }
}

private fun synTypeMentionsAssoc(ty: SynType, rawAssocName: String): Boolean =
    AssocTypeMentionVisitor(rawAssocName).also { it.visitType(ty) }.mentioned

private val COVARIANT_STD_ASSOC_WRAPPERS = setOf("Option", "Result", "Vec", "Box", "Rc", "Arc", "Cow")

private fun synTypeAllowsOutPosition(ty: SynType, rawAssocName: String): Boolean {
    if (!synTypeMentionsAssoc(ty, rawAssocName)) return true
    return when (ty) {
        is SynType.Group -> synTypeAllowsOutPosition(ty.elem, rawAssocName)
        is SynType.Paren -> synTypeAllowsOutPosition(ty.elem, rawAssocName)
        is SynType.Reference -> ty.mutability == null && synTypeAllowsOutPosition(ty.elem, rawAssocName)
        is SynType.Tuple -> {
            val elems = ty.elems.toList()
            elems.size <= 3 && elems.all { synTypeAllowsOutPosition(it, rawAssocName) }
        }
        is SynType.FnPtr -> false
        is SynType.Path -> {
            val segs = ty.path.segments.toList()
            if (matchesSelfAssocTypePath(ty, rawAssocName) && (ty.qself == null || segs.size == 1)) {
                return true
            }
            if (ty.qself != null) return false
            val lastSeg = segs.lastOrNull() ?: return false
            if (lastSeg.ident.toString().removePrefix("r#") !in COVARIANT_STD_ASSOC_WRAPPERS) return false
            val args = (lastSeg.arguments as? PathArguments.AngleBracketed)?.args?.toList().orEmpty()
            args.all { ga ->
                when (ga) {
                    is GenericArgument.TypeArg -> synTypeAllowsOutPosition(ga.type, rawAssocName)
                    is GenericArgument.LifetimeArg -> true
                    else -> false
                }
            }
        }
        else -> false
    }
}

private fun isAssocTypeCovariantInTrait(item: Item.Trait, rawAssocName: String): Boolean {
    val checkVisitor = AssocTypeMentionVisitor(rawAssocName)
    for (bound in item.supertraits.toList()) checkVisitor.visitTypeParamBound(bound)
    item.generics.whereClause?.let(checkVisitor::visitWhereClause)
    if (checkVisitor.mentioned) return false

    for (tItem in item.items) {
        if (traitItemHasDocOnlyCfg(tItem)) continue
        when (tItem) {
            is TraitItem.AssocType -> {
                if (tItem.ident.toString().removePrefix("r#") != rawAssocName) {
                    for (b in tItem.bounds.toList()) checkVisitor.visitTypeParamBound(b)
                    tItem.generics.whereClause?.let(checkVisitor::visitWhereClause)
                    if (checkVisitor.mentioned) return false
                }
            }
            is TraitItem.Const -> if (!synTypeAllowsOutPosition(tItem.ty, rawAssocName)) return false
            is TraitItem.Fn -> {
                val fnInputs = tItem.sig.inputs.toList()
                if (fnInputs.any { it is FnArg.Typed && synTypeMentionsAssoc(it.patType.ty, rawAssocName) }) {
                    return false
                }
                val fnWhere = tItem.sig.generics.whereClause
                fnWhere?.let(checkVisitor::visitWhereClause)
                if (checkVisitor.mentioned) return false
                val retTy = extractReturnSynType(tItem.sig.output)
                if (retTy != null && !synTypeAllowsOutPosition(retTy, rawAssocName)) return false
            }
            else -> {}
        }
    }
    return true
}

private class ErasedDefaultBodyInspector(
    private val emitter: KotlinJ2KEmitter,
    private val traitName: String,
    private val instanceMethodRawNames: Set<String>,
) : Visit() {
    var mutatesDerefSelf: Boolean = false
    var returnsBareSelfOrUnaryOnSelf: Boolean = false
    var callsStaticSelfOrTraitFn: Boolean = false

    override fun visitExprAssign(e: Expr.Assign) {
        val lhs = emitter.unwrapParenOrGroupExpr(e.left)
        if (lhs is Expr.Unary && lhs.op is UnOp.Deref) {
            val inner = emitter.unwrapParenOrGroupExpr(lhs.expr) as? Expr.Path
            if (inner?.qself == null && inner?.path?.isIdent("self") == true) mutatesDerefSelf = true
        }
        super.visitExprAssign(e)
    }

    override fun visitExprUnary(e: Expr.Unary) {
        val inner = emitter.unwrapParenOrGroupExpr(e.expr) as? Expr.Path
        if (inner?.qself == null && inner?.path?.isIdent("self") == true && e.op !is UnOp.Deref) {
            returnsBareSelfOrUnaryOnSelf = true
        }
        super.visitExprUnary(e)
    }

    override fun visitExprCall(e: Expr.Call) {
        val fnPath = emitter.unwrapParenOrGroupExpr(e.func) as? Expr.Path
        if (fnPath != null && fnPath.qself == null) {
            val segs = fnPath.path.segments.toList()
            val head = fnPath.path.firstCleanIdent()
            val tail = fnPath.path.lastCleanIdent()
            val firstArg = e.args.toList().firstOrNull()
            val firstArgPath = firstArg?.let { emitter.unwrapParenOrGroupExpr(it) as? Expr.Path }
            val passesSelfFirst = firstArgPath?.qself == null && firstArgPath?.path?.isIdent("self") == true
            if (segs.size == 2 && head == "Self" && tail != null &&
                tail !in emitter.currentTraitMethodRawNames &&
                (tail !in instanceMethodRawNames || !passesSelfFirst)
            ) {
                callsStaticSelfOrTraitFn = true
            } else if (segs.size >= 2 && head != null && tail != null &&
                (head == traitName || rustTypeIdentToKotlin(head) == traitName) &&
                (tail !in instanceMethodRawNames || !passesSelfFirst)
            ) {
                callsStaticSelfOrTraitFn = true
            } else if (segs.size == 2 && head != null && head != "Self" && head.firstOrNull()?.isUpperCase() == true &&
                emitter.lookupDeclaredTraitAst(rustTypeIdentToKotlin(head)) != null && !passesSelfFirst
            ) {
                callsStaticSelfOrTraitFn = true
            }
        }
        super.visitExprCall(e)
    }
}

private fun KotlinJ2KEmitter.hasInvalidDefaultBodyOnErasedInterface(
    defaultBlock: Block,
    sig: Signature,
    body: String,
    selfParamName: String?,
    traitName: String,
    instanceMethodRawNames: Set<String>,
): Boolean {
    val inspectorVisitor = ErasedDefaultBodyInspector(this, traitName, instanceMethodRawNames).also { it.visitBlock(defaultBlock) }
    if (inspectorVisitor.mutatesDerefSelf) return true
    if (selfParamName == null) return false
    if (inspectorVisitor.callsStaticSelfOrTraitFn || inspectorVisitor.returnsBareSelfOrUnaryOnSelf) return true
    val retSyn = extractReturnSynType(sig.output)
    val checkReturnThis = retSyn != null && synTypeReferencesDirectSelf(retSyn)
    return ErasedDefaultBodyKotlinTokenChecker(body, selfParamName, checkReturnThis).hasInvalidSelfUsage()
}

private class ErasedDefaultBodyKotlinTokenChecker(
    body: String,
    private val selfParamName: String,
    private val checkReturnThis: Boolean,
) : KotlinTokenWalker(body) {
    fun hasInvalidSelfUsage(): Boolean {
        for (idx in tokens.indices) {
            val ident = anyIdentAt(idx) ?: continue
            if (ident == "as" && anyIdentAt(nextNonTriviaIndex(idx + 1)) == selfParamName) {
                return true
            }
            if (ident != "this") continue
            val prevIdx = previousNonTriviaIndex(idx)
            if (anyPunctAt(prevIdx) == '.') continue
            val nextIdx = nextNonTriviaIndex(idx + 1)
            val nextPunct = anyPunctAt(nextIdx)
            if (nextPunct == '=' && anyPunctAt(nextIdx + 1) != '=') return true
            if (anyPunctAt(prevIdx)?.let { it in "!+-*/%" } == true) return true
            if (nextPunct != null && nextPunct in "+-*/%") return true
            if (anyIdentAt(nextIdx) in SHIFT_INFIX_OPS) return true
            if (checkReturnThis && isBareReturnedThisAt(idx)) return true
        }
        return false
    }

    private fun isBareReturnedThisAt(thisIdx: Int): Boolean {
        val prevIdx = previousNonTriviaIndex(thisIdx)
        val validPrefix =
            prevIdx < 0 ||
                anyIdentAt(prevIdx) in RETURN_THIS_KEYWORDS ||
                hasNewlineBetween(prevIdx, thisIdx)
        if (!validPrefix) return false
        var scan = thisIdx + 1
        while (scan < tokens.size && isTriviaToken(tokens[scan]) && tokens[scan] !is KotlinLexToken.Newline) {
            scan++
        }
        return scan >= tokens.size ||
            tokens[scan] is KotlinLexToken.Newline ||
            anyPunctAt(scan) == ';' ||
            anyPunctAt(scan) == '}'
    }

    private fun hasNewlineBetween(fromExclusive: Int, toExclusive: Int): Boolean =
        ((fromExclusive + 1) until toExclusive).any { tokens[it] is KotlinLexToken.Newline }

    private companion object {
        val SHIFT_INFIX_OPS = setOf("shl", "shr")
        val RETURN_THIS_KEYWORDS = setOf("return", "else")
    }
}

internal fun KotlinJ2KEmitter.pickUnusedTraitSelfParamName(
    generics: Generics,
    assocTypeNames: List<String>,
    traitItem: Item.Trait? = null,
): String {
    val memberGenerics =
        traitItem
            ?.items
            ?.flatMap { tItem ->
                when (tItem) {
                    is TraitItem.Fn -> collectGenericTypeParamNames(tItem.sig.generics)
                    is TraitItem.AssocType -> collectGenericTypeParamNames(tItem.generics)
                    else -> emptySet()
                }
            }.orEmpty()
    val existing = collectGenericTypeParamNames(generics) + assocTypeNames + memberGenerics
    return listOf("T", "SelfType", "S", "Target").firstOrNull { it !in existing } ?: "SelfType0"
}

private data class CoreTraitOperatorBridgeSpec(
    val traitName: String,
    val rustMethod: String,
    val kotlinModifier: String,
    val kotlinMethod: String,
    val takesRhs: Boolean = true,
)

private val CORE_TRAIT_OPERATOR_BRIDGES =
    listOf(
        CoreTraitOperatorBridgeSpec("Add", "add", "operator", "plus"),
        CoreTraitOperatorBridgeSpec("Sub", "sub", "operator", "minus"),
        CoreTraitOperatorBridgeSpec("Mul", "mul", "operator", "times"),
        CoreTraitOperatorBridgeSpec("Neg", "neg", "operator", "unaryMinus", takesRhs = false),
        CoreTraitOperatorBridgeSpec("BitAnd", "bitand", "infix", "and"),
        CoreTraitOperatorBridgeSpec("BitOr", "bitor", "infix", "or"),
        CoreTraitOperatorBridgeSpec("BitXor", "bitxor", "infix", "xor"),
    )

internal fun KotlinJ2KEmitter.renderItemTrait(
    item: Item.Trait,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val traitName = rustTypeIdentToKotlin(item.ident.toString())
    if (!reserveUniqueSignature(scopeSignatures, "type:$traitName")) return ""
    val (sb, vis) = beginItemHeaderAndVisibility(renderLeadingTrivia(item, indent), item.attrs, item.vis, indent, isLocalItem)
    val assocTypeNames = lookupTraitDefaultlessAssocTypes(traitName)
    val traitItems = item.items
    val rawAssocByKtName =
        traitItems
            .filterIsInstance<TraitItem.AssocType>()
            .associate { assocItem ->
                val rawIdent = assocItem.ident.toString()
                rustTypeIdentToKotlin(rawIdent) to rawIdent.removePrefix("r#")
            }
    val traitInstanceMethodRawNames =
        traitItems
            .filterIsInstance<TraitItem.Fn>()
            .filter { it.sig.hasSelfReceiver() }
            .mapTo(linkedSetOf()) { it.sig.cleanFnName() }
    val selfParamName = if (isSelfParameterizedTraitName(traitName)) pickUnusedTraitSelfParamName(item.generics, assocTypeNames, item) else null
    val memberSelfType = selfParamName ?: (traitName + renderTypeArgumentsFromGenerics(item.generics))
    val prevTraitAssoc = currentTraitAssocParamNames
    val prevTraitTypeParams = activeTypeParamNames
    val prevTraitConstParams = activeConstParamNames
    val prevTraitMethods = currentTraitMethodRawNames
    currentTraitAssocParamNames = assocTypeNames.toSet()
    currentTraitMethodRawNames = collectLocalTraitAndSupertraitFnRawNames(item)
    activeTypeParamNames = prevTraitTypeParams + collectGenericTypeParamNames(item.generics) + setOfNotNull(selfParamName) + assocTypeNames
    activeConstParamNames = prevTraitConstParams + collectGenericConstParamNames(item.generics)
    return try {
        val baseTypeParams = renderTypeParameters(item.generics, selfTypeName = memberSelfType).removePrefix("<").removeSuffix(">")
        val allowOutAssoc = selfParamName != null || usesTaggedCoreResult()
        val assocParamDecls =
            assocTypeNames.map { assocKt ->
                val rawAssoc = rawAssocByKtName[assocKt]
                val isCovariant = allowOutAssoc && isTraitAssocCovariantWithSupertraits(item, assocKt, rawAssoc)
                if (isCovariant) "out $assocKt" else assocKt
            }
        val combinedTraitParams =
            buildList {
                if (baseTypeParams.isNotEmpty()) add(baseTypeParams)
                if (selfParamName != null) add(selfParamName)
                addAll(assocParamDecls)
            }
        val typeParams = if (combinedTraitParams.isEmpty()) "" else "<${combinedTraitParams.joinToString(", ")}>"
        val coreNsForOps = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        val (superBounds, whereClause) =
            withBoundedTypeScope(boundedType = memberSelfType) {
                val superList = item.supertraits.toList()
                val sbList =
                    superList
                        .mapNotNull { renderTypeParamBound(it, selfTypeName = memberSelfType) }
                        .distinctBy { it.simpleKotlinTypeName() }
                val withDropSuper =
                    if (!headerContext.rawAbiMode &&
                        headerContext.packageName == "$coreNsForOps.ops.drop" &&
                        traitName == "Drop" &&
                        "AutoCloseable" !in sbList
                    ) {
                        sbList + "AutoCloseable"
                    } else {
                        sbList
                    }
                val wc =
                    renderWhereClause(
                        item.generics.whereClause,
                        selfTypeName = memberSelfType,
                        ownerGenerics = item.generics,
                        extraDeclaredNames = setOfNotNull(selfParamName) + assocTypeNames,
                    )
                withDropSuper to wc
            }

        sb.append("${indent}${vis}interface $traitName$typeParams")
        if (superBounds.isNotEmpty()) sb.append(" : ").append(superBounds.joinToString(", "))
        sb.append(whereClause)

        val traitSignatures = linkedMapOf<String, Int>()
        val members = mutableListOf<String>()
        for (tItem in item.items) {
            if (traitItemHasDocOnlyCfg(tItem)) continue
            when (tItem) {
                is TraitItem.Const -> {
                    val cDoc = renderKDocFromAttributes(tItem.attrs, "$indent    ")
                    val cName = disambiguateName(rustConstIdentToKotlin(tItem.ident.toString()), traitSignatures)
                    val cType = renderType(tItem.ty, selfTypeName = memberSelfType)
                    val defaultExpr = tItem.default?.expr
                    if (defaultExpr != null) {
                        val renderedExpr = renderTypedNumericInitializer(defaultExpr, resolveNumericKind(cType), "$indent    ", selfTypeName = memberSelfType)
                        members +=
                            if (renderedExpr == "null" && !cType.endsWith("?")) {
                                "${cDoc}$indent    public val $cName: $cType"
                            } else {
                                "${cDoc}$indent    public val $cName: $cType\n$indent        get() = $renderedExpr"
                            }
                    } else {
                        members += "${cDoc}$indent    public val $cName: $cType"
                    }
                }
                is TraitItem.Fn -> {
                    withFunctionRenderScope(sig = tItem.sig, selfTypeName = memberSelfType, updateEnclosingFunctionName = false) {
                        val sigHeader =
                            renderFunctionSignature(
                                sig = tItem.sig,
                                visPrefix = "public ",
                                openPrefix = "",
                                selfTypeName = memberSelfType,
                                ownerGenerics = item.generics,
                                scopeSignatures = traitSignatures,
                                isInterfaceMethod = true,
                                fnBlock = tItem.default,
                            )
                        if (sigHeader != null) {
                            val fnDoc = renderKDocFromAttributes(tItem.attrs, "$indent    ")
                            val defaultBlock = tItem.default
                            if (defaultBlock != null) {
                                val retType = extractReturnSynType(tItem.sig.output)
                                val shadows = computeReassignedParamShadows(tItem.sig, defaultBlock, "$indent        ", selfTypeName = memberSelfType)
                                val prevInTraitDefault = inTraitDefaultMethodBody
                                inTraitDefaultMethodBody = true
                                val body =
                                    try {
                                        renderBlock(defaultBlock, "$indent    ", selfTypeName = memberSelfType, returnType = retType, leadingInjectedLines = shadows, allowExpressionBody = true)
                                    } finally {
                                        inTraitDefaultMethodBody = prevInTraitDefault
                                    }
                                members +=
                                    if (hasInvalidDefaultBodyOnErasedInterface(defaultBlock, tItem.sig, body, selfParamName, traitName, traitInstanceMethodRawNames)) {
                                        "${fnDoc}$indent    $sigHeader"
                                    } else {
                                        "${fnDoc}$indent    $sigHeader $body"
                                    }
                            } else {
                                members += "${fnDoc}$indent    $sigHeader"
                            }
                        }
                    }
                }
                is TraitItem.Macro -> {
                    members +=
                        renderImplOrTraitMacroMembers(
                            mac = tItem.mac,
                            indent = "$indent    ",
                            selfTypeName = memberSelfType,
                            ownerGenerics = item.generics,
                            scopeSignatures = traitSignatures,
                            asTopLevelExtension = false,
                            isInterface = true,
                        )
                }
                is TraitItem.AssocType, is TraitItem.Verbatim -> {}
            }
        }
        appendSyntheticCoreTraitBridges(item, traitName, coreNsForOps, assocTypeNames, memberSelfType, indent, traitSignatures, members)
        appendUniqueReceiverlessTraitCompanion(
            item = item,
            isLocalItem = isLocalItem,
            assocTypeNames = assocTypeNames,
            indent = indent,
            prevTraitAssoc = prevTraitAssoc,
            prevTraitTypeParams = prevTraitTypeParams,
            prevTraitConstParams = prevTraitConstParams,
            members = members,
        )

        if (members.isNotEmpty()) {
            sb.append(" {\n${members.joinToString("\n\n")}\n$indent}")
        }
        sb.toString()
    } finally {
        currentTraitMethodRawNames = prevTraitMethods
        currentTraitAssocParamNames = prevTraitAssoc
        activeConstParamNames = prevTraitConstParams
        activeTypeParamNames = prevTraitTypeParams
    }
}

private fun KotlinJ2KEmitter.isTraitAssocCovariantWithSupertraits(
    item: Item.Trait,
    assocKt: String,
    rawAssoc: String?,
): Boolean {
    if (rawAssoc != null) return isAssocTypeCovariantInTrait(item, rawAssoc)
    if (!isAssocTypeCovariantInTrait(item, assocKt)) return false
    val supertraitList = item.supertraits.toList()
    val inheritedFromAny =
        supertraitList.any { bound ->
            val path = (bound as? TypeParamBound.Trait)?.takeIf { it.maybe == null }?.path ?: return@any false
            val superKt = path.lastKotlinTypeName().orEmpty()
            assocKt in lookupTraitDefaultlessAssocTypes(superKt, path)
        }
    if (!inheritedFromAny) return false
    return supertraitList.all { bound ->
        val path = (bound as? TypeParamBound.Trait)?.takeIf { it.maybe == null }?.path ?: return@all true
        val superKt = path.lastKotlinTypeName().orEmpty()
        if (assocKt !in lookupTraitDefaultlessAssocTypes(superKt, path)) return@all true
        val superDecl = lookupDeclaredTraitAst(superKt, path) ?: return@all false
        isAssocTypeCovariantInTrait(superDecl, assocKt)
    }
}

private fun KotlinJ2KEmitter.appendSyntheticCoreTraitBridges(
    item: Item.Trait,
    traitName: String,
    coreNsForOps: String,
    assocTypeNames: List<String>,
    memberSelfType: String,
    indent: String,
    traitSignatures: MutableMap<String, Int>,
    members: MutableList<String>,
) {
    if (!headerContext.rawAbiMode &&
        (headerContext.packageName == "$coreNsForOps.ops.arith" || headerContext.packageName == "$coreNsForOps.ops.bit")
    ) {
        val rhsParamType = collectGenericTypeParamNames(item.generics).firstOrNull()
        val outAssocType = assocTypeNames.firstOrNull { it == "Output" }
        if (outAssocType != null) {
            for (spec in CORE_TRAIT_OPERATOR_BRIDGES) {
                if (spec.traitName != traitName || spec.rustMethod !in currentTraitMethodRawNames) continue
                if (spec.takesRhs && rhsParamType == null) continue
                val sigKey = if (spec.takesRhs) "${spec.kotlinMethod}($rhsParamType)" else "${spec.kotlinMethod}()"
                if (reserveUniqueSignature(traitSignatures, sigKey)) {
                    val paramDecl = if (spec.takesRhs) "rhs: $rhsParamType" else ""
                    val argPass = if (spec.takesRhs) "rhs" else ""
                    members += "$indent    public ${spec.kotlinModifier} fun ${spec.kotlinMethod}($paramDecl): $outAssocType = ${spec.rustMethod}($argPass)"
                }
            }
        }
    }
    if (!headerContext.rawAbiMode &&
        headerContext.packageName == "$coreNsForOps.ops.drop" &&
        traitName == "Drop" &&
        reserveUniqueSignature(traitSignatures, "close()")
    ) {
        members += "$indent    public override fun close(): Unit = drop()"
    }
    extractIteratorSupertraitSpec(item.supertraits.toList(), memberSelfType)?.let { iterSuperSpec ->
        if (reserveUniqueSignature(traitSignatures, "next()")) {
            members += "$indent    public fun next(): ${iterSuperSpec.itemType}?"
        }
        if (iterSuperSpec.hasDoubleEnded && reserveUniqueSignature(traitSignatures, "nextBack()")) {
            members += "$indent    public fun nextBack(): ${iterSuperSpec.itemType}?"
        }
        if (iterSuperSpec.hasExactSize && reserveUniqueSignature(traitSignatures, "len()")) {
            members += "$indent    public fun len(): Int"
        }
    }
}

private inline fun <R> KotlinJ2KEmitter.withSignatureInspectionDefScope(block: () -> R): R {
    val prevCompTyped = currentTypedLocals
    val prevCompSourceParams = currentSourceParameterTypes
    val prevCompIntoParams = currentIntoSubstitutedParams
    val prevCompIterable = currentIterableLocals
    val prevCompNullable = currentNullableLocals
    val prevCompMutList = currentMutableListLocals
    val prevCompNumKinds = currentFunctionParamNumericKinds
    scopeBuilder.enterDef()
    return try {
        block()
    } finally {
        scopeBuilder.exitDef()
        currentTypedLocals = prevCompTyped
        currentSourceParameterTypes = prevCompSourceParams
        currentIntoSubstitutedParams = prevCompIntoParams
        currentIterableLocals = prevCompIterable
        currentNullableLocals = prevCompNullable
        currentMutableListLocals = prevCompMutList
        currentFunctionParamNumericKinds = prevCompNumKinds
    }
}

private fun KotlinJ2KEmitter.appendUniqueReceiverlessTraitCompanion(
    item: Item.Trait,
    isLocalItem: Boolean,
    assocTypeNames: List<String>,
    indent: String,
    prevTraitAssoc: Set<String>,
    prevTraitTypeParams: Set<String>,
    prevTraitConstParams: Set<String>,
    members: MutableList<String>,
) {
    if (isLocalItem || !item.generics.params.isEmpty() || assocTypeNames.isNotEmpty()) return
    val traitItems = item.items
    val receiverlessTraitFns =
        traitItems
            .filterIsInstance<TraitItem.Fn>()
            .filter { !traitItemHasDocOnlyCfg(it) && !it.sig.hasSelfReceiver() }
    if (receiverlessTraitFns.isEmpty()) return

    val rawTraitIdent = item.ident.toString()
    val moduleItems = (TranslationSession.sharedModuleFile?.items ?: file.items).toList()
    val uniqueImpl =
        moduleItems
            .filterIsInstance<Item.Impl>()
            .singleOrNull { implItem ->
                val traitPath = implItem.traitPath?.path
                !itemHasDocOnlyCfg(implItem) &&
                    implItem.generics.params.isEmpty() &&
                    traitPath?.lastIdentText() == rawTraitIdent
            } ?: return

    val savedTraitAssoc = currentTraitAssocParamNames
    val savedActiveTypeParams = activeTypeParamNames
    val savedActiveConstParams = activeConstParamNames
    val companionMethods =
        try {
            currentTraitAssocParamNames = prevTraitAssoc
            activeTypeParamNames = prevTraitTypeParams
            activeConstParamNames = prevTraitConstParams
            val concreteSelfType = renderType(uniqueImpl.selfType, selfTypeName = null)
            val directOwner = extractDirectOwnerTypeName(uniqueImpl.selfType, uniqueImpl.generics)
            val isLocalAttached =
                directOwner != null && directOwner in inspector.attachableTypeNames && TranslationSession.sharedModuleFile == null
            val implItems = uniqueImpl.items
            val implFnsByRawName =
                implItems
                    .filterIsInstance<ImplItem.Fn>()
                    .filter { !implItemHasDocOnlyCfg(it) }
                    .associateBy { it.sig.cleanFnName() }
            val companionSigs = linkedMapOf<String, Int>()
            val implTraitPath = uniqueImpl.traitPath!!.path
            val rawImplTrait = implTraitPath.lastCleanIdent()
            receiverlessTraitFns.mapNotNull { tFn ->
                val rawFnName = tFn.sig.cleanFnName()
                val implFn = implFnsByRawName[rawFnName] ?: return@mapNotNull null
                val sigHeader =
                    withSignatureInspectionDefScope {
                        renderFunctionSignature(
                            sig = implFn.sig,
                            visPrefix = "public ",
                            openPrefix = "",
                            selfTypeName = concreteSelfType,
                            ownerGenerics = null,
                            scopeSignatures = companionSigs,
                            isInterfaceMethod = false,
                            isCompanionMember = true,
                            fnBlock = implFn.block,
                        )
                    } ?: return@mapNotNull null
                val ktFnName = rustFunctionIdentToKotlin(implFn.sig.ident.toString())
                val targetCall =
                    if (isLocalAttached ||
                        (
                            concreteSelfType.substringBefore('<').trim() in COMPANION_CAPABLE_VALUE_TYPES &&
                                rawImplTrait in setOf("From", "TryFrom", "Step") &&
                                !implFn.sig.inputs.isEmpty()
                        )
                    ) {
                        "$concreteSelfType.$ktFnName"
                    } else {
                        disambiguateReceiverlessTraitImplFnName(implTraitPath, ktFnName, uniqueImpl.selfType)
                    }
                val forwardArgs = extractSigHeaderForwardArgs(sigHeader)
                val doc = renderKDocFromAttributes(implFn.attrs.ifEmpty { tFn.attrs }, "$indent        ")
                "$doc$indent        $sigHeader = $targetCall(${forwardArgs.joinToString(", ")})"
            }
        } finally {
            currentTraitAssocParamNames = savedTraitAssoc
            activeTypeParamNames = savedActiveTypeParams
            activeConstParamNames = savedActiveConstParams
        }
    if (companionMethods.isNotEmpty()) {
        members += "$indent    public companion object {\n${companionMethods.joinToString("\n")}\n$indent    }"
    }
}

private fun extractSigHeaderForwardArgs(sigHeader: String): List<String> =
    SignatureForwardArgWalker(sigHeader).extractArgs()

private class SignatureForwardArgWalker(
    source: String,
) : KotlinTokenWalker(source) {
    fun extractArgs(): List<String> {
        val funTokenIdx = tokens.indices.firstOrNull { identTextAt(it) == "fun" } ?: return emptyList()
        var idx = funTokenIdx + 1
        while (idx < tokens.size) {
            when (punctAt(idx)) {
                '<' -> {
                    val closeAngle = findMatchingTypeAngleToken(idx)
                    idx = if (closeAngle > idx) closeAngle + 1 else idx + 1
                }
                '(' -> break
                else -> idx++
            }
        }
        if (punctAt(idx) != '(') return emptyList()
        val closeParenIdx = findMatchingParenToken(idx)
        if (closeParenIdx <= idx) return emptyList()
        val body = source.substring(tokens[idx].end, tokens[closeParenIdx].start).trim()
        if (body.isEmpty()) return emptyList()
        return splitTopLevelCommaList(body).mapNotNull { entry ->
            val paramHead = entry.substringBefore(':').trim()
            paramHead.substringAfterLast(' ').takeIf { it.isNotEmpty() }
        }
    }
}

internal fun KotlinJ2KEmitter.stdlibIteratorTypeName(): String {
    val iteratorShadowed =
        "Iterator" in inspector.declaredTypeNames ||
            "Iterator" in headerContext.moduleDeclaredTypeNames ||
            extraTypePathImports.any { it.endsWith(".Iterator") || it.endsWith(".Iterator as Iterator") } ||
            inspector.allUseItems.any { useItem ->
                val leaves = mutableListOf<UseLeaf>()
                collectUseLeaves(useItem.tree, emptyList(), leaves)
                leaves.any { (it.alias ?: it.segments.lastOrNull())?.removePrefix("r#") == "Iterator" }
            }
    return if (iteratorShadowed) "kotlin.collections.Iterator" else "Iterator"
}

internal fun KotlinJ2KEmitter.renderItemTraitAlias(
    item: Item.TraitAlias,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val name = rustTypeIdentToKotlin(item.ident.toString())
    if (!reserveUniqueSignature(scopeSignatures, "type:$name")) return ""
    val (sb, vis) = beginItemHeaderAndVisibility(renderLeadingTrivia(item, indent), item.attrs, item.vis, indent, isLocalItem)
    val aliasSelfType = name + renderTypeArgumentsFromGenerics(item.generics)
    val typeParams = renderTypeParameters(item.generics, selfTypeName = aliasSelfType)
    val bounds =
        withBoundedTypeScope(boundedType = aliasSelfType) {
            val rawBounds = item.bounds.toList()
            rawBounds.mapNotNull { renderTypeParamBound(it, selfTypeName = aliasSelfType) }.distinct()
        }
    sb.append("${indent}${vis}interface $name$typeParams")
    if (bounds.isNotEmpty()) sb.append(" : ").append(bounds.joinToString(", "))
    return sb.toString()
}

private fun KotlinJ2KEmitter.collectBoundsForNamedGenericParam(
    generics: Generics,
    targetParamKt: String,
    selfTypeName: String? = null,
): List<TypeParamBound> {
    val bounds = mutableListOf<TypeParamBound>()
    for (gp in generics.params.toList()) {
        if (gp is GenericParam.TypeParam && rustTypeIdentToKotlin(gp.ident.toString()) == targetParamKt) {
            bounds.addAll(gp.bounds.toList())
        }
    }
    for (pred in generics.wherePredicatesList()) {
        if (pred is WherePredicate.TypePredicate && renderType(pred.boundedTy, selfTypeName) == targetParamKt) {
            bounds.addAll(pred.bounds.toList())
        }
    }
    return bounds
}

internal fun KotlinJ2KEmitter.extractFnTraitClosureReceiver(item: Item.Impl): Pair<String, String>? {
    val selfPath = unwrapSynType(item.selfType) as? SynType.Path ?: return null
    if (selfPath.qself != null) return null
    val selfSegs = selfPath.path.segments.toList()
    if (selfSegs.size != 1 || selfSegs[0].arguments !is PathArguments.None) return null
    val selfParamName = rustTypeIdentToKotlin(selfSegs[0].ident.toString())
    if (selfParamName !in collectGenericTypeParamNames(item.generics)) return null

    for (bound in collectBoundsForNamedGenericParam(item.generics, selfParamName)) {
        if (bound is TypeParamBound.Trait) {
            val lastSeg = bound.path.segments.last() ?: continue
            if (lastSeg.ident.toString() in setOf("Fn", "FnMut", "FnOnce") && lastSeg.arguments is PathArguments.Parenthesized) {
                return selfParamName to renderPathAsType(bound.path, selfTypeName = null)
            }
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.renderStandaloneItemImpl(
    item: Item.Impl,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val implComments = (renderLeadingTrivia(item, indent) + renderKDocFromAttributes(item.attrs, indent)).trimEnd()
    val closureRecv = extractFnTraitClosureReceiver(item)
    val prevSubst = substitutedTypeParamTypes
    val prevStandaloneTraitPath = currentStandaloneImplTraitPath
    val prevStandaloneSelfTy = currentStandaloneImplSelfSynType
    val prevAssocTypes = currentImplAssocTypes
    if (closureRecv != null) {
        substitutedTypeParamTypes = prevSubst + (closureRecv.first to closureRecv.second)
    }
    currentStandaloneImplTraitPath = item.traitPath?.path
    currentStandaloneImplSelfSynType = item.selfType
    val selfPath = (unwrapSynType(item.selfType) as? SynType.Path)?.takeIf { it.qself == null }?.path
    val ownerAssocKey = selfPath?.lastKotlinTypeName()
    currentImplAssocTypes =
        buildMap {
            putAll(prevAssocTypes)
            if (ownerAssocKey != null) putAll(inspector.ownerAssocTypes[ownerAssocKey].orEmpty())
            for (implItem in item.items) {
                if (implItem is ImplItem.AssocType) put(implItem.ident.toString(), implItem.ty)
            }
        }
    return try {
        withGenericParamScope(item.generics) {
            val selfTypeRendered = if (closureRecv != null) "(${closureRecv.second})" else renderType(item.selfType, selfTypeName = null)
            val traitPath = item.traitPath?.path
            val standaloneTraitKt = traitPath?.lastKotlinTypeName()
            if (standaloneTraitKt != null && lookupDeclaredTraitAst(standaloneTraitKt) != null &&
                selfTypeRendered in collectGenericTypeParamNames(item.generics)
            ) {
                val alreadyImplementsTrait =
                    collectBoundsForNamedGenericParam(item.generics, selfTypeRendered).any { b ->
                        b is TypeParamBound.Trait &&
                            b.maybe == null &&
                            b.path.lastKotlinTypeName() == standaloneTraitKt
                    }
                if (alreadyImplementsTrait) return@withGenericParamScope ""
            }
            val renderedMembers = mutableListOf<String>()
            ImplBlockMemberEmitter(
                emitter = this,
                selfTypeName = selfTypeRendered,
                ownerGenerics = item.generics,
                implGenerics = item.generics,
                indent = indent,
                isLocalItem = isLocalItem,
                asTopLevelExtension = true,
                routeStaticsAndConstsToCompanion = false,
                instanceMembers = renderedMembers,
                instanceSignatures = scopeSignatures,
            ).emitItems(item.items)
            if (implComments.isNotBlank()) {
                if (renderedMembers.isNotEmpty()) renderedMembers[0] = "$implComments\n${renderedMembers[0]}" else renderedMembers += implComments
            }
            renderedMembers.joinToString("\n\n")
        }
    } finally {
        currentImplAssocTypes = prevAssocTypes
        currentStandaloneImplTraitPath = prevStandaloneTraitPath
        currentStandaloneImplSelfSynType = prevStandaloneSelfTy
        substitutedTypeParamTypes = prevSubst
    }
}

internal fun KotlinJ2KEmitter.renderImplItemConst(
    item: ImplItem.Const,
    indent: String,
    selfTypeName: String?,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean = false,
): String {
    val (sb, vis) = beginItemHeaderAndVisibility(renderLeadingTrivia(item, indent), item.attrs, item.vis, indent, isLocalItem)
    val name = disambiguateName(rustConstIdentToKotlin(item.ident.toString()), scopeSignatures)
    val ty = renderType(item.ty, selfTypeName)
    val expr = renderTypedNumericInitializer(item.expr, resolveNumericKind(ty), indent, selfTypeName)
    return sb.append("${indent}${vis}val $name: $ty = $expr").toString()
}

internal fun KotlinJ2KEmitter.renderImplItemFn(
    item: ImplItem.Fn,
    indent: String,
    selfTypeName: String?,
    ownerGenerics: Generics?,
    implGenerics: Generics? = null,
    scopeSignatures: MutableMap<String, Int>,
    asTopLevelExtension: Boolean = false,
    isCompanionMember: Boolean = false,
    isLocalItem: Boolean = false,
): String {
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val useFinalAbiLayout = headerContext.rawAbiMode || headerContext.finalClasses
    val openMod =
        if (!useFinalAbiLayout && !asTopLevelExtension && !isCompanionMember && !isLocalItem && !currentAttachedImplIsObjectOwner && indent.isNotEmpty()) {
            "open "
        } else {
            ""
        }
    return withFunctionRenderScope(
        sig = item.sig,
        selfTypeName = selfTypeName,
        ownerGenerics = ownerGenerics,
        implGenerics = implGenerics,
        resetHasherState = true,
    ) {
        val activeTraitPath = currentAttachedImplTraitPath ?: currentStandaloneImplTraitPath
        val activeTraitLast = activeTraitPath?.lastIdentText()
        val inputsList = item.sig.inputs.toList()
        if (activeTraitLast == "Hash" &&
            item.sig.ident.toString() == "hash" &&
            inputsList.size == 2 &&
            inputsList[0] is FnArg.Receiver &&
            inputsList[1] is FnArg.Typed &&
            !asTopLevelExtension &&
            !isCompanionMember
        ) {
            if (!reserveUniqueSignature(scopeSignatures, "hashCode()")) return@withFunctionRenderScope ""
            val stateArg = inputsList[1] as FnArg.Typed
            val rawState = extractRawParamIdent(stateArg.patType.pat) ?: "state"
            val stateKt =
                scopeBuilder
                    .bindLocal(
                        rawName = rawState,
                        baseKotlinName = rustValueIdentToKotlin(rawState),
                        span = stateArg.patType.pat.span(),
                        assignCount = AssignCount.Any,
                    ).kotlinName
            currentHasherStateParam = stateKt
            currentEnclosingReturnRenderedType = "Int"
            val sb = StringBuilder()
            sb.append(renderLeadingTrivia(item, indent))
            sb.append(renderKDocFromAttributes(item.attrs, indent))
            val rawBody = renderBlock(item.block, indent, selfTypeName = selfTypeName, returnType = null)
            val closeSuffix = "\n$indent}"
            val body =
                when {
                    containsWholeIdentifier(rawBody, stateKt) ->
                        if (rawBody.startsWith("{\n") && rawBody.endsWith(closeSuffix)) {
                            val inner = rawBody.substring(2, rawBody.length - closeSuffix.length)
                            "{\n$indent    var $stateKt = 0\n$inner\n$indent    return $stateKt\n$indent}"
                        } else {
                            "{\n$indent    var $stateKt = 0\n$indent    return $stateKt\n$indent}"
                        }
                    rawBody.contains("error(") -> rawBody
                    rawBody == "{}" && (headerContext.isBatchCrateOutput || TranslationSession.finalStructs) -> "= 0"
                    rawBody == "{}" -> "{\n$indent    return 0\n$indent}"
                    rawBody.endsWith(closeSuffix) -> rawBody.dropLast(closeSuffix.length) + "\n$indent    return 0\n$indent}"
                    else -> "{\n$indent    return 0\n$indent}"
                }
            return@withFunctionRenderScope sb.append("$indent${vis}override fun hashCode(): Int $body").toString()
        }
        val sigHeader =
            renderFunctionSignature(
                sig = item.sig,
                visPrefix = vis,
                openPrefix = openMod,
                selfTypeName = selfTypeName,
                ownerGenerics = ownerGenerics,
                implGenerics = implGenerics,
                scopeSignatures = scopeSignatures,
                isInterfaceMethod = false,
                asTopLevelExtension = asTopLevelExtension,
                isCompanionMember = isCompanionMember,
                fnBlock = item.block,
            ) ?: return@withFunctionRenderScope ""
        val sb = StringBuilder()
        sb.append(renderLeadingTrivia(item, indent))
        sb.append(renderKDocFromAttributes(item.attrs, indent))
        val isTest = !isLocalItem && hasTestAttribute(item.attrs)
        if (isTest) sb.append(indent).append("@kotlin.test.Test\n")
        val retType = extractReturnSynType(item.sig.output)
        val panicExpectation = if (isTest) extractRustPanicExpectation(item.attrs) else null
        val bodyIndent = if (panicExpectation == null) indent else "$indent    "
        val shadows = computeReassignedParamShadows(item.sig, item.block, "$bodyIndent    ", selfTypeName = selfTypeName)
        val accessorFields =
            if (retType is SynType.Reference && retType.mutability != null) {
                selfTypeName?.substringBefore('<')?.let { owner ->
                    mutableReferenceProjection(owner, item.sig.ident.toString(), linkedSetOf())
                }
            } else {
                null
            }
        val rawStorageProjection =
            if (retType is SynType.Ptr && selfTypeName != null) {
                val owner = selfTypeName.substringBefore('<')
                val declaration = file.findStructByKotlinName(owner)
                val field = declaration?.let { sourceTransparentField(it, selfTypeName) }
                val projection = mutableReferenceProjection(owner, item.sig.ident.toString(), linkedSetOf())
                field != null && projection == listOf(field.first) && renderType(retType.elem, selfTypeName) == field.second
            } else {
                false
            }
        val body =
            when {
                rawStorageProjection -> {
                    val retPtr = retType as? SynType.Ptr
                    if (retPtr != null && retPtr.mutability is PointerMutability.Const) {
                        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.ConstPtr")
                        "{\n$bodyIndent    return ConstPtr(this.fieldStorage.rawAddress)\n$bodyIndent}"
                    } else {
                        "{\n$bodyIndent    return this.fieldStorage\n$bodyIndent}"
                    }
                }
                accessorFields != null && mutableReferencePointeeType(currentEnclosingReturnRenderedType) != null -> {
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.RustMutableReference")
                    "{\n$bodyIndent    return RustMutableReference(this.fieldStorage, this.fieldLayout)\n$bodyIndent}"
                }
                accessorFields != null -> {
                    val owner = "this" + accessorFields.dropLast(1).joinToString("") { ".$it" }
                    "{\n$bodyIndent    return $owner::${accessorFields.last()}\n$bodyIndent}"
                }
                else -> renderImplMethodBlockWithDropCascade(item, activeTraitLast, asTopLevelExtension, isCompanionMember, selfTypeName, bodyIndent, retType, shadows, panicExpectation)
            }
        sb.append("$indent$sigHeader ").append(if (panicExpectation == null) body else wrapRustPanicTestBody(body, indent, panicExpectation)).toString()
    }
}

private fun KotlinJ2KEmitter.renderImplMethodBlockWithDropCascade(
    item: ImplItem.Fn,
    activeTraitLast: String?,
    asTopLevelExtension: Boolean,
    isCompanionMember: Boolean,
    selfTypeName: String?,
    bodyIndent: String,
    retType: SynType?,
    shadows: List<String>,
    panicExpectation: RustPanicExpectation?,
): String {
    val dropCascadeLines =
        if (activeTraitLast == "Drop" &&
            item.sig.cleanFnName() == "drop" &&
            !asTopLevelExtension &&
            !isCompanionMember &&
            selfTypeName != null
        ) {
            val ownerName = selfTypeName.substringBefore('<')
            val ownerStruct = file.findStructByKotlinName(ownerName)
            val structNamed = (ownerStruct?.fields as? Fields.Named)?.fields?.named
            val namedFields = structNamed?.toList().orEmpty()
            namedFields
                .filterNot { isDocOnlyCfgAttribute(it.attrs) }
                .mapNotNull { field ->
                    val fName = field.ident?.let { rustFieldIdentToKotlin(it.toString()) } ?: return@mapNotNull null
                    val baseTy = renderType(field.ty, selfTypeName = selfTypeName).simpleKotlinTypeName()
                    if (baseTy == "RawVec") "$bodyIndent    this.$fName.close()" else null
                }
        } else {
            emptyList()
        }
    val rendered =
        renderBlock(
            item.block,
            bodyIndent,
            selfTypeName = selfTypeName,
            returnType = retType,
            leadingInjectedLines = shadows,
            allowExpressionBody = panicExpectation == null && dropCascadeLines.isEmpty(),
        )
    if (dropCascadeLines.isEmpty()) return rendered
    val cascadeSuffix = dropCascadeLines.joinToString("\n")
    val closeBrace = "\n$bodyIndent}"
    return when {
        rendered == "{}" -> "{\n$cascadeSuffix\n$bodyIndent}"
        rendered.endsWith(closeBrace) -> rendered.dropLast(closeBrace.length) + "\n$cascadeSuffix$closeBrace"
        else -> rendered
    }
}

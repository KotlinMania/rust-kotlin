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
            val tpName = rustTypeIdentToKotlin(gp.ident.toString())
            inspectBounds(tpName, gp.bounds.toList())
        }
    }
    for (pred in whereClause?.predicates?.toList().orEmpty()) {
        if (pred is WherePredicate.TypePredicate) {
            val tpName = renderType(pred.boundedTy, selfTypeName)
            if (tpName in fnTypeParams) {
                inspectBounds(tpName, pred.bounds.toList())
            }
        }
    }
    val convertibleTraits =
        buildSet {
            if ("Into" !in inspector.declaredTypeNames) add("Into")
            if ("AsRef" !in inspector.declaredTypeNames) add("AsRef")
            if ("Borrow" !in inspector.declaredTypeNames) add("Borrow")
        }
    if (convertibleTraits.isNotEmpty()) {
        for ((tpName, traitBounds) in allBoundsByTp) {
            if (tpName in result || traitBounds.size != 1) continue
            val sole = traitBounds[0]
            val lastSeg = sole.path.segments.last() ?: continue
            if (lastSeg.ident.toString() !in convertibleTraits) continue
            val typeArgs =
                (lastSeg.arguments as? PathArguments.AngleBracketed)
                    ?.args
                    ?.toList()
                    ?.filterIsInstance<GenericArgument.TypeArg>()
                    .orEmpty()
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
        val rawTrait =
            impl.traitPath
                ?.path
                ?.segments
                ?.last()
                ?.ident
                ?.toString() ?: continue
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
        if (rawName == "Iterator" || rawName == "DoubleEndedIterator" || rawName == "ExactSizeIterator") {
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
    return IteratorSupertraitSpec(
        itemType = itemRendered,
        hasDoubleEnded = hasDoubleEnded,
        hasExactSize = hasExactSize,
    )
}

internal fun KotlinJ2KEmitter.collectLocalSupertraitMethodNames(
    superTraits: List<String>,
    includeReceiverlessMethods: Boolean = false,
    ownerIsPublic: Boolean = true,
): Set<String> {
    val names = linkedSetOf<String>()
    val visitedTraits = hashSetOf<String>()
    fun visitTraitByBaseName(
        baseName: String,
        traitPath: Path? = null,
    ) {
        if (!visitedTraits.add(baseName)) return
        val traitDecl = lookupDeclaredTraitAst(baseName, traitPath) ?: return
        if (ownerIsPublic && renderVisibility(traitDecl.vis) != "public ") return
        for (tItem in traitDecl.items) {
            if (traitItemHasDocOnlyCfg(tItem)) continue
            if (tItem is TraitItem.Fn &&
                (includeReceiverlessMethods || tItem.sig.inputs.firstOrNull() is FnArg.Receiver)
            ) {
                names += rustFunctionIdentToKotlin(tItem.sig.ident.toString())
            }
        }
        val iterSpec = extractIteratorSupertraitSpec(traitDecl.supertraits.toList(), null)
        if (iterSpec != null) {
            names += "next"
            if (iterSpec.hasDoubleEnded) names += "nextBack"
            if (iterSpec.hasExactSize) names += "len"
        }
        for (bound in traitDecl.supertraits) {
            val boundPath = (bound as? TypeParamBound.Trait)?.path ?: continue
            val boundBase =
                boundPath.segments
                    .last()
                    ?.ident
                    ?.toString()
                    ?.let(::rustTypeIdentToKotlin) ?: continue
            visitTraitByBaseName(boundBase, boundPath)
        }
    }
    for (st in superTraits) {
        visitTraitByBaseName(st.substringBefore('<').trim())
    }
    return names
}

private fun KotlinJ2KEmitter.collectLocalTraitAndSupertraitFnRawNames(traitDecl: Item.Trait): Set<String> {
    val rawNames = linkedSetOf<String>()
    val visited = hashSetOf<String>()
    fun visitDecl(decl: Item.Trait) {
        val ktName = rustTypeIdentToKotlin(decl.ident.toString())
        if (!visited.add(ktName)) return
        for (tItem in decl.items) {
            if (traitItemHasDocOnlyCfg(tItem)) continue
            if (tItem is TraitItem.Fn) {
                rawNames +=
                    tItem.sig.ident
                        .toString()
                        .removePrefix("r#")
            }
        }
        for (bound in decl.supertraits) {
            val boundPath = (bound as? TypeParamBound.Trait)?.path ?: continue
            val boundKt =
                boundPath.segments
                    .last()
                    ?.ident
                    ?.toString()
                    ?.let(::rustTypeIdentToKotlin) ?: continue
            lookupDeclaredTraitAst(boundKt, boundPath)?.let(::visitDecl)
        }
    }
    visitDecl(traitDecl)
    return rawNames
}

internal fun KotlinJ2KEmitter.renderDistinctSuperTraits(
    attachedImpls: List<Item.Impl>,
    selfTypeWithGenerics: String,
    ownerIsPublic: Boolean = true,
): List<String> {
    val seenInterfaces = hashSetOf<String>()
    val result = mutableListOf<String>()
    for (impl in attachedImpls) {
        if (isDocOnlyCfgAttribute(impl.attrs)) continue
        val path = impl.traitPath?.path ?: continue
        val rawTrait =
            path.segments
                .last()
                ?.ident
                ?.toString() ?: continue
        if (rawTrait in IGNORED_TRAIT_BOUNDS) continue
        if (isImplicitStdlibSupertraitPath(path)) continue
        val prevBounded = currentBoundedTypeName
        val prevSuppressAssocs = suppressTraitAssocPlaceholders
        currentBoundedTypeName = selfTypeWithGenerics
        suppressTraitAssocPlaceholders = true
        val rendered =
            try {
                renderPathAsType(path, selfTypeName = selfTypeWithGenerics)
            } finally {
                currentBoundedTypeName = prevBounded
                suppressTraitAssocPlaceholders = prevSuppressAssocs
            }
        val selfBase = selfTypeWithGenerics.substringBefore('<').trim()
        val baseIface = rendered.substringBefore('<').trim()
        if (baseIface.isNotEmpty() && baseIface != selfBase && !rendered.contains("->")) {
            if (ownerIsPublic) {
                val traitDecl = lookupDeclaredTraitAst(baseIface, path)
                if (traitDecl != null && renderVisibility(traitDecl.vis) != "public ") {
                    continue
                }
            }
            if (!seenInterfaces.add(baseIface)) continue
            val assocNames = lookupTraitDefaultlessAssocTypes(baseIface, path)
            val fullRendered =
                if (assocNames.isNotEmpty()) {
                    val implAssocByName =
                        impl.items.filterIsInstance<ImplItem.AssocType>().associate {
                            rustTypeIdentToKotlin(it.ident.toString()) to renderType(it.ty, selfTypeWithGenerics)
                        }
                    val assocArgs = assocNames.map { implAssocByName[it] ?: "Any" }
                    if (rendered.endsWith(">")) {
                        "${rendered.dropLast(1)}, ${assocArgs.joinToString(", ")}>"
                    } else {
                        "$rendered<${assocArgs.joinToString(", ")}>"
                    }
                } else {
                    rendered
                }
            result += fullRendered
        }
    }
    return result
}

internal fun KotlinJ2KEmitter.structImplsMutateField(attachedImpls: List<Item.Impl>, rawFieldIdent: String): Boolean {
    var mutated = false
    val visitor =
        object : Visit() {
            override fun visitExprMethodCall(e: Expr.MethodCall) {
                val m = e.method.toString()
                if (m in MUTATING_VEC_METHODS) {
                    val recv = e.receiver
                    if (recv is Expr.Field &&
                        recv.base is Expr.Path &&
                        (recv.base as Expr.Path).path.isIdent("self") &&
                        recv.member is Member.Named &&
                        (recv.member as Member.Named).ident.toString().removePrefix("r#") == rawFieldIdent
                    ) {
                        mutated = true
                        return
                    }
                }
                super.visitExprMethodCall(e)
            }
        }
    for (impl in attachedImpls) {
        visitor.visitItemImpl(impl)
        if (mutated) return true
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
): String {
    val prevObjectOwner = currentAttachedImplIsObjectOwner
    currentAttachedImplIsObjectOwner = isObjectOwner
    try {
        val instanceMembers = mutableListOf<String>()
        val companionMembers = mutableListOf<String>()
        val instanceSignatures = linkedMapOf<String, Int>()
        val companionSignatures = linkedMapOf<String, Int>()

        if (headerContext.rawAbiMode || headerContext.finalClasses) {
            for (fName in structFieldNames) {
                instanceSignatures["$fName()"] = 1
            }
        }

        instanceMembers.addAll(extraProperties)
        instanceMembers.addAll(extraMembers)
        if (!isLocalClass && !isObjectOwner) {
            companionMembers.addAll(extraCompanionMembers)
        } else if (isObjectOwner && extraCompanionMembers.isNotEmpty()) {
            instanceMembers.addAll(extraCompanionMembers)
        }
        if (extraMembers.any { it.contains("fun hashCode(): Int") }) {
            instanceSignatures["hashCode()"] = 1
        }
        if (extraMembers.any { it.contains("fun equals(other: Any?): Boolean") }) {
            instanceSignatures["equals(Any?)"] = 1
        }

        val ownerFallbackAssocTypes =
            buildMap<String, SynType> {
                for (impl in attachedImpls) {
                    if (isDocOnlyCfgAttribute(impl.attrs)) continue
                    for (implItem in impl.items) {
                        if (implItem is ImplItem.AssocType) {
                            val key = implItem.ident.toString()
                            if (key !in this) {
                                put(key, implItem.ty)
                            }
                        }
                    }
                }
            }

        for (impl in attachedImpls) {
            if (isDocOnlyCfgAttribute(impl.attrs)) continue
            val implComments =
                (renderLeadingTrivia(impl, indent) + renderKDocFromAttributes(impl.attrs, indent))
                    .trimEnd()
            val instStart = instanceMembers.size
            val compStart = companionMembers.size
            val prevAssocTypes = currentImplAssocTypes
            val prevAttachedTraitPath = currentAttachedImplTraitPath
            val prevAttachedIsRefSelf = currentAttachedImplIsRefSelf
            val prevSubst = substitutedTypeParamTypes
            currentAttachedImplTraitPath = impl.traitPath?.path
            currentAttachedImplIsRefSelf = unwrapSynType(impl.selfType) is SynType.Reference
            val selfPathParam =
                (unwrapSynType(impl.selfType) as? SynType.Path)
                    ?.takeIf { it.qself == null && it.path.segments.len() == 1 }
                    ?.path
                    ?.segments
                    ?.first()
                    ?.ident
                    ?.toString()
                    ?.let(::rustTypeIdentToKotlin)
            val ownerBaseName = typeName.substringBefore('<').trim()
            if (selfPathParam != null &&
                selfPathParam != ownerBaseName &&
                selfPathParam in collectGenericTypeParamNames(impl.generics)
            ) {
                substitutedTypeParamTypes = prevSubst + (selfPathParam to typeName)
            }
            currentImplAssocTypes =
                buildMap {
                    putAll(prevAssocTypes)
                    putAll(ownerFallbackAssocTypes)
                    for (implItem in impl.items) {
                        if (implItem is ImplItem.AssocType) {
                            put(implItem.ident.toString(), implItem.ty)
                        }
                    }
                }
            try {
                for (implItem in impl.items) {
                    if (implItemHasDocOnlyCfg(implItem)) continue
                    when (implItem) {
                        is ImplItem.Const -> {
                            val routeToInstance = isLocalClass || isObjectOwner
                            val constIndent = if (routeToInstance) indent else "$indent    "
                            val targetList = if (routeToInstance) instanceMembers else companionMembers
                            val targetSigs = if (routeToInstance) instanceSignatures else companionSignatures
                            targetList += renderImplItemConst(implItem, constIndent, typeName, targetSigs)
                        }
                        is ImplItem.Fn -> {
                            val hasReceiver = implItem.sig.inputs.firstOrNull() is FnArg.Receiver
                            if (!hasReceiver && !isLocalClass && !isObjectOwner) {
                                val rendered =
                                    renderImplItemFn(
                                        item = implItem,
                                        indent = "$indent    ",
                                        selfTypeName = typeName,
                                        ownerGenerics = ownerGenerics,
                                        implGenerics = impl.generics,
                                        scopeSignatures = companionSignatures,
                                        isCompanionMember = true,
                                    )
                                if (rendered.isNotBlank()) companionMembers += rendered
                            } else {
                                val rendered =
                                    renderImplItemFn(
                                        item = implItem,
                                        indent = indent,
                                        selfTypeName = typeName,
                                        ownerGenerics = ownerGenerics,
                                        implGenerics = impl.generics,
                                        scopeSignatures = instanceSignatures,
                                    )
                                if (rendered.isNotBlank()) instanceMembers += rendered
                            }
                        }
                        is ImplItem.AssocType -> {}
                        is ImplItem.Macro -> {
                            instanceMembers +=
                                renderImplOrTraitMacroMembers(
                                    mac = implItem.mac,
                                    indent = indent,
                                    selfTypeName = typeName,
                                    ownerGenerics = ownerGenerics,
                                    scopeSignatures = instanceSignatures,
                                    asTopLevelExtension = false,
                                    isInterface = false,
                                )
                        }
                        is ImplItem.Verbatim -> {}
                    }
                }
            } finally {
                currentImplAssocTypes = prevAssocTypes
                currentAttachedImplTraitPath = prevAttachedTraitPath
                currentAttachedImplIsRefSelf = prevAttachedIsRefSelf
                substitutedTypeParamTypes = prevSubst
            }
            if (implComments.isNotBlank()) {
                when {
                    instanceMembers.size > instStart -> {
                        instanceMembers[instStart] = "$implComments\n${instanceMembers[instStart]}"
                    }
                    companionMembers.size > compStart -> {
                        val reindented =
                            implComments.lines().joinToString("\n") { line ->
                                if (line.isBlank()) line else "    $line"
                            }
                        companionMembers[compStart] = "$reindented\n${companionMembers[compStart]}"
                    }
                    else -> {
                        instanceMembers += implComments
                    }
                }
            }
        }

        if (companionMembers.isNotEmpty()) {
            val compSb = StringBuilder()
            compSb.append(indent).append("public companion object {\n")
            compSb.append(companionMembers.joinToString("\n\n"))
            compSb.append('\n').append(indent).append("}")
            instanceMembers += compSb.toString()
        }

        return instanceMembers.filter { it.isNotBlank() }.joinToString("\n\n")
    } finally {
        currentAttachedImplIsObjectOwner = prevObjectOwner
    }
}

private fun matchesSelfAssocTypePath(
    t: SynType.Path,
    rawAssocName: String,
): Boolean {
    val segs = t.path.segments.toList()
    val q = t.qself
    if (q != null) {
        val qPath = q.ty as? SynType.Path
        val isSelf =
            qPath
                ?.path
                ?.segments
                ?.toList()
                ?.singleOrNull()
                ?.ident
                ?.toString() == "Self"
        return isSelf &&
            segs
                .getOrNull(q.position)
                ?.ident
                ?.toString()
                ?.removePrefix("r#") == rawAssocName
    }
    if (segs.size == 2 &&
        segs[0].ident.toString() == "Self" &&
        segs[1].ident.toString().removePrefix("r#") == rawAssocName
    ) {
        return true
    }
    return segs.size == 1 && segs[0].ident.toString().removePrefix("r#") == rawAssocName
}

private fun synTypeMentionsAssoc(ty: SynType, rawAssocName: String): Boolean {
    var found = false
    val v =
        object : Visit() {
            override fun visitTypePath(t: SynType.Path) {
                if (matchesSelfAssocTypePath(t, rawAssocName)) {
                    found = true
                }
                super.visitTypePath(t)
            }
        }
    v.visitType(ty)
    return found
}

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
            val q = ty.qself
            if (q != null) {
                val qPath = q.ty as? SynType.Path
                val isSelf =
                    qPath
                        ?.path
                        ?.segments
                        ?.toList()
                        ?.singleOrNull()
                        ?.ident
                        ?.toString() == "Self"
                return isSelf && segs.size == 1 && segs[0].ident.toString().removePrefix("r#") == rawAssocName
            }
            if (segs.size == 2 && segs[0].ident.toString() == "Self" && segs[1].ident.toString().removePrefix("r#") == rawAssocName) {
                return true
            }
            if (segs.size == 1 && segs[0].ident.toString().removePrefix("r#") == rawAssocName) {
                return true
            }
            val lastSeg = segs.lastOrNull() ?: return false
            val rawLast = lastSeg.ident.toString().removePrefix("r#")
            val covariantStdWrappers = setOf("Option", "Result", "Vec", "Box", "Rc", "Arc", "Cow")
            if (rawLast !in covariantStdWrappers) return false
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
    var mentionedInSuperOrWhere = false
    val checkVisitor =
        object : Visit() {
            override fun visitTypePath(t: SynType.Path) {
                if (matchesSelfAssocTypePath(t, rawAssocName)) {
                    mentionedInSuperOrWhere = true
                }
                super.visitTypePath(t)
            }
        }
    for (bound in item.supertraits.toList()) {
        checkVisitor.visitTypeParamBound(bound)
    }
    item.generics.whereClause?.let { checkVisitor.visitWhereClause(it) }
    if (mentionedInSuperOrWhere) return false

    for (tItem in item.items) {
        if (traitItemHasDocOnlyCfg(tItem)) continue
        when (tItem) {
            is TraitItem.AssocType -> {
                if (tItem.ident.toString().removePrefix("r#") != rawAssocName) {
                    for (b in tItem.bounds.toList()) {
                        checkVisitor.visitTypeParamBound(b)
                    }
                    tItem.generics.whereClause?.let { checkVisitor.visitWhereClause(it) }
                    if (mentionedInSuperOrWhere) return false
                }
            }
            is TraitItem.Const -> {
                if (!synTypeAllowsOutPosition(tItem.ty, rawAssocName)) return false
            }
            is TraitItem.Fn -> {
                for (arg in tItem.sig.inputs.toList()) {
                    if (arg is FnArg.Typed && synTypeMentionsAssoc(arg.patType.ty, rawAssocName)) {
                        return false
                    }
                }
                tItem.sig.generics.whereClause
                    ?.let { checkVisitor.visitWhereClause(it) }
                if (mentionedInSuperOrWhere) return false
                val retTy = extractReturnSynType(tItem.sig.output)
                if (retTy != null && !synTypeAllowsOutPosition(retTy, rawAssocName)) {
                    return false
                }
            }
            else -> {}
        }
    }
    return true
}

private fun KotlinJ2KEmitter.hasInvalidDefaultBodyOnErasedInterface(
    defaultBlock: Block,
    sig: Signature,
    body: String,
    selfParamName: String?,
    traitName: String,
    instanceMethodRawNames: Set<String>,
): Boolean {
    var mutatesDerefSelf = false
    var returnsBareSelfOrUnaryOnSelf = false
    var callsStaticSelfOrTraitFn = false
    val blockVisitor =
        object : Visit() {
            override fun visitExprAssign(e: Expr.Assign) {
                val lhs = unwrapParenOrGroupExpr(e.left)
                if (lhs is Expr.Unary && lhs.op is UnOp.Deref) {
                    val inner = unwrapParenOrGroupExpr(lhs.expr) as? Expr.Path
                    if (inner?.qself == null && inner?.path?.isIdent("self") == true) {
                        mutatesDerefSelf = true
                    }
                }
                super.visitExprAssign(e)
            }

            override fun visitExprUnary(e: Expr.Unary) {
                val inner = unwrapParenOrGroupExpr(e.expr) as? Expr.Path
                if (inner?.qself == null && inner?.path?.isIdent("self") == true && e.op !is UnOp.Deref) {
                    returnsBareSelfOrUnaryOnSelf = true
                }
                super.visitExprUnary(e)
            }

            override fun visitExprCall(e: Expr.Call) {
                val fnPath = unwrapParenOrGroupExpr(e.func) as? Expr.Path
                if (fnPath != null && fnPath.qself == null) {
                    val segs = fnPath.path.segments.toList()
                    val head =
                        segs
                            .firstOrNull()
                            ?.ident
                            ?.toString()
                            ?.removePrefix("r#")
                    val tail =
                        segs
                            .lastOrNull()
                            ?.ident
                            ?.toString()
                            ?.removePrefix("r#")
                    val firstArgPath =
                        e.args
                            .toList()
                            .firstOrNull()
                            ?.let { unwrapParenOrGroupExpr(it) as? Expr.Path }
                    val passesSelfFirst =
                        firstArgPath?.qself == null && firstArgPath?.path?.isIdent("self") == true
                    if (segs.size == 2 && head == "Self" && tail != null &&
                        tail !in currentTraitMethodRawNames &&
                        (tail !in instanceMethodRawNames || !passesSelfFirst)
                    ) {
                        callsStaticSelfOrTraitFn = true
                    } else if (segs.size >= 2 && head != null && tail != null &&
                        (head == traitName || rustTypeIdentToKotlin(head) == traitName) &&
                        (tail !in instanceMethodRawNames || !passesSelfFirst)
                    ) {
                        callsStaticSelfOrTraitFn = true
                    } else if (segs.size == 2 &&
                        head != null &&
                        head != "Self" &&
                        head.firstOrNull()?.isUpperCase() == true &&
                        lookupDeclaredTraitAst(rustTypeIdentToKotlin(head)) != null &&
                        !passesSelfFirst
                    ) {
                        callsStaticSelfOrTraitFn = true
                    }
                }
                super.visitExprCall(e)
            }
        }
    blockVisitor.visitBlock(defaultBlock)
    if (mutatesDerefSelf) return true
    if (selfParamName == null) return false

    if (Regex("""\bas\s+${Regex.escape(selfParamName)}\b""").containsMatchIn(body)) return true
    if (Regex("""\bthis\s*=""").containsMatchIn(body)) return true
    if (Regex("""[!+\-*/%]\s*this\b|\bthis\s*(?:\+|-|\*|/|%|shl|shr)\b""").containsMatchIn(body)) return true
    if (callsStaticSelfOrTraitFn || returnsBareSelfOrUnaryOnSelf) return true

    val retSyn = extractReturnSynType(sig.output)
    if (retSyn != null && synTypeReferencesDirectSelf(retSyn)) {
        if (Regex("""(?m)(?:^|\belse\b|return)\s+this\b(?:\s*[;}]|$)""").containsMatchIn(body)) {
            return true
        }
    }
    return false
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
    for (candidate in listOf("T", "SelfType", "S", "Target")) {
        if (candidate !in existing) return candidate
    }
    return "SelfType0"
}

internal fun KotlinJ2KEmitter.renderItemTrait(
    item: Item.Trait,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val traitName = rustTypeIdentToKotlin(item.ident.toString())
    if (!reserveUniqueSignature(scopeSignatures, "type:$traitName")) {
        return ""
    }
    val (sb, vis) = beginItemHeaderAndVisibility(renderLeadingTrivia(item, indent), item.attrs, item.vis, indent, isLocalItem)
    val assocTypeNames = lookupTraitDefaultlessAssocTypes(traitName)
    val rawAssocByKtName =
        item.items
            .filterIsInstance<TraitItem.AssocType>()
            .associate { rustTypeIdentToKotlin(it.ident.toString()) to it.ident.toString().removePrefix("r#") }
    val traitInstanceMethodRawNames =
        item.items
            .filterIsInstance<TraitItem.Fn>()
            .filter { fnItem ->
                fnItem.sig.inputs
                    .toList()
                    .firstOrNull() is FnArg.Receiver
            }.mapTo(linkedSetOf()) {
                it.sig.ident
                    .toString()
                    .removePrefix("r#")
            }
    val selfParamName =
        if (isSelfParameterizedTraitName(traitName)) {
            pickUnusedTraitSelfParamName(item.generics, assocTypeNames, item)
        } else {
            null
        }
    val memberSelfType = selfParamName ?: (traitName + renderTypeArgumentsFromGenerics(item.generics))
    val prevTraitAssoc = currentTraitAssocParamNames
    val prevTraitTypeParams = activeTypeParamNames
    val prevTraitConstParams = activeConstParamNames
    val prevTraitMethods = currentTraitMethodRawNames
    currentTraitAssocParamNames = assocTypeNames.toSet()
    currentTraitMethodRawNames = collectLocalTraitAndSupertraitFnRawNames(item)
    activeTypeParamNames =
        prevTraitTypeParams +
        collectGenericTypeParamNames(item.generics) +
        setOfNotNull(selfParamName) +
        assocTypeNames
    activeConstParamNames = prevTraitConstParams + collectGenericConstParamNames(item.generics)
    return try {
        val baseTypeParams =
            renderTypeParameters(item.generics, selfTypeName = memberSelfType)
                .removePrefix("<")
                .removeSuffix(">")
        val allowOutAssoc = selfParamName != null || usesTaggedCoreResult()
        val assocParamDecls =
            assocTypeNames.map { assocKt ->
                val rawAssoc = rawAssocByKtName[assocKt]
                val isCovariant = allowOutAssoc && rawAssoc != null && isAssocTypeCovariantInTrait(item, rawAssoc)
                if (isCovariant) "out $assocKt" else assocKt
            }
        val combinedTraitParams =
            buildList {
                if (baseTypeParams.isNotEmpty()) add(baseTypeParams)
                if (selfParamName != null) add(selfParamName)
                addAll(assocParamDecls)
            }
        val typeParams =
            if (combinedTraitParams.isEmpty()) {
                ""
            } else {
                "<${combinedTraitParams.joinToString(", ")}>"
            }
        val prevBounded = currentBoundedTypeName
        currentBoundedTypeName = memberSelfType
        val (superBounds, whereClause) =
            try {
                val sbList =
                    item.supertraits
                        .toList()
                        .mapNotNull { renderTypeParamBound(it, selfTypeName = memberSelfType) }
                        .distinct()
                val wc =
                    renderWhereClause(
                        item.generics.whereClause,
                        selfTypeName = memberSelfType,
                        ownerGenerics = item.generics,
                        extraDeclaredNames = setOfNotNull(selfParamName) + assocTypeNames,
                    )
                sbList to wc
            } finally {
                currentBoundedTypeName = prevBounded
            }

        sb
            .append(indent)
            .append(vis)
            .append("interface ")
            .append(traitName)
            .append(typeParams)
        if (superBounds.isNotEmpty()) {
            sb.append(" : ").append(superBounds.joinToString(", "))
        }
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
                        val renderedExpr =
                            renderTypedNumericInitializer(
                                defaultExpr,
                                resolveNumericKind(cType),
                                "$indent    ",
                                selfTypeName = memberSelfType,
                            )
                        if (renderedExpr == "null" && !cType.endsWith("?")) {
                            members += "${cDoc}$indent    public val $cName: $cType"
                        } else {
                            members += "${cDoc}$indent    public val $cName: $cType\n$indent        get() = $renderedExpr"
                        }
                    } else {
                        members += "${cDoc}$indent    public val $cName: $cType"
                    }
                }
                is TraitItem.Fn -> {
                    withFunctionRenderScope(
                        sig = tItem.sig,
                        selfTypeName = memberSelfType,
                        updateEnclosingFunctionName = false,
                    ) {
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
                                val shadows = computeReassignedParamShadows(tItem.sig, defaultBlock, "$indent        ")
                                val prevInTraitDefault = inTraitDefaultMethodBody
                                inTraitDefaultMethodBody = true
                                val body =
                                    try {
                                        renderBlock(
                                            defaultBlock,
                                            "$indent    ",
                                            selfTypeName = memberSelfType,
                                            returnType = retType,
                                            leadingInjectedLines = shadows,
                                            allowExpressionBody = true,
                                        )
                                    } finally {
                                        inTraitDefaultMethodBody = prevInTraitDefault
                                    }
                                if (hasInvalidDefaultBodyOnErasedInterface(
                                        defaultBlock = defaultBlock,
                                        sig = tItem.sig,
                                        body = body,
                                        selfParamName = selfParamName,
                                        traitName = traitName,
                                        instanceMethodRawNames = traitInstanceMethodRawNames,
                                    )
                                ) {
                                    members += "${fnDoc}$indent    $sigHeader"
                                } else {
                                    members += "${fnDoc}$indent    $sigHeader $body"
                                }
                            } else {
                                members += "${fnDoc}$indent    $sigHeader"
                            }
                        }
                    }
                }
                is TraitItem.AssocType -> {}
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
                is TraitItem.Verbatim -> {}
            }
        }
        val iterSuperSpec = extractIteratorSupertraitSpec(item.supertraits.toList(), memberSelfType)
        if (iterSuperSpec != null) {
            if ("next()" !in traitSignatures) {
                traitSignatures["next()"] = 1
                members += "$indent    public fun next(): ${iterSuperSpec.itemType}?"
            }
            if (iterSuperSpec.hasDoubleEnded && "nextBack()" !in traitSignatures) {
                traitSignatures["nextBack()"] = 1
                members += "$indent    public fun nextBack(): ${iterSuperSpec.itemType}?"
            }
            if (iterSuperSpec.hasExactSize && "len()" !in traitSignatures) {
                traitSignatures["len()"] = 1
                members += "$indent    public fun len(): Int"
            }
        }

        if (!isLocalItem && item.generics.params.isEmpty() && assocTypeNames.isEmpty()) {
            val receiverlessTraitFns =
                item.items
                    .filterIsInstance<TraitItem.Fn>()
                    .filter { tFn ->
                        !traitItemHasDocOnlyCfg(tFn) &&
                            tFn.sig.inputs
                                .toList()
                                .firstOrNull() !is FnArg.Receiver
                    }
            if (receiverlessTraitFns.isNotEmpty()) {
                val rawTraitIdent = item.ident.toString()
                val moduleItems = (TranslationSession.sharedModuleFile?.items ?: file.items).toList()
                val uniqueImpl =
                    moduleItems
                        .filterIsInstance<Item.Impl>()
                        .singleOrNull { implItem ->
                            !itemHasDocOnlyCfg(implItem) &&
                                implItem.generics.params.isEmpty() &&
                                implItem.traitPath
                                    ?.path
                                    ?.segments
                                    ?.last()
                                    ?.ident
                                    ?.toString() == rawTraitIdent
                        }
                if (uniqueImpl != null) {
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
                                directOwner != null &&
                                    directOwner in inspector.attachableTypeNames &&
                                    TranslationSession.sharedModuleFile == null
                            val implFnsByRawName =
                                uniqueImpl.items
                                    .filterIsInstance<ImplItem.Fn>()
                                    .filter { !implItemHasDocOnlyCfg(it) }
                                    .associateBy {
                                        it.sig.ident
                                            .toString()
                                            .removePrefix("r#")
                                    }
                            val companionSigs = linkedMapOf<String, Int>()
                            val implTraitPath = uniqueImpl.traitPath!!.path
                            receiverlessTraitFns.mapNotNull { tFn ->
                                val rawFnName =
                                    tFn.sig.ident
                                        .toString()
                                        .removePrefix("r#")
                                val implFn = implFnsByRawName[rawFnName] ?: return@mapNotNull null
                                val prevCompTyped = currentTypedLocals
                                val prevCompSourceParams = currentSourceParameterTypes
                                val prevCompIntoParams = currentIntoSubstitutedParams
                                val prevCompIterable = currentIterableLocals
                                val prevCompNullable = currentNullableLocals
                                val prevCompMutList = currentMutableListLocals
                                val prevCompNumKinds = currentFunctionParamNumericKinds
                                scopeBuilder.enterDef()
                                val sigHeader =
                                    try {
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
                                        ?: return@mapNotNull null
                                val ktFnName = rustFunctionIdentToKotlin(implFn.sig.ident.toString())
                                val targetCall =
                                    if (isLocalAttached) {
                                        "$concreteSelfType.$ktFnName"
                                    } else {
                                        disambiguateReceiverlessTraitImplFnName(implTraitPath, ktFnName, uniqueImpl.selfType)
                                    }
                                val forwardArgs = extractSigHeaderForwardArgs(sigHeader)
                                val docAttrs = implFn.attrs.ifEmpty { tFn.attrs }
                                val doc = renderKDocFromAttributes(docAttrs, "$indent        ")
                                "$doc$indent        $sigHeader = $targetCall(${forwardArgs.joinToString(", ")})"
                            }
                        } finally {
                            currentTraitAssocParamNames = savedTraitAssoc
                            activeTypeParamNames = savedActiveTypeParams
                            activeConstParamNames = savedActiveConstParams
                        }
                    if (companionMethods.isNotEmpty()) {
                        members +=
                            "$indent    public companion object {\n" +
                            companionMethods.joinToString("\n") +
                            "\n$indent    }"
                    }
                }
            }
        }

        if (members.isNotEmpty()) {
            sb.append(" {\n")
            sb.append(members.joinToString("\n\n"))
            sb.append('\n').append(indent).append("}")
        }
        sb.toString()
    } finally {
        currentTraitMethodRawNames = prevTraitMethods
        currentTraitAssocParamNames = prevTraitAssoc
        activeConstParamNames = prevTraitConstParams
        activeTypeParamNames = prevTraitTypeParams
    }
}

private fun extractSigHeaderForwardArgs(sigHeader: String): List<String> {
    val funIdx = sigHeader.indexOf("fun ")
    if (funIdx < 0) return emptyList()
    var i = funIdx + 4
    var angleDepth = 0
    while (i < sigHeader.length) {
        val ch = sigHeader[i]
        when {
            ch == '<' -> angleDepth++
            ch == '>' -> if (angleDepth > 0 && (i == 0 || sigHeader[i - 1] != '-')) angleDepth--
            ch == '(' && angleDepth == 0 -> break
        }
        i++
    }
    if (i >= sigHeader.length || sigHeader[i] != '(') return emptyList()
    val openIdx = i
    var parenDepth = 0
    var closeIdx = -1
    while (i < sigHeader.length) {
        when (sigHeader[i]) {
            '(' -> parenDepth++
            ')' -> {
                parenDepth--
                if (parenDepth == 0) {
                    closeIdx = i
                    break
                }
            }
        }
        i++
    }
    if (closeIdx <= openIdx) return emptyList()
    val body = sigHeader.substring(openIdx + 1, closeIdx).trim()
    if (body.isEmpty()) return emptyList()
    return PhantomRawPointerTypeStripper
        .splitTopLevelCommaList(body)
        .mapNotNull { entry ->
            entry
                .substringBefore(':')
                .trim()
                .substringAfterLast(' ')
                .takeIf { it.isNotEmpty() }
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
                leaves.any { leaf ->
                    val name = leaf.alias ?: leaf.segments.lastOrNull()
                    name?.removePrefix("r#") == "Iterator"
                }
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
    val sigKey = "type:$name"
    val seenCount = scopeSignatures[sigKey] ?: 0
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) {
        return ""
    }
    val (sb, vis) = beginItemHeaderAndVisibility(renderLeadingTrivia(item, indent), item.attrs, item.vis, indent, isLocalItem)
    val aliasSelfType = name + renderTypeArgumentsFromGenerics(item.generics)
    val typeParams = renderTypeParameters(item.generics, selfTypeName = aliasSelfType)
    val prevBounded = currentBoundedTypeName
    currentBoundedTypeName = aliasSelfType
    val bounds =
        try {
            item.bounds
                .toList()
                .mapNotNull { renderTypeParamBound(it, selfTypeName = aliasSelfType) }
                .distinct()
        } finally {
            currentBoundedTypeName = prevBounded
        }
    sb
        .append(indent)
        .append(vis)
        .append("interface ")
        .append(name)
        .append(typeParams)
    if (bounds.isNotEmpty()) {
        sb.append(" : ").append(bounds.joinToString(", "))
    }
    return sb.toString()
}

internal fun KotlinJ2KEmitter.extractFnTraitClosureReceiver(item: Item.Impl): Pair<String, String>? {
    val selfPath = unwrapSynType(item.selfType) as? SynType.Path ?: return null
    if (selfPath.qself != null) return null
    val selfSegs = selfPath.path.segments.toList()
    if (selfSegs.size != 1 || selfSegs[0].arguments !is PathArguments.None) return null
    val selfParamName = rustTypeIdentToKotlin(selfSegs[0].ident.toString())
    val ownerTypeParams = collectGenericTypeParamNames(item.generics)
    if (selfParamName !in ownerTypeParams) return null

    val candidateBounds = mutableListOf<TypeParamBound>()
    for (gp in item.generics.params.toList()) {
        if (gp is GenericParam.TypeParam && rustTypeIdentToKotlin(gp.ident.toString()) == selfParamName) {
            candidateBounds.addAll(gp.bounds.toList())
        }
    }
    val wherePreds = item.generics.whereClause?.predicates
    for (pred in wherePreds?.toList().orEmpty()) {
        if (pred is WherePredicate.TypePredicate) {
            val predTy = unwrapSynType(pred.boundedTy) as? SynType.Path
            if (predTy != null && predTy.qself == null && predTy.path.segments.len() == 1) {
                val firstSeg = predTy.path.segments.first()
                val rawPred = firstSeg?.ident?.toString()
                val predIdent = rawPred?.let(::rustTypeIdentToKotlin)
                if (predIdent == selfParamName) {
                    candidateBounds.addAll(pred.bounds.toList())
                }
            }
        }
    }
    for (bound in candidateBounds) {
        if (bound is TypeParamBound.Trait) {
            val lastSeg = bound.path.segments.last() ?: continue
            val boundIdent = lastSeg.ident.toString()
            if ((boundIdent == "Fn" || boundIdent == "FnMut" || boundIdent == "FnOnce") &&
                lastSeg.arguments is PathArguments.Parenthesized
            ) {
                val fnType = renderPathAsType(bound.path, selfTypeName = null)
                return selfParamName to fnType
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
    val implComments =
        (renderLeadingTrivia(item, indent) + renderKDocFromAttributes(item.attrs, indent))
            .trimEnd()
    val closureRecv = extractFnTraitClosureReceiver(item)
    val prevSubst = substitutedTypeParamTypes
    val prevStandaloneTraitPath = currentStandaloneImplTraitPath
    val prevStandaloneSelfTy = currentStandaloneImplSelfSynType
    val prevAssocTypes = currentImplAssocTypes
    val prevImplTypeParams = activeTypeParamNames
    val prevImplConstParams = activeConstParamNames
    activeTypeParamNames = prevImplTypeParams + collectGenericTypeParamNames(item.generics)
    activeConstParamNames = prevImplConstParams + collectGenericConstParamNames(item.generics)
    if (closureRecv != null) {
        substitutedTypeParamTypes = prevSubst + (closureRecv.first to closureRecv.second)
    }
    currentStandaloneImplTraitPath = item.traitPath?.path
    currentStandaloneImplSelfSynType = item.selfType
    currentImplAssocTypes =
        buildMap {
            putAll(prevAssocTypes)
            for (implItem in item.items) {
                if (implItem is ImplItem.AssocType) {
                    put(implItem.ident.toString(), implItem.ty)
                }
            }
        }
    return try {
        val selfTypeRendered =
            if (closureRecv != null) {
                "(${closureRecv.second})"
            } else {
                renderType(item.selfType, selfTypeName = null)
            }
        val standaloneTraitKt =
            item.traitPath
                ?.path
                ?.segments
                ?.last()
                ?.ident
                ?.toString()
                ?.let(::rustTypeIdentToKotlin)
        if (standaloneTraitKt != null && lookupDeclaredTraitAst(standaloneTraitKt) != null) {
            val implTypeParams = collectGenericTypeParamNames(item.generics)
            if (selfTypeRendered in implTypeParams) {
                val selfBounds = mutableListOf<TypeParamBound>()
                for (gp in item.generics.params.toList()) {
                    if (gp is GenericParam.TypeParam && rustTypeIdentToKotlin(gp.ident.toString()) == selfTypeRendered) {
                        selfBounds.addAll(gp.bounds.toList())
                    }
                }
                for (pred in item.generics.whereClause
                    ?.predicates
                    ?.toList()
                    .orEmpty()) {
                    if (pred is WherePredicate.TypePredicate && renderType(pred.boundedTy, null) == selfTypeRendered) {
                        selfBounds.addAll(pred.bounds.toList())
                    }
                }
                val alreadyImplementsTrait =
                    selfBounds.any { b ->
                        b is TypeParamBound.Trait &&
                            b.maybe == null &&
                            b.path.segments
                                .last()
                                ?.ident
                                ?.toString()
                                ?.let(::rustTypeIdentToKotlin) == standaloneTraitKt
                    }
                if (alreadyImplementsTrait) {
                    return ""
                }
            }
        }
        val scopeSigs = scopeSignatures
        val renderedMembers = mutableListOf<String>()
        for (implItem in item.items) {
            if (implItemHasDocOnlyCfg(implItem)) continue
            when (implItem) {
                is ImplItem.Const -> {
                    renderedMembers +=
                        renderImplItemConst(
                            item = implItem,
                            indent = indent,
                            selfTypeName = selfTypeRendered,
                            scopeSignatures = scopeSigs,
                            isLocalItem = isLocalItem,
                        )
                }
                is ImplItem.Fn -> {
                    val rendered =
                        renderImplItemFn(
                            item = implItem,
                            indent = indent,
                            selfTypeName = selfTypeRendered,
                            ownerGenerics = item.generics,
                            implGenerics = item.generics,
                            scopeSignatures = scopeSigs,
                            asTopLevelExtension = true,
                            isLocalItem = isLocalItem,
                        )
                    if (rendered.isNotBlank()) renderedMembers += rendered
                }
                is ImplItem.AssocType -> {}
                is ImplItem.Macro -> {
                    renderedMembers +=
                        renderImplOrTraitMacroMembers(
                            mac = implItem.mac,
                            indent = indent,
                            selfTypeName = selfTypeRendered,
                            ownerGenerics = item.generics,
                            scopeSignatures = scopeSigs,
                            asTopLevelExtension = true,
                            isInterface = false,
                            isLocalItem = isLocalItem,
                        )
                }
                is ImplItem.Verbatim -> {}
            }
        }
        if (implComments.isNotBlank()) {
            if (renderedMembers.isNotEmpty()) {
                renderedMembers[0] = "$implComments\n${renderedMembers[0]}"
            } else {
                renderedMembers += implComments
            }
        }
        renderedMembers.joinToString("\n\n")
    } finally {
        currentImplAssocTypes = prevAssocTypes
        currentStandaloneImplTraitPath = prevStandaloneTraitPath
        currentStandaloneImplSelfSynType = prevStandaloneSelfTy
        substitutedTypeParamTypes = prevSubst
        activeConstParamNames = prevImplConstParams
        activeTypeParamNames = prevImplTypeParams
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
    val numKind = resolveNumericKind(ty)
    val expr = renderTypedNumericInitializer(item.expr, numKind, indent, selfTypeName)
    sb
        .append(indent)
        .append(vis)
        .append("val ")
        .append(name)
        .append(": ")
        .append(ty)
        .append(" = ")
        .append(expr)
    return sb.toString()
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
        if (!useFinalAbiLayout &&
            !asTopLevelExtension &&
            !isCompanionMember &&
            !isLocalItem &&
            !currentAttachedImplIsObjectOwner &&
            indent.isNotEmpty()
        ) {
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
        val activeTraitLast =
            (currentAttachedImplTraitPath ?: currentStandaloneImplTraitPath)
                ?.segments
                ?.last()
                ?.ident
                ?.toString()
        val inputsList = item.sig.inputs.toList()
        if (activeTraitLast == "Hash" &&
            item.sig.ident.toString() == "hash" &&
            inputsList.size == 2 &&
            inputsList[0] is FnArg.Receiver &&
            inputsList[1] is FnArg.Typed &&
            !asTopLevelExtension &&
            !isCompanionMember
        ) {
            val hashSigKey = "hashCode()"
            val seen = scopeSignatures[hashSigKey] ?: 0
            scopeSignatures[hashSigKey] = seen + 1
            if (seen > 0) return@withFunctionRenderScope ""
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
            val rawBody =
                renderBlock(
                    item.block,
                    indent,
                    selfTypeName = selfTypeName,
                    returnType = null,
                )
            val body =
                when {
                    containsWholeIdentifier(rawBody, stateKt) -> {
                        val openPrefix = "{\n"
                        val closeSuffix = "\n$indent}"
                        if (rawBody.startsWith(openPrefix) && rawBody.endsWith(closeSuffix)) {
                            val inner = rawBody.substring(openPrefix.length, rawBody.length - closeSuffix.length)
                            "{\n$indent    var $stateKt = 0\n$inner\n$indent    return $stateKt\n$indent}"
                        } else {
                            "{\n$indent    var $stateKt = 0\n$indent    return $stateKt\n$indent}"
                        }
                    }
                    rawBody.contains("error(") -> rawBody
                    rawBody == "{}" && (headerContext.isBatchCrateOutput || TranslationSession.finalStructs) -> "= 0"
                    rawBody == "{}" -> "{\n$indent    return 0\n$indent}"
                    rawBody.endsWith("\n$indent}") ->
                        rawBody.substring(0, rawBody.length - "\n$indent}".length) + "\n$indent    return 0\n$indent}"
                    else -> "{\n$indent    return 0\n$indent}"
                }
            sb.append(indent).append("${vis}override fun hashCode(): Int ").append(body)
            return@withFunctionRenderScope sb.toString()
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
        if (!isLocalItem && hasTestAttribute(item.attrs)) {
            sb.append(indent).append("@kotlin.test.Test\n")
        }
        val retType = extractReturnSynType(item.sig.output)
        val panicExpectation = if (!isLocalItem && hasTestAttribute(item.attrs)) extractRustPanicExpectation(item.attrs) else null
        val bodyIndent = if (panicExpectation == null) indent else "$indent    "
        val shadows = computeReassignedParamShadows(item.sig, item.block, "$bodyIndent    ")
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
                val declaration =
                    file.items.filterIsInstance<Item.Struct>().singleOrNull {
                        rustTypeIdentToKotlin(it.ident.toString()) == owner
                    }
                val field = declaration?.let { sourceTransparentField(it, selfTypeName) }
                val projection = mutableReferenceProjection(owner, item.sig.ident.toString(), linkedSetOf())
                field != null && projection == listOf(field.first) &&
                    renderType(retType.elem, selfTypeName) == field.second
            } else {
                false
            }
        val body =
            if (rawStorageProjection) {
                val retPtr = retType as? SynType.Ptr
                if (retPtr != null && retPtr.mutability is io.github.kotlinmania.syn.PointerMutability.Const) {
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.ConstPtr")
                    "{\n$bodyIndent    return ConstPtr(this.fieldStorage.rawAddress)\n$bodyIndent}"
                } else {
                    "{\n$bodyIndent    return this.fieldStorage\n$bodyIndent}"
                }
            } else if (accessorFields != null && mutableReferencePointeeType(currentEnclosingReturnRenderedType) != null) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.RustMutableReference")
                "{\n$bodyIndent    return RustMutableReference(this.fieldStorage, this.fieldLayout)\n$bodyIndent}"
            } else if (accessorFields != null) {
                val owner = "this" + accessorFields.dropLast(1).joinToString("") { ".$it" }
                "{\n$bodyIndent    return $owner::${accessorFields.last()}\n$bodyIndent}"
            } else {
                renderBlock(
                    item.block,
                    bodyIndent,
                    selfTypeName = selfTypeName,
                    returnType = retType,
                    leadingInjectedLines = shadows,
                    allowExpressionBody = panicExpectation == null,
                )
            }
        sb
            .append(indent)
            .append(sigHeader)
            .append(" ")
            .append(if (panicExpectation == null) body else wrapRustPanicTestBody(body, indent, panicExpectation))
        sb.toString()
    }
}

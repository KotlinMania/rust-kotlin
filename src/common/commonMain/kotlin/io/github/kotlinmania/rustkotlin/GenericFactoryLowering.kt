package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.*
import io.github.kotlinmania.syn.gen.Visit

/** Layout contracts are derived from the same source file as the call. Generic
 * factories transport representation evidence; Kotlin values never determine it.
 */
private class SourceFactory(
    val owner: String?,
    val sig: Signature,
    val block: Block?,
    val generics: Generics?,
)

private object SourceFactoryCache {
    var source: Any? = null
    var factories: List<SourceFactory> = emptyList()
    val requirements = mutableMapOf<SourceFactory, List<String>>()
}

private fun KotlinJ2KEmitter.sourceFactories(): List<SourceFactory> {
    val source = TranslationSession.sourceLayoutFile ?: file
    if (SourceFactoryCache.source === source) return SourceFactoryCache.factories
    SourceFactoryCache.source = source
    SourceFactoryCache.requirements.clear()
    val factories =
        sameFileLayoutItems().flatMap { item ->
            when (item) {
                is Item.Fn -> listOf(SourceFactory(null, item.sig, item.block, null))
                is Item.Impl ->
                    item.items.filterIsInstance<ImplItem.Fn>().filter { it.sig.inputs.firstOrNull() !is FnArg.Receiver }.map {
                        SourceFactory(renderType(item.selfType, null).asKotlinRenderedType()?.headPath.orEmpty(), it.sig, it.block, item.generics)
                    }
                else -> emptyList()
            }
        }
    SourceFactoryCache.factories = factories
    return factories
}

private fun SourceFactory.parameters(): List<String> =
    (generics?.params?.toList().orEmpty() + sig.generics.params.toList()).filterIsInstance<GenericParam.TypeParam>().map { it.ident.toString() }.distinct()

private fun KotlinJ2KEmitter.calledFactory(call: Expr.Call, owner: String?): SourceFactory? {
    val path =
        (call.func as? Expr.Path)
            ?.takeIf { it.qself == null }
            ?.path
            ?.segments
            ?.toList() ?: return null
    if (path.size !in 1..2) return null
    val targetOwner =
        if (path.size == 1) {
            null
        } else {
            path
                .first()
                .ident
                .toString()
                .let { if (it == "Self") owner else rustTypeIdentToKotlin(it) }
        }
    return sourceFactories().singleOrNull { it.owner == targetOwner && it.sig.ident.toString() == path.last().ident.toString() }
}

private fun bindFactoryType(pattern: SynType, actual: SynType, parameters: Set<String>, bindings: MutableMap<String, SynType>) {
    val path = (pattern as? SynType.Path)?.takeIf { it.qself == null }?.path
    val name =
        path
            ?.segments
            ?.toList()
            ?.singleOrNull()
            ?.ident
            ?.toString()
    if (name in parameters) {
        bindings[requireNotNull(name)] = actual
        return
    }
    if (pattern is SynType.Reference && actual is SynType.Reference) bindFactoryType(pattern.elem, actual.elem, parameters, bindings)
    if (pattern is SynType.Ptr && actual is SynType.Ptr) bindFactoryType(pattern.elem, actual.elem, parameters, bindings)
    if (pattern is SynType.Path && actual is SynType.Path && pattern.path.segments
            .last()
            ?.ident
            .toString() ==
        actual.path.segments
            .last()
            ?.ident
            .toString()
    ) {
        fun arguments(t: SynType.Path) =
            (
                t.path.segments
                    .last()
                    ?.arguments as? PathArguments.AngleBracketed
            )?.args?.toList().orEmpty().filterIsInstance<GenericArgument.TypeArg>()
        arguments(pattern).zip(arguments(actual)).forEach { (p, a) -> bindFactoryType(p.type, a.type, parameters, bindings) }
    }
}

private fun KotlinJ2KEmitter.factoryBindings(call: Expr.Call, factory: SourceFactory, typeOf: (Expr) -> SynType?): Map<String, SynType> {
    val bindings = linkedMapOf<String, SynType>()
    val segments = (call.func as Expr.Path).path.segments.toList()
    val ownerParameters =
        factory.generics
            ?.params
            ?.toList()
            .orEmpty()
            .filterIsInstance<GenericParam.TypeParam>()
            .map { it.ident.toString() }
    val functionParameters =
        factory.sig.generics.params
            .toList()
            .filterIsInstance<GenericParam.TypeParam>()
            .map { it.ident.toString() }
    fun explicit(index: Int, parameters: List<String>) {
        val arguments =
            (segments.getOrNull(index)?.arguments as? PathArguments.AngleBracketed)
                ?.args
                ?.toList()
                .orEmpty()
                .filterIsInstance<GenericArgument.TypeArg>()
                .map { it.type }
        parameters.zip(arguments).filter { it.second !is SynType.Infer }.forEach { bindings[it.first] = it.second }
    }
    if (segments.size == 2) explicit(0, ownerParameters)
    explicit(segments.lastIndex, functionParameters)
    factory.sig.inputs.toList().filterIsInstance<FnArg.Typed>().zip(call.args.toList()).forEach { (input, argument) ->
        typeOf(argument)?.let { bindFactoryType(input.patType.ty, it, factory.parameters().toSet(), bindings) }
    }
    return bindings
}

private fun KotlinJ2KEmitter.factoryRequirements(factory: SourceFactory, visiting: Set<SourceFactory> = emptySet()): List<String> {
    SourceFactoryCache.requirements[factory]?.let { return it }
    if (factory in visiting) return emptyList()
    val required = linkedSetOf<String>()
    val declaration = transparentDeclaration(factory.owner)
    if (declaration != null && sourceTransparentField(declaration, requireNotNull(factory.owner)) != null &&
        transparentConstructorField(factory.owner, factory.block) != null
    ) {
        val leaf = transparentFactoryLayoutType(declaration, factory.owner)
        factory.parameters().filterTo(required) { rustTypeIdentToKotlin(it) == leaf }
    }
    val parameterTypes =
        factory.sig.inputs
            .toList()
            .filterIsInstance<FnArg.Typed>()
            .mapNotNull {
                val name = (it.patType.pat as? Pat.Ident)?.ident?.toString() ?: return@mapNotNull null
                name to it.patType.ty
            }.toMap()
    object : Visit() {
        override fun visitExprCall(e: Expr.Call) {
            val called = calledFactory(e, factory.owner)
            if (called != null) {
                val bindings =
                    factoryBindings(e, called) { expression ->
                        val name =
                            (unwrapParenOrGroupExpr(expression) as? Expr.Path)
                                ?.path
                                ?.segments
                                ?.toList()
                                ?.singleOrNull()
                                ?.ident
                                ?.toString()
                        parameterTypes[name]
                    }
                factoryRequirements(called, visiting + factory).forEach { parameter ->
                    val actual = bindings[parameter]
                    val name =
                        (actual as? SynType.Path)
                            ?.takeIf { it.qself == null }
                            ?.path
                            ?.segments
                            ?.toList()
                            ?.singleOrNull()
                            ?.ident
                            ?.toString()
                    if (name in factory.parameters()) {
                        required += requireNotNull(name)
                    } else if (actual == null && called.owner == factory.owner && parameter in factory.parameters()) {
                        required += parameter
                    }
                }
            }
            super.visitExprCall(e)
        }
    }.visitBlock(factory.block ?: return required.toList())
    return required.toList().also { if (visiting.isEmpty()) SourceFactoryCache.requirements[factory] = it }
}

private fun factoryLayoutName(parameter: String) = "typeLayout${rustTypeIdentToKotlin(parameter)}"

internal fun KotlinJ2KEmitter.genericFactorySignatureParameters(sig: Signature, block: Block?, selfType: String?): List<Pair<String, String>> {
    val owner = selfType?.substringBefore('<')
    val factory =
        sourceFactories().singleOrNull { it.owner == owner && it.sig === sig }
            ?: sourceFactories().singleOrNull { it.owner == owner && it.sig.ident == sig.ident && it.block == block }
            ?: return emptyList()
    val existing = transparentConstructorField(selfType, block)?.second
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    return factoryRequirements(factory).filter { rustTypeIdentToKotlin(it) != existing }.map {
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.TypeLayout")
        factoryLayoutName(it) to "TypeLayout<${rustTypeIdentToKotlin(it)}>"
    }
}

internal fun KotlinJ2KEmitter.genericFactoryCallArguments(call: Expr.Call, selfType: String?): List<String> {
    val factory = calledFactory(call, selfType?.substringBefore('<')) ?: return emptyList()
    val required = factoryRequirements(factory)
    val existing = transparentConstructorField(factory.owner, factory.block)?.second
    val bindings = factoryBindings(call, factory, ::sourceInitializerType)
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    return required.filter { rustTypeIdentToKotlin(it) != existing }.map { parameter ->
        val actual =
            bindings[parameter] ?: currentStandaloneImplSelfSynType
                ?.takeIf {
                    factory.owner == selfType?.substringBefore('<')
                }?.let { owner ->
                    val arguments =
                        (
                            (owner as? SynType.Path)
                                ?.path
                                ?.segments
                                ?.last()
                                ?.arguments as? PathArguments.AngleBracketed
                        )?.args?.toList().orEmpty().filterIsInstance<GenericArgument.TypeArg>()
                    val names =
                        factory.generics
                            ?.params
                            ?.toList()
                            .orEmpty()
                            .filterIsInstance<GenericParam.TypeParam>()
                            .map { it.ident.toString() }
                    arguments.getOrNull(names.indexOf(parameter))?.type
                }
        val layout =
            if (actual != null) {
                sourcePointerLayout(actual, selfType) ?: run {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.UnresolvedTypeLayout")
                    "UnresolvedTypeLayout<${renderType(actual, selfType)}>()"
                }
            } else {
                val fallbackRendered =
                    call.args
                        .toList()
                        .firstOrNull()
                        ?.let { inferExprRenderedType(it, selfType) } ?: "Any?"
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.UnresolvedTypeLayout")
                "UnresolvedTypeLayout<$fallbackRendered>()"
            }
        "${factoryLayoutName(parameter)} = $layout"
    }
}

internal fun KotlinJ2KEmitter.sourceFactoryCallLayout(call: Expr.Call, declaration: Item.Struct, selfType: String?): String {
    val factory = calledFactory(call, selfType?.substringBefore('<'))
    val bindings = factory?.let { factoryBindings(call, it, ::sourceInitializerType) }.orEmpty()
    val (leaf, substitutions) = transparentFactoryLeaf(declaration, selfType)
    sourcePointerLayout(leaf, selfType, substitutions = substitutions + bindings)?.let { return it }
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.UnresolvedTypeLayout")
    return "UnresolvedTypeLayout<${renderType(substituteSourceType(leaf, substitutions + bindings), selfType)}>()"
}

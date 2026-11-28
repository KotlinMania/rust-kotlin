package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.WherePredicate

internal val PRIMITIVE_ARRAY_TYPES: Set<String>
    get() = activeRules().words("primitiveArrayTypes")
internal val IGNORED_USE_ROOTS: Set<String>
    get() = activeRules().words("ignoredUseRoots")
internal val STDLIB_CRATE_ROOTS: Set<String>
    get() = activeRules().words("stdlibCrateRoots")
internal val STDLIB_MACRO_OR_PRIMITIVE_MODULES: Set<String>
    get() = activeRules().words("stdlibMacroOrPrimitiveModules")
internal val FLAT_KOTLINMANIA_CRATES: Set<String>
    get() = activeRules().words("flatKotlinmaniaCrates")
internal val STDLIB_TYPE_PATH_PREFIXES: Set<String>
    get() = activeRules().words("stdlibTypePathPrefixes")
internal val IGNORED_PATH_PREFIXES: Set<String>
    get() = activeRules().words("ignoredPathPrefixes")
internal val STDLIB_PRELUDE_REEXPORT_MODULES: Set<String>
    get() = activeRules().words("stdlibPreludeReexportModules")
internal val IGNORED_TRAIT_BOUNDS: Set<String>
    get() = activeRules().words("ignoredTraitBounds")
internal val FMT_TRAIT_NAMES: Set<String>
    get() = activeRules().words("fmtTraitNames")
internal val RUST_STD_IMPLICIT_SUPERTRAITS: Set<String>
    get() = activeRules().unionWords("fmtTraitNames", "rustStdImplicitSupertraitsExtra")
internal val STDLIB_TRAIT_SUBMODULES: Set<String>
    get() = activeRules().words("stdlibTraitSubmodules")
internal val STDLIB_EXPLICIT_SUPERTRAITS: Set<String>
    get() = activeRules().unionWords("fmtTraitNames", "rustStdImplicitSupertraitsExtra", "stdlibExplicitSupertraitsExtra")

private fun SynType.singleUnqualifiedIdent(): String? {
    val seg = unqualifiedPath()?.singleSegment() ?: return null
    return seg.ident.toString()
}

internal fun matchesUnspecializedOwnerGenerics(
    selfArgs: PathArguments,
    declarationGenerics: Generics,
): Boolean {
    val declParams = declarationGenerics.params.toList()
    val ownerParameters = declParams.filterIsInstance<GenericParam.TypeParam>().map { it.ident.toString() }
    val ownerConstParameters = declParams.filterIsInstance<GenericParam.ConstParam>().map { it.ident.toString() }
    val rawArgsList = (selfArgs as? PathArguments.AngleBracketed)?.args?.toList().orEmpty()
    if (ownerParameters.isNotEmpty()) {
        val suppliedTypeArgs =
            rawArgsList
                .filterIsInstance<GenericArgument.TypeArg>()
                .take(ownerParameters.size)
                .map { it.type.singleUnqualifiedIdent() }
        if (suppliedTypeArgs != ownerParameters) return false
    }
    if (ownerConstParameters.isNotEmpty() && rawArgsList.isNotEmpty()) {
        val suppliedConstIdents =
            rawArgsList.filterIsInstance<GenericArgument.ConstArg>().map { ca ->
                val constPath = ca.expr.unqualifiedPath()
                constPath?.singleSegment()?.identText()
            }
        if (suppliedConstIdents.isNotEmpty() && suppliedConstIdents != ownerConstParameters) {
            return false
        }
        val extraTypeIdents =
            rawArgsList
                .filterIsInstance<GenericArgument.TypeArg>()
                .drop(ownerParameters.size)
                .map { it.type.singleUnqualifiedIdent() }
        if (extraTypeIdents.isNotEmpty() && extraTypeIdents != ownerConstParameters) {
            return false
        }
    }
    return true
}

internal fun declaresExtraNonImplicitImplTraitBounds(
    impl: Item.Impl,
    ownerDeclGenerics: Generics,
    isImplicitTraitPath: (Path) -> Boolean,
): Boolean {
    fun hasNonImplicitBound(bounds: List<TypeParamBound>): Boolean =
        bounds.any { b ->
            if (b !is TypeParamBound.Trait || b.maybe != null) return@any false
            val rawName = b.path.lastCleanIdent() ?: return@any false
            rawName !in IGNORED_TRAIT_BOUNDS && !isImplicitTraitPath(b.path)
        }

    fun collectBoundedTypeParams(generics: Generics): Set<String> {
        val bounded = hashSetOf<String>()
        for (gp in generics.params.toList()) {
            if (gp is GenericParam.TypeParam && hasNonImplicitBound(gp.bounds.toList())) {
                bounded += rustTypeIdentToKotlin(gp.ident.toString())
            }
        }
        val whereClause = generics.whereClause
        val predicates = whereClause?.predicates?.toList().orEmpty()
        for (pred in predicates) {
            if (pred is WherePredicate.TypePredicate && hasNonImplicitBound(pred.bounds.toList())) {
                val tpRaw = unwrapSynType(pred.boundedTy).singleUnqualifiedIdent()
                if (tpRaw != null) bounded += rustTypeIdentToKotlin(tpRaw)
            }
        }
        return bounded
    }

    val ownerTypeParams = ownerDeclGenerics.typeParamIdentNames().mapTo(linkedSetOf(), ::rustTypeIdentToKotlin)
    if (ownerTypeParams.isEmpty()) return false
    val unboundedOwnerParams = ownerTypeParams - collectBoundedTypeParams(ownerDeclGenerics)
    return unboundedOwnerParams.isNotEmpty() && collectBoundedTypeParams(impl.generics).any { it in unboundedOwnerParams }
}

internal fun KotlinJ2KEmitter.isImplicitStdlibSupertraitPath(path: Path): Boolean {
    val segs = path.cleanIdentStrings()
    val rawTrait = segs.lastOrNull() ?: return false
    val ktTrait = rustTypeIdentToKotlin(rawTrait)
    if (segs.size >= 2) {
        val isStdRoot = segs.first() in STDLIB_CRATE_ROOTS
        val prefixSubmod = segs.dropLast(1).joinToString("::")
        if ((isStdRoot || prefixSubmod in STDLIB_TRAIT_SUBMODULES) && ktTrait in STDLIB_EXPLICIT_SUPERTRAITS) {
            return true
        }
    }
    if (ktTrait in RUST_STD_IMPLICIT_SUPERTRAITS && ktTrait !in inspector.declaredTypeNames) {
        return true
    }
    return ktTrait == "Error" && ktTrait !in inspector.declaredTraitsByName
}

internal fun ensureBlankLineBeforeKDocAfterLineComment(text: String): String {
    if ("/**" !in text) return text
    return KDocSpacingRewriter(text).rewrite()
}

private class KDocSpacingRewriter(
    source: String,
) : KotlinTokenRewriter(source) {
    private var parenDepth: Int = 0
    private var prevLineStartedWithLineComment: Boolean = false
    private var currentLineStartedWithLineComment: Boolean = false
    private var atLineStart: Boolean = true

    override fun emitAt(index: Int, out: StringBuilder): Int {
        if (atLineStart) {
            val firstCodeIdx = skipHorizontalWhitespace(index)
            val firstTok = tokens.getOrNull(firstCodeIdx)
            currentLineStartedWithLineComment = firstTok is KotlinLexToken.LineComment
            if (prevLineStartedWithLineComment &&
                parenDepth == 0 &&
                firstTok is KotlinLexToken.BlockComment &&
                firstTok.rawText.startsWith("/**")
            ) {
                out.append('\n')
            }
            atLineStart = false
        }
        val tok = tokens[index]
        out.append(tok.rawText)
        when {
            tok is KotlinLexToken.Newline -> {
                prevLineStartedWithLineComment = currentLineStartedWithLineComment
                currentLineStartedWithLineComment = false
                atLineStart = true
            }
            tok is KotlinLexToken.BlockComment && '\n' in tok.rawText -> currentLineStartedWithLineComment = false
            tok is KotlinLexToken.StringText && '\n' in tok.rawText -> currentLineStartedWithLineComment = false
            tok is KotlinLexToken.Punct && tok.interpolationDepth == 0 ->
                when (tok.ch) {
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                }
        }
        return index + 1
    }
}

internal val STDLIB_ERASED_OR_BUILTIN_IMPORTS: Set<String>
    get() = activeRules().unionWords("fmtTraitNames", "rustStdImplicitSupertraitsExtra", "stdlibErasedOrBuiltinImportsExtra")
internal val KNOWN_SELF_PARAMETERIZED_TRAITS: Set<String>
    get() = activeRules().words("knownSelfParameterizedTraits")
internal val KOTLIN_COLLECTION_INTERFACES: Set<String>
    get() = activeRules().words("kotlinCollectionInterfaces")
internal val MUTATING_VEC_METHODS: Set<String>
    get() = activeRules().words("mutatingVecMethods")
internal val KNOWN_OPTION_RETURNING_METHODS: Set<String>
    get() = activeRules().words("knownOptionReturningMethods")
internal val KOTLIN_JVM_VALUE_TYPES: Set<String>
    get() = activeRules().words("kotlinJvmValueTypes")

private val NUMERIC_CAST_TARGETS_MEMO =
    RegistryMemo { reg -> reg.words("kotlinJvmValueTypes") - "Boolean" }

internal val NUMERIC_CAST_TARGETS: Set<String>
    get() = NUMERIC_CAST_TARGETS_MEMO.get()

internal val KOTLIN_STDLIB_SHADOWED_IMPORTS: Set<String>
    get() = activeRules().unionWords("kotlinJvmValueTypes", "kotlinStdlibShadowedImportsExtra")
internal val FORWARD_DESERIALIZE_KEYS: Set<String>
    get() = activeRules().words("forwardDeserializeKeys")
internal val PRIMITIVE_TYPE_NAMES: Set<String>
    get() = activeRules().words("primitiveTypeNames")
internal val IGNORED_QUOTE_KEYWORDS: Set<String>
    get() = activeRules().unionWords("ignoredQuoteKeywordsExtra", "primitiveTypeNames")

internal val SYN_KEYWORD_TOKENS: Set<String>
    get() = activeRules().words("synKeywordTokens")
internal val SYN_PUNCT_TOKEN_NAMES: Map<String, String>
    get() = activeRules().map("synPunctTokens")

private val SYN_KEYWORD_TOKEN_CLASS_NAMES_MEMO =
    RegistryMemo { reg ->
        reg.words("synKeywordTokens").mapTo(linkedSetOf()) { it[0].uppercaseChar() + it.substring(1) }
    }

private val SYN_PUNCT_TOKEN_CLASS_NAMES_MEMO =
    RegistryMemo { reg -> reg.map("synPunctTokens").values.toSet() }

internal val SYN_KEYWORD_TOKEN_CLASS_NAMES: Set<String>
    get() = SYN_KEYWORD_TOKEN_CLASS_NAMES_MEMO.get()

internal val SYN_PUNCT_TOKEN_CLASS_NAMES: Set<String>
    get() = SYN_PUNCT_TOKEN_CLASS_NAMES_MEMO.get()

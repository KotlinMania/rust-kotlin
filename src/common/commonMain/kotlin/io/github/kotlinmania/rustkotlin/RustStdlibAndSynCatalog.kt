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

internal val PRIMITIVE_ARRAY_TYPES =
    wordSet("ByteArray UByteArray ShortArray UShortArray IntArray UIntArray LongArray ULongArray FloatArray DoubleArray BooleanArray")
internal val IGNORED_USE_ROOTS = wordSet("super self")
internal val STDLIB_CRATE_ROOTS = wordSet("std core alloc")
internal val STDLIB_MACRO_OR_PRIMITIVE_MODULES = wordSet("primitive concat stringify")
internal val FLAT_KOTLINMANIA_CRATES = wordSet("procmacro2 quote")
internal val STDLIB_TYPE_PATH_PREFIXES =
    wordSet("std core alloc vec boxed collections rc sync slice option marker mem")
internal val IGNORED_PATH_PREFIXES = wordSet("crate super self std core alloc")
internal val STDLIB_PRELUDE_REEXPORT_MODULES =
    wordSet("__private export prelude v1 rust_2015 rust_2018 rust_2021 rust_2024")
internal val IGNORED_TRAIT_BOUNDS =
    wordSet(
        "Sized MetaSized PointeeSized Unsize CoerceUnsized DispatchFromDyn",
        "Send Sync Unpin UnwindSafe RefUnwindSafe TrivialDrop Destruct Hasher Tuple",
    )
internal val FMT_TRAIT_NAMES: Set<String> =
    wordSet("Debug Display Octal LowerHex UpperHex Binary LowerExp UpperExp Pointer")
internal val RUST_STD_IMPLICIT_SUPERTRAITS =
    FMT_TRAIT_NAMES +
        wordSet(
            "Clone Copy Default PartialEq Eq PartialOrd Ord Hash Drop",
            "Send Sync Unpin UnwindSafe RefUnwindSafe",
            "Iterator ExactSizeIterator DoubleEndedIterator FusedIterator FromIterator IntoIterator Extend",
            "Index IndexMut Deref DerefMut From TryFrom Into TryInto",
        )
internal val STDLIB_TRAIT_SUBMODULES =
    wordSet("error fmt cmp hash ops convert iter borrow marker panic any clone default")
internal val STDLIB_EXPLICIT_SUPERTRAITS =
    RUST_STD_IMPLICIT_SUPERTRAITS + wordSet("Error Borrow")

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

internal val STDLIB_ERASED_OR_BUILTIN_IMPORTS =
    RUST_STD_IMPLICIT_SUPERTRAITS +
        wordSet(
            "Hasher Formatter DebugStruct DebugTuple DebugList DebugSet DebugMap",
            "ManuallyDrop needs_drop PhantomData ThreadId Rc Arc VecDeque None Some Ok Err TypeId",
        )
internal val KNOWN_SELF_PARAMETERIZED_TRAITS = wordSet("Parse ParseQuote Read Write")
internal val KOTLIN_COLLECTION_INTERFACES =
    wordSet("List MutableList Map MutableMap Set MutableSet ArrayDeque")
internal val MUTATING_VEC_METHODS =
    wordSet("push pop clear insert remove truncate retain extend extend_from_slice append drain swap_remove")
internal val KNOWN_OPTION_RETURNING_METHODS =
    wordSet(
        "take pop pop_front removeFirstOrNull removeLastOrNull next next_back",
        "first first_mut last last_mut get get_mut getOrNull punct punct_mut",
        "find position rposition checked_add checked_sub checked_mul checked_div",
        "checked_rem checked_shl checked_shr checked_neg strip_prefix strip_suffix",
        "split_once rsplit_once get_key_value",
    )
internal val KOTLIN_JVM_VALUE_TYPES =
    wordSet("Boolean Byte Short Int Long Float Double Char UByte UShort UInt ULong")
internal val NUMERIC_CAST_TARGETS = KOTLIN_JVM_VALUE_TYPES - "Boolean"
internal val KOTLIN_STDLIB_SHADOWED_IMPORTS =
    KOTLIN_JVM_VALUE_TYPES +
        wordSet(
            "String Unit Nothing Any Result Iterator Iterable Comparable",
            "List MutableList Map MutableMap Set MutableSet Pair Triple",
            "Vec Box HashMap BTreeMap HashSet BTreeSet",
        )
internal val FORWARD_DESERIALIZE_KEYS =
    wordSet(
        "bool i8 i16 i32 i64 i128 u8 u16 u32 u64 u128 f32 f64 char str string",
        "bytes byte_buf option unit unit_struct newtype_struct seq tuple tuple_struct",
        "map struct enum identifier ignored_any",
    )
internal val PRIMITIVE_TYPE_NAMES =
    wordSet("u8 u16 u32 u64 u128 usize i8 i16 i32 i64 i128 isize f16 f32 f64 f128 bool str char")
internal val IGNORED_QUOTE_KEYWORDS =
    wordSet(
        "use as crate const let mut match fn impl for in where pub return if else",
        "struct enum trait type static unsafe ref dyn loop while break continue",
        "move async await super default union try yield mod",
    ) + PRIMITIVE_TYPE_NAMES

internal val SYN_KEYWORD_TOKENS =
    wordSet(
        "abstract as async auto await become box break const continue crate default",
        "do dyn else enum extern final fn for if impl in let loop macro match mod",
        "move mut override priv pub raw ref return safe static struct super trait",
        "try typeof union unsafe unsized use virtual where while yield",
    )

internal val SYN_PUNCT_TOKEN_NAMES =
    mapOf(
        "&" to "And",
        "&&" to "AndAnd",
        "&=" to "AndEq",
        "@" to "At",
        "^" to "Caret",
        "^=" to "CaretEq",
        ":" to "Colon",
        "," to "Comma",
        "$" to "Dollar",
        "." to "Dot",
        ".." to "DotDot",
        "..." to "DotDotDot",
        "..=" to "DotDotEq",
        "=" to "Eq",
        "==" to "EqEq",
        "=>" to "FatArrow",
        ">=" to "Ge",
        ">" to "Gt",
        "<-" to "LArrow",
        "<=" to "Le",
        "<" to "Lt",
        "-" to "Minus",
        "-=" to "MinusEq",
        "!=" to "Ne",
        "!" to "Not",
        "|" to "Or",
        "|=" to "OrEq",
        "||" to "OrOr",
        "::" to "PathSep",
        "%" to "Percent",
        "%=" to "PercentEq",
        "+" to "Plus",
        "+=" to "PlusEq",
        "#" to "Pound",
        "?" to "Question",
        "->" to "RArrow",
        ";" to "Semi",
        "<<" to "Shl",
        "<<=" to "ShlEq",
        ">>" to "Shr",
        ">>=" to "ShrEq",
        "/" to "Slash",
        "/=" to "SlashEq",
        "*" to "Star",
        "*=" to "StarEq",
        "~" to "Tilde",
    )

internal val SYN_KEYWORD_TOKEN_CLASS_NAMES =
    SYN_KEYWORD_TOKENS.mapTo(linkedSetOf()) { it[0].uppercaseChar() + it.substring(1) }

internal val SYN_PUNCT_TOKEN_CLASS_NAMES =
    SYN_PUNCT_TOKEN_NAMES.values.toSet()

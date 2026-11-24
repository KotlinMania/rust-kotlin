package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.WherePredicate

internal val PRIMITIVE_ARRAY_TYPES =
    setOf(
        "ByteArray",
        "UByteArray",
        "ShortArray",
        "UShortArray",
        "IntArray",
        "UIntArray",
        "LongArray",
        "ULongArray",
        "FloatArray",
        "DoubleArray",
        "BooleanArray",
    )
internal val IGNORED_USE_ROOTS = setOf("super", "self")
internal val STDLIB_CRATE_ROOTS = setOf("std", "core", "alloc")
internal val STDLIB_MACRO_OR_PRIMITIVE_MODULES = setOf("primitive", "concat", "stringify")
internal val FLAT_KOTLINMANIA_CRATES = setOf("procmacro2", "quote")
internal val STDLIB_TYPE_PATH_PREFIXES =
    setOf("std", "core", "alloc", "vec", "boxed", "collections", "rc", "sync", "slice", "option", "marker", "mem")
internal val IGNORED_PATH_PREFIXES = setOf("crate", "super", "self", "std", "core", "alloc")
internal val STDLIB_PRELUDE_REEXPORT_MODULES =
    setOf("__private", "export", "prelude", "v1", "rust_2015", "rust_2018", "rust_2021", "rust_2024")
internal val IGNORED_TRAIT_BOUNDS =
    setOf(
        "Sized",
        "MetaSized",
        "PointeeSized",
        "Unsize",
        "CoerceUnsized",
        "DispatchFromDyn",
        "Send",
        "Sync",
        "Unpin",
        "UnwindSafe",
        "RefUnwindSafe",
        "TrivialDrop",
        "Destruct",
        "Hasher",
        "Tuple",
    )
internal val RUST_STD_IMPLICIT_SUPERTRAITS =
    setOf(
        "Clone",
        "Copy",
        "Debug",
        "Default",
        "PartialEq",
        "Eq",
        "PartialOrd",
        "Ord",
        "Hash",
        "Drop",
        "Send",
        "Sync",
        "Unpin",
        "UnwindSafe",
        "RefUnwindSafe",
        "Iterator",
        "ExactSizeIterator",
        "DoubleEndedIterator",
        "FusedIterator",
        "FromIterator",
        "IntoIterator",
        "Extend",
        "Index",
        "IndexMut",
        "Deref",
        "DerefMut",
        "Display",
        "Octal",
        "LowerHex",
        "UpperHex",
        "Binary",
        "LowerExp",
        "UpperExp",
        "Pointer",
        "From",
        "TryFrom",
        "Into",
        "TryInto",
    )

internal val STDLIB_TRAIT_SUBMODULES =
    setOf(
        "error",
        "fmt",
        "cmp",
        "hash",
        "ops",
        "convert",
        "iter",
        "borrow",
        "marker",
        "panic",
        "any",
        "clone",
        "default",
    )

internal val STDLIB_EXPLICIT_SUPERTRAITS =
    RUST_STD_IMPLICIT_SUPERTRAITS + setOf("Error", "Borrow")

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
                .map { argument ->
                    (argument.type as? SynType.Path)
                        ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                        ?.path
                        ?.segments
                        ?.last()
                        ?.ident
                        ?.toString()
                }
        // A specialized impl belongs on its exact receiver, not on
        // every instantiation of the generic source declaration.
        if (suppliedTypeArgs != ownerParameters) return false
    }
    if (ownerConstParameters.isNotEmpty() && rawArgsList.isNotEmpty()) {
        val suppliedConstIdents =
            rawArgsList.filterIsInstance<GenericArgument.ConstArg>().map { ca ->
                (ca.expr as? io.github.kotlinmania.syn.Expr.Path)
                    ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                    ?.path
                    ?.segments
                    ?.last()
                    ?.ident
                    ?.toString()
            }
        if (suppliedConstIdents.isNotEmpty() && suppliedConstIdents != ownerConstParameters) {
            return false
        }
        val extraTypeIdents =
            rawArgsList.filterIsInstance<GenericArgument.TypeArg>().drop(ownerParameters.size).map { argument ->
                (argument.type as? SynType.Path)
                    ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                    ?.path
                    ?.segments
                    ?.last()
                    ?.ident
                    ?.toString()
            }
        if (extraTypeIdents.isNotEmpty() && extraTypeIdents != ownerConstParameters) {
            return false
        }
    }
    return true
}

internal fun declaresExtraNonImplicitImplTraitBounds(
    impl: Item.Impl,
    ownerDeclGenerics: Generics,
    isImplicitTraitPath: (io.github.kotlinmania.syn.Path) -> Boolean,
): Boolean {
    fun hasNonImplicitBound(bounds: List<TypeParamBound>): Boolean =
        bounds.any { b ->
            if (b !is TypeParamBound.Trait || b.maybe != null) return@any false
            val rawName =
                b.path.segments
                    .last()
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#") ?: return@any false
            if (rawName in IGNORED_TRAIT_BOUNDS) return@any false
            !isImplicitTraitPath(b.path)
        }

    val ownerDeclTypeParams = ownerDeclGenerics.params.toList().filterIsInstance<GenericParam.TypeParam>()
    if (ownerDeclTypeParams.isEmpty()) return false
    val boundedOwnerParams = hashSetOf<String>()
    for (gp in ownerDeclTypeParams) {
        if (hasNonImplicitBound(gp.bounds.toList())) {
            boundedOwnerParams += rustTypeIdentToKotlin(gp.ident.toString())
        }
    }
    for (pred in ownerDeclGenerics.whereClause
        ?.predicates
        ?.toList()
        .orEmpty()) {
        if (pred is WherePredicate.TypePredicate) {
            val boundedPath = (unwrapSynType(pred.boundedTy) as? SynType.Path)?.takeIf { it.qself == null && it.path.segments.size == 1 }
            val tpKt =
                boundedPath
                    ?.path
                    ?.segments
                    ?.first()
                    ?.ident
                    ?.toString()
                    ?.let(::rustTypeIdentToKotlin)
            if (tpKt != null && hasNonImplicitBound(pred.bounds.toList())) {
                boundedOwnerParams += tpKt
            }
        }
    }
    val unboundedOwnerParams =
        ownerDeclTypeParams
            .map { rustTypeIdentToKotlin(it.ident.toString()) }
            .toSet() - boundedOwnerParams
    if (unboundedOwnerParams.isEmpty()) return false
    for (gp in impl.generics.params.toList()) {
        if (gp is GenericParam.TypeParam) {
            val tpKt = rustTypeIdentToKotlin(gp.ident.toString())
            if (tpKt in unboundedOwnerParams && hasNonImplicitBound(gp.bounds.toList())) {
                return true
            }
        }
    }
    for (pred in impl.generics.whereClause
        ?.predicates
        ?.toList()
        .orEmpty()) {
        if (pred is WherePredicate.TypePredicate) {
            val boundedPath = (unwrapSynType(pred.boundedTy) as? SynType.Path)?.takeIf { it.qself == null && it.path.segments.size == 1 }
            val tpKt =
                boundedPath
                    ?.path
                    ?.segments
                    ?.first()
                    ?.ident
                    ?.toString()
                    ?.let(::rustTypeIdentToKotlin)
            if (tpKt in unboundedOwnerParams && hasNonImplicitBound(pred.bounds.toList())) {
                return true
            }
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.isImplicitStdlibSupertraitPath(path: io.github.kotlinmania.syn.Path): Boolean {
    val segs = path.segments.toList().map { it.ident.toString().removePrefix("r#") }
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
    if (ktTrait == "Error" && ktTrait !in inspector.declaredTraitsByName) {
        return true
    }
    return false
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
        setOf(
            "Hasher",
            "Formatter",
            "DebugStruct",
            "DebugTuple",
            "DebugList",
            "DebugSet",
            "DebugMap",
            "ManuallyDrop",
            "needs_drop",
            "PhantomData",
            "ThreadId",
            "Rc",
            "Arc",
            "VecDeque",
            "None",
            "Some",
            "Ok",
            "Err",
            "TypeId",
            "Into",
            "From",
            "Octal",
            "LowerHex",
            "UpperHex",
            "Binary",
            "LowerExp",
            "UpperExp",
            "Pointer",
        )
internal val FMT_TRAIT_NAMES: Set<String> =
    setOf("Debug", "Display", "Octal", "LowerHex", "UpperHex", "Binary", "LowerExp", "UpperExp", "Pointer")
internal val KNOWN_SELF_PARAMETERIZED_TRAITS = setOf("Parse", "ParseQuote", "Read", "Write")
internal val KOTLIN_COLLECTION_INTERFACES = setOf("List", "MutableList", "Map", "MutableMap", "Set", "MutableSet", "ArrayDeque")
internal val MUTATING_VEC_METHODS =
    setOf("push", "pop", "clear", "insert", "remove", "truncate", "retain", "extend", "extend_from_slice", "append", "drain", "swap_remove")
internal val KNOWN_OPTION_RETURNING_METHODS =
    setOf(
        "take",
        "pop",
        "pop_front",
        "removeFirstOrNull",
        "removeLastOrNull",
        "next",
        "next_back",
        "first",
        "first_mut",
        "last",
        "last_mut",
        "get",
        "get_mut",
        "getOrNull",
        "punct",
        "punct_mut",
        "find",
        "position",
        "rposition",
        "checked_add",
        "checked_sub",
        "checked_mul",
        "checked_div",
        "checked_rem",
        "checked_shl",
        "checked_shr",
        "checked_neg",
        "strip_prefix",
        "strip_suffix",
        "split_once",
        "rsplit_once",
        "get_key_value",
    )
internal val KOTLIN_JVM_VALUE_TYPES =
    setOf(
        "Boolean",
        "Byte",
        "Short",
        "Int",
        "Long",
        "Float",
        "Double",
        "Char",
        "UByte",
        "UShort",
        "UInt",
        "ULong",
    )
internal val NUMERIC_CAST_TARGETS = KOTLIN_JVM_VALUE_TYPES - "Boolean"
internal val KOTLIN_STDLIB_SHADOWED_IMPORTS =
    setOf(
        "String",
        "Boolean",
        "Char",
        "Byte",
        "Short",
        "Int",
        "Long",
        "UByte",
        "UShort",
        "UInt",
        "ULong",
        "Float",
        "Double",
        "Unit",
        "Nothing",
        "Any",
        "Result",
        "Iterator",
        "Iterable",
        "Comparable",
        "List",
        "MutableList",
        "Map",
        "MutableMap",
        "Set",
        "MutableSet",
        "Pair",
        "Triple",
        "Vec",
        "Box",
        "HashMap",
        "BTreeMap",
        "HashSet",
        "BTreeSet",
    )
internal val FORWARD_DESERIALIZE_KEYS =
    setOf(
        "bool",
        "i8",
        "i16",
        "i32",
        "i64",
        "i128",
        "u8",
        "u16",
        "u32",
        "u64",
        "u128",
        "f32",
        "f64",
        "char",
        "str",
        "string",
        "bytes",
        "byte_buf",
        "option",
        "unit",
        "unit_struct",
        "newtype_struct",
        "seq",
        "tuple",
        "tuple_struct",
        "map",
        "struct",
        "enum",
        "identifier",
        "ignored_any",
    )
internal val PRIMITIVE_TYPE_NAMES =
    setOf(
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
        "f16",
        "f32",
        "f64",
        "f128",
        "bool",
        "str",
        "char",
    )
internal val IGNORED_QUOTE_KEYWORDS =
    setOf(
        "use",
        "as",
        "crate",
        "const",
        "let",
        "mut",
        "match",
        "fn",
        "impl",
        "for",
        "in",
        "where",
        "pub",
        "return",
        "if",
        "else",
        "struct",
        "enum",
        "trait",
        "type",
        "static",
        "unsafe",
        "ref",
        "dyn",
        "loop",
        "while",
        "break",
        "continue",
        "move",
        "async",
        "await",
        "super",
        "default",
        "union",
        "try",
        "yield",
        "mod",
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
        "f16",
        "f32",
        "f64",
        "f128",
        "bool",
        "str",
        "char",
    )

internal val SYN_KEYWORD_TOKENS =
    setOf(
        "abstract",
        "as",
        "async",
        "auto",
        "await",
        "become",
        "box",
        "break",
        "const",
        "continue",
        "crate",
        "default",
        "do",
        "dyn",
        "else",
        "enum",
        "extern",
        "final",
        "fn",
        "for",
        "if",
        "impl",
        "in",
        "let",
        "loop",
        "macro",
        "match",
        "mod",
        "move",
        "mut",
        "override",
        "priv",
        "pub",
        "raw",
        "ref",
        "return",
        "safe",
        "static",
        "struct",
        "super",
        "trait",
        "try",
        "typeof",
        "union",
        "unsafe",
        "unsized",
        "use",
        "virtual",
        "where",
        "while",
        "yield",
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

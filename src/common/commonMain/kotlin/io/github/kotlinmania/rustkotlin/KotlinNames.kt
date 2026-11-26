package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.serderive.internals.RenameRule

public class KotlinNamingPolicy {
    public fun toValueIdentifier(raw: String, preserveScreamingSnake: Boolean = false): String {
        val cleaned = raw.removePrefix("r#").trim()
        if (cleaned.isEmpty() || cleaned == "_") return "unused"
        if (cleaned == "self") return "this"
        if (cleaned == "Self") return "Self"

        if (TranslationSession.rawAbiMode) {
            val safeIdent = if (cleaned.first().isDigit()) "v$cleaned" else cleaned
            val nonStub = sanitizeStubWord(safeIdent)
            return escapeKotlinHardKeyword(nonStub)
        }

        if (preserveScreamingSnake && isScreamingSnakeCase(cleaned)) {
            return sanitizeStubWord(cleaned)
        }

        val hasLower = cleaned.any { it.isLowerCase() }
        val hasUpper = cleaned.any { it.isUpperCase() }
        val hasUnderscore = cleaned.contains('_')

        val converted =
            when {
                !hasUnderscore && hasLower && !cleaned.first().isUpperCase() -> cleaned
                !hasUnderscore && !hasLower && hasUpper -> cleaned.lowercase()
                !hasUnderscore && cleaned.first().isUpperCase() ->
                    RenameRule.CamelCase.applyToVariant(cleaned)
                else -> {
                    val normalizedInput = normalizeMixedUnderscoreSegments(cleaned)
                    RenameRule.CamelCase.applyToField(normalizedInput)
                }
            }.ifEmpty { "unused" }

        val cleanConverted = if (preserveScreamingSnake) converted else converted.replace("_", "").ifEmpty { "unused" }
        val safeIdent = if (cleanConverted.first().isDigit()) "v$cleanConverted" else cleanConverted
        val nonStub = sanitizeStubWord(safeIdent)
        return escapeKotlinHardKeyword(nonStub)
    }

    public fun toConstIdentifier(raw: String): String {
        val cleaned = raw.removePrefix("r#").trim()
        if (cleaned.isEmpty() || cleaned.all { it == '_' }) return "UNUSED_CONST"
        if (TranslationSession.rawAbiMode) {
            val safeConst = if (cleaned.first().isDigit()) "C_$cleaned" else cleaned
            return escapeKotlinHardKeyword(sanitizeConstStubWord(safeConst))
        }
        if (isScreamingSnakeCase(cleaned)) {
            return sanitizeConstStubWord(cleaned)
        }
        val segments = cleaned.split('_').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return "UNUSED_CONST"
        val screamingSegments =
            segments.map { seg ->
                if (seg.all { it.isUpperCase() || it.isDigit() }) {
                    seg
                } else if (seg.all { it.isLowerCase() || it.isDigit() }) {
                    seg.uppercase()
                } else {
                    buildString(seg.length + 4) {
                        for ((idx, ch) in seg.withIndex()) {
                            if (idx > 0 && ch.isUpperCase()) {
                                val prev = seg[idx - 1]
                                val nextIsLower = idx + 1 < seg.length && seg[idx + 1].isLowerCase()
                                if (prev.isLowerCase() || (prev.isUpperCase() && nextIsLower)) {
                                    append('_')
                                }
                            }
                            append(ch.uppercaseChar())
                        }
                    }
                }
            }
        val joined = screamingSegments.joinToString("_").ifEmpty { "UNUSED_CONST" }
        val safeConst = if (joined.first().isDigit()) "C_$joined" else joined
        return sanitizeConstStubWord(safeConst)
    }

    public fun toTypeIdentifier(raw: String): String =
        toTypeIdentifier(raw, allowRawAbi = true)

    public fun toTypeIdentifier(raw: String, allowRawAbi: Boolean): String {
        val cleaned = raw.removePrefix("r#").trim()
        if (cleaned.isEmpty() || cleaned == "_") return "Any"
        PRIMITIVE_AND_BUILTIN_TYPE_MAP[cleaned]?.let { return it }
        if (TranslationSession.rawAbiMode && allowRawAbi) {
            val safeType = if (cleaned.first().isDigit()) "T$cleaned" else cleaned
            return escapeKotlinHardKeyword(sanitizeStubWord(safeType))
        }

        val hasUnderscore = cleaned.contains('_')
        val converted =
            if (!hasUnderscore && cleaned.first().isUpperCase()) {
                if (cleaned.all { it.isUpperCase() || it.isDigit() } && cleaned.length > 2) {
                    cleaned.first() + cleaned.substring(1).lowercase()
                } else {
                    cleaned
                }
            } else {
                val normalizedInput = normalizeMixedUnderscoreSegments(cleaned)
                RenameRule.PascalCase.applyToField(normalizedInput)
            }.replace("_", "").ifEmpty { "GeneratedType" }

        val safeType = if (converted.first().isDigit()) "T$converted" else converted
        return PRIMITIVE_AND_BUILTIN_TYPE_MAP[safeType] ?: sanitizeStubWord(safeType)
    }

    private fun sanitizeStubWord(ident: String): String =
        YamlRuleCatalogs.NamingAndComments.valueStubWordReplacements[ident.lowercase()] ?: ident

    private fun sanitizeConstStubWord(ident: String): String =
        YamlRuleCatalogs.NamingAndComments.constStubWordReplacements[ident.uppercase()] ?: ident

    internal fun isScreamingSnakeCase(s: String): Boolean {
        var hasUpper = false
        for (ch in s) {
            if (ch.isLowerCase()) return false
            if (ch.isUpperCase()) hasUpper = true
            if (!(ch.isUpperCase() || ch.isDigit() || ch == '_')) return false
        }
        return hasUpper && !s.startsWith('_') && !s.endsWith('_') && "__" !in s
    }

    private fun normalizeMixedUnderscoreSegments(s: String): String {
        val segments = s.split('_').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return "unused"
        return segments.joinToString("_") { seg ->
            if (seg.all { it.isUpperCase() || it.isDigit() }) {
                seg.lowercase()
            } else {
                buildString(seg.length) {
                    for ((idx, ch) in seg.withIndex()) {
                        if (idx > 0 && ch.isUpperCase() && !seg[idx - 1].isUpperCase()) {
                            append('_')
                        }
                        append(ch.lowercaseChar())
                    }
                }
            }
        }
    }

    private fun escapeKotlinHardKeyword(ident: String): String =
        KOTLIN_KEYWORD_REPLACEMENTS[ident] ?: ident

    public companion object {
        public val Default: KotlinNamingPolicy = KotlinNamingPolicy()

        private val PRIMITIVE_AND_BUILTIN_TYPE_MAP: Map<String, String>
            get() = YamlRuleCatalogs.NamingAndComments.primitiveAndBuiltinTypes

        private val KOTLIN_KEYWORD_REPLACEMENTS: Map<String, String>
            get() = YamlRuleCatalogs.NamingAndComments.kotlinHardKeywordEscapes
    }
}

internal fun rustIdentToKotlin(raw: String, preserveScreamingSnake: Boolean = false): String =
    KotlinNamingPolicy.Default.toValueIdentifier(raw, preserveScreamingSnake)

internal fun rustValueIdentToKotlin(raw: String): String =
    KotlinNamingPolicy.Default.toValueIdentifier(raw, preserveScreamingSnake = false)

internal fun rustFunctionIdentToKotlin(raw: String): String =
    KotlinNamingPolicy.Default.toValueIdentifier(raw, preserveScreamingSnake = false)

internal fun rustMethodIdentToKotlin(raw: String): String =
    KotlinNamingPolicy.Default.toValueIdentifier(raw, preserveScreamingSnake = false)

internal fun rustFieldIdentToKotlin(raw: String): String {
    val ident = KotlinNamingPolicy.Default.toValueIdentifier(raw, preserveScreamingSnake = false)
    if (TranslationSession.rawAbiMode || TranslationSession.finalStructs) {
        return YamlRuleCatalogs.NamingAndComments.rawAbiFieldRenames[ident] ?: ident
    }
    return ident
}

public fun rustModuleIdentToKotlin(raw: String): String {
    val cleaned = raw.removePrefix("r#").trim().removePrefix("__")
    if (cleaned == "private") return "priv"
    val prevRaw = TranslationSession.rawAbiMode
    TranslationSession.rawAbiMode = false
    return try {
        KotlinNamingPolicy.Default.toValueIdentifier(cleaned, preserveScreamingSnake = false).lowercase()
    } finally {
        TranslationSession.rawAbiMode = prevRaw
    }
}

internal fun rustConstIdentToKotlin(raw: String): String =
    KotlinNamingPolicy.Default.toConstIdentifier(raw)

internal fun rustTypeIdentToKotlin(raw: String): String =
    KotlinNamingPolicy.Default.toTypeIdentifier(raw)

internal fun tupleFieldPropertyName(index: Int, arity: Int): String =
    when {
        arity <= 1 && index == 0 -> "value"
        else -> "field$index"
    }

internal fun defaultInitializerForType(kotlinType: String): String {
    if (kotlinType == "CChar") return if (TranslationSession.rawAbiMode) "0" else "0u"
    YamlRuleCatalogs.NamingAndComments.defaultTypeInitializers[kotlinType]?.let { return it }
    if (isConstRawPointerRenderedType(kotlinType)) return "ConstPtr(0L)"
    if (isMutRawPointerRenderedType(kotlinType)) return "MutPtr(0L)"
    if (kotlinType.endsWith("?") || kotlinType in TranslationSession.crateOptionFnTypes) return "null"
    for ((prefix, initExpr) in YamlRuleCatalogs.NamingAndComments.prefixDefaultTypeInitializers) {
        if (kotlinType.startsWith(prefix)) return initExpr
    }
    TranslationSession.crateTypeAliasDefaults[kotlinType]?.let { return it }
    if (TranslationSession.rawAbiMode || TranslationSession.finalStructs) {
        return if (kotlinType == "Any") "Unit" else "$kotlinType()"
    }
    return "null as $kotlinType"
}

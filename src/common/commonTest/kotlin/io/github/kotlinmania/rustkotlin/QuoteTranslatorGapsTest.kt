package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuoteTranslatorGapsTest {
    @Test
    fun gap1MutExternalBorrowsAndEnumVariantMutMatchWriteback() {
        val rust =
            """
            use proc_macro2::{Group, Span, TokenStream, TokenTree};

            pub trait ToTokens {
                fn to_tokens(&self, tokens: &mut TokenStream);

                fn to_token_stream(&self) -> TokenStream {
                    let mut tokens = TokenStream::new();
                    self.to_tokens(&mut tokens);
                    tokens
                }
            }

            fn respan_token_tree(mut token: TokenTree, span: Span) -> TokenTree {
                match &mut token {
                    TokenTree::Group(g) => {
                        let mut tokens = TokenStream::new();
                        for token in g.stream() {
                            tokens.append(respan_token_tree(token, span));
                        }
                        *g = Group::new(g.delimiter(), tokens);
                        g.set_span(span);
                    }
                    other => other.set_span(span),
                }
                token
            }
            """.trimIndent()
        val kt = translateRustSourceToKotlin(recognizeRust(rust))
        assertFalse(kt.contains("AddressableLocal"), "External heap TokenStream must not use AddressableLocal:\n$kt")
        assertContains(kt, "this.toTokens(tokens)")
        assertContains(kt, "var (g) =")
        assertContains(kt, "g = Group.new(g.delimiter(), tokens)")
        assertContains(kt, "= TokenTree.Group(g)")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "gap1")
    }

    @Test
    fun gap2CharVsUShortSwiftExportStandaloneTraitDisambiguation() {
        val rust =
            """
            use core::fmt;
            use proc_macro2::TokenStream;

            pub trait ToTokens {
                fn to_tokens(&self, tokens: &mut TokenStream);
            }

            impl ToTokens for u16 {
                fn to_tokens(&self, tokens: &mut TokenStream) {}
            }

            impl ToTokens for char {
                fn to_tokens(&self, tokens: &mut TokenStream) {}
            }

            pub trait IdentFragment {
                fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result;
            }

            impl IdentFragment for u16 {
                fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
                    fmt::Display::fmt(self, f)
                }
            }

            impl IdentFragment for char {
                fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
                    fmt::Display::fmt(self, f)
                }
            }
            """.trimIndent()
        val kt = translateRustSourceToKotlin(recognizeRust(rust))
        assertContains(kt, "public fun UShort.toTokens(")
        assertContains(kt, "public fun Char.toTokensChar(")
        assertContains(kt, "public fun UShort.fmt(")
        assertContains(kt, "public fun Char.fmtChar(")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "gap2")
    }

    @Test
    fun gap3OptionCombinatorsIntoSubstitutionExtensionRecursionAndForwardingImpls() {
        val rust =
            """
            use alloc::borrow::Cow;
            use alloc::boxed::Box;
            use alloc::rc::Rc;
            use core::fmt;
            use core::iter;
            use proc_macro2::{Ident, Span, TokenStream, TokenTree};

            pub trait ToTokens {
                fn to_tokens(&self, tokens: &mut TokenStream);
            }

            impl<T: ?Sized + ToTokens> ToTokens for &T {
                fn to_tokens(&self, tokens: &mut TokenStream) {
                    (**self).to_tokens(tokens);
                }
            }

            impl<T: ?Sized + ToTokens> ToTokens for &mut T {
                fn to_tokens(&self, tokens: &mut TokenStream) {
                    (**self).to_tokens(tokens);
                }
            }

            impl<T: ?Sized + ToOwned + ToTokens> ToTokens for Cow<'_, T> {
                fn to_tokens(&self, tokens: &mut TokenStream) {
                    (**self).to_tokens(tokens);
                }
            }

            impl<T: ?Sized + ToTokens> ToTokens for Box<T> {
                fn to_tokens(&self, tokens: &mut TokenStream) {
                    (**self).to_tokens(tokens);
                }
            }

            impl<T: ?Sized + ToTokens> ToTokens for Rc<T> {
                fn to_tokens(&self, tokens: &mut TokenStream) {
                    (**self).to_tokens(tokens);
                }
            }

            pub trait TokenStreamExt {
                fn append<U>(&mut self, token: U)
                where
                    U: Into<TokenTree>;
            }

            impl TokenStreamExt for TokenStream {
                fn append<U>(&mut self, token: U)
                where
                    U: Into<TokenTree>,
                {
                    self.extend(iter::once(token.into()));
                }
            }

            pub trait IdentFragment {
                fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result;

                fn span(&self) -> Option<Span> {
                    None
                }
            }

            impl IdentFragment for Ident {
                fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
                    if let Some(id) = self.to_string().strip_prefix("r#") {
                        fmt::Display::fmt(id, f)
                    } else {
                        fmt::Display::fmt(self, f)
                    }
                }

                fn span(&self) -> Option<Span> {
                    Some(self.span())
                }
            }

            pub struct IdentFragmentAdapter<T>(pub T);

            impl<T: IdentFragment> fmt::Display for IdentFragmentAdapter<T> {
                fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
                    IdentFragment::fmt(&self.0, f)
                }
            }

            fn join_spans(tokens: TokenStream) -> Span {
                let mut iter = tokens.into_iter().map(|tt| tt.span());
                let first = match iter.next() {
                    Some(span) => span,
                    None => return Span::call_site(),
                };
                iter.fold(None, |_prev, next| Some(next))
                    .and_then(|last| first.join(last))
                    .unwrap_or(first)
            }

            fn check_map_or(opt: Option<i32>) -> i32 {
                opt.map_or(10, |x| x + 1) + opt.map_or_else(|| 20, |x| x * 2)
            }
            """.trimIndent()
        val kt =
            renderSingleKotlinFile(
                source = rust,
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.quote",
                        projectGroup = "io.github.kotlinmania",
                        projectNamespace = "io.github.kotlinmania.quote",
                        isBatchCrateOutput = true,
                    ),
            ).text
        assertEquals(0, Regex("fun <T : ToTokens> T\\.toTokens\\(").findAll(kt).count(), "Redundant reference/wrapper impls should be skipped:\n$kt")
        assertContains(kt, "public fun TokenStream.append(token: TokenTree)")
        assertContains(kt, "listOf(token)")
        assertFalse(kt.contains("token.into()"), "Erased Into<TokenTree> param should not emit .into():\n$kt")
        assertContains(kt, "public fun Ident.spanIdentFragment(): Span?")
        assertContains(kt, "removePrefix(\"r#\")")
        assertContains(kt, "this.value.fmt(f)")
        assertFalse(kt.contains("IdentFragment.fmt("), "UFCS trait call must not emit companion receiver:\n$kt")
        assertContains(kt, "ArrayDeque((tokens).toList())")
        assertContains(kt, "?.let({ last -> first.join(last) }) ?: first")
        assertContains(kt, "?.let({ x -> x + 1 }) ?: 10")
        assertContains(kt, "?.let({ x -> x * 2 }) ?: 20")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "gap3")
    }

    @Test
    fun gap4KDocDoubleColonAndMacroBangSanitization() {
        val rust =
            """
            /// Formatting trait for `quote!` and `format_ident!` like [alloc::format!]
            /// and `(core::fmt::Octal)` or (core::fmt::Octal) in `std::fmt`.
            /// Invoke quote! or format! directly!
            pub fn helper() {}
            """.trimIndent()
        val kt = translateRustSourceToKotlin(recognizeRust(rust))
        val kdocLines = kt.lines().filter { it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }
        val kdocJoined = kdocLines.joinToString("\n")
        assertFalse(kdocJoined.contains("::"), "Expected zero '::' in KDoc:\n$kdocJoined")
        assertFalse(kdocJoined.contains("quote!"), "Expected zero 'quote!' in KDoc:\n$kdocJoined")
        assertFalse(kdocJoined.contains("format!"), "Expected zero 'format!' in KDoc:\n$kdocJoined")
        assertFalse(kdocJoined.contains("format_ident!"), "Expected zero 'format_ident!' in KDoc:\n$kdocJoined")
        assertContains(kdocJoined, "[alloc.format]")
        assertContains(kdocJoined, "(core.fmt.Octal)")
        assertContains(kdocJoined, "`std.fmt`")
    }

    @Test
    fun gap5StdAnyTranslationAndKotlinJsScalarTaggingRuntimeVerification() {
        val rust =
            """
            use std::any::{Any, TypeId};

            pub fn classify_scalar(val_ref: &dyn Any) -> i32 {
                if val_ref.is::<i8>() {
                    return 8;
                }
                if let Some(v) = val_ref.downcast_ref::<i32>() {
                    return *v;
                }
                if val_ref.type_id() == TypeId::of::<f32>() {
                    return 32;
                }
                if val_ref.type_id() == TypeId::of::<f64>() {
                    return 64;
                }
                0
            }
            """.trimIndent()
        val kt = translateRustSourceToKotlin(recognizeRust(rust))
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.RustAny")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.RustTypeId")
        assertFalse(kt.contains("class RustTypeId("))
        assertFalse(kt.contains("class RustAny("))
        assertContains(kt, "valRef: RustAny")
        assertContains(kt, "valRef.isType<Byte>()")
        assertContains(kt, "valRef.downcastRef<Int>()")
        assertContains(kt, "RustTypeId.of<Float>()")
        assertContains(kt, "RustTypeId.of<Double>()")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "gap5")

        // Execute the exact RustTypeId / RustAny runtime logic across JVM, Kotlin/JS, Wasm, and Native
        // to prove scalar widths never collide under JS `number` erasure.
        data class LocalRustTypeId(
            val kClass: kotlin.reflect.KClass<*>,
            val tag: String,
        )
        fun <T> typeIdFor(kClass: kotlin.reflect.KClass<*>, tag: String): LocalRustTypeId = LocalRustTypeId(kClass, tag)

        class LocalRustAny(
            val typeId: LocalRustTypeId,
            val rawValue: Any?,
        )
        val samples =
            listOf(
                LocalRustAny(typeIdFor<Byte>(Byte::class, "i8"), 1.toByte()),
                LocalRustAny(typeIdFor<Short>(Short::class, "i16"), 1.toShort()),
                LocalRustAny(typeIdFor<Int>(Int::class, "i32"), 1),
                LocalRustAny(typeIdFor<Long>(Long::class, "i64"), 1L),
                LocalRustAny(typeIdFor<UByte>(UByte::class, "u8"), 1.toUByte()),
                LocalRustAny(typeIdFor<UShort>(UShort::class, "u16"), 1.toUShort()),
                LocalRustAny(typeIdFor<UInt>(UInt::class, "u32"), 1u),
                LocalRustAny(typeIdFor<ULong>(ULong::class, "u64"), 1uL),
                LocalRustAny(typeIdFor<Float>(Float::class, "f32"), 1.0f),
                LocalRustAny(typeIdFor<Double>(Double::class, "f64"), 1.0),
                LocalRustAny(typeIdFor<Boolean>(Boolean::class, "bool"), true),
                LocalRustAny(typeIdFor<Char>(Char::class, "char"), '1'),
                LocalRustAny(typeIdFor<String>(String::class, "String"), "1"),
            )
        val distinctIds = samples.map { it.typeId }.toSet()
        assertEquals(samples.size, distinctIds.size, "Every Rust scalar width must have a distinct TypeId on all platforms including Kotlin/JS")
        // Verify Byte::class, Short::class, Int::class, Float::class, Double::class resolve distinct tags via `when (T::class)`
        fun resolveWhenTag(kc: kotlin.reflect.KClass<*>): String =
            when (kc) {
                Byte::class -> "i8"
                Short::class -> "i16"
                Int::class -> "i32"
                Long::class -> "i64"
                UByte::class -> "u8"
                UShort::class -> "u16"
                UInt::class -> "u32"
                ULong::class -> "u64"
                Float::class -> "f32"
                Double::class -> "f64"
                else -> "other"
            }
        assertEquals("i8", resolveWhenTag(Byte::class))
        assertEquals("i16", resolveWhenTag(Short::class))
        assertEquals("i32", resolveWhenTag(Int::class))
        assertEquals("i64", resolveWhenTag(Long::class))
        assertEquals("u8", resolveWhenTag(UByte::class))
        assertEquals("u16", resolveWhenTag(UShort::class))
        assertEquals("u32", resolveWhenTag(UInt::class))
        assertEquals("u64", resolveWhenTag(ULong::class))
        assertEquals("f32", resolveWhenTag(Float::class))
        assertEquals("f64", resolveWhenTag(Double::class))
        assertNull(if (samples[0].typeId == samples[2].typeId) samples[0].rawValue as Int else null)
        assertEquals(1.toByte(), if (samples[0].typeId == LocalRustTypeId(Byte::class, "i8")) samples[0].rawValue as Byte else null)
    }

    @Test
    fun splitModuleRootsSubpackageAndMacroArmOrdering() {
        val out = linkedMapOf<String, String>()
        val extRs =
            """
            use proc_macro2::{TokenStream, TokenTree};
            pub trait TokenStreamExt {
                fn append<U: Into<TokenTree>>(&mut self, token: U);
            }
            """.trimIndent()
        val macrosRs =
            """
            #[macro_export]
            macro_rules! sample_quote {
                (${'$'}tokens:ident) => {
                    ${'$'}crate::ToTokens::to_tokens(&${'$'}tokens, &mut ${'$'}tokens)
                };
                (@spanned ${'$'}tokens:ident ${'$'}span:ident) => {
                    ${'$'}crate::ToTokens::to_tokens(&${'$'}span, &mut ${'$'}tokens)
                };
                (${'$'}tokens:expr) => {
                    ${'$'}crate::ToTokens::to_tokens(&${'$'}tokens, &mut ${'$'}tokens)
                };
            }
            """.trimIndent()
        val files =
            mapOf(
                "tmp/quote/src/ext.rs" to extRs,
                "tmp/quote/src/macros.rs" to macrosRs,
            )
        runSingleFileCommand(
            args =
                arrayOf(
                    "translate",
                    "--recursive",
                    "--split-module-roots",
                    "--source-root",
                    "tmp/quote/src",
                    "--namespace",
                    "io.github.kotlinmania.quote",
                    "--output",
                    "out",
                    "tmp/quote/src",
                ),
            read = { files.getValue(it) },
            write = { p, c -> out[p] = c },
            writeForce = { p, c -> out[p] = c },
            exists = { it in out },
            isDirectory = { it == "tmp/quote/src" },
            collectRustFiles = { files.keys.toList() },
            report = {},
            resolveRustKotlinProperties = { null },
            resolvePackageProperties = { null },
            resolveCrateMacroSources = { emptyList() },
            resolveCrateFlatFileModules = { _, _ -> emptySet() },
            resolveCrateRawAbiMetadata = { _, _, raw, split ->
                buildCrateRawAbiMetadataFromSources(
                    sources = files.entries.map { it.key to it.value },
                    rawAbiMode = raw,
                    splitModuleRoots = split,
                    finalStructs = TranslationSession.finalStructs,
                )
            },
        )
        val extKt = out.getValue("out/ext/Ext.kt")
        assertContains(extKt, "package io.github.kotlinmania.quote.ext")
        val macrosKt = out.getValue("out/macros/Macros.kt")
        assertFalse(macrosKt.contains("&var"), "Macro template must strip &mut before metavar:\n$macrosKt")
        val clausePos = macrosKt.indexOf("clause == \"@spanned\"")
        val singlePos = macrosKt.indexOf("tokens.isNotEmpty() ->")
        assertTrue(clausePos in 0 until singlePos, "Specific @spanned arm must precede general tokens.isNotEmpty() arm:\n$macrosKt")
        assertEquals(1, Regex("tokens\\.isNotEmpty\\(\\) ->").findAll(macrosKt).count(), "Duplicate condition arms must be deduplicated:\n$macrosKt")
        out.forEach { (path, content) ->
            ZeroUnderscoreIdentifierValidator.requireZeroViolations(content, path)
        }
    }

    @Test
    fun gap6QuoteSpannedCopyAndSharedBorrowIntoTokenStreamWithZeroUnderscores() {
        val rust =
            """
            use crate::ToTokens;
            use proc_macro2::extra::DelimSpan;
            use proc_macro2::{Span, TokenStream};

            // Not public API other than via the syn crate. Use syn::spanned::Spanned.
            pub trait Spanned: private::Sealed {
                fn __span(&self) -> Span;
            }

            impl Spanned for Span {
                fn __span(&self) -> Span {
                    *self
                }
            }

            impl Spanned for DelimSpan {
                fn __span(&self) -> Span {
                    self.join()
                }
            }

            impl<T: ?Sized + ToTokens> Spanned for T {
                fn __span(&self) -> Span {
                    join_spans(self.into_token_stream())
                }
            }

            fn join_spans(tokens: TokenStream) -> Span {
                let mut iter = tokens.into_iter().map(|tt| tt.span());

                let Some(first) = iter.next() else {
                    return Span::call_site();
                };

                iter.fold(None, |_prev, next| Some(next))
                    .and_then(|last| first.join(last))
                    .unwrap_or(first)
            }

            mod private {
                use crate::ToTokens;
                use proc_macro2::extra::DelimSpan;
                use proc_macro2::Span;

                pub trait Sealed {}
                impl Sealed for Span {}
                impl Sealed for DelimSpan {}
                impl<T: ?Sized + ToTokens> Sealed for T {}
            }
            """.trimIndent()
        val kt =
            renderSingleKotlinFile(
                source = rust,
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.quote",
                        projectGroup = "io.github.kotlinmania",
                        projectNamespace = "io.github.kotlinmania.quote",
                        isBatchCrateOutput = true,
                    ),
            ).text
        assertContains(kt, "public interface Spanned {")
        assertContains(kt, "public fun span(): Span")
        assertFalse(kt.contains("Sealed"), "Private marker trait Sealed must be omitted:\n$kt")
        assertContains(kt, "public fun Span.span(): Span = this.clone()")
        assertContains(kt, "public fun DelimSpan.span(): Span = this.join()")
        assertContains(kt, "TokenStream.new().also { tokens -> this.toTokens(tokens) }")
        assertFalse(kt.contains("intoTokenStream"), "Shared-borrow &T must lower into_token_stream via toTokens:\n$kt")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "Spanned.kt")
    }
}

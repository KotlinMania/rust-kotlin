package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
class SingleFileTranslationSynAndMacrosTest {
    @Test fun synMacrosAndAstEmissionFixesExpandAstStructEnumReturnImplTraitAndCleanKotlin() {
        val synMacrosRs =
            """
            macro_rules! ast_struct {
                (
                    ${'$'}attrs_pub:tt
                    struct ${'$'}name:ident #full ${'$'}(${'$'}rest:tt)*
                ) => {
                    #[cfg(feature = "full")]
                    ast_struct! {
                        ${'$'}attrs_pub
                        struct ${'$'}name ${'$'}(${'$'}rest)*
                    }

                    #[cfg(not(feature = "full"))]
                    ast_struct! {
                        ${'$'}attrs_pub
                        struct ${'$'}name {
                            _noconstruct: (),
                        }
                    }
                };
                (
                    [${'$'}(${'$'}attrs_pub:tt)*]
                    struct ${'$'}(${'$'}rest:tt)*
                ) => {
                    ${'$'}(${'$'}attrs_pub)* struct ${'$'}(${'$'}rest)*
                };
                (
                    ${'$'}t:tt
                    ${'$'}(${'$'}rest:tt)*
                ) => {
                    ast_struct!([${'$'}t] ${'$'}(${'$'}rest)*);
                };
                (
                    [${'$'}(${'$'}attrs_pub:tt)*]
                    ${'$'}t:tt
                    ${'$'}(${'$'}rest:tt)*
                ) => {
                    ast_struct!([${'$'}(${'$'}attrs_pub)* ${'$'}t] ${'$'}(${'$'}rest)*);
                };
            }

            macro_rules! return_impl_trait {
                (
                    ${'$'}(#[${'$'}attr:meta])*
                    ${'$'}vis:vis fn ${'$'}ident:ident ${'$'}args:tt -> ${'$'}impl_trait:ty [${'$'}concrete:ty] ${'$'}body:block
                ) => {
                    #[cfg(not(docsrs))]
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}vis fn ${'$'}ident ${'$'}args -> ${'$'}concrete ${'$'}body

                    #[cfg(docsrs)]
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}vis fn ${'$'}ident ${'$'}args -> ${'$'}impl_trait ${'$'}body
                };
            }
            """.trimIndent()

        val source =
            """
            /// Example node.
            ///
            /// ```edition2021
            /// # macro_rules! hidden_helper { () => {} }
            /// let x = 1;
            /// ```
            ast_struct! {
                pub struct Arm #full {
                    pub guard_flag: bool,
                }
            }

            impl Arm {
                pub fn to_string(&self) -> String {
                    String::new()
                }

                return_impl_trait! {
                    pub fn members(&self) -> impl Iterator<Item = u32> + Clone + '_ [Vec<u32>] {
                        Vec::new()
                    }
                }
            }

            pub fn advance_stream(mut step: usize, items: &[u32]) -> u8 {
                let any_zero = items.iter().any(|x| {
                    if *x == 0 {
                        return true;
                    }
                    false
                });
                if let Some(first) = items.iter().next() {
                    step += helper_inc(*first as usize);
                }
                while let Some(next_item) = items.iter().next() {
                    if *next_item > 10 {
                        break;
                    }
                    step += 1;
                }
                fn helper_inc(v: usize) -> usize {
                    v + 1
                }
                (step > 0 && !any_zero) as u8
            }
            """.trimIndent()

        val header =
            FileHeaderContext(
                portLintSourcePath = "expr.rs",
                packageName = "io.github.kotlinmania.syn",
                crateMacroSources = listOf(synMacrosRs),
            )
        val kt = renderSingleKotlinFile(source, header).text

        assertContains(kt, "```kotlin")
        assertFalse(kt.contains("hidden_helper"))
        assertFalse(kt.contains("hiddenHelper"))
        assertContains(kt, "public open class Arm(public var guardFlag: Boolean)")
        assertEquals(1, Regex("class Arm\\b").findAll(kt).count())
        assertFalse(kt.contains("noconstruct"))
        assertContains(kt, "public override fun toString(): String")
        assertEquals(1, Regex("fun members\\(").findAll(kt).count())
        assertContains(kt, "var step = step")
        val helperPos = kt.indexOf("fun helperInc(")
        val callPos = kt.indexOf("step += helperInc(")
        assertTrue(helperPos >= 0 && helperPos < callPos)
        assertContains(kt, "closure@{ x ->")
        assertContains(kt, "return@closure true")
        assertEquals(2, Regex("items\\.iter\\(\\)\\.next\\(\\)").findAll(kt).count())
        assertContains(kt, "(if (step > 0 && !anyZero) 1u else 0u).toUByte()")
    }

    @Test fun synMacroOnlyApproximatesWithConstantsAndConditionalsWhileDestructorSuppressionFailsExplicitly() {
        val macroOnlySource =
            """
            #[macro_export]
            macro_rules! custom_punctuation_len {
                (${'$'}mode:ident, &) => { 1 };
                (${'$'}mode:ident, &&) => { 2 };
                (lenient, ${'$'}tt:tt) => { 0 };
            }

            #[cfg(feature = "parsing")]
            #[macro_export]
            macro_rules! impl_parse_for_custom_keyword {
                (${'$'}ident:ident) => {
                    pub struct ${'$'}ident;
                };
            }

            #[cfg(not(feature = "parsing"))]
            #[macro_export]
            macro_rules! impl_parse_for_custom_keyword {
                (${'$'}ident:ident) => {};
            }

            #[macro_export]
            macro_rules! custom_punctuation_unexpected {
                () => {};
            }
            """.trimIndent()
        val macroKt = renderSingleKotlinFile(macroOnlySource).text
        assertContains(macroKt, "public const val CFG_FEATURE_PARSING: Boolean = true")
        assertContains(macroKt, "public fun customPunctuationLen(mode: String = \"\", tt: String = \"\"): Int =")
        assertContains(macroKt, "when {")
        assertContains(macroKt, "tt == \"&&\" -> 2")
        assertContains(macroKt, "mode == \"lenient\" -> 0")
        assertContains(macroKt, "if (CFG_FEATURE_PARSING)")
        assertContains(macroKt, "public const val CUSTOM_PUNCTUATION_UNEXPECTED: String = \"\"")

        val dropsSource =
            """
            use core::mem::{needs_drop, ManuallyDrop};

            pub struct NoDrop<T: ?Sized>(ManuallyDrop<T>);

            impl<T> NoDrop<T> {
                pub fn new(value: T) -> Self
                where
                    T: TrivialDrop,
                {
                    assert!(!needs_drop::<T>());
                    NoDrop(ManuallyDrop::new(value))
                }
            }

            pub trait TrivialDrop {}
            """.trimIndent()
        val dropsErr =
            assertFailsWith<IllegalArgumentException> {
                renderSingleKotlinFile(dropsSource)
            }
        assertContains(dropsErr.message.orEmpty(), "Unsupported Rust destructor-suppression semantics")
        assertContains(dropsErr.message.orEmpty(), "ManuallyDrop")
        assertContains(dropsErr.message.orEmpty(), "needs_drop")
    }

    @Test fun synLetOrPatternsExportAliasesThreadBoundSpansAndSpannedFidelity() {
        val letOrSource =
            """
            pub enum Expr {
                Break(Option<i32>),
                Return(Option<i32>),
                Yield(Option<i32>),
                Assign,
                Binary,
                Cast,
            }

            pub fn has_value(expr: &Expr) -> bool {
                let Expr::Break(val) | Expr::Return(val) | Expr::Yield(val) = expr else {
                    return false;
                };
                let Expr::Assign
                | Expr::Binary
                | Expr::Cast = expr else {
                    return val.is_some();
                };
                true
            }
            """.trimIndent()
        val letOrKt = renderSingleKotlinFile(letOrSource).text
        assertContains(letOrKt, "public fun hasValue(expr: Expr): Boolean")
        assertContains(letOrKt, "!= null")

        fun synHeader(sourcePath: String, flatModules: List<String> = listOf("span", "parse_quote")): FileHeaderContext =
            FileHeaderContext(
                portLintSourcePath = sourcePath,
                packageName = "io.github.kotlinmania.syn",
                projectNamespace = "io.github.kotlinmania.syn",
                projectGroup = "io.github.kotlinmania",
                crateFlatFileModules = flatModules,
            )

        val exportHeader = synHeader("export.rs")
        val exportSource =
            """
            pub use core::clone::Clone;
            pub use core::option::Option::{None, Some};
            pub type Formatter<'a> = core::fmt::Formatter<'a>;
            pub type FmtResult = core::fmt::Result;
            pub type bool = core::primitive::bool;
            pub type str = core::primitive::str;
            pub type Span = proc_macro2::Span;
            pub type TokenStream2 = proc_macro2::TokenStream;
            pub type TokenStream = proc_macro::TokenStream;
            pub struct private(pub(crate) ());
            """.trimIndent()
        val exportKt = renderSingleKotlinFile(exportSource, exportHeader).text
        assertContains(exportKt, "import io.github.kotlinmania.procmacro2.TokenStream")
        assertContains(exportKt, "public typealias Formatter = StringBuilder")
        assertContains(exportKt, "public typealias FmtResult = Unit")
        assertContains(exportKt, "public typealias TokenStream2 = TokenStream")
        assertContains(exportKt, "public open class Private(internal var value: Unit)")
        assertFalse(exportKt.contains("= Any"))
        assertFalse(exportKt.contains("typealias Boolean"))
        assertFalse(exportKt.contains("typealias String"))
        assertFalse(exportKt.contains("typealias Span "))
        assertFalse(exportKt.contains("typealias TokenStream "))

        val threadHeader = synHeader("thread.rs")
        val threadSource =
            """
            use core::fmt::{self, Debug};
            use std::thread::{self, ThreadId};

            pub(crate) struct ThreadBound<T> {
                value: T,
                thread_id: ThreadId,
            }

            // Send bound requires Copy, as otherwise Drop could run in the wrong place.
            unsafe impl<T: Copy> Send for ThreadBound<T> {}

            impl<T> ThreadBound<T> {
                pub(crate) fn new(value: T) -> Self {
                    ThreadBound {
                        value,
                        thread_id: thread::current().id(),
                    }
                }

                pub(crate) fn get(&self) -> Option<&T> {
                    if thread::current().id() == self.thread_id {
                        Some(&self.value)
                    } else {
                        None
                    }
                }
            }

            // Copy the bytes of T, even if the currently running thread is the "wrong" thread.
            impl<T: Copy> Copy for ThreadBound<T> {}

            impl<T: Copy> Clone for ThreadBound<T> {
                fn clone(&self) -> Self {
                    *self
                }
            }
            """.trimIndent()
        val threadKt = renderSingleKotlinFile(threadSource, threadHeader).text
        assertContains(threadKt, "internal expect fun currentThreadBoundToken(): Any")
        assertContains(threadKt, "internal fun <T> new(value: T): ThreadBound<T>")
        assertContains(threadKt, "public open fun clone(): ThreadBound<T>")
        assertContains(threadKt, "return ThreadBound(this.value, this.threadId)")
        assertContains(threadKt, "// Send bound requires Copy")
        assertContains(threadKt, "// Copy the bytes of T")
        assertFalse(threadKt.contains("import core."))
        assertFalse(threadKt.contains("import std."))

        val spanHeader = synHeader("span.rs")
        val spanSource =
            """
            use proc_macro2::extra::DelimSpan;
            use proc_macro2::Span;

            pub trait IntoSpans<S> {
                fn into_spans(self) -> S;
            }

            impl IntoSpans<Span> for Span {
                fn into_spans(self) -> Span { self }
            }
            impl IntoSpans<[Span; 1]> for Span {
                fn into_spans(self) -> [Span; 1] { [self] }
            }
            impl IntoSpans<[Span; 2]> for Span {
                fn into_spans(self) -> [Span; 2] { [self, self] }
            }
            impl IntoSpans<[Span; 3]> for Span {
                fn into_spans(self) -> [Span; 3] { [self, self, self] }
            }
            impl IntoSpans<[Span; 1]> for [Span; 1] {
                fn into_spans(self) -> [Span; 1] { self }
            }
            impl IntoSpans<[Span; 2]> for [Span; 2] {
                fn into_spans(self) -> [Span; 2] { self }
            }
            impl IntoSpans<[Span; 3]> for [Span; 3] {
                fn into_spans(self) -> [Span; 3] { self }
            }
            impl IntoSpans<DelimSpan> for Span {
                fn into_spans(self) -> DelimSpan { unreachable!() }
            }
            impl IntoSpans<DelimSpan> for DelimSpan {
                fn into_spans(self) -> DelimSpan { self }
            }
            """.trimIndent()
        val spanKt = renderSingleKotlinFile(spanSource, spanHeader).text
        assertEquals(9, Regex("public fun (Span|List<Span>|DelimSpan)\\.intoSpans").findAll(spanKt).count())
        assertContains(spanKt, "import io.github.kotlinmania.procmacro2.DelimSpan")

        val spannedHeader = synHeader("spanned.rs")
        val spannedSource =
            """
            use proc_macro2::Span;
            use quote::spanned::Spanned as ToTokens;

            /// A trait that can provide the `Span` of the complete contents of a syntax node.
            ///
            /// ```
            /// #[proc_macro_attribute]
            /// pub fn my_attr(args: TokenStream, input: TokenStream) -> TokenStream {
            ///     /* ... */
            /// }
            /// ```
            pub trait Spanned: private::Sealed {
                fn span(&self) -> Span;
            }

            impl<T: ?Sized + ToTokens> Spanned for T {
                fn span(&self) -> Span {
                    self.__span()
                }
            }
            """.trimIndent()
        val spannedKt = renderSingleKotlinFile(spannedSource, spannedHeader).text
        assertContains(spannedKt, "import io.github.kotlinmania.quote.span as quoteSpan")
        assertContains(spannedKt, "return this.quoteSpan()")
        assertFalse(spannedKt.contains("return this.span()"))
        assertFalse(spannedKt.contains("__span"))
        assertContains(spannedKt, "@ProcMacroAttribute")
        assertFalse(spannedKt.contains("/* ... */"))
    }

    @Test fun synParseParseQuotePunctuatedAndMetaGenericAndWitnessFidelity() {
        val header =
            FileHeaderContext(
                portLintSourcePath = "parse_quote.rs",
                packageName = "io.github.kotlinmania.syn",
                projectNamespace = "io.github.kotlinmania.syn",
                projectGroup = "io.github.kotlinmania",
                crateFlatFileModules = listOf("parse", "error", "punctuated", "token"),
            )
        val parseAndQuoteSource =
            """
            use crate::error::Result;
            use proc_macro2::TokenStream;

            pub struct ParseBuffer<'a> {
                marker: core::marker::PhantomData<&'a ()>,
            }
            pub type ParseStream<'a> = &'a ParseBuffer<'a>;

            pub trait Parse: Sized {
                fn parse(input: ParseStream) -> Result<Self>;
            }

            pub trait Parser: Sized {
                type Output;
                fn parse2(self, tokens: TokenStream) -> Result<Self::Output>;
            }

            impl<F, T> Parser for F
            where
                F: FnOnce(ParseStream) -> Result<T>,
            {
                type Output = T;
                fn parse2(self, _tokens: TokenStream) -> Result<T> {
                    unreachable!()
                }
            }

            pub trait ParseQuote: Sized {
                fn parse(input: ParseStream) -> Result<Self>;
            }

            impl<T: Parse> ParseQuote for T {
                fn parse(input: ParseStream) -> Result<Self> {
                    <T as Parse>::parse(input)
                }
            }

            pub fn parse_item<T: ParseQuote>(tokens: TokenStream) -> T {
                let parser = T::parse;
                let _ = parser;
                let _ = tokens;
                unreachable!()
            }
            """.trimIndent()
        val pqKt = renderSingleKotlinFile(parseAndQuoteSource, header).text
        assertContains(pqKt, "public interface Parse<T>")
        assertContains(pqKt, "public fun parse(input: ParseStream): Result<T>")
        assertContains(pqKt, "public interface Parser<Output>")
        assertContains(pqKt, "public fun <T> ((ParseStream) -> Result<T>).parse2(")
        assertContains(pqKt, "public interface ParseQuote<T>")
        assertContains(pqKt, "parseTFn: (ParseStream) -> Result<T>")
        assertFalse(pqKt.contains("<T as"))
        assertFalse(pqKt.contains("T::parse"))

        val punctHeader = header.copy(portLintSourcePath = "punctuated.rs")
        val punctSource =
            """
            pub struct Punctuated<T, P> {
                inner: Vec<(T, P)>,
                last: Option<Box<T>>,
            }

            pub enum Pair<T, P> {
                Punctuated(T, P),
                End(T),
            }

            impl<T, P> Punctuated<T, P> {
                pub const fn new() -> Self {
                    Punctuated {
                        inner: Vec::new(),
                        last: None,
                    }
                }

                pub fn push_value(&mut self, value: T) {
                    self.last = Some(Box::new(value));
                }

                pub fn push_punct(&mut self, punctuation: P) {
                    let last = self.last.take().unwrap();
                    self.inner.push((*last, punctuation));
                }

                pub fn push(&mut self, value: T)
                where
                    P: Default,
                {
                    if self.last.is_some() {
                        self.push_punct(Default::default());
                    }
                    self.push_value(value);
                }
            }

            impl<T, P> FromIterator<T> for Punctuated<T, P>
            where
                P: Default,
            {
                fn from_iter<I: IntoIterator<Item = T>>(i: I) -> Self {
                    let mut ret = Punctuated::new();
                    ret.extend(i);
                    ret
                }
            }

            impl<T, P> FromIterator<Pair<T, P>> for Punctuated<T, P> {
                fn from_iter<I: IntoIterator<Item = Pair<T, P>>>(_i: I) -> Self {
                    Punctuated::new()
                }
            }
            """.trimIndent()
        val punctKt = renderSingleKotlinFile(punctSource, punctHeader).text
        assertContains(punctKt, "public open class Punctuated<T, P>(public var inner: MutableList<kotlin.Pair<T, P>>, public var last: T?)")
        assertContains(punctKt, "kotlin.Pair(last, punctuation)")
        assertContains(punctKt, "defaultPFn: () -> P")
        assertContains(punctKt, "public fun <T, P, I : Iterable<T>> fromIter(")
        assertContains(punctKt, "public fun <T, P, I : Iterable<Pair<T, P>>> fromIterPairTP(")
    }

    @Test fun synContainerTraitDispatchIteratorsDeferredMatchAndDocExamples() {
        val header =
            FileHeaderContext(
                portLintSourcePath = "punctuated.rs",
                packageName = "io.github.kotlinmania.syn",
                projectNamespace = "io.github.kotlinmania.syn",
                projectGroup = "io.github.kotlinmania",
                crateFlatFileModules = listOf("parse", "error", "punctuated", "attr"),
            )

        val punctRichSource =
            """
            use core::ops::{Index, IndexMut};
            use core::slice;
            use std::option;
            use std::vec;

            pub struct Punctuated<T, P> {
                inner: Vec<(T, P)>,
                last: Option<Box<T>>,
            }

            pub enum Pair<T, P> {
                Punctuated(T, P),
                End(T),
            }

            impl<T, P> Pair<T, P> {
                pub fn into_value(self) -> T {
                    match self {
                        Pair::Punctuated(t, _) | Pair::End(t) => t,
                    }
                }
            }

            pub struct Iter<'a, T: 'a> {
                inner: slice::Iter<'a, T>,
            }

            trait IterMutTrait<'a, T: 'a>: Iterator<Item = &'a mut T> + DoubleEndedIterator + ExactSizeIterator {}

            pub struct IterMut<'a, T: 'a> {
                inner: Box<dyn IterMutTrait<'a, T, Item = &'a mut T> + 'a>,
            }

            pub struct IntoIter<T> {
                inner: vec::IntoIter<T>,
            }

            impl<T, P> Punctuated<T, P> {
                pub fn first(&self) -> Option<&T> {
                    self.inner
                        .first()
                        .map(|pair| &pair.0)
                        .or_else(|| self.last.as_deref())
                }

                pub fn pop_punct(&mut self) -> Option<P> {
                    if self.last.is_some() {
                        return None;
                    }
                    let (t, p) = self.inner.pop()?;
                    self.last = Some(Box::new(t));
                    Some(p)
                }

                pub fn into_tokens(self) -> usize {
                    let Punctuated { inner, last } = self;
                    if last.is_some() {
                        inner.len() + 1
                    } else {
                        inner.len()
                    }
                }
            }

            impl<T, P> Index<usize> for Punctuated<T, P> {
                type Output = T;

                fn index(&self, index: usize) -> &Self::Output {
                    if index == self.len() - 1 {
                        match &self.last {
                            Some(t) => t,
                            None => &self.inner[index].0,
                        }
                    } else {
                        &self.inner[index].0
                    }
                }
            }

            impl<T, P> IndexMut<usize> for Punctuated<T, P> {
                fn index_mut(&mut self, index: usize) -> &mut Self::Output {
                    if index == self.len() - 1 {
                        match &mut self.last {
                            Some(t) => t,
                            None => &mut self.inner[index].0,
                        }
                    } else {
                        &mut self.inner[index].0
                    }
                }
            }

            impl<T> Iterator for IntoIter<T> {
                type Item = T;

                fn next(&mut self) -> Option<Self::Item> {
                    self.inner.next()
                }
            }

            impl<T> DoubleEndedIterator for IntoIter<T> {
                fn next_back(&mut self) -> Option<Self::Item> {
                    self.inner.next_back()
                }
            }
            """.trimIndent()
        val punctRichKt = renderSingleKotlinFile(punctRichSource, header).text
        assertContains(punctRichKt, "public open class Iter<T>(public var inner: ArrayDeque<T>)")
        assertContains(punctRichKt, "public open class IterMut<T>(public var inner: IterMutTrait<T>)")
        assertContains(punctRichKt, "public open class IntoIter<T>(public var inner: ArrayDeque<T>)")
        assertContains(punctRichKt, "public open fun index(index: Int): T")
        assertContains(punctRichKt, "public open fun indexMut(index: Int): T")
        assertContains(punctRichKt, "public open fun nextBack(): T?")
        assertContains(punctRichKt, "return this.inner.removeFirstOrNull()")
        assertContains(punctRichKt, "return this.inner.removeLastOrNull()")
        assertContains(punctRichKt, "val inner = this.inner")
        assertContains(punctRichKt, "val last = this.last")
        assertContains(punctRichKt, "(this.inner.removeLastOrNull() ?: return null)")
        assertFalse(punctRichKt.contains("is Any ->"))

        val metaAndParseSource =
            """
            use crate::error::{Error, Result};

            pub struct Nothing;

            pub enum Unexpected {
                None,
                Some(i32),
            }

            /// Parse nested meta attributes.
            ///
            /// ```
            /// #[tea(kind = "EarlGrey", hot)]
            /// struct Picard;
            /// ```
            pub fn check_unexpected(u: Unexpected, step: Result<i32>) -> Result<()> {
                let content;
                match step {
                    Ok(v) => {
                        content = v;
                    }
                    Err(err) => return Err(err),
                }
                let _ = content;
                match u {
                    Unexpected::None => Ok(()),
                    Unexpected::Some(_) => Ok(()),
                }
            }

            pub fn diverge() -> ! {
                panic!("stop")
            }
            """.trimIndent()
        val metaParseKt = renderSingleKotlinFile(metaAndParseSource, header.copy(portLintSourcePath = "meta.rs")).text
        assertContains(metaParseKt, "@tea(kind = \"EarlGrey\", hot)")
        assertContains(metaParseKt, "public fun diverge(): kotlin.Nothing")
        assertContains(metaParseKt, "is Unexpected.Some -> Result.success(Unit)")
        assertContains(metaParseKt, ".getOrElse {")
        assertFalse(metaParseKt.contains("var content: Any?"))

        val parseQuoteAndIterSource =
            """
            use crate::error::Result;
            use crate::parse::ParseStream;
            use crate::pat::Pat;
            use core::hash::{Hash, Hasher};

            pub trait Parse: Sized {
                fn parse(input: ParseStream) -> Result<Self>;
            }

            pub trait ParseQuote: Sized {
                fn parse(input: ParseStream) -> Result<Self>;
            }

            pub struct Visibility;

            impl Parse for Visibility {
                fn parse(_input: ParseStream) -> Result<Self> {
                    Ok(Visibility)
                }
            }

            impl ParseQuote for Pat {
                fn parse(_input: ParseStream) -> Result<Self> {
                    Ok(Pat)
                }
            }

            pub fn parse_vis_and_pat(input: ParseStream) -> Result<Pat> {
                let _vis: Visibility = input.parse()?;
                <Pat as ParseQuote>::parse(input)
            }

            pub struct Counter {
                pub count: usize,
            }

            impl Hash for Counter {
                fn hash<H: Hasher>(&self, state: &mut H) {
                    self.count.hash(state);
                }
            }

            pub fn checked_next(a: usize, b: usize) -> Option<usize> {
                a.checked_add(b)
            }
            """.trimIndent()
        val pqKt = renderSingleKotlinFile(parseQuoteAndIterSource, header.copy(portLintSourcePath = "parse_quote.rs")).text
        assertContains(pqKt, "input.parse(Visibility::parse)")
        assertContains(pqKt, "parseQuoteParsePat(input)")
        assertContains(pqKt, "override fun hashCode(): Int")
        assertFalse(pqKt.contains("core.hash"))
        assertContains(pqKt, "checkedNext(a: Int, b: Int): Int?")

        val privateReexportMacroSource =
            """
            macro_rules! parenthesized {
                (${'$'}content:ident in ${'$'}cursor:expr) => {
                    match ${'$'}crate::__private::parse_parens(&${'$'}cursor) {
                        ${'$'}crate::__private::Ok(parens) => {
                            ${'$'}content = parens.content;
                            _ = ${'$'}content;
                            parens.token
                        }
                        ${'$'}crate::__private::Err(error) => {
                            return ${'$'}crate::__private::Err(error);
                        }
                    }
                };
            }

            pub fn nested(input: ParseStream) -> Result<Int> {
                let content;
                parenthesized!(content in input);
                content.call()
            }
            """.trimIndent()
        val macroExpandedMetaKt =
            renderSingleKotlinFile(
                privateReexportMacroSource,
                header.copy(portLintSourcePath = "meta.rs"),
            ).text
        assertContains(macroExpandedMetaKt, "val content = parens.content")
        assertFalse(macroExpandedMetaKt.contains("content: Any?"))
        assertFalse(macroExpandedMetaKt.contains("is Ok ->"))
    }

    @Test
    fun preservesCommentsInsideMacroInvocationsAndLowercaseTypeImports() {
        val macrosRs =
            """
            macro_rules! s {
                (${'$'}(
                    ${'$'}(#[${'$'}attr:meta])*
                    ${'$'}pub:vis ${'$'}t:ident ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }
                )*) => (${'$'}(
                    s!(it: ${'$'}(#[${'$'}attr])* ${'$'}pub ${'$'}t ${'$'}i { ${'$'}(${'$'}field)* });
                )*);
                (it: ${'$'}(#[${'$'}attr:meta])* ${'$'}pub:vis struct ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }) => (
                    #[repr(C)]
                    #[::core::prelude::v1::derive(::core::clone::Clone, ::core::marker::Copy, ::core::fmt::Debug)]
                    #[allow(deprecated)]
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}pub struct ${'$'}i { ${'$'}(${'$'}field)* }
                );
            }
            """.trimIndent()
        val riscv64Rs =
            """
            use crate::off_t;
            use crate::prelude::*;
            use libc::{c_void, SOL_SOCKET, _SC_PAGESIZE, termios as Termios};

            // From psABI Calling Convention for RV64
            pub type stat64 = stat;
            s! {
                pub struct stat {
                    pub st_size: off_t,
                    // union
                    pub st_blocks: crate::blkcnt_t,
                }

                // Not actually used, IPC calls just return ENOSYS
                pub struct ipc_perm {
                    pub __ipc_perm_key: crate::key_t,
                    pub __seq: c_ushort,
                }
            }
            """.trimIndent()
        val header =
            FileHeaderContext(
                portLintSourcePath = "fuchsia/riscv64.rs",
                packageName = "io.github.kotlinmania.libc.fuchsia",
                projectGroup = "io.github.kotlinmania",
                projectNamespace = "io.github.kotlinmania.libc",
                crateMacroSources = listOf(macrosRs),
                crateFlatFileModules = listOf("prelude", "primitives", "types"),
            )
        val kt = renderSingleKotlinFile(riscv64Rs, header).text
        assertContains(kt, "import io.github.kotlinmania.libc.*")
        assertContains(kt, "import io.github.kotlinmania.libc.OffT")
        assertContains(kt, "import io.github.kotlinmania.libc.SOL_SOCKET")
        assertContains(kt, "import io.github.kotlinmania.libc.SC_PAGESIZE")
        assertContains(kt, "import io.github.kotlinmania.libc.Termios")
        assertFalse(kt.contains("offT"))
        assertFalse(kt.contains("cVoid"))
        assertFalse(kt.contains("libc.prelude"))
        assertContains(kt, "// From psABI Calling Convention for RV64\npublic typealias Stat64 = Stat")
        assertContains(kt, "    // union\n    public var stBlocks: BlkcntT,")
        assertContains(kt, "// Not actually used, IPC calls just return ENOSYS\npublic open class IpcPerm(")
    }

    @Test fun tokenDrivenSynTokenMacrosLiteralUnescapeAndComplementaryCfgStatements() {
        val rustSource =
            """
            pub struct TypeBinding {
                pub kw: Token![type],
                pub sep: Token![..=],
            }

            pub fn make_binding(sp: Span, x: i32) -> String {
                let _kw = Token![struct](sp);
                let _def = Token![>>=];
                #[cfg(wrap_proc_macro)]
                let label = format!(r#"raw\n:{x}"#);
                #[cfg(not(wrap_proc_macro))]
                let label = format!("fallback:{x}");
                format!("\x41:{label}")
            }
            """.trimIndent()
        val kt = renderSingleKotlinFile(rustSource).text

        assertContains(kt, "public var kw: SynTypeToken")
        assertContains(kt, "public var sep: DotDotEq")
        assertContains(kt, "val kw = Struct.from(sp)")
        assertContains(kt, "val def = ShrEq.default()")
        assertContains(kt, "val label = \"raw\\\\n:${'$'}x\"")
        assertFalse(kt.contains("label1"))
        assertContains(kt, "return \"A:${'$'}label\"")
    }

    @Test fun supplementaryCharLiteralsLosslessScalarsAndScopedFnNumericArgs() {
        val rustSource =
            """
            // Preserve '🦀' in line comments and /* nested /* '🚀' */ block comments */
            pub fn inspect_literals() {
                fn check_byte(literal_text: &str, expected_byte: u8) {
                    let _ = (literal_text, expected_byte);
                }
                let quote_and_crab = ('\'', '🦀', '\u{1F63b}');
                let repeated = ['🚀'; 4];
                let raw_note = r#"keep '🦀' inside raw string"#;
                let cooked_note = "keep '😻' inside cooked string";
                // skip_ws(&mut input.chars(), &mut cb)
                check_byte(r"\x5a", b'Z');
                check_byte(r"\x7f", 127);
                check_byte(r"\xff", 255);
                let _ = (quote_and_crab, repeated, raw_note, cooked_note);
            }
            """.trimIndent()
        val kt = renderSingleKotlinFile(rustSource).text

        assertContains(kt, "// Preserve '🦀' in line comments")
        assertContains(kt, "Triple('\\'', 0x1f980, 0x1f63b)")
        assertContains(kt, "List(4) { 0x1f680 }")
        assertContains(kt, "\"keep '🦀' inside raw string\"")
        assertContains(kt, "\"keep '😻' inside cooked string\"")
        assertFalse(kt.contains("\\uf980"))
        assertFalse(kt.contains("\\uf63b"))
        assertFalse(kt.contains("\\uf680"))
        assertFalse(kt.contains("&mut"))
        assertContains(kt, "checkByte(\"\\\\x5a\", 90u)")
        assertContains(kt, "checkByte(\"\\\\x7f\", 127u)")
        assertContains(kt, "checkByte(\"\\\\xff\", 255u)")
    }

    @Test fun builtinMacrosDebugBuildersComplementaryMatchArmsAndExternUsizeAbi() {
        val macroDefs =
            """
            macro_rules! c_union_no_extra {
                (pub union ${'$'}name:ident { $(${'$'}f:ident: ${'$'}t:ty,)* }) => {
                    pub union ${'$'}name { $(pub ${'$'}f: ${'$'}t,)* }
                    impl ::core::fmt::Debug for ${'$'}name {
                        fn fmt(&self, f: &mut ::core::fmt::Formatter) -> ::core::fmt::Result {
                            f.debug_struct(::core::stringify!(${'$'}name)).finish_non_exhaustive()
                        }
                    }
                };
            }
            """.trimIndent()
        val rustSource =
            """
            use core::fmt;
            use proc_macro::Span as CompilerSpan;

            pub type HttpMallocFunction = Option<extern "C" fn(usize) -> *mut c_void>;

            pub trait EscaperOut {
                type Unit;
            }
            impl EscaperOut for char {
                type Unit = char;
            }

            c_union_no_extra! {
                pub union RawColor {
                    rgba: u32,
                }
            }

            pub enum BackendSpan {
                Compiler(String),
                Fallback(String),
            }

            pub fn format_suffixed(n: u8) -> String {
                format!(concat!("{}", stringify!(u8)), n)
            }

            pub fn check_span(span: &BackendSpan, ch: char) -> (String, bool, u32, &'static str) {
                let text = match span {
                    #[cfg(not(no_source_text))]
                    BackendSpan::Compiler(s) => s.clone(),
                    #[cfg(no_source_text)]
                    BackendSpan::Compiler(_) => String::new(),
                    BackendSpan::Fallback(s) => s.clone(),
                };
                let matched = matches!(ch, 'a' | 'z' if ch.is_ascii());
                (text, matched, line!(), file!())
            }

            pub fn append_debug(debug: &mut fmt::DebugStruct, f: &mut fmt::Formatter, label: &str) {
                debug.field("label", &label);
                f.debug_tuple("Tag").field(&label).finish();
            }
            """.trimIndent()

        val header =
            FileHeaderContext(
                portLintSourcePath = "wrapper.rs",
                packageName = "io.github.kotlinmania.procmacro2",
                projectGroup = "io.github.kotlinmania",
                projectNamespace = "io.github.kotlinmania.procmacro2",
                crateMacroSources = listOf(macroDefs),
            )
        val kt = renderSingleKotlinFile(rustSource, header).text

        assertContains(kt, "import io.github.kotlinmania.procmacro.Span as CompilerSpan")
        assertContains(kt, "public fun interface HttpMallocFunctionFn {\n    public operator fun invoke(arg0: ULong): MutPtr\n}")
        assertFalse(kt.contains("typealias Unit ="))
        assertContains(kt, "public open class RawColor(public var rgba: UInt? = null)")
        assertFalse(kt.contains("RawColor).finishNonExhaustive"))
        assertContains(kt, "return \"${'$'}{n}u8\"")
        assertEquals(1, Regex("is BackendSpan\\.Compiler ->").findAll(kt).count())
        assertContains(kt, "val matched = when {")
        assertContains(kt, "line!()".let { "u, \"wrapper.rs\"" })
        assertContains(kt, "public class DebugStruct(")
        assertContains(kt, "public class DebugTuple(")
        assertContains(kt, "public fun appendDebug(debug: DebugStruct, f: StringBuilder, label: String)")

        val manualDropFnSource =
            """
            use core::mem::ManuallyDrop;
            use core::ptr;
            pub struct Holder { inner: String }
            impl Holder {
                pub fn take_inner(self) -> String {
                    let nodrop = ManuallyDrop::new(self);
                    unsafe { ptr::read(&nodrop.inner) }
                }
            }
            """.trimIndent()
        val dropErr =
            assertFailsWith<IllegalArgumentException> {
                renderSingleKotlinFile(manualDropFnSource, header)
            }
        assertContains(dropErr.message.orEmpty(), "ManuallyDrop")
    }

    @Test fun kdocExamplesAreAstTransliteratedThroughSynAndTokensWithoutRustResidue() {
        val rustSource =
            """
            /// Parse macro body using [`Attribute::parse_nested_meta`] and `syn::parse2::<T>(mac.tokens)`.
            /// Supports [`Visitor::visit_seq`
            /// ] and [`first_item`, `second_item`] and stray ` backticks.
            ///
            /// [`Attribute::parse_nested_meta`]: crate::Attribute::parse_nested_meta
            ///
            /// # Example
            ///
            /// ```
            /// use syn::{parse_quote, Expr, Ident, Macro, Token};
            /// use syn::parse::{Parse, ParseStream, Result};
            ///
            /// struct FormatArgs {
            ///     format_string: Expr,
            ///     positional_args: Vec<Expr>,
            ///     named_args: Vec<(Ident, Expr)>,
            /// }
            ///
            /// impl Parse for FormatArgs {
            ///     fn parse(input: ParseStream) -> Result<Self> {
            ///         let format_string: Expr = input.parse()?;
            ///         let mut positional_args = Vec::new();
            ///         let mut named_args = Vec::new();
            ///         while !input.is_empty() {
            ///             input.parse::<Token![,]>()?;
            ///             positional_args.push(input.parse()?);
            ///         }
            ///         Ok(FormatArgs {
            ///             format_string,
            ///             positional_args,
            ///             named_args,
            ///         })
            ///     }
            /// }
            ///
            /// fn main() {
            ///     let invocation: Macro = parse_quote! {
            ///         println!("{:?}", Instant::now())
            ///     };
            ///     let _ = invocation;
            /// }
            /// ```
            ///
            /// # Statement Example
            ///
            /// ```
            /// use syn::{parse_quote, Attribute, LitStr};
            ///
            /// let attr: Attribute = parse_quote! {
            ///     #[tea(kind = "EarlGrey")]
            /// };
            /// if attr.path().is_ident("tea") {
            ///     attr.parse_nested_meta(|meta| {
            ///         if meta.path.is_ident("kind") {
            ///             let value = meta.value()?;
            ///             let _s: LitStr = value.parse()?;
            ///             Ok(())
            ///         } else {
            ///             Err(meta.error("unsupported"))
            ///         }
            ///     })?;
            /// }
            /// # anyhow::Ok(())
            /// ```
            pub fn parse_body_demo(tokens: i32) -> i32 {
                tokens
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(kt, "[Attribute.parseNestedMeta]")
        assertContains(kt, "[`firstItem`, `secondItem`]")
        assertContains(kt, "`syn.parse2<T>(mac.tokens)`")
        assertFalse(kt.contains("crate::Attribute"))
        assertContains(kt, "open class FormatArgs(")
        assertContains(kt, "public var positionalArgs: List<Expr>")
        assertContains(kt, ".getOrElse { return Result.failure(it) }")
        assertContains(kt, "parseQuote(\"@tea(kind = \\\"EarlGrey\\\")\")")
        assertFalse(kt.contains("input.parse()?"))
        assertFalse(kt.contains("Token!["))
        assertFalse(kt.contains("anyhow"))
    }

    @Test fun syn309GeneratorDefectsNonDrainingTokensOptionalParseIsSomeAndNestedIfLet() {
        val rustSource =
            """
            pub struct TypePath {
                pub attrs: Vec<i32>,
                pub qself: Option<i32>,
                pub path: i32,
            }

            pub enum AstType {
                Path(TypePath),
                Infer,
            }

            pub enum Visibility {
                Public,
                Inherited,
            }

            impl Visibility {
                pub fn is_some(&self) -> bool {
                    matches!(self, Visibility::Public)
                }
                pub fn is_none(&self) -> bool {
                    matches!(self, Visibility::Inherited)
                }
            }

            pub struct Holder {
                pub vis: Visibility,
                pub opt: Option<i32>,
                pub dot2_token: Option<Token![..]>,
            }

            pub fn parse_items(input: ParseStream, h: Holder, first_ty: AstType) -> Result<Holder> {
                let semi_token: Token![;] = input.parse()?;
                let safe_token: Option<Token![safe]> = input.parse()?;
                let abi: Option<Abi> = input.parse()?;
                let maybe: Option<Token![?]> = input.parse::<Option<Token![?]>>()?;
                let built = Holder {
                    vis: Visibility::Public,
                    opt: safe_token.map(|_| 1),
                    dot2_token: Some(input.parse()?),
                };
                let Holder { vis, opt, .. } = h;
                if vis.is_some() && !vis.is_none() && opt.is_some() {
                    let _ = (semi_token, abi, maybe);
                }
                if let AstType::Path(TypePath { attrs: _, qself: None, path }) = first_ty {
                    let _ = path;
                }
                if let AstType::Path(TypePath { attrs: _, qself: None, path }) = next_ty() {
                    let _ = path;
                }
                Ok(built)
            }

            pub fn next_ty() -> AstType {
                AstType::Infer
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(kt, "val semiToken: Semi = input.parse(Semi::parse)")
        assertFalse(kt.contains("val semiToken: Token"))
        assertContains(kt, "val safeToken: Safe? = (if (input.peek(Safe)) input.parse(Safe::parse) else Result.success(null))")
        assertContains(kt, "val abi: Abi? = (if (input.peek(Extern)) input.parse(Abi::parse) else Result.success(null))")
        assertContains(kt, "(if (input.peek(Question)) input.parse(Question::parse) else Result.success(null))")
        assertContains(kt, "dot2Token = input.parse(DotDot::parse).getOrElse { return Result.failure(it) }")
        assertContains(kt, "vis.isSome()")
        assertContains(kt, "!vis.isNone()")
        assertContains(kt, "opt != null")
        assertContains(kt, "if (firstTy is AstType.Path && firstTy.value.qself == null)")
        assertContains(kt, "val path = firstTy.value.path")
        assertContains(kt, "if (matchVal is AstType.Path && matchVal.value.qself == null)")
        assertContains(kt, "val path1 = matchVal.value.path")
        assertFalse(kt.contains("is TypePath"))
        assertFalse(kt.contains("as TypePath"))
    }

    @Test
    fun rfc3762ConstTraitsMacros2AndMetavarExpressionsTranslateCleanly() {
        val dollar = '$'
        val rustSource =
            """
            pub struct BoxVal<T> {
                pub inner: T,
            }

            pub(crate) const unsafe trait BytewiseEq {
                fn tag(&self) -> i32 {
                    1
                }
            }

            pub const trait ScoreBound: [const] BytewiseEq {
                fn score(&self) -> i32;
            }

            macro_rules! impl_bytewise {
                ($dollar(${dollar}t:ty)+) => {
                    $dollar(
                        const unsafe impl BytewiseEq for ${dollar}t {}
                    )+
                };
            }

            impl_bytewise! { i32 i64 }

            const impl<T: [const] BytewiseEq> ScoreBound for BoxVal<T> {
                fn score(&self) -> i32 {
                    self.inner.tag()
                }
            }

            pub const fn read_score<T: [const] ScoreBound>(item: &T) -> i32 {
                item.score()
            }

            macro sum_weighted($dollar(${dollar}x:ident),+) {
                {
                    let mut total: usize = 0;
                    $dollar(
                        let _ = ($dollar{ignore(${dollar}x)} 0usize);
                        total += $dollar{index()} + $dollar{len()};
                    )+
                    total + $dollar{count(${dollar}x)}
                }
            }

            pub fn check_guard_and_macros(opt: Option<i32>) -> usize {
                let a = 1;
                let b = 2;
                let c = 3;
                let base = sum_weighted!(a, b, c);
                match opt {
                    Some(v) if v > 0 => base + (v as usize),
                    _ => base,
                }
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(kt, "internal interface BytewiseEq")
        assertContains(kt, "public interface ScoreBound : BytewiseEq")
        assertContains(kt, "public open class BoxVal<T>(public var inner: T) : ScoreBound")
        assertContains(kt, "override fun score(): Int")
        assertContains(kt, "public fun <T : ScoreBound> readScore(item: T): Int")
        assertContains(kt, "total += 0 + 3")
        assertContains(kt, "total += 1 + 3")
        assertContains(kt, "total += 2 + 3")
        assertContains(kt, "total + 3")
        assertContains(kt, "opt != null && opt > 0")
    }

    @Test
    fun qualifiedExternalMacroInsideWrapperMacroDoesNotRecurse() {
        val dollar = '$'
        val rustSource =
            """
            macro_rules! log {
                ($dollar(${dollar}tt:tt)*) => {
                    #[cfg(feature = "logging")]
                    {
                        $dollar(${dollar}tt)*
                    }
                }
            }

            macro_rules! debug {
                ($dollar(${dollar}tt:tt)*) => { log!(log::debug!($dollar(${dollar}tt)*)) }
            }

            macro_rules! trace {
                ($dollar(${dollar}tt:tt)*) => { log!(log::trace!($dollar(${dollar}tt)*)) }
            }

            /// Computes an offset while logging diagnostics.
            pub fn compute_offset(val_in: usize) -> usize {
                debug!("val_in={}", val_in);
                trace!("done");
                // Keep this inline comment intact.
                val_in + 1
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(kt, "Computes an offset while logging diagnostics.")
        assertContains(kt, "Keep this inline comment intact.")
        assertContains(kt, "public fun computeOffset(valIn: Int): Int")
        assertContains(kt, "return valIn + 1")
    }

    @Test
    fun mutuallyRecursiveBlockWrappedMacroCapsAtMaxDepth() {
        val rustSource =
            """
            macro_rules! ping {
                () => {
                    {
                        pong!();
                    }
                };
            }

            macro_rules! pong {
                () => {
                    {
                        ping!();
                    }
                };
            }

            pub fn run_ping() -> i32 {
                ping!();
                7
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(kt, "public fun runPing(): Int")
        assertContains(kt, "return 7")
    }

    @Test
    fun proseCommentsAvoidLifetimeTicksAndMatchBracesAndSnakeCaseCardIds() {
        val rustSource =
            """
            /// Look for a match at 'ptr' across repeated-{huge,small} cases; bit 'i' stays intact.
            pub fn scan_probe(x: usize) -> usize {
                x
            }

            #[test]
            fn prop_check() {
                quickcheck::quickcheck!(fn prop() -> bool { true });
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(kt, "`ptr`")
        assertContains(kt, "`i`")
        assertContains(kt, "repeated-(huge,small)")
        assertFalse(kt.contains("'ptr'"))
        assertFalse(kt.contains("t_19971811"))
    }
}

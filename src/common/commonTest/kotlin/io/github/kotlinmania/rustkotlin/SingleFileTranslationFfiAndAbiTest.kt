package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
class SingleFileTranslationFfiAndAbiTest {
    @Test fun libcMacrosExpandStructsUnionsTupleStructsCfgIfCEnumsAndFunctions() {
        val libcMacrosRs =
            """
            macro_rules! cfg_if {
                (${'$'}(
                    if #[cfg(${'$'}(${'$'}meta:meta),*)] { ${'$'}(${'$'}it:item)* }
                ) else * else {
                    ${'$'}(${'$'}it2:item)*
                }) => {
                    cfg_if! {
                        @__items
                        () ;
                        ${'$'}( ( (${'$'}(${'$'}meta),*) (${'$'}(${'$'}it)*) ), )*
                        ( () (${'$'}(${'$'}it2)*) ),
                    }
                };
                (
                    if #[cfg(${'$'}(${'$'}i_met:meta),*)] { ${'$'}(${'$'}i_it:item)* }
                    ${'$'}(
                        else if #[cfg(${'$'}(${'$'}e_met:meta),*)] { ${'$'}(${'$'}e_it:item)* }
                    )*
                ) => {
                    cfg_if! {
                        @__items
                        () ;
                        ( (${'$'}(${'$'}i_met),*) (${'$'}(${'$'}i_it)*) ),
                        ${'$'}( ( (${'$'}(${'$'}e_met),*) (${'$'}(${'$'}e_it)*) ), )*
                        ( () () ),
                    }
                };
                (@__items (${'$'}(${'$'}not:meta,)*) ; ) => {};
                (@__items (${'$'}(${'$'}not:meta,)*) ; ( (${'$'}(${'$'}m:meta),*) (${'$'}(${'$'}it:item)*) ), ${'$'}(${'$'}rest:tt)*) => {
                    cfg_if! { @__apply cfg(all(${'$'}(${'$'}m,)* not(any(${'$'}(${'$'}not),*)))), ${'$'}(${'$'}it)* }
                    cfg_if! { @__items (${'$'}(${'$'}not,)* ${'$'}(${'$'}m,)*) ; ${'$'}(${'$'}rest)* }
                };
                (@__apply ${'$'}m:meta, ${'$'}(${'$'}it:item)*) => {
                    ${'$'}(#[${'$'}m] ${'$'}it)*
                };
            }

            macro_rules! s {
                (${'$'}(
                    ${'$'}(#[${'$'}attr:meta])*
                    ${'$'}pub:vis ${'$'}t:ident ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }
                )*) => (${'$'}(
                    s!(it: ${'$'}(#[${'$'}attr])* ${'$'}pub ${'$'}t ${'$'}i { ${'$'}(${'$'}field)* });
                )*);
                (it: ${'$'}(#[${'$'}attr:meta])* ${'$'}pub:vis struct ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }) => (
                    #[repr(C)]
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}pub struct ${'$'}i { ${'$'}(${'$'}field)* }
                );
            }

            macro_rules! s_paren {
                (${'$'}(
                    ${'$'}(#[${'$'}attr:meta])*
                    ${'$'}pub:vis struct ${'$'}i:ident ( ${'$'}(${'$'}field:tt)* );
                )*) => (${'$'}(
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}pub struct ${'$'}i ( ${'$'}(${'$'}field)* );
                )*);
            }

            macro_rules! s_no_extra_traits {
                (${'$'}(
                    ${'$'}(#[${'$'}attr:meta])*
                    ${'$'}pub:vis ${'$'}t:ident ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }
                )*) => (${'$'}(
                    s_no_extra_traits!(it: ${'$'}(#[${'$'}attr])* ${'$'}pub ${'$'}t ${'$'}i { ${'$'}(${'$'}field)* });
                )*);
                (it: ${'$'}(#[${'$'}attr:meta])* ${'$'}pub:vis union ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }) => (
                    #[repr(C)]
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}pub union ${'$'}i { ${'$'}(${'$'}field)* }
                );
                (it: ${'$'}(#[${'$'}attr:meta])* ${'$'}pub:vis struct ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }) => (
                    #[repr(C)]
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}pub struct ${'$'}i { ${'$'}(${'$'}field)* }
                );
            }

            macro_rules! c_enum {
                (${'$'}(
                    ${'$'}(#[repr(${'$'}repr:ty)])?
                    pub enum ${'$'}(${'$'}ty_name:ident)? ${'$'}(#${'$'}anon:ident)? {
                        ${'$'}(${'$'}vis:vis ${'$'}variant:ident ${'$'}(= ${'$'}value:expr)?,)+
                    }
                )+) => {
                    ${'$'}(c_enum!(@single;
                        ${'$'}(#[repr(${'$'}repr)])?
                        pub enum ${'$'}(${'$'}ty_name)? ${'$'}(#${'$'}anon)? {
                            ${'$'}(${'$'}vis ${'$'}variant ${'$'}(= ${'$'}value)?,)+
                        }
                    );)+
                };
                (@single;
                    ${'$'}(#[repr(${'$'}repr:ty)])?
                    pub enum ${'$'}ty_name:ident {
                        ${'$'}(${'$'}vis:vis ${'$'}variant:ident ${'$'}(= ${'$'}value:expr)?,)+
                    }
                ) => {
                    pub type ${'$'}ty_name = c_enum!(@ty ${'$'}(${'$'}repr)?);
                    c_enum! {
                        @variant;
                        ty: ${'$'}ty_name;
                        default: 0;
                        variants: [${'$'}(${'$'}vis ${'$'}variant ${'$'}(= ${'$'}value)?,)+]
                    }
                };
                (@single;
                    ${'$'}(#[repr(${'$'}repr:ty)])?
                    pub enum #anon {
                        ${'$'}(${'$'}vis:vis ${'$'}variant:ident ${'$'}(= ${'$'}value:expr)?,)+
                    }
                ) => {
                    c_enum! {
                        @variant;
                        ty: c_enum!(@ty ${'$'}(${'$'}repr)?);
                        default: 0;
                        variants: [${'$'}(${'$'}vis ${'$'}variant ${'$'}(= ${'$'}value)?,)+]
                    }
                };
                (@variant; ty: ${'$'}_ty_name:ty; default: ${'$'}_idx:expr; variants: []) => {};
                (
                    @variant;
                    ty: ${'$'}ty_name:ty;
                    default: ${'$'}default_val:expr;
                    variants: [
                        ${'$'}vis:vis ${'$'}variant:ident ${'$'}(= ${'$'}value:expr)?,
                        ${'$'}(${'$'}tail:tt)*
                    ]
                ) => {
                    ${'$'}vis const ${'$'}variant: ${'$'}ty_name = {
                        #[allow(unused_variables)]
                        let r = ${'$'}default_val;
                        ${'$'}(let r = ${'$'}value;)?
                        r
                    };
                    c_enum! {
                        @variant;
                        ty: ${'$'}ty_name;
                        default: ${'$'}variant + 1;
                        variants: [${'$'}(${'$'}tail)*]
                    }
                };
                (@ty ${'$'}repr:ty) => { ${'$'}repr };
                (@ty) => { ${'$'}crate::prelude::CEnumRepr };
            }

            macro_rules! f {
                (${'$'}(
                    ${'$'}(#[${'$'}attr:meta])*
                    pub ${'$'}(fn ${'$'}i:ident)? ${'$'}(const fn ${'$'}const_i:ident)?
                    (${'$'}(${'$'}arg:ident: ${'$'}argty:ty),* ${'$'}(,)*) -> ${'$'}ret:ty
                        ${'$'}body:block
                )+) => {${'$'}(
                    #[inline]
                    ${'$'}(#[${'$'}attr])*
                    pub ${'$'}(unsafe extern "C" fn ${'$'}i)? ${'$'}(const unsafe extern "C" fn ${'$'}const_i)?
                    (${'$'}(${'$'}arg: ${'$'}argty),*) -> ${'$'}ret
                        ${'$'}body
                )+};
            }
            """.trimIndent()

        val source =
            """
            s! {
                pub struct timeval {
                    pub tv_sec: i64,
                    #[cfg(target_arch = "x86_64")]
                    pub tv_usec: i64,
                    #[cfg(not(target_arch = "x86_64"))]
                    pub tv_usec: i32,
                }

                struct InternalPair {
                    a: u32,
                    b: u32,
                }
            }

            s_no_extra_traits! {
                pub union epoll_data {
                    pub u64_val: u64,
                    pub u32_val: u32,
                }
            }

            s_paren! {
                pub struct SceUid(pub i32);
            }

            cfg_if! {
                if #[cfg(target_env = "gnu")] {
                    pub const MODE_A: u32 = !0;
                    s! {
                        pub struct sched_param {
                            pub sched_priority: i32,
                        }
                    }
                } else {
                    pub const MODE_B: u32 = 1;
                    s! {
                        pub struct sched_param {
                            pub sched_priority: i32,
                        }
                    }
                }
            }

            c_enum! {
                pub enum clock_kind {
                    pub CLOCK_REALTIME = 0,
                    pub CLOCK_MONOTONIC,
                }

                #[repr(u32)]
                pub enum #anon {
                    pub TX_SETUP = 1,
                    pub TX_DELETE,
                }
            }

            f! {
                pub const fn mask_clear(flags: u32,) -> u32 {
                    flags & !MODE_B
                }
            }
            """.trimIndent()

        val header =
            FileHeaderContext(
                portLintSourcePath = "unix/sched.rs",
                packageName = "io.github.kotlinmania.libc.unix",
                crateMacroSources = listOf(libcMacrosRs),
            )
        val kt = renderSingleKotlinFile(source, header).text

        assertContains(kt, "public open class Timeval(public var tvSec: Long, public var tvUsec: Long)")
        assertEquals(1, Regex("\\bvar tvUsec:").findAll(kt).count())
        assertContains(kt, "public open class InternalPair(public var a: UInt, public var b: UInt)")
        assertContains(kt, "public open class EpollData(public var u64Val: ULong? = null, public var u32Val: UInt? = null)")
        assertContains(kt, "public open class SceUid(public var value: Int)")
        assertEquals(1, Regex("class SchedParam\\b").findAll(kt).count())
        assertContains(kt, "public val MODE_A: UInt = 0u.inv()")
        assertContains(kt, "public typealias ClockKind = CEnumRepr")
        assertContains(kt, "public val CLOCK_REALTIME: ClockKind = 0")
        assertContains(kt, "public val CLOCK_MONOTONIC: ClockKind = CLOCK_REALTIME + 1")
        assertContains(kt, "public val TX_SETUP: UInt = 1u")
        assertContains(kt, "public val TX_DELETE: UInt = TX_SETUP + 1u")
        assertFalse(kt.contains("run {"))
        assertContains(kt, "public fun maskClear(flags: UInt): UInt")
        assertContains(kt, "return flags and MODE_B.inv()")
    }

    @Test fun foreignExternCGeneratesExpectDeclarationsDefHeaderCSourceAndNativeActuals() {
        val rustSource =
            """
            //! Header: `stdlib.h`
            #[link(name = "m")]
            extern "C" {
                pub fn realloc(p: *mut c_void, size: usize) -> *mut c_void;
                pub fn strchr(cs: *const c_char, c: i32) -> *mut c_char;
                pub fn fclose(stream: *mut FILE) -> i32;
            }
            """.trimIndent()

        val files = TestFilesHarness(rustSource)
        files.run(
            "translate",
            "-n",
            "io.github.kotlinmania.libc",
            "tmp/libc/src/new/common/posix/stdlib.rs",
            "/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/new/common/posix/Stdlib.kt",
        )

        val commonKt =
            files.writes.getValue(
                "/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/new/common/posix/Stdlib.kt",
            )
        assertFalse(commonKt.contains("import io.github.kotlinmania.rustlang.core.ffi.CVoid"))
        assertContains(commonKt, "import io.github.kotlinmania.rustlang.core.ptr.ConstPtr")
        assertContains(commonKt, "import io.github.kotlinmania.rustlang.core.ptr.MutPtr")
        assertContains(commonKt, "public expect fun realloc(p: MutPtr, size: ULong): MutPtr")
        assertContains(commonKt, "public expect fun strchr(cs: ConstPtr, c: Int): MutPtr")
        assertContains(commonKt, "public expect fun fclose(stream: MutPtr): Int")

        val defText = files.writes.getValue("/proj/src/native/interop/cinterop/Stdlib.def")
        assertContains(defText, "package = io.github.kotlinmania.libc.new.common.posix.cinterop")
        assertContains(defText, "headers = stdlib_wrapper.h")
        assertContains(defText, "staticLibraries = stdlib_wrapper.a")
        assertContains(defText, "noStringConversion = libcStdlibStrchr")

        val hText = files.writes.getValue("/proj/src/native/interop/cinterop/stdlib_wrapper.h")
        assertContains(hText, "#include <stdint.h>")
        assertContains(hText, "void* libcStdlibRealloc(void* p, uint64_t size);")
        assertContains(hText, "char* libcStdlibStrchr(const char* cs, int32_t c);")
        assertContains(hText, "int32_t libcStdlibFclose(void* stream);")

        val cText = files.writes.getValue("/proj/src/native/interop/cinterop/stdlib_wrapper.c")
        assertContains(cText, "#include \"stdlib_wrapper.h\"")
        assertContains(cText, "#include <stdlib.h>")
        assertContains(cText, "#include <stdio.h>")
        assertContains(cText, "#include <string.h>")
        assertContains(cText, "return (void*)realloc(p, size);")
        assertContains(cText, "return (char*)strchr(cs, c);")
        assertContains(cText, "return (int32_t)fclose((FILE*)stream);")

        val nativeKt =
            files.writes.getValue(
                "/proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/new/common/posix/StdlibNativeActuals.kt",
            )
        assertContains(nativeKt, "@file:OptIn(ExperimentalForeignApi::class")
        assertContains(nativeKt, "import kotlinx.cinterop.ExperimentalForeignApi")
        assertContains(nativeKt, "public actual fun realloc(p: MutPtr, size: ULong): MutPtr")
        assertContains(nativeKt, "public actual fun strchr(cs: ConstPtr, c: Int): MutPtr")
        assertContains(nativeKt, "MutPtr(libcStdlibRealloc(p.rawAddress.toCPointer<ByteVar>(), size)?.toLong() ?: 0L)")
        assertFalse(nativeKt.contains("toKString"))
        assertFalse(nativeKt.contains("COpaquePointer"))
    }

    @Test fun inlineCppMacroGeneratesDefHeaderCppSourceAndExpectActualBridge() {
        val rustSource =
            """
            cpp! {{
                #include <cmath>
                static int scale_factor() { return 3; }
            }}

            pub fn compute_scaled(input_val: i32) -> i32 {
                cpp!([input_val as "int32_t"] -> i32 as "int32_t" {
                    return input_val * scale_factor();
                })
            }
            """.trimIndent()

        val header =
            FileHeaderContext(
                portLintSourcePath = "inline_math.rs",
                packageName = "io.github.kotlinmania.sample",
                projectNamespace = "io.github.kotlinmania.sample",
            )
        val result = renderSingleKotlinFile(rustSource, header)

        assertContains(result.text, "public fun computeScaled(inputVal: Int): Int")
        assertContains(result.text, "return computeScaledCpp(inputVal)")
        assertContains(result.text, "internal expect fun computeScaledCpp(inputVal: Int): Int")

        assertEquals(11, result.companionFiles.size)
        val byName = result.companionFiles.associateBy { it.relativePath }
        assertTrue(byName.containsKey("InlineMath.def"))
        assertTrue(byName.containsKey("inline_math_wrapper.h"))
        val cppFile = requireNotNull(byName["inline_math_wrapper.cpp"])
        assertEquals(CompanionInteropKind.CppSource, cppFile.kind)
        assertContains(cppFile.content, "#include <cmath>")
        assertContains(cppFile.content, "extern \"C\" {")
        assertContains(cppFile.content, "return input_val * scale_factor();")
        val nativeActuals = requireNotNull(byName["InlineMathNativeActuals.kt"])
        assertContains(nativeActuals.content, "internal actual fun computeScaledCpp(inputVal: Int): Int")
    }

    @Test fun libcConstantNamingUnsignedAliasesSamCallbacksAndSealedDiscriminantEnums() {
        val rustSource =
            """
            pub type canid_t = u32;
            pub type sighandler_t = Option<extern "C" fn(i32)>;

            pub const SYS_exit: i32 = 60;
            pub const __O_LARGEFILE: u32 = 0x8000;
            pub const AV_386_AMD_3DNow: u32 = 1 << 31;
            pub const CAN_EFF_FLAG: canid_t = 0x80000000;
            pub const MASK_REST: canid_t = !__O_LARGEFILE;

            #[repr(C)]
            pub enum AudioSampleFrequency {
                Khz44_1 = 44100,
                Khz48,
            }

            pub struct HandlerConfig {
                pub stub: i32,
                pub on_tick: Option<extern "C" fn(i32) -> i32>,
            }

            pub fn next_syscall() -> i32 {
                SYS_exit + 1
            }
            """.trimIndent()

        val header =
            FileHeaderContext(
                portLintSourcePath = "linux/can.rs",
                packageName = "io.github.kotlinmania.libc.linux",
                projectNamespace = "io.github.kotlinmania.libc",
            )
        val kt = renderSingleKotlinFile(rustSource, header).text

        assertContains(kt, "public typealias CanidT = UInt")
        assertContains(kt, "public fun interface SighandlerTFn")
        assertContains(kt, "public typealias SighandlerT = SighandlerTFn?")
        assertContains(kt, "public val SYS_EXIT: Int = 60")
        assertContains(kt, "public val O_LARGEFILE: UInt = 0x8000u")
        assertContains(kt, "public val AV_386_AMD_3D_NOW: UInt = 1u shl 31")
        assertContains(kt, "public val CAN_EFF_FLAG: CanidT = 0x80000000u")
        assertContains(kt, "public val MASK_REST: CanidT = O_LARGEFILE.inv()")
        assertContains(kt, "public sealed class AudioSampleFrequency(public val value: Int)")
        assertContains(kt, "public data object Khz441 : AudioSampleFrequency(44100)")
        assertContains(kt, "public data object Khz48 : AudioSampleFrequency(Khz441.value + 1)")
        assertContains(kt, "public var skeletalImpl: Int")
        assertContains(kt, "public fun interface HandlerConfigOnTickCallback")
        assertContains(kt, "public var onTick: HandlerConfigOnTickCallback?")
        assertFalse(kt.contains("-> Int"))
        assertContains(kt, "return SYS_EXIT + 1")
    }

    @Test fun ffiPointerWidthTypedefsRawBytePointersAndInlineOperatorSamInterfaces() {
        val rustSource =
            """
            pub type size_t = usize;
            pub type uintptr_t = usize;
            pub type ssize_t = isize;
            pub type ptrdiff_t = isize;
            pub type intptr_t = isize;

            pub struct ThreadContext {
                pub id: i32,
            }

            // Thread entry callback typedef
            pub type ThreadEntry = Option<extern "C" fn(argc: size_t, argp: *mut u8) -> i32>;
            """.trimIndent()
        val header =
            FileHeaderContext(
                portLintSourcePath = "psp.rs",
                packageName = "io.github.kotlinmania.libc.psp",
                projectNamespace = "io.github.kotlinmania.libc",
            )
        val kt = renderSingleKotlinFile(rustSource, header).text

        assertContains(kt, "import io.github.kotlinmania.rustlang.core.ptr.MutPtr")
        assertContains(kt, "public typealias SizeT = ULong")
        assertContains(kt, "public typealias UintptrT = ULong")
        assertContains(kt, "public typealias SsizeT = Long")
        assertContains(kt, "public typealias PtrdiffT = Long")
        assertContains(kt, "public typealias IntptrT = Long")
        assertContains(
            kt,
            "// Thread entry callback typedef\n" +
                "public fun interface ThreadEntryFn {\n" +
                "    public operator fun invoke(argc: SizeT, argp: MutPtr): Int\n" +
                "}\n\n" +
                "public typealias ThreadEntry = ThreadEntryFn?",
        )
        assertTrue(kt.indexOf("class ThreadContext") < kt.indexOf("fun interface ThreadEntryFn"))
    }

    @Test fun libcMacrosCEnumAndSafeFExpandCleanly() {
        val libcMacros =
            """
            macro_rules! s {
                (${'$'}(
                    ${'$'}(#[${'$'}attr:meta])*
                    ${'$'}pub:vis struct ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }
                )*) => (${'$'}(
                    #[repr(C)]
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}pub struct ${'$'}i { ${'$'}(${'$'}field)* }
                )*);
            }
            macro_rules! c_enum {
                (${'$'}(
                    ${'$'}(#[repr(${'$'}repr:ty)])?
                    pub enum ${'$'}(${'$'}ty_name:ident)? ${'$'}(#${'$'}anon:ident)? {
                        ${'$'}(${'$'}vis:vis ${'$'}variant:ident ${'$'}(= ${'$'}value:expr)?,)+
                    }
                )+) => {
                    ${'$'}(c_enum!(@single;
                        ${'$'}(#[repr(${'$'}repr)])?
                        pub enum ${'$'}(${'$'}ty_name)? ${'$'}(#${'$'}anon)? {
                            ${'$'}(${'$'}vis ${'$'}variant ${'$'}(= ${'$'}value)?,)+
                        }
                    );)+
                };
                (@single;
                    ${'$'}(#[repr(${'$'}repr:ty)])?
                    pub enum ${'$'}ty_name:ident {
                        ${'$'}(${'$'}vis:vis ${'$'}variant:ident ${'$'}(= ${'$'}value:expr)?,)+
                    }
                ) => {
                    pub type ${'$'}ty_name = c_enum!(@ty ${'$'}(${'$'}repr)?);
                    c_enum! {
                        @variant;
                        ty: ${'$'}ty_name;
                        default: 0;
                        variants: [${'$'}(${'$'}vis ${'$'}variant ${'$'}(= ${'$'}value)?,)+]
                    }
                };
                (@variant; ty: ${'$'}_ty_name:ty; default: ${'$'}_idx:expr; variants: []) => {};
                (
                    @variant;
                    ty: ${'$'}ty_name:ty;
                    default: ${'$'}default_val:expr;
                    variants: [
                        ${'$'}vis:vis ${'$'}variant:ident ${'$'}(= ${'$'}value:expr)?,
                        ${'$'}(${'$'}tail:tt)*
                    ]
                ) => {
                    ${'$'}vis const ${'$'}variant: ${'$'}ty_name = {
                        let r = ${'$'}default_val;
                        ${'$'}(let r = ${'$'}value;)?
                        r
                    };
                    c_enum! {
                        @variant;
                        ty: ${'$'}ty_name;
                        default: ${'$'}variant + 1;
                        variants: [${'$'}(${'$'}tail)*]
                    }
                };
                (@ty ${'$'}repr:ty) => { ${'$'}repr };
                (@ty) => { ${'$'}crate::prelude::CEnumRepr };
            }
            """.trimIndent()
        val rustSource =
            """
            c_enum! {
                pub enum clockid_t {
                    pub CLOCK_REALTIME = 0,
                    pub CLOCK_MONOTONIC = 6,
                }
            }
            s! {
                pub struct timespec {
                    pub tv_sec: i64,
                    pub tv_nsec: i64,
                }
            }
            """.trimIndent()
        val kt =
            renderSingleKotlinFile(
                rustSource,
                FileHeaderContext(
                    portLintSourcePath = "unix/bsd/apple/mod.rs",
                    packageName = "io.github.kotlinmania.libc.unix.bsd.apple",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.libc",
                    portLintDirective = "none",
                    crateMacroSources = listOf(libcMacros),
                    finalClasses = true,
                ),
            ).text
        assertContains(kt, "public typealias ClockidT = CEnumRepr")
        assertContains(kt, "CLOCK_REALTIME")
        assertContains(kt, "CLOCK_MONOTONIC")
        assertContains(kt, "public data class Timespec(")
    }

    @Test fun cDynamicFfiEmitsConcreteFunctionsAndMultiTargetScaffoldWithZeroExpects() {
        val files =
            mapOf(
                "tmp/libc/src/lib.rs" to
                    """
                    pub type c_int = i32;
                    pub type c_char = i8;
                    pub mod unix;
                    """.trimIndent(),
                "tmp/libc/src/unix/mod.rs" to
                    """
                    use crate::{c_char, c_int};
                    extern "C" {
                        pub static mut optind: c_int;
                        pub fn getpid() -> c_int;
                        #[link_name = "_putenv_s"]
                        pub fn putenv_s(name: *const c_char, value: *const c_char) -> c_int;
                    }
                    """.trimIndent(),
            )
        val written =
            runSplitCrateCommand(
                args =
                    arrayOf(
                        "-r",
                        "--force",
                        "--final-structs",
                        "--split-module-roots",
                        "-n",
                        "io.github.kotlinmania.libc",
                        "-o",
                        "proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc",
                        "tmp/libc/src",
                    ),
                sources = files,
                dirRoot = "tmp/libc/src",
                useCrateMetadata = false,
            )

        assertFalse(written.keys.any { it.endsWith("/Lib.kt") || it.endsWith("/Mod.kt") })
        assertFalse(written.values.any { it.contains("port-lint") })
        val unixFns = written["proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/unix/UnixFunctions.kt"]
        val unixConsts = written["proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/unix/UnixConstants.kt"]
        assertTrue(unixFns != null)
        assertContains(unixFns, "public expect fun getpid(): CInt")
        assertContains(unixFns, "public expect fun putenvS(name: ConstPtr, value: ConstPtr): CInt")
        val staticContainer = unixConsts ?: unixFns
        assertContains(staticContainer, "public expect var OPTIND: CInt")

        assertTrue("proj/src/native/interop/cinterop/Unix.def" in written)
        assertTrue("proj/src/native/interop/cinterop/unix_wrapper.h" in written)
        assertTrue("proj/src/native/interop/cinterop/unix_wrapper.c" in written)
        assertTrue("proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/unix/UnixNativeActuals.kt" in written)
        assertTrue("proj/src/jvm/jvmMain/kotlin/io/github/kotlinmania/libc/unix/UnixJvmActuals.kt" in written)
        assertTrue("proj/src/jvm/jvmMain/c/libc_unix_jni.c" in written)
        assertTrue("proj/src/android/jvm/androidMain/kotlin/io/github/kotlinmania/libc/unix/UnixAndroidActuals.kt" in written)
        assertTrue("proj/src/js/jsMain/kotlin/io/github/kotlinmania/libc/unix/UnixJsActuals.kt" in written)
        assertTrue("proj/src/js/node-libc/src/libc_unix_bindings.cpp" in written)
        assertTrue("proj/src/js/wasm/wasmJsMain/kotlin/io/github/kotlinmania/libc/unix/UnixWasmJsActuals.kt" in written)
        assertTrue("proj/src/js/wasm/wasmWasiMain/kotlin/io/github/kotlinmania/libc/unix/UnixWasmWasiActuals.kt" in written)
    }

    @Test fun vhfNtstatusAndCrossModuleTypeAliasCollisionPreserveNativeAbi() {
        val sources =
            listOf(
                "lib.rs" to "pub mod Windows;",
                "Windows/mod.rs" to "pub mod Wdk;\npub mod Win32;",
                "Windows/Win32/mod.rs" to "pub mod Foundation;\npub mod Networking;",
                "Windows/Win32/Foundation/mod.rs" to
                    """
                    pub type NTSTATUS = i32;
                    pub type BOOL = i32;
                    pub type HANDLE = *mut core::ffi::c_void;
                    """.trimIndent(),
                "Windows/Wdk/mod.rs" to "pub mod Devices;",
                "Windows/Wdk/Devices/mod.rs" to "pub mod HumanInterfaceDevice;",
                "Windows/Wdk/Devices/HumanInterfaceDevice/mod.rs" to
                    """
                    windows_link::link!("VhfUm.dll" "C" fn VhfAsyncOperationComplete(vhfoperationhandle : *const core::ffi::c_void, completionstatus : super::super::super::Win32::Foundation::NTSTATUS) -> super::super::super::Win32::Foundation::NTSTATUS);
                    windows_link::link!("VhfUm.dll" "C" fn VhfCreate(vhfconfig : *const core::ffi::c_void, vhfhandle : *mut *mut core::ffi::c_void) -> super::super::super::Win32::Foundation::NTSTATUS);
                    windows_link::link!("VhfUm.dll" "C" fn VhfReadReportSubmit(vhfhandle : *const core::ffi::c_void, hidtransferpacket : *const core::ffi::c_void) -> super::super::super::Win32::Foundation::NTSTATUS);
                    windows_link::link!("VhfUm.dll" "C" fn VhfStart(vhfhandle : *const core::ffi::c_void) -> super::super::super::Win32::Foundation::NTSTATUS);
                    """.trimIndent(),
                "Windows/Win32/Networking/mod.rs" to "pub mod ActiveDirectory;",
                "Windows/Win32/Networking/ActiveDirectory/mod.rs" to
                    """
                    pub type BOOL = bool;
                    windows_link::link!("activeds.dll" "system" fn ADsCheckFlag(flag : super::super::Foundation::BOOL) -> super::super::Foundation::BOOL);
                    """.trimIndent(),
            )
        val fileMap = sources.associate { (rel, txt) -> "tmp/windows-sys/src/$rel" to txt }
        val meta =
            buildCrateRawAbiMetadataFromSources(
                sources = sources,
                rawAbiMode = true,
                splitModuleRoots = true,
                finalStructs = true,
            )
        val written = linkedMapOf<String, String>()
        runSingleFileCommand(
            args =
                arrayOf(
                    "-r",
                    "--force",
                    "--raw-abi",
                    "--final-structs",
                    "--split-module-roots",
                    "-n",
                    "io.github.kotlinmania.windowssys",
                    "-o",
                    "proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys",
                    "tmp/windows-sys/src",
                ),
            read = { path -> fileMap[path] ?: error("missing $path") },
            writeForce = { path, text -> written[path] = text },
            exists = { false },
            isDirectory = { it == "tmp/windows-sys/src" },
            collectRustFiles = { fileMap.keys.toList() },
            report = {},
            resolveRustKotlinProperties = { null },
            resolvePackageProperties = { null },
            resolveCrateMacroSources = { emptyList() },
            resolveCrateFlatFileModules = { _, _ -> emptySet() },
            resolveCrateRawAbiMetadata = { _, _, _, _ -> meta },
        )

        val hidFns =
            written[
                "proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/" +
                    "windows/wdk/devices/humaninterfacedevice/HumanInterfaceDeviceFunctions.kt",
            ]
        assertTrue(hidFns != null)
        assertContains(hidFns, "import io.github.kotlinmania.windowssys.windows.win32.foundation.NTSTATUS")
        assertContains(
            hidFns,
            "public expect fun VhfAsyncOperationComplete(vhfoperationhandle: Long, completionstatus: NTSTATUS): NTSTATUS",
        )
        assertContains(hidFns, "public expect fun VhfCreate(vhfconfig: Long, vhfhandle: Long): NTSTATUS")
        assertContains(hidFns, "public expect fun VhfReadReportSubmit(vhfhandle: Long, hidtransferpacket: Long): NTSTATUS")
        assertContains(hidFns, "public expect fun VhfStart(vhfhandle: Long): NTSTATUS")

        val adTypes =
            written[
                "proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/" +
                    "windows/win32/networking/activedirectory/ActiveDirectoryTypes.kt",
            ]
        val adFns =
            written[
                "proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/" +
                    "windows/win32/networking/activedirectory/ActiveDirectoryFunctions.kt",
            ]
        assertTrue(adTypes != null && adFns != null)
        assertContains(adTypes, "public typealias BOOL = Boolean")
        assertContains(
            adFns,
            "public expect fun ADsCheckFlag(flag: io.github.kotlinmania.windowssys.windows.win32.foundation.BOOL): " +
                "io.github.kotlinmania.windowssys.windows.win32.foundation.BOOL",
        )
    }

    @Test fun u128AndGuidLoweringIsPurelyStructuralAcrossSentinelAndRenamedTypes() {
        val guid128Source =
            """
            #[repr(C)]
            pub struct GUID {
                pub data1: u32,
                pub data2: u16,
                pub data3: u16,
                pub data4: [u8; 8],
            }
            impl GUID {
                pub const fn from_u128(uuid: u128) -> Self {
                    Self {
                        data1: (uuid >> 96) as u32,
                        data2: ((uuid >> 80) & 0xffff) as u16,
                        data3: ((uuid >> 64) & 0xffff) as u16,
                        data4: [
                            (uuid >> 56) as u8,
                            (uuid >> 48) as u8,
                            (uuid >> 40) as u8,
                            (uuid >> 32) as u8,
                            (uuid >> 24) as u8,
                            (uuid >> 16) as u8,
                            (uuid >> 8) as u8,
                            uuid as u8,
                        ],
                    }
                }
            }
            pub const IID_IUNKNOWN: GUID = GUID::from_u128(0x00000000_0000_0000_c000_000000000046);
            """.trimIndent()
        val guidKt =
            renderSingleKotlinFile(
                guid128Source,
                FileHeaderContext(
                    portLintSourcePath = "guid.rs",
                    packageName = "io.github.kotlinmania.sample",
                    rawAbiMode = true,
                    finalClasses = true,
                ),
            ).text
        assertContains(guidKt, "public class U128(")
        assertContains(guidKt, "public fun from_u128(uuid: U128): GUID")
        assertContains(guidKt, "GUID.from_u128(U128(0x0uL, 0xc000000000000046uL))")

        val sentinelGuidSource =
            """
            pub struct GUID(pub u64);
            impl GUID {
                pub const fn from_u128(seed: u64) -> Self {
                    Self(seed ^ 0x55)
                }
            }
            pub const SENTINEL_ID: GUID = GUID::from_u128(7);
            """.trimIndent()
        val sentinelKt =
            renderSingleKotlinFile(
                sentinelGuidSource,
                FileHeaderContext(
                    portLintSourcePath = "sentinel.rs",
                    packageName = "io.github.kotlinmania.sample",
                ),
            ).text
        assertFalse(sentinelKt.contains("class U128"))
        assertFalse(sentinelKt.contains("data1"))
        assertContains(sentinelKt, "seed xor 0x55uL")
        assertContains(sentinelKt, "Guid.fromU128(7uL)")

        val renamed128Source =
            """
            pub struct CustomKey128 {
                pub hi_word: u32,
                pub tail: [u8; 16],
            }
            impl CustomKey128 {
                pub fn make_key(bits: u128) -> Self {
                    Self {
                        hi_word: (bits >> 96) as u32,
                        tail: bits.to_be_bytes(),
                    }
                }
            }
            """.trimIndent()
        val renamedKt =
            renderSingleKotlinFile(
                renamed128Source,
                FileHeaderContext(
                    portLintSourcePath = "custom_key.rs",
                    packageName = "io.github.kotlinmania.sample",
                    finalClasses = true,
                ),
            ).text
        assertContains(renamedKt, "public class U128(")
        assertContains(renamedKt, "bits.toBeBytes()")
        assertContains(renamedKt, "(bits shr 96).toUInt()")
    }

    @Test fun cfgTargetProfileEvaluatesPointerWidthArchEndianEnvAndOpenVsClosedWorld() {
        val cfgSource =
            """
            pub type size_t = usize;
            #[cfg(all(target_arch = "wasm32", target_pointer_width = "32"))]
            pub const IS_WASM32: bool = true;
            #[cfg(target_pointer_width = "64")]
            pub const ONLY_64: i32 = 64;
            #[cfg(feature = "experimental_net")]
            pub const HAS_EXP_NET: i32 = 1;
            pub fn runtime_flags() -> (bool, bool, bool) {
                (
                    cfg!(target_pointer_width = "32"),
                    cfg!(target_endian = "big"),
                    cfg!(windows),
                )
            }
            """.trimIndent()

        val wasmOpenKt =
            renderSingleKotlinFile(
                cfgSource,
                FileHeaderContext(
                    portLintSourcePath = "windows/check.rs",
                    packageName = "io.github.kotlinmania.sample.windows",
                    currentRelativeSourceDir = "windows",
                    rawAbiMode = true,
                    cfgTargetProfile =
                        CfgTargetProfile(
                            pointerWidth = 32,
                            targetEndian = "big",
                            targetArch = "wasm32",
                            targetOs = "unknown",
                            targetFamilies = setOf("wasm"),
                            unknownPolicy = UnknownCfgPolicy.OPEN_WORLD_NULL,
                            useRelativeDirPlatformHints = false,
                        ),
                ),
            ).text
        assertContains(wasmOpenKt, "public typealias size_t = UInt")
        assertContains(wasmOpenKt, "public val IS_WASM32: Boolean = true")
        assertFalse(wasmOpenKt.contains("ONLY_64"))
        assertContains(wasmOpenKt, "public val HAS_EXP_NET: Int = 1")
        assertContains(wasmOpenKt, "Triple(true, true, false)")

        val closedWorld64Kt =
            renderSingleKotlinFile(
                cfgSource,
                FileHeaderContext(
                    portLintSourcePath = "windows/check.rs",
                    packageName = "io.github.kotlinmania.sample.windows",
                    currentRelativeSourceDir = "windows",
                    rawAbiMode = true,
                    cfgTargetProfile =
                        CfgTargetProfile(
                            pointerWidth = 64,
                            targetEndian = "little",
                            targetArch = "aarch64",
                            targetOs = "linux",
                            targetFamilies = setOf("unix"),
                            enabledFeatures = emptySet(),
                            unknownPolicy = UnknownCfgPolicy.CLOSED_WORLD_FALSE,
                            useRelativeDirPlatformHints = false,
                        ),
                ),
            ).text
        assertContains(closedWorld64Kt, "public typealias size_t = ULong")
        assertFalse(closedWorld64Kt.contains("IS_WASM32"))
        assertContains(closedWorld64Kt, "public val ONLY_64: Int = 64")
        assertFalse(closedWorld64Kt.contains("HAS_EXP_NET"))
        assertContains(closedWorld64Kt, "Triple(false, false, false)")
    }

    @Test fun cEnumReprFollowsDeclaredAliasTargetAcrossMsvcUnixI16AndRenamedAliases() {
        val enumMacro =
            """
            macro_rules! c_enum {
                (
                    #[repr(${'$'}repr:ty)]
                    pub enum ${'$'}name:ident {
                        ${'$'}(${'$'}variant:ident = ${'$'}val:expr,)*
                    }
                ) => {
                    pub type ${'$'}name = ${'$'}repr;
                    ${'$'}(pub const ${'$'}variant: ${'$'}name = ${'$'}val;)*
                };
            }
            """.trimIndent()
        val libcTypesAndEnum =
            """
            pub type c_int = i32;
            pub type c_uint = u32;
            #[cfg(all(windows, target_env = "msvc"))]
            pub type CEnumRepr = c_int;
            #[cfg(not(all(windows, target_env = "msvc")))]
            pub type CEnumRepr = c_uint;
            c_enum! {
                #[repr(CEnumRepr)]
                pub enum ModeKind {
                    MODE_FIRST = 0,
                    MODE_SECOND = 1,
                }
            }
            """.trimIndent()

        val msvcKt =
            renderSingleKotlinFile(
                libcTypesAndEnum,
                FileHeaderContext(
                    portLintSourcePath = "types.rs",
                    packageName = "io.github.kotlinmania.libc",
                    crateMacroSources = listOf(enumMacro),
                    cfgTargetProfile =
                        CfgTargetProfile(
                            targetOs = "windows",
                            targetEnv = "msvc",
                            targetFamilies = setOf("windows"),
                        ),
                ),
            ).text
        assertContains(msvcKt, "public typealias CEnumRepr = CInt")
        assertFalse(msvcKt.contains("public typealias CEnumRepr = CUint"))
        assertContains(msvcKt, "public val MODE_FIRST: ModeKind = 0")

        val gnuKt =
            renderSingleKotlinFile(
                libcTypesAndEnum,
                FileHeaderContext(
                    portLintSourcePath = "types.rs",
                    packageName = "io.github.kotlinmania.libc",
                    crateMacroSources = listOf(enumMacro),
                    cfgTargetProfile =
                        CfgTargetProfile(
                            targetOs = "linux",
                            targetEnv = "gnu",
                            targetFamilies = setOf("unix"),
                        ),
                ),
            ).text
        assertContains(gnuKt, "public typealias CEnumRepr = CUint")
        assertFalse(gnuKt.contains("public typealias CEnumRepr = CInt"))
        assertContains(gnuKt, "public val MODE_FIRST: ModeKind = 0u")

        val i16AndRenamed =
            """
            pub type CEnumRepr = i16;
            pub type CustomEnumBacking = i16;
            c_enum! {
                #[repr(CEnumRepr)]
                pub enum SmallTag {
                    TAG_A = 1 + 2,
                }
            }
            c_enum! {
                #[repr(CustomEnumBacking)]
                pub enum OtherTag {
                    OTHER_A = 3 + 4,
                }
            }
            """.trimIndent()
        val smallKt =
            renderSingleKotlinFile(
                i16AndRenamed,
                FileHeaderContext(
                    portLintSourcePath = "small.rs",
                    packageName = "io.github.kotlinmania.sample",
                    crateMacroSources = listOf(enumMacro),
                ),
            ).text
        assertContains(smallKt, "public typealias CEnumRepr = Short")
        assertContains(smallKt, "public typealias CustomEnumBacking = Short")
        assertContains(smallKt, "public val TAG_A: SmallTag = (1 + 2).toShort()")
        assertContains(smallKt, "public val OTHER_A: OtherTag = (3 + 4).toShort()")
    }

    @Test fun tokenAbiScannerPacksArbitraryStructsUnionsSpacedCallbacksAndPointerMacros() {
        val sources =
            listOf(
                "lib.rs" to "pub mod ffi;",
                "ffi/mod.rs" to
                    """
                    #[macro_export]
                    macro_rules! utf8_ptr {
                        (${'$'}lit:literal) => {
                            ::core::concat!(${'$'}lit, '\0').as_ptr()
                        };
                    }
                    #[repr(C)]
                    pub struct PairWord {
                        pub lo: u16,
                        pub hi: u16,
                    }
                    #[repr(C)]
                    pub struct Block16 {
                        pub w0: u64,
                        pub w1: u64,
                    }
                    #[repr(C)]
                    pub union WordOrBytes {
                        pub word: u32,
                        pub half: u16,
                    }
                    pub type HookCb = Option < unsafe /* spaced comment */ extern "system" fn ( code : i32 , ctx : * mut core::ffi::c_void ) -> i32 > ;
                    windows_link::link!("demo.dll" "system" fn DispatchPair(pair : PairWord, blk : Block16, u : WordOrBytes, cb : HookCb) -> PairWord);
                    windows_link::link!("demo.dll" "system" fn ResolveHook() -> HookCb);
                    """.trimIndent(),
            )
        val meta =
            buildCrateRawAbiMetadataFromSources(
                sources = sources,
                rawAbiMode = true,
                splitModuleRoots = true,
                finalStructs = true,
            )
        assertTrue("ffi.PairWord" in meta.valueParamPackTemplates)
        assertTrue("ffi.PairWord" in meta.valueReturnUnpackTemplates)
        assertTrue("ffi.Block16" in meta.valueParamPackTemplates)
        assertTrue("ffi.WordOrBytes" in meta.valueParamPackTemplates)
        assertTrue("ffi.HookCbFn" in meta.fnPtrInvokeTemplates)

        val chunks =
            translateModuleRootIntoFocusedChunks(
                source = sources[1].second,
                modulePascalName = "Ffi",
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.demo.ffi",
                        projectGroup = "io.github.kotlinmania",
                        projectNamespace = "io.github.kotlinmania.demo",
                        portLintDirective = "none",
                        rawAbiMode = true,
                        finalClasses = true,
                        crateTypeAliasDefaults = meta.typeAliasDefaults,
                        crateOptionFnTypes = meta.optionFnTypes,
                        crateNumericTypeKinds = meta.numericTypeKinds,
                        crateStructFieldTypes = meta.structFieldTypes,
                        crateSymbolSubpackages = meta.symbolSubpackages,
                        crateSubpackageRawLeafNames = meta.subpackageRawLeafNames,
                        crateValueParamPackTemplates = meta.valueParamPackTemplates,
                        crateValueReturnUnpackTemplates = meta.valueReturnUnpackTemplates,
                        crateFnPtrInvokeTemplates = meta.fnPtrInvokeTemplates,
                        crateFnParamNumericKinds = meta.fnParamNumericKinds,
                    ),
            )
        val allChunkText = chunks.joinToString("\n\n") { it.text }
        assertContains(allChunkText, "public fun utf8_ptr(lit: String): Long")
        assertContains(allChunkText, "public expect fun DispatchPair(pair: PairWord, blk: Block16, u: WordOrBytes, cb: HookCb): PairWord")
        assertContains(allChunkText, "public expect fun ResolveHook(): HookCb")
    }

    @Test
    fun ffiPointerReturnsPointeeMutabilityAndTargetSizeAliasesRespect64And32BitProfiles() {
        val libRs =
            """
            pub type c_char = i8;
            pub type c_void = u8;
            pub type size_t = usize;
            pub type ssize_t = isize;
            pub type count_alias_t = size_t;
            pub type mut_cstr_t = *mut c_char;
            cfg_if! {
                if #[cfg(all(target_pointer_width = "64", not(windows)))] {
                    pub type c_long = i64;
                } else {
                    pub type c_long = i32;
                }
            }
            #[cfg(unix)]
            pub mod unix;
            #[cfg(target_os = "wasi")]
            pub mod wasi;
            #[cfg(windows)]
            pub mod windows;
            """.trimIndent()
        val unixModRs =
            """
            //! Header: `stdlib.h`
            //! Header: `string.h`
            use crate::{c_char, c_long, c_void, count_alias_t, mut_cstr_t, size_t, ssize_t};
            extern "C" {
                pub fn strdup(cs: *const c_char) -> *mut c_char;
                pub fn strndup(cs: *const c_char, n: size_t) -> *mut c_char;
                pub fn custom_ndup(cs: *const c_char, n: count_alias_t) -> mut_cstr_t;
                pub fn getenv(name: *const c_char) -> *const c_char;
                pub fn strcpy(dst: *mut c_char, src: *const c_char) -> *mut c_char;
                pub fn read_bytes(fd: i32, buf: *mut c_void, count: size_t) -> ssize_t;
                pub fn free(p: *mut c_void);
            }
            """.trimIndent()
        val wasiModRs =
            """
            //! Header: `stdlib.h`
            //! Header: `string.h`
            use crate::{c_char, c_long, c_void, count_alias_t, size_t, ssize_t};
            extern "C" {
                pub fn strdup(cs: *const c_char) -> *mut c_char;
                pub fn strndup(cs: *const c_char, n: size_t) -> *mut c_char;
                pub fn custom_ndup(cs: *const c_char, n: count_alias_t) -> *mut c_char;
                pub fn strtol(s: *const c_char, endp: *mut *mut c_char, base: i32) -> c_long;
                pub fn read_bytes(fd: i32, buf: *mut c_void, count: size_t) -> ssize_t;
                pub fn free(p: *mut c_void);
            }
            """.trimIndent()
        val windowsModRs =
            """
            //! Header: `stdlib.h`
            //! Header: `string.h`
            use crate::{c_char, c_long, count_alias_t, size_t};
            extern "C" {
                pub fn strndup(cs: *const c_char, n: size_t) -> *mut c_char;
                pub fn custom_ndup(cs: *const c_char, n: count_alias_t) -> *mut c_char;
                pub fn strtol(s: *const c_char, endp: *mut *mut c_char, base: i32) -> c_long;
            }
            """.trimIndent()
        val crateSources =
            mapOf(
                "tmp/libc/src/lib.rs" to libRs,
                "tmp/libc/src/unix/mod.rs" to unixModRs,
                "tmp/libc/src/wasi/mod.rs" to wasiModRs,
                "tmp/libc/src/windows/mod.rs" to windowsModRs,
            )

        fun runModuleSplit(
            modPath: String,
            rawAbi: Boolean,
            pointerWidth: String,
            arch: String,
            os: String,
        ): Map<String, String> {
            val flags = mutableListOf<String>()
            flags += "translate"
            flags += "--force"
            flags += "--split-module-roots"
            flags += "--final-structs"
            if (rawAbi) flags += "--raw-abi"
            flags += listOf("--source-root", "tmp/libc/src")
            flags += listOf("--target-pointer-width", pointerWidth)
            flags += listOf("--target-arch", arch)
            flags += listOf("--target-os", os)
            flags += listOf("-n", "io.github.kotlinmania.libc")
            flags += listOf("-o", "/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc")
            flags += modPath
            return runSplitCrateCommand(
                args = flags.toTypedArray(),
                sources = crateSources,
                dirRoot = "tmp/libc/src",
                metadataStripPrefix = "tmp/libc/src/",
            )
        }

        // 1. 64-bit Unix --raw-abi
        val unixRaw = runModuleSplit("tmp/libc/src/unix/mod.rs", rawAbi = true, pointerWidth = "64", arch = "aarch64", os = "macos")
        val unixRawFns = unixRaw.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/unix/UnixFunctions.kt")
        val unixRawH = unixRaw.getValue("/proj/src/native/interop/cinterop/unix_wrapper.h")
        val unixRawC = unixRaw.getValue("/proj/src/native/interop/cinterop/unix_wrapper.c")
        val unixRawDef = unixRaw.getValue("/proj/src/native/interop/cinterop/Unix.def")
        val unixRawNative = unixRaw.getValue("/proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/unix/UnixNativeActuals.kt")

        assertContains(unixRawFns, "public expect fun strdup(cs: Long): Long")
        assertContains(unixRawFns, "public expect fun strndup(cs: Long, n: size_t): Long")
        assertContains(unixRawFns, "public expect fun custom_ndup(cs: Long, n: count_alias_t): mut_cstr_t")
        assertContains(unixRawFns, "public expect fun getenv(name: Long): Long")

        assertContains(unixRawH, "char* libcUnixStrdup(const char* cs);")
        assertContains(unixRawH, "char* libcUnixStrndup(const char* cs, uint64_t n);")
        assertContains(unixRawH, "char* libcUnixCustomNdup(const char* cs, uint64_t n);")
        assertContains(unixRawH, "const char* libcUnixGetenv(const char* name);")
        assertContains(unixRawH, "char* libcUnixStrcpy(char* dst, const char* src);")
        assertContains(unixRawH, "int64_t libcUnixReadBytes(int32_t fd, void* buf, uint64_t count);")
        assertFalse(unixRawH.contains("int32_t n"))

        assertContains(unixRawC, "return (char*)strdup(cs);")
        assertContains(unixRawC, "return (char*)strndup(cs, n);")
        assertContains(unixRawC, "return (const char*)getenv(name);")

        assertContains(unixRawDef, "noStringConversion = ")
        assertContains(unixRawDef, "libcUnixStrdup")
        assertContains(unixRawDef, "libcUnixStrndup")
        assertContains(unixRawDef, "libcUnixGetenv")

        assertFalse(unixRawNative.contains("toKString"))
        assertFalse(unixRawNative.contains("n.toInt()"))
        assertFalse(unixRawNative.contains("?.value"))
        assertContains(unixRawNative, "import io.github.kotlinmania.libc.unix.cinterop.libcUnixStrdup")
        assertContains(
            unixRawNative,
            "public actual fun strdup(cs: Long): Long =\n" +
                "    libcUnixStrdup(cs.toCPointer<ByteVar>())?.toLong() ?: 0L",
        )
        assertContains(
            unixRawNative,
            "public actual fun strndup(cs: Long, n: size_t): Long =\n" +
                "    libcUnixStrndup(cs.toCPointer<ByteVar>(), n)?.toLong() ?: 0L",
        )
        assertContains(
            unixRawNative,
            "public actual fun custom_ndup(cs: Long, n: count_alias_t): mut_cstr_t =\n" +
                "    libcUnixCustomNdup(cs.toCPointer<ByteVar>(), n)?.toLong() ?: 0L",
        )
        assertContains(
            unixRawNative,
            "public actual fun free(p: Long) {\n" +
                "    libcUnixFree(p.toCPointer<ByteVar>())\n" +
                "}",
        )

        // 2. 64-bit Unix mapped mode
        val unixMapped = runModuleSplit("tmp/libc/src/unix/mod.rs", rawAbi = false, pointerWidth = "64", arch = "aarch64", os = "macos")
        val unixMappedFns = unixMapped.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/unix/UnixFunctions.kt")
        val unixMappedH = unixMapped.getValue("/proj/src/native/interop/cinterop/unix_wrapper.h")
        val unixMappedDef = unixMapped.getValue("/proj/src/native/interop/cinterop/Unix.def")
        val unixMappedNative = unixMapped.getValue("/proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/unix/UnixNativeActuals.kt")

        assertContains(unixMappedFns, "public expect fun strdup(cs: ConstPtr): MutPtr")
        assertContains(unixMappedFns, "public expect fun strndup(cs: ConstPtr, n: SizeT): MutPtr")
        assertContains(unixMappedFns, "public expect fun customNdup(cs: ConstPtr, n: CountAliasT): MutCstrT")
        assertContains(unixMappedFns, "public expect fun getenv(name: ConstPtr): ConstPtr")
        assertContains(unixMappedFns, "public expect fun strcpy(dst: MutPtr, src: ConstPtr): MutPtr")
        assertContains(unixMappedH, "char* libcUnixStrdup(const char* cs);")
        assertContains(unixMappedH, "char* libcUnixStrndup(const char* cs, uint64_t n);")
        assertContains(unixMappedH, "const char* libcUnixGetenv(const char* name);")
        assertContains(unixMappedDef, "noStringConversion = ")
        assertFalse(unixMappedNative.contains("toKString"))
        assertFalse(unixMappedNative.contains("COpaquePointer"))
        assertContains(unixMappedNative, "import io.github.kotlinmania.libc.unix.cinterop.libcUnixStrdup")
        assertContains(
            unixMappedNative,
            "public actual fun strdup(cs: ConstPtr): MutPtr =\n" +
                "    MutPtr(libcUnixStrdup(cs.rawAddress.toCPointer<ByteVar>())?.toLong() ?: 0L)",
        )
        assertContains(
            unixMappedNative,
            "public actual fun strndup(cs: ConstPtr, n: SizeT): MutPtr =\n" +
                "    MutPtr(libcUnixStrndup(cs.rawAddress.toCPointer<ByteVar>(), n)?.toLong() ?: 0L)",
        )
        for ((path, content) in unixMapped) {
            if (path.endsWith(".kt")) {
                ZeroUnderscoreIdentifierValidator.requireZeroViolations(content, path)
            }
        }

        // 3. 32-bit WASI control (--raw-abi and mapped)
        val wasiRaw = runModuleSplit("tmp/libc/src/wasi/mod.rs", rawAbi = true, pointerWidth = "32", arch = "wasm32", os = "wasi")
        val wasiRawFns = wasiRaw.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/wasi/WasiFunctions.kt")
        val wasiRawH = wasiRaw.getValue("/proj/src/native/interop/cinterop/wasi_wrapper.h")
        val wasiRawNative = wasiRaw.getValue("/proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/wasi/WasiNativeActuals.kt")
        assertContains(wasiRawFns, "public expect fun strndup(cs: Long, n: size_t): Long")
        assertContains(wasiRawH, "char* libcWasiStrdup(const char* cs);")
        assertContains(wasiRawH, "char* libcWasiStrndup(const char* cs, uint32_t n);")
        assertContains(wasiRawH, "char* libcWasiCustomNdup(const char* cs, uint32_t n);")
        assertContains(wasiRawH, "int32_t libcWasiStrtol(const char* s, void* endp, int32_t base);")
        assertContains(wasiRawH, "int32_t libcWasiReadBytes(int32_t fd, void* buf, uint32_t count);")
        assertFalse(wasiRawH.contains("uint64_t"))
        assertFalse(wasiRawNative.contains("toKString"))
        assertContains(wasiRawNative, "import io.github.kotlinmania.libc.wasi.cinterop.libcWasiStrndup")
        assertContains(
            wasiRawNative,
            "public actual fun strndup(cs: Long, n: size_t): Long =\n" +
                "    libcWasiStrndup(cs.toCPointer<ByteVar>(), n)?.toLong() ?: 0L",
        )
        assertFalse(wasiRawNative.contains("n.toInt()"))
        assertFalse(wasiRawNative.contains("n.toULong()"))

        val wasiMapped = runModuleSplit("tmp/libc/src/wasi/mod.rs", rawAbi = false, pointerWidth = "32", arch = "wasm32", os = "wasi")
        val wasiMappedH = wasiMapped.getValue("/proj/src/native/interop/cinterop/wasi_wrapper.h")
        val wasiMappedNative = wasiMapped.getValue("/proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/wasi/WasiNativeActuals.kt")
        assertContains(wasiMappedH, "char* libcWasiStrdup(const char* cs);")
        assertContains(wasiMappedH, "char* libcWasiStrndup(const char* cs, uint32_t n);")
        assertContains(wasiMappedH, "int32_t libcWasiStrtol(const char* s, void* endp, int32_t base);")
        assertFalse(wasiMappedH.contains("uint64_t"))
        assertFalse(wasiMappedNative.contains("toKString"))
        assertContains(wasiMappedNative, "import io.github.kotlinmania.libc.wasi.cinterop.libcWasiStrndup")
        assertContains(
            wasiMappedNative,
            "public actual fun strndup(cs: ConstPtr, n: SizeT): MutPtr =\n" +
                "    MutPtr(libcWasiStrndup(cs.rawAddress.toCPointer<ByteVar>(), n)?.toLong() ?: 0L)",
        )
        assertFalse(wasiMappedNative.contains("n.toULong()"))
        for ((path, content) in wasiMapped) {
            if (path.endsWith(".kt")) {
                ZeroUnderscoreIdentifierValidator.requireZeroViolations(content, path)
            }
        }

        // 4. 64-bit Windows LLP64 control: size_t is 64-bit while c_long remains 32-bit
        val winRaw = runModuleSplit("tmp/libc/src/windows/mod.rs", rawAbi = true, pointerWidth = "64", arch = "x86_64", os = "windows")
        val winRawH = winRaw.getValue("/proj/src/native/interop/cinterop/windows_wrapper.h")
        val winRawNative = winRaw.getValue("/proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/windows/WindowsNativeActuals.kt")
        assertContains(winRawH, "char* libcWindowsStrndup(const char* cs, uint64_t n);")
        assertContains(winRawH, "char* libcWindowsCustomNdup(const char* cs, uint64_t n);")
        assertContains(winRawH, "int32_t libcWindowsStrtol(const char* s, void* endp, int32_t base);")
        assertContains(winRawNative, "import io.github.kotlinmania.libc.windows.cinterop.libcWindowsStrndup")
        assertContains(
            winRawNative,
            "public actual fun strndup(cs: Long, n: size_t): Long =\n" +
                "    libcWindowsStrndup(cs.toCPointer<ByteVar>(), n)?.toLong() ?: 0L",
        )
        assertFalse(winRawNative.contains("n.toInt()"))
    }

    @Test
    fun ffiEmitsAndroidBionicAndAppleDarwinAwareWrappersAcrossCInteropJniAndNapi() {
        val libRs =
            """
            pub type c_int = i32;
            pub type c_char = i8;
            pub type c_void = u8;
            pub type size_t = usize;
            pub type time64_t = i64;
            pub type iconv_t = *mut c_void;
            """.trimIndent()
        val appleAndBionicModRs =
            """
            use crate::{c_char, c_int, c_void, iconv_t, size_t, time64_t};

            #[repr(u32)]
            pub enum qos_class_t {
                QOS_CLASS_DEFAULT = 0x15,
                QOS_CLASS_BACKGROUND = 0x09,
            }

            #[repr(C)]
            pub struct SmallRecord {
                // param comment
                /// Field doc.
                pub flags: c_int,
            }

            extern "C" {
                pub fn atexit(cb: Option<unsafe extern "C" fn()>) -> c_int;
                pub fn timegm64(tm: *const c_void) -> time64_t;
                pub fn pthread_barrier_init(barrier: *mut c_void, attr: *const c_void, count: u32) -> c_int;
                pub fn pthread_equal(t1: usize, t2: usize) -> c_int;
            }

            #[cfg_attr(not(feature = "rustc-dep-of-std"), link(name = "iconv"))]
            extern "C" {
                pub fn iconv_open(tocode: *const c_char, fromcode: *const c_char) -> iconv_t;
            }
            """.trimIndent()
        val crateSources =
            mapOf(
                "tmp/libc/src/lib.rs" to libRs,
                "tmp/libc/src/platform/mod.rs" to appleAndBionicModRs,
            )
        val flags =
            arrayOf(
                "translate",
                "--force",
                "--split-module-roots",
                "--final-structs",
                "--source-root",
                "tmp/libc/src",
                "--target-pointer-width",
                "64",
                "--target-arch",
                "aarch64",
                "--target-os",
                "macos",
                "-n",
                "io.github.kotlinmania.libc",
                "-o",
                "/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc",
                "tmp/libc/src/platform/mod.rs",
            )
        val out =
            runSplitCrateCommand(
                args = flags,
                sources = crateSources,
                dirRoot = "tmp/libc/src",
                metadataStripPrefix = "tmp/libc/src/",
            )

        val cSrc = out.getValue("/proj/src/native/interop/cinterop/platform_wrapper.c")
        val jniSrc = out.getValue("/proj/src/jvm/jvmMain/c/libc_platform_jni.c")
        val napiSrc = out.getValue("/proj/src/js/node-libc/src/libc_platform_bindings.cpp")

        // 1. atexit: <stdlib.h>, NULL/nullptr EINVAL guard, and #ifdef __ANDROID__ direct crtbegin atexit
        assertContains(cSrc, "#include <stdlib.h>")
        assertContains(cSrc, "if (cb == NULL) {\n        errno = EINVAL;\n        return -1;\n    }")
        assertContains(cSrc, "#ifdef __ANDROID__\n    return (int32_t)atexit((void (*)(void))(uintptr_t)cb);\n#else")
        assertContains(jniSrc, "#include <stdlib.h>")
        assertContains(jniSrc, "if ((void*)(uintptr_t)cb == NULL) {\n        errno = EINVAL;\n        return (jint)-1;\n    }")
        assertContains(jniSrc, "#ifdef __ANDROID__\n    return (jint)atexit((void (*)(void))(uintptr_t)cb);\n#else")
        assertContains(napiSrc, "if (cb == nullptr) {\n        errno = EINVAL;")
        assertContains(napiSrc, "std::atexit(reinterpret_cast<void (*)()>(reinterpret_cast<uintptr_t>(cb)))")

        // 2. 64-bit Android Bionic *64 -> base symbol fallback
        assertContains(
            cSrc,
            "#if defined(__ANDROID__) && defined(__LP64__)\n" +
                "        if (km_fn_ptr == NULL) {\n" +
                "            km_fn_ptr = (km_fn_t)dlsym(RTLD_DEFAULT, \"timegm\");\n" +
                "        }\n" +
                "#endif",
        )
        assertContains(jniSrc, "dlsym(RTLD_DEFAULT, \"timegm\")")
        assertContains(napiSrc, "dlsym(RTLD_DEFAULT, \"timegm\")")

        // 3. Per-extern-block #[cfg_attr(..., link(name = "iconv"))] Apple & desktop Linux dlopen fallback
        assertContains(cSrc, "dlopen(\"/usr/lib/libiconv.dylib\", RTLD_LAZY | RTLD_LOCAL)")
        assertContains(cSrc, "dlopen(\"libiconv.so\", RTLD_LAZY | RTLD_LOCAL)")
        assertContains(jniSrc, "dlopen(\"/usr/lib/libiconv.dylib\", RTLD_LAZY | RTLD_LOCAL)")
        assertContains(napiSrc, "dlopen(\"/usr/lib/libiconv.dylib\", RTLD_LAZY | RTLD_LOCAL)")
        // Scoped strictly to iconv_open (1 occurrence of /usr/lib/libiconv.dylib per file, not leaked to other fns)
        kotlin.test.assertEquals(1, cSrc.split("/usr/lib/libiconv.dylib").size - 1)
        kotlin.test.assertEquals(1, jniSrc.split("/usr/lib/libiconv.dylib").size - 1)
        kotlin.test.assertEquals(1, napiSrc.split("/usr/lib/libiconv.dylib").size - 1)

        // 4. POSIX pthread_* direct ENOSYS return + JNI/N-API errno = ENOSYS; (excluding pthread_equal)
        assertContains(cSrc, "errno = ENOSYS;\n    return ENOSYS;")
        assertContains(jniSrc, "errno = ENOSYS;\n        km_res = ENOSYS;")
        assertContains(napiSrc, "errno = ENOSYS;\n        km_res = ENOSYS;")
        val pthreadEqualC = cSrc.substringAfter("libcPlatformPthreadEqual(").substringBefore("\n}\n")
        assertContains(pthreadEqualC, "return -1;")

        // 5. ktlint-clean NativeActuals imports, multi-line enum constructor, and class-body vs param KDoc spacing
        val nativeActuals = out.getValue("/proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/platform/PlatformNativeActuals.kt")
        assertContains(nativeActuals, "import io.github.kotlinmania.libc.platform.cinterop.libcPlatformIconvOpen")
        assertContains(nativeActuals, "    MutPtr(libcPlatformIconvOpen(tocode.rawAddress.toCPointer<ByteVar>(), fromcode.rawAddress.toCPointer<ByteVar>())?.toLong() ?: 0L)")
        for ((path, content) in out) {
            if (path.endsWith(".kt")) {
                ZeroUnderscoreIdentifierValidator.requireZeroViolations(content, path)
            }
        }
        val platformTypes = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/platform/PlatformTypes.kt")
        assertContains(
            platformTypes,
            "public enum class QosClassT(\n" +
                "    public val value: UInt,\n" +
                ") {",
        )
        val platformStructs = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/platform/PlatformStructs.kt")
        assertContains(platformStructs, "    // param comment\n    /**\n     * Field doc.")
        val classBodySample =
            ensureBlankLineBeforeKDocAfterLineComment(
                "public class KinfoProc {\n" +
                    "    // This is normally \"struct proc\".\n" +
                    "    /**\n" +
                    "     * Address of proc.\n" +
                    "     */\n" +
                    "    public var kiPaddr: COpaquePointer? = null\n" +
                    "}",
            )
        assertContains(classBodySample, "    // This is normally \"struct proc\".\n\n    /**")
    }

    @Test
    fun windowsSysSplitModuleRootsOpaquePtrLiteralsIsizeHandlesAndStringShadowing() {
        val crateSources =
            linkedMapOf(
                "tmp/windows-sys/src/lib.rs" to
                    """
                    pub mod core;
                    pub mod Foundation;
                    pub mod Kernel;
                    pub mod FileSystem;
                    """.trimIndent(),
                "tmp/windows-sys/src/core/mod.rs" to
                    """
                    pub mod literals;
                    pub type PCSTR = *const u8;
                    pub type PWSTR = *mut u16;
                    pub type PCWSTR = *const u16;
                    #[repr(C)]
                    #[derive(Clone, Copy)]
                    pub struct GUID {
                        pub data4: [u8; 8],
                    }
                    impl GUID {
                        pub const fn from_u128(uuid: u128) -> Self {
                            Self { data4: (uuid as u64).to_be_bytes() }
                        }
                    }
                    """.trimIndent(),
                "tmp/windows-sys/src/core/literals.rs" to
                    """
                    #[macro_export]
                    macro_rules! s {
                        (${'$'}s:literal) => {
                            ::core::concat!(${'$'}s, '\0').as_ptr()
                        };
                    }
                    #[macro_export]
                    macro_rules! w {
                        (${'$'}s:literal) => {
                            [0u16].as_ptr()
                        };
                    }
                    """.trimIndent(),
                "tmp/windows-sys/src/Foundation/mod.rs" to
                    """
                    #[repr(C)]
                    #[derive(Clone, Copy, Default)]
                    pub struct SIZE {
                        pub cx: i32,
                        pub cy: i32,
                    }
                    """.trimIndent(),
                "tmp/windows-sys/src/Kernel/mod.rs" to
                    """
                    #[repr(C)]
                    #[derive(Clone, Copy)]
                    pub struct STRING {
                        pub Length: u16,
                        pub Buffer: super::core::PCSTR,
                    }
                    impl Default for STRING {
                        fn default() -> Self {
                            unsafe { core::mem::zeroed() }
                        }
                    }
                    pub type HPOWERNOTIFY = isize;
                    pub type DRIVER_DISPATCH = Option<unsafe extern "system" fn(self_: *mut core::ffi::c_void) -> i32>;
                    #[repr(C)]
                    #[derive(Clone, Copy)]
                    pub struct NT_TIB {
                        pub Self_: *mut core::ffi::c_void,
                        pub ClassName: super::core::PWSTR,
                        pub Io: u32,
                        pub MajorFunction: [DRIVER_DISPATCH; 28],
                    }
                    impl Default for NT_TIB {
                        fn default() -> Self {
                            unsafe { core::mem::zeroed() }
                        }
                    }
                    windows_link::link!("kernel32.dll" "system" fn RtlFirstEntrySList(head: *const core::ffi::c_void) -> *mut core::ffi::c_void);
                    windows_link::link!("user32.dll" "system" fn RegisterPowerNotify(name: super::core::PCWSTR) -> HPOWERNOTIFY);
                    """.trimIndent(),
                "tmp/windows-sys/src/FileSystem/mod.rs" to
                    """
                    #[repr(C)]
                    #[derive(Clone, Copy, Default)]
                    pub struct SIZE {
                        pub width: u32,
                    }
                    #[repr(C)]
                    #[derive(Clone, Copy)]
                    pub struct META_HDR {
                        pub devSize: super::Foundation::SIZE,
                        pub prgActions: *mut super::core::PWSTR,
                    }
                    impl Default for META_HDR {
                        fn default() -> Self {
                            unsafe { core::mem::zeroed() }
                        }
                    }
                    pub const REG_KEY_W: super::core::PCWSTR = crate::core::literals::w!("System\\CurrentControlSet");
                    pub const REG_KEY_A: super::core::PCSTR = crate::core::literals::s!("System\\CurrentControlSet");
                    windows_link::link!("ntoskrnl.exe" "system" fn FsRtlDissectDbcs(path: super::Kernel::STRING, rest: *mut super::Kernel::STRING));
                    """.trimIndent(),
            )

        val flags =
            arrayOf(
                "translate",
                "-r",
                "--force",
                "--final-structs",
                "--split-module-roots",
                "--source-root",
                "tmp/windows-sys/src",
                "--target-pointer-width",
                "64",
                "--target-arch",
                "x86_64",
                "--target-os",
                "windows",
                "-n",
                "io.github.kotlinmania.windowssys",
                "-o",
                "/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys",
                "tmp/windows-sys/src",
            )
        val out =
            runSplitCrateCommand(
                args = flags,
                sources = crateSources,
                dirRoot = "tmp/windows-sys/src",
                metadataStripPrefix = "tmp/windows-sys/src/",
                macroSources = listOf(crateSources.getValue("tmp/windows-sys/src/core/literals.rs")),
                flatFileModules = setOf("core/literals.rs"),
            )

        val coreTypes = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/core/CoreTypes.kt")
        assertContains(coreTypes, "public typealias Pwstr = MutPtr")
        assertContains(coreTypes, "public typealias Pcwstr = ConstPtr")

        val coreStructs = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/core/CoreStructs.kt")
        assertFalse(coreStructs.contains(".toULong()).toULong()"), "Expected no redundant .toULong() in:\n$coreStructs")
        assertFalse(coreStructs.contains(".toULong())).toULong()"), "Expected no redundant .toULong() in:\n$coreStructs")

        val literalsKt = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/core/literals/Literals.kt")
        assertContains(literalsKt, "import io.github.kotlinmania.rustlang.core.ptr.ConstPtr")
        assertContains(literalsKt, "public fun s(s: String): ConstPtr = kmInternCStr(s)")
        assertContains(literalsKt, "public fun w(s: String): ConstPtr = kmInternWideStr(s)")

        val kernelTypes = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/kernel/KernelTypes.kt")
        assertContains(kernelTypes, "public typealias Hpowernotify = Long")
        assertContains(kernelTypes, "public operator fun invoke(selfRef: MutPtr): Int")

        val kernelStructs = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/kernel/KernelStructs.kt")
        assertContains(kernelStructs, "selfRefCtorArg: MutPtr")
        assertContains(kernelStructs, "public var selfRef: MutPtr = selfRefCtorArg")
        assertContains(kernelStructs, "internal set")
        assertContains(kernelStructs, "clsNameCtorArg: Pwstr")
        assertContains(kernelStructs, "public var clsName: Pwstr = clsNameCtorArg")
        assertContains(kernelStructs, "public var ioVal: UInt")
        assertContains(kernelStructs, "public var majorFunction: List<DriverDispatchFn>")
        assertContains(kernelStructs, "selfRef: MutPtr = MutPtr(0L)")
        assertContains(kernelStructs, "clsName: Pwstr = MutPtr(0L)")

        val kernelJs = out.getValue("/proj/src/js/jsMain/kotlin/io/github/kotlinmania/windowssys/kernel/KernelJsActuals.kt")
        assertContains(kernelJs, "as kotlin.String).toLong()")
        assertFalse(kernelJs.contains("as Int)"), "Expected isize handle return to bridge as 64-bit Long in JS actuals:\n$kernelJs")

        val kernelWasm = out.getValue("/proj/src/js/wasm/wasmJsMain/kotlin/io/github/kotlinmania/windowssys/kernel/KernelWasmJsActuals.kt")
        assertContains(kernelWasm, ": kotlin.String")

        val fsStructs = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/filesystem/FileSystemStructs.kt")
        assertContains(fsStructs, "public var devSize: io.github.kotlinmania.windowssys.foundation.Size")
        assertContains(fsStructs, "prgActionsCtorArg: MutPtr")
        assertContains(fsStructs, "public var prgActions: MutPtr = prgActionsCtorArg")
        assertContains(fsStructs, "prgActions: MutPtr = MutPtr(0L)")
        assertFalse(fsStructs.contains("Pwstr?"), "Expected no '?' on non-nullable Pwstr alias:\n$fsStructs")

        assertTrue(
            out.containsKey("/proj/src/native/interop/cinterop/km_core_intrinsics_inline.h"),
            "Expected downstream N-API batch translation to emit km_core_intrinsics_inline.h",
        )
        assertTrue(
            out.containsKey("/proj/src/js/node-windowssys/src/windowssys_kmcore_intrinsics_bindings.cpp"),
            "Expected downstream N-API batch translation to emit windowssys_kmcore_intrinsics_bindings.cpp, got keys: ${out.keys}",
        )

        val fsFunctions = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/filesystem/FileSystemFunctions.kt")
        assertContains(fsFunctions, "path: io.github.kotlinmania.windowssys.kernel.String")
        val fsJs = out.getValue("/proj/src/js/jsMain/kotlin/io/github/kotlinmania/windowssys/filesystem/FilesystemJsActuals.kt")
        assertContains(fsJs, "path: io.github.kotlinmania.windowssys.kernel.String")
        assertFalse(fsJs.contains("fsRtlDissectDbcs(") && fsJs.contains("): Unit ="), "Expected void unsupported actual to use block body without ': Unit':\n$fsJs")
        val fsWasmWasi = out.getValue("/proj/src/js/wasm/wasmWasiMain/kotlin/io/github/kotlinmania/windowssys/filesystem/FilesystemWasmWasiActuals.kt")
        assertContains(fsWasmWasi, "public actual fun fsRtlDissectDbcs(path: io.github.kotlinmania.windowssys.kernel.String, rest: MutPtr) {")
    }
}

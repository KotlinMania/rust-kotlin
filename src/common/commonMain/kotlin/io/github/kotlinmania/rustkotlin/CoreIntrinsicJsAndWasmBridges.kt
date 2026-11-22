package io.github.kotlinmania.rustkotlin

internal object CoreIntrinsicJsAndWasmBridges {
    private const val LO_HI_PACK_HELPERS: String =
        """private fun lo32(v: Long): Int = v.toInt()
private fun hi32(v: Long): Int = (v ushr 32).toInt()
private fun uLo32(v: ULong): Int = v.toInt()
private fun uHi32(v: ULong): Int = (v shr 32).toInt()
private fun packI64(lo: Int, hi: Int): Long = (hi.toLong() shl 32) or (lo.toLong() and 0xFFFF_FFFFL)
private fun packU64(lo: Int, hi: Int): ULong =
    ((hi.toLong().toULong() and 0xFFFF_FFFFuL) shl 32) or (lo.toLong().toULong() and 0xFFFF_FFFFuL)"""

    public fun renderKmCoreNapiSharedCppHelpers(): String {
        val scratchGetters =
            (0..7).joinToString("\n") { idx ->
                """    coreObj.Set("kmOut$idx", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), g_kmCoreOutScratch[$idx]);
    }));"""
            }
        return """
static thread_local int32_t g_kmCoreOutScratch[8] = { 0, 0, 0, 0, 0, 0, 0, 0 };

static inline int32_t kmCoreNapiArgI32(const Napi::Value& v) {
    return v.As<Napi::Number>().Int32Value();
}

static inline uint32_t kmCoreNapiArgU32(const Napi::Value& v) {
    return static_cast<uint32_t>(v.As<Napi::Number>().Int32Value());
}

static inline uint64_t kmCoreNapiArgU64Pair(const Napi::CallbackInfo& info, size_t loIdx) {
    uint32_t lo = static_cast<uint32_t>(info[loIdx].As<Napi::Number>().Int32Value());
    uint32_t hi = static_cast<uint32_t>(info[loIdx + 1].As<Napi::Number>().Int32Value());
    return (static_cast<uint64_t>(hi) << 32) | static_cast<uint64_t>(lo);
}

static inline int64_t kmCoreNapiArgI64Pair(const Napi::CallbackInfo& info, size_t loIdx) {
    return static_cast<int64_t>(kmCoreNapiArgU64Pair(info, loIdx));
}

static inline void kmCoreStoreU64Scratch(int scratchIndex, uint64_t value) {
    g_kmCoreOutScratch[scratchIndex] = static_cast<int32_t>(static_cast<uint32_t>(value & 0xFFFFFFFFULL));
    g_kmCoreOutScratch[scratchIndex + 1] = static_cast<int32_t>(static_cast<uint32_t>(value >> 32));
}

static inline Napi::Value kmCoreReturnU64LoHi(const Napi::CallbackInfo& info, uint64_t value) {
    kmCoreStoreU64Scratch(0, value);
    return Napi::Number::New(info.Env(), g_kmCoreOutScratch[0]);
}

static inline Napi::Value kmCoreReturnI64LoHi(const Napi::CallbackInfo& info, int64_t value) {
    return kmCoreReturnU64LoHi(info, static_cast<uint64_t>(value));
}

static inline void kmCoreCpuSpinHint(void) {
#if defined(__aarch64__)
    __asm__ __volatile__("yield" ::: "memory");
#elif defined(__x86_64__) || defined(_M_X64)
#if defined(_MSC_VER)
    _mm_pause();
#else
    __asm__ __volatile__("pause" ::: "memory");
#endif
#endif
}

static inline uint64_t kmCoreCpuBlackBoxU64(uint64_t v) {
#if defined(__clang__) || defined(__GNUC__)
    __asm__ __volatile__("" : "+r"(v) :: "memory");
    return v;
#else
    volatile uint64_t sink = v;
    return sink;
#endif
}

static inline uint64_t kmCoreCpuUmulhU64(uint64_t a, uint64_t b) {
#if defined(__SIZEOF_INT128__)
    return static_cast<uint64_t>((static_cast<unsigned __int128>(a) * static_cast<unsigned __int128>(b)) >> 64);
#elif defined(_MSC_VER) && defined(_M_X64)
    uint64_t hi = 0;
    (void)_umul128(a, b, &hi);
    return hi;
#else
    uint64_t aLo = a & 0xFFFFFFFFULL, aHi = a >> 32;
    uint64_t bLo = b & 0xFFFFFFFFULL, bHi = b >> 32;
    uint64_t p0 = aLo * bLo, p1 = aLo * bHi, p2 = aHi * bLo, p3 = aHi * bHi;
    uint64_t mid = (p0 >> 32) + (p1 & 0xFFFFFFFFULL) + (p2 & 0xFFFFFFFFULL);
    return p3 + (p1 >> 32) + (p2 >> 32) + (mid >> 32);
#endif
}

static inline int64_t kmCoreCpuSmulhI64(int64_t a, int64_t b) {
#if defined(__SIZEOF_INT128__)
    return static_cast<int64_t>((static_cast<__int128>(a) * static_cast<__int128>(b)) >> 64);
#elif defined(_MSC_VER) && defined(_M_X64)
    int64_t hi = 0;
    (void)_mul128(a, b, &hi);
    return hi;
#else
    uint64_t hi = kmCoreCpuUmulhU64(static_cast<uint64_t>(a), static_cast<uint64_t>(b));
    if (a < 0) hi -= static_cast<uint64_t>(b);
    if (b < 0) hi -= static_cast<uint64_t>(a);
    return static_cast<int64_t>(hi);
#endif
}

static inline uint8_t kmCoreCpuAtomicCxchgU128(
    int64_t addr, uint64_t expLo, uint64_t expHi, uint64_t desLo, uint64_t desHi, uint64_t* outLo, uint64_t* outHi
) {
#if defined(_MSC_VER) && defined(_M_X64)
    int64_t cmp[2] = { static_cast<int64_t>(expLo), static_cast<int64_t>(expHi) };
    unsigned char ok = _InterlockedCompareExchange128(
        reinterpret_cast<volatile int64_t*>(static_cast<uintptr_t>(addr)),
        static_cast<int64_t>(desHi),
        static_cast<int64_t>(desLo),
        cmp
    );
    *outLo = static_cast<uint64_t>(cmp[0]);
    *outHi = static_cast<uint64_t>(cmp[1]);
    return ok ? 1 : 0;
#elif defined(__aarch64__) && (defined(__clang__) || defined(__GNUC__))
    uint64_t curLo = 0, curHi = 0;
    uint32_t status = 0;
    void* ptr = reinterpret_cast<void*>(static_cast<uintptr_t>(addr));
    for (;;) {
        __asm__ __volatile__("ldaxp %0, %1, [%2]" : "=&r"(curLo), "=&r"(curHi) : "r"(ptr) : "memory");
        if (curLo != expLo || curHi != expHi) {
            __asm__ __volatile__("clrex" ::: "memory");
            *outLo = curLo;
            *outHi = curHi;
            return 0;
        }
        __asm__ __volatile__("stlxp %w0, %1, %2, [%3]" : "=&r"(status) : "r"(desLo), "r"(desHi), "r"(ptr) : "memory");
        if (status == 0) {
            *outLo = expLo;
            *outHi = expHi;
            return 1;
        }
    }
#elif defined(__x86_64__) && (defined(__clang__) || defined(__GNUC__))
    uint8_t ok = 0;
    uint64_t prevLo = expLo;
    uint64_t prevHi = expHi;
    __uint128_t* memPtr = reinterpret_cast<__uint128_t*>(static_cast<uintptr_t>(addr));
    __asm__ __volatile__(
        "lock cmpxchg16b %1\n\t"
        "sete %0"
        : "=q"(ok), "+m"(*memPtr), "+a"(prevLo), "+d"(prevHi)
        : "b"(desLo), "c"(desHi)
        : "cc", "memory"
    );
    *outLo = prevLo;
    *outHi = prevHi;
    return ok;
#else
    uint64_t* p = reinterpret_cast<uint64_t*>(static_cast<uintptr_t>(addr));
    *outLo = p[0];
    *outHi = p[1];
    if (p[0] == expLo && p[1] == expHi) {
        p[0] = desLo;
        p[1] = desHi;
        return 1;
    }
    return 0;
#endif
}

static void kmRegisterCoreNapiV2Object(Napi::Env env) {
    Napi::Object global = env.Global();
    if (global.Has("kmCoreNapiV2") && global.Get("kmCoreNapiV2").IsObject()) {
        return;
    }
    Napi::Object coreObj = Napi::Object::New(env);
$scratchGetters

${KmNapiCppBridgeEmitter.renderDeclarations()}
${CoreIntrinsicMathAndBitSpecs.renderNapiRegistrations()}
    global.Set("kmCoreNapiV2", coreObj);
}
""".trimStart()
    }

    public fun renderKmCoreNapiCpp(stemSnake: String = "km_core"): String =
        """
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <napi.h>
#include <cstdint>
#include <cstddef>
#include <cstring>
#if defined(_MSC_VER)
#include <intrin.h>
#elif defined(__x86_64__)
#include <immintrin.h>
#endif
#if __has_include("km_core_intrinsics_inline.h")
#include "km_core_intrinsics_inline.h"
#elif __has_include("../../../native/interop/cinterop/km_core_intrinsics_inline.h")
#include "../../../native/interop/cinterop/km_core_intrinsics_inline.h"
#endif

${renderNapiRegistrarHeader()}

namespace {

${renderKmCoreNapiSharedCppHelpers()}

static void register_${stemSnake}_napi(Napi::Env env, Napi::Object exports) {
    kmRegisterCoreNapiV2Object(env);
    Napi::Value coreVal = env.Global().Get("kmCoreNapiV2");
    if (coreVal.IsObject()) {
        Napi::Object coreObj = coreVal.As<Napi::Object>();
        Napi::Array keys = coreObj.GetPropertyNames();
        for (uint32_t i = 0; i < keys.Length(); i++) {
            Napi::Value k = keys.Get(i);
            exports.Set(k, coreObj.Get(k));
        }
    }
}

${renderNapiRegistrarFooter("register_${stemSnake}_napi")}
""".trimStart()

    private fun renderNodeKmCoreLoaderJsBody(ffiModName: String): String =
        "if (typeof globalThis !== 'undefined' && typeof globalThis.kmCoreNapiV2 !== 'undefined') return globalThis.kmCoreNapiV2; " +
            "if (typeof process === 'undefined' || !process.versions || !process.versions.node) { " +
            "if (typeof globalThis !== 'undefined') globalThis.kmCoreNapiV2 = null; return null; } " +
            "var mod = process.getBuiltinModule ? process.getBuiltinModule('module') : (process.mainModule && process.mainModule.constructor); " +
            "var req = (mod && mod.createRequire) ? mod.createRequire((process.cwd ? process.cwd() : '.') + '/package.json') : (process.mainModule ? process.mainModule.require : null); " +
            "if (!req) { if (typeof globalThis !== 'undefined') globalThis.kmCoreNapiV2 = null; return null; } " +
            "var fs = req('fs'); " +
            "var path = req('path'); " +
            "var cwd = process.cwd ? process.cwd() : '.'; " +
            "var dir = cwd; " +
            "for (var d = 0; d < 8; d++) { " +
            "var directCands = [ " +
            "path.resolve(dir, 'node_modules/@kotlinmania/$ffiModName-native-bindings/index.js'), " +
            "path.resolve(dir, 'build/js/node_modules/@kotlinmania/$ffiModName-native-bindings/index.js'), " +
            "path.resolve(dir, 'build/wasm/node_modules/@kotlinmania/$ffiModName-native-bindings/index.js'), " +
            "path.resolve(dir, 'src/js/node-$ffiModName/dist/build/Release/${ffiModName}_native.node'), " +
            "path.resolve(dir, 'src/js/node-$ffiModName/build/Release/${ffiModName}_native.node') " +
            "]; " +
            "for (var i = 0; i < directCands.length; i++) { " +
            "if (fs.existsSync(directCands[i])) { req(directCands[i]); if (globalThis.kmCoreNapiV2) return globalThis.kmCoreNapiV2; } " +
            "} " +
            "var scopeDirs = [ " +
            "path.resolve(dir, 'node_modules/@kotlinmania'), " +
            "path.resolve(dir, 'build/js/node_modules/@kotlinmania'), " +
            "path.resolve(dir, 'build/wasm/node_modules/@kotlinmania') " +
            "]; " +
            "for (var s = 0; s < scopeDirs.length; s++) { " +
            "if (fs.existsSync(scopeDirs[s])) { " +
            "var entries = fs.readdirSync(scopeDirs[s]); " +
            "for (var e = 0; e < entries.length; e++) { " +
            "if (entries[e].endsWith('-native-bindings')) { " +
            "var pkgEntry = path.resolve(scopeDirs[s], entries[e], 'index.js'); " +
            "if (fs.existsSync(pkgEntry)) { req(pkgEntry); if (globalThis.kmCoreNapiV2) return globalThis.kmCoreNapiV2; } " +
            "} " +
            "} " +
            "} " +
            "} " +
            "var parent = path.dirname(dir); " +
            "if (parent === dir) break; " +
            "dir = parent; " +
            "} " +
            "if (typeof globalThis !== 'undefined') globalThis.kmCoreNapiV2 = null; " +
            "return null;"

    public fun renderKmCoreJsActualsKotlin(
        coreNamespace: String,
        ffiModName: String = "kmcore",
    ): String {
        val loaderBody = renderNodeKmCoreLoaderJsBody(ffiModName)
        val emitter = KmJsOrWasmBridgeEmitter(isWasmJs = false)
        return """
@file:OptIn(ExperimentalUnsignedTypes::class)

package $coreNamespace.intrinsics

private val kmCoreNapiAddon: dynamic =
    js("(function() { $loaderBody })()")

$LO_HI_PACK_HELPERS

${emitter.renderDeclarations()}

${CoreIntrinsicMathAndBitSpecs.renderJsActuals().trimEnd()}

@JsName("FinalizationRegistry")
private external class KmJsFinalizationRegistry(callback: (Int) -> Unit) {
    fun register(target: Any, heldValue: Int, unregisterToken: Any)
    fun unregister(unregisterToken: Any): Boolean
}

internal class KmJsCleanToken

private object KmJsCleanerRegistry {
    private var nextTokenId: Int = 1
    private val pendingCleanups = hashMapOf<Int, () -> Unit>()
    private val registry =
        KmJsFinalizationRegistry { tokenId ->
            val action = pendingCleanups.remove(tokenId)
            if (action != null) {
                action()
            }
        }

    fun register(owner: Any, cleanup: () -> Unit): KmResourceCleaner {
        val id = nextTokenId++
        val token = KmJsCleanToken()
        pendingCleanups[id] = cleanup
        registry.register(owner, id, token)
        return KmResourceCleaner().apply { attach(id, token) }
    }

    fun disarm(id: Int, token: KmJsCleanToken) {
        pendingCleanups.remove(id)
        registry.unregister(token)
    }

    fun clean(id: Int, token: KmJsCleanToken) {
        registry.unregister(token)
        val action = pendingCleanups.remove(id)
        if (action != null) {
            action()
        }
    }
}

public actual class KmResourceCleaner internal actual constructor() : KmForgettableResource {
    private var tokenId: Int = 0
    private var token: KmJsCleanToken? = null

    internal fun attach(id: Int, cleanToken: KmJsCleanToken) {
        tokenId = id
        token = cleanToken
    }

    public actual fun disarm() {
        val activeToken = token ?: return
        val activeId = tokenId
        token = null
        tokenId = 0
        KmJsCleanerRegistry.disarm(activeId, activeToken)
    }

    public actual fun cleanNow() {
        val activeToken = token ?: return
        val activeId = tokenId
        token = null
        tokenId = 0
        KmJsCleanerRegistry.clean(activeId, activeToken)
    }

    public actual override fun disarmCleaner(): Unit = disarm()

    public actual override fun close(): Unit = cleanNow()
}

public actual fun kmRegisterResourceCleaner(owner: Any, cleanup: () -> Unit): KmResourceCleaner =
    KmJsCleanerRegistry.register(owner, cleanup)
""".trimStart()
    }

    public fun renderKmCoreWasmJsActualsKotlin(
        coreNamespace: String,
        ffiModName: String = "kmcore",
    ): String {
        val loaderBody = renderNodeKmCoreLoaderJsBody(ffiModName)
        val emitter = KmJsOrWasmBridgeEmitter(isWasmJs = true)
        return """
@file:OptIn(ExperimentalUnsignedTypes::class, ExperimentalWasmJsInterop::class)

package $coreNamespace.intrinsics

@JsFun("() => { var valObj = (function() { $loaderBody })(); return valObj !== null && typeof valObj !== 'undefined'; }")
private external fun initKmCoreNapiAddon(): Boolean

private val kmCoreNapiAvailable: Boolean = initKmCoreNapiAddon()

$LO_HI_PACK_HELPERS

${emitter.renderWasmJsFunDeclarations()}

${CoreIntrinsicMathAndBitSpecs.renderWasmJsFunDeclarations().trimEnd()}

${emitter.renderDeclarations()}

${CoreIntrinsicMathAndBitSpecs.renderWasmJsActuals().trimEnd()}

internal class KmWasmCleanToken

@JsFun("(cb) => new FinalizationRegistry(cb)")
private external fun kmWasmCreateFinalizationRegistry(cb: (Int) -> Unit): JsAny

@JsFun("(reg, target, heldValue, token) => { reg.register(target, heldValue, token); }")
private external fun kmWasmRegisterCleaner(reg: JsAny, target: JsAny, heldValue: Int, token: JsAny)

@JsFun("(reg, token) => { reg.unregister(token); }")
private external fun kmWasmUnregisterCleaner(reg: JsAny, token: JsAny)

private object KmWasmCleanerRegistry {
    private var nextTokenId: Int = 1
    private val pendingCleanups = hashMapOf<Int, () -> Unit>()
    private val registry: JsAny =
        kmWasmCreateFinalizationRegistry { tokenId ->
            val action = pendingCleanups.remove(tokenId)
            if (action != null) {
                action()
            }
        }

    fun register(owner: Any, cleanup: () -> Unit): KmResourceCleaner {
        val id = nextTokenId++
        val token = KmWasmCleanToken().toJsReference()
        pendingCleanups[id] = cleanup
        kmWasmRegisterCleaner(registry, owner.toJsReference(), id, token)
        return KmResourceCleaner().apply { attach(id, token) }
    }

    fun disarm(id: Int, token: JsAny) {
        pendingCleanups.remove(id)
        kmWasmUnregisterCleaner(registry, token)
    }

    fun clean(id: Int, token: JsAny) {
        kmWasmUnregisterCleaner(registry, token)
        val action = pendingCleanups.remove(id)
        if (action != null) {
            action()
        }
    }
}

public actual class KmResourceCleaner internal actual constructor() : KmForgettableResource {
    private var tokenId: Int = 0
    private var token: JsAny? = null

    internal fun attach(id: Int, cleanToken: JsAny) {
        tokenId = id
        token = cleanToken
    }

    public actual fun disarm() {
        val activeToken = token ?: return
        val activeId = tokenId
        token = null
        tokenId = 0
        KmWasmCleanerRegistry.disarm(activeId, activeToken)
    }

    public actual fun cleanNow() {
        val activeToken = token ?: return
        val activeId = tokenId
        token = null
        tokenId = 0
        KmWasmCleanerRegistry.clean(activeId, activeToken)
    }

    public actual override fun disarmCleaner(): Unit = disarm()

    public actual override fun close(): Unit = cleanNow()
}

public actual fun kmRegisterResourceCleaner(owner: Any, cleanup: () -> Unit): KmResourceCleaner =
    KmWasmCleanerRegistry.register(owner, cleanup)
""".trimStart()
    }

    public fun renderKmCoreWasmWasiActualsKotlin(coreNamespace: String): String =
        """
package $coreNamespace.intrinsics

${KmWasmWasiBridgeEmitter.renderDeclarations()}

${CoreIntrinsicMathAndBitSpecs.renderWasmWasiActuals().trimEnd()}

internal class KmWasmWasiCleanState(
    private var cleanup: (() -> Unit)?,
) {
    private var state: Int = 0

    fun disarm() {
        if (state == 0) {
            state = 1
            cleanup = null
        }
    }

    fun clean() {
        if (state == 0) {
            state = 2
            val action = cleanup
            cleanup = null
            action?.invoke()
        }
    }
}

public actual class KmResourceCleaner internal actual constructor() : KmForgettableResource {
    private var cleanState: KmWasmWasiCleanState? = null

    internal fun attach(state: KmWasmWasiCleanState) {
        cleanState = state
    }

    public actual fun disarm() {
        val target = cleanState
        cleanState = null
        target?.disarm()
    }

    public actual fun cleanNow() {
        val target = cleanState
        cleanState = null
        target?.clean()
    }

    public actual override fun disarmCleaner(): Unit = disarm()

    public actual override fun close(): Unit = cleanNow()
}

public actual fun kmRegisterResourceCleaner(owner: Any, cleanup: () -> Unit): KmResourceCleaner {
    val state = KmWasmWasiCleanState(cleanup)
    return KmResourceCleaner().apply { attach(state) }
}
""".trimStart()
}

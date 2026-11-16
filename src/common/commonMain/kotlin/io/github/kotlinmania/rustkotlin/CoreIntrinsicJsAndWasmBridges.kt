package io.github.kotlinmania.rustkotlin

internal object CoreIntrinsicJsAndWasmBridges {
    public fun renderKmCoreNapiSharedCppHelpers(): String =
        """
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
    coreObj.Set("kmOut0", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), g_kmCoreOutScratch[0]);
    }));
    coreObj.Set("kmOut1", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), g_kmCoreOutScratch[1]);
    }));
    coreObj.Set("kmOut2", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), g_kmCoreOutScratch[2]);
    }));
    coreObj.Set("kmOut3", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), g_kmCoreOutScratch[3]);
    }));
    coreObj.Set("kmOut4", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), g_kmCoreOutScratch[4]);
    }));
    coreObj.Set("kmOut5", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), g_kmCoreOutScratch[5]);
    }));
    coreObj.Set("kmOut6", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), g_kmCoreOutScratch[6]);
    }));
    coreObj.Set("kmOut7", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), g_kmCoreOutScratch[7]);
    }));

    coreObj.Set("kmLoadU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmLoadU8(kmCoreNapiArgI64Pair(info, 0))));
    }));
    coreObj.Set("kmLoadU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmLoadU16(kmCoreNapiArgI64Pair(info, 0))));
    }));
    coreObj.Set("kmLoadU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmLoadU32(kmCoreNapiArgI64Pair(info, 0))));
    }));
    coreObj.Set("kmLoadU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmLoadU64(kmCoreNapiArgI64Pair(info, 0)));
    }));
    coreObj.Set("kmLoadF32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<double>(kmLoadF32(kmCoreNapiArgI64Pair(info, 0))));
    }));
    coreObj.Set("kmLoadF64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), kmLoadF64(kmCoreNapiArgI64Pair(info, 0)));
    }));

    coreObj.Set("kmStoreU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmStoreU8(kmCoreNapiArgI64Pair(info, 0), static_cast<uint8_t>(kmCoreNapiArgU32(info[2])));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmStoreU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmStoreU16(kmCoreNapiArgI64Pair(info, 0), static_cast<uint16_t>(kmCoreNapiArgU32(info[2])));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmStoreU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmStoreU32(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU32(info[2]));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmStoreU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmStoreU64(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmStoreF32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmStoreF32(kmCoreNapiArgI64Pair(info, 0), static_cast<float>(info[2].As<Napi::Number>().DoubleValue()));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmStoreF64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmStoreF64(kmCoreNapiArgI64Pair(info, 0), info[2].As<Napi::Number>().DoubleValue());
        return info.Env().Undefined();
    }));

    coreObj.Set("kmVolatileLoadU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmVolatileLoadU8(kmCoreNapiArgI64Pair(info, 0))));
    }));
    coreObj.Set("kmVolatileLoadU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmVolatileLoadU16(kmCoreNapiArgI64Pair(info, 0))));
    }));
    coreObj.Set("kmVolatileLoadU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmVolatileLoadU32(kmCoreNapiArgI64Pair(info, 0))));
    }));
    coreObj.Set("kmVolatileLoadU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmVolatileLoadU64(kmCoreNapiArgI64Pair(info, 0)));
    }));

    coreObj.Set("kmVolatileStoreU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmVolatileStoreU8(kmCoreNapiArgI64Pair(info, 0), static_cast<uint8_t>(kmCoreNapiArgU32(info[2])));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmVolatileStoreU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmVolatileStoreU16(kmCoreNapiArgI64Pair(info, 0), static_cast<uint16_t>(kmCoreNapiArgU32(info[2])));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmVolatileStoreU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmVolatileStoreU32(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU32(info[2]));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmVolatileStoreU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmVolatileStoreU64(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2));
        return info.Env().Undefined();
    }));

    coreObj.Set("kmCopyNonoverlapping", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmCopyNonoverlapping(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI64Pair(info, 2), kmCoreNapiArgI64Pair(info, 4));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmCopy", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmCopy(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI64Pair(info, 2), kmCoreNapiArgI64Pair(info, 4));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmWriteBytes", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmWriteBytes(kmCoreNapiArgI64Pair(info, 0), static_cast<uint8_t>(kmCoreNapiArgU32(info[2])), kmCoreNapiArgI64Pair(info, 3));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmCompareBytes", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            kmCompareBytes(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI64Pair(info, 2), kmCoreNapiArgI64Pair(info, 4))
        );
    }));

    coreObj.Set("kmAlloc", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnI64LoHi(info, kmAlloc(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI64Pair(info, 2)));
    }));
    coreObj.Set("kmAllocZeroed", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnI64LoHi(info, kmAllocZeroed(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI64Pair(info, 2)));
    }));
    coreObj.Set("kmRealloc", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnI64LoHi(
            info,
            kmRealloc(
                kmCoreNapiArgI64Pair(info, 0),
                kmCoreNapiArgI64Pair(info, 2),
                kmCoreNapiArgI64Pair(info, 4),
                kmCoreNapiArgI64Pair(info, 6)
            )
        );
    }));
    coreObj.Set("kmDealloc", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmDealloc(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI64Pair(info, 2), kmCoreNapiArgI64Pair(info, 4));
        return info.Env().Undefined();
    }));

    coreObj.Set("kmAtomicFence", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmAtomicFence(kmCoreNapiArgI32(info[0]));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmAtomicSignalFence", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmAtomicSignalFence(kmCoreNapiArgI32(info[0]));
        return info.Env().Undefined();
    }));

    coreObj.Set("kmAtomicLoadU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmAtomicLoadU8(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI32(info[2]))));
    }));
    coreObj.Set("kmAtomicLoadU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmAtomicLoadU16(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI32(info[2]))));
    }));
    coreObj.Set("kmAtomicLoadU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmAtomicLoadU32(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI32(info[2]))));
    }));
    coreObj.Set("kmAtomicLoadU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmAtomicLoadU64(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI32(info[2])));
    }));

    coreObj.Set("kmAtomicStoreU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmAtomicStoreU8(kmCoreNapiArgI64Pair(info, 0), static_cast<uint8_t>(kmCoreNapiArgU32(info[2])), kmCoreNapiArgI32(info[3]));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmAtomicStoreU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmAtomicStoreU16(kmCoreNapiArgI64Pair(info, 0), static_cast<uint16_t>(kmCoreNapiArgU32(info[2])), kmCoreNapiArgI32(info[3]));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmAtomicStoreU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmAtomicStoreU32(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU32(info[2]), kmCoreNapiArgI32(info[3]));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmAtomicStoreU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmAtomicStoreU64(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2), kmCoreNapiArgI32(info[4]));
        return info.Env().Undefined();
    }));

    coreObj.Set("kmAtomicXchgU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<int32_t>(kmAtomicXchgU8(kmCoreNapiArgI64Pair(info, 0), static_cast<uint8_t>(kmCoreNapiArgU32(info[2])), kmCoreNapiArgI32(info[3])))
        );
    }));
    coreObj.Set("kmAtomicXchgU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<int32_t>(kmAtomicXchgU16(kmCoreNapiArgI64Pair(info, 0), static_cast<uint16_t>(kmCoreNapiArgU32(info[2])), kmCoreNapiArgI32(info[3])))
        );
    }));
    coreObj.Set("kmAtomicXchgU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<int32_t>(kmAtomicXchgU32(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU32(info[2]), kmCoreNapiArgI32(info[3])))
        );
    }));
    coreObj.Set("kmAtomicXchgU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(
            info,
            kmAtomicXchgU64(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2), kmCoreNapiArgI32(info[4]))
        );
    }));

    coreObj.Set("kmAtomicCxchgU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint8_t ok = 0;
        uint8_t prev = kmAtomicCxchgU8(
            kmCoreNapiArgI64Pair(info, 0),
            static_cast<uint8_t>(kmCoreNapiArgU32(info[2])),
            static_cast<uint8_t>(kmCoreNapiArgU32(info[3])),
            kmCoreNapiArgI32(info[4]),
            kmCoreNapiArgI32(info[5]),
            &ok
        );
        return Napi::Number::New(info.Env(), static_cast<int32_t>((static_cast<uint32_t>(ok) << 8) | static_cast<uint32_t>(prev)));
    }));
    coreObj.Set("kmAtomicCxchgU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint8_t ok = 0;
        uint16_t prev = kmAtomicCxchgU16(
            kmCoreNapiArgI64Pair(info, 0),
            static_cast<uint16_t>(kmCoreNapiArgU32(info[2])),
            static_cast<uint16_t>(kmCoreNapiArgU32(info[3])),
            kmCoreNapiArgI32(info[4]),
            kmCoreNapiArgI32(info[5]),
            &ok
        );
        return Napi::Number::New(info.Env(), static_cast<int32_t>((static_cast<uint32_t>(ok) << 16) | static_cast<uint32_t>(prev)));
    }));
    coreObj.Set("kmAtomicCxchgU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint8_t ok = 0;
        uint32_t prev = kmAtomicCxchgU32(
            kmCoreNapiArgI64Pair(info, 0),
            kmCoreNapiArgU32(info[2]),
            kmCoreNapiArgU32(info[3]),
            kmCoreNapiArgI32(info[4]),
            kmCoreNapiArgI32(info[5]),
            &ok
        );
        g_kmCoreOutScratch[0] = static_cast<int32_t>(prev);
        return Napi::Number::New(info.Env(), static_cast<int32_t>(ok));
    }));
    coreObj.Set("kmAtomicCxchgU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint8_t ok = 0;
        uint64_t prev = kmAtomicCxchgU64(
            kmCoreNapiArgI64Pair(info, 0),
            kmCoreNapiArgU64Pair(info, 2),
            kmCoreNapiArgU64Pair(info, 4),
            kmCoreNapiArgI32(info[6]),
            kmCoreNapiArgI32(info[7]),
            &ok
        );
        kmCoreStoreU64Scratch(0, prev);
        return Napi::Number::New(info.Env(), static_cast<int32_t>(ok));
    }));

    coreObj.Set("kmAtomicRmwU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<int32_t>(kmAtomicRmwU8(kmCoreNapiArgI64Pair(info, 0), static_cast<uint8_t>(kmCoreNapiArgU32(info[2])), kmCoreNapiArgI32(info[3]), kmCoreNapiArgI32(info[4])))
        );
    }));
    coreObj.Set("kmAtomicRmwU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<int32_t>(kmAtomicRmwU16(kmCoreNapiArgI64Pair(info, 0), static_cast<uint16_t>(kmCoreNapiArgU32(info[2])), kmCoreNapiArgI32(info[3]), kmCoreNapiArgI32(info[4])))
        );
    }));
    coreObj.Set("kmAtomicRmwU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<int32_t>(kmAtomicRmwU32(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU32(info[2]), kmCoreNapiArgI32(info[3]), kmCoreNapiArgI32(info[4])))
        );
    }));
    coreObj.Set("kmAtomicRmwU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(
            info,
            kmAtomicRmwU64(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2), kmCoreNapiArgI32(info[4]), kmCoreNapiArgI32(info[5]))
        );
    }));

    coreObj.Set("kmCtpopU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmCtpopU64(kmCoreNapiArgU64Pair(info, 0))));
    }));
    coreObj.Set("kmCtlzU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmCtlzU64(kmCoreNapiArgU64Pair(info, 0))));
    }));
    coreObj.Set("kmCttzU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmCttzU64(kmCoreNapiArgU64Pair(info, 0))));
    }));
    coreObj.Set("kmBswapU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmBswapU16(static_cast<uint16_t>(kmCoreNapiArgU32(info[0])))));
    }));
    coreObj.Set("kmBswapU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmBswapU32(kmCoreNapiArgU32(info[0]))));
    }));
    coreObj.Set("kmBswapU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmBswapU64(kmCoreNapiArgU64Pair(info, 0)));
    }));
    coreObj.Set("kmBitreverseU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmBitreverseU64(kmCoreNapiArgU64Pair(info, 0)));
    }));

    coreObj.Set("kmFmaf32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<double>(kmFmaf32(
                static_cast<float>(info[0].As<Napi::Number>().DoubleValue()),
                static_cast<float>(info[1].As<Napi::Number>().DoubleValue()),
                static_cast<float>(info[2].As<Napi::Number>().DoubleValue())
            ))
        );
    }));
    coreObj.Set("kmFmaf64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            kmFmaf64(
                info[0].As<Napi::Number>().DoubleValue(),
                info[1].As<Napi::Number>().DoubleValue(),
                info[2].As<Napi::Number>().DoubleValue()
            )
        );
    }));
    coreObj.Set("kmSqrtf32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<double>(kmSqrtf32(static_cast<float>(info[0].As<Napi::Number>().DoubleValue()))));
    }));
    coreObj.Set("kmSqrtf64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), kmSqrtf64(info[0].As<Napi::Number>().DoubleValue()));
    }));
    coreObj.Set("kmAbort", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        (void)info;
        kmAbort();
        return info.Env().Undefined();
    }));
    coreObj.Set("kmSpinLoopHint", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmCoreCpuSpinHint();
        return info.Env().Undefined();
    }));
    coreObj.Set("kmBlackBoxU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmCoreCpuBlackBoxU64(kmCoreNapiArgU64Pair(info, 0)));
    }));
    coreObj.Set("kmUmulhU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmCoreCpuUmulhU64(kmCoreNapiArgU64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2)));
    }));
    coreObj.Set("kmSmulhI64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnI64LoHi(info, kmCoreCpuSmulhI64(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI64Pair(info, 2)));
    }));
    coreObj.Set("kmAtomicCxchgU128Pair", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint64_t outLo = 0, outHi = 0;
        uint8_t ok = kmCoreCpuAtomicCxchgU128(
            kmCoreNapiArgI64Pair(info, 0),
            kmCoreNapiArgU64Pair(info, 2),
            kmCoreNapiArgU64Pair(info, 4),
            kmCoreNapiArgU64Pair(info, 6),
            kmCoreNapiArgU64Pair(info, 8),
            &outLo,
            &outHi
        );
        kmCoreStoreU64Scratch(0, outLo);
        kmCoreStoreU64Scratch(2, outHi);
        return Napi::Number::New(info.Env(), static_cast<int32_t>(ok));
    }));
    coreObj.Set("kmSimdFmaF32x4", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmSimdFmaF32x4(
            kmCoreNapiArgI64Pair(info, 0),
            kmCoreNapiArgI64Pair(info, 2),
            kmCoreNapiArgI64Pair(info, 4),
            kmCoreNapiArgI64Pair(info, 6)
        );
        return info.Env().Undefined();
    }));
    coreObj.Set("kmSimdDotF32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<double>(kmSimdDotF32(
                kmCoreNapiArgI64Pair(info, 0),
                kmCoreNapiArgI64Pair(info, 2),
                kmCoreNapiArgI64Pair(info, 4)
            ))
        );
    }));
${CoreIntrinsicMathAndBitSpecs.renderNapiRegistrations()}
    global.Set("kmCoreNapiV2", coreObj);
}
""".trimStart()

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

struct KmNapiRegistrarNode {
    void (*register_fn)(Napi::Env, Napi::Object);
    KmNapiRegistrarNode* next;
};
#if defined(__GNUC__) || defined(__clang__)
__attribute__((visibility("hidden")))
#endif
extern KmNapiRegistrarNode* km_napi_registrar_head;

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

static KmNapiRegistrarNode registrar_node = { &register_${stemSnake}_napi, nullptr };
struct KmNapiAutoRegister {
    KmNapiAutoRegister() {
        registrar_node.next = km_napi_registrar_head;
        km_napi_registrar_head = &registrar_node;
    }
};
static KmNapiAutoRegister auto_register_instance;

} // namespace

#ifdef KM_NAPI_STANDALONE_MODULE
#if defined(__GNUC__) || defined(__clang__)
__attribute__((visibility("hidden")))
#endif
KmNapiRegistrarNode* km_napi_registrar_head = nullptr;
static Napi::Object KmNapiStandaloneInit(Napi::Env env, Napi::Object exports) {
    for (KmNapiRegistrarNode* cur = km_napi_registrar_head; cur != nullptr; cur = cur->next) {
        cur->register_fn(env, exports);
    }
    return exports;
}
NODE_API_MODULE(NODE_GYP_MODULE_NAME, KmNapiStandaloneInit)
#endif
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
        return """
@file:OptIn(ExperimentalUnsignedTypes::class)

package $coreNamespace.intrinsics

private val kmCoreNapiAddon: dynamic =
    js("(function() { $loaderBody })()")

private fun lo32(v: Long): Int = v.toInt()
private fun hi32(v: Long): Int = (v ushr 32).toInt()
private fun uLo32(v: ULong): Int = v.toInt()
private fun uHi32(v: ULong): Int = (v shr 32).toInt()
private fun packI64(lo: Int, hi: Int): Long = (hi.toLong() shl 32) or (lo.toLong() and 0xFFFF_FFFFL)
private fun packU64(lo: Int, hi: Int): ULong =
    ((hi.toLong().toULong() and 0xFFFF_FFFFuL) shl 32) or (lo.toLong().toULong() and 0xFFFF_FFFFuL)

public actual fun kmLoadU8(addr: Long): UByte =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmLoadU8(lo32(addr), hi32(addr)) as Int).toUByte() else KmLinearMemoryArena.loadU8(addr)
public actual fun kmLoadU16(addr: Long): UShort =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmLoadU16(lo32(addr), hi32(addr)) as Int).toUShort() else KmLinearMemoryArena.loadU16(addr)
public actual fun kmLoadU32(addr: Long): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmLoadU32(lo32(addr), hi32(addr)) as Int).toUInt() else KmLinearMemoryArena.loadU32(addr)
public actual fun kmLoadU64(addr: Long): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmLoadU64(lo32(addr), hi32(addr)) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.loadU64(addr)
    }
public actual fun kmLoadF32(addr: Long): Float =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmLoadF32(lo32(addr), hi32(addr)) as Double).toFloat() else KmLinearMemoryArena.loadF32(addr)
public actual fun kmLoadF64(addr: Long): Double =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmLoadF64(lo32(addr), hi32(addr)) as Double) else KmLinearMemoryArena.loadF64(addr)

public actual fun kmStoreU8(addr: Long, value: UByte) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmStoreU8(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU8(addr, value)
}
public actual fun kmStoreU16(addr: Long, value: UShort) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmStoreU16(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU16(addr, value)
}
public actual fun kmStoreU32(addr: Long, value: UInt) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmStoreU32(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU32(addr, value)
}
public actual fun kmStoreU64(addr: Long, value: ULong) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmStoreU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value)) else KmLinearMemoryArena.storeU64(addr, value)
}
public actual fun kmStoreF32(addr: Long, value: Float) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmStoreF32(lo32(addr), hi32(addr), value.toDouble()) else KmLinearMemoryArena.storeF32(addr, value)
}
public actual fun kmStoreF64(addr: Long, value: Double) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmStoreF64(lo32(addr), hi32(addr), value) else KmLinearMemoryArena.storeF64(addr, value)
}

public actual fun kmVolatileLoadU8(addr: Long): UByte =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmVolatileLoadU8(lo32(addr), hi32(addr)) as Int).toUByte() else KmLinearMemoryArena.loadU8(addr)
public actual fun kmVolatileLoadU16(addr: Long): UShort =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmVolatileLoadU16(lo32(addr), hi32(addr)) as Int).toUShort() else KmLinearMemoryArena.loadU16(addr)
public actual fun kmVolatileLoadU32(addr: Long): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmVolatileLoadU32(lo32(addr), hi32(addr)) as Int).toUInt() else KmLinearMemoryArena.loadU32(addr)
public actual fun kmVolatileLoadU64(addr: Long): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmVolatileLoadU64(lo32(addr), hi32(addr)) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.loadU64(addr)
    }

public actual fun kmVolatileStoreU8(addr: Long, value: UByte) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmVolatileStoreU8(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU8(addr, value)
}
public actual fun kmVolatileStoreU16(addr: Long, value: UShort) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmVolatileStoreU16(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU16(addr, value)
}
public actual fun kmVolatileStoreU32(addr: Long, value: UInt) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmVolatileStoreU32(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU32(addr, value)
}
public actual fun kmVolatileStoreU64(addr: Long, value: ULong) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmVolatileStoreU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value)) else KmLinearMemoryArena.storeU64(addr, value)
}

public actual fun kmCopyNonoverlapping(dst: Long, src: Long, bytes: Long) {
    if (kmCoreNapiAddon != null) {
        kmCoreNapiAddon.kmCopyNonoverlapping(lo32(dst), hi32(dst), lo32(src), hi32(src), lo32(bytes), hi32(bytes))
    } else {
        KmLinearMemoryArena.copyNonoverlapping(dst, src, bytes)
    }
}
public actual fun kmCopy(dst: Long, src: Long, bytes: Long) {
    if (kmCoreNapiAddon != null) {
        kmCoreNapiAddon.kmCopy(lo32(dst), hi32(dst), lo32(src), hi32(src), lo32(bytes), hi32(bytes))
    } else {
        KmLinearMemoryArena.copy(dst, src, bytes)
    }
}
public actual fun kmWriteBytes(dst: Long, value: UByte, bytes: Long) {
    if (kmCoreNapiAddon != null) {
        kmCoreNapiAddon.kmWriteBytes(lo32(dst), hi32(dst), value.toInt(), lo32(bytes), hi32(bytes))
    } else {
        KmLinearMemoryArena.writeBytes(dst, value, bytes)
    }
}
public actual fun kmCompareBytes(left: Long, right: Long, bytes: Long): Int =
    if (kmCoreNapiAddon != null) {
        kmCoreNapiAddon.kmCompareBytes(lo32(left), hi32(left), lo32(right), hi32(right), lo32(bytes), hi32(bytes)) as Int
    } else {
        KmLinearMemoryArena.compareBytes(left, right, bytes)
    }

public actual fun kmAlloc(size: Long, align: Long): Long =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmAlloc(lo32(size), hi32(size), lo32(align), hi32(align)) as Int
        packI64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.alloc(size, align)
    }
public actual fun kmAllocZeroed(size: Long, align: Long): Long =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmAllocZeroed(lo32(size), hi32(size), lo32(align), hi32(align)) as Int
        packI64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.allocZeroed(size, align)
    }
public actual fun kmRealloc(ptr: Long, oldSize: Long, align: Long, newSize: Long): Long =
    if (kmCoreNapiAddon != null) {
        val lo =
            kmCoreNapiAddon.kmRealloc(
                lo32(ptr),
                hi32(ptr),
                lo32(oldSize),
                hi32(oldSize),
                lo32(align),
                hi32(align),
                lo32(newSize),
                hi32(newSize),
            ) as Int
        packI64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.realloc(ptr, oldSize, align, newSize)
    }
public actual fun kmDealloc(ptr: Long, size: Long, align: Long) {
    if (kmCoreNapiAddon != null) {
        kmCoreNapiAddon.kmDealloc(lo32(ptr), hi32(ptr), lo32(size), hi32(size), lo32(align), hi32(align))
    } else {
        KmLinearMemoryArena.dealloc(ptr, size, align)
    }
}

public actual fun kmAtomicFence(ord: Int) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmAtomicFence(ord)
}
public actual fun kmAtomicSignalFence(ord: Int) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmAtomicSignalFence(ord)
}
public actual fun kmAtomicLoadU8(addr: Long, ord: Int): UByte =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicLoadU8(lo32(addr), hi32(addr), ord) as Int).toUByte() else KmLinearMemoryArena.loadU8(addr)
public actual fun kmAtomicLoadU16(addr: Long, ord: Int): UShort =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicLoadU16(lo32(addr), hi32(addr), ord) as Int).toUShort() else KmLinearMemoryArena.loadU16(addr)
public actual fun kmAtomicLoadU32(addr: Long, ord: Int): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicLoadU32(lo32(addr), hi32(addr), ord) as Int).toUInt() else KmLinearMemoryArena.loadU32(addr)
public actual fun kmAtomicLoadU64(addr: Long, ord: Int): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmAtomicLoadU64(lo32(addr), hi32(addr), ord) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.loadU64(addr)
    }

public actual fun kmAtomicStoreU8(addr: Long, value: UByte, ord: Int) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmAtomicStoreU8(lo32(addr), hi32(addr), value.toInt(), ord) else KmLinearMemoryArena.storeU8(addr, value)
}
public actual fun kmAtomicStoreU16(addr: Long, value: UShort, ord: Int) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmAtomicStoreU16(lo32(addr), hi32(addr), value.toInt(), ord) else KmLinearMemoryArena.storeU16(addr, value)
}
public actual fun kmAtomicStoreU32(addr: Long, value: UInt, ord: Int) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmAtomicStoreU32(lo32(addr), hi32(addr), value.toInt(), ord) else KmLinearMemoryArena.storeU32(addr, value)
}
public actual fun kmAtomicStoreU64(addr: Long, value: ULong, ord: Int) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmAtomicStoreU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value), ord) else KmLinearMemoryArena.storeU64(addr, value)
}

public actual fun kmAtomicXchgU8(addr: Long, value: UByte, ord: Int): UByte =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicXchgU8(lo32(addr), hi32(addr), value.toInt(), ord) as Int).toUByte() else KmLinearMemoryArena.atomicXchgU8(addr, value)
public actual fun kmAtomicXchgU16(addr: Long, value: UShort, ord: Int): UShort =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicXchgU16(lo32(addr), hi32(addr), value.toInt(), ord) as Int).toUShort() else KmLinearMemoryArena.atomicXchgU16(addr, value)
public actual fun kmAtomicXchgU32(addr: Long, value: UInt, ord: Int): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicXchgU32(lo32(addr), hi32(addr), value.toInt(), ord) as Int).toUInt() else KmLinearMemoryArena.atomicXchgU32(addr, value)
public actual fun kmAtomicXchgU64(addr: Long, value: ULong, ord: Int): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmAtomicXchgU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value), ord) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.atomicXchgU64(addr, value)
    }

public actual fun kmAtomicCxchgU8(addr: Long, expected: UByte, desired: UByte, succ: Int, fail: Int): AtomicCxchgU8Outcome =
    if (kmCoreNapiAddon != null) {
        val packed = kmCoreNapiAddon.kmAtomicCxchgU8(lo32(addr), hi32(addr), expected.toInt(), desired.toInt(), succ, fail) as Int
        AtomicCxchgU8Outcome((packed and 0xFF).toUByte(), (packed ushr 8) != 0)
    } else {
        KmLinearMemoryArena.atomicCxchgU8(addr, expected, desired)
    }

public actual fun kmAtomicCxchgU16(addr: Long, expected: UShort, desired: UShort, succ: Int, fail: Int): AtomicCxchgU16Outcome =
    if (kmCoreNapiAddon != null) {
        val packed = kmCoreNapiAddon.kmAtomicCxchgU16(lo32(addr), hi32(addr), expected.toInt(), desired.toInt(), succ, fail) as Int
        AtomicCxchgU16Outcome((packed and 0xFFFF).toUShort(), (packed ushr 16) != 0)
    } else {
        KmLinearMemoryArena.atomicCxchgU16(addr, expected, desired)
    }

public actual fun kmAtomicCxchgU32(addr: Long, expected: UInt, desired: UInt, succ: Int, fail: Int): AtomicCxchgU32Outcome =
    if (kmCoreNapiAddon != null) {
        val ok = (kmCoreNapiAddon.kmAtomicCxchgU32(lo32(addr), hi32(addr), expected.toInt(), desired.toInt(), succ, fail) as Int) != 0
        AtomicCxchgU32Outcome((kmCoreNapiAddon.kmOut0() as Int).toUInt(), ok)
    } else {
        KmLinearMemoryArena.atomicCxchgU32(addr, expected, desired)
    }

public actual fun kmAtomicCxchgU64(addr: Long, expected: ULong, desired: ULong, succ: Int, fail: Int): AtomicCxchgU64Outcome =
    if (kmCoreNapiAddon != null) {
        val ok =
            (
                kmCoreNapiAddon.kmAtomicCxchgU64(
                    lo32(addr),
                    hi32(addr),
                    uLo32(expected),
                    uHi32(expected),
                    uLo32(desired),
                    uHi32(desired),
                    succ,
                    fail,
                ) as Int
            ) != 0
        AtomicCxchgU64Outcome(packU64(kmCoreNapiAddon.kmOut0() as Int, kmCoreNapiAddon.kmOut1() as Int), ok)
    } else {
        KmLinearMemoryArena.atomicCxchgU64(addr, expected, desired)
    }

public actual fun kmAtomicRmwU8(addr: Long, value: UByte, op: Int, ord: Int): UByte =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicRmwU8(lo32(addr), hi32(addr), value.toInt(), op, ord) as Int).toUByte() else KmLinearMemoryArena.atomicRmwU8(addr, value, op)
public actual fun kmAtomicRmwU16(addr: Long, value: UShort, op: Int, ord: Int): UShort =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicRmwU16(lo32(addr), hi32(addr), value.toInt(), op, ord) as Int).toUShort() else KmLinearMemoryArena.atomicRmwU16(addr, value, op)
public actual fun kmAtomicRmwU32(addr: Long, value: UInt, op: Int, ord: Int): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicRmwU32(lo32(addr), hi32(addr), value.toInt(), op, ord) as Int).toUInt() else KmLinearMemoryArena.atomicRmwU32(addr, value, op)
public actual fun kmAtomicRmwU64(addr: Long, value: ULong, op: Int, ord: Int): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmAtomicRmwU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value), op, ord) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.atomicRmwU64(addr, value, op)
    }

public actual fun kmCtpopU64(value: ULong): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmCtpopU64(uLo32(value), uHi32(value)) as Int).toUInt() else KmLinearMemoryArena.ctpopU64(value)
public actual fun kmCtlzU64(value: ULong): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmCtlzU64(uLo32(value), uHi32(value)) as Int).toUInt() else KmLinearMemoryArena.ctlzU64(value)
public actual fun kmCttzU64(value: ULong): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmCttzU64(uLo32(value), uHi32(value)) as Int).toUInt() else KmLinearMemoryArena.cttzU64(value)
public actual fun kmBswapU16(value: UShort): UShort =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmBswapU16(value.toInt()) as Int).toUShort() else KmLinearMemoryArena.bswapU16(value)
public actual fun kmBswapU32(value: UInt): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmBswapU32(value.toInt()) as Int).toUInt() else KmLinearMemoryArena.bswapU32(value)
public actual fun kmBswapU64(value: ULong): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmBswapU64(uLo32(value), uHi32(value)) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.bswapU64(value)
    }
public actual fun kmBitreverseU64(value: ULong): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmBitreverseU64(uLo32(value), uHi32(value)) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.bitreverseU64(value)
    }
public actual fun kmFmaf32(a: Float, b: Float, c: Float): Float =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmFmaf32(a.toDouble(), b.toDouble(), c.toDouble()) as Double).toFloat() else KmLinearMemoryArena.fmaf32(a, b, c)
public actual fun kmFmaf64(a: Double, b: Double, c: Double): Double =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmFmaf64(a, b, c) as Double) else KmLinearMemoryArena.fmaf64(a, b, c)
public actual fun kmSqrtf32(x: Float): Float =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmSqrtf32(x.toDouble()) as Double).toFloat() else KmLinearMemoryArena.sqrtf32(x)
public actual fun kmSqrtf64(x: Double): Double =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmSqrtf64(x) as Double) else KmLinearMemoryArena.sqrtf64(x)
public actual fun kmAbort(): Nothing {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmAbort()
    error("Rust intrinsic abort triggered")
}

public actual fun kmSpinLoopHint() {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmSpinLoopHint()
}
public actual fun kmBlackBoxU64(value: ULong): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmBlackBoxU64(uLo32(value), uHi32(value)) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        value
    }
public actual fun kmUmulhU64(a: ULong, b: ULong): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmUmulhU64(uLo32(a), uHi32(a), uLo32(b), uHi32(b)) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.umulhU64(a, b)
    }
public actual fun kmSmulhI64(a: Long, b: Long): Long =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmSmulhI64(lo32(a), hi32(a), lo32(b), hi32(b)) as Int
        packI64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.smulhI64(a, b)
    }
public actual fun kmAtomicCxchgU128Pair(
    addr: Long,
    expLo: ULong,
    expHi: ULong,
    desLo: ULong,
    desHi: ULong,
): AtomicCxchgU128Outcome =
    if (kmCoreNapiAddon != null) {
        val ok =
            (
                kmCoreNapiAddon.kmAtomicCxchgU128Pair(
                    lo32(addr),
                    hi32(addr),
                    uLo32(expLo),
                    uHi32(expLo),
                    uLo32(expHi),
                    uHi32(expHi),
                    uLo32(desLo),
                    uHi32(desLo),
                    uLo32(desHi),
                    uHi32(desHi),
                ) as Int
            ) != 0
        AtomicCxchgU128Outcome(
            packU64(kmCoreNapiAddon.kmOut0() as Int, kmCoreNapiAddon.kmOut1() as Int),
            packU64(kmCoreNapiAddon.kmOut2() as Int, kmCoreNapiAddon.kmOut3() as Int),
            ok,
        )
    } else {
        KmLinearMemoryArena.atomicCxchgU128Pair(addr, expLo, expHi, desLo, desHi)
    }

public actual fun kmSimdFmaF32x4(dstAddr: Long, aAddr: Long, bAddr: Long, cAddr: Long) {
    if (kmCoreNapiAddon != null) {
        kmCoreNapiAddon.kmSimdFmaF32x4(lo32(dstAddr), hi32(dstAddr), lo32(aAddr), hi32(aAddr), lo32(bAddr), hi32(bAddr), lo32(cAddr), hi32(cAddr))
    } else {
        KmLinearMemoryArena.simdFmaF32x4(dstAddr, aAddr, bAddr, cAddr)
    }
}
public actual fun kmSimdDotF32(aAddr: Long, bAddr: Long, len: Long): Float =
    if (kmCoreNapiAddon != null) {
        (kmCoreNapiAddon.kmSimdDotF32(lo32(aAddr), hi32(aAddr), lo32(bAddr), hi32(bAddr), lo32(len), hi32(len)) as Double).toFloat()
    } else {
        KmLinearMemoryArena.simdDotF32(aAddr, bAddr, len)
    }

${CoreIntrinsicMathAndBitSpecs.renderJsActuals().trimEnd()}
""".trimStart()
    }

    public fun renderKmCoreWasmJsActualsKotlin(
        coreNamespace: String,
        ffiModName: String = "kmcore",
    ): String {
        val loaderBody = renderNodeKmCoreLoaderJsBody(ffiModName)
        return """
@file:OptIn(ExperimentalUnsignedTypes::class, ExperimentalWasmJsInterop::class)

package $coreNamespace.intrinsics

@JsFun("() => { var valObj = (function() { $loaderBody })(); return valObj !== null && typeof valObj !== 'undefined'; }")
private external fun initKmCoreNapiAddon(): Boolean

private val kmCoreNapiAvailable: Boolean = initKmCoreNapiAddon()

private fun lo32(v: Long): Int = v.toInt()
private fun hi32(v: Long): Int = (v ushr 32).toInt()
private fun uLo32(v: ULong): Int = v.toInt()
private fun uHi32(v: ULong): Int = (v shr 32).toInt()
private fun packI64(lo: Int, hi: Int): Long = (hi.toLong() shl 32) or (lo.toLong() and 0xFFFF_FFFFL)
private fun packU64(lo: Int, hi: Int): ULong =
    ((hi.toLong().toULong() and 0xFFFF_FFFFuL) shl 32) or (lo.toLong().toULong() and 0xFFFF_FFFFuL)

@JsFun("() => globalThis.kmCoreNapiV2.kmOut0()")
private external fun wasmKmOut0(): Int

@JsFun("() => globalThis.kmCoreNapiV2.kmOut1()")
private external fun wasmKmOut1(): Int

@JsFun("() => globalThis.kmCoreNapiV2.kmOut2()")
private external fun wasmKmOut2(): Int

@JsFun("() => globalThis.kmCoreNapiV2.kmOut3()")
private external fun wasmKmOut3(): Int

@JsFun("() => globalThis.kmCoreNapiV2.kmOut4()")
private external fun wasmKmOut4(): Int

@JsFun("() => globalThis.kmCoreNapiV2.kmOut5()")
private external fun wasmKmOut5(): Int

@JsFun("() => globalThis.kmCoreNapiV2.kmOut6()")
private external fun wasmKmOut6(): Int

@JsFun("() => globalThis.kmCoreNapiV2.kmOut7()")
private external fun wasmKmOut7(): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmLoadU8(lo, hi)")
private external fun wasmKmLoadU8(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmLoadU16(lo, hi)")
private external fun wasmKmLoadU16(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmLoadU32(lo, hi)")
private external fun wasmKmLoadU32(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmLoadU64(lo, hi)")
private external fun wasmKmLoadU64(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmLoadF32(lo, hi)")
private external fun wasmKmLoadF32(lo: Int, hi: Int): Double

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmLoadF64(lo, hi)")
private external fun wasmKmLoadF64(lo: Int, hi: Int): Double

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmStoreU8(lo, hi, v)")
private external fun wasmKmStoreU8(lo: Int, hi: Int, v: Int)

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmStoreU16(lo, hi, v)")
private external fun wasmKmStoreU16(lo: Int, hi: Int, v: Int)

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmStoreU32(lo, hi, v)")
private external fun wasmKmStoreU32(lo: Int, hi: Int, v: Int)

@JsFun("(lo, hi, vLo, vHi) => globalThis.kmCoreNapiV2.kmStoreU64(lo, hi, vLo, vHi)")
private external fun wasmKmStoreU64(lo: Int, hi: Int, vLo: Int, vHi: Int)

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmStoreF32(lo, hi, v)")
private external fun wasmKmStoreF32(lo: Int, hi: Int, v: Double)

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmStoreF64(lo, hi, v)")
private external fun wasmKmStoreF64(lo: Int, hi: Int, v: Double)

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmVolatileLoadU8(lo, hi)")
private external fun wasmKmVolatileLoadU8(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmVolatileLoadU16(lo, hi)")
private external fun wasmKmVolatileLoadU16(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmVolatileLoadU32(lo, hi)")
private external fun wasmKmVolatileLoadU32(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmVolatileLoadU64(lo, hi)")
private external fun wasmKmVolatileLoadU64(lo: Int, hi: Int): Int

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmVolatileStoreU8(lo, hi, v)")
private external fun wasmKmVolatileStoreU8(lo: Int, hi: Int, v: Int)

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmVolatileStoreU16(lo, hi, v)")
private external fun wasmKmVolatileStoreU16(lo: Int, hi: Int, v: Int)

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmVolatileStoreU32(lo, hi, v)")
private external fun wasmKmVolatileStoreU32(lo: Int, hi: Int, v: Int)

@JsFun("(lo, hi, vLo, vHi) => globalThis.kmCoreNapiV2.kmVolatileStoreU64(lo, hi, vLo, vHi)")
private external fun wasmKmVolatileStoreU64(lo: Int, hi: Int, vLo: Int, vHi: Int)

@JsFun("(dLo, dHi, sLo, sHi, bLo, bHi) => globalThis.kmCoreNapiV2.kmCopyNonoverlapping(dLo, dHi, sLo, sHi, bLo, bHi)")
private external fun wasmKmCopyNonoverlapping(dLo: Int, dHi: Int, sLo: Int, sHi: Int, bLo: Int, bHi: Int)

@JsFun("(dLo, dHi, sLo, sHi, bLo, bHi) => globalThis.kmCoreNapiV2.kmCopy(dLo, dHi, sLo, sHi, bLo, bHi)")
private external fun wasmKmCopy(dLo: Int, dHi: Int, sLo: Int, sHi: Int, bLo: Int, bHi: Int)

@JsFun("(dLo, dHi, v, bLo, bHi) => globalThis.kmCoreNapiV2.kmWriteBytes(dLo, dHi, v, bLo, bHi)")
private external fun wasmKmWriteBytes(dLo: Int, dHi: Int, v: Int, bLo: Int, bHi: Int)

@JsFun("(lLo, lHi, rLo, rHi, bLo, bHi) => globalThis.kmCoreNapiV2.kmCompareBytes(lLo, lHi, rLo, rHi, bLo, bHi)")
private external fun wasmKmCompareBytes(lLo: Int, lHi: Int, rLo: Int, rHi: Int, bLo: Int, bHi: Int): Int

@JsFun("(sLo, sHi, aLo, aHi) => globalThis.kmCoreNapiV2.kmAlloc(sLo, sHi, aLo, aHi)")
private external fun wasmKmAlloc(sLo: Int, sHi: Int, aLo: Int, aHi: Int): Int

@JsFun("(sLo, sHi, aLo, aHi) => globalThis.kmCoreNapiV2.kmAllocZeroed(sLo, sHi, aLo, aHi)")
private external fun wasmKmAllocZeroed(sLo: Int, sHi: Int, aLo: Int, aHi: Int): Int

@JsFun("(pLo, pHi, oLo, oHi, aLo, aHi, nLo, nHi) => globalThis.kmCoreNapiV2.kmRealloc(pLo, pHi, oLo, oHi, aLo, aHi, nLo, nHi)")
private external fun wasmKmRealloc(pLo: Int, pHi: Int, oLo: Int, oHi: Int, aLo: Int, aHi: Int, nLo: Int, nHi: Int): Int

@JsFun("(pLo, pHi, sLo, sHi, aLo, aHi) => globalThis.kmCoreNapiV2.kmDealloc(pLo, pHi, sLo, sHi, aLo, aHi)")
private external fun wasmKmDealloc(pLo: Int, pHi: Int, sLo: Int, sHi: Int, aLo: Int, aHi: Int)

@JsFun("(p0) => globalThis.kmCoreNapiV2.kmAtomicFence(p0)")
private external fun wasmKmAtomicFence(p0: Int)

@JsFun("(p0) => globalThis.kmCoreNapiV2.kmAtomicSignalFence(p0)")
private external fun wasmKmAtomicSignalFence(p0: Int)

@JsFun("(lo, hi, ord) => globalThis.kmCoreNapiV2.kmAtomicLoadU8(lo, hi, ord)")
private external fun wasmKmAtomicLoadU8(lo: Int, hi: Int, ord: Int): Int

@JsFun("(lo, hi, ord) => globalThis.kmCoreNapiV2.kmAtomicLoadU16(lo, hi, ord)")
private external fun wasmKmAtomicLoadU16(lo: Int, hi: Int, ord: Int): Int

@JsFun("(lo, hi, ord) => globalThis.kmCoreNapiV2.kmAtomicLoadU32(lo, hi, ord)")
private external fun wasmKmAtomicLoadU32(lo: Int, hi: Int, ord: Int): Int

@JsFun("(lo, hi, ord) => globalThis.kmCoreNapiV2.kmAtomicLoadU64(lo, hi, ord)")
private external fun wasmKmAtomicLoadU64(lo: Int, hi: Int, ord: Int): Int

@JsFun("(lo, hi, v, ord) => globalThis.kmCoreNapiV2.kmAtomicStoreU8(lo, hi, v, ord)")
private external fun wasmKmAtomicStoreU8(lo: Int, hi: Int, v: Int, ord: Int)

@JsFun("(lo, hi, v, ord) => globalThis.kmCoreNapiV2.kmAtomicStoreU16(lo, hi, v, ord)")
private external fun wasmKmAtomicStoreU16(lo: Int, hi: Int, v: Int, ord: Int)

@JsFun("(lo, hi, v, ord) => globalThis.kmCoreNapiV2.kmAtomicStoreU32(lo, hi, v, ord)")
private external fun wasmKmAtomicStoreU32(lo: Int, hi: Int, v: Int, ord: Int)

@JsFun("(lo, hi, vLo, vHi, ord) => globalThis.kmCoreNapiV2.kmAtomicStoreU64(lo, hi, vLo, vHi, ord)")
private external fun wasmKmAtomicStoreU64(lo: Int, hi: Int, vLo: Int, vHi: Int, ord: Int)

@JsFun("(lo, hi, v, ord) => globalThis.kmCoreNapiV2.kmAtomicXchgU8(lo, hi, v, ord)")
private external fun wasmKmAtomicXchgU8(lo: Int, hi: Int, v: Int, ord: Int): Int

@JsFun("(lo, hi, v, ord) => globalThis.kmCoreNapiV2.kmAtomicXchgU16(lo, hi, v, ord)")
private external fun wasmKmAtomicXchgU16(lo: Int, hi: Int, v: Int, ord: Int): Int

@JsFun("(lo, hi, v, ord) => globalThis.kmCoreNapiV2.kmAtomicXchgU32(lo, hi, v, ord)")
private external fun wasmKmAtomicXchgU32(lo: Int, hi: Int, v: Int, ord: Int): Int

@JsFun("(lo, hi, vLo, vHi, ord) => globalThis.kmCoreNapiV2.kmAtomicXchgU64(lo, hi, vLo, vHi, ord)")
private external fun wasmKmAtomicXchgU64(lo: Int, hi: Int, vLo: Int, vHi: Int, ord: Int): Int

@JsFun("(lo, hi, exp, des, succ, fail) => globalThis.kmCoreNapiV2.kmAtomicCxchgU8(lo, hi, exp, des, succ, fail)")
private external fun wasmKmAtomicCxchgU8(lo: Int, hi: Int, exp: Int, des: Int, succ: Int, fail: Int): Int

@JsFun("(lo, hi, exp, des, succ, fail) => globalThis.kmCoreNapiV2.kmAtomicCxchgU16(lo, hi, exp, des, succ, fail)")
private external fun wasmKmAtomicCxchgU16(lo: Int, hi: Int, exp: Int, des: Int, succ: Int, fail: Int): Int

@JsFun("(lo, hi, exp, des, succ, fail) => globalThis.kmCoreNapiV2.kmAtomicCxchgU32(lo, hi, exp, des, succ, fail)")
private external fun wasmKmAtomicCxchgU32(lo: Int, hi: Int, exp: Int, des: Int, succ: Int, fail: Int): Int

@JsFun("(lo, hi, expLo, expHi, desLo, desHi, succ, fail) => globalThis.kmCoreNapiV2.kmAtomicCxchgU64(lo, hi, expLo, expHi, desLo, desHi, succ, fail)")
private external fun wasmKmAtomicCxchgU64(
    lo: Int,
    hi: Int,
    expLo: Int,
    expHi: Int,
    desLo: Int,
    desHi: Int,
    succ: Int,
    fail: Int,
): Int

@JsFun("(lo, hi, v, op, ord) => globalThis.kmCoreNapiV2.kmAtomicRmwU8(lo, hi, v, op, ord)")
private external fun wasmKmAtomicRmwU8(lo: Int, hi: Int, v: Int, op: Int, ord: Int): Int

@JsFun("(lo, hi, v, op, ord) => globalThis.kmCoreNapiV2.kmAtomicRmwU16(lo, hi, v, op, ord)")
private external fun wasmKmAtomicRmwU16(lo: Int, hi: Int, v: Int, op: Int, ord: Int): Int

@JsFun("(lo, hi, v, op, ord) => globalThis.kmCoreNapiV2.kmAtomicRmwU32(lo, hi, v, op, ord)")
private external fun wasmKmAtomicRmwU32(lo: Int, hi: Int, v: Int, op: Int, ord: Int): Int

@JsFun("(lo, hi, vLo, vHi, op, ord) => globalThis.kmCoreNapiV2.kmAtomicRmwU64(lo, hi, vLo, vHi, op, ord)")
private external fun wasmKmAtomicRmwU64(lo: Int, hi: Int, vLo: Int, vHi: Int, op: Int, ord: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmCtpopU64(lo, hi)")
private external fun wasmKmCtpopU64(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmCtlzU64(lo, hi)")
private external fun wasmKmCtlzU64(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmCttzU64(lo, hi)")
private external fun wasmKmCttzU64(lo: Int, hi: Int): Int

@JsFun("(p0) => globalThis.kmCoreNapiV2.kmBswapU16(p0)")
private external fun wasmKmBswapU16(p0: Int): Int

@JsFun("(p0) => globalThis.kmCoreNapiV2.kmBswapU32(p0)")
private external fun wasmKmBswapU32(p0: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmBswapU64(lo, hi)")
private external fun wasmKmBswapU64(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmBitreverseU64(lo, hi)")
private external fun wasmKmBitreverseU64(lo: Int, hi: Int): Int

@JsFun("(p0, p1, p2) => globalThis.kmCoreNapiV2.kmFmaf32(p0, p1, p2)")
private external fun wasmKmFmaf32(p0: Double, p1: Double, p2: Double): Double

@JsFun("(p0, p1, p2) => globalThis.kmCoreNapiV2.kmFmaf64(p0, p1, p2)")
private external fun wasmKmFmaf64(p0: Double, p1: Double, p2: Double): Double

@JsFun("(p0) => globalThis.kmCoreNapiV2.kmSqrtf32(p0)")
private external fun wasmKmSqrtf32(p0: Double): Double

@JsFun("(p0) => globalThis.kmCoreNapiV2.kmSqrtf64(p0)")
private external fun wasmKmSqrtf64(p0: Double): Double

@JsFun("() => globalThis.kmCoreNapiV2.kmAbort()")
private external fun wasmKmAbort()

@JsFun("() => globalThis.kmCoreNapiV2.kmSpinLoopHint()")
private external fun wasmKmSpinLoopHint()

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmBlackBoxU64(lo, hi)")
private external fun wasmKmBlackBoxU64(lo: Int, hi: Int): Int

@JsFun("(aLo, aHi, bLo, bHi) => globalThis.kmCoreNapiV2.kmUmulhU64(aLo, aHi, bLo, bHi)")
private external fun wasmKmUmulhU64(aLo: Int, aHi: Int, bLo: Int, bHi: Int): Int

@JsFun("(aLo, aHi, bLo, bHi) => globalThis.kmCoreNapiV2.kmSmulhI64(aLo, aHi, bLo, bHi)")
private external fun wasmKmSmulhI64(aLo: Int, aHi: Int, bLo: Int, bHi: Int): Int

@JsFun("(addrLo, addrHi, expLoLo, expLoHi, expHiLo, expHiHi, desLoLo, desLoHi, desHiLo, desHiHi) => globalThis.kmCoreNapiV2.kmAtomicCxchgU128Pair(addrLo, addrHi, expLoLo, expLoHi, expHiLo, expHiHi, desLoLo, desLoHi, desHiLo, desHiHi)")
private external fun wasmKmAtomicCxchgU128Pair(
    addrLo: Int,
    addrHi: Int,
    expLoLo: Int,
    expLoHi: Int,
    expHiLo: Int,
    expHiHi: Int,
    desLoLo: Int,
    desLoHi: Int,
    desHiLo: Int,
    desHiHi: Int,
): Int

@JsFun("(dLo, dHi, aLo, aHi, bLo, bHi, cLo, cHi) => globalThis.kmCoreNapiV2.kmSimdFmaF32x4(dLo, dHi, aLo, aHi, bLo, bHi, cLo, cHi)")
private external fun wasmKmSimdFmaF32x4(dLo: Int, dHi: Int, aLo: Int, aHi: Int, bLo: Int, bHi: Int, cLo: Int, cHi: Int)

@JsFun("(aLo, aHi, bLo, bHi, lLo, lHi) => globalThis.kmCoreNapiV2.kmSimdDotF32(aLo, aHi, bLo, bHi, lLo, lHi)")
private external fun wasmKmSimdDotF32(aLo: Int, aHi: Int, bLo: Int, bHi: Int, lLo: Int, lHi: Int): Double

${CoreIntrinsicMathAndBitSpecs.renderWasmJsFunDeclarations().trimEnd()}

public actual fun kmLoadU8(addr: Long): UByte =
    if (kmCoreNapiAvailable) wasmKmLoadU8(lo32(addr), hi32(addr)).toUByte() else KmLinearMemoryArena.loadU8(addr)
public actual fun kmLoadU16(addr: Long): UShort =
    if (kmCoreNapiAvailable) wasmKmLoadU16(lo32(addr), hi32(addr)).toUShort() else KmLinearMemoryArena.loadU16(addr)
public actual fun kmLoadU32(addr: Long): UInt =
    if (kmCoreNapiAvailable) wasmKmLoadU32(lo32(addr), hi32(addr)).toUInt() else KmLinearMemoryArena.loadU32(addr)
public actual fun kmLoadU64(addr: Long): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmLoadU64(lo32(addr), hi32(addr))
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.loadU64(addr)
    }
public actual fun kmLoadF32(addr: Long): Float =
    if (kmCoreNapiAvailable) wasmKmLoadF32(lo32(addr), hi32(addr)).toFloat() else KmLinearMemoryArena.loadF32(addr)
public actual fun kmLoadF64(addr: Long): Double =
    if (kmCoreNapiAvailable) wasmKmLoadF64(lo32(addr), hi32(addr)) else KmLinearMemoryArena.loadF64(addr)

public actual fun kmStoreU8(addr: Long, value: UByte) {
    if (kmCoreNapiAvailable) wasmKmStoreU8(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU8(addr, value)
}
public actual fun kmStoreU16(addr: Long, value: UShort) {
    if (kmCoreNapiAvailable) wasmKmStoreU16(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU16(addr, value)
}
public actual fun kmStoreU32(addr: Long, value: UInt) {
    if (kmCoreNapiAvailable) wasmKmStoreU32(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU32(addr, value)
}
public actual fun kmStoreU64(addr: Long, value: ULong) {
    if (kmCoreNapiAvailable) wasmKmStoreU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value)) else KmLinearMemoryArena.storeU64(addr, value)
}
public actual fun kmStoreF32(addr: Long, value: Float) {
    if (kmCoreNapiAvailable) wasmKmStoreF32(lo32(addr), hi32(addr), value.toDouble()) else KmLinearMemoryArena.storeF32(addr, value)
}
public actual fun kmStoreF64(addr: Long, value: Double) {
    if (kmCoreNapiAvailable) wasmKmStoreF64(lo32(addr), hi32(addr), value) else KmLinearMemoryArena.storeF64(addr, value)
}

public actual fun kmVolatileLoadU8(addr: Long): UByte =
    if (kmCoreNapiAvailable) wasmKmVolatileLoadU8(lo32(addr), hi32(addr)).toUByte() else KmLinearMemoryArena.loadU8(addr)
public actual fun kmVolatileLoadU16(addr: Long): UShort =
    if (kmCoreNapiAvailable) wasmKmVolatileLoadU16(lo32(addr), hi32(addr)).toUShort() else KmLinearMemoryArena.loadU16(addr)
public actual fun kmVolatileLoadU32(addr: Long): UInt =
    if (kmCoreNapiAvailable) wasmKmVolatileLoadU32(lo32(addr), hi32(addr)).toUInt() else KmLinearMemoryArena.loadU32(addr)
public actual fun kmVolatileLoadU64(addr: Long): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmVolatileLoadU64(lo32(addr), hi32(addr))
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.loadU64(addr)
    }

public actual fun kmVolatileStoreU8(addr: Long, value: UByte) {
    if (kmCoreNapiAvailable) wasmKmVolatileStoreU8(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU8(addr, value)
}
public actual fun kmVolatileStoreU16(addr: Long, value: UShort) {
    if (kmCoreNapiAvailable) wasmKmVolatileStoreU16(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU16(addr, value)
}
public actual fun kmVolatileStoreU32(addr: Long, value: UInt) {
    if (kmCoreNapiAvailable) wasmKmVolatileStoreU32(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU32(addr, value)
}
public actual fun kmVolatileStoreU64(addr: Long, value: ULong) {
    if (kmCoreNapiAvailable) wasmKmVolatileStoreU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value)) else KmLinearMemoryArena.storeU64(addr, value)
}

public actual fun kmCopyNonoverlapping(dst: Long, src: Long, bytes: Long) {
    if (kmCoreNapiAvailable) wasmKmCopyNonoverlapping(lo32(dst), hi32(dst), lo32(src), hi32(src), lo32(bytes), hi32(bytes)) else KmLinearMemoryArena.copyNonoverlapping(dst, src, bytes)
}
public actual fun kmCopy(dst: Long, src: Long, bytes: Long) {
    if (kmCoreNapiAvailable) wasmKmCopy(lo32(dst), hi32(dst), lo32(src), hi32(src), lo32(bytes), hi32(bytes)) else KmLinearMemoryArena.copy(dst, src, bytes)
}
public actual fun kmWriteBytes(dst: Long, value: UByte, bytes: Long) {
    if (kmCoreNapiAvailable) wasmKmWriteBytes(lo32(dst), hi32(dst), value.toInt(), lo32(bytes), hi32(bytes)) else KmLinearMemoryArena.writeBytes(dst, value, bytes)
}
public actual fun kmCompareBytes(left: Long, right: Long, bytes: Long): Int =
    if (kmCoreNapiAvailable) wasmKmCompareBytes(lo32(left), hi32(left), lo32(right), hi32(right), lo32(bytes), hi32(bytes)) else KmLinearMemoryArena.compareBytes(left, right, bytes)

public actual fun kmAlloc(size: Long, align: Long): Long =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmAlloc(lo32(size), hi32(size), lo32(align), hi32(align))
        packI64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.alloc(size, align)
    }
public actual fun kmAllocZeroed(size: Long, align: Long): Long =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmAllocZeroed(lo32(size), hi32(size), lo32(align), hi32(align))
        packI64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.allocZeroed(size, align)
    }
public actual fun kmRealloc(ptr: Long, oldSize: Long, align: Long, newSize: Long): Long =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmRealloc(lo32(ptr), hi32(ptr), lo32(oldSize), hi32(oldSize), lo32(align), hi32(align), lo32(newSize), hi32(newSize))
        packI64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.realloc(ptr, oldSize, align, newSize)
    }
public actual fun kmDealloc(ptr: Long, size: Long, align: Long) {
    if (kmCoreNapiAvailable) wasmKmDealloc(lo32(ptr), hi32(ptr), lo32(size), hi32(size), lo32(align), hi32(align)) else KmLinearMemoryArena.dealloc(ptr, size, align)
}

public actual fun kmAtomicFence(ord: Int) {
    if (kmCoreNapiAvailable) wasmKmAtomicFence(ord)
}
public actual fun kmAtomicSignalFence(ord: Int) {
    if (kmCoreNapiAvailable) wasmKmAtomicSignalFence(ord)
}
public actual fun kmAtomicLoadU8(addr: Long, ord: Int): UByte =
    if (kmCoreNapiAvailable) wasmKmAtomicLoadU8(lo32(addr), hi32(addr), ord).toUByte() else KmLinearMemoryArena.loadU8(addr)
public actual fun kmAtomicLoadU16(addr: Long, ord: Int): UShort =
    if (kmCoreNapiAvailable) wasmKmAtomicLoadU16(lo32(addr), hi32(addr), ord).toUShort() else KmLinearMemoryArena.loadU16(addr)
public actual fun kmAtomicLoadU32(addr: Long, ord: Int): UInt =
    if (kmCoreNapiAvailable) wasmKmAtomicLoadU32(lo32(addr), hi32(addr), ord).toUInt() else KmLinearMemoryArena.loadU32(addr)
public actual fun kmAtomicLoadU64(addr: Long, ord: Int): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmAtomicLoadU64(lo32(addr), hi32(addr), ord)
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.loadU64(addr)
    }

public actual fun kmAtomicStoreU8(addr: Long, value: UByte, ord: Int) {
    if (kmCoreNapiAvailable) wasmKmAtomicStoreU8(lo32(addr), hi32(addr), value.toInt(), ord) else KmLinearMemoryArena.storeU8(addr, value)
}
public actual fun kmAtomicStoreU16(addr: Long, value: UShort, ord: Int) {
    if (kmCoreNapiAvailable) wasmKmAtomicStoreU16(lo32(addr), hi32(addr), value.toInt(), ord) else KmLinearMemoryArena.storeU16(addr, value)
}
public actual fun kmAtomicStoreU32(addr: Long, value: UInt, ord: Int) {
    if (kmCoreNapiAvailable) wasmKmAtomicStoreU32(lo32(addr), hi32(addr), value.toInt(), ord) else KmLinearMemoryArena.storeU32(addr, value)
}
public actual fun kmAtomicStoreU64(addr: Long, value: ULong, ord: Int) {
    if (kmCoreNapiAvailable) wasmKmAtomicStoreU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value), ord) else KmLinearMemoryArena.storeU64(addr, value)
}

public actual fun kmAtomicXchgU8(addr: Long, value: UByte, ord: Int): UByte =
    if (kmCoreNapiAvailable) wasmKmAtomicXchgU8(lo32(addr), hi32(addr), value.toInt(), ord).toUByte() else KmLinearMemoryArena.atomicXchgU8(addr, value)
public actual fun kmAtomicXchgU16(addr: Long, value: UShort, ord: Int): UShort =
    if (kmCoreNapiAvailable) wasmKmAtomicXchgU16(lo32(addr), hi32(addr), value.toInt(), ord).toUShort() else KmLinearMemoryArena.atomicXchgU16(addr, value)
public actual fun kmAtomicXchgU32(addr: Long, value: UInt, ord: Int): UInt =
    if (kmCoreNapiAvailable) wasmKmAtomicXchgU32(lo32(addr), hi32(addr), value.toInt(), ord).toUInt() else KmLinearMemoryArena.atomicXchgU32(addr, value)
public actual fun kmAtomicXchgU64(addr: Long, value: ULong, ord: Int): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmAtomicXchgU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value), ord)
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.atomicXchgU64(addr, value)
    }

public actual fun kmAtomicCxchgU8(addr: Long, expected: UByte, desired: UByte, succ: Int, fail: Int): AtomicCxchgU8Outcome =
    if (kmCoreNapiAvailable) {
        val packed = wasmKmAtomicCxchgU8(lo32(addr), hi32(addr), expected.toInt(), desired.toInt(), succ, fail)
        AtomicCxchgU8Outcome((packed and 0xFF).toUByte(), (packed ushr 8) != 0)
    } else {
        KmLinearMemoryArena.atomicCxchgU8(addr, expected, desired)
    }

public actual fun kmAtomicCxchgU16(addr: Long, expected: UShort, desired: UShort, succ: Int, fail: Int): AtomicCxchgU16Outcome =
    if (kmCoreNapiAvailable) {
        val packed = wasmKmAtomicCxchgU16(lo32(addr), hi32(addr), expected.toInt(), desired.toInt(), succ, fail)
        AtomicCxchgU16Outcome((packed and 0xFFFF).toUShort(), (packed ushr 16) != 0)
    } else {
        KmLinearMemoryArena.atomicCxchgU16(addr, expected, desired)
    }

public actual fun kmAtomicCxchgU32(addr: Long, expected: UInt, desired: UInt, succ: Int, fail: Int): AtomicCxchgU32Outcome =
    if (kmCoreNapiAvailable) {
        val ok = wasmKmAtomicCxchgU32(lo32(addr), hi32(addr), expected.toInt(), desired.toInt(), succ, fail) != 0
        AtomicCxchgU32Outcome(wasmKmOut0().toUInt(), ok)
    } else {
        KmLinearMemoryArena.atomicCxchgU32(addr, expected, desired)
    }

public actual fun kmAtomicCxchgU64(addr: Long, expected: ULong, desired: ULong, succ: Int, fail: Int): AtomicCxchgU64Outcome =
    if (kmCoreNapiAvailable) {
        val ok = wasmKmAtomicCxchgU64(lo32(addr), hi32(addr), uLo32(expected), uHi32(expected), uLo32(desired), uHi32(desired), succ, fail) != 0
        AtomicCxchgU64Outcome(packU64(wasmKmOut0(), wasmKmOut1()), ok)
    } else {
        KmLinearMemoryArena.atomicCxchgU64(addr, expected, desired)
    }

public actual fun kmAtomicRmwU8(addr: Long, value: UByte, op: Int, ord: Int): UByte =
    if (kmCoreNapiAvailable) wasmKmAtomicRmwU8(lo32(addr), hi32(addr), value.toInt(), op, ord).toUByte() else KmLinearMemoryArena.atomicRmwU8(addr, value, op)
public actual fun kmAtomicRmwU16(addr: Long, value: UShort, op: Int, ord: Int): UShort =
    if (kmCoreNapiAvailable) wasmKmAtomicRmwU16(lo32(addr), hi32(addr), value.toInt(), op, ord).toUShort() else KmLinearMemoryArena.atomicRmwU16(addr, value, op)
public actual fun kmAtomicRmwU32(addr: Long, value: UInt, op: Int, ord: Int): UInt =
    if (kmCoreNapiAvailable) wasmKmAtomicRmwU32(lo32(addr), hi32(addr), value.toInt(), op, ord).toUInt() else KmLinearMemoryArena.atomicRmwU32(addr, value, op)
public actual fun kmAtomicRmwU64(addr: Long, value: ULong, op: Int, ord: Int): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmAtomicRmwU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value), op, ord)
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.atomicRmwU64(addr, value, op)
    }

public actual fun kmCtpopU64(value: ULong): UInt =
    if (kmCoreNapiAvailable) wasmKmCtpopU64(uLo32(value), uHi32(value)).toUInt() else KmLinearMemoryArena.ctpopU64(value)
public actual fun kmCtlzU64(value: ULong): UInt =
    if (kmCoreNapiAvailable) wasmKmCtlzU64(uLo32(value), uHi32(value)).toUInt() else KmLinearMemoryArena.ctlzU64(value)
public actual fun kmCttzU64(value: ULong): UInt =
    if (kmCoreNapiAvailable) wasmKmCttzU64(uLo32(value), uHi32(value)).toUInt() else KmLinearMemoryArena.cttzU64(value)
public actual fun kmBswapU16(value: UShort): UShort =
    if (kmCoreNapiAvailable) wasmKmBswapU16(value.toInt()).toUShort() else KmLinearMemoryArena.bswapU16(value)
public actual fun kmBswapU32(value: UInt): UInt =
    if (kmCoreNapiAvailable) wasmKmBswapU32(value.toInt()).toUInt() else KmLinearMemoryArena.bswapU32(value)
public actual fun kmBswapU64(value: ULong): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmBswapU64(uLo32(value), uHi32(value))
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.bswapU64(value)
    }
public actual fun kmBitreverseU64(value: ULong): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmBitreverseU64(uLo32(value), uHi32(value))
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.bitreverseU64(value)
    }
public actual fun kmFmaf32(a: Float, b: Float, c: Float): Float =
    if (kmCoreNapiAvailable) wasmKmFmaf32(a.toDouble(), b.toDouble(), c.toDouble()).toFloat() else KmLinearMemoryArena.fmaf32(a, b, c)
public actual fun kmFmaf64(a: Double, b: Double, c: Double): Double =
    if (kmCoreNapiAvailable) wasmKmFmaf64(a, b, c) else KmLinearMemoryArena.fmaf64(a, b, c)
public actual fun kmSqrtf32(x: Float): Float =
    if (kmCoreNapiAvailable) wasmKmSqrtf32(x.toDouble()).toFloat() else KmLinearMemoryArena.sqrtf32(x)
public actual fun kmSqrtf64(x: Double): Double =
    if (kmCoreNapiAvailable) wasmKmSqrtf64(x) else KmLinearMemoryArena.sqrtf64(x)
public actual fun kmAbort(): Nothing {
    if (kmCoreNapiAvailable) wasmKmAbort()
    error("Rust intrinsic abort triggered")
}

public actual fun kmSpinLoopHint() {
    if (kmCoreNapiAvailable) wasmKmSpinLoopHint()
}
public actual fun kmBlackBoxU64(value: ULong): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmBlackBoxU64(uLo32(value), uHi32(value))
        packU64(lo, wasmKmOut1())
    } else {
        value
    }
public actual fun kmUmulhU64(a: ULong, b: ULong): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmUmulhU64(uLo32(a), uHi32(a), uLo32(b), uHi32(b))
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.umulhU64(a, b)
    }
public actual fun kmSmulhI64(a: Long, b: Long): Long =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmSmulhI64(lo32(a), hi32(a), lo32(b), hi32(b))
        packI64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.smulhI64(a, b)
    }
public actual fun kmAtomicCxchgU128Pair(
    addr: Long,
    expLo: ULong,
    expHi: ULong,
    desLo: ULong,
    desHi: ULong,
): AtomicCxchgU128Outcome =
    if (kmCoreNapiAvailable) {
        val ok =
            wasmKmAtomicCxchgU128Pair(
                lo32(addr),
                hi32(addr),
                uLo32(expLo),
                uHi32(expLo),
                uLo32(expHi),
                uHi32(expHi),
                uLo32(desLo),
                uHi32(desLo),
                uLo32(desHi),
                uHi32(desHi),
            ) != 0
        AtomicCxchgU128Outcome(
            packU64(wasmKmOut0(), wasmKmOut1()),
            packU64(wasmKmOut2(), wasmKmOut3()),
            ok,
        )
    } else {
        KmLinearMemoryArena.atomicCxchgU128Pair(addr, expLo, expHi, desLo, desHi)
    }

public actual fun kmSimdFmaF32x4(dstAddr: Long, aAddr: Long, bAddr: Long, cAddr: Long) {
    if (kmCoreNapiAvailable) wasmKmSimdFmaF32x4(lo32(dstAddr), hi32(dstAddr), lo32(aAddr), hi32(aAddr), lo32(bAddr), hi32(bAddr), lo32(cAddr), hi32(cAddr)) else KmLinearMemoryArena.simdFmaF32x4(dstAddr, aAddr, bAddr, cAddr)
}
public actual fun kmSimdDotF32(aAddr: Long, bAddr: Long, len: Long): Float =
    if (kmCoreNapiAvailable) wasmKmSimdDotF32(lo32(aAddr), hi32(aAddr), lo32(bAddr), hi32(bAddr), lo32(len), hi32(len)).toFloat() else KmLinearMemoryArena.simdDotF32(aAddr, bAddr, len)

${CoreIntrinsicMathAndBitSpecs.renderWasmJsActuals().trimEnd()}
""".trimStart()
    }

    public fun renderKmCoreWasmWasiActualsKotlin(coreNamespace: String): String =
        """
package $coreNamespace.intrinsics

public actual fun kmLoadU8(addr: Long): UByte = KmLinearMemoryArena.loadU8(addr)
public actual fun kmLoadU16(addr: Long): UShort = KmLinearMemoryArena.loadU16(addr)
public actual fun kmLoadU32(addr: Long): UInt = KmLinearMemoryArena.loadU32(addr)
public actual fun kmLoadU64(addr: Long): ULong = KmLinearMemoryArena.loadU64(addr)
public actual fun kmLoadF32(addr: Long): Float = KmLinearMemoryArena.loadF32(addr)
public actual fun kmLoadF64(addr: Long): Double = KmLinearMemoryArena.loadF64(addr)

public actual fun kmStoreU8(addr: Long, value: UByte): Unit = KmLinearMemoryArena.storeU8(addr, value)
public actual fun kmStoreU16(addr: Long, value: UShort): Unit = KmLinearMemoryArena.storeU16(addr, value)
public actual fun kmStoreU32(addr: Long, value: UInt): Unit = KmLinearMemoryArena.storeU32(addr, value)
public actual fun kmStoreU64(addr: Long, value: ULong): Unit = KmLinearMemoryArena.storeU64(addr, value)
public actual fun kmStoreF32(addr: Long, value: Float): Unit = KmLinearMemoryArena.storeF32(addr, value)
public actual fun kmStoreF64(addr: Long, value: Double): Unit = KmLinearMemoryArena.storeF64(addr, value)

public actual fun kmVolatileLoadU8(addr: Long): UByte = KmLinearMemoryArena.loadU8(addr)
public actual fun kmVolatileLoadU16(addr: Long): UShort = KmLinearMemoryArena.loadU16(addr)
public actual fun kmVolatileLoadU32(addr: Long): UInt = KmLinearMemoryArena.loadU32(addr)
public actual fun kmVolatileLoadU64(addr: Long): ULong = KmLinearMemoryArena.loadU64(addr)

public actual fun kmVolatileStoreU8(addr: Long, value: UByte): Unit = KmLinearMemoryArena.storeU8(addr, value)
public actual fun kmVolatileStoreU16(addr: Long, value: UShort): Unit = KmLinearMemoryArena.storeU16(addr, value)
public actual fun kmVolatileStoreU32(addr: Long, value: UInt): Unit = KmLinearMemoryArena.storeU32(addr, value)
public actual fun kmVolatileStoreU64(addr: Long, value: ULong): Unit = KmLinearMemoryArena.storeU64(addr, value)

public actual fun kmCopyNonoverlapping(dst: Long, src: Long, bytes: Long): Unit =
    KmLinearMemoryArena.copyNonoverlapping(dst, src, bytes)
public actual fun kmCopy(dst: Long, src: Long, bytes: Long): Unit =
    KmLinearMemoryArena.copy(dst, src, bytes)
public actual fun kmWriteBytes(dst: Long, value: UByte, bytes: Long): Unit =
    KmLinearMemoryArena.writeBytes(dst, value, bytes)
public actual fun kmCompareBytes(left: Long, right: Long, bytes: Long): Int =
    KmLinearMemoryArena.compareBytes(left, right, bytes)

public actual fun kmAlloc(size: Long, align: Long): Long = KmLinearMemoryArena.alloc(size, align)
public actual fun kmAllocZeroed(size: Long, align: Long): Long = KmLinearMemoryArena.allocZeroed(size, align)
public actual fun kmRealloc(ptr: Long, oldSize: Long, align: Long, newSize: Long): Long =
    KmLinearMemoryArena.realloc(ptr, oldSize, align, newSize)
public actual fun kmDealloc(ptr: Long, size: Long, align: Long): Unit = KmLinearMemoryArena.dealloc(ptr, size, align)

public actual fun kmAtomicFence(ord: Int) {}
public actual fun kmAtomicSignalFence(ord: Int) {}
public actual fun kmAtomicLoadU8(addr: Long, ord: Int): UByte = KmLinearMemoryArena.loadU8(addr)
public actual fun kmAtomicLoadU16(addr: Long, ord: Int): UShort = KmLinearMemoryArena.loadU16(addr)
public actual fun kmAtomicLoadU32(addr: Long, ord: Int): UInt = KmLinearMemoryArena.loadU32(addr)
public actual fun kmAtomicLoadU64(addr: Long, ord: Int): ULong = KmLinearMemoryArena.loadU64(addr)
public actual fun kmAtomicStoreU8(addr: Long, value: UByte, ord: Int): Unit = KmLinearMemoryArena.storeU8(addr, value)
public actual fun kmAtomicStoreU16(addr: Long, value: UShort, ord: Int): Unit = KmLinearMemoryArena.storeU16(addr, value)
public actual fun kmAtomicStoreU32(addr: Long, value: UInt, ord: Int): Unit = KmLinearMemoryArena.storeU32(addr, value)
public actual fun kmAtomicStoreU64(addr: Long, value: ULong, ord: Int): Unit = KmLinearMemoryArena.storeU64(addr, value)
public actual fun kmAtomicXchgU8(addr: Long, value: UByte, ord: Int): UByte = KmLinearMemoryArena.atomicXchgU8(addr, value)
public actual fun kmAtomicXchgU16(addr: Long, value: UShort, ord: Int): UShort = KmLinearMemoryArena.atomicXchgU16(addr, value)
public actual fun kmAtomicXchgU32(addr: Long, value: UInt, ord: Int): UInt = KmLinearMemoryArena.atomicXchgU32(addr, value)
public actual fun kmAtomicXchgU64(addr: Long, value: ULong, ord: Int): ULong = KmLinearMemoryArena.atomicXchgU64(addr, value)
public actual fun kmAtomicCxchgU8(addr: Long, expected: UByte, desired: UByte, succ: Int, fail: Int): AtomicCxchgU8Outcome =
    KmLinearMemoryArena.atomicCxchgU8(addr, expected, desired)
public actual fun kmAtomicCxchgU16(addr: Long, expected: UShort, desired: UShort, succ: Int, fail: Int): AtomicCxchgU16Outcome =
    KmLinearMemoryArena.atomicCxchgU16(addr, expected, desired)
public actual fun kmAtomicCxchgU32(addr: Long, expected: UInt, desired: UInt, succ: Int, fail: Int): AtomicCxchgU32Outcome =
    KmLinearMemoryArena.atomicCxchgU32(addr, expected, desired)
public actual fun kmAtomicCxchgU64(addr: Long, expected: ULong, desired: ULong, succ: Int, fail: Int): AtomicCxchgU64Outcome =
    KmLinearMemoryArena.atomicCxchgU64(addr, expected, desired)
public actual fun kmAtomicRmwU8(addr: Long, value: UByte, op: Int, ord: Int): UByte =
    KmLinearMemoryArena.atomicRmwU8(addr, value, op)
public actual fun kmAtomicRmwU16(addr: Long, value: UShort, op: Int, ord: Int): UShort =
    KmLinearMemoryArena.atomicRmwU16(addr, value, op)
public actual fun kmAtomicRmwU32(addr: Long, value: UInt, op: Int, ord: Int): UInt =
    KmLinearMemoryArena.atomicRmwU32(addr, value, op)
public actual fun kmAtomicRmwU64(addr: Long, value: ULong, op: Int, ord: Int): ULong =
    KmLinearMemoryArena.atomicRmwU64(addr, value, op)

public actual fun kmCtpopU64(value: ULong): UInt = KmLinearMemoryArena.ctpopU64(value)
public actual fun kmCtlzU64(value: ULong): UInt = KmLinearMemoryArena.ctlzU64(value)
public actual fun kmCttzU64(value: ULong): UInt = KmLinearMemoryArena.cttzU64(value)
public actual fun kmBswapU16(value: UShort): UShort = KmLinearMemoryArena.bswapU16(value)
public actual fun kmBswapU32(value: UInt): UInt = KmLinearMemoryArena.bswapU32(value)
public actual fun kmBswapU64(value: ULong): ULong = KmLinearMemoryArena.bswapU64(value)
public actual fun kmBitreverseU64(value: ULong): ULong = KmLinearMemoryArena.bitreverseU64(value)
public actual fun kmFmaf32(a: Float, b: Float, c: Float): Float = KmLinearMemoryArena.fmaf32(a, b, c)
public actual fun kmFmaf64(a: Double, b: Double, c: Double): Double = KmLinearMemoryArena.fmaf64(a, b, c)
public actual fun kmSqrtf32(x: Float): Float = KmLinearMemoryArena.sqrtf32(x)
public actual fun kmSqrtf64(x: Double): Double = KmLinearMemoryArena.sqrtf64(x)
public actual fun kmAbort(): Nothing = error("Rust intrinsic abort triggered")

public actual fun kmSpinLoopHint() {}
public actual fun kmBlackBoxU64(value: ULong): ULong = value
public actual fun kmUmulhU64(a: ULong, b: ULong): ULong = KmLinearMemoryArena.umulhU64(a, b)
public actual fun kmSmulhI64(a: Long, b: Long): Long = KmLinearMemoryArena.smulhI64(a, b)
public actual fun kmAtomicCxchgU128Pair(
    addr: Long,
    expLo: ULong,
    expHi: ULong,
    desLo: ULong,
    desHi: ULong,
): AtomicCxchgU128Outcome = KmLinearMemoryArena.atomicCxchgU128Pair(addr, expLo, expHi, desLo, desHi)
public actual fun kmSimdFmaF32x4(dstAddr: Long, aAddr: Long, bAddr: Long, cAddr: Long): Unit =
    KmLinearMemoryArena.simdFmaF32x4(dstAddr, aAddr, bAddr, cAddr)
public actual fun kmSimdDotF32(aAddr: Long, bAddr: Long, len: Long): Float =
    KmLinearMemoryArena.simdDotF32(aAddr, bAddr, len)

${CoreIntrinsicMathAndBitSpecs.renderWasmWasiActuals().trimEnd()}
""".trimStart()
}

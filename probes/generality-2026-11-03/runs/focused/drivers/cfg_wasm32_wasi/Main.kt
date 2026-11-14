fun main() {
    val actual1 = listOf(probe.cfg_wasm32_wasi.plain.width().toString(), probe.cfg_wasm32_wasi.plain.little().toString(), probe.cfg_wasm32_wasi.plain.wasm().toString(), probe.cfg_wasm32_wasi.plain.msvc().toString(), probe.cfg_wasm32_wasi.plain.pc().toString(), probe.cfg_wasm32_wasi.plain.windows().toString(), probe.cfg_wasm32_wasi.plain.configured().toString(), probe.cfg_wasm32_wasi.plain.tautology().toString(), probe.cfg_wasm32_wasi.plain.unknown().toString(), probe.cfg_wasm32_wasi.plain.featureonly().toString(), probe.cfg_wasm32_wasi.plain.flagonly().toString(), probe.cfg_wasm32_wasi.plain.keyonly().toString()).joinToString(",")
    println("actual:probe.cfg_wasm32_wasi.plain=" + actual1)
    check(actual1 == "32,true,true,false,false,false,true,true,false,true,true,true")
    val actual4 = listOf(probe.cfg_wasm32_wasi.windows.width().toString(), probe.cfg_wasm32_wasi.windows.little().toString(), probe.cfg_wasm32_wasi.windows.wasm().toString(), probe.cfg_wasm32_wasi.windows.msvc().toString(), probe.cfg_wasm32_wasi.windows.pc().toString(), probe.cfg_wasm32_wasi.windows.windows().toString(), probe.cfg_wasm32_wasi.windows.configured().toString(), probe.cfg_wasm32_wasi.windows.tautology().toString(), probe.cfg_wasm32_wasi.windows.unknown().toString(), probe.cfg_wasm32_wasi.windows.featureonly().toString(), probe.cfg_wasm32_wasi.windows.flagonly().toString(), probe.cfg_wasm32_wasi.windows.keyonly().toString()).joinToString(",")
    println("actual:probe.cfg_wasm32_wasi.windows=" + actual4)
    check(actual4 == "32,true,true,false,false,false,true,true,false,true,true,true")
    println("32,true,true,false,false,false,true,true,false,true,true,true")
}

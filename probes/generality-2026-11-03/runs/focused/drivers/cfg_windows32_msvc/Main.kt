fun main() {
    val actual1 = listOf(probe.cfg_windows32_msvc.plain.width().toString(), probe.cfg_windows32_msvc.plain.little().toString(), probe.cfg_windows32_msvc.plain.wasm().toString(), probe.cfg_windows32_msvc.plain.msvc().toString(), probe.cfg_windows32_msvc.plain.pc().toString(), probe.cfg_windows32_msvc.plain.windows().toString(), probe.cfg_windows32_msvc.plain.configured().toString(), probe.cfg_windows32_msvc.plain.tautology().toString(), probe.cfg_windows32_msvc.plain.unknown().toString(), probe.cfg_windows32_msvc.plain.featureonly().toString(), probe.cfg_windows32_msvc.plain.flagonly().toString(), probe.cfg_windows32_msvc.plain.keyonly().toString()).joinToString(",")
    println("actual:probe.cfg_windows32_msvc.plain=" + actual1)
    check(actual1 == "32,true,false,true,true,true,true,true,false,true,true,true")
    val actual4 = listOf(probe.cfg_windows32_msvc.windows.width().toString(), probe.cfg_windows32_msvc.windows.little().toString(), probe.cfg_windows32_msvc.windows.wasm().toString(), probe.cfg_windows32_msvc.windows.msvc().toString(), probe.cfg_windows32_msvc.windows.pc().toString(), probe.cfg_windows32_msvc.windows.windows().toString(), probe.cfg_windows32_msvc.windows.configured().toString(), probe.cfg_windows32_msvc.windows.tautology().toString(), probe.cfg_windows32_msvc.windows.unknown().toString(), probe.cfg_windows32_msvc.windows.featureonly().toString(), probe.cfg_windows32_msvc.windows.flagonly().toString(), probe.cfg_windows32_msvc.windows.keyonly().toString()).joinToString(",")
    println("actual:probe.cfg_windows32_msvc.windows=" + actual4)
    check(actual4 == "32,true,false,true,true,true,true,true,false,true,true,true")
    println("32,true,false,true,true,true,true,true,false,true,true,true")
}

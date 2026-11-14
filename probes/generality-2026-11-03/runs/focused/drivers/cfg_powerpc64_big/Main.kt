fun main() {
    val actual1 = listOf(probe.cfg_powerpc64_big.plain.width().toString(), probe.cfg_powerpc64_big.plain.little().toString(), probe.cfg_powerpc64_big.plain.wasm().toString(), probe.cfg_powerpc64_big.plain.msvc().toString(), probe.cfg_powerpc64_big.plain.pc().toString(), probe.cfg_powerpc64_big.plain.windows().toString(), probe.cfg_powerpc64_big.plain.configured().toString(), probe.cfg_powerpc64_big.plain.tautology().toString(), probe.cfg_powerpc64_big.plain.unknown().toString(), probe.cfg_powerpc64_big.plain.featureonly().toString(), probe.cfg_powerpc64_big.plain.flagonly().toString(), probe.cfg_powerpc64_big.plain.keyonly().toString()).joinToString(",")
    println("actual:probe.cfg_powerpc64_big.plain=" + actual1)
    check(actual1 == "64,false,false,false,false,false,true,true,false,true,true,true")
    val actual4 = listOf(probe.cfg_powerpc64_big.windows.width().toString(), probe.cfg_powerpc64_big.windows.little().toString(), probe.cfg_powerpc64_big.windows.wasm().toString(), probe.cfg_powerpc64_big.windows.msvc().toString(), probe.cfg_powerpc64_big.windows.pc().toString(), probe.cfg_powerpc64_big.windows.windows().toString(), probe.cfg_powerpc64_big.windows.configured().toString(), probe.cfg_powerpc64_big.windows.tautology().toString(), probe.cfg_powerpc64_big.windows.unknown().toString(), probe.cfg_powerpc64_big.windows.featureonly().toString(), probe.cfg_powerpc64_big.windows.flagonly().toString(), probe.cfg_powerpc64_big.windows.keyonly().toString()).joinToString(",")
    println("actual:probe.cfg_powerpc64_big.windows=" + actual4)
    check(actual4 == "64,false,false,false,false,false,true,true,false,true,true,true")
    println("64,false,false,false,false,false,true,true,false,true,true,true")
}

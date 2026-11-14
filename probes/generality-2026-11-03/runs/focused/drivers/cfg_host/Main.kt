fun main() {
    val actual1 = listOf(probe.cfg_host.plain.width().toString(), probe.cfg_host.plain.little().toString(), probe.cfg_host.plain.wasm().toString(), probe.cfg_host.plain.msvc().toString(), probe.cfg_host.plain.pc().toString(), probe.cfg_host.plain.windows().toString(), probe.cfg_host.plain.configured().toString(), probe.cfg_host.plain.tautology().toString(), probe.cfg_host.plain.unknown().toString(), probe.cfg_host.plain.featureonly().toString(), probe.cfg_host.plain.flagonly().toString(), probe.cfg_host.plain.keyonly().toString()).joinToString(",")
    println("actual:probe.cfg_host.plain=" + actual1)
    check(actual1 == "64,true,false,false,false,false,true,true,false,true,true,true")
    val actual4 = listOf(probe.cfg_host.windows.width().toString(), probe.cfg_host.windows.little().toString(), probe.cfg_host.windows.wasm().toString(), probe.cfg_host.windows.msvc().toString(), probe.cfg_host.windows.pc().toString(), probe.cfg_host.windows.windows().toString(), probe.cfg_host.windows.configured().toString(), probe.cfg_host.windows.tautology().toString(), probe.cfg_host.windows.unknown().toString(), probe.cfg_host.windows.featureonly().toString(), probe.cfg_host.windows.flagonly().toString(), probe.cfg_host.windows.keyonly().toString()).joinToString(",")
    println("actual:probe.cfg_host.windows=" + actual4)
    check(actual4 == "64,true,false,false,false,false,true,true,false,true,true,true")
    println("64,true,false,false,false,false,true,true,false,true,true,true")
}

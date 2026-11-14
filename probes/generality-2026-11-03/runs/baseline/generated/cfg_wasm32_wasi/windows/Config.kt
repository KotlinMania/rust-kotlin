package probe.cfg_wasm32_wasi.windows

public fun width(): UInt {
    return 32
}

public fun little(): Boolean {
    return true
}

public fun wasm(): Boolean {
    return true
}

public fun msvc(): Boolean {
    return false
}

public fun pc(): Boolean {
    return false
}

public fun windows(): Boolean {
    return false
}

public fun configured(): Boolean {
    return false
}

public fun tautology(): Boolean {
    return true
}

public fun unknown(): Boolean {
    return false
}

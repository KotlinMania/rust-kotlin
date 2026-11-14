package probe.alias_renamed

public typealias WireRepr = Short

public fun negative(): WireRepr {
    return -7
}

public fun probe(): Boolean {
    return negative() == -7
}

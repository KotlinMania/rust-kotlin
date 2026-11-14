package probe.alias_named

public typealias CEnumRepr = Short

public fun negative(): CEnumRepr {
    return -7
}

public fun probe(): Boolean {
    return negative() == -7
}

package probe.alias_target_gnu

public typealias CEnumRepr = UInt

public fun probe(): Boolean {
    val value: CEnumRepr = 7u
    return value == 7u
}

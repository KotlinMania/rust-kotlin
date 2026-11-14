package probe.alias_target_msvc

public typealias CEnumRepr = Int

public fun probe(): Boolean {
    val value: CEnumRepr = 7
    return value == 7
}

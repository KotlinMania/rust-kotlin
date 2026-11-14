package probe.guid_layout_values
fun main() { check(head() == 0x11223344u); check(second() == 0x5566u.toUShort()); check(third() == 0x7788u.toUShort()); check(firstbyte() == 0x99u.toUByte()); check(lastbyte() == 0u.toUByte()); println("true") }

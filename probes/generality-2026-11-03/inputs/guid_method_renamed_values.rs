pub struct GUID { pub marker: u32 }
impl GUID {
    pub fn from_bits(_value: u128) -> Self { Self { marker: 73 } }
}
pub fn probe() -> u32 {
    let number: u128 = 1u128 << 96;
    GUID::from_bits(number).marker
}

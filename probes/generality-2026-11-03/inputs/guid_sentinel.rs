pub struct GUID { pub marker: u32 }
impl GUID {
    pub fn from_u128(_value: u128) -> Self { Self { marker: 73 } }
}
pub fn probe() -> bool {
    let number: u128 = 1u128 << 96;
    GUID::from_u128(number).marker == 73
}

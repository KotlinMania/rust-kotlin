pub struct RecordId { pub marker: u32 }
impl RecordId {
    pub fn from_u128(_value: u128) -> Self { Self { marker: 73 } }
}
pub fn probe() -> u32 {
    let number: u128 = 1u128 << 96;
    RecordId::from_u128(number).marker
}

pub struct GUID {
    pub data1: u32,
    pub data2: u16,
    pub data3: u16,
    pub data4: [u8; 8],
}
impl GUID {
    pub fn from_u128(uuid: u128) -> Self {
        Self {
            data1: (uuid >> 96) as u32,
            data2: (uuid >> 80) as u16,
            data3: (uuid >> 64) as u16,
            data4: (uuid as u64).to_be_bytes(),
        }
    }
}
pub fn identifier() -> GUID {
    let number: u128 = 0x112233445566778899aabbccddeeff00u128;
    GUID::from_u128(number)
}
pub fn head() -> u32 { identifier().data1 }
pub fn second() -> u16 { identifier().data2 }
pub fn third() -> u16 { identifier().data3 }
pub fn firstbyte() -> u8 { identifier().data4[0] }
pub fn lastbyte() -> u8 { identifier().data4[7] }

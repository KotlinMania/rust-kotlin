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
pub fn probe() -> bool {
    let number: u128 = 0x112233445566778899aabbccddeeff00u128;
    let id = GUID::from_u128(number);
    id.data1 == 0x11223344 && id.data2 == 0x5566 && id.data3 == 0x7788
        && id.data4[0] == 0x99 && id.data4[7] == 0x00
}

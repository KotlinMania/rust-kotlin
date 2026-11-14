#[path = "../../../../inputs/guid_layout_values.rs"]
mod input;
fn main() { assert_eq!(input::head(), 0x11223344u32); assert_eq!(input::second(), 0x5566u16); assert_eq!(input::third(), 0x7788u16); assert_eq!(input::firstbyte(), 0x99u8); assert_eq!(input::lastbyte(), 0u8); println!("true"); }

#[path = "../../../../inputs/guid_owner_renamed_values.rs"]
mod input;
fn main() { assert_eq!(input::probe(), 73u32); println!("true"); }

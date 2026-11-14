#[path = "../../../../inputs/guid_sentinel_values.rs"]
mod input;
fn main() { assert_eq!(input::probe(), 73u32); println!("true"); }

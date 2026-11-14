#[cfg(target_env = "msvc")]
pub type CEnumRepr = i32;
#[cfg(not(target_env = "msvc"))]
pub type CEnumRepr = u32;
pub fn probe() -> bool { let value: CEnumRepr = 7; value == 7 }

#[cfg(target_pointer_width = "32")]
pub fn width() -> u32 { 32u32 }
#[cfg(target_pointer_width = "64")]
pub fn width() -> u32 { 64u32 }
pub fn little() -> bool { cfg!(target_endian = "little") }
pub fn wasm() -> bool { cfg!(target_arch = "wasm32") }
pub fn msvc() -> bool { cfg!(target_env = "msvc") }
pub fn pc() -> bool { cfg!(target_vendor = "pc") }
pub fn windows() -> bool { cfg!(target_family = "windows") }
pub fn configured() -> bool { cfg!(all(feature = "tracing", probe_flag, flavor = "mint")) }
pub fn tautology() -> bool { cfg!(all()) }
pub fn unknown() -> bool { cfg!(unlisted_probe) }
#[cfg(unlisted_probe)]
pub fn openmarker() -> bool { true }
pub fn featureonly() -> bool { cfg!(feature = "tracing") }
pub fn flagonly() -> bool { cfg!(probe_flag) }
pub fn keyonly() -> bool { cfg!(flavor = "mint") }

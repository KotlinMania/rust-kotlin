pub type WireRepr = i16;
pub fn negative() -> WireRepr { -7 }
pub fn probe() -> bool { negative() == -7 }

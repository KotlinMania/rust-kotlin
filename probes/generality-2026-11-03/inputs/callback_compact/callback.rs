pub type Completion = Option<unsafe extern "C" fn(i32) -> i32>;
pub struct Slot { pub callback: Completion }
pub unsafe extern "C" fn increment(value: i32) -> i32 { value + 1 }
pub fn apply(callback: Completion, value: i32) -> i32 {
    match callback {
        Some(function) => unsafe { function(value) },
        None => value,
    }
}
pub fn probe() -> bool {
    let slot = Slot { callback: None };
    slot.callback.is_none() && apply(None, 9) == 9 && apply(Some(increment), 9) == 10
}

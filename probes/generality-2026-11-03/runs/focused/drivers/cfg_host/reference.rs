#[path = "../../../../inputs/cfg_explicit_literals/plain/config.rs"]
mod input;
fn main() { println!("{},{},{},{},{},{},{},{},{},{},{},{}", input::width(), input::little(), input::wasm(), input::msvc(), input::pc(), input::windows(), input::configured(), input::tautology(), input::unknown(), input::featureonly(), input::flagonly(), input::keyonly()); }

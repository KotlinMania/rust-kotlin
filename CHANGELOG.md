<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Rust-transpiler Changelog

## [Unreleased]

## [1.1.0] - 2026-11-01

### Added
- Strict 1-Rust-file-to-1-Kotlin-file AST transpilation via `proc-macro2-kotlin` and `syn-kotlin`.
- Crate-local `macro_rules!` discovery and expansion (`s!`, `s_no_extra_traits!`, `s_paren!`, `cfg_if!`) in both KMP CLI and IntelliJ IDEA VFS actions.
- Companion `.def`, `.h`, `.c`/`.cpp`, and `nativeMain` actuals generation for `extern "C"` and inline C blocks.

### Fixed
- Resolved Kotlin `import` casing for lowercase Rust/C typedefs (`off_t` -> `OffT`), `SCREAMING_SNAKE_CASE` constants, and `c_void` pointer imports.
- Preserved active editor `VirtualFile` crate macro context in the IntelliJ Tool Window transpiler action.

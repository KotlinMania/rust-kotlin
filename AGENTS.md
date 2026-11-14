# rust-kotlin Agent & Architecture Contract

`rust-kotlin` (`rust-transpiler`) is KotlinMania's deterministic, purely language-level Rust-to-Kotlin mechanical translator modeled on IntelliJ's J2K (`NewJavaToKotlinConverter`), packaged as a JetBrains IntelliJ IDEA plugin.

## Core Invariants

1. **KMP Native CLI + JetBrains IntelliJ Platform Plugin Structure**:
   - Shared translator logic and AST emission live in KMP `src/common/commonMain` (with tests in `src/common/commonTest`), standalone native CLI entrypoints live in `src/native/nativeMain`, and the JetBrains IntelliJ IDEA (`2025.3.6.1`) plugin lives in `ide-plugin/`.

2. **Strict `1 Rust File → 1 Kotlin File` Language-Level Boundary (Zero Cross-File Scope/Type Resolution)**:
   - Every regular Rust `.rs` source file is parsed via our own KotlinMania libraries (`proc-macro2-kotlin` for lexical `TokenStream` / spans, `syn-kotlin` for full Rust AST `File` / `Item` / `Stmt` / `Expr` / `Type` / `Pat`, and `starlark-kotlin`'s `FormatParser` for format string templates) and deterministically emitted as one `.kt` file.
   - Focus strictly on **Rust-to-Kotlin language differences** within a single file using consistent deterministic naming and type mappings. **Never** build cross-file type/trait symbol tables, cross-file `use`/`mod` scope resolvers, cross-file `impl` mergers, or automatic `lib.rs`/`mod.rs` multi-file splitters; leave cross-file type checking to `kotlinc` and IntelliJ.
   - **Explicit C/FFI Companion Exception**: Extracting inline C / FFI declarations into companion `.def`, `.c`/`.cpp`, and `nativeMain` interop bridge files is explicitly permitted and excluded from the single-output-file rule because Kotlin cannot inline C source.
   - `lib.rs` and `mod.rs` are crate/module roots and must **never** be emitted as `Lib.kt` or `Mod.kt` (nor carry `// port-lint: source .../lib.rs` or `.../mod.rs`).

3. **Single Code Path Only — Zero Fallbacks, Zero Mode Gates**:
   - **Do NOT create fallbacks or gates that allow two different ways of working.**
   - **Never** write code that tries one direction, fails (or catches an exception), and falls back to another method (such as emitting raw unparsed Rust text or switching between span-rewriting and AST emission).
   - **Do NOT** add CLI flags, profile switches, or environment variables that toggle between different translation engines or code paths.
   - If you ever need to test a different approach, do it on a separate git branch — **never** on `main` branch code.
   - Actively remove any legacy paths that attempt one method, fail, and try another.

4. **Build & Test**:
   - Always verify with `./gradlew build` and `./gradlew test`.
   - Validate generated Kotlin files across `kotlinmania/*-kotlin` crate ports using `tools/ast_distance --deep`.

## Platform & Target Emission Invariants

1. **`sealed class` (Never `sealed interface`) for Exported Sum Types**:
   - Swift Export's `_SealedType` code generator emits Swift protocols for `sealed interface` whose nested `data object` / `data class` wrappers fail Swift type checking in `_SealedType.value` without explicit casts, whereas `sealed class` exports cleanly as an `open class` hierarchy. Always emit `sealed class` for Rust `enum`s and synthesized bound sum types.

2. **Collection Interfaces (`List`, `MutableList`, `Map`, `MutableMap`) Instead of Concrete `ArrayList` / `HashMap` on Public Signatures**:
   - Exposing `ArrayList<T>` in public/exported Kotlin signatures forces Swift Export to generate a `KotlinStdlib` wrapper for `kotlin.collections.ArrayList` whose `set(index:element:)` calls `_set(index:element:)` without discarding its return value, failing `xcodebuild` with `error: result of call to '_set(index:element:)' is unused`. Always emit collection interface types on public return and parameter signatures.

3. **Unpaired UTF-16 Surrogates in Constant `String` Literals (Kotlin/JS Incremental Klib Corruption)**:
   - Kotlin/JS's incremental klib serializer stores constant `String` literals in Protobuf `string` fields (UTF-8), which silently replaces unpaired UTF-16 surrogates (`\uD800`..`\uDFFF`) with `'?'` (`\u003F`) on incremental rebuilds. Moreover, Kotlin's `IrInterpreter` constant-folds `'c'.toString()` back into a compile-time `IrConst<String>`. Never emit constant `String` literals or `'c'.toString()` for lone surrogates; always construct unpaired surrogate strings via `charArrayOf(...).concatToString()`.


# rust-kotlin

[![GitHub link](https://img.shields.io/badge/GitHub-KotlinMania%2Frust--kotlin-blue.svg)](https://github.com/KotlinMania/rust-kotlin)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kotlinmania/rust-kotlin)](https://central.sonatype.com/artifact/io.github.kotlinmania/rust-kotlin)
[![Build status](https://img.shields.io/github/actions/workflow/status/KotlinMania/rust-kotlin/ci.yml?branch=main)](https://github.com/KotlinMania/rust-kotlin/actions)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF.svg)](https://kotlinlang.org/docs/multiplatform.html)
[![Platforms](https://img.shields.io/badge/platforms-macOS%20arm64%20%7C%20JVM-lightgrey.svg)](#supported-targets)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20IDEA-2025.3-000000.svg?logo=intellijidea&logoColor=white)](#intellij-idea-plugin)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A deterministic Rust-to-Kotlin source translator. One `.rs` file in, one `.kt` file out.

`rust-kotlin` parses Rust with [`proc-macro2-kotlin`](https://github.com/KotlinMania/proc-macro2-kotlin)
and [`syn-kotlin`](https://github.com/KotlinMania/syn-kotlin) (Kotlin ports of the Rust
crates), walks the syntax tree, and emits Kotlin Multiplatform source. There is no
language model in the loop: the same input and flags always produce the same output.

It ships as two front ends over one translation core:

- a **native command-line tool** (`rust-kotlin.kexe`), with no JVM at runtime
- an **IntelliJ IDEA plugin** that adds a *Convert Rust File to Kotlin File* action

It is the generator behind the KotlinMania Rust ports, including
[`rust-lang-kotlin`](https://github.com/KotlinMania/rust-lang-kotlin).

---

## What it does

- Translates items, expressions, patterns, generics, traits and impls into idiomatic
  Kotlin naming (`dist_sq` becomes `distSq`, `off_t` becomes `OffT`).
- Lowers `Option<T>` to nullable types and `match` to `when`, and maps Rust primitives
  and slices onto Kotlin types.
- Computes struct layouts and emits a `TypeLayout<T>` for each struct so generic code
  can read and write values at native addresses.
- Discovers and expands crate-local `macro_rules!` macros.
- Evaluates `cfg` predicates against a configurable target profile (arch, OS, env,
  pointer width, endianness, features).
- Lowers `extern "C"` blocks and compiler intrinsics to companion `.def`, `.h`,
  `.c`/`.cpp`, JNI and N-API sources.
- Translates inline `#[cfg(test)]` modules into a separate test output tree.
- Writes a `// port-lint: source <path>` provenance header on each generated file.

Generated code targets the Rust standard library port in
[`rust-lang-kotlin`](https://github.com/KotlinMania/rust-lang-kotlin) for types that
have no direct Kotlin equivalent.

---

## Example

Input, `shapes.rs`:

```rust
/// A point in the plane.
pub struct Point {
    pub x: f64,
    pub y: f64,
}

impl Point {
    pub fn new(x: f64, y: f64) -> Self {
        Point { x, y }
    }

    pub fn dist_sq(&self, other: &Point) -> f64 {
        let dx = self.x - other.x;
        let dy = self.y - other.y;
        dx * dx + dy * dy
    }
}

pub fn largest(values: &[i32]) -> Option<i32> {
    let mut best: Option<i32> = None;
    for v in values {
        match best {
            Some(b) if b >= *v => {}
            _ => best = Some(*v),
        }
    }
    best
}
```

```bash
rust-kotlin translate --stdout -p io.example.shapes shapes.rs
```

Output (the generated `typeLayout()` companion member is elided here):

```kotlin
package io.example.shapes

/**
 * A point in the plane.
 */
public open class Point(public var x: Double, public var y: Double) {
    public open fun distSq(other: Point): Double {
        val dx = this.x - other.x
        val dy = this.y - other.y
        return dx * dx + dy * dy
    }

    public companion object {
        public fun new(x: Double, y: Double): Point {
            return Point(x = x, y = y)
        }
    }
}

public fun largest(values: List<Int>): Int? {
    var best: Int? = null
    for (v in values) {
        when {
            best != null && best >= v -> {
                val b = best
            }
            else -> best = v
        }
    }
    return best
}
```

---

## Command-line usage

```
rust-kotlin inspect [options] <input.rs | dir...>
rust-kotlin translate [options] <input.rs> <output.kt>
rust-kotlin [translate] [options] <input.rs | dir...>
rust-kotlin --help | --version
```

### Common invocations

Translate one file to an explicit output:

```bash
rust-kotlin translate src/shapes.rs out/Shapes.kt
```

Translate a crate recursively into a Kotlin source root:

```bash
rust-kotlin translate -r --split-module-roots -n io.github.kotlinmania.mycrate --source-root tmp/mycrate -o src/commonMain/kotlin tmp/mycrate/src
```

See what would be written without touching disk:

```bash
rust-kotlin translate -r --dry-run -o src/commonMain/kotlin tmp/mycrate/src
```

List the top-level items the parser sees:

```bash
rust-kotlin inspect src/shapes.rs
```

Existing `.kt` targets are never overwritten unless you pass `--force`; use
`--skip-existing` to leave them and continue.

### Options

| Option | Meaning |
|---|---|
| `-r`, `--recursive` | Recurse into directories (skips `lib.rs` and `mod.rs`) |
| `-o`, `--output <file.kt\|dir>` | Target file (single input) or output root directory |
| `--in-place` | Write a sibling `<PascalCase>.kt` next to each input |
| `--stdout` | Print the Kotlin source instead of writing files |
| `--dry-run` | Print planned `input -> output` actions only |
| `-f`, `--force`, `--overwrite` | Overwrite existing `.kt` files |
| `--skip-existing` | Skip targets that already exist |
| `-g`, `--gradle-properties <file>` | `gradle.properties` used to infer group and namespace |
| `--package-properties <file>` | `package.properties` with package and crate overrides |
| `-p`, `--package <pkg>` | Explicit Kotlin package (sub-packages appended in recursive mode) |
| `-n`, `--namespace <ns>` | Kotlin namespace of the crate root |
| `--group <group>` | Project group, for example `io.github.kotlinmania` |
| `-c`, `--crate-namespace <crate>=<ns>` | Map an external crate to a Kotlin namespace (repeatable) |
| `--source-root <dir>` | Base directory for relative `port-lint` paths |
| `--test-output <dir>` | Output root for inline `#[cfg(test)]` modules |
| `--port-lint <mode>` | Header directive: `source` (default), `tests`, or `none` |
| `--raw-abi` | Keep raw C / Win32 ABI identifiers |
| `--final-structs` | Emit final classes for structs and unions |
| `--split-module-roots` | Split `mod.rs` / `lib.rs` implementation into focused files |
| `--target-pointer-width <n>` | `16`, `32` or `64` (default `64`) |
| `--target-endian <e>` | `little` (default) or `big` |
| `--target-arch`, `--target-os`, `--target-env`, `--target-vendor`, `--target-family` | Target profile for `cfg` predicates |
| `--feature <name>` | Enable a Cargo feature (repeatable) |
| `--cfg <spec>` | Enable a custom `cfg` flag or `key=value` (repeatable) |
| `--cfg-closed-world` | Treat unspecified `cfg` predicates as false |
| `--scalar-char` | Translate `char` to `RustChar` (32-bit scalar) instead of `Char` |
| `--timings` | Report lexer, parser and emitter timings |
| `-q`, `--quiet` | Suppress progress lines |
| `-v`, `--version`, `-h`, `--help` | Version and help |

Exit status is `0` on success and `2` on a usage or translation error.

---

## IntelliJ IDEA plugin

The `ide-plugin` module wraps the same translation core for IntelliJ IDEA 2025.3.

- **Convert Rust File to Kotlin File**: available from the *Code* menu, the Project
  view context menu and the editor context menu (search abbreviation `r2k`). Works on a
  single `.rs` file or recursively on a directory, with an overwrite prompt per file.
- **Rust Transpiler tool window**: pick a `gradle.properties` for package and group
  inference, then translate the active editor file.

Run a sandbox IDE with the plugin loaded:

```bash
./gradlew :ide-plugin:runIde
```

Build the distributable plugin ZIP:

```bash
./gradlew :ide-plugin:buildPlugin
```

---

## Building

Requirements: JDK 21 and, for the native CLI, macOS on Apple Silicon with Xcode. The
Gradle wrapper (9.8.2) is checked in.

Build the native CLI:

```bash
./gradlew linkReleaseExecutableMacosArm64
```

The binary is written to `build/bin/macosArm64/releaseExecutable/rust-kotlin.kexe`.

Run the test suites:

```bash
./gradlew test
```

Smoke-test the built binary in place:

```bash
python3 tools/check-native-cli.py build/cli-check
```

### Supported targets

| Target | Role |
|---|---|
| `macosArm64` | Native CLI executable and native tests |
| `jvm` | Library target and host for the IntelliJ plugin |

Other Kotlin Multiplatform targets are declared in the build but switched off in
`gradle.properties` (`project.target.*`).

### Dependencies

All dependencies are KotlinMania ports published under `io.github.kotlinmania`:
`proc-macro2-kotlin`, `syn-kotlin`, `quote-kotlin`, `rust-lang-kotlin`, `km-io`,
`libc-kotlin`, `serde-json-kotlin`, `anyhow-kotlin`, `num-bigint-kotlin`, `rand-kotlin`
and `windows-sys-kotlin`. Versions are pinned in
[gradle/libs.versions.toml](gradle/libs.versions.toml). `maven.properties` controls
whether Maven Local is consulted first.

---

## Status

Project version `1.1.0`. No release has been published to Maven Central or JetBrains
Marketplace; build from source. The `--version` string printed by the CLI is hardcoded
separately and currently reads `0.1.0`.

Output is a mechanical first translation. It is designed to be compiled, measured
against upstream and corrected, not to be shipped unread.

GitHub Actions workflows are manual-only: `ci.yml` runs on `workflow_dispatch`, the
per-platform workflows are reusable, and `publish.yml` runs on a published release.

---

## Repository layout

```
src/common/commonMain/   translation core (parser front end, emitters, lowering)
src/common/commonTest/   translation tests
src/native/              CLI entry point, cinterop, POSIX and Windows IO
src/jvm/                 JVM IO and JNI support
src/js/                  JS / Wasm IO and N-API support
ide-plugin/              IntelliJ IDEA plugin
probes/                  recorded translation probes and evidence
third_party/             provenance and licenses for adapted upstream sources
verification/            build and publication verification notes
tools/                   CLI smoke test
```

Related documents: [CHANGELOG.md](CHANGELOG.md), [AGENTS.md](AGENTS.md),
[SWIFT.md](SWIFT.md), [verification/README.md](verification/README.md).

---

## Maintainer

Sydney Renee, The Solace Project
GitHub: [@sydneyrenee](https://github.com/sydneyrenee)
Email: [sydney@solace.ofharmony.ai](mailto:sydney@solace.ofharmony.ai)

Issues and pull requests are welcome at
[KotlinMania/rust-kotlin](https://github.com/KotlinMania/rust-kotlin/issues).

---

## License

[MIT](LICENSE). Adapted upstream sources keep their own licenses; see
[third_party/](third_party/).

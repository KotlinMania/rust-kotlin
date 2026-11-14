# PROBE results — current translator generation is broader, with two remaining failures

Source revision: `81b949dd74933c0161c5645636249c802a55f8c0` on
`automation/rust-kotlin-transpiler-fixes`. Native CLI SHA-256:
`c52134a64b7e691fde00ea7124d1bcc21f70334fb9a96f42ef543f8f4a86f046`.
The source and CLI stayed unchanged during both runs; each shared-index check was empty.
Iris's full production build is separate evidence and was not rerun by this probe.

| Stage | Baseline | Focused controls |
|---|---:|---:|
| Generation invocations | 16/16 exit 0 | 11/11 exit 0 |
| Kotlin compilation | 5/15 pass; 10 fail | 10/10 pass |
| Kotlin execution | 5/5 attempted pass; 10 blocked by compilation | 6/10 pass; 4 assertions fail |
| Rust host compilation and execution | 12/12 pass both stages | 7/7 pass both stages |
| Rust cross-target cfg metadata | 4/4 queried successfully | 4/4 queried successfully |

All command arrays, outputs, exit codes and timestamps are in each run's commands.json
and logs/. Generated Kotlin is unmodified. Run-level results and before/after source
hashes are saved alongside them. Harness snapshots preserve the executed versions.

## What passed

- **Structural GUID/u128 behavior:** focused raw-ABI GUID with a sentinel body and a
  nonliteral u128 argument returns marker 73. Renaming the owner to RecordId or the
  method to from_bits preserves that result. The familiar layout example retains
  data1/data2/data3 and the first/last data4 byte for
  0x112233445566778899aabbccddeeff00. All four compile and execute; typed Rust drivers
  independently assert the same values. Assertions live in separate drivers to isolate
  the comparison-literal issue below. This supports removal of the former name/body
  substitution for these examples; it does not certify all u128 arithmetic.
- **Alias naming:** CEnumRepr=i16 and WireRepr=i16 both emit Short and return -7 under
  compiled runtime assertions. Separate msvc/gnu profile generations emit Int/UInt
  according to the conditional alias RHS and both execute. The Rust references for
  those two profile cases run on the host, not Windows or GNU cross-target binaries.
- **Callback token spacing:** compact, spaced and commented fn syntax all emit nullable
  CompletionFn aliases, compile and execute None and Some callback paths. Normalized
  generated bodies agree. This is the single-file CLI path in raw ABI mode; native ABI
  calls, cross-file metadata resolution and an isolated scanner unit were not tested.
- **cfg target components:** focused emitted runtime vectors match Rust target metadata
  for 32/64-bit width, little/big endian, wasm32 architecture, msvc environment, pc
  vendor and windows family across host Darwin, wasm32-wasip1, i686 MSVC, and powerpc64
  GNU profiles. Feature-only and bare custom-flag predicates also match. all() is true;
  an unspecified runtime flag is false. Explicit profiles generate identical bodies
  under plain/ and windows/ directories. Both packages compile; runtime reaches plain/
  and stops at the key/value assertion before windows/. Open-world preservation and
  closed-world omission of an unknown attributed declaration pass generation checks;
  the open-world files were not compiled or executed separately.

## Current defects observed

1. **Custom cfg key/value is ignored at runtime.** `--cfg flavor="mint"` is accepted,
   but `cfg!(flavor = "mint")` emits false. The combined all(feature, flag, key/value)
   is also false; feature-only and flag-only are true. All four focused profile files
   compile, then fail the runtime oracle. The host Rust reference returns true for
   these enabled predicates. CLI parsing and evaluator source corroborate this gap;
   see evidence/source-review.md. Cross-target expectations use rustc --print cfg plus
   the explicit enabled feature/flag/key, not cross-target Rust execution.
2. **Inferred numeric literals can produce invalid Kotlin.** Baseline valid Rust uses
   unsuffixed literals. GUID/member comparisons produce UInt/UShort/UByte == Int;
   Short alias results compare with Int -7; cfg width functions return Int 32/64 from
   a UInt signature. Kotlin rejects these ten cases. The renamed controls fail the
   same way, so this is broader than the old GUID/CEnumRepr naming shortcuts. Rust
   compiles and executes every baseline reference. No runtime pass is claimed for
   those ten Kotlin cases. Focused controls leave all baseline output intact and use
   value-returning functions/typed drivers or explicitly suffixed width literals.

## Boundaries and reproduction

This is a small local probe, not a production refactor or generality certification.
Twenty authored Rust files, 25 primary case configurations and two open-world
generation-only checks were exercised. No generated Kotlin was patched. No production
sources, scanner, shared Gradle outputs, other-owner changes, running jobs, desktop
cursor or process inventory were modified. No CI, push, PR, merge, tag, release or
publication was performed. Binary/class artifacts remain visibly in artifacts/ and
are ignored; all inputs, generated sources, drivers, logs and results are checkpointed.

Reproduce after coordinating a fresh idle window with Iris, using distinct run names:

    python3 probes/generality-2026-11-03/run_probe.py --run-name rerun-baseline
    python3 probes/generality-2026-11-03/run_probe.py --suite focused --run-name rerun-focused

Both suites intentionally exit 1 while the documented observed failures remain.
The harness refuses a changed production source revision/CLI hash, an occupied shared index,
foreign dirty files, or an existing run directory. It uses cached Kotlin/JVM 2.4.21,
JDK 21 and installed rustc; exact paths, versions and compiler-jar hashes are recorded.
No translator source rebuild, tool install or network request is part of reproduction.
Kotlin runs on the host JVM. Cross-target Rust binaries, pointer width 16, direct
OS-predicate assertions, native FFI ABI, qualified aliases, and broad u128/I128 edge
cases are outside this probe's coverage.

Probe-only descendant commits of the recorded production revision are accepted for reruns; production-path changes are rejected.

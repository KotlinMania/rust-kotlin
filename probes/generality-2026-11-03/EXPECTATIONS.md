# Expectations set before execution

- GUID sentinel: from_u128 must preserve a body returning marker 73. Owner rename and
  method rename controls must give the same result, including a nonliteral u128 call.
- GUID layout positive control: shifts/casts/to_be_bytes must retain the expected field
  layout of 0x112233445566778899aabbccddeeff00. These values fit Rust u128 exactly.
- CEnumRepr = i16 and WireRepr = i16 must preserve a negative value and Short width.
  Names must not force UInt. The conditional alias follows the declared cfg-selected RHS.
- cfg: pointer-width declarations, endian/arch/env/vendor/family predicates and all()
  must match explicit target metadata. Enabled feature/custom flag/key must be true;
  unspecified custom flags are false at runtime. Identical source under plain/ and
  windows/ must agree under the same explicit target profile. Closed-world attributes
  omit an unspecified custom predicate; open-world preserves it by documented policy.
- Callback compact fn(, spaced fn (, and commented fn /*...*/ ( are equivalent valid
  Rust. The nullable callback alias and field must survive generation and compilation;
  None and Some invocation should agree with the Rust control. Raw ABI mode exercises
  the token scanner too, but this is not an isolated scanner unit test or native ABI test.
- Every Rust source must compile/run independently on the host. Cross-target Rust cfg
  metadata is read with rustc --print cfg, without claiming target compilation/execution.
- A Kotlin compile/runtime failure is an observed probe failure, even if generation exits 0.

## Focused controls added after the baseline

The baseline retained valid Rust inferred literals and exposed Kotlin literal typing
failures. The focused suite preserves those original failures and leaves generated code
untouched. New value-returning GUID/alias inputs move assertions to separately authored,
typed Rust/Kotlin drivers. All focused GUID cases use --raw-abi, preserving the exact
GUID identifier that the former name guard recognized. cfg keeps the same predicates,
uses explicit u32 return literals, and adds feature-only/flag-only/key-value-only checks
to distinguish configuration evaluation from literal typing. This is a distinct suite,
not a repair of baseline output. Run:

    python3 probes/generality-2026-11-03/run_probe.py --suite focused --run-name focused

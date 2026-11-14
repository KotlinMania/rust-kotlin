# PROBE: Rust-to-Kotlin generality, 2026-11-03

This is a small local experiment, not a production refactor or a generality certification.
Sydney authorized this openly visible folder. Iris owns production translation work and
reserved this folder for the probe. Her verified idle handover is recorded in evidence/.
No production sources or shared Gradle outputs are changed by this harness.

Inputs are newly authored self-contained examples for identifier records, wire values,
target configuration, and callback slots. They are independent of the production test
fixtures and have no third-party Rust dependencies. Rename/spacing controls keep the
semantics constant. GUID layout is a positive control for the useful Windows pattern.

Run from the repository root after coordinating an idle window with Iris:

    python3 probes/generality-2026-11-03/run_probe.py --run-name rerun-baseline

The harness checks the handed-over revision and CLI hash, then runs commands serially.
It uses the existing native CLI, rustc, and the cached Kotlin/JVM compiler; it does not
rebuild the translator, invoke Gradle, install tools, contact the network, or enumerate
processes. All inputs, generated Kotlin, drivers, command arrays, stdout/stderr, exit
codes and results stay here. artifacts/ holds visible ignored executable/class outputs.

Generation, Kotlin compilation, Kotlin execution, Rust compilation and Rust execution
are separate stages. A failed stage does not count as a pass or trigger a translation
fallback. Generated Kotlin is never patched. Cross-target cfg checks run emitted policy
on the JVM and compare Rust target metadata; they do not execute native cross-target Rust.
See EXPECTATIONS.md and RESULTS.md for exact coverage and limitations.

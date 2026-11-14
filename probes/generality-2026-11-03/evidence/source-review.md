# Current source recheck at 81b949dd74933c0161c5645636249c802a55f8c0

The early scout described an older dirty snapshot. Those claims were rechecked before
this probe; they are not carried forward as current defects.

- `SourceTranslation.kt` now contains no GUID/from_u128 emitter branch; its only GUID
  mention is a path example at line 2531. `Int128Synthesis.kt` contains the reusable
  U128/I128 lowering helpers. Focused GUID runtime evidence uses raw ABI naming.
- `SourceTranslation.kt:2987` renderItemType follows the declared alias RHS. There is
  no production CEnumRepr name override. Lines 3050 onward map pointer-sized aliases
  using the active target profile rather than forcing all of them to 64 bits.
- `MacroRulesExpander.kt:96` evaluates runtime cfg predicates; line 179 implements
  structural all/any/not evaluation, and line 211 compares pointer width to the profile.
  `SourceTranslation.kt:11275` calls that evaluator rather than emitting a fixed false.
- `CrateTokenAbiScanner.kt:1345` and line 1424 parse Option/bare-function token groups;
  whitespace/comments between fn and '(' no longer depend on a source substring.
  The AST emission path is `SourceTranslation.kt:3010` and line 3014.

Current key/value defect corroboration:

- `CliRunner.kt:378` accepts --cfg and parses key=value; line 424 stores custom pairs
  as `key=value` strings in enabledCfgFlags.
- `MacroRulesExpander.kt:162` consults that set for one-token bare identifiers. Its
  three-token key/value branch at line 211 handles built-in keys and feature, but
  line 259 sends other keys to unknown policy instead of consulting the stored pair.
- The untouched focused emitted code returns false for keyonly() and configured()
  even though `--cfg flavor="mint"` was supplied. Featureonly() and flagonly() return
  true. Runtime vectors and independent Rust host results are saved in runs/focused/.

The numeric-literal issue is supported by the actual compiler diagnostics and generated
source, not an inferred production root cause. No production fix was attempted.

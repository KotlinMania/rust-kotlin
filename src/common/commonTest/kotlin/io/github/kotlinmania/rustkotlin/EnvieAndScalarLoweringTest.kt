package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class EnvieAndScalarLoweringTest {
    private val coreTaggedHeader =
        FileHeaderContext(
            portLintSourcePath = "lib.rs",
            packageName = "io.github.kotlinmania.sample",
            projectGroup = "io.github.kotlinmania",
            projectNamespace = "io.github.kotlinmania.sample",
            crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
        )

    @Test
    fun scalarCharRefCellAndFmtWriteResultLowering() {
        val rust =
            """
            use core::cell::{Ref, RefCell, RefMut};
            use core::fmt;

            pub fn decode_chars(b: u8, u: u32, ch: char) -> (Option<char>, char, char, Result<char, ()>, u32, Result<u8, ()>) {
                (
                    char::from_u32(u),
                    char::from_u32_unchecked(u),
                    char::from(b),
                    char::try_from(u),
                    u32::from(ch),
                    u8::try_from(ch),
                )
            }

            pub fn cell_string_ops(cell: &RefCell<String>, r: Ref<'_, String>, mut rm: RefMut<'_, String>) -> usize {
                let s: &str = r.as_str();
                let mut g = cell.borrow_mut();
                g.push_str(s);
                rm.push_str(s);
                rm.push('!');
                rm.clear();
                r.len()
            }

            pub fn write_fmt_unit(f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str("ok")?;
                write!(f, "!")?;
                f.write_str("tail")
            }

            pub fn write_fmt_tagged(f: &mut fmt::Formatter<'_>) -> Result<(), fmt::Error> {
                f.write_str("ok")?;
                write!(f, "!")?;
                f.write_str("tail")
            }
            """.trimIndent()

        val kt = translateRustSourceToKotlin(recognizeRust(rust), coreTaggedHeader.copy(scalarChar = true))
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.char.RustChar")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.result.Result")
        assertContains(kt, "RustChar.fromU32(")
        assertContains(kt, "RustChar.fromU32Unchecked(")
        assertContains(kt, "RustChar.fromUByte(")
        assertContains(kt, "RustChar.tryFrom(")
        assertContains(kt, ".code")
        assertContains(kt, ".tryToUByte()")
        assertContains(kt, "r.value")
        assertContains(kt, "rm.value += ")
        assertContains(kt, "rm.value = \"\"")
        assertContains(kt, "r.value.length")
        assertContains(kt, "cell.borrowMut().useMut")
        assertContains(kt, "public fun writeFmtUnit(f: StringBuilder)")
        assertContains(kt, "public fun writeFmtTagged(f: StringBuilder): Result<Unit, Unit>")
        assertContains(kt, "Result.Ok(Unit)")
    }

    @Test
    fun envieStdEnvHashMapSplitFilterMapParseAndCoreResultImports() {
        val rust =
            """
            use std::collections::HashMap;
            use std::env;

            pub struct EnvBox {
                vars: HashMap<String, String>,
            }

            impl EnvBox {
                pub fn empty() -> Self {
                    let mut m = HashMap::new();
                    m.insert(String::from("K"), String::from("v"));
                    let copied = m.clone();
                    EnvBox { vars: copied }
                }

                pub fn from_pairs(pairs: Vec<(String, String)>) -> Self {
                    EnvBox { vars: HashMap::from(pairs) }
                }

                pub fn parse_lines(content: &str) -> HashMap<String, String> {
                    content
                        .lines()
                        .filter_map(|line| {
                            let (k, v) = line.split_once('=')?;
                            Some((k.trim().to_string(), v.trim().to_string()))
                        })
                        .collect()
                }

                pub fn get_lower(&self, key: &str) -> Result<String, String> {
                    let env = self.vars.get(key).cloned().or_else(|| env::var(key).ok());
                    env.map(|v| v.to_lowercase()).ok_or_else(|| format!("missing {key}"))
                }

                pub fn export_one(&self, key: &str) -> Result<(), String> {
                    let val = self.get_lower(key)?;
                    env::set_var(key, val);
                    Ok(())
                }

                pub fn get_num(&self, key: &str) -> Result<i32, String> {
                    self.get_lower(key).and_then(|v| v.parse::<i32>().map_err(|e| e.to_string()))
                }

                pub fn build_str(name: &str) -> String {
                    let mut out = String::new();
                    out.push_str(name);
                    out
                }
            }
            """.trimIndent()

        val kt = translateRustSourceToKotlin(recognizeRust(rust), coreTaggedHeader)
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.result.Result")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.result.andThen")
        assertContains(kt, "MutableMap<String, String>")
        assertContains(kt, "mutableMapOf()")
        assertContains(kt, "m.put(\"K\", \"v\")")
        assertContains(kt, ".toMap(LinkedHashMap())")
        assertContains(kt, ".toMutableMap()")
        assertContains(kt, ".mapNotNull")
        assertContains(kt, "indexOf('=')")
        assertContains(kt, ".lowercase()")
        assertContains(kt, "toIntOrNull()")
        assertContains(kt, "out += name")
        assertFalse(kt.contains("env.setVar("), "std::env call must not collide with local `val env`:\n$kt")
    }
}

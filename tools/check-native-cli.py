#!/usr/bin/env python3
"""Validate the real native CLI in place, without JVM launchers or consumer scaffolding."""
from pathlib import Path
import argparse,hashlib,json,re,subprocess
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('output');p.add_argument('--binary',default=str(ROOT/'build/bin/macosArm64/releaseExecutable/rust-kotlin.kexe'));a=p.parse_args()
BINARY=Path(a.binary).resolve();OUT=(ROOT/a.output).resolve()
if ROOT/'build' not in OUT.parents:p.error('output must be inside this tool project build directory')
OUT.mkdir(parents=True,exist_ok=False)
source=OUT/'input.rs';source.write_text('/// Native CLI UTF-8 smoke: 漢 🌍\npub fn sum(a: i32, b: i32) -> i32 { a + b }\n')
results=[]
def execute(name,command,expected=0,timeout=120):
    r=subprocess.run(list(map(str,command)),capture_output=True,text=True,timeout=timeout,cwd=ROOT)
    (OUT/(name+'.stdout')).write_text(r.stdout);(OUT/(name+'.stderr')).write_text(r.stderr)
    results.append({'name':name,'command':list(map(str,command)),'exit':r.returncode})
    print(name,r.returncode,flush=True)
    if r.returncode!=expected:raise RuntimeError(name+': '+r.stdout[-1500:]+r.stderr[-1500:])
    return r
kind=execute('file',['/usr/bin/file',BINARY]).stdout
assert 'Mach-O' in kind and 'arm64' in kind
linked=execute('linked-libraries',['/usr/bin/otool','-L',BINARY]).stdout
assert 'libjvm' not in linked.lower() and 'libjli' not in linked.lower()
assert 'Usage:' in execute('help',[BINARY,'--help']).stdout
assert 'items=1' in execute('inspect',[BINARY,'inspect',source]).stdout
output=OUT/'Sum.kt';execute('translate',[BINARY,'translate',source,output])
code=output.read_text();assert 'fun sum' in code and 'import java.' not in code and 'import javax.' not in code
before=hashlib.sha256(output.read_bytes()).hexdigest()
execute('refuse-existing',[BINARY,'translate',source,output],2)
assert before==hashlib.sha256(output.read_bytes()).hexdigest()
invalid=OUT/'invalid.rs';invalid.write_text('pub fn broken(');invalid_output=OUT/'Invalid.kt'
execute('reject-invalid',[BINARY,'translate',invalid,invalid_output],2);assert not invalid_output.exists()
execute('reject-missing',[BINARY,'inspect',OUT/'missing.rs'],2)
execute('usage',[BINARY],2)
# Compile and execute the simple translated function as Kotlin/Native as well.
package=re.search(r'^package\s+([^\s;]+)',code,re.M)
runner=OUT/'CheckSum.kt';runner.write_text(('package '+package.group(1)+'\n' if package else '')+'fun main() { check(sum(2, 3) == 5); println("PASS: translated native sum") }\n')
konanc=Path.home()/'.konan/kotlin-native-prebuilt-macos-aarch64-2.4.21/bin/konanc'
execute('compile-translated-native',[konanc,output,runner,'-produce','program','-entry',(package.group(1).replace('`','')+'.main' if package else 'main'),'-o',OUT/'translated-check'],timeout=180)
execute('run-translated-native',[OUT/'translated-check.kexe'])
sources=sorted((ROOT/'src/common/commonMain/kotlin/io/github/kotlinmania/rustkotlin').rglob('*.kt'))+sorted((ROOT/'src/native').rglob('*.kt'))
receipt={'binary':str(BINARY),'binary_sha256':hashlib.sha256(BINARY.read_bytes()).hexdigest(),'native_file_type':kind.strip(),'no_jvm_runtime_linked':True,'source_hashes':{str(s.relative_to(ROOT)):hashlib.sha256(s.read_bytes()).hexdigest() for s in sources},'checks':results,'existing_output_preserved':True,'invalid_output_absent':True,'installed':False}
(OUT/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print('PASS: actual native CLI and translated native program',flush=True)

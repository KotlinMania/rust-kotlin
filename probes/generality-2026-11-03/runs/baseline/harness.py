#!/usr/bin/env python3
"""Local PROBE only. Run serially after Iris explicitly hands over an idle window."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
EXPECTED_HEAD = '81b949dd74933c0161c5645636249c802a55f8c0'
EXPECTED_CLI = 'c52134a64b7e691fde00ea7124d1bcc21f70334fb9a96f42ef543f8f4a86f046'
CLI = REPO / 'build/bin/macosArm64/releaseExecutable/rust-kotlin.kexe'
ENV = dict(os.environ, GIT_OPTIONAL_LOCKS='0')

def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()

def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def git(*args):
    return subprocess.check_output(['git', '-C', str(REPO), *args], env=ENV, text=True)

def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')

def snapshot():
    return {'utc': utc(), 'head': git('rev-parse', 'HEAD').strip(),
            'status': git('status', '--porcelain=v1', '--untracked-files=all'),
            'staged': git('diff', '--cached', '--name-only'), 'cli_sha256': digest(CLI),
            'production_hashes': {str(p.relative_to(REPO)): digest(p)
                for p in sorted((REPO / 'src').rglob('*.kt'))}}

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--run-name', default='baseline')
args = parser.parse_args()
assert re.fullmatch(r'[A-Za-z0-9_-]+', args.run_name)
RUN = ROOT / 'runs' / args.run_name
if RUN.exists():
    raise SystemExit('Refusing to overwrite an existing run; choose a new --run-name.')
state = snapshot()
assert state['head'] == EXPECTED_HEAD, 'Re-coordinate and update provenance for a different source revision.'
assert state['cli_sha256'] == EXPECTED_CLI, 'CLI differs from the handed-over immutable binary.'
assert not state['staged'], 'Shared index must be empty before this probe.'
outside = [line for line in state['status'].splitlines()
           if not line[3:].startswith('probes/generality-2026-11-03/')]
assert not outside, 'Concurrent dirty files outside probe; stop and coordinate.'
RUN.mkdir(parents=True)
ART = ROOT / 'artifacts' / args.run_name
ART.mkdir(parents=True)
save(RUN / 'before.json', state)
commands = []
results = []

def run(label, argv, timeout=180):
    n = len(commands) + 1
    stem = f'{n:03d}-{label}'
    log = RUN / 'logs'
    log.mkdir(exist_ok=True)
    start = time.monotonic()
    row = {'label': label, 'argv': [str(a) for a in argv], 'cwd': str(REPO), 'started_utc': utc()}
    try:
        p = subprocess.run(row['argv'], cwd=REPO, env=ENV, capture_output=True, text=True, timeout=timeout)
        rc, stdout, stderr = p.returncode, p.stdout, p.stderr
    except subprocess.TimeoutExpired as exc:
        rc = 124
        stdout = exc.stdout or b''
        stderr = exc.stderr or b''
        if isinstance(stdout, bytes): stdout = stdout.decode(errors='replace')
        if isinstance(stderr, bytes): stderr = stderr.decode(errors='replace')
        stderr += '\nPROBE: command exceeded timeout; only this probe-owned child was terminated.\n'
    row.update(exit_code=rc, duration_seconds=round(time.monotonic()-start, 3), finished_utc=utc(),
               stdout=f'logs/{stem}.stdout.txt', stderr=f'logs/{stem}.stderr.txt')
    (RUN / row['stdout']).write_text(stdout)
    (RUN / row['stderr']).write_text(stderr)
    commands.append(row)
    save(RUN / 'commands.json', commands)
    return rc, stdout, stderr

def record():
    save(RUN / 'results.json', results)

# Use only existing cached toolchain artifacts. No Gradle, installs or network.
reports = sorted((REPO / 'build/reports/kotlin-build').glob('*.txt'), key=lambda p:p.stat().st_mtime, reverse=True)
compiler_cp = None
for report in reports:
    content = report.read_text()
    compiler_line = next((l for l in content.splitlines() if '[KOTLIN] Kotlin compiler classpath:' in l), None)
    if compiler_line:
        cp = compiler_line.split('Kotlin compiler classpath:', 1)[1].strip()
        if 'kotlin-compiler-embeddable/2.4.21/' in cp and all(Path(p).exists() for p in cp.split(':')):
            compiler_cp = cp
            compiler_report = str(report)
            break
assert compiler_cp, 'No complete cached Kotlin 2.4.21 compiler classpath found.'
stdlib = next(p for p in compiler_cp.split(':') if '/kotlin-stdlib/2.4.21/' in p)
java = Path('/opt/homebrew/opt/openjdk@21/bin/java')
assert java.exists()
rustc = shutil.which('rustc') or '/opt/homebrew/bin/rustc'
toolchain = {'compiler_report': compiler_report, 'compiler_jars': {p:digest(p) for p in compiler_cp.split(':')},
             'java': str(java), 'rustc': rustc, 'cli': str(CLI), 'cli_sha256': digest(CLI)}
save(RUN / 'toolchain.json', toolchain)
run('cli-help', [CLI, '--help'])
run('java-version', [java, '-version'])
_, rust_version, _ = run('rustc-version', [rustc, '-vV'])
host = re.search(r'^host: (.+)$', rust_version, re.M).group(1)

# All generated sources are captured directly from CLI stdout, without postprocessing.
cases = []
for name in ['guid_sentinel', 'guid_owner_renamed', 'guid_method_renamed', 'guid_layout', 'alias_named', 'alias_renamed']:
    cases.append((name, ROOT / f'inputs/{name}.rs', []))
for variant in ['compact', 'spaced', 'commented']:
    cases.append((f'callback_{variant}', ROOT / f'inputs/callback_{variant}/callback.rs', ['--raw-abi']))
for env in ['msvc', 'gnu']:
    cases.append((f'alias_target_{env}', ROOT/'inputs/alias_target.rs', ['--target-env', env, '--cfg-closed-world']))

for name, source, flags in cases:
    package = 'probe.' + name
    outdir = RUN / 'generated' / name
    outdir.mkdir(parents=True)
    rc, output, _ = run(name+'-generation', [CLI, 'translate', '--quiet', '--stdout', '--port-lint', 'none',
                              '--package', package, '--source-root', source.parent, *flags, source])
    generated = outdir / 'Generated.kt'
    generated.write_text(output)
    entry = {'case': name, 'input': str(source.relative_to(ROOT)), 'package':package,
             'generation_exit':rc, 'generated':str(generated.relative_to(ROOT)),
             'kotlin_compile_exit':None, 'kotlin_runtime_exit':None,
             'rust_compile_exit':None, 'rust_runtime_exit':None}
    results.append(entry)
    if name in ['alias_named','alias_renamed']:
        alias = 'CEnumRepr' if name=='alias_named' else 'WireRepr'
        entry['alias_rhs_short'] = bool(re.search(r'typealias\s+'+alias+r'\s*=\s*Short\b', output))
    if name.startswith('alias_target_'):
        expected = 'Int' if name.endswith('msvc') else 'UInt'
        entry['target_alias_rhs_expected'] = expected
        entry['target_alias_rhs_matches'] = bool(re.search(r'typealias\s+CEnumRepr\s*=\s*'+expected+r'\b',output))
    if name.startswith('callback_'):
        entry['nullable_sam_alias'] = bool(re.search(r'typealias\s+Completion\s*=\s*CompletionFn\?',output))
    record()
    print('GENERATION', name, 'exit', rc, 'bytes', len(output), flush=True)

# Explicit target metadata is an independent Rust compiler reference for cfg policy.
profiles = [('host', host), ('wasm32_wasi', 'wasm32-wasip1'),
            ('windows32_msvc', 'i686-pc-windows-msvc'), ('powerpc64_big', 'powerpc64-unknown-linux-gnu')]
functions = ['width','little','wasm','msvc','pc','windows','configured','tautology','unknown']
def config_values(text):
    values = {}
    for line in text.splitlines():
        m = re.fullmatch(r'([A-Za-z0-9_]+)="(.*)"',line)
        if m: values.setdefault(m[1],[]).append(m[2])
    return values
for name, target in profiles:
    meta_rc, meta, _ = run('cfg-'+name+'-rust-target-metadata', [rustc,'--print','cfg','--target',target])
    row = {'case':'cfg_'+name, 'rust_target':target,'rust_target_metadata_exit':meta_rc,
           'generation_exit':None,'kotlin_compile_exit':None,'kotlin_runtime_exit':None,
           'rust_compile_exit':None,'rust_runtime_exit':None,
           'cross_target_rust_compile_run':'not run; metadata reference only' if name!='host' else 'host reference follows'}
    results.append(row)
    if meta_rc: record(); continue
    cfg = config_values(meta)
    expected = [cfg['target_pointer_width'][0], str(cfg['target_endian'][0]=='little').lower(),
                str(cfg['target_arch'][0]=='wasm32').lower(), str(cfg.get('target_env',[''])[0]=='msvc').lower(),
                str(cfg['target_vendor'][0]=='pc').lower(),str('windows' in cfg.get('target_family',[])).lower(),
                'true','true','false']
    row['expected_runtime'] = ','.join(expected)
    flags=[]
    for key, option in [('target_pointer_width','--target-pointer-width'),('target_endian','--target-endian'),
                        ('target_arch','--target-arch'),('target_os','--target-os'),('target_env','--target-env'),
                        ('target_vendor','--target-vendor')]:
        flags += [option,cfg.get(key,[''])[0]]
    for family in cfg.get('target_family',[]): flags += ['--target-family',family]
    flags += ['--feature','tracing','--cfg','probe_flag','--cfg','flavor="mint"']
    outdir = RUN/'generated'/('cfg_'+name)
    outdir.mkdir(parents=True)
    rc, _, _ = run('cfg-'+name+'-generation',[CLI,'translate','--quiet','--recursive','--force',
              '--port-lint','none','--final-structs','--package','probe.cfg_'+name,
              '--source-root',ROOT/'inputs/cfg','--output',outdir,*flags,'--cfg-closed-world',ROOT/'inputs/cfg'])
    row['generation_exit'] = rc
    files = sorted(outdir.rglob('*.kt'))
    row['generated'] = [str(p.relative_to(ROOT)) for p in files]
    bodies = [re.sub(r'^package .*\n','',p.read_text(),flags=re.M) for p in files]
    row['directory_outputs_equal_except_package'] = len(bodies)==2 and bodies[0]==bodies[1]
    row['closed_world_omits_unknown_attribute'] = len(files)==2 and all('fun openmarker(' not in p.read_text() for p in files)
    row['packages'] = [re.search(r'^package ([^\n]+)',p.read_text(),re.M).group(1) for p in files]
    if name=='host':
        openout = RUN/'generated/cfg_host_open'
        openout.mkdir(parents=True)
        orc,_,_ = run('cfg-host-open-generation',[CLI,'translate','--quiet','--recursive','--force',
                    '--port-lint','none','--final-structs','--package','probe.cfg_host_open',
                    '--source-root',ROOT/'inputs/cfg','--output',openout,*flags,ROOT/'inputs/cfg'])
        openfiles=list(openout.rglob('*.kt'))
        row['open_world_generation_exit']=orc
        row['open_world_preserves_unknown_attribute']=len(openfiles)==2 and all('fun openmarker(' in p.read_text() for p in openfiles)
        row['open_world_compile_run']='not run; generation/policy check only'
    record()
    print('GENERATION cfg_'+name,'exit',rc,'files',len(files),flush=True)

print('GENERATION STAGE COMPLETE; compiling independent Rust references and untouched generated Kotlin.',flush=True)
for row in results:
    name=row['case']
    if name.startswith('cfg_') and name!='cfg_host': continue
    input_path = ROOT / (row['input'] if 'input' in row else 'inputs/cfg/plain/config.rs')
    driverdir = RUN/'drivers'/name
    driverdir.mkdir(parents=True,exist_ok=True)
    rust_driver = driverdir/'reference.rs'
    rel = os.path.relpath(input_path,driverdir)
    if name=='cfg_host':
        expr = ', '.join('input::'+fn+'()' for fn in functions)
        rust_body='println!("'+','.join(['{}']*len(functions))+'", '+expr+');'
        extra=['--cfg','feature="tracing"','--cfg','probe_flag','--cfg','flavor="mint"']
    else:
        rust_body='assert!(input::probe()); println!("true");'
        extra=[]
    rust_driver.write_text(f'#[path = "{rel}"]\nmod input;\nfn main() {{ {rust_body} }}\n')
    binary = ART/name/'rust-reference'
    binary.parent.mkdir(parents=True,exist_ok=True)
    rc,_,_ = run(name+'-rust-compile',[rustc,'--edition=2021','-Awarnings',*extra,rust_driver,'-o',binary])
    row['rust_compile_exit']=rc
    if rc==0:
        rr,ro,_=run(name+'-rust-execution',[binary])
        row['rust_runtime_exit']=rr;row['rust_runtime_stdout']=ro.strip()
        row['rust_reference_matches']=rr==0 and ro.strip()==row.get('expected_runtime','true')
    record()

for row in results:
    name=row['case']
    if row.get('generation_exit')!=0: continue
    files = [ROOT/p for p in row['generated']] if isinstance(row.get('generated'),list) else [ROOT/row['generated']]
    if not files: continue
    driverdir=RUN/'drivers'/name
    driverdir.mkdir(parents=True,exist_ok=True)
    driver=driverdir/'Main.kt'
    if name.startswith('cfg_'):
        packages=row['packages']
        lines=['fun main() {']
        for package in packages:
            values=', '.join(package+'.'+fn+'().toString()' for fn in functions)
            lines += [f'    check(listOf({values}).joinToString(",") == "{row["expected_runtime"]}")']
        lines += ['    println("'+row['expected_runtime']+'")','}']
        driver.write_text('\n'.join(lines)+'\n')
        mainclass='MainKt'
    else:
        driver.write_text('package '+row['package']+'\nfun main() { check(probe()); println("true") }\n')
        mainclass=row['package']+'.MainKt'
    classes=ART/name/'classes'
    classes.mkdir(parents=True,exist_ok=True)
    rc,_,_=run(name+'-kotlin-compile',[java,'-Xmx2g','-cp',compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
             '-no-stdlib','-no-reflect','-classpath',stdlib,'-jvm-target','21',
             '-opt-in=kotlin.ExperimentalUnsignedTypes','-d',classes,*files,driver])
    row['kotlin_compile_exit']=rc
    if rc==0:
        rr,ro,_=run(name+'-kotlin-execution',[java,'-cp',str(classes)+':'+stdlib,mainclass])
        row['kotlin_runtime_exit']=rr;row['kotlin_runtime_stdout']=ro.strip()
        row['kotlin_runtime_matches']=rr==0 and ro.strip()==row.get('expected_runtime','true')
    record()
    print('KOTLIN',name,'compile',rc,'runtime',row['kotlin_runtime_exit'],flush=True)

final=snapshot()
save(RUN/'after.json',final)
same=state['production_hashes']==final['production_hashes'] and state['head']==final['head'] and state['cli_sha256']==final['cli_sha256']
save(RUN/'integrity.json',{'production_sources_head_and_cli_unchanged':same,'index_empty':not final['staged']})
print('FINISHED',RUN,'production unchanged',same,flush=True)
# Probe failures are data. Exit nonzero to make a failed stage visible to the caller.
failures=[r['case'] for r in results if r.get('generation_exit')!=0 or r.get('kotlin_compile_exit')!=0 or r.get('kotlin_runtime_exit')!=0 or not r.get('kotlin_runtime_matches',False)]
print('FAILED_CASES',json.dumps(failures),flush=True)
sys.exit(0 if not failures and same else 1)

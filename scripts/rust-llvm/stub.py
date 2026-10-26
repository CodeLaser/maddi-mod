"""Compile a translated crate function by function: record rustc's errors per function, stub the bodies they are in.

    python3 -I stub.py <crate dir> <module>

Runs rustc (--emit=metadata) on <crate>/src/lib.rs. Each error inside a function body is recorded against that
function, and the body becomes `unimplemented!()`; an error in a signature removes the function. Repeats until the
crate compiles, or an error outside any function (a struct, a use) stops it. Writes <crate>/rustc.json: the errors,
per function, with the round they appeared in and what was done, and {"compiles": bool}. The original module is kept
as src/<module>.rs.orig.
"""
import json
import os
import re
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rustsrc  # noqa: E402

crate, module = sys.argv[1], sys.argv[2]
path = f'{crate}/src/{module}.rs'
shutil.copy(path, path + '.orig')
errors, compiles = [], False
for round_no in range(1, 30):
    run = subprocess.run(['rustc', '--edition', '2024', '--crate-type', 'lib', '--crate-name', module, '-A', 'warnings',
                          '--error-format=json', f'--emit=metadata={crate}/stub.rmeta', 'src/lib.rs'],
                         cwd=crate, capture_output=True, text=True)
    if run.returncode == 0:
        compiles = True
        break
    lines = open(path).read().split('\n')
    fns = rustsrc.functions(lines, module)
    stub, remove, stuck = set(), set(), []
    for raw in run.stderr.splitlines():
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if msg.get('level') != 'error' or not msg.get('spans'):
            continue
        span = next((s for s in msg['spans'] if s['is_primary']), msg['spans'][0])
        code = (msg.get('code') or {}).get('code', 'other')
        if not span['file_name'].endswith(f'/{module}.rs'):
            stuck.append(code)
            continue
        key, in_body = rustsrc.enclosing(fns, span['line_start'] - 1)
        if key is None:
            stuck.append(code)
            continue
        (stub if in_body else remove).add(key)
        errors.append({'function': '::'.join(key), 'round': round_no, 'code': code, 'message': msg['message'],
                       'line': span['line_start'], 'action': 'stubbed' if in_body else 'removed'})
    if stuck or not (stub or remove):
        errors.append({'function': None, 'round': round_no, 'code': ','.join(sorted(set(stuck))) or 'other',
                       'message': 'outside any function', 'action': 'gave up'})
        break
    # bottom up, so that earlier line numbers stay valid
    for key in sorted(stub | remove, key=lambda k: -fns[k]['first']):
        f = fns[key]
        if key in remove:
            del lines[f['first']:f['last'] + 1]
        elif key in stub:
            b0, b1 = f['body']
            indent = re.match(r'\s*', lines[f['line'] - 1]).group(0)
            lines[b0:b1 + 1] = [indent + '    unimplemented!()']
    open(path, 'w').write('\n'.join(lines))
json.dump({'compiles': compiles, 'errors': errors}, open(f'{crate}/rustc.json', 'w'), indent=1)

"""Compile a translated crate function by function: record rustc's errors per function, stub the bodies they are in.

    python3 -I stub.py <crate dir>

Runs rustc (--emit=metadata) on <crate>/src/lib.rs, whose modules are the other files of src/ (jrt.rs, the runtime,
excepted). Each error inside a function body is recorded against that function, and the body becomes
`unimplemented!()`; an error in a signature removes the function; an error outside any function removes the
top-level item it is in (a struct, an impl block, with its functions). Repeats until the crate compiles, or an error
in the runtime or in no item stops it. Writes <crate>/rustc.json: the errors, per function or item, with the round
they appeared in and what was done, and {"compiles": bool}. The original modules are kept as src/<module>.rs.orig.
"""
import json
import os
import re
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rustsrc  # noqa: E402

crate = sys.argv[1]
name = os.path.basename(crate.rstrip('/'))
modules = sorted(f[:-3] for f in os.listdir(f'{crate}/src') if f.endswith('.rs') and f not in ('lib.rs', 'jrt.rs'))
for module in modules:
    shutil.copy(f'{crate}/src/{module}.rs', f'{crate}/src/{module}.rs.orig')
errors, compiles = [], False
for round_no in range(1, 60):
    run = subprocess.run(['rustc', '--edition', '2024', '--crate-type', 'lib', '--crate-name', name, '-A', 'warnings',
                          '--error-format=json', f'--emit=metadata={crate}/stub.rmeta', 'src/lib.rs'],
                         cwd=crate, capture_output=True, text=True)
    if run.returncode == 0:
        compiles = True
        break
    lines = {m: open(f'{crate}/src/{m}.rs').read().split('\n') for m in modules}
    fns = {m: rustsrc.functions(lines[m], m) for m in modules}
    stub, remove, items, stuck = set(), set(), set(), []
    for raw in run.stderr.splitlines():
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if msg.get('level') != 'error' or not msg.get('spans'):
            continue
        span = next((s for s in msg['spans'] if s['is_primary']), msg['spans'][0])
        code = (msg.get('code') or {}).get('code', 'other')
        module = os.path.basename(span['file_name'])[:-3]
        if module not in lines:
            stuck.append(code)
            continue
        line0 = span['line_start'] - 1
        key, in_body = rustsrc.enclosing(fns[module], line0)
        if key is not None:
            (stub if in_body else remove).add(key)
            errors.append({'function': '::'.join(key), 'round': round_no, 'code': code, 'message': msg['message'],
                           'line': span['line_start'], 'action': 'stubbed' if in_body else 'removed'})
            continue
        item = rustsrc.item_at(lines[module], line0)
        if item is None:
            stuck.append(code)
            continue
        items.add((module, item))
        head = next(l for l in lines[module][item[0]:item[1] + 1] if rustsrc.ITEM.match(l))
        errors.append({'function': None, 'module': module, 'item': head.strip()[:100], 'round': round_no,
                       'code': code, 'message': msg['message'], 'action': 'removed item'})
    if stuck or not (stub or remove or items):
        errors.append({'function': None, 'round': round_no, 'code': ','.join(sorted(set(stuck))) or 'other',
                       'message': 'outside any item', 'action': 'gave up'})
        break
    for module in modules:
        # bottom up, so that earlier line numbers stay valid; a removed item takes its functions along
        edits = [(fns[module][k]['first'], fns[module][k]['last'], k in remove, k) for k in stub | remove
                 if k[0] == module]
        edits += [(a, b, True, None) for m, (a, b) in items if m == module]
        done_until = None
        for first, last, drop, key in sorted(edits, key=lambda e: (-e[0], e[1])):
            if done_until is not None and last >= done_until:
                continue
            ls = lines[module]
            if drop:
                del ls[first:last + 1]
            else:
                b0, b1 = fns[module][key]['body']
                indent = re.match(r'\s*', ls[fns[module][key]['line'] - 1]).group(0)
                ls[b0:b1 + 1] = [indent + '    unimplemented!()']
            done_until = first
        open(f'{crate}/src/{module}.rs', 'w').write('\n'.join(lines[module]))
json.dump({'compiles': compiles, 'errors': errors}, open(f'{crate}/rustc.json', 'w'), indent=1)

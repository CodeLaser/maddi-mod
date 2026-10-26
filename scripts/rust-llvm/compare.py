"""Join maddi's parameter verdicts with what LLVM and clippy say about the Rust they print as (see run.sh).

    python3 -I compare.py <out dir>     reads <out>/results/*.json and the crates <out>/crates/<module>/ that run.sh
                                        built: src/*.rs, rustc.txt (empty when it compiled), lib.ll, clippy.json

Per parameter of the crates' own functions: maddi's verdicts (U unmodified, SU structurally unmodified, I independent,
as the results files encode them: 1 or 2 true, - false), the Rust mode the printer chose, LLVM's inferred attributes
(readonly, writeonly, readnone, captures), and the clippy lints on it. A row gets a reading where the sources meet:

- LLVM proves what it states and only that: `captures(none)` is proof of no capture, a missing `captures` is LLVM not
  knowing. So maddi can only be contradicted on dependence by `captures(none)`.
- LLVM looks one pointer level deep: on a `&mut Vec<T>` it sees the header (pointer, length, capacity), not the
  elements. `readonly` there means the length is kept; a set() writes the buffer, so it is not structural
  unmodification in maddi's sense, where replacing an element is structural.
- clippy's needless_pass_by_ref_mut and needless_pass_by_value are imprecise verdicts: a `&mut` nothing writes through,
  an owned (kept) parameter only borrowed. ptr_arg on a `&mut Vec` is the length kept again.
"""
import glob
import json
import os
import re
import subprocess
import sys
from collections import Counter

CXXFILT = os.environ.get('LLVM_CXXFILT', '/opt/homebrew/opt/llvm/bin/llvm-cxxfilt')
out = sys.argv[1]
show_all = '--all' in sys.argv


def snake(name):
    if name == '<init>':
        return 'new'
    return re.sub(r'(?<=[a-z0-9])([A-Z])', r'_\1', name).lower()


def module_of(package):
    return package.replace('.', '_')


# ---- maddi's verdicts: (module, type, rust fn, param) -> data; (module, type, rust fn) -> method data
maddi_params, maddi_methods, overloaded = {}, {}, set()


def walk(entry, type_key, method):
    name, data = entry['name'], entry.get('data', {})
    kind = name[0]
    if kind == 'T':
        package, simple = name[1:].rsplit('.', 1)
        type_key = (module_of(package), simple)
    elif kind == 'S':
        # a nested type: Rust names it after its enclosing types, X.Inner as XInner
        type_key = (type_key[0], type_key[1] + name[1:name.index('(')])
    elif kind in 'MC':
        method = snake(name[1:name.index('(')])
        key = type_key + (method,)
        if key in maddi_methods:
            overloaded.add(key)
        maddi_methods[key] = data
    elif kind == 'P' and method:
        maddi_params[type_key + (method, name[1:name.index('(')])] = data
    for s in entry.get('subs', []) + ([entry['sub']] if 'sub' in entry else []):
        walk(s, type_key, method)


for f in glob.glob(out + '/results/*.json'):
    for e in json.load(open(f)):
        if 'name' in e:
            walk(e, None, None)


def flags(data, *keys):
    return '/'.join(str(data.get(k, '-')) for k in keys)


# ---- the printed Rust: per function its line, and per parameter its mode and column range
def split_params(text, offset):
    params, depth, start = [], 0, 0
    for i, ch in enumerate(text + ','):
        if ch in '<([':
            depth += 1
        elif ch in '>)]' and not (ch == '>' and text[i - 1:i] == '-'):
            depth -= 1
        elif ch == ',' and depth == 0:
            p = text[start:i]
            if p.strip():
                lead = len(p) - len(p.lstrip())
                params.append((p.strip(), offset + start + lead, offset + i))
            start = i + 1
    return params


def rust_functions(crate, module):
    fns = {}  # (module, type, fn) -> {'file', 'line', 'params': [(name, mode, col0, col1)]}
    path = f'{crate}/src/{module}.rs'
    impl = None
    lines = open(path).read().splitlines()
    for no, line in enumerate(lines, 1):
        m = re.match(r'impl(?:<[^>]*>)? (\w+)', line)
        if m:
            impl = m.group(1)
            continue
        m = re.match(r'\s*(?:pub )?(?:unsafe )?fn (?:r#)?(\w+)(?:<[^(]*>)?\((.*)\)', line)
        if not m or impl is None:
            continue
        params = []
        for p, c0, c1 in split_params(m.group(2), m.start(2)):
            if p in ('self', '&self', '&mut self', 'mut self'):
                params.append(('self', {'&self': '&', '&mut self': '&mut'}.get(p, 'owned'), c0, c1))
                continue
            if ':' not in p:
                continue
            pname, ptype = p.split(':', 1)
            ptype = ptype.strip()
            mode = '&mut' if ptype.startswith('&mut ') or re.match(r"&'\w+ mut ", ptype) else \
                '&' if ptype.startswith('&') else 'owned'
            params.append((pname.replace('mut ', '').strip(), mode, c0, c1))
        # an abstract method's body says nothing about its parameters
        abstract = no < len(lines) and 'unimplemented!()' in lines[no]
        body = []
        for later in lines[no:]:
            if re.match(r'\s*(#\[inline|(pub )?(unsafe )?fn )', later) or later.startswith('}'):
                break
            body.append(later)
        fns[(module, impl, m.group(1))] = {'file': f'{module}.rs', 'line': no, 'params': params,
                                           'abstract': abstract, 'body': '\n'.join(body)}
    return fns


# ---- LLVM: inferred parameter attributes, in parameter order; merged functions are aliases of their twin
FN = r'@<\w+::(\w+)::(\w+)(?:<[^>]*>)?>::(?:r#)?(\w+)'


def llvm_attributes(ir_file):
    llvm, aliases = {}, {}
    lines = [l for l in open(ir_file) if l.startswith(('define', '@<'))]
    demangled = subprocess.run([CXXFILT], input=''.join(lines), capture_output=True, text=True).stdout.splitlines()
    for d in demangled:
        a = re.match(FN + r' = .*alias .*, ptr ' + FN, d)
        if a:
            aliases[a.group(1, 2, 3)] = a.group(4, 5, 6)
            continue
        m = re.search(r'^define .*?' + FN + r'\((.*?)\)( unnamed_addr)? #', d)
        if not m:
            continue
        attrs = {}
        # each parameter is its type and attributes up to its %name; attributes themselves contain ", "
        for pm in re.finditer(r'([^%]*?)%([\w.]+)', m.group(4)):
            p, base = pm.group(1), pm.group(2).split('.')[0]
            if base in attrs or base == '_0':
                continue
            got = [a for a in ('readonly', 'writeonly', 'readnone') if re.search(r'\b' + a + r'\b', p)]
            cap = re.search(r'captures\(([^)]*)\)', p)
            got.append('captures(' + (cap.group(1) if cap else 'any') + ')')
            attrs[base] = ' '.join(got)
        llvm[m.group(1, 2, 3)] = attrs
    return llvm, aliases


def clippy_lints(clippy_file):
    lints = {}  # (file, line) -> [(col, lint)]
    for line in open(clippy_file):
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        code = (msg.get('code') or {}).get('code', '')
        if not code.startswith('clippy::'):
            continue
        for span in msg['spans']:
            if span['is_primary']:
                lints.setdefault((span['file_name'].split('/')[-1], span['line_start']), []).append(
                    (span['column_start'] - 1, code[8:]))
    return lints


def reading(name, mode, d, ir, cl, body):
    ind, su = d.get('independentParameter'), d.get('structurallyUnmodifiedParameter')
    r = []
    if 'needless_pass_by_ref_mut' in cl:
        r.append('disagree: maddi modified, clippy: nothing writes through it')
    if mode == '&mut' and 'readnone' in ir and 'captures(none)' in ir:
        r.append('disagree: maddi modified, LLVM: never read or written')
    if 'needless_pass_by_value' in cl:
        # moved in to be kept, then copied: the printer's clone, not the verdict
        r.append('printer: an owned parameter cloned' if re.search(r'\b' + name + r'\.clone\(\)', body)
                 else 'disagree: maddi kept, clippy: only borrowed')
    if mode == '&mut' and ind != 2 and 'captures(none)' in ir:
        r.append('disagree: maddi dependent, LLVM: not captured')
    if mode == '&mut' and ind != 2 and 'captures(ret' in ir:
        r.append('agree: dependent, LLVM: kept in the result')
    if mode == '&mut' and ('readonly' in ir or 'ptr_arg' in cl):
        r.append('agree: structurally unmodified, length kept' if su == 1
                 else 'info: maddi structural, length kept (an element replaced?)')
    return r


rows, tally, rustc = [], Counter(), Counter()
crates = sorted(glob.glob(out + '/crates/*/'))
compiled = 0
for crate in crates:
    crate = crate.rstrip('/')
    module = crate.split('/')[-1]
    errors = open(crate + '/rustc.txt').read() if os.path.exists(crate + '/rustc.txt') else 'not built'
    if errors.strip() or not os.path.exists(crate + '/lib.ll'):
        codes = sorted(set(re.findall(r'error\[(E\d+)\]', errors))) or ['other']
        for c in codes:
            rustc[c] += 1
        continue
    compiled += 1
    fns = rust_functions(crate, module)
    llvm, aliases = llvm_attributes(crate + '/lib.ll')
    lints = clippy_lints(crate + '/clippy.json') if os.path.exists(crate + '/clippy.json') else {}
    for key, info in sorted(fns.items()):
        ir = llvm.get(key)
        if ir is None and key in aliases:
            # merged: the same signature, so the parameters correspond by position
            merged = list(llvm.get(aliases[key], {}).values())
            ir = {p[0]: merged[i] for i, p in enumerate(info['params']) if i < len(merged)}
        for pname, mode, c0, c1 in info['params']:
            if pname == 'self':
                d = maddi_methods.get(key, {})
                v = flags(d, 'nonModifyingMethod', 'structurallyNonModifyingMethod', 'independentMethod')
            else:
                d = maddi_params.get(key + (pname,), {})
                v = flags(d, 'unmodifiedParameter', 'structurallyUnmodifiedParameter', 'independentParameter')
            a = 'absent' if ir is None else ir.get(pname, '-')
            cl = [code for col, code in lints.get((info['file'], info['line']), []) if c0 <= col < c1]
            r = [] if key in overloaded or pname == 'self' or info['abstract'] else reading(pname, mode, d, a, cl, info['body'])
            tally.update(r)
            if r or show_all:
                rows.append(('::'.join(key), pname, mode, v, a, ','.join(cl), '; '.join(r)))

header = ('function', 'param', 'mode', 'maddi U/SU/I', 'llvm', 'clippy', 'reading')
widths = [max(len(str(row[i])) for row in rows + [header]) for i in range(6)]
for row in [header] + rows:
    print('  '.join(str(c).ljust(w) for c, w in zip(row, widths + [0])).rstrip())
print()
print(f'{len(crates)} crates, {compiled} compiled; rejected by rustc, per error code (a crate counts once per code):')
for k, n in rustc.most_common():
    print(f'{n:5}  {k}')
print('readings:')
for k, n in tally.most_common():
    print(f'{n:5}  {k}')

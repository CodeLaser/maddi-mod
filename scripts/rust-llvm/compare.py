"""Join maddi's parameter verdicts with what LLVM and clippy say about the Rust they print as (see run.sh).

    python3 -I compare.py <out dir> [--all]    reads <out>/results/*.json and the crates <out>/crates/<module>/ that
                                               run.sh built: src/ (the module as printed in <module>.rs.orig),
                                               rustc.json (stub.py), lib.ll, clippy.json

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

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rustsrc  # noqa: E402

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


# ---- LLVM: inferred parameter attributes, in parameter order; merged functions are aliases of their twin
FN = r'@<\w+::(\w+)::(\w+)(?:<[^>]*>)?>::(?:r#)?(\w+)'


def llvm_attributes(ir_file):
    """Per function: its parameters' attributes; the crate functions it calls; whether it never returns. Merged
    functions are aliases of their twin."""
    llvm, aliases, calls, noreturn = {}, {}, {}, set()
    text = open(ir_file).read()
    groups = dict(re.findall(r'^attributes (#\d+) = \{ (.*) \}$', text, re.M))
    demangled = subprocess.run([CXXFILT], input=text, capture_output=True, text=True).stdout.splitlines()
    current = None
    for d in demangled:
        if current and d.startswith('}'):
            current = None
            continue
        if current:
            for c in re.finditer(r'(?:call|invoke) .*?' + FN + r'\(', d):
                calls[current].add(c.group(1, 2, 3))
            continue
        a = re.match(FN + r' = .*alias .*, ptr ' + FN, d)
        if a:
            aliases[a.group(1, 2, 3)] = a.group(4, 5, 6)
            continue
        m = re.search(r'^define .*?' + FN + r'\((.*?)\)( unnamed_addr)? (#\d+)', d)
        if not m:
            continue
        current = m.group(1, 2, 3)
        calls[current] = set()
        if 'noreturn' in groups.get(m.group(6), ''):
            noreturn.add(current)
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
        llvm[current] = attrs
    return llvm, aliases, calls, noreturn


def tainted(stubs, calls, noreturn, aliases):
    """The functions whose attributes a stub or an always-panicking body reaches: LLVM, seeing that the call never
    returns, infers attributes the code as printed does not have."""
    bad = set(stubs) | noreturn | {a for a, t in aliases.items() if t in stubs or t in noreturn}
    changed = True
    while changed:
        changed = False
        for f, callees in calls.items():
            if f not in bad and callees & bad:
                bad.add(f)
                changed = True
    return bad


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


# ---- rustc's errors per function (stub.py): the borrow checker's verdicts, and the printer's gaps
def rustc_readings(errors, fn_params, d_param, d_method):
    """(param or '-', reading) per error; a write through `&` names the variable it writes through."""
    r = []
    for e in errors:
        code, msg = e['code'], e['message']
        root = re.search(r'`\*?(\w+)', msg)
        root = root.group(1) if root else None
        if code in ('E0596', 'E0594') and 'behind a `&` reference' in msg and root in fn_params:
            if root == 'self':
                r.append((root, 'disagree: maddi non-modifying, rustc: writes self' if d_method.get(
                    'nonModifyingMethod') == 1 else 'printer: &self for a modifying method'))
            else:
                r.append((root, 'disagree: maddi unmodified, rustc: written through it' if d_param(root).get(
                    'unmodifiedParameter') == 1 else 'printer: & for a modified parameter'))
        elif code in ('E0596', 'E0594') and 'not declared as mutable' in msg:
            r.append(('-', 'printer: a local not declared mut'))
        elif code in ('E0596', 'E0594') and 'closure' in msg:
            r.append(('-', 'printer: a Fn closure that writes'))
        elif code in ('E0596', 'E0594') and re.search(r'data in an? `', msg):
            r.append(('-', 'rustc: modified through a shared borrow (a getter\'s result, as_ref, Rc)'))
        elif code in ('E0596', 'E0594') and 'behind a `&` reference' in msg:
            r.append(('-', 'rustc: written through a local borrow'))
        elif code in ('E0515', 'E0597', 'E0716', 'E0505', 'E0521', 'E0621'):
            r.append(('-', f'rustc: lifetime ({code}), a missed dependence or the printer'))
        elif code in ('E0382', 'E0507', 'E0499', 'E0502', 'E0500', 'E0501'):
            r.append(('-', f'rustc: ownership ({code}), Java sharing or the printer'))
        elif code == 'E0133':
            r.append(('-', 'rustc: unsafe call, a missed @StaticSideEffects?'))
        else:
            r.append(('-', f'coverage: {code}'))
    return r


rows, tally, coverage = [], Counter(), Counter()
crates = sorted(glob.glob(out + '/crates/*/'))
outcome, functions, stubbed_fns = Counter(), 0, 0
for crate in crates:
    crate = crate.rstrip('/')
    module = crate.split('/')[-1]
    if not os.path.exists(crate + '/rustc.json'):
        outcome['not built'] += 1
        continue
    report = json.load(open(crate + '/rustc.json'))
    per_fn = {}
    for e in report['errors']:
        if e['function'] is None:
            for c in e['code'].split(','):
                coverage[c + ' (outside functions)'] += 1
        else:
            per_fn.setdefault(tuple(e['function'].split('::')), []).append(e)
    built = os.path.exists(crate + '/lib.ll')
    outcome['compiled as printed' if built and not report['errors'] else
            'compiled with stubs' if built else 'gave up'] += 1
    orig = crate + f'/src/{module}.rs' + ('.orig' if os.path.exists(crate + f'/src/{module}.rs.orig') else '')
    lines = open(orig).read().split('\n')
    fns = rustsrc.functions(lines, module)
    stubbed = rustsrc.functions(open(crate + f'/src/{module}.rs').read().split('\n'), module) if built else {}
    llvm, aliases, calls, noreturn = llvm_attributes(crate + '/lib.ll') if built else ({}, {}, {}, set())
    bad = tainted({k for k in per_fn if k in stubbed}, calls, noreturn, aliases)
    lints = clippy_lints(crate + '/clippy.json') if built and os.path.exists(crate + '/clippy.json') else {}
    for key, info in sorted(fns.items()):
        functions += 1
        body = '\n'.join(lines[info['body'][0]:info['body'][1] + 1]) if info['body'] else ''
        d_method = maddi_methods.get(key, {})
        names = {p[0] for p in info['params']}
        errs = rustc_readings(per_fn.get(key, []), names, lambda n: maddi_params.get(key + (n,), {}), d_method)
        if key in per_fn:
            stubbed_fns += 1
        for pname, r in errs:
            tally[r] += 1
            if not r.startswith('coverage'):
                rows.append(('::'.join(key), pname, '', '', 'rustc ' + ','.join(sorted({e['code'] for e in per_fn[key]})), '', r))
        # LLVM and clippy see the function as printed only when it compiled unstubbed, with its own signature
        if key in per_fn or key not in stubbed:
            continue
        ir = llvm.get(key)
        if ir is None and key in aliases:
            # merged: the same signature, so the parameters correspond by position
            merged = list(llvm.get(aliases[key], {}).values())
            ir = {p[0]: merged[i] for i, p in enumerate(info['params']) if i < len(merged)}
        line_now = stubbed[key]['line']
        abstract = 'unimplemented!()' in body
        for pname, mode, c0, c1 in info['params']:
            if pname == 'self':
                d = d_method
                v = flags(d, 'nonModifyingMethod', 'structurallyNonModifyingMethod', 'independentMethod')
            else:
                d = maddi_params.get(key + (pname,), {})
                v = flags(d, 'unmodifiedParameter', 'structurallyUnmodifiedParameter', 'independentParameter')
            a = 'absent' if ir is None else 'reaches a panic' if key in bad else ir.get(pname, '-')
            cl = [code for col, code in lints.get((f'{module}.rs', line_now), []) if c0 <= col < c1]
            r = [] if key in overloaded or pname == 'self' or abstract else reading(pname, mode, d, a, cl, body)
            tally.update(r)
            if r or show_all:
                rows.append(('::'.join(key), pname, mode, v, a, ','.join(cl), '; '.join(r)))

header = ('function', 'param', 'mode', 'maddi U/SU/I', 'llvm / rustc', 'clippy', 'reading')
widths = [max(len(str(row[i])) for row in rows + [header]) for i in range(6)]
for row in [header] + rows:
    print('  '.join(str(c).ljust(w) for c, w in zip(row, widths + [0])).rstrip())
print()
print(f'{len(crates)} crates: ' + ', '.join(f'{n} {k}' for k, n in outcome.most_common()))
print(f'{functions} functions, {stubbed_fns} with rustc errors (stubbed or removed)')
print('readings:')
for k, n in tally.most_common():
    if not k.startswith('coverage'):
        print(f'{n:5}  {k}')
print('coverage, rustc errors per code (the printer\'s gaps):')
for k, n in sorted(((k, n) for k, n in tally.items() if k.startswith('coverage')), key=lambda x: -x[1])[:15]:
    print(f'{n:5}  {k[10:]}')
for k, n in coverage.most_common():
    print(f'{n:5}  {k}')

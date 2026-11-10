#!/usr/bin/env python3
"""KotlinConf step 2: maddi's declarations, an LLM's bodies.

The stubbed translation (TestKotlinConfStubs: every body TODO("maddi:<id>"), src/maddi-bodies.json) is the input.
The LLM is a pluggable step: `prepare` writes one prompt per Kotlin file, the LLM (an API call, a subagent) writes
answer.json beside it ({id: body}), `assemble` splices, compiles all files together with kotlinc and writes each
file's errors for a fix round; `finish` puts maddi's own body back where a body still fails, compiles, and runs the
corpus's tests.

  bodies.py prepare  <work> <file.kt>...     prompts for these files (paths relative to the stubbed src)
  bodies.py assemble <work>                  splice the answers, compile, errors per file -> <work>/<file>/errors.txt
  bodies.py finish   <work>                  fall back where errors remain, compile, run the tests, report
  bodies.py maddi    <work>                  every body maddi's: the fallback alone (checks the splicing)
"""
import json
import urllib.parse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

EXP = Path('/Users/bnaudts/git/ws/maddi-mcp/llm-bodies-exp')
STUBS = Path('/Users/bnaudts/git/ws/maddi-mcp/maddi-mod/maddi-run-analysis/build/j2k/fernflower-stubs')
SRC = STUBS / 'src'
KOTLINC = EXP / 'kc' / 'kotlinc'
DIAG = re.compile(r'^(.+\.kt):(\d+):(\d+): error: (.*)$')


def manifest():
    return json.load(open(SRC / 'maddi-bodies.json'))


def todo(stub_id):
    escaped = stub_id.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$')
    return '{ TODO("maddi:' + escaped + '") }'


def fallback_body(stub):
    """maddi's body: its translation of the method minus the declaration ("{ ... }" or "= ...")."""
    printed, declaration = stub['printed'], stub['declaration']
    if not printed.startswith(declaration):
        raise ValueError('printed does not start with the declaration: ' + stub['id'])
    return printed[len(declaration):].strip()


def java_lines(stub):
    path = stub['javaFile'].removeprefix('file:') if hasattr(str, 'removeprefix') else stub['javaFile'][5:]
    lines = open(path).read().split('\n')
    b, e = stub['javaLines']
    return '\n'.join(lines[b - 1:e])


def by_file(stubs):
    out = {}
    for s in stubs:
        out.setdefault(s['kotlinFile'], []).append(s)
    return out


def work_dir(work, kotlin_file):
    return Path(work) / kotlin_file.replace('/', '__').removesuffix('.kt') if hasattr(str, 'removesuffix') \
        else Path(work) / kotlin_file.replace('/', '__')[:-3]


PROMPT = """\
You are translating one Java file to Kotlin. The declarations are already written: every class, property,
constructor and method signature in the Kotlin file below is final, with the parameter and return types (their
nullability, and read-only versus mutable collections) decided by a whole-program analysis that the other files of
the program were translated against. Each method body is a stub, `{ TODO("maddi:<id>") }`. Write the bodies.

Rules:
- Translate the Java body faithfully: the same behaviour, the same exceptions in the same places (where Java throws a
  NullPointerException, Kotlin may throw one too, `!!` at the same dereference), the same order of side effects.
- Do not change a declaration. A value whose Kotlin type is nullable must be handled as such where a non-null one is
  needed: `!!` where Java dereferences it, a check where Java checks it.
- Write idiomatic Kotlin where it does not change behaviour: `val` over `var`, `for (x in xs)`, `when`, string
  templates, smart casts on local `val`s. Keep the Java comments that belong to the body.
- The other files are compiled with this one: call their members as their declarations (listed below) say.
  Java library calls stay as they are (`java.util.HashMap`, `map.get(k)` etc. are fine).
- A body is what goes between the braces of the stub (statements; end with `return` where the method returns a value).

Answer with a JSON object only: each stub id mapped to its body, as a string. Every id below must be present.

## Stubs in this file
{stub_list}

## The Java file ({java_path})
```java
{java}
```

## The Kotlin file, with the stubs ({kotlin_file})
```kotlin
{kotlin}
```

## Declarations of the translated members the bodies call, in other files
```kotlin
{callees}
```

## Declarations of the fields of other files that the bodies read or write (as Kotlin properties)
```kotlin
{fields}
```
"""


def prepare(work, files):
    stubs = by_file(manifest())
    for f in files:
        mine = stubs[f]
        d = work_dir(work, f)
        d.mkdir(parents=True, exist_ok=True)
        java_path = urllib.parse.urlparse(mine[0]['javaFile']).path.replace('/home/bnaudts/', '/Users/bnaudts/', 1)  # a manifest made on laser1
        own = {s['id'] for s in mine}
        callees, fields = {}, {}
        for s in mine:
            for c in s['callees']:
                if c['id'] not in own and not c['id'].startswith(own_type_prefix(mine)):
                    callees[c['id']] = c['declaration']
            for c in s.get('fields', []):
                if not c['id'].startswith(own_type_prefix(mine)):
                    fields[c['id']] = c['declaration']
        stub_list = '\n'.join('- `%s` (Java lines %d-%d)' % (s['id'], s['javaLines'][0], s['javaLines'][1]) for s in mine)
        (d / 'prompt.md').write_text(PROMPT
                                     .replace('{stub_list}', stub_list)
                                     .replace('{java_path}', java_path)
                                     .replace('{java}', open(java_path).read())
                                     .replace('{kotlin_file}', f)
                                     .replace('{kotlin}', (SRC / f).read_text())
                                     .replace('{callees}', '\n'.join('// %s\n%s' % kv for kv in sorted(callees.items()))
                                              or '// (none)')
                                     .replace('{fields}', '\n'.join('// %s\n%s' % kv for kv in sorted(fields.items()))
                                              or '// (none)'))
        (d / 'file.txt').write_text(f + '\n')
        (d / 'ids.json').write_text(json.dumps([s['id'] for s in mine], indent=1))
        print(d)


def own_type_prefix(mine):
    # members of the file's own primary type are in the Kotlin file the prompt shows
    first = mine[0]['id']
    return first[:first.rfind('.', 0, first.find('('))] + '.'


def splice(text, stub, body, fallback):
    """The stub replaced by the LLM's body (statements) or maddi's (fallback: "{ ... }" / "= ...")."""
    t = todo(stub['id'])
    if t not in text:
        raise ValueError('stub not found: ' + stub['id'])
    if fallback:
        return text.replace(t, body, 1)
    return text.replace(t, '{\n' + body.strip('\n') + '\n}', 1)


def build(work, use_answers, fallback_ids=frozenset()):
    """All files into <work>/src: answered bodies where there is an answer (and the id is not in fallback_ids),
    maddi's elsewhere. Returns {kotlin file: [(stub id, first line, last line, origin)]}."""
    out = Path(work) / 'src'
    if out.exists():
        shutil.rmtree(out)
    stubs = by_file(manifest())
    answers = {}
    if use_answers:
        for d in Path(work).iterdir():
            if (d / 'answer.json').is_file():
                f = (d / 'file.txt').read_text().strip()
                answers[f] = json.load(open(d / 'answer.json'))
    spans = {}
    for kt in SRC.rglob('*.kt'):  # the files without a stub too (constants, plain interfaces)
        rel = kt.relative_to(SRC)
        (out / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(kt, out / rel)
    for f, mine in stubs.items():
        text = (SRC / f).read_text()
        for s in mine:
            mine_answer = answers.get(f, {}).get(s['id'])
            llm = mine_answer is not None and s['id'] not in fallback_ids
            text = splice(text, s, mine_answer if llm else fallback_body(s), not llm)
        # spans of the answered bodies, by locating each body after splicing: the stub ids are gone, so
        # re-find each by its text
        target = out / f
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        if f in answers:
            spans[f] = locate(text, mine, answers[f], fallback_ids)
    return spans


def locate(text, mine, answer, fallback_ids):
    """Line spans of the LLM bodies in the spliced text."""
    spans, pos = [], 0
    for s in mine:
        if s['id'] not in answer or s['id'] in fallback_ids:
            continue
        body = '{\n' + answer[s['id']].strip('\n') + '\n}'
        i = text.find(body, pos)
        if i < 0:
            continue
        first = text.count('\n', 0, i) + 1
        last = first + body.count('\n')
        spans.append((s['id'], first, last))
        pos = i + len(body)
    return spans


def compile_all(work, out_name):
    src = Path(work) / 'src'
    cp = [p for p in open(EXP / 'fernflower.cp').read().strip().split(':') if 'test-oss/fernflower/build' not in p]
    files = sorted(str(p) for p in src.rglob('*.kt'))
    classes = Path(work) / out_name
    if classes.exists():
        shutil.rmtree(classes)
    args = Path(work) / (out_name + '.args')
    args.write_text('\n'.join('"%s"' % a for a in
                              ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-classpath', ':'.join(cp),
                               '-d', str(classes)] + files))
    log = Path(work) / (out_name + '.log')
    with open(log, 'w') as fh:
        rc = subprocess.call([str(KOTLINC), '@' + str(args)], stdout=fh, stderr=subprocess.STDOUT)
    errors = []
    for line in open(log):
        m = DIAG.match(line.rstrip('\n'))
        if m:
            errors.append((os.path.relpath(os.path.abspath(m.group(1)), src), int(m.group(2)), m.group(4)))
    return rc, errors, classes


def assemble(work):
    spans = build(work, True)
    rc, errors, _ = compile_all(work, 'classes')
    report_errors(work, spans, errors)
    return spans, errors


def report_errors(work, spans, errors):
    per_file = {}
    for f, line, msg in errors:
        per_file.setdefault(f, []).append((line, msg))
    failing = {}
    for d in Path(work).iterdir():
        if not (d / 'file.txt').is_file():
            continue
        f = (d / 'file.txt').read_text().strip()
        mine = per_file.get(f, [])
        text = (Path(work) / 'src' / f).read_text().split('\n')
        lines = []
        bad = set()
        for line, msg in mine:
            owner = next((i for i, a, b in spans.get(f, []) if a <= line <= b), None)
            if owner:
                bad.add(owner)
            lines.append('%s:%d: %s\n    %s' % (owner or '(outside the bodies)', line, msg,
                                                text[line - 1].strip() if line <= len(text) else ''))
        (d / 'errors.txt').write_text('\n'.join(lines) + ('\n' if lines else ''))
        failing[f] = sorted(bad)
        print('%-70s %3d errors, %d bodies failing' % (f, len(mine), len(bad)))
    other = [e for e in errors if e[0] not in spans]
    if other:
        print('%d errors outside the answered files, first: %s' % (len(other), other[0]))
    json.dump(failing, open(Path(work) / 'failing.json', 'w'), indent=1)


def run_tests(work, classes):
    out = Path(work) / 'tests'
    r = subprocess.run([str(EXP / 'runtests.sh'), str(classes), str(out)], capture_output=True, text=True)
    print(r.stdout.strip())
    return r.stdout


def finish(work):
    spans, errors = assemble(work)
    failing = json.load(open(Path(work) / 'failing.json'))
    fallback_ids = frozenset(i for ids in failing.values() for i in ids)
    spans = build(work, True, fallback_ids)
    rc, errors, classes = compile_all(work, 'classes-final')
    answered = sum(len(v) for v in spans.values())
    print('LLM bodies kept: %d, fallen back to maddi: %d, compile errors left: %d' %
          (answered, len(fallback_ids), len(errors)))
    for e in errors[:10]:
        print('  ', e)
    if not errors:
        run_tests(work, classes)


def maddi_only(work):
    Path(work).mkdir(parents=True, exist_ok=True)
    build(work, False)
    rc, errors, classes = compile_all(work, 'classes')
    print('maddi bodies only: rc %d, %d errors' % (rc, len(errors)))
    for e in errors[:10]:
        print('  ', e)
    if not errors:
        run_tests(work, classes)


if __name__ == '__main__':
    cmd, work = sys.argv[1], sys.argv[2]
    {'prepare': lambda: prepare(work, sys.argv[3:]), 'assemble': lambda: assemble(work),
     'finish': lambda: finish(work), 'maddi': lambda: maddi_only(work)}[cmd]()

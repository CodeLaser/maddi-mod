#!/usr/bin/env python3
"""KotlinConf step 3, the LLM-alone arm: an LLM translates whole Java files (declarations and bodies), no maddi.

  alone.py groups  <work> <n>     split the corpus's Java files into n groups of about equal size
  alone.py compile <work>         compile <work>/src/**/*.kt together; errors per file -> <work>/errors/<group>.txt
  alone.py test    <work>         compile, and if clean run the corpus's tests
"""
import os, re, shutil, subprocess, sys
from pathlib import Path

EXP = Path('/Users/bnaudts/git/ws/maddi-mcp/llm-bodies-exp')
JAVA = Path('/Users/bnaudts/git/test-oss/fernflower/src')
KOTLINC = EXP / 'kc' / 'kotlinc'
DIAG = re.compile(r'^(.+\.kt):(\d+):(\d+): error: (.*)$')


def groups(work, n):
    files = sorted(JAVA.rglob('*.java'), key=lambda p: -p.stat().st_size)
    gs = [[0, []] for _ in range(n)]
    for f in files:
        g = min(gs, key=lambda x: x[0])
        g[0] += f.stat().st_size
        g[1].append(str(f.relative_to(JAVA)))
    d = Path(work) / 'groups'
    d.mkdir(parents=True, exist_ok=True)
    for i, (size, fs) in enumerate(gs, 1):
        (d / ('a%02d.txt' % i)).write_text('\n'.join(sorted(fs)) + '\n')
        print('a%02d %7d bytes %3d files' % (i, size, len(fs)))


def owner_map(work):
    owners = {}
    for g in sorted((Path(work) / 'groups').glob('*.txt')):
        for line in g.read_text().split():
            owners[line[:-len('.java')] + '.kt'] = g.stem
    return owners


def compile_all(work, out_name='classes'):
    src = Path(work) / 'src'
    cp = [p for p in open(EXP / 'fernflower.cp').read().strip().split(':') if 'test-oss/fernflower/build' not in p]
    files = sorted(str(p) for p in src.rglob('*.kt'))
    classes = Path(work) / out_name
    if classes.exists():
        shutil.rmtree(classes)
    args = Path(work) / (out_name + '.args')
    args.write_text('\n'.join('"%s"' % a for a in ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-classpath',
                                                    ':'.join(cp), '-d', str(classes)] + files))
    log = Path(work) / (out_name + '.log')
    with open(log, 'w') as fh:
        rc = subprocess.call([str(KOTLINC), '@' + str(args)], stdout=fh, stderr=subprocess.STDOUT)
    errors = []
    for line in open(log):
        m = DIAG.match(line.rstrip('\n'))
        if m:
            errors.append((os.path.relpath(os.path.abspath(m.group(1)), src), int(m.group(2)), m.group(4)))
    return rc, errors, classes, len(files)


def compile_cmd(work):
    rc, errors, classes, nfiles = compile_all(work)
    owners = owner_map(work)
    missing = [f for f in owners if not (Path(work) / 'src' / f).is_file()]
    per_group = {}
    for f, line, msg in errors:
        text = (Path(work) / 'src' / f).read_text().split('\n')
        per_group.setdefault(owners.get(f, '?'), []).append('%s:%d: %s\n    %s' % (
            f, line, msg, text[line - 1].strip() if line <= len(text) else ''))
    ed = Path(work) / 'errors'
    if ed.exists():
        shutil.rmtree(ed)
    ed.mkdir()
    for g in sorted(set(owners.values())):
        lines = per_group.get(g, [])
        (ed / (g + '.txt')).write_text('\n'.join(lines) + ('\n' if lines else ''))
        files_bad = len({l.split(':')[0] for l in lines})
        print('%s %4d errors in %d files' % (g, len(lines), files_bad))
    bad_files = {e[0] for e in errors}
    print('files: %d written, %d missing; %d errors in %d files; kotlinc rc %d' % (
        nfiles, len(missing), len(errors), len(bad_files), rc))
    for m in missing:
        print('  missing', m)
    return errors, classes, missing


def test(work):
    errors, classes, missing = compile_cmd(work)
    if errors or missing:
        return
    r = subprocess.run([str(EXP / 'runtests.sh'), str(Path(classes).resolve()), str((Path(work) / "tests").resolve())],
                       capture_output=True, text=True)
    print(r.stdout.strip())


if __name__ == '__main__':
    cmd, work = sys.argv[1], sys.argv[2]
    {'groups': lambda: groups(work, int(sys.argv[3])), 'compile': lambda: compile_cmd(work),
     'test': lambda: test(work)}[cmd]()

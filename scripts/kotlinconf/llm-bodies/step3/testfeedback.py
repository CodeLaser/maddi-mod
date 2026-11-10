#!/usr/bin/env python3
"""testfeedback.py <work>: the exceptions in <work>/tests/tests.log, grouped by the fernflower frame on top of the
stack, each given to the group that owns that Kotlin file -> <work>/errors-tests/<group>.txt"""
import re, sys, collections, shutil
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from alone import owner_map

work = Path(sys.argv[1])
owners = owner_map(work)
lines = open(work / 'tests' / 'tests.log', errors='replace').read().split('\n')
EXC = re.compile(r'^(Caused by: )?((?:java|kotlin)\.[\w.$]+(?:Exception|Error))(: .*)?$')
FRAME = re.compile(r'^\s+at (org\.jetbrains\.java\.decompiler\.[\w.$<>]+)\((\w+\.kt):(\d+)\)')
stacks = collections.OrderedDict()
for i, l in enumerate(lines):
    m = EXC.match(l)
    if not m:
        continue
    frames = []
    for j in range(i + 1, min(i + 60, len(lines))):
        f = FRAME.match(lines[j])
        if f:
            frames.append((f.group(1), f.group(2), int(f.group(3))))
            if len(frames) == 12:
                break
        elif not lines[j].startswith('\t'):
            break
    if not frames:
        continue
    cls = frames[0][0].rsplit('.', 1)[0].split('$')[0]
    path = cls.rsplit('.', 1)[0].replace('.', '/') + '/' + frames[0][1]
    key = (m.group(2) + (m.group(3) or ''), frames[0])
    context = lines[i - 1] if i and lines[i - 1].startswith(('WARN', 'ERROR')) else ''
    e = stacks.setdefault(key, {'n': 0, 'frames': frames, 'path': path, 'context': context})
    e['n'] += 1
out = work / 'errors-tests'
if out.exists():
    shutil.rmtree(out)
out.mkdir()
per = collections.defaultdict(list)
for (exc, top), e in stacks.items():
    per[owners.get(e['path'], '?')].append((exc, e))
for g in sorted(set(owners.values())):
    text = []
    for exc, e in sorted(per.get(g, []), key=lambda x: -x[1]['n']):
        text.append('%s  (thrown %d times in the test run)\n%s' % (exc, e['n'], ('  logged after: ' + e['context'] + '\n') if e['context'] else '')
                    + '\n'.join('    at %s(%s:%d)' % f for f in e['frames']) + '\n')
    (out / (g + '.txt')).write_text('\n'.join(text))
    print('%s %3d distinct exceptions, thrown %d times' % (g, len(per.get(g, [])), sum(e['n'] for _, e in per.get(g, []))))
if '?' in per:
    print('unowned:', [e['path'] for _, e in per['?']])

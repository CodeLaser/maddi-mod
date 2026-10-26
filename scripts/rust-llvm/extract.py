"""The Java inputs of maddi-mod's analyzer and link tests as one source tree, each text block in a package of its own.

    python3 -I extract.py <dest dir> <test source dir>...

A text block that starts with a package declaration is one compilation unit; it moves to package `t<test><n>` (its
package's qualified uses renamed along) so that the many `a.b.X` of the tests do not clash. Prints the packages, comma
separated, for --source-packages. Blocks that are format strings or concatenations are skipped.
"""
import pathlib
import re
import sys
import textwrap

dest = pathlib.Path(sys.argv[1])
packages = []
for root in sys.argv[2:]:
    for f in sorted(pathlib.Path(root).rglob('*.java')):
        text = f.read_text()
        for n, m in enumerate(re.finditer(r'"""\n(.*?)"""', text, re.S)):
            body = textwrap.dedent(m.group(1))
            p = re.match(r'\s*package ([\w.]+);', body)
            if not p or '%s' in body or '%d' in body or re.match(r'\s*\+', text[m.end():m.end() + 8]):
                continue
            old = p.group(1)
            new = re.sub(r'\W', '', f.stem.lower()) + f'_{n}'
            body = body.replace(f'package {old};', f'package {new};', 1)
            body = re.sub(r'\b' + re.escape(old) + r'\.(?=[A-Z])', new + '.', body)
            body = body.replace('\\"', '"')
            t = re.search(r'^public\s+(?:(?:final|abstract|sealed|non-sealed|static)\s+)*'
                          r'(?:class|interface|record|enum|@interface)\s+(\w+)', body, re.M) \
                or re.search(r'^(?:(?:final|abstract|sealed|non-sealed)\s+)*(?:class|interface|record|enum)\s+(\w+)',
                             body, re.M)
            if not t:
                continue
            target = dest / new / (t.group(1) + '.java')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(body)
            packages.append(new)
print(','.join(packages))

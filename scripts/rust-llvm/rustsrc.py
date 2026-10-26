"""The functions of a printed Rust module: per function its key, signature line, body lines and parameters."""
import re

SIGNATURE = re.compile(r'(\s*)(?:pub )?(?:unsafe )?fn (?:r#)?(\w+)(?:<[^(]*>)?\((.*)\)')


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


def functions(lines, module):
    """(module, type, fn) -> {'line': 1-based signature line, 'first'/'last': 0-based line range of the item,
    attribute included, 'body': (first, last) 0-based body lines or None, 'params': [(name, mode, col0, col1)]}."""
    fns = {}
    impl = None
    for i, line in enumerate(lines):
        m = re.match(r'(?:impl|trait)(?:<[^>]*>)? (\w+)', line)
        if m:
            impl = m.group(1)
            continue
        m = SIGNATURE.match(line)
        if not m or impl is None:
            continue
        indent = m.group(1)
        first = i - 1 if i > 0 and lines[i - 1].strip() == '#[inline(never)]' else i
        last, body = i, None
        if line.rstrip().endswith('{'):
            for j in range(i + 1, len(lines)):
                if lines[j] == indent + '}':
                    last, body = j, (i + 1, j - 1)
                    break
        params = []
        for p, c0, c1 in split_params(m.group(3), m.start(3)):
            if p in ('self', '&self', '&mut self', 'mut self'):
                params.append(('self', {'&self': '&', '&mut self': '&mut'}.get(p, 'owned'), c0, c1))
                continue
            if ':' not in p:
                continue
            pname, ptype = p.split(':', 1)
            ptype = ptype.strip()
            mode = '&mut' if ptype.startswith('&mut ') or re.match(r"&'\w+ mut ", ptype) else \
                '&' if ptype.startswith('&') else 'owned'
            params.append((pname.replace('mut ', '').strip().removeprefix('r#'), mode, c0, c1))
        fns[(module, impl, m.group(2))] = {'line': i + 1, 'first': first, 'last': last, 'body': body,
                                           'params': params}
    return fns


def enclosing(fns, line0):
    """The function whose item holds 0-based line `line0`, and whether the line is in its body."""
    for key, f in fns.items():
        if f['first'] <= line0 <= f['last']:
            return key, f['body'] is not None and f['body'][0] <= line0 <= f['body'][1]
    return None, False


ITEM = re.compile(r'(?:pub(?:\([\w:]+\))? )?(?:unsafe )?(?:struct|enum|impl|trait|static|const|type|use|fn|mod)\b')


def item_at(lines, line0):
    """The 0-based line range of the top-level item holding `line0`, its doc comments and attributes included."""
    start = next((i for i in range(line0, -1, -1) if ITEM.match(lines[i])), None)
    if start is None:
        return None
    end = start
    if not lines[start].rstrip().endswith((';', '}')):
        end = next((j for j in range(start + 1, len(lines)) if lines[j] == '}'), len(lines) - 1)
    if end < line0:
        return None
    while start > 0 and lines[start - 1].startswith(('///', '#[', '#![')):
        start -= 1
    return start, end

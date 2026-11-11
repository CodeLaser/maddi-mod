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


def closing_line(lines, i):
    """The 0-based line of the brace closing the last '{' of line `i`, skipping strings, chars and comments; None if
    the braces do not balance. The closing brace must start its line (only whitespace before it)."""
    depth, in_block = 0, False
    for j in range(i, len(lines)):
        text, k = lines[j], 0
        while k < len(text):
            ch = text[k]
            if in_block:
                if text.startswith('*/', k):
                    in_block, k = False, k + 2
                    continue
                k += 1
                continue
            if text.startswith('//', k):
                break
            if text.startswith('/*', k):
                in_block, k = True, k + 2
                continue
            m = re.match(r'r(#*)"', text[k:]) if ch == 'r' and (k == 0 or not (text[k - 1].isalnum() or text[k - 1] == '_')) else None
            if m:
                end = text.find('"' + m.group(1), k + len(m.group(0)))
                k = len(text) if end < 0 else end + 1 + len(m.group(1))
                continue
            if ch == '"':
                k += 1
                while k < len(text) and text[k] != '"':
                    k += 2 if text[k] == '\\' else 1
                k += 1
                continue
            if ch == "'":
                # a char literal ('x', '\n', '\u{1F}', '{'); otherwise a lifetime ('a)
                m = re.match(r"'(?:\\u\{[0-9a-fA-F]+\}|\\.|[^\\'])'", text[k:])
                k += len(m.group(0)) if m else 1
                continue
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    return j if j > i and not text[:k].strip() else None
            k += 1
        # a string spanning lines is not printed; a block comment is
    return None


def functions(lines, module):
    """(module, type, fn) -> {'line': 1-based signature line, 'first'/'last': 0-based line range of the item,
    attribute included, 'body': (first, last) 0-based body lines or None, 'params': [(name, mode, col0, col1)]}."""
    fns = {}
    impl = None
    for i, line in enumerate(lines):
        # 'pub trait Filter {', 'pub(crate) unsafe trait ..': a trait's default methods are functions too
        m = re.match(r'(?:pub(?:\([\w:]+\))? )?(?:unsafe )?(?:impl|trait)(?:<[^>]*>)? (\w+)', line)
        if m:
            impl = m.group(1)
            # 'impl PartialEq for AiMessage', 'impl std::fmt::Display for BasicBlock': keyed by the type, so that every
            # type's eq or fmt is a function of its own
            f = re.search(r' for (?:[\w:]+::)?(\w+)', line)
            if f and line.startswith(('impl', 'unsafe impl')):
                impl = f.group(1) + '/' + re.sub(r'<.*', '', line.split(' for ')[0].split()[-1]).split('::')[-1]
            continue
        m = SIGNATURE.match(line)
        if not m or impl is None:
            continue
        indent = m.group(1)
        first = i - 1 if i > 0 and lines[i - 1].strip() == '#[inline(never)]' else i
        last, body = i, None
        if line.rstrip().endswith('{'):
            # matched by braces, not by indentation: a closure's body can be printed at column 0, and its '    }' then
            # looks like the end of the function
            j = closing_line(lines, i)
            if j is not None:
                last, body = j, (i + 1, j - 1)
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


# thread_local! { static .. } holds a static, indented: the macro call is the item
ITEM = re.compile(r'(?:pub(?:\([\w:]+\))? )?(?:unsafe )?(?:struct|enum|impl|trait|static|const|type|use|fn|mod)\b|thread_local!')


def item_at(lines, line0):
    """The 0-based line range of the top-level item holding `line0`, its doc comments and attributes included."""
    start = next((i for i in range(line0, -1, -1) if ITEM.match(lines[i])), None)
    if start is None:
        return None
    end = start
    if not lines[start].rstrip().endswith((';', '}')):
        end = closing_line(lines, start) if lines[start].rstrip().endswith('{') else None
        if end is None:
            end = next((j for j in range(start + 1, len(lines)) if lines[j] == '}'), len(lines) - 1)
    if end < line0:
        return None
    while start > 0 and lines[start - 1].startswith(('///', '#[', '#![')):
        start -= 1
    return start, end

#!/usr/bin/env python3
"""Replace every function, constructor and init body in a Kotlin file with TODO(): maddi's skeleton, for an LLM
to fill in. Usage: strip_bodies.py in.kt out.kt"""
import re
import sys

src = open(sys.argv[1]).read()
n = len(src)

# code[i] is True where src[i] is code (not in a string, char literal or comment)
code = [True] * n
i = 0
stack = []  # string-template nesting: ('str', raw) or ('tmpl', depth)
def skip_string(i, raw):
    """i at the first char inside the string; returns index after the closing quote(s)."""
    while i < n:
        if raw and src.startswith('"""', i):
            j = i + 3
            while j < n and src[j] == '"': j += 1
            for k in range(i, j): code[k] = False
            return j
        if not raw and src[i] == '\\':
            code[i] = False
            if i + 1 < n: code[i + 1] = False
            i += 2; continue
        if not raw and src[i] == '"':
            code[i] = False
            return i + 1
        if src.startswith('${', i):
            code[i] = code[i + 1] = False
            i = skip_code(i + 2, template=True)
            continue
        code[i] = False
        i += 1
    return i

def skip_code(i, template=False):
    depth = 0
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            while i < n and src[i] != '\n': code[i] = False; i += 1
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2); j = n if j < 0 else j + 2
            for k in range(i, j): code[k] = False
            i = j; continue
        if src.startswith('"""', i):
            for k in range(i, i + 3): code[k] = False
            i = skip_string(i + 3, True); continue
        if c == '"':
            code[i] = False
            i = skip_string(i + 1, False); continue
        if c == "'":
            m = re.compile(r"'(\\u[0-9a-fA-F]{4}|\\.|[^\\'])'").match(src, i)
            if m:
                for k in range(i, m.end()): code[k] = False
                i = m.end(); continue
        if template:
            if c == '{': depth += 1
            elif c == '}':
                if depth == 0:
                    code[i] = False
                    return i + 1
                depth -= 1
        i += 1
    return i

skip_code(0)

def match(i, open_c, close_c):
    depth = 0
    while i < n:
        if code[i]:
            if src[i] == open_c: depth += 1
            elif src[i] == close_c:
                depth -= 1
                if depth == 0: return i
        i += 1
    raise ValueError(f"unbalanced {open_c} at {i}")

CONT = re.compile(r'\s*(\.|\?\.|\?:|&&|\|\||\+|-|\*|/|%|==|!=|<|>|\)|\]|as\b|is\b|in\b|!in\b|else\b|catch\b|finally\b)')

def expr_end(i):
    """i just after '='; end of an expression body."""
    depth = 0
    while i < n:
        c = src[i]
        if code[i]:
            if c in '([{': depth += 1
            elif c in ')]}':
                if depth == 0: return i
                depth -= 1
            elif c == '\n' and depth == 0:
                prev = src[:i].rstrip()
                if prev and prev[-1] in '=+-*/%&|.,(:?' or prev.endswith('->'):
                    i += 1; continue
                if CONT.match(src, i + 1) and not src[i + 1:].lstrip().startswith(('//', '/*')):
                    i += 1; continue
                return i
            elif c == ';' and depth == 0:
                return i
        i += 1
    return i

edits = []
for m in re.finditer(r'\b(fun|constructor|init)\b', src):
    s = m.start()
    if not code[s]: continue
    kw = m.group(1)
    j = m.end()
    if kw == 'init':
        k = j
        while k < n and src[k] in ' \t': k += 1
        if k < n and src[k] == '{':
            e = match(k, '{', '}')
            edits.append((k, e + 1, '{ TODO() }'))
        continue
    # find the parameter list
    p = src.find('(', j)
    if p < 0: continue
    if kw == 'constructor' and src[j:p].strip(): continue
    line_start = src.rfind('\n', 0, s) + 1
    if kw == 'constructor' and re.search(r'\b(class|object)\b', src[line_start:s]): continue  # primary constructor
    q = match(p, '(', ')')
    k = q + 1
    # header tail: return type, where clause, delegation `: this(...)`; stop at { = or a line end at depth 0
    depth = 0
    while k < n:
        c = src[k]
        if code[k]:
            if c in '(<[': depth += 1
            elif c in ')>]':
                if not (c == '>' and src[k - 1] == '-'): depth -= 1
            elif depth == 0 and c == '{':
                e = match(k, '{', '}')
                edits.append((k, e + 1, '{ TODO() }'))
                break
            elif depth == 0 and c == '=' and src[k + 1] != '=' and src[k - 1] not in '!<>=':
                e = expr_end(k + 1)
                edits.append((k, e, '= TODO()'))
                break
            elif depth == 0 and (c == '\n' or c == '}' or c == ';'):
                nxt = src[k + 1:].lstrip()
                if c == '\n' and (nxt.startswith(('{', '=', ':', 'where')) or src[:k].rstrip().endswith(':')):
                    k += 1; continue
                break  # abstract
        k += 1

# keep only outermost edits (a local fun inside a stripped body goes with it)
edits.sort()
out, last, kept = [], 0, 0
for s, e, r in edits:
    if s < last: continue
    out.append(src[last:s]); out.append(r); last = e; kept += 1
out.append(src[last:])
open(sys.argv[2], 'w').write(''.join(out))
print(f"{sys.argv[1]}: {kept} bodies stripped", file=sys.stderr)

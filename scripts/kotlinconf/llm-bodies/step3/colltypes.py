#!/usr/bin/env python3
"""Align the collection-typed declarations of two Kotlin translations of the same Java (by file, function name and
parameter names; properties by name) and compare read-only against mutable. Usage: colltypes.py <a dir> <b dir>"""
import re, sys, json
from pathlib import Path
from collections import Counter

RO = {"List", "Set", "Map", "Collection", "Iterable"}
MUT = {"MutableList", "MutableSet", "MutableMap", "MutableCollection", "MutableIterable", "ArrayList", "HashMap",
       "HashSet", "LinkedHashMap", "LinkedHashSet", "TreeMap", "TreeSet", "LinkedList", "ArrayDeque", "Deque", "Queue",
       "SortedMap", "SortedSet", "NavigableMap", "NavigableSet", "EnumMap", "EnumSet", "ConcurrentHashMap",
       "ConcurrentMap"}

def strip(src):
    src = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group()), src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    src = re.sub(r'"(\\.|[^"\\\n])*"', '""', src)
    return src

def close(s, i, o, c):
    d = 0
    while i < len(s):
        if s[i] == o: d += 1
        elif s[i] == c:
            d -= 1
            if d == 0: return i
        i += 1
    return len(s)

def split_top(s):
    out, d, cur = [], 0, ''
    for ch in s:
        if ch in '(<[{': d += 1
        elif ch in ')>]}': d -= 1
        if ch == ',' and d == 0: out.append(cur); cur = ''
        else: cur += ch
    if cur.strip(): out.append(cur)
    return out

def kinds(t):
    """the collection kinds in a type, outermost first: RO or MUT per collection name"""
    return [('RO' if n in RO else 'MUT') for n in re.findall(r'\b([A-Z]\w*)\b', t) if n in RO or n in MUT]

def decls(path):
    s = strip(path.read_text())
    res = {}
    # class stack by brace depth is overkill: key by function name + parameter names, which is near unique per file
    for m in re.finditer(r'\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(', s):
        p = m.end() - 1
        q = close(s, p, '(', ')')
        params = []
        for prm in split_top(s[p + 1:q]):
            prm = re.sub(r'@\w+(\([^)]*\))?', '', prm)
            prm = re.sub(r'\b(vararg|val|var|crossinline|noinline)\b', '', prm).strip()
            if ':' not in prm: continue
            n, t = prm.split(':', 1)
            params.append((n.strip(), t.split('=')[0].strip()))
        key = f"{m.group(1)}({','.join(n for n, _ in params)})"
        for n, t in params:
            res[(key, 'param ' + n)] = t
        rest = s[q + 1:q + 300]
        r = re.match(r'\s*:\s*([\w.<>?, *()\-]+?)\s*(\{|=|\n|$)', rest)
        if r: res[(key, 'return')] = r.group(1)
    for m in re.finditer(r'\b(val|var)\s+(\w+)\s*:\s*([^=\n{]+)', s):
        # constructor properties come out here too; locals as well, keyed by name and so possibly merged
        res[('prop ' + m.group(2), 'type')] = m.group(3).strip()
    return res

def main(a, b):
    a, b = Path(a), Path(b)
    pairs = Counter(); rows = []
    for fa in sorted(a.rglob('*.kt')):
        rel = fa.relative_to(a); fb = b / rel
        if not fb.exists(): continue
        da, db = decls(fa), decls(fb)
        for k in da.keys() & db.keys():
            ka, kb = kinds(da[k]), kinds(db[k])
            if not ka and not kb: continue
            for i in range(max(len(ka), len(kb))):
                x = ka[i] if i < len(ka) else '-'; y = kb[i] if i < len(kb) else '-'
                pairs[(x, y)] += 1
                if x != y: rows.append({'file': str(rel), 'decl': k[0], 'what': k[1], 'depth': i, 'a': da[k], 'b': db[k]})
    for (x, y), n in sorted(pairs.items()): print(f"{x:4} {y:4} {n}")
    json.dump(rows, open('diff.json', 'w'), indent=1)
    print(len(rows), 'differences in diff.json')

main(*sys.argv[1:])

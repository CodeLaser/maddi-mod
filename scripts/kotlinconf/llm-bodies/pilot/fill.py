import re, sys
def fill(skel_path, out_path, bodies):
    s = open(skel_path).read()
    for header_re, body in bodies:
        m = re.search(header_re, s, re.S)
        assert m, header_re
        seg = s[m.start():]
        i = seg.find('TODO()')
        if seg[i-2:i] == '{ ':
            s = s[:m.start()] + seg[:i-2] + '{\n' + body.rstrip() + '\n    }' + seg[i+len('TODO() }'):]
        else:
            s = s[:m.start()] + seg[:i] + body.strip() + seg[i+len('TODO()'):]
    assert 'TODO()' not in s, 'unfilled'
    open(out_path, 'w').write(s)

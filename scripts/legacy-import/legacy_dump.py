"""Reads a mysqldump file of the legacy self-service portal into rows per table.

The dump is plain SQL (CREATE TABLE + extended INSERT statements). Only the table definitions'
column names and the INSERT values are read; nothing is executed.
"""
import re

_UNQUOTED = re.compile(r"[^,)]+")
_ESCAPES = {'n': '\n', 'r': '\r', 't': '\t', '0': '\0', 'Z': '\x1a'}


def _parse_values(s, i):
    """Parse "(..),(..);" starting at s[i]. Returns (rows, index after ';')."""
    rows, n = [], len(s)
    while i < n:
        while s[i] in ' \n\r\t,':
            i += 1
        if s[i] == ';':
            return rows, i + 1
        if s[i] != '(':
            raise ValueError(f'unexpected input near: {s[i:i + 40]!r}')
        i += 1
        row, cur = [], None
        while True:
            c = s[i]
            if c == "'":
                i += 1
                buf = []
                while True:
                    c = s[i]
                    if c == '\\':
                        nxt = s[i + 1]
                        buf.append(_ESCAPES.get(nxt, nxt))
                        i += 2
                    elif c == "'":
                        if s[i + 1] == "'":
                            buf.append("'")
                            i += 2
                        else:
                            i += 1
                            break
                    else:
                        buf.append(c)
                        i += 1
                cur = ''.join(buf)
            elif c == ',':
                row.append(cur)
                cur = None
                i += 1
            elif c == ')':
                row.append(cur)
                i += 1
                break
            else:
                m = _UNQUOTED.match(s, i)
                tok = m.group(0).strip()
                i = m.end()
                cur = None if tok == 'NULL' else tok
        rows.append(tuple(row))
    raise ValueError('INSERT statement not terminated')


def read_dump(path):
    """Returns {table: [ {column: value-or-None}, ... ]} for every table in the dump."""
    with open(path, encoding='utf-8') as f:
        s = f.read()
    columns = {m.group(1): re.findall(r'^\s+`(\w+)`', m.group(2), re.M)
               for m in re.finditer(r'CREATE TABLE `(\w+)` \((.*?)\n\) ENGINE', s, re.S)}
    tables = {t: [] for t in columns}
    for m in re.finditer(r'INSERT INTO `(\w+)` VALUES ', s):
        table = m.group(1)
        rows, _ = _parse_values(s, m.end())
        cols = columns[table]
        for r in rows:
            if len(r) != len(cols):
                raise ValueError(f'{table}: row has {len(r)} values, table has {len(cols)} columns')
            tables[table].append(dict(zip(cols, r)))
    return tables

#!/usr/bin/env python3
import sys, re
pat = re.compile(r'Text\s*\(\s*(?:text\s*=\s*)?"([^"]+)"')
# Skip legitimate decorative/single-char/icon previews
skip_exact = {
    'A','T', '\u0192', '\u270e', '\u2013', '\u2713', '\u2717', '\u00d7', '\u2192', '\u2190', '\u232b', '\u25cf', '\u25cb', '\u2014', '\u2022', '\u00b7', '.', '+', '\u2191', '\u2193', '\u25be', '\u2205', 'Aa', '\u21bb', '?'
}
skip_contains = ['font'] # lines containing 'font' and single-char A are preview
for line in sys.stdin:
    m = pat.search(line)
    if not m:
        continue
    lit = m.group(1)
    s = lit.strip()
    if s in skip_exact:
        continue
    if len(s) <= 2 and ' ' not in s and not re.search(r'[A-Za-z]{2,}', s):
        continue
    # office font-size preview A with bodySmall/headlineSmall
    if s == 'A' and ('bodySmall' in line or 'headlineSmall' in line):
        continue
    if s == 'T' and ('sp' in line):
        continue
    # Remove interpolations like ${...}, $var, %1$s etc
    stripped = re.sub(r'\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_\.]*|\\u[0-9A-Fa-f]{4}|%\d*\$?[sdif]|%[sdif]', '', lit)
    stripped = stripped.strip()
    if len(stripped) < 2:
        continue
    if not re.search(r'[A-Za-z]{2,}', stripped):
        continue
    print(line, end='')

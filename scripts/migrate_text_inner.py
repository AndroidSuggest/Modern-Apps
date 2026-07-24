#!/usr/bin/env python3
"""
Comprehensive migration: extract ALL string constants inside Text() composables,
including fallback constants like ifBlank { "Hotel" }, ?: "Anonymous", if (c) "A" else "B",
and interpolated strings like "Goal $ml mL", "Original: ${name}".
"""
import pathlib, re, collections, hashlib

root = pathlib.Path("/Users/vayun/Documents/Modern-Apps")

MODULES = [
    ("astronomy", "com.vayunmathur.astronomy"),
    ("calendar", "com.vayunmathur.calendar"),
    ("camera", "com.vayunmathur.camera"),
    ("clock", "com.vayunmathur.clock"),
    ("contacts", "com.vayunmathur.contacts"),
    ("dooraccess", "com.vayunmathur.dooraccess"),
    ("education", "com.vayunmathur.education"),
    ("email", "com.vayunmathur.email"),
    ("everysync", "com.vayunmathur.everysync"),
    ("files", "com.vayunmathur.files"),
    ("findfamily", "com.vayunmathur.findfamily"),
    ("health", "com.vayunmathur.health"),
    ("library", "com.vayunmathur.library"),
    ("library/ui", "com.vayunmathur.library.ui"),
    ("library/downloadservice", "com.vayunmathur.library.downloadservice"),
    ("library/biometric", "com.vayunmathur.library.biometric"),
    ("library/network", "com.vayunmathur.library.network"),
    ("library/room", "com.vayunmathur.library.room"),
    ("library/ink", "com.vayunmathur.library.ink"),
    ("library/e2ee-p2p", "com.vayunmathur.library.e2ee-p2p"),
    ("library/widgets", "com.vayunmathur.library.widgets"),
    ("library/work", "com.vayunmathur.library.work"),
    ("library/ocr", "com.vayunmathur.library.ocr"),
    ("maps", "com.vayunmathur.maps"),
    ("messages", "com.vayunmathur.messages"),
    ("music", "com.vayunmathur.music"),
    ("notes", "com.vayunmathur.notes"),
    ("office", "com.vayunmathur.office"),
    ("openassistant", "com.vayunmathur.openassistant"),
    ("passwords", "com.vayunmathur.passwords"),
    ("pdf", "com.vayunmathur.pdf"),
    ("photos", "com.vayunmathur.photos"),
    ("things", "com.vayunmathur.things"),
    ("travel", "com.vayunmathur.travel"),
    ("weather", "com.vayunmathur.weather"),
    ("youpipe", "com.vayunmathur.youpipe"),
    ("games/hub", "com.vayunmathur.games.hub"),
    ("games/chess", "com.vayunmathur.games.chess"),
    ("games/unblockjam", "com.vayunmathur.games.unblockjam"),
    ("games/wordmaker", "com.vayunmathur.games.wordmaker"),
    ("games/alchemist", "com.vayunmathur.games.alchemist"),
    ("games/pipes", "com.vayunmathur.games.pipes"),
    ("games/solitaire", "com.vayunmathur.games.solitaire"),
    ("games/logicgate", "com.vayunmathur.games.logicgate"),
]

def snake_key(s, max_len=40):
    tmp = re.sub(r'%(\d+)\$[sd]|\s*%\d*\$?[sd]|\s*%[sd]', '', s)
    tmp = re.sub(r'\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_\.]*', '', tmp)
    tmp = tmp.lower()
    tmp = re.sub(r'[^a-z0-9]+', '_', tmp)
    tmp = re.sub(r'_+', '_', tmp).strip('_')
    if not tmp or len(tmp) < 2:
        tmp = "text"
    if re.match(r'^\d', tmp):
        tmp = "n_" + tmp
    if len(tmp) > max_len:
        tmp = tmp[:max_len].rstrip('_')
    return tmp

def deterministic_suffix(lit):
    h = hashlib.md5(lit.encode('utf-8')).hexdigest()
    num = int(h[:8], 16) % 10000
    return f"{num:04d}"

def xml_escape(s):
    out = s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace("'", "\\'")
    return out

def extract_args(lit):
    pattern = re.compile(r'\$\{([^}]+)\}|\$([A-Za-z_][A-Za-z0-9_\.]*)')
    args = []
    for m in pattern.finditer(lit):
        expr = m.group(1) if m.group(1) is not None else m.group(2)
        args.append(expr.strip())
    return args

def to_xml_value(lit):
    args = extract_args(lit)
    xml = lit
    counter = [1]
    def repl(m):
        ph = f"%{counter[0]}$s"
        counter[0] += 1
        return ph
    xml = re.sub(r'\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_\.]*', repl, xml)
    def repl_percent(m):
        spec = m.group(1)
        ph = f"%{counter[0]}${spec}"
        counter[0] += 1
        return ph
    percent_pat = re.compile(r'%(?!%)(?!\d+\$)([0-9\.\-]*[sdif])')
    xml = percent_pat.sub(repl_percent, xml)
    return xml, args

def get_existing_keys_values(strings_path):
    content = strings_path.read_text(encoding='utf-8')
    keys = set(re.findall(r'<string name="([^"]+)"', content))
    value_to_key = {}
    for m in re.finditer(r'<string name="([^"]+)">([^<]*)</string>', content, re.DOTALL):
        k = m.group(1)
        v = m.group(2)
        v_unesc = v.replace("\\'", "'").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"')
        value_to_key[v_unesc] = k
    return keys, value_to_key

skip_exact = {
    'A','T', '\u0192', '\u270e', '\u2013', '\u2713', '\u2717', '\u00d7', '\u2192', '\u2190', '\u232b',
    '\u25cf', '\u25cb', '\u2014', '\u2022', '\u00b7', '.', '+', '\u2191', '\u2193', '\u25be', '\u2205',
    'Aa', '\u21bb', '?', '-', '—', '–', '•', '·', '…'
}

def is_valid_literal(lit, line_context=""):
    s = lit.strip()
    if not s:
        return False
    if s in skip_exact:
        return False
    if len(s) <= 2 and ' ' not in s and not re.search(r'[A-Za-z]{2,}', s):
        return False
    if s == 'A' and ('bodySmall' in line_context or 'headlineSmall' in line_context):
        return False
    if s == 'T' and ('sp' in line_context):
        return False
    # ClipData MIME labels - not UI
    if s in ('date','note','detail','QR') and ('newPlainText' in line_context or 'ClipData' in line_context):
        return False
    stripped = re.sub(r'\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_\.]*|\\u[0-9A-Fa-f]{4}|%\d*\$?[sdif]|%[sdif]|\\n|\\t|\\r', '', lit)
    stripped = stripped.strip()
    if len(stripped) < 2:
        return False
    if not re.search(r'[A-Za-z]{2,}', stripped):
        return False
    # Exclude purely numbers with symbols like "360°" - keep? It has numbers but should be translatable maybe "360°"? It has no letters, so would be skipped by letter check - but "360°" is UI but not letters, we skip for now (symbolic)
    return True

def find_text_regions(file_text):
    regions = []
    pat = re.compile(r'\bText\s*\(')
    pos = 0
    while True:
        m = pat.search(file_text, pos)
        if not m:
            break
        paren_start = m.end() - 1  # '('
        depth = 0
        end = -1
        in_str = False
        escape_next = False
        # iterate from paren_start
        for j in range(paren_start, min(len(file_text), paren_start+5000)):
            c = file_text[j]
            if escape_next:
                escape_next = False
                continue
            if c == '\\' and in_str:
                escape_next = True
                continue
            if c == '"' and not in_str:
                in_str = True
                continue
            if c == '"' and in_str:
                in_str = False
                continue
            if in_str:
                continue
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0:
                    end = j
                    break
        if end == -1:
            pos = m.end()
            continue
        call_with_parens = file_text[paren_start:end+1]
        line_no = file_text[:m.start()].count('\n') + 1
        ls = file_text.rfind('\n', 0, m.start()) + 1
        le = file_text.find('\n', end)
        if le == -1:
            le = len(file_text)
        ctx = file_text[ls:le]
        regions.append((m.start(), paren_start, end, call_with_parens, line_no, ctx))
        pos = end + 1
    return regions

total_added = 0

for mod, pkg in MODULES:
    mod_path = root / mod
    if not mod_path.exists():
        continue
    java_dir = mod_path / "src/main/java"
    if not java_dir.exists():
        continue
    strings_path = mod_path / "src/main/res/values/strings.xml"
    if not strings_path.exists():
        strings_path.parent.mkdir(parents=True, exist_ok=True)
        strings_path.write_text('<resources>\n    <string name="app_name">App</string>\n</resources>\n')
        print(f"Created {strings_path}")

    try:
        keys, value_to_key = get_existing_keys_values(strings_path)
    except Exception as e:
        print(f"Failed parsing {strings_path}: {e}")
        continue

    kt_files = list(java_dir.rglob("*.kt"))
    if not kt_files:
        continue

    literal_to_occurrences = collections.defaultdict(list)
    # Gather
    for kf in kt_files:
        try:
            file_text = kf.read_text(encoding='utf-8')
        except:
            continue
        if 'Text(' not in file_text:
            continue
        regions = find_text_regions(file_text)
        for _, _, _, call_text, line_no, line_ctx in regions:
            # If call already has stringResource and no fallback pattern, we still want to check for inner ?: "lit" not yet migrated
            # Find quoted lits inside call_text
            for qm in re.finditer(r'"((?:\\.|[^"\\])*)"', call_text):
                lit = qm.group(1)
                if not is_valid_literal(lit, line_ctx + " " + call_text):
                    continue
                # Skip if this literal is already inside stringResource or R.string? our call_text may contain stringResource(R.string.xxx) and also a literal elsewhere; we already skip those lits that are part of R.string? No, R.string is not quoted.
                # But if the region already contains stringResource for same literal? We still add occurrence, dedup later via value_to_key
                literal_to_occurrences[lit].append((kf, line_no, line_ctx))

    if not literal_to_occurrences:
        continue

    # Determine keys
    new_entries_by_file = collections.defaultdict(list)
    lit_to_key = {}

    # Existing lits reuse
    for lit in list(literal_to_occurrences.keys()):
        if lit in value_to_key:
            lit_to_key[lit] = value_to_key[lit]
            continue
        if lit.strip() in value_to_key:
            lit_to_key[lit] = value_to_key[lit.strip()]
            continue
        xml_val, args = to_xml_value(lit)
        if xml_val in value_to_key:
            lit_to_key[lit] = value_to_key[xml_val]
            continue
        if xml_val.strip() in value_to_key:
            lit_to_key[lit] = value_to_key[xml_val.strip()]
            continue

    # Need new keys for remaining
    for lit, occs in literal_to_occurrences.items():
        if lit in lit_to_key:
            continue
        xml_val, args = to_xml_value(lit)
        base = snake_key(lit)
        key = base
        suffix = 1
        while key in keys:
            key = f"{base}_{suffix}"
            suffix += 1
            if suffix > 100:
                fallback_key = f"{base}_{deterministic_suffix(lit)}"
                extra = 1
                while fallback_key in keys and extra < 20:
                    fallback_key = f"{base}_{deterministic_suffix(lit + str(extra))}"
                    extra += 1
                key = fallback_key if fallback_key not in keys else f"{base}_{deterministic_suffix(lit + '_final')}"
                break
        keys.add(key)
        lit_to_key[lit] = key
        first_file = occs[0][0]
        try:
            rel = first_file.relative_to(java_dir)
            stem = rel.parts[-1].replace('.kt','')
        except:
            stem = "common"
        new_entries_by_file[stem].append((key, xml_val, lit, args))

    if new_entries_by_file:
        insert_text = "\n    <!-- Migrated inner Text constants (auto) -->\n"
        for stem, entries in sorted(new_entries_by_file.items()):
            insert_text += f"    <!-- {stem} -->\n"
            for key, xml_val, orig_lit, args in entries:
                esc = xml_escape(xml_val)
                insert_text += f'    <string name="{key}">{esc}</string>\n'
        current = strings_path.read_text(encoding='utf-8')
        new_content = current.replace('</resources>', insert_text + '</resources>')
        strings_path.write_text(new_content, encoding='utf-8')
        added = sum(len(v) for v in new_entries_by_file.values())
        total_added += added
        print(f"{mod}: added {added} new keys")

    # Replace in files - process per file, regions descending
    sorted_lits = sorted(literal_to_occurrences.keys(), key=lambda x: len(x), reverse=True)
    for kf in kt_files:
        try:
            orig_content = kf.read_text(encoding='utf-8')
        except:
            continue
        content = orig_content
        if not any(lit in content for lit in sorted_lits):
            continue
        # Will need to check if file contains Text
        if 'Text(' not in content:
            continue
        # Find regions in current content (not orig) each iteration? We'll recompute each time from scratch by scanning descending
        regions = find_text_regions(content)
        if not regions:
            continue
        # Sort regions by paren_start descending to avoid index shift
        regions_sorted = sorted(regions, key=lambda x: x[1], reverse=True)
        file_changed = False
        for r_start_text, paren_start, paren_end, call_text, line_no, line_ctx in regions_sorted:
            # Re-extract call_text from current content at this point (since content may have been modified for later regions)
            # But since we go descending, content up to paren_start is unchanged for earlier regions, and our stored call_text corresponds to original slice at that location? After modifications of later regions (higher indices), earlier indices remain valid, but call_text we stored is from original content for that region? However if we modified later regions, the current content's slice from paren_start:paren_end+1 might differ in length if we replaced lits in later regions? No, because later regions are after this region, so their modifications affect indices after paren_end, not before. So current content[paren_start:paren_end+1] should still be the original call_text for this region unless previous replacements inside this same region changed its length? But we haven't yet replaced inside this region in this loop (we process one region at a time). So we can use current content slice.
            cur_call = content[paren_start:paren_end+1]
            # Quick skip if no lits inside
            if not any(lit in cur_call for lit in sorted_lits):
                continue
            # Skip if call is already fully using stringResource and contains no remaining valid literals that are not already replaced? We'll still scan
            # Avoid replacing inside lines that are ClipData already handled? Already filtered in is_valid
            new_call = cur_call
            for lit in sorted_lits:
                if lit not in new_call:
                    continue
                key = lit_to_key.get(lit)
                if not key:
                    continue
                # Build replacement
                args = extract_args(lit)
                if args:
                    args_str = ", ".join(args)
                    repl = f'stringResource(R.string.{key}, {args_str})'
                else:
                    repl = f'stringResource(R.string.{key})'
                # Replace quoted literal with repl - must match exact quoted literal
                esc_lit = re.escape(lit)
                pat_q = re.compile(r'"' + esc_lit + r'"')
                # Only replace if not already replaced? If new_call already contains repl for same key, still allow if still has quoted lit
                if pat_q.search(new_call):
                    new_call_new = pat_q.sub(repl, new_call)
                    if new_call_new != new_call:
                        file_changed = True
                        new_call = new_call_new
            if new_call != cur_call:
                # Replace in content
                content = content[:paren_start] + new_call + content[paren_end+1:]
                # After replacement, need to adjust subsequent regions? Since we go descending, earlier regions (smaller start) indices remain valid
                # No need to recalc, but paren_end for earlier regions unchanged

        if file_changed:
            # Add imports if needed
            if 'import androidx.compose.ui.res.stringResource' not in content:
                m = list(re.finditer(r'^import .+', content, re.MULTILINE))
                if m:
                    last = m[-1]
                    pos = last.end()
                    content = content[:pos] + '\nimport androidx.compose.ui.res.stringResource' + content[pos:]
                else:
                    content = re.sub(r'(package .+\n)', r'\1import androidx.compose.ui.res.stringResource\n', content, count=1)
            r_import = f'import {pkg}.R'
            if r_import not in content:
                if 'import androidx.compose.ui.res.stringResource' in content:
                    content = content.replace('import androidx.compose.ui.res.stringResource', f'import androidx.compose.ui.res.stringResource\n{r_import}')
                else:
                    m = list(re.finditer(r'^import .+', content, re.MULTILINE))
                    if m:
                        last = m[-1]
                        pos = last.end()
                        content = content[:pos] + f'\n{r_import}' + content[pos:]
                    else:
                        content = re.sub(r'(package .+\n)', rf'\1{r_import}\n', content, count=1)
            kf.write_text(content, encoding='utf-8')
            print(f"  Migrated {kf.relative_to(root)}")

print(f"Done, total added {total_added}")

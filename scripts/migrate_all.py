#!/usr/bin/env python3
import pathlib, re, collections, os, hashlib

root = pathlib.Path("/Users/vayun/Documents/Modern-Apps")

MODULES = [
    ("office", "com.vayunmathur.office"),
    ("photos", "com.vayunmathur.photos"),
    ("astronomy", "com.vayunmathur.astronomy"),
    ("email", "com.vayunmathur.email"),
    ("pdf", "com.vayunmathur.pdf"),
    ("contacts", "com.vayunmathur.contacts"),
    ("calendar", "com.vayunmathur.calendar"),
    ("messages", "com.vayunmathur.messages"),
    ("games/hub", "com.vayunmathur.games.hub"),
    ("travel", "com.vayunmathur.travel"),
    ("clock", "com.vayunmathur.clock"),
    ("weather", "com.vayunmathur.weather"),
    ("openassistant", "com.vayunmathur.openassistant"),
    ("notes", "com.vayunmathur.notes"),
    ("music", "com.vayunmathur.music"),
    ("camera", "com.vayunmathur.camera"),
    ("passwords", "com.vayunmathur.passwords"),
    ("everysync", "com.vayunmathur.everysync"),
    ("library", "com.vayunmathur.library"),
    ("library/ui", "com.vayunmathur.library.ui"),
    ("games/solitaire", "com.vayunmathur.games.solitaire"),
    ("things", "com.vayunmathur.things"),
    ("health", "com.vayunmathur.health"),
    ("findfamily", "com.vayunmathur.findfamily"),
    ("youpipe", "com.vayunmathur.youpipe"),
    ("dooraccess", "com.vayunmathur.dooraccess"),
    ("education", "com.vayunmathur.education"),
    ("files", "com.vayunmathur.files"),
    ("maps", "com.vayunmathur.maps"),
]

def snake_key(s, max_len=40):
    # remove xml placeholders %n$s etc
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
    """Deterministic hash-based suffix for collision resolution (replaces non-deterministic hash())."""
    h = hashlib.md5(lit.encode('utf-8')).hexdigest()
    num = int(h[:8], 16) % 10000
    return f"{num:04d}"

def xml_escape(s):
    # Escape &, <, >, ' . Android strings use \' for '
    # Do & first, but avoid double escaping if already &amp; ? Input is raw kotlin literal, not escaped.
    out = s
    out = out.replace('&', '&amp;')
    out = out.replace('<', '&lt;')
    out = out.replace('>', '&gt;')
    # Escape ' as \'
    # Replace ' with \'
    out = out.replace("'", "\\'")
    # For " inside? Usually not needed, but &quot; could be used. Keep as is, Android allows ".
    return out

def extract_args(lit):
    # Returns list of args expressions in order: from ${expr} and $var
    # Use finditer to preserve order
    pattern = re.compile(r'\$\{([^}]+)\}|\$([A-Za-z_][A-Za-z0-9_\.]*)')
    args = []
    for m in pattern.finditer(lit):
        expr = m.group(1) if m.group(1) is not None else m.group(2)
        args.append(expr.strip())
    return args

def to_xml_value(lit):
    # Convert kotlin interpolated string to Android format with %n$s placeholders
    # Also handle existing %d formatting without positional -> make positional
    # Step 1: replace ${} and $var with %n$s placeholders sequentially
    args = extract_args(lit)
    xml = lit
    # Replace placeholders sequentially
    # Use a function that increments
    counter = [1]
    def repl(m):
        ph = f"%{counter[0]}$s"
        counter[0] += 1
        return ph
    xml = re.sub(r'\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_\.]*', repl, xml)

    # Now convert any remaining non-positional % format specifiers like %02d, %d, %.1f etc
    # Pattern: % (optional 0, width, .precision) [sdif] not followed by $ and not preceded by % (escape)
    # We need to make them positional, continuing counter
    # Example: "%02d:%02d" -> "%1$02d:%2$02d" assuming counter starts at 1 if no previous args
    # If we already replaced ${} args, counter continues
    # Let's find all % patterns that are not %% and not positional
    # Use regex: % (?!%) ([0-9\.\-+]* ) [sdif]  -> but ensure not already %n$
    # We'll replace sequentially
    def repl_percent(m):
        # m groups: width+type
        spec = m.group(1)  # e.g. "02d" or "s"
        ph = f"%{counter[0]}${spec}"
        counter[0] += 1
        return ph

    # Pattern for percent without positional: % not followed by digit + $
    # Need to avoid %% 
    # Use negative lookahead for digit+$ and for second %
    percent_pat = re.compile(r'%(?!%)(?!\d+\$)([0-9\.\-]*[sdif])')
    # To avoid replacing %% we skip if next char is %
    # We'll loop
    # For safety, replace only if not already positional
    xml = percent_pat.sub(repl_percent, xml)

    return xml, args

def get_existing_keys_values(strings_path):
    content = strings_path.read_text()
    keys = set(re.findall(r'<string name="([^"]+)"', content))
    value_to_key = {}
    # value unescaped mapping
    for m in re.finditer(r'<string name="([^"]+)">([^<]*)</string>', content, re.DOTALL):
        k = m.group(1)
        v = m.group(2)
        # unescape
        v_unesc = v.replace("\\'", "'").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"')
        # Also replace %1$s -> placeholder normalization? Keep raw
        value_to_key[v_unesc] = k
        # also try stripped version without positional? For reuse detection we match raw literal stripped of ${}?
        # Not needed
    return keys, value_to_key, content

# For each module
for mod, pkg in MODULES:
    mod_path = root / mod
    if not mod_path.exists():
        continue
    java_dir = mod_path / "src/main/java"
    if not java_dir.exists():
        continue
    strings_path = mod_path / "src/main/res/values/strings.xml"
    if not strings_path.exists():
        # create
        strings_path.parent.mkdir(parents=True, exist_ok=True)
        strings_path.write_text('<resources>\n    <string name="app_name">App</string>\n</resources>\n')
        print(f"Created {strings_path}")

    kt_files = list(java_dir.rglob("*.kt"))
    if not kt_files:
        continue

    keys, value_to_key, strings_content = get_existing_keys_values(strings_path)

    # Gather all Text("...") occurrences across files
    # Use regex for Text\s*\(\s*(?:text\s*=\s*)?"([^"]+)"
    # We need to also capture Text("...") where ... may contain escaped \" – rare
    text_pat = re.compile(r'Text\s*\(\s*(?:text\s*=\s*)?"([^"]+)"')
    # Additive: Toast pattern for i18n migration – captures context and literal
    toast_pat = re.compile(r'(?:android\.widget\.)?Toast\.makeText\s*\(\s*([^,]+?)\s*,\s*"([^"]+)"')

    def is_valid_literal(lit):
        """Shared validation used for both Text and Toast literals – ensures at least letters and not symbolic."""
        if not lit.strip():
            return False
        if len(lit.strip()) <= 1:
            return False
        tmp_stripped = re.sub(r'\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_\.]*|%\d*\$?[sdif]|%[sdif]|\\u[0-9A-Fa-f]+|\\n|\\t', '', lit)
        if not re.search(r'[A-Za-z]', tmp_stripped):
            return False
        if lit.strip() in ['×','→','←','⌫','●','○','✓','✗','—','–','•','·','.','+','↑','↓','▾','∅','—','–','…']:
            return False
        return True

    literal_to_occurrences = collections.defaultdict(list)  # literal -> list of (file, line_no, line_text)
    toast_literal_to_occurrences = collections.defaultdict(list)  # Additive: Toast literals -> list of (file, line_no, line, ctx)

    for kf in kt_files:
        try:
            file_text = kf.read_text()
        except Exception as e:
            continue
        has_text = 'Text("' in file_text or 'Text(text' in file_text
        has_toast = 'Toast.makeText' in file_text
        # Quick filter: must contain Text(" or Toast.makeText
        if not has_text and not has_toast:
            continue
        lines = file_text.splitlines()
        for idx, line in enumerate(lines, 1):
            # --- Text gathering (preserved original logic) ---
            if has_text:
                if 'stringResource' in line or 'R.string' in line:
                    pass
                elif 'contentDescription' in line or 'Log.' in line:
                    pass
                elif 'Text("' in line or 'Text(text' in line:
                    for m in text_pat.finditer(line):
                        lit = m.group(1)
                        # Skip if empty or only whitespace
                        if not lit.strip():
                            continue
                        # Must contain at least one letter after removing placeholders and % formats
                        stripped = re.sub(r'\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_\.]*|%\d*\$?[sdif]|%[sdif]|\\u[0-9A-Fa-f]+|\\n|\\t', '', lit)
                        if not re.search(r'[A-Za-z]', stripped):
                            continue
                        if len(lit.strip()) <= 1:
                            continue
                        # Skip symbolic etc
                        if lit.strip() in ['×','→','←','⌫','●','○','✓','✗','—','–','•','·','.','+','↑','↓','▾','∅','—','–','…']:
                            continue
                        literal_to_occurrences[lit].append((kf, idx, line))
            # --- Toast gathering (additive enhancement) ---
            if has_toast and 'Toast.makeText' in line:
                if re.search(r'Toast\.makeText.*R\.string', line) or re.search(r'Toast\.makeText.*getString', line):
                    continue
                # Only process lines with quoted literal after context comma
                for m in toast_pat.finditer(line):
                    ctx_expr = m.group(1).strip()
                    lit = m.group(2)
                    if not is_valid_literal(lit):
                        continue
                    # Must have at least 2 letters for Toast as well
                    stripped = re.sub(r'\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_\.]*', '', lit)
                    if not re.search(r'[A-Za-z]{2,}', stripped):
                        if len(re.sub(r'[^A-Za-z]', '', stripped)) < 2:
                            continue
                    toast_literal_to_occurrences[lit].append((kf, idx, line, ctx_expr))

    if not literal_to_occurrences and not toast_literal_to_occurrences:
        # print(f"{mod}: no hardcoded left")
        continue

    # Determine new entries – unified for Text and Toast to reuse keys (additive)
    new_entries_by_file = collections.defaultdict(list)  # file stem -> list of (key, xml_value, original_lit, args)
    lit_to_key = {}
    all_lits = set(literal_to_occurrences.keys()) | set(toast_literal_to_occurrences.keys())

    for lit in all_lits:
        occs = literal_to_occurrences.get(lit) or toast_literal_to_occurrences.get(lit)
        # Check if exact literal already mapped via value_to_key
        if lit in value_to_key:
            lit_to_key[lit] = value_to_key[lit]
            continue
        if lit.strip() in value_to_key:
            lit_to_key[lit] = value_to_key[lit.strip()]
            continue
        # For interpolated, try to see if xml_value already exists
        xml_val, args = to_xml_value(lit)
        if xml_val in value_to_key:
            lit_to_key[lit] = value_to_key[xml_val]
            continue
        if xml_val.strip() in value_to_key:
            lit_to_key[lit] = value_to_key[xml_val.strip()]
            continue
        # Need new key
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
        # Group by file for XML comment
        first_file = occs[0][0] if occs else None
        try:
            rel = first_file.relative_to(java_dir) if first_file else pathlib.Path("common")
            stem = rel.parts[-1].replace('.kt','') if hasattr(rel, 'parts') else "common"
        except:
            stem = "common"
        new_entries_by_file[stem].append((key, xml_val, lit, args))

    # Add new entries to strings.xml
    if new_entries_by_file:
        insert_text = "\n    <!-- Migrated hardcoded UI strings (auto) -->\n"
        for stem, entries in sorted(new_entries_by_file.items()):
            insert_text += f"    <!-- {stem} -->\n"
            for key, xml_val, orig_lit, args in entries:
                esc = xml_escape(xml_val)
                insert_text += f'    <string name="{key}">{esc}</string>\n'
        # Reload fresh content (in case previous loop modified? we have strings_content variable)
        current = strings_path.read_text()
        new_content = current.replace('</resources>', insert_text + '</resources>')
        strings_path.write_text(new_content)
        print(f"{mod}: added {sum(len(v) for v in new_entries_by_file.values())} new keys")

    # Now replace in Kotlin files – preserved original Text() logic
    # Sort literals by length descending for safe replacement
    sorted_lits = sorted(literal_to_occurrences.keys(), key=lambda x: len(x), reverse=True)
    for kf in kt_files:
        try:
            orig_content = kf.read_text()
        except:
            continue
        content = orig_content
        # Check if file contains any of the lits
        if not any(lit in content for lit in sorted_lits):
            continue

        # Add imports if needed and file will be changed
        will_change = False
        for lit in sorted_lits:
            if f'Text("{lit}")' in content:
                # check line not already has stringResource
                # quick
                will_change = True
                break
        if not will_change:
            continue

        # Add imports
        if 'import androidx.compose.ui.res.stringResource' not in content:
            # Insert after last import
            m = list(re.finditer(r'^import .+', content, re.MULTILINE))
            if m:
                last = m[-1]
                pos = last.end()
                content = content[:pos] + '\nimport androidx.compose.ui.res.stringResource' + content[pos:]
            else:
                # after package
                content = re.sub(r'(package .+\n)', r'\1import androidx.compose.ui.res.stringResource\n', content, count=1)

        r_import = f'import {pkg}.R'
        if r_import not in content:
            # Add after stringResource import
            if 'import androidx.compose.ui.res.stringResource' in content:
                content = content.replace('import androidx.compose.ui.res.stringResource', f'import androidx.compose.ui.res.stringResource\n{r_import}')
            else:
                # fallback add after package
                content = re.sub(r'(package .+\n)', rf'\1{r_import}\n', content, count=1)

        # Replace line by line
        lines = content.splitlines()
        new_lines = []
        file_changed = False
        for line in lines:
            orig_line = line
            # Quick skip
            if 'Text("' not in line:
                new_lines.append(line)
                continue
            if 'stringResource' in line or 'R.string' in line:
                new_lines.append(line)
                continue
            if 'contentDescription' in line or 'Log.' in line:
                new_lines.append(line)
                continue
            # For each lit, replace in this line (multiple different lits could be on same line? unlikely but handle)
            new_line = line
            for lit in sorted_lits:
                if lit not in new_line:
                    continue
                key = lit_to_key.get(lit)
                if not key:
                    continue
                # Re-check after partial replacements
                if 'stringResource' in new_line and key in new_line:
                    # already replaced? skip
                    pass
                # Extract args again for this lit
                args = extract_args(lit)
                # Build replacement Text(...)
                # Escape lit for regex
                esc_lit = re.escape(lit)
                pat = re.compile(r'Text\s*\(\s*(?:text\s*=\s*)?"' + esc_lit + r'"\s*\)')
                if args:
                    args_str = ", ".join(args)
                    repl = f'Text(stringResource(R.string.{key}, {args_str}))'
                else:
                    repl = f'Text(stringResource(R.string.{key}))'
                # Only replace one occurrence at a time to allow multiple different lits
                # Use sub
                # To avoid replacing inside already replaced, check not containing stringResource in immediate match
                # Perform sub
                new_line_candidate = pat.sub(repl, new_line)
                if new_line_candidate != new_line:
                    file_changed = True
                    new_line = new_line_candidate
            new_lines.append(new_line)

        if file_changed:
            new_content = "\n".join(new_lines)
            if orig_content.endswith("\n"):
                new_content += "\n"
            kf.write_text(new_content)
            print(f"  Migrated file {kf.relative_to(root)}")

    # Additive: Toast.makeText literal migration – new functionality preserving Text behavior above
    sorted_toast_lits = sorted(toast_literal_to_occurrences.keys(), key=lambda x: len(x), reverse=True)
    if sorted_toast_lits:
        for kf in kt_files:
            try:
                orig_content = kf.read_text()
            except:
                continue
            content = orig_content
            if 'Toast.makeText' not in content:
                continue
            if not any(lit in content for lit in sorted_toast_lits):
                continue
            # Skip if all Toast lines already migrated – quick check
            will_change_toast = False
            for lit in sorted_toast_lits:
                if lit in content and 'Toast.makeText' in content:
                    # Ensure not already containing getString for this literal context
                    will_change_toast = True
                    break
            if not will_change_toast:
                continue

            # Ensure R import exists for Toast-only files
            r_import = f'import {pkg}.R'
            if r_import not in content:
                # Prefer adding after existing R or stringResource import, else after last import
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

            lines = content.splitlines()
            new_lines = []
            file_changed = False
            for line in lines:
                new_line = line
                if 'Toast.makeText' not in new_line:
                    new_lines.append(new_line)
                    continue
                if 'getString' in new_line and 'R.string' in new_line:
                    # Already migrated this line
                    # But still allow other lits on same line – check via loop below (skip if contains getString for simplicity)
                    # To preserve safety, we will attempt replacement even if getString present, but pattern will not match already migrated
                    pass
                if re.search(r'Toast\.makeText.*R\.string', new_line):
                    # Contains already migrated – skip if also contains getString
                    if 'getString' in new_line:
                        new_lines.append(new_line)
                        continue
                for lit in sorted_toast_lits:
                    if lit not in new_line:
                        continue
                    key = lit_to_key.get(lit)
                    if not key:
                        continue
                    esc_lit = re.escape(lit)
                    toast_repl_pat = re.compile(r'((?:android\.widget\.)?Toast\.makeText\s*\(\s*)([^,]+?)\s*,\s*"' + esc_lit + r'"\s*,')
                    def toast_repl_func(m, _key=key, _lit=lit):
                        prefix = m.group(1)
                        ctx = m.group(2).strip()
                        args = extract_args(_lit)
                        if args:
                            args_str = ", ".join(args)
                            return f'{prefix}{ctx}, {ctx}.getString(R.string.{_key}, {args_str}),'
                        else:
                            return f'{prefix}{ctx}, {ctx}.getString(R.string.{_key}),'
                    candidate = toast_repl_pat.sub(toast_repl_func, new_line)
                    if candidate != new_line:
                        file_changed = True
                        new_line = candidate
                new_lines.append(new_line)

            if file_changed:
                new_content = "\n".join(new_lines)
                if orig_content.endswith("\n"):
                    new_content += "\n"
                kf.write_text(new_content)
                print(f"  Migrated Toast in file {kf.relative_to(root)}")

print("Done")

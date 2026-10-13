// ---- named-range address parsing ------------------------------------------
// Parses ODF named-range addresses (`Sheet.A1:Sheet.B2`) into engine Refs.
// Included into the formula module (see formula.rs); shares Ref/Sheet/col_to_index.

/// Renders one scalar to its display string (arrays reduce to their first
/// element). Split from Workbook::display_value for file length.
fn display_scalar(v: &Value, fmt: Option<&NumberFormat>) -> String {
    match v {
        Value::Num(x) => {
            if x.is_nan() {
                "#ERR".to_string()
            } else {
                format_with_style(*x, fmt)
            }
        }
        Value::Bool(b) => {
            if *b {
                "TRUE".to_string()
            } else {
                "FALSE".to_string()
            }
        }
        Value::Str(s) => s.clone(),
        Value::Err(code) => code.clone(),
        Value::Blank => String::new(),
        Value::Arr(vs) => display_scalar(&Value::first_of(vs), fmt),
    }
}

/// Parses an ODF named-range address (`Sheet.A1:Sheet.B2`, `Sheet.A1`, or a
/// space-separated union — first part wins) into a [Ref]. Sheet names are
/// matched against the workbook's sheets; an unknown sheet falls back to the
/// first sheet, mirroring the engine's lenient cross-sheet behavior.
fn parse_odf_address(
    addr: &str,
    sheets: &[Sheet],
    name_to_idx: &HashMap<String, usize>,
) -> Option<Ref> {
    if sheets.is_empty() {
        return None;
    }
    let mut first = addr.split_whitespace().next()?.trim();
    // Strip a leading "=" some exporters include.
    first = first.strip_prefix('=').unwrap_or(first);
    if first.is_empty() {
        return None;
    }
    // Split endpoints; a single endpoint is a one-cell range.
    let (a, b) = match first.split_once(':') {
        Some((x, y)) => (x.trim(), y.trim()),
        None => (first, first),
    };
    let (sheet_name, cell_a) = split_sheet_cell(a);
    let (_, cell_b) = split_sheet_cell(b);
    let (r1, c1) = parse_a1_coords(cell_a)?;
    let (r2, c2) = parse_a1_coords(cell_b)?;
    let sheet = sheet_name.filter(|s| !s.is_empty()).map(|s| {
        // Keep the canonical sheet name casing for eval_on lookups.
        sheets
            .iter()
            .find(|sh| sh.name.eq_ignore_ascii_case(&s))
            .map(|sh| sh.name.clone())
            .unwrap_or(s)
    });
    if let Some(ref s) = sheet {
        if !name_to_idx.keys().any(|k| k.eq_ignore_ascii_case(s)) {
            return None;
        }
    }
    Some(Ref {
        r1,
        c1,
        r2,
        c2,
        is_range: true,
        sheet,
    })
}

/// Splits `Sheet.A1` / `'My Sheet'.$A$1` / `A1` into (sheet, cell). The sheet
/// part is everything before the LAST dot (names may contain dots); the cell
/// part follows it.
fn split_sheet_cell(endpoint: &str) -> (Option<String>, &str) {
    let e = endpoint.trim().trim_start_matches('$');
    match e.rfind('.') {
        Some(i) => {
            let sheet = e[..i].trim().trim_matches('\'').trim_start_matches('$');
            let cell = e[i + 1..].trim();
            if sheet.is_empty() {
                (None, cell)
            } else {
                (Some(sheet.to_string()), cell)
            }
        }
        None => (None, e),
    }
}

/// Parses an A1 cell token (`$B$12`, `c3`) into 0-based (row, col).
fn parse_a1_coords(token: &str) -> Option<(i32, i32)> {
    let t = token.trim().replace('$', "");
    let chars: Vec<char> = t.chars().collect();
    let mut i = 0;
    while i < chars.len() && !chars[i].is_ascii_alphabetic() {
        i += 1;
    }
    let ls = i;
    while i < chars.len() && chars[i].is_ascii_alphabetic() {
        i += 1;
    }
    let letters: String = chars[ls..i].iter().collect();
    let ds = i;
    while i < chars.len() && chars[i].is_ascii_digit() {
        i += 1;
    }
    // Reject trailing junk (e.g. "#REF!" fragments) and missing parts.
    if i != chars.len() || letters.is_empty() || ds == i {
        return None;
    }
    let digits: String = chars[ds..i].iter().collect();
    if letters.len() > 3 {
        return None;
    }
    let row = digits.parse::<i32>().ok()? - 1;
    if row < 0 || row >= 1048576 {
        return None;
    }
    Some((row, col_to_index(&letters)))
}

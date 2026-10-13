// ---- dynamic-array helpers -------------------------------------------------
// Elementwise FILTER evaluation, Excel ordering, LET substitution.
// Included into the formula module (see formula.rs).

/// Splits thunk text at the first top-level comparison operator (`<=`, `>=`,
/// `<>`, `<`, `>`, `=`), skipping string literals, [bracketed refs] and
/// (parenthesized groups). Returns (op, left, right).
fn split_top_compare(text: &str) -> Option<(String, String, String)> {
    let chars: Vec<char> = text.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if c == '"' {
            i += 1;
            while i < chars.len() {
                if chars[i] == '"' {
                    if i + 1 < chars.len() && chars[i + 1] == '"' {
                        i += 2;
                        continue;
                    }
                    i += 1;
                    break;
                }
                i += 1;
            }
            continue;
        }
        if c == '[' {
            i += 1;
            while i < chars.len() && chars[i] != ']' {
                i += 1;
            }
            i += 1;
            continue;
        }
        if c == '(' {
            let mut depth = 1;
            i += 1;
            while i < chars.len() && depth > 0 {
                if chars[i] == '"' {
                    i += 1;
                    while i < chars.len() && chars[i] != '"' {
                        i += 1;
                    }
                    i += 1;
                    continue;
                }
                if chars[i] == '(' {
                    depth += 1;
                } else if chars[i] == ')' {
                    depth -= 1;
                }
                i += 1;
            }
            continue;
        }
        if c == '<' || c == '>' || c == '=' {
            let op = if i + 1 < chars.len() && chars[i + 1] == '>' && c == '<' {
                "<>".to_string()
            } else if i + 1 < chars.len() && chars[i + 1] == '=' {
                format!("{}=", c)
            } else if c == '=' {
                "=".to_string()
            } else {
                c.to_string()
            };
            let left: String = chars[..i].iter().collect();
            let right: String = chars[i + op.len()..].iter().collect();
            if left.trim().is_empty() || right.trim().is_empty() {
                return None;
            }
            return Some((op, left, right));
        }
        i += 1;
    }
    None
}

/// True when the text is a bare range operand: a [bracketed ref] or a named
/// range (letters/underscore/dots, not a call).
fn is_bare_ref_text(text: &str) -> bool {
    let t = text.trim();
    if t.starts_with('[') {
        return true;
    }
    if t.contains('(') || t.contains('"') {
        return false;
    }
    let mut chars = t.chars();
    match chars.next() {
        Some(c) if c.is_ascii_alphabetic() || c == '_' => {}
        _ => return false,
    }
    t.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.')
}

/// Excel sort rank for total ordering: numbers < text < logicals < blanks;
/// errors sort last.
fn rank_of(v: &Value) -> u8 {
    match v {
        Value::Num(_) => 0,
        Value::Str(_) => 1,
        Value::Bool(_) => 2,
        Value::Blank => 3,
        Value::Err(_) => 4,
        Value::Arr(_) => 5,
    }
}

/// Total ordering mirroring Excel's sort: by type rank, then by value
/// (numbers numerically, text case-insensitively, FALSE < TRUE).
fn cmp_values(a: &Value, b: &Value) -> std::cmp::Ordering {
    let ra = rank_of(a);
    let rb = rank_of(b);
    if ra != rb {
        return ra.cmp(&rb);
    }
    match (a, b) {
        (Value::Num(x), Value::Num(y)) => x.total_cmp(y),
        (Value::Str(x), Value::Str(y)) => {
            x.to_uppercase().cmp(&y.to_uppercase()).then_with(|| x.cmp(y))
        }
        (Value::Bool(x), Value::Bool(y)) => x.cmp(y),
        (Value::Err(x), Value::Err(y)) => x.cmp(y),
        (Value::Arr(x), Value::Arr(y)) => {
            x.len().cmp(&y.len()).then_with(|| {
                x.iter().zip(y.iter()).map(|(p, q)| cmp_values(p, q)).find(|o| *o != std::cmp::Ordering::Equal).unwrap_or(std::cmp::Ordering::Equal)
            })
        }
        _ => std::cmp::Ordering::Equal,
    }
}

/// XLOOKUP match_mode=2: wildcard text match, else exact equality.
fn wildcard_match(key: &Value, candidate: &Value) -> R<bool> {
    match (key, candidate) {
        (Value::Str(pat), Value::Str(s)) => Ok(wildcard_equals(s, pat)),
        _ => {
            // Numbers/bools fall back to exact equality.
            let eq = match (key, candidate) {
                (Value::Num(x), Value::Num(y)) => x == y,
                (Value::Bool(x), Value::Bool(y)) => x == y,
                (Value::Blank, Value::Blank) => true,
                _ => {
                    let ks = format_scalar(key);
                    let cs = format_scalar(candidate);
                    ks.eq_ignore_ascii_case(&cs)
                }
            };
            Ok(eq)
        }
    }
}

fn format_scalar(v: &Value) -> String {
    match v {
        Value::Num(x) => format_number(*x),
        Value::Bool(b) => {
            if *b {
                "TRUE".to_string()
            } else {
                "FALSE".to_string()
            }
        }
        Value::Str(s) => s.clone(),
        Value::Err(c) => c.clone(),
        Value::Blank => String::new(),
        Value::Arr(vs) => format_scalar(&Value::first_of(vs)),
    }
}

/// True for a valid LET binding name: starts with a letter/underscore, continues
/// with letters/digits/underscore/dot, and is not itself a cell reference.
fn is_let_name(name: &str) -> bool {
    if name.is_empty() {
        return false;
    }
    let mut chars = name.chars();
    let first = chars.next().unwrap();
    if !first.is_ascii_alphabetic() && first != '_' {
        return false;
    }
    if !name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.') {
        return false;
    }
    // Reject A1-style cell refs (letters then digits, e.g. X1, AB12).
    let letters = name.chars().take_while(|c| c.is_ascii_alphabetic()).count();
    if letters > 0 && letters <= 3 && name[letters..].chars().all(|c| c.is_ascii_digit()) && !name[letters..].is_empty() {
        return false;
    }
    // Reject function/constant names already meaningful to the parser.
    !matches!(name, "TRUE" | "FALSE" | "PI")
}

/// Renders a scalar binding value as a re-parseable literal. Arrays reduce to
/// their first element (documented scalar simplification); errors propagate.
fn value_literal(v: &Value) -> R<String> {
    match v {
        Value::Num(x) => Ok(format_number(*x)),
        Value::Str(s) => Ok(format!("\"{}\"", s.replace('"', "\"\""))),
        Value::Bool(b) => Ok(if *b { "TRUE".to_string() } else { "FALSE".to_string() }),
        Value::Blank => Ok("0".to_string()),
        Value::Err(c) => Err(EvalError(c.clone())),
        Value::Arr(vs) => value_literal(&Value::first_of(vs)),
    }
}

/// Substitutes LET bindings into calc text at token boundaries, skipping
/// string literals and [bracketed refs]. Matching is case-insensitive
/// (bindings are stored uppercase).
fn substitute_let_names(calc: &str, bindings: &[(String, String)]) -> String {
    if bindings.is_empty() {
        return calc.to_string();
    }
    let chars: Vec<char> = calc.chars().collect();
    let mut out = String::new();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if c == '"' {
            // Copy string literal verbatim ("" = escaped quote).
            out.push(c);
            i += 1;
            while i < chars.len() {
                out.push(chars[i]);
                if chars[i] == '"' {
                    if i + 1 < chars.len() && chars[i + 1] == '"' {
                        out.push(chars[i + 1]);
                        i += 2;
                        continue;
                    }
                    i += 1;
                    break;
                }
                i += 1;
            }
            continue;
        }
        if c == '[' {
            // Copy bracketed ref verbatim.
            out.push(c);
            i += 1;
            while i < chars.len() && chars[i] != ']' {
                out.push(chars[i]);
                i += 1;
            }
            if i < chars.len() {
                out.push(chars[i]);
                i += 1;
            }
            continue;
        }
        if c.is_ascii_alphabetic() || c == '_' {
            let start = i;
            while i < chars.len() && (chars[i].is_ascii_alphanumeric() || chars[i] == '_' || chars[i] == '.') {
                i += 1;
            }
            let tok: String = chars[start..i].iter().collect();
            let upper = tok.to_uppercase();
            match bindings.iter().find(|(n, _)| *n == upper) {
                Some((_, lit)) => {
                    out.push('(');
                    out.push_str(lit);
                    out.push(')');
                }
                None => out.push_str(&tok),
            }
            continue;
        }
        out.push(c);
        i += 1;
    }
    out
}

//! Excel number-format codes → the ODF number-style model.
//!
//! Port of `office/src/main/java/.../odf/ExcelNumFmt.kt`. Handles builtin ids 0–49 and custom
//! format strings, producing the `OdfNumberFormat` / `OdfNumberToken` shapes the ODF serializer
//! writes out. Best-effort, matching the Kotlin: decimals, thousands grouping, percent, currency,
//! scientific, fractions, and date/time token lists.
//!
//! Runs for every formatted cell, and is pure string work with no Android surface — which is why
//! it moved. Deliberately regex-free: the scans below are all single-pass, and the repo's
//! workspace avoids pulling dependencies for what a `while` loop does.

use serde::Serialize;

/// One ordered child of a `number:date-style` / `number:time-style`.
///
/// `kind` is the `number:*` local name (`year`, `month`, `text`, …); `style` mirrors
/// `number:style` (`long`/`short`); `textual` mirrors `number:textual` (month/day names).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct NumberToken {
    pub kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub style: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    pub textual: bool,
}

impl NumberToken {
    fn unit(kind: &str, style: &str) -> Self {
        Self { kind: kind.into(), style: Some(style.into()), text: None, textual: false }
    }

    fn named(kind: &str, style: &str, textual: bool) -> Self {
        Self { kind: kind.into(), style: Some(style.into()), text: None, textual }
    }

    fn bare(kind: &str) -> Self {
        Self { kind: kind.into(), style: None, text: None, textual: false }
    }

    fn text(literal: &str) -> Self {
        Self { kind: "text".into(), style: None, text: Some(literal.into()), textual: false }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct NumberFormat {
    pub decimals: i32,
    pub percent: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub currency_symbol: Option<String>,
    pub grouping: bool,
    pub is_scientific: bool,
    pub is_fraction: bool,
    pub fraction_denominator_digits: i32,
    pub is_date: bool,
    pub is_time: bool,
    pub date_time_tokens: Vec<NumberToken>,
}

/// Builtin `numFmtId` → format code. Ids with no entry are "General".
const BUILTINS: &[(i32, &str)] = &[
    (0, "General"), (1, "0"), (2, "0.00"), (3, "#,##0"), (4, "#,##0.00"),
    (9, "0%"), (10, "0.00%"), (11, "0.00E+00"), (12, "# ?/?"), (13, "# ??/??"),
    (14, "mm-dd-yy"), (15, "d-mmm-yy"), (16, "d-mmm"), (17, "mmm-yy"),
    (18, "h:mm AM/PM"), (19, "h:mm:ss AM/PM"), (20, "h:mm"), (21, "h:mm:ss"),
    (22, "m/d/yy h:mm"), (37, "#,##0;(#,##0)"), (38, "#,##0;[Red](#,##0)"),
    (39, "#,##0.00;(#,##0.00)"), (40, "#,##0.00;[Red](#,##0.00)"),
    (45, "mm:ss"), (46, "[h]:mm:ss"), (47, "mmss.0"), (48, "##0.0E+0"), (49, "@"),
];

pub fn for_builtin(id: i32) -> Option<NumberFormat> {
    match BUILTINS.iter().find(|(k, _)| *k == id).map(|(_, v)| *v) {
        Some("General") => None,
        Some(code) => parse(code),
        // 27–36 and 50–58 are locale/CJK date-time builtins. The Kotlin substitutes a plain date
        // pattern because the literal "date" is not a format code and yields garbage tokens.
        None if is_locale_date_builtin(id) => parse("yyyy-mm-dd"),
        None => None,
    }
}

fn is_locale_date_builtin(id: i32) -> bool {
    (27..=36).contains(&id) || (50..=58).contains(&id)
}

/// Whether a builtin id is date/time, for tagging a value type without a full parse.
pub fn is_date_time_builtin(id: i32) -> bool {
    (14..=22).contains(&id) || (45..=47).contains(&id) || is_locale_date_builtin(id)
}

/// Parses a custom format code. Only the first `;` section (positive numbers) is used.
/// Returns `None` for "General", empty, and text-only (`@`) formats.
pub fn parse(code_raw: &str) -> Option<NumberFormat> {
    let full = code_raw.trim();
    if full.is_empty() || full.eq_ignore_ascii_case("General") {
        return None;
    }
    let section = full.split(';').next().unwrap_or("").trim();

    let currency = extract_currency(section);
    let cleaned = strip_brackets(section);
    // Date detection must ignore quoted literals: `0" days"` is a number, not a date.
    let date_probe = strip_literals(section);

    let is_text = cleaned.contains('@');
    let is_scientific = contains_ignore_case(&cleaned, "E+") || contains_ignore_case(&cleaned, "E-");
    let has_date = !is_scientific && contains_date_token(&date_probe);
    let is_fraction = cleaned.contains('/') && has_fraction_shape(&cleaned);

    if has_date {
        // Quotes are kept so literal segments are not read as date letters.
        return Some(parse_date_time(&strip_brackets_keep_quotes(section)));
    }
    if is_text {
        return None;
    }

    let percent = cleaned.contains('%');
    let grouping = cleaned.contains(',') && (cleaned.contains("#,##0") || cleaned.contains("0,0"));
    let decimals = decimal_places(&cleaned);
    let fraction_digits = if is_fraction { fraction_denominator_digits(&cleaned) } else { 1 };

    Some(NumberFormat {
        decimals: if cleaned.contains('.') || is_scientific { decimals } else { 0 },
        percent,
        currency_symbol: currency,
        grouping,
        is_scientific,
        is_fraction,
        fraction_denominator_digits: fraction_digits,
        is_date: false,
        is_time: false,
        date_time_tokens: Vec::new(),
    })
}

/// Count of `0`/`#` immediately after the first `.`.
fn decimal_places(cleaned: &str) -> i32 {
    match cleaned.split_once('.') {
        Some((_, rest)) => rest.chars().take_while(|c| *c == '0' || *c == '#').count() as i32,
        None => 0,
    }
}

/// Digits after the last `/`, at least 1.
fn fraction_denominator_digits(cleaned: &str) -> i32 {
    let after = cleaned.rsplit('/').next().unwrap_or("");
    let n = after.chars().take_while(|c| matches!(c, '?' | '#' | '0')).count() as i32;
    n.max(1)
}

/// Equivalent of `[?#0]\s*/\s*[?#0]` — a placeholder either side of a slash.
fn has_fraction_shape(cleaned: &str) -> bool {
    let chars: Vec<char> = cleaned.chars().collect();
    for (i, c) in chars.iter().enumerate() {
        if *c != '/' {
            continue;
        }
        let before = chars[..i].iter().rev().find(|c| !c.is_whitespace());
        let after = chars[i + 1..].iter().find(|c| !c.is_whitespace());
        let placeholder = |c: Option<&char>| matches!(c, Some('?') | Some('#') | Some('0'));
        if placeholder(before) && placeholder(after) {
            return true;
        }
    }
    false
}

fn contains_ignore_case(haystack: &str, needle: &str) -> bool {
    haystack.to_ascii_lowercase().contains(&needle.to_ascii_lowercase())
}

/// A code is date/time if it has y/d/s, or h, or m outside scientific notation.
fn contains_date_token(code: &str) -> bool {
    let c = remove_bracket_sections(code);
    let has = |set: &str| c.chars().any(|ch| set.contains(ch.to_ascii_lowercase()));
    has("yds") || has("h") || (has("m") && !c.to_ascii_lowercase().contains('e'))
}

fn parse_date_time(code: &str) -> NumberFormat {
    let chars: Vec<char> = code.chars().collect();
    let mut tokens: Vec<NumberToken> = Vec::new();
    let mut i = 0usize;
    let mut seen_hour = false;
    let mut seen_time = false;
    let ampm = contains_ignore_case(code, "AM/PM") || contains_ignore_case(code, "A/P");

    while i < chars.len() {
        let lower = chars[i].to_ascii_lowercase();
        match lower {
            'y' => {
                let run = run_len(&chars, i, 'y');
                tokens.push(NumberToken::unit("year", if run >= 4 { "long" } else { "short" }));
                i += run;
            }
            'm' => {
                let run = run_len(&chars, i, 'm');
                // `m` after an hour token, or immediately before seconds, is minutes; else month.
                if seen_hour || next_non_space_is_seconds(&chars, i + run) {
                    tokens.push(NumberToken::unit(
                        "minutes",
                        if run >= 2 { "long" } else { "short" },
                    ));
                    seen_time = true;
                } else {
                    tokens.push(NumberToken::named(
                        "month",
                        if run >= 4 { "long" } else { "short" },
                        run >= 3,
                    ));
                }
                i += run;
            }
            'd' => {
                let run = run_len(&chars, i, 'd');
                tokens.push(NumberToken::named(
                    if run >= 3 { "day-of-week" } else { "day" },
                    if run == 4 || run == 2 { "long" } else { "short" },
                    run >= 3,
                ));
                i += run;
            }
            'h' => {
                let run = run_len(&chars, i, 'h');
                tokens.push(NumberToken::unit("hours", if run >= 2 { "long" } else { "short" }));
                seen_hour = true;
                seen_time = true;
                i += run;
            }
            's' => {
                let run = run_len(&chars, i, 's');
                tokens.push(NumberToken::unit("seconds", if run >= 2 { "long" } else { "short" }));
                seen_time = true;
                i += run;
            }
            '"' => {
                let end = chars[i + 1..].iter().position(|c| *c == '"').map(|p| p + i + 1);
                let literal: String = match end {
                    Some(e) => chars[i + 1..e].iter().collect(),
                    None => chars[i + 1..].iter().collect(),
                };
                if !literal.is_empty() {
                    tokens.push(NumberToken::text(&literal));
                }
                i = end.map_or(chars.len(), |e| e + 1);
            }
            '\\' => {
                // Backslash escapes exactly one literal character.
                if i + 1 < chars.len() {
                    tokens.push(NumberToken::text(&chars[i + 1].to_string()));
                    i += 2;
                } else {
                    i += 1;
                }
            }
            _ => {
                if starts_with_ignore_case(&chars, i, "AM/PM") {
                    tokens.push(NumberToken::bare("am-pm"));
                    i += 5;
                } else if starts_with_ignore_case(&chars, i, "A/P") {
                    tokens.push(NumberToken::bare("am-pm"));
                    i += 3;
                } else {
                    let start = i;
                    while i < chars.len()
                        && !"ymdhs".contains(chars[i].to_ascii_lowercase())
                        && chars[i] != '"'
                        && chars[i] != '\\'
                        && !starts_with_ignore_case(&chars, i, "AM/PM")
                    {
                        i += 1;
                    }
                    let literal: String = chars[start..i].iter().collect();
                    if !literal.is_empty() {
                        tokens.push(NumberToken::text(&literal));
                    }
                }
            }
        }
    }

    if ampm && !tokens.iter().any(|t| t.kind == "am-pm") {
        tokens.push(NumberToken::bare("am-pm"));
    }

    let has_date_part = tokens
        .iter()
        .any(|t| matches!(t.kind.as_str(), "year" | "month" | "day" | "day-of-week"));

    NumberFormat {
        is_date: !seen_time || has_date_part,
        is_time: seen_time && !has_date_part,
        date_time_tokens: tokens,
        fraction_denominator_digits: 1,
        ..Default::default()
    }
}

fn run_len(chars: &[char], start: usize, ch: char) -> usize {
    let target = ch.to_ascii_lowercase();
    let mut i = start;
    while i < chars.len() && chars[i].to_ascii_lowercase() == target {
        i += 1;
    }
    i - start
}

fn next_non_space_is_seconds(chars: &[char], from: usize) -> bool {
    let mut i = from;
    while i < chars.len() && (chars[i] == ':' || chars[i] == ' ') {
        i += 1;
    }
    i < chars.len() && chars[i].eq_ignore_ascii_case(&'s')
}

fn starts_with_ignore_case(chars: &[char], at: usize, needle: &str) -> bool {
    let needle: Vec<char> = needle.chars().collect();
    if at + needle.len() > chars.len() {
        return false;
    }
    chars[at..at + needle.len()]
        .iter()
        .zip(needle.iter())
        .all(|(a, b)| a.eq_ignore_ascii_case(b))
}

/// `[$USD-409]` → `USD`; otherwise the first bare currency sign present.
fn extract_currency(code: &str) -> Option<String> {
    if let Some(start) = code.find("[$") {
        let rest = &code[start + 2..];
        let end = rest.find([']', '-']).unwrap_or(rest.len());
        let symbol = rest[..end].trim();
        if !symbol.is_empty() {
            return Some(symbol.to_string());
        }
    }
    ["$", "€", "£", "¥", "₹"].iter().find(|s| code.contains(**s)).map(|s| s.to_string())
}

/// Removes `[...]` sections.
fn remove_bracket_sections(code: &str) -> String {
    let mut out = String::with_capacity(code.len());
    let mut depth = 0usize;
    for c in code.chars() {
        match c {
            '[' => depth += 1,
            ']' if depth > 0 => depth -= 1,
            _ if depth == 0 => out.push(c),
            _ => {}
        }
    }
    out
}

/// Removes `[...]`, quotes and backslashes — the general-purpose cleaned form.
fn strip_brackets(code: &str) -> String {
    remove_bracket_sections(code).replace(['"', '\\'], "")
}

/// Removes `[...]` but keeps quoted literals, for the date token parser.
fn strip_brackets_keep_quotes(code: &str) -> String {
    remove_bracket_sections(code)
}

/// Removes `[...]`, quoted literals and backslash escapes, for date detection.
fn strip_literals(code: &str) -> String {
    let without_brackets = remove_bracket_sections(code);
    let chars: Vec<char> = without_brackets.chars().collect();
    let mut out = String::with_capacity(chars.len());
    let mut i = 0;
    while i < chars.len() {
        match chars[i] {
            '"' => {
                // Skip to the closing quote, or to the end if unterminated.
                match chars[i + 1..].iter().position(|c| *c == '"') {
                    Some(p) => i = i + 1 + p + 1,
                    None => i = chars.len(),
                }
            }
            '\\' => i += 2,
            c => {
                out.push(c);
                i += 1;
            }
        }
    }
    out
}

include!("numfmt_part1.rs");
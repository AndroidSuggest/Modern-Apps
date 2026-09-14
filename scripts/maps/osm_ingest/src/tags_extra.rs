/// Leading decimal number, plus the remainder of the string. Recognises the
/// forms OSM `maxspeed` values actually use (`50`, `50.5`, `1e2`, `+50`).
pub(crate) fn parse_leading_f64(s: &str) -> (f64, &str) {
    let b = s.as_bytes();
    let mut i = 0;
    while i < b.len() && (b[i] == b' ' || b[i] == b'\t') {
        i += 1;
    }
    let start = i;
    if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
        i += 1;
    }
    let mut digits = 0;
    while i < b.len() && b[i].is_ascii_digit() {
        i += 1;
        digits += 1;
    }
    if i < b.len() && b[i] == b'.' {
        i += 1;
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
            digits += 1;
        }
    }
    if digits == 0 {
        return (0.0, s);
    }
    let mantissa_end = i;
    if i < b.len() && (b[i] == b'e' || b[i] == b'E') {
        let mut j = i + 1;
        if j < b.len() && (b[j] == b'+' || b[j] == b'-') {
            j += 1;
        }
        let exp_start = j;
        while j < b.len() && b[j].is_ascii_digit() {
            j += 1;
        }
        if j > exp_start {
            i = j;
        }
    }
    let text = &s[start..i];
    match text.parse::<f64>() {
        Ok(v) => (v, &s[i..]),
        // An exponent that overflows still leaves the mantissa parseable.
        Err(_) => (s[start..mantissa_end].parse::<f64>().unwrap_or(0.0), &s[mantissa_end..]),
    }
}

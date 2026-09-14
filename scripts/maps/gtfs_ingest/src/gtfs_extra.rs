/// Split one CSV line into fields, honouring quotes and `""` escapes. Unlike
/// [`crate::gtfs::parse_csv`] this cannot span newlines, which `shapes.txt` never needs — all
/// five of its columns are numbers or an id.
pub(crate) fn split_line(line: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut field = String::new();
    let mut in_quotes = false;
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        if in_quotes {
            if c == '"' {
                if chars.peek() == Some(&'"') {
                    field.push('"');
                    let _ = chars.next();
                } else {
                    in_quotes = false;
                }
            } else {
                field.push(c);
            }
        } else if c == '"' {
            in_quotes = true;
        } else if c == ',' {
            out.push(std::mem::take(&mut field));
        } else {
            field.push(c);
        }
    }
    out.push(field);
    out
}

/// A `shapes.txt` row before ordering: `(sequence, lat_e7, lon_e7, dist)`, where
/// `dist` is NaN when the row has none. 24 bytes rather than the 32 an
/// `(i64, i32, i32, Option<f64>)` needs — this intermediate has exactly the
/// defect the streaming loader exists to avoid.
pub(crate) type RawShapePoint = (u32, i32, i32, f64);

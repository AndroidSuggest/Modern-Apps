/// The byte a `<0xNN>` piece stands for, or `None` for an ordinary piece.
///
/// Matched on the literal spelling rather than by id so that [`crate::post::sentencepiece::Table::decode`] works the same
/// whether or not the table happened to hold all 256.
pub(crate) fn byte_piece(piece: &[u8]) -> Option<u8> {
    let [b'<', b'0', b'x', hi, lo, b'>'] = piece else { return None };
    let digit = |c: &u8| match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'A'..=b'F' => Some(c - b'A' + 10),
        b'a'..=b'f' => Some(c - b'a' + 10),
        _ => None,
    };
    Some(digit(hi)? * 16 + digit(lo)?)
}

/// Write header + section directory + 8-byte-aligned payloads to `out`, and
/// return the total byte length.
///
/// Every section's length is known by the time this runs, so the directory is
/// computed up front: nothing is reserved and seeked back to, which is what lets
/// the pack go straight to a `BufWriter` instead of being concatenated into one
/// multi-gigabyte `Vec` first.
fn write_pack(
    out: &mut impl Write,
    header: &[u8],
    sections: &[&[u8]; SECTION_COUNT],
) -> std::io::Result<usize> {
    const PAD: [u8; 8] = [0; 8];
    let align = |o: usize| (o + 7) & !7;

    let mut off = HEADER_LEN + SECTION_COUNT * 16;
    let mut dir = Vec::with_capacity(SECTION_COUNT * 16);
    for s in sections {
        off = align(off);
        dir.extend_from_slice(&(off as u64).to_le_bytes());
        dir.extend_from_slice(&(s.len() as u64).to_le_bytes());
        off += s.len();
    }
    let total = off;

    out.write_all(header)?;
    out.write_all(&dir)?;
    let mut pos = header.len() + dir.len();
    for s in sections {
        let pad = align(pos) - pos;
        out.write_all(&PAD[..pad])?;
        out.write_all(s)?;
        pos += pad + s.len();
    }
    debug_assert_eq!(pos, total);
    Ok(total)
}

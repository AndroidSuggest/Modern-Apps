/// Write all of `buf` at `offset` without moving the file's cursor.
///
/// The positional twin of [`tilecodec::pmtiles::read_exact_at`], and the one genuinely new I/O
/// primitive this change needs: nothing else in the tree writes positionally. Same shape and the
/// same reason -- the cursor is the only thing that would need `&mut File`, and it is shared between
/// clones of a handle, so seek-then-write cannot be done concurrently on one file while this can.
/// Neither platform guarantees a full write, hence the loop.
fn write_all_at(file: &File, mut buf: &[u8], mut offset: u64) -> std::io::Result<()> {
    while !buf.is_empty() {
        #[cfg(windows)]
        let n = std::os::windows::fs::FileExt::seek_write(file, buf, offset)?;
        #[cfg(unix)]
        let n = std::os::unix::fs::FileExt::write_at(file, buf, offset)?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::WriteZero,
                "the file took none of the bytes offered",
            ));
        }
        buf = &buf[n..];
        offset += n as u64;
    }
    Ok(())
}

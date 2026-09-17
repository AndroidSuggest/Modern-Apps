//! The PNG decode behind [`crate::tile::sprite`]'s atlas, moved wholesale
//! out of `sprite.rs` to keep that file under the `rustFileLength` limit.
//!
//! No logic changes: [`decode_rgba8`] is called from
//! [`SpriteAtlas::build`](crate::tile::sprite::SpriteAtlas::build) exactly as before.

/// Decode a PNG to tightly-packed RGBA8.
///
/// `EXPAND` normalises palette, greyscale and sub-8-bit inputs to 8-bit colour, so the
/// only cases left here are RGB (widened) and RGBA (taken as is). Anything else is a sheet
/// this renderer was not given, and is refused rather than sampled as noise.
pub(crate) fn decode_rgba8(bytes: &[u8]) -> Result<(Vec<u8>, u32, u32), String> {
    // `Cursor`, because png 0.18's `Decoder` takes `BufRead + Seek` and a `&[u8]` is only
    // the former.
    let mut decoder = png::Decoder::new(std::io::Cursor::new(bytes));
    decoder.set_transformations(png::Transformations::EXPAND);
    let mut reader = decoder
        .read_info()
        .map_err(|e| format!("sprite PNG header: {e}"))?;
    let mut buffer = vec![0u8; reader.output_buffer_size().unwrap_or(0)];
    let info = reader
        .next_frame(&mut buffer)
        .map_err(|e| format!("sprite PNG data: {e}"))?;
    if info.bit_depth != png::BitDepth::Eight {
        return Err(format!(
            "the sprite sheet is {:?}, not 8-bit",
            info.bit_depth
        ));
    }
    let (width, height) = (info.width, info.height);
    let texels = (width as usize) * (height as usize);
    let pixels = match info.color_type {
        png::ColorType::Rgba => {
            buffer.truncate(texels * 4);
            buffer
        }
        png::ColorType::Rgb => {
            let mut out = Vec::with_capacity(texels * 4);
            for rgb in buffer.chunks_exact(3).take(texels) {
                out.extend_from_slice(&[rgb[0], rgb[1], rgb[2], 0xFF]);
            }
            out
        }
        other => return Err(format!("the sprite sheet is {other:?}, not RGB or RGBA")),
    };
    if pixels.len() != texels * 4 {
        return Err(format!(
            "the sprite sheet decoded to {} bytes for {width}x{height}",
            pixels.len()
        ));
    }
    Ok((pixels, width, height))
}

use super::supertonic_vocoder::{COMPRESS, LATENT, PACKED};

/// Unpack a `[144, frames]` latent into the `[24, 6 * frames]` the plan reads.
///
/// **Not** a flat reinterpretation. The export reshapes `[144, L]` to `[24, 6, L]`, transposes
/// the last two axes and flattens, so position `p` of channel `c` comes from
/// `latent[c * 6 + p % 6][p / 6]`. Assuming a plain reshape correlated with the reference at
/// 0.009 rather than 0.99, so this is worth a fixture of its own.
pub fn unpack_latent(latent: &[f32], frames: usize) -> Result<Vec<f32>, String> {
    let packed = PACKED as usize;
    let compress = COMPRESS as usize;
    if latent.len() != packed * frames {
        return Err(format!(
            "{} latent values for {packed} channels over {frames} frames",
            latent.len()
        ));
    }
    let positions = frames * compress;
    let mut out = vec![0.0f32; LATENT as usize * positions];
    for channel in 0..LATENT as usize {
        for position in 0..positions {
            let source = (channel * compress + position % compress) * frames + position / compress;
            out[channel * positions + position] = latent[source];
        }
    }
    Ok(out)
}

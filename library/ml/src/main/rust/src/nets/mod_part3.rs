/// `TILE` in `shaders/conv_point.comp` and `shaders/conv_point_int8.comp`.
///
/// Both tiled shaders are dispatched one workgroup per tile, so [`Builder::emit`] has to know
/// this to compute [`Push::count`]. Shared by the two so the fp16 and int8 lowerings cannot
/// drift apart.
const CONV_POINT_TILE: u32 = 16;

/// `ROWS` in `shaders/conv_vec_int8.comp`: output channels per workgroup.
///
/// That shader is dispatched one workgroup per group of this many channels, so [`Builder::emit`]
/// has to know it to compute [`Push::count`], exactly as it does for [`CONV_POINT_TILE`].
/// **Must equal `ROWS` in `conv_vec_int8.comp`.** The two are separate declarations in
/// separate languages and nothing checks them against each other; a mismatch leaves most
/// output channels never dispatched, which parity catches as zeros. Held by
/// `the_gemv_row_count_matches_both_shaders`.
const CONV_VEC_ROWS: u32 = 2;

/// `ROWS` in `shaders/conv_vec_int4.comp`: output channels per workgroup.
///
/// The int4 gemv runs eight rows per workgroup for the stream reason that shader's header
/// gives, while the int8 gemv stays at [`CONV_VEC_ROWS`]. Separate constants because they
/// are separate decisions now: the two shaders diverged, and one shared name would let an
/// edit to either silently dispatch the other wrong. [`Builder::emit`] uses this for the
/// int4 vector kinds; `the_int4_gemv_row_count_matches_its_shader` holds it against the shader.
const CONV_VEC_INT4_ROWS: u32 = 8;

/// `ROWS` in `shaders/conv_vec_q2k.comp`: output channels per workgroup.
///
/// Eight like the int4 gemv, for the same stream reason: a Q2_K row's superblocks are
/// wider than int4's blocks, so fewer rows per workgroup would leave the same
/// load-ALU overlap on the table. Separate constant for the same reason as
/// [`CONV_VEC_INT4_ROWS`]: an edit to either shader must not silently dispatch the other
/// wrong. [`Builder::emit`] uses this for the Q2_K vector kinds;
/// `the_q2k_gemv_row_count_matches_its_shader` holds it against the shader.
pub(crate) const CONV_VEC_Q2K_ROWS: u32 = 8;

#[cfg(test)]
fn gemv_rows_of(shader: &str) -> u32 {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("shaders").join(shader);
    let source = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
    let line = source
        .lines()
        .find(|line| line.trim_start().starts_with("#define ROWS"))
        .unwrap_or_else(|| panic!("{shader} declares no ROWS"));
    line.split_whitespace()
        .nth(2)
        .and_then(|word| word.trim_end_matches('u').parse().ok())
        .unwrap_or_else(|| panic!("{shader} has an unreadable ROWS: {line}"))
}

/// `erf`, to about 1.5e-7 — Abramowitz and Stegun 7.1.26.
///
/// Rust has no `erf`, and [`Act::Gelu`] is the exact form rather than the tanh approximation, so
/// approximating the *activation* would be a different function. This approximates `erf` itself
/// instead, well below fp16's resolution.
///
/// Test-only now. It began in `post::duration` for VITS's separable stacks and was moved here so
/// that deleting Piper would not take it with it; with Piper gone its only remaining caller is
/// `nets::reference`, which is `#[cfg(test)]`. It stays beside [`Act::Gelu`] rather than moving
/// into that module because the two have to agree with `activate` in `common.glsl`, which is where
/// the *shipped* GELU is computed — the series here and the one there are the same coefficients,
/// and keeping them one scroll apart is what makes that checkable.
#[cfg(test)]
pub(crate) fn erf(x: f32) -> f32 {
    const A: [f32; 5] = [0.254_829_6, -0.284_496_74, 1.421_413_7, -1.453_152, 1.061_405_4];
    const P: f32 = 0.327_591_1;
    let sign = if x < 0.0 { -1.0 } else { 1.0 };
    let x = x.abs();
    let t = 1.0 / (1.0 + P * x);
    let mut poly = 0.0;
    for coefficient in A.iter().rev() {
        poly = (poly + coefficient) * t;
    }
    sign * (1.0 - poly * (-x * x).exp())
}

/// The largest integer fp16 holds exactly, and so the width of one embedding id lane.
///
/// Every integer below this has an exact fp16 representation; at 2049 the gaps open to 2 and
/// keep doubling. A table with more rows than this takes its ids as two lanes — see
/// [`Kind::Embed`] and [`embed_lanes`].
pub const EMBED_LANE: u32 = 2048;

/// Ids for [`Builder::embed`] over a table of more than [`EMBED_LANE`] rows, laid out as the
/// `[2, 1, T]` tensor it wants: all the low lanes, then all the high ones.
pub fn embed_lanes(ids: &[u32]) -> Vec<f32> {
    let mut out = Vec::with_capacity(ids.len() * 2);
    out.extend(ids.iter().map(|&id| (id % EMBED_LANE) as f32));
    out.extend(ids.iter().map(|&id| (id / EMBED_LANE) as f32));
    out
}

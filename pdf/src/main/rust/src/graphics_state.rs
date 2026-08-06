use crate::*;

#[derive(Clone, Copy, PartialEq, Eq, Default)]
#[repr(u8)]
pub(crate) enum BlendMode {
    #[default]
    Normal = 0,
    Multiply = 1,
    Screen = 2,
    Overlay = 3,
    Darken = 4,
    Lighten = 5,
    ColorDodge = 6,
    ColorBurn = 7,
    HardLight = 8,
    SoftLight = 9,
    Difference = 10,
    Exclusion = 11,
    Hue = 12,
    Saturation = 13,
    Color = 14,
    Luminosity = 15,
}

impl BlendMode {
    pub(crate) fn from_name(name: &[u8]) -> Self {
        match name {
            b"Normal" | b"Compatible" => BlendMode::Normal,
            b"Multiply" => BlendMode::Multiply,
            b"Screen" => BlendMode::Screen,
            b"Overlay" => BlendMode::Overlay,
            b"Darken" => BlendMode::Darken,
            b"Lighten" => BlendMode::Lighten,
            b"ColorDodge" => BlendMode::ColorDodge,
            b"ColorBurn" => BlendMode::ColorBurn,
            b"HardLight" => BlendMode::HardLight,
            b"SoftLight" => BlendMode::SoftLight,
            b"Difference" => BlendMode::Difference,
            b"Exclusion" => BlendMode::Exclusion,
            b"Hue" => BlendMode::Hue,
            b"Saturation" => BlendMode::Saturation,
            b"Color" => BlendMode::Color,
            b"Luminosity" => BlendMode::Luminosity,
            _ => BlendMode::Normal,
        }
    }
}

/// Active ExtGState soft mask. The mask group is rendered at the CTM in effect
/// when the mask was set (per ISO 32000 11.6.5.2), optionally over a `/BC`
/// backdrop color. `/TR` transfer functions are not applied (documented).
#[derive(Clone)]
pub(crate) struct SoftMask {
    pub(crate) group_id: ObjectId,
    /// 0 = alpha, 1 = luminosity.
    pub(crate) mask_type: u8,
    pub(crate) ctm: Mat,
    /// `/BC` backdrop color components (in the group's colorspace), if given.
    pub(crate) backdrop: Option<Vec<f64>>,
}

#[derive(Clone)]
pub(crate) struct GraphicsState {
    pub(crate) ctm: Mat,
    pub(crate) fill: u32,
    pub(crate) stroke: u32,
    pub(crate) line_width: f64,
    pub(crate) line_cap: u8,
    pub(crate) line_join: u8,
    pub(crate) miter_limit: f64,
    pub(crate) alpha_fill: f64,
    pub(crate) alpha_stroke: f64,
    pub(crate) non_stroke_cs: CsKind,
    pub(crate) stroke_cs: CsKind,
    pub(crate) font_key: Vec<u8>,
    pub(crate) font_size: f64,
    /// Character spacing (Tc), user-space units.
    pub(crate) char_spacing: f64,
    /// Word spacing (Tw), user-space units (applies to single-byte code 32).
    pub(crate) word_spacing: f64,
    /// Horizontal scaling (Tz) as a fraction (100% = 1.0).
    pub(crate) h_scale: f64,
    /// Text rise (Ts), user-space units.
    pub(crate) rise: f64,
    /// Text leading (TL), user-space units. Part of the text state, so it is
    /// saved/restored by q/Q.
    pub(crate) leading: f64,
    /// Text rendering mode (Tr). 3 = invisible, 7 = clip-only (not drawn).
    pub(crate) render_mode: i64,
    /// Dash pattern (user-space segment lengths) and phase; empty = solid.
    pub(crate) dash: Vec<f64>,
    pub(crate) dash_phase: f64,
    pub(crate) flatness: f64,
    pub(crate) blend_mode: BlendMode,
    /// Active fill/stroke pattern (object id) when the colorspace is `/Pattern`.
    pub(crate) fill_pattern: Option<ObjectId>,
    pub(crate) stroke_pattern: Option<ObjectId>,
    /// Active ExtGState soft mask, or `None` when `/SMask` is `/None`.
    pub(crate) soft_mask: Option<SoftMask>,
    /// Overprint state: `/OP` (stroking), `/op` (nonstroking), `/OPM` (mode).
    pub(crate) overprint_stroke: bool,
    pub(crate) overprint_fill: bool,
    pub(crate) overprint_mode: u8,
}

impl Default for GraphicsState {
    fn default() -> Self {
        GraphicsState {
            ctm: IDENTITY,
            fill: 0xFF00_0000,
            stroke: 0xFF00_0000,
            line_width: 1.0,
            line_cap: 0,
            line_join: 0,
            miter_limit: 10.0,
            alpha_fill: 1.0,
            alpha_stroke: 1.0,
            non_stroke_cs: CsKind::DeviceGray,
            stroke_cs: CsKind::DeviceGray,
            font_key: Vec::new(),
            font_size: 0.0,
            char_spacing: 0.0,
            word_spacing: 0.0,
            h_scale: 1.0,
            rise: 0.0,
            leading: 0.0,
            render_mode: 0,
            dash: Vec::new(),
            dash_phase: 0.0,
            flatness: 0.0,
            blend_mode: BlendMode::Normal,
            fill_pattern: None,
            stroke_pattern: None,
            soft_mask: None,
            overprint_stroke: false,
            overprint_fill: false,
            overprint_mode: 0,
        }
    }
}

/// Number of line segments a bezier curve is flattened into (base). Adapted by flatness `i`.
pub(crate) const BEZIER_STEPS: usize = 16;
pub(crate) const MAX_CLIP_DEPTH: usize = 64;
pub(crate) const MAX_GRAPHICS_STACK: usize = 128;
pub(crate) const MAX_PRIMITIVES: usize = 50000;
pub(crate) const MAX_ANNOTATIONS: usize = 10000;
pub(crate) const MAX_IMAGE_DIM: u32 = 20000;
pub(crate) const MAX_IMAGE_BYTES: usize = 16 * 1024 * 1024;
pub(crate) const MAX_IMAGE_PIXELS: usize = 16 * 1024 * 1024; // ~16 MP cap
pub(crate) const MAX_SHADING_PATCHES: usize = 1000;
pub(crate) const MAX_TYPE3_GLYPHS: usize = 500;
pub(crate) const MAX_TYPE3_PRIMS_PER_GLYPH: usize = 1000;
pub(crate) const MAX_PATTERN_RECURSION: u32 = 4;
pub(crate) const MAX_OC_STACK: usize = 32;
pub(crate) const MAX_SUBPATHS: usize = 20000;
pub(crate) const MAX_DASH_LEN: usize = 64;
pub(crate) const MAX_CONTENT_OPS: usize = 200_000;

use crate::*;

/// Drawing primitives in PDF page space (origin bottom-left). Kotlin performs
/// the Y-flip and fit-to-width scale. Extended v3 adds GroupPush/Pop and blend.
pub(crate) enum Prim {
    Text {
        x: f32,
        y: f32,
        size: f32,
        argb: u32,
        text: String,
        stroke_argb: Option<u32>,
        stroke_width: Option<f32>,
        /// Accurate advance for search rect alignment (not serialized in v2, v3 adds).
        advance: f32,
        /// PDF text rendering mode (Tr): 0 fill, 1 stroke, 2 fill+stroke, 3
        /// invisible, 4-6 = 0-2 plus add-to-clip, 7 clip-only. Serialized in v4.
        render_mode: u8,
        /// Blend mode (from the graphics-state `/BM`). Serialized in v5.
        blend: BlendMode,
    },
    Fill {
        argb: u32,
        even_odd: bool,
        pts: Vec<(f32, f32)>,
        /// Blend mode (from the graphics-state `/BM`). Serialized in v5.
        blend: BlendMode,
    },
    Stroke {
        argb: u32,
        width: f32,
        /// Dash segment lengths in device space (empty = solid).
        dash: Vec<f32>,
        dash_phase: f32,
        cap: u8,
        join: u8,
        miter: f32,
        pts: Vec<(f32, f32)>,
        /// Blend mode (from the graphics-state `/BM`). Serialized in v5.
        blend: BlendMode,
    },
    /// A raster image placed by mapping the unit square through `ctm` (PDF image
    /// space). `format`: 0 = raw RGBA8888 (`w*h*4` bytes), 1 = JPEG bytes.
    Image {
        ctm: Mat,
        w: u32,
        h: u32,
        format: u8,
        data: Vec<u8>,
        /// Per-image alpha (from SMask or explicit) * alpha_fill, for transparency group compositing.
        alpha: f32,
    },
    ClipPush {
        even_odd: bool,
        /// Full path with bezier retention: flat encoding where cubic points are marked via flag?
        /// For v2 compatibility we keep as polygon pts (flattened). For v3 we emit with bezier as separate but here storage remains polyline with extra flag in serialization step.
        pts: Vec<(f32, f32)>,
        /// Raw bezier path for accurate clipping (optional, used for v3 wire).
        path_ops: Option<Vec<PathOp>>,
    },
    ClipPop,
    /// Marker emitted at `ET` when the just-ended text object used a clip render
    /// mode (Tr 4-7): the accumulated glyph outlines are intersected into the
    /// clip on the Kotlin side. v4 only.
    TextClipApply,
    /// Transparency group push (v3). Emits saveLayer with alpha/blend in Kotlin.
    GroupPush {
        isolated: bool,
        knockout: bool,
        alpha: f32,
        blend: BlendMode,
    },
    GroupPop,
    /// Begin an ExtGState soft-masked region (v5). `mask_type`: 0 = alpha,
    /// 1 = luminosity. Primitives until `SoftMaskContent` are the masked
    /// content; those from `SoftMaskContent` to `SoftMaskPop` are the mask.
    SoftMaskPush { mask_type: u8 },
    /// Marker: switch from masked content to mask drawing (v5).
    SoftMaskContent,
    /// End a soft-masked region; composite the mask onto the content (v5).
    SoftMaskPop,
}

/// Path operation for bezier-retentive clip (Phase 5 fidelity).
#[derive(Clone)]
pub(crate) enum PathOp {
    Move(f32,f32),
    Line(f32,f32),
    Cubic(f32,f32,f32,f32,f32,f32),
    Close,
}

pub(crate) struct PageData {
    pub(crate) width: f32,
    pub(crate) height: f32,
    pub(crate) prims: Vec<Prim>,
}

/// Multiply the alpha channel of a primitive's color by `alpha_mul` (0..1). Used to
/// honor an annotation's constant opacity (`/CA`). Images: scale per-image alpha.
pub(crate) fn scale_prim_alpha(prim: &mut Prim, alpha_mul: f64) {
    let scale = |argb: &mut u32| {
        let a = ((*argb >> 24) & 0xFF) as f64;
        let na = (a * alpha_mul).round().clamp(0.0, 255.0) as u32;
        *argb = (*argb & 0x00FF_FFFF) | (na << 24);
    };
    let scale_opt = |argb: &mut Option<u32>| {
        if let Some(v) = argb {
            let a = ((*v >> 24) & 0xFF) as f64;
            let na = (a * alpha_mul).round().clamp(0.0, 255.0) as u32;
            *v = (*v & 0x00FF_FFFF) | (na << 24);
        }
    };
    match prim {
        Prim::Text { argb, stroke_argb, .. } => { scale(argb); scale_opt(stroke_argb); },
        Prim::Fill { argb, .. } => scale(argb),
        Prim::Stroke { argb, .. } => scale(argb),
        Prim::Image { alpha: img_a, .. } => {
            let cur = *img_a as f64;
            let na = (cur * alpha_mul.clamp(0.0,1.0)).clamp(0.0,1.0) as f32;
            *img_a = if na.is_nan() { 1.0 } else { na };
        },
        Prim::ClipPush { .. } => {},
        Prim::ClipPop => {},
        Prim::TextClipApply => {},
        Prim::GroupPush { alpha: ga, .. } => { let cur = *ga as f64; *ga = (cur * alpha_mul.clamp(0.0,1.0)) as f32; },
        Prim::GroupPop => {},
        Prim::SoftMaskPush { .. } => {},
        Prim::SoftMaskContent => {},
        Prim::SoftMaskPop => {},
    }
}

pub(crate) fn apply_alpha_to_argb(argb: u32, alpha_mul: f64) -> u32 {
    if (alpha_mul - 1.0).abs() < 1e-6 {
        return argb;
    }
    let a = ((argb >> 24) & 0xFF) as f64;
    let na = (a * alpha_mul).round().clamp(0.0, 255.0) as u32;
    (argb & 0x00FF_FFFF) | (na << 24)
}

// ---------------------------------------------------------------------------
// Object helpers

// ---------------------------------------------------------------------------

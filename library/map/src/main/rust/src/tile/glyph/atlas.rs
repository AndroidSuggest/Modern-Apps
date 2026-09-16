use super::metrics::{
    charset, GlyphMetrics, UvRect, Weight, ATLAS_PX, MEDIUM_TTF, REGULAR_TTF, SDF_SPREAD_PX, UP_EM,
};
use super::placement::{ink_uv, place_in_cell};
use super::sdf::{blit_cell, sdf_from_coverage};

use ab_glyph::{Font, FontRef, ScaleFont};
use std::collections::HashMap;

/// The built atlas: R8 SDF bytes plus metrics and UVs per (weight, char).
pub struct GlyphAtlas {
    /// Row-major R8 SDF, `ATLAS_PX`² bytes, 0 = far outside, 255 = far inside.
    pub pixels: Vec<u8>,
    /// How much the stored SDF value changes across one em of glyph.
    ///
    /// The distance transform writes `1 / (2 * SDF_SPREAD_PX)` per rasterised pixel
    /// and an em spans `UP_EM / per_raster_px` of them, so this is the conversion the
    /// fragment shader needs to turn a halo width in screen px into an SDF threshold.
    /// Derived rather than hardcoded because it moves with the bundled font's metrics.
    pub sdf_per_em: f32,
    metrics: HashMap<(Weight, char), GlyphMetrics>,
}

impl GlyphAtlas {
    /// Rasterise both weights and build the SDF atlas. Called once per process.
    pub fn build() -> GlyphAtlas {
        let regular =
            FontRef::try_from_slice(REGULAR_TTF).expect("bundled NotoSans-Regular.ttf parses");
        let medium =
            FontRef::try_from_slice(MEDIUM_TTF).expect("bundled NotoSans-Medium.ttf parses");
        let chars = charset();
        let mut pixels = vec![0u8; (ATLAS_PX * ATLAS_PX) as usize];
        let mut metrics = HashMap::new();
        let mut sdf_per_em = 0.0f32;
        for (weight, font) in [(Weight::Regular, regular), (Weight::Medium, medium)] {
            // Font units per rasterised pixel. Derived from the font rather than from
            // `UP_EM / px`, because ab_glyph's `PxScale` is a HEIGHT (ascent + descent)
            // and not pixels-per-em: at scale 48 this face puts an em at 35.2px, so the
            // naive ratio is out by `height_unscaled / units_per_em`.
            let px = 48.0f32;
            let per_raster_px = font.height_unscaled() / font.as_scaled(px).height();
            sdf_per_em = UP_EM as f32 / per_raster_px / (2.0 * SDF_SPREAD_PX as f32);
            for (i, &ch) in chars.iter().enumerate() {
                let cell = match weight {
                    Weight::Regular => i as u32,
                    Weight::Medium => (chars.len() + i) as u32,
                };
                let id = font.glyph_id(ch);
                // Skip .notdef: an unknown glyph rasterises as tofu; shaping skips
                // these codepoints instead (see `tess::text::shape`).
                if id.0 == 0 {
                    continue;
                }
                let advance = font.h_advance_unscaled(id);
                let bearing = font.h_side_bearing_unscaled(id);
                // A glyph with an advance but no ink. It still has to reach the metrics
                // table: shaping looks every codepoint up there and skips the ones it
                // cannot find, so a space that is missing here closes the gap between two
                // words instead of widening it — "Telegraph Hill" shapes as "TelegraphHill".
                let blank = GlyphMetrics {
                    advance,
                    bearing_x: bearing,
                    top: 0.0,
                    w: 0.0,
                    h: 0.0,
                    cell,
                    uv: UvRect {
                        u0: 0.0,
                        v0: 0.0,
                        u1: 0.0,
                        v1: 0.0,
                    },
                };
                // 4x raster of the outline at 48px, then downsample-by-distance to
                // the SDF cell: coverage at 4x is a 2-bit alpha proxy.
                let Some(outlined) = font.outline_glyph(id.with_scale(px)) else {
                    // Space and friends: `outline_glyph` gives nothing at all for these,
                    // so the zero-size check below is never reached for them.
                    metrics.insert((weight, ch), blank);
                    continue;
                };
                let bounds = outlined.px_bounds();
                let w_px = bounds.width().ceil() as u32;
                let h_px = bounds.height().ceil() as u32;
                if w_px == 0 || h_px == 0 {
                    // A zero-size quad emits two degenerate triangles, which rasterise to
                    // nothing.
                    metrics.insert((weight, ch), blank);
                    continue;
                }
                let mut coverage = vec![0u8; (w_px * h_px) as usize];
                outlined.draw(|x, y, v| {
                    if x < w_px && y < h_px {
                        coverage[(y * w_px + x) as usize] = (v * 255.0) as u8;
                    }
                });
                let placement = place_in_cell(w_px, h_px);
                let sdf = sdf_from_coverage(&coverage, w_px, h_px, placement);
                blit_cell(&mut pixels, cell, &sdf);
                // The spread margin measured in rasterised pixels. The cell margin is a
                // constant number of CELL px, so a glyph scaled down to fit spans
                // proportionally more of its own pixels — hence the divide by `scale`.
                let pad = SDF_SPREAD_PX as f32 / placement.scale;
                metrics.insert(
                    (weight, ch),
                    GlyphMetrics {
                        advance,
                        bearing_x: bearing - pad * per_raster_px,
                        // `px_bounds` is y-down from the baseline, so the ink's top
                        // above the baseline is `-min.y`.
                        top: (-bounds.min.y + pad) * per_raster_px,
                        w: (w_px as f32 + 2.0 * pad) * per_raster_px,
                        h: (h_px as f32 + 2.0 * pad) * per_raster_px,
                        cell,
                        uv: ink_uv(cell, placement),
                    },
                );
            }
        }
        GlyphAtlas {
            pixels,
            sdf_per_em,
            metrics,
        }
    }

    pub fn metrics(&self, weight: Weight, ch: char) -> Option<GlyphMetrics> {
        self.metrics.get(&(weight, ch)).copied()
    }

    pub fn uv(&self, weight: Weight, ch: char) -> Option<UvRect> {
        Some(self.metrics.get(&(weight, ch))?.uv)
    }

    /// Horizontal kerning between two codepoints, in font units.
    pub fn kern(&self, _weight: Weight, _prev: char, _next: char) -> f32 {
        // Noto Sans Latin kerning is small vs label sizes; ab_glyph's
        // `kern_unscaled` needs glyph ids, which the atlas deliberately does not
        // retain (metrics-only shaping). Revisit if tracking looks off.
        0.0
    }
}

/// The process-wide atlas, built once.
pub fn atlas() -> &'static GlyphAtlas {
    static ATLAS: std::sync::OnceLock<GlyphAtlas> = std::sync::OnceLock::new();
    ATLAS.get_or_init(GlyphAtlas::build)
}

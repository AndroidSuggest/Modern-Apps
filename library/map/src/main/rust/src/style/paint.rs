//! The flat style: a file that says only what this renderer can draw, and a loader for it.
//!
//! `style/basemap.flat.json` is one entry per drawn layer, in draw order, with a fixed set of
//! properties: a source layer, a `kind` whitelist, a zoom range, a light and a dark colour, and
//! opacity, width, gap width and dash. Nothing is derived at runtime and there are no
//! expressions — a property that varies with zoom is a list of stops and an interpolation, and
//! that is the only shape a property can take.
//!
//! # Why a stops list rather than a number
//!
//! A stroke width is not a property of a layer, it is a function of the camera's zoom, and no
//! single number can stand in for one: a width that reads correctly at street level is
//! continent-wide at world level. The same is true of a fill's opacity, which is the whole of
//! how the low zooms are meant to look — `landcover` fades out between z5 and z7, and
//! `landuse_park` fades in between z6 and z11. Collapsing either to a constant plus an integer
//! zoom gate drew `landcover` at **full** strength at z6 where the ramp asks for half, laying a
//! flat mint blanket over a continent.
//!
//! Evaluating a [`Ramp`] per frame is affordable because both reach the GPU as push constants:
//! `shaders/line.vert` extrudes the centreline to the width it is given, so a width that
//! changes every frame re-tessellates nothing. It is per *frame* and not per tile because the
//! camera's zoom is fractional and continuous, and quantising it to the integer tile zoom is
//! exactly what makes a layer pop instead of fade.
//!
//! # Where the values come from
//!
//! `style/basemap.json`, the 71-layer MapLibre style this used to interpret at runtime, is kept
//! vendored beside the flat file for one reason: `the_flat_style_agrees_with_basemap_json`
//! cross-checks every value that exists in both, so a transcription slip fails a build instead
//! of being found on a screenshot. One column is deliberately **ours** and is not
//! cross-checked:
//!
//! * **The dark colours.** `basemap.json` is light-only. These are
//!   `maps/src/main/java/com/vayunmathur/maps/ui/theme/BasemapPalette.kt`'s contrast-checked
//!   values by role - see [`super`]'s module docs for why that palette rather than a third
//!   party's.
//!
//! The light line colours used to be a second such column - a warmer set with cream fills over
//! tan casings. It was measured against the comparator and reverted to the authored
//! white-on-`#e0e0e0`: a casing *darker* than the land it sits on outlines every road, so a
//! street grid at z14 filled with tan linework and the map read as blurred rather than warm.
//! The bridge layers had never diverged, so the two halves of the same road disagreed as well.
//!
//! One flattening is worth naming. The authored style splits two road casings into
//! `*_casing_early`/`*_casing_late` pairs at z12, one gated by `maxzoom` and the other by
//! `minzoom`. A flat layer carries one ramp, so it carries the `_late` half, which is the one
//! that spans the whole range. Where the casing is wide enough to draw at all the halves differ
//! by at most 0.4 Dp — a little over half a pixel per edge on a density-3 screen — and the worst
//! of that is immediately below the z12 split, which is where two ramps meeting at a point are
//! furthest apart. `a_collapsed_casing_pair_matches_its_authored_late_half` pins the bound.

use super::paint_extra::layer;
use super::Layer;
use serde_json::Value as Json;
use std::sync::OnceLock;

/// The flat style, vendored beside the sources.
///
/// Compiled in rather than read from assets: it is one file for every host app, and it makes
/// the loader host-testable rather than reachable only from a device.
pub(crate) const FLAT: &str = include_str!("../../style/basemap.flat.json");

/// The deepest zoom the renderer draws at, and the default top of a layer's zoom range.
///
/// The archive stops at z14 and the renderer overzooms past it.
pub const MAX_ZOOM: u8 = 22;

/// The narrowest half-width the **geometry** is allowed to be, in device pixels.
///
/// A quad narrower than a pixel is filled only where it happens to straddle a pixel
/// centre, which rasterises as stipple that crawls along the road while panning. So
/// `shaders/line.vert` expands any thinner stroke to this, and `line.frag` takes the
/// difference straight back off as alpha — the road ends up a faint continuous line,
/// which is what the ramp was asking for.
///
/// This used to be applied here, in [`Stroke::half_px`], where it could only round a
/// sub-pixel road *up* to a solid pixel. That is why low zooms read as heavier than
/// MapLibre's: every road the style had ramped down to a hairline drew at full
/// strength. The floor belongs with the rasteriser, not with the style.
///
/// GLSL cannot include a Rust constant, so both shaders carry the literal;
/// [`the_shader_width_floor_matches_this_constant`] pins them together.
pub const MIN_HALF_WIDTH_PX: f32 = 0.5;

/// A line's stroke at one zoom, in Dp.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Stroke {
    pub width_dp: f32,
    pub gap_width_dp: f32,
}

impl Stroke {
    pub const NONE: Stroke = Stroke { width_dp: 0.0, gap_width_dp: 0.0 };

    /// Would this stroke put anything on screen?
    ///
    /// A casing with a gap but no width is two bands of zero thickness, so width alone decides.
    /// A zoom where this is false is a zoom the style ramped the layer out at, which is the
    /// style's own gate and the reason road layers need no `min_zoom`.
    pub fn visible(&self) -> bool {
        self.width_dp > 0.0
    }

    /// Half-width and half-gap in device pixels, which is what the vertex shader extrudes by.
    ///
    /// Halved because the shader offsets each edge from the centreline. Neither is floored:
    /// the shader widens a sub-pixel stroke to [`MIN_HALF_WIDTH_PX`] of geometry and fades it
    /// by coverage instead, so the value handed over stays the width the style asked for.
    pub fn half_px(&self, density: f32) -> (f32, f32) {
        (self.width_dp * density / 2.0, self.gap_width_dp * density / 2.0)
    }
}

/// A property as a function of zoom: a list of stops and how to interpolate between them.
///
/// The whole of what the flat format can say about a varying value, and enough for every ramp
/// the authored style uses. A constant is a single stop, so nothing downstream has to branch on
/// whether a property varies.
#[derive(Clone, Debug, PartialEq)]
pub struct Ramp {
    /// The exponential base, or `1.0` for linear.
    pub(crate) base: f64,
    /// Zoom and value, ascending by zoom, never empty.
    pub(crate) stops: Vec<(f64, f32)>,
}

impl Ramp {
    /// A property that does not vary.
    pub fn constant(value: f32) -> Ramp {
        Ramp { base: 1.0, stops: vec![(0.0, value)] }
    }

    /// The value at `zoom`, clamped to the first and last stop outside the ramp's range.
    ///
    /// The exponential curve is the style spec's: `t = (base^dz - 1) / (base^span - 1)`, which
    /// is what makes a road grow slowly at low zoom and quickly at high.
    pub fn at(&self, zoom: f64) -> f32 {
        let last = self.stops.len() - 1;
        if zoom <= self.stops[0].0 {
            return self.stops[0].1;
        }
        if zoom >= self.stops[last].0 {
            return self.stops[last].1;
        }
        let index = self.stops.windows(2).position(|pair| zoom <= pair[1].0).unwrap_or(0);
        let (lower_zoom, lower) = self.stops[index];
        let (upper_zoom, upper) = self.stops[index + 1];
        let span = upper_zoom - lower_zoom;
        let t = if span <= 0.0 {
            0.0
        } else if self.base == 1.0 {
            (zoom - lower_zoom) / span
        } else {
            (self.base.powf(zoom - lower_zoom) - 1.0) / (self.base.powf(span) - 1.0)
        };
        lower + (upper - lower) * t as f32
    }

    /// The largest value any stop takes.
    ///
    /// Interpolation never leaves the interval between two stops, so this bounds the whole ramp
    /// — which is what makes it the right answer to "does this layer have a gap at all", a
    /// question [`Layer::gapped`] has to answer once rather than per zoom.
    pub fn peak(&self) -> f32 {
        self.stops.iter().fold(f32::NEG_INFINITY, |peak, (_, value)| peak.max(*value))
    }

    pub(crate) fn parse(json: Option<&Json>, id: &str, property: &str, default: f32) -> Result<Ramp, String> {
        let Some(json) = json else {
            return Ok(Ramp::constant(default));
        };
        if let Some(value) = json.as_f64() {
            return Ok(Ramp::constant(value as f32));
        }
        let where_ = || format!("`{id}`'s {property}");
        let base = match json.get("interpolate").and_then(Json::as_str) {
            Some("linear") => 1.0,
            Some("exponential") => json
                .get("base")
                .and_then(Json::as_f64)
                .ok_or_else(|| format!("{}: an exponential ramp needs a `base`", where_()))?,
            other => {
                return Err(format!("{}: unknown interpolation {other:?}", where_()));
            }
        };
        let stops: Vec<(f64, f32)> = json
            .get("stops")
            .and_then(Json::as_array)
            .ok_or_else(|| format!("{}: a ramp needs `stops`", where_()))?
            .iter()
            .map(|stop| match stop.as_array().map(|pair| pair.as_slice()) {
                Some([zoom, value]) => match (zoom.as_f64(), value.as_f64()) {
                    (Some(zoom), Some(value)) => Ok((zoom, value as f32)),
                    _ => Err(format!("{}: a stop must be two numbers", where_())),
                },
                _ => Err(format!("{}: a stop must be `[zoom, value]`", where_())),
            })
            .collect::<Result<_, _>>()?;
        if stops.is_empty() {
            return Err(format!("{}: a ramp needs at least one stop", where_()));
        }
        // Ascending zooms are what `at`'s scan relies on, and what makes clamping to the
        // first and last stop mean what it says.
        if stops.windows(2).any(|pair| pair[1].0 <= pair[0].0) {
            return Err(format!("{}: stops must ascend by zoom", where_()));
        }
        Ok(Ramp { base, stops })
    }
}

/// The whole style: the backdrop, and every layer in draw order.
pub struct Style {
    /// ARGB behind everything, light and dark.
    pub background: (u32, u32),
    pub layers: Vec<Layer>,
}

/// Parse a flat style file.
pub fn parse(source: &str) -> Result<Style, String> {
    let root: Json =
        serde_json::from_str(source).map_err(|e| format!("the flat style is not JSON: {e}"))?;
    let background = root.get("background").ok_or("the flat style has no `background`")?;
    let layers = root
        .get("layers")
        .and_then(Json::as_array)
        .ok_or("the flat style has no `layers` array")?;
    Ok(Style {
        background: (
            color(background.get("light"), "background.light")?,
            color(background.get("dark"), "background.dark")?,
        ),
        layers: layers.iter().map(layer).collect::<Result<_, _>>()?,
    })
}

/// Parse a `#rrggbb` or `#rrggbbaa` colour into ARGB.
///
/// The one spelling the flat file uses. `basemap.json`'s `rgba(...)` form is gone from the
/// production path along with the evaluator that needed it; the cross-check test reads it,
/// because the authored file still writes seven colours that way.
pub(crate) fn color(json: Option<&Json>, what: &str) -> Result<u32, String> {
    let source = json
        .and_then(Json::as_str)
        .ok_or_else(|| format!("`{what}` has no colour string"))?;
    parse_hex(source).ok_or_else(|| format!("`{what}`'s colour `{source}` will not parse"))
}

/// `#rrggbb` or `#rrggbbaa` to ARGB, or `None`.
///
/// `None` rather than a default: a colour that will not parse must fail the load, because any
/// substituted colour is a plausible-looking wrong map.
pub(crate) fn parse_hex(source: &str) -> Option<u32> {
    let hex = source.strip_prefix('#')?;
    // Explicitly, rather than leaving it to `from_str_radix`, which accepts a leading `+`.
    if !hex.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    let pair = |i: usize| u32::from_str_radix(&hex[i..i + 2], 16).ok();
    match hex.len() {
        6 => Some(0xFF00_0000 | (pair(0)? << 16) | (pair(2)? << 8) | pair(4)?),
        8 => Some((pair(6)? << 24) | (pair(0)? << 16) | (pair(2)? << 8) | pair(4)?),
        _ => None,
    }
}

/// The vendored flat style, parsed once.
///
/// Immutable and derived from a compiled-in string, so this is a constant table that happens to
/// need a parser. Keeping it here rather than threading it from Kotlin also keeps the render and
/// tessellation threads reading the same paint without having to agree on it across JNI.
///
/// A parse failure panics. The file ships inside the binary and
/// `the_vendored_flat_style_parses` fails the build if it will not load, so the alternative — a
/// blank map with no diagnostic — is strictly worse than a crash that names the line.
pub fn style() -> &'static Style {
    static STYLE: OnceLock<Style> = OnceLock::new();
    STYLE.get_or_init(|| {
        let mut style = parse(FLAT).unwrap_or_else(|e| panic!("style/basemap.flat.json: {e}"));
        if !super::LANE_RENDERING {
            style.layers.retain(|layer| !layer.carriageway);
        }
        style
    })
}

/// The style as authored, carriageways included, whatever [`super::LANE_RENDERING`] says.
///
/// Behind [`super::layers_with_lane_rendering`]; see that for why the tests need it.
#[cfg(test)]
pub fn style_with_lane_rendering() -> &'static Style {
    static STYLE: OnceLock<Style> = OnceLock::new();
    STYLE.get_or_init(|| parse(FLAT).unwrap_or_else(|e| panic!("style/basemap.flat.json: {e}")))
}

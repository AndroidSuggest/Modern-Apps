//! A drawn layer: the [`Layer`] table row, its render-path predicates and the
//! interned-id helpers behind them.

use super::paint::{Ramp, Stroke};
use super::{background, Anchor, KindFilter, LayerKind, Palette, Toggle, Variant, MUTED_BLEND};
use tilecodec::mamaps::body::Feature;
use tilecodec::mamaps::dict;

/// Blend `color` toward `toward` by `amount`.
fn blend(color: u32, toward: u32, amount: f32) -> u32 {
    let channel = |v: u32, shift: u32| ((v >> shift) & 0xFF) as f32;
    let mix = |shift: u32| {
        let from = channel(color, shift);
        let to = channel(toward, shift);
        (from + (to - from) * amount).round().clamp(0.0, 255.0) as u32
    };
    // Alpha is preserved: muting changes hue, not opacity.
    (color & 0xFF00_0000) | (mix(16) << 16) | (mix(8) << 8) | mix(0)
}

/// One drawn layer, as the flat style file states it.
pub struct Layer {
    pub id: String,
    /// The tile layer to read, e.g. `roads`.
    pub source_layer: String,
    /// The same layer, as the id a `.mamaps` body carries.
    ///
    /// Resolved once at load through [`tilecodec::mamaps::dict::LAYERS`], so the render path never
    /// compares a string. A `source` the schema has no id for fails the load.
    pub source_layer_id: u8,
    pub kind: LayerKind,
    /// Feature `kind` values to draw, or empty for every feature in the layer.
    pub kinds: Vec<String>,
    /// The same whitelist as interned ids, sorted.
    ///
    /// The hot path: `tile::geometry` tests every feature of every layer of every tile against
    /// this, and reading a `kind` used to mean a `String` allocation per feature per tile. A name
    /// the schema cannot emit fails the load rather than silently drawing nothing.
    pub kind_ids: Vec<u16>,
    /// Which of the tiler's road flags a feature must (not) carry to be drawn here.
    ///
    /// The authored style filters every road layer on `is_bridge`/`is_tunnel`/`is_link`
    /// (surface layers exclude them, link layers require `is_link`), but a flat entry only
    /// names `kind`s — so without this the surface layers would also draw every ramp, bridge
    /// and tunnel at full class width with casing, which is exactly the too-wide roads the
    /// comparator showed at super-zoom. Stored as require/forbid bitmasks over the feature
    /// flag bits so the render path stays two integer ops per feature.
    pub require_flags: u8,
    pub forbid_flags: u8,
    /// Which interned `kind_detail` values to draw, or empty for every detail.
    ///
    /// The authored style splits `minor_road` into `service` and non-`service` layers with
    /// different widths; `service` is an interned detail id, so this is a second sorted
    /// whitelist beside [`kind_ids`](Self::kind_ids).
    pub detail_ids: Vec<u16>,
    /// Interned `kind_detail` values explicitly excluded even when `detail_ids` is empty.
    ///
    /// `roads-minor` draws every minor road *except* `service`; an exclusion list states
    /// that without enumerating all thirty details.
    pub forbid_details: Vec<u16>,
    /// ARGB in light mode.
    pub light: u32,
    /// ARGB in dark mode.
    pub dark: u32,
    /// Fill opacity, in 0..=1. Always 1 for a line.
    ///
    /// Applied per frame against the camera's fractional zoom rather than folded into
    /// [`light`](Self::light)/[`dark`](Self::dark), because a fill has to fade *across* a zoom:
    /// a constant alpha plus an integer zoom gate is what drew `landcover` at full strength at
    /// z6 where the ramp asks for half.
    pub opacity: Ramp,
    /// Stroke width in Dp. Zero for a fill.
    ///
    /// On a [`carriageway`](Self::carriageway) layer it is one **lane's** width instead, and the
    /// road's own width is that times the lanes the feature carries. A single number cannot serve
    /// both a two-lane street and an eight-lane motorway, and the lane count is the feature's,
    /// not the style's.
    pub width: Ramp,
    /// Gap between the two halves of a casing, in Dp.
    ///
    /// Non-zero turns the stroke into two bands standing off the centreline — how the real
    /// style draws road casings. Whether there is a gap *at all* is decided once, at
    /// tessellation time, by [`gapped`](Self::gapped).
    pub gap_width: Ramp,
    /// Distance between two adjacent parallel lines of one corridor, in Dp.
    ///
    /// Only `transit-rail` sets it. Screen-space rather than a ground distance, and constant
    /// across zoom: the fan widens in discrete jumps as [`lanes`](Self::lanes) steps up, which
    /// is what makes a colour visibly re-assign at a boundary instead of its neighbours merely
    /// closing in on it.
    pub spread: Ramp,
    /// How many parallel lanes a corridor draws at a given zoom, floored at read.
    ///
    /// The one thing a feature cannot carry: it is a property of the camera, not of the route.
    /// A feature carries its colour's ordinal and its corridor's colour count
    /// ([`tilecodec::mamaps::body::Feature::transit_ordinal`]) and
    /// [`Layer::lane_offset_px`] turns the three into a lateral offset per frame.
    pub lanes: Ramp,
    /// Draw this line layer's features as carriageway surfaces rather than strokes.
    ///
    /// A stroke is a band of one colour, so nothing can be painted *within* it — which is why the
    /// divider fan this replaces had to re-stroke a road once per lane boundary. A carriageway
    /// ribbon ([`crate::tess::ribbon`]) carries an across-road coordinate, so every marking is
    /// arithmetic in `road_surface.frag` over one mesh.
    ///
    /// A flag on a line layer rather than a fourth [`LayerKind`], because the kind is what decides
    /// a [`crate::tile::geometry::LayerMesh`]'s stride and pipeline and a carriageway is not one:
    /// it has its own mesh list and its own render pass.
    pub carriageway: bool,
    /// Dash and gap lengths in line widths, as `line-dasharray` defines them.
    pub dash: (f32, f32),
    /// Halo color (ARGB), light and dark. The authored `text-halo-color` per layer;
    /// M1 transcribes it as a flat color like the fill color (no data-driven halos).
    pub halo_light: u32,
    pub halo_dark: u32,
    /// Halo width in screen px (authored `text-halo-width`, 1 everywhere in the
    /// authored style). Pushed per frame like the text size; making it style
    /// data rather than a constant keeps width and color agreeing in one place.
    pub halo_width: f32,
    /// Text size in px, as a zoom ramp. Zero/empty for non-symbol layers.
    ///
    /// The authored `text-size` is a *pixel* size at the camera zoom (unlike a road
    /// width in Dp it is not density-scaled - MapLibre sizes text in screen px, and
    /// the renderer applies density on the way to the shader because the tile span it
    /// is measured against is in device px).
    ///
    /// This is the arm for a place **below** [`Layer::rank_threshold`].
    pub text_size: Ramp,
    /// Text size for a place **at or above** [`Layer::rank_threshold`], where the
    /// authored style gives a second arm.
    ///
    /// `places_country` and `places_locality` size their labels with a two-arm `case`
    /// on `population_rank`, and the gap is wide: at z10 a locality is 12px below the
    /// threshold and 20px above it. Collapsing that to a single ramp drew every small
    /// town at close to city size - and because a label's collision box follows its
    /// size, those towns then beat the cities they should have lost to.
    pub text_size_large: Option<Ramp>,
    /// The population rank at which [`Layer::text_size_large`] takes over, per zoom.
    ///
    /// Falls as the camera descends (13 at z2 down to 8 at z15): the closer in, the
    /// smaller a place may be and still be worth drawing large.
    pub rank_threshold: Option<Ramp>,
    /// Uppercase the label (`text-transform: uppercase` in the authored style).
    pub uppercase: bool,
    /// Glyph weight: the authored `text-font` reduced to Regular/Medium.
    pub medium: bool,
    /// Which optional layer group this belongs to, or `None` for always-on basemap.
    pub toggle: Option<Toggle>,
    /// Draw a sprite icon beside the label, named by the feature's `kind`.
    ///
    /// Only the POI layers set this. The reference style's `icon-image` is
    /// `match(kind, "station", "train_station", kind)`, which is a rename of one kind and
    /// otherwise the kind itself — so a boolean plus that one rule says all of it, and no
    /// per-layer icon name has to be authored.
    pub icon: bool,
    /// `text-offset` in ems, applied along the resolved anchor.
    pub text_offset: (f32, f32),
    /// `text-max-width` in ems, the target width line breaking aims for. Zero means no
    /// wrapping, which is what every place layer wants.
    pub text_max_width: f32,
    /// `text-variable-anchor`: the anchors to try, in order, before giving up.
    ///
    /// Empty means the label is centred and does not move, which is the place layers'
    /// behaviour and what MapLibre does with no variable anchor declared.
    pub variable_anchor: Vec<Anchor>,
    pub min_zoom: u8,
    /// The floor that applies while *browsing*, i.e. with no category filter active.
    ///
    /// `min_zoom` says which zooms the archive is worth asking for. This says which zooms are
    /// worth *showing* when the user has not asked for anything in particular. They differ for
    /// exactly one reason: a category chip should reach further out than the ambient map does.
    /// Restaurants everywhere at z12 is clutter; restaurants at z12 *because you tapped
    /// Restaurants* is the feature.
    ///
    /// Defaults to `min_zoom`, so a layer that does not set it behaves exactly as before.
    pub browse_min_zoom: u8,
    pub max_zoom: u8,
    /// The `style/basemap.json` layer this was transcribed from.
    ///
    /// Provenance, and the key the cross-check test joins the two files on — see
    /// [`super::paint_extra5::the_flat_style_agrees_with_basemap_json`]. Nothing in the render path
    /// reads it.
    pub authored: String,
}

impl Layer {
    /// Does this layer draw `feature`?
    ///
    /// The kind whitelist plus the road flag/detail filters, in one test so the render path
    /// calls a single function per feature per layer. Flag bits are require/forbid masks;
    /// details are whitelist-or-exclusion over the interned `kind_detail` id.
    pub fn matches_feature(&self, feature: &Feature) -> bool {
        if !self.matches_id(feature.kind) {
            return false;
        }
        if feature.flags & self.require_flags != self.require_flags {
            return false;
        }
        if feature.flags & self.forbid_flags != 0 {
            return false;
        }
        if !self.detail_ids.is_empty()
            && self.detail_ids.binary_search(&feature.kind_detail).is_err()
        {
            return false;
        }
        if !self.forbid_details.is_empty()
            && self
                .forbid_details
                .binary_search(&feature.kind_detail)
                .is_ok()
        {
            return false;
        }
        true
    }

    /// Does this layer draw a feature whose interned `kind` is `kind`?
    ///
    /// The kind half of [`matches_feature`](Self::matches_feature): kept because tests and
    /// diagnostics name kinds without a feature to hand.
    /// The whole of the attribute filtering the renderer does, and all the style asks for.
    pub fn matches_id(&self, kind: u16) -> bool {
        self.kind_ids.is_empty() || self.kind_ids.binary_search(&kind).is_ok()
    }

    /// Does this layer draw a feature whose `kind` is named `kind`?
    ///
    /// Delegates to [`matches_id`](Self::matches_id) rather than comparing strings, so the two can
    /// never disagree. For tests and diagnostics; nothing on the render path takes a name.
    pub fn matches(&self, kind: Option<&str>) -> bool {
        match kind {
            None => self.matches_id(dict::NONE),
            Some(name) => match kind_id(name) {
                Some(id) => self.matches_id(id),
                // A name the schema cannot emit. Not drawn, because no feature can carry it.
                None => false,
            },
        }
    }

    /// Is this layer worth asking the archive for at `zoom`?
    ///
    /// A data-and-cost gate, not paint: it says which pyramid levels carry the layer. Paint is
    /// [`stroke`](Self::stroke) and [`opacity_at`](Self::opacity_at), which follow the *camera*.
    /// Deriving this from the opacity ramp instead meant `landcover` was never tessellated for
    /// a tile deeper than z6 even though its ramp wants it visible up to camera z7 — so whether
    /// a shape existed depended on which pyramid level happened to be resident, and shapes
    /// appeared and vanished while zooming.
    pub fn draws_at(&self, zoom: u8) -> bool {
        zoom >= self.min_zoom && zoom <= self.max_zoom
    }

    /// [`draws_at`](Self::draws_at), but honouring the browse floor unless this layer is what the
    /// user asked for.
    ///
    /// `focused` means at least one of this layer's kinds is in the active category filter. When
    /// it is, the layer falls back to its data floor and appears as early as the archive allows;
    /// when it is not, [`browse_min_zoom`](Self::browse_min_zoom) applies.
    pub fn draws_at_focused(&self, zoom: u8, focused: bool) -> bool {
        let floor = if focused {
            self.min_zoom
        } else {
            self.browse_min_zoom.max(self.min_zoom)
        };
        zoom >= floor && zoom <= self.max_zoom
    }

    /// Does the active category filter name any of this layer's kinds?
    ///
    /// An empty filter is "no category selected", not "select nothing", so it focuses nothing.
    pub fn focused_by(&self, filter: &KindFilter) -> bool {
        !filter.is_empty() && self.kind_ids.iter().any(|k| filter.admits(self, *k))
    }

    /// This label's size in px at `zoom`, for a place of population rank `pop`.
    ///
    /// Picks between the two arms the authored style declares. A layer without a second
    /// arm answers from [`Layer::text_size`] whatever the rank, which is what every
    /// non-place symbol layer wants.
    pub fn text_size_for(&self, zoom: f64, pop: u16) -> f32 {
        match (&self.text_size_large, &self.rank_threshold) {
            (Some(large), Some(threshold)) if f32::from(pop) >= threshold.at(zoom) => {
                large.at(zoom)
            }
            _ => self.text_size.at(zoom),
        }
    }

    /// Whether any place could produce a visible label at `zoom`.
    ///
    /// The cheap per-layer gate before the per-label sizing: a layer whose *widest* arm
    /// has ramped to zero cannot draw anything, whatever ranks the tile holds.
    pub fn text_visible_at(&self, zoom: f64) -> bool {
        let large = self
            .text_size_large
            .as_ref()
            .map_or(0.0, |ramp| ramp.at(zoom));
        self.text_size.at(zoom).max(large) > 0.0
    }

    /// A casing: two bands offset either side of the centreline.
    ///
    /// Read at tessellation time, so it cannot vary with zoom — one geometry has to serve every
    /// frame. A layer whose ramp has a gap but which tessellates a plain stroke would discard
    /// the pushed gap silently, which is why this is the ramp's peak rather than its value
    /// anywhere in particular.
    pub fn gapped(&self) -> bool {
        self.gap_width.peak() > 0.0
    }

    /// Does this line layer fan its features into parallel lanes, rather than draw one stroke
    /// down each centreline?
    ///
    /// True when the style gives the layer a [`spread`](Self::spread) — the sideways step between
    /// adjacent lanes. Only `transit-rail` sets it, fanning its features by their colour ordinal.
    /// `lanes` defaults to 1 and so cannot be the discriminator; `spread` defaults to 0 and is only
    /// ever set on purpose.
    pub fn lane_fan(&self) -> bool {
        self.spread.peak() > 0.0
    }

    /// The stroke this layer draws at `zoom`, in Dp.
    pub fn stroke(&self, zoom: f64) -> Stroke {
        Stroke {
            width_dp: self.width.at(zoom),
            gap_width_dp: self.gap_width.at(zoom),
        }
    }

    /// How far sideways a transit feature's mesh shifts at `zoom`, in device pixels.
    ///
    /// `count` is the corridor's colour count and `ordinal` the colour's index within it, both
    /// straight off the feature; `taper` is how far into the lane this piece sits, over 255.
    ///
    /// Past the style's lane count the colours are squashed onto the lanes there are rather
    /// than the fan growing without bound. Squashing rather than wrapping is what keeps the map
    /// free of crossings at every zoom: the map is monotonic in `ordinal`, so two colours can
    /// come to share a lane but can never swap sides.
    ///
    /// It squashes from the **middle**. Ordinal zero takes lane zero and the last ordinal takes
    /// the last lane, so the outermost line on each side of a corridor keeps a lane to itself
    /// for as long as there is one to spare, and the doubling-up happens where it is least
    /// visible. Six colours over four lanes go `0,1,1,2,2,3` — one, two, two, one — where
    /// spacing them evenly would give `0,0,1,2,2,3` and crowd the two edges instead.
    pub fn lane_offset_px(
        &self,
        zoom: f64,
        density: f32,
        ordinal: u8,
        count: u8,
        taper: u8,
    ) -> f32 {
        let lanes = (self.lanes.at(zoom).floor() as i32).min(count as i32);
        if lanes < 2 {
            return 0.0;
        }
        // `lanes >= 2` implies `count >= 2`, so the divisor cannot be zero. The `+ steps` is
        // the round-to-nearest, which is what puts the wider buckets in the middle.
        let (span, steps) = (lanes - 1, count as i32 - 1);
        let lane = (2 * ordinal as i32 * span + steps) / (2 * steps);
        (2 * lane - (lanes - 1)) as f32 * self.spread.at(zoom) * density / 2.0
            * (taper as f32 / 255.0)
    }

    /// The fill opacity this layer draws at `zoom`, in 0..=1.
    pub fn opacity_at(&self, zoom: f64) -> f32 {
        self.opacity.at(zoom).clamp(0.0, 1.0)
    }

    pub fn color(&self, palette: Palette) -> u32 {
        let base = match palette.variant {
            Variant::Light => self.light,
            Variant::Dark => self.dark,
        };
        if palette.muted && !self.is_base() {
            blend(base, background(palette.variant), MUTED_BLEND)
        } else {
            base
        }
    }

    /// The halo color for a symbol layer, by palette. Non-symbol layers return
    /// transparent (their shaders never read it).
    pub fn halo_color(&self, palette: Palette) -> u32 {
        match palette.variant {
            Variant::Light => self.halo_light,
            Variant::Dark => self.halo_dark,
        }
    }

    /// Is this the land/sea base rather than detail drawn on it?
    ///
    /// The base is **never muted**. Muting blends toward the background, which is the water
    /// colour, so muting the base pulls land into the sea and erases the coastline — and no
    /// choice of colours avoids it, because blending toward a common point shrinks every
    /// separation by `1 - amount`. Positron, which is what muting imitates, keeps water
    /// plainly visible and mutes the detail on top. A host that mutes the basemap wants less
    /// competing detail, not less geography: `weather`'s overlay is meaningless without a
    /// recognisable coastline under it.
    fn is_base(&self) -> bool {
        matches!(self.source_layer.as_str(), "earth" | "water")
    }
}

/// A `kind` name's interned id, or `None` when the schema has no counterpart.
pub fn kind_id(name: &str) -> Option<u16> {
    dict::KINDS
        .iter()
        .position(|k| *k == name)
        .map(|i| i as u16 + 1)
}

/// Test-only access to [`kind_id`]: symbol tests build layers by hand.
#[cfg(test)]
pub fn kind_id_for_test(name: &str) -> u16 {
    kind_id(name).expect("a schema kind")
}

/// A `kind_detail` name's interned id, or `None` when the schema has no counterpart.
///
/// Details share the archive-wide [`dict::DETAILS`] table, so `service` here is the same id
/// the tiler wrote on the feature.
pub(super) fn detail_id(name: &str) -> Option<u16> {
    dict::DETAILS
        .iter()
        .position(|k| *k == name)
        .map(|i| i as u16 + 1)
}

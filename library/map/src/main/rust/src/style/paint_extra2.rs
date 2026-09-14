//! Shared helpers for the flat-style tests.
//!
//! Split from [`paint`]'s test module so each test file stays small. The
//! `paint_extra3`, `paint_extra4` and `paint_extra5` test modules use these.

use super::paint::{MAX_ZOOM, Ramp, parse_hex};
use crate::style::{Layer, LayerKind, layers};
use serde_json::Value as Json;

    /// The archive's
    /// The archive's zoom range, plus the overzoom the renderer allows past it.
    pub(crate) const ZOOMS: std::ops::RangeInclusive<i32> = 0..=22;

    /// The authored MapLibre style, for the cross-check only.
    pub(crate) const BASEMAP: &str = include_str!("../../style/basemap.json");

    pub(crate) fn line_layers() -> Vec<&'static Layer> {
        layers().iter().filter(|layer| layer.kind == LayerKind::Line).collect()
    }

    pub(crate) fn find(id: &str) -> &'static Layer {
        layers().iter().find(|layer| layer.id == id).unwrap_or_else(|| panic!("{id}"))
    }

    /// A layer from the set with lane rendering forced on.
    ///
    /// [`crate::style::LANE_RENDERING`] is off for release, so the carriageway layers are absent
    /// from [`layers`] and the tests that pin them have to name the authored set explicitly.
    pub(crate) fn find_lane(id: &str) -> &'static Layer {
        crate::style::layers_with_lane_rendering()
            .iter()
            .find(|layer| layer.id == id)
            .unwrap_or_else(|| panic!("{id}"))
    }
    /// Mirror of `line.frag`'s dash test so WS-B's clock-driven phase can be checked without a
    /// GPU. `phase_px` is `misc.w * morph.y` — the per-frame clock times the per-draw phase
    /// speed — which is 0 for every static line (`morph.y` defaults to 0) and for a stopped
    /// clock. GLSL `mod` matches Rust's `rem_euclid` for a positive period, and the shader
    /// draws (does not `discard`) when the result is `<= on`.
    pub(crate) fn dash_drawn(distance_px: f32, on: f32, off: f32, phase_px: f32) -> bool {
        let period = on + off;
        if off <= 0.0 || period <= 0.0 {
            return true;
        }
        (distance_px - phase_px).rem_euclid(period) <= on
    }
    // --- the cross-check against basemap.json ------------------------------

    pub(crate) fn basemap() -> Json {
        serde_json::from_str(BASEMAP).expect("basemap.json should parse")
    }

    /// The authored layer a flat layer names, by id.
    pub(crate) fn authored_layer(root: &Json, id: &str) -> Json {
        root.get("layers")
            .and_then(Json::as_array)
            .expect("layers")
            .iter()
            .find(|layer| layer.get("id").and_then(Json::as_str) == Some(id))
            .unwrap_or_else(|| panic!("basemap.json has no layer `{id}`"))
            .clone()
    }
    /// `#rrggbb` or `rgb(...)`/`rgba(...)`, the two spellings `basemap.json` uses.
    ///
    /// Only the test needs the functional form: the flat file writes hex, and the seven
    /// `landcover` arms in the authored file are what this is here to read.
    pub(crate) fn parse_authored_color(source: &str) -> Option<u32> {
        if let Some(argb) = parse_hex(source) {
            return Some(argb);
        }
        let (name, rest) = source.trim().split_once('(')?;
        if !matches!(name.trim(), "rgb" | "rgba") {
            return None;
        }
        let parts: Vec<f64> = rest
            .strip_suffix(')')?
            .split(',')
            .map(|part| part.trim().parse::<f64>())
            .collect::<Result<_, _>>()
            .ok()?;
        let channel = |v: f64| v.round().clamp(0.0, 255.0) as u32;
        // Alpha is 0..1 in the functional notation, unlike the 0..255 of the channels.
        let alpha = match parts.len() {
            3 => 0xFF,
            4 => channel(parts[3] * 255.0),
            _ => return None,
        };
        Some(
            (alpha << 24)
                | (channel(parts[0]) << 16)
                | (channel(parts[1]) << 8)
                | channel(parts[2]),
        )
    }
    /// Every colour string anywhere in an expression subtree.
    pub(crate) fn colors_in(json: &Json, out: &mut Vec<u32>) {
        match json {
            Json::String(s) => {
                if let Some(argb) = parse_authored_color(s) {
                    out.push(argb);
                }
            }
            Json::Array(items) => items.iter().for_each(|item| colors_in(item, out)),
            _ => {}
        }
    }
    /// An authored `interpolate` expression as a flat [`Ramp`], or `None` if it is a constant.
    pub(crate) fn authored_ramp(json: &Json) -> Option<Ramp> {
        let items = json.as_array()?;
        if items.first().and_then(Json::as_str) != Some("interpolate") {
            return None;
        }
        let interpolation = items.get(1)?.as_array()?;
        let base = match interpolation.first().and_then(Json::as_str) {
            Some("linear") => 1.0,
            Some("exponential") => interpolation.get(1)?.as_f64()?,
            _ => return None,
        };
        let stops = items[3..]
            .chunks(2)
            .map(|pair| Some((pair[0].as_f64()?, pair.get(1)?.as_f64()? as f32)))
            .collect::<Option<Vec<_>>>()?;
        Some(Ramp { base, stops })
    }
    /// The authored value of one paint property, as a ramp — a literal number becoming a
    /// one-stop ramp, exactly as the loader treats one.
    pub(crate) fn authored_property(layer: &Json, property: &str) -> Option<Ramp> {
        let json = layer.get("paint")?.get(property)?;
        if let Some(value) = json.as_f64() {
            return Some(Ramp::constant(value as f32));
        }
        authored_ramp(json)
    }
    /// The authored value of one _layout_ property (`text-size` lives in `layout`,
    /// not `paint`). A data-driven `text-size` with `case`/`get` arms has no flat
    /// equivalent — the caller transcribes the constant the arms evaluate to at the
    /// reference zooms, and this reads the same arms-free ramps only. Returns `None`
    /// when the value is data-driven, so the cross-check skips rather than lies.
    pub(crate) fn authored_layout_ramp(layer: &Json, property: &str) -> Option<Ramp> {
        let json = layer.get("layout")?.get(property)?;
        if let Some(value) = json.as_f64() {
            return Some(Ramp::constant(value as f32));
        }
        // A plain `interpolate` over zoom transcribes directly; anything data-driven
        // (`case`, `get`, `step` over feature properties) is M1-out-of-scope.
        let text = serde_json::to_string(json).unwrap_or_default();
        if text.contains("\"case\"") || text.contains("\"get\"") {
            return None;
        }
        authored_ramp(json)
    }
    /// The ramps whose width tracks a fixed **ground** width above the zoom upstream stops at.
    ///
    /// `basemap.json` clamps these at their last stop — z18 for most, z20 for a few — because a
    /// MapLibre style is written for a camera that stops there. Ours does not: the renderer
    /// overzooms to [`MAX_ZOOM`], and a clamped ramp holds its Dp width while the ground halves
    /// under it, so a road 3.9 m wide at z18 is 0.24 m wide at z22. The carriageway surface used
    /// to cover that whole range, but [`super::LANE_RENDERING`] is off for release and drops it,
    /// leaving these layers as the only thing drawing a road at z16+. Above the clamp each stop
    /// doubles, which is the rate the ground shrinks, so the road keeps the width it had there.
    ///
    /// Enumerated layer by layer and property by property rather than matched by a rule over
    /// ids: this is the only place the flat style is allowed to say more about a line's width
    /// than the authored file does, so it has to be a list a reader can check. Every other
    /// property on these layers, and every property on every other layer, stays pinned.
    ///
    /// A casing carries both halves. `gap_width` is the clear span the fill sits in and `width`
    /// is the thickness of each band beside it, so extending one without the other would leave
    /// the outline stranded inside a road sixteen times wider than the gap it was drawn around.
    ///
    /// `roads-rail` is deliberately absent: it is not a road, and a rail line is a symbol whose
    /// width says "there is a railway here" rather than how wide the track is.
    pub(crate) const FIXED_GROUND_WIDTH: &[(&str, &str, f64)] = &[
        ("roads-minor-casing", "width", 18.0),
        ("roads-minor-casing", "gap_width", 18.0),
        ("roads-major-casing", "width", 18.0),
        ("roads-major-casing", "gap_width", 18.0),
        ("roads-highway-casing", "width", 20.0),
        ("roads-highway-casing", "gap_width", 18.0),
        ("roads-link-casing", "width", 18.0),
        ("roads-link-casing", "gap_width", 18.0),
        ("roads-bridges-highway-casing", "width", 20.0),
        ("roads-bridges-highway-casing", "gap_width", 18.0),
        ("roads-bridges-major-casing", "width", 18.0),
        ("roads-bridges-major-casing", "gap_width", 18.0),
        ("roads-bridges-minor-casing", "width", 18.0),
        ("roads-bridges-minor-casing", "gap_width", 18.0),
        ("roads-bridges-link-casing", "width", 18.0),
        ("roads-bridges-link-casing", "gap_width", 18.0),
        ("roads-bridges-other-casing", "width", 20.0),
        ("roads-bridges-other-casing", "gap_width", 20.0),
        ("roads-path", "width", 20.0),
        ("roads-minor", "width", 18.0),
        ("roads-major", "width", 18.0),
        ("roads-highway", "width", 18.0),
        ("roads-link", "width", 18.0),
        ("roads-bridges-highway", "width", 18.0),
        ("roads-bridges-major", "width", 18.0),
        ("roads-bridges-minor", "width", 18.0),
        ("roads-bridges-link", "width", 18.0),
        ("roads-bridges-other", "width", 20.0),
        ("roads-minor-service", "width", 18.0),
    ];
    /// The zoom [`FIXED_GROUND_WIDTH`] tabulates for a ramp, or `None` if it is not one.
    pub(crate) fn fixed_ground_width_anchor(id: &str, property: &str) -> Option<f64> {
        FIXED_GROUND_WIDTH
            .iter()
            .find(|(layer, prop, _)| *layer == id && *prop == property)
            .map(|(_, _, anchor)| *anchor)
    }
    /// An authored ramp carrying the doubling tail the flat file is expected to add to it.
    ///
    /// Extended here, from the authored ramp, rather than hand-copied into `basemap.json`: that
    /// file is a vendored copy of Protomaps' style, and writing our stops into it would both
    /// falsify the copy and leave this comparison checking one hand transcription against
    /// another — which is the failure the cross-check exists to catch. Reading upstream and
    /// applying one stated rule keeps every value below the clamp pinned to the source, and
    /// makes the values above it a consequence of a rule rather than 80 more typed numbers.
    pub(crate) fn extended_to_fixed_ground_width(id: &str, property: &str, authored: &Ramp) -> Ramp {
        let Some(anchor) = fixed_ground_width_anchor(id, property) else {
            return authored.clone();
        };
        let last = authored.stops[authored.stops.len() - 1].0;
        // Catches a vendor refresh that moves the clamp: upstream deciding a road grows until
        // z19 would otherwise be silently overwritten by a tail anchored at the old zoom.
        assert_eq!(
            anchor,
            last.max(18.0),
            "`{id}`'s {property} is tabulated as extending from z{anchor}, but basemap.json \
             clamps it at z{last} — upstream moved and the table has to move with it",
        );
        let value = authored.at(anchor);
        let mut stops = authored.stops.clone();
        if last < anchor {
            stops.push((anchor, value));
        }
        let mut zoom = anchor + 1.0;
        while zoom <= MAX_ZOOM as f64 {
            stops.push((zoom, value * 2.0f32.powf((zoom - anchor) as f32)));
            zoom += 1.0;
        }
        Ramp { base: authored.base, stops }
    }
    /// Two ramps must agree at every tenth of a zoom, not merely stop for stop.
    ///
    /// Comparing evaluated values rather than the stop lists is what lets a constant and a
    /// one-stop ramp compare equal, and it is also the thing that actually matters: a stop
    /// written at a different zoom with a compensating value is still the same paint.
    pub(crate) fn assert_ramps_agree(id: &str, property: &str, flat: &Ramp, authored: &Ramp) {
        let anchor = fixed_ground_width_anchor(id, property);
        for tenth in 0..=(MAX_ZOOM as u32 * 10) {
            let zoom = tenth as f64 / 10.0;
            let (ours, theirs) = (flat.at(zoom), authored.at(zoom));
            // Above the clamp the expected value is upstream's *extended* by
            // `FIXED_GROUND_WIDTH`, not something `basemap.json` states, and saying otherwise
            // would send whoever reads this looking for a stop that is not there.
            let source = match anchor {
                Some(anchor) if zoom > anchor => "basemap.json extended to a fixed ground width",
                _ => "basemap.json",
            };
            assert!(
                (ours - theirs).abs() < 1e-5,
                "`{id}`'s {property} is {ours} at z{zoom} where {source} says {theirs}",
            );
        }
    }
    /// The `kind` values an authored filter admits, or empty for "any of them".
    ///
    /// The four shapes `basemap.json` uses on its `fill` layers. A `$type` filter restricts
    /// geometry rather than `kind`, so it admits everything.
    pub(crate) fn authored_filter_kinds(filter: Option<&Json>) -> Vec<String> {
        let Some(Json::Array(items)) = filter else {
            return Vec::new();
        };
        let (op, args) = (items.first().and_then(Json::as_str).unwrap_or_default(), &items[1..]);
        let strings = |from: &[Json]| -> Vec<String> {
            from.iter().filter_map(Json::as_str).map(str::to_string).collect()
        };
        match op {
            "==" if args.first().and_then(Json::as_str) == Some("kind") => strings(&args[1..]),
            "in" if args.first().and_then(Json::as_str) == Some("kind") => strings(&args[1..]),
            // A union, and unrestricted if any branch is.
            "any" => {
                let mut out = Vec::new();
                for inner in args {
                    let kinds = authored_filter_kinds(Some(inner));
                    if kinds.is_empty() {
                        return Vec::new();
                    }
                    out.extend(kinds);
                }
                out
            }
            _ => Vec::new(),
        }
    }

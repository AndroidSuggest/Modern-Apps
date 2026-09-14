//! Flat-style layer loader: one JSON object to one [`Layer`].
//!
//! Split from [`paint`]: [`layer`] is the largest single item in the style loader,
//! so it lives here and both files stay small. [`paint::parse`] calls it once per
//! entry of the flat file's `layers` array.

use super::paint::{MAX_ZOOM, Ramp, color};
use super::{Anchor, Layer, LayerKind, Toggle};
use serde_json::Value as Json;
use tilecodec::mamaps::body::{FLAG_IS_BRIDGE, FLAG_IS_LINK, FLAG_IS_TUNNEL};
use tilecodec::mamaps::dict;

pub(crate) fn layer(json: &Json) -> Result<Layer, String> {
    let string = |key: &str| -> Result<String, String> {
        json.get(key)
            .and_then(Json::as_str)
            .map(str::to_string)
            .ok_or_else(|| format!("a layer has no `{key}`: {json}"))
    };
    let id = string("id")?;
    // Every property this format has. A hand-authored file is the source of truth now, so a
    // misspelling has to be a load failure: `gapwidth` or `minZoom` would otherwise take the
    // default and render something plausible that nobody asked for.
    const KNOWN: &[&str] = &[
        "id",
        "authored",
        "source",
        "type",
        "kinds",
        "require_flags",
        "forbid_flags",
        "details",
        "forbid_details",
        "light",
        "dark",
        "opacity",
        "width",
        "gap_width",
        "spread",
        "lanes",
        "carriageway",
        "dash",
        "text_size",
        "text_size_large",
        "rank_threshold",
        "uppercase",
        "medium",
        "toggle",
        "icon",
        "text_offset",
        "text_max_width",
        "variable_anchor",
        "halo_light",
        "halo_dark",
        "halo_width",
        "minzoom",
        // The floor that applies while browsing, i.e. with no category chip selected. Optional;
        // defaults to `minzoom`. See `Layer::browse_min_zoom`.
        "browse_minzoom",
        "maxzoom",
    ];
    for key in json.as_object().ok_or_else(|| format!("`{id}` is not an object"))?.keys() {
        if !KNOWN.contains(&key.as_str()) {
            return Err(format!("`{id}` has an unknown property `{key}`"));
        }
    }
    let kind = match json.get("type").and_then(Json::as_str) {
        Some("fill") => LayerKind::Fill,
        Some("line") => LayerKind::Line,
        Some("symbol") => LayerKind::Symbol,
        other => return Err(format!("`{id}` has an unknown type {other:?}")),
    };
    let zoom = |key: &str, default: u8| -> Result<u8, String> {
        match json.get(key) {
            None => Ok(default),
            Some(value) => value
                .as_u64()
                .filter(|z| *z <= MAX_ZOOM as u64)
                .map(|z| z as u8)
                .ok_or_else(|| {
                    format!("`{id}`'s {key} must be a whole zoom in 0..={MAX_ZOOM}")
                }),
        }
    };
    let dash = match json.get("dash").and_then(Json::as_array).map(|d| d.as_slice()) {
        None => (0.0, 0.0),
        Some([on, off]) => match (on.as_f64(), off.as_f64()) {
            (Some(on), Some(off)) => (on as f32, off as f32),
            _ => return Err(format!("`{id}`'s dash must be two numbers")),
        },
        Some(_) => return Err(format!("`{id}`'s dash must be `[on, off]`")),
    };
    let kinds: Vec<String> = match json.get("kinds") {
        None => Vec::new(),
        Some(Json::Array(kinds)) => kinds
            .iter()
            .map(|kind| {
                kind.as_str()
                    .map(str::to_string)
                    .ok_or_else(|| format!("`{id}`'s kinds must be strings"))
            })
            .collect::<Result<_, _>>()?,
        Some(_) => return Err(format!("`{id}`'s kinds must be an array")),
    };
    // Both halves of the schema closure the plan asks for: a `source` or a `kind` the archive
    // cannot carry is a build failure, not a layer that quietly draws nothing on device.
    let source = string("source")?;
    let source_layer_id = dict::LAYERS
        .iter()
        .position(|name| *name == source)
        .ok_or_else(|| {
            format!("`{id}` reads source layer `{source}`, which no .mamaps archive carries")
        })? as u8;
    let mut kind_ids = kinds
        .iter()
        .map(|name| {
            super::kind_id(name).ok_or_else(|| {
                format!("`{id}` filters on kind `{name}`, which the schema cannot emit")
            })
        })
        .collect::<Result<Vec<u16>, _>>()?;
    // Sorted so the render path's membership test is a binary search over a `u16` slice.
    kind_ids.sort_unstable();
    kind_ids.dedup();
    // The road flag/detail filters, as interned ids and bitmasks. `require_flags` names
    // features that must carry a bit (`["link"]`); `forbid_flags` names bits that must be
    // absent (`["bridge", "tunnel"]` on a surface layer). `details`/`forbid_details` are
    // `kind_detail` names (`service`), interned through the same DETAILS table the tiler
    // wrote. All four default to empty/zero — no filter — so fills never name them.
    let flag_bits = |key: &str| -> Result<u8, String> {
        let mut bits = 0u8;
        match json.get(key) {
            None => Ok(0),
            Some(Json::Array(names)) => {
                for name in names {
                    bits |= match name.as_str() {
                        Some("tunnel") => FLAG_IS_TUNNEL,
                        Some("bridge") => FLAG_IS_BRIDGE,
                        Some("link") => FLAG_IS_LINK,
                        other => {
                            return Err(format!(
                                "`{id}`'s {key} names an unknown flag {other:?}"
                            ))
                        }
                    };
                }
                Ok(bits)
            }
            Some(_) => Err(format!("`{id}`'s {key} must be an array of flag names")),
        }
    };
    let detail_ids_of = |key: &str| -> Result<Vec<u16>, String> {
        let mut ids: Vec<u16> = match json.get(key) {
            None => Vec::new(),
            Some(Json::Array(names)) => names
                .iter()
                .map(|name| {
                    name.as_str()
                        .and_then(super::detail_id)
                        .ok_or_else(|| {
                            format!("`{id}` filters on detail `{name}`, which the schema cannot emit")
                        })
                })
                .collect::<Result<_, _>>()?,
            Some(_) => return Err(format!("`{id}`'s {key} must be an array of detail names")),
        };
        ids.sort_unstable();
        ids.dedup();
        Ok(ids)
    };

    let toggle = match json.get("toggle").map(|v| v.as_str()) {
        None => None,
        Some(Some("poi")) => Some(Toggle::Poi),
        Some(Some("transit")) => Some(Toggle::Transit),
        Some(other) => return Err(format!("`{id}` has an unknown toggle {other:?}")),
    };
    let text_offset = match json.get("text_offset").map(|v| v.as_array()) {
        None => (0.0, 0.0),
        Some(Some(pair)) => match pair.as_slice() {
            [x, y] => match (x.as_f64(), y.as_f64()) {
                (Some(x), Some(y)) => (x as f32, y as f32),
                _ => return Err(format!("`{id}`'s text_offset must be two numbers")),
            },
            _ => return Err(format!("`{id}`'s text_offset must be `[x, y]`")),
        },
        Some(None) => return Err(format!("`{id}`'s text_offset must be an array")),
    };
    let variable_anchor: Vec<Anchor> = match json.get("variable_anchor") {
        None => Vec::new(),
        Some(Json::Array(names)) => names
            .iter()
            .map(|name| match name.as_str() {
                Some("center") => Ok(Anchor::Center),
                Some("left") => Ok(Anchor::Left),
                Some("right") => Ok(Anchor::Right),
                other => Err(format!("`{id}`'s variable_anchor names an unknown anchor {other:?}")),
            })
            .collect::<Result<_, _>>()?,
        Some(_) => return Err(format!("`{id}`'s variable_anchor must be an array")),
    };

    Ok(Layer {
        source_layer: source,
        source_layer_id,
        authored: string("authored")?,
        kind,
        kinds,
        kind_ids,
        require_flags: flag_bits("require_flags")?,
        forbid_flags: flag_bits("forbid_flags")?,
        detail_ids: detail_ids_of("details")?,
        forbid_details: detail_ids_of("forbid_details")?,
        light: color(json.get("light"), &id)?,
        dark: color(json.get("dark"), &id)?,
        opacity: Ramp::parse(json.get("opacity"), &id, "opacity", 1.0)?,
        width: Ramp::parse(json.get("width"), &id, "width", 0.0)?,
        gap_width: Ramp::parse(json.get("gap_width"), &id, "gap_width", 0.0)?,
        spread: Ramp::parse(json.get("spread"), &id, "spread", 0.0)?,
        lanes: Ramp::parse(json.get("lanes"), &id, "lanes", 1.0)?,
        carriageway: json.get("carriageway").and_then(Json::as_bool).unwrap_or(false),
        dash,
        text_size: Ramp::parse(json.get("text_size"), &id, "text_size", 0.0)?,
        // Optional second arm: present only where the authored style's `text-size` is a
        // `case` on `population_rank`. Both halves have to be there or neither, since a
        // threshold with nothing to switch to says nothing.
        text_size_large: match json.get("text_size_large") {
            Some(value) => Some(Ramp::parse(Some(value), &id, "text_size_large", 0.0)?),
            None => None,
        },
        rank_threshold: match json.get("rank_threshold") {
            Some(value) => Some(Ramp::parse(Some(value), &id, "rank_threshold", 0.0)?),
            None => None,
        },
        uppercase: json.get("uppercase").and_then(Json::as_bool).unwrap_or(false),
        medium: json.get("medium").and_then(Json::as_bool).unwrap_or(false),
        toggle,
        icon: json.get("icon").and_then(Json::as_bool).unwrap_or(false),
        text_offset,
        text_max_width: json
            .get("text_max_width")
            .and_then(Json::as_f64)
            .map(|v| v as f32)
            .unwrap_or(0.0),
        variable_anchor,
        halo_light: color(json.get("halo_light"), &id).unwrap_or(0x00000000),
        halo_dark: color(json.get("halo_dark"), &id).unwrap_or(0x00000000),
        halo_width: json
            .get("halo_width")
            .and_then(Json::as_f64)
            .map(|v| v as f32)
            .unwrap_or(1.0),
        min_zoom: zoom("minzoom", 0)?,
        // Defaults to the data floor, so a layer that does not set it is gated exactly as before.
        browse_min_zoom: zoom("browse_minzoom", zoom("minzoom", 0)?)?,
        max_zoom: zoom("maxzoom", MAX_ZOOM)?,
        id,
    })
}

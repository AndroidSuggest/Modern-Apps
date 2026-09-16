use super::build::build;
use crate::style::paint::Ramp;
use crate::style::{Layer, LayerKind};
use tilecodec::mamaps::body::{Body, GEOM_LINE};

// --- curved labels along roads / rivers (WS-E) -------------------------

/// A symbol layer over the `roads` source with no kind filter, so it labels any named road
/// line — the render-side shape of the `roads-label` style layer the build carries names for.
fn roads_label_layer() -> Layer {
    Layer {
        id: "roads-label".to_string(),
        source_layer: "roads".to_string(),
        source_layer_id: tilecodec::mamaps::dict::LAYER_ROADS,
        kind: LayerKind::Symbol,
        kinds: Vec::new(),
        kind_ids: Vec::new(),
        require_flags: 0,
        forbid_flags: 0,
        detail_ids: Vec::new(),
        forbid_details: Vec::new(),
        light: 0xFF3B3B3B,
        dark: 0xFFEDEDED,
        opacity: Ramp::constant(1.0),
        width: Ramp::constant(0.0),
        gap_width: Ramp::constant(0.0),
        spread: Ramp::constant(0.0),
        lanes: Ramp::constant(1.0),
        carriageway: false,
        dash: (0.0, 0.0),
        text_size: Ramp::constant(12.0),
        text_size_large: None,
        rank_threshold: None,
        uppercase: false,
        medium: false,
        toggle: None,
        icon: false,
        text_offset: (0.0, 0.0),
        text_max_width: 0.0,
        variable_anchor: Vec::new(),
        halo_light: 0xFFFFFFFF,
        halo_dark: 0xFF0D1B2A,
        halo_width: 1.0,
        min_zoom: 0,
        browse_min_zoom: 0,
        max_zoom: 22,
        authored: "roads_label".to_string(),
    }
}

/// A body with one named road line running along `pts` (extent units).
fn named_road_body(name: &str, pts: &[(i16, i16)]) -> Body {
    use tilecodec::mamaps::body::{
        Feature, Layer as BodyLayer, Part, DEFAULT_EXTENT, WINDING_OUTER,
    };
    use tilecodec::mamaps::dict;
    let mut source = BodyLayer::new(dict::LAYER_ROADS);
    source.parts.push(Part {
        coord_start: 0,
        point_count: pts.len() as u32,
        winding: WINDING_OUTER,
    });
    source.coords.extend_from_slice(pts);
    source.features.push(Feature {
        kind: crate::style::kind_id_for_test("major_road"),
        kind_detail: 0,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: 1,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    Body {
        extent: DEFAULT_EXTENT,
        layers: vec![source],
        names: vec![name.to_string()],
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None, carriageways: Vec::new(), convention: None,
    }
}

#[test]
fn a_named_road_line_shapes_a_curved_label_along_its_centreline() {
    if !crate::tile::glyph::fonts_staged() {
        eprintln!("SKIP: staged TTFs are not fonts");
        return;
    }
    // A straight eastbound road spanning the tile.
    let body = named_road_body("Market Street", &[(200, 2048), (3800, 2048)]);
    let layers = vec![roads_label_layer()];
    let mesh = build(&body, &layers, 14, 0, 8192, false);

    assert_eq!(mesh.labels.len(), 1, "the named road shapes exactly one label");
    let label = &mesh.labels[0];
    assert_eq!(label.name, "Market Street");
    assert_eq!(label.layer_index, 0);
    let centreline = label.centreline.as_ref().expect("a road label is curved, not point");
    assert_eq!(centreline.len(), 2, "the whole feature centreline rides on the label");
    // Normalised into tile-local 0..1 from extent units, along the tile's mid-line.
    assert!(centreline.iter().all(|&(x, y)| (0.0..=1.0).contains(&x) && (y - 0.5).abs() < 1e-3));
    assert!(centreline[1].0 > centreline[0].0, "eastbound: x increases along the line");
}

#[test]
fn an_unnamed_road_line_shapes_no_label() {
    // Without a name there is nothing to lay along the line, so no curved label is produced —
    // the same silent skip the point path makes for an unnamed place.
    use tilecodec::mamaps::body::NAME_NONE;
    let mut body = named_road_body("ignored", &[(200, 2048), (3800, 2048)]);
    body.layers[0].features[0].name_idx = NAME_NONE;
    let mesh = build(&body, &vec![roads_label_layer()], 14, 0, 8192, false);
    assert!(mesh.labels.is_empty(), "an unnamed road line shapes nothing");
}

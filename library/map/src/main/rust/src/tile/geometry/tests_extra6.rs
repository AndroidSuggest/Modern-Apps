use super::build::build;
use super::mesh::{LayerMesh, TileMesh};
use crate::style;
use crate::style::paint::Ramp;
use crate::style::{Layer, LayerKind};
use tilecodec::mamaps::body::{
    Body, Feature, Layer as BodyLayer, Part, GEOM_LINE, GEOM_POLYGON, NAME_NONE, WINDING_OUTER,
};
use tilecodec::mamaps::dict;

/// A representative v7 body: one `earth` polygon, one `major_road` LineString
/// (kind 45) and one `water` polygon — the same layers the old MVT fixture
/// carried, built directly as a body so the tests no longer depend on the
/// MVT→body converter.
fn real() -> Body {
    let mut body = Body::new(4096);
    let mut earth = BodyLayer::new(dict::LAYER_EARTH);
    earth.features.push(Feature {
        kind: 1,
        kind_detail: dict::NONE,
        geom_type: GEOM_POLYGON,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    earth.parts.push(Part {
        coord_start: 0,
        point_count: 4,
        winding: WINDING_OUTER,
    });
    earth.coords = vec![(0, 0), (4096, 0), (4096, 4096), (0, 4096)];
    body.layers.push(earth);
    let mut roads = BodyLayer::new(dict::LAYER_ROADS);
    roads.features.push(Feature {
        kind: crate::style::kind_id_for_test("major_road"),
        kind_detail: dict::NONE,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 1,
    });
    roads.parts.push(Part {
        coord_start: 0,
        point_count: 4,
        winding: WINDING_OUTER,
    });
    roads.coords = vec![(100, 100), (1500, 900), (2600, 1800), (3900, 2700)];
    body.layers.push(roads);
    let mut water = BodyLayer::new(dict::LAYER_WATER);
    water.features.push(Feature {
        kind: 4,
        kind_detail: dict::NONE,
        geom_type: GEOM_POLYGON,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    water.parts.push(Part {
        coord_start: 0,
        point_count: 4,
        winding: WINDING_OUTER,
    });
    water.coords = vec![(500, 3000), (1100, 3000), (1100, 3400), (500, 3400)];
    body.layers.push(water);
    body
}

fn mesh_for<'a>(mesh: &'a TileMesh, layers: &[Layer], id: &str) -> Option<&'a LayerMesh> {
    mesh.meshes.iter().find(|m| layers[m.layer_index].id == id)
}

#[test]
fn a_line_layer_also_strokes_polygon_outlines() {
    // A lake shoreline and an administrative boundary are lines over area features.
    let outline = vec![Layer {
        id: "water-edge".to_string(),
        source_layer: "water".to_string(),
        source_layer_id: tilecodec::mamaps::dict::LAYER_WATER,
        kind: LayerKind::Line,
        kinds: Vec::new(),
        kind_ids: Vec::new(),
        require_flags: 0,
        forbid_flags: 0,
        detail_ids: Vec::new(),
        forbid_details: Vec::new(),
        light: 0xFF000000,
        dark: 0xFF000000,
        opacity: Ramp::constant(1.0),
        width: Ramp::constant(1.0),
        gap_width: Ramp::constant(0.0),
        spread: Ramp::constant(0.0),
        lanes: Ramp::constant(1.0),
        carriageway: false,
        dash: (0.0, 0.0),
        text_size: Ramp::constant(1.0),
        text_size_large: None,
        rank_threshold: None,
        uppercase: false,
        medium: false,
        toggle: None,
        icon: false,
        text_offset: (0.0, 0.0),
        text_max_width: 0.0,
        variable_anchor: Vec::new(),
        halo_light: 0x00000000,
        halo_dark: 0x00000000,
        halo_width: 1.0,
        min_zoom: 0,
        browse_min_zoom: 0,
        max_zoom: 22,
        authored: "water".to_string(),
    }];
    let mesh = build(&real(), &outline, 11, 339, 770, false);
    assert_eq!(mesh.meshes.len(), 1, "the water polygons' outlines stroke");
    assert!(!mesh.meshes[0].indices.is_empty());
}
/// **The Phase 4 milestone, end to end inside this crate.** A `.mamaps` archive is built,
/// opened through a `RangeReader`, and tessellated -- so the container, the reader, the style's
/// interned ids and this module are proven together, on data the tiler already produced.
#[test]
fn a_tile_read_out_of_a_mamaps_archive_tessellates() {
    use std::cell::RefCell;
    use tilecodec::mamaps::write::{Options, StreamWriter};
    use tilecodec::mamaps::MamapsArchive;
    use tilecodec::stream::RangeReader;

    struct Memory {
        bytes: Vec<u8>,
        requests: RefCell<usize>,
    }
    impl RangeReader for Memory {
        fn read(&self, offset: u64, length: u32) -> tilecodec::proto::Result<Vec<u8>> {
            *self.requests.borrow_mut() += 1;
            if offset >= self.bytes.len() as u64 {
                return Ok(Vec::new());
            }
            let end = (offset + length as u64).min(self.bytes.len() as u64);
            Ok(self.bytes[offset as usize..end as usize].to_vec())
        }
    }

    let id = tilecodec::pmtiles::tile_id(11, 339, 770);
    let options = Options {
        min_zoom: 0,
        max_zoom: 14,
        ..Options::default()
    };
    let mut writer = StreamWriter::new(options).expect("options");
    writer.append(id, &real()).expect("append");
    let bytes = writer.finish().expect("finish");

    let mut archive = MamapsArchive::open(Memory {
        bytes,
        requests: RefCell::new(0),
    })
    .expect("open");
    assert_eq!(
        *archive.reader().requests.borrow(),
        1,
        "a cold open is one request"
    );

    let layers = style::layers();
    let body = archive.tile(11, 339, 770).expect("read").expect("present");
    let mesh = build(
        &body,
        layers,
        11,
        339,
        770,
        archive.header.rings_validated(),
    );
    // The same layers the fixture produces when tessellated directly, so nothing was lost
    // between the encoder and the reader.
    for id in ["earth", "water", "roads-major", "roads-major-casing"] {
        assert!(mesh_for(&mesh, layers, id).is_some(), "{id} should draw");
    }
    assert!(
        mesh_for(&mesh, layers, "buildings").is_none(),
        "the tile has no buildings"
    );
}

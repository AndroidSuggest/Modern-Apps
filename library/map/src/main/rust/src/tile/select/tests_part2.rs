use super::*;
use crate::camera::Camera;

fn camera(lon: f64, lat: f64, zoom: f64, w: f32, h: f32) -> Camera {
    Camera {
        center_lon: lon,
        center_lat: lat,
        zoom,
        width_dp: w,
        height_dp: h,
        density: 1.0,
        bearing_deg: 0.0,
        pitch_deg: 0.0,
        time_seconds: 0.0,
    }
}

#[test]
fn ancestors_are_included_and_ordered_under_their_descendants() {
    // Without ancestors the map goes blank on every zoom step: z changes, every resident
    // tile is dropped, and nothing draws until the new level arrives.
    let c = camera(-122.4194, 37.7749, 14.0, 411.0, 891.0);
    let exact = visible(&c, 0, 16);
    let all = resident_set(&c, 0, 16);
    assert!(all.len() > exact.len(), "ancestors must be added");

    // Every exact tile is still present.
    for tile in &exact {
        assert!(all.contains(tile), "{tile:?} was dropped");
    }
    // Every ancestor really is one: the child's coordinates shifted right.
    for tile in &all {
        if exact.contains(tile) {
            continue;
        }
        let covers = exact.iter().any(|child| {
            child.z > tile.z && {
                let shift = child.z - tile.z;
                child.x >> shift == tile.x && child.y >> shift == tile.y
            }
        });
        assert!(covers, "{tile:?} is not an ancestor of any visible tile");
    }
    // Coarsest first: the renderer draws in this order, so a parent must precede its
    // child or a stale parent lands on top of the sharp child.
    let zooms: Vec<u8> = all.iter().map(|t| t.z).collect();
    let mut sorted = zooms.clone();
    sorted.sort_unstable();
    assert_eq!(zooms, sorted, "ancestors must come before descendants");
}

#[test]
fn ancestors_never_go_below_the_archives_minimum_zoom() {
    let c = camera(0.0, 0.0, 6.0, 411.0, 891.0);
    for tile in resident_set(&c, 5, 16) {
        assert!(tile.z >= 5, "{tile:?} is below the archive's min zoom");
    }
}

#[test]
fn ancestors_are_distinct_and_bounded() {
    let c = camera(-122.4194, 37.7749, 16.0, 411.0, 891.0);
    let all = resident_set(&c, 0, 16);
    let mut keys: Vec<u64> = all.iter().map(|t| t.key()).collect();
    let count = keys.len();
    keys.sort_unstable();
    keys.dedup();
    assert_eq!(count, keys.len(), "a tile is listed twice");
    // Residency is GPU memory, so this must stay proportional to the viewport rather
    // than growing with depth.
    assert!(
        all.len() < bound(&c) * 3,
        "{} tiles is too many to keep resident",
        all.len()
    );
}

#[test]
fn an_unmeasured_viewport_asks_for_nothing_even_with_ancestors() {
    // The viewport is null until Compose measures it. Asking for ancestors of nothing
    // must stay nothing rather than falling back to the whole world.
    let unmeasured = camera(0.0, 0.0, 5.0, 0.0, 0.0);
    assert!(visible(&unmeasured, 0, 16).is_empty());
    assert!(resident_set(&unmeasured, 0, 16).is_empty());
}

#[test]
fn every_tile_is_distinct() {
    let tiles = visible(&camera(2.3522, 48.8566, 12.0, 411.0, 891.0), 0, 16);
    let mut keys: Vec<u64> = tiles.iter().map(|t| t.key()).collect();
    let count = keys.len();
    keys.sort_unstable();
    keys.dedup();
    assert_eq!(count, keys.len(), "no tile is requested twice");
}

#[test]
fn tile_keys_do_not_collide_across_the_archives_zoom_range() {
    let mut seen = std::collections::HashSet::new();
    for z in 0..=16u8 {
        let n = 1u32 << z;
        let mut coords = vec![0, n / 2, n - 1];
        coords.dedup();
        for &x in &coords {
            for &y in &coords {
                assert!(
                    seen.insert(TileId { z, x, y }.key()),
                    "z{z}/{x}/{y} collided"
                );
            }
        }
    }
}

#[test]
fn a_key_round_trips() {
    // `from_key` is what lets the renderer recover a tile's position from its residency map
    // key alone, so the two must agree at the edges of the bit packing as well as the middle.
    for z in 0..=22u8 {
        let n = 1u32 << z;
        for &(x, y) in &[(0, 0), (n - 1, n - 1), (n / 2, n / 3)] {
            let tile = TileId { z, x, y };
            assert_eq!(TileId::from_key(tile.key()), tile);
        }
    }
}

#[test]
fn a_tilted_viewport_pulls_in_the_trapezoid_toward_the_horizon() {
    // Under tilt the top of the screen recedes toward the horizon, so the covered ground is a
    // trapezoid larger than the flat viewport box. A pitched camera must ask for strictly more
    // tiles than the same level camera, and every level-camera tile must still be present.
    let level = camera(-122.4194, 37.7749, 14.0, 411.0, 891.0);
    let tilted = Camera {
        pitch_deg: 55.0,
        ..level
    };
    let flat = visible(&level, 0, 16);
    let pitched = visible(&tilted, 0, 16);
    assert!(
        pitched.len() > flat.len(),
        "a tilted camera covers the receding trapezoid: {} vs {}",
        pitched.len(),
        flat.len(),
    );
    for tile in &flat {
        assert!(pitched.contains(tile), "{tile:?} was dropped by the tilt");
    }
    assert!(
        pitched.len() <= bound(&tilted),
        "the bound must grow with the tilt too"
    );
}

#[test]
fn every_on_screen_ground_point_of_a_tilted_view_lands_in_a_selected_tile() {
    // The property that matters: whatever the tilt (and bearing), the ground under each screen
    // corner belongs to a tile that was asked for. Uses the same tilt-aware unproject the
    // renderer draws with, so selection and drawing agree.
    let c = Camera {
        pitch_deg: 50.0,
        bearing_deg: 30.0,
        ..camera(2.3522, 48.8566, 13.0, 411.0, 891.0)
    };
    let tiles = visible(&c, 0, 16);
    let span = c.tile_span_dp(13);
    for &(sx, sy) in &[
        (0.0, 0.0),
        (411.0, 0.0),
        (411.0, 891.0),
        (0.0, 891.0),
        (205.0, 20.0),
    ] {
        let ground = c
            .screen_to_world(sx, sy)
            .expect("below the horizon under the cap");
        let tx = (ground.x / span).floor() as i64;
        let ty = (ground.y / span).floor() as i64;
        assert!(
            tiles.iter().any(|t| t.x as i64 == tx && t.y as i64 == ty),
            "nothing covers the on-screen point at ({sx},{sy}) -> tile 13/{tx}/{ty}",
        );
    }
}

#[test]
fn a_zero_pitch_selection_is_unchanged() {
    // The regression guard: adding tilt coverage must not perturb the flat/phone path. At pitch
    // 0 the coverage box is exactly the viewport bounds, so the selection is what it always was.
    let c = camera(-122.4194, 37.7749, 14.0, 411.0, 891.0);
    let turned = Camera {
        bearing_deg: 37.0,
        ..c
    };
    assert_eq!(
        visible(&c, 0, 16),
        visible(
            &Camera {
                pitch_deg: 0.0,
                ..c
            },
            0,
            16
        )
    );
    assert_eq!(
        visible(&turned, 0, 16),
        visible(
            &Camera {
                pitch_deg: 0.0,
                ..turned
            },
            0,
            16
        )
    );
}

#[test]
fn a_tile_descends_from_its_ancestors_and_from_itself() {
    let tile = TileId {
        z: 12,
        x: 2048,
        y: 1362,
    };
    assert!(tile.descends_from(&tile, 2), "a tile stands in for itself");
    assert!(tile.descends_from(
        &TileId {
            z: 11,
            x: 1024,
            y: 681
        },
        2
    ));
    assert!(tile.descends_from(
        &TileId {
            z: 10,
            x: 512,
            y: 340
        },
        2
    ));
}

#[test]
fn descent_stops_at_the_depth_and_never_goes_upwards() {
    let tile = TileId {
        z: 12,
        x: 2048,
        y: 1362,
    };
    // Three levels up is a real ancestor, but past the depth we are willing to hold.
    assert!(!tile.descends_from(
        &TileId {
            z: 9,
            x: 256,
            y: 170
        },
        2
    ));
    // An ancestor does not descend from its own descendant.
    assert!(!TileId {
        z: 10,
        x: 512,
        y: 340
    }
    .descends_from(&tile, 2));
    // A neighbour at the same zoom shares no ground.
    assert!(!tile.descends_from(
        &TileId {
            z: 11,
            x: 1025,
            y: 681
        },
        2
    ));
}

#[test]
fn a_resident_descendant_stands_in_for_a_visible_tile() {
    // The zoom-out case: the camera has pulled back to z10 and those tiles are still in
    // flight, but the z12 tiles from a moment ago are resident and cover the same ground.
    let visible = vec![TileId {
        z: 10,
        x: 512,
        y: 340,
    }];
    assert!(stands_in_for_visible(
        TileId {
            z: 12,
            x: 2048,
            y: 1362
        }
        .key(),
        &visible,
        2
    ));
    assert!(stands_in_for_visible(
        TileId {
            z: 11,
            x: 1024,
            y: 681
        }
        .key(),
        &visible,
        2
    ));
    // Deeper than we hold, and elsewhere in the world.
    assert!(!stands_in_for_visible(
        TileId {
            z: 13,
            x: 4096,
            y: 2724
        }
        .key(),
        &visible,
        2
    ));
    assert!(!stands_in_for_visible(
        TileId { z: 12, x: 8, y: 8 }.key(),
        &visible,
        2
    ));
}

#[test]
fn the_keep_list_stays_free_of_descendants() {
    // Descendants are recognised against what is resident, never enumerated into the keep
    // list: naming them would be 4^DESCENDANT_DEPTH keys per visible tile, almost all of
    // which were never fetched. This is the invariant that lets the bound above hold.
    let c = camera(-122.4194, 37.7749, 14.0, 411.0, 891.0);
    let exact = visible(&c, 0, 16);
    let deepest = exact.iter().map(|t| t.z).max().expect("a tile");
    for tile in resident_set(&c, 0, 16) {
        assert!(
            tile.z <= deepest,
            "{tile:?} is deeper than any visible tile"
        );
    }
}

//! Flat-style tests, part 5: kind closure and authored-layer existence.
//!
//! Split from [`paint`]'s test module so each file stays small.

use super::paint_extra2::{authored_filter_kinds, authored_layer, basemap};
use crate::style::{layers, LayerKind};
use serde_json::Value as Json;

/// The other half of the cross-check, and the larger hand-transcribed surface: **which
/// features each layer draws**.
///
/// A data-driven authored layer becomes several flat layers, one per colour, so no single
/// flat layer's `kinds` matches the authored filter. What must hold is closure: the union
/// across the family is exactly the set the authored filter admits, so a kind cannot be
/// dropped (it would stop being drawn) or invented (it would be drawn in the wrong colour).
#[test]
fn the_kinds_each_authored_layer_admits_are_all_drawn_and_no_others() {
    let root = basemap();
    let mut families: Vec<&str> = Vec::new();
    for layer in layers().iter().filter(|l| l.kind == LayerKind::Fill) {
        if !families.contains(&layer.authored.as_str()) {
            families.push(&layer.authored);
        }
    }
    for family in families {
        let authored = authored_layer(&root, family);
        let mut admitted = authored_filter_kinds(authored.get("filter"));
        let mut drawn: Vec<String> = layers()
            .iter()
            .filter(|l| l.authored == family)
            .flat_map(|l| l.kinds.iter().cloned())
            .collect();
        if admitted.is_empty() {
            // An unrestricted authored layer needs an unfiltered flat layer, or the kinds
            // its colour expression does not name would stop being drawn at all.
            assert!(
                layers()
                    .iter()
                    .any(|l| l.authored == family && l.kinds.is_empty()),
                "`{family}` admits every kind but no flat layer draws them",
            );
            continue;
        }
        admitted.sort_unstable();
        drawn.sort_unstable();
        // Kinds we deliberately do not draw, and why. Checked explicitly so the assertion
        // below still catches an *accidental* divergence from upstream, which is what it is
        // for — a silent one would mean a kind quietly stopped rendering.
        //
        // `protected_area` and `nature_reserve` are the tags the world's MARINE protected
        // areas carry. The sea has no geometry, so nothing is drawn over them, and on a planet
        // build they painted green across open water — the reported bug. Deriving sea geometry
        // to cover them was attempted twice and failed twice; see `mamaps_build`'s
        // `tiler::add_ocean` for both failure modes. Not drawing them is the fix that works.
        //
        // The cost, stated plainly: a protected area or nature reserve *on land* is no longer
        // green. Restore them here the day the sea can paint over them.
        const NOT_DRAWN: &[&str] = &["protected_area", "nature_reserve"];
        let (skipped, admitted): (Vec<String>, Vec<String>) = admitted
            .into_iter()
            .partition(|k| NOT_DRAWN.contains(&k.as_str()));
        for kind in &skipped {
            assert!(
                !drawn.contains(kind),
                "`{kind}` is in NOT_DRAWN but a flat layer still draws it",
            );
        }
        // Every kind exactly once: two flat layers claiming the same kind would draw it
        // twice, in whichever colour came last.
        assert_eq!(
            drawn, admitted,
            "the flat layers for `{family}` draw a different kind set than its filter admits",
        );
    }
}

/// Every authored layer a flat layer names has to exist, or the cross-check silently stops
/// checking that layer.
#[test]
fn every_authored_layer_a_flat_layer_names_exists() {
    let root = basemap();
    let ids: Vec<&str> = root
        .get("layers")
        .and_then(Json::as_array)
        .expect("layers")
        .iter()
        .filter_map(|layer| layer.get("id").and_then(Json::as_str))
        .collect();
    for layer in layers() {
        assert!(
            ids.contains(&layer.authored.as_str()),
            "`{}` names authored layer `{}`, which is not in basemap.json",
            layer.id,
            layer.authored,
        );
    }
}

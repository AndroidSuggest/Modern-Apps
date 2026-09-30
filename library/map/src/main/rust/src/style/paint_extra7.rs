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
    // App-only v8 families with no authored counterpart (see
    // `every_authored_layer_a_flat_layer_names_exists`): their kind whitelists
    // are pinned by `the_interned_whitelist_is_the_authored_one`, not here.
    const APP_ONLY_FAMILIES: &[&str] = &[
        "landuse_orchard",
        "landuse_vineyard",
        "landuse_quarry",
        "landuse_swimming_pool",
        "landuse_residential",
        "landuse_commercial",
    ];
    for family in families {
        if APP_ONLY_FAMILIES.contains(&family) {
            continue;
        }
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
            //
            // v8 merges the wash sources: authored `earth` and `water` are both type-only
            // filters over their own sources, while every flat arm reads merged
            // `landtype`. Flat `earth` (empty whitelist — matches every kind) is the
            // unfiltered member for both families: it draws the mainland and every
            // water kind, and flat `water` repaints the water kinds over it. So family
            // `water` is covered without its own unfiltered arm.
            let covered = layers().iter().any(|l| {
                l.kinds.is_empty()
                    && (l.authored == family
                        || (family == "water"
                            && l.authored == "earth"
                            && l.source_layer == "landtype"))
            });
            assert!(
                covered,
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
        // areas carry (Monterey Bay tile 12/659/1595: 100.5% `protected_area`, no water
        // polygon). The sea has no geometry, so drawing them paints green across open
        // water; and the match-all `earth`/`landcover` arms used to claim them, painting
        // the ocean as *land*. Both arms now carry `forbid_kinds` for these two, so no
        // fill arm draws them at all and the sea stays the water-blue clear colour.
        //
        // The cost, stated plainly: a protected area or nature reserve *on land* is no
        // longer green either (no arm draws them anywhere). Restoring land green needs
        // a layer that draws them only where land is — i.e. sea geometry to paint over.
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
///
/// The six v8 landtype arms (`landuse_orchard` … `landuse_commercial`) are exempt: the
/// reference style has no such arms, so there is nothing to name. Their sources and kind
/// whitelists are pinned by `the_interned_whitelist_is_the_authored_one` instead.
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
    // App-only flat layers with no authored counterpart (see above).
    const APP_ONLY: &[&str] = &[
        "landuse_orchard",
        "landuse_vineyard",
        "landuse_quarry",
        "landuse_swimming_pool",
        "landuse_residential",
        "landuse_commercial",
    ];
    for layer in layers() {
        if APP_ONLY.contains(&layer.authored.as_str()) {
            continue;
        }
        assert!(
            ids.contains(&layer.authored.as_str()),
            "`{}` names authored layer `{}`, which is not in basemap.json",
            layer.id,
            layer.authored,
        );
    }
}

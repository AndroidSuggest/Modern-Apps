/// The build report, as JSON so a script can diff two builds.
fn build_report(
    stats: &extract::Stats,
    per_zoom: &[tiler::ZoomStats],
    build_id: u64,
    bytes: usize,
) -> String {
    let mut out = String::from("{\n");
    out.push_str(&format!("  \"build_id\": \"{build_id:#018x}\",\n"));
    out.push_str(&format!("  \"file_bytes\": {bytes},\n"));
    out.push_str(&format!("  \"ways_classified\": {},\n", stats.ways_classified));
    out.push_str(&format!("  \"relations_classified\": {},\n", stats.relations_classified));
    out.push_str(&format!("  \"nodes_classified\": {},\n", stats.nodes_classified));
    out.push_str(&format!("  \"transit_routes\": {},\n", stats.transit_routes));
    out.push_str(&format!("  \"corridor_promotions\": {},\n", stats.corridor_promotions));
    out.push_str(&format!("  \"lanes_inherited\": {},\n", stats.lanes_inherited));
    out.push_str(&format!("  \"features\": {},\n", stats.features));
    out.push_str(&format!("  \"geometry_failed\": {},\n", stats.geometry_failed));
    out.push_str(&format!("  \"nodes_needed\": {},\n", stats.nodes_needed));
    let lines = per_zoom.iter().fold(coalesce::Stats::default(), |mut a, z| {
        a.add(z.lines);
        a
    });
    out.push_str(&format!(
        "  \"coalesced\": {{ \"line_features_before\": {}, \"line_features_after\": {}, \
         \"parts_before\": {}, \"parts_after\": {} }},\n",
        lines.features_before, lines.features_after, lines.parts_before, lines.parts_after,
    ));
    out.push_str("  \"zooms\": [\n");
    for (i, z) in per_zoom.iter().enumerate() {
        out.push_str(&format!(
            "    {{ \"zoom\": {}, \"tiles\": {}, \"features\": {}, \"points\": {}, \
             \"dropped\": {}, \"bytes\": {}, \"map_ms\": {}, \"merge_ms\": {}, \
             \"encode_ms\": {}, \"append_ms\": {} }}{}\n",
            z.zoom,
            z.tiles,
            z.features,
            z.points,
            z.dropped,
            z.bytes,
            z.map_ms,
            z.merge_ms,
            z.encode_ms,
            z.append_ms,
            if i + 1 == per_zoom.len() { "" } else { "," },
        ));
    }
    out.push_str("  ]\n}\n");
    out
}

fn usage() {
    eprintln!(
        "usage: mamaps_build --input IN.osm.pbf --out OUT.mamaps\n\
         \x20                   [--layers earth,water,buildings,roads,boundaries,landcover,landuse]\n\
         \x20                   [--min-zoom N] [--max-zoom N]\n\
         \x20                   [--coastline LAND.shp|LAND.geojsonseq]\n\
         \x20                   [--transit-routes ROUTES.geojsonseq]\n\
         \x20                   [--graph GRAPH_DIR]\n\
         \x20                   [--dem HEIGHTMAPS.mdem]\n\
         \x20                   [--simplification F] [--build-id N] [--report FILE]\n\
         \x20                   [--keep-store] [--reuse-store] [--shared-table]\n\
         \n\
         --keep-store   leave the feature spill and its index behind\n\
         --reuse-store  tile from that spill instead of re-running stage A\n\
         --shared-table intern the v8 shared section (logical rows + slim refs)"
    );
}

/// Whether this build is allowed to proceed given what it was told about land.
///
/// **The coastline is required, with no way to decline it.** It used to be a warning, which is
/// how an archive ends up with islands and no mainland: the line scrolls past in a build that
/// takes hours and nobody reads it until the map is on a phone. A `--no-coastline` escape hatch
/// then replaced the warning and reintroduced the same failure a keystroke at a time — it existed
/// for the determinism harness, which did not want to stream a 1.3 GB shapefile three times, and
/// a flag that exists for a test is a flag a real build will eventually be run with. The harness
/// passes a real coastline now.
///
/// Building without land is still reachable, but only by saying what you mean: leave `earth` out
/// of `--layers`, which produces an archive that has no land layer rather than one whose land
/// layer is quietly wrong.
///
/// This applies to `--reuse-store` too, even though that skips the stage A which reads the
/// shapefile. The spill's fingerprint records whether it was built with a coastline, and a reuse
/// has to declare the same flags for the check to pass — so the path is still named, still
/// required, and simply never re-read.
fn check_coastline(coastline: Option<&std::path::Path>, earth: bool) -> Result<(), String> {
    if coastline.is_some() && !earth {
        return Err("--coastline was given but the earth layer is not selected".to_string());
    }
    if coastline.is_none() && earth {
        return Err(
            "--coastline is required: without it `earth` carries islands only and the map has \
             no mainland. Pass the OSMCoastline land-polygons shapefile (or GeoJSONSeq). To \
             build with no land at all, drop `earth` from --layers."
                .to_string(),
        );
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The coastline is **required**, and there is no flag that says otherwise.
    ///
    /// It used to print a line and carry on, which is how an archive ships with islands and no
    /// mainland: the warning scrolls past in a build that takes hours and nobody sees it until
    /// the map is on a phone. `--no-coastline` replaced that warning and brought the same
    /// failure back as a one-word opt-in, so it is gone. Dropping `earth` from `--layers` is
    /// the only way to build without land, and it says so in the layer set.
    #[test]
    fn a_build_with_earth_cannot_proceed_without_land() {
        let shp = std::path::Path::new("land_polygons.shp");
        assert!(check_coastline(Some(shp), true).is_ok(), "the normal build");
        // Not selecting `earth` at all: there is no land layer to be missing.
        assert!(check_coastline(None, false).is_ok());

        assert!(check_coastline(None, true).is_err(), "silently landless");
        assert!(check_coastline(Some(shp), false).is_err(), "land with no earth layer");
    }

    /// The schema's own floor for buildings and the style's have to agree, or the archive either
    /// carries what nothing draws or is asked for what it does not carry.
    #[test]
    fn the_schema_floor_matches_the_styles() {
        // The style's floor lives in `library/map`'s flat style, which this crate does not link. So
        // this pins the number and names where the other copy is: a change here without a change
        // there shows up as a tile the renderer asks for and does not get.
        assert_eq!(
            schema::buildings::MIN_ZOOM,
            14,
            "buildings' floor is 14 in library/map/src/main/rust/style/basemap.flat.json too",
        );
    }

    /// The id has to change when the data would, and not otherwise. A rebuild from an unchanged file
    /// keeps its id, which is what makes a byte-identical rebuild byte-identical.
    #[test]
    fn a_build_id_follows_the_inputs_that_decide_the_output() {
        let path = std::path::Path::new("nonexistent.osm.pbf");
        let all = schema::Layers::all();
        let base = derive_build_id(path, all, 0, 14, 1.0, 100, false, false, false);
        assert_eq!(base, derive_build_id(path, all, 0, 14, 1.0, 100, false, false, false), "stable");
        for other in [
            derive_build_id(path, all, 1, 14, 1.0, 100, false, false, false),
            derive_build_id(path, all, 0, 15, 1.0, 100, false, false, false),
            derive_build_id(path, all, 0, 14, 2.0, 100, false, false, false),
            derive_build_id(path, all, 0, 14, 1.0, 101, false, false, false),
            // A transit-routes file adds a whole layer the same `.pbf` would not produce.
            derive_build_id(path, all, 0, 14, 1.0, 100, true, false, false),
            // A routing graph adds the traffic layer the same `.pbf` would not produce.
            derive_build_id(path, all, 0, 14, 1.0, 100, false, true, false),
            // A shared-table build appends the v8 section a v7 build does not carry.
            derive_build_id(path, all, 0, 14, 1.0, 100, false, false, true),
            derive_build_id(
                path,
                schema::Layers { water: true, ..schema::Layers::none() },
                0,
                14,
                1.0,
                100,
                false,
                false,
                false,
            ),
            derive_build_id(
                std::path::Path::new("other.osm.pbf"),
                all,
                0,
                14,
                1.0,
                100,
                false,
                false,
                false,
            ),
        ] {
            assert_ne!(base, other, "a changed input should change the id");
        }
    }

    #[test]
    fn the_report_is_valid_json_shaped_output() {
        let stats = extract::Stats { features: 3, ..extract::Stats::default() };
        let zooms = vec![
            tiler::ZoomStats { zoom: 0, tiles: 1, features: 3, points: 12, dropped: 0, bytes: 40, ..Default::default() },
            tiler::ZoomStats { zoom: 1, tiles: 4, features: 3, points: 20, dropped: 1, bytes: 90, ..Default::default() },
        ];
        let report = build_report(&stats, &zooms, 42, 1024);
        assert!(report.starts_with("{\n") && report.ends_with("}\n"));
        assert_eq!(report.matches("\"zoom\":").count(), 2);
        // No trailing comma on the last entry, which is the one thing hand-written JSON gets wrong.
        assert!(!report.contains("},\n  ]"));
    }
}

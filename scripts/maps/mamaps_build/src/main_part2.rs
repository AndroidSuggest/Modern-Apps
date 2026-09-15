fn usage() {
    eprintln!(
        "usage: mamaps_build --input IN.osm.pbf --out OUT.mamaps\n\
        \x20                   --coastline LAND.shp|LAND.geojsonseq\n\
        \x20                   --transit-routes ROUTES.geojsonseq\n\
        \x20                   --graph GRAPH_DIR\n\
        \x20                   --dem HEIGHTMAPS.mdem\n\
        \n\
        \x20All six flags are required. Every build carries all 12 layers at\n\
        \x20z0-14 as FORMAT_VERSION 7."
    );
}

/// Whether this build is allowed to proceed given what it was told about a side input.
///
/// **Every side input is required, with no way to decline it.** A missing coastline used to be a
/// warning, which is how an archive ends up with islands and no mainland: the line scrolls past
/// in a build that takes hours and nobody reads it until the map is on a phone. The same holds
/// for the other three: the `transit` layer comes from GTFS rather than the `.osm.pbf`, the
/// `traffic` and `junction` layers from the routing graph, and every tile's heightmap from the
/// DEM — so a build without one is silently missing a layer, not a build with different options.
fn check_required(path: &std::path::Path, flag: &str) -> Result<(), String> {
    if path.as_os_str().is_empty() {
        return Err(format!("{flag} is required: every build carries all 12 layers"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every side input is **required**, and there is no flag that says otherwise.
    ///
    /// A missing input used to print a line and carry on, which is how an archive ships with
    /// islands and no mainland: the warning scrolls past in a build that takes hours and nobody
    /// sees it until the map is on a phone.
    #[test]
    fn a_build_cannot_proceed_without_its_side_inputs() {
        let shp = std::path::Path::new("land_polygons.shp");
        assert!(check_required(shp, "--coastline").is_ok(), "the normal build");
        assert!(
            check_required(std::path::Path::new(""), "--coastline").is_err(),
            "an empty path is a missing input",
        );
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
        let base = derive_build_id(path, all, 0, 14, 1.0, 100);
        assert_eq!(base, derive_build_id(path, all, 0, 14, 1.0, 100), "stable");
        for other in [
            derive_build_id(path, all, 0, 14, 2.0, 100),
            derive_build_id(path, all, 0, 14, 1.0, 101),
            derive_build_id(
                std::path::Path::new("other.osm.pbf"),
                all,
                0,
                14,
                1.0,
                100,
            ),
        ] {
            assert_ne!(base, other, "a changed input should change the id");
        }
    }
}

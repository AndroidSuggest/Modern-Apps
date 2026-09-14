fn run(
    input: &std::path::Path,
    out: &std::path::Path,
    run: &RunSettings,
) -> Result<(), String> {
    let (layers, min_zoom, max_zoom, simplification) =
        (run.layers, run.min_zoom, run.max_zoom, run.simplification);
    let report = run.report.as_deref();
    let started = std::time::Instant::now();
    println!("reading {}", input.display());
    // Features are spilled here rather than held: it was 4.9 GB of a measured 10.03 GB California
    // peak, and nothing reads them until the tiler does.
    let spill = out.with_extension("features.tmp");
    check_coastline(run.coastline.as_deref(), layers.earth)?;
    // The same asymmetry, for the same reasons: the flag without the layer is a mistake worth
    // stopping for, the layer without the flag is a legitimate build of an archive with no transit.
    if run.transit_routes.is_some() && !layers.transit {
        return Err("--transit-routes was given but the transit layer is not selected".to_string());
    }
    if run.transit_routes.is_none() && layers.transit {
        println!(
            "no --transit-routes given, so `transit` is empty; its lines come from GTFS, not the .pbf"
        );
    }
    // The same asymmetry once more, for the two layers the routing graph feeds: the flag without
    // either layer is a mistake worth stopping for, a layer without the flag is a legitimate build
    // of an archive with no traffic overlay and no lane connectors.
    if run.graph.is_some() && !layers.traffic && !layers.junction {
        return Err(
            "--graph was given but neither the traffic nor the junction layer is selected"
                .to_string(),
        );
    }
    if run.graph.is_none() && (layers.traffic || layers.junction) {
        println!(
            "no --graph given, so `traffic` and `junction` are empty; their lines come from the v6 routing graph, not the .pbf"
        );
    }
    let provenance = store::Provenance::of(
        input,
        layers,
        run.coastline.is_some(),
        run.transit_routes.is_some(),
        run.graph.is_some(),
    )
    .map_err(|e| e.to_string())?;
    // Stage A is most of a large build -- 17.6 minutes of a north-america run, and identical every
    // time for the same input and layer set. `--reuse-store` skips it, which is what makes iterating
    // on the tiler affordable. The index records what it was built from and `Store::open` refuses a
    // mismatch, so the shortcut cannot silently produce an archive missing most of the world.
    let (store, stats) = if run.reuse_store {
        let (store, features) =
            store::Store::open(&spill, provenance).map_err(|e| e.to_string())?;
        println!(
            "reusing the feature spill at {} ({} feature(s)); stage A skipped",
            spill.display(),
            features,
        );
        (store, extract::Stats { features, ..Default::default() })
    } else {
        let (store, stats) = extract::extract(
            input,
            layers,
            run.coastline.as_deref(),
            run.transit_routes.as_deref(),
            run.graph.as_deref(),
            &spill,
        )
        .map_err(|e| format!("{}: {e}", input.display()))?;
        println!(
            "classified {} way(s), {} relation(s) and {} node(s) -> {} feature(s), {} node(s) resolved",
            stats.ways_classified,
            stats.relations_classified,
            stats.nodes_classified,
            stats.features,
            stats.nodes_needed,
        );
        if stats.geometry_failed > 0 {
            // Expected at an extract's cut edges, and worth reporting because a large count means
            // something else.
            println!("  {} classified element(s) produced no geometry", stats.geometry_failed);
        }
        if stats.land_polygons > 0 {
            println!("  including {} prepared land polygon(s)", stats.land_polygons);
        }
        if stats.transit_routes > 0 {
            println!("  including {} coloured transit route(s) from GTFS", stats.transit_routes);
        }
        if stats.traffic_segments > 0 {
            println!(
                "  including {} drivable component segment(s) for the traffic layer",
                stats.traffic_segments,
            );
        }
        if stats.junction_connectors > 0 {
            println!(
                "  including {} lane connector(s) for the junction layer",
                stats.junction_connectors,
            );
        }
        if stats.corridor_promotions > 0 {
            println!(
                "  {} road way(s) pulled to their corridor's zoom",
                stats.corridor_promotions,
            );
        }
        if stats.lanes_inherited > 0 {
            println!(
                "  {} untagged road way(s) took a lane count from a neighbour",
                stats.lanes_inherited,
            );
        }
        if run.keep_store {
            let index = store.save_index(provenance, stats.features).map_err(|e| e.to_string())?;
            println!("  wrote {} so --reuse-store can skip stage A", index.display());
        }
        (store, stats)
    };

    // The build id identifies the *data*: change the input, the zoom range, the layer set or the
    // simplification and every reader has to drop its cache. Derived rather than asked for, so a
    // forgotten `--build-id` cannot silently republish under the old one.
    let build_id = run.build_id.unwrap_or_else(|| {
        derive_build_id(
            input,
            layers,
            min_zoom,
            max_zoom,
            simplification,
            stats.features,
            run.transit_routes.is_some(),
            run.graph.is_some(),
            run.shared_table,
        )
    });

    // The DEM heightmap dataset, if one was fetched. Loaded here (this is where I/O belongs) and
    // handed to the tiler, which samples one grid per output tile into the body's heightmap section.
    let dem = match run.dem.as_deref() {
        Some(path) => {
            let dem = dem::Dem::load(path).map_err(|e| e.to_string())?;
            println!("loaded the DEM dataset at {}", path.display());
            Some(dem)
        }
        None => None,
    };

    let settings = tiler::Settings {
        min_zoom,
        max_zoom,
        simplification,
        build_id,
        // Beside the archive, as the feature spill is. One zoom at a time, removed as each finishes.
        scratch: scratch_path(out),
        // OFF. Deriving sea geometry per tile was tried twice and failed twice; see
        // `tiler::add_ocean`. The green-over-water problem it existed to solve is handled in the
        // style instead, by not painting marine protected areas green in the first place.
        ocean: false,
        dem,
        shared_table: run.shared_table,
    };
    let (bytes, per_zoom) = tiler::build(&store, &settings).map_err(|e| e.to_string())?;
    tiler::check_not_empty(&per_zoom).map_err(|e| e.to_string())?;
    std::fs::write(out, &bytes).map_err(|e| format!("cannot write {}: {e}", out.display()))?;
    // The spill is scratch. Removed on success; left behind on failure, where it is evidence, and
    // kept deliberately under `--keep-store`, where it is the input to the next run.
    if !run.keep_store && !run.reuse_store {
        let _ = std::fs::remove_file(&spill);
    }

    println!(
        "\n{:<6}{:>10}{:>12}{:>12}{:>10}{:>12}{:>8}{:>8}{:>8}{:>8}",
        "zoom", "tiles", "features", "points", "dropped", "bytes", "map_s", "merge_s", "enc_s",
        "app_s",
    );
    for z in &per_zoom {
        println!(
            "z{:<5}{:>10}{:>12}{:>12}{:>10}{:>12}{:>8.1}{:>8.1}{:>8.1}{:>8.1}",
            z.zoom,
            z.tiles,
            z.features,
            z.points,
            z.dropped,
            z.bytes,
            z.map_ms as f64 / 1000.0,
            z.merge_ms as f64 / 1000.0,
            z.encode_ms as f64 / 1000.0,
            z.append_ms as f64 / 1000.0,
        );
    }
    // The four phase columns summed, because which of them dominates is the only thing that says
    // whether more cores would help: map and encode run on the pool, merge and append do not.
    let (map, merge, encode, append) = per_zoom.iter().fold((0u64, 0u64, 0u64, 0u64), |a, z| {
        (a.0 + z.map_ms, a.1 + z.merge_ms, a.2 + z.encode_ms, a.3 + z.append_ms)
    });
    let serial = (merge + append) as f64;
    let total = (map + merge + encode + append).max(1) as f64;
    // What coalescing removed. The `features` column above is counted in `push`, during the map
    // phase and therefore BEFORE the merge that coalescing runs after, so it reports what OSM
    // yielded rather than what was written -- which is the number the map phase's cost tracks. This
    // line is what was actually encoded.
    let lines = per_zoom.iter().fold(coalesce::Stats::default(), |mut a, z| {
        a.add(z.lines);
        a
    });
    if lines.features_before > lines.features_after {
        println!(
            "       coalesced: {} line feature(s) -> {} ({:.0}x), {} part(s) -> {} ({:.1}x)",
            lines.features_before,
            lines.features_after,
            lines.features_before as f64 / lines.features_after.max(1) as f64,
            lines.parts_before,
            lines.parts_after,
            lines.parts_before as f64 / lines.parts_after.max(1) as f64,
        );
    }
    let (stage_c, serialize, deflate) = tiler::encode_seconds();
    // How close the archive came to the 65,535-feature cap on one tile-layer. Printed rather than
    // asserted because the answer is what decides whether the field has to widen, and widening it is
    // a `FORMAT_VERSION` bump and a matching renderer change.
    let widest = tiler::widest_layers();
    if !widest.is_empty() {
        let dict = tilecodec::mamaps::dict::Dictionary::schema();
        let worst = widest.iter().map(|(_, n)| *n).max().unwrap_or(0);
        let named: Vec<String> = widest
            .iter()
            .map(|(id, n)| {
                let name = dict.layer_name(*id).unwrap_or("?");
                format!("{name} {n}")
            })
            .collect();
        println!(
            "       widest tile-layer: {} (cap {}, {:.0}% used)",
            named.join(", "),
            u16::MAX,
            worst as f64 / u16::MAX as f64 * 100.0,
        );
    }
    println!(
        "total  map {:.1}s (of which {:.1}s deserialising the spill, on one thread)  merge {:.1}s  encode {:.1}s  append {:.1}s   ({:.0}% of tiling is serial)",
        map as f64 / 1000.0,
        tiler::read_seconds(),
        merge as f64 / 1000.0,
        encode as f64 / 1000.0,
        append as f64 / 1000.0,
        serial / total * 100.0,
    );
    // CPU, not wall, and summed across workers: compared against the encode wall above it says
    // whether that phase is short of work or short of parallelism. Only under `MAPS_TIMING`,
    // because three atomics per tile is not free at a million tiles.
    if stage_c + serialize + deflate > 0.0 {
        println!(
            "       encode CPU: stage C {stage_c:.1}s  serialise {serialize:.1}s  deflate {deflate:.1}s  \
             (={:.1}s of CPU against {:.1}s of wall)",
            stage_c + serialize + deflate,
            encode as f64 / 1000.0,
        );
    }
    println!(
        "\nwrote {} ({} bytes, build_id {build_id:#018x}) in {:.1}s",
        out.display(),
        bytes.len(),
        started.elapsed().as_secs_f64(),
    );
    if let Some(path) = report {
        std::fs::write(path, build_report(&stats, &per_zoom, build_id, bytes.len()))
            .map_err(|e| format!("cannot write {}: {e}", path.display()))?;
        println!("report {}", path.display());
    }
    Ok(())
}

/// Where the tiler parks one zoom's chunks while it merges them.
///
/// Beside the archive, as `<out>.tilechunks`, matching where the feature spill is placed. Appended
/// to the whole file name rather than replacing an extension, so `world.mamaps` and `world.tmp`
/// cannot collide on one path.
fn scratch_path(out: &std::path::Path) -> PathBuf {
    let mut p = out.as_os_str().to_owned();
    p.push(".tilechunks");
    PathBuf::from(p)
}

/// Hash the inputs that decide what an archive contains.
///
/// The input's **length and modification time** rather than its bytes: digesting 1.3 GB to decide a
/// cache key would double the build's I/O for a number that only has to change when the data does.
/// A rebuild from an unchanged file therefore keeps its id, which is what makes a byte-identical
/// rebuild byte-identical.
///
/// The leading generator revision has to be bumped whenever this crate changes what it *puts* in an
/// archive for the same input, because readers cache byte ranges under `(url, build_id)` and the
/// URL is deliberately stable across republishes. A device with a warm cache would otherwise keep
/// serving the old archive's bytes forever. See `library/map/.../tile/source.rs`.
fn derive_build_id(
    input: &std::path::Path,
    layers: schema::Layers,
    min_zoom: u8,
    max_zoom: u8,
    simplification: f64,
    features: u64,
    transit_routes: bool,
    graph: bool,
    shared_table: bool,
) -> u64 {
    let mut h = 0xcbf2_9ce4_8422_2325u64;
    let mut eat = |bytes: &[u8]| {
        for &b in bytes {
            h ^= b as u64;
            h = h.wrapping_mul(0x100_0000_01b3);
        }
    };
    // Revision 13: a `junction` layer (id 11) is baked from the same v6 routing graph — one line
    // per lane connector through an intersection. Folded into `.mamaps` v7 rather than bumping the
    // format, because no v7 archive has been built, but the layer set moves and the dictionary
    // every archive carries gains an entry, so a warm cache must miss.
    //
    // Revision 12: `.mamaps` v4. A `traffic` layer (id 10) is baked from the v6 routing graph —
    // one line per drivable component segment, each carrying its packed `component_id` in the
    // body id table — and `FORMAT_VERSION` bumps 3->4. The layer set and the format both move, so
    // a warm cache must miss.
    //
    // Revision 11: the `boundaries` layer populates the id side table, so a region's shape carries
    // its OSM relation id. Without it a region is an anonymous polygon per tile and nothing says
    // which pieces belong to the same region, so a mask could only ever punch out the one tile
    // under the finger.
    //
    // Revision 10: POI `min_zoom` floors moved. The kinds a category chip selects are carried
    // from z12, most other destinations from z13, and `railway=tram_stop` is classified at all
    // (it was matched by nothing, so street tram stops were absent). Same `.pbf`, different
    // features in the spill.
    //
    // Also `dict::KINDS` gains `region_area`, and an administrative relation now emits the
    // region's shape alongside its border line, for the region mask to read. Appending to the
    // frozen table moves no existing id, but it is still a format change: an older reader would
    // not know the kind.
    //
    // Revision 9: burned. It was taken for boundary relations as areas and a synthesised sea, both
    // of which were reverted before shipping — the areas grew tile-edge segments that the boundary
    // style stroked as a grid across the map, and neither sea derivation survived contact with a
    // real coastline (see `tiler::add_ocean`). Nothing was published under it. Left in the sequence
    // rather than reused, because a revision number's only job is to differ from the last one.
    //
    // Revision 8: `.mamaps` v3. `places` and `poi` features carry a stable OSM id in a new body
    // side table, `dict::KINDS` gains `fuel`, `hotel`, `atm` and `bank`, and v1 bodies are no
    // longer read. Every byte offset in the archive moves, so a warm cache must miss.
    //
    // Revision 7: a `transit` feature carries the lane *inputs* (ordinal, colour count, taper)
    // rather than a baked offset, so the same feeds yield different transit records again.
    //
    // Revision 6: a `transit` feature carries a corridor slot (`transit_spread`), the exporter
    // collapses a route's two directions into one line, and `coalesce` keys on the slot -- so
    // the same feeds now yield different transit geometry again.
    //
    // Revision 5: the `transit` layer is sourced from a GTFS export (`--transit-routes`) rather
    // than from OSM route relations, so the same `.pbf` now yields entirely different transit
    // geometry and colours -- and none at all without the flag.
    //
    // Revision 4: `coalesce` keys line merging on `transit_color` too, so transit lines of one
    // mode but different operator colours no longer collapse into one feature.
    //
    // Revision 3: road `min_zoom` is decided per corridor (`corridor`), and place
    // `kind_detail` carries the reference basemap's 0-15 population rank rather than a
    // three-step one (`schema::places::rank_of`).
    eat(b"mamaps_build/13");
    eat(input.to_string_lossy().as_bytes());
    if let Ok(meta) = std::fs::metadata(input) {
        eat(&meta.len().to_le_bytes());
        if let Ok(time) = meta.modified() {
            if let Ok(since) = time.duration_since(std::time::UNIX_EPOCH) {
                eat(&since.as_secs().to_le_bytes());
            }
        }
    }
    eat(&[
        u8::from(layers.earth),
        u8::from(layers.water),
        u8::from(layers.buildings),
        u8::from(layers.roads),
        u8::from(layers.boundaries),
        u8::from(layers.landcover),
        u8::from(layers.landuse),
        u8::from(layers.places),
        u8::from(layers.poi),
        u8::from(layers.transit),
        u8::from(layers.traffic),
        u8::from(layers.junction),
        min_zoom,
        max_zoom,
        // A build with a transit-routes file and one without carry different layers from the
        // same `.pbf`, and readers cache byte ranges under `(url, build_id)`.
        u8::from(transit_routes),
        // Likewise a build with a graph carries the whole traffic and junction layers that one
        // without does not.
        u8::from(graph),
        // Likewise a shared-table build appends the v8 section past the tile data that a v7
        // build does not carry.
        u8::from(shared_table),
    ]);
    eat(&simplification.to_le_bytes());
    eat(&features.to_le_bytes());
    // The schema table's own version, so a remapped kind invalidates every cache even when the
    // input has not moved.
    eat(&(tilecodec::mamaps::dict::KINDS.len() as u64).to_le_bytes());
    h
}

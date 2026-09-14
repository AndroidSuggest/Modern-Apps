impl RoadRow {
    fn tags(&self) -> RoadTags<'_> {
        RoadTags {
            // `highway` is not re-read: `class` already holds what it classified to.
            highway: None,
            maxspeed: self.maxspeed.as_deref(),
            lanes: self.lanes.as_deref(),
            lanes_forward: self.lanes_forward.as_deref(),
            lanes_backward: self.lanes_backward.as_deref(),
            turn_lanes: self.turn_lanes.as_deref(),
            turn_lanes_forward: self.turn_lanes_forward.as_deref(),
            turn_lanes_backward: self.turn_lanes_backward.as_deref(),
            oneway: self.oneway.as_deref(),
            width: self.width.as_deref(),
            bridge: self.bridge.as_deref(),
            tunnel: self.tunnel.as_deref(),
            layer: self.layer.as_deref(),
        }
    }
}

fn build_roads(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    out: &Path,
    opts: &Options,
) -> Result<Stats> {
    let select = Select::parse(&roads::FILTERS)?;

    // Same two passes as `maxspeed`: ways, then the node coordinates they need.
    let (chunks, blob_kinds) = pbf::run_pass(
        input,
        blobs,
        None,
        KIND_WAYS,
        "Pass 1: ways",
        Vec::<RoadRow>::new,
        |state: &mut Vec<RoadRow>, block| roads_blob(state, block, &select),
    )?;
    let mut rows: Vec<RoadRow> = Vec::new();
    for chunk in chunks {
        rows.extend(chunk);
    }
    println!("{} road way(s)", rows.len());

    let table = NodeLocations::new(rows.iter().flat_map(|r| r.refs.iter().copied()).collect())?;
    println!("{} node location(s) needed", table.len());
    let table = resolve_nodes(input, blobs, &blob_kinds, "Pass 2: nodes", table)?;

    let mut lines: Vec<LineFeature> = Vec::with_capacity(rows.len());
    let mut outside_bbox = 0usize;
    // `drain` rather than a borrow: at planet scale the rows and the rendered output
    // would otherwise both be resident, and the rows are the larger half.
    for row in rows.drain(..) {
        let coords = table.line(&row.refs);
        if coords.len() < 2 {
            continue;
        }
        if !parts_touch_bbox(std::slice::from_ref(&coords), opts.bbox.as_ref()) {
            outside_bbox += 1;
            continue;
        }
        let f = roads::feature(row.class, &row.tags(), Geometry::LineString(coords), row.id);
        lines.push(LineFeature { sort: ("way", row.id), rendered: render(&f) });
    }

    let written = write_lines(out, lines)?;
    println!("Wrote {} roads feature(s) to {}", written, out.display());
    if outside_bbox > 0 {
        println!("{outside_bbox} feature(s) dropped by --bbox");
    }
    Ok(Stats {
        features: written,
        from_nodes: 0,
        from_ways: written,
        from_relations: 0,
        outside_bbox,
    })
}

fn roads_blob(
    state: &mut Vec<RoadRow>,
    block: &pbf::PrimitiveBlock,
    select: &Select,
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_WAYS, &mut kinds, &mut |el: Element| {
        if let Element::Way(w) = el {
            if w.refs.len() < 2 || w.tags.is_empty() {
                return Ok(());
            }
            if !select.matches(|k| w.tags.get_str(k)) {
                return Ok(());
            }
            let t = RoadTags { highway: w.tags.get_str("highway"), ..Default::default() };
            let Some(class) = roads::classify(&t) else {
                return Ok(());
            };
            let own = |k: &str| w.tags.get_str(k).map(str::to_string);
            state.push(RoadRow {
                id: w.id,
                refs: w.refs.to_vec(),
                class,
                maxspeed: own("maxspeed"),
                lanes: own("lanes"),
                lanes_forward: own("lanes:forward"),
                lanes_backward: own("lanes:backward"),
                turn_lanes: own("turn:lanes"),
                turn_lanes_forward: own("turn:lanes:forward"),
                turn_lanes_backward: own("turn:lanes:backward"),
                oneway: own("oneway"),
                width: own("width"),
                bridge: own("bridge"),
                tunnel: own("tunnel"),
                layer: own("layer"),
            });
        }
        Ok(())
    })?;
    Ok(kinds)
}

// --- transit_lines --------------------------------------------------------

/// One classified way or relation, with its tag values copied out of the block.
struct TransitRow {
    /// `"way"` or `"relation"`, for the `osm_id` and for the sort.
    element: &'static str,
    id: i64,
    kind: &'static str,
    name: Option<String>,
    reference: Option<String>,
    colour: Option<String>,
    /// A way has one part. A relation has one per member way, in member order.
    /// Empty until the way pass fills a relation's in.
    parts: Vec<Vec<i64>>,
    /// A relation's member way ids, in member order.
    member_ways: Vec<i64>,
}

#[derive(Default)]
struct TransitWayPass {
    rows: Vec<TransitRow>,
    /// `(way id, its refs)` for ways a relation claimed. A per-chunk assoc list
    /// rather than a map: cheap to push, and the merge is single-threaded anyway.
    wanted: Vec<(i64, Vec<i64>)>,
}

fn build_transit_lines(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    out: &Path,
    opts: &Options,
) -> Result<Stats> {
    let way_select = Select::parse(&transit_lines::WAY_FILTERS)?;
    let rel_select = Select::parse(&transit_lines::RELATION_FILTERS)?;

    // Pass 1: relations, which decide which ways matter.
    let (chunks, blob_kinds) = pbf::run_pass(
        input,
        blobs,
        None,
        KIND_RELATIONS,
        "Pass 1: relations",
        Vec::<TransitRow>::new,
        |state: &mut Vec<TransitRow>, block| transit_relation_blob(state, block, &rel_select),
    )?;
    let mut rel_rows: Vec<TransitRow> = Vec::new();
    for chunk in chunks {
        rel_rows.extend(chunk);
    }
    let mut wanted_ways: Vec<i64> = rel_rows
        .iter()
        .flat_map(|r| r.member_ways.iter().copied())
        .collect();
    wanted_ways.sort_unstable();
    wanted_ways.dedup();
    println!(
        "{} route relation(s), {} member way(s)",
        rel_rows.len(),
        wanted_ways.len()
    );

    // Pass 2: ways, which are both features in their own right and the geometry
    // the relations need.
    let (chunks, _) = pbf::run_pass(
        input,
        blobs,
        Some(&blob_kinds),
        KIND_WAYS,
        "Pass 2: ways",
        TransitWayPass::default,
        |state, block| transit_way_blob(state, block, &way_select, &wanted_ways),
    )?;
    let mut way_rows: Vec<TransitRow> = Vec::new();
    let mut member_refs: HashMap<i64, Vec<i64>> = HashMap::new();
    for chunk in chunks {
        way_rows.extend(chunk.rows);
        member_refs.extend(chunk.wanted);
    }
    println!("{} railway way(s)", way_rows.len());

    // A relation's parts, in member order. Members whose way is missing from the
    // extract are simply absent, which is the same thing GDAL produced.
    for row in &mut rel_rows {
        row.parts = row
            .member_ways
            .iter()
            .filter_map(|w| member_refs.get(w).cloned())
            .collect();
    }

    // Pass 3: node coordinates for everything both earlier passes collected.
    let table = NodeLocations::new(
        way_rows
            .iter()
            .chain(rel_rows.iter())
            .flat_map(|r| r.parts.iter().flat_map(|p| p.iter().copied()))
            .collect(),
    )?;
    println!("{} node location(s) needed", table.len());
    let table = resolve_nodes(input, blobs, &blob_kinds, "Pass 3: nodes", table)?;

    let mut lines: Vec<LineFeature> = Vec::new();
    let mut outside_bbox = 0usize;
    let mut from_ways = 0usize;
    let mut from_relations = 0usize;
    for row in way_rows.iter().chain(rel_rows.iter()) {
        let parts: Vec<Vec<Coord>> = row
            .parts
            .iter()
            .map(|refs| table.line(refs))
            .filter(|p| p.len() >= 2)
            .collect();
        if parts.is_empty() {
            continue;
        }
        if !parts_touch_bbox(&parts, opts.bbox.as_ref()) {
            outside_bbox += 1;
            continue;
        }
        // A way is one line; a relation is a MultiLineString of its member ways,
        // deliberately unstitched. See the module docs in `transit_lines`.
        let geometry = if row.element == "way" {
            Geometry::LineString(parts.into_iter().next().expect("non-empty"))
        } else {
            Geometry::MultiLineString(parts)
        };
        let t = TransitTags {
            name: row.name.as_deref(),
            reference: row.reference.as_deref(),
            colour: row.colour.as_deref(),
            ..Default::default()
        };
        let f = transit_lines::feature(row.kind, &t, geometry, row.element, row.id);
        lines.push(LineFeature {
            sort: (row.element, row.id),
            rendered: render(&f),
        });
        if row.element == "way" {
            from_ways += 1;
        } else {
            from_relations += 1;
        }
    }

    let written = write_lines(out, lines)?;
    println!(
        "Wrote {written} transit_lines feature(s) to {} ({from_ways} way, {from_relations} relation)",
        out.display()
    );
    if outside_bbox > 0 {
        println!("{outside_bbox} feature(s) dropped by --bbox");
    }
    Ok(Stats {
        features: written,
        from_nodes: 0,
        from_ways,
        from_relations,
        outside_bbox,
    })
}

fn transit_relation_blob(
    state: &mut Vec<TransitRow>,
    block: &pbf::PrimitiveBlock,
    select: &Select,
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_RELATIONS, &mut kinds, &mut |el: Element| {
        if let Element::Relation(r) = el {
            if !select.matches(|k| r.tags.get_str(k)) {
                return Ok(());
            }
            let t = TransitTags {
                railway: r.tags.get_str("railway"),
                type_: r.tags.get_str("type"),
                route: r.tags.get_str("route"),
                name: r.tags.get_str("name"),
                reference: r.tags.get_str("ref"),
                colour: r.tags.get_str("colour"),
                color: r.tags.get_str("color"),
            };
            let Some(kind) = transit_lines::classify(&t) else {
                return Ok(());
            };
            // Way members that are the route's path. `forward`/`backward` count;
            // `platform*` and `stop*` do not - see [`transit_lines::member_is_path`].
            let member_ways: Vec<i64> = r
                .members
                .iter()
                .filter(|m: &&Member| {
                    m.kind == MEMBER_WAY && transit_lines::member_is_path(m.role)
                })
                .map(|m| m.id)
                .collect();
            if member_ways.is_empty() {
                return Ok(());
            }
            state.push(TransitRow {
                element: "relation",
                id: r.id,
                kind,
                name: t.name.map(str::to_string),
                reference: t.reference.map(str::to_string),
                colour: transit_lines::colour(&t).map(str::to_string),
                parts: Vec::new(),
                member_ways,
            });
        }
        Ok(())
    })?;
    Ok(kinds)
}

fn transit_way_blob(
    state: &mut TransitWayPass,
    block: &pbf::PrimitiveBlock,
    select: &Select,
    wanted: &[i64],
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_WAYS, &mut kinds, &mut |el: Element| {
        if let Element::Way(w) = el {
            if w.refs.len() < 2 {
                return Ok(());
            }
            if wanted.binary_search(&w.id).is_ok() {
                state.wanted.push((w.id, w.refs.to_vec()));
            }
            if w.tags.is_empty() || !select.matches(|k| w.tags.get_str(k)) {
                return Ok(());
            }
            let t = TransitTags {
                railway: w.tags.get_str("railway"),
                type_: w.tags.get_str("type"),
                route: w.tags.get_str("route"),
                name: w.tags.get_str("name"),
                reference: w.tags.get_str("ref"),
                colour: w.tags.get_str("colour"),
                color: w.tags.get_str("color"),
            };
            if let Some(kind) = transit_lines::classify(&t) {
                state.rows.push(TransitRow {
                    element: "way",
                    id: w.id,
                    kind,
                    name: t.name.map(str::to_string),
                    reference: t.reference.map(str::to_string),
                    colour: transit_lines::colour(&t).map(str::to_string),
                    parts: vec![w.refs.to_vec()],
                    member_ways: Vec::new(),
                });
            }
        }
        Ok(())
    })?;
    Ok(kinds)
}

// --- admin_city -----------------------------------------------------------

/// One `admin_level=8` boundary relation, with its member ways and tags copied out.
struct AdminRow {
    id: i64,
    name: Option<String>,
    name_en: Option<String>,
    /// `(way id, is_outer)` in member order.
    members: Vec<(i64, bool)>,
}

#[derive(Default)]
struct AdminWayPass {
    /// `(way id, its refs)` for ways a boundary relation claimed.
    wanted: Vec<(i64, Vec<i64>)>,
}

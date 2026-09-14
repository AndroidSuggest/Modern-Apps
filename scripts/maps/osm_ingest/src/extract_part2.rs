fn build_admin_city(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    out: &Path,
    opts: &Options,
) -> Result<Stats> {
    let select = Select::parse(&admin::FILTERS)?;

    // Pass 1: relations, which decide which ways matter.
    let (chunks, blob_kinds) = pbf::run_pass(
        input,
        blobs,
        None,
        KIND_RELATIONS,
        "Pass 1: relations",
        Vec::<AdminRow>::new,
        |state: &mut Vec<AdminRow>, block| admin_relation_blob(state, block, &select),
    )?;
    let mut rows: Vec<AdminRow> = Vec::new();
    for chunk in chunks {
        rows.extend(chunk);
    }
    let mut wanted_ways: Vec<i64> = rows.iter().flat_map(|r| r.members.iter().map(|m| m.0)).collect();
    wanted_ways.sort_unstable();
    wanted_ways.dedup();
    println!(
        "{} admin_level=8 relation(s), {} member way(s)",
        rows.len(),
        wanted_ways.len()
    );

    // Pass 2: the member ways' node refs, IN ORDER. Nothing sorts or dedups them:
    // vertex order is the geometry. See the `rings` module docs.
    let (chunks, _) = pbf::run_pass(
        input,
        blobs,
        Some(&blob_kinds),
        KIND_WAYS,
        "Pass 2: ways",
        AdminWayPass::default,
        |state, block| admin_way_blob(state, block, &wanted_ways),
    )?;
    let mut member_refs: HashMap<i64, Vec<i64>> = HashMap::new();
    for chunk in chunks {
        member_refs.extend(chunk.wanted);
    }

    // Pass 3: node coordinates.
    let table = NodeLocations::new(
        member_refs.values().flat_map(|r| r.iter().copied()).collect(),
    )?;
    println!("{} node location(s) needed", table.len());
    let table = resolve_nodes(input, blobs, &blob_kinds, "Pass 3: nodes", table)?;

    let locate = |id: i64| -> Option<Coord> {
        table
            .get(id)
            .map(|(lat_e7, lon_e7)| (lon_e7 as f64 * 1e-7, lat_e7 as f64 * 1e-7))
    };

    let mut lines: Vec<LineFeature> = Vec::new();
    let mut stats = RingStats::default();
    let mut outside_bbox = 0usize;
    let mut no_geometry = 0usize;
    for row in &rows {
        let members: Vec<MemberWay> = row
            .members
            .iter()
            .filter_map(|(way, outer)| {
                member_refs.get(way).map(|refs| MemberWay {
                    refs: refs.clone(),
                    outer: *outer,
                })
            })
            .collect();
        let polygons = rings::assemble(&members, locate, &mut stats);
        if polygons.is_empty() {
            no_geometry += 1;
            continue;
        }
        if !parts_touch_bbox(
            &polygons.iter().flatten().cloned().collect::<Vec<_>>(),
            opts.bbox.as_ref(),
        ) {
            outside_bbox += 1;
            continue;
        }
        // A relation with one ring is a Polygon; several are a MultiPolygon. Both
        // are what `keep_geometry` accepted, and the tiler takes either.
        let geometry = if polygons.len() == 1 {
            Geometry::Polygon(polygons.into_iter().next().expect("non-empty"))
        } else {
            Geometry::MultiPolygon(polygons)
        };
        let t = AdminTags {
            name: row.name.as_deref(),
            name_en_tag: row.name_en.as_deref(),
            ..Default::default()
        };
        let Some(f) = admin::feature(&t, geometry, row.id) else {
            continue;
        };
        lines.push(LineFeature {
            sort: ("relation", row.id),
            rendered: render(&f),
        });
    }

    let written = write_lines(out, lines)?;
    println!(
        "Wrote {written} admin_city feature(s) to {} ({} outer, {} inner ring(s))",
        out.display(),
        stats.outer_rings,
        stats.inner_rings
    );
    // Loud, because an unclosed ring is a data problem the operator can act on --
    // usually a member missing from the extract.
    if stats.unclosed > 0 || stats.orphan_inner > 0 || no_geometry > 0 {
        println!(
            "{} ring(s) would not close, {} orphan hole(s) dropped, {} relation(s) yielded no geometry",
            stats.unclosed, stats.orphan_inner, no_geometry
        );
    }
    if outside_bbox > 0 {
        println!("{outside_bbox} feature(s) dropped by --bbox");
    }
    Ok(Stats {
        features: written,
        from_nodes: 0,
        from_ways: 0,
        from_relations: written,
        outside_bbox,
    })
}

fn admin_relation_blob(
    state: &mut Vec<AdminRow>,
    block: &pbf::PrimitiveBlock,
    select: &Select,
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_RELATIONS, &mut kinds, &mut |el: Element| {
        if let Element::Relation(r) = el {
            if !select.matches(|k| r.tags.get_str(k)) {
                return Ok(());
            }
            let t = AdminTags {
                boundary: r.tags.get_str("boundary"),
                admin_level: r.tags.get_str("admin_level"),
                name_en_tag: r.tags.get_str("name:en"),
                name: r.tags.get_str("name"),
                type_: r.tags.get_str("type"),
            };
            if !admin::is_city(&t) {
                return Ok(());
            }
            // An empty role means outer, per the OSM boundary convention: most real
            // members are unroled, and treating them as holes would leave nearly
            // every boundary with no exterior at all.
            let members: Vec<(i64, bool)> = r
                .members
                .iter()
                .filter(|m: &&Member| m.kind == MEMBER_WAY)
                .filter_map(|m| match m.role {
                    b"outer" | b"" => Some((m.id, true)),
                    b"inner" => Some((m.id, false)),
                    // Anything else (`label`, `admin_centre`, a subarea) is not part
                    // of the edge.
                    _ => None,
                })
                .collect();
            if members.is_empty() {
                return Ok(());
            }
            state.push(AdminRow {
                id: r.id,
                name: t.name.map(str::to_string),
                name_en: t.name_en_tag.map(str::to_string),
                members,
            });
        }
        Ok(())
    })?;
    Ok(kinds)
}

fn admin_way_blob(
    state: &mut AdminWayPass,
    block: &pbf::PrimitiveBlock,
    wanted: &[i64],
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_WAYS, &mut kinds, &mut |el: Element| {
        if let Element::Way(w) = el {
            if w.refs.len() >= 2 && wanted.binary_search(&w.id).is_ok() {
                state.wanted.push((w.id, w.refs.to_vec()));
            }
        }
        Ok(())
    })?;
    Ok(kinds)
}

// --- shared output --------------------------------------------------------

/// A rendered feature plus the key it sorts on.
///
/// Rendering before the sort means the sort moves one `Vec<u8>` per feature rather
/// than a geometry, and the writer then has nothing left to decide.
struct LineFeature {
    sort: (&'static str, i64),
    rendered: Vec<u8>,
}

fn render(f: &Feature) -> Vec<u8> {
    let mut line = Vec::new();
    geojson::render_feature(f, &mut line);
    line
}

/// `true` when there is no bbox, or any vertex of any part is inside it.
///
/// Whole-element, matching `osmium extract`'s default `complete_ways` strategy: a
/// way with one node in the box is kept entire, overspill included. Truncating it
/// at the boundary would invent a vertex that is not in OSM, and the tiler clips
/// properly per tile later anyway.
fn parts_touch_bbox(parts: &[Vec<Coord>], bbox: Option<&BBox>) -> bool {
    let Some(b) = bbox else { return true };
    parts
        .iter()
        .flatten()
        .any(|(lon, lat)| b.contains(*lon, *lat))
}

/// Sort by `(element kind, id)` and write. Deterministic, so two runs of the same
/// input are byte-identical and a diff against the previous build shows only real
/// changes.
fn write_lines(out: &Path, mut lines: Vec<LineFeature>) -> Result<usize> {
    lines.sort_by_key(|l| l.sort);
    let mut writer = BufWriter::new(create(out)?);
    for l in &lines {
        writer.write_all(&l.rendered).map_err(io_err)?;
    }
    writer.flush().map_err(io_err)?;
    Ok(lines.len())
}

fn create(path: &Path) -> Result<File> {
    File::create(path).map_err(|e| Error(format!("cannot write {}: {e}", path.display())))
}

fn io_err(e: std::io::Error) -> Error {
    Error(e.to_string())
}

// --- CLI ------------------------------------------------------------------

pub struct Args {
    pub input: PathBuf,
    pub out: PathBuf,
    pub layer: Layer,
    pub bbox: Option<BBox>,
    /// `None` leaves the pool at whatever `par::threads()` decides.
    pub threads: Option<usize>,
}

/// `osm_extract IN.osm.pbf --layer NAME --out FILE [--bbox BOX] [--threads N]`
pub fn parse_args(args: &[String]) -> std::result::Result<Args, String> {
    let mut input: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut layer: Option<Layer> = None;
    let mut bbox: Option<BBox> = None;
    let mut threads: Option<usize> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            flag @ ("--out" | "--layer" | "--bbox" | "--threads") => {
                i += 1;
                let value = args
                    .get(i)
                    .ok_or_else(|| format!("{flag} needs a value"))?
                    .as_str();
                match flag {
                    "--out" => out = Some(PathBuf::from(value)),
                    "--layer" => layer = Some(Layer::parse(value)?),
                    "--threads" => threads = Some(crate::par::parse_threads(value)?),
                    _ => bbox = Some(BBox::parse(value).map_err(|e| e.0)?),
                }
            }
            a if a.starts_with('-') => return Err(format!("unknown option: {a}")),
            a => {
                if input.is_some() {
                    return Err(format!("unexpected extra argument: {a}"));
                }
                input = Some(PathBuf::from(a));
            }
        }
        i += 1;
    }
    Ok(Args {
        input: input.ok_or_else(|| "missing IN.osm.pbf".to_string())?,
        out: out.ok_or_else(|| "--out is required".to_string())?,
        layer: layer.ok_or_else(|| "--layer is required".to_string())?,
        bbox,
        threads,
    })
}

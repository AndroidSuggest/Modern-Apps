impl Sink {
    pub fn create(path: impl AsRef<Path>) -> Result<Sink> {
        Ok(Sink {
            writer: NormalizedWriter::create(path.as_ref().to_path_buf())
                .map_err(|e| osm_ingest::proto::Error(e.to_string()))?,
            props: vec![(CLASS_KEY.to_string(), Value::Uint(0))],
            chunk_mins: Vec::new(),
            filling: u8::MAX,
            count: 0,
            bbox: None,
        })
    }

    pub fn push(&mut self, class: &Class, geometry: &Geometry) -> Result<()> {
        self.push_named(class, geometry, None, tilecodec::mamaps::body::ID_NONE)
    }

    /// Push a transit line: like [`Sink::push`], plus the route colour that becomes the body's
    /// `transit_color` and the lane inputs that become its `transit_ordinal`, `transit_lanes` and
    /// `transit_taper`. A zero colour is refused, not written as absent — zero means "no colour"
    /// on the wire, and a transit line without one is a caller bug, not a pale line.
    pub fn push_transit(
        &mut self,
        class: &Class,
        geometry: &Geometry,
        color: u32,
        ordinal: u8,
        lanes: u8,
        taper: u8,
    ) -> Result<()> {
        if color == 0 {
            return err("a transit line with no colour".to_string());
        }
        self.props[0].1 = Value::Uint(pack(class)?);
        self.props.truncate(1);
        self.props.push((COLOR_KEY.to_string(), Value::Uint(color as u64)));
        let lane_bits = ((ordinal as u64) << 16) | ((lanes as u64) << 8) | (taper as u64);
        self.props.push((LANE_KEY.to_string(), Value::Uint(lane_bits)));
        self.write_record(class, geometry)
    }

    /// Push a feature with an optional display name (labels: `places` and `poi`) and, for those
    /// same two layers, the tagged OSM id it came from.
    ///
    /// One prop on the wire for the nameless, idless 190 M and up to three for a label: the spill
    /// encodes a per-record prop count, so variable shapes cost nothing. `feature_of` reads them
    /// all back.
    ///
    /// An id on any layer but `places` or `poi` is refused rather than dropped. Those are the only
    /// two the archive's id table carries, and a caller passing one elsewhere has misunderstood
    /// which features have a stable identity — `coalesce` merges lines and areas, so theirs would
    /// be whichever input happened to survive.
    pub fn push_named(
        &mut self,
        class: &Class,
        geometry: &Geometry,
        name: Option<&str>,
        id: u64,
    ) -> Result<()> {
        self.props[0].1 = Value::Uint(pack(class)?);
        self.props.truncate(1);
        if let Some(name) = name.filter(|n| !n.is_empty()) {
            self.props.push((NAME_KEY.to_string(), Value::String(name.to_string())));
        }
        if id != tilecodec::mamaps::body::ID_NONE {
            if !crate::extract::tracks_ids(class) {
                return err(format!(
                    "layer {} carries a feature id, which only places, poi and a boundaries region shape may",
                    class.layer,
                ));
            }
            self.props.push((ID_KEY.to_string(), Value::Uint(id)));
        }
        self.write_record(class, geometry)
    }

    /// Push a road with its carriageway lane count, per-lane turn masks and directional split:
    /// like [`Sink::push`], plus the optional display name (so a road's label survives even when it
    /// carries lane data), the lane count (body `lane_count`), the forward/backward `turn:lanes`
    /// masks (body turn-lane table) and the [`Carriageway`] (body carriageway table). Refused with
    /// no name and all lane data empty — a plain, nameless road goes through [`Sink::push`] and
    /// carries no extra property at all, so the spill pays nothing for it.
    pub fn push_road(
        &mut self,
        class: &Class,
        geometry: &Geometry,
        name: Option<&str>,
        lane_count: u8,
        turn_fwd: &[u16],
        turn_bwd: &[u16],
        carriageway: Carriageway,
    ) -> Result<()> {
        if name.is_none()
            && lane_count == 0
            && turn_fwd.is_empty()
            && turn_bwd.is_empty()
            && carriageway.is_empty()
        {
            return err("a road pushed with no lane data".to_string());
        }
        self.props[0].1 = Value::Uint(pack(class)?);
        self.props.truncate(1);
        if let Some(name) = name.filter(|n| !n.is_empty()) {
            self.props.push((NAME_KEY.to_string(), Value::String(name.to_string())));
        }
        if lane_count > 0 {
            self.props.push((LANE_COUNT_KEY.to_string(), Value::Uint(lane_count as u64)));
        }
        if !turn_fwd.is_empty() {
            self.props
                .push((TURN_FWD_KEY.to_string(), Value::String(osm_ingest::roads::pack_lanes(turn_fwd))));
        }
        if !turn_bwd.is_empty() {
            self.props
                .push((TURN_BWD_KEY.to_string(), Value::String(osm_ingest::roads::pack_lanes(turn_bwd))));
        }
        if !carriageway.is_empty() {
            self.props
                .push((CARRIAGEWAY_KEY.to_string(), Value::Uint(pack_carriageway(carriageway))));
        }
        self.write_record(class, geometry)
    }

    /// Push a building with its S3DB attributes and optional display name. The attributes ride as
    /// three packed `Uint`s ([`BLD_A_KEY`]/[`BLD_B_KEY`]/[`BLD_C_KEY`]), always written — even for
    /// an all-default building — so the tiler can build a side table dense-parallel to the
    /// `buildings` layer. A name is written only when present (a building is rarely named; its
    /// label is usually a `poi`).
    pub fn push_building(
        &mut self,
        class: &Class,
        geometry: &Geometry,
        name: Option<&str>,
        attrs: BuildingAttrs,
    ) -> Result<()> {
        self.props[0].1 = Value::Uint(pack(class)?);
        self.props.truncate(1);
        if let Some(name) = name.filter(|n| !n.is_empty()) {
            self.props.push((NAME_KEY.to_string(), Value::String(name.to_string())));
        }
        let (a, b, c) = pack_building(&attrs);
        self.props.push((BLD_A_KEY.to_string(), Value::Uint(a)));
        self.props.push((BLD_B_KEY.to_string(), Value::Uint(b)));
        self.props.push((BLD_C_KEY.to_string(), Value::Uint(c)));
        self.write_record(class, geometry)
    }

    fn write_record(&mut self, class: &Class, geometry: &Geometry) -> Result<()> {
        self.writer
            .push(geometry, &self.props)
            .map_err(|e| osm_ingest::proto::Error(e.to_string()))?;
        self.filling = self.filling.min(class.min_zoom);
        self.count += 1;
        // Closed on the same boundary `NormalizedWriter` closes its own chunks on, so entry `i` here
        // describes chunk `i` there. Off by one and the tiler skips the wrong features.
        if self.count % NORM_CHUNK_FEATURES == 0 {
            self.chunk_mins.push(self.filling);
            self.filling = u8::MAX;
        }
        self.grow(geometry);
        Ok(())
    }

    /// Extend the bounding box by a geometry, in degrees times 1e7.
    fn grow(&mut self, geometry: &Geometry) {
        let mut visit = |&(lon, lat): &(f64, f64)| {
            let (x, y) = ((lon * 1e7) as i32, (lat * 1e7) as i32);
            match &mut self.bbox {
                None => self.bbox = Some((x, y, x, y)),
                Some(b) => {
                    b.0 = b.0.min(x);
                    b.1 = b.1.min(y);
                    b.2 = b.2.max(x);
                    b.3 = b.3.max(y);
                }
            }
        };
        match geometry {
            Geometry::Points(points) => points.iter().for_each(&mut visit),
            Geometry::Lines(lines) => lines.iter().flatten().for_each(&mut visit),
            Geometry::Polygons(polygons) => {
                polygons.iter().flatten().flatten().for_each(&mut visit)
            }
        }
    }

    pub fn finish(mut self, path: impl Into<PathBuf>) -> Result<Store> {
        let (count, bbox) = (self.count, self.bbox);
        // The last chunk is usually partial and still needs an entry.
        if self.filling != u8::MAX {
            self.chunk_mins.push(self.filling);
        }
        let chunk_mins = std::mem::take(&mut self.chunk_mins);
        let summary = self.writer.finish().map_err(|e| osm_ingest::proto::Error(e.to_string()))?;
        let chunks = summary.chunks;
        // The two indexes must describe the same chunks, or skipping silently drops real features.
        // Cheap to assert and near-impossible to diagnose from the symptom, which would be missing
        // geometry in a handful of tiles at one zoom.
        let expected = chunks.len().saturating_sub(1);
        if chunk_mins.len() != expected {
            return err(format!(
                "the spill has {expected} chunk(s) but {} zoom entr(ies)",
                chunk_mins.len(),
            ));
        }
        Ok(Store {
            path: path.into(),
            count,
            bbox: bbox.unwrap_or((0, 0, 0, 0)),
            chunks,
            chunk_mins,
            conventions: crate::schema::boundaries::Conventions::default(),
        })
    }

    /// The bounding box of everything pushed so far, in degrees.
    ///
    /// `None` until something has been pushed. Used to clip a planet-wide coastline product down to
    /// the area a build actually covers, which is why it is readable mid-stream.
    pub fn bbox_degrees(&self) -> Option<(f64, f64, f64, f64)> {
        let (min_x, min_y, max_x, max_y) = self.bbox?;
        Some((
            min_x as f64 * 1e-7,
            min_y as f64 * 1e-7,
            max_x as f64 * 1e-7,
            max_y as f64 * 1e-7,
        ))
    }
}

/// Features on disk, re-readable in order as many times as the tiler needs.
pub struct Store {
    path: PathBuf,
    /// Byte offset of every 64th record, plus a sentinel holding the file length, so chunk `i` spans
    /// `chunks[i]..chunks[i + 1]` and reads with no knowledge of any other chunk.
    chunks: Vec<u64>,
    /// The shallowest `min_zoom` in each chunk.
    chunk_mins: Vec<u8>,
    count: u64,
    bbox: (i32, i32, i32, i32),
    /// Which marking convention applies where, resolved once from the extract's country relations.
    ///
    /// Not a property of the features and so not in the spill: it is one small grid for the whole
    /// build, read by the tiler once per tile. It rides on the `Store` because that is the one
    /// thing stage A already hands the tiler, and because `--reuse-store` has to reproduce it — a
    /// reused build that silently drew every road right-hand would be the kind of difference
    /// nothing downstream could see.
    conventions: crate::schema::boundaries::Conventions,
}

/// What a spill was built from, so reusing one cannot silently build the wrong archive.
///
/// A store is only valid for the input and layer selection that produced it. Reusing one built from
/// a different `.pbf`, or with `--layers water` when this run wants all ten, would produce an
/// archive that looks fine and is missing most of the world. Cheap to record, impossible to diagnose
/// from the symptom.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Provenance {
    /// Length and modification time of the source `.pbf`. Not a hash: hashing 18 GB to save 18
    /// minutes of stage A would give most of the saving back.
    pub source_len: u64,
    pub source_mtime: u64,
    /// The layer selection, one bit each, in [`crate::schema::Layers`] declaration order.
    /// `u16`: ten layers do not fit a byte.
    pub layers: u16,
    /// Whether a coastline product was folded in, which adds features nothing else would.
    pub coastline: bool,
    /// Whether a GTFS transit-routes export was folded in. The `transit` layer comes from nowhere
    /// else, so a spill built without one holds no transit at all.
    pub transit_routes: bool,
    /// Whether the v6 routing graph was folded in for the `traffic` layer. That layer comes from
    /// nowhere else, so a spill built without a graph holds no traffic segments.
    pub graph: bool,
}

impl Provenance {
    /// Read the source file's identity, or an error naming it.
    pub fn of(
        source: &Path,
        layers: crate::schema::Layers,
        coastline: bool,
        transit_routes: bool,
        graph: bool,
    ) -> Result<Provenance> {
        let meta = std::fs::metadata(source)
            .map_err(|e| osm_ingest::proto::Error(format!("cannot stat {}: {e}", source.display())))?;
        let mtime = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs())
            .unwrap_or(0);
        Ok(Provenance {
            source_len: meta.len(),
            source_mtime: mtime,
            layers: u16::from(layers.earth)
                | u16::from(layers.water) << 1
                | u16::from(layers.buildings) << 2
                | u16::from(layers.roads) << 3
                | u16::from(layers.boundaries) << 4
                | u16::from(layers.landcover) << 5
                | u16::from(layers.landuse) << 6
                | u16::from(layers.places) << 7
                | u16::from(layers.poi) << 8
                | u16::from(layers.transit) << 9
                | u16::from(layers.traffic) << 10
                // Appending a bit needs no `INDEX_VERSION` bump: `Store::open` compares the whole
                // `Provenance`, and a spill written before this layer existed carries a zero here,
                // so a run that wants `junction` sees a mismatch and refuses the reuse by itself.
                | u16::from(layers.junction) << 11,
            coastline,
            transit_routes,
            graph,
        })
    }
}

/// Magic and version of the sidecar index. Bumped whenever the layout below changes, so an index
/// written by an older build is refused rather than misread.
///
/// v8: the archive gains the v8 shared section (`MBSH`) after the tile data,
/// fed by logical rows the tiler keys by stable identity (roads/buildings OSM
/// way id, traffic component id, junctions by geometry hash). A v7 spill was
/// built without those keys, and `--reuse-store` over one would feed a tiler
/// that now interns shared rows with a spill that carries nothing to key them
/// on — so the version gate refuses it outright rather than misbuilding.
///
/// v7: `roads` features carry a directional carriageway split under the `cw` property key, and the
/// index gained the marking-convention grid stage A resolves from the country relations. A v6
/// spill has neither, and `--reuse-store` over one would feed a tiler that now builds a carriageway
/// side table and bakes the tile's marking convention beside it with a spill that carries neither
/// — an archive whose roads all draw as an undivided stroke, which is exactly what v7 exists to
/// stop.
///
/// v6: `buildings` features carry S3DB attributes (height, roof, colours) under the `ba`/`bb`/`bc`
/// property keys, and road/river names now ride on `roads`/`water` line features. A v5 spill has
/// neither, and `--reuse-store` over one would feed a tiler that now builds a building side table
/// and interns line names with a spill that carries none — a silently flatter, unlabelled map.
///
/// v5: a `graph` byte joins `coastline`/`transit_routes`, because the `traffic` layer is sourced
/// from the v6 routing graph -- so a spill built without one is missing that layer, and reusing it
/// would feed a graphless build to a tiler that now expects traffic segments.
///
/// v4: `places` and `poi` features carry a tagged OSM id under the `i` property key. A v3 spill
/// has none, and `--reuse-store` over one would feed idless features to a tiler that now builds
/// an id table from them — producing an archive whose POIs are silently unidentifiable rather
/// than a build that fails.
///
/// v3: `transit_routes` joins `coastline`, because the `transit` layer is now sourced from a GTFS
/// export rather than the `.pbf` -- so a spill built without one is missing a whole layer.
///
/// v2: `layers` widened to `u16` (ten layers) and the spill's packed class widened its layer
/// field to 4 bits, so a v1 spill would misdecode every feature. Refused here, not there.
const INDEX_MAGIC: &[u8; 8] = b"MAMASTOR";
const INDEX_VERSION: u32 = 8;

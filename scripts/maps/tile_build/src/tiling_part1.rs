/// Merge several tilesets into one, unioning each tile's layers.
///
/// This is the `tile-join` replacement. Later inputs win a name collision, so a
/// freshly built layer replaces a stale copy of itself in the base archive. Tiles
/// present in only one input are carried through as-is.
///
/// Both the decode and the re-encode are lossless for geometry, which is what lets
/// the base tileset's lines and polygons survive a merge untouched.
/// Zoom range, bounds, centre and the `vector_layers` list are all derived from every
/// input rather than taken from any one of them. That matters because the header is
/// what a client reads to decide which tiles to even ask for: an overlay-only merge
/// has no base to inherit a world-sized envelope from, so whichever overlay happened
/// to be listed first would otherwise clip every other layer to its own extent — and
/// point a viewer at its own centre.
///
/// A tile only ONE input holds is copied through as the producer's own compressed
/// bytes: no inflate, no deflate, no MVT round trip, and it stays compressed while it
/// waits, which keeps peak memory down.
///
/// How often that applies depends entirely on how much the inputs' coverage overlaps,
/// and it is worth not guessing: on a two-layer overlay join of 323k tiles whose
/// layers cover the same box, only 30% were copied and 70% needed the full round trip.
/// Layers with genuinely disjoint coverage — a sparse camera layer against dense roads
/// — copy far more. [`merge_archives_to`] reports the split, so a given build's ratio
/// is a fact rather than an assumption.
/// Merge several tilesets into one IN MEMORY, returning the archive bytes.
///
/// Kept as the reference implementation and the oracle the streaming
/// [`merge_archives_to`] is pinned against byte for byte. Use that one for anything
/// large: this holds several copies of every tile body and cannot do a planet join.
pub fn merge_archives(inputs: &[&Archive]) -> Result<Vec<u8>> {
    let mut min_zoom = u8::MAX;
    let mut max_zoom = 0u8;
    // (west, south, east, north), in e7 degrees.
    let mut bounds: Option<(i32, i32, i32, i32)> = None;
    // tile_id -> each input's STILL-COMPRESSED tile, in input order, paired with
    // that input's `tile_compression` so the decode below stays faithful to it.
    let mut collected: HashMap<u64, Vec<(Vec<u8>, u8)>> = HashMap::new();
    for a in inputs {
        let h = &a.header;
        min_zoom = min_zoom.min(h.min_zoom);
        max_zoom = max_zoom.max(h.max_zoom);
        bounds = Some(match bounds {
            None => (h.min_lon_e7, h.min_lat_e7, h.max_lon_e7, h.max_lat_e7),
            Some((w, s, e, n)) => (
                w.min(h.min_lon_e7),
                s.min(h.min_lat_e7),
                e.max(h.max_lon_e7),
                n.max(h.max_lat_e7),
            ),
        });
        // Only gzip is pass-through-able, because gzip is what the builder writes.
        // Anything else still has to be decoded, and labelling e.g. zstd bytes as
        // gzip would produce an archive no reader could open.
        for (id, raw) in a.iter_tiles()? {
            collected.entry(id).or_default().push((raw.to_vec(), h.tile_compression));
        }
    }

    let mut b = Builder::new();
    b.min_zoom = if min_zoom == u8::MAX { 0 } else { min_zoom };
    b.max_zoom = max_zoom;
    // Shallowest zoom any input holds, matching what every layer builder sets for
    // itself. Inheriting the first input's would pair a unioned bbox with an
    // unrelated zoom.
    b.center_zoom = b.min_zoom;
    if let Some((w, s, e, n)) = bounds {
        b.min_lon_e7 = w;
        b.min_lat_e7 = s;
        b.max_lon_e7 = e;
        b.max_lat_e7 = n;
        b.center_lon_e7 = midpoint_e7(w, e);
        b.center_lat_e7 = midpoint_e7(s, n);
    }
    if !inputs.is_empty() {
        let metas: Vec<&[u8]> = inputs.iter().map(|a| a.metadata.as_slice()).collect();
        b.metadata = merge_metadata(&metas);
    }

    let mut ids: Vec<u64> = collected.keys().copied().collect();
    ids.sort_unstable();
    for id in ids {
        let bodies = &collected[&id];
        // Sole owner, already in the compression the builder writes: hand the
        // producer's bytes straight through. No inflate, no deflate, and the tile is
        // preserved byte for byte.
        if let [(raw, pmtiles::COMPRESSION_GZIP)] = bodies.as_slice() {
            b.add_tile_raw(id, raw.clone());
            continue;
        }
        let mut decoded: Vec<Vec<u8>> = Vec::with_capacity(bodies.len());
        for (raw, compression) in bodies {
            decoded.push(match *compression {
                pmtiles::COMPRESSION_NONE => raw.clone(),
                _ => crate::gz::decompress(raw)?,
            });
        }
        let merged = if decoded.len() == 1 {
            // One input, but not gzip: re-encoding the MVT is still unnecessary.
            decoded.pop().unwrap_or_default()
        } else {
            merge_tiles(&decoded)?
        };
        b.add_tile_raw(id, crate::gz::compress(&merged));
    }
    b.build()
}

/// Widened to i64 first: two e7 longitudes at opposite edges of the world sum to
/// 3.6e9, which an i32 cannot hold.
fn midpoint_e7(a: i32, b: i32) -> i32 {
    ((a as i64 + b as i64) / 2) as i32
}

/// Graft every input's `vector_layers` entries into the first input's metadata.
///
/// The list is how a reader learns which layers an archive holds, so publishing one
/// input's copy of it would advertise a fraction of the merge. Keeping the first
/// input as the template preserves a base archive's other keys (name, attribution,
/// tilestats) instead of discarding them.
///
/// Hand-rolled because this crate deliberately carries no JSON dependency. Only the
/// one array is interpreted; every other byte is passed through verbatim.
fn merge_metadata(metas: &[&[u8]]) -> Vec<u8> {
    let texts: Vec<String> = metas
        .iter()
        .map(|m| String::from_utf8_lossy(m).into_owned())
        .collect();

    let mut layers: Vec<(String, String)> = Vec::new();
    for text in &texts {
        let Some(span) = vector_layers_span(text) else { continue };
        for obj in array_elements(text, span) {
            let id = object_string_field(&obj, "id");
            // An entry with no string `id` cannot be matched against, so it is kept
            // rather than deduplicated. Folding them all onto one empty key would
            // make several id-less layers collapse into a single advertised one.
            let slot = id
                .as_ref()
                .and_then(|id| layers.iter_mut().find(|(seen, _)| seen == id));
            match slot {
                // Later inputs win, exactly as they do for the tiles themselves.
                Some(slot) => slot.1 = obj,
                None => layers.push((id.unwrap_or_default(), obj)),
            }
        }
    }

    let template = texts.first().map(String::as_str).unwrap_or("");
    let inner: Vec<&str> = layers.iter().map(|(_, o)| o.as_str()).collect();
    splice_vector_layers(template, &inner.join(",")).into_bytes()
}

/// Byte range of the `vector_layers` array, brackets included.
fn vector_layers_span(text: &str) -> Option<(usize, usize)> {
    let b = text.as_bytes();
    let value = vector_layers_value_start(text)?;
    if b.get(value) != Some(&b'[') {
        return None;
    }
    balanced_end(b, value).map(|e| (value, e))
}

/// First byte of whatever `vector_layers` is set to, array or not.
///
/// Walks strings properly rather than scanning for the key, so a `"vector_layers"`
/// mentioned inside some layer's description cannot be mistaken for the real key.
fn vector_layers_value_start(text: &str) -> Option<usize> {
    let b = text.as_bytes();
    let mut i = 0;
    while i < b.len() {
        if b[i] != b'"' {
            i += 1;
            continue;
        }
        let key_end = string_end(b, i)?;
        let is_key = &b[i + 1..key_end] == b"vector_layers";
        i = key_end + 1;
        if !is_key {
            continue;
        }
        let j = skip_ws(b, i);
        if b.get(j) != Some(&b':') {
            continue;
        }
        return Some(skip_ws(b, j + 1));
    }
    None
}

/// The top-level elements of the array at `span`, each as its own raw JSON text.
fn array_elements(text: &str, span: (usize, usize)) -> Vec<String> {
    let b = text.as_bytes();
    let close = span.1 - 1;
    let mut out = Vec::new();
    let mut i = skip_ws(b, span.0 + 1);
    while i < close {
        let stop = match b[i] {
            b'{' | b'[' => match balanced_end(b, i) {
                Some(e) => e,
                None => break,
            },
            b'"' => match string_end(b, i) {
                Some(e) => e + 1,
                None => break,
            },
            _ => {
                let mut k = i;
                while k < close && b[k] != b',' {
                    k += 1;
                }
                k
            }
        };
        let element = text[i..stop].trim();
        if !element.is_empty() {
            out.push(element.to_string());
        }
        i = skip_ws(b, stop);
        if b.get(i) == Some(&b',') {
            i = skip_ws(b, i + 1);
        }
    }
    out
}

/// A JSON object's own string field. Nested values are skipped rather than
/// descended into, so a layer's `fields` map cannot supply the layer's `id`.
fn object_string_field(text: &str, key: &str) -> Option<String> {
    let b = text.as_bytes();
    if b.first() != Some(&b'{') {
        return None;
    }
    let mut i = skip_ws(b, 1);
    while i < b.len() && b[i] != b'}' {
        if b[i] != b'"' {
            return None;
        }
        let key_end = string_end(b, i)?;
        let matched = &b[i + 1..key_end] == key.as_bytes();
        let mut j = skip_ws(b, key_end + 1);
        if b.get(j) != Some(&b':') {
            return None;
        }
        j = skip_ws(b, j + 1);
        if matched {
            return match b.get(j) {
                Some(b'"') => {
                    let value_end = string_end(b, j)?;
                    Some(text[j + 1..value_end].to_string())
                }
                _ => None,
            };
        }
        i = match b.get(j)? {
            b'{' | b'[' => balanced_end(b, j)?,
            b'"' => string_end(b, j)? + 1,
            _ => {
                let mut k = j;
                while k < b.len() && b[k] != b',' && b[k] != b'}' {
                    k += 1;
                }
                k
            }
        };
        i = skip_ws(b, i);
        if b.get(i) == Some(&b',') {
            i = skip_ws(b, i + 1);
        }
    }
    None
}

/// Replace the metadata's `vector_layers` with `inner`, or add one if it had none.
fn splice_vector_layers(template: &str, inner: &str) -> String {
    let array = format!("[{inner}]");
    if let Some((start, end)) = vector_layers_span(template) {
        return format!("{}{}{}", &template[..start], array, &template[end..]);
    }
    // A template that sets `vector_layers` to something that is not an array cannot
    // be spliced, and grafting a second copy of the key would emit duplicate keys.
    // Nor can an unparseable template be preserved. Either way our list is the part
    // that has to be right.
    let t = template.trim();
    if vector_layers_value_start(template).is_some() {
        return format!("{{\"vector_layers\":{array}}}");
    }
    let body = t
        .strip_prefix('{')
        .and_then(|s| s.strip_suffix('}'))
        .map(str::trim)
        .unwrap_or("");
    if body.is_empty() {
        format!("{{\"vector_layers\":{array}}}")
    } else {
        format!("{{\"vector_layers\":{array},{body}}}")
    }
}

/// Index of the quote closing the string that opens at `open`, honouring `\"`.
fn string_end(b: &[u8], open: usize) -> Option<usize> {
    let mut i = open + 1;
    while i < b.len() {
        match b[i] {
            b'\\' => i += 2,
            b'"' => return Some(i),
            _ => i += 1,
        }
    }
    None
}

/// Index just past the bracket or brace matching the one at `open`.
fn balanced_end(b: &[u8], open: usize) -> Option<usize> {
    let mut depth = 0usize;
    let mut i = open;
    while i < b.len() {
        match b[i] {
            b'"' => i = string_end(b, i)?,
            b'[' | b'{' => depth += 1,
            b']' | b'}' => {
                depth -= 1;
                if depth == 0 {
                    return Some(i + 1);
                }
            }
            _ => {}
        }
        i += 1;
    }
    None
}

fn skip_ws(b: &[u8], mut i: usize) -> usize {
    while i < b.len() && b[i].is_ascii_whitespace() {
        i += 1;
    }
    i
}

/// Union the layers of several MVT bodies for the same tile. Later bodies win a
/// layer-name collision.
pub fn merge_tiles(bodies: &[Vec<u8>]) -> Result<Vec<u8>> {
    let mut out = Tile::new();
    for body in bodies {
        let tile = Tile::decode(body)?;
        for layer in tile.layers {
            match out.layers.iter_mut().find(|l| l.name == layer.name) {
                Some(existing) => *existing = layer,
                None => out.layers.push(layer),
            }
        }
    }
    Ok(out.encode())
}

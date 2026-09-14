/// A node's location in lon/lat, which is the order [`rings::assemble`] and GeoJSON both want.
///
/// [`NodeLocations::get`] returns lat/lon, matching the PBF's own field order.
fn locate(table: &NodeLocations, id: i64) -> Option<(f64, f64)> {
    let (lat_e7, lon_e7) = table.get(id)?;
    Some((lon_e7 as f64 * 1e-7, lat_e7 as f64 * 1e-7))
}

/// Is this a label layer (`places`/`poi`)? Labels are points with names: ways mapped as areas
/// are centroided to one, relations likewise, and nodes spill directly.
pub(crate) fn is_label(layer: u8) -> bool {
    use tilecodec::mamaps::dict::{LAYER_PLACES, LAYER_POI};
    layer == LAYER_PLACES || layer == LAYER_POI
}

/// Does this layer have an id side table at all?
///
/// Layer-level, and separate from [`tracks_ids`], because the table is indexed by feature
/// position: every feature in such a layer needs an entry, `ID_NONE` included, or the table stops
/// lining up with the features it describes.
pub(crate) fn layer_tracks_ids(layer: u8) -> bool {
    is_label(layer)
        || layer == tilecodec::mamaps::dict::LAYER_BOUNDARIES
        || layer == tilecodec::mamaps::dict::LAYER_TRAFFIC
}

/// May this feature carry a non-zero id?
///
/// Wider than [`is_label`], and deliberately a separate predicate: `is_label` also means
/// "centroid this to a point", which a region's shape must not be. `boundaries` needs ids for
/// a different reason — a region is stored as one clipped polygon per tile, so without an id
/// there is nothing to say which pieces are the same region, and the mask can only punch out
/// the piece under the finger.
///
/// Keyed on the whole class rather than the layer because `boundaries` holds both kinds of
/// geometry: the region's shape, which is an area and keeps its id, and the border, which is a
/// line and must not. `coalesce` merges adjacent border lines, and the survivor's id would be
/// whichever member happened to come first.
pub(crate) fn tracks_ids(class: &crate::schema::Class) -> bool {
    use tilecodec::mamaps::dict::{LAYER_BOUNDARIES, LAYER_TRAFFIC};
    is_label(class.layer)
        || (class.layer == LAYER_BOUNDARIES && class.area)
        || class.layer == LAYER_TRAFFIC
}

/// The centroid of a coordinate list: the arithmetic mean, or `None` when there is nothing.
///
/// A label anchor, not a geometric centroid — cheap and exactly what a basemap needs. Area
/// weighting would move the point toward the largest lobe; the mean keeps it among the parts.
fn centroid(line: &[(f64, f64)]) -> Option<(f64, f64)> {
    if line.is_empty() {
        return None;
    }
    let (sx, sy) = line.iter().fold((0.0f64, 0.0f64), |(x, y), &(px, py)| (x + px, y + py));
    let n = line.len() as f64;
    Some((sx / n, sy / n))
}

/// A way's coordinates as the geometry its class says it is.
///
/// A closed way is a ring only if the tags call it an area; the same shape is a cul-de-sac
/// otherwise. A ring that does not close is closed here rather than dropped, which is what
/// `osmium`'s own export does — an extract cut through the way is the usual reason.
fn way_geometry(line: &[(f64, f64)], area: bool) -> Option<Geometry> {
    if !area {
        return (line.len() >= 2).then(|| Geometry::Lines(vec![line.to_vec()]));
    }
    // Three distinct points at minimum, plus the closing one.
    if line.len() < 3 {
        return None;
    }
    let mut ring = line.to_vec();
    if ring.first() != ring.last() {
        ring.push(ring[0]);
    }
    if ring.len() < 4 {
        return None;
    }
    Some(Geometry::Polygons(vec![vec![ring]]))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The claim the bitset path rests on: it yields exactly the sequence the vector path's sort and
    /// dedup would, for the same input. Everything downstream -- the id index, the positions the node
    /// pass writes coordinates at -- follows from that one equality.
    #[test]
    fn the_bitset_yields_what_a_sort_and_dedup_would() {
        // Deliberately awkward: duplicates, a run of consecutive ids inside one word, gaps that skip
        // whole words, both ends of the space, and ids past `u32::MAX`.
        let refs: Vec<i64> = vec![
            0, 63, 64, 65, 1, 1, 1, 4_294_967_295, 4_294_967_296, 12_345_678_901, 128, 127, 2, 63,
            12_345_678_901, 500_000, 499_999, 0,
        ];
        let mut want = refs.clone();
        want.sort_unstable();
        want.dedup();

        let max = *refs.iter().max().expect("refs");
        let mut bits = NeededBits::new(max).expect("size the bitset");
        for &id in &refs {
            bits.set(id).expect("set a bit");
        }
        assert_eq!(bits.ids().collect::<Vec<i64>>(), want);
        assert_eq!(bits.len(), want.len(), "the count must match what `from_sorted` is sized with");
    }

    /// The empty and single-id cases, which the word-at-a-time walk makes easy to get wrong.
    #[test]
    fn an_empty_or_single_id_bitset_is_not_a_special_case() {
        let empty = NeededBits::new(0).expect("size");
        assert_eq!(empty.len(), 0);
        assert!(empty.ids().next().is_none());

        let mut one = NeededBits::new(0).expect("size");
        one.set(0).expect("set");
        assert_eq!(one.ids().collect::<Vec<i64>>(), vec![0]);
        assert_eq!(one.len(), 1);

        // The maximum is inclusive, so a bitset sized for it must hold it.
        let mut edge = NeededBits::new(64).expect("size");
        edge.set(64).expect("the maximum is in range");
        assert_eq!(edge.ids().collect::<Vec<i64>>(), vec![64]);
    }

    /// The bound that makes the bitset affordable is a property of OSM's ids, so an input that
    /// breaks it has to say so rather than ask for an allocation the size of one id.
    #[test]
    fn an_id_outside_osms_own_range_is_refused_rather_than_sized_for() {
        assert!(NeededBits::new(MAX_NODE_ID + 1).is_err(), "a wild maximum was sized for");
        assert!(NeededBits::new(i64::MAX).is_err(), "`i64::MAX` was sized for");

        let mut bits = NeededBits::new(100).expect("size");
        assert!(bits.set(-1).is_err(), "a negative ref was accepted");
        assert!(bits.set(101).is_err(), "a ref past the maximum was accepted");
        assert!(bits.set(100).is_ok());
    }

    /// End to end over a real ways spill: the two collectors must agree on the set they hand pass 3.
    #[test]
    fn the_two_ref_collectors_agree_on_the_same_input() {
        let path = std::env::temp_dir().join(format!(
            "mamaps_refcollect_{}_{:?}.ways.tmp",
            std::process::id(),
            std::thread::current().id(),
        ));
        let class = schema::Class::area(
            tilecodec::mamaps::dict::LAYER_WATER,
            schema::kind("lake"),
            0,
        );
        let mut sink = WaySink::create(&path).expect("create the ways spill");
        // Ascending way ids, as pass 1 produces; overlapping refs, as real ways have.
        for way in 0..64i64 {
            let refs: Vec<i64> = (0..8).map(|i| way * 5 + i).collect();
            sink.push(way + 1, &class, &refs, None, 0, &[], &[], Carriageway::default(), None)
                .expect("push");
        }
        let counts = sink.finish().expect("finish");

        let mut members: HashMap<i64, Vec<i64>> = HashMap::new();
        // One member whose refs overlap the spill's, and one that is entirely new.
        members.insert(7, vec![30, 31, 32, 30]);
        members.insert(9, vec![10_000, 9_999, 10_000]);
        let member_refs: usize = members.values().map(|refs| refs.len()).sum();
        let max_ref = members
            .values()
            .flat_map(|refs| refs.iter().copied())
            .fold(counts.max_ref, i64::max);

        let quiet = |_: &str| {};
        let in_memory =
            collect_needed_in_memory(&path, &members, counts.refs as usize + member_refs, &quiet)
                .expect("the in-memory path");
        let by_bitset =
            collect_needed_by_bitset(&path, &members, max_ref, &quiet).expect("the bitset path");

        assert_eq!(
            in_memory.len(),
            by_bitset.len(),
            "the two paths disagree on how many distinct nodes are needed",
        );
        // And the same ids, not merely the same count: both tables must answer for every ref and
        // for nothing between them that was never asked for.
        for id in [0i64, 1, 30, 31, 32, 319, 9_999, 10_000] {
            assert_eq!(
                in_memory.contains(id),
                by_bitset.contains(id),
                "the two paths disagree about node {id}",
            );
        }
        assert!(in_memory.contains(10_000), "a member-only ref is missing");
        assert!(!in_memory.contains(5_000), "an id nothing asked for is present");

        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn an_open_way_is_a_line_and_a_closed_one_an_area_only_if_tagged() {
        let square = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0), (0.0, 0.0)];
        // Tagged an area: a ring.
        match way_geometry(&square, true).expect("area") {
            Geometry::Polygons(polygons) => {
                assert_eq!(polygons.len(), 1);
                assert_eq!(polygons[0][0].len(), 5, "already closed, so nothing is added");
            }
            other => panic!("{other:?}"),
        }
        // The same shape untagged: a loop road, which is a line.
        assert!(matches!(way_geometry(&square, false), Some(Geometry::Lines(_))));
    }

    /// An extract that cuts through a way leaves it unclosed. Closing it is what `osmium export`
    /// does; dropping it would punch a hole in the coastline at every extract boundary.
    #[test]
    fn an_unclosed_area_is_closed_rather_than_dropped() {
        let open = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)];
        match way_geometry(&open, true).expect("closed") {
            Geometry::Polygons(polygons) => {
                let ring = &polygons[0][0];
                assert_eq!(ring.len(), 4);
                assert_eq!(ring.first(), ring.last(), "the ring closes");
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn a_degenerate_way_produces_nothing() {
        assert!(way_geometry(&[], true).is_none());
        assert!(way_geometry(&[(0.0, 0.0)], true).is_none());
        assert!(way_geometry(&[(0.0, 0.0), (1.0, 1.0)], true).is_none(), "two points bound no area");
        assert!(way_geometry(&[(0.0, 0.0)], false).is_none(), "one point is not a line");
        // But two points are a line.
        assert!(way_geometry(&[(0.0, 0.0), (1.0, 1.0)], false).is_some());
    }
}

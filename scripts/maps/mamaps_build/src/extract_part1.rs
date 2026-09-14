/// Read `input` and spill every feature the schema classifies to `spill_path`.
///
/// Features go to disk rather than into a `Vec`, because holding them was 4.9 GB of a measured
/// 10.03 GB California peak and nothing reads them until the tiler does. Classified ways go to a
/// scratch file beside it for the same reason. See [`crate::store`].
pub fn extract(
    input: &Path,
    layers: Layers,
    coastline: Option<&Path>,
    transit_routes: Option<&Path>,
    graph: Option<&Path>,
    spill_path: &Path,
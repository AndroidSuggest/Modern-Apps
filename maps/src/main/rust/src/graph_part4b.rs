impl Graph {

    /// Load the graph from a single-archive `.mamaps` file.
    ///
    /// Maps the one file, parses the tile header and the sidecar directory
    /// (which refuses a corrupt container, a build-id mismatch and any
    /// section the header's counts do not describe), then hands the seven
    /// graph payloads to [`Graph::assemble`]. Optional sections absent from
    /// the directory degrade exactly as absent files do today: no names pool,
    /// topology-inferred lanes, a flat profile.
    pub fn load_archive(path: &str) -> Option<Graph> {
        let region = MmapRegion::map(path)?;
        // The mapping must cover the whole file: ArchiveView::parse checks
        // `bytes.len() == header.file_len`, and a short mapping would refuse
        // a good archive rather than read past it.
        let bytes = unsafe { std::slice::from_raw_parts(region.base(), region.len) };
        let header = Header::parse(bytes).ok()?;
        let view = ArchiveView::parse(bytes, &header).ok()?;
        let section = |kind| view.section(bytes, kind);
        let sections = GraphSections {
            meta: section(ARCHIVE_KIND_GRAPH_META)?,
            nodes: section(ARCHIVE_KIND_GRAPH_NODES)?,
            edges: section(ARCHIVE_KIND_GRAPH_EDGES)?,
            intermediate: section(ARCHIVE_KIND_GRAPH_INTERMEDIATE)?,
            road_names: section(ARCHIVE_KIND_GRAPH_NAMES),
            lanes: section(ARCHIVE_KIND_GRAPH_LANES),
            elevation: section(ARCHIVE_KIND_GRAPH_ELEVATION),
        };
        Graph::assemble(
            sections,
            GraphOwnership {
                nodes: None,
                edges: None,
                intermediate: None,
                road_names: None,
                lanes: None,
                elevation: None,
                archive: Some(region),
                buffers: Vec::new(),
            },
        )
    }
}

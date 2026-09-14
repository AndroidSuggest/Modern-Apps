// `TransitIndex::load_archive`: the single-archive (`MAMA8`) loader.
//
// The transit pack (`<feed>.transit` bytes) lives as section kind 13 of the
// one `.mamaps` file. This maps that one file once and parses the pack
// in place — no copy — via `Backing::Archive`, which owns the whole mapping
// while `base`/`len` name the section. `parse` is untouched: its TRIX
// magic/version check and section-directory fit run on the pack bytes
// exactly as from the pack's own file.
use tilecodec::mamaps::archive::{ARCHIVE_KIND_TRANSIT, ArchiveView};
use tilecodec::mamaps::header::Header;

impl TransitIndex {
    /// Load the transit pack from a single-archive `.mamaps` file.
    ///
    /// Returns `None` when the container is corrupt, the build id disagrees,
    /// the archive carries no transit section, or the pack itself is not a
    /// known TRIX version — the same refusals as the multi-file path, with
    /// the container's own checks first.
    pub fn load_archive(path: &str) -> Option<TransitIndex> {
        let region = MmapRegion::map(path)?;
        // The mapping must cover the whole file: ArchiveView::parse checks
        // `bytes.len() == header.file_len`, and a short mapping would refuse
        // a good archive rather than read past it.
        let bytes = unsafe { std::slice::from_raw_parts(region.base(), region.len) };
        let header = Header::parse(bytes).ok()?;
        let view = ArchiveView::parse(bytes, &header).ok()?;
        let (offset, len) = view.location(ARCHIVE_KIND_TRANSIT)?;
        TransitIndex::parse(Backing::Archive {
            region,
            offset: offset as usize,
            len: len as usize,
        })
    }
}

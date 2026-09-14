/// As [`interpret_content`], but seeds the initial device-space clip extent.
/// Only the page-level caller has a meaningful starting clip region (the page
/// box); nested streams start with none.
#[allow(clippy::too_many_arguments)]
pub(crate) fn interpret_content_seeded(
    doc: &Document,
    ops: &[lopdf::content::Operation],
    resources: Option<&lopdf::Dictionary>,
    init: GraphicsState,
    prims: &mut Vec<Prim>,
    depth: u32,
    text_only: bool,
    init_clip_bbox: Option<[f64; 4]>,
/// Paint a Widget's field value, building the appearance from `/V` and `/DA`
/// the way §12.7.2 Table 218 requires of a consumer when `/NeedAppearances` is
/// set or no `/AP` was supplied. Returns whether anything was drawn.
///
/// The geometry deliberately mirrors `forms::set_text_field`: the same content
/// builder, `/BBox` and `/Matrix`, played through `appearance_matrix` exactly as
/// the baked stream would be, so synthesizing at render time and baking at edit
/// time put the value in the same place.
fn render_widget_value(
    doc: &Document,
    dict: &lopdf::Dictionary,
    rect: [f64; 4],
    base: &Mat,
    prims: &mut Vec<Prim>,
) -> bool {
    let (content, res, bbox, apm) = match crate::forms::widget_value_appearance(doc, dict, rect) {
        Some(v) => v,
        None => return false,
    };
    let ops = crate::content::parse_operations_lenient(&content);
    if ops.is_empty() {
        return false;
    }
    let ctm = mat_mul(&appearance_matrix(rect, bbox, apm), base);
    // §8.10.2's /BBox clip, for the same reason a real appearance gets one: a
    // value longer than its field must stop at the field's edge rather than run
    // out across the page.
    let dev: Vec<(f64, f64)> = [
        (bbox[0], bbox[1]),
        (bbox[2], bbox[1]),
        (bbox[2], bbox[3]),
        (bbox[0], bbox[3]),
    ]
    .iter()
    .map(|&(x, y)| transform(&ctm, x, y))
    .collect();
    let pts: Vec<(f32, f32)> = dev.iter().map(|&(x, y)| (x as f32, y as f32)).collect();
    let mut path_ops = vec![PathOp::Move(pts[0].0, pts[0].1)];
    path_ops.extend(pts[1..].iter().map(|&(x, y)| PathOp::Line(x, y)));
    path_ops.push(PathOp::Close);
    prims.push(Prim::ClipPush { even_odd: false, pts, path_ops: Some(path_ops) });
    let gs = GraphicsState { ctm, ..Default::default() };
    interpret_content_seeded(doc, &ops, Some(&res), gs, prims, 1, false, None);
    prims.push(Prim::ClipPop);
    true
}

/// Emit a single line of substitute-font text at a device-space baseline (used
/// for synthesized FreeText appearances).
fn emit_annot_text(prims: &mut Vec<Prim>, x: f32, y: f32, size: f32, argb: u32, text: &str) {
    prims.push(Prim::Text {
        x, y, size, argb,
        text: text.to_string(),
        stroke_argb: None,
        stroke_width: None,
        advance: size * 0.5,
        render_mode: 0,
        blend: BlendMode::Normal,
        is_bold: false,
        is_italic: false,
        font_family: 0,
        outline: false,
        h_scale: 1.0,
    });
}

/// Paint one `/LE` line ending (§12.5.6.7 Table 176) at page-space point `tip`,
/// where `dir` is the unit vector pointing along the line TOWARDS `tip` so that
/// arrowheads point outward. `size` is the ending's page-space extent and
/// `interior` is `/IC`, which §12.5.6.7 defines as the fill for line endings.
/// `/None` and any unrecognised name draw nothing.
#[allow(clippy::too_many_arguments)]
fn emit_line_ending(
    prims: &mut Vec<Prim>,
    base: &Mat,
    tip: (f64, f64),
    dir: (f64, f64),
    style: &[u8],
    size: f64,
    sgs: &GraphicsState,
    interior: Option<u32>,
    ca: f64,
) {
    let n = (-dir.1, dir.0);
    let mut closed: Option<Vec<(f64, f64)>> = None;
    let mut open: Vec<(f64, f64)> = Vec::new();
    match style {
        b"OpenArrow" | b"ROpenArrow" | b"ClosedArrow" | b"RClosedArrow" => {
            // Reversed forms point back along the line instead of outward.
            let s = if style == b"ROpenArrow" || style == b"RClosedArrow" { -size } else { size };
            let hw = size * 0.577; // 30-degree half-angle
            let a = (tip.0 - dir.0 * s + n.0 * hw, tip.1 - dir.1 * s + n.1 * hw);
            let b = (tip.0 - dir.0 * s - n.0 * hw, tip.1 - dir.1 * s - n.1 * hw);
            if style == b"OpenArrow" || style == b"ROpenArrow" {
                open = vec![a, tip, b];
            } else {
                closed = Some(vec![tip, a, b]);
            }
        }
        b"Square" => {
            let h = size * 0.5;
            closed = Some(vec![
                (tip.0 - dir.0 * h - n.0 * h, tip.1 - dir.1 * h - n.1 * h),
                (tip.0 + dir.0 * h - n.0 * h, tip.1 + dir.1 * h - n.1 * h),
                (tip.0 + dir.0 * h + n.0 * h, tip.1 + dir.1 * h + n.1 * h),
                (tip.0 - dir.0 * h + n.0 * h, tip.1 - dir.1 * h + n.1 * h),
            ]);
        }
        b"Diamond" => {
            let h = size * 0.6;
            closed = Some(vec![
                (tip.0 - dir.0 * h, tip.1 - dir.1 * h),
                (tip.0 - n.0 * h, tip.1 - n.1 * h),
                (tip.0 + dir.0 * h, tip.1 + dir.1 * h),
                (tip.0 + n.0 * h, tip.1 + n.1 * h),
            ]);
        }
        b"Circle" => {
            let r = size * 0.5;
            closed = Some(
                (0..24)
                    .map(|i| {
                        let t = i as f64 / 24.0 * std::f64::consts::TAU;
                        (tip.0 + r * t.cos(), tip.1 + r * t.sin())
                    })
                    .collect(),
            );
        }
        b"Butt" => {
            let h = size * 0.5;
            open = vec![
                (tip.0 - n.0 * h, tip.1 - n.1 * h),
                (tip.0 + n.0 * h, tip.1 + n.1 * h),
            ];
        }
        b"Slash" => {
            // A short line at 60 degrees counter-clockwise from the line itself.
            let (c, s) = (std::f64::consts::FRAC_PI_3.cos(), std::f64::consts::FRAC_PI_3.sin());
            let u = (dir.0 * c - dir.1 * s, dir.0 * s + dir.1 * c);
            let h = size * 0.5;
            open = vec![
                (tip.0 - u.0 * h, tip.1 - u.1 * h),
                (tip.0 + u.0 * h, tip.1 + u.1 * h),
            ];
        }
        _ => return,
    }
    // The line's dash pattern applies to the line, not to its endings.
    let mut g = sgs.clone();
    g.dash = Vec::new();
    if let Some(shape) = closed {
        let pts: Vec<(f64, f64)> = shape.iter().map(|&(x, y)| transform(base, x, y)).collect();
        if let Some(f) = interior {
            emit_fill(prims, std::slice::from_ref(&pts), apply_alpha_to_argb(f, ca), false, 1.0, BlendMode::Normal);
        }
        let mut ring = pts.clone();
        ring.push(pts[0]);
        emit_stroke(prims, std::slice::from_ref(&ring), &g);
    }
    if !open.is_empty() {
        let pts: Vec<(f64, f64)> = open.iter().map(|&(x, y)| transform(base, x, y)).collect();
        emit_stroke(prims, std::slice::from_ref(&pts), &g);
    }
}

/// Split a standard stamp `/Name` (§12.5.6.12 Table 181 names them in CamelCase,
/// e.g. `ForPublicRelease`) into the upper-case wording the stamp displays.
fn stamp_label(name: &[u8]) -> String {
    let raw = String::from_utf8_lossy(name);
    let mut out = String::with_capacity(raw.len() + 4);
    let mut prev_lower = false;
    for c in raw.chars() {
        if c.is_ascii_uppercase() && prev_lower {
            out.push(' ');
        }
        prev_lower = c.is_ascii_lowercase() || c.is_ascii_digit();
        out.push(c.to_ascii_uppercase());
    }
    out
}

// ---------------------------------------------------------------------------
// Editing: annotations, form filling, and save (lopdf write-back)
// ---------------------------------------------------------------------------
//
// The "safe" viewer edits via an overlay model written back through lopdf: new
// content is added as annotations (with generated appearance streams so other
// viewers render them) or as AcroForm field values. Existing body-text glyph
// runs are not editable in this architecture.

/// Encode a lopdf `ObjectId` (num, gen) into a single `i64` handle for Kotlin.
pub(crate) fn encode_id(id: ObjectId) -> i64 {
    ((id.0 as i64) << 16) | (id.1 as i64)
}

pub(crate) fn decode_id(v: i64) -> ObjectId {
    (((v >> 16) & 0xFFFF_FFFF) as u32, (v & 0xFFFF) as u16)
}

/// Object id of the `index`-th page (0-based), or `None` when out of range.
///
/// `index` arrives unvalidated from the JNI boundary as a `jint`, so it may be negative.
/// `(index as u32) + 1` overflowed for `-1`: a debug panic, and in release a silent wrap
/// to 0, which page keys being 1-based made accidentally harmless. Reject negatives up
/// front instead of relying on that.
pub(crate) fn nth_page_id(doc: &Document, index: i32) -> Option<ObjectId> {
    let page_number = u32::try_from(index).ok()?.checked_add(1)?;
    doc.get_pages().get(&page_number).copied()
}

pub(crate) fn name_obj(s: &str) -> Object {
    Object::Name(s.as_bytes().to_vec())
}

pub(crate) fn rect_obj(r: [f64; 4]) -> Object {
    Object::Array(vec![r[0].into(), r[1].into(), r[2].into(), r[3].into()])
}

pub(crate) fn argb_rgb(argb: u32) -> (f64, f64, f64) {
    (
        ((argb >> 16) & 0xFF) as f64 / 255.0,
        ((argb >> 8) & 0xFF) as f64 / 255.0,
        (argb & 0xFF) as f64 / 255.0,
    )
}

pub(crate) fn normalize_rect(r: [f64; 4]) -> [f64; 4] {
    [
        r[0].min(r[2]),
        r[1].min(r[3]),
        r[0].max(r[2]),
        r[1].max(r[3]),
    ]
}

/// Escape a string for PDF literal — full escapes per spec §7.3.4.2: \n \r \t \b \f ( ) \
/// Previous only escaped ( ) \ causing invalid streams for FreeText with newlines.
pub(crate) fn escape_pdf_literal(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '(' => out.push_str("\\("),
            ')' => out.push_str("\\)"),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{0008}' => out.push_str("\\b"),
            '\u{000C}' => out.push_str("\\f"),
            _ => out.push(c),
        }
    }
    out
}

/// Decode PDF text string: handles BE BOM FE FF and LE BOM FF FE, else Latin-1/PDFDoc approximation.
/// Fix: previously only BE, not LE.
pub(crate) fn decode_pdf_text(bytes: &[u8]) -> String {
    if bytes.len() >= 2 && bytes[0] == 0xFE && bytes[1] == 0xFF {
        let units: Vec<u16> = bytes[2..].chunks(2).map(|c| ((c[0] as u16) << 8) | *c.get(1).unwrap_or(&0) as u16).collect();
        String::from_utf16_lossy(&units)
    } else if bytes.len() >= 2 && bytes[0] == 0xFF && bytes[1] == 0xFE {
        let units: Vec<u16> = bytes[2..].chunks(2).map(|c| (c[0] as u16) | ((*c.get(1).unwrap_or(&0) as u16) << 8)).collect();
        String::from_utf16_lossy(&units)
    } else {
        // Not a UTF-16 string, so treat the bytes as Latin-1. PDFDocEncoding
        // (§7.9.2.2) differs from Latin-1 only in 0x18-0x1F and 0x80-0x9F, so
        // this is an approximation: a true PDFDocEncoding table would map those
        // ranges, and WinAnsiEncoding would map 0x80-0x9F differently again.
        bytes.iter().map(|&b| b as char).collect()
    }
}

/// Resources with Helvetica /F1 plus WinAnsiEncoding for non-ASCII, per P0 #7
pub(crate) fn helvetica_resources() -> Dictionary {
    let mut font = Dictionary::new();
    font.set("Type", name_obj("Font"));
    font.set("Subtype", name_obj("Type1"));
    font.set("BaseFont", name_obj("Helvetica"));
    font.set("Encoding", name_obj("WinAnsiEncoding"));
    let mut fonts = Dictionary::new();
    fonts.set("F1", Object::Dictionary(font));
    let mut res = Dictionary::new();
    res.set("Font", Object::Dictionary(fonts));
    res
}

/// Display-orientation size of a raw-page rect, plus the appearance `/Matrix`
/// that maps a form drawn in that orientation back into raw page space.
///
/// Text-bearing appearances (FreeText, callouts, underlines, field values) must
/// be laid out the way the reader sees them, but `/Rect` is in raw page space, so
/// on a rotated page the two orientations differ and content comes out sideways.
/// `appearance_matrix` (§12.5.5) already supplies translation and scale by
/// fitting the transformed BBox onto `/Rect`, so `/Matrix` only has to carry the
/// rotation — it is the inverse of the page base matrix's linear part.
///
/// Purely geometric appearances (Square/Circle/Ink/Polygon/Highlight) are defined
/// by their own coordinates and correctly rotate with the page, so they must NOT
/// use this. At `/Rotate 0` this is a strict no-op.
pub(crate) fn display_orientation(rotation: i64, w: f64, h: f64) -> (f64, f64, Mat) {
    match rotation {
        90 => (h, w, [0.0, 1.0, -1.0, 0.0, 0.0, 0.0]),
        180 => (w, h, [-1.0, 0.0, 0.0, -1.0, 0.0, 0.0]),
        270 => (h, w, [0.0, -1.0, 1.0, 0.0, 0.0, 0.0]),
        _ => (w, h, IDENTITY),
    }
}

/// `display_orientation` for the page at `page_index`.
pub(crate) fn page_display_orientation(doc: &Document, page_index: i32, w: f64, h: f64) -> (f64, f64, Mat) {
    let rot = nth_page_id(doc, page_index)
        .map(|pid| page_rotation(doc, pid))
        .unwrap_or(0);
    display_orientation(rot, w, h)
}

/// Build a Form XObject appearance stream with the given BBox size, content and
/// resources, returning its object id.
pub(crate) fn make_appearance(doc: &mut Document, w: f64, h: f64, content: Vec<u8>, res: Dictionary) -> ObjectId {
    make_appearance_oriented(doc, w, h, content, res, IDENTITY)
}

/// `make_appearance` plus a `/Matrix`, for appearances that must stay upright on
/// a rotated page (see `display_orientation`).
pub(crate) fn make_appearance_oriented(
    doc: &mut Document,
    w: f64,
    h: f64,
    content: Vec<u8>,
    res: Dictionary,
    matrix: Mat,
) -> ObjectId {
    let mut d = Dictionary::new();
    d.set("Type", name_obj("XObject"));
    d.set("Subtype", name_obj("Form"));
    d.set("FormType", 1);
    d.set(
        "BBox",
        Object::Array(vec![0.into(), 0.into(), w.into(), h.into()]),
    );
    if matrix != IDENTITY {
        d.set(
            "Matrix",
            Object::Array(matrix.iter().map(|v| Object::Real(*v as f32)).collect()),
        );
    }
    d.set("Resources", Object::Dictionary(res));
    doc.add_object(Stream::new(d, content))
}

/// Append an annotation reference to a page's `/Annots` array (creating it if
/// needed), handling both inline and indirect arrays.
pub(crate) fn append_annot(doc: &mut Document, page_id: ObjectId, annot_id: ObjectId) {
    let indirect = match doc.get_dictionary(page_id).ok().and_then(|d| d.get(b"Annots").ok()) {
        Some(Object::Reference(id)) => Some(*id),
        _ => None,
    };
    if let Some(arr_id) = indirect {
        if let Ok(Object::Array(a)) = doc.get_object_mut(arr_id) {
            a.push(Object::Reference(annot_id));
        }
        return;
    }
    if let Ok(page) = doc.get_dictionary_mut(page_id) {
        match page.get_mut(b"Annots") {
            Ok(Object::Array(a)) => a.push(Object::Reference(annot_id)),
            _ => page.set("Annots", Object::Array(vec![Object::Reference(annot_id)])),
        }
    }
}

/// Attach `Rect` + `AP /N` to an annotation dict.
pub(crate) fn set_appearance(annot: &mut Dictionary, rect: [f64; 4], ap_id: ObjectId) {
    annot.set("Rect", rect_obj(rect));
    let mut ap = Dictionary::new();
    ap.set("N", Object::Reference(ap_id));
    annot.set("AP", Object::Dictionary(ap));
}

pub(crate) fn add_annotation_object(doc: &mut Document, page_index: i32, annot: Dictionary) -> Option<i64> {
    let page_id = nth_page_id(doc, page_index)?;
    let annot_id = doc.add_object(annot);
    append_annot(doc, page_id, annot_id);
    Some(encode_id(annot_id))
}

/// Content stream drawing a (possibly multi-line) text block in a `w`×`h` box.
pub(crate) fn free_text_content(w: f64, h: f64, text: &str, argb: u32, size: f64) -> Vec<u8> {
    let (r, g, b) = argb_rgb(argb);
    let leading = size * 1.2;
    let mut c = format!(
        "q {r:.3} {g:.3} {b:.3} rg BT /F1 {size} Tf {leading} TL {x} {y} Td",
        x = 2.0,
        y = h - size,
    );
    let _ = w;
    for line in text.split('\n') {
        c.push_str(&format!(" ({}) Tj T*", escape_pdf_literal(line)));
    }
    c.push_str(" ET Q");
    c.into_bytes()
}

pub(crate) fn add_free_text(
    handle: i64,
    page_index: i32,
    rect: [f64; 4],
    argb: u32,
    size: f64,
    text: &str,
) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    // Text must read upright regardless of /Rotate.
    let (w, h, apm) = page_display_orientation(doc, page_index, r[2] - r[0], r[3] - r[1]);
    let content = free_text_content(w, h, text, argb, size);
    let ap_id = make_appearance_oriented(doc, w, h, content, helvetica_resources(), apm);
    let (cr, cg, cb) = argb_rgb(argb);

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("FreeText"));
    annot.set("Contents", Object::string_literal(text));
    annot.set(
        "DA",
        Object::string_literal(format!("{cr:.3} {cg:.3} {cb:.3} rg /F1 {size} Tf")),
    );
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

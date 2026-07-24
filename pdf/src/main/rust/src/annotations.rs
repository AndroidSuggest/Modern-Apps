use crate::*;

/// The matrix mapping an appearance stream's form space to page space, fitting
/// the (Matrix-transformed) `/BBox` into the annotation `/Rect` (PDF 12.5.5).
pub(crate) fn appearance_matrix(rect: [f64; 4], bbox: [f64; 4], matrix: Mat) -> Mat {
    let corners = [
        (bbox[0], bbox[1]),
        (bbox[2], bbox[1]),
        (bbox[2], bbox[3]),
        (bbox[0], bbox[3]),
    ];
    let mut tx0 = f64::INFINITY;
    let mut ty0 = f64::INFINITY;
    let mut tx1 = f64::NEG_INFINITY;
    let mut ty1 = f64::NEG_INFINITY;
    for (x, y) in corners {
        let (px, py) = transform(&matrix, x, y);
        tx0 = tx0.min(px);
        ty0 = ty0.min(py);
        tx1 = tx1.max(px);
        ty1 = ty1.max(py);
    }
    let rx0 = rect[0].min(rect[2]);
    let ry0 = rect[1].min(rect[3]);
    let rx1 = rect[0].max(rect[2]);
    let ry1 = rect[1].max(rect[3]);
    let bw = tx1 - tx0;
    let bh = ty1 - ty0;
    let sx = if bw.abs() > 1e-6 { (rx1 - rx0) / bw } else { 1.0 };
    let sy = if bh.abs() > 1e-6 { (ry1 - ry0) / bh } else { 1.0 };
    let fit = [sx, 0.0, 0.0, sy, rx0 - sx * tx0, ry0 - sy * ty0];
    mat_mul(&matrix, &fit)
}

/// Render each visible page annotation's normal appearance (`/AP /N`) into
/// primitives, mapping the appearance BBox into the annotation Rect, then
/// through `base` (page rotation / origin) into displayed space.
pub(crate) fn render_annotations(doc: &Document, page_id: ObjectId, base: &Mat, prims: &mut Vec<Prim>) {
    let annots = match doc
        .get_dictionary(page_id)
        .ok()
        .and_then(|d| d.get(b"Annots").ok())
        .and_then(|o| deref(doc, o))
    {
        Some(Object::Array(a)) => a.clone(),
        _ => return,
    };

    for a in &annots {
        let dict = match deref(doc, a).and_then(|o| o.as_dict().ok()) {
            Some(d) => d,
            None => continue,
        };
        // Skip Hidden (bit 2) and NoView (bit 6) annotations.
        let flags = dict.get(b"F").ok().and_then(num).unwrap_or(0.0) as i64;
        if flags & 0b10 != 0 || flags & 0b10_0000 != 0 {
            continue;
        }
        render_annotation(doc, dict, base, prims);
    }
}

pub(crate) fn render_annotation(doc: &Document, dict: &lopdf::Dictionary, base: &Mat, prims: &mut Vec<Prim>) {
    let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
        Some(r) => r,
        None => return,
    };

    // Resolve the normal appearance: /AP /N is either a stream or a subdictionary
    // of appearance states selected by /AS.
    let ap = match dict.get(b"AP").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Dictionary(d)) => d,
        _ => return,
    };
    let normal = match ap.get(b"N").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Stream(s)) => s,
        Some(Object::Dictionary(states)) => {
            let as_name = dict.get(b"AS").ok().and_then(|o| o.as_name().ok());
            let picked = as_name
                .and_then(|n| states.get(n).ok())
                .or_else(|| states.iter().next().map(|(_, v)| v));
            match picked.and_then(|o| deref(doc, o)) {
                Some(Object::Stream(s)) => s,
                _ => return,
            }
        }
        _ => return,
    };

    let bbox = normal
        .dict
        .get(b"BBox")
        .ok()
        .and_then(|o| read_rect(doc, o))
        .unwrap_or([0.0, 0.0, 1.0, 1.0]);
    let matrix = normal
        .dict
        .get(b"Matrix")
        .ok()
        .and_then(read_matrix_obj)
        .unwrap_or(IDENTITY);
    let res = normal
        .dict
        .get(b"Resources")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .cloned();

    let bytes = stream_data_with_doc(doc, normal);
    let ops = match Content::decode(&bytes) {
        Ok(c) => c.operations,
        Err(_) => return,
    };

    let mut gs = GraphicsState::default();
    gs.ctm = mat_mul(&appearance_matrix(rect, bbox, matrix), base);
    let start = prims.len();
    interpret_content(doc, &ops, res.as_ref(), gs, prims, 1, false);

    // Honor the annotation's constant opacity (/CA) over its rendered prims.
    let ca = dict.get(b"CA").ok().and_then(num).unwrap_or(1.0);
    if ca < 1.0 {
        for p in prims[start..].iter_mut() {
            scale_prim_alpha(p, ca);
        }
    }
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

pub(crate) fn nth_page_id(doc: &Document, index: i32) -> Option<ObjectId> {
    doc.get_pages().get(&((index as u32) + 1)).copied()
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

/// Escape a string for use inside a PDF content-stream literal `(...)`.
pub(crate) fn escape_pdf_literal(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '(' => out.push_str("\\("),
            ')' => out.push_str("\\)"),
            '\\' => out.push_str("\\\\"),
            _ => out.push(c),
        }
    }
    out
}

/// Decode a PDF text string (`/Contents`, `/V`): UTF-16BE if it has a BOM, else
/// treated as Latin-1 (a superset-safe approximation of PDFDocEncoding).
pub(crate) fn decode_pdf_text(bytes: &[u8]) -> String {
    if bytes.len() >= 2 && bytes[0] == 0xFE && bytes[1] == 0xFF {
        let units: Vec<u16> = bytes[2..]
            .chunks(2)
            .map(|c| ((c[0] as u16) << 8) | *c.get(1).unwrap_or(&0) as u16)
            .collect();
        String::from_utf16_lossy(&units)
    } else {
        bytes.iter().map(|&b| b as char).collect()
    }
}

/// Resources dictionary with a single Helvetica font under `/F1`.
pub(crate) fn helvetica_resources() -> Dictionary {
    let mut font = Dictionary::new();
    font.set("Type", name_obj("Font"));
    font.set("Subtype", name_obj("Type1"));
    font.set("BaseFont", name_obj("Helvetica"));
    let mut fonts = Dictionary::new();
    fonts.set("F1", Object::Dictionary(font));
    let mut res = Dictionary::new();
    res.set("Font", Object::Dictionary(fonts));
    res
}

/// Build a Form XObject appearance stream with the given BBox size, content and
/// resources, returning its object id.
pub(crate) fn make_appearance(doc: &mut Document, w: f64, h: f64, content: Vec<u8>, res: Dictionary) -> ObjectId {
    let mut d = Dictionary::new();
    d.set("Type", name_obj("XObject"));
    d.set("Subtype", name_obj("Form"));
    d.set("FormType", 1);
    d.set(
        "BBox",
        Object::Array(vec![0.into(), 0.into(), w.into(), h.into()]),
    );
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
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let content = free_text_content(w, h, text, argb, size);
    let ap_id = make_appearance(doc, w, h, content, helvetica_resources());
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

pub(crate) fn add_highlight(handle: i64, page_index: i32, rect: [f64; 4], argb: u32) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    // Multiply-blended translucent fill so underlying text shows through.
    let content = format!(
        "q /GS1 gs {cr:.3} {cg:.3} {cb:.3} rg 0 0 {w} {h} re f Q"
    )
    .into_bytes();
    let mut gs = Dictionary::new();
    gs.set("Type", name_obj("ExtGState"));
    gs.set("ca", Object::Real(0.4));
    gs.set("BM", name_obj("Multiply"));
    let mut gss = Dictionary::new();
    gss.set("GS1", Object::Dictionary(gs));
    let mut res = Dictionary::new();
    res.set("ExtGState", Object::Dictionary(gss));
    let ap_id = make_appearance(doc, w, h, content, res);

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Highlight"));
    annot.set(
        "QuadPoints",
        Object::Array(vec![
            r[0].into(), r[3].into(), r[2].into(), r[3].into(),
            r[0].into(), r[1].into(), r[2].into(), r[1].into(),
        ]),
    );
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a text-markup annotation over `rect`. kind: 0 Underline, 1 StrikeOut, 2 Squiggly.
pub(crate) fn add_text_markup(handle: i64, page_index: i32, rect: [f64; 4], argb: u32, kind: i32) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = (h * 0.06).clamp(0.8, 3.0);
    let content = match kind {
        1 => {
            let y = h / 2.0;
            format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG 0 {y:.2} m {w:.2} {y:.2} l S Q")
        }
        2 => {
            let base = h * 0.12;
            let amp = (h * 0.08).clamp(1.0, 4.0);
            let step = (amp * 2.0).max(3.0);
            let mut c = format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG 0 {base:.2} m ");
            let mut x = 0.0;
            let mut up = true;
            while x < w {
                let nx = (x + step).min(w);
                let y = if up { base + amp } else { base };
                c.push_str(&format!("{nx:.2} {y:.2} l "));
                x = nx;
                up = !up;
            }
            c.push_str("S Q");
            c
        }
        _ => {
            let y = h * 0.10;
            format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG 0 {y:.2} m {w:.2} {y:.2} l S Q")
        }
    }
    .into_bytes();
    let ap_id = make_appearance(doc, w, h, content, Dictionary::new());
    let subtype = match kind {
        1 => "StrikeOut",
        2 => "Squiggly",
        _ => "Underline",
    };
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj(subtype));
    annot.set(
        "QuadPoints",
        Object::Array(vec![
            r[0].into(), r[3].into(), r[2].into(), r[3].into(),
            r[0].into(), r[1].into(), r[2].into(), r[1].into(),
        ]),
    );
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a sticky-note (Text) annotation at editor point (x,y) with `text`.
pub(crate) fn add_note(handle: i64, page_index: i32, x: f64, y: f64, argb: u32, text: &str) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let binv = page_base_inverse(doc, page_index);
    let (px, py) = transform(&binv, x, y);
    let s = 20.0;
    let r = normalize_rect([px, py - s, px + s, py]);
    let (cr, cg, cb) = argb_rgb(argb);
    let content = format!(
        "q {cr:.3} {cg:.3} {cb:.3} rg 1 1 {w:.1} {h:.1} re f 1 1 1 rg 4 5 12 2 re f 4 9 12 2 re f 4 13 8 2 re f Q",
        w = s - 2.0,
        h = s - 2.0,
    )
    .into_bytes();
    let ap_id = make_appearance(doc, s, s, content, Dictionary::new());
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Text"));
    annot.set("Name", name_obj("Note"));
    annot.set("Contents", Object::string_literal(text));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a FreeText callout: a leader line from anchor (ax,ay) to a text box near
/// (bx,by), all in editor coordinates.
pub(crate) fn add_callout(
    handle: i64,
    page_index: i32,
    ax: f64,
    ay: f64,
    bx: f64,
    by: f64,
    argb: u32,
    size: f64,
    text: &str,
) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let binv = page_base_inverse(doc, page_index);
    let (pax, pay) = transform(&binv, ax, ay);
    let (pbx, pby) = transform(&binv, bx, by);
    let bw = 160.0;
    let bh = (size * 1.6).max(24.0);
    let (box_x0, box_y1) = (pbx, pby);
    let box_y0 = pby - bh;
    let box_x1 = pbx + bw;
    let minx = pax.min(box_x0);
    let miny = pay.min(box_y0);
    let maxx = pax.max(box_x1);
    let maxy = pay.max(box_y1);
    let r = [minx, miny, maxx, maxy];
    let (w, h) = (maxx - minx, maxy - miny);
    let (cr, cg, cb) = argb_rgb(argb);
    let lax = pax - minx;
    let lay = pay - miny;
    let lx0 = box_x0 - minx;
    let ly0 = box_y0 - miny;
    let lx1 = box_x1 - minx;
    let ly1 = box_y1 - miny;
    let knee_y = (ly0 + ly1) / 2.0;
    let mut c = format!(
        "q 1 w {cr:.3} {cg:.3} {cb:.3} RG {lax:.2} {lay:.2} m {lx0:.2} {knee_y:.2} l S "
    );
    c.push_str(&format!(
        "{lx0:.2} {ly0:.2} {bw2:.2} {bh2:.2} re S ",
        bw2 = lx1 - lx0,
        bh2 = ly1 - ly0,
    ));
    c.push_str(&format!(
        "{cr:.3} {cg:.3} {cb:.3} rg BT /F1 {size} Tf {tx:.2} {ty:.2} Td ({t}) Tj ET Q",
        tx = lx0 + 4.0,
        ty = ly1 - size - 2.0,
        t = escape_pdf_literal(text),
    ));
    let ap_id = make_appearance(doc, w, h, c.into_bytes(), helvetica_resources());
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("FreeText"));
    annot.set("IT", name_obj("FreeTextCallout"));
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

/// Add a redaction annotation: an opaque black filled rectangle marked so that
/// `apply_redactions` can permanently remove the content beneath it.
pub(crate) fn add_redaction(handle: i64, page_index: i32, rect: [f64; 4]) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let content = format!("q 0 0 0 rg 0 0 {w} {h} re f Q").into_bytes();
    let ap_id = make_appearance(doc, w, h, content, Dictionary::new());
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Square"));
    annot.set("IC", Object::Array(vec![0.into(), 0.into(), 0.into()]));
    annot.set("PdfRedact", Object::Boolean(true));
    let mut bs = Dictionary::new();
    bs.set("W", Object::Real(0.0));
    annot.set("BS", Object::Dictionary(bs));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

pub(crate) fn add_square(
    handle: i64,
    page_index: i32,
    rect: [f64; 4],
    argb: u32,
    line_width: f64,
    fill: bool,
) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = line_width.max(0.5);
    let content = if fill {
        format!("q {cr:.3} {cg:.3} {cb:.3} rg 0 0 {w} {h} re f Q")
    } else {
        format!(
            "q {lw} w {cr:.3} {cg:.3} {cb:.3} RG {x} {y} {rw} {rh} re S Q",
            x = lw / 2.0,
            y = lw / 2.0,
            rw = w - lw,
            rh = h - lw,
        )
    }
    .into_bytes();
    let ap_id = make_appearance(doc, w, h, content, Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Square"));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_shape_border(&mut annot, argb, lw, fill);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a Circle (ellipse) annotation inscribed in [rect], stroked or filled.
pub(crate) fn add_circle(
    handle: i64,
    page_index: i32,
    rect: [f64; 4],
    argb: u32,
    line_width: f64,
    fill: bool,
) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = line_width.max(0.5);

    // Ellipse inscribed in the BBox
    // approximated by four cubic Bézier arcs.
    let inset = if fill { 0.0 } else { lw / 2.0 };
    let cx = w / 2.0;
    let cy = h / 2.0;
    let rx = (w / 2.0 - inset).max(0.0);
    let ry = (h / 2.0 - inset).max(0.0);
    let k = 0.552_284_75_f64; // 4/3 * (sqrt(2) - 1)
    let ox = rx * k;
    let oy = ry * k;

    let mut c = String::from("q ");
    if fill {
        c.push_str(&format!("{cr:.3} {cg:.3} {cb:.3} rg "));
    } else {
        c.push_str(&format!("{lw} w {cr:.3} {cg:.3} {cb:.3} RG "));
    }
    c.push_str(&format!("{:.2} {:.2} m ", cx + rx, cy));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx + rx, cy + oy, cx + ox, cy + ry, cx, cy + ry,
    ));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx - ox, cy + ry, cx - rx, cy + oy, cx - rx, cy,
    ));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx - rx, cy - oy, cx - ox, cy - ry, cx, cy - ry,
    ));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx + ox, cy - ry, cx + rx, cy - oy, cx + rx, cy,
    ));
    c.push_str(if fill { "f Q" } else { "S Q" });
    let ap_id = make_appearance(doc, w, h, c.into_bytes(), Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Circle"));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_shape_border(&mut annot, argb, lw, fill);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Set annotation constant opacity (`/CA`, `/ca`) from the alpha byte of `argb`.
pub(crate) fn set_alpha(annot: &mut Dictionary, argb: u32) {
    let a = ((argb >> 24) & 0xFF) as f64 / 255.0;
    if a < 1.0 {
        annot.set("CA", Object::Real(a as f32));
        annot.set("ca", Object::Real(a as f32));
    }
}

/// Set `/BS` (border) and, for filled shapes, `/IC` (interior color) on a
/// Square/Circle annotation. Filled shapes carry a zero-width border.
pub(crate) fn set_shape_border(annot: &mut Dictionary, argb: u32, line_width: f64, fill: bool) {
    let (cr, cg, cb) = argb_rgb(argb);
    let mut bs = Dictionary::new();
    if fill {
        annot.set("IC", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
        bs.set("W", Object::Real(0.0));
    } else {
        bs.set("W", Object::Real(line_width as f32));
    }
    annot.set("BS", Object::Dictionary(bs));
    set_alpha(annot, argb);
}

/// Add a Polygon (when `closed`) or PolyLine (open) annotation from flat
/// page-space x,y `points`. Closed polygons may be filled; open polylines are
/// always stroked. Used for triangles, stars, arrows, lines, polylines and
/// flattened Bézier curves.
pub(crate) fn add_poly(
    handle: i64,
    page_index: i32,
    points: &[f32],
    argb: u32,
    line_width: f64,
    fill: bool,
    closed: bool,
) -> Option<i64> {
    if points.len() < 4 {
        return None;
    }
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let converted = page_points(doc, page_index, points);
    let points = converted.as_slice();
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = line_width.max(0.5);
    let do_fill = fill && closed;

    let mut minx = f64::INFINITY;
    let mut miny = f64::INFINITY;
    let mut maxx = f64::NEG_INFINITY;
    let mut maxy = f64::NEG_INFINITY;
    let mut i = 0;
    while i + 1 < points.len() {
        let (x, y) = (points[i] as f64, points[i + 1] as f64);
        minx = minx.min(x);
        miny = miny.min(y);
        maxx = maxx.max(x);
        maxy = maxy.max(y);
        i += 2;
    }
    let pad = lw + 2.0;
    let rect = [minx - pad, miny - pad, maxx + pad, maxy + pad];
    let (w, h) = (rect[2] - rect[0], rect[3] - rect[1]);

    let mut c = String::from("q ");
    if do_fill {
        c.push_str(&format!("{cr:.3} {cg:.3} {cb:.3} rg "));
    } else {
        c.push_str(&format!("{lw} w {cr:.3} {cg:.3} {cb:.3} RG "));
    }
    let mut verts = Vec::new();
    let mut j = 0;
    let mut first = true;
    while j + 1 < points.len() {
        let px = points[j] as f64;
        let py = points[j + 1] as f64;
        verts.push(px.into());
        verts.push(py.into());
        let (lx, ly) = (px - rect[0], py - rect[1]);
        if first {
            c.push_str(&format!("{lx:.2} {ly:.2} m "));
            first = false;
        } else {
            c.push_str(&format!("{lx:.2} {ly:.2} l "));
        }
        j += 2;
    }
    if closed {
        c.push_str(if do_fill { "h f Q" } else { "h S Q" });
    } else {
        c.push_str("S Q");
    }
    let ap_id = make_appearance(doc, w, h, c.into_bytes(), Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj(if closed { "Polygon" } else { "PolyLine" }));
    annot.set("Vertices", Object::Array(verts));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    if do_fill {
        annot.set("IC", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    }
    let mut bs = Dictionary::new();
    bs.set("W", Object::Real(if do_fill { 0.0 } else { lw as f32 }));
    annot.set("BS", Object::Dictionary(bs));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, normalize_rect(rect), ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// `points`: flat page-space x,y pairs of a single ink stroke.
pub(crate) fn add_ink(
    handle: i64,
    page_index: i32,
    argb: u32,
    line_width: f64,
    points: &[f32],
) -> Option<i64> {
    if points.len() < 4 {
        return None;
    }
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let converted = page_points(doc, page_index, points);
    let points = converted.as_slice();
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = line_width.max(0.5);

    let mut minx = f64::INFINITY;
    let mut miny = f64::INFINITY;
    let mut maxx = f64::NEG_INFINITY;
    let mut maxy = f64::NEG_INFINITY;
    let mut i = 0;
    while i + 1 < points.len() {
        let (x, y) = (points[i] as f64, points[i + 1] as f64);
        minx = minx.min(x);
        miny = miny.min(y);
        maxx = maxx.max(x);
        maxy = maxy.max(y);
        i += 2;
    }
    let pad = lw + 2.0;
    let rect = [minx - pad, miny - pad, maxx + pad, maxy + pad];
    let (w, h) = (rect[2] - rect[0], rect[3] - rect[1]);

    // Appearance content in BBox space (origin at rect min).
    let mut c = format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG ");
    let mut ink = Vec::new();
    let mut j = 0;
    let mut first = true;
    while j + 1 < points.len() {
        let px = points[j] as f64;
        let py = points[j + 1] as f64;
        ink.push(px.into());
        ink.push(py.into());
        let (lx, ly) = (px - rect[0], py - rect[1]);
        if first {
            c.push_str(&format!("{lx:.2} {ly:.2} m "));
            first = false;
        } else {
            c.push_str(&format!("{lx:.2} {ly:.2} l "));
        }
        j += 2;
    }
    c.push_str("S Q");
    let ap_id = make_appearance(doc, w, h, c.into_bytes(), Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Ink"));
    annot.set("InkList", Object::Array(vec![Object::Array(ink)]));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    let mut bs = Dictionary::new();
    bs.set("W", Object::Real(lw as f32));
    annot.set("BS", Object::Dictionary(bs));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, rect, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// `jpeg`: raw JPEG bytes for a Stamp annotation image.
pub(crate) fn add_stamp(
    handle: i64,
    page_index: i32,
    rect: [f64; 4],
    img_w: u32,
    img_h: u32,
    jpeg: &[u8],
) -> Option<i64> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);

    let mut img_dict = Dictionary::new();
    img_dict.set("Type", name_obj("XObject"));
    img_dict.set("Subtype", name_obj("Image"));
    img_dict.set("Width", Object::Integer(img_w as i64));
    img_dict.set("Height", Object::Integer(img_h as i64));
    img_dict.set("BitsPerComponent", Object::Integer(8));
    img_dict.set("ColorSpace", name_obj("DeviceRGB"));
    img_dict.set("Filter", name_obj("DCTDecode"));
    let img_id = doc.add_object(Stream::new(img_dict, jpeg.to_vec()));

    let mut xobj = Dictionary::new();
    xobj.set("Im0", Object::Reference(img_id));
    let mut res = Dictionary::new();
    res.set("XObject", Object::Dictionary(xobj));
    let content = format!("q {w} 0 0 {h} 0 0 cm /Im0 Do Q").into_bytes();
    let ap_id = make_appearance(doc, w, h, content, res);

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Stamp"));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

pub(crate) fn update_annotation_rect(handle: i64, page_index: i32, annot_id: i64, rect: [f64; 4]) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let pr = page_rect(doc, page_index, rect);
    let id = decode_id(annot_id);
    if let Ok(dict) = doc.get_dictionary_mut(id) {
        dict.set("Rect", rect_obj(pr));
        true
    } else {
        false
    }
}

pub(crate) fn update_free_text(handle: i64, annot_id: i64, text: &str) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    // Read existing rect / color / size.
    let (rect, argb, size) = {
        let dict = match doc.get_dictionary(id) {
            Ok(d) => d,
            Err(_) => return false,
        };
        let rect = dict
            .get(b"Rect")
            .ok()
            .and_then(|o| read_rect(doc, o))
            .map(normalize_rect)
            .unwrap_or([0.0, 0.0, 100.0, 20.0]);
        let argb = dict
            .get(b"C")
            .ok()
            .and_then(|o| o.as_array().ok())
            .filter(|a| a.len() == 3)
            .map(|a| {
                let r = a[0].as_float().unwrap_or(0.0);
                let g = a[1].as_float().unwrap_or(0.0);
                let b = a[2].as_float().unwrap_or(0.0);
                rgb_to_argb(r as f64, g as f64, b as f64)
            })
            .unwrap_or(0xFF00_0000);
        let size = dict
            .get(b"DA")
            .ok()
            .and_then(|o| o.as_str().ok())
            .and_then(|s| parse_da_size(s))
            .unwrap_or(12.0);
        (rect, argb, size)
    };
    let (w, h) = (rect[2] - rect[0], rect[3] - rect[1]);
    let content = free_text_content(w, h, text, argb, size);
    let ap_id = make_appearance(doc, w, h, content, helvetica_resources());
    if let Ok(dict) = doc.get_dictionary_mut(id) {
        dict.set("Contents", Object::string_literal(text));
        let mut ap = Dictionary::new();
        ap.set("N", Object::Reference(ap_id));
        dict.set("AP", Object::Dictionary(ap));
        true
    } else {
        false
    }
}

/// Extract the font size preceding `Tf` in a `/DA` string.
pub(crate) fn parse_da_size(da: &[u8]) -> Option<f64> {
    let s = String::from_utf8_lossy(da);
    let toks: Vec<&str> = s.split_whitespace().collect();
    let tf = toks.iter().position(|t| *t == "Tf")?;
    if tf == 0 {
        return None;
    }
    toks[tf - 1].parse::<f64>().ok()
}

/// Remove an annotation reference from a page's `/Annots` (inline or indirect).
/// Returns whether a reference was actually removed. Does NOT delete the object.
pub(crate) fn remove_annot_ref(doc: &mut Document, page_id: ObjectId, id: ObjectId) -> bool {
    let indirect = match doc.get_dictionary(page_id).ok().and_then(|d| d.get(b"Annots").ok()) {
        Some(Object::Reference(aid)) => Some(*aid),
        _ => None,
    };
    if let Some(arr_id) = indirect {
        if let Ok(Object::Array(a)) = doc.get_object_mut(arr_id) {
            let before = a.len();
            a.retain(|o| o.as_reference().ok() != Some(id));
            return before != a.len();
        }
        return false;
    }
    if let Ok(page) = doc.get_dictionary_mut(page_id) {
        if let Ok(Object::Array(a)) = page.get_mut(b"Annots") {
            let before = a.len();
            a.retain(|o| o.as_reference().ok() != Some(id));
            return before != a.len();
        }
    }
    false
}

pub(crate) fn delete_annotation(handle: i64, page_index: i32, annot_id: i64) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return false,
    };
    let removed = remove_annot_ref(doc, page_id, id);
    doc.objects.remove(&id);
    removed
}

/// Detach an annotation (remove its page reference) but keep the object, so it
/// can be re-attached for undo/redo.
pub(crate) fn detach_annotation(handle: i64, page_index: i32, annot_id: i64) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return false,
    };
    remove_annot_ref(doc, page_id, id)
}

/// Re-attach a previously detached annotation to its page.
pub(crate) fn reattach_annotation(handle: i64, page_index: i32, annot_id: i64) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    if !doc.objects.contains_key(&id) {
        return false;
    }
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return false,
    };
    append_annot(doc, page_id, id);
    true
}

/// Offset alternating x,y numbers of a flat array in place by (dx, dy).
pub(crate) fn offset_flat(arr: &mut [Object], dx: f64, dy: f64) {
    for (i, o) in arr.iter_mut().enumerate() {
        if let Some(n) = num(o) {
            let d = if i % 2 == 0 { dx } else { dy };
            *o = Object::Real((n + d) as f32);
        }
    }
}

/// Duplicate an annotation, shifting its geometry by (dx, dy) page-space units.
/// The copy shares the (immutable) appearance stream. Returns the new id, or 0.
pub(crate) fn duplicate_annotation(handle: i64, page_index: i32, annot_id: i64, dx: f64, dy: f64) -> i64 {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return 0,
    };
    let id = decode_id(annot_id);
    let mut dict = match doc.get_dictionary(id) {
        Ok(d) => d.clone(),
        Err(_) => return 0,
    };
    for key in [b"Rect".as_ref(), b"Vertices", b"QuadPoints", b"L"] {
        if let Ok(Object::Array(a)) = dict.get(key) {
            let mut a2 = a.clone();
            offset_flat(&mut a2, dx, dy);
            dict.set(key.to_vec(), Object::Array(a2));
        }
    }
    if let Ok(Object::Array(lists)) = dict.get(b"InkList") {
        let mut out = Vec::with_capacity(lists.len());
        for l in lists {
            if let Object::Array(pts) = l {
                let mut p2 = pts.clone();
                offset_flat(&mut p2, dx, dy);
                out.push(Object::Array(p2));
            } else {
                out.push(l.clone());
            }
        }
        dict.set("InkList", Object::Array(out));
    }
    let new_id = doc.add_object(dict);
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return 0,
    };
    append_annot(doc, page_id, new_id);
    encode_id(new_id)
}

// --- Serialized listing for the UI ---------------------------------------

pub(crate) fn subtype_code(subtype: &[u8]) -> u8 {
    match subtype {
        b"FreeText" => 1,
        b"Highlight" => 2,
        b"Square" => 3,
        b"Ink" => 4,
        b"Stamp" => 5,
        b"Widget" => 6,
        b"Text" => 7,
        b"Line" => 8,
        b"Circle" => 9,
        b"Polygon" => 10,
        b"PolyLine" => 11,
        b"Underline" => 12,
        b"StrikeOut" => 13,
        b"Squiggly" => 14,
        b"Link" => 15,
        b"Popup" => 16,
        b"FileAttachment" => 17,
        b"Sound" => 18,
        b"Movie" => 19,
        b"Screen" => 20,
        b"Caret" => 21,
        b"Redact" => 22,
        b"Watermark" => 23,
        b"PrinterMark" => 24,
        b"TrapNet" => 25,
        b"3D" => 26,
        _ => 0,
    }
}

pub(crate) fn annot_color(doc: &Document, dict: &Dictionary) -> u32 {
    dict.get(b"C")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_array().ok())
        .filter(|a| a.len() == 3)
        .map(|a| {
            rgb_to_argb(
                a[0].as_float().unwrap_or(0.0) as f64,
                a[1].as_float().unwrap_or(0.0) as f64,
                a[2].as_float().unwrap_or(0.0) as f64,
            )
        })
        .unwrap_or(0xFF00_0000)
}

pub(crate) fn list_annotations(handle: i64, page_index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap();
    let doc = reg.get(&handle)?;
    let page_id = nth_page_id(doc, page_index)?;
    let base = page_base_matrix(doc, page_id);

    let mut records: Vec<(i64, u8, [f64; 4], u32, String)> = Vec::new();
    if let Some(Object::Array(annots)) = doc
        .get_dictionary(page_id)
        .ok()
        .and_then(|d| d.get(b"Annots").ok())
        .and_then(|o| deref(doc, o))
    {
        for a in annots {
            let id = match a.as_reference() {
                Ok(id) => id,
                Err(_) => continue,
            };
            let dict = match doc.get_dictionary(id) {
                Ok(d) => d,
                Err(_) => continue,
            };
            let subtype = dict.get(b"Subtype").ok().and_then(|o| o.as_name().ok());
            let code = subtype.map(subtype_code).unwrap_or(0);
            // Report rects in displayed space so the editor's hit-testing and
            // selection boxes line up with the (rotation-baked) render.
            let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                Some(r) => {
                    let n = normalize_rect(r);
                    let (dx0, dy0) = transform(&base, n[0], n[1]);
                    let (dx1, dy1) = transform(&base, n[2], n[3]);
                    normalize_rect([dx0, dy0, dx1, dy1])
                }
                None => continue,
            };
            let color = annot_color(doc, dict);
            let contents = dict
                .get(b"Contents")
                .ok()
                .and_then(|o| o.as_str().ok())
                .map(decode_pdf_text)
                .unwrap_or_default();
            records.push((encode_id(id), code, rect, color, contents));
        }
    }

    let mut buf = Vec::new();
    buf.extend_from_slice(&(records.len() as u32).to_le_bytes());
    for (id, code, rect, color, contents) in records {
        buf.extend_from_slice(&id.to_le_bytes());
        buf.push(code);
        for v in rect {
            buf.extend_from_slice(&(v as f32).to_le_bytes());
        }
        buf.extend_from_slice(&color.to_le_bytes());
        let b = contents.as_bytes();
        let len = b.len().min(u16::MAX as usize);
        buf.extend_from_slice(&(len as u16).to_le_bytes());
        buf.extend_from_slice(&b[..len]);
    }
    Some(buf)
}

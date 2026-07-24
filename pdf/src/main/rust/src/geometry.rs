use crate::*;

/// A PDF transformation matrix `[a b c d e f]` representing
/// `[[a b 0] [c d 0] [e f 1]]`.
pub(crate) type Mat = [f64; 6];

pub(crate) const IDENTITY: Mat = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0];

/// `m1 * m2` in PDF convention (m1 is applied first).
pub(crate) fn mat_mul(m1: &Mat, m2: &Mat) -> Mat {
    [
        m1[0] * m2[0] + m1[1] * m2[2],
        m1[0] * m2[1] + m1[1] * m2[3],
        m1[2] * m2[0] + m1[3] * m2[2],
        m1[2] * m2[1] + m1[3] * m2[3],
        m1[4] * m2[0] + m1[5] * m2[2] + m2[4],
        m1[4] * m2[1] + m1[5] * m2[3] + m2[5],
    ]
}

/// Transform point `(x, y)` by `m`.
pub(crate) fn transform(m: &Mat, x: f64, y: f64) -> (f64, f64) {
    (m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5])
}

pub(crate) fn translate(tx: f64, ty: f64) -> Mat {
    [1.0, 0.0, 0.0, 1.0, tx, ty]
}

// ---------------------------------------------------------------------------
// Primitives
// ---------------------------------------------------------------------------

/// Look up `key` on the page dict, walking up `/Parent` for inherited
/// attributes (`MediaBox`, `Resources`).
pub(crate) fn inherited<'a>(doc: &'a Document, page_id: ObjectId, key: &[u8]) -> Option<&'a Object> {
    let mut current = page_id;
    for _ in 0..32 {
        let dict = doc.get_dictionary(current).ok()?;
        if let Ok(obj) = dict.get(key) {
            return Some(obj);
        }
        match dict.get(b"Parent").ok().and_then(|o| o.as_reference().ok()) {
            Some(parent) => current = parent,
            None => return None,
        }
    }
    None
}

/// Page MediaBox as `[x0, y0, x1, y1]`, defaulting to US Letter.
pub(crate) fn media_box(doc: &Document, page_id: ObjectId) -> [f64; 4] {
    let default = [0.0, 0.0, 612.0, 792.0];
    let obj = match inherited(doc, page_id, b"MediaBox").and_then(|o| deref(doc, o)) {
        Some(o) => o,
        None => return default,
    };
    let arr = match obj.as_array() {
        Ok(a) => a,
        Err(_) => return default,
    };
    if arr.len() != 4 {
        return default;
    }
    let mut out = [0.0; 4];
    for (i, v) in arr.iter().enumerate() {
        out[i] = match deref(doc, v).and_then(num) {
            Some(n) => n,
            None => return default,
        };
    }
    out
}

/// Normalized page rotation in {0,90,180,270}, inherited via `/Parent`.
pub(crate) fn page_rotation(doc: &Document, page_id: ObjectId) -> i64 {
    let r = inherited(doc, page_id, b"Rotate")
        .and_then(|o| deref(doc, o))
        .and_then(num)
        .unwrap_or(0.0) as i64;
    (((r % 360) + 360) % 360 / 90) * 90
}

/// Matrix mapping raw page space (MediaBox origin, before rotation) into
/// displayed space: origin bottom-left, with dimensions swapped for 90/270.
pub(crate) fn page_base_matrix(doc: &Document, page_id: ObjectId) -> Mat {
    let mb = media_box(doc, page_id);
    let w = (mb[2] - mb[0]).abs();
    let h = (mb[3] - mb[1]).abs();
    let t = translate(-mb[0].min(mb[2]), -mb[1].min(mb[3]));
    let r: Mat = match page_rotation(doc, page_id) {
        90 => [0.0, 1.0, -1.0, 0.0, h, 0.0],
        180 => [-1.0, 0.0, 0.0, -1.0, w, h],
        270 => [0.0, -1.0, 1.0, 0.0, 0.0, w],
        _ => IDENTITY,
    };
    mat_mul(&t, &r)
}

/// Page dimensions as displayed (after `/Rotate`).
pub(crate) fn page_display_size(doc: &Document, page_id: ObjectId) -> (f32, f32) {
    let mb = media_box(doc, page_id);
    let w = (mb[2] - mb[0]).abs() as f32;
    let h = (mb[3] - mb[1]).abs() as f32;
    match page_rotation(doc, page_id) {
        90 | 270 => (h, w),
        _ => (w, h),
    }
}

/// Inverse of an affine matrix `[a b c d e f]` (identity if singular).
pub(crate) fn mat_inverse(m: &Mat) -> Mat {
    let det = m[0] * m[3] - m[1] * m[2];
    if det.abs() < 1e-12 {
        return IDENTITY;
    }
    let inv = 1.0 / det;
    let a = m[3] * inv;
    let b = -m[1] * inv;
    let c = -m[2] * inv;
    let d = m[0] * inv;
    let e = -(m[4] * a + m[5] * c);
    let f = -(m[4] * b + m[5] * d);
    [a, b, c, d, e, f]
}

/// Inverse base matrix for a page index, mapping displayed (editor) coordinates
/// back into raw page space so stored annotations remain valid PDF.
pub(crate) fn page_base_inverse(doc: &Document, page_index: i32) -> Mat {
    match nth_page_id(doc, page_index) {
        Some(pid) => mat_inverse(&page_base_matrix(doc, pid)),
        None => IDENTITY,
    }
}

/// Convert an editor-space rect into a normalized raw-page-space rect.
pub(crate) fn page_rect(doc: &Document, page_index: i32, rect: [f64; 4]) -> [f64; 4] {
    let binv = page_base_inverse(doc, page_index);
    let (x0, y0) = transform(&binv, rect[0], rect[1]);
    let (x1, y1) = transform(&binv, rect[2], rect[3]);
    normalize_rect([x0, y0, x1, y1])
}

/// Convert editor-space flat x,y points into raw-page-space.
pub(crate) fn page_points(doc: &Document, page_index: i32, points: &[f32]) -> Vec<f32> {
    let binv = page_base_inverse(doc, page_index);
    let mut out = Vec::with_capacity(points.len());
    let mut i = 0;
    while i + 1 < points.len() {
        let (x, y) = transform(&binv, points[i] as f64, points[i + 1] as f64);
        out.push(x as f32);
        out.push(y as f32);
        i += 2;
    }
    out
}

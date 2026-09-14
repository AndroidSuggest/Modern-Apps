use crate::*;

/// The §12.5.5 appearance algorithm's matrix **A**: it maps the `/Matrix`-
/// transformed `/BBox` onto the annotation `/Rect`, and does NOT itself include
/// `/Matrix`.
///
/// This is the matrix to concatenate before invoking the appearance with `Do`,
/// because §8.10.2 makes `Do` concatenate the form's own `/Matrix` with the CTM.
/// Using the full `AA` there applies `/Matrix` twice. Callers that play the
/// appearance's operators directly want `appearance_matrix` instead.
pub(crate) fn appearance_fit_matrix(rect: [f64; 4], bbox: [f64; 4], matrix: Mat) -> Mat {
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
    [sx, 0.0, 0.0, sy, rx0 - sx * tx0, ry0 - sy * ty0]
}

/// The §12.5.5 appearance algorithm's matrix **AA** = `Matrix` × **A**: maps the
/// appearance stream's form space straight to page space.
///
/// For a caller that interprets the appearance's operator list itself (which
/// therefore never sees a `Do` to apply `/Matrix` for it). A caller that emits
/// `Do` must use `appearance_fit_matrix`.
pub(crate) fn appearance_matrix(rect: [f64; 4], bbox: [f64; 4], matrix: Mat) -> Mat {
    mat_mul(&matrix, &appearance_fit_matrix(rect, bbox, matrix))
}

/// Whether an annotation should be painted on screen. Shared by the renderer and
/// by `flatten_document`, which must not bake in anything invisible.
///
/// Per §12.5.3 Table 165 the `/F` flags are tested by VALUE: Hidden is bit
/// position 2 (value 2), NoView is bit position 6 (value 32). Also honors
/// optional content (§8.11.2) and skips `/Popup`, whose appearance is shown only
/// via its parent's open state (§12.5.6.14).
///
/// The `OcConfig` is a PARAMETER, deliberately. There was a `annot_visible_on_screen(doc,
/// dict)` convenience wrapper that called `OcConfig::from_doc` itself, and both callers
/// walk an `/Annots` array — so it rebuilt the catalog's whole membership configuration
/// once per annotation. It is deleted rather than left for the next caller to reach for,
/// because that shape is what silently undid a measured 8.8x win twice: once in the
/// renderer's `Do` arm and once here in `flatten_document`. Resolve the config once, at
/// the outermost point that has the document, and pass it down.
pub(crate) fn annot_visible_on_screen_with(
    oc: &OcConfig,
    doc: &Document,
    dict: &lopdf::Dictionary,
) -> bool {
    let flags = dict
        .get(b"F")
        .ok()
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(num)
        .unwrap_or(0.0) as i64;
    if flags & 0b10 != 0 || flags & 0b10_0000 != 0 {
        return false;
    }
    if dict.get(b"OC").ok().map(|o| oc.object_hidden(doc, o)).unwrap_or(false) {
        return false;
    }
    dict.get(b"Subtype")
        .ok()
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(|o| o.as_name().ok())
        != Some(b"Popup")
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

    let oc = OcConfig::from_doc(doc);
    for a in annots.iter().take(MAX_ANNOTATIONS) {
        let dict = match deref(doc, a).and_then(|o| o.as_dict().ok()) {
            Some(d) => d,
            None => continue,
        };
        if !annot_visible_on_screen_with(&oc, doc, dict) {
            continue;
        }
        render_annotation(doc, dict, base, prims);
    }
    if annots.len() > MAX_ANNOTATIONS {
        // Each annotation can emit an appearance stream's worth of primitives, so an
        // /Annots array with a hostile number of entries is an unbounded amount of work
        // even though the array itself is bounded by the file size.
        if cfg!(debug_assertions) {
            eprintln!(
                "[pdf_render/annotations] page has {} annotations - only the first {} are rendered",
                annots.len(),
                MAX_ANNOTATIONS
            );
        }
    }
}

pub(crate) fn render_annotation(doc: &Document, dict: &lopdf::Dictionary, base: &Mat, prims: &mut Vec<Prim>) {
    let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
        Some(r) => r,
        None => return,
    };

    // §12.7.2 Table 218: when the AcroForm sets /NeedAppearances the consumer
    // SHALL construct field appearances from /V and /DA, so the flag wins over a
    // /AP that is present but stale. Without the flag a real /AP wins, and a
    // Widget reaches the same synthesis only through
    // `synthesize_annotation_appearance` below — i.e. when there is no usable
    // /AP at all.
    if dict.get(b"Subtype").ok().and_then(|o| deref(doc, o).or(Some(o))).and_then(|o| o.as_name().ok())
        == Some(b"Widget".as_ref())
        && crate::forms::need_appearances(doc)
        && render_widget_value(doc, dict, rect, base, prims)
    {
        return;
    }

    // Resolve the normal appearance: /AP /N is either a stream or a subdictionary
    // of appearance states selected by /AS. When absent, synthesize a basic
    // appearance for common markup/shape annotation types.
    let ap = match dict.get(b"AP").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Dictionary(d)) => d,
        _ => {
            synthesize_annotation_appearance(doc, dict, rect, base, prims);
            return;
        }
    };
    // Fix P0 early return without fallback — if /AP present but N malformed, synthesize fallback
    let normal = match ap.get(b"N").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Stream(s)) => s,
        Some(Object::Dictionary(states)) => {
            // §7.3.10 lets any object be indirect. An indirect /AS read as a
            // plain name yields None, which falls into the no-/AS branch below
            // and renders a checkbox whose state was set indirectly as unchecked
            // — or draws nothing at all. /F, /Subtype, /C and /CA are all
            // dereferenced in this file; /AS was the outlier.
            let as_name = dict
                .get(b"AS")
                .ok()
                .and_then(|o| deref(doc, o).or(Some(o)))
                .and_then(|o| o.as_name().ok());
            let picked = match as_name {
                // §12.5.5 defines no fallback for an /AS that names a state the
                // /N dictionary does not hold, so this is a judgement about
                // malformed input. The name itself decides the direction:
                //
                //   /AS = /Off, absent — blank IS the off appearance, and
                //   omitting the /Off entry is a normal way to encode it. Draw
                //   nothing, which the synthesis fallback below does.
                //
                //   /AS = anything else, absent — the file is asserting this
                //   widget is ON. Falling back to /Off then renders a checked
                //   box as unchecked, and if /Off is absent too it renders
                //   NOTHING at all. When exactly one non-Off state exists it is
                //   unambiguously the on-art under a different export name
                //   (/Yes vs /On vs /1), so use it. That is not the
                //   "nondeterministic arbitrary entry" this used to warn about:
                //   it is taken only when /AS has explicitly said not-Off, so it
                //   cannot turn an unchecked box into a checked one.
                Some(n) => states.get(n).ok().or_else(|| {
                    if n == b"Off" {
                        return None;
                    }
                    let mut on: Vec<&Object> =
                        states.iter().filter(|(k, _)| k.as_slice() != b"Off").map(|(_, v)| v).collect();
                    match on.len() {
                        1 => Some(on.remove(0)),
                        _ => states.get(b"Off").ok(),
                    }
                }),
                // /AS absent is malformed (§12.5.5, Table 168 requires it when /N
                // is a subdictionary). Prefer "Off"; failing that, a single entry
                // is unambiguous, so use the real appearance rather than
                // synthesizing a crude one over the top of it.
                None => states.get(b"Off").ok().or_else(|| {
                    if states.len() == 1 {
                        states.iter().next().map(|(_, v)| v)
                    } else {
                        None
                    }
                }),
            };
            match picked.and_then(|o| deref(doc, o)) {
                Some(Object::Stream(s)) => s,
                _ => {
                    synthesize_annotation_appearance(doc, dict, rect, base, prims);
                    return;
                }
            }
        }
        _ => {
            synthesize_annotation_appearance(doc, dict, rect, base, prims);
            return;
        }
    };

    let bbox_raw = normal
        .dict
        .get(b"BBox")
        .ok()
        .and_then(|o| read_rect(doc, o));
    let bbox = bbox_raw.unwrap_or([0.0, 0.0, 1.0, 1.0]);
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

    let ops = crate::content::stream_operations(doc, normal);
    if ops.is_empty() {
        // `objects.rs` returns an empty operation list for a stream it could not
        // decode at all (unknown filter, /Crypt, broken filter chain), which is
        // indistinguishable here from a genuinely empty appearance. Every other
        // unusable-/AP branch above falls back to the synthesized shape; this one
        // returned, so the annotation vanished outright.
        synthesize_annotation_appearance(doc, dict, rect, base, prims);
        return;
    }

    let ctm = mat_mul(&appearance_matrix(rect, bbox, matrix), base);

    // §8.10.2: a form XObject's /BBox "shall be used to clip" its contents, and
    // §12.5.5 defines an appearance stream as a form XObject. Without this an
    // appearance paints outside its own Rect. Clip to the /Matrix-transformed
    // BBox QUAD rather than the axis-aligned box §12.5.5 derives for the /Rect
    // fit — the latter is too loose and lets a rotated appearance spill. The
    // same `ctm` is reused for the clip and the content, so the form's /Matrix
    // (already folded into it by appearance_matrix) cannot be applied twice.
    //
    // A degenerate BBox cannot clip — a collapsed quad would swallow the whole
    // annotation, turning a spill into a vanish — but emitting NO clip is not safe
    // either: `sh` fills the whole of the current clip region (§8.7.4.3), so an
    // appearance stream containing one floods the entire page. Fall back to the
    // annotation's own /Rect, which bounds it per §12.5.5 regardless of the /BBox.
    // /Rect is in default user space, so it maps through `base`, not `ctm`.
    let (clip, clip_ctm) = match bbox_raw
        .map(normalize_rect)
        .filter(|b| b[2] - b[0] > 0.0 && b[3] - b[1] > 0.0)
    {
        Some(b) => (Some(b), ctm),
        None => {
            let r = normalize_rect(rect);
            let usable = (r[2] - r[0] > 0.0 && r[3] - r[1] > 0.0).then_some(r);
            (usable, *base)
        }
    };
    let mut clip_bbox_device: Option<[f64; 4]> = None;
    if let Some(b) = clip {
        let dev: Vec<(f64, f64)> = [(b[0], b[1]), (b[2], b[1]), (b[2], b[3]), (b[0], b[3])]
            .iter()
            .map(|&(x, y)| transform(&clip_ctm, x, y))
            .collect();
        let xs = dev.iter().map(|p| p.0);
        let ys = dev.iter().map(|p| p.1);
        let bb = [
            xs.clone().fold(f64::INFINITY, f64::min),
            ys.clone().fold(f64::INFINITY, f64::min),
            xs.fold(f64::NEG_INFINITY, f64::max),
            ys.fold(f64::NEG_INFINITY, f64::max),
        ];
        if bb.iter().all(|v| v.is_finite()) && bb[2] > bb[0] && bb[3] > bb[1] {
            clip_bbox_device = Some(bb);
        }
        let pts: Vec<(f32, f32)> = dev.iter().map(|&(x, y)| (x as f32, y as f32)).collect();
        let mut path_ops = vec![PathOp::Move(pts[0].0, pts[0].1)];
        path_ops.extend(pts[1..].iter().map(|&(x, y)| PathOp::Line(x, y)));
        path_ops.push(PathOp::Close);
        prims.push(Prim::ClipPush { even_odd: false, pts, path_ops: Some(path_ops) });
    }

    let gs = GraphicsState {
        ctm,
        ..Default::default()
    };
    let start = prims.len();
    // Seed the device clip extent rather than using `interpret_content`, which passes
    // `None`. §8.7.4.1 requires `sh` to cover the entire clipping region, so when a
    // shading has no `/BBox` of its own `rasterize_shading` needs that extent to know
    // what to cover — and with `None` it returns `None` and pushes no prim at all, so a
    // gradient painted with `sh` inside an appearance stream was INVISIBLE. The comment
    // on that early return assumed the page-level caller always seeds it; an annotation
    // appearance is the caller that does not. The extent is the same BBox-or-Rect quad
    // clipped above, which is exactly the region such an `sh` may cover.
    interpret_content_seeded(
        doc,
        &ops,
        res.as_ref(),
        gs,
        prims,
        1,
        false,
        clip_bbox_device,
    );

    // §12.5.2: /CA is the constant opacity the WHOLE annotation is painted with, so
    // §11.6.6 applies it once to the composited appearance — exactly what a
    // GroupPush/GroupPop layer does, and the same mechanism the `Do` arm uses for /ca on
    // a form XObject.
    //
    // This used to walk `prims[start..]` calling `scale_prim_alpha` on each, which
    // conflicts with two other fixes:
    //   * a transparency group inside the appearance already carries its alpha on
    //     `GroupPush`, and the loop scaled BOTH the GroupPush and every prim inside it,
    //     so /CA landed twice (CA² on the group's contents);
    //   * `wrap_with_soft_mask` appends the mask group's own prims after
    //     `SoftMaskContent`, inside this same range, so the loop faded the MASK rather
    //     than the content — for a luminosity mask that changes the mask's shape, not
    //     its opacity.
    // Wrapping instead of scaling cannot reach either: the layer's alpha is applied to
    // the composite, and nothing inside is rewritten.
    let ca = dict.get(b"CA").ok().and_then(|o| deref(doc, o).or(Some(o))).and_then(num).unwrap_or(1.0);
    if ca < 1.0 && prims.len() > start {
        prims.insert(
            start,
            Prim::GroupPush {
                isolated: true,
                knockout: false,
                alpha: ca.clamp(0.0, 1.0) as f32,
                blend: BlendMode::Normal,
            },
        );
        prims.push(Prim::GroupPop);
    }
    // Unconditional pop (mirrors the `Do` arm): interpret_content always returns
    // with a balanced clip depth, so this keeps the canvas clip stack balanced
    // even if the content hit the primitive cap.
    if clip.is_some() {
        prims.push(Prim::ClipPop);
    }
}

/// Read an annotation color array (`/C`, `/IC`) as ARGB, or `None` when the
/// array is empty (meaning "no color" / transparent) or absent.
///
/// The array is dereferenced: §7.3.10 lets any object be indirect, and an
/// indirect `/C` previously read as "no colour", silently substituting the
/// default instead of the author's.
fn markup_color(doc: &Document, dict: &lopdf::Dictionary, key: &[u8]) -> Option<u32> {
    let arr = dict
        .get(key)
        .ok()
        .and_then(|o| deref(doc, o).or(Some(o)))?
        .as_array()
        .ok()?;
    let c: Vec<f64> = arr.iter().filter_map(num).collect();
    match c.len() {
        1 => Some(gray_to_argb(c[0])),
        3 => Some(rgb_to_argb(c[0], c[1], c[2])),
        4 => Some(cmyk_to_argb(c[0], c[1], c[2], c[3])),
        _ => None,
    }
}

/// Border width from `/BS /W` (preferred) or the legacy `/Border` array [_,_,W].
fn annot_border_width(doc: &Document, dict: &lopdf::Dictionary) -> f64 {
    if let Some(w) = dict.get(b"BS").ok().and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|bs| bs.get(b"W").ok()).and_then(num) {
        return w;
    }
    if let Some(Object::Array(b)) = dict.get(b"Border").ok().and_then(|o| deref(doc, o)) {
        if let Some(w) = b.get(2).and_then(num) { return w; }
    }
    1.0
}

/// Border dash array from ExGState `/D` or `/BS /D` or `/Border` dash [3rd?].
fn annot_border_dash(doc: &Document, dict: &lopdf::Dictionary) -> Vec<f64> {
    const MAX_D: usize = 32;
    if let Some(b) = dict.get(b"BS").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok()) {
        if let Some(db) = b.get(b"D").ok().and_then(|o| deref(doc, o).or(Some(o))) {
            let (d, _phase) = parse_dash_extgstate(doc, db);
            if !d.is_empty() {
                return d.into_iter().take(MAX_D).collect();
            }
        }
    }
    if let Some(Object::Array(b)) = dict.get(b"Border").ok().and_then(|o| deref(doc, o)) {
        if b.len() >= 4 {
            if let Some(Object::Array(dash_arr)) = b.get(3) {
                return dash_arr.iter().filter_map(num).filter(|v| *v >= 0.0).take(MAX_D).collect();
            }
        }
    }
    Vec::new()
}

/// Numbers of an annotation's coordinate array (`/QuadPoints`, `/L`,
/// `/Vertices`, one `/InkList` path), or `None` when any of them is non-finite.
///
/// §7.3.3 numbers are finite, but a real with more digits than `f32` can hold
/// parses to an infinity, and one poisoned coordinate propagates through every
/// primitive derived from it — the rasterizer drops a path containing a
/// non-finite point silently, so the annotation vanishes with no error
/// anywhere. Rejecting the whole array matches `read_rect`'s treatment of a
/// non-finite `/Rect`, and matches this function's rule for missing geometry:
/// draw nothing rather than a shape the file did not actually give.
fn finite_coords(doc: &Document, obj: &Object) -> Option<Vec<f64>> {
    let v: Vec<f64> = deref(doc, obj)?.as_array().ok()?.iter().filter_map(num).collect();
    v.iter().all(|n| n.is_finite()).then_some(v)
}

include!("annotations_part1.rs");
include!("annotations_part2.rs");
include!("annotations_part3.rs");
include!("annotations_part4.rs");
include!("annotations_part5.rs");
include!("annotations_part6.rs");
include!("annotations_part7.rs");
include!("annotations_part8.rs");
include!("annotations_part9.rs");
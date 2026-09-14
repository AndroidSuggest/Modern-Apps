pub(crate) fn synthesize_annotation_appearance(
    doc: &Document,
    dict: &lopdf::Dictionary,
    rect: [f64; 4],
    base: &Mat,
    prims: &mut Vec<Prim>,
) {
    // Dereferenced per §7.3.10: an indirect /Subtype read as a plain name gives
    // None and returns here, so the annotation synthesizes NOTHING — the same
    // total loss the arms below exist to prevent. `annot_visible_on_screen_with`
    // already derefs this key.
    let subtype = match dict.get(b"Subtype").ok().and_then(|o| deref(doc, o).or(Some(o))).and_then(|o| o.as_name().ok()) {
        Some(s) => s.to_vec(),
        None => return,
    };
    let ca = dict
        .get(b"CA")
        .ok()
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(num)
        .unwrap_or(1.0)
        .clamp(0.0, 1.0);
    let stroke = markup_color(doc, dict, b"C");
    let fill = markup_color(doc, dict, b"IC");
    let bw = annot_border_width(doc, dict);
    // §12.5.4 Table 166: "/W ... the border width in points. If this value is 0,
    // no border shall be drawn." `gs.line_width` floors at 0.5, so a shape whose
    // file says W=0 would otherwise come out ringed in a hairline it never asked
    // for — most visibly around a filled Square/Circle, which is exactly how
    // this codebase's own `set_shape_border` marks a filled shape.
    //
    // Applied only to shapes whose content survives losing the border: the
    // Square/Circle interior (/IC) and the FreeText /Contents. For Line,
    // PolyLine and Ink the stroke IS the whole annotation, and dropping it on a
    // producer that wrote W=0 by accident would erase the annotation outright,
    // which is the larger risk of the two.
    let has_border = bw > 0.0;
    let dev = |x: f64, y: f64| -> (f64, f64) { transform(base, x, y) };
    // Device half-width for strokes (approx via base scale).
    let scale = ((base[0]*base[0]+base[1]*base[1]).sqrt() + (base[2]*base[2]+base[3]*base[3]).sqrt()) / 2.0;

    let gs = GraphicsState {
        ctm: *base,
        line_width: bw.max(0.5),
        alpha_fill: ca,
        alpha_stroke: ca,
        ..Default::default()
    };

    // QuadPoints (text markup): 8 numbers per quad.
    let quads: Vec<[(f64,f64);4]> = dict.get(b"QuadPoints").ok()
        .and_then(|o| finite_coords(doc, o))
        .map(|v| {
            v.chunks_exact(8).map(|q| [(q[0],q[1]),(q[2],q[3]),(q[4],q[5]),(q[6],q[7])]).collect()
        }).unwrap_or_default();

    match subtype.as_slice() {
        b"Square" => {
            // §12.5.6.8: the square is /Rect inset by /RD, or by half the border
            // width when /RD is absent — not /Rect itself.
            let sr = shape_rect(doc, dict, rect, bw);
            let poly = vec![dev(sr[0],sr[1]), dev(sr[2],sr[1]), dev(sr[2],sr[3]), dev(sr[0],sr[3])];
            if let Some(f) = fill { emit_fill(prims, std::slice::from_ref(&poly), apply_alpha_to_argb(f, ca), false, 1.0, BlendMode::Normal); }
            if let Some(s) = stroke.filter(|_| has_border) {
                let mut ring = poly.clone(); ring.push(poly[0]);
                let mut sgs = gs.clone(); sgs.stroke = s;
                let d = annot_border_dash(doc, dict);
                if !d.is_empty() { sgs.dash = d; }
                emit_stroke(prims, std::slice::from_ref(&ring), &sgs);
            }
        }
        b"Circle" => {
            // Approximate the inscribed ellipse with a polygon, inscribed in the
            // /RD- or border-inset rect rather than the full /Rect (§12.5.6.8).
            let sr = shape_rect(doc, dict, rect, bw);
            let (cx, cy) = ((sr[0]+sr[2])/2.0, (sr[1]+sr[3])/2.0);
            let (rx, ry) = ((sr[2]-sr[0]).abs()/2.0, (sr[3]-sr[1]).abs()/2.0);
            let poly: Vec<(f64,f64)> = (0..48).map(|i| {
                let t = i as f64 / 48.0 * std::f64::consts::TAU;
                dev(cx + rx*t.cos(), cy + ry*t.sin())
            }).collect();
            if let Some(f) = fill { emit_fill(prims, std::slice::from_ref(&poly), apply_alpha_to_argb(f, ca), false, 1.0, BlendMode::Normal); }
            if let Some(s) = stroke.filter(|_| has_border) {
                let mut ring = poly.clone(); ring.push(poly[0]);
                let mut sgs = gs.clone(); sgs.stroke = s;
                let d = annot_border_dash(doc, dict);
                if !d.is_empty() { sgs.dash = d; }
                emit_stroke(prims, std::slice::from_ref(&ring), &sgs);
            }
        }
        b"Line" => {
            let l_arr: Option<Vec<f64>> = dict.get(b"L").ok().and_then(|o| finite_coords(doc, o));
            if let Some(n) = l_arr {
                if n.len() >= 4 {
                    let (p0, p1) = ((n[0], n[1]), (n[2], n[3]));
                    let seg = vec![dev(p0.0, p0.1), dev(p1.0, p1.1)];
                    let mut sgs = gs.clone(); sgs.stroke = stroke.unwrap_or(0xFF00_0000);
                    let d = annot_border_dash(doc, dict);
                    if !d.is_empty() { sgs.dash = d; }
                    emit_stroke(prims, std::slice::from_ref(&seg), &sgs);
                    // §12.5.6.7: /LE is [startStyle endStyle] and each ending
                    // points OUTWARD along the line, so the start ending's
                    // direction is p1 -> p0 and the end ending's is p0 -> p1.
                    let (dx, dy) = (p1.0 - p0.0, p1.1 - p0.1);
                    let len = (dx * dx + dy * dy).sqrt();
                    if len > 1e-6 {
                        let u = (dx / len, dy / len);
                        let le: Vec<Vec<u8>> = dict
                            .get(b"LE")
                            .ok()
                            .and_then(|o| deref(doc, o))
                            .and_then(|o| o.as_array().ok())
                            .map(|a| a.iter().filter_map(|o| o.as_name().ok().map(|n| n.to_vec())).collect())
                            .unwrap_or_default();
                        let le_size = (bw.max(1.0) * 4.0).clamp(4.0, 24.0);
                        if let Some(s) = le.first() {
                            emit_line_ending(prims, base, p0, (-u.0, -u.1), s, le_size, &sgs, fill, ca);
                        }
                        if let Some(s) = le.get(1) {
                            emit_line_ending(prims, base, p1, u, s, le_size, &sgs, fill, ca);
                        }
                    }
                }
            }
        }
        b"Ink" => {
            if let Some(Object::Array(paths)) = dict.get(b"InkList").ok().and_then(|o| deref(doc, o)) {
                let mut sgs = gs.clone(); sgs.stroke = stroke.unwrap_or(0xFF00_0000);
                let d = annot_border_dash(doc, dict);
                if !d.is_empty() { sgs.dash = d; }
                for p in paths {
                    let n = match finite_coords(doc, p) {
                        Some(n) => n,
                        None => continue,
                    };
                    let pts: Vec<(f64,f64)> = n.chunks_exact(2).map(|c| dev(c[0], c[1])).collect();
                    if pts.len() >= 2 { emit_stroke(prims, std::slice::from_ref(&pts), &sgs); }
                }
            }
        }
        b"Highlight" => {
            let color = stroke.unwrap_or(0xFFFF_FF00); // default yellow
            if quads.is_empty() {
                // §12.5.6.10 makes /QuadPoints required, so this is malformed
                // input. The /Rect is still kept as the fallback region, unlike
                // Polygon (which draws nothing without /Vertices) and unlike the
                // sibling markup types below: a fill is well defined for ANY
                // region, and §12.5.2 already makes /Rect the area the annotation
                // occupies, so it claims nothing new. Underline and StrikeOut
                // need a quad to locate a BASELINE, which a /Rect cannot supply
                // at all — hence they draw nothing and this does not.
                let poly = vec![dev(rect[0],rect[1]), dev(rect[2],rect[1]), dev(rect[2],rect[3]), dev(rect[0],rect[3])];
                emit_fill(prims, std::slice::from_ref(&poly), apply_alpha_to_argb(color, ca), false, 1.0, BlendMode::Multiply);
            } else {
                // §12.5.6.10 orders the vertices UL, UR, LL, LR — a "Z", not a
                // ring — so traversing them in file order self-intersects into a
                // bow-tie. Reorder to UL, UR, LR, LL. Kept as a quad rather than
                // its bbox so rotated/skewed text quads stay correct.
                //
                // Over the primitive cap the loop simply stops. The previous
                // fallback swapped to axis-aligned bbox rects instead, which both
                // emitted the same number of primitives it was trying to avoid
                // AND drew the wrong shape for rotated text.
                for q in &quads {
                    if prims.len() >= MAX_PRIMITIVES {
                        break;
                    }
                    let poly: Vec<(f64,f64)> = [q[0], q[1], q[3], q[2]].iter().map(|&(x,y)| dev(x,y)).collect();
                    emit_fill(prims, std::slice::from_ref(&poly), apply_alpha_to_argb(color, ca), false, 1.0, BlendMode::Multiply);
                }
            }
        }
        b"Underline" | b"StrikeOut" | b"Squiggly" => {
            let color = stroke.unwrap_or(0xFF00_0000);
            let mut sgs = gs.clone(); sgs.stroke = color;
            let d = annot_border_dash(doc, dict);
            if !d.is_empty() { sgs.dash = d; }
            // §12.5.6.10 quad order is UL, UR, LL, LR. Work along the quad's own
            // edges in PAGE space and map each point through `base`, instead of
            // taking the device-space bbox: the bbox is axis-aligned, so on a
            // rotated page (or over rotated text) it drew a horizontal rule
            // across the glyphs rather than a rule following the baseline.
            if subtype != b"Squiggly" {
                sgs.line_width = (bw.max(1.0)) / scale.max(1e-6); // ~1px device
            }
            // Fraction of the quad height at which the rule sits, measured from
            // the bottom edge towards the top.
            let t = if subtype == b"StrikeOut" { 0.5 } else { 0.10 };
            for q in &quads {
                if prims.len() >= MAX_PRIMITIVES {
                    break;
                }
                let (ul, ur, ll, lr) = (q[0], q[1], q[2], q[3]);
                // Baseline-parallel start/end, lifted off the bottom edge.
                let a = (ll.0 + (ul.0 - ll.0) * t, ll.1 + (ul.1 - ll.1) * t);
                let b = (lr.0 + (ur.0 - lr.0) * t, lr.1 + (ur.1 - lr.1) * t);
                if subtype == b"Squiggly" {
                    // Zig-zag between the rule line and a line 8% of the quad
                    // height above it, so the wave follows the text direction.
                    let up = ((ul.0 - ll.0) * 0.08, (ul.1 - ll.1) * 0.08);
                    let mut zig: Vec<(f64, f64)> = Vec::with_capacity(9);
                    for i in 0..=8 {
                        let f = i as f64 / 8.0;
                        let (x, y) = (a.0 + (b.0 - a.0) * f, a.1 + (b.1 - a.1) * f);
                        let (x, y) = if i % 2 == 0 { (x, y) } else { (x + up.0, y + up.1) };
                        zig.push(dev(x, y));
                    }
                    emit_stroke(prims, std::slice::from_ref(&zig), &sgs);
                } else {
                    let seg = vec![dev(a.0, a.1), dev(b.0, b.1)];
                    emit_stroke(prims, std::slice::from_ref(&seg), &sgs);
                }
            }
        }
        b"Polygon" | b"PolyLine" => {
            // §12.5.6.9: the shape IS /Vertices. Without it there is no shape, so
            // draw nothing rather than the /Rect outline the old code fell back
            // to — a rectangle is indistinguishable from a Square annotation and
            // claims a geometry the file never gave.
            if let Some(n) = dict.get(b"Vertices").ok().and_then(|o| finite_coords(doc, o)) {
                let pts: Vec<(f64,f64)> = n.chunks_exact(2).map(|c| dev(c[0], c[1])).collect();
                if pts.len() >= 2 {
                    let closed = subtype == b"Polygon";
                    // /IC is the interior colour and only a closed Polygon has an
                    // interior; on a PolyLine it colours the line endings, so it
                    // must not become the stroke colour (§12.5.6.9).
                    if closed {
                        if let Some(f) = fill {
                            emit_fill(prims, std::slice::from_ref(&pts), apply_alpha_to_argb(f, ca), false, 1.0, BlendMode::Normal);
                        }
                    }
                    if let Some(s) = stroke {
                        let mut ring = pts.clone();
                        if closed { ring.push(pts[0]); }
                        let mut sgs = gs.clone(); sgs.stroke = s;
                        let d = annot_border_dash(doc, dict);
                        if !d.is_empty() { sgs.dash = d; }
                        emit_stroke(prims, std::slice::from_ref(&ring), &sgs);
                    }
                }
            }
        }
        b"Caret" => {
            // §12.5.6.11: a caret marks a text insertion point. Synthesize the
            // upward wedge, which says "inserted here"; the old /Rect outline was
            // indistinguishable from a Square annotation.
            let r = normalize_rect(rect);
            if r[2] - r[0] > 0.0 && r[3] - r[1] > 0.0 {
                let tri = vec![dev(r[0], r[1]), dev((r[0] + r[2]) / 2.0, r[3]), dev(r[2], r[1])];
                let col = stroke.unwrap_or(0xFF00_0000);
                emit_fill(prims, std::slice::from_ref(&tri), apply_alpha_to_argb(col, ca), false, 1.0, BlendMode::Normal);
            }
        }
        b"Stamp" => {
            // §12.5.6.12: /Name selects a standard stamp whose artwork we do not
            // ship. Drawing the stamp's own wording inside its border conveys
            // what the stamp says. With no /Name there is nothing defensible to
            // draw, so draw nothing — the old /Rect outline read as a Square.
            let label = dict
                .get(b"Name")
                .ok()
                .and_then(|o| deref(doc, o).or(Some(o)))
                .and_then(|o| o.as_name().ok())
                .map(stamp_label)
                .unwrap_or_default();
            let r = normalize_rect(rect);
            let (rw, rh) = (r[2] - r[0], r[3] - r[1]);
            if !label.is_empty() && rw > 0.0 && rh > 0.0 {
                let col = stroke.unwrap_or(0xFFFF_0000); // Acrobat's stamps are red
                let poly = vec![dev(r[0], r[1]), dev(r[2], r[1]), dev(r[2], r[3]), dev(r[0], r[3])];
                let mut ring = poly.clone(); ring.push(poly[0]);
                let mut sgs = gs.clone(); sgs.stroke = col;
                emit_stroke(prims, std::slice::from_ref(&ring), &sgs);
                // `emit_annot_text` advances 0.5em per character, so size the
                // text to fit the box on both axes.
                let n = label.chars().count().max(1) as f64;
                let size = (rh * 0.55).min(rw * 0.85 / (0.5 * n)).max(1.0);
                let (px, py) = dev(
                    r[0] + (rw - n * size * 0.5).max(0.0) / 2.0,
                    r[1] + (rh - size) / 2.0,
                );
                emit_annot_text(prims, px as f32, py as f32, (size * scale) as f32, apply_alpha_to_argb(col, ca), &label);
            }
        }
        b"FreeText" => {
            // Border/background box plus the /Contents text (no /AP fallback).
            //
            // Normalized up front, and every read below goes through `r`: the
            // box quad survives any corner ordering (inverting only reverses the
            // winding) but the text layout does NOT, and mixing raw and
            // normalized reads in one arm is what hid that. §7.9.5 lets /Rect be
            // given by any two diagonally opposite corners and `read_rect` does
            // not reorder them, so on an inverted rect `rect[3]` is the BOTTOM:
            // the first line started below the box and the `y < rect[1]` guard
            // — comparing against what is actually the TOP — broke the loop
            // immediately, painting the box with none of its text inside.
            let r = border_inset_rect(rect, bw);
            let poly = vec![dev(r[0],r[1]), dev(r[2],r[1]), dev(r[2],r[3]), dev(r[0],r[3])];
            if let Some(f) = fill { emit_fill(prims, std::slice::from_ref(&poly), apply_alpha_to_argb(f, ca), false, 1.0, BlendMode::Normal); }
            if let Some(s) = stroke.filter(|_| has_border) {
                let mut ring = poly.clone(); ring.push(poly[0]);
                let mut sgs = gs.clone(); sgs.stroke = s;
                let d = annot_border_dash(doc, dict);
                if !d.is_empty() { sgs.dash = d; }
                emit_stroke(prims, std::slice::from_ref(&ring), &sgs);
            }
            let text = dict.get(b"Contents").ok().and_then(|o| deref(doc, o)).and_then(|o| match o { Object::String(b,_) => Some(decode_pdf_text(b)), _ => None }).unwrap_or_default();
            if !text.is_empty() {
                // §12.5.6.6 Table 174 gives FreeText its OWN /RD: the inner
                // rectangle "is where the annotation's text should be
                // displayed". Different meaning from Table 177's shape-bounding
                // /RD, so this must not go through `shape_rect` — but ignoring
                // it entirely runs the text under a thick border, which is the
                // case /RD exists to describe.
                let t = free_text_rect(doc, dict, r);
                let size = 12.0_f64;
                let dsize = (size * scale) as f32;
                let mut y = t[3] - size; // top-down in page space
                for line in text.split(['\n', '\r']).filter(|l| !l.is_empty()) {
                    if prims.len() >= MAX_PRIMITIVES || y < t[1] { break; }
                    let (px, py) = dev(t[0] + 2.0, y);
                    emit_annot_text(prims, px as f32, py as f32, dsize, 0xFF00_0000, line);
                    y -= size * 1.2;
                }
            }
        }
        b"Text" => {
            // Sticky-note marker. This is the one place where "draw nothing
            // rather than a crude shape" inverts, so the reasoning is worth
            // recording: the rule exists because a synthesized shape must not
            // assert geometry the file never gave (the /Rect outline that read as
            // a Square) or hide content already on the page (the redaction fill,
            // the translucent wash). A Text annotation is neither. §12.5.6.4
            // makes it an ICON at /Rect that "shall not scale with the page" —
            // position IS its whole geometry and the file always gives it, and a
            // marker asserts only "a comment is here", which is true. Drawing
            // nothing loses that fact entirely, and nothing else in the page view
            // surfaces it.
            //
            // What was wrong was the execution, in two ways, both fixed here:
            //   * `s` came from the rect WIDTH alone, so a wide, short /Rect got
            //     a marker up to 18pt tall over a rect a fraction of that,
            //     spilling onto the text below;
            //   * an unconditional hard-black ring, which is the highest-contrast
            //     mark available and read as an authored black box rather than
            //     viewer chrome.
            // The 12pt floor on each axis stays: §12.5.6.4's icon "shall not
            // scale with the page", and producers do write a zero-size /Rect and
            // rely on the viewer's fixed icon size, so clamping strictly to a
            // degenerate rect would make those notes vanish.
            //
            // Table 172's /Name (Comment, Key, Note, Help, ...) is still ignored,
            // deliberately: we ship none of that artwork, and inventing a
            // distinct crude glyph per name IS the failure mode the rule is
            // about. One neutral marker says only what we actually know.
            // §7.9.5 lets a /Rect be given by any two diagonally opposite
            // corners and `read_rect` does not reorder them, so anchor on the
            // NORMALIZED rect as the Caret and Stamp arms do. Anchoring on the
            // raw one put the marker below an inverted rect's real box, over
            // unrelated page content — the sizing already used `.abs()`, so only
            // the anchor was missing the same treatment.
            let r = normalize_rect(rect);
            let s = 18.0_f64
                .min((r[2] - r[0]).max(12.0))
                .min((r[3] - r[1]).max(12.0));
            let (x0, y1) = (r[0], r[3]);
            let poly = vec![dev(x0, y1 - s), dev(x0 + s, y1 - s), dev(x0 + s, y1), dev(x0, y1)];
            let col = stroke.or(fill).unwrap_or(0xFFFF_E000); // note yellow
            emit_fill(prims, std::slice::from_ref(&poly), apply_alpha_to_argb(col, ca), false, 1.0, BlendMode::Normal);
        }
        b"Redact" => {
            // §12.5.6.24: a redaction is not applied until apply_redactions runs,
            // so before that the annotation only MARKS the region. Outline it in
            // /C (default red) and draw no interior at all.
            //
            // /IC is deliberately NOT painted here. Table 187 defines it as "the
            // interior colour with which to fill the redacted region AFTER the
            // affected content has been removed" — it is the post-application
            // art, alongside /RO and /OverlayText. Filling with it while the
            // content is still there hides the very text the user has to read to
            // check the mark, which is the same "wash over the page" failure the
            // rest of this function exists to avoid.
            // The ring is inset by half the border width for the same §8.4.3.2
            // reason as Square: a path on /Rect leaves half the pen outside the
            // annotation. Table 187 defines no /RD for Redact, so there is no
            // author-supplied inset to honour here.
            let r = border_inset_rect(rect, bw);
            let poly = vec![dev(r[0],r[1]), dev(r[2],r[1]), dev(r[2],r[3]), dev(r[0],r[3])];
            let mut ring = poly.clone(); ring.push(poly[0]);
            let mut sgs = gs.clone();
            sgs.stroke = stroke.unwrap_or(0xFFFF_0000);
            let d = annot_border_dash(doc, dict);
            if !d.is_empty() { sgs.dash = d; }
            emit_stroke(prims, std::slice::from_ref(&ring), &sgs);
        }
        b"Widget" => {
            // A form field with no usable /AP: §12.7.2 Table 218 makes /V and
            // /DA sufficient to rebuild the appearance, and without doing so a
            // form the user filled in — or received filled in — renders
            // completely blank. Unlike the shape types above this asserts no
            // geometry the file did not give: the value is the file's own.
            render_widget_value(doc, dict, rect, base, prims);
        }
        _ => {}
    }
}

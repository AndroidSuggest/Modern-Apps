/// Render a Type 3 text run by interpreting each glyph's CharProc content stream
/// into the current graphics state. Returns the total text-space advance.
#[allow(clippy::too_many_arguments)]
fn show_string_type3(
    doc: &Document,
    prims: &mut Vec<Prim>,
    gs: &GraphicsState,
    fi: &FontInfo,
    t3: &Type3Font,
    text_matrix: &Mat,
    bytes: &[u8],
    depth: u32,
    ambient_resources: Option<&lopdf::Dictionary>,
) -> f64 {
    let tfs = gs.font_size;
    let th = gs.h_scale;
    let drawable = gs.render_mode != 3 && gs.render_mode != 7;
    // Which modes need the non-painting `Prim::Text` record, independently of
    // whether the CharProc also runs (§9.3.6 Table 106):
    //   3     — invisible, but a scan's OCR layer: needed for the search index.
    //   7     — clip only: no ink, but the clip still has to be built.
    //   4..=6 — paint AND clip: the CharProc supplies the ink, this record is the
    //           only thing the consumer can accumulate an outline from.
    // Mode 7 used to match neither branch and emit nothing at all, and 4-6 emitted
    // the CharProc alone. The consumer accumulates text clips ONLY from Text prims
    // with render_mode 4..7, so a Type 3 run contributed nothing to the clip in any
    // mode — art clipped to Type 3 letterforms painted as a full rectangle, and
    // once an empty accumulation correctly clips to EMPTY (§9.4.3) it would instead
    // blank the page. `argb: 0` keeps 4-6 from double-painting the CharProc's ink
    // with substitute-face glyphs; the consumer skips painting 3 and 7 outright.
    //
    // The outline the consumer derives is a SUBSTITUTE-face glyph, not the Type 3
    // procedure, so the clip shape is approximate. That is a known and much smaller
    // error than clipping to nothing: a Type 3 CharProc is arbitrary drawing
    // operators, and recovering a true outline from it needs a path-accumulating
    // interpreter mode that does not exist here.
    let needs_text_record = gs.render_mode == 3 || (4..=7).contains(&gs.render_mode);
    let trm = mat_mul(text_matrix, &gs.ctm);
    let x_scale = (trm[0] * trm[0] + trm[1] * trm[1]).sqrt();
    let y_scale = (trm[2] * trm[2] + trm[3] * trm[3]).sqrt();
    // See `show_string_in`: Th alone under-describes an anisotropic matrix to the
    // substitute face, is exactly right for every isotropic one, and is bounded on
    // the serialized f32 so neither a degenerate matrix nor an overflowing cast can
    // put a non-finite scale on the wire (NaN survives the consumer's clamp).
    let aniso = if y_scale > 1e-9 { x_scale / y_scale } else { 1.0 };
    let wire_h_scale = th * if aniso.is_finite() { aniso.clamp(0.01, 100.0) } else { 1.0 };
    let wire_h_scale = {
        let v = wire_h_scale as f32;
        if v.is_finite() {
            v
        } else {
            1.0
        }
    };
    let size = finite_or_zero((tfs * y_scale) as f32);
    let mut pen = 0.0_f64;
    let mut glyphs = 0usize;

    fi.for_each_code(bytes, |code, is_space| {
        let advance = fi.width(code) * tfs + gs.char_spacing + if is_space { gs.word_spacing } else { 0.0 };
        let advance = advance * th;
        if drawable
            && glyphs < MAX_TYPE3_GLYPHS
            && depth < MAX_TYPE3_DEPTH
            && prims.len() < MAX_PRIMITIVES
        {
            if let Some(&proc_id) = t3.char_procs.get(&code) {
                if let Ok(Object::Stream(s)) = doc.get_object(proc_id) {
                    let glyph_ops = crate::content::stream_operations(doc, s);
                    if !glyph_ops.is_empty() {
                        // Glyph space -> device: FontMatrix · [Tfs·Th,0,0,Tfs,0,0]
                        // · translate(pen, rise) · Tm · CTM.
                        let scale_m: Mat = [tfs * th, 0.0, 0.0, tfs, 0.0, 0.0];
                        let place = translate(pen, gs.rise);
                        let m1 = mat_mul(&scale_m, &mat_mul(&place, &mat_mul(text_matrix, &gs.ctm)));
                        let glyph_ctm = mat_mul(&t3.font_matrix, &m1);
                        let mut glyph_gs = gs.clone();
                        glyph_gs.ctm = glyph_ctm;
                        let before = prims.len();
                        interpret_content(
                            doc,
                            &glyph_ops,
                            // §9.6.5: the Type 3 `/Resources` entry is optional, and
                            // "if any glyph descriptions refer to named resources but
                            // this dictionary is absent, the names shall be looked up
                            // in the resource dictionary of the page on which the font
                            // is used". Passing `None` instead made a CharProc that
                            // does `/Im0 Do`, `/GS0 gs` or `/Sh0 sh` against the page's
                            // resources draw nothing at all — common in TeX/dvips
                            // output, which routinely omits the font's own /Resources.
                            t3.resources.as_ref().or(ambient_resources),
                            glyph_gs,
                            prims,
                            depth + 1,
                            false,
                        );
                        // Bound the per-glyph primitive count without breaking the
                        // renderer's save/restore stack. A CharProc may wrap all its
                        // content in `q … W n … Q` or a form XObject, so the capped
                        // range can hold ClipPush/GroupPush with their pops beyond the
                        // cap: cutting blind severs them and everything after this
                        // glyph on the page stays clipped. Cutting back to the last
                        // balanced point is safe but drops the whole glyph whenever the
                        // content sits inside one bracket, which is the common shape.
                        // So cut AT the cap and append the closers the cut orphaned.
                        let cap = before + MAX_TYPE3_PRIMS_PER_GLYPH;
                        if prims.len() > cap {
                            // A soft-mask bracket is the one kind that cannot be
                            // closed after the fact: per `model.rs` its mask is what
                            // FOLLOWS `SoftMaskContent`, so appending a bare
                            // `SoftMaskPop` leaves an empty mask, which hides the very
                            // content the mask exists to reveal. Locate the outermost
                            // one still open at the cap.
                            let mut mask_depth = 0i32;
                            let mut mask_start: Option<usize> = None;
                            for (i, p) in prims[before..cap].iter().enumerate() {
                                match p {
                                    Prim::SoftMaskPush { .. } => {
                                        if mask_depth == 0 { mask_start = Some(before + i); }
                                        mask_depth += 1;
                                    }
                                    Prim::SoftMaskPop => {
                                        mask_depth -= 1;
                                        if mask_depth == 0 { mask_start = None; }
                                    }
                                    _ => {}
                                }
                            }
                            let mut cut = cap;
                            let mut closed = false;
                            if let Some(start) = mask_start {
                                // Locate this bracket's `SoftMaskContent` separator and
                                // its matching `SoftMaskPop`.
                                let mut d = 0i32;
                                let mut content_idx = None;
                                let mut pop_idx = None;
                                for (i, p) in prims[start..].iter().enumerate() {
                                    match p {
                                        Prim::SoftMaskPush { .. } => d += 1,
                                        Prim::SoftMaskContent => {
                                            if d == 1 && content_idx.is_none() {
                                                content_idx = Some(start + i);
                                            }
                                        }
                                        Prim::SoftMaskPop => {
                                            d -= 1;
                                            if d == 0 { pop_idx = Some(start + i); break; }
                                        }
                                        _ => {}
                                    }
                                }
                                match (content_idx, pop_idx) {
                                    // The cap falls in the MASKED CONTENT. Drop the
                                    // surplus content but move the mask back on, so the
                                    // bracket stays whole and keeps its real mask.
                                    // Cutting before the push instead would discard the
                                    // whole glyph, because consecutive paints under one
                                    // mask are coalesced into a SINGLE bracket whose
                                    // push sits at the very start.
                                    //
                                    // `ci >= cap` is load-bearing, not a case split:
                                    // draining a range that began BEFORE `cap` would
                                    // shift later indices down, and the `truncate(cap)`
                                    // below would then keep content that was originally
                                    // past the cap.
                                    (Some(ci), Some(pi)) if ci >= cap => {
                                        let tail: Vec<Prim> = prims.drain(ci..=pi).collect();
                                        prims.truncate(cap);
                                        // Nesting order matters. A bracket opened INSIDE
                                        // the masked content has to close before
                                        // `SoftMaskContent`; one opened outside closes
                                        // after `SoftMaskPop`. Emitting both at the end
                                        // would cross them, leaving the renderer to
                                        // restore across a saveLayer boundary.
                                        let inner = open_brackets(&prims[start..]);
                                        push_closers(prims, inner);
                                        prims.extend(tail);
                                        let outer = open_brackets(&prims[before..start]);
                                        push_closers(prims, outer);
                                        closed = true;
                                        cut = prims.len();
                                    }
                                    // The cap falls inside the MASK itself, which is not
                                    // separable: complete the bracket instead. Bounded
                                    // by the mask group's own primitive count.
                                    (Some(_), Some(pi)) => cut = pi + 1,
                                    // No closing pop at all: drop the masked run rather
                                    // than emit a bracket whose mask never arrives.
                                    _ => cut = start,
                                }
                            }
                            prims.truncate(cut);
                            if !closed {
                                let open = open_brackets(&prims[before..]);
                                push_closers(prims, open);
                            }
                        }
                        glyphs += 1;
                    }
                }
            }
        }
        // Deliberately a separate `if`, not an `else`: modes 4-6 need BOTH the
        // CharProc's ink and this record. It also now fires for a glyph the caps
        // above rejected, so a capped Type 3 run stays searchable instead of
        // vanishing from the index entirely.
        if needs_text_record && prims.len() < MAX_PRIMITIVES {
            let mut s = String::new();
            fi.push_code(code, &mut s);
            if !s.is_empty() {
                let (x, y) = transform(&trm, pen, gs.rise);
                prims.push(Prim::Text {
                    x: x as f32,
                    y: y as f32,
                    size,
                    argb: 0,
                    text: s,
                    advance: finite_or_zero((fi.width(code) * tfs * th * x_scale) as f32)
                        .max(size * 0.1),
                    stroke_argb: None,
                    stroke_width: None,
                    render_mode: gs.render_mode as u8,
                    blend: gs.blend_mode,
                    is_bold: false,
                    is_italic: false,
                    font_family: 0,
                    outline: false,
                    h_scale: wire_h_scale,
                });
            }
        }
        pen += advance;
    });
    pen
}

// ---------------------------------------------------------------------------
// Image XObjects
// ---------------------------------------------------------------------------

#[cfg(test)]
mod type3_resource_fallback_tests {
    use crate::*;
    use lopdf::content::Operation;
    use lopdf::{dictionary, Stream};

    /// §9.6.5: a Type 3 font's `/Resources` is optional, and "if any glyph
    /// descriptions refer to named resources but this dictionary is absent, the
    /// names shall be looked up in the resource dictionary of the page on which
    /// the font is used". `show_string_type3` passed `t3.resources` straight
    /// through, so with no `/Resources` on the font the CharProc's resource
    /// lookups all missed and the glyph drew NOTHING — not even a fallback shape.
    /// TeX/dvips bitmap-font output routinely omits the entry. Reported by
    /// `a-text2`.
    #[test]
    fn a_type3_charproc_falls_back_to_the_page_resources() {
        let mut doc = Document::with_version("1.7");
        // The CharProc paints through an ExtGState it can only reach via the page.
        let egs = doc.add_object(dictionary! { "ca" => 0.5 });
        let proc_id = doc.add_object(Stream::new(
            dictionary! {},
            b"/GSP gs 0 0 700 700 re f".to_vec(),
        ));
        let font = dictionary! {
            "Type" => "Font", "Subtype" => "Type3",
            "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
            "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
            "CharProcs" => doc.add_object(dictionary! { "a" => proc_id }),
            "Encoding" => doc.add_object(dictionary! {
                "Type" => "Encoding", "Differences" => vec![65.into(), "a".into()],
            }),
            "FirstChar" => 65, "LastChar" => 65, "Widths" => vec![700.into()],
            // Deliberately NO /Resources on the font: the whole point of the test.
        };
        let font_id = doc.add_object(font.clone());
        let res = dictionary! {
            "Font" => dictionary! { "F1" => Object::Reference(font_id) },
            "ExtGState" => dictionary! { "GSP" => Object::Reference(egs) },
        };
        let ops = vec![
            Operation::new("BT", vec![]),
            Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 100.into()]),
            Operation::new("Tj", vec![Object::string_literal("A")]),
            Operation::new("ET", vec![]),
        ];
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);

        let alpha = prims
            .iter()
            .find_map(|p| match p {
                Prim::Fill { argb, .. } => Some((argb >> 24) as u8),
                _ => None,
            })
            .expect("the Type 3 glyph must paint");
        assert_eq!(
            alpha, 0x80,
            "the CharProc's /GSP must resolve through the page resources"
        );
    }
}

#[cfg(test)]
mod degenerate_stroke_tests {
    use crate::*;

    fn stroke(subpaths: &[Vec<(f64, f64)>], cap: u8) -> Vec<Prim> {
        let gs = GraphicsState { line_cap: cap, line_width: 4.0, ..Default::default() };
        let mut prims = Vec::new();
        emit_stroke(&mut prims, subpaths, &gs);
        prims
    }

    /// §8.5.3.2: "If a subpath is degenerate (consists of a single-point closed
    /// subpath or of two or more points at the same coordinates), `S` shall paint
    /// it only if round line caps have been specified, producing a filled circle
    /// centred at the single point. If butt or projecting square line caps have
    /// been specified, `S` shall paint nothing."
    ///
    /// A single-point subpath — what `x y m S` and `x y m h S` produce — was
    /// dropped before it reached the renderer, so the round-cap dot idiom used for
    /// stipple patterns, leader-line dots and map symbols painted nothing at all.
    #[test]
    fn a_degenerate_subpath_paints_a_dot_only_with_round_caps() {
        for sp in [vec![(5.0, 7.0)], vec![(5.0, 7.0), (5.0, 7.0)]] {
            let round = stroke(std::slice::from_ref(&sp), 1);
            let contours = round
                .iter()
                .find_map(|p| match p {
                    Prim::Fill { contours, .. } => Some(contours.clone()),
                    _ => None,
                })
                .unwrap_or_else(|| panic!("round caps must paint a dot for {sp:?}"));
            assert_eq!(contours.len(), 1);
            // Centred on the point, radius = half the (device) line width.
            for &(x, y) in &contours[0] {
                let r = ((x as f64 - 5.0).hypot(y as f64 - 7.0) - 2.0).abs();
                assert!(r < 1e-3, "dot vertex ({x}, {y}) is not on the r=2 circle");
            }

            for butt_or_square in [0u8, 2] {
                let out = stroke(std::slice::from_ref(&sp), butt_or_square);
                assert!(
                    out.is_empty(),
                    "cap {butt_or_square} must paint nothing for a degenerate \
                     subpath, got {} prims",
                    out.len()
                );
            }
        }
    }

    /// The dot path must not swallow real strokes: a subpath with distinct points
    /// still strokes, round caps or not.
    #[test]
    fn a_non_degenerate_subpath_still_strokes() {
        let sp = vec![vec![(0.0, 0.0), (10.0, 0.0)]];
        for cap in [0u8, 1, 2] {
            let out = stroke(&sp, cap);
            assert_eq!(
                out.iter().filter(|p| matches!(p, Prim::Stroke { .. })).count(),
                1,
                "cap {cap}"
            );
            assert!(!out.iter().any(|p| matches!(p, Prim::Fill { .. })));
        }
    }

    /// `emit_stroke` emits one primitive PER SUBPATH, and every caller checks the
    /// cap once BEFORE the call (`interpret.rs`: `else if prims.len() <
    /// MAX_PRIMITIVES { emit_stroke(…) }`), so the loop has to bound itself or a
    /// single `S` on a MAX_SUBPATHS-subpath path walks straight past the ceiling.
    /// `MAX_PRIMITIVES` is the process's memory guard against an uncatchable Rust
    /// OOM and its doc states it is enforced at every content-emitting push.
    #[test]
    fn emit_stroke_stops_at_the_primitive_cap() {
        let gs = GraphicsState { line_width: 1.0, ..Default::default() };
        let subpaths: Vec<Vec<(f64, f64)>> =
            (0..64).map(|i| vec![(i as f64, 0.0), (i as f64, 10.0)]).collect();
        let mut prims: Vec<Prim> = Vec::new();
        // Start just under the cap, as the caller's own check guarantees. Built by
        // `extend` rather than `resize` on purpose: `Vec::resize` needs `T: Clone`,
        // and `Prim` must not be cloneable-by-habit — `Image`/`ImageTiled` carry the
        // whole decoded payload. Nothing here should force a derive on model.rs.
        prims.extend((0..MAX_PRIMITIVES - 4).map(|_| Prim::ClipPop));
        emit_stroke(&mut prims, &subpaths, &gs);
        assert_eq!(
            prims.len(),
            MAX_PRIMITIVES,
            "the cap must bound the per-subpath loop, not just its entry"
        );
    }
}

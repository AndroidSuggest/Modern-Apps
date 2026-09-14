/// Emit one text primitive per glyph, each positioned at its exact device-space
/// origin computed from the PDF glyph widths + text state. Drawing glyph-by-glyph
/// (rather than one run) keeps kerned/justified text aligned even though a
/// substitute system font renders the glyph shapes. Returns the total advance in
/// text space so the caller can step the text matrix.
///
/// `ambient_resources` is the resource dictionary in force at the showing
/// operator. Only Type 3 needs it: §9.6.5 makes a Type 3 font's own `/Resources`
/// optional and says the names "shall be looked up in the resource dictionary of
/// the page on which the font is used" when it is absent.
#[allow(clippy::too_many_arguments)]
pub(crate) fn show_string_in(
    doc: &Document,
    prims: &mut Vec<Prim>,
    gs: &GraphicsState,
    fonts: &HashMap<Vec<u8>, FontInfo>,
    text_matrix: &Mat,
    bytes: &[u8],
    depth: u32,
    ambient_resources: Option<&lopdf::Dictionary>,
) -> f64 {
    let tfs = gs.font_size;
    let th = gs.h_scale;
    let trm = mat_mul(text_matrix, &gs.ctm);
    let y_scale = (trm[2] * trm[2] + trm[3] * trm[3]).sqrt();
    // Device-space horizontal scale, used to convert glyph advances (user space)
    // into the device advance carried on the wire for text selection/search.
    let x_scale = (trm[0] * trm[0] + trm[1] * trm[1]).sqrt();
    // Horizontal scale to put on the wire for the SUBSTITUTE face. Kotlin derives
    // the glyph's em from `size` (= Tfs·y_scale) and multiplies its width by
    // `h_scale`, so Th alone under-describes an anisotropic matrix: with
    // x_scale != y_scale the outline path draws the glyph x_scale/y_scale wider
    // than the substitute path does, and the two disagree about the SIZE of the
    // same glyph. Folding the ratio in is exactly 1.0 for every isotropic matrix
    // — including every pure rotation — so it changes nothing on ordinary pages.
    // This is the only part of the mismatch expressible in the v8 wire; the glyph
    // being axis-aligned under a rotated matrix needs a field that does not exist.
    //
    // Bounded here rather than at the consumer. A near-degenerate matrix makes the
    // ratio enormous, and `th` is `Tz/100` whose product can overflow; the check is
    // on the f32 that is actually serialized, because `f64 as f32` saturates to inf
    // above ~3.4e38 and a finite f64 would otherwise sail through.
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
    let size = (tfs * y_scale) as f32;
    // `Tf`'s operand and the CTM are file input, and `size` is their product, so a
    // non-finite value is reachable even with both validated — an f64 above the f32
    // range saturates to inf on the cast. NaN then survives Kotlin's
    // `coerceAtLeast` (a comparison) into `Paint.textSize` and the glyph vanishes.
    // Zero is the honest substitute: the consumer floors it to one pixel.
    let size = finite_or_zero(size);
    // Modes 3 (invisible) and 7 (clip only) advance the pen but paint nothing.
    let drawable = gs.render_mode != 3 && gs.render_mode != 7;
    // Mode 3 is how a scan carries its OCR layer: nothing is painted, but the run
    // must still reach the text index or the document is unsearchable. `argb: 0`
    // here plus the `rm != 3` paint guard in SafePdfViewerScreen.kt keep it unseen.
    let invisible = gs.render_mode == 3;
    let clip_only = gs.render_mode == 7;

    let fi = match fonts.get(&gs.font_key) {
        Some(fi) => fi,
        None => {
            // No font metrics: emit the run at the origin and estimate advance.
            let run_advance = bytes.len() as f64 * 0.5 * tfs * th;
            // Mode 7 is included deliberately. §9.3.6 Table 106 separates 4-6 from 7
            // only by whether ink is laid down; their §9.4.3 clip contribution is
            // identical, and 4-6 already emit here. Excluding 7 made the operator a
            // total no-op whenever the font resource was missing, which also broke the
            // commitment in `hidden_render_mode`: it maps a 4-6 run inside an OFF
            // optional-content group to 7 precisely so the clip still contributes, and
            // that clip then disappeared. This gate and the latch must stay IDENTICAL:
            // `latches_text_clip`'s no-font arm is `!bytes.is_empty()`, the same
            // condition as here, so every clip it latches has a record to accumulate
            // from. The latch does NOT follow prim emission — that predicate was
            // considered and rejected there, and a run whose glyphs all drop still
            // latches and clips to EMPTY. So narrowing this gate alone would not drop
            // the clip, it would apply an empty one and blank the rest of the text
            // object. The outline is a substitute face, so the shape is approximate;
            // that is the same approximation 4-6 already accept.
            if (drawable || invisible || clip_only) && !bytes.is_empty() {
                let (x, y) = transform(&trm, 0.0, gs.rise);
                let text: String =
                    bytes.iter().filter_map(|&b| char::from_u32(b as u32)).collect();
                if !text.is_empty() {
                    prims.push(Prim::Text {
                        x: x as f32,
                        y: y as f32,
                        size,
                        argb: if invisible || clip_only { 0 } else { apply_alpha_to_argb(gs.fill, gs.alpha_fill) },
                        text,
                        stroke_argb: None,
                        stroke_width: None,
                        // The DEVICE advance of the whole run, which is what the
                        // wire contract says this field is and what the selection
                        // layer rescales a non-painted run to. `size` was one
                        // glyph's worth for a run of any length, so a mode-3 OCR
                        // run with no font resource had every selection rectangle
                        // piled onto its first character.
                        advance: finite_or_zero((run_advance * x_scale) as f32).max(size * 0.1),
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
            return run_advance;
        }
    };

    // Type 3 fonts: draw each glyph by interpreting its CharProc content stream.
    if let Some(t3) = &fi.t3 {
        return show_string_type3(doc, prims, gs, fi, t3, text_matrix, bytes, depth, ambient_resources);
    }

    let mut pen = 0.0_f64;
    // §8.4.3.2: the line width is a distance in USER space, so the pen is scaled by
    // the CTM alone. The text matrix and font size scale the GLYPH, not the pen —
    // §9.3.6 does not override this. Scaling by Trm made a `12 0 0 12 ...` Tm (the
    // shape cairo emits) paint a 0.5pt pen as 6pt, closing every counter.
    let ctm = &gs.ctm;
    let sx_ctm = (ctm[0] * ctm[0] + ctm[1] * ctm[1]).sqrt();
    let sy_ctm = (ctm[2] * ctm[2] + ctm[3] * ctm[3]).sqrt();
    let device_stroke_w = (gs.line_width * (sx_ctm + sy_ctm) * 0.5) as f32;
    // Constant per-font attributes hoisted out of the per-glyph closure.
    let bold = fi.style.bold;
    let italic = fi.style.italic;
    let family = fi.family;
    let has_program = fi.glyph_program.is_some();
    // Vertical writing mode (WMode 1): glyphs advance down the page and are
    // positioned by the /W2 //DW2 position vector (PDF 9.4.4).
    let vertical = fi.wmode == 1;

    fi.for_each_code(bytes, |code, is_space| {
        let w0 = fi.width(code); // horizontal glyph width (em)
        let tx = w0 * tfs + gs.char_spacing + if is_space { gs.word_spacing } else { 0.0 };
        let glyph_advance_user = tx * th; // accurate advance using /Widths /W
        // Placement point (text space) and pen advance depend on writing mode.
        // Vertical (PDF 9.4.4, 9.7.4.3): the glyph is offset by the position
        // vector v and the pen advances by w1_y, both from /W2 //DW2 rather than
        // assumed. Tc/Tw keep widening the gap as they do horizontally, which
        // deviates from the literal `ty = w1*Tfs + Tc + Tw` in 9.4.4 (where a
        // positive Tc would tighten negative-w1 vertical text) but matches the
        // behaviour every horizontal path here already has.
        let (place_x, place_y, advance) = if vertical {
            let cid = fi.to_cid(code);
            let (w1y, vx) = fi
                .vertical_metrics
                .get(&cid)
                .copied()
                .unwrap_or((fi.default_vertical.1, 0.5 * w0));
            let vy = fi.default_vertical.0;
            let extra = gs.char_spacing + if is_space { gs.word_spacing } else { 0.0 };
            // Trise is part of §9.4.4's text-space parameter matrix, which does not
            // depend on the writing mode: it displaces the glyph in text-space y in
            // vertical writing exactly as it does in horizontal. Dropping it here put
            // super/subscripts in vertical CJK back on the baseline.
            (-vx * tfs * th, pen - vy * tfs + gs.rise, w1y * tfs - extra)
        } else {
            (pen, gs.rise, glyph_advance_user)
        };
        // Render modes 0-7 (PDF 9.3.6, Table 106) all emit something: 0-2 ink,
        // 4-6 ink plus clip, 7 clip only, and 3 an invisible record that keeps the
        // text selectable and searchable. An out-of-range Tr emits nothing.
        if (0..=7).contains(&gs.render_mode) {
            let (x, y) = transform(&trm, place_x, place_y);
            let mut s = String::new();
            fi.push_code(code, &mut s);
            // `s` is the selection/search payload only. A glyph whose Unicode
            // cannot be recovered (surrogate-range CID, code > 0x10FFFF, or a
            // /ToUnicode entry mapping to an empty string) must still be PAINTED
            // from its embedded outline, so nothing below is gated on `s` except
            // the Prim::Text records that carry the text itself.
            {
                let fill_alpha = gs.alpha_fill;
                let stroke_alpha = gs.alpha_stroke;
                let has_fill = matches!(gs.render_mode, 0|2|4|6);
                let has_stroke = matches!(gs.render_mode, 1|2|5|6);
                let clip_only = gs.render_mode == 7;
                let invisible = gs.render_mode == 3;
                let rm = gs.render_mode as u8;
                let glyph_device_adv = if vertical {
                    (advance.abs() * y_scale) as f32
                } else {
                    (glyph_advance_user * x_scale) as f32
                };
                // Same reasoning as `size`. The selection layer rescales a
                // non-painted run by `advance * scale / measured`, and its
                // `advance > 0f` guard PASSES infinity, so one overflowed glyph
                // would stretch every remaining glyph's rectangle in the run.
                let glyph_device_adv = finite_or_zero(glyph_device_adv);
                // Real embedded outline for every mode that lays down ink: 0-2 and
                // 4-6. Restricting this to 0-2 meant an embedded font in a clip mode
                // was PAINTED with a substitute system face — wrong letterforms,
                // weight and widths — while its own outline sat unused, so the same
                // font rendered correctly at Tr 0 and wrongly at Tr 4.
                //
                // 3 and 7 stay out, and 7 deliberately so: `has_fill` is 0|2|4|6 and
                // `has_stroke` is 1|2|5|6, so mode 7 is in neither and this branch
                // would emit no Fill, no Stroke, and a Text record tagged
                // `outline: true` that the consumer drops before its clip
                // accumulator — no ink AND no clip, which under an unconditional
                // §9.4.3 clip is total content loss after every ET. Mode 7 has no
                // ink to improve, so it keeps the substitute path.
                let outline = if has_program && matches!(gs.render_mode, 0..=2 | 4..=6) {
                    crate::outlines::glyph_outline(fi, code)
                } else {
                    None
                };
                if let Some((contours, upm)) = outline {
                    // Glyph space (font units) -> device: (1/upm) · [Tfs·Th,0,0,Tfs] ·
                    // translate(pen, rise) · Tm · CTM. Mirrors the Type 3 pipeline.
                    let font_matrix: Mat = [1.0 / upm, 0.0, 0.0, 1.0 / upm, 0.0, 0.0];
                    let scale_m: Mat = [tfs * th, 0.0, 0.0, tfs, 0.0, 0.0];
                    let place = translate(place_x, place_y);
                    let m1 = mat_mul(&scale_m, &mat_mul(&place, &trm));
                    let glyph_ctm = mat_mul(&font_matrix, &m1);
                    let dev: Vec<Vec<(f32, f32)>> = contours
                        .iter()
                        .map(|c| {
                            c.iter()
                                .map(|&(gx, gy)| {
                                    let (dx, dy) = transform(&glyph_ctm, gx, gy);
                                    (dx as f32, dy as f32)
                                })
                                .collect()
                        })
                        .collect();
                    if prims.len() < MAX_PRIMITIVES {
                        if has_fill {
                            prims.push(Prim::Fill {
                                argb: apply_alpha_to_argb(gs.fill, fill_alpha),
                                even_odd: false,
                                contours: dev.clone(),
                                blend: gs.blend_mode,
                            });
                        }
                        if has_stroke {
                            let sargb = apply_alpha_to_argb(gs.stroke, stroke_alpha);
                            for c in &dev {
                                if c.len() >= 2 {
                                    prims.push(Prim::Stroke {
                                        argb: sargb,
                                        width: device_stroke_w.max(0.1),
                                        dash: Vec::new(),
                                        dash_phase: 0.0,
                                        cap: gs.line_cap,
                                        join: gs.line_join,
                                        miter: gs.miter_limit as f32,
                                        pts: c.clone(),
                                        blend: gs.blend_mode,
                                    });
                                }
                            }
                        }
                        // Non-painting Text carrying the glyph for selection/search.
                        // Skipped when no Unicode was recoverable — the ink above is
                        // already on the page either way.
                        //
                        // The two flags must DISAGREE between the mode groups, and
                        // they are the same two fields, so this branches rather than
                        // mirroring modes 0-2:
                        //   0-2: `outline: true` — the consumer skips the record
                        //        entirely, which is right, the Fill/Stroke above is
                        //        the ink and there is no clip to build.
                        //   4-6: `outline: false` so the record REACHES the clip
                        //        accumulator (the consumer's `if prim.outline
                        //        continue` sits above it), and `argb: 0` so it still
                        //        paints nothing on top of the real contours above.
                        //        The accumulator keys on render mode and text only —
                        //        it never reads colour — so transparency costs the
                        //        clip nothing. Same shape as the Type 3 record.
                        if !s.is_empty() {
                            let clip_mode = matches!(gs.render_mode, 4..=6);
                            prims.push(Prim::Text {
                                x: x as f32,
                                y: y as f32,
                                size,
                                argb: if clip_mode {
                                    0
                                } else {
                                    apply_alpha_to_argb(gs.fill, fill_alpha)
                                },
                                text: s.clone(),
                                advance: glyph_device_adv.max(size * 0.1),
                                stroke_argb: None,
                                stroke_width: None,
                                render_mode: rm,
                                blend: gs.blend_mode,
                                is_bold: bold,
                                is_italic: italic,
                                font_family: family,
                                outline: !clip_mode,
                                h_scale: wire_h_scale,
                            });
                        }
                    }
                } else if !s.is_empty() && prims.len() < MAX_PRIMITIVES {
                    // Substitute-font path (no embedded program or glyph missing).
                    // Requires a string: there is no outline to fall back on, so a
                    // glyph with no recoverable Unicode simply cannot be drawn here.
                    if has_fill {
                        prims.push(Prim::Text {
                            x: x as f32,
                            y: y as f32,
                            size,
                            argb: apply_alpha_to_argb(gs.fill, fill_alpha),
                            text: s.clone(),
                            advance: glyph_device_adv.max(size * 0.1),
                            stroke_argb: if has_stroke { Some(apply_alpha_to_argb(gs.stroke, stroke_alpha)) } else { None },
                            stroke_width: if has_stroke { Some(device_stroke_w) } else { None },
                            render_mode: rm,
                            blend: gs.blend_mode,
                            is_bold: bold,
                            is_italic: italic,
                            font_family: family,
                            outline: false,
                            h_scale: wire_h_scale,
                        });
                    } else if has_stroke {
                        prims.push(Prim::Text {
                            x: x as f32,
                            y: y as f32,
                            size,
                            argb: apply_alpha_to_argb(gs.stroke, stroke_alpha), // stroke-only: use stroke color as fill for visibility (Kotlin draws stroke)
                            text: s.clone(),
                            advance: glyph_device_adv.max(size * 0.1),
                            stroke_argb: Some(apply_alpha_to_argb(gs.stroke, stroke_alpha)),
                            stroke_width: Some(device_stroke_w),
                            render_mode: rm,
                            blend: gs.blend_mode,
                            is_bold: bold,
                            is_italic: italic,
                            font_family: family,
                            outline: false,
                            h_scale: wire_h_scale,
                        });
                    } else if clip_only || invisible {
                        // Mode 7: no paint, but carry the glyph so Kotlin can add
                        // its outline to the clip at the text-clip-apply marker.
                        // Mode 3: paints nothing at all, but the glyph must still
                        // reach the text index -- a scanned page's OCR layer is
                        // drawn in mode 3, and dropping it is why such documents
                        // had no selectable or searchable text. Kotlin skips
                        // painting both modes.
                        prims.push(Prim::Text {
                            x: x as f32,
                            y: y as f32,
                            size,
                            argb: 0,
                            text: s.clone(),
                            advance: glyph_device_adv.max(size * 0.1),
                            stroke_argb: None,
                            stroke_width: None,
                            render_mode: rm,
                            blend: gs.blend_mode,
                            is_bold: bold,
                            is_italic: italic,
                            font_family: family,
                            outline: false,
                            h_scale: wire_h_scale,
                        });
                    }
                }
            }
        }
        pen += advance;
    });
    // No `prims.truncate(MAX_PRIMITIVES)` here. MAX_PRIMITIVES bounds PAINT
    // primitives, and every push above is already gated on it, so a truncate can
    // only ever fire on primitives this call did not emit — cutting the tail off
    // a caller's ClipPush/ClipPop or SoftMaskPush/Pop bracket and leaving the
    // renderer's stack unbalanced for the rest of the page. Structural prims are
    // bounded transitively instead: each needs its own operator, and the operator
    // loop is capped at MAX_CONTENT_OPS with nesting capped at MAX_CLIP_DEPTH.
    pen
}

/// Clip and transparency-group brackets left open in `slice`, outermost first.
/// `true` = clip, `false` = group. Soft-mask prims are ignored: a soft mask is
/// not closable by appending a pop (see the per-glyph cap in `show_string_type3`).
fn open_brackets(slice: &[Prim]) -> Vec<bool> {
    let mut open: Vec<bool> = Vec::new();
    for p in slice {
        match p {
            Prim::ClipPush { .. } => open.push(true),
            Prim::GroupPush { .. } => open.push(false),
            Prim::ClipPop | Prim::GroupPop => {
                open.pop();
            }
            _ => {}
        }
    }
    open
}

/// Append the matching pops for `open`, innermost first.
fn push_closers(prims: &mut Vec<Prim>, mut open: Vec<bool>) {
    while let Some(is_clip) = open.pop() {
        prims.push(if is_clip { Prim::ClipPop } else { Prim::GroupPop });
    }
}

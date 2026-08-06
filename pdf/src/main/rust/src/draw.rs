use crate::*;

pub(crate) fn cubic_bezier(
    p0: (f64, f64),
    p1: (f64, f64),
    p2: (f64, f64),
    p3: (f64, f64),
    t: f64,
) -> (f64, f64) {
    let u = 1.0 - t;
    let w0 = u * u * u;
    let w1 = 3.0 * u * u * t;
    let w2 = 3.0 * u * t * t;
    let w3 = t * t * t;
    (
        w0 * p0.0 + w1 * p1.0 + w2 * p2.0 + w3 * p3.0,
        w0 * p0.1 + w1 * p1.1 + w2 * p2.1 + w3 * p3.1,
    )
}

// old emit_fill removed - replaced by alpha-aware version


pub(crate) fn emit_stroke(prims: &mut Vec<Prim>, subpaths: &[Vec<(f64, f64)>], gs: &GraphicsState) {
    // Device-space scale via CTM
    let ctm = &gs.ctm;
    let sx = (ctm[0] * ctm[0] + ctm[1] * ctm[1]).sqrt();
    let sy = (ctm[2] * ctm[2] + ctm[3] * ctm[3]).sqrt();
    let scale = (sx + sy) / 2.0;
    let width = (gs.line_width * scale) as f32;
    // Single-element dash is valid (odd -> duplicate) per PDF spec — fix #19
    let mut dash: Vec<f32> = gs.dash.iter().map(|d| (d * scale) as f32).filter(|d| *d >= 0.0).collect();
    if dash.len() == 1 && dash[0] > 0.0 {
        dash.push(dash[0]);
    } else if dash.len() % 2 == 1 && dash.len() > 1 {
        let cl = dash.clone();
        dash.extend(cl);
    }
    let dash = if dash.len() >= 2 && dash.iter().sum::<f32>() > 0.0 { dash } else { Vec::new() };
    let dash_phase = (gs.dash_phase * scale) as f32;
    let argb = apply_alpha_to_argb(gs.stroke, gs.alpha_stroke);
    // Overprint (stroking) is approximated as Multiply on an RGB compositor:
    // white/zero-ink channels leave the backdrop unchanged, matching overprint's
    // intent for the common case. Only applied when no explicit blend is set.
    let blend = if gs.overprint_stroke && gs.blend_mode == BlendMode::Normal {
        BlendMode::Multiply
    } else {
        gs.blend_mode
    };
    for sp in subpaths {
        if sp.len() >= 2 {
            prims.push(Prim::Stroke {
                argb,
                width: width.max(0.1),
                dash: dash.clone(),
                dash_phase,
                cap: gs.line_cap,
                join: gs.line_join,
                miter: gs.miter_limit as f32,
                pts: sp.iter().map(|&(x, y)| (x as f32, y as f32)).collect(),
                blend,
            });
        }
    }
}

pub(crate) fn emit_fill(prims: &mut Vec<Prim>, subpaths: &[Vec<(f64, f64)>], argb: u32, even_odd: bool, alpha_fill: f64, blend: BlendMode) {
    let argb = apply_alpha_to_argb(argb, alpha_fill);
    // All subpaths of the path form ONE fill region so interior contours (glyph
    // counters / holes) are cut out by the winding rule, instead of being filled
    // in as separate solid polygons.
    let contours: Vec<Vec<(f32, f32)>> = subpaths
        .iter()
        .filter(|sp| sp.len() >= 3)
        .map(|sp| sp.iter().map(|&(x, y)| (x as f32, y as f32)).collect())
        .collect();
    if !contours.is_empty() {
        prims.push(Prim::Fill { argb, even_odd, contours, blend });
    }
}

/// Emit a text primitive for `bytes` at the current text matrix (unless the
/// render mode is invisible/clip-only) and return the horizontal advance in
/// user-space units so the caller can step the text matrix.
/// Emit one text primitive per glyph, each positioned at its exact device-space
/// origin computed from the PDF glyph widths + text state. Drawing glyph-by-glyph
/// (rather than one run) keeps kerned/justified text aligned even though a
/// substitute system font renders the glyph shapes. Returns the total advance in
/// text space so the caller can step the text matrix.
pub(crate) fn show_string(
    doc: &Document,
    prims: &mut Vec<Prim>,
    gs: &GraphicsState,
    fonts: &HashMap<Vec<u8>, FontInfo>,
    text_matrix: &Mat,
    bytes: &[u8],
    depth: u32,
) -> f64 {
    let tfs = gs.font_size;
    let th = gs.h_scale;
    let trm = mat_mul(text_matrix, &gs.ctm);
    let y_scale = (trm[2] * trm[2] + trm[3] * trm[3]).sqrt();
    // Device-space horizontal scale, used to convert glyph advances (user space)
    // into the device advance carried on the wire for text selection/search.
    let x_scale = (trm[0] * trm[0] + trm[1] * trm[1]).sqrt();
    let size = (tfs * y_scale) as f32;
    // Modes 3 (invisible) and 7 (clip only) advance the pen but paint nothing.
    let drawable = gs.render_mode != 3 && gs.render_mode != 7;

    let fi = match fonts.get(&gs.font_key) {
        Some(fi) => fi,
        None => {
            // No font metrics: emit the run at the origin and estimate advance.
            if drawable && !bytes.is_empty() {
                let (x, y) = transform(&trm, 0.0, gs.rise);
                let text: String =
                    bytes.iter().filter_map(|&b| char::from_u32(b as u32)).collect();
                if !text.is_empty() {
                    prims.push(Prim::Text {
                        x: x as f32,
                        y: y as f32,
                        size,
                        argb: apply_alpha_to_argb(gs.fill, gs.alpha_fill),
                        text,
                        stroke_argb: None,
                        stroke_width: None,
                        advance: size,
                        render_mode: gs.render_mode as u8,
                        blend: gs.blend_mode,
                        is_bold: false,
                        is_italic: false,
                        font_family: 0,
                        outline: false,
                        h_scale: th as f32,
                    });
                }
            }
            return bytes.len() as f64 * 0.5 * tfs * th;
        }
    };

    // Type 3 fonts: draw each glyph by interpreting its CharProc content stream.
    if let Some(t3) = &fi.t3 {
        return show_string_type3(doc, prims, gs, fi, t3, text_matrix, bytes, depth);
    }

    let mut pen = 0.0_f64;
    // Fix high #12: device stroke width should use Trm scale (includes Tm·Tfs·Th), not just CTM
    let trm = mat_mul(text_matrix, &gs.ctm);
    let sx_trm = (trm[0] * trm[0] + trm[1] * trm[1]).sqrt();
    let sy_trm = (trm[2] * trm[2] + trm[3] * trm[3]).sqrt();
    let avg_trm_scale = (sx_trm + sy_trm) * 0.5;
    let device_stroke_w = (gs.line_width * avg_trm_scale) as f32;
    // Constant per-font attributes hoisted out of the per-glyph closure.
    let bold = fi.style.bold;
    let italic = fi.style.italic;
    let family = fi.family;
    let has_program = fi.glyph_program.is_some();
    // Vertical writing mode (WMode 1): glyphs advance down the page and are
    // centered on the vertical line (PDF 9.4.4). Metrics use typical CJK values.
    let vertical = fi.wmode == 1;

    fi.for_each_code(bytes, |code, is_space| {
        let w0 = fi.width(code); // horizontal glyph width (em)
        let tx = w0 * tfs + gs.char_spacing + if is_space { gs.word_spacing } else { 0.0 };
        let glyph_advance_user = tx * th; // accurate advance using /Widths /W
        // Placement point (text space) and pen advance depend on writing mode.
        // Vertical: center horizontally (-w0/2), drop by the vertical origin
        // (~0.88 em), and advance one em downward (plus Tc/Tw).
        let (place_x, place_y, advance) = if vertical {
            let adv_down = tfs + gs.char_spacing + if is_space { gs.word_spacing } else { 0.0 };
            (-0.5 * w0 * tfs, pen - 0.880 * tfs, -adv_down)
        } else {
            (pen, gs.rise, glyph_advance_user)
        };
        if gs.render_mode != 3 {
            let (x, y) = transform(&trm, place_x, place_y);
            let mut s = String::new();
            fi.push_code(code, &mut s);
            if !s.is_empty() {
                let fill_alpha = gs.alpha_fill;
                let stroke_alpha = gs.alpha_stroke;
                let has_fill = matches!(gs.render_mode, 0|2|4|6);
                let has_stroke = matches!(gs.render_mode, 1|2|5|6);
                let clip_only = gs.render_mode == 7;
                let rm = gs.render_mode as u8;
                let glyph_device_adv = if vertical {
                    (advance.abs() * y_scale) as f32
                } else {
                    (glyph_advance_user * x_scale) as f32
                };
                // Real embedded outline for pure paint modes (0/1/2). Clip modes
                // (4-7) keep the substitute-glyph path so Kotlin can build the clip.
                let outline = if has_program && matches!(gs.render_mode, 0..=2) {
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
                        prims.push(Prim::Text {
                            x: x as f32,
                            y: y as f32,
                            size,
                            argb: apply_alpha_to_argb(gs.fill, fill_alpha),
                            text: s.clone(),
                            advance: glyph_device_adv.max(size * 0.1),
                            stroke_argb: None,
                            stroke_width: None,
                            render_mode: rm,
                            blend: gs.blend_mode,
                            is_bold: bold,
                            is_italic: italic,
                            font_family: family,
                            outline: true,
                            h_scale: th as f32,
                        });
                    }
                } else if prims.len() < MAX_PRIMITIVES {
                    // Substitute-font path (no embedded program or glyph missing).
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
                            h_scale: th as f32,
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
                            h_scale: th as f32,
                        });
                    } else if clip_only {
                        // Mode 7: no paint, but carry the glyph so Kotlin can add
                        // its outline to the clip at the text-clip-apply marker.
                        prims.push(Prim::Text {
                            x: x as f32,
                            y: y as f32,
                            size,
                            argb: 0,
                            text: s.clone(),
                            advance: glyph_device_adv.max(size * 0.1),
                            stroke_argb: None,
                            stroke_width: None,
                            render_mode: 7,
                            blend: gs.blend_mode,
                            is_bold: bold,
                            is_italic: italic,
                            font_family: family,
                            outline: false,
                            h_scale: th as f32,
                        });
                    }
                }
            }
        }
        pen += advance;
    });
    // Enforce primitive cap
    if prims.len() > MAX_PRIMITIVES {
        prims.truncate(MAX_PRIMITIVES);
    }
    pen
}

/// Render a Type 3 text run by interpreting each glyph's CharProc content stream
/// into the current graphics state. Returns the total text-space advance.
fn show_string_type3(
    doc: &Document,
    prims: &mut Vec<Prim>,
    gs: &GraphicsState,
    fi: &FontInfo,
    t3: &Type3Font,
    text_matrix: &Mat,
    bytes: &[u8],
    depth: u32,
) -> f64 {
    let tfs = gs.font_size;
    let th = gs.h_scale;
    let drawable = gs.render_mode != 3 && gs.render_mode != 7;
    let mut pen = 0.0_f64;
    let mut glyphs = 0usize;

    fi.for_each_code(bytes, |code, is_space| {
        let advance = fi.width(code) * tfs + gs.char_spacing + if is_space { gs.word_spacing } else { 0.0 };
        let advance = advance * th;
        if drawable
            && glyphs < MAX_TYPE3_GLYPHS
            && depth < MAX_PATTERN_RECURSION
            && prims.len() < MAX_PRIMITIVES
        {
            if let Some(&proc_id) = t3.char_procs.get(&code) {
                if let Ok(Object::Stream(s)) = doc.get_object(proc_id) {
                    if let Ok(content) = Content::decode(&stream_data_with_doc(doc, s)) {
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
                            &content.operations,
                            t3.resources.as_ref(),
                            glyph_gs,
                            prims,
                            depth + 1,
                            false,
                        );
                        // Bound per-glyph primitive count.
                        let cap = before + MAX_TYPE3_PRIMS_PER_GLYPH;
                        if prims.len() > cap {
                            prims.truncate(cap);
                        }
                        glyphs += 1;
                    }
                }
            }
        }
        pen += advance;
    });
    pen
}

// ---------------------------------------------------------------------------
// Image XObjects
// ---------------------------------------------------------------------------

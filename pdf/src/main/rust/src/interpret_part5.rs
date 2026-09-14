            "Tw" => {
                if let Some(v) = numop(o, 0) {
                    gs.word_spacing = v;
                }
            }
            "Tz" => {
                if let Some(v) = numop(o, 0) {
                    gs.h_scale = v / 100.0;
                }
            }
            "Ts" => {
                if let Some(v) = numop(o, 0) {
                    gs.rise = v;
                }
            }
            "Tr" => {
                if let Some(v) = numop(o, 0) {
                    // §9.3.6 defines modes 0..7 only.
                    gs.render_mode = (v as i64).clamp(0, 7);
                }
            }
            "Td" => {
                if let (Some(tx), Some(ty)) = (numop(o, 0), numop(o, 1)) {
                    line_matrix = mat_mul(&translate(tx, ty), &line_matrix);
                    text_matrix = line_matrix;
                }
            }
            "TD" => {
                if let (Some(tx), Some(ty)) = (numop(o, 0), numop(o, 1)) {
                    gs.leading = -ty;
                    line_matrix = mat_mul(&translate(tx, ty), &line_matrix);
                    text_matrix = line_matrix;
                }
            }
            "Tm" => {
                if let Some(m) = read_matrix(o) {
                    line_matrix = m;
                    text_matrix = m;
                }
            }
            "T*" => {
                line_matrix = mat_mul(&translate(0.0, -gs.leading), &line_matrix);
                text_matrix = line_matrix;
            }
            "Tj" => {
                if let Some(Object::String(bytes, _)) = o.first() {
                    let sm_start = prims.len();
                    // §8.11.2: content in a disabled optional-content group shall
                    // not be drawn — text as much as paths. Render mode 3 is
                    // precisely "neither fill nor stroke" (§9.3.6), so borrow it for
                    // the duration of the show rather than discarding the glyphs:
                    // the layer is hidden, not absent, and `search::build_index`
                    // runs this same code, so dropping them would silently remove
                    // the text from the search index too.
                    let oc_hidden = oc_stack.last().copied().unwrap_or(false);
                    let shown_mode = gs.render_mode;
                    if oc_hidden { gs.render_mode = hidden_render_mode(gs.render_mode); }
                    let adv = show_string_in(doc, prims, &gs, &fonts, &text_matrix, bytes, depth, resources);
                    gs.render_mode = shown_mode;
                    if latches_text_clip(shown_mode, &fonts, &gs.font_key, bytes) { text_clip_used = true; }
                    if fonts.get(&gs.font_key).map(|f| f.wmode == 1).unwrap_or(false) {
                        text_matrix = mat_mul(&translate(0.0, adv), &text_matrix);
                    } else {
                        text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                    }
                    // Soft-mask must also cover invisible-clip modes 4-6, not only 0-2.
                    // `!text_only` matches every other painting site: in the
                    // search-index mode nothing here is consumed, and expanding the
                    // mask group anyway re-interprets its content stream (rasterizing
                    // any shading in it) and spends the shared [`MAX_PRIMITIVES`]
                    // budget that `show_string`'s Text records are also drawn from —
                    // so a mask-heavy document silently indexed less of its own text.
                    if !text_only && !oc_hidden && matches!(gs.render_mode, 0|1|2|4|5|6) {
                        if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket, current_clip_bbox); }
                    }
                }
            }
            "'" => {
                line_matrix = mat_mul(&translate(0.0, -gs.leading), &line_matrix);
                text_matrix = line_matrix;
                if let Some(Object::String(bytes, _)) = o.first() {
                    // P0 fix #24: soft-mask must apply to ' operator
                    let sm_start = prims.len();
                    let oc_hidden = oc_stack.last().copied().unwrap_or(false);
                    let shown_mode = gs.render_mode;
                    if oc_hidden { gs.render_mode = hidden_render_mode(gs.render_mode); }
                    let adv = show_string_in(doc, prims, &gs, &fonts, &text_matrix, bytes, depth, resources);
                    gs.render_mode = shown_mode;
                    if latches_text_clip(shown_mode, &fonts, &gs.font_key, bytes) { text_clip_used = true; }
                    if fonts.get(&gs.font_key).map(|f| f.wmode == 1).unwrap_or(false) {
                        text_matrix = mat_mul(&translate(0.0, adv), &text_matrix);
                    } else {
                        text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                    }
                    if !text_only && !oc_hidden && matches!(gs.render_mode, 0|1|2|4|5|6) {
                        if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket, current_clip_bbox); }
                    }
                }
            }
            "\"" => {
                if let Some(aw) = numop(o, 0) { gs.word_spacing = aw; }
                if let Some(ac) = numop(o, 1) { gs.char_spacing = ac; }
                line_matrix = mat_mul(&translate(0.0, -gs.leading), &line_matrix);
                text_matrix = line_matrix;
                if let Some(Object::String(bytes, _)) = o.get(2) {
                    // P0 fix #24: soft-mask must apply to " operator
                    let sm_start = prims.len();
                    let oc_hidden = oc_stack.last().copied().unwrap_or(false);
                    let shown_mode = gs.render_mode;
                    if oc_hidden { gs.render_mode = hidden_render_mode(gs.render_mode); }
                    let adv = show_string_in(doc, prims, &gs, &fonts, &text_matrix, bytes, depth, resources);
                    gs.render_mode = shown_mode;
                    if latches_text_clip(shown_mode, &fonts, &gs.font_key, bytes) { text_clip_used = true; }
                    if fonts.get(&gs.font_key).map(|f| f.wmode == 1).unwrap_or(false) {
                        text_matrix = mat_mul(&translate(0.0, adv), &text_matrix);
                    } else {
                        text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                    }
                    if !text_only && !oc_hidden && matches!(gs.render_mode, 0|1|2|4|5|6) {
                        if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket, current_clip_bbox); }
                    }
                }
            }
            "TJ" => {
                let sm_start = prims.len();
                let oc_hidden = oc_stack.last().copied().unwrap_or(false);
                let shown_mode = gs.render_mode;
                if oc_hidden { gs.render_mode = hidden_render_mode(gs.render_mode); }
                if let Some(Object::Array(arr)) = o.first() {
                    for el in arr {
                        match el {
                            Object::String(bytes, _) => {
                                // §9.4.3: the clip accumulates the outlines of the
                                // glyphs SHOWN. Latching outside this destructure
                                // made `7 Tr [] TJ` — and any TJ whose operand is
                                // not an array — claim a clip built from no glyphs
                                // at all, which `ET` then applies.
                                let adv = show_string_in(doc, prims, &gs, &fonts, &text_matrix, bytes, depth, resources);
                                if latches_text_clip(shown_mode, &fonts, &gs.font_key, bytes) { text_clip_used = true; }
                                if fonts.get(&gs.font_key).map(|f| f.wmode == 1).unwrap_or(false) {
                        text_matrix = mat_mul(&translate(0.0, adv), &text_matrix);
                    } else {
                        text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                    }
                            }
                            Object::Integer(_) | Object::Real(_) => {
                                // A non-finite adjustment poisons the text matrix and
                                // with it every glyph origin after it; treat it as no
                                // adjustment (§7.3.3, see `numop`).
                                let n = num(el).filter(|v| v.is_finite()).unwrap_or(0.0);
                                // TJ adjustment applies along the writing axis.
                                if fonts.get(&gs.font_key).map(|f| f.wmode == 1).unwrap_or(false) {
                                    let ty = -n / 1000.0 * gs.font_size;
                                    text_matrix = mat_mul(&translate(0.0, ty), &text_matrix);
                                } else {
                                    let tx = -n / 1000.0 * gs.font_size * gs.h_scale;
                                    text_matrix = mat_mul(&translate(tx, 0.0), &text_matrix);
                                }
                            }
                            _ => {}
                        }
                    }
                }
                if oc_hidden { gs.render_mode = shown_mode; }
                if !text_only && !oc_hidden && matches!(gs.render_mode, 0|1|2|4|5|6) {
                    if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket, current_clip_bbox); }
                }
            }
            // Explicit no-ops (documented): rendering intent, and compatibility
            // sections have no effect on our flat-primitive output.
            "ri" | "BX" | "EX" | "EI" => {}
            _ => {}
        }
    }
    while group_depth > 0 { if !text_only { prims.push(Prim::GroupPop); } group_depth-=1; }
    while clip_depth > 0 {
        if !text_only {
            prims.push(Prim::ClipPop);
        }
        clip_depth -= 1;
    }
}

pub(crate) fn read_matrix(operands: &[Object]) -> Option<Mat> {
    let n: Vec<f64> = operands.iter().filter_map(num).collect();
    if n.len() != 6 {
        return None;
    }
    let m = [n[0], n[1], n[2], n[3], n[4], n[5]];
    // §8.3.3 defines a matrix as six NUMBERS. lopdf's `Object::Real` is an f32, so a
    // file carrying `1e40` yields INFINITY here, and every coordinate derived from
    // the matrix is then non-finite. `read_rect` already rejects a non-finite
    // rectangle for the same reason; this is the other half of the same boundary,
    // and it was the one still open.
    //
    // Rejecting rather than patching, because every caller already has a correct
    // meaning for `None`: `cm` and `Tm` leave the current matrix alone (matching the
    // `cm` arm's existing guard on its own product), and the four `/Matrix` reads —
    // form XObject §8.10.2, tiling and shading patterns §8.7.3.1, soft-mask group
    // §11.6.5.2 — all fall back to IDENTITY, which is precisely what the spec says an
    // ABSENT `/Matrix` means. A malformed optional entry is treated as absent.
    //
    // `Tm` is the one that was not covered transitively: `cm` guards the product it
    // computes, but `Tm` assigns the matrix straight to the text matrix, so a
    // non-finite one placed every subsequent glyph at a non-finite origin.
    if m.iter().all(|v| v.is_finite()) {
        Some(m)
    } else {
        None
    }
}

/// Resolve the ARGB base color for an uncolored (`/PaintType 2`) pattern's
/// operands. When the Pattern colorspace declares an underlying base space
/// (`[/Pattern base]`), the operands are interpreted in that space; otherwise
/// they are approximated as Gray/RGB/CMYK by arity.
pub(crate) fn uncolored_pattern_argb(
    doc: &Document,
    cs: &CsKind,
    comps: &[f64],
    cs_resources: &HashMap<Vec<u8>, ObjectId>,
) -> u32 {
    if let CsKind::Pattern { base: Some(base) } = cs {
        if let Some(rgb) = eval_cs_to_rgb(doc, base, comps, cs_resources) {
            return rgb;
        }
    }
    match comps.len() {
        1 => gray_to_argb(comps[0]),
        3 => rgb_to_argb(comps[0], comps[1], comps[2]),
        4 => cmyk_to_argb(comps[0], comps[1], comps[2], comps[3]),
        _ => 0xFF00_0000,
    }
}

/// Paint a pattern fill within the region described by `polys` (device space).
/// Handles PatternType 2 (shading) and PatternType 1 (tiling), bounded by
/// [`MAX_PATTERN_RECURSION`] and a per-pattern tile cap.
/// Build stroke-outline quadrilaterals (device space) for a set of polyline
/// subpaths, offsetting each segment by `hw` (half the device line width) on
/// both sides, plus a small square at every vertex so joints/caps don't leave
/// gaps. Each quad is painted independently so the segments union correctly.
fn stroke_outline_quads(subpaths: &[Vec<(f64, f64)>], hw: f64) -> Vec<Vec<(f64, f64)>> {
    let mut quads: Vec<Vec<(f64, f64)>> = Vec::new();
    for sp in subpaths {
        if sp.len() < 2 { continue; }
        for w in sp.windows(2) {
            let (x0, y0) = w[0];
            let (x1, y1) = w[1];
            let dx = x1 - x0;
            let dy = y1 - y0;
            let len = (dx*dx + dy*dy).sqrt();
            if len < 1e-9 { continue; }
            let nx = -dy / len * hw;
            let ny = dx / len * hw;
            quads.push(vec![
                (x0 + nx, y0 + ny),
                (x1 + nx, y1 + ny),
                (x1 - nx, y1 - ny),
                (x0 - nx, y0 - ny),
            ]);
        }
        for &(x, y) in sp.iter() {
            quads.push(vec![
                (x - hw, y - hw),
                (x + hw, y - hw),
                (x + hw, y + hw),
                (x - hw, y + hw),
            ]);
        }
    }
    quads
}

/// Identity of a soft mask, used to decide whether an already-emitted bracket
/// can absorb another painting operation.
///
/// §11.6.5.2 renders the mask group with the CTM in effect when `gs` set the
/// mask, so the CTM is part of the identity: the same group under two different
/// CTMs is two different masks. `/BC` changes the rendered group and `/TR` the
/// push record, so both are included.
#[derive(PartialEq)]
pub(crate) struct MaskKey {
    group_id: ObjectId,
    mask_type: u8,
    ctm: [u64; 6],
    backdrop: Option<Vec<u64>>,
    tr: Option<[u8; 256]>,
}

impl MaskKey {
    fn of(mask: &SoftMask) -> Self {
        let mut ctm = [0u64; 6];
        for (dst, src) in ctm.iter_mut().zip(mask.ctm.iter()) {
            *dst = src.to_bits();
        }
        MaskKey {
            group_id: mask.group_id,
            mask_type: mask.mask_type,
            ctm,
            backdrop: mask
                .backdrop
                .as_ref()
                .map(|b| b.iter().map(|v| v.to_bits()).collect()),
            tr: mask.tr,
        }
    }
}

/// The soft-mask bracket most recently emitted into `prims`, so a following
/// painting operation under the same mask can extend it.
pub(crate) struct MaskBracket {
    key: MaskKey,
    /// Index of the `SoftMaskPush`.
    push: usize,
    /// Index of the `SoftMaskContent` separator.
    content: usize,
    /// `prims.len()` when the bracket was closed. Coalescing is only sound while
    /// the bracket is still the tail of `prims`.
    end: usize,
}

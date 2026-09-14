                                    && !oc_stack.last().copied().unwrap_or(false)
                                    && prims.len() < crate::MAX_PRIMITIVES
                                    && depth < MAX_GROUP_DEPTH;
                                let sm_start = prims.len();
                                // The group push is NOT mutually exclusive with the
                                // soft-mask bracket. Making it so silently disabled the
                                // §11.6.6 alpha reset below, which is gated on
                                // `pushed_group`: a form carrying BOTH `/ca` < 1 and an
                                // `/SMask` then applied `ca` to every element inside
                                // instead of once to the composited group, over-darkening
                                // wherever that content overlaps itself.
                                //
                                // Both fit because `sm_start` is captured ABOVE the push,
                                // so `wrap_with_soft_mask` inserts `SoftMaskPush` outside
                                // the group: the group composites (applying `ca` once)
                                // inside the masked layer, then the mask applies to that
                                // result, which is the §11.6.5.1 order.
                                let should_emit_group = is_transparency_group && !text_only && !oc_stack.last().copied().unwrap_or(false) && depth < MAX_GROUP_DEPTH;
                                let pushed_group = should_emit_group
                                    && prims.len() < crate::MAX_PRIMITIVES
                                    && group_depth < 32;
                                if pushed_group {
                                    // The nonstroking constant alpha (ca) applies to the
                                    // group as a whole when it is painted; NOT ca*CA.
                                    prims.push(Prim::GroupPush { isolated, knockout, alpha: gs.alpha_fill as f32, blend: gs.blend_mode });
                                    group_depth+=1;
                                }
                                // Form content shall be clipped to /BBox (transformed by
                                // /Matrix), per PDF 8.10.1, so it can't bleed past its box.
                                let form_ctm = mat_mul(&form_matrix, &gs.ctm);
                                // §8.7.4.1 requires `sh` to cover the ENTIRE current
                                // clipping region, so `rasterize_shading` refuses to guess:
                                // handed no clip extent, a shading with no `/BBox` of its own
                                // paints NOTHING at all. Only the page-level caller seeded
                                // that extent, and every nested stream reaches the
                                // interpreter through `interpret_content`, which passes
                                // `None` — so `/Sh sh` inside a form XObject silently
                                // vanished, which is one of the few ways a fully-implemented
                                // operator can still render nothing.
                                //
                                // §8.10.1 clips a form's content to `/BBox` transformed by
                                // `/Matrix`, so that box — intersected with the clip already
                                // in force — IS the region a nested `sh` has to fill. It is
                                // the same quantity the `ClipPush` below is built from.
                                let form_clip_bbox = {
                                    let own = stream
                                        .dict
                                        .get(b"BBox")
                                        .ok()
                                        .and_then(|o| read_rect(doc, o))
                                        .and_then(|bb| {
                                            quad_device_bbox(&[
                                                transform(&form_ctm, bb[0], bb[1]),
                                                transform(&form_ctm, bb[2], bb[1]),
                                                transform(&form_ctm, bb[2], bb[3]),
                                                transform(&form_ctm, bb[0], bb[3]),
                                            ])
                                        });
                                    // No `/BBox` (malformed, §8.10.2 makes it required) means
                                    // no extra bound, not an empty one: inherit the caller's.
                                    match (own, current_clip_bbox) {
                                        (Some(b), Some(cur)) => Some([
                                            b[0].max(cur[0]),
                                            b[1].max(cur[1]),
                                            b[2].min(cur[2]),
                                            b[3].min(cur[3]),
                                        ]),
                                        (Some(b), None) => Some(b),
                                        (None, cur) => cur,
                                    }
                                    // The intersection itself can come out empty.
                                    .filter(|b| b[2] > b[0] && b[3] > b[1])
                                };
                                let mut bbox_clipped = false;
                                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                                    // §8.10.2 makes `/BBox` REQUIRED on a form
                                    // XObject, so a form without one is malformed
                                    // and there is no box to clip to. Deliberately
                                    // left unclipped rather than falling back to
                                    // the page box: that matches pdf.js, and the
                                    // form's `/Matrix` may legitimately place its
                                    // content outside the page, so a page-box
                                    // fallback would erase content in files that
                                    // render today. It does resolve malformed
                                    // input in the ink-ADDING direction, which is
                                    // the trade being made knowingly. Raised by
                                    // `hunt-extra`; a decision, not an oversight.
                                    if let Some(bb) = stream.dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)) {
                                        if prims.len() < MAX_PRIMITIVES {
                                            let c = [
                                                transform(&form_ctm, bb[0], bb[1]),
                                                transform(&form_ctm, bb[2], bb[1]),
                                                transform(&form_ctm, bb[2], bb[3]),
                                                transform(&form_ctm, bb[0], bb[3]),
                                            ];
                                            let pts: Vec<(f32, f32)> = c.iter().map(|&(x, y)| (x as f32, y as f32)).collect();
                                            let po = vec![
                                                PathOp::Move(c[0].0 as f32, c[0].1 as f32),
                                                PathOp::Line(c[1].0 as f32, c[1].1 as f32),
                                                PathOp::Line(c[2].0 as f32, c[2].1 as f32),
                                                PathOp::Line(c[3].0 as f32, c[3].1 as f32),
                                                PathOp::Close,
                                            ];
                                            prims.push(Prim::ClipPush { even_odd: false, pts, path_ops: Some(po) });
                                            bbox_clipped = true;
                                        }
                                    }
                                }
                                // §8.9.7: lopdf wraps inline-image parsing in nom
                                // `cut(...)`, so ONE inline image it cannot handle
                                // failed this whole form and blanked it, exactly as it
                                // used to blank a whole page. `stream_operations`
                                // returns lopdf's result untouched when it succeeds, so
                                // a form that renders today is unaffected.
                                let sub_ops = crate::content::stream_operations(doc, stream);
                                if !sub_ops.is_empty() {
                                        let mut sub_gs = gs.clone();
                                        sub_gs.ctm = form_ctm;
                                        // A soft mask applies once, to this form as a
                                        // whole. Only clear it when the wrap actually
                                        // happened; otherwise the mask must stay in the
                                        // state and be applied per element inside, since
                                        // §11.6.5.1 makes it inherited state that cannot
                                        // silently vanish.
                                        if use_smask { sub_gs.soft_mask = None; }
                                        // Per PDF 11.6.6: on entering a transparency group the
                                        // alpha constants reset to 1.0 and blend to Normal — they
                                        // are applied when the group's result is composited (via
                                        // GroupPush), not again to each element inside. Without
                                        // this, ca is double-applied and low-alpha groups vanish.
                                        //
                                        // Deliberately still gated on `pushed_group`, so when
                                        // `MAX_PRIMITIVES` or the group-depth cap demotes the push
                                        // the alpha keeps being applied per element. There is no
                                        // composite to apply it to once in that case, and resetting
                                        // anyway would drop `ca` entirely and paint the form fully
                                        // opaque — wrong for all content, where per-element is
                                        // wrong only where content overlaps itself.
                                        if pushed_group {
                                            sub_gs.alpha_fill = 1.0;
                                            sub_gs.alpha_stroke = 1.0;
                                            sub_gs.blend_mode = BlendMode::Normal;
                                        }
                                        let res_ref = form_res.as_ref().or(resources);
                                        interpret_content_seeded(
                                            doc,
                                            &sub_ops,
                                            res_ref,
                                            sub_gs,
                                            prims,
                                            depth + 1,
                                            text_only,
                                            form_clip_bbox,
                                        );
                                }
                                if bbox_clipped {
                                    // Always balance the ClipPush, even if the prim cap
                                    // was hit inside the form, to keep the clip stack sane.
                                    prims.push(Prim::ClipPop);
                                }
                                // §8.10.1: `Do` on a form behaves as `q … Q`, so the
                                // group must be composited here rather than being left
                                // open until the next `Q` (or end of stream), which let
                                // its alpha and blend mode leak onto later content.
                                if pushed_group {
                                    prims.push(Prim::GroupPop);
                                    group_depth -= 1;
                                }
                                // Bracket the whole form as the masked content, then
                                // append the mask group (rendered at the mask's set-time CTM).
                                if use_smask {
                                    if let Some(mask) = active_smask {
                                        wrap_with_soft_mask(prims, sm_start, doc, resources, &mask, depth, &mut mask_bracket, current_clip_bbox);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "rg" => {
                let n: Vec<f64> = o.iter().filter_map(num).collect();
                if n.len() == 3 {
                    gs.fill = rgb_to_argb(n[0], n[1], n[2]);
                    gs.non_stroke_cs = CsKind::DeviceRGB;
                    gs.fill_pattern = None;
                }
            }
            "RG" => {
                let n: Vec<f64> = o.iter().filter_map(num).collect();
                if n.len() == 3 {
                    gs.stroke = rgb_to_argb(n[0], n[1], n[2]);
                    gs.stroke_cs = CsKind::DeviceRGB;
                    gs.stroke_pattern = None;
                }
            }
            "g" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.fill = gray_to_argb(v);
                    gs.non_stroke_cs = CsKind::DeviceGray;
                    gs.fill_pattern = None;
                }
            }
            "G" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.stroke = gray_to_argb(v);
                    gs.stroke_cs = CsKind::DeviceGray;
                    gs.stroke_pattern = None;
                }
            }
            "k" => {
                let n: Vec<f64> = o.iter().filter_map(num).collect();
                if n.len() == 4 {
                    gs.fill = cmyk_to_argb(n[0], n[1], n[2], n[3]);
                    gs.non_stroke_cs = CsKind::DeviceCMYK;
                    gs.fill_pattern = None;
                }
            }
            "K" => {
                let n: Vec<f64> = o.iter().filter_map(num).collect();
                if n.len() == 4 {
                    gs.stroke = cmyk_to_argb(n[0], n[1], n[2], n[3]);
                    gs.stroke_cs = CsKind::DeviceCMYK;
                    gs.stroke_pattern = None;
                }
            }
            "CS" => {
                if let Some(cs_name) = o.first() {
                    if let Some(kind) = parse_named_cs(doc, cs_name, resources, &colorspaces) {
                        // Selecting a color space resets the current color to its
                        // initial value (PDF 8.6.8).
                        if let Some(c) = cs_initial_color(doc, &kind, &colorspaces) { gs.stroke = c; }
                        gs.stroke_cs = kind;
                    }
                    gs.stroke_pattern = None;
                }
            }
            "cs" => {
                if let Some(cs_name) = o.first() {
                    if let Some(kind) = parse_named_cs(doc, cs_name, resources, &colorspaces) {
                        if let Some(c) = cs_initial_color(doc, &kind, &colorspaces) { gs.fill = c; }
                        gs.non_stroke_cs = kind;
                    }
                    gs.fill_pattern = None;
                }
            }
            "SC" => {
                let comps: Vec<f64> = o.iter().filter_map(num).collect();
                if let Some(rgb) = eval_cs_to_rgb(doc, &gs.stroke_cs, &comps, &colorspaces) {
                    gs.stroke = rgb;
                }
            }
            "sc" => {
                let comps: Vec<f64> = o.iter().filter_map(num).collect();
                if let Some(rgb) = eval_cs_to_rgb(doc, &gs.non_stroke_cs, &comps, &colorspaces) {
                    gs.fill = rgb;
                }
            }
            "SCN" => {
                let comps: Vec<f64> = o.iter().filter_map(num).collect();
                if matches!(gs.stroke_cs, CsKind::Pattern { .. }) {
                    gs.stroke_pattern = o.last().and_then(|obj| obj.as_name().ok()).and_then(|pn| patterns.get(pn).copied());
                    if !comps.is_empty() {
                        gs.stroke = uncolored_pattern_argb(doc, &gs.stroke_cs, &comps, &colorspaces);
                    }
                } else if !comps.is_empty() {
                    if let Some(rgb) = eval_cs_to_rgb(doc, &gs.stroke_cs, &comps, &colorspaces) {
                        gs.stroke = rgb;
                    }
                }
            }
            "scn" => {
                let comps: Vec<f64> = o.iter().filter_map(num).collect();
                if matches!(gs.non_stroke_cs, CsKind::Pattern { .. }) {
                    gs.fill_pattern = o.last().and_then(|obj| obj.as_name().ok()).and_then(|pn| patterns.get(pn).copied());
                    if !comps.is_empty() {
                        gs.fill = uncolored_pattern_argb(doc, &gs.non_stroke_cs, &comps, &colorspaces);
                    }
                } else if !comps.is_empty() {
                    if let Some(rgb) = eval_cs_to_rgb(doc, &gs.non_stroke_cs, &comps, &colorspaces) {
                        gs.fill = rgb;
                    }
                }
            }
            "sh" => {
                // Capture the clip extent (device space) that bounds this shading
                // so it can be rasterized at device resolution and, when the
                // shading has no /BBox, cover the whole clip.
                let clip_bbox_device: Option<[f64;4]> = pending_clip.as_ref().map(|pc| {
                    let mut x0 = f64::INFINITY; let mut y0 = f64::INFINITY;
                    let mut x1 = f64::NEG_INFINITY; let mut y1 = f64::NEG_INFINITY;
                    for poly in pc.polys.iter() {
                        for &(x,y) in poly.iter() {
                            x0 = x0.min(x); y0 = y0.min(y); x1 = x1.max(x); y1 = y1.max(y);
                        }
                    }
                    [x0, y0, x1, y1]
                }).filter(|b| b[2] > b[0] && b[3] > b[1])
                // Fall back to the already-committed clip region (the common
                // `re W n /Sh sh` case, where pending_clip is None by now).
                .or(current_clip_bbox);
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only);
                }
                if !text_only {
                    if let Some(Object::Name(name)) = o.first() {
                        // §8.7.4.2: a /Shading resource is "a dictionary or a
                        // stream", and §7.3.8.1 requires only the stream form to
                        // be indirect — so ShadingTypes 1-3 are legally written
                        // DIRECTLY in the resource dictionary and never reach the
                        // reference-only `shadings` map.
                        let shading = shadings
                            .get(name)
                            .and_then(|&id| doc.get_object(id).ok())
                            .or_else(|| resolve_named_resource(doc, resources, b"Shading", name));
                        if let Some(obj) = shading {
                            if let Some((ctm,w,h,data)) = rasterize_shading(doc, obj, &gs.ctm, &colorspaces, 0, clip_bbox_device) {
                                if prims.len() < MAX_PRIMITIVES && !oc_stack.last().copied().unwrap_or(false) {
                                    let sm_start = prims.len();
                                    prims.push(Prim::Image { ctm, w, h, format: 0, data, alpha: gs.alpha_fill as f32, blend: gs.blend_mode });
                                    if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket, current_clip_bbox); }
                                }
                            }
                        }
                    }
                }
            }
            "BMC" => {
                let hidden = oc_stack.last().copied().unwrap_or(false);
                if oc_stack.len() < MAX_OC_STACK { oc_stack.push(hidden); } else { oc_overflow += 1; }
            }
            "BDC" => {
                // Optional content: `/OC <props> BDC`, where <props> is the OCG/OCMD
                // itself (an inline dict or a name resolved via /Properties). The
                // property list IS the group — there is no nested /OC key.
                let mut should_hide = false;
                let tag = o.first().and_then(|t| t.as_name().ok());
                if tag == Some(b"OC") {
                    if let Some(prop_obj) = o.get(1) {
                        match prop_obj {
                            Object::Name(n) => {
                                // Resolve via the /Properties resource, keeping the
                                // indirect reference so ON/OFF lists can match it.
                                if let Some(&cached) = oc_cache.get(&OcKey::Named(n.clone())) {
                                    should_hide = cached;
                                } else if let Some(res_dict) = resources {
                                    if let Some(prop_dict) = res_dict.get(b"Properties").ok().and_then(|ob| deref(doc, ob)).and_then(|ob| ob.as_dict().ok()) {
                                        if let Ok(oc_ref) = prop_dict.get(n) {
                                            should_hide = oc_hidden!(oc_ref);
                                            oc_cache.insert(OcKey::Named(n.clone()), should_hide);
                                        }
                                    }
                                }
                            }
                            Object::Reference(id) => {
                                should_hide = match oc_cache.get(&OcKey::Ref(*id)) {
                                    Some(&cached) => cached,
                                    None => {
                                        let v = oc_hidden!(prop_obj);
                                        oc_cache.insert(OcKey::Ref(*id), v);
                                        v
                                    }
                                };
                            }
                            other => {
                                should_hide = oc_hidden!(other);
                            }
                        }
                    }
                }
                // Hiding is inherited: a visible OCG nested inside a hidden
                // region stays hidden (§8.11.4.5).
                let hidden = oc_stack.last().copied().unwrap_or(false) || should_hide;
                if oc_stack.len() < MAX_OC_STACK { oc_stack.push(hidden); } else { oc_overflow += 1; }
            }
            "MP" | "DP" => {
                // Marked-content point operators: no matching EMC, so they must not
                // affect the marked-content / optional-content stack.
            }
            "EMC" => {
                // Exactly one frame per EMC (§14.6). Unmatched EMCs are ignored.
                if oc_overflow > 0 { oc_overflow -= 1; } else { oc_stack.pop(); }
            }
            "d0" | "d1" => {
                // §9.6.5 Table 113: `wx wy d0` and `wx wy llx lly urx ury d1` declare
                // the glyph's advance (and, for `d1`, its bbox). The advance comes
                // from the font's /Widths array, which §9.6.5 requires to agree, so
                // there is nothing to apply here — but `d1` additionally makes the
                // glyph SHAPE ONLY, which `type3_shape_only` above acts on.
                //
                // Reachable only since `content::repair_d0_d1`: lopdf 0.36 ends an
                // operator token at the first digit, so this arm was dead code and
                // the `"d"` arm ran instead, clearing the dash pattern the glyph
                // inherits.
            }
            "BT" => {
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only);
                }
                text_matrix = IDENTITY;
                line_matrix = IDENTITY;
                text_clip_used = false;
            }
            "ET" => {
                // If the text object added glyphs to the clip (Tr 4-7), apply it.
                // Deliberately NOT gated on optional-content visibility: the
                // renderer accumulates glyph outlines as they are shown, so a
                // BDC/EMC that hides only the tail of the text object would
                // otherwise leave those outlines pending and fold them into the
                // NEXT text object's clip.
                //
                // Nor gated on MAX_PRIMITIVES, for exactly that reason. The
                // consumer resets its accumulated path ONLY in the TextClipApply
                // arm and accumulates by UNION, so dropping the marker does not
                // drop the clip — it hands this object's letterforms to the next
                // Tr 4-7 object, which then paints through both. The cap is
                // reachable and recoverable: a failed soft-mask expansion and the
                // per-glyph Type 3 bound both truncate `prims` back below it. One
                // prim past the cap is bounded by MAX_CONTENT_OPS, and the
                // matching `ClipPop` is emitted unconditionally at `Q` and at end
                // of stream, so the clip stack stays balanced.
                //
                // The marker is NOT harmless when nothing was accumulated: §9.4.3
                // intersects the shown glyphs' outlines with the current clip, so
                // an empty accumulation clips to EMPTY. That is why `text_clip_used`
                // must be latched if and only if a glyph was actually shown.
                if text_clip_used && !text_only {
                    prims.push(Prim::TextClipApply);
                    clip_depth += 1;
                }
                text_clip_used = false;
            }
            "Tf" => {
                if let Some(Object::Name(name)) = o.first() {
                    gs.font_key = name.clone();
                }
                if let Some(sz) = numop(o, 1) {
                    gs.font_size = sz;
                }
            }
            "TL" => {
                if let Some(v) = numop(o, 0) {
                    gs.leading = v;
                }
            }
            "Tc" => {
                if let Some(v) = numop(o, 0) {
                    gs.char_spacing = v;
                }
            }
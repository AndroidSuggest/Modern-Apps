                        //   shape. Both change how overlapping marks composite INSIDE
                        //   one text object / group. The primitive stream composites
                        //   marks in order against the running result, and neither
                        //   alternative is expressible with Canvas layers (the same
                        //   reason /I and /K on a transparency group are approximated
                        //   as isolated non-knockout). Faking either would change the
                        //   common case to fix the rare one.
                        // ---------------------------------------------------------
                        // Soft mask: /SMask /None clears; dict may have /G as Ref OR direct Stream (P0 fix)
                        if let Ok(sm_raw) = dict.get(b"SMask") {
                            if let Ok(n) = sm_raw.as_name() {
                                if n == b"None" { gs.soft_mask = None; }
                            } else if let Some(sm) = deref(doc, sm_raw).or(Some(sm_raw)) {
                                if let Ok(smdict) = sm.as_dict() {
                                    let mask_type = match smdict.get(b"S").ok().and_then(|o| o.as_name().ok()) {
                                        Some(b"Luminosity") => 1u8,
                                        _ => 0u8,
                                    };
                                    let backdrop = smdict.get(b"BC").ok()
                                        .and_then(|o| deref(doc, o))
                                        .and_then(|o| o.as_array().ok())
                                        .map(|a| a.iter().filter_map(num).collect::<Vec<f64>>())
                                        .filter(|v| !v.is_empty());
                                    // /G can be Ref or direct Stream/Dict — handle both
                                    let gid_opt = smdict.get(b"G").ok().and_then(|g| {
                                        if let Object::Reference(id) = g { Some(*id) }
                                        else if let Some(Object::Reference(id)) = deref(doc, g) {
                                            match deref(doc, g) { Some(Object::Reference(_)) => Some(*id), _ => g.as_reference().ok() }
                                        } else { g.as_reference().ok() }
                                    });
                                    if let Some(gid) = gid_opt {
                                        // §11.6.5.2: the mask value passes through /TR
                                        // before use. `read_transfer_lut` returns None for
                                        // /Identity and for anything within one 8-bit step
                                        // of it, so the common case costs nothing.
                                        // (imaging owns functions.rs/graphics_state.rs;
                                        // this line only populates the new field.)
                                        let tr = smdict
                                            .get(b"TR")
                                            .ok()
                                            .and_then(|o| functions::read_transfer_lut(doc, o));
                                        gs.soft_mask = Some(SoftMask { group_id: gid, mask_type, ctm: gs.ctm, backdrop, tr });
                                    }
                                }
                            }
                        }
                    };
                    let chosen: Option<lopdf::Dictionary> = if let Some(&id) = extgstates.get(name) {
                        // Cloned rather than borrowed: `apply_dict` needs `&mut gs`
                        // while `doc` is still borrowed by the dictionary.
                        doc.get_dictionary(id).ok().cloned()
                    } else {
                        inline_dict.cloned()
                    };
                    if let Some(dict_clone) = chosen {
                        apply_dict(&dict_clone, &mut gs, doc);
                        // §8.4.5 Table 58 `/Font` is `[font size]`, where `font` is an
                        // INDIRECT REFERENCE to a font dictionary rather than a
                        // resource name — it is the graphics-state equivalent of `Tf`
                        // and is saved and restored by q/Q like the rest of the text
                        // state (§9.3.1). Unparsed, `gs.font_key` stayed empty and
                        // `show_string` fell through to its no-metrics branch: the run
                        // was emitted as ONE primitive at the origin with a guessed
                        // 0.5-em-per-byte advance and the bytes read as Latin-1, so the
                        // text appeared but at the wrong place, spacing and encoding.
                        //
                        // The font is registered under a key derived from its object id
                        // rather than a resource name, because it deliberately has no
                        // name: `/Font` exists precisely to reference a font that the
                        // resource dictionary need not list.
                        //
                        // Two known limits of that, neither a regression on the
                        // no-metrics branch this replaces. The registration bypasses
                        // fonts.rs's `FontCacheScope` (its cache helpers are private to
                        // that module), so a `/Font` inside a stream that is REPLAYED —
                        // a tiling-pattern cell — re-parses the font program per
                        // replay. And a nested stream rebuilds `fonts` from its own
                        // resources, which cannot contain this key, so a form XObject
                        // that shows text under an INHERITED `/Font` selection still
                        // falls through to the no-metrics branch — exactly as it
                        // already does for an inherited `Tf` resource name.
                        if let Some(farr) = dict_clone
                            .get(b"Font")
                            .ok()
                            .and_then(|o| deref(doc, o))
                            .and_then(|o| o.as_array().ok())
                        {
                            if let Some(fid) = farr.first().and_then(|o| o.as_reference().ok()) {
                                let key = format!("\u{0}gsfont{}_{}", fid.0, fid.1).into_bytes();
                                if !fonts.contains_key(&key) {
                                    if let Ok(Object::Dictionary(fd)) = doc.get_object(fid) {
                                        let fd = fd.clone();
                                        fonts.insert(key.clone(), font_info(doc, &fd));
                                    }
                                }
                                // Only adopt it once there really are metrics behind
                                // the key: a dangling reference must leave whatever
                                // `Tf` last selected alone rather than blanking it.
                                if fonts.contains_key(&key) {
                                    gs.font_key = key;
                                    if let Some(sz) = farr.get(1).and_then(|o| deref(doc, o).and_then(num).or_else(|| num(o))).filter(|v| v.is_finite()) {
                                        gs.font_size = sz;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "W" => {
                // P0 fix: emit as single ClipPush preserving holes via path_ops (was per-poly loop)
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only);
                }
                pending_clip = Some(PendingClip { even_odd: false, polys: subpaths.clone(), path_ops: clip_path_ops.clone() });
            }
            "W*" => {
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only);
                }
                pending_clip = Some(PendingClip { even_odd: true, polys: subpaths.clone(), path_ops: clip_path_ops.clone() });
            }
            "m" => {
                let xn = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x)));
                let yn = o.get(1).and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x)));
                if let (Some(x), Some(y)) = (xn, yn) {
                    let (dx, dy) = dev(&gs, x, y);
                    // A non-finite moveto starts no subpath: see `finite2`.
                    if !finite2((dx, dy)) { continue; }
                    cur_user = (x, y);
                    start_user = (x, y);
                    subpath_closed = false;
                    subpath_dropped = !reopen_subpath(&mut subpaths, &mut clip_path_ops, (dx, dy));
                }
            }
            "l" => {
                let xn = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x)));
                let yn = o.get(1).and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x)));
                if let (Some(x), Some(y)) = (xn, yn) {
                    // §8.5.2.1: `l` with no current point is an error. Fabricating a
                    // subpath here desynchronised `subpaths` from `clip_path_ops`,
                    // and an unmatched Line makes Android's Path start at (0,0).
                    if subpaths.is_empty() || subpath_dropped { continue; }
                    let (dx, dy) = dev(&gs, x, y);
                    // A non-finite point would make the whole contour undrawable,
                    // not just this vertex: see `finite2`.
                    if !finite2((dx, dy)) { continue; }
                    // §8.5.2.1: a segment after a close starts a new subpath.
                    if subpath_closed {
                        let at = dev(&gs, start_user.0, start_user.1);
                        if !finite2(at) { continue; }
                        if !reopen_subpath(&mut subpaths, &mut clip_path_ops, at) {
                            subpath_dropped = true;
                            continue;
                        }
                        subpath_closed = false;
                    }
                    cur_user = (x, y);
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push((dx, dy));
                    }
                    clip_path_ops.push(PathOp::Line(dx as f32, dy as f32));
                }
            }
            "c" | "v" | "y" => {
                let nums: Vec<f64> = o.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).collect();
                let (p1, p2, p3) = match op.operator.as_str() {
                    "c" if nums.len() == 6 => (
                        (nums[0], nums[1]),
                        (nums[2], nums[3]),
                        (nums[4], nums[5]),
                    ),
                    "v" if nums.len() == 4 => {
                        (cur_user, (nums[0], nums[1]), (nums[2], nums[3]))
                    }
                    "y" if nums.len() == 4 => {
                        ((nums[0], nums[1]), (nums[2], nums[3]), (nums[2], nums[3]))
                    }
                    _ => continue,
                };
                let p0 = cur_user;
                // Same rule as `l`: a curve with no current point is a no-op.
                if subpaths.is_empty() || subpath_dropped { continue; }
                let (d0x, d0y) = dev(&gs, p0.0, p0.1);
                let (c1x, c1y) = dev(&gs, p1.0, p1.1);
                let (c2x, c2y) = dev(&gs, p2.0, p2.1);
                let (c3x, c3y) = dev(&gs, p3.0, p3.1);
                // One non-finite control point makes every flattened vertex
                // non-finite: see `finite2`.
                if !(finite2((d0x, d0y)) && finite2((c1x, c1y))
                    && finite2((c2x, c2y)) && finite2((c3x, c3y)))
                {
                    continue;
                }
                // §8.5.2.1: a segment after a close starts a new subpath, anchored
                // at the closepoint the curve itself starts from.
                if subpath_closed {
                    if !reopen_subpath(&mut subpaths, &mut clip_path_ops, (d0x, d0y)) {
                        subpath_dropped = true;
                        continue;
                    }
                    subpath_closed = false;
                }
                let bez_steps = bezier_steps_for_flatness(
                    [(d0x, d0y), (c1x, c1y), (c2x, c2y), (c3x, c3y)],
                    gs.flatness,
                );
                for step in 1..=bez_steps {
                    let t = step as f64 / bez_steps as f64;
                    let (bx, by) = cubic_bezier(p0, p1, p2, p3, t);
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push(dev(&gs, bx, by));
                    }
                }
                cur_user = p3;
                // Record the exact cubic (device space) for bezier-retentive clips.
                clip_path_ops.push(PathOp::Cubic(c1x as f32, c1y as f32, c2x as f32, c2y as f32, c3x as f32, c3y as f32));
            }
            "re" => {
                let nums: Vec<f64> = o.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).collect();
                if nums.len() == 4 {
                    let (x, y, w, h) = (nums[0], nums[1], nums[2], nums[3]);
                    let (mx, my) = dev(&gs, x, y);
                    let (x1, y1d) = dev(&gs, x + w, y);
                    let (x2, y2d) = dev(&gs, x + w, y + h);
                    let (x3, y3d) = dev(&gs, x, y + h);
                    // See `finite2`: a non-finite corner makes the whole rect
                    // undrawable, and `re` is the usual shape of a `W n` clip.
                    if !(finite2((mx, my)) && finite2((x1, y1d))
                        && finite2((x2, y2d)) && finite2((x3, y3d)))
                    {
                        continue;
                    }
                    let rect = vec![
                        (mx, my),
                        (x1, y1d),
                        (x2, y2d),
                        (x3, y3d),
                        (mx, my),
                    ];
                    if subpaths.len() >= MAX_SUBPATHS { subpath_dropped = true; continue; }
                    subpaths.push(rect);
                    clip_path_ops.push(PathOp::Move(mx as f32, my as f32));
                    clip_path_ops.push(PathOp::Line(x1 as f32, y1d as f32));
                    clip_path_ops.push(PathOp::Line(x2 as f32, y2d as f32));
                    clip_path_ops.push(PathOp::Line(x3 as f32, y3d as f32));
                    clip_path_ops.push(PathOp::Close);
                    cur_user = (x, y);
                    start_user = (x, y);
                    // §8.5.2.1 Table 59 defines `re` as `… l h`, so the subpath is
                    // closed and terminated: a following segment starts a new one.
                    subpath_closed = true;
                    subpath_dropped = false;
                }
            }
            "h" => {
                // §8.5.2.1: `h` closes the CURRENT subpath; with no current point
                // there is nothing to close. Emitting a bare `Close` desynchronised
                // `clip_path_ops` from `subpaths` and moved the current point to a
                // stale `start_user`.
                if subpaths.is_empty() || subpath_dropped { continue; }
                // §8.5.2.1: "If the current subpath is already closed, h shall do
                // nothing." A second Close would also duplicate the path op.
                if subpath_closed { continue; }
                // P0 fix: avoid duplicate close point causing zero-length segment
                if let Some(sp) = subpaths.last_mut() {
                    let (sx, sy) = dev(&gs, start_user.0, start_user.1);
                    let skip_dup = sp.last().map(|&(px, py)| (px-sx).abs() < 1e-6 && (py-sy).abs() < 1e-6).unwrap_or(false);
                    if !skip_dup { sp.push((sx, sy)); }
                }
                clip_path_ops.push(PathOp::Close);
                cur_user = start_user;
                subpath_closed = true;
            }
            "S" | "s" => {
                // `s` closes first (§8.5.3.1). A subpath `re` or `h` already closed
                // must not get a second closing point.
                if op.operator == "s" && !subpath_closed {
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push(dev(&gs, start_user.0, start_user.1));
                    }
                }
                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                    let sm_start = prims.len();
                    if let Some(pid) = gs.stroke_pattern {
                        paint_pattern_stroke(doc, pid, &subpaths, &gs, &pattern_base_ctm, &colorspaces, prims, depth, clip_depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        emit_stroke(prims, &subpaths, &gs);
                    }
                    if let Some(m) = gs.soft_mask.clone() {
                        wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket, current_clip_bbox);
                    }
                }
                // §8.5.4: a pending `W` takes effect only AFTER the painting
                // operator that ends the path object — "the painting operation
                // shall be unaffected by the new clipping path". Committing it
                // first clipped a stroke to its own centreline, so `W S` came out
                // at half width.
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only);
                }
                subpaths.clear(); clip_path_ops.clear();
            }
            "f" | "F" | "f*" => {
                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                    let sm_start = prims.len();
                    if let Some(pid) = gs.fill_pattern {
                        paint_pattern_fill(doc, pid, &subpaths, op.operator == "f*", &pattern_base_ctm, gs.fill, gs.alpha_fill as f32, gs.blend_mode, &colorspaces, prims, depth, clip_depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        emit_fill(prims, &subpaths, gs.fill, op.operator == "f*", gs.alpha_fill, gs.blend_mode);
                    }
                    if let Some(m) = gs.soft_mask.clone() {
                        wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket, current_clip_bbox);
                    }
                }
                // §8.5.4: see the `S` arm — the clip lands after the paint.
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only);
                }
                subpaths.clear(); clip_path_ops.clear();
            }
            "B" | "B*" | "b" | "b*" => {
                if op.operator.starts_with('b') && !subpath_closed {
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push(dev(&gs, start_user.0, start_user.1));
                    }
                }
                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                    let sm_start = prims.len();
                    if let Some(pid) = gs.fill_pattern {
                        paint_pattern_fill(doc, pid, &subpaths, op.operator.ends_with('*'), &pattern_base_ctm, gs.fill, gs.alpha_fill as f32, gs.blend_mode, &colorspaces, prims, depth, clip_depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        emit_fill(prims, &subpaths, gs.fill, op.operator.ends_with('*'), gs.alpha_fill, gs.blend_mode);
                    }
                    if let Some(pid) = gs.stroke_pattern {
                        paint_pattern_stroke(doc, pid, &subpaths, &gs, &pattern_base_ctm, &colorspaces, prims, depth, clip_depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        emit_stroke(prims, &subpaths, &gs);
                    }
                    if let Some(m) = gs.soft_mask.clone() {
                        wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket, current_clip_bbox);
                    }
                }
                // §8.5.4: see the `S` arm — the clip lands after the paint.
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only);
                }
                subpaths.clear(); clip_path_ops.clear();
            }
            "n" => {
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only);
                }
                subpaths.clear(); clip_path_ops.clear();
            }
            "BI" => {
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only);
                }
                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                    if let Some(Object::Stream(stream)) = o.first() {
                        if let Some(img) = extract_inline_image(doc, stream, gs.fill, &colorspaces) {
                            let sm_start = prims.len();
                            if prims.len() < MAX_PRIMITIVES { prims.push(Prim::Image { ctm: gs.ctm, w: img.w, h: img.h, format: img.format, data: img.data, alpha: gs.alpha_fill as f32, blend: gs.blend_mode }); }
                            if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket, current_clip_bbox); }
                        }
                    }
                }
            }
            "Do" => {
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only);
                }
                if let Some(Object::Name(name)) = o.first() {
                    if let Some(&id) = xobjects.get(name) {
                        if let Ok(Object::Stream(stream)) = doc.get_object(id) {
                            // Skip the whole XObject if its optional-content group is OFF.
                            if stream.dict.get(b"OC").ok().map(|oc| oc_hidden!(oc)).unwrap_or(false) {
                                continue;
                            }
                            let subtype = stream
                                .dict
                                .get(b"Subtype")
                                .ok()
                                .and_then(|o| o.as_name().ok());
                            if subtype == Some(b"Image") {
                                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                                    if let Some(img) = extract_image(doc, stream, gs.fill, &colorspaces) {
                                        let sm_start = prims.len();
                                        if prims.len() < MAX_PRIMITIVES { prims.push(Prim::Image { ctm: gs.ctm, w: img.w, h: img.h, format: img.format, data: img.data, alpha: gs.alpha_fill as f32, blend: gs.blend_mode }); }
                                        if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket, current_clip_bbox); }
                                    }
                                }
                            } else if subtype == Some(b"Form")
                                && depth < 10
                                // §8.11.2: content in a disabled optional-content group
                                // shall not be drawn. The Image branch above is gated on
                                // this; the form recursion was NOT, and `oc_stack` is a
                                // local that no nested stream inherits, so
                                // `/OC1 BDC /Fm0 Do EMC` with OC1 OFF painted the form's
                                // ENTIRE contents. Worse, it painted them UNCLIPPED,
                                // because the `/BBox` ClipPush and the `GroupPush` below
                                // are both gated on this same flag while the recursion
                                // that needed them was not.
                                //
                                // `text_only` deliberately still descends. That caller is
                                // `search::build_index`, and the policy set in the `Tj`
                                // arm is that an OC-hidden layer is hidden, not absent:
                                // its text has to stay searchable. Suppressing ink and
                                // keeping the index is exactly what mode 3 achieves there.
                                && !(oc_stack.last().copied().unwrap_or(false) && !text_only)
                            {
                                // Bounds BRANCHING, which `depth < 10` above does
                                // not: see [`MAX_FORM_INVOCATIONS`]. Checked before
                                // anything is emitted, so exhausting it drops the
                                // whole `Do` cleanly rather than leaving a clip or
                                // group bracket half-open.
                                if !take_form_budget() {
                                    continue;
                                }
                                let form_matrix = stream
                                    .dict
                                    .get(b"Matrix")
                                    .ok()
                                    .and_then(|o| read_matrix_obj(deref(doc, o).unwrap_or(o)))
                                    .unwrap_or(IDENTITY);
                                let form_res = stream
                                    .dict
                                    .get(b"Resources")
                                    .ok()
                                    .and_then(|o| deref(doc, o))
                                    .and_then(|o| o.as_dict().ok())
                                    .cloned();
                                // Transparency group detection per Phase 4: /Group << /S /Transparency /I bool /K bool >>
                                let (is_transparency_group, isolated, knockout) = {
                                    if let Some(Object::Dictionary(gdict)) = stream.dict.get(b"Group").ok().and_then(|o| deref(doc,o).or(Some(o))).cloned() {
                                        let s = gdict.get(b"S").ok().and_then(|o| o.as_name().ok());
                                        if s == Some(b"Transparency") {
                                            let i = gdict.get(b"I").ok().and_then(|o| match o { Object::Boolean(b) => Some(*b), _=> None }).unwrap_or(false);
                                            let k = gdict.get(b"K").ok().and_then(|o| match o { Object::Boolean(b) => Some(*b), _=> None }).unwrap_or(false);
                                            (true, i, k)
                                        } else { (false,false,false) }
                                    } else { (false,false,false) }
                                };
                                // ExtGState soft mask active at this Do: bracket
                                // the form as the masked content and emit the /G
                                // group as the mask.
                                let active_smask = gs.soft_mask.clone();
                                let use_smask = active_smask.is_some()
                                    && !text_only
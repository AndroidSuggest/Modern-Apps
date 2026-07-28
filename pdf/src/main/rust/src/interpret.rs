use crate::*;

pub(crate) fn bezier_steps_for_flatness(flatness: f64) -> usize {
    // PDF spec: flatness is tolerance, higher => fewer steps (previously inverted)
    if flatness <= 0.0 {
        return BEZIER_STEPS;
    }
    let steps = (BEZIER_STEPS as f64 / (1.0 + flatness / 3.0)).round() as usize;
    steps.clamp(1, 32)
}

pub(crate) fn shoelace_area(pts: &[(f64,f64)]) -> f64 {
    if pts.len() < 3 { return 0.0; }
    let mut area = 0.0;
    for i in 0..pts.len() {
        let j = (i+1) % pts.len();
        area += pts[i].0 * pts[j].1 - pts[j].0 * pts[i].1;
    }
    area * 0.5
}

/// Parse ExtGState dash `D`: Spec §8.4.3.6 canonical is [[dashArray] phase] nested. Flat [a b c] lenient where last=phase only for len>=3 (critical fix: pure [3 3] must NOT become [3] phase 3).
pub(crate) fn parse_dash_d_array(doc: &Document, arr: &[Object]) -> (Vec<f64>, f64) {
    const MAX_DASH: usize = 64;
    if arr.is_empty() {
        return (Vec::new(), 0.0);
    }
    // Canonical nested [[dashArray] phase]
    if arr.len() == 2 {
        let first_is_arr = matches!(arr[0], Object::Array(_)) || matches!(deref(doc, &arr[0]), Some(Object::Array(_)));
        if first_is_arr {
            let inner = match &arr[0] {
                Object::Array(a) => a.clone(),
                _ => deref(doc, &arr[0]).and_then(|o| o.as_array().ok()).cloned().unwrap_or_default(),
            };
            let phase = deref(doc, &arr[1]).and_then(num).or_else(|| num(&arr[1])).unwrap_or(0.0);
            let dashes: Vec<f64> = inner.iter().filter_map(|o| deref(doc, o).and_then(num).or_else(|| num(o))).filter(|v| *v >= 0.0).take(MAX_DASH).collect();
            return (dashes, phase);
        }
    }
    // Check for any nested arrays
    let has_nested = arr.iter().any(|o| matches!(o, Object::Array(_)) || matches!(deref(doc, o), Some(Object::Array(_))));
    let nums: Vec<f64> = arr.iter().filter_map(|o| deref(doc, o).and_then(num).or_else(|| num(o))).collect();
    if has_nested {
        let mut dashes = Vec::new();
        for o in arr {
            match o {
                Object::Array(inner) => {
                    dashes.extend(inner.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).filter(|v| *v >= 0.0));
                }
                _ => {
                    if let Some(Object::Array(inner)) = deref(doc, o) {
                        dashes.extend(inner.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).filter(|v| *v >= 0.0));
                    }
                }
            }
        }
        if dashes.is_empty() {
            dashes = nums;
        }
        return (dashes.into_iter().filter(|v| *v >= 0.0).take(MAX_DASH).collect(), 0.0);
    }
    if nums.is_empty() {
        return (Vec::new(), 0.0);
    }
    // Lenient flat [dashes..., phase] only for len>=3 to avoid [3 3] bug
    if nums.len() >= 3 {
        let phase = nums.last().copied().unwrap_or(0.0);
        let dashes: Vec<f64> = nums[..nums.len()-1].iter().copied().filter(|v| *v >= 0.0).take(MAX_DASH).collect();
        (dashes, phase)
    } else {
        (nums.into_iter().filter(|v| *v >= 0.0).take(MAX_DASH).collect(), 0.0)
    }
}


pub(crate) fn parse_dash_extgstate(doc: &Document, obj: &Object) -> (Vec<f64>, f64) {
    match deref(doc, obj).unwrap_or(obj) {
        Object::Array(arr) => parse_dash_d_array(doc, arr),
        _ => (Vec::new(), 0.0),
    }
}

pub(crate) fn interpret_page(doc: &Document, page_id: ObjectId) -> Result<PageData, String> {
    let (width, height) = page_display_size(doc, page_id);
    let base = page_base_matrix(doc, page_id);

    let content = doc
        .get_and_decode_page_content(page_id)
        .map_err(|e| format!("decode content failed: {e:?}"))?;
    let res = resources_dict(doc, page_id);

    let mut prims = Vec::new();
    let mut init = GraphicsState::default();
    init.ctm = base;
    interpret_content(
        doc,
        &content.operations,
        res.as_ref(),
        init,
        &mut prims,
        0,
        false,
    );
    render_annotations(doc, page_id, &base, &mut prims);

    Ok(PageData {
        width,
        height,
        prims,
    })
}

/// Interpret a content stream (`ops`) against a `resources` dictionary into
/// drawing primitives, starting from `init` graphics state. Reused for page
/// content, form XObjects (`Do`), and annotation appearance streams. `depth`
/// bounds recursion through nested form XObjects.

pub(crate) fn interpret_content(
    doc: &Document,
    ops: &[lopdf::content::Operation],
    resources: Option<&lopdf::Dictionary>,
    init: GraphicsState,
    prims: &mut Vec<Prim>,
    depth: u32,
    text_only: bool,
) {
    let fonts = resources
        .map(|r| fonts_from_resources(doc, r))
        .unwrap_or_default();
    let xobjects = resources
        .map(|r| xobjects_from_resources(doc, r))
        .unwrap_or_default();
    let extgstates = resources
        .map(|r| extgstates_from_resources(doc, r))
        .unwrap_or_default();
    let colorspaces = resources
        .map(|r| colorspaces_from_resources(doc, r))
        .unwrap_or_default();
    let shadings = resources
        .map(|r| shadings_from_resources(doc, r))
        .unwrap_or_default();
    let patterns = resources
        .map(|r| patterns_from_resources(doc, r))
        .unwrap_or_default();

    let mut gs = init;
    // Pattern matrices are relative to the coordinate system in effect when this
    // content stream begins (the page default CTM, or the form's CTM).
    let pattern_base_ctm = gs.ctm;
    #[derive(Clone)]
    struct SavedState {
        gs: GraphicsState,
        clip_depth: usize,
        group_depth: usize,
    }
    let mut stack: Vec<SavedState> = Vec::new();
    let mut q_overflow: usize = 0;

    struct PendingClip {
        even_odd: bool,
        polys: Vec<Vec<(f64,f64)>>,
        path_ops: Vec<PathOp>,
    }

    // single ClipPush per W op preserving holes via full path_ops (fix high #7)
    #[inline]
    fn emit_one_clip(prims: &mut Vec<Prim>, pc: PendingClip, clip_depth: &mut usize, text_only: bool, oc_hidden: bool) {
        if text_only || oc_hidden { return; }
        if *clip_depth >= MAX_CLIP_DEPTH { return; }
        let has_valid = pc.polys.iter().any(|poly| poly.len() >= 3 && shoelace_area(poly).abs() >= 1e-3) || !pc.path_ops.is_empty();
        if !has_valid && pc.polys.is_empty() && pc.path_ops.is_empty() { return; }
        let pts: Vec<(f32,f32)> = pc.polys.first().map(|p| p.iter().map(|&(x,y)| (x as f32, y as f32)).collect()).unwrap_or_default();
        let po = if pc.path_ops.is_empty() { None } else { Some(pc.path_ops) };
        prims.push(Prim::ClipPush { even_odd: pc.even_odd, pts, path_ops: po });
        *clip_depth += 1;
    }

    let mut text_matrix = IDENTITY;
    let mut line_matrix = IDENTITY;
    let mut leading = 0.0_f64;

    let mut subpaths: Vec<Vec<(f64, f64)>> = Vec::new();
    let mut cur_user: (f64, f64) = (0.0, 0.0);
    let mut start_user: (f64, f64) = (0.0, 0.0);
    // OCG visibility stack: true=visible, false=hidden (marked content /OC)
    let mut oc_stack: Vec<bool> = Vec::new(); // true means currently invisible due to OCG suppression
    let mut group_depth: usize = 0;
    let mut pending_clip: Option<PendingClip> = None;
    let mut clip_depth: usize = 0;
    let mut clip_path_ops: Vec<PathOp> = Vec::new(); // current clip path ops before W
    // Whether the current text object (BT..ET) used a clip render mode (Tr 4-7).
    let mut text_clip_used = false;

    let dev = |gs: &GraphicsState, x: f64, y: f64| transform(&gs.ctm, x, y);

    for op in ops {
        let o = &op.operands;
        match op.operator.as_str() {
            "q" => {
                const MAX_SUBPATHS_LOCAL: usize = 20000;
                if stack.len() < MAX_GRAPHICS_STACK {
                    stack.push(SavedState { gs: gs.clone(), clip_depth, group_depth });
                } else {
                    q_overflow += 1;
                }
                if subpaths.len() > MAX_SUBPATHS_LOCAL {
                    subpaths.truncate(MAX_SUBPATHS_LOCAL);
                }
            }
            "Q" => {
                if q_overflow > 0 {
                    q_overflow -= 1;
                } else if let Some(saved) = stack.pop() {
                    while clip_depth > saved.clip_depth {
                        if !text_only { prims.push(Prim::ClipPop); }
                        clip_depth = clip_depth.saturating_sub(1);
                    }
                    while group_depth > saved.group_depth {
                        if !text_only { prims.push(Prim::GroupPop); }
                        group_depth = group_depth.saturating_sub(1);
                    }
                    gs = saved.gs;
                }
            }
            "cm" => {
                if let Some(m) = read_matrix(o) {
                    gs.ctm = mat_mul(&m, &gs.ctm);
                }
            }
            "w" => {
                if let Some(v) = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x))) { gs.line_width = v; }
            }
            "J" => {
                if let Some(v) = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x))) { gs.line_cap = (v as i64).clamp(0,2) as u8; }
            }
            "j" => {
                if let Some(v) = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x))) { gs.line_join = (v as i64).clamp(0,2) as u8; }
            }
            "M" => {
                if let Some(v) = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x))) { gs.miter_limit = v; }
            }
            "i" => {
                if let Some(v) = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x))) { gs.flatness = v.clamp(0.0, 100.0); }
            }
            "d" => {
                const MAX_DASH_LEN: usize = 64;
                let dash_obj = o.first().and_then(|x| deref(doc, x).or(Some(x)));
                let mut dashes = if let Some(Object::Array(arr)) = dash_obj {
                    arr.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).filter(|v| *v >= 0.0).take(MAX_DASH_LEN).collect()
                } else { Vec::new() };
                if dashes.len() % 2 == 1 && !dashes.is_empty() { let cl = dashes.clone(); dashes.extend(cl); if dashes.len() > MAX_DASH_LEN { dashes.truncate(MAX_DASH_LEN); } }
                gs.dash = dashes;
                gs.dash_phase = o.get(1).and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x))).unwrap_or(0.0);
            }
            "gs" => {
                if let Some(Object::Name(name)) = o.first() {
                    let inline_dict = resources.and_then(|r| {
                        r.get(b"ExtGState").ok()
                            .and_then(|o| deref(doc, o))
                            .and_then(|o| o.as_dict().ok())
                            .and_then(|d| d.get(name).ok())
                            .and_then(|o| deref(doc, o))
                            .and_then(|o| o.as_dict().ok())
                    });
                    // Helper to apply a dict to gs
                    let apply_dict = |dict: &lopdf::Dictionary, gs: &mut GraphicsState, doc: &Document| {
                        // ISO 32000: /CA is the stroking alpha, /ca is the nonstroking (fill) alpha.
                        if let Some(v) = dict.get(b"CA").ok().and_then(num) {
                            gs.alpha_stroke = v.clamp(0.0,1.0);
                        }
                        if let Some(v) = dict.get(b"ca").ok().and_then(num) {
                            gs.alpha_fill = v.clamp(0.0,1.0);
                        }
                        if let Some(v) = dict.get(b"LW").ok().and_then(num) {
                            gs.line_width = v;
                        }
                        if let Some(v) = dict.get(b"LC").ok().and_then(num) {
                            gs.line_cap = (v as i64).clamp(0,2) as u8;
                        }
                        if let Some(v) = dict.get(b"LJ").ok().and_then(num) {
                            gs.line_join = (v as i64).clamp(0,2) as u8;
                        }
                        if let Some(v) = dict.get(b"ML").ok().and_then(num) {
                            gs.miter_limit = v;
                        }
                        if let Some(d_obj) = dict.get(b"D").ok().and_then(|obj| deref(doc, obj).or(Some(obj))) {
                            // P1 fix: /D [] 0 must reset to solid (was previously ignored)
                            let (dashes, phase) = parse_dash_extgstate(doc, d_obj);
                            gs.dash = dashes;
                            gs.dash_phase = phase;
                        }
                        if let Some(bm_obj) = dict.get(b"BM").ok().and_then(|obj| deref(doc, obj).or(Some(obj))) {
                            if let Ok(n) = bm_obj.as_name() {
                                gs.blend_mode = BlendMode::from_name(n);
                            } else if let Ok(arr) = bm_obj.as_array() {
                                // BM can be array, take first that is not Normal
                                for el in arr {
                                    if let Ok(n) = el.as_name() {
                                        let bm = BlendMode::from_name(n);
                                        if bm != BlendMode::Normal {
                                            gs.blend_mode = bm;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        // Overprint: /OP (stroking), /op (nonstroking; defaults
                        // to /OP when absent), /OPM (overprint mode 0|1).
                        if let Some(b) = dict.get(b"OP").ok().and_then(|o| deref(doc, o).or(Some(o))).and_then(|o| match o { Object::Boolean(v) => Some(*v), _ => None }) {
                            gs.overprint_stroke = b;
                            gs.overprint_fill = b; // op defaults to OP unless overridden below
                        }
                        if let Some(b) = dict.get(b"op").ok().and_then(|o| deref(doc, o).or(Some(o))).and_then(|o| match o { Object::Boolean(v) => Some(*v), _ => None }) {
                            gs.overprint_fill = b;
                        }
                        if let Some(v) = dict.get(b"OPM").ok().and_then(num) {
                            gs.overprint_mode = (v as i64).clamp(0, 1) as u8;
                        }
                        // Soft mask: /SMask /None clears; dict may have /G as Ref OR direct Stream (P0 fix)
                        if let Some(sm_raw) = dict.get(b"SMask").ok() {
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
                                        gs.soft_mask = Some(SoftMask { group_id: gid, mask_type, ctm: gs.ctm, backdrop });
                                    }
                                }
                            }
                        }
                    };
                    if let Some(&id) = extgstates.get(name) {
                        if let Ok(dict) = doc.get_dictionary(id) {
                            // Clone dict data to avoid borrow across closure capture mutable gs
                            let dict_clone = dict.clone();
                            apply_dict(&dict_clone, &mut gs, doc);
                        }
                    } else if let Some(dict) = inline_dict {
                        let dict_clone = dict.clone();
                        apply_dict(&dict_clone, &mut gs, doc);
                    }
                }
            }
            "W" => {
                // P0 fix: emit as single ClipPush preserving holes via path_ops (was per-poly loop)
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, text_only, oc_stack.last().copied().unwrap_or(false));
                }
                pending_clip = Some(PendingClip { even_odd: false, polys: subpaths.clone(), path_ops: clip_path_ops.clone() });
            }
            "W*" => {
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, text_only, oc_stack.last().copied().unwrap_or(false));
                }
                pending_clip = Some(PendingClip { even_odd: true, polys: subpaths.clone(), path_ops: clip_path_ops.clone() });
            }
            "m" => {
                if let (Some(x), Some(y)) = (o.first().and_then(num), o.get(1).and_then(num)) {
                    cur_user = (x, y);
                    start_user = (x, y);
                    let (dx, dy) = dev(&gs, x, y);
                    subpaths.push(vec![(dx, dy)]);
                    clip_path_ops.push(PathOp::Move(dx as f32, dy as f32));
                }
            }
            "l" => {
                if let (Some(x), Some(y)) = (o.first().and_then(num), o.get(1).and_then(num)) {
                    cur_user = (x, y);
                    let (dx, dy) = dev(&gs, x, y);
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push((dx, dy));
                    } else {
                        subpaths.push(vec![(dx, dy)]);
                    }
                    clip_path_ops.push(PathOp::Line(dx as f32, dy as f32));
                }
            }
            "c" | "v" | "y" => {
                let nums: Vec<f64> = o.iter().filter_map(num).collect();
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
                let bez_steps = bezier_steps_for_flatness(gs.flatness);
                for step in 1..=bez_steps {
                    let t = step as f64 / bez_steps as f64;
                    let (bx, by) = cubic_bezier(p0, p1, p2, p3, t);
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push(dev(&gs, bx, by));
                    }
                }
                cur_user = p3;
                // Record the exact cubic (device space) for bezier-retentive clips.
                let (c1x, c1y) = dev(&gs, p1.0, p1.1);
                let (c2x, c2y) = dev(&gs, p2.0, p2.1);
                let (c3x, c3y) = dev(&gs, p3.0, p3.1);
                clip_path_ops.push(PathOp::Cubic(c1x as f32, c1y as f32, c2x as f32, c2y as f32, c3x as f32, c3y as f32));
            }
            "re" => {
                let nums: Vec<f64> = o.iter().filter_map(num).collect();
                if nums.len() == 4 {
                    let (x, y, w, h) = (nums[0], nums[1], nums[2], nums[3]);
                    let rect = vec![
                        dev(&gs, x, y),
                        dev(&gs, x + w, y),
                        dev(&gs, x + w, y + h),
                        dev(&gs, x, y + h),
                        dev(&gs, x, y),
                    ];
                    subpaths.push(rect);
                    let (mx, my) = dev(&gs, x, y);
                    let (x1, y1d) = dev(&gs, x + w, y);
                    let (x2, y2d) = dev(&gs, x + w, y + h);
                    let (x3, y3d) = dev(&gs, x, y + h);
                    clip_path_ops.push(PathOp::Move(mx as f32, my as f32));
                    clip_path_ops.push(PathOp::Line(x1 as f32, y1d as f32));
                    clip_path_ops.push(PathOp::Line(x2 as f32, y2d as f32));
                    clip_path_ops.push(PathOp::Line(x3 as f32, y3d as f32));
                    clip_path_ops.push(PathOp::Close);
                    cur_user = (x, y);
                    start_user = (x, y);
                }
            }
            "h" => {
                if let Some(sp) = subpaths.last_mut() {
                    sp.push(dev(&gs, start_user.0, start_user.1));
                }
                clip_path_ops.push(PathOp::Close);
                cur_user = start_user;
            }
            "S" | "s" => {
                if let Some(to_emit) = pending_clip.take() {
                    for poly in to_emit.polys.iter() {
                        if poly.len() >= 3 && !text_only && !oc_stack.last().copied().unwrap_or(false) && clip_depth < MAX_CLIP_DEPTH && shoelace_area(poly).abs() >= 1e-3 {
                            prims.push(Prim::ClipPush {
                                even_odd: to_emit.even_odd,
                                pts: poly.iter().map(|&(x,y)| (x as f32, y as f32)).collect(),
                                path_ops: { let ops = to_emit.path_ops.clone(); if ops.is_empty() { None } else { Some(ops) } },
                            });
                            clip_depth += 1;
                        }
                    }
                }
                if op.operator == "s" {
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push(dev(&gs, start_user.0, start_user.1));
                    }
                }
                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                    let sm_start = prims.len();
                    if let Some(pid) = gs.stroke_pattern {
                        paint_pattern_stroke(doc, pid, &subpaths, &gs, &pattern_base_ctm, prims, depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        emit_stroke(prims, &subpaths, &gs);
                    }
                    if let Some(m) = gs.soft_mask.clone() {
                        wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth);
                    }
                }
                subpaths.clear(); clip_path_ops.clear();
            }
            "f" | "F" | "f*" => {
                if let Some(to_emit) = pending_clip.take() {
                    for poly in to_emit.polys.iter() {
                        if poly.len() >=3 && !text_only && !oc_stack.last().copied().unwrap_or(false) && clip_depth < MAX_CLIP_DEPTH && shoelace_area(poly).abs() >= 1e-3 {
                            prims.push(Prim::ClipPush {
                                even_odd: to_emit.even_odd,
                                pts: poly.iter().map(|&(x,y)| (x as f32, y as f32)).collect(),
                                path_ops: { let ops = to_emit.path_ops.clone(); if ops.is_empty() { None } else { Some(ops) } },
                            });
                            clip_depth+=1;
                        }
                    }
                }
                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                    let sm_start = prims.len();
                    if let Some(pid) = gs.fill_pattern {
                        paint_pattern_fill(doc, pid, &subpaths, op.operator == "f*", &pattern_base_ctm, gs.fill, prims, depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        let fb = if gs.overprint_fill && gs.blend_mode == BlendMode::Normal { BlendMode::Multiply } else { gs.blend_mode };
                        emit_fill(prims, &subpaths, gs.fill, op.operator == "f*", gs.alpha_fill, fb);
                    }
                    if let Some(m) = gs.soft_mask.clone() {
                        wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth);
                    }
                }
                subpaths.clear(); clip_path_ops.clear();
            }
            "B" | "B*" | "b" | "b*" => {
                if let Some(to_emit) = pending_clip.take() {
                    for poly in to_emit.polys.iter() {
                        if poly.len()>=3 && !text_only && !oc_stack.last().copied().unwrap_or(false) && clip_depth < MAX_CLIP_DEPTH && shoelace_area(poly).abs() >= 1e-3 {
                            prims.push(Prim::ClipPush {
                                even_odd: to_emit.even_odd,
                                pts: poly.iter().map(|&(x,y)| (x as f32, y as f32)).collect(),
                                path_ops: { let ops = to_emit.path_ops.clone(); if ops.is_empty() { None } else { Some(ops) } },
                            });
                            clip_depth+=1;
                        }
                    }
                }
                if op.operator.starts_with('b') {
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push(dev(&gs, start_user.0, start_user.1));
                    }
                }
                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                    let sm_start = prims.len();
                    if let Some(pid) = gs.fill_pattern {
                        paint_pattern_fill(doc, pid, &subpaths, op.operator.ends_with('*'), &pattern_base_ctm, gs.fill, prims, depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        let fb = if gs.overprint_fill && gs.blend_mode == BlendMode::Normal { BlendMode::Multiply } else { gs.blend_mode };
                        emit_fill(prims, &subpaths, gs.fill, op.operator.ends_with('*'), gs.alpha_fill, fb);
                    }
                    if let Some(pid) = gs.stroke_pattern {
                        paint_pattern_stroke(doc, pid, &subpaths, &gs, &pattern_base_ctm, prims, depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        emit_stroke(prims, &subpaths, &gs);
                    }
                    if let Some(m) = gs.soft_mask.clone() {
                        wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth);
                    }
                }
                subpaths.clear(); clip_path_ops.clear();
            }
            "n" => {
                if let Some(to_emit) = pending_clip.take() {
                    for poly in to_emit.polys.iter() {
                        if poly.len()>=3 && !text_only && !oc_stack.last().copied().unwrap_or(false) && clip_depth < MAX_CLIP_DEPTH && shoelace_area(poly).abs() >= 1e-3 {
                            prims.push(Prim::ClipPush {
                                even_odd: to_emit.even_odd,
                                pts: poly.iter().map(|&(x,y)| (x as f32, y as f32)).collect(),
                                path_ops: { let ops = to_emit.path_ops.clone(); if ops.is_empty() { None } else { Some(ops) } },
                            });
                            clip_depth+=1;
                        }
                    }
                }
                subpaths.clear(); clip_path_ops.clear();
            }
            "BI" => {
                if let Some(to_emit) = pending_clip.take() {
                    for poly in to_emit.polys.iter() {
                        if poly.len()>=3 && !text_only && !oc_stack.last().copied().unwrap_or(false) && clip_depth < MAX_CLIP_DEPTH && shoelace_area(poly).abs() >= 1e-3 {
                            prims.push(Prim::ClipPush { even_odd: to_emit.even_odd, pts: poly.iter().map(|&(x,y)| (x as f32, y as f32)).collect(), path_ops: { let ops = to_emit.path_ops.clone(); if ops.is_empty() { None } else { Some(ops) } } });
                            clip_depth+=1;
                        }
                    }
                }
                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                    if let Some(Object::Stream(stream)) = o.first() {
                        if let Some(img) = extract_inline_image(doc, stream, gs.fill, &colorspaces) {
                            let sm_start = prims.len();
                            if prims.len() < MAX_PRIMITIVES { prims.push(Prim::Image { ctm: gs.ctm, w: img.w, h: img.h, format: img.format, data: img.data, alpha: 1.0 }); }
                            if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth); }
                        }
                    }
                }
            }
            "Do" => {
                if let Some(to_emit) = pending_clip.take() {
                    for poly in to_emit.polys.iter() {
                        if poly.len()>=3 && !text_only && !oc_stack.last().copied().unwrap_or(false) && clip_depth < MAX_CLIP_DEPTH && shoelace_area(poly).abs() >= 1e-3 {
                            prims.push(Prim::ClipPush { even_odd: to_emit.even_odd, pts: poly.iter().map(|&(x,y)| (x as f32, y as f32)).collect(), path_ops: { let ops = to_emit.path_ops.clone(); if ops.is_empty() { None } else { Some(ops) } } });
                            clip_depth+=1;
                        }
                    }
                }
                if let Some(Object::Name(name)) = o.first() {
                    if let Some(&id) = xobjects.get(name) {
                        if let Ok(Object::Stream(stream)) = doc.get_object(id) {
                            let subtype = stream
                                .dict
                                .get(b"Subtype")
                                .ok()
                                .and_then(|o| o.as_name().ok());
                            if subtype == Some(b"Image") {
                                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                                    if let Some(img) = extract_image(doc, stream, gs.fill, &colorspaces) {
                                        let sm_start = prims.len();
                                        if prims.len() < MAX_PRIMITIVES { prims.push(Prim::Image { ctm: gs.ctm, w: img.w, h: img.h, format: img.format, data: img.data, alpha: 1.0 }); }
                                        if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth); }
                                    }
                                }
                            } else if subtype == Some(b"Form") && depth < 10 {
                                let form_matrix = stream
                                    .dict
                                    .get(b"Matrix")
                                    .ok()
                                    .and_then(read_matrix_obj)
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
                                    if let Some(gobj) = stream.dict.get(b"Group").ok().and_then(|o| deref(doc,o).or(Some(o))).cloned() {
                                        if let Object::Dictionary(gdict) = gobj {
                                            let s = gdict.get(b"S").ok().and_then(|o| o.as_name().ok());
                                            if s == Some(b"Transparency") {
                                                let i = gdict.get(b"I").ok().and_then(|o| match o { Object::Boolean(b) => Some(*b), _=> None }).unwrap_or(false);
                                                let k = gdict.get(b"K").ok().and_then(|o| match o { Object::Boolean(b) => Some(*b), _=> None }).unwrap_or(false);
                                                (true, i, k)
                                            } else { (false,false,false) }
                                        } else { (false,false,false) }
                                    } else { (false,false,false) }
                                };
                                // ExtGState soft mask active at this Do: bracket
                                // the form as the masked content and emit the /G
                                // group as the mask. The soft-mask layer provides
                                // isolation, so skip the form's own group push.
                                let active_smask = gs.soft_mask.clone();
                                let use_smask = active_smask.is_some()
                                    && !text_only
                                    && !oc_stack.last().copied().unwrap_or(false)
                                    && prims.len() < crate::MAX_PRIMITIVES
                                    && depth < crate::MAX_PATTERN_RECURSION as u32;
                                let sm_start = prims.len();
                                let should_emit_group = is_transparency_group && !use_smask && !text_only && !oc_stack.last().copied().unwrap_or(false) && depth < crate::MAX_PATTERN_RECURSION as u32;
                                if should_emit_group {
                                    if prims.len() < crate::MAX_PRIMITIVES && group_depth < 32 {
                                        // The nonstroking constant alpha (ca) applies to the
                                        // group as a whole when it is painted; NOT ca*CA.
                                        prims.push(Prim::GroupPush { isolated, knockout, alpha: gs.alpha_fill as f32, blend: gs.blend_mode });
                                        group_depth+=1;
                                    }
                                }
                                if let Ok(sub) = Content::decode(&stream_data_with_doc(doc, &stream)) {
                                        let mut sub_gs = gs.clone();
                                        sub_gs.ctm = mat_mul(&form_matrix, &gs.ctm);
                                        // A soft mask does not re-apply to nested Do's inside the
                                        // (masked) form content.
                                        sub_gs.soft_mask = None;
                                        // Per PDF 11.6.6: on entering a transparency group the
                                        // alpha constants reset to 1.0 and blend to Normal — they
                                        // are applied when the group's result is composited (via
                                        // GroupPush), not again to each element inside. Without
                                        // this, ca is double-applied and low-alpha groups vanish.
                                        if should_emit_group {
                                            sub_gs.alpha_fill = 1.0;
                                            sub_gs.alpha_stroke = 1.0;
                                            sub_gs.blend_mode = BlendMode::Normal;
                                        }
                                        let res_ref = form_res.as_ref().or(resources);
                                        interpret_content(
                                            doc,
                                            &sub.operations,
                                            res_ref,
                                            sub_gs,
                                            prims,
                                            depth + 1,
                                            text_only,
                                        );
                                }
                                // Bracket the whole form as the masked content, then
                                // append the mask group (rendered at the mask's set-time CTM).
                                if use_smask {
                                    if let Some(mask) = active_smask {
                                        wrap_with_soft_mask(prims, sm_start, doc, resources, &mask, depth);
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
                        gs.stroke_cs = kind;
                    }
                    gs.stroke_pattern = None;
                }
            }
            "cs" => {
                if let Some(cs_name) = o.first() {
                    if let Some(kind) = parse_named_cs(doc, cs_name, resources, &colorspaces) {
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
                }).filter(|b| b[2] > b[0] && b[3] > b[1]);
                if let Some(to_emit) = pending_clip.take() {
                    for poly in to_emit.polys.iter() {
                        if poly.len()>=3 && !text_only && !oc_stack.last().copied().unwrap_or(false) && clip_depth < MAX_CLIP_DEPTH && shoelace_area(poly).abs() >= 1e-3 {
                            prims.push(Prim::ClipPush { even_odd: to_emit.even_odd, pts: poly.iter().map(|&(x,y)| (x as f32, y as f32)).collect(), path_ops: { let ops = to_emit.path_ops.clone(); if ops.is_empty() { None } else { Some(ops) } } });
                            clip_depth+=1;
                        }
                    }
                }
                if !text_only {
                    if let Some(Object::Name(name)) = o.first() {
                        if let Some(&id) = shadings.get(name) {
                            if let Ok(obj) = doc.get_object(id) {
                                if let Some((ctm,w,h,data)) = rasterize_shading(doc, obj, &gs.ctm, &colorspaces, 0, clip_bbox_device) {
                                    if prims.len() < MAX_PRIMITIVES && !oc_stack.last().copied().unwrap_or(false) {
                                        let sm_start = prims.len();
                                        prims.push(Prim::Image { ctm, w, h, format: 0, data, alpha: 1.0 });
                                        if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth); }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "BMC" => {
                if let Some(&hidden) = oc_stack.last() {
                    if hidden && oc_stack.len() < MAX_OC_STACK {
                        oc_stack.push(true);
                    }
                }
            }
            "BDC" | "MP" | "DP" => {
                let mut should_hide = false;
                if let Some(prop_obj) = o.get(1) {
                    let prop_deref = deref(doc, prop_obj).unwrap_or(prop_obj);
                    let resolve_oc_ref = |obj: &Object, doc: &Document| -> Option<bool> {
                        match deref(doc, obj).unwrap_or(obj) {
                            Object::Reference(id) => Some(!is_ocg_visible(doc, *id)),
                            _ => None
                        }
                    };
                    if let Object::Dictionary(d) = prop_deref {
                        if let Some(oc_obj) = d.get(b"OC").ok().and_then(|ob| deref(doc,ob).or(Some(ob))) {
                            if let Some(h) = resolve_oc_ref(oc_obj, doc) { should_hide = h; }
                        }
                    } else if let Object::Name(n) = prop_deref {
                        if let Some(res_dict) = resources {
                            if let Some(prop_dict) = res_dict.get(b"Properties").ok().and_then(|ob| deref(doc,ob)).and_then(|ob| ob.as_dict().ok()) {
                                if let Some(ocg_dict_obj) = prop_dict.get(n).ok().and_then(|ob| deref(doc,ob).or(Some(ob))) {
                                    if let Object::Dictionary(pd) = ocg_dict_obj {
                                        if let Some(oc_obj) = pd.get(b"OC").ok().and_then(|ob| deref(doc,ob).or(Some(ob))) {
                                            if let Some(h) = resolve_oc_ref(oc_obj, doc) { should_hide = h; }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if should_hide {
                    if oc_stack.len() < MAX_OC_STACK { oc_stack.push(true); }
                } else {
                    if let Some(&hidden) = oc_stack.last() {
                        if hidden && oc_stack.len() < MAX_OC_STACK { oc_stack.push(true); }
                    }
                }
            }
            "EMC" => {
                if !oc_stack.is_empty() { oc_stack.pop(); }
            }
            "d0" | "d1" => {
                // Type3 glyph width+bbox: record if inside Type3 context (in draw.rs)
                // Here at top-level content stream, these are explicit no-ops per spec
                // outside charproc, but we honor d1/d0 as no-op without advancing pen.
            }
            "BT" => {
                if let Some(to_emit) = pending_clip.take() {
                    for poly in to_emit.polys.iter() {
                        if poly.len()>=3 && !text_only && !oc_stack.last().copied().unwrap_or(false) && clip_depth < MAX_CLIP_DEPTH && shoelace_area(poly).abs() >= 1e-3 {
                            prims.push(Prim::ClipPush { even_odd: to_emit.even_odd, pts: poly.iter().map(|&(x,y)| (x as f32, y as f32)).collect(), path_ops: { let ops = to_emit.path_ops.clone(); if ops.is_empty() { None } else { Some(ops) } } });
                            clip_depth+=1;
                        }
                    }
                }
                text_matrix = IDENTITY;
                line_matrix = IDENTITY;
                text_clip_used = false;
            }
            "ET" => {
                // If the text object added glyphs to the clip (Tr 4-7), apply it.
                if text_clip_used && !text_only && !oc_stack.last().copied().unwrap_or(false) {
                    if prims.len() < MAX_PRIMITIVES {
                        prims.push(Prim::TextClipApply);
                        clip_depth += 1;
                    }
                }
                text_clip_used = false;
            }
            "Tf" => {
                if let Some(Object::Name(name)) = o.first() {
                    gs.font_key = name.clone();
                }
                if let Some(sz) = o.get(1).and_then(num) {
                    gs.font_size = sz;
                }
            }
            "TL" => {
                if let Some(v) = o.first().and_then(num) {
                    leading = v;
                }
            }
            "Tc" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.char_spacing = v;
                }
            }
            "Tw" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.word_spacing = v;
                }
            }
            "Tz" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.h_scale = v / 100.0;
                }
            }
            "Ts" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.rise = v;
                }
            }
            "Tr" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.render_mode = v as i64;
                    if gs.render_mode >= 4 {
                        text_clip_used = true;
                    }
                }
            }
            "Td" => {
                if let (Some(tx), Some(ty)) = (o.first().and_then(num), o.get(1).and_then(num)) {
                    line_matrix = mat_mul(&translate(tx, ty), &line_matrix);
                    text_matrix = line_matrix;
                }
            }
            "TD" => {
                if let (Some(tx), Some(ty)) = (o.first().and_then(num), o.get(1).and_then(num)) {
                    leading = -ty;
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
                line_matrix = mat_mul(&translate(0.0, -leading), &line_matrix);
                text_matrix = line_matrix;
            }
            "Tj" => {
                if let Some(Object::String(bytes, _)) = o.first() {
                    let sm_start = prims.len();
                    let adv = show_string(doc, prims, &gs, &fonts, &text_matrix, bytes, depth);
                    text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                    if gs.render_mode <= 2 { if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth); } }
                }
            }
            "'" => {
                line_matrix = mat_mul(&translate(0.0, -leading), &line_matrix);
                text_matrix = line_matrix;
                if let Some(Object::String(bytes, _)) = o.first() {
                    let adv = show_string(doc, prims, &gs, &fonts, &text_matrix, bytes, depth);
                    text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                }
            }
            "\"" => {
                if let Some(aw) = o.first().and_then(num) {
                    gs.word_spacing = aw;
                }
                if let Some(ac) = o.get(1).and_then(num) {
                    gs.char_spacing = ac;
                }
                line_matrix = mat_mul(&translate(0.0, -leading), &line_matrix);
                text_matrix = line_matrix;
                if let Some(Object::String(bytes, _)) = o.get(2) {
                    let adv = show_string(doc, prims, &gs, &fonts, &text_matrix, bytes, depth);
                    text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                }
            }
            "TJ" => {
                let sm_start = prims.len();
                if let Some(Object::Array(arr)) = o.first() {
                    for el in arr {
                        match el {
                            Object::String(bytes, _) => {
                                let adv =
                                    show_string(doc, prims, &gs, &fonts, &text_matrix, bytes, depth);
                                text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                            }
                            Object::Integer(_) | Object::Real(_) => {
                                let n = num(el).unwrap_or(0.0);
                                let tx = -n / 1000.0 * gs.font_size * gs.h_scale;
                                text_matrix = mat_mul(&translate(tx, 0.0), &text_matrix);
                            }
                            _ => {}
                        }
                    }
                }
                if gs.render_mode <= 2 { if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth); } }
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
    if n.len() == 6 {
        Some([n[0], n[1], n[2], n[3], n[4], n[5]])
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

/// Paint a tiling/shading pattern along a stroked path. The stroke is converted
/// to outline quads (`stroke_outline_quads`); each quad is clipped independently
/// and the pattern rasterized within it, so the painted region is the union of
/// Render an ExtGState soft-mask group into `prims` as the mask content of a
/// SoftMaskPush/Content/Pop bracket. The group is placed at the CTM captured
/// when the mask was set. For a luminosity mask with a `/BC` backdrop, a
/// backdrop-colored rectangle is painted first so uncovered areas take the
/// backdrop luminance (default black otherwise).
pub(crate) fn render_soft_mask_group(
    doc: &Document,
    resources: Option<&lopdf::Dictionary>,
    mask: &SoftMask,
    prims: &mut Vec<Prim>,
    depth: u32,
) {
    if depth >= MAX_PATTERN_RECURSION as u32 || prims.len() >= MAX_PRIMITIVES {
        return;
    }
    let mstream = match doc.get_object(mask.group_id) {
        Ok(Object::Stream(s)) => s.clone(),
        _ => return,
    };
    let mmatrix = mstream.dict.get(b"Matrix").ok().and_then(read_matrix_obj).unwrap_or(IDENTITY);
    let group_ctm = mat_mul(&mmatrix, &mask.ctm);
    let mres = mstream.dict.get(b"Resources").ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .cloned();
    // /BC backdrop rectangle for luminosity masks.
    if mask.mask_type == 1 {
        if let Some(bc) = &mask.backdrop {
            if let Some(rect) = mstream.dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)) {
                let cs = mstream.dict.get(b"Group").ok().and_then(|o| deref(doc, o))
                    .and_then(|o| o.as_dict().ok())
                    .and_then(|gd| gd.get(b"CS").ok().and_then(|o| parse_cs_kind(doc, Some(o), &HashMap::new())));
                let argb = cs.as_ref()
                    .and_then(|k| eval_cs_to_rgb(doc, k, bc, &HashMap::new()))
                    .unwrap_or_else(|| match bc.len() {
                        1 => gray_to_argb(bc[0]),
                        3 => rgb_to_argb(bc[0], bc[1], bc[2]),
                        4 => cmyk_to_argb(bc[0], bc[1], bc[2], bc[3]),
                        _ => 0xFF00_0000,
                    });
                let corners = [
                    transform(&group_ctm, rect[0], rect[1]),
                    transform(&group_ctm, rect[2], rect[1]),
                    transform(&group_ctm, rect[2], rect[3]),
                    transform(&group_ctm, rect[0], rect[3]),
                ];
                let poly: Vec<(f64, f64)> = corners.to_vec();
                emit_fill(prims, std::slice::from_ref(&poly), argb, false, 1.0, BlendMode::Normal);
            }
        }
    }
    if let Ok(msub) = Content::decode(&stream_data_with_doc(doc, &mstream)) {
        let mut mgs = GraphicsState::default();
        mgs.ctm = group_ctm;
        mgs.soft_mask = None;
        mgs.blend_mode = BlendMode::Normal;
        mgs.alpha_fill = 1.0;
        mgs.alpha_stroke = 1.0;
        let mres_ref = mres.as_ref().or(resources);
        interpret_content(doc, &msub.operations, mres_ref, mgs, prims, depth + 1, false);
    }
}

/// Bracket the primitives appended since `start` with the given soft mask so
/// they are drawn only where the mask is opaque/luminous. No-op if nothing was
/// emitted. Reuses the SoftMaskPush/Content/Pop wire prims.
pub(crate) fn wrap_with_soft_mask(
    prims: &mut Vec<Prim>,
    start: usize,
    doc: &Document,
    resources: Option<&lopdf::Dictionary>,
    mask: &SoftMask,
    depth: u32,
) {
    if start >= prims.len() || prims.len() >= MAX_PRIMITIVES {
        return;
    }
    prims.insert(start, Prim::SoftMaskPush { mask_type: mask.mask_type });
    prims.push(Prim::SoftMaskContent);
    render_soft_mask_group(doc, resources, mask, prims, depth);
    prims.push(Prim::SoftMaskPop);
}

/// Bounding box (device space) of a set of polygons, or `None` if empty.
fn polys_device_bbox(polys: &[Vec<(f64, f64)>]) -> Option<[f64;4]> {
    let mut x0 = f64::INFINITY; let mut y0 = f64::INFINITY;
    let mut x1 = f64::NEG_INFINITY; let mut y1 = f64::NEG_INFINITY;
    for poly in polys {
        for &(x,y) in poly.iter() {
            x0 = x0.min(x); y0 = y0.min(y); x1 = x1.max(x); y1 = y1.max(y);
        }
    }
    if x1 > x0 && y1 > y0 { Some([x0, y0, x1, y1]) } else { None }
}

/// the segments (matching how a real stroke covers the path).
pub(crate) fn paint_pattern_stroke(
    doc: &Document,
    pattern_id: ObjectId,
    subpaths: &[Vec<(f64, f64)>],
    gs: &GraphicsState,
    pattern_base_ctm: &Mat,
    prims: &mut Vec<Prim>,
    depth: u32,
) {
    if depth >= MAX_PATTERN_RECURSION || prims.len() >= MAX_PRIMITIVES {
        return;
    }
    let obj = match doc.get_object(pattern_id) {
        Ok(o) => o,
        Err(_) => return,
    };
    let dict = match obj {
        Object::Dictionary(d) => d,
        Object::Stream(s) => &s.dict,
        _ => return,
    };
    let ptype = dict.get(b"PatternType").ok().and_then(num).unwrap_or(0.0) as i64;
    let matrix = dict.get(b"Matrix").ok().and_then(read_matrix_obj).unwrap_or(IDENTITY);
    let pmat = mat_mul(&matrix, pattern_base_ctm);

    // Half stroke width in device space (CTM average axis scale).
    let ctm = &gs.ctm;
    let sx = (ctm[0]*ctm[0] + ctm[1]*ctm[1]).sqrt();
    let sy = (ctm[2]*ctm[2] + ctm[3]*ctm[3]).sqrt();
    let scale = (sx + sy) / 2.0;
    let hw = ((gs.line_width * scale) / 2.0).max(0.35);

    let quads = stroke_outline_quads(subpaths, hw);
    let stroke_bbox = polys_device_bbox(subpaths);
    for quad in &quads {
        if prims.len() >= MAX_PRIMITIVES { break; }
        if quad.len() < 3 || shoelace_area(quad).abs() < 1e-3 { continue; }
        prims.push(Prim::ClipPush {
            even_odd: false,
            pts: quad.iter().map(|&(x, y)| (x as f32, y as f32)).collect(),
            path_ops: None,
        });
        if ptype == 2 {
            if let Some(shobj) = dict.get(b"Shading").ok().and_then(|o| deref(doc, o)) {
                if let Some((ctm, w, h, data)) = rasterize_shading(doc, shobj, &pmat, &HashMap::new(), 0, stroke_bbox) {
                    if prims.len() < MAX_PRIMITIVES {
                        prims.push(Prim::Image { ctm, w, h, format: 0, data, alpha: 1.0 });
                    }
                }
            }
        } else if ptype == 1 {
            let region = std::slice::from_ref(quad);
            paint_tiling_pattern(doc, obj, dict, &pmat, gs.stroke, region, prims, depth);
        }
        prims.push(Prim::ClipPop);
    }
}

pub(crate) fn paint_pattern_fill(
    doc: &Document,
    pattern_id: ObjectId,
    polys: &[Vec<(f64, f64)>],
    even_odd: bool,
    pattern_base_ctm: &Mat,
    base_argb: u32,
    prims: &mut Vec<Prim>,
    depth: u32,
) {
    if depth >= MAX_PATTERN_RECURSION || prims.len() >= MAX_PRIMITIVES {
        return;
    }
    let obj = match doc.get_object(pattern_id) {
        Ok(o) => o,
        Err(_) => return,
    };
    let dict = match obj {
        Object::Dictionary(d) => d,
        Object::Stream(s) => &s.dict,
        _ => return,
    };
    let ptype = dict.get(b"PatternType").ok().and_then(num).unwrap_or(0.0) as i64;
    let matrix = dict.get(b"Matrix").ok().and_then(read_matrix_obj).unwrap_or(IDENTITY);
    let pmat = mat_mul(&matrix, pattern_base_ctm);

    // Clip to the fill region.
    let mut pushed = 0usize;
    for poly in polys {
        if poly.len() >= 3 && shoelace_area(poly).abs() >= 1e-3 && prims.len() < MAX_PRIMITIVES {
            prims.push(Prim::ClipPush {
                even_odd,
                pts: poly.iter().map(|&(x, y)| (x as f32, y as f32)).collect(),
                path_ops: None,
            });
            pushed += 1;
        }
    }
    if pushed == 0 {
        return;
    }

    if ptype == 2 {
        if let Some(shobj) = dict.get(b"Shading").ok().and_then(|o| deref(doc, o)) {
            let fill_bbox = polys_device_bbox(polys);
            if let Some((ctm, w, h, data)) = rasterize_shading(doc, shobj, &pmat, &HashMap::new(), 0, fill_bbox) {
                if prims.len() < MAX_PRIMITIVES {
                    prims.push(Prim::Image { ctm, w, h, format: 0, data, alpha: 1.0 });
                }
            }
        }
    } else if ptype == 1 {
        paint_tiling_pattern(doc, obj, dict, &pmat, base_argb, polys, prims, depth);
    }

    for _ in 0..pushed {
        prims.push(Prim::ClipPop);
    }
}

fn paint_tiling_pattern(
    doc: &Document,
    obj: &Object,
    dict: &lopdf::Dictionary,
    pmat: &Mat,
    base_argb: u32,
    polys: &[Vec<(f64, f64)>],
    prims: &mut Vec<Prim>,
    depth: u32,
) {
    let stream = match obj {
        Object::Stream(s) => s,
        _ => return,
    };
    let paint_type = dict.get(b"PaintType").ok().and_then(num).unwrap_or(1.0) as i64;
    let bbox = dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)).unwrap_or([0.0, 0.0, 1.0, 1.0]);
    let xstep = dict.get(b"XStep").ok().and_then(num).unwrap_or(bbox[2] - bbox[0]);
    let ystep = dict.get(b"YStep").ok().and_then(num).unwrap_or(bbox[3] - bbox[1]);
    // Zero-step pattern is malformed — show bbox once instead of blanking
    if xstep.abs() < 1e-6 || ystep.abs() < 1e-6 {
        let res = dict
            .get(b"Resources")
            .ok()
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .cloned();
        if let Ok(content) = Content::decode(&stream_data_with_doc(doc, stream)) {
            let mut tile_gs = GraphicsState { ctm: *pmat, ..GraphicsState::default() };
            if paint_type == 2 { tile_gs.fill = base_argb; tile_gs.stroke = base_argb; }
            interpret_content(doc, &content.operations, res.as_ref(), tile_gs, prims, depth + 1, false);
        }
        return;
    }
    let res = dict
        .get(b"Resources")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .cloned();
    let content = match Content::decode(&stream_data_with_doc(doc, stream)) {
        Ok(c) => c,
        Err(_) => return,
    };

    // Device-space bounding box of the fill region.
    let (mut minx, mut miny, mut maxx, mut maxy) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
    for poly in polys {
        for &(x, y) in poly {
            minx = minx.min(x);
            miny = miny.min(y);
            maxx = maxx.max(x);
            maxy = maxy.max(y);
        }
    }
    if !minx.is_finite() {
        return;
    }

    // Map that box into pattern space to bound the tile index range.
    let inv = mat_inverse(pmat);
    // Singular pattern matrix — degrade to single tile instead of blank.
    if (inv[0]*inv[3] - inv[1]*inv[2]).abs() < 1e-12 {
        let mut tile_gs = GraphicsState { ctm: *pmat, ..GraphicsState::default() };
        if paint_type == 2 { tile_gs.fill = base_argb; tile_gs.stroke = base_argb; }
        interpret_content(doc, &content.operations, res.as_ref(), tile_gs, prims, depth + 1, false);
        return;
    }
    let (mut pminx, mut pminy, mut pmaxx, mut pmaxy) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
    for (x, y) in [(minx, miny), (maxx, miny), (minx, maxy), (maxx, maxy)] {
        let (px, py) = transform(&inv, x, y);
        pminx = pminx.min(px);
        pminy = pminy.min(py);
        pmaxx = pmaxx.max(px);
        pmaxy = pmaxy.max(py);
    }
    let i0 = ((pminx - bbox[2]) / xstep).floor() as i64;
    let i1 = ((pmaxx - bbox[0]) / xstep).ceil() as i64;
    let j0 = ((pminy - bbox[3]) / ystep).floor() as i64;
    let j1 = ((pmaxy - bbox[1]) / ystep).ceil() as i64;

    const MAX_TILES: i64 = 400;
    // Cap tile range to MAX_TILES instead of blanking — degrade at high zoom.
    let total_i = (i1 - i0 + 1).max(0);
    let total_j = (j1 - j0 + 1).max(0);
    let (i1_clamped, j1_clamped) = if total_i * total_j > MAX_TILES {
        // Prioritize tiles around origin for single-tile fallback quality
        let side = (MAX_TILES as f64).sqrt() as i64;
        (i0 + side.min(total_i), j0 + side.min(total_j))
    } else {
        (i1, j1)
    };
    let mut count = 0i64;
    'outer: for j in j0..=j1_clamped {
        for i in i0..=i1_clamped {
            if count >= MAX_TILES || prims.len() >= MAX_PRIMITIVES {
                break 'outer;
            }
            count += 1;
            let translate: Mat = [1.0, 0.0, 0.0, 1.0, i as f64 * xstep, j as f64 * ystep];
            let tile_ctm = mat_mul(&translate, pmat);
            let mut tile_gs = GraphicsState { ctm: tile_ctm, ..GraphicsState::default() };
            if paint_type == 2 {
                tile_gs.fill = base_argb;
                tile_gs.stroke = base_argb;
            }
            interpret_content(doc, &content.operations, res.as_ref(), tile_gs, prims, depth + 1, false);
        }
    }
}

/// Read a 6-element matrix from an array object.
pub(crate) fn read_matrix_obj(obj: &Object) -> Option<Mat> {
    match obj {
        Object::Array(a) => read_matrix(a),
        _ => None,
    }
}

/// Read a 4-number array (e.g. `/Rect`, `/BBox`) resolving references.
pub(crate) fn read_rect(doc: &Document, obj: &Object) -> Option<[f64; 4]> {
    let arr = deref(doc, obj)?.as_array().ok()?;
    if arr.len() != 4 {
        return None;
    }
    let mut out = [0.0; 4];
    for (i, v) in arr.iter().enumerate() {
        out[i] = deref(doc, v).and_then(num)?;
    }
    Some(out)
}

#[cfg(test)]
mod stroke_pattern_tests {
    use super::stroke_outline_quads;

    // A single horizontal segment yields one segment quad plus two vertex
    // squares, all offset by the half width.
    #[test]
    fn horizontal_segment_quad_offsets_by_half_width() {
        let sp = vec![vec![(0.0, 0.0), (10.0, 0.0)]];
        let quads = stroke_outline_quads(&sp, 2.0);
        // 1 segment quad + 2 vertex squares.
        assert_eq!(quads.len(), 3);
        let seg = &quads[0];
        assert_eq!(seg.len(), 4);
        // Normal to a horizontal segment is vertical: y offset = +/-hw.
        assert!(seg.iter().any(|&(_, y)| (y - 2.0).abs() < 1e-9));
        assert!(seg.iter().any(|&(_, y)| (y + 2.0).abs() < 1e-9));
    }

    // Zero-length segments are skipped (no NaN normals), but the vertex square
    // still covers the point.
    #[test]
    fn degenerate_segment_is_skipped() {
        let sp = vec![vec![(5.0, 5.0), (5.0, 5.0)]];
        let quads = stroke_outline_quads(&sp, 1.0);
        // No segment quad, just the two coincident vertex squares.
        assert_eq!(quads.len(), 2);
    }
}

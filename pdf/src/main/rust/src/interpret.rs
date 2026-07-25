use crate::*;

pub(crate) fn bezier_steps_for_flatness(flatness: f64) -> usize {
    if flatness <= 0.0 {
        return BEZIER_STEPS;
    }
    // Higher flatness tolerates coarser curves; we do inverse of spec tolerance: steps ~ 16 * (1 + flatness/3) clamped 4..32
    let steps = (BEZIER_STEPS as f64 * (1.0 + flatness / 3.0)).round() as usize;
    steps.clamp(4, 32)
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

/// Parse ExtGState dash `D`: handles both normalized forms:
/// - [[2 1] 0.5] -> outer array len=2, elem0=inner array of dashes, elem1=phase num
/// - [3 3 0] flat where last entry is phase (spec allows but some writers do)
/// - simple dash array [2 1] -> phase 0
/// Filters negative values (>=0 guard) and returns (dashes, phase).
pub(crate) fn parse_dash_d_array(doc: &Document, arr: &[Object]) -> (Vec<f64>, f64) {
    if arr.is_empty() {
        return (Vec::new(), 0.0);
    }
    // Case: [[dashArray] phase] — first array, second num
    if arr.len() == 2 {
        if let Some(inner) = arr[0].as_array().ok().or_else(|| deref(doc, &arr[0]).and_then(|o| o.as_array().ok())) {
            if let Some(phase) = deref(doc, &arr[1]).and_then(num).or_else(|| num(&arr[1])) {
                let dashes: Vec<f64> = inner.iter().filter_map(|o| deref(doc, o).and_then(num).or_else(|| num(o))).filter(|v| *v >= 0.0).collect();
                return (dashes, phase);
            }
        }
    }
    // Case: flat [dashes..., phase] ? but spec says phase separate; we support fallback where len>=2 and treating last as phase if array length >1 and last is num and preceding are dashes
    // However canonical ExtGState /D is [dashArray phase] nested, so flat is less common. For safety:
    // If arr contains only numbers, treat as dash only (phase 0) — operator form.
    // If caller wants to handle [dash..., phase] flat, we can do: if last element numeric and len>1 and this arr is NOT a nested dash array, interpret last as phase and rest as dashes.
    // The plan says to support flat [3 3 0] where last = phase. So attempt:
    let derefed_nums: Vec<f64> = arr.iter().filter_map(|o| deref(doc, o).and_then(num).or_else(|| num(o))).collect();
    if derefed_nums.is_empty() {
        return (Vec::new(), 0.0);
    }
    // If originally arr[0] is an array-like and len==2 already handled, fall through
    // Check if total array was actually 2 elements where first is array – already returned.
    // Now if this array contains only numbers and we are parsing ExtGState D that was mistakenly flattened as numbers only (no nested), the spec still expects [dashArray phase] but writer may flatten?
    // The plan's second case is inline d operator style? Actually for safety: if arr len>=2 and this is being called for both d-operator (phase separate) vs D-array, but we unify: for D-array that is nested we already handled.
    // For D-array that appears as flat numbers [2 1 0.5]? That would be ambiguous: last could be phase. We adopt heuristic: if len>=3 treat last as phase only if dash count>=1 and caller explicitly wants flat phase detection.
    // Here for ExtGState we will treat flat as: all numbers except last are dash, last is phase if len>=2 (configurable).
    // But to avoid breaking normal dash arrays, we introduce a version that checks if arr was originally [dashArray phase] nested vs flat numeric. For flat numeric case [3 3 0] -> dashes [3,3] phase 0.
    // We'll decide: if arr.len() >=2 and all elements are numbers, return dashes = all except last if last < and previous dash count >0? Actually can't distinguish. Use heuristic: if last element interpreted as phase and second last interpretation as phase-not-dash? We'll treat as: if len>=2, dashes = derefed_nums[..len-1], phase = last, but only if len>=3 or dash len>0. However that would mis-handle pure dash array without phase.
    // Since this function is specifically for ExtGState D which SHOULD be nested [dash phase], we should NOT do flat fallback for ExtGState unless caller requests. But plan says to support both.
    // So we will: if flat numeric array detected and its len>=2, return dashes = all but last, phase = last (as a lenient parse).
    // The caller for operator d will not use this – it parses separately.
    // For ExtGState D lenient, return dashes = derefed_nums[..len-1], phase = last if derefed_nums.len()>=2 else dash=derefed_nums, phase=0.
    // However to avoid breaking a valid dash case where D = [ [dash] phase ] already handled, the remaining cases of flat numbers we treat as dash only if len==1? Let's just if len>=2 treat last as phase for ExtGState lenient mode.
    // We'll return dash = filter >=0 for all but last, phase = last.
    // This matches plan: "[ [3 3] 0 ] (Array len2 where elem0 Array + elem1 Num) and flat [3 3 0] (last = phase)". So flat case indeed implies len=3 last=phase.
    // We'll implement lenient: if derefed_nums.len() >=2 and this function is called for ExtGState, interpret last as phase ONLY when arr is flat numbers (no nested arrays). That is, if arr.iter().all(|o| deref(doc,o).and_then(num).is_some() || num(o).is_some()) AND derefed_nums.len()>=2, then last=phase.
    // To avoid breaking pure dash without phase, we only apply flat-phase heuristic when derefed_nums.len()>=2 AND caller explicitly wants flat-phase support? Plan says treat phase as dash previously – bug. So fixing means we should for ExtGState D that is flat numbers, interpret correctly.
    // Implement:
    let all_nums = arr.iter().all(|o| {
        if let Some(Object::Array(_)) = deref(doc,o) { false } else { num(o).is_some() || deref(doc,o).and_then(num).is_some() }
    });
    if all_nums && derefed_nums.len() >= 2 {
        let phase = derefed_nums.last().copied().unwrap_or(0.0);
        let dashes: Vec<f64> = derefed_nums[..derefed_nums.len()-1].iter().copied().filter(|v| *v >=0.0).collect();
        return (dashes, phase);
    }
    // Otherwise pure dash array
    (derefed_nums.into_iter().filter(|v| *v >=0.0).collect(), 0.0)
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
    }
    let mut stack: Vec<SavedState> = Vec::new();

    let mut text_matrix = IDENTITY;
    let mut line_matrix = IDENTITY;
    let mut leading = 0.0_f64;

    let mut subpaths: Vec<Vec<(f64, f64)>> = Vec::new();
    let mut cur_user: (f64, f64) = (0.0, 0.0);
    let mut start_user: (f64, f64) = (0.0, 0.0);

    struct PendingClip {
        even_odd: bool,
        polys: Vec<Vec<(f64,f64)>>,
        path_ops: Vec<PathOp>, // bezier-retentive for v3 clip
    }
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
                if stack.len() < MAX_GRAPHICS_STACK {
                    stack.push(SavedState { gs: gs.clone(), clip_depth });
                }
            }
            "Q" => {
                if let Some(saved) = stack.pop() {
                    while clip_depth > saved.clip_depth {
                        if !text_only {
                            prims.push(Prim::ClipPop);
                        }
                        clip_depth = clip_depth.saturating_sub(1);
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
                if let Some(v) = o.first().and_then(num) {
                    gs.line_width = v;
                }
            }
            "J" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.line_cap = (v as i64).clamp(0,2) as u8;
                }
            }
            "j" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.line_join = (v as i64).clamp(0,2) as u8;
                }
            }
            "M" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.miter_limit = v;
                }
            }
            "i" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.flatness = v.clamp(0.0, 100.0);
                }
            }
            "d" => {
                if let Some(Object::Array(arr)) = o.first() {
                    let dashes: Vec<f64> = arr.iter().filter_map(num).filter(|v| *v >= 0.0).collect();
                    gs.dash = dashes;
                }
                gs.dash_phase = o.get(1).and_then(num).unwrap_or(0.0);
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
                        if let Some(v) = dict.get(b"CA").ok().and_then(num) {
                            gs.alpha_fill = v.clamp(0.0,1.0);
                        }
                        if let Some(v) = dict.get(b"ca").ok().and_then(num) {
                            gs.alpha_stroke = v.clamp(0.0,1.0);
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
                            let (dashes, phase) = parse_dash_extgstate(doc, d_obj);
                            if !dashes.is_empty() || phase != 0.0 {
                                gs.dash = dashes;
                                gs.dash_phase = phase;
                            }
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
                        // Soft mask: /SMask /None clears; a dict << /S /Luminosity|/Alpha
                        // /G <group stream ref> >> sets a luminosity/alpha soft mask.
                        if let Some(sm_raw) = dict.get(b"SMask").ok() {
                            if let Ok(n) = sm_raw.as_name() {
                                if n == b"None" { gs.soft_mask = None; }
                            } else if let Some(sm) = deref(doc, sm_raw).or(Some(sm_raw)) {
                                if let Ok(smdict) = sm.as_dict() {
                                    let mask_type = match smdict.get(b"S").ok().and_then(|o| o.as_name().ok()) {
                                        Some(b"Luminosity") => 1u8,
                                        _ => 0u8, // Alpha (default)
                                    };
                                    // /G must be an indirect reference to the group stream.
                                    if let Some(Object::Reference(gid)) = smdict.get(b"G").ok() {
                                        gs.soft_mask = Some((*gid, mask_type));
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
                pending_clip = Some(PendingClip { even_odd: false, polys: subpaths.clone(), path_ops: clip_path_ops.clone() });
            }
            "W*" => {
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
                    if let Some(pid) = gs.stroke_pattern {
                        paint_pattern_stroke(doc, pid, &subpaths, &gs, &pattern_base_ctm, prims, depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        emit_stroke(prims, &subpaths, &gs);
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
                    if let Some(pid) = gs.fill_pattern {
                        paint_pattern_fill(doc, pid, &subpaths, op.operator == "f*", &pattern_base_ctm, gs.fill, prims, depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        emit_fill(prims, &subpaths, gs.fill, op.operator == "f*", gs.alpha_fill, gs.blend_mode);
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
                    if let Some(pid) = gs.fill_pattern {
                        paint_pattern_fill(doc, pid, &subpaths, op.operator.ends_with('*'), &pattern_base_ctm, gs.fill, prims, depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        emit_fill(prims, &subpaths, gs.fill, op.operator.ends_with('*'), gs.alpha_fill, gs.blend_mode);
                    }
                    if let Some(pid) = gs.stroke_pattern {
                        paint_pattern_stroke(doc, pid, &subpaths, &gs, &pattern_base_ctm, prims, depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        emit_stroke(prims, &subpaths, &gs);
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
                            if prims.len() < MAX_PRIMITIVES { prims.push(Prim::Image { ctm: gs.ctm, w: img.w, h: img.h, format: img.format, data: img.data, alpha: 1.0 }); }
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
                                        if prims.len() < MAX_PRIMITIVES { prims.push(Prim::Image { ctm: gs.ctm, w: img.w, h: img.h, format: img.format, data: img.data, alpha: 1.0 }); }
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
                                let active_smask = gs.soft_mask;
                                let use_smask = active_smask.is_some()
                                    && !text_only
                                    && !oc_stack.last().copied().unwrap_or(false)
                                    && prims.len() < crate::MAX_PRIMITIVES
                                    && depth < crate::MAX_PATTERN_RECURSION as u32;
                                if let (true, Some((_g, mtype))) = (use_smask, active_smask) {
                                    prims.push(Prim::SoftMaskPush { mask_type: mtype });
                                }
                                let should_emit_group = is_transparency_group && !use_smask && !text_only && !oc_stack.last().copied().unwrap_or(false) && depth < crate::MAX_PATTERN_RECURSION as u32;
                                if should_emit_group {
                                    if prims.len() < crate::MAX_PRIMITIVES && group_depth < 32 {
                                        prims.push(Prim::GroupPush { isolated, knockout, alpha: gs.alpha_fill as f32 * gs.alpha_stroke as f32, blend: gs.blend_mode });
                                        group_depth+=1;
                                    }
                                }
                                if let Ok(sub) = Content::decode(&stream_data_with_doc(doc, &stream)) {
                                        let mut sub_gs = gs.clone();
                                        sub_gs.ctm = mat_mul(&form_matrix, &gs.ctm);
                                        // The mask does not apply to nested Do's inside the masked content.
                                        sub_gs.soft_mask = None;
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
                                // Emit the mask group content, then close the bracket.
                                if let (true, Some((g_id, _mtype))) = (use_smask, active_smask) {
                                    prims.push(Prim::SoftMaskContent);
                                    if let Ok(Object::Stream(mstream)) = doc.get_object(g_id) {
                                        let mmatrix = mstream.dict.get(b"Matrix").ok().and_then(read_matrix_obj).unwrap_or(IDENTITY);
                                        let mres = mstream.dict.get(b"Resources").ok()
                                            .and_then(|o| deref(doc, o))
                                            .and_then(|o| o.as_dict().ok())
                                            .cloned();
                                        if let Ok(msub) = Content::decode(&stream_data_with_doc(doc, mstream)) {
                                            let mut mgs = gs.clone();
                                            mgs.ctm = mat_mul(&mmatrix, &gs.ctm);
                                            mgs.soft_mask = None;
                                            mgs.blend_mode = BlendMode::Normal;
                                            mgs.alpha_fill = 1.0;
                                            mgs.alpha_stroke = 1.0;
                                            let mres_ref = mres.as_ref().or(resources);
                                            interpret_content(
                                                doc,
                                                &msub.operations,
                                                mres_ref,
                                                mgs,
                                                prims,
                                                depth + 1,
                                                text_only,
                                            );
                                        }
                                    }
                                    prims.push(Prim::SoftMaskPop);
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
                if matches!(gs.stroke_cs, CsKind::Pattern) {
                    gs.stroke_pattern = o.last().and_then(|obj| obj.as_name().ok()).and_then(|pn| patterns.get(pn).copied());
                    if !comps.is_empty() {
                        gs.stroke = uncolored_pattern_argb(&comps);
                    }
                } else if !comps.is_empty() {
                    if let Some(rgb) = eval_cs_to_rgb(doc, &gs.stroke_cs, &comps, &colorspaces) {
                        gs.stroke = rgb;
                    }
                }
            }
            "scn" => {
                let comps: Vec<f64> = o.iter().filter_map(num).collect();
                if matches!(gs.non_stroke_cs, CsKind::Pattern) {
                    gs.fill_pattern = o.last().and_then(|obj| obj.as_name().ok()).and_then(|pn| patterns.get(pn).copied());
                    if !comps.is_empty() {
                        gs.fill = uncolored_pattern_argb(&comps);
                    }
                } else if !comps.is_empty() {
                    if let Some(rgb) = eval_cs_to_rgb(doc, &gs.non_stroke_cs, &comps, &colorspaces) {
                        gs.fill = rgb;
                    }
                }
            }
            "sh" => {
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
                                if let Some((ctm,w,h,data)) = rasterize_shading(doc, obj, &gs.ctm, &colorspaces, 256) {
                                    if prims.len() < MAX_PRIMITIVES && !oc_stack.last().copied().unwrap_or(false) {
                                        prims.push(Prim::Image { ctm, w, h, format: 0, data, alpha: 1.0 });
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
            "d0" | "d1" => {}
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
                    let adv = show_string(doc, prims, &gs, &fonts, &text_matrix, bytes, depth);
                    text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
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
            }
            // Explicit no-ops (documented): rendering intent, and compatibility
            // sections have no effect on our flat-primitive output.
            "ri" | "BX" | "EX" => {}
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

/// Approximate an RGB color for an uncolored (`/PaintType 2`) pattern's base
/// color operands, by treating them as Gray/RGB/CMYK by arity.
pub(crate) fn uncolored_pattern_argb(comps: &[f64]) -> u32 {
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
                if let Some((ctm, w, h, data)) = rasterize_shading(doc, shobj, &pmat, &HashMap::new(), 256) {
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
            if let Some((ctm, w, h, data)) = rasterize_shading(doc, shobj, &pmat, &HashMap::new(), 256) {
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
    if xstep.abs() < 1e-6 || ystep.abs() < 1e-6 {
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
    let mut count = 0i64;
    'outer: for j in j0..=j1 {
        for i in i0..=i1 {
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

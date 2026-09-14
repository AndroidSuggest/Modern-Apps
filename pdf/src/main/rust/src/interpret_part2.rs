) {
    // `mut` for the ExtGState `/Font` arm (§8.4.5 Table 58), which references a font
    // dictionary directly instead of through a resource name and so has to register it.
    let mut fonts = resources
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
    // Establishes the form-invocation budget when this is the outermost
    // interpretation on the thread, at whatever `depth` it was entered with.
    // See [`FormBudgetScope`].
    let _form_budget = FormBudgetScope::enter();
    // Pattern matrices are relative to the coordinate system in effect when this
    // content stream begins (the page default CTM, or the form's CTM).
    let pattern_base_ctm = gs.ctm;
    #[derive(Clone)]
    struct SavedState {
        gs: GraphicsState,
        clip_depth: usize,
        group_depth: usize,
        clip_bbox: Option<[f64; 4]>,
    }
    let mut stack: Vec<SavedState> = Vec::new();
    let mut q_overflow: usize = 0;

    struct PendingClip {
        even_odd: bool,
        polys: Vec<Vec<(f64,f64)>>,
        path_ops: Vec<PathOp>,
    }

    // single ClipPush per W op preserving holes via full path_ops (fix high #7).
    // Also intersects the new clip's device-space bbox into `clip_bbox` so that
    // later `sh` operators know the current clip region even after `W n` commits.
    //
    // Deliberately NOT gated on optional-content visibility. §8.11.3.3 makes an
    // OFF group's content undrawn, and §8.5.3 Table 60 lists the PAINTING
    // operators; `W`/`W*` are clipping-path operators (§8.5.4) and `n` paints
    // nothing, so a clip is a graphics-state change (§8.4.1 Table 52) and not a
    // mark. A clip set inside BDC/EMC survives the EMC and bounds the VISIBLE
    // content after it — exactly like the `q`, `cm`, `gs` and colour operators in
    // the same hidden run, none of which are suppressed here either. Dropping it
    // let that later content paint unclipped, which is the direction that puts ink
    // where the file said there should be none.
    #[inline]
    fn emit_one_clip(prims: &mut Vec<Prim>, pc: PendingClip, clip_depth: &mut usize, clip_bbox: &mut Option<[f64;4]>, text_only: bool) {
        if text_only { return; }
        if *clip_depth >= MAX_CLIP_DEPTH { return; }
        if pc.polys.is_empty() && pc.path_ops.is_empty() { return; }
        // Intersect the accumulated clip bbox with this clip's device bbox.
        let mut nx0 = f64::INFINITY; let mut ny0 = f64::INFINITY;
        let mut nx1 = f64::NEG_INFINITY; let mut ny1 = f64::NEG_INFINITY;
        for poly in &pc.polys {
            for &(x, y) in poly {
                nx0 = nx0.min(x); ny0 = ny0.min(y);
                nx1 = nx1.max(x); ny1 = ny1.max(y);
            }
        }
        if nx1 > nx0 && ny1 > ny0 {
            *clip_bbox = Some(match *clip_bbox {
                Some(cur) => [cur[0].max(nx0), cur[1].max(ny0), cur[2].min(nx1), cur[3].min(ny1)],
                None => [nx0, ny0, nx1, ny1],
            });
        }
        let pts: Vec<(f32,f32)> = pc.polys.first().map(|p| p.iter().map(|&(x,y)| (x as f32, y as f32)).collect()).unwrap_or_default();
        let po = if pc.path_ops.is_empty() { None } else { Some(pc.path_ops) };
        prims.push(Prim::ClipPush { even_odd: pc.even_odd, pts, path_ops: po });
        *clip_depth += 1;
    }

    let mut text_matrix = IDENTITY;
    let mut line_matrix = IDENTITY;

    let mut subpaths: Vec<Vec<(f64, f64)>> = Vec::new();
    let mut cur_user: (f64, f64) = (0.0, 0.0);
    let mut start_user: (f64, f64) = (0.0, 0.0);
    // §8.5.2.1 Table 59, `h`: "This operator shall terminate the current subpath.
    // Appending another segment to the current path shall begin a NEW subpath,
    // even if the new segment begins at the endpoint reached by the h operation."
    // `re` is defined in the same table as `… l h`, so it closes too. Appending to
    // the closed contour instead merges it with whatever follows, which deletes the
    // closing edge from the fill and flips the winding of the merged region.
    // `clip_path_ops` already behaved correctly here — a `lineTo` after `close`
    // starts a fresh contour — so the polygon list and the path-op list were two
    // different paths for the same input, and `W f` clipped and filled differently.
    let mut subpath_closed = false;
    // Set when [`MAX_SUBPATHS`] blocked a `m`/`re`. The segments that follow belong
    // to a subpath that does not exist; appending them to the last one that DOES
    // draws a stray line from it to each of them, so a page that trips the cap gets
    // a wedge across it rather than a cleanly truncated path.
    let mut subpath_dropped = false;
    // Begin the subpath §8.5.2.1 requires after a close, at the closepoint.
    // Returns false when the cap refuses it.
    fn reopen_subpath(
        subpaths: &mut Vec<Vec<(f64, f64)>>,
        clip_path_ops: &mut Vec<PathOp>,
        at: (f64, f64),
    ) -> bool {
        if subpaths.len() >= MAX_SUBPATHS {
            return false;
        }
        subpaths.push(vec![at]);
        clip_path_ops.push(PathOp::Move(at.0 as f32, at.1 as f32));
        true
    }
    // Marked-content stack, one entry per BMC/BDC: true means content in this
    // frame is suppressed by optional content. §14.6 requires 1:1 nesting, so a
    // frame is pushed for EVERY BMC/BDC even when visibility is unchanged.
    let mut oc_stack: Vec<bool> = Vec::new();
    // BMC/BDC pushes dropped at MAX_OC_STACK, so EMC can discard the matching
    // pop instead of popping a frame it does not own (which un-hid content).
    let mut oc_overflow: usize = 0;
    let mut group_depth: usize = 0;
    let mut pending_clip: Option<PendingClip> = None;
    // §11.6.5.1: the soft mask is a graphics-state parameter, not a per-operator
    // one. Tracks the last bracket emitted so a run of paints under the same mask
    // expands the mask group once instead of once per painting operator.
    let mut mask_bracket: Option<MaskBracket> = None;
    // Memo for optional-content answers within this stream. Distinct from
    // `oc_config` below: that holds the /ON and /OFF membership sets, while this
    // memoizes the work AROUND a lookup — resolving a /Properties resource NAME to
    // its OCG, and evaluating an OCMD's /OCGs + /P policy — neither of which the
    // membership sets cover. A layer is opened and closed many times per page, and
    // within one content stream the /Properties resource is fixed, so each distinct
    // property list resolves to the same answer every time.
    //
    // Deliberately NOT shared with nested streams, even though a nested form
    // starts with an empty memo and pays the re-read again. `OcKey::Named` is a
    // /Properties resource NAME, and resource dictionaries are per-stream: `/P1`
    // in a form's /Properties may name a different OCG than the page's `/P1`, so
    // a shared memo would answer the wrong question. Only `OcKey::Ref` is
    // stream-independent, and splitting the memo to share half of it buys a
    // dictionary lookup on a path that is already off the hot loop.
    #[derive(PartialEq, Eq, Hash)]
    enum OcKey {
        Named(Vec<u8>),
        Ref(ObjectId),
    }
    let mut oc_cache: HashMap<OcKey, bool> = HashMap::new();
    // The `/ON` and `/OFF` membership sets, built AT MOST ONCE per content stream and
    // only if an optional-content lookup actually happens.
    //
    // Held rather than resolved per call because `OcConfig::from_doc` builds both
    // HashSets from the catalog, which is O(N) in the document's OCG count. There used
    // to be a free `oc_object_hidden(doc, obj)` wrapper in images.rs that called it
    // internally, and these call sites were on it: that silently threw away a measured
    // 8.8x speedup (`bench`: 111 ms -> 12.6 ms over 1600 distinct OCGs) by relocating
    // the same O(N) work out of four `clone()`s and into two set builds — identical
    // asymptotics, no win. That wrapper has since been DELETED precisely so the trap
    // cannot be re-entered; `OcConfig::object_hidden` is now the only way in, and it
    // requires you to have hoisted the config first.
    //
    // Lazy rather than eager because the overwhelming majority of content streams
    // contain no optional content at all, and this runs for every form XObject, every
    // tiling-pattern cell replay and every soft-mask group — building it unconditionally
    // would turn a measured win into a per-stream tax. `oc_cache` above still earns its
    // keep: it memoizes the /Properties NAME resolution and the OCMD `/OCGs` + `/P`
    // evaluation, neither of which the membership sets cover.
    let mut oc_config: Option<OcConfig> = None;
    macro_rules! oc_hidden {
        ($obj:expr) => {{
            let cfg = oc_config.get_or_insert_with(|| OcConfig::from_doc(doc));
            cfg.object_hidden(doc, $obj)
        }};
    }
    // Device-space bbox of the accumulated (committed) clip region, tracked so the
    // `sh` operator can fill the current clip even after `W n` clears pending_clip.
    let mut current_clip_bbox: Option<[f64; 4]> = init_clip_bbox;
    let mut clip_depth: usize = 0;
    let mut clip_path_ops: Vec<PathOp> = Vec::new(); // current clip path ops before W
    // Whether the current text object (BT..ET) used a clip render mode (Tr 4-7).
    let mut text_clip_used = false;

    let dev = |gs: &GraphicsState, x: f64, y: f64| transform(&gs.ctm, x, y);
    // §7.3.3 bounds a real to the implementation limit, and lopdf's `Object::Real`
    // is an f32, so a content stream carrying `1e40` parses to INFINITY and every
    // device coordinate derived from it is non-finite. Nothing between here and
    // `Canvas.drawPath` filters those: not `wire.rs`, not the parser, not the
    // renderer. One such point in a contour is not a locally wrong point — it makes
    // the WHOLE path non-finite, and a non-finite path is dropped rather than
    // clipped to something sane, so a single bad number can silently erase an entire
    // fill. As a `W n` clip path it can erase everything drawn after it.
    //
    // This is the same hazard the `cm` arm already guards ("a non-finite CTM poisons
    // every coordinate derived from it"), reached by the other route: bad operands
    // rather than a bad matrix. Guarding one and not the other was the inconsistency.
    // Checked AFTER the transform so an inherited non-finite CTM — a form `/Matrix`,
    // which is not finite-checked — is caught too.
    let finite2 = |a: (f64, f64)| a.0.is_finite() && a.1.is_finite();
    // Read operand `i` as a FINITE number, resolving an indirect reference.
    //
    // §7.3.3 bounds a real to the implementation limit, and lopdf's `Object::Real`
    // is an f32, so a long enough literal parses to INFINITY. Scalar operands were
    // the last carrier of that left unguarded: `read_matrix` (§8.3.3), `read_rect`
    // (§7.9.5), the `cm` product and every path operand (via `finite2`) are all
    // checked, while `w`/`M`/`i`/`Tf`/`Tc`/`Tw`/`Tz`/`Ts`/`TL`/`Td`/`TD`/`TJ`
    // assigned whatever arrived straight into the graphics state.
    //
    // NaN is the dangerous half and it needs no malformed syntax to reach: an
    // infinite `Tfs` times the zero scale of a perfectly legal `0 0 0 0 0 0 cm` is
    // `inf * 0` = NaN. NaN is not a wrong number, it is an INVISIBLE one — every
    // comparison against it is false, so it passes straight through a clamp (Rust's
    // `f64::clamp` returns NaN, and Kotlin's `coerceIn`/`coerceAtLeast` are
    // comparisons, so they do too) and is noticed only by the rasterizer, which
    // drops the geometry without a word. Diagnosed by `r5-text` and `r5-kotlin` from
    // a non-finite `Prim::Text.h_scale`; guarded here at the operand so it cannot be
    // reintroduced by the next consumer that forgets to defend itself.
    //
    // `None` means "treat the operand as absent", which every caller below already
    // handles by leaving the current value alone — the rule `read_matrix` gives
    // `cm` and `Tm`.
    let numop = |o: &[Object], i: usize| -> Option<f64> {
        o.get(i)
            .and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x)))
            .filter(|v| v.is_finite())
    };

    // §9.6.5: "The glyph description shall begin with either the d0 or the d1
    // operator." A `d1` glyph is SHAPE ONLY — Table 113 requires any colour or
    // colour-related parameters it specifies to be IGNORED and the glyph painted
    // with the current text-state colour. Without this a CharProc that does
    // `1 1 1 rg` after its `d1` paints white on white: invisible, no crash, no log.
    //
    // Keyed on the leading operator rather than a threaded parameter because `d1`
    // is legal nowhere else, so its presence at index 0 IS the signal, and reading
    // it here needs no change at the four call sites. Unreachable until
    // `content::repair_d0_d1` restored `d1` as an operator lopdf can produce.
    let type3_shape_only = ops.first().is_some_and(|op| op.operator == "d1");

    for op in ops.iter().take(MAX_CONTENT_OPS) {
        let o = &op.operands;
        if type3_shape_only
            && matches!(
                op.operator.as_str(),
                "g" | "rg" | "k" | "cs" | "sc" | "scn" | "G" | "RG" | "K" | "CS" | "SC" | "SCN"
            )
        {
            continue;
        }
        match op.operator.as_str() {
            "q" => {
                if stack.len() < MAX_GRAPHICS_STACK_HARD {
                    stack.push(SavedState { gs: gs.clone(), clip_depth, group_depth, clip_bbox: current_clip_bbox });
                } else {
                    q_overflow += 1;
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
                    current_clip_bbox = saved.clip_bbox;
                    gs = saved.gs;
                    // A pending `W` belongs to the path being built inside this
                    // q/Q pair; it must not survive to clip later content.
                    pending_clip = None;
                }
            }
            "cm" => {
                if let Some(m) = read_matrix(o) {
                    // A non-finite CTM poisons every coordinate derived from it,
                    // which makes whole regions of the page silently disappear.
                    let next = mat_mul(&m, &gs.ctm);
                    if next.iter().all(|v| v.is_finite()) {
                        gs.ctm = next;
                    }
                }
            }
            "w" => {
                if let Some(v) = numop(o, 0) { gs.line_width = v; }
            }
            "J" => {
                if let Some(v) = numop(o, 0) { gs.line_cap = (v as i64).clamp(0,2) as u8; }
            }
            "j" => {
                if let Some(v) = numop(o, 0) { gs.line_join = (v as i64).clamp(0,2) as u8; }
            }
            "M" => {
                if let Some(v) = numop(o, 0) { gs.miter_limit = v; }
            }
            "i" => {
                if let Some(v) = numop(o, 0) { gs.flatness = v.clamp(0.0, 100.0); }
            }
            "d" => {
                let dash_obj = o.first().and_then(|x| deref(doc, x).or(Some(x)));
                let mut dashes: Vec<f64> = if let Some(Object::Array(arr)) = dash_obj {
                    arr.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).filter(|v| v.is_finite() && *v >= 0.0).take(MAX_DASH_LEN).collect()
                } else { Vec::new() };
                if dashes.len() % 2 == 1 && !dashes.is_empty() { let cl = dashes.clone(); dashes.extend(cl); if dashes.len() > MAX_DASH_LEN { dashes.truncate(MAX_DASH_LEN); } }
                gs.dash = dashes;
                gs.dash_phase = numop(o, 1).unwrap_or(0.0);
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
                        // §7.3.10: ANY object may be an indirect reference, including a
                        // Table 58 scalar. These were read with a bare `num`, so `/ca
                        // 5 0 R` was silently ignored and the element painted fully
                        // opaque instead of at the requested alpha — a silent failure,
                        // and inconsistent with the `w`/`J`/`j`/`M`/`i` operator arms
                        // above, which all deref their operands.
                        let scalar = |key: &[u8]| {
                            dict.get(key)
                                .ok()
                                .and_then(|o| deref(doc, o).or(Some(o)))
                                .and_then(num)
                                // Same §7.3.3 hazard as the operator arms: a
                                // non-finite `/ca` clamps to NaN, and
                                // `apply_alpha_to_argb`'s `as u32` then saturates it
                                // to 0, painting the element fully TRANSPARENT.
                                .filter(|v| v.is_finite())
                        };
                        // ISO 32000: /CA is the stroking alpha, /ca is the nonstroking (fill) alpha.
                        if let Some(v) = scalar(b"CA") {
                            gs.alpha_stroke = v.clamp(0.0,1.0);
                        }
                        if let Some(v) = scalar(b"ca") {
                            gs.alpha_fill = v.clamp(0.0,1.0);
                        }
                        if let Some(v) = scalar(b"LW") {
                            gs.line_width = v;
                        }
                        if let Some(v) = scalar(b"LC") {
                            gs.line_cap = (v as i64).clamp(0,2) as u8;
                        }
                        if let Some(v) = scalar(b"LJ") {
                            gs.line_join = (v as i64).clamp(0,2) as u8;
                        }
                        if let Some(v) = scalar(b"ML") {
                            gs.miter_limit = v;
                        }
                        // §8.4.5 Table 58 `/FL` is the flatness tolerance, i.e. the
                        // graphics-state form of the `i` operator (§10.6.2), and it is
                        // what `bezier_steps_for_flatness` consumes. It was the one
                        // Table 58 key with existing plumbing and no parser, so a
                        // document that set flatness via `gs` instead of `i` got the
                        // default tolerance and visibly faceted large curves. The clamp
                        // is the `i` arm's.
                        if let Some(v) = scalar(b"FL") {
                            gs.flatness = v.clamp(0.0, 100.0);
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
                                // §11.6.3: when `/BM` is an array the reader shall use
                                // the FIRST name in it that it RECOGNISES. The array
                                // form exists so a file can name a future or vendor
                                // blend mode first and a supported fallback after it —
                                // it is not "the first non-Normal name". Skipping a
                                // leading /Normal inverts the rule: `/BM [/Normal
                                // /Multiply]` means Normal, and treating it as Multiply
                                // darkens content that should composite normally, twice
                                // over wherever it overlaps itself. Recognition lives in
                                // `from_name_checked` next to the name table, so it
                                // cannot drift from it when a mode is added.
                                if let Some(bm) = arr
                                    .iter()
                                    .filter_map(|el| el.as_name().ok())
                                    .find_map(BlendMode::from_name_checked)
                                {
                                    gs.blend_mode = bm;
                                }
                            }
                        }
                        // ---------------------------------------------------------
                        // Table 58 keys that are DELIBERATELY not parsed. Recorded
                        // here so the next audit can tell a decision from an
                        // oversight, and because for several of them a partial
                        // simulation is measurably WORSE than ignoring them — the
                        // lesson of the overprint approximation that had to be
                        // removed, where `white MULTIPLY dst == dst` turned white
                        // knockout rectangles into no-ops and let covered content
                        // reappear.
                        //
                        // /OP /op /OPM — §8.6.7 scopes overprint to devices with
                        //   separable colorants. An additive RGB compositor has none,
                        //   so there is nothing to record and no consumer for it.
                        // /TR /TR2 — §10.4. A transfer function maps DEVICE colour
                        //   components after conversion into the device colour space,
                        //   which is Clause 10 "Rendering", i.e. device-dependent
                        //   calibration for a specific press. It is not the same
                        //   parameter as the soft-mask /TR of §11.6.5.2, which is a
                        //   mask-SHAPE function, is device-independent, and IS
                        //   implemented (see the /SMask arm and `read_transfer_lut`).
                        //   Honouring this one would also have to be all-or-nothing:
                        //   fill/stroke/text colours are computed here and could be
                        //   remapped, but a DCTDecode image is passed through to the
                        //   renderer as JPEG bytes and cannot be, so an inverting /TR
                        //   would invert the vector layer and leave the photographs
                        //   alone. A uniformly wrong page beats a half-inverted one.
                        // /HT — §10.5 halftones. A halftone screen exists to render
                        //   continuous tone on a bilevel device; the output here is
                        //   8-bit-per-channel antialiased RGB, which represents the
                        //   requested tone directly. Applying a screen could only
                        //   throw tonal resolution away.
                        // /BG /BG2 /UCR /UCR2 — §10.3 black generation and undercolour
                        //   removal, defined only for the DeviceGray -> DeviceCMYK
                        //   conversion. Nothing here converts to CMYK.
                        // /RI — §8.6.5.8 rendering intent. Selects a gamut-mapping
                        //   policy for an ICC transform; colour here goes through each
                        //   space's defining formulae rather than an ICC engine, so
                        //   there is no transform for it to parameterise. The `ri`
                        //   operator is a documented no-op for the same reason.
                        // /SM — §10.6.3 smoothness tolerance: a shading-quality hint,
                        //   with the raster resolution already derived from the device
                        //   footprint of the clip region.
                        // /SA — §10.6 automatic stroke adjustment: quantises stroke
                        //   edges to the pixel grid for crispness at low resolution.
                        //   That is the renderer's own scan conversion, not something
                        //   expressible in the primitive stream.
                        // /TK — §9.3.8 text knockout, and /AIS — §11.6.4.3 alpha-is-
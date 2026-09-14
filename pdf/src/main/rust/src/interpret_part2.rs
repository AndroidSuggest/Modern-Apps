
impl SeededInterp {
    fn handle_state_ops(&mut self, op: &lopdf::content::Operation, o: &[Object], doc: &Document, resources: Option<&lopdf::Dictionary>, prims: &mut Vec<Prim>, text_only: bool) {
        // Operand accessor: `None` means "treat the operand as absent", which every
        // caller handles by leaving the current value alone. Filters non-finite
        // values (see the `finite2` guard rationale at the top of this method).
        let numop = |o: &[Object], i: usize| -> Option<f64> {
            o.get(i)
                .and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x)))
                .filter(|v| v.is_finite())
        };
        match op.operator.as_str() {
                "q" => {
                    if self.stack.len() < MAX_GRAPHICS_STACK_HARD {
                        self.stack.push(SavedState { gs: self.gs.clone(), clip_depth: self.clip_depth, group_depth: self.group_depth, clip_bbox: self.current_clip_bbox });
                    } else {
                        self.q_overflow += 1;
                    }
                }
                "Q" => {
                    if self.q_overflow > 0 {
                        self.q_overflow -= 1;
                    } else if let Some(saved) = self.stack.pop() {
                        while self.clip_depth > saved.clip_depth {
                            if !text_only { prims.push(Prim::ClipPop); }
                            self.clip_depth = self.clip_depth.saturating_sub(1);
                        }
                        while self.group_depth > saved.group_depth {
                            if !text_only { prims.push(Prim::GroupPop); }
                            self.group_depth = self.group_depth.saturating_sub(1);
                        }
                        self.current_clip_bbox = saved.clip_bbox;
                        self.gs = saved.gs;
                        // A pending `W` belongs to the path being built inside this
                        // q/Q pair; it must not survive to clip later content.
                        self.pending_clip = None;
                    }
                }
                "cm" => {
                    if let Some(m) = read_matrix(o) {
                        // A non-finite CTM poisons every coordinate derived from it,
                        // which makes whole regions of the page silently disappear.
                        let next = mat_mul(&m, &self.gs.ctm);
                        if next.iter().all(|v| v.is_finite()) {
                            self.gs.ctm = next;
                        }
                    }
                }
                "w" => {
                    if let Some(v) = numop(o, 0) { self.gs.line_width = v; }
                }
                "J" => {
                    if let Some(v) = numop(o, 0) { self.gs.line_cap = (v as i64).clamp(0,2) as u8; }
                }
                "j" => {
                    if let Some(v) = numop(o, 0) { self.gs.line_join = (v as i64).clamp(0,2) as u8; }
                }
                "M" => {
                    if let Some(v) = numop(o, 0) { self.gs.miter_limit = v; }
                }
                "i" => {
                    if let Some(v) = numop(o, 0) { self.gs.flatness = v.clamp(0.0, 100.0); }
                }
                "d" => {
                    let dash_obj = o.first().and_then(|x| deref(doc, x).or(Some(x)));
                    let mut dashes: Vec<f64> = if let Some(Object::Array(arr)) = dash_obj {
                        arr.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).filter(|v| v.is_finite() && *v >= 0.0).take(MAX_DASH_LEN).collect()
                    } else { Vec::new() };
                    if dashes.len() % 2 == 1 && !dashes.is_empty() { let cl = dashes.clone(); dashes.extend(cl); if dashes.len() > MAX_DASH_LEN { dashes.truncate(MAX_DASH_LEN); } }
                    self.gs.dash = dashes;
                    self.gs.dash_phase = numop(o, 1).unwrap_or(0.0);
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
                        let chosen: Option<lopdf::Dictionary> = if let Some(&id) = self.extgstates.get(name) {
                            // Cloned rather than borrowed: `apply_dict` needs `&mut gs`
                            // while `doc` is still borrowed by the dictionary.
                            doc.get_dictionary(id).ok().cloned()
                        } else {
                            inline_dict.cloned()
                        };
                        if let Some(dict_clone) = chosen {
                            apply_dict(&dict_clone, &mut self.gs, doc);
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
                                    if !self.fonts.contains_key(&key) {
                                        if let Ok(Object::Dictionary(fd)) = doc.get_object(fid) {
                                            let fd = fd.clone();
                                            self.fonts.insert(key.clone(), font_info(doc, &fd));
                                        }
                                    }
                                    // Only adopt it once there really are metrics behind
                                    // the key: a dangling reference must leave whatever
                                    // `Tf` last selected alone rather than blanking it.
                                    if self.fonts.contains_key(&key) {
                                        self.gs.font_key = key;
                                        if let Some(sz) = farr.get(1).and_then(|o| deref(doc, o).and_then(num).or_else(|| num(o))).filter(|v| v.is_finite()) {
                                            self.gs.font_size = sz;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                _ => {}
        }
    }
}

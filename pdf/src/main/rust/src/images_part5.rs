fn rasterize_shading_inner(doc: &Document, shading_obj: &Object, base_ctm: &Mat, cs_resources: &HashMap<Vec<u8>, ObjectId>, size: u32, clip_bbox_device: Option<[f64;4]>, apply_background: bool) -> Option<(Mat, u32, u32, Vec<u8>)> {
    // A shading may be a plain dictionary (Type 1-3) or a stream (Type 4-7,
    // whose mesh data lives in the stream body).
    let (dict, mesh_bytes): (&lopdf::Dictionary, Option<Vec<u8>>) = match shading_obj {
        Object::Dictionary(d) => (d, None),
        Object::Stream(s) => (&s.dict, Some(stream_data_with_doc(doc, s))),
        _ => return None,
    };
    let shading_type = dict.get(b"ShadingType").ok().and_then(|o| deref(doc, o)).and_then(num).unwrap_or(0.0) as i64;
    // When the caller passes size==0, derive an effective resolution from the
    // device footprint of the clip region (falls back to 256 if unknown).
    let auto_size = || -> u32 {
        match clip_bbox_device {
            Some(b) => (((b[2]-b[0]).abs()).max((b[3]-b[1]).abs()).ceil() as u32).clamp(64, 1024),
            None => 256,
        }
    };
    let size = if size == 0 { auto_size() } else { size };
    if shading_type==4 || shading_type==5 || shading_type==6 || shading_type==7 {
        // Mesh shadings: pass the decoded stream body so real vertices/patches
        // can be parsed (falls back to /DataSource when the dict provides one).
        let mut out = shading::rasterize_shading_mesh(doc, dict, mesh_bytes.as_deref(), base_ctm, cs_resources, size)?;
        if apply_background { fill_background_outside(doc, dict, cs_resources, &mut out.3); }
        return Some(out);
    }
    if shading_type==1 {
        // NO `fill_background_outside` here, unlike the mesh types above. Table 78
        // scopes /Background to "those portions of the area to be painted that lie
        // OUTSIDE the bounds of the shading object", and a function-based raster has no
        // such portions: it covers exactly the /Domain rect, so every pixel in it is
        // in-domain by construction. The only way one stays at alpha 0 is
        // `eval_cs_to_rgb` failing, which is a colour we could not compute for a point
        // that IS inside the extent — the same distinction the axial/radial loop below
        // already draws. Filling those with /Background paints an opaque rectangle of
        // background colour over the page content, which is the failure direction this
        // file consistently refuses. (r5-color, task 5.)
        return rasterize_shading_function_based(doc, dict, base_ctm, cs_resources, size);
    }
    if shading_type!=2 && shading_type!=3 { return None; }

    let coords = dict.get(b"Coords").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect::<Vec<f64>>()).unwrap_or_default();
    // Background color for areas outside Extend
    let bg = dict.get(b"Background").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect::<Vec<f64>>());

    // /BBox bounds the shading in shading space. When absent, cover the current
    // clip region instead of an arbitrary 100×100 box: map the device clip bbox
    // back into shading space via the inverse CTM.
    //
    // With no /BBox AND no clip bbox we now paint NOTHING rather than inventing a
    // 100x100 shading-space box, which rendered the gradient as a small patch near
    // the origin with the rest of the region unpainted. §8.7.4.1 requires `sh` to
    // cover the entire clipping region, so a guessed box is always wrong; returning
    // None pushes no Image prim at all. audit-b seeds current_clip_bbox from the page
    // device box, so the None arm should be unreachable in practice.
    let bbox = match dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)) {
        Some(b) => b,
        None => {
            let cb = clip_bbox_device?;
            let inv = mat_inverse(base_ctm);
            let corners = [
                transform(&inv, cb[0], cb[1]), transform(&inv, cb[2], cb[1]),
                transform(&inv, cb[2], cb[3]), transform(&inv, cb[0], cb[3]),
            ];
            let xs = corners.iter().map(|p| p.0);
            let ys = corners.iter().map(|p| p.1);
            let x0 = xs.clone().fold(f64::INFINITY, f64::min);
            let x1 = xs.fold(f64::NEG_INFINITY, f64::max);
            let y0 = ys.clone().fold(f64::INFINITY, f64::min);
            let y1 = ys.fold(f64::NEG_INFINITY, f64::max);
            if x1 > x0 && y1 > y0 {
                [x0, y0, x1, y1]
            } else {
                return None;
            }
        }
    };

    let color_space_obj = dict.get(b"ColorSpace").ok().and_then(|o| parse_cs_kind(doc, Some(o), cs_resources));
    // If CS not present, try to infer from Function or Background etc.

    // Function: full PDF function support (Type 0/2/3/4, or array-of-functions).
    let pdf_func = dict.get(b"Function").ok().and_then(|o| PdfFunction::parse(doc, o));

    // Extend [bool bool] — spec default is [false false] (ISO 32000 Table 79).
    let extend = dict.get(b"Extend").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).map(|v| matches!(v, Object::Boolean(true)))).collect::<Vec<bool>>()).unwrap_or(vec![false,false]);
    // Domain [t0 t1] maps the normalized axis parameter to the function domain
    // (default [0 1]).
    let domain = dict.get(b"Domain").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect::<Vec<f64>>())
        .filter(|v| v.len() >= 2).unwrap_or(vec![0.0, 1.0]);

    // The raster used to be square (`let w = size; let h = size;`), so a 600x5 pt
    // gradient bar allocated size² pixels — up to ~100x the memory the shading
    // actually covers, tripled across prims + wire buffer + Kotlin Bitmap. Size each
    // axis from the bbox's DEVICE-space extent, keeping the long side at `size`.
    let (w, h) = {
        // `size` is guaranteed >=64 by the auto_size() shadowing above, but clamp(1, n)
        // panics when n == 0, so don't depend on a distant invariant for memory safety.
        let long = size.max(1);
        let ext_x = (bbox[2] - bbox[0]).abs();
        let ext_y = (bbox[3] - bbox[1]).abs();
        // A bbox-space edge of length L maps to a device vector of length
        // |(a·L, b·L)| horizontally and |(c·L, d·L)| vertically.
        let dev_x = ((base_ctm[0] * ext_x).powi(2) + (base_ctm[1] * ext_x).powi(2)).sqrt();
        let dev_y = ((base_ctm[2] * ext_y).powi(2) + (base_ctm[3] * ext_y).powi(2)).sqrt();
        if !dev_x.is_finite() || !dev_y.is_finite() || dev_x <= 0.0 || dev_y <= 0.0 {
            (long, long)
        } else if dev_x >= dev_y {
            (long, ((long as f64 * dev_y / dev_x).round() as u32).clamp(1, long))
        } else {
            (((long as f64 * dev_x / dev_y).round() as u32).clamp(1, long), long)
        }
    };
    // Bound a SINGLE shading's raster. `auto_size()` keeps the long side <=1024, so a
    // near-square shading still reaches 1024*1024*4 = 4 MB, and these are NOT transient
    // in aggregate: every shading raster on the page is held simultaneously in `prims`
    // and then copied wholesale into the wire buffer, so a 131-shading page peaks at
    // hundreds of MB inside Rust before Kotlin sees a byte. A Rust OOM is an
    // uncatchable process abort, so the cumulative cap on the Kotlin bitmap heap
    // (audit-e/audit-g) cannot substitute for a floor here.
    //
    // Scale BOTH axes by the same factor so the aspect ratio derived above survives.
    // This path only ever handles types 2 and 3, whose colour comes from a 256-entry LUT
    // over a single scalar — so MAX_GRADIENT_RASTER_BYTES rather than the general
    // MAX_SHADING_RASTER_BYTES the mesh path in shading.rs uses. See that constant for
    // why the tighter bound costs no fidelity at all here.
    let (w, h) = {
        let bytes = (w as usize).saturating_mul(h as usize).saturating_mul(4);
        if bytes > MAX_GRADIENT_RASTER_BYTES {
            let scale = (MAX_GRADIENT_RASTER_BYTES as f64 / bytes as f64).sqrt();
            (
                ((w as f64 * scale).round() as u32).max(1),
                ((h as f64 * scale).round() as u32).max(1),
            )
        } else {
            (w, h)
        }
    };
    let mut rgba = vec![0u8; (w*h*4) as usize];

    // Helpers to evaluate color at t 0..1 via function. The normalized parameter
    // is first mapped through /Domain before the function is evaluated.
    let eval_func = |t: f64| -> Option<Vec<f64>> {
        if let Some(ref f) = pdf_func {
            let td = domain[0] + t * (domain[1] - domain[0]);
            return Some(f.eval(&[td]));
        }
        // NOT /Background. §8.7.4.3 Table 78 confines it to "those portions of the area
        // to be painted that lie OUTSIDE the bounds of the shading object" — it is never
        // the shading's own colour. /Function is required for types 2 and 3 (§8.7.4.5.3),
        // and substituting the background here made every one of the 256 LUT slots the
        // background colour, so a /Function-less shading painted its WHOLE area solid
        // opaque background, gradient region included.
        None
    };

    // Precompute a 256-entry color LUT over the normalized parameter t∈[0,1].
    // Axial/radial color depends only on t, so evaluating the PDF function and
    // colorspace conversion once per LUT slot instead of once per pixel turns an
    // O(size²) per-pixel function-eval into O(256) — the dominant cost on
    // gradient-heavy pages (e.g. issue #321 missinggraphic: 131 shadings).
    let cs_for_lut = color_space_obj.as_ref().unwrap_or(&CsKind::DeviceRGB);
    let mut color_lut: [Option<u32>; 256] = [None; 256];
    let mut lut_fallback: [Option<[u8; 4]>; 256] = [None; 256];
    for (i, slot) in color_lut.iter_mut().enumerate() {
        let t = i as f64 / 255.0;
        if let Some(comps) = eval_func(t) {
            if let Some(argb) = eval_cs_to_rgb(doc, cs_for_lut, &comps, cs_resources) {
                *slot = Some(argb);
            } else {
                let v = (comps.first().copied().unwrap_or(0.0) * 255.0) as u8;
                lut_fallback[i] = Some([v, v, v, 255]);
            }
        }
    }
    // Background color for out-of-range pixels, computed once. Only when the shading
    // is painted as a PATTERN — §8.7.4.3 makes `sh` ignore /Background.
    let bg_argb = if !apply_background {
        None
    } else {
        bg.as_ref().and_then(|bgc| {
            let cs = color_space_obj.as_ref().unwrap_or(&CsKind::DeviceRGB);
            eval_cs_to_rgb(doc, cs, bgc, cs_resources)
        })
    };

    for y in 0..h as usize {
        for x in 0..w as usize {
            // map pixel to shading BBox space
            let fx = bbox[0] + (x as f64 + 0.5)/ w as f64 * (bbox[2]-bbox[0]);
            // Raster row 0 is the TOP of the image, i.e. unit-square v=1, i.e. the HIGH-y
            // edge of the bbox (8.9.5.2: the first sample of the first row is at the
            // upper-left). Sampling upward from bbox[1] instead put row 0 at LOW y, and the
            // placement CTM's positive `d` then mirrored every gradient vertically - an
            // axial ramp ran backwards. Real decoded images are top-down and already
            // correct, so the fix belongs in the synthesised rasters, not the renderer.
            let fy = bbox[3] - (y as f64 + 0.5)/ h as f64 * (bbox[3]-bbox[1]);

            let t = if shading_type==2 {
                // Axial: coords [x0 y0 x1 y1]; project point onto line
                if coords.len()>=4 {
                    let x0=coords[0]; let y0=coords[1]; let x1=coords[2]; let y1=coords[3];
                    let dx = x1 - x0;
                    let dy = y1 - y0;
                    let len2 = dx*dx+dy*dy;
                    if len2<1e-12 {
                        0.0
                    } else {
                        let v = ((fx - x0)*dx + (fy - y0)*dy)/len2;
                        // `len2 < 1e-12` is FALSE for inf and NaN, so non-finite
                        // /Coords (a real like 1e40 overflows an f32 on parse) fall
                        // through to this division and yield NaN for every pixel. That
                        // used to be harmless, because the NaN guard below just skipped
                        // them — but now that the guard paints /Background, a degenerate
                        // axial PATTERN would flood its whole raster with the background
                        // colour. Axial has a defined answer for degenerate geometry
                        // (the same 0.0 the len2 ~ 0 arm gives), so keep it finite by
                        // construction: the NaN branch is then reachable only by radial,
                        // the one type for which NaN means "outside the extent".
                        if v.is_finite() { v } else { 0.0 }
                    }
                } else { 0.0 }
            } else {
                // Radial (Type 3): solve for the gradient parameter at (fx,fy).
                if coords.len()>=6 {
                    radial_shading_param(
                        &coords,
                        extend.first().copied().unwrap_or(false),
                        extend.get(1).copied().unwrap_or(false),
                        fx, fy,
                    ).unwrap_or(f64::NAN)
                } else { 0.0 }
            };

            let idx = (y * w as usize + x) * 4;
            // A point covered by no circle of the (possibly extended) family lies
            // OUTSIDE the shading's extent, which is exactly what /Background is for
            // (§8.7.4.3 Table 78). `radial_shading_param` applies /Extend itself and
            // returns None rather than an out-of-range t, so the `t < 0` and `t > 1`
            // arms below are structurally UNREACHABLE for ShadingType 3 — this is the
            // only place a radial shading can paint its background, and plain
            // `continue` meant a radial pattern with /Background painted none of it.
            // Under `sh`, bg_argb is None and the pixel stays clear, as it must.
            if t.is_nan() {
                if let Some(argb) = bg_argb {
                    rgba[idx] = ((argb >> 16) & 0xFF) as u8;
                    rgba[idx + 1] = ((argb >> 8) & 0xFF) as u8;
                    rgba[idx + 2] = (argb & 0xFF) as u8;
                    rgba[idx + 3] = 255;
                }
                continue;
            }

            // Extend handling. Out-of-range pixels use the precomputed
            // background color (or stay transparent when none is defined).
            let t_clamped = if t < 0.0 {
                if extend.first().copied().unwrap_or(false) {
                    0.0
                } else {
                    if let Some(argb) = bg_argb {
                        rgba[idx] = ((argb >> 16) & 0xFF) as u8;
                        rgba[idx + 1] = ((argb >> 8) & 0xFF) as u8;
                        rgba[idx + 2] = (argb & 0xFF) as u8;
                        rgba[idx + 3] = 255;
                    }
                    continue;
                }
            } else if t > 1.0 {
                if extend.get(1).copied().unwrap_or(false) {
                    1.0
                } else {
                    if let Some(argb) = bg_argb {
                        rgba[idx] = ((argb >> 16) & 0xFF) as u8;
                        rgba[idx + 1] = ((argb >> 8) & 0xFF) as u8;
                        rgba[idx + 2] = (argb & 0xFF) as u8;
                        rgba[idx + 3] = 255;
                    }
                    continue;
                }
            } else {
                t
            };

            // Look up the gradient color from the precomputed LUT.
            let li = ((t_clamped.clamp(0.0, 1.0) * 255.0).round() as usize).min(255);
            if let Some(argb) = color_lut[li] {
                rgba[idx] = ((argb >> 16) & 0xFF) as u8;
                rgba[idx + 1] = ((argb >> 8) & 0xFF) as u8;
                rgba[idx + 2] = (argb & 0xFF) as u8;
                rgba[idx + 3] = 255;
            } else if let Some(px) = lut_fallback[li] {
                rgba[idx] = px[0];
                rgba[idx + 1] = px[1];
                rgba[idx + 2] = px[2];
                rgba[idx + 3] = px[3];
            }
            // and NO /Background arm here. This point is INSIDE the shading's extent —
            // t is in [0,1] — so Table 78 does not apply: a colour we could not compute
            // leaves the pixel clear. Painting the background here is the same conflation
            // as the `eval_func` case above, and the two must be removed together:
            // with `eval_func` now returning None for a /Function-less shading, every LUT
            // slot is empty and this arm alone would reproduce the whole-area flood.
        }
    }

    // Map bbox to base CTM: we produce an image that covers bbox rectangle
    // CTM to place: translate bbox[0],bbox[1] and scale bboxW, bboxH
    let bw = bbox[2]-bbox[0];
    let bh = bbox[3]-bbox[1];
    // shading CTM: [bw 0 0 bh bbox0 bbox1] * base_ctm
    let shading_mat: Mat = [bw, 0.0, 0.0, bh, bbox[0], bbox[1]];
    let ctm = mat_mul(&shading_mat, base_ctm);
    if !mat_is_finite(&ctm) {
        image_warn!("axial/radial shading placement matrix is non-finite - shading dropped");
        return None;
    }
    Some((ctm, w, h, rgba))
}

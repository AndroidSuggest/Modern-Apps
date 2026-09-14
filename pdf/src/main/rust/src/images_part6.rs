/// Type 1 (function-based) shading: sample the 2-D `/Domain` through the
/// shading `/Function` and emit the result as an image, placed via `/Matrix`.
fn rasterize_shading_function_based(doc: &Document, dict: &lopdf::Dictionary, base_ctm: &Mat, cs_resources: &HashMap<Vec<u8>, ObjectId>, size: u32) -> Option<(Mat, u32, u32, Vec<u8>)> {
    if size == 0 || size > 1024 { return None; }
    let domain: Vec<f64> = dict.get(b"Domain").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect()).unwrap_or_else(|| vec![0.0, 1.0, 0.0, 1.0]);
    if domain.len() < 4 { return None; }
    let (dx0, dx1, dy0, dy1) = (domain[0], domain[1], domain[2], domain[3]);
    if (dx1 - dx0).abs() < 1e-9 || (dy1 - dy0).abs() < 1e-9 { return None; }

    let matrix: Mat = dict.get(b"Matrix").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .and_then(|a| {
            let v: Vec<f64> = a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect();
            if v.len() == 6 { Some([v[0], v[1], v[2], v[3], v[4], v[5]]) } else { None }
        }).unwrap_or(IDENTITY);

    let func = dict.get(b"Function").ok().and_then(|o| PdfFunction::parse(doc, o))?;
    let cs = dict.get(b"ColorSpace").ok().and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)).unwrap_or(CsKind::DeviceRGB);

    // §8.7.4.3 Table 78: /BBox is a common shading entry, not a type 2/3 one, and is
    // "applied as a temporary clipping boundary when the shading is painted". Types 2
    // and 3 use it as their raster's footprint and the mesh path intersects it with the
    // /Decode extent, leaving type 1 as the only shading that ignored it — a /BBox
    // smaller than the /Domain painted outside the box.
    //
    // It cannot be the footprint here, because a type 1 raster IS the /Domain rect. Its
    // coordinates are in the shading's TARGET space, i.e. after /Matrix, so the test has
    // to happen per pixel with /Matrix applied and cannot be folded into the raster's
    // extent.
    //
    // §7.9.5 permits "any two diagonally opposite corners", so the corners are normalised
    // BEFORE any width test: `read_rect` hands back the four numbers raw and only rejects
    // non-finite ones, so an unnormalised [100 100 0 0] would otherwise read as degenerate
    // and have its perfectly legitimate 100x100 clip discarded.
    let clip_bbox = dict
        .get(b"BBox")
        .ok()
        .and_then(|o| read_rect(doc, o))
        .map(|b| [b[0].min(b[2]), b[1].min(b[3]), b[0].max(b[2]), b[1].max(b[3])]);
    // A zero-area /BBox clips EVERYTHING away, and this returns None rather than ignoring
    // it. That reverses my first instinct — that a degenerate entry silently deleting the
    // graphic is the invisible-hole failure — and r5-color's counter-argument is right on
    // all three counts:
    //
    //  - It is not a special case, it is the endpoint of one already handled. A /BBox that
    //    simply does not intersect the domain (say [500 500 600 600] over a domain at the
    //    origin) paints nothing, and must. Ignoring only the exactly-zero case makes the
    //    behaviour DISCONTINUOUS in the data: a 1e-9-wide box paints nothing while a
    //    0-wide box paints the whole domain. Leniency cannot be made consistent here;
    //    only honouring the clip can.
    //  - shading.rs:151-158 already returns None for a degenerate /BBox on types 4-7, so
    //    ignoring it here would split one Table 78 entry into two answers by ShadingType.
    //    That is the same shape of defect as the CCITT stride disagreement found earlier
    //    this round, and just as hard to trace from the rendering.
    //  - The non-finite precedent does not transfer. Falling back to a default for a
    //    non-finite /Matrix is right because the value has no meaning. A zero-area
    //    rectangle is well-formed data with defined clipping semantics; "I understand
    //    this and it says paint nothing" is not the same as "I cannot parse this".
    //
    // The counterweight points the same way once stated precisely: ignoring a clip makes
    // content APPEAR that the file said to hide, which is the direction the removed
    // transparency approximation failed in, not the opposite one.
    //
    // Same 1e-9 threshold as shading.rs, so the two paths agree at the boundary too.
    if let Some(b) = clip_bbox {
        if b[2] - b[0] < 1e-9 || b[3] - b[1] < 1e-9 {
            image_warn!("function-based shading /BBox has zero area - nothing to paint");
            return None;
        }
    }

    // Image [0,1]^2 -> domain rect -> Matrix -> base CTM.
    let unit_to_domain: Mat = [dx1 - dx0, 0.0, 0.0, dy1 - dy0, dx0, dy0];
    let ctm = mat_mul(&mat_mul(&unit_to_domain, &matrix), base_ctm);
    if !mat_is_finite(&ctm) {
        image_warn!("function-based shading placement matrix is non-finite - shading dropped");
        return None;
    }

    // Size each axis from its DEVICE-space extent instead of forcing a square. Types 2/3
    // and the mesh types were fixed in round 1 but this one was missed: a /Domain of
    // [0 600 0 5] still allocated size*size, up to ~100x the memory the shading covers
    // and tripled across `prims`, the wire buffer and the Kotlin Bitmap. `ctm` maps the
    // unit square, so a non-square raster is geometrically identical.
    let (w, h) = {
        let long = size.max(1);
        let dev_x = (ctm[0] * ctm[0] + ctm[1] * ctm[1]).sqrt();
        let dev_y = (ctm[2] * ctm[2] + ctm[3] * ctm[3]).sqrt();
        if !dev_x.is_finite() || !dev_y.is_finite() || dev_x <= 0.0 || dev_y <= 0.0 {
            (long, long)
        } else if dev_x >= dev_y {
            (long, ((long as f64 * dev_y / dev_x).round() as u32).clamp(1, long))
        } else {
            (((long as f64 * dev_x / dev_y).round() as u32).clamp(1, long), long)
        }
    };
    // And bound the total bytes, as the other two paths already do — see
    // MAX_SHADING_RASTER_BYTES. Scaling both axes by sqrt preserves the aspect ratio.
    let (w, h) = {
        let bytes = (w as usize).saturating_mul(h as usize).saturating_mul(4);
        if bytes > MAX_SHADING_RASTER_BYTES {
            let scale = (MAX_SHADING_RASTER_BYTES as f64 / bytes as f64).sqrt();
            (
                ((w as f64 * scale).round() as u32).max(1),
                ((h as f64 * scale).round() as u32).max(1),
            )
        } else {
            (w, h)
        }
    };

    let (wu, hu) = (w as usize, h as usize);
    let mut rgba = vec![0u8; wu * hu * 4];
    for py in 0..hu {
        for px in 0..wu {
            let u = (px as f64 + 0.5) / wu as f64;
            // Row 0 is the top of the image (v=1), so walk the domain's y DOWNWARD. See
            // the axial/radial loop for why.
            let v = 1.0 - (py as f64 + 0.5) / hu as f64;
            let x = dx0 + u * (dx1 - dx0);
            let y = dy0 + v * (dy1 - dy0);
            // /BBox is in target space, so the domain point has to go through /Matrix
            // before the containment test.
            if let Some(b) = clip_bbox {
                let (tx, ty) = transform(&matrix, x, y);
                if tx < b[0] || tx > b[2] || ty < b[1] || ty > b[3] {
                    continue;
                }
            }
            let comps = func.eval(&[x, y]);
            let idx = (py * wu + px) * 4;
            if let Some(argb) = eval_cs_to_rgb(doc, &cs, &comps, cs_resources) {
                rgba[idx] = ((argb >> 16) & 0xFF) as u8;
                rgba[idx + 1] = ((argb >> 8) & 0xFF) as u8;
                rgba[idx + 2] = (argb & 0xFF) as u8;
                rgba[idx + 3] = 255;
            }
        }
    }
    Some((ctm, w, h, rgba))
}


/// Apply a per-pixel soft-mask alpha (length `w*h`) to an RGBA buffer.
/// Rasterize ONE tiling-pattern cell for a repeating-image fill (§8.7.3.3).
///
/// `cell_prims` must be the pattern cell's content interpreted in PATTERN space (the
/// pattern `/Matrix` NOT yet applied), so `bbox`, `xstep` and `ystep` share their
/// coordinate system. `device_scale` converts pattern-space units to device pixels and
/// sets the raster resolution.
///
/// Returns `(w, h, rgba)` for a cell that can be tiled periodically, or `None` when the
/// pattern CANNOT be expressed that way — in which case the caller must fall back to the
/// per-tile path. This function owns the two rules that make a periodic repeat correct,
/// because both are easy to violate silently:
///
/// 1. The raster covers `/XStep` × `/YStep`, NOT the `/BBox`. A `BitmapShader` in REPEAT
///    mode has a period equal to the bitmap's own dimensions, and PDF's step is
///    independent of the bbox — so a bbox-sized cell retiles at the wrong spacing, which
///    looks like a pattern at subtly wrong density. Where step > bbox the margin is
///    transparent padding, which falls out of rasterizing the STEP rect anchored at the
///    bbox origin: nothing paints there.
/// 2. Overlapping patterns (a step SMALLER than the bbox, 8.7.3.1) are still periodic,
///    with period `(xstep, ystep)` - that is what makes them a lattice at all. What fails
///    is rasterizing the cell ONCE, because content from neighbouring cells spills into
///    every period window. So the cell is drawn at every lattice offset that can reach
///    the window, composited in increasing lattice order to honour "later tiles paint
///    over earlier".
///
///    This is EXACT, not an approximation. 8.7.3.2 replicates the cell across the entire
///    plane and the fill path does the clipping, so the lattice has no boundary; with no
///    boundary every point's set of overlapping contributions is identical modulo the
///    period. Refused only when the bbox/step ratio is pathological.
pub(crate) fn rasterize_pattern_cell(
    cell_prims: &[Prim],
    bbox: [f64; 4],
    xstep: f64,
    ystep: f64,
    device_scale: f64,
) -> Option<(u32, u32, Vec<u8>)> {
    let bw = (bbox[2] - bbox[0]).abs();
    let bh = (bbox[3] - bbox[1]).abs();
    if !xstep.is_finite() || !ystep.is_finite() || xstep <= 0.0 || ystep <= 0.0 {
        return None;
    }
    // Rule 2. An overlapping pattern IS periodic with period (xstep, ystep); the cell
    // just has to be drawn at every offset that can reach one period window. Cell
    // `(i, 0)` paints x in [bx0 + i*xstep, bx0 + i*xstep + bw), which meets the window
    // [bx0, bx0 + xstep) exactly when -bw/xstep < i < 1, i.e. i in -(nx_off-1)..=0.
    let nx_off = (bw / xstep).ceil().max(1.0);
    let ny_off = (bh / ystep).ceil().max(1.0);
    // A bbox 8x the step in both axes is already far beyond anything real; past that the
    // compositing cost stops being worth it and the per-tile path is the honest answer.
    const MAX_CELL_OVERLAP_COPIES: f64 = 64.0;
    if !nx_off.is_finite() || !ny_off.is_finite() || nx_off * ny_off > MAX_CELL_OVERLAP_COPIES {
        image_warn!(
            "tiling pattern bbox {:.3}x{:.3} over step {:.3}x{:.3} needs {}x{} overlapping \
             copies per period - using the per-tile path",
            bw, bh, xstep, ystep, nx_off, ny_off
        );
        return None;
    }
    if !device_scale.is_finite() || device_scale <= 0.0 {
        return None;
    }
    let mut w = (xstep * device_scale).round().max(1.0);
    let mut h = (ystep * device_scale).round().max(1.0);
    // Fit the cell into its budget, preserving the aspect so the period stays square in
    // pattern space. Downscaling a cell only softens it; the tiling stays exact because
    // the renderer's period is the bitmap, whatever its resolution.
    let budget = MAX_TILE_RASTER_BYTES as f64 / 4.0;
    if w * h > budget {
        let s = (budget / (w * h)).sqrt();
        w = (w * s).round().max(1.0);
        h = (h * s).round().max(1.0);
    }
    let (w, h) = (w as usize, h as usize);
    // Rule 1: the STEP rect, anchored at the bbox origin. Content is placed at the bbox
    // origin and anything between the bbox edge and the step edge is simply never
    // painted, which IS the transparent padding.
    let step_box = [bbox[0], bbox[1], bbox[0] + xstep, bbox[1] + ystep];
    // The common non-overlapping case needs no copies at all.
    if nx_off <= 1.0 && ny_off <= 1.0 {
        return rasterize_prims_to_rgba(cell_prims, step_box, w, h)
            .map(|rgba| (w as u32, h as u32, rgba));
    }
    // Overlapping: draw the cell at each reaching offset, in increasing lattice order so
    // later tiles composite over earlier ones (8.7.3.1).
    let (nxo, nyo) = (nx_off as i64, ny_off as i64);
    let mut spread: Vec<Prim> = Vec::with_capacity(cell_prims.len() * (nxo * nyo) as usize);
    for jj in 0..nyo {
        let dy = ((jj - (nyo - 1)) as f64 * ystep) as f32;
        for ii in 0..nxo {
            let dx = ((ii - (nxo - 1)) as f64 * xstep) as f32;
            spread.extend(cell_prims.iter().filter_map(|p| translated_prim(p, dx, dy)));
        }
    }
    rasterize_prims_to_rgba(&spread, step_box, w, h).map(|rgba| (w as u32, h as u32, rgba))
}

/// A `Prim::Fill` or `Prim::Stroke` translated by `(dx, dy)`, or `None` for any other
/// variant. `Prim` is deliberately not `Clone` because it owns image pixel buffers, and
/// these two are the only variants [`rasterize_prims_to_rgba`] draws anyway.
fn translated_prim(p: &Prim, dx: f32, dy: f32) -> Option<Prim> {
    match p {
        Prim::Fill { argb, even_odd, contours, blend } => Some(Prim::Fill {
            argb: *argb,
            even_odd: *even_odd,
            blend: *blend,
            contours: contours
                .iter()
                .map(|c| c.iter().map(|&(x, y)| (x + dx, y + dy)).collect())
                .collect(),
        }),
        Prim::Stroke { argb, width, dash, dash_phase, cap, join, miter, pts, blend } => {
            Some(Prim::Stroke {
                argb: *argb,
                width: *width,
                dash: dash.clone(),
                dash_phase: *dash_phase,
                cap: *cap,
                join: *join,
                miter: *miter,
                blend: *blend,
                pts: pts.iter().map(|&(x, y)| (x + dx, y + dy)).collect(),
            })
        }
        _ => None,
    }
}

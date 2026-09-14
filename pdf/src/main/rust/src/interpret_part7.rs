pub(crate) fn paint_pattern_fill(
    doc: &Document,
    pattern_id: ObjectId,
    polys: &[Vec<(f64, f64)>],
    even_odd: bool,
    pattern_base_ctm: &Mat,
    base_argb: u32,
    alpha_fill: f32,
    blend: BlendMode,
    // See [`paint_pattern_stroke`]: the invoking stream's `/Resources
    // /ColorSpace` map, without which a shading whose `/ColorSpace` is a NAME
    // falls back to DeviceRGB.
    cs_resources: &HashMap<Vec<u8>, ObjectId>,
    prims: &mut Vec<Prim>,
    depth: u32,
    clip_depth: usize,
) {
    if clip_depth >= MAX_CLIP_DEPTH {
        return;
    }
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
    let matrix = dict.get(b"Matrix").ok().and_then(|o| read_matrix_obj(deref(doc, o).unwrap_or(o))).unwrap_or(IDENTITY);
    let pmat = mat_mul(&matrix, pattern_base_ctm);

    // ONE clip for the whole fill region.
    // per contour made a path with disjoint subpaths paint nothing at all, and
    // more than 63 contours tripped the renderer's clip-depth guard and discarded
    // the rest of the page. path_ops carries every contour so holes survive.
    let mut path_ops: Vec<PathOp> = Vec::new();
    let mut first: Option<&Vec<(f64, f64)>> = None;
    for poly in polys {
        if poly.len() < 3 {
            continue;
        }
        if first.is_none() {
            first = Some(poly);
        }
        path_ops.push(PathOp::Move(poly[0].0 as f32, poly[0].1 as f32));
        for &(x, y) in &poly[1..] {
            path_ops.push(PathOp::Line(x as f32, y as f32));
        }
        path_ops.push(PathOp::Close);
    }
    if path_ops.is_empty() || prims.len() >= MAX_PRIMITIVES {
        return;
    }
    let pts: Vec<(f32, f32)> = first
        .map(|p| p.iter().map(|&(x, y)| (x as f32, y as f32)).collect())
        .unwrap_or_default();
    prims.push(Prim::ClipPush { even_odd, pts, path_ops: Some(path_ops) });

    if ptype == 2 {
        if let Some(shobj) = dict.get(b"Shading").ok().and_then(|o| deref(doc, o)) {
            let fill_bbox = polys_device_bbox(polys);
            // §8.7.4.3 Table 78: see `paint_pattern_stroke` — PatternType 2 honours
            // `/Background`, the `sh` operator ignores it.
            if let Some((ctm, w, h, data)) = rasterize_shading_as_pattern(doc, shobj, &pmat, cs_resources, 0, fill_bbox) {
                if prims.len() < MAX_PRIMITIVES {
                    prims.push(Prim::Image { ctm, w, h, format: 0, data, alpha: alpha_fill, blend });
                }
            }
        }
    } else if ptype == 1 {
        paint_tiling_pattern(doc, obj, dict, &pmat, base_argb, polys, prims, depth, alpha_fill, blend);
    }

    prims.push(Prim::ClipPop);
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
    alpha: f32,
    blend: BlendMode,
) {
    let stream = match obj {
        Object::Stream(s) => s,
        _ => return,
    };
    let paint_type = dict.get(b"PaintType").ok().and_then(num).unwrap_or(1.0) as i64;
    let bbox = dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)).unwrap_or([0.0, 0.0, 1.0, 1.0]);
    let xstep = dict.get(b"XStep").ok().and_then(num).unwrap_or(bbox[2] - bbox[0]);
    let ystep = dict.get(b"YStep").ok().and_then(num).unwrap_or(bbox[3] - bbox[1]);
    // The tile lattice spacing is a magnitude; a negative /XStep or /YStep made
    // i0 > i1 so the loop body never ran and the pattern painted nothing.
    let xstep = xstep.abs();
    let ystep = ystep.abs();
    // §8.7.4.1: `sh` fills the whole current clip, and `rasterize_shading` paints NOTHING
    // for a shading with no `/BBox` of its own when handed no clip extent. §8.7.3.1 clips a
    // cell to the pattern `/BBox`, so that box is the extent for every path below that
    // interprets the cell. Two of them are the malformed-pattern fallbacks, which paint the
    // cell once at `pmat`; the third (the periodic-raster path) works in cell space and
    // needs the box unmapped, computed separately at its own site.
    let cell_bbox_device = quad_device_bbox(&[
        transform(pmat, bbox[0], bbox[1]),
        transform(pmat, bbox[2], bbox[1]),
        transform(pmat, bbox[2], bbox[3]),
        transform(pmat, bbox[0], bbox[3]),
    ]);
    // Zero-step pattern is malformed — show bbox once instead of blanking
    if xstep.abs() < 1e-6 || ystep.abs() < 1e-6 {
        let res = dict
            .get(b"Resources")
            .ok()
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .cloned();
        // Same all-or-nothing hazard as a page or a form: one inline image lopdf
        // rejects would otherwise blank the whole cell.
        let cell_ops = crate::content::stream_operations(doc, stream);
        if !cell_ops.is_empty() {
            let mut tile_gs = GraphicsState { ctm: *pmat, alpha_fill: alpha as f64, alpha_stroke: alpha as f64, blend_mode: blend, ..GraphicsState::default() };
            if paint_type == 2 { tile_gs.fill = base_argb; tile_gs.stroke = base_argb; }
            interpret_content_seeded(doc, &cell_ops, res.as_ref(), tile_gs, prims, depth + 1, false, cell_bbox_device);
        }
        return;
    }
    let res = dict
        .get(b"Resources")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .cloned();
    let content_ops = crate::content::stream_operations(doc, stream);
    if content_ops.is_empty() {
        return;
    }

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
        let mut tile_gs = GraphicsState { ctm: *pmat, alpha_fill: alpha as f64, alpha_stroke: alpha as f64, blend_mode: blend, ..GraphicsState::default() };
        if paint_type == 2 { tile_gs.fill = base_argb; tile_gs.stroke = base_argb; }
        interpret_content_seeded(doc, &content_ops, res.as_ref(), tile_gs, prims, depth + 1, false, cell_bbox_device);
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
    let total_i = (i1 - i0 + 1).max(0);
    let total_j = (j1 - j0 + 1).max(0);

    // §8.7.3.3 requires the cell replicated across the WHOLE region. Rasterizing
    // the cell ONCE and emitting a periodic bitmap makes the tile count
    // irrelevant, which is the only way to satisfy that: the per-tile path below
    // has to cap the count, and any cap leaves part of a large hatched region
    // blank.
    //
    // Restricted to cells made only of fills and strokes, because
    // `rasterize_prims_to_rgba` has no glyph rasterizer and ignores images, clips
    // and groups — a cell containing any of those would silently lose it, so it
    // keeps the per-tile path. `rasterize_pattern_cell` owns the other gates (the
    // period being the step and not the bbox, and bounding the copies-per-period
    // needed to honour §8.7.3.1 overlap).
    let mut cell_prims: Vec<Prim> = Vec::new();
    let mut cell_gs = GraphicsState {
        ctm: IDENTITY,
        // §11.6.7 treats the pattern as a transparency group: alpha and blend ride
        // on the composited result, not on each element inside the cell.
        alpha_fill: 1.0,
        alpha_stroke: 1.0,
        ..GraphicsState::default()
    };
    if paint_type == 2 { cell_gs.fill = base_argb; cell_gs.stroke = base_argb; }
    // This cell is interpreted at IDENTITY, so its "device" space IS pattern space and the
    // extent is the `/BBox` unmapped. Seeding it is not cosmetic: it decides the GATE below.
    // Unseeded, a cell whose only content is `sh` produced no Image prim, `cell_prims` came
    // out all-Fill/Stroke, the periodic-raster path was taken, and the gradient was silently
    // dropped from every tile. Seeded, the Image appears, the gate correctly rejects it, and
    // the per-tile path (also seeded) paints it.
    let cell_space_bbox = quad_device_bbox(&[
        (bbox[0], bbox[1]),
        (bbox[2], bbox[1]),
        (bbox[2], bbox[3]),
        (bbox[0], bbox[3]),
    ]);
    interpret_content_seeded(doc, &content_ops, res.as_ref(), cell_gs, &mut cell_prims, depth + 1, false, cell_space_bbox);
    if !cell_prims.is_empty()
        && cell_prims.iter().all(|p| matches!(p, Prim::Fill { .. } | Prim::Stroke { .. }))
        && prims.len() < MAX_PRIMITIVES
    {
        // Pattern-space -> device scale, so the cell is rasterized at display
        // resolution instead of an arbitrary fixed size.
        let sx = (pmat[0] * pmat[0] + pmat[1] * pmat[1]).sqrt();
        let sy = (pmat[2] * pmat[2] + pmat[3] * pmat[3]).sqrt();
        if let Some((cw, ch, data)) =
            rasterize_pattern_cell(&cell_prims, bbox, xstep, ystep, sx.max(sy))
        {
            // The unit square maps onto ONE CELL — one period of the lattice —
            // anchored at the bbox origin, matching `rasterize_pattern_cell`'s
            // step rect.
            let cell_mat: Mat = [xstep, 0.0, 0.0, ystep, bbox[0], bbox[1]];
            // For a PERIODIC bitmap the extent is counted in whole periods
            // relative to the region: cell `i` spans pattern x in
            // [bbox[0] + i*xstep, bbox[0] + (i+1)*xstep). That differs from the
            // per-tile loop's `i0`/`i1`, which are in bbox-overlap terms and
            // deliberately start a cell early — here that would report an extent
            // a period wider than the region on every side.
            let ti0 = ((pminx - bbox[0]) / xstep).floor();
            let tj0 = ((pminy - bbox[1]) / ystep).floor();
            let tnx = (((pmaxx - bbox[0]) / xstep).ceil() - ti0).max(1.0);
            let tny = (((pmaxy - bbox[1]) / ystep).ceil() - tj0).max(1.0);
            prims.push(Prim::ImageTiled {
                ctm: mat_mul(&cell_mat, pmat),
                w: cw,
                h: ch,
                data,
                xstep: xstep as f32,
                ystep: ystep as f32,
                i0: ti0.clamp(i32::MIN as f64, i32::MAX as f64) as i32,
                j0: tj0.clamp(i32::MIN as f64, i32::MAX as f64) as i32,
                nx: tnx.clamp(1.0, u32::MAX as f64) as u32,
                ny: tny.clamp(1.0, u32::MAX as f64) as u32,
                alpha,
                blend,
            });
            return;
        }
    }

    // Per-tile fallback. Reached when the cell contains text, an image or its own
    // clipping (see the gate above), and when `rasterize_pattern_cell` declines:
    // a degenerate step, or a `/BBox` so much larger than the step that honouring
    // §8.7.3.1 overlap would need more than its copies-per-period budget. Plain
    // overlap is NOT a fallback case — that path composites the cell at each
    // reaching lattice offset and stays periodic.
    //
    // Each cell is REPLAYED as primitives here, so the count has to be capped.
    const MAX_TILES: i64 = 20_000;
    // §8.7.3.3 requires the cell replicated across the WHOLE region, so when the
    // lattice exceeds the budget, thin it out UNIFORMLY. Taking a dense square
    // patch anchored at one corner instead — which is what this used to do — left
    // the rest of the region empty, and a 2pt lattice over a 400x400 region is
    // 40,401 tiles, so it covered barely half. A lower-density cell over the whole
    // region still reads as the texture that was asked for; a correct patch beside
    // a blank area reads as missing content.
    //
    // f64 for the product: `i1`/`j1` come from a division by a step that may be
    // tiny, so `total_i * total_j` can overflow `i64` and panic in a debug build.
    let need = total_i as f64 * total_j as f64;
    let stride = if need > MAX_TILES as f64 {
        ((need / MAX_TILES as f64).sqrt().ceil() as i64).max(1)
    } else {
        1
    };
    let mut count = 0i64;
    'outer: for j in (j0..=j1).step_by(stride as usize) {
        for i in (i0..=i1).step_by(stride as usize) {
            if count >= MAX_TILES || prims.len() >= MAX_PRIMITIVES {
                break 'outer;
            }
            count += 1;
            let translate: Mat = [1.0, 0.0, 0.0, 1.0, i as f64 * xstep, j as f64 * ystep];
            let tile_ctm = mat_mul(&translate, pmat);
            // Clip each cell to the pattern /BBox (PDF 8.7.3.1) so content that
            // overflows the cell — or a cell smaller than XStep/YStep — cannot
            // bleed into neighboring cells.
            let bc = [
                transform(&tile_ctm, bbox[0], bbox[1]),
                transform(&tile_ctm, bbox[2], bbox[1]),
                transform(&tile_ctm, bbox[2], bbox[3]),
                transform(&tile_ctm, bbox[0], bbox[3]),
            ];
            let cell_pts: Vec<(f32, f32)> = bc.iter().map(|&(x, y)| (x as f32, y as f32)).collect();
            let cell_po = vec![
                PathOp::Move(bc[0].0 as f32, bc[0].1 as f32),
                PathOp::Line(bc[1].0 as f32, bc[1].1 as f32),
                PathOp::Line(bc[2].0 as f32, bc[2].1 as f32),
                PathOp::Line(bc[3].0 as f32, bc[3].1 as f32),
                PathOp::Close,
            ];
            prims.push(Prim::ClipPush { even_odd: false, pts: cell_pts, path_ops: Some(cell_po) });
            let mut tile_gs = GraphicsState { ctm: tile_ctm, alpha_fill: alpha as f64, alpha_stroke: alpha as f64, blend_mode: blend, ..GraphicsState::default() };
            if paint_type == 2 {
                tile_gs.fill = base_argb;
                tile_gs.stroke = base_argb;
            }
            // §8.7.3.1 clips the cell to the pattern `/BBox`, which is therefore the
            // clip extent a `sh` inside the cell must fill (§8.7.4.1). Without it
            // `rasterize_shading` declines and a gradient-filled hatch cell paints
            // nothing — the `bc` corners just used for the ClipPush are that extent.
            let cell_clip = quad_device_bbox(&bc);
            interpret_content_seeded(doc, &content_ops, res.as_ref(), tile_gs, prims, depth + 1, false, cell_clip);
            prims.push(Prim::ClipPop);
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
    // §7.9.5 defines a rectangle as four NUMBERS; NaN and the infinities are not
    // numbers a rectangle can be made of. Rejecting the whole rect rather than
    // patching a component matches the `cm` arm's treatment of a non-finite CTM,
    // and for the same reason: one poisoned coordinate propagates through every
    // transform derived from it, and the rasterizer drops a path containing a
    // NaN point silently, so a region of the page vanishes with no error
    // anywhere. Every caller already handles `None` — as an absent /BBox, an
    // unclipped form, or a skipped annotation — which are all visibly wrong in
    // the way a malformed file should be, instead of invisibly wrong.
    if out.iter().all(|v| v.is_finite()) {
        Some(out)
    } else {
        None
    }
}

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

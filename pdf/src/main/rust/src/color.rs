use crate::*;

pub(crate) fn rgb_to_argb(r: f64, g: f64, b: f64) -> u32 {
    let c = |v: f64| (v.clamp(0.0, 1.0) * 255.0).round() as u32;
    0xFF00_0000 | (c(r) << 16) | (c(g) << 8) | c(b)
}

pub(crate) fn gray_to_argb(v: f64) -> u32 {
    rgb_to_argb(v, v, v)
}

pub(crate) fn cmyk_to_argb(c: f64, m: f64, y: f64, k: f64) -> u32 {
    let r = (1.0 - c) * (1.0 - k);
    let g = (1.0 - m) * (1.0 - k);
    let b = (1.0 - y) * (1.0 - k);
    rgb_to_argb(r, g, b)
}

// ---------------------------------------------------------------------------
// Content-stream interpreter
// ---------------------------------------------------------------------------

#[derive(Clone)]
pub(crate) enum CsKind {
    DeviceGray,
    DeviceRGB,
    DeviceCMYK,
    Lab { white: [f64;3], range: [[f64;2];2], black: Option<[f64;3]> },
    Separation { name: Vec<u8>, alt: Box<CsKind>, tint_fn: Option<PdfFunction> },
    DeviceN { names: Vec<Vec<u8>>, alt: Box<CsKind>, tint_fn: Option<PdfFunction> },
    Pattern,
    Indexed { base: Box<CsKind>, lookup: Vec<u8>, base_ncomp: u8 },
    ICCBased { n: u8, alt: Option<Box<CsKind>> },
    CalRGB { white: [f64;3], gamma: [f64;3], matrix: [[f64;3];3] },
    CalGray { white: [f64;3], gamma: f64, black: Option<[f64;3]> },
}

impl Default for CsKind {
    fn default() -> Self { CsKind::DeviceGray }
}

pub(crate) fn colorspaces_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, ObjectId> {
    let mut out = HashMap::new();
    if let Some(Object::Dictionary(cs)) = res_dict.get(b"ColorSpace").ok().and_then(|o| deref(doc, o)) {
        for (name, v) in cs.iter() {
            if let Ok(id) = v.as_reference() {
                out.insert(name.clone(), id);
            }
        }
    }
    out
}

pub(crate) fn shadings_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, ObjectId> {
    let mut out = HashMap::new();
    if let Some(Object::Dictionary(sh)) = res_dict.get(b"Shading").ok().and_then(|o| deref(doc, o)) {
        for (name, v) in sh.iter() {
            if let Ok(id) = v.as_reference() {
                out.insert(name.clone(), id);
            }
        }
    }
    out
}

/// Map `/Pattern` resource names to their object ids (mirrors
/// [`shadings_from_resources`]). Both tiling (PatternType 1) and shading
/// (PatternType 2) patterns are indirect objects.
pub(crate) fn patterns_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, ObjectId> {
    let mut out = HashMap::new();
    if let Some(Object::Dictionary(pat)) = res_dict.get(b"Pattern").ok().and_then(|o| deref(doc, o)) {
        for (name, v) in pat.iter() {
            if let Ok(id) = v.as_reference() {
                out.insert(name.clone(), id);
            }
        }
    }
    out
}

// Parse a colorspace object (Name or Array) into CsKind, using resources map for named entries
pub(crate) fn parse_cs_kind(doc: &Document, cs_obj: Option<&Object>, cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<CsKind> {
    let obj = cs_obj?;
    // If Name, check if it's a resource reference
    if let Object::Name(name) = obj {
        // Check resources
        if let Some(&id) = cs_resources.get(name) {
            if let Ok(d) = doc.get_dictionary(id) {
                // Should be an array?
                // For simplicity, try to parse from dict that may contain colorspace array? Actually colorspace resource can be array directly stored as indirect object
                // So we need to get object id's object
                if let Ok(Object::Array(arr)) = doc.get_object(id) {
                    return parse_cs_array(doc, &arr, cs_resources);
                }
                // fallback: try array from dict? Not
            }
        }
        // Builtin names
        return match name.as_slice() {
            b"DeviceRGB" | b"RGB" => Some(CsKind::DeviceRGB),
            b"DeviceCMYK" | b"CMYK" => Some(CsKind::DeviceCMYK),
            b"DeviceGray" | b"Gray" | b"G" => Some(CsKind::DeviceGray),
            b"Pattern" => Some(CsKind::Pattern),
            _ => None,
        }
    }
    if let Object::Array(arr) = obj {
        return parse_cs_array(doc, arr, cs_resources);
    }
    // If Reference, deref
    if let Some(deref_obj) = deref(doc, obj) {
        return parse_cs_kind(doc, Some(deref_obj), cs_resources);
    }
    None
}

pub(crate) fn parse_cs_array(doc: &Document, arr: &[Object], cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<CsKind> {
    // arr head is name
    let head = arr.first().and_then(|o| o.as_name().ok()).unwrap_or(b"");
    match head {
        b"DeviceRGB" | b"RGB" => Some(CsKind::DeviceRGB),
        b"DeviceCMYK" | b"CMYK" => Some(CsKind::DeviceCMYK),
        b"DeviceGray" | b"G" | b"Gray" => Some(CsKind::DeviceGray),
        b"Pattern" => Some(CsKind::Pattern),
        b"CalRGB" => {
            // [ /CalRGB dict ]
            let dict = arr.get(1).and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok());
            if let Some(d) = dict {
                let white = read_white_point(d).unwrap_or([0.9505,1.0,1.0890]);
                let gamma = read_gamma_rgb(d).unwrap_or([1.0,1.0,1.0]);
                let matrix = read_matrix_cal(d).unwrap_or([[1.0,0.0,0.0],[0.0,1.0,0.0],[0.0,0.0,1.0]]);
                Some(CsKind::CalRGB { white, gamma, matrix })
            } else {
                Some(CsKind::DeviceRGB)
            }
        }
        b"CalGray" => {
            let dict = arr.get(1).and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok());
            if let Some(d) = dict {
                let white = read_white_point(d).unwrap_or([0.9505,1.0,1.0890]);
                let gamma = d.get(b"Gamma").ok().and_then(num).unwrap_or(1.0);
                Some(CsKind::CalGray { white, gamma, black: None })
            } else {
                Some(CsKind::DeviceGray)
            }
        }
        b"Lab" => {
            // [ /Lab dict ]
            let dict = arr.get(1).and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok());
            if let Some(d) = dict {
                let white = read_white_point(d).unwrap_or([0.9505,1.0,1.0890]);
                let range = read_lab_range(d).unwrap_or([[ -100.0, 100.0],[ -100.0, 100.0]]);
                Some(CsKind::Lab { white, range, black: None })
            } else {
                Some(CsKind::Lab { white: [0.9505,1.0,1.0890], range: [[ -100.0,100.0],[ -100.0,100.0]], black: None })
            }
        }
        b"ICCBased" => {
            let dict_obj = arr.get(1).and_then(|o| deref(doc, o));
            let n = if let Some(Object::Stream(s)) = dict_obj {
                s.dict.get(b"N").ok().and_then(num).unwrap_or(1.0) as u8
            } else {
                1
            };
            // alt colorspace in dict /Alternate
            let alt = if let Some(Object::Stream(s)) = dict_obj {
                s.dict.get(b"Alternate").ok().and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)).map(Box::new)
            } else { None };
            Some(CsKind::ICCBased { n: n.max(1), alt })
        }
        b"Indexed" | b"I" => {
            // [ /Indexed base hival lookup ]
            let base = arr.get(1).and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)).unwrap_or(CsKind::DeviceRGB);
            let base_n = cs_kind_ncomp(&base);
            let lookup = match arr.get(3).and_then(|o| deref(doc, o)) {
                Some(Object::String(s,_)) => s.clone(),
                Some(Object::Stream(s)) => { s.decompressed_content().unwrap_or_else(|_| s.content.clone()) },
                _ => Vec::new(),
            };
            Some(CsKind::Indexed { base: Box::new(base), lookup, base_ncomp: base_n })
        }
        b"Separation" => {
            // [ /Separation name alt tintTransform ]
            let name = arr.get(1).and_then(|o| o.as_name().ok()).unwrap_or(b"").to_vec();
            let alt = arr.get(2).and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)).unwrap_or(CsKind::DeviceGray);
            let tint_fn = arr.get(3).and_then(|o| PdfFunction::parse(doc, o));
            Some(CsKind::Separation { name, alt: Box::new(alt), tint_fn })
        }
        b"DeviceN" => {
            let names = arr.get(1).and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok()).map(|a| a.iter().filter_map(|obj| obj.as_name().ok().map(|n| n.to_vec())).collect()).unwrap_or_default();
            let alt = arr.get(2).and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)).unwrap_or(CsKind::DeviceGray);
            let tint_fn = arr.get(3).and_then(|o| PdfFunction::parse(doc, o));
            Some(CsKind::DeviceN { names, alt: Box::new(alt), tint_fn })
        }
        _ => None,
    }
}

pub(crate) fn cs_kind_ncomp(kind: &CsKind) -> u8 {
    match kind {
        CsKind::DeviceGray => 1,
        CsKind::DeviceRGB => 3,
        CsKind::DeviceCMYK => 4,
        CsKind::Lab { .. } => 3,
        CsKind::CalRGB { .. } => 3,
        CsKind::CalGray { .. } => 1,
        CsKind::ICCBased { n, .. } => *n,
        CsKind::Indexed { base_ncomp, .. } => *base_ncomp,
        CsKind::Separation { .. } => 1,
        CsKind::DeviceN { names, .. } => names.len() as u8,
        CsKind::Pattern => 0,
    }
}

pub(crate) fn read_white_point(dict: &lopdf::Dictionary) -> Option<[f64;3]> {
    let arr = dict.get(b"WhitePoint").ok().and_then(|o| o.as_array().ok())?;
    if arr.len()>=3 {
        Some([num(&arr[0])?, num(&arr[1])?, num(&arr[2])?])
    } else { None }
}

pub(crate) fn read_gamma_rgb(dict: &lopdf::Dictionary) -> Option<[f64;3]> {
    let arr = dict.get(b"Gamma").ok().and_then(|o| o.as_array().ok())?;
    if arr.len()>=3 {
        Some([num(&arr[0])?, num(&arr[1])?, num(&arr[2])?])
    } else { None }
}

pub(crate) fn read_matrix_cal(dict: &lopdf::Dictionary) -> Option<[[f64;3];3]> {
    let arr = dict.get(b"Matrix").ok().and_then(|o| o.as_array().ok())?;
    if arr.len()>=9 {
        Some([
            [num(&arr[0])?, num(&arr[1])?, num(&arr[2])?],
            [num(&arr[3])?, num(&arr[4])?, num(&arr[5])?],
            [num(&arr[6])?, num(&arr[7])?, num(&arr[8])?],
        ])
    } else { None }
}

pub(crate) fn read_lab_range(dict: &lopdf::Dictionary) -> Option<[[f64;2];2]> {
    let arr = dict.get(b"Range").ok().and_then(|o| o.as_array().ok())?;
    if arr.len()>=4 {
        Some([[num(&arr[0])?, num(&arr[1])?],[num(&arr[2])?, num(&arr[3])?]])
    } else { None }
}

pub(crate) fn eval_cs_to_rgb(doc: &Document, kind: &CsKind, comps: &[f64], cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<u32> {
    match kind {
        CsKind::DeviceGray => {
            let v = comps.get(0).copied().unwrap_or(0.0);
            Some(gray_to_argb(v))
        }
        CsKind::DeviceRGB => {
            if comps.len()>=3 {
                Some(rgb_to_argb(comps[0], comps[1], comps[2]))
            } else { None }
        }
        CsKind::DeviceCMYK => {
            if comps.len()>=4 {
                Some(cmyk_to_argb(comps[0], comps[1], comps[2], comps[3]))
            } else { None }
        }
        CsKind::Lab { white, range, .. } => {
            // PDF spec 8.6.5.4 Lab to XYZ to RGB
            // comps: L 0..100, a,b via Range
            let l = comps.get(0).copied().unwrap_or(0.0).clamp(0.0,100.0);
            let a = comps.get(1).copied().unwrap_or(0.0);
            let b = comps.get(2).copied().unwrap_or(0.0);
            // Lab to XYZ
            let fy = (l + 16.0)/116.0;
            let fx = a / 500.0 + fy;
            let fz = fy - b / 200.0;
            let eps = 0.008856;
            let kappa = 903.3;
            let f_inv = |t: f64| -> f64 { if t.powi(3) > eps { t.powi(3) } else { (t - 16.0/116.0)/7.787 } };
            // Actually inverse: f^3 or linear
            let fx3 = fx.powi(3);
            let fz3 = fz.powi(3);
            let fy3 = fy.powi(3);
            let xr = if fx3 > eps { fx3 } else { (fx - 16.0/116.0)/7.787 };
            let yr = if l > kappa*eps { fy3 } else { l/kappa };
            let zr = if fz3 > eps { fz3 } else { (fz - 16.0/116.0)/7.787 };
            // white point
            let wx = white[0];
            let wy = white[1];
            let wz = white[2];
            // XYZ
            let x = xr * wx;
            let y = yr * wy;
            let z = zr * wz;
            // D50 to D65? PDF uses D50 but for simplicity use D65 sRGB matrix with D50 adaptation approximated by Bradford? Use simple sRGB matrix from XYZ
            // Simplified matrix: XYZ (D65) -> sRGB linear (IEC)
            // Use standard matrix: [[3.2406, -1.5372, -0.4986], [-0.9689,1.8758,0.0415],[0.0557,-0.2040,1.0570]]
            let r_lin =  3.2406 * x -1.5372 * y -0.4986 * z;
            let g_lin = -0.9689 * x +1.8758 * y +0.0415 * z;
            let b_lin =  0.0557 * x -0.2040 * y +1.0570 * z;
            let gamma = |u: f64| -> f64 {
                let u = u.clamp(0.0,1.0);
                if u <= 0.0031308 { 12.92*u } else { 1.055 * u.powf(1.0/2.4) -0.055 }
            };
            Some(rgb_to_argb(gamma(r_lin), gamma(g_lin), gamma(b_lin)))
        }
        CsKind::CalRGB { white, gamma, matrix } => {
            // Apply gamma then matrix then white scaling? Simplified
            // comps are A,B,C in 0..1
            let a = comps.get(0).copied().unwrap_or(0.0).powf(gamma[0]);
            let b = comps.get(1).copied().unwrap_or(0.0).powf(gamma[1]);
            let c = comps.get(2).copied().unwrap_or(0.0).powf(gamma[2]);
            let x = matrix[0][0]*a + matrix[0][1]*b + matrix[0][2]*c;
            let y = matrix[1][0]*a + matrix[1][1]*b + matrix[1][2]*c;
            let z = matrix[2][0]*a + matrix[2][1]*b + matrix[2][2]*c;
            // XYZ to sRGB as above
            let r_lin =  3.2406 * x -1.5372 * y -0.4986 * z;
            let g_lin = -0.9689 * x +1.8758 * y +0.0415 * z;
            let b_lin =  0.0557 * x -0.2040 * y +1.0570 * z;
            let gamma_corr = |u: f64| -> f64 {
                let u = u.clamp(0.0,1.0);
                if u <= 0.0031308 { 12.92*u } else { 1.055 * u.powf(1.0/2.4) -0.055 }
            };
            Some(rgb_to_argb(gamma_corr(r_lin), gamma_corr(g_lin), gamma_corr(b_lin)))
        }
        CsKind::CalGray { gamma, white, .. } => {
            let a = comps.get(0).copied().unwrap_or(0.0).powf(*gamma);
            // XYZ: white * a
            let x = white[0]*a;
            let y = white[1]*a;
            let z = white[2]*a;
            let r_lin =  3.2406 * x -1.5372 * y -0.4986 * z;
            let g_lin = -0.9689 * x +1.8758 * y +0.0415 * z;
            let b_lin =  0.0557 * x -0.2040 * y +1.0570 * z;
            let gamma_corr = |u: f64| {
                let u = u.clamp(0.0,1.0);
                if u <= 0.0031308 { 12.92*u } else { 1.055 * u.powf(1.0/2.4) -0.055 }
            };
            Some(rgb_to_argb(gamma_corr(r_lin), gamma_corr(g_lin), gamma_corr(b_lin)))
        }
        CsKind::ICCBased { n, alt } => {
            // Use alt if present and we can evaluate, else fallback based on n
            if let Some(alt_kind) = alt {
                // If alt is RGB/Gray/CMYK, evaluate with comps as alt
                // But ICC n may differ from alt comps? For simplicity, if alt n matches, evaluate
                if let Some(rgb) = eval_cs_to_rgb(doc, alt_kind, comps, cs_resources) {
                    return Some(rgb);
                }
            }
            // Fallback: based on component count
            match n {
                1 => {
                    let v = comps.get(0).copied().unwrap_or(0.0);
                    Some(gray_to_argb(v))
                }
                3 => {
                    if comps.len()>=3 { Some(rgb_to_argb(comps[0], comps[1], comps[2])) } else { None }
                }
                4 => {
                    if comps.len()>=4 { Some(cmyk_to_argb(comps[0], comps[1], comps[2], comps[3])) } else { None }
                }
                _ => None,
            }
        }
        CsKind::Indexed { base, lookup, base_ncomp } => {
            let idx = (comps.get(0).copied().unwrap_or(0.0) as usize).clamp(0, 255);
            let off = idx * *base_ncomp as usize;
            if off + *base_ncomp as usize <= lookup.len() {
                let slice = &lookup[off..off+*base_ncomp as usize];
                // Convert lookup bytes 0..255 to 0..1 floats
                let comps_f: Vec<f64> = slice.iter().map(|b| *b as f64 /255.0).collect();
                eval_cs_to_rgb(doc, base, &comps_f, cs_resources)
            } else {
                None
            }
        }
        CsKind::Separation { alt, tint_fn, .. } => {
            let t = comps.get(0).copied().unwrap_or(0.0);
            if let Some(tf) = tint_fn {
                let alt_comps = tf.eval(&[t]);
                eval_cs_to_rgb(doc, alt, &alt_comps, cs_resources)
            } else {
                // No tint transform: approximate as subtractive ink (0 tint = white).
                Some(gray_to_argb(1.0 - t))
            }
        }
        CsKind::DeviceN { alt, tint_fn, .. } => {
            if let Some(tf) = tint_fn {
                // Evaluate the tint transform over all N input components.
                let alt_comps = tf.eval(comps);
                eval_cs_to_rgb(doc, alt, &alt_comps, cs_resources)
            } else {
                Some(gray_to_argb(1.0 - comps.get(0).copied().unwrap_or(0.0)))
            }
        }
        CsKind::Pattern => {
            // Pattern color handling: SCN may include base color, we already evaluated base if comps present
            // For pattern-only, we have no color - return None to keep current
            None
        }
    }
}




/// Resolve the page's (inherited) `/Resources` as an owned dictionary.
pub(crate) fn resources_dict(doc: &Document, page_id: ObjectId) -> Option<lopdf::Dictionary> {
    inherited(doc, page_id, b"Resources")
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .cloned()
}

/// Collect the `/Filter` names of a stream (single name or array).
pub(crate) fn filter_names(doc: &Document, dict: &lopdf::Dictionary) -> Vec<String> {
    match dict.get(b"Filter").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Name(n)) => vec![String::from_utf8_lossy(n).into_owned()],
        Some(Object::Array(a)) => a
            .iter()
            .filter_map(|o| o.as_name().ok())
            .map(|n| String::from_utf8_lossy(n).into_owned())
            .collect(),
        _ => Vec::new(),
    }
}

/// Number of color components for a colorspace object, plus an optional Indexed
/// palette `(base_components, lookup_bytes)`.
/// Number of color components for a colorspace object, plus an optional Indexed
/// palette `(base_components, lookup_bytes)`. Now also handles Lab, Pattern etc returning fallback.
pub(crate) fn colorspace_info(
    doc: &Document,
    cs: Option<&Object>,
) -> (u8, Option<(u8, Vec<u8>)>) {
    let cs = match cs.and_then(|o| deref(doc, o)) {
        Some(o) => o,
        None => return (1, None),
    };
    match cs {
        Object::Name(n) => match n.as_slice() {
            b"DeviceRGB" | b"RGB" => (3, None),
            b"DeviceCMYK" | b"CMYK" => (4, None),
            b"CalRGB" => (3, None),
            b"Lab" => (3, None),
            _ => (1, None), // DeviceGray / CalGray / fallback
        },
        Object::Array(a) => {
            let head = a.first().and_then(|o| o.as_name().ok()).unwrap_or(b"");
            match head {
                b"ICCBased" => {
                    let n = a
                        .get(1)
                        .and_then(|o| deref(doc, o))
                        .and_then(|o| match o {
                            Object::Stream(s) => s.dict.get(b"N").ok().and_then(num),
                            Object::Dictionary(d) => d.get(b"N").ok().and_then(num),
                            _ => None,
                        })
                        .unwrap_or(1.0) as u8;
                    (n.max(1), None)
                }
                b"Indexed" | b"I" => {
                    let (base_n, _) = colorspace_info(doc, a.get(1));
                    let lookup = match a.get(3).and_then(|o| deref(doc, o)) {
                        Some(Object::String(s, _)) => s.clone(),
                        Some(Object::Stream(s)) => {
                            s.decompressed_content().unwrap_or_else(|_| s.content.clone())
                        }
                        _ => Vec::new(),
                    };
                    (1, Some((base_n, lookup)))
                }
                b"CalRGB" => (3, None),
                b"CalGray" => (1, None),
                b"Lab" => (3, None),
                b"DeviceN" => {
                    let n = a
                        .get(1)
                        .and_then(|o| deref(doc, o))
                        .and_then(|o| o.as_array().ok())
                        .map(|arr| arr.len() as u8)
                        .unwrap_or(1);
                    (n.max(1), None)
                }
                b"Separation" => (1, None),
                _ => (1, None),
            }
        }
        _ => (1, None),
    }
}


pub(crate) fn comps_to_rgb(comps: &[u8], n: u8) -> (u8, u8, u8) {
    match n {
        3 => (comps[0], comps[1], comps[2]),
        4 => {
            let c = comps[0] as f64 / 255.0;
            let m = comps[1] as f64 / 255.0;
            let y = comps[2] as f64 / 255.0;
            let k = comps[3] as f64 / 255.0;
            let r = (1.0 - c) * (1.0 - k);
            let g = (1.0 - m) * (1.0 - k);
            let b = (1.0 - y) * (1.0 - k);
            ((r * 255.0).round().clamp(0.0,255.0) as u8, (g * 255.0).round().clamp(0.0,255.0) as u8, (b * 255.0).round().clamp(0.0,255.0) as u8)
        }
        _ => {
            // includes 1 and also maybe DeviceN fallback
            if comps.is_empty() { (0,0,0) } else { (comps[0], comps[0], comps[0]) }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn separation_uses_tint_transform() {
        // A Separation colorspace over DeviceRGB whose tint transform is a
        // Type 2 exponential mapping t -> (t, 0, 0): full tint => pure red.
        let doc = Document::with_version("1.7");
        let cs = CsKind::Separation {
            name: b"Spot".to_vec(),
            alt: Box::new(CsKind::DeviceRGB),
            tint_fn: Some(PdfFunction::Exponential {
                domain: [0.0, 1.0],
                c0: vec![0.0, 0.0, 0.0],
                c1: vec![1.0, 0.0, 0.0],
                n: 1.0,
            }),
        };
        let res = HashMap::new();
        let argb = eval_cs_to_rgb(&doc, &cs, &[1.0], &res).unwrap();
        assert_eq!(argb & 0x00FF_FFFF, 0x00FF_0000, "full tint should be red");
        let half = eval_cs_to_rgb(&doc, &cs, &[0.5], &res).unwrap();
        let r = (half >> 16) & 0xFF;
        assert!(r > 100 && r < 160, "half tint red channel ~128, got {r}");
    }
}

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

#[derive(Clone, Default)]
pub(crate) enum CsKind {
    #[default]
    DeviceGray,
    DeviceRGB,
    DeviceCMYK,
    Lab { white: [f64;3], range: [[f64;2];2] },
    Separation { name: Vec<u8>, alt: Box<CsKind>, tint_fn: Option<PdfFunction> },
    DeviceN { names: Vec<Vec<u8>>, alt: Box<CsKind>, tint_fn: Option<PdfFunction> },
    Pattern { base: Option<Box<CsKind>> },
    Indexed { base: Box<CsKind>, lookup: Vec<u8>, base_ncomp: u8, hival: u16 },
    ICCBased { n: u8, alt: Option<Box<CsKind>> },
    CalRGB { white: [f64;3], gamma: [f64;3], matrix: [[f64;3];3] },
    /// CIE-based grey (§8.6.5.2). `/BlackPoint` is parsed by nobody and ignored: the
    /// conversion below adapts from `/WhitePoint` only, so a non-default black point
    /// shifts the darkest tones slightly. Recorded here rather than kept as an
    /// always-`None` field that reads as if the support were half-written.
    CalGray { white: [f64;3], gamma: f64 },
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

/// The `&Object` a resource NAME denotes, whether the entry is an indirect
/// reference or written directly in the resource dictionary.
///
/// §7.3.8.1 requires only STREAMS to be indirect. §8.7.4.2 makes a `/Shading`
/// resource value "a dictionary or a stream", and ShadingTypes 1-3 are
/// dictionaries, so they are legally DIRECT; the same holds for a PatternType 2
/// dictionary (§8.7.3.3 Table 76). [`shadings_from_resources`] and
/// [`patterns_from_resources`] collect only `as_reference()` entries, so a
/// direct one misses the map entirely and `/Sh0 sh` paints nothing at all.
///
/// Mirrors the shape [`parse_named_cs`] already uses for `/ColorSpace`.
pub(crate) fn resolve_named_resource<'a>(
    doc: &'a Document,
    resources: Option<&'a lopdf::Dictionary>,
    key: &[u8],
    name: &[u8],
) -> Option<&'a Object> {
    let sub = resources?.get(key).ok().and_then(|o| deref(doc, o))?;
    let entry = sub.as_dict().ok()?.get(name).ok()?;
    deref(doc, entry)
}

// Parse a colorspace object (Name or Array) into CsKind, using resources map for named entries
/// Depth ceiling for colour-space nesting. §8.6 nests only a handful of levels deep in
/// practice (Indexed over ICCBased over an /Alternate, say), so this only ever stops a
/// cycle. `[/Indexed 5 0 R 255 <00FF00>]` stored AS object 5 makes the base resolve back
/// to the same array, and the plain per-name self-reference guard cannot see it because
/// the cycle runs through the array element rather than a name. That recursed until the
/// stack overflowed, which unlike a panic cannot be caught and takes the process with it.
const MAX_CS_DEPTH: u32 = 16;

pub(crate) fn parse_cs_kind(doc: &Document, cs_obj: Option<&Object>, cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<CsKind> {
    parse_cs_kind_at(doc, cs_obj, cs_resources, 0)
}

fn parse_cs_kind_at(doc: &Document, cs_obj: Option<&Object>, cs_resources: &HashMap<Vec<u8>, ObjectId>, depth: u32) -> Option<CsKind> {
    if depth >= MAX_CS_DEPTH {
        return None;
    }
    let obj = cs_obj?;
    // If Name, check if it's a resource reference
    if let Object::Name(name) = obj {
        // A named colorspace from the resource dictionary. The resolved object is
        // normally an Array (e.g. [/ICCBased ...], [/Separation ...]); it may also
        // be an indirect reference to one. (The previous `get_dictionary` check
        // rejected arrays outright, silently dropping the colorspace to gray.)
        if let Some(&id) = cs_resources.get(name) {
            if let Ok(resolved) = doc.get_object(id) {
                match resolved {
                    Object::Array(arr) => return parse_cs_array_at(doc, arr, cs_resources, depth + 1),
                    // Guard against a name that resolves to itself.
                    Object::Name(n2) if n2 != name => {
                        return parse_cs_kind_at(doc, Some(resolved), cs_resources, depth + 1);
                    }
                    _ => {}
                }
            }
        }
        // Builtin names
        return match name.as_slice() {
            b"DeviceRGB" | b"RGB" => Some(CsKind::DeviceRGB),
            b"DeviceCMYK" | b"CMYK" => Some(CsKind::DeviceCMYK),
            b"DeviceGray" | b"Gray" | b"G" => Some(CsKind::DeviceGray),
            b"Pattern" => Some(CsKind::Pattern { base: None }),
            _ => None,
        }
    }
    if let Object::Array(arr) = obj {
        return parse_cs_array_at(doc, arr, cs_resources, depth + 1);
    }
    // If Reference, deref
    if let Some(deref_obj) = deref(doc, obj) {
        return parse_cs_kind_at(doc, Some(deref_obj), cs_resources, depth + 1);
    }
    None
}

/// Resolve a colorspace operand for the `cs`/`CS` operators. Named entries in
/// the page `/Resources /ColorSpace` dict are honored whether they are stored as
/// a direct array (e.g. `/Cs0 [/ICCBased 5 0 R]`) or an indirect reference — the
/// pre-built id map only captures the reference form, so a direct array would
/// otherwise fall through to the DeviceGray default and render colors as gray.
pub(crate) fn parse_named_cs(
    doc: &Document,
    cs_obj: &Object,
    resources: Option<&lopdf::Dictionary>,
    cs_resources: &HashMap<Vec<u8>, ObjectId>,
) -> Option<CsKind> {
    if let Object::Name(name) = cs_obj {
        // Device builtins take precedence and never live in the resource dict.
        match name.as_slice() {
            b"DeviceRGB" | b"RGB" => return Some(CsKind::DeviceRGB),
            b"DeviceCMYK" | b"CMYK" => return Some(CsKind::DeviceCMYK),
            b"DeviceGray" | b"Gray" | b"G" => return Some(CsKind::DeviceGray),
            b"Pattern" => return Some(CsKind::Pattern { base: None }),
            _ => {}
        }
        if let Some(res) = resources {
            if let Some(Object::Dictionary(csd)) = res.get(b"ColorSpace").ok().and_then(|o| deref(doc, o)) {
                if let Ok(entry) = csd.get(name) {
                    if let Some(k) = parse_cs_kind(doc, Some(entry), cs_resources) {
                        return Some(k);
                    }
                }
            }
        }
    }
    parse_cs_kind(doc, Some(cs_obj), cs_resources)
}

fn parse_cs_array_at(doc: &Document, arr: &[Object], cs_resources: &HashMap<Vec<u8>, ObjectId>, depth: u32) -> Option<CsKind> {
    if depth >= MAX_CS_DEPTH {
        return None;
    }
    // Every nested colour space below goes through `parse_cs_kind_at` so the depth keeps
    // accumulating across the array/kind boundary; a cycle alternates between the two.
    let parse_cs_kind = |doc: &Document, o: Option<&Object>, r: &HashMap<Vec<u8>, ObjectId>| {
        parse_cs_kind_at(doc, o, r, depth + 1)
    };
    // arr head is name
    let head = arr.first().and_then(|o| o.as_name().ok()).unwrap_or(b"");
    match head {
        b"DeviceRGB" | b"RGB" => Some(CsKind::DeviceRGB),
        b"DeviceCMYK" | b"CMYK" => Some(CsKind::DeviceCMYK),
        b"DeviceGray" | b"G" | b"Gray" => Some(CsKind::DeviceGray),
        b"Pattern" => Some(CsKind::Pattern {
            // [ /Pattern baseColorSpace ] for uncolored (PaintType 2) patterns.
            base: arr.get(1).and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)).map(Box::new),
        }),
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
                let gamma = d.get(b"Gamma").ok().and_then(num).filter(|g| g.is_finite()).unwrap_or(1.0);
                Some(CsKind::CalGray { white, gamma })
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
                Some(CsKind::Lab { white, range })
            } else {
                Some(CsKind::Lab { white: [0.9505,1.0,1.0890], range: [[ -100.0,100.0],[ -100.0,100.0]] })
            }
        }
        b"ICCBased" => {
            let dict_obj = arr.get(1).and_then(|o| deref(doc, o));
            // alt colorspace in dict /Alternate
            let alt = match dict_obj {
                Some(Object::Stream(s)) => s
                    .dict
                    .get(b"Alternate")
                    .ok()
                    .and_then(|o| parse_cs_kind(doc, Some(o), cs_resources))
                    .map(Box::new),
                Some(Object::Dictionary(d)) => d
                    .get(b"Alternate")
                    .ok()
                    .and_then(|o| parse_cs_kind(doc, Some(o), cs_resources))
                    .map(Box::new),
                _ => None,
            };
            // /N is required (§8.6.5.5) and must be 1, 3 or 4. When it is missing or
            // bogus, infer from /Alternate rather than defaulting to 1: a wrong
            // component count shifts every sample in an image and yields the
            // distinctive diagonal-rainbow garbage. Accept a bare dictionary too,
            // which `colorspace_info` already tolerated.
            let declared = match dict_obj {
                Some(Object::Stream(s)) => s.dict.get(b"N").ok().and_then(num),
                Some(Object::Dictionary(d)) => d.get(b"N").ok().and_then(num),
                _ => None,
            };
            let n = match declared {
                Some(v) if matches!(v as u8, 1 | 3 | 4) => v as u8,
                _ => alt.as_ref().map(|a| cs_kind_ncomp(a)).unwrap_or(3),
            };
            Some(CsKind::ICCBased { n: n.max(1), alt })
        }
        b"Indexed" | b"I" => {
            // [ /Indexed base hival lookup ]
            let base = arr.get(1).and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)).unwrap_or(CsKind::DeviceRGB);
            let base_n = cs_kind_ncomp(&base);
            let hival = arr.get(2).and_then(|o| deref(doc, o)).as_ref().and_then(|o| o.as_i64().ok())
                .unwrap_or(255).clamp(0, 65535) as u16;
            let lookup = match arr.get(3).and_then(|o| deref(doc, o)) {
                Some(Object::String(s,_)) => s.clone(),
                // NOT `decompressed_content()`. lopdf 0.36 implements only
                // Flate/LZW/ASCII85, so RunLength or ASCIIHex hits the `Err` arm and the
                // old fallback handed back the still-ENCODED bytes AS THE PALETTE. Worse,
                // it reads /DecodeParms with `as_dict()`, so an INDIRECT or ARRAY
                // /DecodeParms makes it return `Ok` with the PREDICTOR NEVER APPLIED —
                // a success carrying garbage, which no `unwrap_or_else` can catch.
                // Either way §8.6.6.3's palette is nonsense and every colour in the
                // image is wrong. `stream_data_with_doc` runs our own chain.
                Some(Object::Stream(s)) => stream_data_with_doc(doc, s),
                _ => Vec::new(),
            };
            Some(CsKind::Indexed { base: Box::new(base), lookup, base_ncomp: base_n, hival })
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
        CsKind::Pattern { .. } => 0,
    }
}

/// Component count for an IMAGE sample in this colour space. Identical to
/// [`cs_kind_ncomp`] except for Indexed, where one image sample is a single
/// palette index (§8.6.6.3), not `base_ncomp` colour components.
///
/// Deliberately separate from [`cs_kind_ncomp`], which reports the BASE arity for
/// Indexed because that is what the palette decode needs. Clamped to 1..=32 so a bogus
/// `/N` or a 255-name `/DeviceN` cannot turn `w*h*ncomp` into a huge allocation; Pattern
/// (0 components, illegal for an image per §8.9.5.1) recovers as 1.
pub(crate) fn cs_kind_image_ncomp(kind: &CsKind) -> u8 {
    let n = match kind {
        CsKind::Indexed { .. } => 1,
        other => cs_kind_ncomp(other),
    };
    n.clamp(1, 32)
}

/// Initial color value when a color space is selected via `cs`/`CS` (PDF 8.6.8):
/// black for device/CIE/ICC spaces, index 0 for Indexed, full tint (all 1.0) for
/// Separation/DeviceN. Returns `None` for Pattern (color unchanged).
pub(crate) fn cs_initial_color(doc: &Document, kind: &CsKind, resources: &HashMap<Vec<u8>, ObjectId>) -> Option<u32> {
    let comps: Vec<f64> = match kind {
        CsKind::Separation { .. } => vec![1.0],
        CsKind::DeviceN { names, .. } => vec![1.0; names.len().max(1)],
        CsKind::Indexed { .. } => vec![0.0],
        CsKind::Pattern { .. } => return None,
        _ => vec![0.0; cs_kind_ncomp(kind).max(1) as usize],
    };
    eval_cs_to_rgb(doc, kind, &comps, resources)
}

/// Default per-component value range for a color space, used to decode Indexed
/// palette bytes and image samples. All spaces use [0,1] except Lab, whose L is
/// [0,100] and a*/b* follow the space's /Range.
pub(crate) fn cs_kind_default_decode(kind: &CsKind) -> Vec<(f64, f64)> {
    match kind {
        CsKind::Lab { range, .. } => vec![
            (0.0, 100.0),
            (range[0][0], range[0][1]),
            (range[1][0], range[1][1]),
        ],
        _ => vec![(0.0, 1.0); cs_kind_ncomp(kind) as usize],
    }
}

/// The Cal*/Lab dictionary readers below all feed `.unwrap_or(<spec default>)` at their
/// call sites, so rejecting a malformed entry makes it mean exactly what an ABSENT one
/// means. That matters for NON-FINITE values specifically: a real like 1e40 overflows
/// the f32 `Object::Real` holds, and an infinite /WhitePoint or /Matrix propagates
/// through `adapt_to_d65` (inf/inf) into NaN, which `rgb_to_argb` saturates to 0 — a
/// CalRGB image rendering as a solid black rectangle. Falling back to the default
/// renders the picture instead.
fn all_finite(v: &[f64]) -> bool {
    v.iter().all(|x| x.is_finite())
}

pub(crate) fn read_white_point(dict: &lopdf::Dictionary) -> Option<[f64;3]> {
    let arr = dict.get(b"WhitePoint").ok().and_then(|o| o.as_array().ok())?;
    if arr.len()>=3 {
        let wp = [num(&arr[0])?, num(&arr[1])?, num(&arr[2])?];
        // A zero or negative Y also breaks the adaptation; §8.6.5.2 requires X and Z
        // positive and Y exactly 1.
        if all_finite(&wp) && wp[0] > 0.0 && wp[1] > 0.0 && wp[2] > 0.0 {
            Some(wp)
        } else {
            None
        }
    } else { None }
}

pub(crate) fn read_gamma_rgb(dict: &lopdf::Dictionary) -> Option<[f64;3]> {
    let arr = dict.get(b"Gamma").ok().and_then(|o| o.as_array().ok())?;
    if arr.len()>=3 {
        let g = [num(&arr[0])?, num(&arr[1])?, num(&arr[2])?];
        if all_finite(&g) { Some(g) } else { None }
    } else { None }
}

pub(crate) fn read_matrix_cal(dict: &lopdf::Dictionary) -> Option<[[f64;3];3]> {
    let arr = dict.get(b"Matrix").ok().and_then(|o| o.as_array().ok())?;
    if arr.len()>=9 {
        // PDF stores the CalRGB Matrix column-major as
        // [XA YA ZA  XB YB ZB  XC YC ZC], defining
        //   X = XA·A + XB·B + XC·C, Y = YA·A + …, Z = ZA·A + …
        // eval_cs_to_rgb multiplies rows against (A,B,C), so store it as rows
        // [[XA XB XC],[YA YB YC],[ZA ZB ZC]] (i.e. transposed from the array
        // order). Storing it in raw array order transposes the transform and
        // turns e.g. CalRGB white into cyan (issue #321 colorrenderexample).
        let m = [
            [num(&arr[0])?, num(&arr[3])?, num(&arr[6])?],
            [num(&arr[1])?, num(&arr[4])?, num(&arr[7])?],
            [num(&arr[2])?, num(&arr[5])?, num(&arr[8])?],
        ];
        if m.iter().all(|row| all_finite(row)) { Some(m) } else { None }
    } else { None }
}

pub(crate) fn read_lab_range(dict: &lopdf::Dictionary) -> Option<[[f64;2];2]> {
    let arr = dict.get(b"Range").ok().and_then(|o| o.as_array().ok())?;
    if arr.len()>=4 {
        let r = [num(&arr[0])?, num(&arr[1])?, num(&arr[2])?, num(&arr[3])?];
        // /Range also seeds the default /Decode for a Lab image (§8.9.5.2 Table 90),
        // so a non-finite bound would scale every sample to NaN.
        if all_finite(&r) { Some([[r[0], r[1]],[r[2], r[3]]]) } else { None }
    } else { None }
}

include!("color_part1.rs");
include!("color_part2.rs");
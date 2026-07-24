use crate::*;

/// JPEG2000 (`JPXDecode`) decoding via the pure-Rust `openjp2` port of OpenJPEG.
pub(crate) mod jp2 {
    use openjp2::openjpeg::*;
    use std::ffi::c_void;

    struct Slice<'a> {
        off: usize,
        buf: &'a [u8],
    }
    impl<'a> Slice<'a> {
        fn seek(&mut self, n: usize) -> usize {
            self.off = self.buf.len().min(n);
            self.off
        }
        fn consume(&mut self, n: usize) -> usize {
            self.off = self.buf.len().min(self.off.saturating_add(n));
            self.off
        }
    }
    extern "C" fn free_fn(p: *mut c_void) {
        drop(unsafe { Box::from_raw(p as *mut Slice) })
    }
    extern "C" fn read_fn(pb: *mut c_void, nb: usize, p: *mut c_void) -> usize {
        if pb.is_null() || nb == 0 {
            return usize::MAX;
        }
        let s = unsafe { &mut *(p as *mut Slice) };
        let remaining = s.buf.len() - s.off;
        if remaining == 0 {
            return usize::MAX;
        }
        let n = remaining.min(nb);
        let out = unsafe { std::slice::from_raw_parts_mut(pb as *mut u8, n) };
        out.copy_from_slice(&s.buf[s.off..s.off + n]);
        s.off += n;
        n
    }
    extern "C" fn skip_fn(nb: i64, p: *mut c_void) -> i64 {
        let s = unsafe { &mut *(p as *mut Slice) };
        s.consume(nb.max(0) as usize) as i64
    }
    extern "C" fn seek_fn(nb: i64, p: *mut c_void) -> i32 {
        let s = unsafe { &mut *(p as *mut Slice) };
        let want = nb.max(0) as usize;
        if s.seek(want) == want {
            1
        } else {
            0
        }
    }

    /// Decode JP2/J2K bytes to `(width, height, RGBA8888)`, or `None`.
    pub fn decode(bytes: &[u8]) -> Option<(u32, u32, Vec<u8>)> {
        // JP2 signature box vs raw codestream.
        let fmt = if bytes.len() > 4 && &bytes[4..8] == b"jP  " {
            OPJ_CODEC_JP2
        } else {
            OPJ_CODEC_J2K
        };
        unsafe { decode_with(bytes, fmt).or_else(|| decode_with(bytes, OPJ_CODEC_JP2)) }
    }

    unsafe fn decode_with(bytes: &[u8], fmt: OPJ_CODEC_FORMAT) -> Option<(u32, u32, Vec<u8>)> {
        let data = Box::new(Slice { off: 0, buf: bytes });
        let stream = opj_stream_default_create(1);
        if stream.is_null() {
            return None;
        }
        let p = Box::into_raw(data) as *mut c_void;
        opj_stream_set_read_function(stream, Some(read_fn));
        opj_stream_set_skip_function(stream, Some(skip_fn));
        opj_stream_set_seek_function(stream, Some(seek_fn));
        opj_stream_set_user_data_length(stream, bytes.len() as u64);
        opj_stream_set_user_data(stream, p, Some(free_fn));

        let codec = opj_create_decompress(fmt);
        if codec.is_null() {
            opj_stream_destroy(stream);
            return None;
        }
        let mut params = opj_dparameters_t::default();
        opj_set_default_decoder_parameters(&mut params);
        let mut out = None;
        if opj_setup_decoder(codec, &mut params) != 0 {
            let mut image = std::ptr::null_mut() as *mut opj_image_t;
            if opj_read_header(stream, codec, &mut image) != 0
                && opj_decode(codec, stream, image) != 0
                && opj_end_decompress(codec, stream) != 0
                && !image.is_null()
            {
                out = image_to_rgba(&*image);
            }
            if !image.is_null() {
                opj_image_destroy(image);
            }
        }
        opj_destroy_codec(codec);
        opj_stream_destroy(stream);
        out
    }

    unsafe fn image_to_rgba(img: &opj_image_t) -> Option<(u32, u32, Vec<u8>)> {
        let w = (img.x1 - img.x0) as usize;
        let h = (img.y1 - img.y0) as usize;
        if w == 0 || h == 0 || w > 20000 || h > 20000 || img.numcomps == 0 {
            return None;
        }
        let comps = std::slice::from_raw_parts(img.comps, img.numcomps as usize);
        let n = img.numcomps as usize;
        // Sample a component's value at (x,y) scaled to 8-bit.
        let sample = |c: &opj_image_comp_t, x: usize, y: usize| -> u8 {
            let cw = c.w as usize;
            let ch = c.h as usize;
            if cw == 0 || ch == 0 || c.data.is_null() {
                return 0;
            }
            let sx = (x * cw / w).min(cw - 1);
            let sy = (y * ch / h).min(ch - 1);
            let mut v = *c.data.add(sy * cw + sx);
            if c.sgnd != 0 {
                v += 1 << (c.prec - 1);
            }
            let prec = c.prec as i32;
            let v = if prec > 8 {
                v >> (prec - 8)
            } else if prec < 8 {
                v << (8 - prec)
            } else {
                v
            };
            v.clamp(0, 255) as u8
        };

        let mut rgba = vec![0u8; w * h * 4];
        for y in 0..h {
            for x in 0..w {
                let idx = (y * w + x) * 4;
                let (r, g, b) = if n >= 3 {
                    (
                        sample(&comps[0], x, y),
                        sample(&comps[1], x, y),
                        sample(&comps[2], x, y),
                    )
                } else {
                    let v = sample(&comps[0], x, y);
                    (v, v, v)
                };
                rgba[idx] = r;
                rgba[idx + 1] = g;
                rgba[idx + 2] = b;
                rgba[idx + 3] = 255;
            }
        }
        Some((w as u32, h as u32, rgba))
    }
}

pub(crate) struct ImageData {
    pub(crate) w: u32,
    pub(crate) h: u32,
    /// 0 = raw RGBA8888, 1 = JPEG bytes.
    pub(crate) format: u8,
    pub(crate) data: Vec<u8>,
}

/// Map of XObject resource name -> object id from a resources dictionary.
pub(crate) fn xobjects_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, ObjectId> {
    let mut out = HashMap::new();
    if let Some(Object::Dictionary(xo)) = res_dict.get(b"XObject").ok().and_then(|o| deref(doc, o)) {
        for (name, v) in xo.iter() {
            if let Ok(id) = v.as_reference() {
                out.insert(name.clone(), id);
            }
        }
    }
    out
}

/// Map of ExtGState resource name -> object id
pub(crate) fn extgstates_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, ObjectId> {
    let mut out = HashMap::new();
    if let Some(Object::Dictionary(eg)) = res_dict.get(b"ExtGState").ok().and_then(|o| deref(doc, o)) {
        for (name, v) in eg.iter() {
            if let Ok(id) = v.as_reference() {
                out.insert(name.clone(), id);
            } else if let Object::Dictionary(_) = v {
                // inline dict without indirect: create synthetic entry? For now, parse directly by storing dummy - handled via direct dict lookup in gs implementation
                // We'll handle inline dict in interpret_content by also checking resources for direct dict
                // To support, we add a separate path: store name with a sentinel and also keep dict; but for MVP we allow direct lookup via resources dict retrieval
                // For simplicity, insert with placeholder and treat separately? Instead, we will parse inline ExtGState via a separate function extgstate_dict
                // No placeholder needed - we will also check direct dict in interpret_content if not found in extgstates
            }
        }
    }
    out
}

pub(crate) fn extgstate_dict<'a>(doc: &'a Document, res_dict: &'a lopdf::Dictionary, name: &[u8]) -> Option<&'a lopdf::Dictionary> {
    let eg = res_dict.get(b"ExtGState").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok())?;
    let obj = eg.get(name).ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok())?;
    Some(obj)
}


/// Check if an OCG (Optional Content Group) is visible based on document's OCProperties.
/// Returns true if visible or unknown (default visible to avoid breaking existing PDFs).
pub(crate) fn is_ocg_visible(doc: &Document, ocg_id: ObjectId) -> bool {
    // Try to parse OCProperties from catalog
    let catalog = match doc.catalog() {
        Ok(c) => c,
        Err(_) => return true,
    };
    let oc_props = match catalog.get(b"OCProperties").ok().and_then(|o| doc.dereference(o).ok()).map(|(_,obj)| obj) {
        Some(Object::Dictionary(d)) => d.clone(),
        _ => return true,
    };
    let d_dict = match oc_props.get(b"D").ok().and_then(|o| doc.dereference(o).ok()).map(|(_,obj)| obj).and_then(|o| o.as_dict().ok()).cloned() {
        Some(d) => d,
        None => return true,
    };
    // BaseState: ON or OFF
    let base_on = matches!(d_dict.get(b"BaseState").ok().and_then(|o| o.as_name().ok()), Some(b"ON") | None);
    // ON and OFF arrays
    let on_list = d_dict.get(b"ON").ok().and_then(|o| doc.dereference(o).ok()).map(|(_,obj)| obj).and_then(|o| o.as_array().ok()).cloned().unwrap_or_default();
    let off_list = d_dict.get(b"OFF").ok().and_then(|o| doc.dereference(o).ok()).map(|(_,obj)| obj).and_then(|o| o.as_array().ok()).cloned().unwrap_or_default();

    let is_in_list = |list: &[Object], id: ObjectId| -> bool {
        list.iter().any(|obj| {
            if let Ok(ref_id) = obj.as_reference() { ref_id == id } else { false }
        })
    };

    if is_in_list(&on_list, ocg_id) {
        return true;
    }
    if is_in_list(&off_list, ocg_id) {
        return false;
    }
    // No explicit entry, use BaseState
    base_on
}

pub(crate) fn ocg_is_visible_alias(doc: &Document, id: ObjectId) -> Option<bool> {
    Some(is_ocg_visible(doc, id))
}


/// Whether a colorspace requires the full `eval_cs_to_rgb` path (vs the fast
/// `comps_to_rgb` device path which is equivalent for plain RGB/Gray/CMYK).
fn cs_needs_eval(k: &CsKind) -> bool {
    match k {
        CsKind::DeviceGray | CsKind::DeviceRGB | CsKind::DeviceCMYK | CsKind::Pattern => false,
        CsKind::ICCBased { alt, .. } => alt.as_ref().map(|a| cs_needs_eval(a)).unwrap_or(false),
        CsKind::Lab { .. }
        | CsKind::Separation { .. }
        | CsKind::DeviceN { .. }
        | CsKind::CalRGB { .. }
        | CsKind::CalGray { .. }
        | CsKind::Indexed { .. } => true,
    }
}

/// Default `/Decode` ranges per component for a colorspace (used when the image
/// has no explicit `/Decode`). Lab uses [0,100] for L and its a*/b* ranges.
fn default_decode_for(k: &CsKind, ncomp: usize) -> Vec<(f64, f64)> {
    match k {
        CsKind::Lab { range, .. } => vec![
            (0.0, 100.0),
            (range[0][0], range[0][1]),
            (range[1][0], range[1][1]),
        ],
        _ => vec![(0.0, 1.0); ncomp.max(1)],
    }
}

/// Convert unpacked component bytes (w*h*ncomp, each 0..=255, already scaled
/// from `bpc`) into RGBA using the image's full `/ColorSpace`. Handles
/// Separation/DeviceN tint transforms, Lab, Cal*, ICCBased alternates and
/// Indexed palettes via `eval_cs_to_rgb`, building LUTs for single-component
/// and indexed images to stay fast. Falls back to `comps_to_rgb` for plain
/// device spaces.
fn image_samples_to_rgba(
    doc: &Document,
    dict: &lopdf::Dictionary,
    cs_resources: &HashMap<Vec<u8>, ObjectId>,
    decoded_comps: &[u8],
    w: usize,
    h: usize,
    ncomp: usize,
    bpc: u32,
) -> Vec<u8> {
    let mut rgba = vec![0u8; w * h * 4];
    let cs_kind = dict
        .get(b"ColorSpace")
        .or_else(|_| dict.get(b"CS"))
        .ok()
        .and_then(|o| parse_cs_kind(doc, Some(o), cs_resources));

    let decode_arr: Vec<f64> = dict
        .get(b"Decode")
        .or_else(|_| dict.get(b"D"))
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect())
        .unwrap_or_default();

    let put = |rgba: &mut [u8], idx: usize, argb: u32| {
        rgba[idx] = ((argb >> 16) & 0xFF) as u8;
        rgba[idx + 1] = ((argb >> 8) & 0xFF) as u8;
        rgba[idx + 2] = (argb & 0xFF) as u8;
        rgba[idx + 3] = 255;
    };

    let kind = match cs_kind {
        Some(k) if cs_needs_eval(&k) => k,
        _ => {
            // Fast device path.
            for i in 0..w * h {
                let base = i * ncomp;
                let (r, g, b) = if base + ncomp <= decoded_comps.len() {
                    comps_to_rgb(&decoded_comps[base..base + ncomp], ncomp as u8)
                } else {
                    (0, 0, 0)
                };
                let idx = i * 4;
                rgba[idx] = r;
                rgba[idx + 1] = g;
                rgba[idx + 2] = b;
                rgba[idx + 3] = 255;
            }
            return rgba;
        }
    };

    // Indexed: build a palette LUT over the base colorspace.
    if let CsKind::Indexed { base, lookup, base_ncomp } = &kind {
        let bn = *base_ncomp as usize;
        let maxidx = if bpc >= 8 { 255usize } else { (1usize << bpc) - 1 };
        let hival = if bn > 0 { (lookup.len() / bn).saturating_sub(1) } else { 0 };
        let mut palette = vec![0xFF00_0000u32; hival + 1];
        for (i, slot) in palette.iter_mut().enumerate() {
            let off = i * bn;
            if off + bn <= lookup.len() {
                let comps: Vec<f64> = lookup[off..off + bn].iter().map(|b| *b as f64 / 255.0).collect();
                if let Some(argb) = eval_cs_to_rgb(doc, base, &comps, cs_resources) {
                    *slot = argb;
                }
            }
        }
        for i in 0..w * h {
            let byte = decoded_comps.get(i * ncomp).copied().unwrap_or(0) as usize;
            let index = if bpc >= 8 { byte } else { (byte * maxidx + 127) / 255 };
            let argb = palette.get(index.min(hival)).copied().unwrap_or(0xFF00_0000);
            put(&mut rgba, i * 4, argb);
        }
        return rgba;
    }

    let default_decode = default_decode_for(&kind, ncomp);
    let comp_range = |i: usize| -> (f64, f64) {
        if decode_arr.len() >= (i + 1) * 2 {
            (decode_arr[i * 2], decode_arr[i * 2 + 1])
        } else {
            default_decode.get(i).copied().unwrap_or((0.0, 1.0))
        }
    };

    // Single-component spaces: 256-entry LUT.
    if ncomp == 1 {
        let (lo, hi) = comp_range(0);
        let mut lut = [0xFF00_0000u32; 256];
        for (v, slot) in lut.iter_mut().enumerate() {
            let comp = lo + (v as f64 / 255.0) * (hi - lo);
            if let Some(argb) = eval_cs_to_rgb(doc, &kind, &[comp], cs_resources) {
                *slot = argb;
            }
        }
        for i in 0..w * h {
            let byte = decoded_comps.get(i * ncomp).copied().unwrap_or(0) as usize;
            put(&mut rgba, i * 4, lut[byte]);
        }
        return rgba;
    }

    // General per-pixel evaluation (bounded by MAX_IMAGE_PIXELS at entry).
    for i in 0..w * h {
        let base = i * ncomp;
        let idx = i * 4;
        if base + ncomp <= decoded_comps.len() {
            let comps: Vec<f64> = (0..ncomp)
                .map(|c| {
                    let (lo, hi) = comp_range(c);
                    lo + (decoded_comps[base + c] as f64 / 255.0) * (hi - lo)
                })
                .collect();
            if let Some(argb) = eval_cs_to_rgb(doc, &kind, &comps, cs_resources) {
                put(&mut rgba, idx, argb);
            } else {
                let (r, g, b) = comps_to_rgb(&decoded_comps[base..base + ncomp], ncomp as u8);
                rgba[idx] = r;
                rgba[idx + 1] = g;
                rgba[idx + 2] = b;
                rgba[idx + 3] = 255;
            }
        }
    }
    rgba
}

/// Extract a drawable image from an image XObject stream, or `None` if the
/// format is unsupported (e.g. JPEG2000, exotic color spaces).
pub(crate) fn extract_image(doc: &Document, stream: &lopdf::Stream, fill_argb: u32, cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<ImageData> {
    let dict = &stream.dict;
    let w = dict.get(b"Width").ok().and_then(num)? as u32;
    let h = dict.get(b"Height").ok().and_then(num)? as u32;
    if w == 0 || h == 0 || w > MAX_IMAGE_DIM || h > MAX_IMAGE_DIM {
        return None;
    }
    if (w as usize).checked_mul(h as usize).unwrap_or(usize::MAX) > MAX_IMAGE_PIXELS {
        return None;
    }
    if stream.content.len() > MAX_IMAGE_BYTES * 4 {
        // raw compressed size guard, still attempt but cap later
    }

    // Normalize filter chain case-insensitive + DecodeParms pairing
    let specs = filters::filter_specs_from_dict(doc, dict);
    let has_kind = |k: filters::FilterKind| specs.iter().any(|(kind,_)| *kind == k);

    // Legacy names fallback for callers without new parser (inline images)
    let legacy_filters = filter_names(doc, dict);
    let legacy_is_dct = legacy_filters.iter().any(|f| f.eq_ignore_ascii_case("DCTDecode") || f.eq_ignore_ascii_case("DCT"));
    let legacy_is_jpx = legacy_filters.iter().any(|f| f.eq_ignore_ascii_case("JPXDecode"));
    let is_dct = has_kind(filters::FilterKind::Dct) || legacy_is_dct;
    let is_jpx = has_kind(filters::FilterKind::Jpx) || legacy_is_jpx;
    let is_ccitt = has_kind(filters::FilterKind::Ccitt);
    let is_jbig2 = has_kind(filters::FilterKind::Jbig2);

    // JBIG2: attempt with Globals
    if is_jbig2 {
        let mut globals_bytes: Option<Vec<u8>> = None;
        // Try to find JBIG2Globals in DecodeParms of JBIG2 filter
        for (kind, parms) in specs.iter() {
            if *kind == filters::FilterKind::Jbig2 {
                if let Some(pd) = parms {
                    // /JBIG2Globals may be indirect stream
                    let obj_opt = pd.get(b"JBIG2Globals").ok()
                        .and_then(|o| deref(doc,o).or(Some(o)))
                        .cloned();
                    if let Some(obj) = obj_opt {
                        match obj {
                            Object::Stream(s) => {
                                globals_bytes = Some(s.decompressed_content().unwrap_or_else(|_| s.content.clone()));
                            }
                            Object::Reference(id) => {
                                if let Ok(Object::Stream(s)) = doc.get_object(id) {
                                    globals_bytes = Some(s.decompressed_content().unwrap_or_else(|_| s.content.clone()));
                                }
                            }
                            _ => {}
                        }
                    }
                    // also nested deref if DecodeParms is reference containing indirect
                }
            }
        }
        // Also check dict's own DecodeParms may be array with first dict containing globals
        if globals_bytes.is_none() {
            if let Some(Object::Dictionary(d)) = dict.get(b"DecodeParms").ok().and_then(|o| deref(doc,o)) {
                if let Some(obj) = d.get(b"JBIG2Globals").ok().and_then(|o| deref(doc,o).or(Some(o))).cloned() {
                    if let Object::Stream(s) = obj {
                        globals_bytes = Some(s.decompressed_content().unwrap_or_else(|_| s.content.clone()));
                    }
                }
            }
        }

        // Attempt decode from raw content
        if let Some((jw,jh,mut rgba)) = jbig2::decode_jbig2(&stream.content, globals_bytes.as_deref(), w, h) {
            let smask = read_smask(doc, dict, jw, jh);
            apply_smask(&mut rgba, &smask);
            if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
            return Some(ImageData{ w: jw, h: jh, format: 0, data: rgba });
        }
        // If JBIG2 decode fails, fallback to attempt chain decode? It will still be blank; we continue to allow placeholder path later
        // But try to decode as if data was after ASCII filters
        let raw = stream.content.clone();
        if let Some(chain) = filters::decode_stream_chain(raw, &specs, doc) {
            // chain may still be JBIG2; retry
            if let Some((jw,jh,mut rgba)) = jbig2::decode_jbig2(&chain, globals_bytes.as_deref(), w, h) {
                let smask = read_smask(doc, dict, jw, jh);
                apply_smask(&mut rgba, &smask);
                if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
                return Some(ImageData{ w: jw, h: jh, format: 0, data: rgba });
            }
        }
        // Instead of blank, emit placeholder gray box 100x100 with warning color per Phase 6
        let pw = w.min(200);
        let ph = h.min(200);
        if (pw as usize)*(ph as usize) > crate::MAX_IMAGE_PIXELS { return None; }
        let mut rgba = vec![0u8; (pw * ph * 4) as usize];
        for y in 0..ph as usize {
            for x in 0..pw as usize {
                let idx = (y * pw as usize + x)*4;
                // light gray with red border to indicate JBIG2 decode failure placeholder
                let is_border = x<2 || y<2 || x>=pw as usize -2 || y>=ph as usize -2;
                if is_border { rgba[idx]=200; rgba[idx+1]=0; rgba[idx+2]=0; rgba[idx+3]=255; }
                else { rgba[idx]=0xCC; rgba[idx+1]=0xCC; rgba[idx+2]=0xCC; rgba[idx+3]=255; }
            }
        }
        return Some(ImageData{ w: pw, h: ph, format: 0, data: rgba });
    }

    // JPEG2000 path
    if is_jpx {
        // Raw JPX may be after chain of Ascii/Flate decodes
        let raw = stream.content.clone();
        let chain_bytes = filters::decode_stream_chain(raw.clone(), &specs, doc).unwrap_or(raw.clone());
        let try_data = [&chain_bytes[..], &stream.content[..]];
        for d in try_data {
            if let Some((jw, jh, mut rgba)) = jp2::decode(d) {
                let smask = read_smask(doc, dict, jw, jh);
                apply_smask(&mut rgba, &smask);
                if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
                return Some(ImageData { w: jw, h: jh, format: 0, data: rgba });
            }
        }
        // JPX decode failed: do not fall through and reinterpret the encoded
        // JPEG2000 stream as raw samples.
        return None;
    }

    // CCITTFax full Group3/4 with K,Columns,BlackIs1 params
    if is_ccitt {
        let params = {
            // Find first Ccitt parms dict from specs
            let mut found = None;
            for (kind, pd) in specs.iter() {
                if *kind == filters::FilterKind::Ccitt {
                    found = Some(filters::parse_ccitt_params(doc, pd.as_ref()));
                    break;
                }
            }
            found.unwrap_or_else(|| {
                // Try DecodeParms singly
                if let Some(Object::Dictionary(d)) = dict.get(b"DecodeParms").ok().and_then(|o| deref(doc,o)) {
                    filters::parse_ccitt_params(doc, Some(d))
                } else {
                    filters::CcittParams::default()
                }
            })
        };
        // Try chain decode first for possible Ascii/Flate wrappers before CCITT?
        let raw = stream.content.clone();
        let chain_bytes = filters::decode_stream_chain(raw.clone(), &specs, doc).unwrap_or(raw);
        // If chain_bytes still seems compressed CCITT, try fax decoder
        if let Some(packed) = filters::decode_ccitt(&chain_bytes, w, h, &params) {
            // packed is 1-bit per pixel packed bits -> convert to RGBA
            let row_bytes = ((params.columns.max(w) as usize +7)/8);
            let rows = if params.rows>0 { params.rows as usize } else { h as usize };
            let cols = params.columns.max(w) as usize;
            let w_us = w as usize; // use PDF declared w? but columns may differ, use columns for raster width?
            // Use columns as actual width for output, clamped to PDF w? Plan says legible for Columns=1728 fax
            let out_w = params.columns.max(w);
            let out_h = if params.rows>0 { params.rows } else { h };
            if (out_w as usize)*(out_h as usize) > MAX_IMAGE_PIXELS { return None; }
            let mut rgba = vec![0u8; (out_w * out_h *4) as usize];
            // `decode_ccitt` already returns semantic 1-bit data (1 = black), so
            // /BlackIs1 has been accounted for during decode and is not re-applied.
            for y in 0..rows.min(out_h as usize) {
                for x in 0..cols {
                    let byte = packed.get(y*row_bytes + x/8).copied().unwrap_or(0);
                    let bit = (byte >> (7 - (x%8))) &1;
                    let black = bit==1;
                    if black {
                        // black pixel
                        let idx = (y * out_w as usize + x)*4;
                        if idx+3 < rgba.len() { rgba[idx]=0; rgba[idx+1]=0; rgba[idx+2]=0; rgba[idx+3]=255; }
                    } else {
                        let idx = (y * out_w as usize + x)*4;
                        if idx+3 < rgba.len() { rgba[idx]=255; rgba[idx+1]=255; rgba[idx+2]=255; rgba[idx+3]=255; }
                    }
                }
            }
            let smask = read_smask(doc, dict, out_w, out_h);
            apply_smask(&mut rgba, &smask);
            if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
            return Some(ImageData{ w: out_w, h: out_h, format: 0, data: rgba });
        }
        // CCITT decode failed: don't reinterpret the encoded fax data as raw
        // 1-bit samples.
        return None;
    }

    // DCT with SMask/Mask: decode to RGBA + apply mask, with gray fallback
    if is_dct {
        let smask_present = dict.get(b"SMask").is_ok();
        let mask_present = dict.get(b"Mask").is_ok();
        // Chain decode to get JPEG bytes if wrapped in Ascii etc
        let raw = stream.content.clone();
        let jpeg_bytes = filters::decode_stream_chain(raw.clone(), &specs, doc).unwrap_or(raw);
        if smask_present || mask_present {
            if let Some((jw,jh,mut rgba)) = decode_jpeg_rgba(&jpeg_bytes) {
                let smask = read_smask(doc, dict, jw, jh);
                apply_smask(&mut rgba, &smask);
                if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
                return Some(ImageData { w: jw, h: jh, format: 0, data: rgba });
            }
            // Fallback to gray JPEG decode + alpha
            if let Some((jw,jh,gray)) = decode_jpeg_gray(&jpeg_bytes) {
                let mut rgba = vec![0u8; (jw*jh*4) as usize];
                for i in 0..(jw*jh) as usize {
                    let g = gray.get(i).copied().unwrap_or(0);
                    rgba[i*4]=g; rgba[i*4+1]=g; rgba[i*4+2]=g; rgba[i*4+3]=255;
                }
                let smask = read_smask(doc, dict, jw, jh);
                apply_smask(&mut rgba, &smask);
                if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
                return Some(ImageData{ w: jw, h: jh, format: 0, data: rgba });
            }
        }
        if !smask_present && !mask_present {
            // efficient passthrough
            return Some(ImageData { w, h, format: 1, data: jpeg_bytes });
        }
        // DCT with a mask but JPEG decode failed: avoid reinterpreting the
        // encoded JPEG stream as raw samples.
        return None;
    }

    let image_mask = matches!(dict.get(b"ImageMask").ok(), Some(Object::Boolean(true)));
    let bpc = if image_mask { 1 } else { dict.get(b"BitsPerComponent").ok().and_then(num).unwrap_or(8.0) as u32 };
    let samples = stream_data(stream);
    let mut rgba = vec![0u8; (w * h * 4) as usize];
    let smask = read_smask(doc, dict, w, h);
    let colorkey = read_color_key_mask(doc, dict);

    if image_mask {
        let invert = matches!(
            dict.get(b"Decode").ok().and_then(|o| deref(doc, o)),
            Some(Object::Array(a)) if a.first().and_then(num) == Some(1.0)
        );
        let fr = ((fill_argb >> 16) & 0xFF) as u8;
        let fg = ((fill_argb >> 8) & 0xFF) as u8;
        let fb = (fill_argb & 0xFF) as u8;
        // Use unpack for 1-bit
        let row_bytes = ((w + 7) / 8) as usize;
        // If lopdf decompressed with predictor, row may have predictor overhead - for simplicity try direct
        for y in 0..h as usize {
            for x in 0..w as usize {
                let byte = samples.get(y * row_bytes + x / 8).copied().unwrap_or(0);
                let mut bit = (byte >> (7 - (x % 8))) & 1;
                if invert { bit ^= 1; }
                let idx = (y * w as usize + x) * 4;
                if bit == 0 {
                    rgba[idx]=fr; rgba[idx+1]=fg; rgba[idx+2]=fb; rgba[idx+3]=255;
                }
            }
        }
        return Some(ImageData { w, h, format: 0, data: rgba });
    }

    let (ncomp, indexed) = colorspace_info(doc, dict.get(b"ColorSpace").ok());

    // Ensure samples unpacked according to BPC
    // First try generic unpack for BPC 1,2,4,8,12,16
    let unpacked = unpack_samples_to_bytes(&samples, w as usize, h as usize, ncomp as usize, bpc);
    let decoded_comps = if let Some(u) = unpacked {
        u
    } else {
        // Unsupported BPC path -> None
        return None;
    };

    // Now decoded_comps is w*h*ncomp bytes (0..255)
    let w_us = w as usize;
    let h_us = h as usize;
    let _ = &indexed; // colorspace handled fully by image_samples_to_rgba
    let mut rgba = image_samples_to_rgba(doc, dict, cs_resources, &decoded_comps, w_us, h_us, ncomp as usize, bpc);

    apply_smask(&mut rgba, &smask);
    apply_color_key_mask(&mut rgba, &colorkey);
    Some(ImageData { w, h, format: 0, data: rgba })
}

pub(crate) fn extract_inline_image(doc: &Document, stream: &lopdf::Stream, _fill_argb: u32, cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<ImageData> {
    let dict = &stream.dict;
    let w = dict.get(b"Width").or_else(|_| dict.get(b"W")).ok().and_then(num)? as u32;
    let h = dict.get(b"Height").or_else(|_| dict.get(b"H")).ok().and_then(num)? as u32;
    if w==0 || h==0 || w>MAX_IMAGE_DIM || h>MAX_IMAGE_DIM { return None; }
    if (w as usize)*(h as usize) > MAX_IMAGE_PIXELS { return None; }

    let bpc = dict.get(b"BitsPerComponent").or_else(|_| dict.get(b"BPC")).ok().and_then(num).unwrap_or(8.0) as u32;

    let cs_obj = dict.get(b"ColorSpace").or_else(|_| dict.get(b"CS")).ok().cloned();
    let (ncomp, indexed) = if let Some(ref cs) = cs_obj {
        colorspace_info(doc, Some(cs))
    } else {
        (1, None)
    };

    // Support filter chain for inline BI: may have Flate, AHx, A85 etc.
    let specs = filters::filter_specs_from_dict(doc, dict);
    let raw = stream.content.clone();
    let mut samples = filters::decode_stream_chain(raw.clone(), &specs, doc).unwrap_or(raw.clone());

    // DCT inline
    let legacy_filters = filter_names(doc, dict);
    let mut all_has_dct = specs.iter().any(|(k,_)| *k == filters::FilterKind::Dct);
    if !all_has_dct {
        all_has_dct = legacy_filters.iter().any(|f| f.eq_ignore_ascii_case("DCTDecode")||f.eq_ignore_ascii_case("DCT"));
    }
    if all_has_dct {
        let smask = read_smask(doc, dict, w, h);
        let colorkey = read_color_key_mask(doc, dict);
        if let Some((jw,jh,mut rgba)) = decode_jpeg_rgba(&samples) {
            apply_smask(&mut rgba, &smask);
            apply_color_key_mask(&mut rgba, &colorkey);
            return Some(ImageData { w: jw, h: jh, format: 0, data: rgba });
        }
        return Some(ImageData { w, h, format: 1, data: samples });
    }

    // For other filters, samples is already chain-decoded
    let unpacked = unpack_samples_to_bytes(&samples, w as usize, h as usize, ncomp as usize, bpc)?;
    let _ = &indexed; // colorspace handled fully by image_samples_to_rgba
    let mut rgba = image_samples_to_rgba(doc, dict, cs_resources, &unpacked, w as usize, h as usize, ncomp as usize, bpc);
    let smask = read_smask(doc, dict, w, h);
    apply_smask(&mut rgba, &smask);
    if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
    Some(ImageData { w, h, format: 0, data: rgba })
}

/// Radial (Type 3) shading parameter at a point in shading space.
///
/// The gradient is defined by the family of circles C(s) centered at
/// c0 + s·(c1−c0) with radius r0 + s·(r1−r0), for `coords` = [x0 y0 r0 x1 y1 r1].
/// For a point (fx,fy) this returns the largest `s` such that the point lies on
/// C(s) with a non-negative radius, honoring the two `/Extend` flags (`e0` past
/// s<0, `e1` past s>1). Solving |p − c(s)| = r(s) yields the quadratic
/// a·s² − 2b·s + c = 0. Returns `None` when no circle covers the point (the
/// caller leaves that pixel transparent).
pub(crate) fn radial_shading_param(coords: &[f64], e0: bool, e1: bool, fx: f64, fy: f64) -> Option<f64> {
    if coords.len() < 6 { return None; }
    let (x0, y0, r0) = (coords[0], coords[1], coords[2]);
    let (x1, y1, r1) = (coords[3], coords[4], coords[5]);
    let dx = x1 - x0;
    let dy = y1 - y0;
    let dr = r1 - r0;
    let px = fx - x0;
    let py = fy - y0;
    let a = dx*dx + dy*dy - dr*dr;
    let b = px*dx + py*dy + r0*dr;
    let c = px*px + py*py - r0*r0;

    let mut best: Option<f64> = None;
    let mut consider = |s: f64| {
        // The interpolated circle radius must be non-negative.
        if r0 + s*dr < 0.0 { return; }
        // Respect the shading domain unless extended past an end.
        let in_range = (s >= 0.0 && s <= 1.0) || (s < 0.0 && e0) || (s > 1.0 && e1);
        if !in_range { return; }
        best = Some(match best { Some(cur) if cur >= s => cur, _ => s });
    };
    if a.abs() < 1e-9 {
        // Degenerate to a linear equation: -2b·s + c = 0.
        if b.abs() > 1e-12 { consider(c / (2.0*b)); }
    } else {
        let disc = b*b - a*c;
        if disc >= 0.0 {
            let sq = disc.sqrt();
            consider((b + sq)/a);
            consider((b - sq)/a);
        }
    }
    best
}

pub(crate) fn rasterize_shading(doc: &Document, shading_obj: &Object, base_ctm: &Mat, cs_resources: &HashMap<Vec<u8>, ObjectId>, size: u32) -> Option<(Mat, u32, u32, Vec<u8>)> {
    // A shading may be a plain dictionary (Type 1-3) or a stream (Type 4-7,
    // whose mesh data lives in the stream body).
    let (dict, mesh_bytes): (&lopdf::Dictionary, Option<Vec<u8>>) = match shading_obj {
        Object::Dictionary(d) => (d, None),
        Object::Stream(s) => (&s.dict, Some(stream_data_with_doc(doc, s))),
        _ => return None,
    };
    let shading_type = dict.get(b"ShadingType").ok().and_then(num).unwrap_or(0.0) as i64;
    if shading_type==4 || shading_type==5 || shading_type==6 || shading_type==7 {
        // Mesh shadings: pass the decoded stream body so real vertices/patches
        // can be parsed (falls back to /DataSource when the dict provides one).
        return shading::rasterize_shading_mesh(doc, dict, mesh_bytes.as_deref(), base_ctm, cs_resources, size);
    }
    if shading_type==1 {
        return rasterize_shading_function_based(doc, dict, base_ctm, cs_resources, size);
    }
    if shading_type!=2 && shading_type!=3 { return None; }

    let coords = dict.get(b"Coords").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect::<Vec<f64>>()).unwrap_or_default();
    // Background color for areas outside Extend
    let bg = dict.get(b"Background").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect::<Vec<f64>>());

    let bbox = dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)).unwrap_or([0.0,0.0,100.0,100.0]);

    let color_space_obj = dict.get(b"ColorSpace").ok().and_then(|o| parse_cs_kind(doc, Some(o), cs_resources));
    // If CS not present, try to infer from Function or Background etc.

    // Function: full PDF function support (Type 0/2/3/4, or array-of-functions).
    let pdf_func = dict.get(b"Function").ok().and_then(|o| PdfFunction::parse(doc, o));

    // Extend [bool bool]
    let extend = dict.get(b"Extend").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).map(|v| matches!(v, Object::Boolean(true)))).collect::<Vec<bool>>()).unwrap_or(vec![true,true]);

    let w = size;
    let h = size;
    let mut rgba = vec![0u8; (w*h*4) as usize];

    // Helpers to evaluate color at t 0..1 via function
    let eval_func = |t: f64| -> Option<Vec<f64>> {
        if let Some(ref f) = pdf_func {
            return Some(f.eval(&[t]));
        }
        // If no function, try to use Background as single color.
        if let Some(ref bgc) = bg {
            return Some(bgc.clone());
        }
        None
    };

    for y in 0..h as usize {
        for x in 0..w as usize {
            // map pixel to shading BBox space
            let fx = bbox[0] + (x as f64 + 0.5)/ w as f64 * (bbox[2]-bbox[0]);
            let fy = bbox[1] + (y as f64 + 0.5)/ h as f64 * (bbox[3]-bbox[1]);

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
                        let t = ((fx - x0)*dx + (fy - y0)*dy)/len2;
                        t
                    }
                } else { 0.0 }
            } else {
                // Radial (Type 3): solve for the gradient parameter at (fx,fy).
                if coords.len()>=6 {
                    radial_shading_param(
                        &coords,
                        extend.get(0).copied().unwrap_or(true),
                        extend.get(1).copied().unwrap_or(true),
                        fx, fy,
                    ).unwrap_or(f64::NAN)
                } else { 0.0 }
            };

            // Radial pixels not covered by any circle stay transparent.
            if t.is_nan() {
                continue;
            }

            // Extend handling
            let t_clamped = if t<0.0 {
                if extend.get(0).copied().unwrap_or(true) { 0.0 } else {
                    // background outside
                    // pixel stays background/transparent
                    let idx = (y*w as usize + x)*4;
                    // If bg defined, use it else transparent
                    if let Some(ref bgc) = bg {
                        let cs = color_space_obj.as_ref().unwrap_or(&CsKind::DeviceRGB);
                        if let Some(argb) = eval_cs_to_rgb(doc, cs, bgc, cs_resources) {
                            rgba[idx]= ((argb>>16)&0xFF) as u8;
                            rgba[idx+1]= ((argb>>8)&0xFF) as u8;
                            rgba[idx+2]= (argb &0xFF) as u8;
                            rgba[idx+3]=255;
                        }
                    }
                    continue;
                }
            } else if t>1.0 {
                if extend.get(1).copied().unwrap_or(true) { 1.0 } else {
                    let idx = (y*w as usize + x)*4;
                    if let Some(ref bgc) = bg {
                        let cs = color_space_obj.as_ref().unwrap_or(&CsKind::DeviceRGB);
                        if let Some(argb) = eval_cs_to_rgb(doc, cs, bgc, cs_resources) {
                            rgba[idx]= ((argb>>16)&0xFF) as u8;
                            rgba[idx+1]= ((argb>>8)&0xFF) as u8;
                            rgba[idx+2]= (argb &0xFF) as u8;
                            rgba[idx+3]=255;
                        }
                    }
                    continue;
                }
            } else { t };

            let idx = (y*w as usize + x)*4;
            if let Some(comps) = eval_func(t_clamped) {
                let cs = color_space_obj.as_ref().unwrap_or(&CsKind::DeviceRGB);
                if let Some(argb) = eval_cs_to_rgb(doc, cs, &comps, cs_resources) {
                    rgba[idx]= ((argb>>16)&0xFF) as u8;
                    rgba[idx+1]= ((argb>>8)&0xFF) as u8;
                    rgba[idx+2]= (argb &0xFF) as u8;
                    rgba[idx+3]=255;
                } else {
                    // fallback gray for comps
                    let v = (comps.get(0).copied().unwrap_or(0.0)*255.0) as u8;
                    rgba[idx]=v; rgba[idx+1]=v; rgba[idx+2]=v; rgba[idx+3]=255;
                }
            } else {
                // No function - use background or transparent
                if let Some(ref bgc) = bg {
                    let cs = color_space_obj.as_ref().unwrap_or(&CsKind::DeviceRGB);
                    if let Some(argb) = eval_cs_to_rgb(doc, cs, bgc, cs_resources) {
                        rgba[idx]= ((argb>>16)&0xFF) as u8;
                        rgba[idx+1]= ((argb>>8)&0xFF) as u8;
                        rgba[idx+2]= (argb &0xFF) as u8;
                        rgba[idx+3]=255;
                    }
                }
            }
        }
    }

    // Map bbox to base CTM: we produce an image that covers bbox rectangle
    // CTM to place: translate bbox[0],bbox[1] and scale bboxW, bboxH
    let bw = bbox[2]-bbox[0];
    let bh = bbox[3]-bbox[1];
    // shading CTM: [bw 0 0 bh bbox0 bbox1] * base_ctm
    let shading_mat: Mat = [bw, 0.0, 0.0, bh, bbox[0], bbox[1]];
    let ctm = mat_mul(&shading_mat, base_ctm);
    Some((ctm, w, h, rgba))
}

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

    let w = size as usize;
    let h = size as usize;
    let mut rgba = vec![0u8; w * h * 4];
    for py in 0..h {
        for px in 0..w {
            let u = (px as f64 + 0.5) / w as f64;
            let v = (py as f64 + 0.5) / h as f64;
            let x = dx0 + u * (dx1 - dx0);
            let y = dy0 + v * (dy1 - dy0);
            let comps = func.eval(&[x, y]);
            let idx = (py * w + px) * 4;
            if let Some(argb) = eval_cs_to_rgb(doc, &cs, &comps, cs_resources) {
                rgba[idx] = ((argb >> 16) & 0xFF) as u8;
                rgba[idx + 1] = ((argb >> 8) & 0xFF) as u8;
                rgba[idx + 2] = (argb & 0xFF) as u8;
                rgba[idx + 3] = 255;
            }
        }
    }
    // Image [0,1]^2 -> domain rect -> Matrix -> base CTM.
    let unit_to_domain: Mat = [dx1 - dx0, 0.0, 0.0, dy1 - dy0, dx0, dy0];
    let ctm = mat_mul(&mat_mul(&unit_to_domain, &matrix), base_ctm);
    Some((ctm, size, size, rgba))
}


/// Apply a per-pixel soft-mask alpha (length `w*h`) to an RGBA buffer.
/// Apply a per-pixel soft-mask alpha (length `w*h`) to an RGBA buffer.
pub(crate) fn apply_smask(rgba: &mut [u8], smask: &Option<Vec<u8>>) {
    if let Some(alpha) = smask {
        let n = (rgba.len() / 4).min(alpha.len());
        for i in 0..n {
            rgba[i * 4 + 3] = alpha[i];
        }
    }
}

pub(crate) fn apply_color_key_mask(rgba: &mut [u8], mask_ranges: &Option<Vec<(u8,u8)>>) {
    let ranges = match mask_ranges {
        Some(r) => r,
        None => return,
    };
    if ranges.is_empty() { return; }
    let pixel_count = rgba.len() / 4;
    // ncomp inferred from ranges len: could be 1,3,4
    let ncomp = ranges.len();
    for i in 0..pixel_count {
        let base = i*4;
        let r = rgba[base];
        let g = rgba[base+1];
        let b = rgba[base+2];
        // For 1-comp, use r (gray)
        let transparent = match ncomp {
            1 => {
                let (mn,mx) = ranges[0];
                r >= mn && r <= mx
            }
            3 => {
                let (r0,r1)=ranges.get(0).copied().unwrap_or((0,0));
                let (g0,g1)=ranges.get(1).copied().unwrap_or((0,0));
                let (b0,b1)=ranges.get(2).copied().unwrap_or((0,0));
                r>=r0 && r<=r1 && g>=g0 && g<=g1 && b>=b0 && b<=b1
            }
            _ => {
                // For CMYK or DeviceN, approximate using first 3 or first 1
                // If 4 components, use ranges for each but we only have RGB after conversion; fallback check red channel against first range
                // For simplicity, if any component out-of-range, not transparent; to be conservative we check all that we can
                let mut ok = true;
                // Gray fallback: check r against first
                if ncomp>=1 {
                    let (mn,mx)=ranges[0];
                    if !(r>=mn && r<=mx) { ok=false; }
                }
                ok
            }
        };
        if transparent {
            rgba[base+3]=0;
        }
    }
}

pub(crate) fn read_color_key_mask(doc: &Document, dict: &lopdf::Dictionary) -> Option<Vec<(u8,u8)>> {
    let mask_obj = dict.get(b"Mask").ok().and_then(|o| deref(doc, o))?;
    let arr = match mask_obj {
        Object::Array(a) => a,
        _ => return None,
    };
    if arr.len()%2!=0 { return None; }
    if arr.len()>20 { return None; } // sanity: up to 10 components (DeviceN)
    let mut out = Vec::with_capacity(arr.len()/2);
    for i in 0..arr.len()/2 {
        let mn = num(&arr[i*2]).unwrap_or(0.0);
        let mx = num(&arr[i*2+1]).unwrap_or(0.0);
        // Convert to u8: if value >1.0 treat as 0..255 direct, else 0..1 *255
        let to_u8 = |v: f64| -> u8 {
            if v > 1.0 { v.round().clamp(0.0,255.0) as u8 } else { (v*255.0).round().clamp(0.0,255.0) as u8 }
        };
        let mn_u = to_u8(mn.min(mx));
        let mx_u = to_u8(mn.max(mx));
        out.push((mn_u, mx_u));
    }
    Some(out)
}

pub(crate) fn decode_jpeg_rgba(data: &[u8]) -> Option<(u32,u32,Vec<u8>)> {
    let mut decoder = jpeg_decoder::Decoder::new(Cursor::new(data));
    let pixels = decoder.decode().ok()?;
    let info = decoder.info()?;
    let w = info.width as u32;
    let h = info.height as u32;
    if w==0 || h==0 || w>20000 || h>20000 { return None; }
    let rgba = match info.pixel_format {
        jpeg_decoder::PixelFormat::L8 => {
            // gray -> rgba
            let mut out = vec![0u8; (w*h*4) as usize];
            for i in 0..(w*h) as usize {
                let g = pixels.get(i).copied().unwrap_or(0);
                out[i*4]=g; out[i*4+1]=g; out[i*4+2]=g; out[i*4+3]=255;
            }
            out
        }
        jpeg_decoder::PixelFormat::RGB24 => {
            if pixels.len() < (w*h*3) as usize { return None; }
            let mut out = vec![0u8; (w*h*4) as usize];
            for i in 0..(w*h) as usize {
                out[i*4]=pixels[i*3];
                out[i*4+1]=pixels[i*3+1];
                out[i*4+2]=pixels[i*3+2];
                out[i*4+3]=255;
            }
            out
        }
        jpeg_decoder::PixelFormat::CMYK32 => {
            // CMYK -> RGB
            if pixels.len() < (w*h*4) as usize { return None; }
            let mut out = vec![0u8; (w*h*4) as usize];
            for i in 0..(w*h) as usize {
                let c = pixels[i*4] as f64 /255.0;
                let m = pixels[i*4+1] as f64 /255.0;
                let y = pixels[i*4+2] as f64 /255.0;
                let k = pixels[i*4+3] as f64 /255.0;
                let r = ((1.0 - c)*(1.0 - k)*255.0) as u8;
                let g = ((1.0 - m)*(1.0 - k)*255.0) as u8;
                let b = ((1.0 - y)*(1.0 - k)*255.0) as u8;
                out[i*4]=r; out[i*4+1]=g; out[i*4+2]=b; out[i*4+3]=255;
            }
            out
        }
        _ => { return None; }
    };
    Some((w,h,rgba))
}

pub(crate) fn decode_jpeg_gray(data: &[u8]) -> Option<(u32,u32,Vec<u8>)> {
    let mut decoder = jpeg_decoder::Decoder::new(Cursor::new(data));
    let pixels = decoder.decode().ok()?;
    let info = decoder.info()?;
    let w = info.width as u32;
    let h = info.height as u32;
    if w==0 || h==0 || w>20000 || h>20000 { return None; }
    let gray = match info.pixel_format {
        jpeg_decoder::PixelFormat::L8 => {
            if pixels.len() < (w*h) as usize { return None; }
            pixels[..(w*h) as usize].to_vec()
        }
        jpeg_decoder::PixelFormat::RGB24 => {
            if pixels.len() < (w*h*3) as usize { return None; }
            let mut out = vec![0u8; (w*h) as usize];
            for i in 0..(w*h) as usize {
                let r = pixels[i*3] as u16;
                let g = pixels[i*3+1] as u16;
                let b = pixels[i*3+2] as u16;
                out[i] = ((r*30 + g*59 + b*11)/100) as u8;
            }
            out
        }
        _ => { return None; }
    };
    Some((w,h,gray))
}

// Generic bit unpacker for BPC 2,4,12,16
pub(crate) fn unpack_samples_to_bytes(samples: &[u8], w: usize, h: usize, ncomp: usize, bpc: u32) -> Option<Vec<u8>> {
    // Returns Vec<u8> of size w*h*ncomp with values 0..255 scaled from bpc
    let total_comps = w*ncomp;
    if bpc==8 {
        let need = h*total_comps;
        if samples.len() < need { return None; }
        return Some(samples[..need].to_vec());
    }
    if bpc==1 {
        // 1 bit per component -> 0/255
        let row_bits = total_comps;
        let row_bytes = (row_bits+7)/8;
        if samples.len() < h*row_bytes { return None; }
        let mut out = vec![0u8; h*total_comps];
        for y in 0..h {
            for x in 0..total_comps {
                let byte_idx = y*row_bytes + x/8;
                let bit = (samples[byte_idx] >> (7 - (x%8))) & 1;
                out[y*total_comps + x] = if bit==1 { 255 } else { 0 };
            }
        }
        return Some(out);
    }
    if bpc==2 {
        let row_bits = total_comps *2;
        let row_bytes = (row_bits+7)/8;
        if samples.len() < h*row_bytes { return None; }
        let mut out = vec![0u8; h*total_comps];
        for y in 0..h {
            for x in 0..total_comps {
                let bit_off = x*2;
                let byte_idx = y*row_bytes + bit_off/8;
                let bit_in_byte = bit_off %8; // 0,2,4,6
                let shift = 6 - bit_in_byte;
                let val = (samples[byte_idx] >> shift) & 0x3;
                out[y*total_comps + x] = (val * 85) as u8; // 255/3=85
            }
        }
        return Some(out);
    }
    if bpc==4 {
        let row_bits = total_comps*4;
        let row_bytes = (row_bits+7)/8;
        if samples.len() < h*row_bytes { return None; }
        let mut out = vec![0u8; h*total_comps];
        for y in 0..h {
            for x in 0..total_comps {
                let bit_off = x*4;
                let byte_idx = y*row_bytes + bit_off/8;
                let shift = if bit_off%8==0 {4} else {0};
                let val = (samples[byte_idx] >> shift) & 0xF;
                out[y*total_comps + x] = (val * 17) as u8; // 255/15=17
            }
        }
        return Some(out);
    }
    if bpc==12 || bpc==16 {
        // For 12 and 16, samples are 2 bytes per component (for 12, possibly packed? But we handle unpacked 16-bit or packed 12-bit via bit reader)
        // If bpc==16: row_bytes = total_comps*2
        // If bpc==12: need to handle packing: 2 samples =3 bytes. To simplify, use bit reader for generic
        // Implement bit reader
        let row_bits = total_comps * bpc as usize;
        let row_bytes = (row_bits+7)/8;
        if samples.len() < h*row_bytes { return None; }
        let mut out = vec![0u8; h*total_comps];
        for y in 0..h {
            let row_start = y*row_bytes;
            let row = &samples[row_start..row_start+row_bytes];
            let mut bit_pos = 0usize;
            for x in 0..total_comps {
                // read bpc bits
                let mut val: u32 = 0;
                for _ in 0..bpc {
                    let byte_idx = bit_pos/8;
                    let bit_idx = 7 - (bit_pos%8);
                    let bit = (row[byte_idx] >> bit_idx) &1;
                    val = (val<<1) | bit as u32;
                    bit_pos+=1;
                }
                // scale to 0..255: high byte
                let scaled = if bpc==16 { (val>>8) as u8 } else if bpc==12 { (val>>4) as u8 } else { (val as f64 / ((1<<bpc)-1) as f64 *255.0) as u8 };
                out[y*total_comps + x]=scaled;
            }
        }
        return Some(out);
    }
    None
}

/// Decode an image's `/SMask` (soft mask) into a `w*h` 8-bit alpha buffer,
/// now supporting DCT (JPEG) and JPX and low BPC.
pub(crate) fn read_smask(doc: &Document, dict: &lopdf::Dictionary, w: u32, h: u32) -> Option<Vec<u8>> {
    let sm = dict.get(b"SMask").ok().and_then(|o| deref(doc, o))?;
    let s = match sm {
        Object::Stream(s) => s,
        _ => return None,
    };
    let filters = filter_names(doc, &s.dict);
    let is_dct = filters.iter().any(|f| f=="DCTDecode" || f=="DCT");
    let is_jpx = filters.iter().any(|f| f=="JPXDecode");
    let sw = s.dict.get(b"Width").ok().and_then(num)? as usize;
    let sh = s.dict.get(b"Height").ok().and_then(num)? as usize;
    if sw==0 || sh==0 || sw>20000 || sh>20000 { return None; }
    let sbpc = s.dict.get(b"BitsPerComponent").ok().and_then(num).unwrap_or(8.0) as u32;

    // Get mask data: if JPEG, decode via jpeg
    let data_raw = stream_data(s);
    let mask_bytes: Vec<u8> = if is_dct {
        if let Some((_jw,_jh,gray)) = decode_jpeg_gray(&data_raw) {
            gray
        } else {
            // fallback try to decode as RGBA JPEG and take gray?
            if let Some((_jw,_jh,rgba)) = decode_jpeg_rgba(&data_raw) {
                // take R channel as alpha approx
                rgba.chunks(4).map(|c| c[0]).collect()
            } else {
                return None;
            }
        }
    } else if is_jpx {
        if let Some((jw,jh,rgba)) = jp2::decode(&s.content) {
            // rgba is RGBA, take R as alpha? For JPX smask, it's gray; jp2 decode returns gray in R
            let mut gray = vec![0u8; (jw*jh) as usize];
            for i in 0..(jw*jh) as usize {
                gray[i]=rgba[i*4];
            }
            // resample later if needed, but we already have sw,sh from dict which may differ from decoded? decoded dims should equal sw,sh
            gray
        } else {
            return None;
        }
    } else {
        // For other filters, decompressed content already in data_raw, but need to unpack based on BPC
        let unpacked = unpack_samples_to_bytes(&data_raw, sw, sh, 1, sbpc)?;
        unpacked
    };

    // Now mask_bytes is size sw*sh (grayscale 0..255)
    let (w_us, h_us) = (w as usize, h as usize);
    let mut alpha = vec![255u8; w_us*h_us];
    for y in 0..h_us {
        for x in 0..w_us {
            let sx = x * sw / w_us;
            let sy = y * sh / h_us;
            alpha[y * w_us + x] = mask_bytes.get(sy * sw + sx).copied().unwrap_or(255);
        }
    }
    Some(alpha)
}

#[cfg(test)]
mod radial_tests {
    use super::radial_shading_param;

    // A concentric radial gradient (same center, r0=0..r1=50) must vary with
    // distance from the center — the old axial approximation collapsed it to a
    // single value because the centers coincide.
    #[test]
    fn concentric_radial_varies_with_radius() {
        let coords = [50.0, 50.0, 0.0, 50.0, 50.0, 50.0];
        let center = radial_shading_param(&coords, true, true, 50.0, 50.0).unwrap();
        let mid = radial_shading_param(&coords, true, true, 75.0, 50.0).unwrap();
        let edge = radial_shading_param(&coords, true, true, 100.0, 50.0).unwrap();
        assert!((center - 0.0).abs() < 1e-6, "center s={center}");
        assert!((mid - 0.5).abs() < 1e-6, "mid s={mid}");
        assert!((edge - 1.0).abs() < 1e-6, "edge s={edge}");
        assert!(center < mid && mid < edge);
    }

    // Outside the outer circle with Extend[1]=false there is no covering circle.
    #[test]
    fn outside_without_extend_is_none() {
        let coords = [50.0, 50.0, 0.0, 50.0, 50.0, 50.0];
        assert!(radial_shading_param(&coords, false, false, 200.0, 50.0).is_none());
        // With Extend[1]=true the far point still maps (s>1 allowed).
        let s = radial_shading_param(&coords, false, true, 200.0, 50.0).unwrap();
        assert!(s > 1.0, "expected extended s>1, got {s}");
    }

    // Offset circles (different centers) still resolve on the axis.
    #[test]
    fn offset_circles_resolve_on_axis() {
        let coords = [0.0, 0.0, 10.0, 100.0, 0.0, 10.0];
        // On the segment between centers, near the start circle boundary.
        let s = radial_shading_param(&coords, true, true, 10.0, 0.0).unwrap();
        assert!(s >= 0.0 && s <= 1.0, "s={s}");
    }
}

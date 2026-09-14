/// Decode a mask stream into a gray 8-bit buffer of size sw*sh, attempting
/// compressed codecs (CCITT/JBIG2/DCT/JPX) via the same image pipeline.
/// Returns None only if the mask is truly undecodable.
///
/// The returned buffer is always in "alpha" terms — 255 keeps the base pixel,
/// 0 removes it — with `pol` selecting how a one-bit sample maps onto that.
/// Everything at >=2 bpc is a gray ramp and is polarity-independent.
fn decode_mask_stream_gray(
    doc: &Document,
    s: &lopdf::Stream,
    sw: usize,
    sh: usize,
    pol: MaskPolarity,
) -> Option<Vec<u8>> {
    // A decoded mask stream yields DeviceGray SAMPLES (0 = black, 255 = white) for
    // every codec below. Mapping a sample onto alpha is where the two conventions
    // diverge, and sharing one mapping between them is what inverted 1-bit soft masks:
    //   /SMask (§11.6.5.3): the sample IS the alpha, so white = opaque.
    //   stencil /Mask (§8.9.6.4 + §8.9.6.2): sample 1 (white) = masked out.
    let to_alpha = |sample: u8| -> u8 {
        match pol {
            MaskPolarity::GrayLuminance => sample,
            MaskPolarity::StencilMaskedIf1 => 255 - sample,
        }
    };
    // "Never masked", used where a codec produced no data. Correct in both
    // conventions because it bypasses `to_alpha`.
    const KEEP: u8 = 255;
    // A codec's own raster dimensions can differ from the mask dict's /Width x /Height
    // (a truncated codestream, or a dict that simply disagrees). Both callers resample
    // the returned buffer using `sw` as the ROW STRIDE, so handing back a differently
    // sized buffer shears the mask diagonally instead of scaling it. Fit it here.
    let fit = |buf: Vec<u8>, bw: usize, bh: usize| -> Vec<u8> {
        if bw == sw && bh == sh {
            return buf;
        }
        if bw == 0 || bh == 0 || sw == 0 || sh == 0 {
            return vec![KEEP; sw * sh];
        }
        let mut out = vec![KEEP; sw * sh];
        for y in 0..sh {
            let sy = (y * bh / sh).min(bh - 1);
            for x in 0..sw {
                let sx = (x * bw / sw).min(bw - 1);
                out[y * sw + x] = buf.get(sy * bw + sx).copied().unwrap_or(KEEP);
            }
        }
        out
    };
    // Try filter-aware decode chain first
    let specs = filters::filter_specs_from_dict(doc, &s.dict);
    let has_ccitt = specs.iter().any(|(k, _)| *k == filters::FilterKind::Ccitt);
    let has_jbig2 = specs.iter().any(|(k, _)| *k == filters::FilterKind::Jbig2);
    let has_dct = specs.iter().any(|(k, _)| *k == filters::FilterKind::Dct);
    let has_jpx = specs.iter().any(|(k, _)| *k == filters::FilterKind::Jpx);
    let sbpc = s.dict.get(b"BitsPerComponent").ok().and_then(|o| deref(doc, o)).and_then(num).unwrap_or(1.0) as u32;

    if has_jbig2 {
        // Attempt JBIG2 path
        let raw = s.content.clone();
        let chain = filters::decode_stream_chain(raw.clone(), &specs, doc).unwrap_or(raw.clone());
        // /JBIG2Globals holds the symbol dictionary shared across pages. Without it a
        // JBIG2 image decodes to nothing, and a failed JBIG2 mask renders as a silently
        // transparent region, so this is the single most common cause of total failure.
        // `jbig2_globals` is the same resolution the primary image path uses, so the two
        // cannot disagree about which shapes of /DecodeParms carry the globals.
        let globals = jbig2_globals(doc, &s.dict, &specs);
        if globals.is_none() {
            image_warn!("JBIG2 mask {}x{}: no /JBIG2Globals found - decode may fail", sw, sh);
        }
        if let Some((jw, jh, rgba)) = jbig2::decode_jbig2(&chain, globals.as_deref(), sw as u32, sh as u32)
            .or_else(|| jbig2::decode_jbig2(&s.content, globals.as_deref(), sw as u32, sh as u32))
        {
            // jbig2.rs emits DeviceGray samples (black = 0, white = 255) with alpha 255
            // only on pixels it actually decoded. alpha == 0 therefore means "never
            // decoded" (a truncated codestream leaves whole trailing rows untouched),
            // and those must not mask the base image at all.
            let mut gray = Vec::with_capacity((jw * jh) as usize);
            for chunk in rgba.chunks(4) {
                gray.push(if chunk.get(3).copied().unwrap_or(0) == 0 {
                    KEEP
                } else {
                    to_alpha(chunk[0])
                });
            }
            // Resample if jw*jh != sw*sh
            if jw as usize == sw && jh as usize == sh {
                return Some(gray);
            }
            // Nearest resample here for fallback then bilinear later maps final
            let mut out = vec![KEEP; sw * sh];
            for y in 0..sh {
                for x in 0..sw {
                    let sx = x * (jw as usize) / sw.max(1);
                    let sy = y * (jh as usize) / sh.max(1);
                    out[y * sw + x] = gray.get(sy * (jw as usize) + sx).copied().unwrap_or(KEEP);
                }
            }
            return Some(out);
        }
        // The bytes are a JBIG2 codestream we could not decode. `stream_data_with_doc`
        // hands image codecs back STILL ENCODED, so falling through to the raw-bit path
        // below would unpack the codestream itself as mask samples: noise, and for an
        // /SMask mostly alpha 0, i.e. the base image disappears. Leave it unmasked.
        image_warn!("mask /SMask or /Mask: JBIG2 {}x{} decode failed - leaving the image unmasked", sw, sh);
        return None;
    }
    if has_ccitt {
        let params = filters::parse_ccitt_params(doc, specs.iter().find(|(k, _)| *k == filters::FilterKind::Ccitt).and_then(|(_, d)| d.as_ref()));
        let raw = s.content.clone();
        let chain = filters::decode_stream_chain(raw.clone(), &specs, doc).unwrap_or(raw);
        if let Some(packed) = filters::decode_ccitt(&chain, sw as u32, sh as u32, &params) {
            // `decode_ccitt` emits one-bit samples in the polarity `/BlackIs1` selects
            // (§7.4.6 Table 11, default false); as DeviceGray those are read with bit 0
            // BLACK and bit 1 white - the same convention the main CCITT image path uses.
            // This branch previously treated bit 1 as black, which inverted every
            // CCITT-encoded soft mask.
            //
            // The raster's geometry is /Columns x /Rows, NOT the mask dictionary's
            // /Width x /Height: Table 11 defaults /Columns to 1728 regardless of
            // /Width. Striding by `sw` therefore sheared the mask diagonally whenever
            // the two disagreed - the CCITT branch of `extract_image_inner` already
            // sizes its output from /Columns, so the two consumers of the same filter
            // disagreed about the row length. `fit` scales the result onto the
            // caller's grid.
            let cols = if params.columns > 0 { params.columns as usize } else { sw };
            let rows = if params.rows > 0 { params.rows as usize } else { sh };
            let row_bytes = cols.div_ceil(8);
            let mut gray = vec![KEEP; cols * rows];
            for y in 0..rows {
                for x in 0..cols {
                    let byte = packed.get(y * row_bytes + x / 8).copied().unwrap_or(0xFF);
                    let bit = (byte >> (7 - (x % 8))) & 1;
                    gray[y * cols + x] = to_alpha(if bit == 1 { 255 } else { 0 });
                }
            }
            return Some(fit(gray, cols, rows));
        }
        image_warn!("mask /SMask or /Mask: CCITT {}x{} decode failed - leaving the image unmasked", sw, sh);
        return None;
    }
    if has_dct {
        // NOT `stream_data`: that is lopdf's decoder, which implements only
        // Flate/LZW/ASCII85 and returns Err for DCTDecode — and `stream_data` maps that
        // Err to an EMPTY Vec whenever a /Filter is present. So every DCT-compressed
        // /SMask decoded to nothing, fell through to the generic unpack path below,
        // read absent bytes as sample 0, and produced alpha 0 for every pixel: the base
        // image became completely invisible. A JPEG soft mask beside a JPEG image is the
        // commonest /SMask there is, so this hid whole photographs. `stream_data_with_doc`
        // passes image codecs through untouched and unwraps any Ascii/Flate wrapper.
        let raw = stream_data_with_doc(doc, s);
        if let Some((jw, jh, gray)) = decode_jpeg_gray(&raw) {
            let fitted = fit(gray, jw as usize, jh as usize);
            return Some(fitted.into_iter().map(to_alpha).collect());
        }
        if let Some((jw, jh, rgba)) = decode_jpeg_rgba(&raw) {
            let gray: Vec<u8> = rgba.chunks(4).map(|c| c[0]).collect();
            let fitted = fit(gray, jw as usize, jh as usize);
            return Some(fitted.into_iter().map(to_alpha).collect());
        }
        image_warn!(
            "mask /SMask or /Mask: DCT {}x{} decode failed ({} bytes) - leaving the image unmasked",
            sw, sh, raw.len()
        );
        return None;
    }
    if has_jpx {
        // Same reasoning as the DCT branch: use the project's chain so an
        // Ascii85/Flate-wrapped JPX codestream is unwrapped first.
        let raw = stream_data_with_doc(doc, s);
        if let Some((jw, jh, rgba)) = jp2::decode(&raw) {
            let gray: Vec<u8> = rgba.chunks(4).map(|c| c[0]).collect();
            let fitted = fit(gray, jw as usize, jh as usize);
            return Some(fitted.into_iter().map(to_alpha).collect());
        }
        image_warn!(
            "mask /SMask or /Mask: JPX {}x{} decode failed ({} bytes) - leaving the image unmasked",
            sw, sh, raw.len()
        );
        return None;
    }
    // Plain bit path (1-bit masks without compression). The raw bit IS the DeviceGray
    // sample: 1 = white. Mapping bit 1 to black here inverted every uncompressed
    // 1-bit /SMask, making the image opaque exactly where it should have been clear.
    let data = stream_data_with_doc(doc, s);
    // No bytes at all means no mask. Falling through with an empty buffer made the
    // unpacker read every sample as 0, which for a /SMask is alpha 0 — an undecodable
    // mask deleted the whole image. `None` here leaves the base image unmasked, which
    // is wrong in a way you can see and fix rather than wrong by omission.
    if data.is_empty() {
        image_warn!(
            "mask stream {}x{} (bpc {}) decoded to 0 bytes - leaving the image unmasked",
            sw, sh, sbpc
        );
        return None;
    }
    if sbpc == 1 {
        let row_bytes = sw.div_ceil(8);
        let mut gray = vec![KEEP; sw * sh];
        for y in 0..sh {
            for x in 0..sw {
                if y * row_bytes + x / 8 >= data.len() {
                    break;
                }
                let byte = data[y * row_bytes + x / 8];
                let bit = (byte >> (7 - (x % 8))) & 1;
                gray[y * sw + x] = to_alpha(if bit == 1 { 255 } else { 0 });
            }
        }
        return Some(gray);
    }
    // Generic low-BPC unpack path: >=2 bpc is a gray ramp, so the sample value maps
    // onto alpha through the same `to_alpha` as the one-bit paths.
    let unpacked = unpack_samples_to_bytes(&data, sw, sh, 1, sbpc).or_else(|| {
        let bpp = sbpc as usize;
        let row_bits = sw * bpp;
        let row_bytes = row_bits.div_ceil(8);
        let mut gray = vec![255u8; sw * sh];
        for y in 0..sh {
            let base = y * row_bytes;
            if base + row_bytes > data.len() {
                break;
            }
            for x in 0..sw {
                gray[y * sw + x] = data.get(base + x).copied().unwrap_or(255);
            }
        }
        Some(gray)
    })?;
    Some(unpacked.into_iter().map(to_alpha).collect())
}

/// Decode an explicit stencil `/Mask` image (an `ImageMask` XObject) into a
/// `w*h` 8-bit alpha buffer for the base image: mask sample 1 => masked
/// (alpha 0), 0 => painted (alpha 255), honoring the mask's `/Decode`. Scaled to
/// the base image's dimensions via bilinear filtering. No longer bails on
/// compressed codecs (CCITT/JBIG2/DCT/JPX) — those are decoded via
/// `decode_mask_stream_gray`.
pub(crate) fn read_explicit_mask(doc: &Document, dict: &lopdf::Dictionary, w: u32, h: u32) -> Option<Vec<u8>> {
    let m = dict.get(b"Mask").ok().and_then(|o| deref(doc, o))?;
    let s = match m {
        Object::Stream(s) => s,
        _ => return None,
    };
    if !dict_true(doc, &s.dict, b"ImageMask") {
        // §8.9.6.4 requires an explicit /Mask stream to BE an image mask. Without the
        // flag we cannot know the sample polarity, so the mask is skipped and the base
        // image is left unmasked — visible, but not silently so.
        image_warn!("/Mask stream is not an /ImageMask - mask ignored, image left unmasked");
        return None;
    }
    let sw = s.dict.get(b"Width").ok().and_then(|o| deref(doc, o)).and_then(num)? as usize;
    let sh = s.dict.get(b"Height").ok().and_then(|o| deref(doc, o)).and_then(num)? as usize;
    if sw == 0 || sh == 0 || sw > 20000 || sh > 20000 {
        image_warn!("/Mask stream {}x{} outside 1..20000 - mask ignored", sw, sh);
        return None;
    }
    // A per-dimension cap still admits 20000x20000, and `decode_mask_stream_gray`
    // allocates sw*sh bytes plus a resample buffer. Bound it the same way the base
    // image is bounded; a mask that big cannot be resolving anything anyway.
    if sw.saturating_mul(sh) > MAX_IMAGE_PIXELS {
        image_warn!("/Mask stream {}x{} over the {} pixel budget - mask ignored", sw, sh, MAX_IMAGE_PIXELS);
        return None;
    }
    let invert = matches!(
        s.dict.get(b"Decode").ok().and_then(|o| deref(doc, o)),
        Some(Object::Array(a)) if a.first().and_then(num) == Some(1.0)
    );
    let mut mask_alpha =
        decode_mask_stream_gray(doc, s, sw, sh, MaskPolarity::StencilMaskedIf1)?;
    // `/Decode [1 0]` reverses the stencil sense (§8.9.6.2). Apply it ONCE, here,
    // before any resampling: the resampled path used to have `invert` and non-invert
    // arms that were both the identity, so an inverted mask at any size other than
    // the base image's silently showed what should have been hidden.
    if invert {
        for v in mask_alpha.iter_mut() {
            *v = 255 - *v;
        }
    }
    let (w_us, h_us) = (w as usize, h as usize);
    if sw == w_us && sh == h_us {
        return Some(mask_alpha);
    }
    let mut alpha = vec![255u8; w_us * h_us];
    for y in 0..h_us {
        for x in 0..w_us {
            let sx = if w_us > 1 { x as f64 * (sw - 1) as f64 / (w_us - 1).max(1) as f64 } else { 0.0 };
            let sy = if h_us > 1 { y as f64 * (sh - 1) as f64 / (h_us - 1).max(1) as f64 } else { 0.0 };
            alpha[y * w_us + x] = bilinear_mask_sample(&mask_alpha, sw, sh, sx, sy);
        }
    }
    Some(alpha)
}

/// Read an SMask `/Matte` color as an RGB triple (0..1), if present. The matte
/// is given in the base image's colorspace; components are interpreted by arity
/// (gray/RGB/CMYK), which is sufficient for un-premultiplication.
pub(crate) fn read_matte(doc: &Document, dict: &lopdf::Dictionary) -> Option<[f64; 3]> {
    let sm = dict.get(b"SMask").ok().and_then(|o| deref(doc, o))?;
    // lopdf's `as_dict()` matches ONLY Object::Dictionary, never a Stream's dict, so
    // this returned None for every /SMask (which is always a stream) and /Matte was
    // dead code — `apply_matte` never ran.
    let smd = match &sm {
        Object::Stream(s) => &s.dict,
        Object::Dictionary(d) => d,
        _ => return None,
    };
    let arr = smd.get(b"Matte").ok().and_then(|o| deref(doc, o))?;
    let comps: Vec<f64> = arr.as_array().ok()?.iter().filter_map(num).collect();
    let rgb = match comps.len() {
        1 => [comps[0], comps[0], comps[0]],
        3 => [comps[0], comps[1], comps[2]],
        4 => {
            let (c, m, y, k) = (comps[0], comps[1], comps[2], comps[3]);
            [(1.0 - c) * (1.0 - k), (1.0 - m) * (1.0 - k), (1.0 - y) * (1.0 - k)]
        }
        _ => return None,
    };
    Some(rgb)
}

/// Un-premultiply an RGBA buffer whose colors were premultiplied against a
/// `/Matte` background, using the already-applied SMask alpha in `rgba`.
/// `c = matte + (c' - matte) / alpha`.
pub(crate) fn apply_matte(rgba: &mut [u8], matte: [f64; 3]) {
    let m = [matte[0] * 255.0, matte[1] * 255.0, matte[2] * 255.0];
    for px in rgba.chunks_exact_mut(4) {
        let a = px[3] as f64 / 255.0;
        if a <= 0.0 { continue; }
        for ch in 0..3 {
            let cp = px[ch] as f64;
            let un = m[ch] + (cp - m[ch]) / a;
            px[ch] = un.round().clamp(0.0, 255.0) as u8;
        }
    }
}

/// Decode an image's `/SMask` into w*h alpha via unified mask decoder (handles all filters)
pub(crate) fn read_smask(doc: &Document, dict: &lopdf::Dictionary, w: u32, h: u32) -> Option<Vec<u8>> {
    let sm = dict.get(b"SMask").ok().and_then(|o| deref(doc, o))?;
    let s = match sm {
        Object::Stream(s) => s,
        _ => return None,
    };
    let sw = s.dict.get(b"Width").ok().and_then(|o| deref(doc, o)).and_then(num).unwrap_or(w as f64) as usize;
    let sh = s.dict.get(b"Height").ok().and_then(|o| deref(doc, o)).and_then(num).unwrap_or(h as f64) as usize;
    if sw == 0 || sh == 0 || sw > 20000 || sh > 20000 {
        image_warn!("/SMask stream {}x{} outside 1..20000 - mask ignored", sw, sh);
        return None;
    }
    if sw.saturating_mul(sh) > MAX_IMAGE_PIXELS {
        image_warn!("/SMask stream {}x{} over the {} pixel budget - mask ignored", sw, sh, MAX_IMAGE_PIXELS);
        return None;
    }
    // P0 fix critical #1: previously DCT/JPX only; now uses unified decoder for all filters
    let mut gray = decode_mask_stream_gray(doc, s, sw, sh, MaskPolarity::GrayLuminance)?;
    // Honor the SMask's own /Decode [1 0], which inverts the alpha ramp.
    if matches!(s.dict.get(b"Decode").ok().and_then(|o| deref(doc, o)), Some(Object::Array(a)) if a.first().and_then(num) == Some(1.0)) {
        for v in gray.iter_mut() { *v = 255 - *v; }
    }
    // Bilinear resample sw*sh -> w*h
    let (w_us, h_us) = (w as usize, h as usize);
    if sw == w_us && sh == h_us { return Some(gray); }
    let mut alpha = vec![255u8; w_us * h_us];
    for y in 0..h_us {
        for x in 0..w_us {
            let sx = if w_us > 1 { x as f64 * (sw - 1) as f64 / (w_us - 1).max(1) as f64 } else { 0.0 };
            let sy = if h_us > 1 { y as f64 * (sh - 1) as f64 / (h_us - 1).max(1) as f64 } else { 0.0 };
            alpha[y * w_us + x] = bilinear_mask_sample(&gray, sw, sh, sx, sy);
        }
    }
    Some(alpha)
}

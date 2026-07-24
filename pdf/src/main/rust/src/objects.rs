use crate::*;

/// Numeric value of an integer or real object, else `None`.
pub(crate) fn num(obj: &Object) -> Option<f64> {
    match obj {
        Object::Integer(i) => Some(*i as f64),
        Object::Real(r) => Some(*r as f64),
        _ => None,
    }
}

/// Follow a chain of references to the underlying object.
pub(crate) fn deref<'a>(doc: &'a Document, obj: &'a Object) -> Option<&'a Object> {
    match doc.dereference(obj) {
        Ok((_, o)) => Some(o),
        Err(_) => None,
    }
}

/// Decoded stream bytes, falling back to the raw content when the stream has no
/// `/Filter` (lopdf's `decompressed_content` errors instead of returning raw).
/// This version is legacy without Document context (used for ToUnicode cmap and font files).
pub(crate) fn stream_data(s: &lopdf::Stream) -> Vec<u8> {
    s.decompressed_content().unwrap_or_else(|_| s.content.clone())
}

/// Extended decoder that uses Document context to handle filter chains case-insensitively,
/// including ASCIIHex, ASCII85, RunLength, LZW (EarlyChange), Flate with PNG predictors,
/// CCITT and JBIG2 (passthrough).
pub(crate) fn decode_stream_content(doc: &Document, dict: &Dictionary, raw: &[u8]) -> Vec<u8> {
    // First try lopdf's native chain (Flate, LZW, ASCII85)
    // But if there are unsupported filters (RunLength, ASCIIHex, CCITT, JBIG2, DCT, JPX) it will error,
    // so we attempt our own chain decode using filters module.
    let specs = filters::filter_specs_from_dict(doc, dict);
    if !specs.is_empty() {
        // If specs contain only DCT/JPX/JBIG2, we return raw (compressed image)
        let has_compressed_only = specs.iter().all(|(k,_)| matches!(k, filters::FilterKind::Dct | filters::FilterKind::Jpx | filters::FilterKind::Jbig2 | filters::FilterKind::Crypt));
        if has_compressed_only {
            return raw.to_vec();
        }
        if let Some(decoded) = filters::decode_stream_chain(raw.to_vec(), &specs, doc) {
            // If after chain we still have compressed image filters (DCT/JPX/JBIG2) trailing? Our chain stops, returns current data.
            // That's sufficient for further unpacking.
            return decoded;
        }
        // fallback to lopdf attempt
    }

    // Try decompressed_content if possible (covers Flate/LZW/ASCII85)
    // Construct temporary Stream for lopdf helper
    let temp = Stream::new(dict.clone(), raw.to_vec());
    if let Ok(d) = temp.decompressed_content() {
        return d;
    }
    raw.to_vec()
}

pub(crate) fn stream_data_with_doc(doc: &Document, s: &Stream) -> Vec<u8> {
    decode_stream_content(doc, &s.dict, &s.content)
}

// ---------------------------------------------------------------------------
// Fonts + ToUnicode
// ---------------------------------------------------------------------------


// Fonts + ToUnicode
// ---------------------------------------------------------------------------

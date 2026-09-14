use crate::*;

/// Field type + name inherited through the widget's `/Parent` chain.
pub(crate) fn field_attr<'a>(doc: &'a Document, mut id: ObjectId, key: &[u8]) -> Option<&'a Object> {
    for _ in 0..16 {
        let dict = doc.get_dictionary(id).ok()?;
        if let Ok(v) = dict.get(key) {
            return Some(v);
        }
        id = dict.get(b"Parent").ok().and_then(|o| o.as_reference().ok())?;
    }
    None
}

/// A numeric field key resolved as §12.7.3.1 requires for the entries it marks
/// "Optional; inheritable": the widget's own value if it has one, otherwise the
/// nearest ancestor's up the `/Parent` chain.
fn inherited_num(doc: &Document, id: ObjectId, key: &[u8]) -> Option<f64> {
    field_attr(doc, id, key)
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(num)
}

/// The terminal field a widget belongs to: the nearest dictionary at or above it
/// carrying `/FT` (§12.7.3.1). `/V`, `/I`, `/Ff`, `/Q` and `/MaxLen` are keys of
/// THAT field, so writing them on the clicked widget (when the widget is a
/// separate `/Kids` entry) leaves every sibling widget stale, and writing them on
/// a grouping ancestor above the terminal field puts them out of reach. Bounded
/// so a malformed cyclic `/Parent` chain terminates; falls back to the widget
/// itself when nothing in the chain declares `/FT`.
pub(crate) fn terminal_field_id(doc: &Document, id: ObjectId) -> ObjectId {
    let mut cur = id;
    for _ in 0..16 {
        let dict = match doc.get_dictionary(cur) {
            Ok(d) => d,
            Err(_) => break,
        };
        if dict.get(b"FT").is_ok() {
            return cur;
        }
        match dict.get(b"Parent").ok().and_then(|o| o.as_reference().ok()) {
            Some(p) if p != cur => cur = p,
            _ => break,
        }
    }
    id
}

/// The interactive form dictionary (`/Root /AcroForm`).
fn acroform(doc: &Document) -> Option<&Dictionary> {
    doc.catalog()
        .ok()?
        .get(b"AcroForm")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
}

/// A variable-text field's `/Q` quadding (0 left, 1 centred, 2 right): the
/// field's own, inheritable up `/Parent` (§12.7.4.3 Table 222), else the
/// AcroForm document-wide default (§12.7.2 Table 218).
fn field_quadding(doc: &Document, id: ObjectId) -> i64 {
    quadding_of(doc, field_attr(doc, id, b"Q"))
}

/// `field_quadding` given the field's own `/Q` already resolved, so the id-based
/// and dictionary-based lookups cannot drift apart on the AcroForm default.
fn quadding_of(doc: &Document, own: Option<&Object>) -> i64 {
    own.and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(num)
        .or_else(|| {
            acroform(doc)
                .and_then(|af| af.get(b"Q").ok())
                .and_then(|o| deref(doc, o).or(Some(o)))
                .and_then(num)
        })
        .unwrap_or(0.0) as i64
}

/// The pieces of a `/DA` default appearance string that a generated field
/// appearance needs (§12.7.3.3: "a sequence of valid page-content graphics or
/// text state operators ... defining such properties as the field's text size
/// and colour").
pub(crate) struct DefaultAppearance {
    /// Resource name given to `Tf`, without the leading slash. `None` when the
    /// string names no font or names one we refuse to trust.
    pub font: Option<Vec<u8>>,
    /// Size given to `Tf`. ZERO is not "invisible": §12.7.4.3 makes it mean
    /// AUTO-SIZE to fit the field, so callers must substitute their own size.
    pub size: f64,
    /// Fill colour from `g` / `rg` / `k`, defaulting to black as the previous
    /// hardcoded `0 0 0 rg` did.
    pub argb: u32,
}

impl Default for DefaultAppearance {
    fn default() -> Self {
        DefaultAppearance { font: None, size: 0.0, argb: 0xFF00_0000 }
    }
}

/// Whether a `/DA` font name is safe to interpolate into a generated content
/// stream. The name is written straight into `/<name> <size> Tf`, so anything
/// outside the regular-character set could close the operand and inject
/// operators (§7.3.5 restricts names to regular characters anyway).
fn safe_resource_name(n: &[u8]) -> bool {
    !n.is_empty()
        && n.len() <= 127
        && n.iter().all(|c| c.is_ascii_alphanumeric() || matches!(c, b'_' | b'.' | b'+' | b'-'))
}

/// Parse the font name, size and fill colour out of a `/DA` string.
///
/// Only the fill-colour operators are read: a generated field appearance paints
/// glyphs in text render mode 0, which uses the fill colour, so `G`/`RG`/`K`
/// would have no effect. Later operators win, matching content-stream semantics.
pub(crate) fn parse_da(da: &[u8]) -> DefaultAppearance {
    let s = String::from_utf8_lossy(da);
    let toks: Vec<&str> = s.split_whitespace().collect();
    let mut out = DefaultAppearance::default();
    let n = |t: &str| t.parse::<f64>().ok().filter(|v| v.is_finite());
    for (i, t) in toks.iter().enumerate() {
        match *t {
            "Tf" if i >= 2 => {
                if let Some(v) = n(toks[i - 1]) {
                    // Clamped rather than rejected: a negative size is malformed,
                    // and an absurd one would only produce a huge clipped glyph.
                    out.size = v.clamp(0.0, 1000.0);
                }
                out.font = toks[i - 2]
                    .strip_prefix('/')
                    .map(|f| f.as_bytes())
                    .filter(|f| safe_resource_name(f))
                    .map(|f| f.to_vec());
            }
            "g" if i >= 1 => {
                if let Some(v) = n(toks[i - 1]) {
                    out.argb = gray_to_argb(v.clamp(0.0, 1.0));
                }
            }
            "rg" if i >= 3 => {
                if let (Some(r), Some(g), Some(b)) = (n(toks[i - 3]), n(toks[i - 2]), n(toks[i - 1])) {
                    out.argb = rgb_to_argb(r.clamp(0.0, 1.0), g.clamp(0.0, 1.0), b.clamp(0.0, 1.0));
                }
            }
            "k" if i >= 4 => {
                if let (Some(c), Some(m), Some(y), Some(k)) =
                    (n(toks[i - 4]), n(toks[i - 3]), n(toks[i - 2]), n(toks[i - 1]))
                {
                    out.argb = cmyk_to_argb(
                        c.clamp(0.0, 1.0), m.clamp(0.0, 1.0), y.clamp(0.0, 1.0), k.clamp(0.0, 1.0),
                    );
                }
            }
            _ => {}
        }
    }
    out
}

/// A widget's effective `/DA`: the field's own (inheritable through `/Parent`),
/// else the document-wide AcroForm default (§12.7.2 Table 218).
pub(crate) fn field_da(doc: &Document, id: ObjectId) -> DefaultAppearance {
    da_of(doc, field_attr(doc, id, b"DA"))
}

/// `field_da` given the field's own `/DA` already resolved, shared with the
/// dictionary-based lookup the renderer uses.
fn da_of(doc: &Document, own: Option<&Object>) -> DefaultAppearance {
    let own = own
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(|o| o.as_str().ok());
    if let Some(s) = own {
        return parse_da(s);
    }
    acroform(doc)
        .and_then(|af| af.get(b"DA").ok())
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(|o| o.as_str().ok())
        .map(parse_da)
        .unwrap_or_default()
}

/// Resources for a generated field appearance, plus the resource name to write
/// into `Tf`. Resolves a `/DA` font name against the AcroForm `/DR /Font`
/// dictionary (§12.7.3.3) and falls back to the Helvetica substitute otherwise.
///
/// A font is adopted only when the generated content can actually address it.
/// The value is written as a plain literal string, so a composite (`Type0`) font
/// — addressed by multi-byte CIDs — would render it as garbage, and a font with
/// a `/Differences` or symbolic encoding would remap the bytes to unrelated
/// glyphs. Both are strictly worse than the substitute, so in those cases the
/// substitute is kept and only the `/DA` size and colour are honoured.
fn da_font_resources(doc: &Document, name: Option<&[u8]>) -> (Dictionary, Vec<u8>) {
    let fallback = (helvetica_resources(), b"F1".to_vec());
    let name = match name {
        Some(n) if safe_resource_name(n) => n,
        _ => return fallback,
    };
    let entry = acroform(doc)
        .and_then(|af| af.get(b"DR").ok())
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|dr| dr.get(b"Font").ok())
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|fonts| fonts.get(name).ok());
    let entry = match entry {
        Some(e) => e,
        None => return fallback,
    };
    let font = match deref(doc, entry).and_then(|o| o.as_dict().ok()) {
        Some(d) => d,
        None => return fallback,
    };
    let simple = matches!(
        font.get(b"Subtype").ok().and_then(|o| o.as_name().ok()),
        Some(b"Type1") | Some(b"TrueType") | Some(b"MMType1")
    );
    if !simple || !latin_text_encoding(doc, font) {
        return fallback;
    }
    let mut res = helvetica_resources();
    if let Ok(Object::Dictionary(fonts)) = res.get_mut(b"Font") {
        fonts.set(name.to_vec(), entry.clone());
    }
    (res, name.to_vec())
}

/// Whether a simple font's `/Encoding` maps single Latin-1-ish bytes to the
/// glyphs they name, so a literal string written from `decode_pdf_text` output
/// lands on the right glyphs. Absent (the font's built-in encoding) counts,
/// since for the standard text faces that is StandardEncoding.
fn latin_text_encoding(doc: &Document, font: &Dictionary) -> bool {
    const OK: [&[u8]; 3] = [b"WinAnsiEncoding", b"MacRomanEncoding", b"StandardEncoding"];
    let enc = match font.get(b"Encoding").ok().and_then(|o| deref(doc, o).or(Some(o))) {
        None => return true,
        Some(e) => e,
    };
    match enc {
        Object::Name(n) => OK.contains(&n.as_slice()),
        Object::Dictionary(d) => {
            d.get(b"Differences").is_err()
                && d.get(b"BaseEncoding")
                    .ok()
                    .and_then(|o| o.as_name().ok())
                    .map(|n| OK.contains(&n))
                    .unwrap_or(true)
        }
        _ => false,
    }
}

/// Resolve a GoTo destination to a 0-based page index, or -1.
pub(crate) fn resolve_dest_page(doc: &Document, d: &Object, page_of: &HashMap<ObjectId, i32>) -> i32 {
    let d = deref(doc, d).unwrap_or(d);
    if let Object::Array(a) = d {
        if let Some(first) = a.first() {
            if let Ok(id) = first.as_reference() {
                return *page_of.get(&id).unwrap_or(&-1);
            }
        }
    }
    -1
}

/// Serialize link annotations for a page: rect (displayed space), destination
/// page (-1 if none), and URI (empty if none).
pub(crate) fn list_links(handle: i64, page_index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get(&handle)?;
    let page_id = nth_page_id(doc, page_index)?;
    let base = page_base_matrix(doc, page_id);
    let mut page_of: HashMap<ObjectId, i32> = HashMap::new();
    for (n, id) in doc.get_pages() {
        page_of.insert(id, (n as i32) - 1);
    }
    let mut records: Vec<([f64; 4], i32, String)> = Vec::new();
    if let Some(Object::Array(annots)) = doc
        .get_dictionary(page_id)
        .ok()
        .and_then(|d| d.get(b"Annots").ok())
        .and_then(|o| deref(doc, o))
    {
        for a in annots {
            // §12.5.2 does not require /Annots entries to be indirect, and
            // `render_annotations` already derefs either form — so a direct
            // dictionary PAINTED but was invisible here, leaving a link the user
            // could see and not tap. Unlike `list_form_fields` and
            // `list_annotations`, these records carry no object id (rect,
            // destination page, URI), so there is no identity to invent.
            let dict = match deref(doc, a).and_then(|o| o.as_dict().ok()) {
                Some(d) => d,
                None => continue,
            };
            if dict.get(b"Subtype").ok().and_then(|o| deref(doc, o).or(Some(o))).and_then(|o| o.as_name().ok()) != Some(b"Link".as_ref()) {
                continue;
            }
            let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                Some(r) => {
                    let n = normalize_rect(r);
                    let (x0, y0) = transform(&base, n[0], n[1]);
                    let (x1, y1) = transform(&base, n[2], n[3]);
                    normalize_rect([x0, y0, x1, y1])
                }
                None => continue,
            };
            let mut dest_page = -1i32;
            let mut uri = String::new();
            if let Some(action) = dict.get(b"A").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok()) {
                let s = action.get(b"S").ok().and_then(|o| o.as_name().ok());
                if s == Some(b"URI".as_ref()) {
                    if let Ok(u) = action.get(b"URI").and_then(|o| o.as_str()) {
                        uri = String::from_utf8_lossy(u).into_owned();
                    }
                } else if s == Some(b"GoTo".as_ref()) {
                    if let Ok(d) = action.get(b"D") {
                        // §12.6.4.2: a GoTo action's /D "shall be" a name, a byte
                        // string or an array — the first two being named
                        // destinations resolved through /Dests or the /Names
                        // name tree (§12.3.2.3). Accepting only the array form
                        // left dest_page at -1, and the record filter below then
                        // dropped the link entirely, so every named-destination
                        // link in the document was untappable.
                        dest_page = resolve_dest(doc, deref(doc, d).unwrap_or(d), &page_of);
                    }
                } else if s == Some(b"GoToR".as_ref()) {
                    if let Ok(d) = action.get(b"D") {
                        dest_page = resolve_dest_page(doc, d, &page_of);
                    }
                    if dest_page < 0 {
                        if let Some(f) = action
                            .get(b"F")
                            .ok()
                            .and_then(|o| deref(doc, o).or(Some(o)))
                            .and_then(|o| o.as_str().ok())
                        {
                            uri = String::from_utf8_lossy(f).into_owned();
                        }
                    }
                } else if s == Some(b"Launch".as_ref()) {
                    if let Some(f_obj) = action
                        .get(b"F")
                        .ok()
                        .and_then(|o| deref(doc, o).or(Some(o)))
                        .cloned()
                    {
                        let f_str = f_obj.as_str().ok().or_else(|| {
                            f_obj
                                .as_dict()
                                .ok()
                                .and_then(|d| d.get(b"F").ok())
                                .and_then(|o| o.as_str().ok())
                        });
                        if let Some(f) = f_str {
                            uri = String::from_utf8_lossy(f).into_owned();
                        }
                    } else if let Some(win_f) = action
                        .get(b"Win")
                        .ok()
                        .and_then(|o| deref(doc, o))
                        .and_then(|o| o.as_dict().ok())
                        .and_then(|d| d.get(b"F").ok())
                        .and_then(|o| o.as_str().ok())
                    {
                        uri = String::from_utf8_lossy(win_f).into_owned();
                    }
                } else if s == Some(b"Named".as_ref()) {
                    // Named action (e.g. /N /GoToPage) — not directly resolvable
                }
            } else if let Ok(d) = dict.get(b"Dest") {
                dest_page = resolve_dest_page(doc, d, &page_of);
                // If Dest is Named via string, attempt name tree lookup
                if dest_page < 0 {
                    if let Some(obj) = deref(doc, d).or(Some(d)) {
                        dest_page = resolve_dest(doc, obj, &page_of);
                    }
                }
            }
            if dest_page >= 0 || !uri.is_empty() {
                records.push((rect, dest_page, uri));
            }
        }
    }
    let mut buf = Vec::new();
    buf.extend_from_slice(&(records.len() as u32).to_le_bytes());
    for (rect, dest, uri) in records {
        for v in rect {
            buf.extend_from_slice(&(v as f32).to_le_bytes());
        }
        buf.extend_from_slice(&dest.to_le_bytes());
        let b = uri.as_bytes();
        let len = b.len().min(u16::MAX as usize);
        buf.extend_from_slice(&(len as u16).to_le_bytes());
        buf.extend_from_slice(&b[..len]);
    }
    Some(buf)
}

include!("forms_part1.rs");
include!("forms_part2.rs");
include!("forms_part3.rs");
include!("forms_part4.rs");
include!("forms_part5.rs");
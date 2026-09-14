pub(crate) fn list_form_fields(handle: i64, page_index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get(&handle)?;
    let page_id = nth_page_id(doc, page_index)?;
    let base = page_base_matrix(doc, page_id);

    // (widgetId, typeCode, rect, name, value, checked)
    let mut fields: Vec<(i64, u8, [f64; 4], String, String, u8)> = Vec::new();
    if let Some(Object::Array(annots)) = doc
        .get_dictionary(page_id)
        .ok()
        .and_then(|d| d.get(b"Annots").ok())
        .and_then(|o| deref(doc, o))
    {
        for a in annots {
            // A DIRECT annotation dictionary is skipped here deliberately, even
            // though §12.5.2 permits one and `render_annotations` paints it.
            // The `encode_id` below is the handle the caller passes back to
            // `set_text_field` / `set_checkbox` / `set_choice_field`, and a
            // direct dictionary has no ObjectId to put in it. Listing the field
            // under a sentinel id would let the user tap it, type, and have the
            // write silently fail — losing their input with no feedback, which
            // is worse than a field that is visibly not interactive. A widget
            // must also be reachable from the AcroForm /Fields array, whose
            // entries ARE references, so a conforming form cannot reach this.
            let id = match a.as_reference() {
                Ok(id) => id,
                Err(_) => continue,
            };
            let dict = match doc.get_dictionary(id) {
                Ok(d) => d,
                Err(_) => continue,
            };
            let is_widget = dict.get(b"Subtype").ok().and_then(|o| deref(doc, o).or(Some(o))).and_then(|o| o.as_name().ok())
                == Some(b"Widget".as_ref());
            // Follow Parent T to locate AcroForm field type - handle nested field attrs
            let ft = field_attr(doc, id, b"FT").and_then(|o| o.as_name().ok());
            if !is_widget || ft.is_none() {
                continue;
            }
            let ft = ft.unwrap();
            // P0 fix #8: Sig distinct type 4, not generic 3
            let type_code = match ft {
                b"Tx" => 0u8,
                b"Btn" => 1u8,
                b"Ch" => 2u8,
                b"Sig" => 4u8,
                _ => 3u8,
            };
            let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                Some(r) => {
                    let n = normalize_rect(r);
                    let (dx0, dy0) = transform(&base, n[0], n[1]);
                    let (dx1, dy1) = transform(&base, n[2], n[3]);
                    normalize_rect([dx0, dy0, dx1, dy1])
                }
                None => continue,
            };
            let name = field_attr(doc, id, b"T")
                .and_then(|o| o.as_str().ok())
                .map(decode_pdf_text)
                .unwrap_or_default();
            // P0 fix #10: Choice multi-select V can be array
            let value = field_attr(doc, id, b"V")
                .map(|o| match o {
                    Object::String(s, _) => decode_pdf_text(s),
                    Object::Name(n) => String::from_utf8_lossy(n).into_owned(),
                    Object::Array(arr) => {
                        // Multi-select array of strings
                        arr.iter().filter_map(|x| match x {
                            Object::String(s, _) => Some(decode_pdf_text(s)),
                            Object::Name(n) => Some(String::from_utf8_lossy(n).into_owned()),
                            _ => None,
                        }).collect::<Vec<_>>().join(",")
                    }
                    _ => String::new(),
                })
                .unwrap_or_default();
            let checked = if type_code == 1 {
                // §12.5.5: /AS names which of /AP /N's states this WIDGET paints,
                // so it is the per-widget truth. /V is the FIELD's value, shared
                // by every kid of a radio group — using it as a fallback for a
                // widget that has /AS = /Off reported every button in the group as
                // selected. Only fall back to /V when the widget has no /AS.
                // §7.3.10: an indirect /AS reads as absent and falls back to /V,
                // which is the FIELD's value shared by every kid of a radio
                // group — reporting every button in the group as selected.
                match dict.get(b"AS").ok().and_then(|o| deref(doc, o).or(Some(o))).and_then(|o| o.as_name().ok()) {
                    Some(s) => (s != b"Off") as u8,
                    None => (!value.is_empty() && value != "Off") as u8,
                }
            } else {
                0
            };
            fields.push((encode_id(id), type_code, rect, name, value, checked));
        }
    }

    let mut buf = Vec::new();
    buf.extend_from_slice(&(fields.len() as u32).to_le_bytes());
    for (id, tc, rect, name, value, checked) in fields {
        buf.extend_from_slice(&id.to_le_bytes());
        buf.push(tc);
        for v in rect {
            buf.extend_from_slice(&(v as f32).to_le_bytes());
        }
        for s in [&name, &value] {
            let b = s.as_bytes();
            let len = b.len().min(u16::MAX as usize);
            buf.extend_from_slice(&(len as u16).to_le_bytes());
            buf.extend_from_slice(&b[..len]);
        }
        buf.push(checked);
    }
    Some(buf)
}

/// Set the AcroForm `/NeedAppearances` flag so conformant viewers regenerate
/// field appearances after a value change.
pub(crate) fn set_need_appearances(doc: &mut Document) {
    let acro_id = doc
        .catalog()
        .ok()
        .and_then(|c| c.get(b"AcroForm").ok())
        .and_then(|o| o.as_reference().ok());
    if let Some(id) = acro_id {
        if let Ok(af) = doc.get_dictionary_mut(id) {
            af.set("NeedAppearances", Object::Boolean(true));
        }
    }
}

/// Build the content stream for a text field's `/N` appearance, honoring
/// alignment (`/Q`: 0 left, 1 center, 2 right), multiline (line-wrapped) and
/// comb (one glyph per `/MaxLen` cell) fields, and the font, size and colour the
/// caller resolved from `/DA`. Widths are approximated with Helvetica's ~0.5em
/// average since exact metrics aren't needed for a legible generated appearance.
#[allow(clippy::too_many_arguments)]
fn build_text_appearance(
    value: &str,
    w: f64,
    h: f64,
    size: f64,
    font: &[u8],
    argb: u32,
    quadding: i64,
    multiline: bool,
    comb: bool,
    max_len: usize,
) -> Vec<u8> {
    let char_w = size * 0.5;
    let (r, g, b) = argb_rgb(argb);
    let mut body = String::new();
    body.push_str(&format!(
        "q {r:.3} {g:.3} {b:.3} rg BT /{} {size:.3} Tf ",
        String::from_utf8_lossy(font)
    ));

    if comb && !multiline && max_len > 0 {
        // One glyph per cell, centered in each cell. §12.7.4.3 Table 226 bit 25
        // makes Comb "meaningful only if the MaxLen entry is present ... and if
        // the Multiline, Password, and FileSelect flags are clear", so a field
        // carrying both flags wraps rather than chopping the value into MaxLen
        // one-character cells on a single row.
        //
        // The vertical offset has to be repeated on every `Tm`: `Tm` REPLACES
        // the text matrix rather than concatenating (§9.4.2), so setting the
        // baseline once up front and then issuing per-cell `Tm`s dropped every
        // glyph to y=0, sitting the row on the bottom edge of the box with its
        // descenders clipped.
        let cell_w = w / max_len as f64;
        let base_y = centered_baseline(h, size);
        for (i, ch) in value.chars().take(max_len).enumerate() {
            let cx = i as f64 * cell_w + (cell_w - char_w) / 2.0;
            body.push_str(&format!(
                "1 0 0 1 {cx:.2} {base_y:.2} Tm ({}) Tj ",
                escape_pdf_literal(&ch.to_string())
            ));
        }
    } else if multiline {
        // Split on explicit newlines and greedily wrap to the box width. CRLF is
        // normalized first: splitting on both characters turns each "\r\n" into
        // an extra empty line, double-spacing the whole field.
        let value = value.replace("\r\n", "\n");
        let leading = size * 1.15;
        let max_chars = ((w - 4.0) / char_w).floor().max(1.0) as usize;
        let mut lines: Vec<String> = Vec::new();
        for raw in value.split(['\n', '\r']) {
            if raw.is_empty() {
                lines.push(String::new());
                continue;
            }
            let mut cur = String::new();
            for word in raw.split(' ') {
                if cur.is_empty() {
                    cur = word.to_string();
                } else if cur.chars().count() + 1 + word.chars().count() <= max_chars {
                    cur.push(' ');
                    cur.push_str(word);
                } else {
                    lines.push(std::mem::take(&mut cur));
                    cur = word.to_string();
                }
            }
            lines.push(cur);
        }
        let mut y = h - size - 2.0;
        for line in lines {
            let x = aligned_x(&line, w, char_w, quadding);
            body.push_str(&format!(
                "1 0 0 1 {x:.2} {y:.2} Tm ({}) Tj ",
                escape_pdf_literal(&line)
            ));
            y -= leading;
            if y < -size {
                break;
            }
        }
    } else {
        let x = aligned_x(value, w, char_w, quadding);
        let y = centered_baseline(h, size);
        body.push_str(&format!(
            "1 0 0 1 {x:.2} {y:.2} Tm ({}) Tj ",
            escape_pdf_literal(value)
        ));
    }
    body.push_str("ET Q");
    body.into_bytes()
}

/// The BASELINE for a single row of text vertically centred in a field of
/// height `h`, in the appearance's own form space.
///
/// `(h - size) / 2` centres the EM BOX, which is not the same thing: the
/// baseline is not the bottom of the em box, glyphs hang below it by the
/// descender. Centring the visible ink means centring the ascent-to-descent
/// span, which puts the baseline `0.2445 * size` HIGHER than the em-box
/// calculation for Helvetica's metrics. The old expression was always low by
/// that amount, and since §8.10.1 clips the content to the `[0 0 w h]` BBox the
/// descenders were cut off once `h < 1.414 * size`.
///
/// Helvetica's metrics are used because the rest of this generator already
/// approximates with them (`char_w = size * 0.5`), and a `/DR` font adopted by
/// `da_font_resources` is a Latin text face whose metrics are close enough that
/// a centred row stays centred.
fn centered_baseline(h: f64, size: f64) -> f64 {
    const ASCENDER: f64 = 0.718;
    const DESCENDER: f64 = 0.207; // magnitude; the metric itself is negative
    (h - (ASCENDER + DESCENDER) * size) / 2.0 + DESCENDER * size
}

/// Horizontal text origin for a line given the box width, approximate glyph
/// width and `/Q` alignment.
fn aligned_x(line: &str, w: f64, char_w: f64, quadding: i64) -> f64 {
    let text_w = line.chars().count() as f64 * char_w;
    match quadding {
        1 => ((w - text_w) / 2.0).max(2.0),      // centered
        2 => (w - text_w - 2.0).max(2.0),        // right
        _ => 2.0,                                 // left
    }
}

/// The font size for a generated field appearance: the `/DA` size, or — when
/// that is ZERO, which §12.7.4.3 defines as auto-size-to-fit and NOT as
/// invisible — a size derived from the field height.
fn field_font_size(da_size: f64, h: f64) -> f64 {
    if da_size > 0.0 {
        da_size
    } else {
        (h - 4.0).clamp(6.0, 14.0)
    }
}

/// Whether the AcroForm asks consumers to regenerate field appearances.
///
/// §12.7.2 Table 218: when `/NeedAppearances` is true the consumer SHALL
/// construct appearance streams from `/V` and `/DA`, so it OVERRIDES a `/AP`
/// already present in the file — that `/AP` is by definition the stale one.
pub(crate) fn need_appearances(doc: &Document) -> bool {
    acroform(doc)
        .and_then(|af| af.get(b"NeedAppearances").ok())
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(|o| o.as_bool().ok())
        .unwrap_or(false)
}

/// A field attribute resolved from the widget DICTIONARY: its own entry, else
/// inherited up the `/Parent` chain (§12.7.3.1). The renderer walks `/Annots`
/// values and so holds a dictionary rather than an id, which `field_attr` needs.
fn widget_attr<'a>(doc: &'a Document, w: &'a Dictionary, key: &[u8]) -> Option<&'a Object> {
    if let Ok(v) = w.get(key) {
        return Some(v);
    }
    field_attr(doc, w.get(b"Parent").ok()?.as_reference().ok()?, key)
}

/// The text a widget displays for its field's `/V`, or `None` when there is
/// nothing to draw. For a choice field `/V` holds the EXPORT value, so it is
/// mapped back through `/Opt` to the display string the user picked
/// (§12.7.4.4) — otherwise a field this crate itself filled in would re-render
/// showing the export string.
fn widget_display_value(doc: &Document, w: &Dictionary, choice: bool) -> Option<String> {
    let text = |o: &Object| match deref(doc, o).unwrap_or(o) {
        Object::String(s, _) => decode_pdf_text(s),
        Object::Name(n) => String::from_utf8_lossy(n).into_owned(),
        _ => String::new(),
    };
    let v = widget_attr(doc, w, b"V")?;
    // §12.7.4.4: a multi-select choice field's /V is an array of the selected
    // export values.
    let value = match deref(doc, v).unwrap_or(v) {
        Object::Array(sel) => sel.iter().map(text).filter(|s| !s.is_empty()).collect::<Vec<_>>().join(", "),
        other => text(other),
    };
    if value.is_empty() {
        return None;
    }
    if !choice {
        return Some(value);
    }
    let opts = match widget_attr(doc, w, b"Opt")
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_array().ok())
    {
        Some(o) => o,
        None => return Some(value),
    };
    for o in opts {
        if let Object::Array(pair) = deref(doc, o).unwrap_or(o) {
            if pair.len() >= 2 && text(&pair[0]) == value {
                return Some(text(&pair[1]));
            }
        }
    }
    Some(value)
}

/// Build the appearance a Widget's value should paint when the file supplies no
/// usable `/AP`, or when `/NeedAppearances` requires one to be regenerated
/// (§12.7.2 Table 218).
///
/// Returns the content stream, its resources, the `/BBox` it is laid out in and
/// the `/Matrix` to place it with — the same four things `set_text_field` hands
/// to `make_appearance_oriented`, so a value synthesized at render time lands
/// exactly where the same value baked in by an edit would.
pub(crate) fn widget_value_appearance(
    doc: &Document,
    widget: &Dictionary,
    rect: [f64; 4],
) -> Option<(Vec<u8>, Dictionary, [f64; 4], Mat)> {
    // Only the variable-text field types (§12.7.4). A /Btn's value NAMES an /AP
    // state rather than supplying text, and a /Sig's is a signature dictionary;
    // neither has an appearance that can be derived from /DA.
    let ft = widget_attr(doc, widget, b"FT")
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(|o| o.as_name().ok())?;
    let choice = ft == b"Ch";
    if ft != b"Tx" && !choice {
        return None;
    }
    let flags = widget_attr(doc, widget, b"Ff")
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(num)
        .unwrap_or(0.0) as u32;
    // §12.7.4.3 Table 226 bit 14: a Password field's value "shall not be echoed
    // visually". Regenerating an appearance for it would put a stored password
    // on screen, so draw nothing.
    if flags & (1 << 13) != 0 {
        return None;
    }
    let value = widget_display_value(doc, widget, choice)?;

    let r = normalize_rect(rect);
    let (rw, rh) = (r[2] - r[0], r[3] - r[1]);
    if !(rw > 0.0 && rh > 0.0) {
        return None;
    }
    // Same display-orientation handling as set_text_field (§12.5.2 /P), so the
    // value reads upright on a rotated page.
    let rot = widget
        .get(b"P")
        .ok()
        .and_then(|o| o.as_reference().ok())
        .map(|pid| page_rotation(doc, pid))
        .unwrap_or(0);
    let (w, h, apm) = display_orientation(rot, rw, rh);
    let da = da_of(doc, widget_attr(doc, widget, b"DA"));
    let (res, font) = da_font_resources(doc, da.font.as_deref());
    let size = field_font_size(da.size, h);
    let quadding = quadding_of(doc, widget_attr(doc, widget, b"Q"));
    let max_len = widget_attr(doc, widget, b"MaxLen")
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(num)
        .unwrap_or(0.0) as usize;
    // A choice field is laid out as a single line: §12.7.4.3's Multiline and
    // Comb bits are text-field flags and share their bit positions with the
    // choice flags (Combo, Sort, ...), so honouring them on a /Ch would wrap or
    // comb the value on the strength of an unrelated flag.
    let multiline = !choice && flags & (1 << 12) != 0; // Ff bit 13
    let comb = !choice && flags & (1 << 24) != 0; // Ff bit 25
    let content = build_text_appearance(&value, w, h, size, &font, da.argb, quadding, multiline, comb, max_len);
    Some((content, res, [0.0, 0.0, w, h], apm))
}

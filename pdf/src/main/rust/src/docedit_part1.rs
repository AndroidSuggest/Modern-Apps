/// Ensure page `page_id` has an inline `/Resources` sub-dictionary `category`
/// mapping `name` -> `id`.
fn add_page_resource(
    doc: &mut Document,
    page_id: ObjectId,
    category: &str,
    name: &str,
    id: ObjectId,
) {
    // Resolve to an inline Resources dict on the page (copying a referenced one).
    let res_inline = matches!(
        doc.get_dictionary(page_id).ok().and_then(|d| d.get(b"Resources").ok()),
        Some(Object::Dictionary(_))
    );
    if !res_inline {
        // §7.7.3.4: /Resources is INHERITABLE, so a page that carries none is not a
        // page without resources — `resources_dict`, the renderer's read path, walks
        // /Parent for it. Seeding the inline copy from the page's OWN entry produced
        // an empty dictionary that then SHADOWS the inherited one, so flattening a
        // single annotation blanked every font and image on such a page.
        let copied = inherited(doc, page_id, b"Resources")
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .cloned()
            .unwrap_or_else(Dictionary::new);
        if let Ok(p) = doc.get_dictionary_mut(page_id) {
            p.set("Resources", Object::Dictionary(copied));
        }
    }
    if let Ok(p) = doc.get_dictionary_mut(page_id) {
        if let Ok(Object::Dictionary(res)) = p.get_mut(b"Resources") {
            let has = matches!(res.get(category.as_bytes()), Ok(Object::Dictionary(_)));
            if !has {
                res.set(category, Object::Dictionary(Dictionary::new()));
            }
            if let Ok(Object::Dictionary(sub)) = res.get_mut(category.as_bytes()) {
                sub.set(name, Object::Reference(id));
            }
        }
    }
}

/// Ensure page `page_id` has an inline `/Resources /XObject` mapping `name` -> `xid`.
pub(crate) fn add_page_xobject(doc: &mut Document, page_id: ObjectId, name: &str, xid: ObjectId) {
    add_page_resource(doc, page_id, "XObject", name, xid);
}

/// Prepend `content_id` (a content stream) before page `page_id`'s `/Contents`.
pub(crate) fn prepend_content(doc: &mut Document, page_id: ObjectId, content_id: ObjectId) {
    let current = doc.get_dictionary(page_id).ok().and_then(|d| d.get(b"Contents").ok()).cloned();
    let new_contents = match current {
        Some(Object::Reference(r)) => Object::Array(vec![Object::Reference(content_id), Object::Reference(r)]),
        Some(Object::Array(a)) => {
            let mut v = vec![Object::Reference(content_id)];
            v.extend(a);
            Object::Array(v)
        }
        _ => Object::Array(vec![Object::Reference(content_id)]),
    };
    if let Ok(p) = doc.get_dictionary_mut(page_id) {
        p.set("Contents", new_contents);
    }
}

/// Append `content_id` (a content stream) to page `page_id`'s `/Contents`.
pub(crate) fn append_content(doc: &mut Document, page_id: ObjectId, content_id: ObjectId) {
    let current = doc.get_dictionary(page_id).ok().and_then(|d| d.get(b"Contents").ok()).cloned();
    let new_contents = match current {
        Some(Object::Reference(r)) => Object::Array(vec![Object::Reference(r), Object::Reference(content_id)]),
        Some(Object::Array(mut a)) => {
            a.push(Object::Reference(content_id));
            Object::Array(a)
        }
        _ => Object::Array(vec![Object::Reference(content_id)]),
    };
    if let Ok(p) = doc.get_dictionary_mut(page_id) {
        p.set("Contents", new_contents);
    }
}

/// Flatten every annotation's appearance into its page content stream, then drop
/// the annotations. Makes overlays (incl. redaction boxes) permanent.
pub(crate) fn flatten_document(handle: i64) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let page_ids: Vec<ObjectId> = doc.get_pages().values().copied().collect();
    // Built ONCE: `OcConfig::from_doc` reads the catalog and builds both membership sets,
    // and `annot_visible_on_screen` (the convenience wrapper) does that per call. Inside
    // this per-page, per-annotation loop that rebuilt the whole config for every
    // annotation in the document — the same trap the renderer's `Do` arm hit. The config
    // is document-level and immutable, so one is correct for the whole flatten.
    let oc = crate::images::OcConfig::from_doc(doc);
    for page_id in page_ids {
        // Collect (xobject name, appearance id, placement matrix) for each annot.
        // The ORIGINAL array is kept, not just the ids: every entry that does not get
        // baked has to be written back below, and a direct-dictionary entry has no id
        // to write back with.
        let annots_arr: Vec<Object> = match doc
            .get_dictionary(page_id)
            .ok()
            .and_then(|d| d.get(b"Annots").ok())
            .and_then(|o| deref(doc, o))
        {
            Some(Object::Array(a)) => a.clone(),
            _ => continue,
        };
        // §12.5.2 does not require an /Annots entry to be indirect, so a direct
        // dictionary is legal here. It cannot be BAKED — the bake path needs an
        // ObjectId to reference the annotation's appearance from the page's
        // /XObject — but the retain below keeps it in /Annots, so it survives the
        // flatten and goes on rendering instead of being erased.
        let annot_ids: Vec<ObjectId> = annots_arr.iter().filter_map(|o| o.as_reference().ok()).collect();
        if annot_ids.is_empty() {
            continue;
        }
        let mut placements: Vec<(String, ObjectId, Mat, f64)> = Vec::new();
        // Exactly the annotations whose art reached the content stream. Everything
        // else must stay in /Annots — see the retain at the end of this loop.
        let mut baked_ids: Vec<ObjectId> = Vec::new();
        for (i, aid) in annot_ids.iter().enumerate() {
            let dict = match doc.get_dictionary(*aid) {
                Ok(d) => d,
                Err(_) => continue,
            };
            // Must match the renderer exactly: baking a NoView (§12.5.3),
            // /OC-disabled (§8.11.2) or /Popup (§12.5.6.14) annotation into page
            // content makes it permanently visible, and /Annots is dropped below
            // so it cannot be undone.
            if !annot_visible_on_screen_with(&oc, doc, dict) {
                continue;
            }
            let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                Some(r) => r,
                None => continue,
            };
            // §12.5.5: /AP /N may be a SUBDICTIONARY of appearance states keyed by
            // /AS — how every checkbox and radio button stores its on/off art.
            // Resolving only a direct reference skipped those annotations, and
            // /Annots is removed below, so flattening a filled form silently
            // erased every check mark. Mirror the renderer's selection policy so a
            // flattened page matches what was on screen.
            let ap_n = match dict.get(b"AP").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok())
                .and_then(|ap| ap.get(b"N").ok())
            {
                Some(n) => n,
                None => continue,
            };
            let picked = match deref(doc, ap_n) {
                Some(Object::Dictionary(states)) => {
                    // §7.3.10: /AS may be an indirect reference like any other value.
                    // Without the deref it read as None, so a checkbox fell through to
                    // the /Off branch and the UNCHECKED art was baked permanently over a
                    // checked box — a wrong answer written to the file, not a missing one.
                    match dict.get(b"AS").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_name().ok()) {
                        Some(a) => states.get(a).ok().or_else(|| states.get(b"Off").ok()),
                        None => states.get(b"Off").ok().or_else(|| {
                            if states.len() == 1 {
                                states.iter().next().map(|(_, v)| v)
                            } else {
                                None
                            }
                        }),
                    }
                    .and_then(|o| o.as_reference().ok())
                }
                _ => ap_n.as_reference().ok(),
            };
            let ap_id = match picked {
                Some(id) => id,
                None => continue,
            };
            let (bbox, matrix) = match doc.get_object(ap_id).ok().and_then(|o| o.as_stream().ok()) {
                Some(s) => {
                    let bbox = s.dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)).unwrap_or([0.0, 0.0, 1.0, 1.0]);
                    let matrix = s.dict.get(b"Matrix").ok().and_then(read_matrix_obj).unwrap_or(IDENTITY);
                    (bbox, matrix)
                }
                None => continue,
            };
            // §12.5.5 computes AA = Matrix x A, but AA is for a caller that plays
            // the appearance's operators itself. This bakes the appearance as a
            // `cm ... Do`, and §8.10.2 makes `Do` concatenate the form's own
            // /Matrix with the CTM — so emitting AA applies /Matrix TWICE. Only
            // the fit matrix A belongs in the `cm`. Every appearance this app
            // authors for a rotated page carries a /Matrix (see
            // `display_orientation`), so flattening one used to rotate it a
            // second time and translate it clean off its /Rect.
            let m = appearance_fit_matrix(rect, bbox, matrix);
            // §12.5.2 Table 164: /CA is the annotation's constant opacity, and the
            // renderer honours it (`render_annotation`). A bare `cm ... Do` carries no
            // alpha, so flattening turned a half-transparent highlight or stamp fully
            // opaque — and /Annots is dropped below, so it cannot be undone. §12.5.2
            // makes /CA govern stroking and non-stroking alike, so it is emitted as an
            // /ExtGState setting BOTH /ca and /CA (§11.6.4.4).
            let ca = dict
                .get(b"CA")
                .ok()
                .and_then(|o| deref(doc, o).or(Some(o)))
                .and_then(num)
                .unwrap_or(1.0)
                .clamp(0.0, 1.0);
            placements.push((format!("Fl{}_{}", page_id.0, i), ap_id, m, ca));
            baked_ids.push(*aid);
        }
        if placements.is_empty() {
            continue;
        }
        let mut content = String::new();
        let mut gstates: Vec<(String, ObjectId)> = Vec::new();
        for (i, (name, _, m, ca)) in placements.iter().enumerate() {
            let gs = if *ca < 1.0 {
                let gid = doc.add_object(dictionary! {
                    "Type" => name_obj("ExtGState"),
                    "ca" => Object::Real(*ca as f32),
                    "CA" => Object::Real(*ca as f32),
                });
                let gname = format!("FlG{}_{}", page_id.0, i);
                gstates.push((gname.clone(), gid));
                format!("/{gname} gs ")
            } else {
                String::new()
            };
            content.push_str(&format!(
                "q {:.4} {:.4} {:.4} {:.4} {:.4} {:.4} cm {}/{} Do Q ",
                m[0], m[1], m[2], m[3], m[4], m[5], gs, name
            ));
        }
        let cid = doc.add_object(Stream::new(dictionary! {}, content.into_bytes()));
        // §7.8.2: the streams of a /Contents array are concatenated into a single
        // stream, so state the original content changed and never restored (CTM,
        // colour, clip) would leak into the overlay. Bracket the original in q/Q
        // so the overlay starts from the default graphics state.
        let qid = doc.add_object(Stream::new(dictionary! {}, b"q\n".to_vec()));
        let unqid = doc.add_object(Stream::new(dictionary! {}, b"\nQ\n".to_vec()));
        prepend_content(doc, page_id, qid);
        append_content(doc, page_id, unqid);
        append_content(doc, page_id, cid);
        for (name, ap_id, _, _) in &placements {
            add_page_xobject(doc, page_id, name, *ap_id);
        }
        for (name, gid) in &gstates {
            add_page_resource(doc, page_id, "ExtGState", name, *gid);
        }
        // Retain everything that was NOT baked instead of removing /Annots wholesale.
        //
        // Every failure path in the loop above is a `continue`, and this used to be an
        // unconditional `p.remove(b"Annots")`, so "skipped by the bake loop" and "erased
        // from the saved file" were the same outcome. An annotation with no /AP renders
        // through `synthesize_annotation_appearance`, so it is on screen right up until
        // a flatten deletes it — and unlike a dropped image this is written to the
        // user's document and cannot be recovered by reopening it.
        //
        // The comment at the /AP /N lookup above records this mechanism biting once
        // already: a cause was found and fixed while the mechanism was left in place,
        // which is how the remaining ways in went on erasing content. Fixing the
        // mechanism covers all of them, including the deliberate skips — a NoView, /OC
        // -disabled or /Popup annotation must not be baked (it would become permanently
        // visible), and it must not be destroyed either.
        if let Ok(p) = doc.get_dictionary_mut(page_id) {
            let survivors: Vec<Object> = annots_arr
                .into_iter()
                .filter(|o| match o.as_reference() {
                    Ok(id) => !baked_ids.contains(&id),
                    // A direct dictionary was never a bake candidate, so it always survives.
                    Err(_) => true,
                })
                .collect();
            if survivors.is_empty() {
                p.remove(b"Annots");
            } else {
                p.set("Annots", Object::Array(survivors));
            }
        }
    }
    true
}

/// Approximate per-string text length for advance estimation (byte count).
pub(crate) fn approx_text_len(op: &lopdf::content::Operation) -> f64 {
    if op.operator == "TJ" {
        if let Some(Object::Array(a)) = op.operands.first() {
            return a
                .iter()
                .map(|o| if let Object::String(s, _) = o { s.len() as f64 } else { 0.0 })
                .sum();
        }
        return 0.0;
    }
    op.operands
        .iter()
        .rev()
        .find_map(|o| if let Object::String(s, _) = o { Some(s.len() as f64) } else { None })
        .unwrap_or(0.0)
}

/// Rewrite a page's operator list, dropping text-show operators whose origin
/// falls within any redaction `rects` (page space). Heuristic advance tracking.
pub(crate) fn redact_operations(
    ops: Vec<lopdf::content::Operation>,
    rects: &[[f64; 4]],
) -> Vec<lopdf::content::Operation> {
    let mut out: Vec<lopdf::content::Operation> = Vec::with_capacity(ops.len());
    let mut ctm_stack: Vec<Mat> = Vec::new();
    let mut ctm = IDENTITY;
    let mut tm = IDENTITY;
    let mut lm = IDENTITY;
    let mut font_size = 0.0f64;
    let mut leading = 0.0f64;
    let mut char_spacing = 0.0f64;
    let mut h_scale = 1.0f64;
    let n = |o: Option<&Object>| o.and_then(num).unwrap_or(0.0);
    for op in ops {
        let operands = &op.operands;
        match op.operator.as_str() {
            "q" => ctm_stack.push(ctm),
            "Q" => {
                if let Some(m) = ctm_stack.pop() {
                    ctm = m;
                }
            }
            "cm" if operands.len() >= 6 => {
                let m = [
                    n(operands.first()), n(operands.get(1)), n(operands.get(2)),
                    n(operands.get(3)), n(operands.get(4)), n(operands.get(5)),
                ];
                ctm = mat_mul(&m, &ctm);
            }
            "BT" => {
                tm = IDENTITY;
                lm = IDENTITY;
            }
            "Tf" if operands.len() >= 2 => font_size = n(operands.get(1)),
            "TL" => leading = n(operands.first()),
            "Tc" => char_spacing = n(operands.first()),
            "Tz" => h_scale = n(operands.first()) / 100.0,
            "Tm" if operands.len() >= 6 => {
                let m = [
                    n(operands.first()), n(operands.get(1)), n(operands.get(2)),
                    n(operands.get(3)), n(operands.get(4)), n(operands.get(5)),
                ];
                tm = m;
                lm = m;
            }
            "Td" if operands.len() >= 2 => {
                lm = mat_mul(&translate(n(operands.first()), n(operands.get(1))), &lm);
                tm = lm;
            }
            "TD" if operands.len() >= 2 => {
                leading = -n(operands.get(1));
                lm = mat_mul(&translate(n(operands.first()), n(operands.get(1))), &lm);
                tm = lm;
            }
            "T*" => {
                lm = mat_mul(&translate(0.0, -leading), &lm);
                tm = lm;
            }
            "Tj" | "'" | "\"" | "TJ" => {
                if op.operator == "'" || op.operator == "\"" {
                    lm = mat_mul(&translate(0.0, -leading), &lm);
                    tm = lm;
                }
                let trm = mat_mul(&tm, &ctm);
                let (x, y) = (trm[4], trm[5]);
                let hit = rects.iter().any(|r| {
                    x >= r[0] - 1.0 && x <= r[2] + 1.0 && y >= r[1] - 2.0 && y <= r[3] + font_size + 2.0
                });
                let len = approx_text_len(&op);
                let adv = len * font_size * 0.5 * h_scale + len * char_spacing;
                if !hit {
                    out.push(op);
                }
                tm = mat_mul(&translate(adv, 0.0), &tm);
                continue;
            }
            _ => {}
        }
        out.push(op);
    }
    out
}

/// Encode an operator list back into content-stream bytes.
///
/// Not `Content::encode` on its own: §8.9.7 inline images come out of lopdf's parser
/// as a single `BI` operation whose one operand is an `Object::Stream`, and the writer
/// serializes a stream in INDIRECT-object syntax — `<< ... >> stream <data> endstream`,
/// with no `ID` and the `BI` keyword landing AFTER the data. That is not a content
/// stream: the image is destroyed and every operator after it desynchronizes. So `BI`
/// is re-emitted in inline syntax here and the rest is handed to `Content::encode`.
fn encode_operations(ops: Vec<lopdf::content::Operation>) -> Option<Vec<u8>> {
    fn flush(run: &mut Vec<lopdf::content::Operation>, out: &mut Vec<u8>) -> Option<()> {
        if run.is_empty() {
            return Some(());
        }
        let encoded = Content { operations: std::mem::take(run) }.encode().ok()?;
        out.extend_from_slice(&encoded);
        out.push(b'\n');
        Some(())
    }
    let mut out = Vec::new();
    let mut run: Vec<lopdf::content::Operation> = Vec::new();
    for op in ops {
        let inline = match (op.operator.as_str(), op.operands.first()) {
            ("BI", Some(Object::Stream(s))) => Some(s.clone()),
            _ => None,
        };
        match inline {
            Some(s) => {
                flush(&mut run, &mut out)?;
                out.extend_from_slice(&encode_inline_image(&s)?);
                out.push(b'\n');
            }
            None => run.push(op),
        }
    }
    flush(&mut run, &mut out)?;
    Some(out)
}

/// §8.9.7 `BI <key value>… ID <data> EI`.
///
/// The key/value pairs are emitted through `Content::encode` with `ID` as the operator,
/// which is exactly the `/Key value … ID` text the inline-image syntax calls for, and
/// keeps the operand writer (name escaping, number formatting) in one place.
fn encode_inline_image(s: &Stream) -> Option<Vec<u8>> {
    let mut operands = Vec::new();
    for (k, v) in s.dict.iter() {
        operands.push(Object::Name(k.clone()));
        operands.push(v.clone());
    }
    let head = Content {
        operations: vec![lopdf::content::Operation { operator: "ID".to_string(), operands }],
    }
    .encode()
    .ok()?;
    let mut out = b"BI ".to_vec();
    out.extend_from_slice(&head);
    // §8.9.7: exactly one white-space byte separates `ID` from the data.
    out.push(b'\n');
    out.extend_from_slice(&s.content);
    out.extend_from_slice(b"\nEI");
    Some(out)
}

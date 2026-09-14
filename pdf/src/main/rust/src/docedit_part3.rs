#[cfg(test)]
mod redaction_tests {
    use super::*;

    /// One-page document whose `/Contents` is `content`, carrying a single `/PdfRedact`
    /// annotation over `rect`. Returns its registry handle plus the page and annot ids.
    fn redactable_doc(content: &[u8], rect: [i64; 4]) -> (i64, ObjectId, ObjectId) {
        let mut doc = Document::with_version("1.5");
        let content_id = doc.add_object(Stream::new(dictionary! {}, content.to_vec()));
        let pages_id = doc.new_object_id();
        let annot_id = doc.add_object(dictionary! {
            "Type" => "Annot",
            "Subtype" => "Square",
            "Rect" => vec![rect[0].into(), rect[1].into(), rect[2].into(), rect[3].into()],
            "PdfRedact" => true,
        });
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "Contents" => content_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Annots" => vec![annot_id.into()],
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages",
                "Kids" => vec![page_id.into()],
                "Count" => 1,
            }),
        );
        let catalog_id = doc.add_object(dictionary! {
            "Type" => "Catalog",
            "Pages" => pages_id,
        });
        doc.trailer.set("Root", catalog_id);
        let handle = next_handle();
        registry().lock().unwrap_or_else(|e| e.into_inner()).insert(handle, doc);
        (handle, page_id, annot_id)
    }

    /// Decoded bytes of the page's current `/Contents`, which `apply_redactions` writes
    /// uncompressed.
    fn page_bytes(handle: i64, page_id: ObjectId) -> Vec<u8> {
        let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
        let doc = reg.get(&handle).expect("handle is registered");
        let mut out = Vec::new();
        for id in doc.get_page_contents(page_id) {
            if let Ok(Object::Stream(s)) = doc.get_object(id) {
                out.extend_from_slice(&s.content);
            }
        }
        out
    }

    fn annot_exists(handle: i64, annot_id: ObjectId) -> bool {
        let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
        let doc = reg.get(&handle).expect("handle is registered");
        doc.get_dictionary(annot_id).is_ok()
    }

    /// §8.10.2: `Do` concatenates the invoked form's own `/Matrix` with the CTM.
    /// §12.5.5's `AA = Matrix × A` already contains `/Matrix`, so baking an
    /// appearance as `cm AA ... Do` applies it TWICE. Every appearance this app
    /// authors for a rotated page carries a `/Matrix` (`display_orientation`), so
    /// flattening one rotated it again and translated it clean off its `/Rect`.
    /// The emitted `cm` must be `A`, i.e. `Matrix × cm` must map the `/BBox` onto
    /// the `/Rect`.
    #[test]
    fn flatten_emits_the_fit_matrix_not_the_one_do_will_double() {
        let (dw, dh) = (160.0_f64, 40.0_f64);
        let bbox = [0.0, 0.0, dw, dh];
        for rot in [0i64, 90, 180, 270] {
            let (_, _, apm) = display_orientation(rot, dw, dh);
            let rect = match rot {
                90 | 270 => [10.0, 20.0, 10.0 + dh, 20.0 + dw],
                _ => [10.0, 20.0, 10.0 + dw, 20.0 + dh],
            };
            let (handle, page_id) = flattenable_doc(rect, bbox, apm);
            assert!(flatten_document(handle), "rot={rot}: flatten failed");
            let cm = emitted_cm(handle, page_id);
            // What the renderer will build: the form's /Matrix on top of the `cm`.
            let effective = mat_mul(&apm, &cm);
            for (x, y) in [(0.0, 0.0), (dw, 0.0), (dw, dh), (0.0, dh)] {
                let (px, py) = transform(&effective, x, y);
                assert!(
                    px >= rect[0] - 0.01 && px <= rect[2] + 0.01
                        && py >= rect[1] - 0.01 && py <= rect[3] + 0.01,
                    "rot={rot}: flattened BBox corner ({px},{py}) landed outside {rect:?}"
                );
            }
            close_document(handle);
        }
    }

    /// §12.5.2 Table 164: `/CA` is the annotation's constant opacity, and the
    /// renderer honours it (`render_annotation` wraps the appearance in a group
    /// with that alpha). A bare `cm … Do` carries no alpha, so flattening turned
    /// a half-transparent highlight fully opaque — and `/Annots` is dropped, so
    /// the original opacity is unrecoverable.
    #[test]
    fn flatten_carries_the_annotation_constant_opacity() {
        let (dw, dh) = (40.0_f64, 20.0_f64);
        let rect = [10.0, 20.0, 10.0 + dw, 20.0 + dh];
        let (handle, page_id) = flattenable_doc(rect, [0.0, 0.0, dw, dh], IDENTITY);
        {
            let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
            let doc = reg.get_mut(&handle).expect("handle is registered");
            let aid = doc
                .get_dictionary(page_id)
                .ok()
                .and_then(|d| d.get(b"Annots").ok())
                .and_then(|o| o.as_array().ok())
                .and_then(|a| a.first().and_then(|o| o.as_reference().ok()))
                .expect("the fixture has one annotation");
            doc.get_dictionary_mut(aid)
                .expect("annot")
                .set("CA", Object::Real(0.5));
        }
        assert!(flatten_document(handle), "flatten failed");

        let text = String::from_utf8_lossy(&page_bytes(handle, page_id)).into_owned();
        let gs = text
            .split_whitespace()
            .collect::<Vec<_>>()
            .windows(2)
            .find(|w| w[1] == "gs")
            .map(|w| w[0].trim_start_matches('/').to_string())
            .unwrap_or_else(|| panic!("no `gs` in the flattened content: {text}"));

        let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
        let doc = reg.get(&handle).expect("handle is registered");
        let res = resources_dict(doc, page_id).expect("resources");
        let egs = res
            .get(b"ExtGState")
            .ok()
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .and_then(|d| d.get(gs.as_bytes()).ok())
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .unwrap_or_else(|| panic!("/{gs} is not in /Resources /ExtGState: {res:?}"));
        // §12.5.2 makes /CA govern stroking and non-stroking alike, so both must be set.
        for key in [&b"ca"[..], &b"CA"[..]] {
            let v = egs.get(key).ok().and_then(num).unwrap_or_else(|| {
                panic!("/{} missing from the flatten ExtGState: {egs:?}", String::from_utf8_lossy(key))
            });
            assert!((v - 0.5).abs() < 1e-6, "expected 0.5, got {v}");
        }
        drop(reg);
        close_document(handle);
    }

    /// A fully opaque annotation (the common case, and `/CA` absent) must not gain
    /// an /ExtGState it does not need.
    #[test]
    fn flatten_emits_no_extgstate_for_an_opaque_annotation() {
        let (dw, dh) = (40.0_f64, 20.0_f64);
        let (handle, page_id) =
            flattenable_doc([10.0, 20.0, 10.0 + dw, 20.0 + dh], [0.0, 0.0, dw, dh], IDENTITY);
        assert!(flatten_document(handle));
        let text = String::from_utf8_lossy(&page_bytes(handle, page_id)).into_owned();
        assert!(!text.contains(" gs"), "unexpected /ExtGState: {text}");
        close_document(handle);
    }

    /// One-page document carrying a single annotation whose `/AP /N` has the
    /// given `/BBox` and `/Matrix`.
    /// Flatten must not DESTROY an annotation it could not bake.
    ///
    /// Every failure path in the bake loop is a `continue`, and `/Annots` used to be
    /// removed unconditionally afterwards, so "we could not bake this" and "this is
    /// erased from the user's saved file" were the same outcome. The severe case is an
    /// annotation with no `/AP`: it renders through `synthesize_annotation_appearance`,
    /// so it is visible on screen right up to the moment a flatten deletes it — and
    /// unlike a dropped image this is written to disk, so reopening cannot recover it.
    ///
    /// Note the precondition, which narrows the blast radius: the page needs at least
    /// one BAKEABLE annotation, because `placements.is_empty()` already skips the page
    /// entirely. So the loss needs a MIXED page — a stamp beside a synthesised square,
    /// which is the ordinary shape.
    #[test]
    fn flatten_keeps_an_annotation_it_could_not_bake() {
        let (dw, dh) = (40.0_f64, 20.0_f64);
        let (handle, page_id) =
            flattenable_doc([10.0, 20.0, 10.0 + dw, 20.0 + dh], [0.0, 0.0, dw, dh], IDENTITY);
        // A second annotation with NO /AP — exactly what the renderer synthesises an
        // appearance for, and exactly what the bake loop skips.
        let no_ap_id = {
            let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
            let doc = reg.get_mut(&handle).expect("handle is registered");
            let id = doc.add_object(dictionary! {
                "Type" => name_obj("Annot"),
                "Subtype" => name_obj("Square"),
                "Rect" => rect_obj([100.0, 100.0, 200.0, 150.0]),
            });
            let mut annots = doc
                .get_dictionary(page_id)
                .ok()
                .and_then(|d| d.get(b"Annots").ok())
                .and_then(|o| o.as_array().ok())
                .expect("fixture has /Annots")
                .clone();
            annots.push(Object::Reference(id));
            doc.get_dictionary_mut(page_id).expect("page").set("Annots", Object::Array(annots));
            id
        };

        assert!(flatten_document(handle), "flatten failed");

        let text = String::from_utf8_lossy(&page_bytes(handle, page_id)).into_owned();
        assert!(text.contains("Do"), "the bakeable annotation must still flatten: {text}");

        let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
        let doc = reg.get(&handle).expect("handle is registered");
        let annots: Vec<ObjectId> = doc
            .get_dictionary(page_id)
            .ok()
            .and_then(|d| d.get(b"Annots").ok())
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_array().ok())
            .map(|a| a.iter().filter_map(|o| o.as_reference().ok()).collect())
            .unwrap_or_default();
        assert_eq!(
            annots,
            vec![no_ap_id],
            "the un-bakeable annotation must survive in /Annots, and the baked one must not"
        );
        assert!(doc.get_dictionary(no_ap_id).is_ok(), "its object must still be in the document");
        drop(reg);
        close_document(handle);
    }

    /// A direct-dictionary `/Annots` entry is legal — §12.5.2 does not require the
    /// indirection — and cannot be baked, because the bake path needs an ObjectId to
    /// reference the appearance from the page's /XObject. So it must be left alone
    /// rather than erased.
    #[test]
    fn flatten_keeps_a_direct_dictionary_annotation() {
        let (dw, dh) = (40.0_f64, 20.0_f64);
        let (handle, page_id) =
            flattenable_doc([10.0, 20.0, 10.0 + dw, 20.0 + dh], [0.0, 0.0, dw, dh], IDENTITY);
        {
            let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
            let doc = reg.get_mut(&handle).expect("handle is registered");
            let mut annots = doc
                .get_dictionary(page_id)
                .ok()
                .and_then(|d| d.get(b"Annots").ok())
                .and_then(|o| o.as_array().ok())
                .expect("fixture has /Annots")
                .clone();
            annots.push(Object::Dictionary(dictionary! {
                "Type" => name_obj("Annot"),
                "Subtype" => name_obj("Square"),
                "Rect" => rect_obj([100.0, 100.0, 200.0, 150.0]),
            }));
            doc.get_dictionary_mut(page_id).expect("page").set("Annots", Object::Array(annots));
        }
        assert!(flatten_document(handle), "flatten failed");

        let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
        let doc = reg.get(&handle).expect("handle is registered");
        let annots = doc
            .get_dictionary(page_id)
            .ok()
            .and_then(|d| d.get(b"Annots").ok())
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_array().ok())
            .cloned()
            .unwrap_or_default();
        assert_eq!(annots.len(), 1, "only the direct dictionary should remain: {annots:?}");
        assert!(
            matches!(annots.first(), Some(Object::Dictionary(_))),
            "and it must survive verbatim: {annots:?}"
        );
        drop(reg);
        close_document(handle);
    }

    /// When everything on the page IS baked, `/Annots` still goes away entirely: the
    /// retain must not leave an empty array behind, and flatten must still mean
    /// flattened.
    #[test]
    fn flatten_still_removes_annots_when_everything_baked() {
        let (dw, dh) = (40.0_f64, 20.0_f64);
        let (handle, page_id) =
            flattenable_doc([10.0, 20.0, 10.0 + dw, 20.0 + dh], [0.0, 0.0, dw, dh], IDENTITY);
        assert!(flatten_document(handle));
        let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
        let doc = reg.get(&handle).expect("handle is registered");
        assert!(
            doc.get_dictionary(page_id).ok().and_then(|d| d.get(b"Annots").ok()).is_none(),
            "a fully baked page must have no /Annots at all"
        );
        drop(reg);
        close_document(handle);
    }

    /// §7.3.10: `/AS` may be indirect. Without a deref it read as absent, the /Off
    /// branch was taken, and the UNCHECKED art was baked permanently over a checked
    /// box — a wrong answer written into the file rather than a missing one.
    #[test]
    fn flatten_follows_an_indirect_appearance_state() {
        let mut doc = Document::with_version("1.7");
        let form = |content: &[u8]| -> Stream {
            Stream::new(
                dictionary! {
                    "Type" => name_obj("XObject"),
                    "Subtype" => name_obj("Form"),
                    "BBox" => rect_obj([0.0, 0.0, 10.0, 10.0]),
                },
                content.to_vec(),
            )
        };
        let on_id = doc.add_object(form(b"1 0 0 rg 0 0 10 10 re f"));
        let off_id = doc.add_object(form(b"0 1 0 rg 0 0 10 10 re f"));
        // The checked state, reachable only if /AS is dereferenced.
        let as_ref = doc.add_object(Object::Name(b"On".to_vec()));
        let annot_id = doc.add_object(dictionary! {
            "Type" => name_obj("Annot"),
            "Subtype" => name_obj("Widget"),
            "Rect" => rect_obj([0.0, 0.0, 10.0, 10.0]),
            "AS" => Object::Reference(as_ref),
            "AP" => dictionary! { "N" => dictionary! { "On" => on_id, "Off" => off_id } },
        });
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => name_obj("Page"),
            "Parent" => Object::Reference(pages_id),
            "MediaBox" => rect_obj([0.0, 0.0, 612.0, 792.0]),
            "Annots" => Object::Array(vec![Object::Reference(annot_id)]),
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => name_obj("Pages"),
                "Kids" => Object::Array(vec![Object::Reference(page_id)]),
                "Count" => 1,
            }),
        );
        let cat = doc.add_object(dictionary! {
            "Type" => name_obj("Catalog"),
            "Pages" => Object::Reference(pages_id),
        });
        doc.trailer.set("Root", cat);
        let handle = next_handle();
        registry().lock().unwrap_or_else(|e| e.into_inner()).insert(handle, doc);

        assert!(flatten_document(handle), "flatten failed");

        let text = String::from_utf8_lossy(&page_bytes(handle, page_id)).into_owned();
        let name = text
            .split_whitespace()
            .collect::<Vec<_>>()
            .windows(2)
            .find(|w| w[1] == "Do")
            .map(|w| w[0].trim_start_matches('/').to_string())
            .unwrap_or_else(|| panic!("no `Do` in the flattened content: {text}"));

        let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
        let doc = reg.get(&handle).expect("handle is registered");
        let res = resources_dict(doc, page_id).expect("resources");
        let baked = res
            .get(b"XObject")
            .ok()
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .and_then(|xo| xo.get(name.as_bytes()).ok())
            .and_then(|o| o.as_reference().ok())
            .expect("the baked appearance must be in /Resources /XObject");
        assert_ne!(baked, off_id, "an indirect /AS must not fall through to the /Off art");
        assert_eq!(baked, on_id, "an indirect /AS On must bake the CHECKED art");
        drop(reg);
        close_document(handle);
    }

    fn flattenable_doc(rect: [f64; 4], bbox: [f64; 4], matrix: Mat) -> (i64, ObjectId) {
        let mut doc = Document::with_version("1.7");
        let ap_dict = dictionary! {
            "Type" => name_obj("XObject"),
            "Subtype" => name_obj("Form"),
            "BBox" => rect_obj(bbox),
            "Matrix" => Object::Array(matrix.iter().map(|v| Object::Real(*v as f32)).collect()),
        };
        let ap_id = doc.add_object(Stream::new(ap_dict, b"0 0 1 rg 0 0 1 1 re f".to_vec()));
        let annot_id = doc.add_object(dictionary! {
            "Type" => name_obj("Annot"),
            "Subtype" => name_obj("FreeText"),
            "Rect" => rect_obj(rect),
            "AP" => dictionary! { "N" => ap_id },
        });
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => name_obj("Page"),
            "Parent" => Object::Reference(pages_id),
            "MediaBox" => rect_obj([0.0, 0.0, 612.0, 792.0]),
            "Annots" => Object::Array(vec![Object::Reference(annot_id)]),
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => name_obj("Pages"),
                "Kids" => Object::Array(vec![Object::Reference(page_id)]),
                "Count" => 1,
            }),
        );
        let cat = doc.add_object(dictionary! {
            "Type" => name_obj("Catalog"),
            "Pages" => Object::Reference(pages_id),
        });
        doc.trailer.set("Root", cat);
        let handle = next_handle();
        registry().lock().unwrap_or_else(|e| e.into_inner()).insert(handle, doc);
        (handle, page_id)
    }

    /// The six operands of the single `cm` the flatten overlay emitted.
    fn emitted_cm(handle: i64, page_id: ObjectId) -> Mat {
        let bytes = page_bytes(handle, page_id);
        let text = String::from_utf8_lossy(&bytes).into_owned();
        let toks: Vec<&str> = text.split_whitespace().collect();
        let at = toks.iter().position(|t| *t == "cm").expect("no `cm` in the flattened content");
        assert!(at >= 6, "malformed `cm`: {text}");
        let mut m = IDENTITY;
        for i in 0..6 {
            m[i] = toks[at - 6 + i].parse::<f64>().expect("cm operand");
        }
        m
    }

    /// An inline image makes lopdf reject the whole stream, and the lenient tokenizer
    /// cannot promise it saw every text-show operator. Redacting anyway would cover the
    /// text with black, drop the annotation and still ship the text inside the file, so
    /// the operation must refuse and leave the document exactly as it was.
    #[test]
    fn a_stream_only_the_lenient_tokenizer_can_read_is_not_redacted() {
        // No /BPC, so lopdf's inline-image parser errors inside `cut(...)`.
        let mut content = b"BT /F1 12 Tf 100 700 Td (secret) Tj ET\n".to_vec();
        content.extend_from_slice(b"BI /W 2 /H 2 /CS /G ID ");
        content.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
        content.extend_from_slice(b" EI\n");
        let (handle, page_id, annot_id) = redactable_doc(&content, [90, 690, 200, 720]);
        {
            let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
            let doc = reg.get(&handle).expect("handle is registered");
            assert!(
                doc.get_and_decode_page_content(page_id).is_err(),
                "precondition: lopdf is expected to reject this content stream"
            );
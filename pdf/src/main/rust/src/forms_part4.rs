#[cfg(test)]
mod da_tests {
    use super::{build_text_appearance, field_font_size};
    use crate::*;

    fn body(da: &[u8], h: f64) -> String {
        let d = parse_da(da);
        let font = d.font.clone().unwrap_or_else(|| b"F1".to_vec());
        let size = field_font_size(d.size, h);
        String::from_utf8(build_text_appearance(
            "Ab", 100.0, h, size, &font, d.argb, 0, false, false, 0,
        ))
        .expect("utf8")
    }

    /// §12.7.4.3: a font size of ZERO in `/DA` means AUTO-SIZE to fit, NOT zero.
    /// Reading the size straight out of `/DA` renders every such field as
    /// INVISIBLE text, which is strictly worse than ignoring `/DA` altogether, so
    /// size 0 must fall through to the height-derived size.
    #[test]
    fn zero_da_size_means_auto_not_invisible() {
        assert_eq!(parse_da(b"/Helv 0 Tf 0 g").size, 0.0, "0 is reported verbatim");
        assert_eq!(field_font_size(0.0, 24.0), 14.0);
        assert_eq!(field_font_size(0.0, 4.0), 6.0, "clamped up, never zero");
        assert_eq!(field_font_size(0.0, 200.0), 14.0, "clamped down");
        assert_eq!(field_font_size(9.5, 24.0), 9.5, "an explicit size wins");
        for h in [1.0, 4.0, 12.0, 24.0, 1000.0] {
            assert!(field_font_size(0.0, h) >= 6.0, "auto size collapsed at h={h}");
        }
        let c = body(b"/Helv 0 Tf 0 g", 24.0);
        assert!(c.contains("/Helv 14.000 Tf"), "auto-sized appearance was: {c}");
    }

    /// §12.7.3.3: `/DA` defines the field's font, size and colour. The generator
    /// hardcoded `0 0 0 rg /F1`, so a filled field came out in the wrong font,
    /// size and colour compared with Acrobat.
    #[test]
    fn da_font_size_and_colour_reach_the_content_stream() {
        let c = body(b"/Helv 11 Tf 1 0 0 rg", 24.0);
        assert!(c.contains("1.000 0.000 0.000 rg"), "colour: {c}");
        assert!(c.contains("/Helv 11.000 Tf"), "font and size: {c}");
        assert_eq!(parse_da(b"0.5 g").argb, gray_to_argb(0.5));
        assert_eq!(parse_da(b"0 0 1 0 k").argb, cmyk_to_argb(0.0, 0.0, 1.0, 0.0));
        assert_eq!(parse_da(b"1 0 0 rg 0 g").argb, gray_to_argb(0.0), "later wins");
        // No colour operator keeps the black the generator used to hardcode.
        assert_eq!(parse_da(b"/Helv 12 Tf").argb, 0xFF00_0000);
    }

    /// The font name is interpolated straight into `/<name> <size> Tf`, so a name
    /// carrying delimiters could close the operand and inject operators. §7.3.5
    /// restricts names to regular characters, so anything else falls back to the
    /// substitute.
    #[test]
    fn da_font_name_cannot_inject_operators() {
        for bad in [&b"/F1) Tj 0 0 1 rg ( 12 Tf"[..], &b"/(evil 12 Tf"[..], &b"/ 12 Tf"[..]] {
            assert!(
                parse_da(bad).font.is_none(),
                "accepted unsafe name from {:?}",
                String::from_utf8_lossy(bad)
            );
        }
        assert_eq!(parse_da(b"/Helv-Bold.1+2 12 Tf").font.as_deref(), Some(&b"Helv-Bold.1+2"[..]));
        // A malformed size must not become a negative or non-finite Tf operand.
        assert_eq!(parse_da(b"/Helv -5 Tf").size, 0.0, "negative falls to the auto path");
        assert_eq!(parse_da(b"/Helv nope Tf").size, 0.0);
    }

    /// A comb field lays out one `Tm` per cell, and `Tm` REPLACES the text matrix
    /// (§9.4.2) rather than concatenating it — so the vertical offset must be
    /// repeated on every one. Setting it once up front dropped the whole row to
    /// y=0, sitting the characters on the bottom edge with descenders clipped.
    #[test]
    fn comb_cells_keep_their_vertical_offset() {
        let c = String::from_utf8(build_text_appearance(
            "AB", 100.0, 20.0, 12.0, b"F1", 0xFF00_0000, 0, false, true, 5,
        ))
        .expect("utf8");
        // Ink-centred baseline for h=20, size=12:
        // (20 - 0.925*12)/2 + 0.207*12 == 6.93, shared by every cell.
        assert_eq!(c.matches("6.93 Tm").count(), 2, "both cells centered: {c}");
        assert!(!c.contains(" 0.00 Tm"), "a cell fell to the bottom edge: {c}");
    }

    /// Splitting on both `\r` and `\n` turns each CRLF into an extra empty line,
    /// double-spacing a multiline field.
    #[test]
    fn multiline_crlf_does_not_double_space() {
        let c = String::from_utf8(build_text_appearance(
            "one\r\ntwo", 200.0, 60.0, 10.0, b"F1", 0xFF00_0000, 0, true, false, 0,
        ))
        .expect("utf8");
        assert_eq!(c.matches("Tj").count(), 2, "expected exactly two lines: {c}");
    }

    /// §12.6.4.2: a GoTo action's `/D` "shall be" a name, a byte string or an
    /// array — the first two naming a destination resolved through `/Dests` or
    /// the `/Names` name tree (§12.3.2.3). Accepting only the array form left
    /// `dest_page` at -1, and `list_links` drops any record with neither a page
    /// nor a URI, so every named-destination link was silently untappable.
    #[test]
    fn a_goto_link_to_a_named_destination_resolves() {
        for (name, dest) in [
            ("array", Object::Array(vec![])),           // placeholder, replaced below
            ("name", name_obj("Chapter2")),
            ("string", Object::string_literal("Chapter2")),
        ] {
            let mut doc = Document::with_version("1.7");
            let pages_id = doc.new_object_id();
            let p0 = doc.add_object(dictionary! {
                "Type" => name_obj("Page"), "Parent" => Object::Reference(pages_id),
                "MediaBox" => rect_obj([0.0, 0.0, 612.0, 792.0]),
            });
            let p1 = doc.add_object(dictionary! {
                "Type" => name_obj("Page"), "Parent" => Object::Reference(pages_id),
                "MediaBox" => rect_obj([0.0, 0.0, 612.0, 792.0]),
            });
            let target = Object::Array(vec![Object::Reference(p1), name_obj("Fit")]);
            let dest = if name == "array" { target.clone() } else { dest };
            let action = doc.add_object(dictionary! { "S" => name_obj("GoTo"), "D" => dest });
            let link = doc.add_object(dictionary! {
                "Type" => name_obj("Annot"), "Subtype" => name_obj("Link"),
                "Rect" => rect_obj([10.0, 10.0, 100.0, 30.0]),
                "A" => Object::Reference(action),
            });
            if let Ok(Object::Dictionary(d)) = doc.get_object_mut(p0) {
                d.set("Annots", Object::Array(vec![Object::Reference(link)]));
            }
            doc.objects.insert(
                pages_id,
                Object::Dictionary(dictionary! {
                    "Type" => name_obj("Pages"),
                    "Kids" => Object::Array(vec![Object::Reference(p0), Object::Reference(p1)]),
                    "Count" => 2,
                }),
            );
            let names = doc.add_object(dictionary! {
                "Dests" => dictionary! {
                    "Names" => Object::Array(vec![Object::string_literal("Chapter2"), target]),
                },
            });
            let cat = doc.add_object(dictionary! {
                "Type" => name_obj("Catalog"),
                "Pages" => Object::Reference(pages_id),
                "Names" => Object::Reference(names),
            });
            doc.trailer.set("Root", cat);
            let handle = next_handle();
            registry().lock().unwrap_or_else(|e| e.into_inner()).insert(handle, doc);

            let buf = list_links(handle, 0).expect("links");
            let count = u32::from_le_bytes(buf[0..4].try_into().expect("count"));
            assert_eq!(count, 1, "/D as {name}: the link was dropped entirely");
            let dest_page = i32::from_le_bytes(buf[20..24].try_into().expect("dest"));
            assert_eq!(dest_page, 1, "/D as {name}: resolved to the wrong page");
            close_document(handle);
        }
    }

    /// A document with one AcroForm `/DR /Font /Fx` entry.
    fn doc_with_dr_font(font: Dictionary) -> Document {
        let mut doc = Document::with_version("1.7");
        let fid = doc.add_object(font);
        let acro = doc.add_object(dictionary! {
            "DR" => dictionary! { "Font" => dictionary! { "Fx" => fid } },
        });
        let pages = doc.add_object(dictionary! { "Type" => "Pages", "Kids" => Object::Array(vec![]), "Count" => 0 });
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages, "AcroForm" => acro });
        doc.trailer.set("Root", cat);
        doc
    }

    /// §12.7.3.3 resolves the `/DA` font name against `/DR`. It is only safe to
    /// adopt when the generated content — a plain literal string — can address it:
    /// a composite (Type0) font takes multi-byte CIDs and a `/Differences` or
    /// symbolic encoding remaps the bytes, both of which render the value as
    /// garbage. Those must keep the Helvetica substitute, which is legible.
    #[test]
    fn only_safely_addressable_dr_fonts_are_adopted() {
        let adopted = |font: Dictionary| {
            let doc = doc_with_dr_font(font);
            super::da_font_resources(&doc, Some(b"Fx")).1 == b"Fx".to_vec()
        };
        assert!(adopted(dictionary! { "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Times-Roman" }));
        assert!(adopted(
            dictionary! { "Type" => "Font", "Subtype" => "TrueType", "Encoding" => "WinAnsiEncoding" }
        ));
        assert!(
            !adopted(dictionary! { "Type" => "Font", "Subtype" => "Type0", "Encoding" => "Identity-H" }),
            "a composite font would render the value as multi-byte garbage"
        );
        assert!(
            !adopted(dictionary! {
                "Type" => "Font", "Subtype" => "Type1",
                "Encoding" => dictionary! { "Differences" => Object::Array(vec![]) },
            }),
            "a /Differences encoding remaps the bytes to unrelated glyphs"
        );
        assert!(
            !adopted(dictionary! { "Type" => "Font", "Subtype" => "Type1", "Encoding" => "Identity-H" }),
            "an unrecognised encoding is not assumed to be Latin text"
        );

        // A name that /DR does not define, and no name at all, both fall back.
        let doc = doc_with_dr_font(dictionary! { "Type" => "Font", "Subtype" => "Type1" });
        assert_eq!(super::da_font_resources(&doc, Some(b"Nope")).1, b"F1".to_vec());
        assert_eq!(super::da_font_resources(&doc, None).1, b"F1".to_vec());
        // When adopted, the font must actually be in the appearance's resources,
        // or the Tf name would not resolve and nothing would paint.
        let (res, name) = super::da_font_resources(&doc, Some(b"Fx"));
        let fonts = res.get(b"Font").and_then(|o| o.as_dict()).expect("/Font");
        assert!(fonts.get(&name).is_ok(), "adopted font missing from resources");
        assert!(fonts.get(b"F1").is_ok(), "substitute must stay available");
    }

    /// §12.7.3.1 Table 220 marks `/Ff`, and §12.7.4.3 Table 222 marks `/Q` and
    /// `/MaxLen`, "Optional; inheritable"; §12.7.2 Table 218 makes the AcroForm
    /// `/Q` the document-wide default. A generator that reads them off a single
    /// dictionary renders a wrapped value as one clipped line and ignores the
    /// form's alignment entirely.
    #[test]
    fn inheritable_field_attributes_reach_the_generated_appearance() {
        // widget -> terminal field (/FT, /Ff, /MaxLen) -> group -> AcroForm /Q.
        let mut doc = Document::with_version("1.7");
        let group_id = doc.new_object_id();
        let field_id = doc.new_object_id();
        let widget = doc.add_object(dictionary! {
            "Type" => name_obj("Annot"), "Subtype" => name_obj("Widget"),
            "Rect" => rect_obj([0.0, 0.0, 200.0, 60.0]),
            "Parent" => Object::Reference(field_id),
        });
        doc.objects.insert(
            field_id,
            Object::Dictionary(dictionary! {
                "FT" => name_obj("Tx"),
                "Ff" => 1 << 12, // Multiline
                "Parent" => Object::Reference(group_id),
                "Kids" => Object::Array(vec![Object::Reference(widget)]),
            }),
        );
        doc.objects.insert(
            group_id,
            Object::Dictionary(dictionary! { "Kids" => Object::Array(vec![Object::Reference(field_id)]) }),
        );
        let acro = doc.add_object(dictionary! {
            "Q" => 2, // right-aligned, document-wide
            "Fields" => Object::Array(vec![Object::Reference(group_id)]),
        });
        let pages = doc.add_object(dictionary! { "Type" => "Pages", "Kids" => Object::Array(vec![]), "Count" => 0 });
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages, "AcroForm" => acro });
        doc.trailer.set("Root", cat);

        assert_eq!(super::inherited_num(&doc, widget, b"Ff"), Some(4096.0), "/Ff up /Parent");

        let handle = next_handle();
        registry().lock().unwrap_or_else(|e| e.into_inner()).insert(handle, doc);
        assert!(set_text_field(handle, encode_id(widget), "alpha beta gamma delta epsilon zeta"));

        let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
        let doc = reg.get(&handle).expect("doc");
        let ap = doc
            .get_dictionary(widget)
            .and_then(|d| d.get(b"AP"))
            .ok()
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .and_then(|ap| ap.get(b"N").ok())
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_stream().ok())
            .expect("/AP /N");
        let c = String::from_utf8_lossy(&ap.content).into_owned();
        assert!(c.matches("Tj").count() > 1, "inherited Multiline never wrapped: {c}");
        // Right-aligned from the AcroForm /Q, so no line starts at the left inset.
        assert!(!c.contains("1 0 0 1 2.00 "), "AcroForm /Q 2 ignored, drew flush left: {c}");
        drop(reg);
        close_document(handle);
    }

    /// §12.7.4.3 Table 226 bit 25: Comb is "meaningful only if the MaxLen entry
    /// is present ... and if the Multiline, Password, and FileSelect flags are
    /// clear". Applied regardless, it chops a multiline value into `MaxLen`
    /// one-character cells on a single row.
    #[test]
    fn comb_is_ignored_when_the_field_is_also_multiline() {
        let combed = build_text_appearance("AB", 100.0, 20.0, 12.0, b"F1", 0, 0, false, true, 5);
        let wrapped = build_text_appearance("AB", 100.0, 20.0, 12.0, b"F1", 0, 0, true, true, 5);
        assert_eq!(String::from_utf8_lossy(&combed).matches("Tj").count(), 2, "comb: one Tj per cell");
        assert_eq!(
            String::from_utf8_lossy(&wrapped).matches("Tj").count(),
            1,
            "multiline + comb must lay out as one line, not per-character cells"
        );
    }

    /// `(h - size)/2` centres the EM BOX, but the baseline is not the bottom of
    /// the em box — glyphs hang below it by the descender. The old expression
    /// therefore sat `0.2445 * size` too LOW, always downward, and since §8.10.1
    /// clips the appearance to its `[0 0 w h]` BBox the descenders of a tight
    /// field were cut off entirely.
    #[test]
    fn the_field_baseline_centres_the_ink_not_the_em_box() {
        const ASC: f64 = 0.718;
        const DESC: f64 = 0.207;
        let baseline = |c: &str| -> f64 {
            let tail = c.split("1 0 0 1 ").nth(1).expect("a Tm");
            tail.split_whitespace().nth(1).expect("the y operand").parse().expect("a number")
        };
        for (h, size) in [(20.0, 12.0), (14.0, 12.0), (30.0, 9.0), (12.0, 10.0)] {
            let c = String::from_utf8(build_text_appearance(
                "gyp", 100.0, h, size, b"F1", 0xFF00_0000, 0, false, false, 0,
            ))
            .expect("utf8");
            let y = baseline(&c);

            // The descender clears the bottom of the BBox — the clipping failure.
            let below = y - DESC * size;
            assert!(below >= -0.01, "h={h} size={size}: descender at {below} is clipped away");
            // Ink is centred: the gap below equals the gap above.
            let above = h - (y + ASC * size);
            assert!(
                (below - above).abs() < 0.02,
                "h={h} size={size}: ink not centred, {below} below vs {above} above"
            );
            // And it sits strictly higher than the old em-box expression.
            assert!(y > (h - size) / 2.0, "h={h} size={size}: must be above the em-box baseline");
        }
    }

    /// §12.5.2 does not require `/Annots` entries to be indirect, and
    /// `render_annotations` derefs either form — so a link written as a DIRECT
    /// dictionary painted but never reached `list_links`, leaving the user a
    /// visible link they could not tap. These records carry no object id, so
    /// unlike the field and annotation lists there is no identity to invent.
    #[test]
    fn a_direct_annotation_dictionary_is_still_a_tappable_link() {
        for direct in [false, true] {
            let mut doc = Document::with_version("1.7");
            let pages_id = doc.new_object_id();
            let target = doc.add_object(dictionary! {
                "Type" => name_obj("Page"),
                "Parent" => Object::Reference(pages_id),
                "MediaBox" => rect_obj([0.0, 0.0, 200.0, 200.0]),
            });
            let link = dictionary! {
                "Type" => name_obj("Annot"),
                "Subtype" => name_obj("Link"),
                "Rect" => rect_obj([10.0, 10.0, 90.0, 30.0]),
                "Dest" => Object::Array(vec![Object::Reference(target), name_obj("Fit")]),
            };
            let entry = if direct {
                Object::Dictionary(link)
            } else {
                Object::Reference(doc.add_object(link))
            };
            let page = doc.add_object(dictionary! {
                "Type" => name_obj("Page"),
                "Parent" => Object::Reference(pages_id),
                "MediaBox" => rect_obj([0.0, 0.0, 200.0, 200.0]),
                "Annots" => Object::Array(vec![entry]),
            });
            doc.objects.insert(
                pages_id,
                Object::Dictionary(dictionary! {
                    "Type" => name_obj("Pages"),
                    "Kids" => Object::Array(vec![Object::Reference(page), Object::Reference(target)]),
                    "Count" => 2,
                }),
            );
            let cat = doc.add_object(dictionary! {
                "Type" => name_obj("Catalog"),
                "Pages" => Object::Reference(pages_id),
            });
            doc.trailer.set("Root", cat);

            let handle = next_handle();
            registry().lock().unwrap_or_else(|e| e.into_inner()).insert(handle, doc);
            let buf = list_links(handle, 0).expect("links");
            let count = u32::from_le_bytes(buf[0..4].try_into().expect("count"));
            assert_eq!(count, 1, "direct={direct}: the link must be listed");
            close_document(handle);
        }
    }

    /// A document whose catalog carries the given AcroForm.
    fn form_doc(acroform: Dictionary) -> Document {
        let mut doc = Document::with_version("1.7");
        let acro = doc.add_object(acroform);
        let pages = doc.add_object(dictionary! { "Type" => "Pages", "Kids" => Object::Array(vec![]), "Count" => 0 });
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages, "AcroForm" => acro });
        doc.trailer.set("Root", cat);
        doc
    }

    fn appearance_of(doc: &Document, widget: &Dictionary) -> String {
        let (content, _, _, _) = super::widget_value_appearance(doc, widget, [0.0, 0.0, 100.0, 20.0])
            .expect("a value appearance");
        String::from_utf8_lossy(&content).into_owned()
    }

    /// §12.7.4.4: a choice field's `/V` holds the EXPORT value while the widget
    /// shows the display string from `/Opt`. `set_choice_field` writes the export
    /// and sets `/NeedAppearances`, so without the mapping back a field this
    /// crate itself filled in would re-render showing the export code the user
    /// never saw.
    #[test]
    fn a_choice_widgets_export_value_renders_as_its_display_string() {
        let mut w = dictionary! {
            "FT" => name_obj("Ch"),
            "DA" => Object::string_literal("/Helv 10 Tf 0 g"),
            "V" => Object::string_literal("US"),
            "Opt" => Object::Array(vec![Object::Array(vec![
                Object::string_literal("US"),
                Object::string_literal("United States"),
            ])]),
        };
        let doc = form_doc(Dictionary::new());
        assert!(appearance_of(&doc, &w).contains("(United States) Tj"), "export must map to display");

        // No /Opt entry matches: the raw value is better than nothing.
        w.set("V", Object::string_literal("ZZ"));
        assert!(appearance_of(&doc, &w).contains("(ZZ) Tj"), "unmatched value still renders");
    }

    /// The Multiline and Comb bits of §12.7.4.3 Table 226 are TEXT-field flags;
    /// a choice field's `/Ff` uses those same bit positions for Combo, Sort and
    /// friends. Honouring them on a `/Ch` wraps or combs the value on the
    /// strength of an unrelated flag.
    #[test]
    fn choice_field_flags_are_not_read_as_text_field_layout_flags() {
        let mut w = dictionary! {
            "FT" => name_obj("Ch"),
            "DA" => Object::string_literal("/Helv 10 Tf 0 g"),
            "V" => Object::string_literal("ABCDE"),
            "MaxLen" => 5,
            // Bit 25 is Comb on a /Tx and CommitOnSelChange on a /Ch.
            "Ff" => Object::Integer(1 << 24),
        };
        let doc = form_doc(Dictionary::new());
        assert_eq!(appearance_of(&doc, &w).matches("Tj").count(), 1, "a choice value is one line");

        w.set("FT", name_obj("Tx"));
        assert_eq!(
            appearance_of(&doc, &w).matches("Tj").count(),
            5,
            "the same flag on a text field IS Comb"
        );
    }

    /// §12.7.2 Table 218 makes the AcroForm `/DA` and `/Q` the document-wide
    /// defaults. The renderer resolves a widget from its DICTIONARY rather than
    /// its id, so it cannot use `field_attr`; both routes must still land on the
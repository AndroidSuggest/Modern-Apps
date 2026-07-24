#[cfg(test)]
mod tests {
use crate::*;
    use lopdf::content::{Content, Operation};
    use lopdf::{dictionary, Object, Stream};

    /// Build a one-page PDF in memory with a filled rectangle and one text run,
    /// then check the interpreted page size and primitives.
    #[test]
    fn interprets_rect_and_text() {
        let mut doc = Document::with_version("1.5");

        let content = Content {
            operations: vec![
                Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
                Operation::new("re", vec![100.into(), 100.into(), 50.into(), 40.into()]),
                Operation::new("f", vec![]),
                Operation::new("BT", vec![]),
                Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
                Operation::new("Td", vec![72.into(), 700.into()]),
                Operation::new("Tj", vec![Object::string_literal("Hi")]),
                Operation::new("ET", vec![]),
            ],
        };
        let content_data = content.encode().unwrap();
        let content_id = doc.add_object(Stream::new(dictionary! {}, content_data));

        let font_id = doc.add_object(dictionary! {
            "Type" => "Font",
            "Subtype" => "Type1",
            "BaseFont" => "Helvetica",
        });
        let resources = dictionary! {
            "Font" => dictionary! { "F1" => font_id },
        };

        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => content_id,
            "Resources" => resources,
        });
        let pages = dictionary! {
            "Type" => "Pages",
            "Kids" => vec![page_id.into()],
            "Count" => 1,
        };
        doc.objects.insert(pages_id, Object::Dictionary(pages));
        let catalog_id = doc.add_object(dictionary! {
            "Type" => "Catalog",
            "Pages" => pages_id,
        });
        doc.trailer.set("Root", catalog_id);

        let page = interpret_page(&doc, page_id).expect("interpret should succeed");
        assert_eq!(page.width, 612.0);
        assert_eq!(page.height, 792.0);

        let fills: Vec<&Prim> = page
            .prims
            .iter()
            .filter(|p| matches!(p, Prim::Fill { .. }))
            .collect();
        assert_eq!(fills.len(), 1, "expected one filled rectangle");
        if let Prim::Fill { argb, pts, .. } = fills[0] {
            assert_eq!(*argb, 0xFFFF0000, "fill should be red");
            assert!(pts.len() >= 4, "rectangle should have >=4 points");
            assert_eq!(pts[0], (100.0, 100.0));
        }

        let texts: Vec<&Prim> = page
            .prims
            .iter()
            .filter(|p| matches!(p, Prim::Text { .. }))
            .collect();
        // Per-glyph emission: "Hi" -> two glyph primitives.
        assert_eq!(texts.len(), 2, "expected two glyph runs for \"Hi\"");
        if let Prim::Text { x, y, size, text, .. } = texts[0] {
            assert_eq!(text, "H");
            assert_eq!(*x, 72.0);
            assert_eq!(*y, 700.0);
            assert_eq!(*size, 12.0);
        }
        if let Prim::Text { text, .. } = texts[1] {
            assert_eq!(text, "i");
        }
    }

    /// Two consecutive `Tj` runs on one line must not stack at the same x: the
    /// second run is offset by the first run's glyph-width advance.
    #[test]
    fn text_advances_by_glyph_widths() {
        let doc = Document::with_version("1.5");
        let fi = FontInfo {
            two_byte: false,
            to_unicode: None,
            encoding: HashMap::new(),
            cmap_uni: HashMap::new(),
            // 'A' (0x41) and 'B' (0x42) each 500 glyph units => 0.5.
            widths: HashMap::from([(0x41, 0.5), (0x42, 0.5)]),
            default_width: 0.5,
            t3: None,
        };
        let mut fonts = HashMap::new();
        fonts.insert(b"F1".to_vec(), fi);

        let mut gs = GraphicsState::default();
        gs.font_key = b"F1".to_vec();
        gs.font_size = 10.0;

        let mut prims = Vec::new();
        let mut tm = translate(0.0, 100.0);

        let adv1 = show_string(&doc, &mut prims, &gs, &fonts, &tm, b"AB", 0);
        tm = mat_mul(&translate(adv1, 0.0), &tm);
        let _adv2 = show_string(&doc, &mut prims, &gs, &fonts, &tm, b"AB", 0);

        // Per-glyph emission: run "AB" -> 2 prims; advance = 2*0.5*10 = 10.
        assert!((adv1 - 10.0).abs() < 1e-6, "advance was {adv1}");
        let xs: Vec<f32> = prims
            .iter()
            .filter_map(|p| match p {
                Prim::Text { x, .. } => Some(*x),
                _ => None,
            })
            .collect();
        assert_eq!(xs.len(), 4, "expected 4 glyphs across 2 runs");
        assert_eq!(xs[0], 0.0); // first 'A'
        assert_eq!(xs[1], 5.0); // 'B' advanced by 0.5*10
        assert!((xs[2] - 10.0).abs() < 1e-4, "second run 'A' x was {}", xs[2]);
    }

    /// Invisible text (render mode 3) advances the cursor but emits no glyphs.
    #[test]
    fn invisible_text_not_emitted() {
        let doc = Document::with_version("1.5");
        let fi = FontInfo {
            two_byte: false,
            to_unicode: None,
            encoding: HashMap::new(),
            cmap_uni: HashMap::new(),
            widths: HashMap::new(),
            default_width: 0.5,
            t3: None,
        };
        let mut fonts = HashMap::new();
        fonts.insert(b"F1".to_vec(), fi);
        let mut gs = GraphicsState::default();
        gs.font_key = b"F1".to_vec();
        gs.font_size = 10.0;
        gs.render_mode = 3;
        let mut prims = Vec::new();
        let adv = show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"hidden", 0);
        assert!(adv > 0.0);
        assert!(prims.is_empty(), "mode-3 text should not be drawn");
    }

    /// A Type 3 glyph whose CharProc fills a rectangle must emit Fill prims.
    #[test]
    fn type3_glyph_emits_prims() {
        let mut doc = Document::with_version("1.5");
        // CharProc: paint a filled rectangle in glyph space.
        let proc_content = Content {
            operations: vec![
                Operation::new("re", vec![0.into(), 0.into(), 700.into(), 700.into()]),
                Operation::new("f", vec![]),
            ],
        };
        let proc_data = proc_content.encode().unwrap();
        let proc_id = doc.add_object(Stream::new(dictionary! {}, proc_data));
        let char_procs = doc.add_object(dictionary! { "a" => proc_id });
        let encoding = doc.add_object(dictionary! {
            "Type" => "Encoding",
            "Differences" => vec![65.into(), "a".into()],
        });
        let font = dictionary! {
            "Type" => "Font",
            "Subtype" => "Type3",
            "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
            "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
            "CharProcs" => char_procs,
            "Encoding" => encoding,
            "FirstChar" => 65,
            "LastChar" => 65,
            "Widths" => vec![700.into()],
            "Resources" => dictionary! {},
        };
        let fi = font_info(&doc, &font);
        assert!(fi.t3.is_some(), "should parse as Type3");
        let mut fonts = HashMap::new();
        fonts.insert(b"F1".to_vec(), fi);
        let mut gs = GraphicsState::default();
        gs.font_key = b"F1".to_vec();
        gs.font_size = 12.0;
        let mut prims = Vec::new();
        let adv = show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"A", 0);
        assert!(adv > 0.0, "advance should be positive");
        let fills = prims.iter().filter(|p| matches!(p, Prim::Fill { .. })).count();
        assert!(fills >= 1, "type3 glyph should emit at least one Fill prim");
    }

    /// Round-trip a full open -> count -> render -> close cycle via the byte API.
    #[test]
    fn open_render_close_roundtrip() {
        let mut doc = Document::with_version("1.5");
        let content = Content {
            operations: vec![
                Operation::new("re", vec![0.into(), 0.into(), 10.into(), 10.into()]),
                Operation::new("f", vec![]),
            ],
        };
        let content_data = content.encode().unwrap();
        let content_id = doc.add_object(Stream::new(dictionary! {}, content_data));
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 200.into(), 300.into()],
            "Contents" => content_id,
            "Resources" => dictionary! {},
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages",
                "Kids" => vec![page_id.into()],
                "Count" => 1,
            }),
        );
        let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", catalog_id);

        let mut bytes = Vec::new();
        doc.save_to(&mut bytes).unwrap();

        let handle = open_document(&bytes);
        assert_ne!(handle, 0);
        assert_eq!(page_count(handle), 1);
        let buf = render_page(handle, 0).expect("render should succeed");
        // Header v2: MAGIC, VERSION, width, height, count.
        let magic = u32::from_le_bytes(buf[0..4].try_into().unwrap());
        assert_eq!(magic, 0x50444657);
        let width = f32::from_le_bytes(buf[8..12].try_into().unwrap());
        let height = f32::from_le_bytes(buf[12..16].try_into().unwrap());
        assert_eq!(width, 200.0);
        assert_eq!(height, 300.0);
        close_document(handle);
        assert_eq!(page_count(handle), 0);
    }
}

#[cfg(test)]
mod edit_render_tests {
use crate::*;
    use lopdf::{dictionary, Stream};

    fn one_page_pdf() -> Vec<u8> {
        let mut doc = Document::with_version("1.5");
        let content = lopdf::content::Content {
            operations: vec![lopdf::content::Operation::new("re", vec![0.into(), 0.into(), 10.into(), 10.into()])],
        };
        let cid = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cid, "Resources" => dictionary! {},
        });
        doc.objects.insert(pages_id, Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }));
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", cat);
        let mut bytes = Vec::new();
        doc.save_to(&mut bytes).unwrap();
        bytes
    }

    #[test]
    fn added_rect_annotation_renders() {
        let bytes = one_page_pdf();
        let handle = open_document(&bytes);
        assert_ne!(handle, 0);
        let id = add_square(handle, 0, [100.0, 100.0, 300.0, 250.0], 0xFFFF0000, 2.0, false);
        assert!(id.is_some() && id != Some(0), "add_square failed: {id:?}");

        let buf = render_page(handle, 0).expect("render");
        // Header v2: magic, version, w,h,count =16 bytes
        let count = u32::from_le_bytes(buf[16..20].try_into().unwrap());
        let mut pos = 20;
        let mut strokes = 0;
        for _ in 0..count {
            let tag = buf[pos]; pos += 1;
            match tag {
                1 => {
                    pos += 12; // x,y,size
                    pos += 4; // argb
                    let l = u16::from_le_bytes(buf[pos..pos+2].try_into().unwrap()) as usize; pos+=2;
                    pos += l;
                    pos += 1+4+4; // hasStroke, strokeArgb, strokeWidth
                }
                2 => {
                    pos += 4; pos +=1;
                    let n = u16::from_le_bytes(buf[pos..pos+2].try_into().unwrap()) as usize; pos+=2;
                    pos += n*8;
                }
                3 => {
                    strokes+=1;
                    pos+=4; pos+=4;
                    let nd = buf[pos] as usize; pos+=1;
                    pos+= nd*4;
                    pos+=4; // phase
                    pos+=1; // cap
                    pos+=1; // join
                    pos+=4; // miter
                    let n = u16::from_le_bytes(buf[pos..pos+2].try_into().unwrap()) as usize; pos+=2;
                    pos+= n*8;
                }
                4 => {
                    pos+=24; pos+=4; pos+=4; pos+=1;
                    let len = u32::from_le_bytes(buf[pos..pos+4].try_into().unwrap()) as usize; pos+=4;
                    pos+=len;
                }
                5 => { pos+=1; let n = u16::from_le_bytes(buf[pos..pos+2].try_into().unwrap()) as usize; pos+=2; pos+=n*8; }
                6 => {},
                _ => panic!("bad tag {tag}"),
            }
        }
        println!("prims={count} strokes={strokes}");
        assert!(strokes >= 1, "expected the annotation stroke to render, got {strokes}");
        close_document(handle);
    }
}

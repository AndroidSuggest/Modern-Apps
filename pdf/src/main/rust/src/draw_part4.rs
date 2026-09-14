    /// The no-metrics fallback returns a run advance of `len * 0.5 * Tfs * Th` but
    /// used to put ONE glyph's `size` on the wire as the run's device advance. A
    /// non-painted run (mode 3 — the OCR layer of a scan with an unresolvable font
    /// resource) is aligned to that field by the selection layer, so every glyph's
    /// selection rectangle piled up on the first character.
    #[test]
    fn a_run_with_no_font_metrics_reports_the_whole_runs_advance() {
        let doc = Document::with_version("1.7");
        let fonts: HashMap<Vec<u8>, FontInfo> = HashMap::new();
        let gs = GraphicsState { font_key: b"F1".to_vec(), font_size: 10.0, ..Default::default() };
        let mut prims = Vec::new();
        let pen = show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"ABCD", 0);
        let advance = prims
            .iter()
            .find_map(|p| match p {
                Prim::Text { advance, .. } => Some(*advance),
                _ => None,
            })
            .expect("the run must still reach the text index");
        assert!((pen - 20.0).abs() < 1e-9, "4 codes at 0.5 em of 10 Tf");
        assert!(
            (advance - pen as f32).abs() < 1e-3,
            "the wire advance must span the whole run ({pen}), got {advance}"
        );
    }
}

#[cfg(test)]
mod type3_cap_tests {
    use super::MAX_TYPE3_DEPTH;
    use crate::*;
    use lopdf::content::{Content, Operation};
    use lopdf::{dictionary, Stream};

    /// The clip record must survive the recursion/glyph caps.
    ///
    /// A capped glyph takes the `drawable` branch and is then skipped, so when the
    /// record was emitted in an `else` on `drawable` it was lost: the run vanished
    /// from the search index AND contributed nothing to the text clip, which under
    /// an unconditional §9.4.3 clip means a capped Type 3 run blanks the page rather
    /// than merely losing its glyphs. Asserted with `depth == MAX_TYPE3_DEPTH`
    /// because that is the cheapest cap to reach deterministically; the glyph and
    /// primitive caps share the same `if`.
    #[test]
    fn a_capped_type3_glyph_still_emits_its_clip_record() {
        let build = |render_mode: i64, depth: u32| -> Vec<Prim> {
            let mut doc = Document::with_version("1.7");
            let proc_id = doc.add_object(Stream::new(
                dictionary! {},
                Content { operations: vec![
                    // §9.6.5 makes d0/d1 mandatory as the CharProc's first operator,
                    // so a fixture without one is not a shape the renderer ever sees.
                    Operation::new("d0", vec![1000.into(), 0.into()]),
                    Operation::new("re", vec![0.into(), 0.into(), 750.into(), 750.into()]),
                    Operation::new("f", vec![]),
                ]}.encode().unwrap(),
            ));
            let font = dictionary! {
                "Type" => "Font", "Subtype" => "Type3",
                "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
                "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
                "CharProcs" => doc.add_object(dictionary! { "a" => proc_id }),
                "Encoding" => doc.add_object(dictionary! {
                    "Type" => "Encoding", "Differences" => vec![97.into(), "a".into()],
                }),
                "FirstChar" => 97, "LastChar" => 97, "Widths" => vec![1000.into()],
            };
            let mut fonts = HashMap::new();
            fonts.insert(b"F1".to_vec(), font_info(&doc, &font));
            let gs = GraphicsState {
                font_key: b"F1".to_vec(),
                font_size: 100.0,
                render_mode,
                ..Default::default()
            };
            let mut prims = Vec::new();
            show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"a", depth);
            prims
        };

        // Precondition: under the cap this glyph really does paint, so the capped
        // comparison below is measuring the cap and not a broken fixture.
        assert!(
            build(4, 0).iter().any(|p| matches!(p, Prim::Fill { .. })),
            "precondition: an uncapped mode-4 glyph must paint its CharProc"
        );

        for rm in [4u8, 5, 6, 7] {
            let prims = build(rm as i64, MAX_TYPE3_DEPTH);
            assert!(
                !prims.iter().any(|p| matches!(p, Prim::Fill { .. } | Prim::Stroke { .. })),
                "precondition: the cap must actually suppress the CharProc for mode {rm}"
            );
            let texts: Vec<_> = prims
                .iter()
                .filter_map(|p| match p {
                    Prim::Text { render_mode, .. } => Some(*render_mode),
                    _ => None,
                })
                .collect();
            assert_eq!(
                texts,
                vec![rm],
                "a capped mode-{rm} Type 3 glyph must STILL emit its clip record — \
                 dropping it makes an unconditional text clip blank the page"
            );
        }
    }

    /// §9.3.6 Table 106 + §9.4.3: every clip mode (4, 5, 6, 7) must put something on
    /// the wire that the consumer can accumulate into the text clip, and §9.6.5
    /// grants Type 3 no exemption.
    ///
    /// The consumer builds text clips ONLY from `Prim::Text` with `render_mode`
    /// 4..=7. A Type 3 run emitted its CharProc's own Fill/Stroke prims, which carry
    /// no glyph outline, and mode 7 emitted nothing whatsoever — so a Type 3 run
    /// contributed nothing to the clip in ANY mode. While an empty accumulation
    /// installed no clip that merely painted art unclipped; once an empty
    /// accumulation correctly clips to EMPTY it blanks the page instead, so this is
    /// what keeps mode 4-6 Type 3 content from disappearing.
    ///
    /// Modes 4-6 must ALSO still emit the CharProc's ink, and must not paint the
    /// substitute glyph on top of it — hence `argb == 0` on the record.
    #[test]
    fn type3_clip_modes_all_emit_an_accumulable_text_record() {
        let build = |render_mode: i64| -> Vec<Prim> {
            let mut doc = Document::with_version("1.7");
            let proc_id = doc.add_object(Stream::new(
                dictionary! {},
                Content { operations: vec![
                    // §9.6.5 makes d0/d1 mandatory as the CharProc's first operator,
                    // so a fixture without one is not a shape the renderer ever sees.
                    Operation::new("d0", vec![1000.into(), 0.into()]),
                    Operation::new("re", vec![0.into(), 0.into(), 750.into(), 750.into()]),
                    Operation::new("f", vec![]),
                ]}.encode().unwrap(),
            ));
            let font = dictionary! {
                "Type" => "Font", "Subtype" => "Type3",
                "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
                "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
                "CharProcs" => doc.add_object(dictionary! { "a" => proc_id }),
                "Encoding" => doc.add_object(dictionary! {
                    "Type" => "Encoding", "Differences" => vec![97.into(), "a".into()],
                }),
                "FirstChar" => 97, "LastChar" => 97, "Widths" => vec![1000.into()],
            };
            let mut fonts = HashMap::new();
            fonts.insert(b"F1".to_vec(), font_info(&doc, &font));
            let gs = GraphicsState {
                font_key: b"F1".to_vec(),
                font_size: 100.0,
                render_mode,
                ..Default::default()
            };
            let mut prims = Vec::new();
            show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"a", 0);
            prims
        };

        // Control: a pure paint mode must emit the CharProc's ink and NO clip record.
        let prims = build(0);
        assert!(
            prims.iter().any(|p| matches!(p, Prim::Fill { .. })),
            "precondition: mode 0 must paint the CharProc, or this test proves nothing"
        );
        assert!(
            !prims.iter().any(|p| matches!(p, Prim::Text { .. })),
            "mode 0 needs no clip record"
        );

        for rm in [4u8, 5, 6, 7] {
            let prims = build(rm as i64);
            let texts: Vec<_> = prims
                .iter()
                .filter_map(|p| match p {
                    Prim::Text { render_mode, argb, .. } => Some((*render_mode, *argb)),
                    _ => None,
                })
                .collect();
            assert_eq!(
                texts.len(),
                1,
                "mode {rm} must emit exactly one accumulable Text record, got {texts:?}"
            );
            assert_eq!(texts[0].0, rm, "the record must carry its real render mode");
            assert_eq!(
                texts[0].1, 0,
                "mode {rm} clip record must be transparent — the CharProc already \
                 supplies the ink, and painting the substitute glyph would double it"
            );

            // 4-6 paint as well as clip; 7 is clip-only and must lay down no ink.
            let ink = prims
                .iter()
                .filter(|p| matches!(p, Prim::Fill { .. } | Prim::Stroke { .. }))
                .count();
            if rm == 7 {
                assert_eq!(ink, 0, "mode 7 is clip-only and must paint nothing");
            } else {
                assert!(ink > 0, "mode {rm} must still paint the CharProc's ink");
            }
        }
    }

    /// §9.6.5 + §11.6.5.1: when the per-glyph primitive bound falls inside a
    /// soft-mask bracket, the glyph must keep painting AND keep its real mask.
    ///
    /// A soft mask is the one bracket that cannot be closed after the fact — per
    /// `model.rs` the mask is what follows `SoftMaskContent`, so appending a bare
    /// `SoftMaskPop` yields an EMPTY mask, which hides the content it exists to
    /// reveal. And cutting back to before the `SoftMaskPush` discards the whole
    /// glyph, because `wrap_with_soft_mask` coalesces consecutive paints under one
    /// mask into a SINGLE bracket whose push sits at the very first prim. Both
    /// failure modes are silent, which is why this is asserted rather than reasoned.
    #[test]
    fn per_glyph_cap_inside_a_soft_mask_keeps_the_glyph_and_the_mask() {
        let mut doc = Document::with_version("1.7");
        let mask_id = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Form",
                "BBox" => vec![0.into(), 0.into(), 1000.into(), 1000.into()],
                "Group" => dictionary! { "S" => "Transparency" },
            },
            Content { operations: vec![
                Operation::new("rg", vec![1.0.into(), 1.0.into(), 1.0.into()]),
                Operation::new("re", vec![0.into(), 0.into(), 1000.into(), 1000.into()]),
                Operation::new("f", vec![]),
            ]}.encode().unwrap(),
        ));
        let gs_id = doc.add_object(dictionary! {
            "SMask" => dictionary! { "S" => "Luminosity", "G" => Object::Reference(mask_id) },
        });
        // Well past the cap, so it lands in the masked content rather than the mask.
        let mut src = String::from("/GS1 gs\n");
        for i in 0..(MAX_TYPE3_PRIMS_PER_GLYPH * 3) {
            src.push_str(&format!("0 {} 10 10 re f\n", i % 600));
        }
        let proc_id = doc.add_object(Stream::new(dictionary! {}, src.into_bytes()));
        let font = dictionary! {
            "Type" => "Font", "Subtype" => "Type3",
            "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
            "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
            "CharProcs" => doc.add_object(dictionary! { "a" => proc_id }),
            "Encoding" => doc.add_object(dictionary! {
                "Type" => "Encoding", "Differences" => vec![65.into(), "a".into()],
            }),
            "FirstChar" => 65, "LastChar" => 65, "Widths" => vec![700.into()],
            "Resources" => dictionary! { "ExtGState" => dictionary! { "GS1" => gs_id } },
        };
        let mut fonts = HashMap::new();
        fonts.insert(b"F1".to_vec(), font_info(&doc, &font));
        let gs = GraphicsState { font_key: b"F1".to_vec(), font_size: 100.0, ..Default::default() };
        let mut prims = Vec::new();
        // Two glyphs, so a mis-cut on the first corrupts the one after it.
        show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"AA", 0);

        let count = |f: fn(&Prim) -> bool| prims.iter().filter(|p| f(p)).count();
        let fills = count(|p| matches!(p, Prim::Fill { .. }));
        let pushes = count(|p| matches!(p, Prim::SoftMaskPush { .. }));
        let contents = count(|p| matches!(p, Prim::SoftMaskContent));
        println!(
            "type3 smask cap: {} prims, {fills} fills, {pushes} push / {contents} content \
             (MAX_TYPE3_PRIMS_PER_GLYPH={MAX_TYPE3_PRIMS_PER_GLYPH})",
            prims.len()
        );
        assert!(fills > 0, "the glyph must still paint after being capped");
        assert!(
            fills < MAX_TYPE3_PRIMS_PER_GLYPH * 3,
            "the per-glyph bound must actually bind, or this proves nothing"
        );
        assert_eq!(
            pushes, contents,
            "every SoftMaskPush must keep its SoftMaskContent — a bracket closed \
             without one has an empty mask and hides the content it should reveal"
        );
        let mut depth = 0i32;
        for (i, p) in prims.iter().enumerate() {
            match p {
                Prim::SoftMaskPush { .. } => depth += 1,
                Prim::SoftMaskPop => {
                    depth -= 1;
                    assert!(depth >= 0, "unmatched SoftMaskPop at prim {i}");
                }
                _ => {}
            }
        }
        assert_eq!(depth, 0, "{depth} soft-mask level(s) left open by the cap");
    }
}

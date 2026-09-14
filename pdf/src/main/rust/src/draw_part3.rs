#[cfg(test)]
mod blind_reaudit_r5_text_tests {
    use crate::outlines::GlyphProgram;
    use crate::type1::Type1Font;
    use crate::*;
    use std::sync::Arc;

    /// A 1000x1000 box on a 1000-unit em, reachable as glyph name "A" at code 65.
    fn embedded_box_font(wmode: u8) -> FontInfo {
        let t1 = Type1Font {
            glyphs: [(
                "A".to_string(),
                vec![vec![(0.0, 0.0), (1000.0, 0.0), (1000.0, 1000.0), (0.0, 1000.0), (0.0, 0.0)]],
            )]
            .into_iter()
            .collect(),
            encoding: [(65u32, "A".to_string())].into_iter().collect(),
            font_matrix: [0.001, 0.0, 0.0, 0.001, 0.0, 0.0],
        };
        FontInfo {
            two_byte: false,
            wmode,
            vertical_metrics: Arc::default(),
            default_vertical: (0.880, -1.0),
            cid_to_gid: None,
            to_unicode: None,
            encoding: Arc::new([(65u32, 'A')].into_iter().collect()),
            cmap_uni: Arc::default(),
            cmap: None,
            widths: Arc::new([(65u32, 0.5)].into_iter().collect()),
            default_width: 0.5,
            t3: None,
            style: FontStyle::default(),
            family: 0,
            base_font: String::new(),
            glyph_program: Some(Arc::new(GlyphProgram::Type1(t1))),
            glyph_names: Arc::new([(65u32, "A".to_string())].into_iter().collect()),
        }
    }

    fn show(fi: FontInfo, gs: GraphicsState) -> Vec<Prim> {
        let doc = Document::with_version("1.7");
        let mut fonts = HashMap::new();
        fonts.insert(b"F1".to_vec(), fi);
        let mut prims = Vec::new();
        show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"A", 0);
        prims
    }

    fn state(render_mode: i64) -> GraphicsState {
        GraphicsState {
            font_key: b"F1".to_vec(),
            font_size: 100.0,
            render_mode,
            ..Default::default()
        }
    }

    /// §8.4.3.2: `w` is "a nonnegative number expressed in USER SPACE units", and
    /// stroking paints the points whose perpendicular distance from the path IN USER
    /// SPACE is at most half of it. So the pen is scaled by the CTM alone; the text
    /// matrix and font size scale the GLYPH, not the pen, and §9.3.6 grants text no
    /// exemption. pdf.js, pdfium and MuPDF all do exactly this.
    ///
    /// The pen used to be scaled by Trm = Tm·CTM, which on the shape cairo emits by
    /// default — `0.5 w BT 12 0 0 12 100 700 Tm 2 Tr (Hg) Tj ET` — turned a 0.5pt pen
    /// into 6.0pt. A 12pt glyph has ~1.2pt stems, so a 6pt pen adds 3pt each side,
    /// every counter in H/g/e/o closes and the word paints as a solid black slab.
    ///
    /// Nothing else in the tree pins this value, so this test is the only thing
    /// stopping it. Both consumers of the width — `Prim::Stroke.width` on the
    /// embedded-outline path and `Prim::Text.stroke_width` on the substitute path —
    /// read the one binding, so the outline path is asserted here as the strict case
    /// (`Prim::Stroke.width` is additionally floored at 0.1, which 0.5 clears).
    #[test]
    fn a_text_pen_is_scaled_by_the_ctm_not_by_the_text_matrix() {
        let stroke_width = |line_width: f64, ctm: Mat, tm: Mat| -> f32 {
            let doc = Document::with_version("1.7");
            let mut fonts = HashMap::new();
            fonts.insert(b"F1".to_vec(), embedded_box_font(0));
            let gs = GraphicsState {
                font_key: b"F1".to_vec(),
                font_size: 12.0,
                render_mode: 1, // stroke only, so the outline path emits Prim::Stroke
                line_width,
                ctm,
                ..Default::default()
            };
            let mut prims = Vec::new();
            show_string(&doc, &mut prims, &gs, &fonts, &tm, b"A", 0);
            prims
                .iter()
                .find_map(|p| match p {
                    Prim::Stroke { width, .. } => Some(*width),
                    _ => None,
                })
                .expect("mode 1 with an embedded program must emit a Prim::Stroke")
        };

        let identity: Mat = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0];
        let cairo_tm: Mat = [12.0, 0.0, 0.0, 12.0, 100.0, 700.0];

        // The regression itself: a 12x text matrix must not touch the pen.
        let w = stroke_width(0.5, identity, cairo_tm);
        assert!(
            (w - 0.5).abs() < 1e-5,
            "Tm [12 0 0 12] with 0.5 w must give a 0.5 pen, got {w} \
             (the old Trm scaling gave 6.0 — 12x too heavy)"
        );

        // The second reported shape, 3x too heavy.
        let w = stroke_width(1.0, identity, [3.0, 0.0, 0.0, 3.0, 0.0, 0.0]);
        assert!((w - 1.0).abs() < 1e-5, "Tm [3 0 0 3] with 1 w must give 1.0, got {w}");

        // The CTM, and only the CTM, still scales it — a zoomed/scaled page must
        // not lose its stroke weight, or the fix would just be a different bug.
        let w = stroke_width(0.5, [4.0, 0.0, 0.0, 4.0, 0.0, 0.0], cairo_tm);
        assert!((w - 2.0).abs() < 1e-5, "0.5 w under a 4x CTM must give 2.0, got {w}");

        // The case that was already correct and must not regress.
        let w = stroke_width(2.0, identity, [1.0, 0.0, 0.0, 1.0, 100.0, 700.0]);
        assert!((w - 2.0).abs() < 1e-5, "identity Tm and CTM must give line_width, got {w}");
    }

    /// A non-empty string can show ZERO glyphs, so "the string was non-empty" is not
    /// a safe stand-in for "a glyph was shown".
    ///
    /// `fonts.rs`'s Identity-H/V branch is `while i + 1 < bytes.len()`, so a 2-byte
    /// font consumes codes in pairs and an ODD-length string drops its final byte —
    /// a 1-byte string yields no codes at all and emits nothing.
    ///
    /// This matters beyond this file. `interpret.rs::latches_text_clip` latches the
    /// §9.4.3 text clip on `shown_mode >= 4 && !bytes.is_empty()`. On this input that
    /// latches a clip for a run that produced no glyph outline, so `ET` emits the
    /// marker, the consumer's accumulation is empty, and an empty accumulation clips
    /// to NOTHING — blanking every following prim up to the matching pop. The
    /// predicate needs "a glyph was actually iterated", which is knowable only here
    /// and not from the byte string at the show site.
    ///
    /// The sibling branch differs: the non-Identity CMap path advances by
    /// `min(bytes.len() - i, ..)` and so DOES yield a code for a 1-byte remainder.
    /// The defect is specific to Identity-H/V, which is why reading the show sites
    /// alone cannot reveal it.
    #[test]
    fn an_odd_length_identity_h_string_shows_no_glyph_despite_non_empty_bytes() {
        let identity_h = || {
            let mut fi = embedded_box_font(0);
            fi.two_byte = true;
            fi.cmap = None; // Identity-H: fixed 2-byte codes
            fi.glyph_program = None; // substitute path, so `s` alone decides emission
            fi
        };
        let run = |bytes: &[u8]| -> usize {
            let doc = Document::with_version("1.7");
            let mut fonts = HashMap::new();
            fonts.insert(b"F1".to_vec(), identity_h());
            // Mode 7: clip-only, which is where the latch mistake becomes destructive.
            let gs = GraphicsState {
                font_key: b"F1".to_vec(),
                font_size: 12.0,
                render_mode: 7,
                ..Default::default()
            };
            let mut prims = Vec::new();
            show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, bytes, 0);
            prims.len()
        };

        // Precondition: a complete 2-byte code does show a glyph, so the assertion
        // below measures the odd length rather than a broken fixture.
        assert!(run(&[0x00, 0x41]) > 0, "precondition: a complete 2-byte code must show a glyph");

        assert_eq!(
            run(&[0x41]),
            0,
            "a 1-byte string in an Identity-H font is non-empty yet shows no glyph; \
             latches_text_clip's `!bytes.is_empty()` therefore latches a clip with no \
             outline behind it, which clips the following content to nothing"
        );
    }

    /// C4: an embedded font in a clip mode must paint from its OWN outline.
    ///
    /// The outline was gated on modes 0..=2, so an embedded font in Tr 4/5/6 fell to
    /// the substitute branch and was painted with a system face — wrong letterforms,
    /// weight and intra-glyph widths — while its real outline sat unused. The same
    /// font rendered correctly at Tr 0 and wrongly at Tr 4.
    ///
    /// This pins the two traps that make the obvious one-line version wrong:
    ///   - mode 7 must STAY on the substitute path. It is in neither `has_fill` nor
    ///     `has_stroke`, so the outline branch would emit no ink at all, and tag its
    ///     record `outline: true`, which the consumer drops before the clip
    ///     accumulator — no ink and no clip, i.e. total loss after every ET.
    ///   - the 4-6 record must be `outline: false` (so it reaches the accumulator)
    ///     AND `argb: 0` (so it does not paint over the contours). Those two flags
    ///     have to disagree with what modes 0-2 set.
    #[test]
    fn an_embedded_font_paints_its_own_outline_in_clip_modes() {
        let ink = |ps: &[Prim]| ps.iter().filter(|p| matches!(p, Prim::Fill { .. } | Prim::Stroke { .. })).count();
        let text_rec = |ps: &[Prim]| -> Option<(u8, u32, bool)> {
            ps.iter().find_map(|p| match p {
                Prim::Text { render_mode, argb, outline, .. } => Some((*render_mode, *argb, *outline)),
                _ => None,
            })
        };

        // Modes 0-2 keep their existing contract: real ink, record flagged as
        // already-drawn so the consumer ignores it.
        let prims = show(embedded_box_font(0), state(0));
        assert!(ink(&prims) > 0, "mode 0 must paint the embedded outline");
        assert_eq!(
            text_rec(&prims).map(|t| t.2),
            Some(true),
            "mode 0's record must stay outline:true — it is not a clip contribution"
        );

        // Modes 4-6: real ink AND a record the accumulator can use.
        for rm in [4i64, 5, 6] {
            let prims = show(embedded_box_font(0), state(rm));
            assert!(
                ink(&prims) > 0,
                "mode {rm} must paint from the EMBEDDED outline, not a substitute face"
            );
            let (got_rm, argb, outline) =
                text_rec(&prims).expect("mode {rm} must still emit a clip record");
            assert_eq!(got_rm, rm as u8);
            assert!(
                !outline,
                "mode {rm} record must be outline:false or the consumer drops it \
                 before the clip accumulator — that is total content loss, not a \
                 fidelity bug"
            );
            assert_eq!(
                argb, 0,
                "mode {rm} record must be transparent so it does not paint a \
                 substitute face on top of the real contours"
            );
        }

        // Mode 7 stays on the substitute path: no ink, and a usable clip record.
        let prims = show(embedded_box_font(0), state(7));
        assert_eq!(ink(&prims), 0, "mode 7 is clip-only and must lay down no ink");
        let (got_rm, argb, outline) =
            text_rec(&prims).expect("mode 7 must still emit a clip record");
        assert_eq!((got_rm, argb, outline), (7u8, 0u32, false));
    }

    /// mode 7 is "Add to path for clipping" — neither marks the page. Mode 3 is how
    /// every scanned document carries its OCR layer, so if it paints, the scan is
    /// overprinted with a second copy of its own text.
    ///
    /// Asserted against an EMBEDDED font, because that is the path that can paint:
    /// modes 0-2 emit real outlines as `Prim::Fill`/`Prim::Stroke`, which the Kotlin
    /// side has no render-mode guard for (it skips `Prim::Text` for rm 3/7, and skips
    /// `outline`-flagged Text entirely). Ink for mode 3 therefore has to be suppressed
    /// HERE or not at all.
    #[test]
    fn render_mode_3_and_7_emit_no_ink_even_with_an_embedded_program() {
        let ink = |p: &Prim| matches!(p, Prim::Fill { .. } | Prim::Stroke { .. });

        // Precondition: this font really does paint in a painting mode.
        assert!(
            show(embedded_box_font(0), state(0)).iter().any(ink),
            "precondition: mode 0 must emit outline ink, or the test proves nothing"
        );

        for rm in [3i64, 7] {
            let prims = show(embedded_box_font(0), state(rm));
            assert!(
                !prims.iter().any(ink),
                "render mode {rm} must paint nothing, got {} ink prim(s)",
                prims.iter().filter(|p| ink(p)).count()
            );
            let texts: Vec<_> = prims
                .iter()
                .filter_map(|p| match p {
                    Prim::Text { argb, render_mode, text, .. } => Some((*argb, *render_mode, text)),
                    _ => None,
                })
                .collect();
            assert_eq!(texts.len(), 1, "mode {rm} must still reach the text index");
            assert_eq!(texts[0].0, 0, "mode {rm} text prim must carry no colour");
            assert_eq!(texts[0].1, rm as u8);
        }
    }

    /// The glyph-space -> text-space scale is the font program's units-per-em, and it
    /// is applied exactly once: a 1000-unit box on a 1000-unit em at 100 Tf spans
    /// 100 user-space units. Applying it twice (or not at all) is invisible in a
    /// units-per-em-1000 font only if the second factor is 1, which is why this
    /// asserts the absolute extent rather than a ratio.
    #[test]
    fn an_embedded_outline_is_scaled_by_units_per_em_exactly_once() {
        let prims = show(embedded_box_font(0), state(0));
        let contours = prims
            .iter()
            .find_map(|p| match p {
                Prim::Fill { contours, .. } => Some(contours.clone()),
                _ => None,
            })
            .expect("mode 0 with an embedded program must emit outline fills");
        let xs: Vec<f32> = contours.iter().flatten().map(|p| p.0).collect();
        let ys: Vec<f32> = contours.iter().flatten().map(|p| p.1).collect();
        let max_x = xs.iter().cloned().fold(f32::MIN, f32::max);
        let max_y = ys.iter().cloned().fold(f32::MIN, f32::max);
        assert!((max_x - 100.0).abs() < 1e-3, "em box should span 100 user units, got {max_x}");
        assert!((max_y - 100.0).abs() < 1e-3, "em box should span 100 user units, got {max_y}");
    }

    /// Horizontal scaling (Tz) scales the glyph and its advance horizontally only
    /// (§9.4.4: Th multiplies the x column of the text-space parameter matrix).
    #[test]
    fn horizontal_scaling_widens_the_outline_without_stretching_it_vertically() {
        let gs = GraphicsState { h_scale: 2.0, ..state(0) };
        let prims = show(embedded_box_font(0), gs);
        let contours = prims
            .iter()
            .find_map(|p| match p {
                Prim::Fill { contours, .. } => Some(contours.clone()),
                _ => None,
            })
            .expect("outline fill");
        let max_x = contours.iter().flatten().map(|p| p.0).fold(f32::MIN, f32::max);
        let max_y = contours.iter().flatten().map(|p| p.1).fold(f32::MIN, f32::max);
        assert!((max_x - 200.0).abs() < 1e-3, "Tz 200 must double the width, got {max_x}");
        assert!((max_y - 100.0).abs() < 1e-3, "Tz must not touch the height, got {max_y}");
    }

    /// §9.4.4: Trise is a row of the text-space parameter matrix, which does not
    /// depend on the writing mode. The vertical branch built its placement point
    /// from the position vector alone and dropped Trise, so a superscript in
    /// vertical CJK sat on the baseline.
    #[test]
    fn text_rise_applies_in_vertical_writing_mode_too() {
        let origin = |rise: f64| {
            let gs = GraphicsState { rise, ..state(3) };
            show(embedded_box_font(1), gs)
                .into_iter()
                .find_map(|p| match p {
                    Prim::Text { y, .. } => Some(y),
                    _ => None,
                })
                .expect("a text prim per glyph")
        };
        assert!(
            (origin(20.0) - origin(0.0) - 20.0).abs() < 1e-3,
            "Trise must displace a vertical glyph by 20 user units"
        );
    }

    /// Substitute glyphs are sized by Kotlin as `size` (the em, taken from the
    /// matrix's Y scale) times `h_scale` (Tz). Under an ANISOTROPIC matrix those
    /// two do not describe the glyph the outline path draws: the outline is scaled
    /// by x_scale horizontally and y_scale vertically, so the substitute came out
    /// narrower or wider by exactly that ratio — the two paths disagreeing about
    /// the size of the same glyph. The ratio is the only part of the mismatch the
    /// current wire can carry (a rotation still needs a field that does not exist),
    /// and it must be exactly 1 for every isotropic matrix, which is nearly all of
    /// them — hence the second half of this test.
    #[test]
    fn the_substitute_face_is_told_the_matrixs_horizontal_scale() {
        let h_scale_for = |ctm: Mat, th: f64| {
            let mut fi = embedded_box_font(0);
            fi.glyph_program = None; // force the substitute path
            let gs = GraphicsState { ctm, h_scale: th, ..state(0) };
            show(fi, gs)
                .into_iter()
                .find_map(|p| match p {
                    Prim::Text { h_scale, size, .. } => Some((h_scale, size)),
                    _ => None,
                })
                .expect("substitute path emits a text prim")
        };

        // Isotropic: unchanged, whatever the zoom. This is the case that must not move.
        for s in [1.0, 3.0, 0.25] {
            let (hs, _) = h_scale_for([s, 0.0, 0.0, s, 0.0, 0.0], 1.0);
            assert!((hs - 1.0).abs() < 1e-5, "isotropic scale {s} must leave Tz alone, got {hs}");
        }
        // A pure rotation is isotropic too.
        let (a, b) = (0.6_f64, 0.8_f64); // cos/sin of a 53-degree rotation
        let (hs, _) = h_scale_for([a, b, -b, a, 0.0, 0.0], 1.0);
        assert!((hs - 1.0).abs() < 1e-5, "a pure rotation must leave Tz alone, got {hs}");

        // Anisotropic: x twice y. The em still comes from the Y scale, and the
        // horizontal stretch rides on h_scale.
        let (hs, size) = h_scale_for([2.0, 0.0, 0.0, 1.0, 0.0, 0.0], 1.0);
        assert!((hs - 2.0).abs() < 1e-5, "x_scale/y_scale = 2 must reach the wire, got {hs}");
        assert!((size - 100.0).abs() < 1e-3, "the em still follows the Y scale, got {size}");

        // …and it composes with a real Tz rather than replacing it.
        let (hs, _) = h_scale_for([2.0, 0.0, 0.0, 1.0, 0.0, 0.0], 0.5);
        assert!((hs - 1.0).abs() < 1e-5, "Tz 50% under a 2:1 matrix, got {hs}");

        // A near-degenerate matrix must not put a non-finite or absurd scale on the
        // wire. y_scale sits just above the divide-by-zero guard, so the raw ratio
        // is ~5e8; the producer bounds it rather than relying on the consumer.
        let (hs, _) = h_scale_for([1.0, 0.0, 0.0, 2e-9, 0.0, 0.0], 1.0);
        assert!(hs.is_finite() && (0.01..=100.0).contains(&hs), "unbounded scale {hs}");
        // Fully degenerate (below the guard) falls back to plain Tz.
        let (hs, _) = h_scale_for([1.0, 0.0, 0.0, 0.0, 0.0, 0.0], 0.75);
        assert!((hs - 0.75).abs() < 1e-5, "degenerate matrix must yield plain Tz, got {hs}");

        // A pathological Tz must not reach the wire either. NaN is the one that
        // matters: Kotlin's `coerceIn` is two comparisons, both false against NaN,
        // so it would sail through the consumer's clamp into `Paint.textScaleX` and
        // the glyph would silently disappear rather than be the wrong width.
        for bad_tz in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY] {
            for ctm in [[1.0, 0.0, 0.0, 1.0, 0.0, 0.0], [2.0, 0.0, 0.0, 1.0, 0.0, 0.0]] {
                let (hs, _) = h_scale_for(ctm, bad_tz);
                assert!(hs.is_finite(), "Tz {bad_tz} put {hs} on the wire");
            }
        }
    }

    /// `Tf`'s operand and the CTM are file input and `size` is their product, so a
    /// non-finite value is reachable even with both validated upstream: `f64 as f32`
    /// saturates to infinity above ~3.4e38, and `inf * 0` from a zero-scale matrix
    /// is NaN. It matters because NaN is not merely a wrong number here — Kotlin
    /// floors the size with `coerceAtLeast`, a comparison that is false against NaN,
    /// so it reaches `Paint.textSize` and the glyph vanishes.
    #[test]
    fn a_pathological_font_size_cannot_put_nan_on_the_wire() {
        let sizes = |tfs: f64, ctm: Mat| {
            let mut fi = embedded_box_font(0);
            fi.glyph_program = None;
            let gs = GraphicsState { ctm, font_size: tfs, ..state(0) };
            show(fi, gs)
                .into_iter()
                .filter_map(|p| match p {
                    Prim::Text { size, advance, .. } => Some((size, advance)),
                    _ => None,
                })
                .collect::<Vec<_>>()
        };
        let zero_scale: Mat = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0];
        let identity: Mat = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0];
        for (tfs, ctm) in [
            (f64::INFINITY, zero_scale), // inf * 0 = NaN, the reachable case
            (f64::INFINITY, identity),
            (f64::NAN, identity),
            (1e308, identity),
        ] {
            for (size, advance) in sizes(tfs, ctm) {
                assert!(size.is_finite(), "Tf {tfs} produced size {size}");
                assert!(advance.is_finite(), "Tf {tfs} produced advance {advance}");
            }
        }
    }

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

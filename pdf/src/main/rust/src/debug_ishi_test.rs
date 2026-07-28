#[cfg(test)]
mod ishi_tests {
    use crate::*;
    use lopdf::Document;
    use std::path::PathBuf;

    #[test]
    fn debug_ishi() {
        let path = match std::env::var("ISHI_PDF") {
            Ok(p) => PathBuf::from(p),
            Err(_) => {
                let home = std::env::var("HOME").unwrap();
                PathBuf::from(home).join("Downloads/Ishihara_Tests.pdf")
            }
        };
        if !path.exists() {
            println!("PDF not found {}", path.display());
            return;
        }
        let doc = Document::load(&path).expect("load pdf");
        for (page_num, page_id) in doc.get_pages().iter().take(40) {
            if *page_num != 3 && *page_num != 4 && *page_num != 5 && *page_num != 27 && *page_num != 28 && *page_num != 40 {
                // 3,4,5 are Arial plates; 27,28 are Times custom; 40 is last with Type0
                continue;
            }
            println!("\n=== Page {} ===", page_num);
            let res = resources_dict(&doc, *page_id);
            if let Some(r) = &res {
                let fonts = fonts_from_resources(&doc, r);
                for (name, fi) in &fonts {
                    let family_name = match fi.family { 1 => "serif", 2 => "mono", _ => "sans" };
                    // Verify embedded outline extraction: how many known codes yield
                    // non-empty contours, and a couple of sample contour counts.
                    let has_program = fi.glyph_program.is_some();
                    let mut outlined = 0usize;
                    let mut sample = Vec::new();
                    if has_program {
                        let mut codes: Vec<u32> = fi.widths.keys().copied().collect();
                        codes.sort();
                        for c in &codes {
                            if let Some((contours, upm)) = crate::outlines::glyph_outline(fi, *c) {
                                if !contours.is_empty() {
                                    outlined += 1;
                                    if sample.len() < 4 {
                                        let pts: usize = contours.iter().map(|c| c.len()).sum();
                                        sample.push((*c, contours.len(), pts, upm));
                                    }
                                }
                            }
                        }
                    }
                    println!(" font {} base={} family={}({}) bold={} italic={} two_byte={} program={} outlined={}/{} sample={:?}", String::from_utf8_lossy(name), fi.base_font, fi.family, family_name, fi.style.bold, fi.style.italic, fi.two_byte, has_program, outlined, fi.widths.len(), sample);
                    if let Some(tu) = &fi.to_unicode {
                        println!("  to_unicode: {:?}", tu.iter().take(15).collect::<Vec<_>>());
                    }
                    println!("  encoding: {:?}", fi.encoding.iter().take(20).collect::<Vec<_>>());
                    println!("  cmap_uni: {:?}", fi.cmap_uni.iter().take(20).collect::<Vec<_>>());
                    println!("  widths: {:?}", fi.widths.iter().take(30).collect::<Vec<_>>());
                }
            }
            match interpret_page(&doc, *page_id) {
                Ok(pd) => {
                    println!(" page data {}x{} prims {}", pd.width, pd.height, pd.prims.len());
                    let texts: Vec<(f32, f32, f32, f32, &String, u8)> = pd
                        .prims
                        .iter()
                        .filter_map(|p| match p {
                            Prim::Text { x, y, size, advance, text, font_family, .. } => {
                                Some((*x, *y, *size, *advance, text, *font_family))
                            }
                            _ => None,
                        })
                        .collect();
                    for (x, y, size, advance, text, fam) in texts.iter().take(50) {
                        println!("  Text x={:.3} y={:.3} sz={:.2} adv={:.3} fam={} txt={:?}", x, y, size, advance, fam, text);
                    }
                    // Verify letter spacing corresponds to widths. Consecutive glyphs on
                    // the same baseline step by the previous glyph's advance, plus any TJ
                    // kerning adjustment between runs. So `step ≈ advance` for the common
                    // case, while `step != advance` (small deltas) just reflects kerning.
                    // The real bug we guard against is glyphs NOT advancing by their width
                    // at all (advance emitted but origins stacking / ignoring it).
                    let mut checked = 0usize;
                    let mut kerned = 0usize;
                    let mut degenerate = 0usize;
                    for w in texts.windows(2) {
                        let (x0, y0, _s0, adv0, _t0, _f0) = w[0];
                        let (x1, y1, ..) = w[1];
                        if (y0 - y1).abs() < 0.01 && adv0 > 0.5 {
                            checked += 1;
                            let step = x1 - x0;
                            if (step - adv0).abs() > 0.5 {
                                kerned += 1; // TJ kerning / word-spacing between runs
                            }
                            // Degenerate: a full-width glyph whose origin barely moves means
                            // the advance is being dropped — that is the spacing bug.
                            if step < adv0 * 0.35 {
                                degenerate += 1;
                                if degenerate <= 10 {
                                    println!("  SPACING BUG: x0={:.3} x1={:.3} step={:.3} adv={:.3}", x0, x1, step, adv0);
                                }
                            }
                        }
                    }
                    let fills = pd.prims.iter().filter(|p| matches!(p, Prim::Fill{..})).count();
                    let outline_texts = pd.prims.iter().filter(|p| matches!(p, Prim::Text{outline:true,..})).count();
                    let paint_texts = pd.prims.iter().filter(|p| matches!(p, Prim::Text{outline:false,..})).count();
                    let subst: String = pd.prims.iter().filter_map(|p| match p {
                        Prim::Text{outline:false, text, render_mode, ..} if *render_mode != 3 => Some(text.clone()),
                        _ => None,
                    }).collect();
                    println!(" prims: {} fills, {} outline-texts (drawn as fills), {} paint-texts (substitute) subst_chars={:?}", fills, outline_texts, paint_texts, subst);
                    println!(" spacing check: {} same-line pairs, {} kerned (ok), {} degenerate", checked, kerned, degenerate);
                    assert_eq!(degenerate, 0, "page {}: {} glyphs do not advance by their width", page_num, degenerate);
                }
                Err(e) => println!(" interpret err {}", e),
            }
        }
    }
}

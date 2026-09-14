fn text_content(n_runs: usize) -> Vec<u8> {
    let mut s = String::with_capacity(n_runs * 48);
    s.push_str("BT\n/F1 12 Tf\n");
    for i in 0..n_runs {
        let y = 780 - (i % 65) * 12;
        let x = 20 + (i / 65) % 20;
        s.push_str(&format!("1 0 0 1 {} {} Tm\n(Benchmark text run) Tj\n", x, y));
    }
    s.push_str("ET\n");
    s.into_bytes()
}

fn rect_content(n: usize) -> Vec<u8> {
    let mut s = String::with_capacity(n * 32);
    s.push_str("0.5 0.2 0.7 rg\n");
    for i in 0..n {
        let x = (i % 300) as f64 * 2.0;
        let y = (i / 300) as f64 * 2.0 % 780.0;
        s.push_str(&format!("{:.1} {:.1} 3 3 re f\n", x, y));
    }
    s.into_bytes()
}

// ---------------------------------------------------------------------------
// Noise floor
// ---------------------------------------------------------------------------

/// Run one fixed workload as two independent benchmarks and report how far
/// apart two measurements of *identical* work land. Any claimed difference
/// smaller than this is not a difference.
fn noise_floor() -> f64 {
    let mut doc0 = Document::with_version("1.5");
    let res = standard_font_res(&mut doc0);
    let (doc, pages) = build_doc(&[text_content(300)], res);
    let a = bench(15, || {
        black_box(interpret_page(&doc, pages[0]).unwrap());
    });
    let b = bench(15, || {
        black_box(interpret_page(&doc, pages[0]).unwrap());
    });
    let gap = ((b.median_ms - a.median_ms) / a.median_ms).abs() * 100.0;
    let floor = gap.max(a.spread_pct).max(b.spread_pct).max(3.0);
    println!("--- noise floor ---");
    a.row("identical workload, run A", 300, "");
    b.row("identical workload, run B", 300, "");
    println!(
        "  run-to-run gap on identical work: {:.1}%;  adopted noise floor: {:.1}%",
        gap, floor
    );
    println!("  (differences at or below the floor are reported as WITHIN NOISE)");
    floor
}

#[test]
#[ignore]
fn perf_noise_floor() {
    println!();
    noise_floor();
}

// ---------------------------------------------------------------------------
// 1. Text runs, and the reference page for the fuzz budget
// ---------------------------------------------------------------------------

#[test]
#[ignore]
fn perf_text_runs_scaling() {
    println!("\n=== text runs (standard font), single page ===");
    let ns = [250usize, 1000, 4000, 16000];
    let mut meds = Vec::new();
    for &n in &ns {
        let mut d0 = Document::with_version("1.5");
        let res = standard_font_res(&mut d0);
        let (doc, pages) = build_doc(&[text_content(n)], res);
        let mut prims = 0;
        let st = bench(9, || {
            let p = interpret_page(&doc, pages[0]).unwrap();
            prims = p.prims.len();
            black_box(p);
        });
        st.row("text runs", n, &format!("prims={}", prims));
        meds.push(st.median_ms);
    }
    scaling(&ns, &meds);
}

#[test]
#[ignore]
fn perf_reference_page_budget() {
    println!("\n=== reference 'normal page' (the fuzz budget baseline) ===");
    // A deliberately ordinary page: some text, some vector art. This is the
    // number `fuzz` should multiply to define "pathologically slow".
    let mut d0 = Document::with_version("1.5");
    let res = standard_font_res(&mut d0);
    let mut c = text_content(60);
    c.extend_from_slice(&rect_content(200));
    let (doc, pages) = build_doc(&[c], res);
    let st = bench(25, || {
        black_box(interpret_page(&doc, pages[0]).unwrap());
    });
    let p = interpret_page(&doc, pages[0]).unwrap();
    st.row("normal page (60 text + 200 rects)", 1, &format!("prims={}", p.prims.len()));
    println!(
        "  => normal page median {:.3} ms in RELEASE.  50x = {:.1} ms, 1000x = {:.1} ms",
        st.median_ms,
        st.median_ms * 50.0,
        st.median_ms * 1000.0
    );
}

// ---------------------------------------------------------------------------
// 2. CLAIM: the font cache. Author says it is a per-DOCUMENT win.
// ---------------------------------------------------------------------------

/// `interpret_page` opens its own [`FontCacheScope`] internally. Holding an
/// outer scope across several pages is therefore exactly the post-fix behaviour
/// (font parsed once per document); calling the pages with no outer scope is
/// exactly the pre-fix behaviour (parsed once per page). That gives a clean A/B
/// without touching production code.
#[test]
#[ignore]
fn perf_font_cache_claim() {
    println!("\n=== CLAIM: font cache (embedded TrueType, 400 glyphs x 64 pts) ===");
    let floor = noise_floor();
    println!();

    for &n_pages in &[1usize, 5, 20] {
        let mut d0 = Document::with_version("1.5");
        let res = embedded_font_res(&mut d0, 400, 64);
        let contents: Vec<Vec<u8>> = (0..n_pages).map(|_| text_content(200)).collect();
        // Rebuild in the real doc so the font objects belong to it.
        let mut doc = Document::with_version("1.5");
        let res = {
            let _ = res;
            embedded_font_res(&mut doc, 400, 64)
        };
        let pages_id = doc.new_object_id();
        let mut kids = Vec::new();
        let mut page_ids = Vec::new();
        for c in &contents {
            let cid = doc.add_object(Stream::new(Dictionary::new(), c.clone()));
            let pid = doc.add_object(dictionary! {
                "Type" => "Page",
                "Parent" => pages_id,
                "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
                "Contents" => cid,
                "Resources" => res.clone(),
            });
            kids.push(pid.into());
            page_ids.push(pid);
        }
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages", "Kids" => kids, "Count" => n_pages as i64,
            }),
        );
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", cat);

        // Pre-fix: no document-wide scope, so each page parses the font again.
        let without = bench(9, || {
            for &pid in &page_ids {
                black_box(interpret_page(&doc, pid).unwrap());
            }
        });
        // Post-fix: one scope for the whole document.
        let with = bench(9, || {
            let _scope = FontCacheScope::new();
            for &pid in &page_ids {
                black_box(interpret_page(&doc, pid).unwrap());
            }
        });
        without.row("no document scope (pre-fix)", n_pages, "");
        with.row("document-wide scope (post-fix)", n_pages, "");
        println!(
            "  {} page(s): {}",
            n_pages,
            verdict(&without, &with, floor)
        );
        println!();
    }
    println!("  Read the 1-page row before believing the 20-page row.");
}

/// The single-page case the author flagged: does the cache help *within* one
/// page? It only can if `fonts_from_resources` runs more than once, i.e. if the
/// page has nested form XObjects that each re-resolve the same font.
#[test]
#[ignore]
fn perf_font_cache_single_page_nested_forms() {
    println!("\n=== font cache within ONE page, N nested form XObjects ===");
    let floor = 5.0;
    for &n_forms in &[1usize, 8, 32] {
        let mut doc = Document::with_version("1.5");
        let res_font = embedded_font_res(&mut doc, 400, 64);
        let font_obj = res_font
            .get(b"Font")
            .ok()
            .and_then(|o| o.as_dict().ok())
            .and_then(|d| d.get(b"F1").ok())
            .cloned()
            .unwrap();

        // Each form carries its own resources dict pointing at the SAME font
        // object, so every form triggers a fresh fonts_from_resources call.
        let mut xobjs = Dictionary::new();
        for i in 0..n_forms {
            let fres = dictionary! {
                "Font" => dictionary! { "F1" => font_obj.clone() },
            };
            let body = text_content(10);
            let fid = doc.add_object(Stream::new(
                dictionary! {
                    "Type" => "XObject",
                    "Subtype" => "Form",
                    "BBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
                    "Resources" => fres,
                },
                body,
            ));
            xobjs.set(format!("Fm{}", i), fid);
        }
        let mut s = String::new();
        for i in 0..n_forms {
            s.push_str(&format!("q /Fm{} Do Q\n", i));
        }
        let cid = doc.add_object(Stream::new(Dictionary::new(), s.into_bytes()));
        let res = dictionary! {
            "Font" => dictionary! { "F1" => font_obj },
            "XObject" => xobjs,
        };
        let pages_id = doc.new_object_id();
        let pid = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cid, "Resources" => res,
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages", "Kids" => vec![pid.into()], "Count" => 1,
            }),
        );
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", cat);

        let st = bench(9, || {
            black_box(interpret_page(&doc, pid).unwrap());
        });
        st.row("1 page, nested forms sharing a font", n_forms, "");
        let _ = floor;
    }
    println!("  (interpret_page always holds a scope, so this is the POST-fix cost;");
    println!("   compare growth per form against the per-page parse cost above.)");
}

/// Cost of a cache *hit*. `FontInfo::clone` still deep-copies every non-`Arc`
/// field (`widths`, `encoding`, `cmap_uni`, `glyph_names`, `vertical_metrics`,
/// `cid_to_gid`, `cmap`); only `to_unicode` and `glyph_program` are shared. So a
/// hit is cheap only while those maps are small.
#[test]
#[ignore]
fn perf_font_cache_hit_cost_vs_width_table() {
    println!("\n=== cost of a font-cache HIT vs size of the /Widths table ===");
    let ns = [256usize, 4096, 32768];
    let mut meds = Vec::new();
    for &nw in &ns {
        let mut doc = Document::with_version("1.5");
        let prog = synth_ttf(400, 64);
        let len = prog.len() as i64;
        let ff = doc.add_object(Stream::new(dictionary! { "Length1" => len }, prog));
        let fd = doc.add_object(dictionary! {
            "Type" => "FontDescriptor", "FontName" => "BenchFont", "Flags" => 4,
            "ItalicAngle" => 0, "Ascent" => 800, "Descent" => -200,
            "CapHeight" => 700, "StemV" => 80,
            "FontBBox" => vec![0.into(), (-200).into(), 1000.into(), 1000.into()],
            "FontFile2" => ff,
        });
        // A Type0/CID font takes widths from /W, which can be huge for CJK.
        let mut w: Vec<Object> = Vec::new();
        for cid in 0..nw {
            w.push(Object::Integer(cid as i64));
            w.push(Object::Array(vec![Object::Integer(500)]));
        }
        let desc = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "CIDFontType2",
            "BaseFont" => "BenchFont",
            "CIDSystemInfo" => dictionary! {
                "Registry" => Object::string_literal("Adobe"),
                "Ordering" => Object::string_literal("Identity"),
                "Supplement" => 0,
            },
            "FontDescriptor" => fd,
            "DW" => 1000,
            "W" => w,
        });
        let font = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type0",
            "BaseFont" => "BenchFont", "Encoding" => "Identity-H",
            "DescendantFonts" => vec![desc.into()],
        });
        let res = dictionary! { "Font" => dictionary! { "F1" => font } };
        let cid_content = b"BT\n/F1 12 Tf\n1 0 0 1 20 700 Tm\n<00410042> Tj\nET\n".to_vec();

        let pages_id = doc.new_object_id();
        let cstream = doc.add_object(Stream::new(Dictionary::new(), cid_content));
        let pid = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cstream, "Resources" => res,
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages", "Kids" => vec![pid.into()], "Count" => 1,
            }),
        );
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", cat);

        // 40 pages sharing the font: 1 parse + 39 cache hits.
        let st = bench(7, || {
            let _scope = FontCacheScope::new();
            for _ in 0..40 {
                black_box(interpret_page(&doc, pid).unwrap());
            }
        });
        st.row("40 cached renders, /W entries", nw, "");
        meds.push(st.median_ms);
    }
    scaling(&ns, &meds);
    println!("  Every collection in FontInfo is now Arc-shared, so the CLONE is O(1).");
    println!("  What still grows with /W is the cache-key check: `font_identity`");
    println!("  hashes the font dict with references RESOLVED on every lookup, which");
    println!("  walks the /W array element by element (and, for a real font, the whole");
    println!("  embedded program's bytes). The per-hit cost moved rather than went away.");
}

// ---------------------------------------------------------------------------
// 3. CLAIM: soft-mask group caching
// ---------------------------------------------------------------------------

fn smask_doc(n_shapes: usize, interleave: bool) -> (Document, ObjectId) {
    let mut doc = Document::with_version("1.5");
    // Luminosity mask group: a form XObject with some content of its own.
    let mask_body = rect_content(40);
    let gid = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject",
            "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Group" => dictionary! {
                "S" => "Transparency",
                "CS" => "DeviceGray",
            },
        },
        mask_body,
    ));
    let gs_mask = doc.add_object(dictionary! {
        "Type" => "ExtGState",
        "SMask" => dictionary! {
            "S" => "Luminosity",
            "G" => gid,
        },
    });
    let gs_none = doc.add_object(dictionary! {
        "Type" => "ExtGState",
        "SMask" => "None",
    });

    let mut s = String::new();
    s.push_str("/GSM gs\n0.2 0.4 0.9 rg\n");
    for i in 0..n_shapes {
        if interleave {
            // Break the bracket every other shape, defeating adjacency-based
            // coalescing while keeping the same number of masked shapes.
            s.push_str("/GSN gs\n/GSM gs\n");
        }
        let x = (i % 300) as f64 * 2.0;
        let y = (i / 300) as f64 * 2.0 % 780.0;
        s.push_str(&format!("{:.1} {:.1} 4 4 re f\n", x, y));
    }
    let cid = doc.add_object(Stream::new(Dictionary::new(), s.into_bytes()));
    let res = dictionary! {
        "ExtGState" => dictionary! { "GSM" => gs_mask, "GSN" => gs_none },
    };
    let pages_id = doc.new_object_id();
    let pid = doc.add_object(dictionary! {
        "Type" => "Page", "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        "Contents" => cid, "Resources" => res,
    });
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![pid.into()], "Count" => 1,
        }),
    );
    let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", cat);
    (doc, pid)
}

#[test]
#[ignore]
fn perf_softmask_claim() {
    println!("\n=== CLAIM: soft-mask group caching (one SMask over N shapes) ===");
    let ns = [50usize, 200, 800];
    let mut coalesced = Vec::new();
    let mut broken = Vec::new();
    for &n in &ns {
        let (doc, pid) = smask_doc(n, false);
        let st = bench(9, || {
            black_box(interpret_page(&doc, pid).unwrap());
        });
        let p = interpret_page(&doc, pid).unwrap();
        st.row("contiguous masked shapes", n, &format!("prims={}", p.prims.len()));
        coalesced.push(st.median_ms);

        let (doc2, pid2) = smask_doc(n, true);
        let st2 = bench(9, || {
            black_box(interpret_page(&doc2, pid2).unwrap());
        });
        let p2 = interpret_page(&doc2, pid2).unwrap();
        st2.row("bracket broken between shapes", n, &format!("prims={}", p2.prims.len()));
        broken.push(st2.median_ms);
        println!();
    }
    println!(" contiguous (reuse possible):");
    scaling(&ns, &coalesced);
    println!(" bracket broken (reuse defeated):");
    scaling(&ns, &broken);
    println!("  The reuse is adjacency-based (MaskBracket requires b.end == start),");
    println!("  so the second series is the cost when anything intervenes.");
}

// ---------------------------------------------------------------------------
// 4. CLAIM: optional-content config hoisting
// ---------------------------------------------------------------------------

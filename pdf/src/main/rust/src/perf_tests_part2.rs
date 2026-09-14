fn ocg_doc(n_sections: usize, distinct: bool) -> (Document, ObjectId) {
    let mut doc = Document::with_version("1.5");
    let n_ocgs = if distinct { n_sections } else { 1 };
    let mut ocg_ids = Vec::new();
    for i in 0..n_ocgs {
        let id = doc.add_object(dictionary! {
            "Type" => "OCG",
            "Name" => Object::string_literal(format!("Layer{}", i)),
        });
        ocg_ids.push(id);
    }
    // Every group listed in /ON, so the resolved config holds an n-element set —
    // which used to be an n-element array cloned and scanned on each call the
    // memo did not absorb.
    let on: Vec<Object> = ocg_ids.iter().map(|id| Object::Reference(*id)).collect();
    let all: Vec<Object> = on.clone();
    let d = dictionary! { "ON" => on, "OFF" => Vec::<Object>::new(), "BaseState" => "ON" };
    let cat_extra = dictionary! { "OCGs" => all, "D" => d };

    let mut props = Dictionary::new();
    let mut s = String::new();
    for i in 0..n_sections {
        let which = if distinct { i } else { 0 };
        props.set(format!("P{}", i), ocg_ids[which]);
        s.push_str(&format!(
            "/OC /P{} BDC\n0.1 0.2 0.3 rg\n{} 10 5 5 re f\nEMC\n",
            i,
            (i % 300) * 2
        ));
    }
    let cid = doc.add_object(Stream::new(Dictionary::new(), s.into_bytes()));
    let res = dictionary! { "Properties" => props };
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
    let cat = doc.add_object(dictionary! {
        "Type" => "Catalog",
        "Pages" => pages_id,
        "OCProperties" => cat_extra,
    });
    doc.trailer.set("Root", cat);
    (doc, pid)
}

#[test]
#[ignore]
fn perf_optional_content_claim() {
    println!("\n=== CLAIM: optional-content config hoisting (N BDC/EMC sections) ===");
    // Extended past 1600 to answer a specific question: after the per-call
    // clones were removed, do the two remaining linear scans of /ON and /OFF
    // still produce a measurable O(N^2) at realistic layer counts?
    let ns = [100usize, 400, 1600, 3200, 6400];
    let mut same = Vec::new();
    let mut dist = Vec::new();
    for &n in &ns {
        // Big N is slow if the quadratic is still there; keep the sample count
        // low enough that the sweep terminates either way.
        let iters = if n > 1600 { 5 } else { 9 };
        let (doc, pid) = ocg_doc(n, false);
        let st = bench(iters, || {
            black_box(interpret_page(&doc, pid).unwrap());
        });
        st.row("N sections, all ONE ocg (memo hits)", n, "");
        same.push(st.median_ms);

        let (doc2, pid2) = ocg_doc(n, true);
        let st2 = bench(iters, || {
            black_box(interpret_page(&doc2, pid2).unwrap());
        });
        st2.row("N sections, N DISTINCT ocgs", n, "");
        dist.push(st2.median_ms);
        println!(
            "    per-section cost: one-ocg {:.4} ms, distinct {:.4} ms",
            st.median_ms / n as f64,
            st2.median_ms / n as f64
        );
        println!();
    }
    println!(" all one OCG (memoized):");
    scaling(&ns, &same);
    println!(" N distinct OCGs (memo cannot help):");
    scaling(&ns, &dist);
    println!("  A flat 'per-section cost' for the distinct series means linear;");
    println!("  a per-section cost that grows with N means the scans still bite.");
}

// ---------------------------------------------------------------------------
// 5. CLAIM: shading raster sizing (square vs long thin)
// ---------------------------------------------------------------------------

fn axial_shading_dict() -> Dictionary {
    dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => "DeviceRGB",
        "Coords" => vec![0.into(), 0.into(), 600.into(), 0.into()],
        "Function" => dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![1.into(), 0.into(), 0.into()],
            "C1" => vec![0.into(), 0.into(), 1.into()],
            "N" => 1,
        },
        "Extend" => vec![true.into(), true.into()],
    }
}

fn shading_doc(n: usize, clip: (f64, f64)) -> (Document, ObjectId) {
    let mut doc = Document::with_version("1.5");
    let sh = doc.add_object(Object::Dictionary(axial_shading_dict()));
    let mut s = String::new();
    for _ in 0..n {
        s.push_str(&format!(
            "q 0 0 {:.1} {:.1} re W n /Sh0 sh Q\n",
            clip.0, clip.1
        ));
    }
    let cid = doc.add_object(Stream::new(Dictionary::new(), s.into_bytes()));
    let res = dictionary! { "Shading" => dictionary! { "Sh0" => sh } };
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

fn raster_bytes(p: &PageData) -> usize {
    p.prims
        .iter()
        .map(|pr| match pr {
            Prim::Image { data, .. } => data.len(),
            Prim::ImageTiled { data, .. } => data.len(),
            _ => 0,
        })
        .sum()
}

#[test]
#[ignore]
fn perf_shading_claim() {
    println!("\n=== CLAIM: shading raster sizing (square vs long thin) ===");
    let ns = [10usize, 40, 160];
    let mut meds = Vec::new();
    for &n in &ns {
        let (doc, pid) = shading_doc(n, (600.0, 600.0));
        let st = bench(7, || {
            black_box(interpret_page(&doc, pid).unwrap());
        });
        let p = interpret_page(&doc, pid).unwrap();
        st.row(
            "N square shadings",
            n,
            &format!(
                "prims={} raster={:.2} MiB",
                p.prims.len(),
                mib(raster_bytes(&p))
            ),
        );
        meds.push(st.median_ms);
    }
    scaling(&ns, &meds);

    println!("\n  one shading, square vs long-thin clip (raster allocation):");
    for (label, clip) in [
        ("square 600x600", (600.0, 600.0)),
        ("thin   600x8", (600.0, 8.0)),
        ("thin   600x2", (600.0, 2.0)),
        ("thin   8x600", (8.0, 600.0)),
    ] {
        let (doc, pid) = shading_doc(1, clip);
        let st = bench(15, || {
            black_box(interpret_page(&doc, pid).unwrap());
        });
        let p = interpret_page(&doc, pid).unwrap();
        let mut dims = String::new();
        for pr in &p.prims {
            if let Prim::Image { w, h, data, .. } = pr {
                dims = format!("{}x{} ({} B)", w, h, data.len());
            }
        }
        st.row(label, 1, &format!("raster={}", dims));
    }
    println!("  If the thin cases allocate ~the same bytes as the square one, the");
    println!("  raster is still effectively square-sized.");
}

// ---------------------------------------------------------------------------
// 6. CLAIM: tiling pattern rasterization
// ---------------------------------------------------------------------------

fn tiling_doc(pitch: f64) -> (Document, ObjectId) {
    let mut doc = Document::with_version("1.5");
    let cell = format!(
        "0 0 0 RG 0.4 w 0 0 m {:.2} {:.2} l S\n0 {:.2} m {:.2} 0 l S\n",
        pitch, pitch, pitch, pitch
    );
    let pat = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "Pattern",
            "PatternType" => 1,
            "PaintType" => 1,
            "TilingType" => 1,
            "BBox" => vec![0.into(), 0.into(), pitch.into(), pitch.into()],
            "XStep" => pitch,
            "YStep" => pitch,
            "Resources" => Dictionary::new(),
        },
        cell.into_bytes(),
    ));
    let content = b"/Pattern cs /P0 scn\n0 0 612 792 re f\n".to_vec();
    let cid = doc.add_object(Stream::new(Dictionary::new(), content));
    let res = dictionary! { "Pattern" => dictionary! { "P0" => pat } };
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
fn perf_tiling_pattern_claim() {
    println!("\n=== CLAIM: tiling pattern rasterization (full-page hatch) ===");
    // Smaller pitch = more tiles over the same page area, so tile count grows
    // as 1/pitch^2.
    let pitches = [32.0f64, 16.0, 8.0, 4.0, 2.0];
    let ns: Vec<usize> = pitches
        .iter()
        .map(|p| ((612.0 / p).ceil() * (792.0 / p).ceil()) as usize)
        .collect();
    let mut meds = Vec::new();
    for (i, &pitch) in pitches.iter().enumerate() {
        let (doc, pid) = tiling_doc(pitch);
        let st = bench(7, || {
            black_box(interpret_page(&doc, pid).unwrap());
        });
        let p = interpret_page(&doc, pid).unwrap();
        st.row(
            &format!("hatch pitch {:.0} pt", pitch),
            ns[i],
            &format!(
                "prims={} raster={:.3} MiB",
                p.prims.len(),
                mib(raster_bytes(&p))
            ),
        );
        meds.push(st.median_ms);
    }
    scaling(&ns, &meds);
    println!("  n here is the TILE COUNT covering the page. Flat time against a");
    println!("  growing tile count means the cell is rasterized once, not per tile.");
}

// ---------------------------------------------------------------------------
// 7. Glyph outline extraction
// ---------------------------------------------------------------------------

#[test]
#[ignore]
fn perf_glyph_outlines_scaling() {
    println!("\n=== glyph outline extraction (embedded TrueType) ===");
    println!(" a) scaling in glyphs DRAWN (font fixed at 400 glyphs x 64 pts):");
    let ns = [200usize, 800, 3200];
    let mut meds = Vec::new();
    for &n in &ns {
        let mut doc = Document::with_version("1.5");
        let res = embedded_font_res(&mut doc, 400, 64);
        let pages_id = doc.new_object_id();
        let cid = doc.add_object(Stream::new(Dictionary::new(), text_content(n / 19 + 1)));
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
        let p = interpret_page(&doc, pid).unwrap();
        st.row("glyphs drawn (approx)", n, &format!("prims={}", p.prims.len()));
        meds.push(st.median_ms);
    }
    scaling(&ns, &meds);

    println!(" b) scaling in FONT PROGRAM size (same tiny page, one text run):");
    let gs = [100u16, 400, 1600];
    let mut meds2 = Vec::new();
    let nsz: Vec<usize> = gs.iter().map(|g| *g as usize).collect();
    for &g in &gs {
        let mut doc = Document::with_version("1.5");
        let res = embedded_font_res(&mut doc, g, 64);
        let pages_id = doc.new_object_id();
        let cid = doc.add_object(Stream::new(Dictionary::new(), text_content(1)));
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
        let st = bench(11, || {
            black_box(interpret_page(&doc, pid).unwrap());
        });
        st.row("font glyph count", g as usize, "one text run only");
        meds2.push(st.median_ms);
    }
    scaling(&nsz, &meds2);
    println!("  (b) is the fixed cost a page pays just for HAVING a big embedded font.");
}

// ---------------------------------------------------------------------------
// 8. Primitive count / memory: the "crasheshalfway" (OOM) hypothesis
// ---------------------------------------------------------------------------

#[test]
#[ignore]
fn perf_primitive_cap_and_memory() {
    println!("\n=== primitive count + PEAK HEAP (MAX_PRIMITIVES = {}) ===", MAX_PRIMITIVES);
    println!("  Peak heap measured with a counting global allocator (net live bytes");
    println!("  since reset), not RSS. Timing rows are measured with tracking OFF.");
    let ns = [10_000usize, 50_000, 200_000, 400_000];
    let mut meds = Vec::new();
    for &n in &ns {
        let mut d0 = Document::with_version("1.5");
        let res = standard_font_res(&mut d0);
        let (doc, pages) = build_doc(&[rect_content(n)], res);

        let st = bench(5, || {
            black_box(interpret_page(&doc, pages[0]).unwrap());
        });

        mem_reset();
        let p = interpret_page(&doc, pages[0]).unwrap();
        let prims = p.prims.len();
        let rb = raster_bytes(&p);
        drop(p);
        let peak = mem_peak();

        st.row(
            "rects requested",
            n,
            &format!(
                "prims={} ({}) peak_heap={:.2} MiB  prim_bytes~{:.2} MiB  raster={:.2} MiB",
                prims,
                if prims >= MAX_PRIMITIVES { "CAPPED" } else { "under cap" },
                mib(peak),
                mib(prims * std::mem::size_of::<Prim>()),
                mib(rb),
            ),
        );
        meds.push(st.median_ms);
    }
    scaling(&ns, &meds);
    println!("  size_of::<Prim>() = {} bytes", std::mem::size_of::<Prim>());
    println!(
        "  cap-implied ceiling on the primitive vector alone: {:.1} MiB",
        mib(MAX_PRIMITIVES * std::mem::size_of::<Prim>())
    );
}

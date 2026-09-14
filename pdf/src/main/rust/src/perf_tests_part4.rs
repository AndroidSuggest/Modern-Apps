/// `size_of::<Prim>()` as of whatever is checked out right now, plus the cost of
/// serialising a page to the wire buffer measured as an allocator delta across
/// the `wire::serialize` call alone.
#[test]
#[ignore]
fn perf_prim_width_and_wire_cost() {
    println!("\n=== Prim width, measured on the CURRENT working tree ===");
    println!("  size_of::<Prim>()      = {} B", std::mem::size_of::<Prim>());
    println!("  align_of::<Prim>()     = {} B", std::mem::align_of::<Prim>());
    println!("  size_of::<PageData>()  = {} B", std::mem::size_of::<PageData>());
    println!("  for reference: Mat = {} B, Vec<u8> = {} B, Box<[u8;256]> = {} B",
        std::mem::size_of::<Mat>(),
        std::mem::size_of::<Vec<u8>>(),
        std::mem::size_of::<Box<[u8; 256]>>(),
    );
    println!(
        "  primitive vector at MAX_PRIMITIVES = {:.2} MiB",
        mib(MAX_PRIMITIVES * std::mem::size_of::<Prim>())
    );

    println!("\n=== wire::serialize cost (allocator delta across the call alone) ===");
    println!(
        "{:<30} {:>9} {:>12} {:>12} {:>11} {:>11}",
        "page", "prims", "buf MiB", "peak MiB", "B/prim buf", "B/prim pk"
    );

    let mut cases: Vec<(String, Document, ObjectId)> = Vec::new();
    {
        let mut d0 = Document::with_version("1.5");
        let res = standard_font_res(&mut d0);
        let (doc, pages) = build_doc(&[rect_content(100_000)], res);
        cases.push(("100k rects (Fill prims)".into(), doc, pages[0]));
    }
    {
        let mut d0 = Document::with_version("1.5");
        let res = standard_font_res(&mut d0);
        let (doc, pages) = build_doc(&[text_content(4000)], res);
        cases.push(("4000 text runs (Text prims)".into(), doc, pages[0]));
    }
    {
        let (doc, pid) = shading_doc(40, (600.0, 600.0));
        cases.push(("40 square shadings (Image)".into(), doc, pid));
    }
    {
        let (doc, pid) = smask_doc(800, false);
        cases.push(("800 shapes under SMask".into(), doc, pid));
    }

    for (label, doc, pid) in &cases {
        // Build the page OUTSIDE the measured window so only serialisation counts.
        let page = interpret_page(doc, *pid).unwrap();
        let n = page.prims.len();
        mem_reset();
        let buf = crate::wire::serialize(&page);
        let buf_len = buf.len();
        let peak = mem_peak();
        drop(buf);
        println!(
            "{:<30} {:>9} {:>12.2} {:>12.2} {:>11.0} {:>11.0}",
            label,
            n,
            mib(buf_len),
            mib(peak),
            if n > 0 { buf_len as f64 / n as f64 } else { 0.0 },
            if n > 0 { peak as f64 / n as f64 } else { 0.0 }
        );
    }
    println!();
    println!("  'buf' is the returned buffer; 'peak' is the high-water mark during");
    println!("  the call, so peak > buf means serialisation transiently doubles.");
    println!("  This buffer is live at the same time as the primitive vector, so the");
    println!("  two add on the way out to Kotlin.");

    // The full pipeline: how much is live at once for one heavy page.
    let mut d0 = Document::with_version("1.5");
    let res = standard_font_res(&mut d0);
    let (doc, pages) = build_doc(&[rect_content(400_000)], res);
    mem_reset();
    let page = interpret_page(&doc, pages[0]).unwrap();
    let after_interpret = mem_current();
    let buf = crate::wire::serialize(&page);
    let both_live = mem_current();
    let pipeline_peak = mem_peak();
    println!(
        "\n  400k-rect page: after interpret {:.2} MiB live, with wire buffer also live {:.2} MiB, pipeline peak {:.2} MiB",
        mib(after_interpret),
        mib(both_live),
        mib(pipeline_peak)
    );
    drop(buf);
    drop(page);
}

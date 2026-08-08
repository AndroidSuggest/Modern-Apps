//! Host-side reproduction harness for GitHub issue #321 rendering bugs.
//!
//! Drives the real rendering pipeline (`open_document` → `interpret_page`) on the
//! host target so each of the 13 example PDFs from the issue can be reproduced
//! and verified with `cargo test`, without needing a device/emulator.
//!
//! The fixture PDFs are NOT committed (potential copyright). Point the harness at
//! them with the `ISSUE321_DIR` env var, or drop them in `<repo>/issue321_pdfs/`.
//! When the directory is absent the tests print a notice and pass, mirroring the
//! `ISHI_PDF` skip pattern in `debug_ishi_test.rs`.
//!
//! Run with output:
//!   cargo test -p pdf_render issue321 -- --nocapture

#[cfg(test)]
mod issue321 {
    use crate::*;
    use std::panic::AssertUnwindSafe;
    use std::path::PathBuf;
    use std::time::Instant;

    /// The 13 fixtures attached to issue #321, each named after the symptom it
    /// triggers (see the plan). `Ishihara_Tests.pdf` is covered separately.
    const FIXTURES: &[&str] = &[
        "bleedexample",
        "colorrenderexample",
        "crasheshalfway",
        "crashexample",
        "doesntopenexample",
        "doesntrenderfully",
        "doesntrenderimages",
        "doesntzoomfully-stopsrenderhalfway",
        "doesntzoomfully2",
        "missinggraphic",
        "pagerotationexample",
        "slowdownexample",
        "slowdownexample2",
    ];

    /// Locate the fixtures directory: `ISSUE321_DIR` env override, else the repo's
    /// `issue321_pdfs/` resolved relative to this crate's manifest dir.
    fn fixtures_dir() -> Option<PathBuf> {
        if let Ok(p) = std::env::var("ISSUE321_DIR") {
            let pb = PathBuf::from(p);
            return if pb.is_dir() { Some(pb) } else { None };
        }
        // CARGO_MANIFEST_DIR = <repo>/pdf/src/main/rust ; repo root is 4 levels up.
        let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let candidate = manifest
            .ancestors()
            .nth(4)
            .map(|root| root.join("issue321_pdfs"));
        candidate.filter(|c| c.is_dir())
    }

    /// Per-page primitive tally, split by the categories relevant to the issue.
    #[derive(Default)]
    struct PageStats {
        fills: usize,
        strokes: usize,
        text_paint: usize,   // substitute glyphs actually painted
        text_outline: usize, // real outlines emitted as fills (outline=true)
        images_ok: usize,    // decoded RGBA (format 0) with a non-empty buffer
        images_jpeg: usize,  // JPEG passthrough (format 1)
        images_empty: usize, // image prim with an empty / zero-size buffer
        clip_push: usize,
        max_clip_depth: usize,
        groups: usize,
        soft_masks: usize,
        total: usize,
        max_img_w: u32,          // largest single decoded image (RGBA) dimensions
        max_img_h: u32,
        decoded_img_bytes: usize, // sum of RGBA bytes across format-0 images
    }

    fn tally(prims: &[Prim]) -> PageStats {
        let mut s = PageStats::default();
        let mut depth = 0usize;
        for p in prims {
            s.total += 1;
            match p {
                Prim::Fill { .. } => s.fills += 1,
                Prim::Stroke { .. } => s.strokes += 1,
                Prim::Text { outline, .. } => {
                    if *outline {
                        s.text_outline += 1;
                    } else {
                        s.text_paint += 1;
                    }
                }
                Prim::Image { format, data, w, h, .. } => {
                    let empty = data.is_empty() || *w == 0 || *h == 0;
                    if empty {
                        s.images_empty += 1;
                    } else if *format == 1 {
                        s.images_jpeg += 1;
                    } else {
                        s.images_ok += 1;
                        s.decoded_img_bytes += data.len();
                        if (*w as u64) * (*h as u64) > (s.max_img_w as u64) * (s.max_img_h as u64) {
                            s.max_img_w = *w;
                            s.max_img_h = *h;
                        }
                    }
                }
                Prim::ClipPush { .. } => {
                    s.clip_push += 1;
                    depth += 1;
                    s.max_clip_depth = s.max_clip_depth.max(depth);
                }
                Prim::ClipPop => depth = depth.saturating_sub(1),
                Prim::GroupPush { .. } => s.groups += 1,
                Prim::SoftMaskPush { .. } => s.soft_masks += 1,
                _ => {}
            }
        }
        s
    }

    /// Result of processing one fixture PDF.
    struct FixtureReport {
        name: String,
        opened: bool,
        page_count: usize,
        panics: Vec<usize>,      // 0-based page indices that panicked
        interp_errs: Vec<usize>, // pages where interpret_page returned Err
        slowest_ms: u128,
        slowest_page: usize,
        total_ms: u128,
        empty_image_pages: usize,
    }

    fn process(name: &str, path: &PathBuf) -> FixtureReport {
        let mut report = FixtureReport {
            name: name.to_string(),
            opened: false,
            page_count: 0,
            panics: Vec::new(),
            interp_errs: Vec::new(),
            slowest_ms: 0,
            slowest_page: 0,
            total_ms: 0,
            empty_image_pages: 0,
        };

        let bytes = match std::fs::read(path) {
            Ok(b) => b,
            Err(e) => {
                println!("  [{}] read error: {}", name, e);
                return report;
            }
        };

        // Mirror the real open path (registry::open_document_pw returns 0 on failure).
        let handle = open_document(&bytes);
        report.opened = handle != 0;
        if report.opened {
            close_document(handle);
        }
        println!(
            "\n=== {} ===  open={}",
            name,
            if report.opened { "OK" } else { "FAILED (handle 0)" }
        );

        // Load once for per-page interpretation, via the same recovery-capable
        // path the app uses (registry::open_document → load_document_lenient).
        let doc = match load_document_lenient(&bytes) {
            Some(d) => d,
            None => {
                println!("  load_document_lenient failed");
                return report;
            }
        };

        let pages = doc.get_pages();
        report.page_count = pages.len();
        println!("  pages: {}", report.page_count);

        for (idx, (page_num, page_id)) in pages.iter().enumerate() {
            let start = Instant::now();
            let outcome =
                std::panic::catch_unwind(AssertUnwindSafe(|| interpret_page(&doc, *page_id)));
            let ms = start.elapsed().as_millis();
            report.total_ms += ms;
            if ms > report.slowest_ms {
                report.slowest_ms = ms;
                report.slowest_page = idx;
            }
            match outcome {
                Ok(Ok(pd)) => {
                    let st = tally(&pd.prims);
                    if st.images_empty > 0 {
                        report.empty_image_pages += 1;
                    }
                    println!(
                        "  p{} (#{}) {:.0}x{:.0} {}ms prims={} fill={} stroke={} \
                         txt(paint={},outline={}) img(ok={},jpeg={},empty={}) \
                         clip={}(d{}) grp={} smask={} maximg={}x{} imgMB={:.1}",
                        idx, page_num, pd.width, pd.height, ms, st.total, st.fills, st.strokes,
                        st.text_paint, st.text_outline, st.images_ok, st.images_jpeg,
                        st.images_empty, st.clip_push, st.max_clip_depth, st.groups,
                        st.soft_masks, st.max_img_w, st.max_img_h,
                        st.decoded_img_bytes as f64 / (1024.0 * 1024.0),
                    );
                }
                Ok(Err(e)) => {
                    report.interp_errs.push(idx);
                    println!("  p{} (#{}) {}ms INTERPRET ERROR: {}", idx, page_num, ms, e);
                }
                Err(_) => {
                    report.panics.push(idx);
                    println!("  p{} (#{}) {}ms *** PANIC ***", idx, page_num, ms);
                }
            }
        }

        println!(
            "  summary: opened={} pages={} panics={} interp_errs={} empty_img_pages={} \
             total={}ms slowest=p{}({}ms)",
            report.opened, report.page_count, report.panics.len(), report.interp_errs.len(),
            report.empty_image_pages, report.total_ms, report.slowest_page, report.slowest_ms,
        );

        report
    }

    /// Debug helper: print the most frequent fill colors (ARGB) a fixture page
    /// emits, to compare against a reference render when chasing color bugs.
    /// Ignored by default; run with:
    ///   cargo test -p pdf_render issue321_fill_colors -- --ignored --nocapture
    #[test]
    #[ignore]
    fn issue321_fill_colors() {
        let dir = match fixtures_dir() {
            Some(d) => d,
            None => return,
        };
        let name = std::env::var("FILL_PDF").unwrap_or_else(|_| "colorrenderexample".to_string());
        let page_idx: usize = std::env::var("FILL_PAGE").ok().and_then(|s| s.parse().ok()).unwrap_or(0);
        let path = dir.join(format!("{}.pdf", name));
        let bytes = std::fs::read(&path).expect("read fixture");
        let doc = load_document_lenient(&bytes).expect("load");
        let pages = doc.get_pages();
        let (_, page_id) = pages.iter().nth(page_idx).expect("page");
        let pd = interpret_page(&doc, *page_id).expect("interpret");
        let mut counts: std::collections::HashMap<u32, usize> = std::collections::HashMap::new();
        for p in &pd.prims {
            match p {
                Prim::Fill { argb, .. } => *counts.entry(*argb).or_default() += 1,
                Prim::Text { argb, outline: false, .. } => *counts.entry(*argb).or_default() += 1,
                _ => {}
            }
        }
        let mut v: Vec<(u32, usize)> = counts.into_iter().collect();
        v.sort_by(|a, b| b.1.cmp(&a.1));
        println!("=== {} p{} top fill/text colors (ARGB) ===", name, page_idx);
        for (argb, n) in v.iter().take(25) {
            println!(
                "  #{:08X}  a={} r={} g={} b={}  ×{}",
                argb, (argb >> 24) & 0xFF, (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, n
            );
        }
        // Dump the first text runs with their page-space origins so content
        // orientation can be checked against a reference render (rotation bugs).
        println!("  page {:.0}x{:.0} (display)", pd.width, pd.height);
        let mut tno = 0;
        for p in &pd.prims {
            if let Prim::Text { x, y, text, .. } = p {
                if text.trim().is_empty() {
                    continue;
                }
                println!("   text @({:.0},{:.0}) {:?}", x, y, text);
                tno += 1;
                if tno >= 12 {
                    break;
                }
            }
        }
        // Sample decoded image pixels (RGBA format 0) at a 3×3 grid so colors can
        // be diffed against a reference renderer.
        let mut img_no = 0;
        for p in &pd.prims {
            if let Prim::Image { w, h, format, data, .. } = p {
                if *format != 0 || data.is_empty() {
                    continue;
                }
                println!("  image#{} {}x{}:", img_no, w, h);
                for fy in [0.25, 0.5, 0.75] {
                    let mut row = String::new();
                    for fx in [0.25, 0.5, 0.75] {
                        let px = ((*w as f64 * fx) as u32).min(w - 1);
                        let py = ((*h as f64 * fy) as u32).min(h - 1);
                        let i = ((py * w + px) * 4) as usize;
                        if i + 3 < data.len() {
                            row += &format!(
                                " ({:3},{:3},{:3},{:3})",
                                data[i], data[i + 1], data[i + 2], data[i + 3]
                            );
                        }
                    }
                    println!("   {}", row);
                }
                img_no += 1;
                if img_no >= 6 {
                    break;
                }
            }
        }
    }

    /// Drive every fixture and print a per-PDF / per-page report. This test never
    /// fails on rendering defects itself (it is a diagnostic harness); it only
    /// fails if the harness cannot run. Skips cleanly when fixtures are absent.
    #[test]
    fn issue321_report() {
        let dir = match fixtures_dir() {
            Some(d) => d,
            None => {
                println!(
                    "issue321 fixtures not found (set ISSUE321_DIR or add <repo>/issue321_pdfs/); \
                     skipping."
                );
                return;
            }
        };
        println!("issue321 fixtures dir: {}", dir.display());

        let mut reports = Vec::new();
        for name in FIXTURES {
            let path = dir.join(format!("{}.pdf", name));
            if !path.exists() {
                println!("\n=== {} ===  (missing file, skipped)", name);
                continue;
            }
            reports.push(process(name, &path));
        }

        // Compact overview table at the end for quick triage.
        println!("\n================ issue321 overview ================");
        println!(
            "{:<38} {:>4} {:>5} {:>6} {:>6} {:>8}",
            "fixture", "open", "pages", "panic", "err", "slow(ms)"
        );
        for r in &reports {
            println!(
                "{:<38} {:>4} {:>5} {:>6} {:>6} {:>8}",
                r.name,
                if r.opened { "OK" } else { "FAIL" },
                r.page_count,
                r.panics.len(),
                r.interp_errs.len(),
                r.slowest_ms,
            );
        }
        println!("==================================================");
    }
}

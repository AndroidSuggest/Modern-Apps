//! Differential tests: invariant and metamorphic checks that do not need an
//! oracle.
//!
//! Why this module exists. Rounds 1 and 2 verified ~90 fixes by reading ISO
//! 32000-1 and asserting what we concluded. That process cannot catch a bug
//! where the code is SELF-CONSISTENT but our reading of the spec was WRONG,
//! because the tests assert the same belief the code implements — they agree
//! with us by construction. Two families of check escape that trap without
//! needing a second implementation:
//!
//! * INVARIANTS that must hold whatever the right answer is. Rendering the same
//!   page twice must produce identical primitives; rendering page N must not
//!   depend on whether page M was rendered first. These are true of every
//!   conforming renderer and of every non-conforming one, so they are immune to
//!   a mistaken spec reading. They are exactly the checks that catch leaking
//!   shared state — see the `FontCacheScope` thread-local in `fonts.rs`.
//! * METAMORPHIC relations, where a transformation of the input implies a
//!   predictable transformation of the output. We need not know where a mark
//!   belongs on a `/Rotate 90` page to know that turning the page four times
//!   must put it back where it started, or that prepending a `cm` translation
//!   must move it by exactly that vector.
//!
//! A reference renderer was investigated and none was usable here; see the
//! module-level note in the report. These checks are what remains valuable
//! regardless, and were wanted even if a reference had been found.
//!
//! House style follows `golden_tests.rs`: build with `lopdf`, run the real
//! pipeline. Unlike `golden_tests.rs` this module deliberately compares EXACT
//! fingerprints rather than meaning, because the property under test is
//! bit-level reproducibility — a "close enough" comparison would let precisely
//! the cache-staleness bugs being hunted slip through.

use crate::*;
use lopdf::content::{Content, Operation};
use lopdf::{dictionary, Object, Stream};

// ---------------------------------------------------------------------------
// Exact fingerprinting of a rendered page
//
// `Prim` deliberately derives nothing — not `Debug`, not `PartialEq` — so the
// determinism checks need their own total projection. Every field of every
// variant is matched by name with no `..` rest pattern, which means adding a
// field to `Prim` breaks THIS FILE at compile time. That is intentional: a new
// field silently excluded from the fingerprint would quietly weaken every test
// below into a vacuous pass.

fn fnv1a(bytes: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for &b in bytes {
        h ^= b as u64;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}

/// Floats go in as raw bits, never as formatted decimals. Two renders that
/// differ by one ULP are a determinism bug, and `{:?}` would round them into
/// agreement and hide it. Bit equality also distinguishes `0.0` from `-0.0`.
fn f32b(v: f32) -> String {
    format!("{:08x}", v.to_bits())
}

fn f64b(v: f64) -> String {
    format!("{:016x}", v.to_bits())
}

fn fp_mat(m: &Mat) -> String {
    m.iter().map(|v| f64b(*v)).collect::<Vec<_>>().join(",")
}

fn fp_pts(pts: &[(f32, f32)]) -> String {
    let mut s = String::with_capacity(pts.len() * 18);
    for (x, y) in pts {
        s.push_str(&f32b(*x));
        s.push(':');
        s.push_str(&f32b(*y));
        s.push(';');
    }
    s
}

fn fp_path_ops(ops: &Option<Vec<PathOp>>) -> String {
    match ops {
        None => "none".to_string(),
        Some(v) => v
            .iter()
            .map(|o| match o {
                PathOp::Move(a, b) => format!("M{}:{}", f32b(*a), f32b(*b)),
                PathOp::Line(a, b) => format!("L{}:{}", f32b(*a), f32b(*b)),
                PathOp::Cubic(a, b, c, d, e, f) => format!(
                    "C{}:{}:{}:{}:{}:{}",
                    f32b(*a),
                    f32b(*b),
                    f32b(*c),
                    f32b(*d),
                    f32b(*e),
                    f32b(*f)
                ),
                PathOp::Close => "Z".to_string(),
            })
            .collect::<Vec<_>>()
            .join(","),
    }
}

/// A total, exact projection of one primitive to a string.
///
/// Bulk pixel payloads are hashed rather than inlined — a page with a 4 MB
/// image would otherwise produce an 8 MB fingerprint per render — but the hash
/// covers every byte, so a single differing pixel still changes the result.
fn fp_prim(p: &Prim) -> String {
    match p {
        Prim::Text {
            x,
            y,
            size,
            argb,
            text,
            stroke_argb,
            stroke_width,
            advance,
            render_mode,
            blend,
            is_bold,
            is_italic,
            font_family,
            outline,
            h_scale,
        } => format!(
            "Text|{}|{}|{}|{:08x}|{}|{:?}|{:?}|{}|{}|{}|{}|{}|{}|{}|{}",
            f32b(*x),
            f32b(*y),
            f32b(*size),
            argb,
            text.escape_debug(),
            stroke_argb,
            stroke_width.map(f32b),
            f32b(*advance),
            render_mode,
            *blend as u8,
            is_bold,
            is_italic,
            font_family,
            outline,
            f32b(*h_scale)
        ),
        Prim::Fill { argb, even_odd, contours, blend } => format!(
            "Fill|{:08x}|{}|{}|{}",
            argb,
            even_odd,
            contours.iter().map(|c| fp_pts(c)).collect::<Vec<_>>().join("/"),
            *blend as u8
        ),
        Prim::Stroke { argb, width, dash, dash_phase, cap, join, miter, pts, blend } => format!(
            "Stroke|{:08x}|{}|{}|{}|{}|{}|{}|{}|{}",
            argb,
            f32b(*width),
            dash.iter().map(|d| f32b(*d)).collect::<Vec<_>>().join(","),
            f32b(*dash_phase),
            cap,
            join,
            f32b(*miter),
            fp_pts(pts),
            *blend as u8
        ),
        Prim::Image { ctm, w, h, format, data, alpha, blend } => format!(
            "Image|{}|{}|{}|{}|{}|{:016x}|{}|{}",
            fp_mat(ctm),
            w,
            h,
            format,
            data.len(),
            fnv1a(data),
            f32b(*alpha),
            *blend as u8
        ),
        Prim::ImageTiled {
            ctm,
            w,
            h,
            data,
            xstep,
            ystep,
            i0,
            j0,
            nx,
            ny,
            alpha,
            blend,
        } => format!(
            "ImageTiled|{}|{}|{}|{}|{:016x}|{}|{}|{}|{}|{}|{}|{}|{}",
            fp_mat(ctm),
            w,
            h,
            data.len(),
            fnv1a(data),
            f32b(*xstep),
            f32b(*ystep),
            i0,
            j0,
            nx,
            ny,
            f32b(*alpha),
            *blend as u8
        ),
        Prim::ClipPush { even_odd, pts, path_ops } => {
            format!("ClipPush|{}|{}|{}", even_odd, fp_pts(pts), fp_path_ops(path_ops))
        }
        Prim::ClipPop => "ClipPop".to_string(),
        Prim::TextClipApply => "TextClipApply".to_string(),
        Prim::GroupPush { isolated, knockout, alpha, blend } => format!(
            "GroupPush|{}|{}|{}|{}",
            isolated,
            knockout,
            f32b(*alpha),
            *blend as u8
        ),
        Prim::GroupPop => "GroupPop".to_string(),
        Prim::SoftMaskPush { mask_type } => format!("SoftMaskPush|{}", mask_type),
        Prim::SoftMaskTransfer(lut) => format!("SoftMaskTransfer|{:016x}", fnv1a(&lut[..])),
        Prim::SoftMaskContent => "SoftMaskContent".to_string(),
        Prim::SoftMaskPop => "SoftMaskPop".to_string(),
    }
}

/// Order-sensitive fingerprint of a whole rendered page, dimensions included.
/// Order matters because primitive order IS the paint order; a renderer that
/// emitted the same set in a different sequence would draw a different page.
fn fingerprint(page: &PageData) -> String {
    let mut s = format!("{}x{}\n", f32b(page.width), f32b(page.height));
    for (i, p) in page.prims.iter().enumerate() {
        s.push_str(&format!("{i}:{}\n", fp_prim(p)));
    }
    s
}

/// Where the fingerprints first diverge, for a failure message that names the
/// offending primitive instead of dumping two multi-kilobyte blobs.
fn first_difference(a: &str, b: &str) -> String {
    let (mut la, mut lb) = (a.lines(), b.lines());
    let mut n = 0;
    loop {
        match (la.next(), lb.next()) {
            (None, None) => return "identical".to_string(),
            (x, y) if x == y => n += 1,
            (x, y) => return format!("line {n}:\n  first:  {x:?}\n  second: {y:?}"),
        }
    }
}

// ---------------------------------------------------------------------------
// Fixture builders (mirrors of the `golden_tests.rs` helpers, which are private
// to that module)

fn assemble_with_contents(
    doc: &mut Document,
    contents: Object,
    resources: Dictionary,
    page: &mut Dictionary,
    catalog: Dictionary,
) -> ObjectId {
    let pages_id = doc.new_object_id();
    page.set("Type", Object::Name(b"Page".to_vec()));
    page.set("Parent", pages_id);
    page.set("Resources", resources);
    if !matches!(contents, Object::Null) {
        page.set("Contents", contents);
    }
    if page.get(b"MediaBox").is_err() {
        page.set("MediaBox", vec![0.into(), 0.into(), 612.into(), 792.into()]);
    }
    let page_id = doc.add_object(page.clone());
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }),
    );
    let mut cat = catalog;
    cat.set("Type", Object::Name(b"Catalog".to_vec()));
    cat.set("Pages", pages_id);
    let catalog_id = doc.add_object(cat);
    doc.trailer.set("Root", catalog_id);
    page_id
}

fn assemble(
    doc: &mut Document,
    content: Vec<u8>,
    resources: Dictionary,
    mut page: Dictionary,
) -> ObjectId {
    let content_id = doc.add_object(Stream::new(dictionary! {}, content));
    assemble_with_contents(
        doc,
        Object::Reference(content_id),
        resources,
        &mut page,
        dictionary! {},
    )
}

fn page_from_ops(doc: &mut Document, ops: Vec<Operation>, resources: Dictionary) -> ObjectId {
    let bytes = Content { operations: ops }.encode().unwrap();
    assemble(doc, bytes, resources, dictionary! {})
}

/// Build a many-page document. `per_page` supplies the operator list and the
/// resource dictionary for page `i`, so callers can give each page a distinct
/// font object — the arrangement that would expose a font cache keyed on the
/// resource NAME rather than the font object's id.
fn multi_page_doc<F>(n: usize, mut per_page: F) -> (Document, Vec<ObjectId>)
where
    F: FnMut(&mut Document, usize) -> (Vec<Operation>, Dictionary),
{
    let mut doc = Document::with_version("1.5");
    let pages_id = doc.new_object_id();
    let mut page_ids = Vec::new();
    for i in 0..n {
        let (ops, resources) = per_page(&mut doc, i);
        let content_id =
            doc.add_object(Stream::new(dictionary! {}, Content { operations: ops }.encode().unwrap()));
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 300.into(), 200.into()],
            "Contents" => content_id,
            "Resources" => resources,
        });
        page_ids.push(page_id);
    }
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages",
            "Kids" => page_ids.iter().map(|id| Object::Reference(*id)).collect::<Vec<_>>(),
            "Count" => n as i64,
        }),
    );
    let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", catalog_id);
    (doc, page_ids)
}

include!("differential_tests_part1.rs");
include!("differential_tests_part2.rs");
include!("differential_tests_part3.rs");
//! Embedded-font glyph outline extraction. Wraps `ttf-parser` for
//! TrueType/CFF/OpenType (`/FontFile2`, `/FontFile3`) and the hand-written
//! Type 1 interpreter (`/FontFile`), exposing a single `glyph_outline(code)`
//! that returns flattened contours in font-unit space plus the units-per-em.
//!
//! Rendering these real outlines (instead of substituting a system font) makes
//! both the glyph shapes and the letter spacing match the source PDF exactly.

use crate::fonts::FontInfo;
use crate::type1::Type1Font;
use std::collections::HashMap;

/// Bezier flattening resolution (segments per curve). Glyphs are usually small
/// on screen, so a modest fixed count keeps outlines smooth without bloat.
///
/// DELIBERATELY FIXED. A fixed count holds a constant fraction of the EM, so its
/// device error scales with FONT SIZE: at 10 segments a cap bowl (radius ≈0.35 em,
/// one cubic per quarter in CFF and Type 1) carries a sagitta of 0.0010789 em —
/// 0.14 device px on 12 pt text but 2.3 px on 200 pt text at the same zoom. The
/// path flattener does not share this, because `bezier_steps_for_flatness` adapts
/// to the curve's length in page points and so stays sub-pixel at any size
/// (measured 0.16-0.56 device px across the real zoom ceilings).
///
/// TUNING THIS CONSTANT IS NOT THE FIX, and two attempts to make it adaptive were
/// reverted. Nothing on this side of the wire knows the viewer's zoom —
/// `geometry::page_base_matrix` is a translate and a rotate with no scale, so
/// `Tfs * ctm_scale` is page POINTS per em, not pixels. Feeding that to a
/// pixel-denominated target gave 4 segments for 12 pt text, coarser than this.
///
/// The real fix is to stop pre-flattening and SEND CURVES, as clip paths already
/// do: `PathOp::Cubic` (the sole producer is the `c`/`v`/`y` arm in `interpret.rs`)
/// survives the wire and the consumer rebuilds a real path from it, so Skia
/// flattens clips at true DEVICE resolution and a clip curve never facets at any
/// zoom. Fills, strokes and these glyph contours instead ship point lists.
/// Extending the clip's representation to them is a change to `model.rs` and
/// `wire.rs`, so it is not reachable from this file.
const CURVE_STEPS: usize = 10;

/// Accumulates path segments into closed contours, flattening quadratic and
/// cubic beziers to polylines. Shared by the ttf-parser sink and the Type 1
/// interpreter so both emit the same contour representation.
pub(crate) struct ContourBuilder {
    contours: Vec<Vec<(f64, f64)>>,
    cur: Vec<(f64, f64)>,
    pos: (f64, f64),
}

impl ContourBuilder {
    pub(crate) fn new() -> Self {
        Self { contours: Vec::new(), cur: Vec::new(), pos: (0.0, 0.0) }
    }

    pub(crate) fn move_to(&mut self, x: f64, y: f64) {
        self.flush();
        self.cur.push((x, y));
        self.pos = (x, y);
    }

    pub(crate) fn line_to(&mut self, x: f64, y: f64) {
        self.cur.push((x, y));
        self.pos = (x, y);
    }

    pub(crate) fn quad_to(&mut self, cx: f64, cy: f64, x: f64, y: f64) {
        let (x0, y0) = self.pos;
        for k in 1..=CURVE_STEPS {
            let t = k as f64 / CURVE_STEPS as f64;
            let u = 1.0 - t;
            let px = u * u * x0 + 2.0 * u * t * cx + t * t * x;
            let py = u * u * y0 + 2.0 * u * t * cy + t * t * y;
            self.cur.push((px, py));
        }
        self.pos = (x, y);
    }

    pub(crate) fn curve_to(&mut self, c1x: f64, c1y: f64, c2x: f64, c2y: f64, x: f64, y: f64) {
        let (x0, y0) = self.pos;
        for k in 1..=CURVE_STEPS {
            let t = k as f64 / CURVE_STEPS as f64;
            let u = 1.0 - t;
            let w0 = u * u * u;
            let w1 = 3.0 * u * u * t;
            let w2 = 3.0 * u * t * t;
            let w3 = t * t * t;
            let px = w0 * x0 + w1 * c1x + w2 * c2x + w3 * x;
            let py = w0 * y0 + w1 * c1y + w2 * c2y + w3 * y;
            self.cur.push((px, py));
        }
        self.pos = (x, y);
    }

    pub(crate) fn close(&mut self) {
        self.flush();
    }

    fn flush(&mut self) {
        if self.cur.len() >= 3 {
            // Close the contour explicitly. A glyph contour is closed by
            // definition in every format here (TrueType, CFF and Type 1), and
            // this renderer represents closure as a duplicated first point —
            // `interpret.rs`'s `h` operator pushes the subpath's start point for
            // exactly this reason, and `Prim::Stroke` carries no closed flag.
            // Without it a stroked glyph (Tr 1/2) is drawn as an OPEN polyline, so
            // every contour is missing its final edge: outlined text shows a notch
            // in each letter and each counter. Fills are unaffected either way,
            // which is why this only ever showed up in stroke modes.
            //
            // NOTE (seam probe, this round): reverting this breaks ONLY tests in
            // this crate's font files. Nothing in `draw.rs` or the golden suite
            // notices that glyph contours arrive unclosed, so the contract with
            // `Prim::Stroke` is witnessed here and nowhere downstream.
            let first = self.cur[0];
            if let Some(&last) = self.cur.last() {
                if (last.0 - first.0).abs() > 1e-9 || (last.1 - first.1).abs() > 1e-9 {
                    self.cur.push(first);
                }
            }
            self.contours.push(std::mem::take(&mut self.cur));
        } else {
            self.cur.clear();
        }
    }

    /// Append an already-flattened contour. Used by `seac` accent composition,
    /// which must interpret the accent at its natural origin and then translate
    /// the result, because the accent's own `hsbw` overwrites the current point.
    pub(crate) fn add_contour(&mut self, c: Vec<(f64, f64)>) {
        if c.len() >= 3 {
            self.contours.push(c);
        }
    }

    pub(crate) fn finish(mut self) -> Vec<Vec<(f64, f64)>> {
        self.flush();
        self.contours
    }
}

/// Adapter so `ttf-parser` can emit into a `ContourBuilder`.
struct TtfSink<'a>(&'a mut ContourBuilder);

impl ttf_parser::OutlineBuilder for TtfSink<'_> {
    fn move_to(&mut self, x: f32, y: f32) {
        self.0.move_to(x as f64, y as f64);
    }
    fn line_to(&mut self, x: f32, y: f32) {
        self.0.line_to(x as f64, y as f64);
    }
    fn quad_to(&mut self, x1: f32, y1: f32, x: f32, y: f32) {
        self.0.quad_to(x1 as f64, y1 as f64, x as f64, y as f64);
    }
    fn curve_to(&mut self, x1: f32, y1: f32, x2: f32, y2: f32, x: f32, y: f32) {
        self.0.curve_to(x1 as f64, y1 as f64, x2 as f64, y2 as f64, x as f64, y as f64);
    }
    fn close(&mut self) {
        self.0.close();
    }
}

/// An embedded font program that can produce glyph outlines.
pub(crate) enum GlyphProgram {
    /// TrueType / OpenType (incl. OpenType-CFF) parsed lazily via ttf-parser.
    Sfnt { data: Vec<u8>, upm: f64 },
    /// Bare CFF (`/Type1C`, `/CIDFontType0C`) parsed via ttf-parser's CFF table.
    /// `cid_to_gid` is the inverted charset for CID-keyed CFF (else `None`).
    Cff { data: Vec<u8>, upm: f64, cid_to_gid: Option<HashMap<u32, u16>> },
    /// Bare Type 1 font, pre-interpreted to name -> contours.
    Type1(Type1Font),
}

impl GlyphProgram {
    pub(crate) fn units_per_em(&self) -> f64 {
        match self {
            GlyphProgram::Sfnt { upm, .. } | GlyphProgram::Cff { upm, .. } => *upm,
            GlyphProgram::Type1(t1) => {
                if t1.font_matrix[0].abs() > 1e-9 {
                    1.0 / t1.font_matrix[0]
                } else {
                    1000.0
                }
            }
        }
    }
}

/// Build a glyph outline program from the font's embedded program, if any.
/// `fd` is the (already-dereferenced) FontDescriptor dictionary.
pub(crate) fn build_glyph_program(
    doc: &lopdf::Document,
    fd: Option<&lopdf::Dictionary>,
) -> Option<GlyphProgram> {
    let fd = fd?;
    // TrueType (may also be an OpenType wrapper).
    if let Some(data) = font_file_stream(doc, fd, b"FontFile2") {
        let upm = ttf_parser::Face::parse(&data, 0)
            .ok()
            .map(|f| f.units_per_em() as f64)
            .filter(|u| *u > 0.0)
            .unwrap_or(1000.0);
        return Some(GlyphProgram::Sfnt { data, upm });
    }
    // CFF (`/FontFile3`): OpenType-CFF parses as an sfnt Face; bare CFF
    // (`/Type1C`, `/CIDFontType0C`) is parsed directly via ttf-parser's CFF table.
    if let Some(data) = font_file_stream(doc, fd, b"FontFile3") {
        if let Ok(f) = ttf_parser::Face::parse(&data, 0) {
            let upm = (f.units_per_em() as f64).max(1.0);
            return Some(GlyphProgram::Sfnt { data, upm });
        }
        if let Some(table) = ttf_parser::cff::Table::parse(&data) {
            // FontMatrix sx (≈0.001) gives units-per-em; ignore skew/translation,
            // which are zero for essentially all text fonts.
            let sx = table.matrix().sx as f64;
            let upm = if sx.abs() > 1e-9 { 1.0 / sx } else { 1000.0 };
            // CID-keyed CFF: build the charset CID->GID map so CIDs select glyphs.
            let cid_to_gid = crate::cff::cid_to_gid_map(&data);
            return Some(GlyphProgram::Cff { data, upm, cid_to_gid });
        }
    }
    // Bare Type 1 (`/FontFile`): eexec-encrypted charstrings.
    if let Some(data) = font_file_stream(doc, fd, b"FontFile") {
        if let Some(t1) = crate::type1::parse(&data) {
            return Some(GlyphProgram::Type1(t1));
        }
    }
    None
}

fn font_file_stream(doc: &lopdf::Document, fd: &lopdf::Dictionary, key: &[u8]) -> Option<Vec<u8>> {
    match fd.get(key).ok().and_then(|o| crate::deref(doc, o)) {
        Some(lopdf::Object::Stream(s)) => Some(crate::stream_data(s)),
        _ => None,
    }
}

/// Flattened glyph contours in font units, paired with the font's units-per-em.
pub(crate) type GlyphContours = (Vec<Vec<(f64, f64)>>, f64);

/// Return the flattened outline (font-unit contours) and units-per-em for `code`.
/// `code` is the raw content-stream code (CID for Type0, byte for simple fonts).
pub(crate) fn glyph_outline(fi: &FontInfo, code: u32) -> Option<GlyphContours> {
    let program = fi.glyph_program.as_deref()?;
    match program {
        GlyphProgram::Type1(t1) => {
            // PDF 32000-1 9.6.6.2 order: /Differences and a named base encoding
            // (both already folded into `glyph_names`) outrank the program's own
            // built-in /Encoding, which is consulted next.
            //
            // A base-encoding name the program does not actually define falls
            // THROUGH to the built-in rather than giving up: the base encoding
            // says what the code means, the built-in says what this particular
            // subset calls it, and dropping to the substitute face when the two
            // disagree would lose an outline the font does contain.
            let contours = fi
                .glyph_names
                .get(&code)
                .and_then(|n| t1.glyphs.get(n))
                .or_else(|| t1.encoding.get(&code).and_then(|n| t1.glyphs.get(n)))?;
            if contours.is_empty() {
                return None;
            }
            Some((contours.clone(), program.units_per_em()))
        }
        GlyphProgram::Sfnt { data, upm } => {
            let face = ttf_parser::Face::parse(data, 0).ok()?;
            let gid = resolve_gid(fi, &face, code)?;
            let mut cb = ContourBuilder::new();
            let mut sink = TtfSink(&mut cb);
            face.outline_glyph(gid, &mut sink)?;
            let contours = cb.finish();
            if contours.is_empty() {
                return None;
            }
            Some((contours, *upm))
        }
        GlyphProgram::Cff { data, upm, cid_to_gid } => {
            let table = ttf_parser::cff::Table::parse(data)?;
            let gid = resolve_cff_gid(fi, &table, code, cid_to_gid.as_ref())?;
            let mut cb = ContourBuilder::new();
            let mut sink = TtfSink(&mut cb);
            table.outline(gid, &mut sink).ok()?;
            let contours = cb.finish();
            if contours.is_empty() {
                return None;
            }
            Some((contours, *upm))
        }
    }
}

/// Glyph names of the form `gNN`, `glyphNN`, `cidNN` or `indexNN` are direct
/// glyph-index references emitted by some subsetters. They carry no Unicode
/// meaning, so they cannot be resolved through the AGL and are decoded here.
/// Only consulted after the font's own name tables have failed.
fn name_as_gid(name: &str) -> Option<u32> {
    for prefix in ["glyph", "index", "cid", "g"] {
        if let Some(rest) = name.strip_prefix(prefix) {
            if !rest.is_empty() && rest.bytes().all(|b| b.is_ascii_digit()) {
                return rest.parse::<u32>().ok();
            }
        }
    }
    None
}

/// Map a content-stream code to a glyph id in a bare CFF table. `cff_cid_to_gid`
/// is the font's own charset inversion for CID-keyed CFF.
fn resolve_cff_gid(fi: &FontInfo, table: &ttf_parser::cff::Table, code: u32, cff_cid_to_gid: Option<&HashMap<u32, u16>>) -> Option<ttf_parser::GlyphId> {
    let n = table.number_of_glyphs();
    let as_gid = |g: u32| -> Option<ttf_parser::GlyphId> {
        if g < n as u32 { Some(ttf_parser::GlyphId(g as u16)) } else { None }
    };
    if fi.two_byte {
        // Map code -> CID (via /Encoding CMap), then CID -> GID. A /CIDToGIDMap
        // stream is authoritative (PDF 32000-1 9.7.4.2): an entry of 0, or a CID
        // past the end of the stream, means .notdef — report None so the caller
        // substitutes, instead of falling through to identity and drawing an
        // arbitrary glyph. Identity applies only to /CIDToGIDMap /Identity or an
        // absent key, which `fi.cid_to_gid == None` already encodes.
        let cid = fi.to_cid(code);
        let gid = match fi.cid_to_gid.as_ref() {
            Some(m) => match m.get(&cid).copied() {
                Some(0) | None => return None,
                Some(g) => g,
            },
            None => cff_cid_to_gid.and_then(|m| m.get(&cid).copied()).unwrap_or(cid as u16),
        };
        return as_gid(gid as u32);
    }
    // Simple CFF: prefer glyph name, then the CFF's own 8-bit encoding.
    if let Some(name) = fi.glyph_names.get(&code) {
        if let Some(gid) = table.glyph_index_by_name(name) {
            return Some(gid);
        }
        if let Some(gid) = name_as_gid(name).and_then(as_gid) {
            return Some(gid);
        }
    }
    if code <= 0xFF {
        if let Some(gid) = table.glyph_index(code as u8) {
            return Some(gid);
        }
    }
    // A CID-keyed CFF has no 8-bit encoding at all — `glyph_index` answers None
    // structurally, not because the code is uncovered — so read the code as a CID
    // through the charset exactly as the Type0 path above does.
    if let Some(m) = cff_cid_to_gid {
        return as_gid(m.get(&code).copied().map_or(code, u32::from));
    }
    // A name-keyed CFF always HAS an encoding: ttf-parser falls back to the format's
    // own StandardEncoding when the font declares none, so reaching here means the
    // code genuinely selects no glyph in this program. Report None and let the
    // caller substitute. Treating the code as a glyph id was the one thing left
    // here that could not be right: CFF glyph ids are ordered by the charset and
    // never by character code, so `as_gid(code)` painted an unrelated letter at the
    // correct position — text that reads as nonsense rather than text that is
    // missing — and suppressed the substitute-font fallback that would have drawn
    // the right one. `resolve_gid` already refuses the same shortcut for an sfnt
    // that has a cmap; the two paths now agree.
    None
}

include!("outlines_part1.rs");
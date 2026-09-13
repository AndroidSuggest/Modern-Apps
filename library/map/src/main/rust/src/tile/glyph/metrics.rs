/// Font units per em for the bundled Noto Sans.
///
/// The denominator that turns a font-unit metric into a fraction of an em, and so the
/// thing that decides how big `text_size` px actually draws. It was 2048 — the common
/// value, but not this font's — which rendered every label at 1000/2048 of its size.
/// `the_bundled_fonts_use_the_declared_upem` asserts it against the fonts themselves,
/// so swapping in a 2048-upem face fails a test instead of shrinking the map's text.
pub const UP_EM: u16 = 1000;

/// Atlas grid: 16×16 cells.
pub const ATLAS_COLS: u32 = 16;
/// Cell size in px, including padding. 64px at 4x raster of a 16px glyph.
pub const CELL_PX: u32 = 64;
/// Atlas edge in px.
pub const ATLAS_PX: u32 = ATLAS_COLS * CELL_PX;
/// SDF spread in px each side of the edge: 8px at cell resolution.
pub const SDF_SPREAD_PX: u32 = 8;

/// Noto Sans Regular, embedded (task 54 staged the TTF; the APK asset is not a file
/// the renderer can open, so the bytes ship in the `.so` — 267 KB).
pub(super) const REGULAR_TTF: &[u8] =
    include_bytes!("../../../assets/fonts/NotoSans-Regular.ttf");
/// Noto Sans Medium, embedded likewise.
pub(super) const MEDIUM_TTF: &[u8] =
    include_bytes!("../../../assets/fonts/NotoSans-Medium.ttf");

/// Which bundled weight a label uses. The authored style uses Regular everywhere
/// except country labels and big cities (Medium).
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum Weight {
    Regular,
    Medium,
}

/// Per-glyph metrics in font units, plus the atlas cell.
///
/// `bearing_x`, `top`, `w` and `h` describe the **quad**, not the ink: they cover the
/// glyph bitmap grown by the SDF spread on every side, which is exactly the region
/// [`GlyphMetrics::uv`] addresses. Quad and UV have to be derived from the same
/// placement or the ink draws at the wrong scale inside a correctly-sized advance —
/// see `place_in_cell`.
#[derive(Clone, Copy, Debug)]
pub struct GlyphMetrics {
    /// Advance width including kerning base, in font units. The one field that is a
    /// pure font metric: padding the quad must not move the pen.
    pub advance: f32,
    /// Quad's left edge relative to the pen, in font units.
    pub bearing_x: f32,
    /// Quad's top above the baseline, in font units (y-up).
    pub top: f32,
    /// Quad width/height in font units.
    pub w: f32,
    pub h: f32,
    /// Atlas cell index.
    pub cell: u32,
    /// The sub-rect of [`GlyphMetrics::cell`] this glyph's quad samples.
    pub uv: UvRect,
}

/// UV rect of one cell, with `v0` at the atlas top.
#[derive(Clone, Copy, Debug)]
pub struct UvRect {
    pub u0: f32,
    pub v0: f32,
    pub u1: f32,
    pub v1: f32,
}

/// Whether the staged TTFs are real fonts (SFNT magic), not 404 pages.
///
/// Task 54 staged GitHub 404 HTML under the `.ttf` names; tests needing glyphs
/// check this and skip loudly until real Noto Sans lands.
pub fn fonts_staged() -> bool {
    REGULAR_TTF.starts_with(&[0x00, 0x01, 0x00, 0x00])
        && MEDIUM_TTF.starts_with(&[0x00, 0x01, 0x00, 0x00])
}

/// The Latin set M1 shapes: printable ASCII. `name:en` coalesced by the tiler is
/// Latin in the overwhelming NA case; CJK/others are a stated M5.
pub fn charset() -> Vec<char> {
    (0x20u8..=0x7Eu8).map(|b| b as char).collect()
}

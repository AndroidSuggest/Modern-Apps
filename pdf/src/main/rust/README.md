# pdf_render — memory-safe PDF renderer (Rust + JNI)

Parses PDFs entirely in Rust with [`lopdf`] (no pdfium / no system PDF stack)
and reduces each page to plain drawing primitives — text runs (with accurate
advance), filled/stroked polygons, raster images (JPEG, JPEG2000 via openjp2,
JBIG2 via hayro-jbig2, CCITTFax G3/G4 via the `fax` crate), clipping with bezier
retention, axial/radial and mesh shadings, tiling/shading patterns (for both
fills and strokes), Type 3 fonts, transparency groups, and ExtGState soft masks —
for the "Open PDF (safe)" viewer. Built into `libpdf_render.so` and called from
Kotlin via `PdfNative`.

The point of "safe": all untrusted binary parsing happens in memory-safe Rust,
and the JNI boundary only ever passes a flat little-endian buffer of geometry +
UTF-8 text, so Kotlin never touches the raw PDF bytes.

## Scope (v5 — implemented)

- **PDF functions** (`functions.rs`): a full evaluator for all four function
  types — Type 0 sampled (multilinear interpolation), Type 2 exponential, Type 3
  stitching, and Type 4 PostScript calculator (a bounded stack machine over the
  arithmetic/comparison/stack/control operator subset). Shared by colorspaces
  and shadings.
- **Text** with `/ToUnicode` decoding (1-byte simple fonts and 2-byte
  Identity-H / Type0), full base encodings (WinAnsi, **real MacRoman**, Standard,
  Symbol, ZapfDingbats), a comprehensive Adobe Glyph List, and embedded-program
  encoding recovery: TrueType `cmap`, **Type 1 `/FontFile`** clear-text
  `/Encoding` scan, and **CFF `/FontFile3`** charset+Encoding recovery
  (`cff.rs`). Text render modes 0–7 including the clip modes (4–7). Accurate
  glyph advance from `/Widths`/`/W`/`/DW` + `Tc`/`Tw`/`Th`.
- **Type 3 fonts**: glyph CharProc content streams are interpreted and drawn
  (`draw.rs::show_string_type3`), mapped through the font matrix and bounded by
  `MAX_TYPE3_GLYPHS` / `MAX_TYPE3_PRIMS_PER_GLYPH`.
- **Vector paths**: lines, rectangles, filled/stroked paths, flattened beziers
  for drawing; clip paths retain the exact beziers via `PathOp`
  (Move/Line/Cubic/Close) and are drawn in Kotlin with `cubicTo` (wire v4).
- **Color**: `g/G/rg/RG/k/K` plus `CS/cs/SC/sc/SCN/scn`. Separation and DeviceN
  evaluate their tint transforms through the full `PdfFunction` evaluator (any
  function type, all N inputs), plus Lab, CalRGB/CalGray, ICCBased (alternate),
  and Indexed. **Raster image samples** are converted through the same
  colorspace machinery (with LUTs for single-component and indexed images), so
  Separation/DeviceN/Lab/ICC images are colored correctly.
- **Shadings** (`images.rs` + `shading.rs`): Type 1 (function-based), Type 2
  axial, Type 3 radial (all using `PdfFunction`), and real mesh shadings —
  Type 4 free-form Gouraud (flag-driven triangle strips), Type 5 lattice
  (`/VerticesPerRow`), and Type 6/7 Coons/tensor patches subdivided from their
  actual boundary Bézier curves. Radial (Type 3) shadings solve the true
  circle-family parameter per pixel (honoring `/Extend` and non-negative radii),
  rather than approximating them as axial. A byte-accurate `BitReader` handles
  arbitrary `BitsPerCoordinate/Component/Flag`.
- **Patterns** (`interpret.rs`): PatternType 2 (shading) patterns are rasterized
  with the pattern `/Matrix` and clipped to the fill region; PatternType 1
  (tiling) patterns replay their content stream tiled across the fill bbox
  (colored and uncolored `/PaintType`), bounded by `MAX_PATTERN_RECURSION` and a
  per-pattern tile cap. Both tiling and shading patterns are honored for
  **strokes** too: the stroked path is converted to outline quads and the
  pattern is painted within each segment (`paint_pattern_stroke`).
- **Filters** (`filters.rs`): ASCIIHex, ASCII85, RunLength, LZW and Flate, both
  now with PNG (Predictor 10–15) **and TIFF (Predictor 2)** support; CCITT
  G3/G4; JBIG2 and DCT/JPX passthrough. Decode failures for JPX/CCITT/DCT no
  longer fall through to reinterpreting encoded bytes as raw samples.
- **Encryption** (`crypto.rs` + `decrypt.rs`): open and save with the Standard
  security handler — RC4-128 and **AES-128 (V4/R4)** and **AES-256 (V5/R6)**.
  `save_encrypted` defaults to AES-128.
- **Wire format v5**: header `MAGIC 0x50444657 VERSION=5 f32 w,h u32 count`;
  tags 1 Text, 2 Fill, 3 Stroke, 4 Image, 5 ClipPush (with a bezier-retentive
  path-ops section), 6 ClipPop, 7 GroupPush, 8 GroupPop, 9 TextClipApply,
  10 SoftMaskPush, 11 SoftMaskContent, 12 SoftMaskPop. Text carries its render
  mode; Text/Fill/Stroke each carry a per-primitive blend byte (v5).
  `SafePdfParser.kt` parses v5 and keeps v1–v4 as fallbacks.
- **Blend modes**: honored not just on transparency groups but on individual
  fills, strokes and text (the graphics-state `/BM` travels with each Text/Fill/
  Stroke primitive and is applied per-draw in Kotlin via `Paint.blendMode`
  (API 29+) / Compose `BlendMode`, with a Porter-Duff fallback for the separable
  modes on older devices).
- **ExtGState soft masks** (`/SMask`): luminosity and alpha soft masks set via
  `gs` are applied around the following Form XObject. Rust brackets the masked
  content and the `/G` group content with SoftMaskPush/Content/Pop; Kotlin
  composites them with nested `saveLayer`s — a `DST_IN` mask layer, plus a
  luminance→alpha `ColorMatrix` layer for luminosity masks. `/SMask /None`
  clears the mask.
- **Kotlin drawing**: bezier clips via `cubicTo`; text-clip modes accumulate
  glyph outlines (`Paint.getTextPath`) and intersect them into the clip at the
  `TextClipApply` marker; blend modes use `Paint.blendMode` on API 29+ and fall
  back to `PorterDuffXfermode` (Multiply/Screen/Darken/Lighten) on older devices.
- **Search**: a per-page text index stores both a lowercased (byte-aligned) and
  original-case string, so case-sensitive and case-insensitive search are both
  exact.
- **Annotations / forms**: `subtype_code` covers the full ISO 32000 annotation
  subtype set; text-field appearance regeneration honors `/Q` alignment,
  multiline wrapping and comb (`/MaxLen`) fields, alongside checkboxes.

## Genuinely unsupported (documented, not silently skipped)

- **Public-key / certificate encryption** (`/Filter /Adobe.PubSec`): decryption
  requires the recipient's private key, which the viewer does not possess.
  Reported as `DecryptStatus::Unsupported`.
- **Type 1 / CFF glyph *outline* vector rendering**: the renderer emits Unicode
  text runs drawn with system fonts, so embedded Type 1/CFF programs are parsed
  only for code→Unicode recovery, not redrawn as vector outlines. (Text-clip
  modes therefore clip with the system-font outlines via `Paint.getTextPath`.)
- **Non-separable blend modes on API < 29** (Hue/Saturation/Color/Luminosity,
  and Overlay/ColorDodge/…): no native pre-29 support; they fall back to Normal.
- **Knockout transparency groups**: not expressible with Canvas layers;
  approximated as non-knockout.

## Prerequisites (local + CI)

`rustup target add aarch64-linux-android`. `./gradlew :pdf:assembleDebug`
triggers `cargoNdkBuild` with NDK 29.

## Host tests

```sh
cd pdf/src/main/rust
cargo test
```

Covers PDF function evaluation (all four types), radial-shading parameterization,
mesh-shading rasterization, stroke-outline quad generation, Type 3 glyph
emission, image colorspace conversion, LZW/TIFF predictors, RC4/AES-128/AES-256
save round-trips, CFF/Type 1 encoding recovery, case-sensitive search, and the
wire round-trip (including per-primitive blend and soft-mask markers).

[`lopdf`]: https://crates.io/crates/lopdf

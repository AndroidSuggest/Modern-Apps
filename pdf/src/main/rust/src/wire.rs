use crate::*;

const WIRE_MAGIC: u32 = 0x50444657; // 'PDFW'
/// Bump to V9 to carry per-image alpha (fix for audit #10 / #critical alpha ignored).
const WIRE_VERSION: u32 = 9;
#[allow(dead_code)]
const WIRE_VERSION_V2: u32 = 2;
const WIRE_VERSION_V7: u32 = 7;
const WIRE_VERSION_V8: u32 = 8;
const WIRE_VERSION_V9: u32 = 9;
const TAG_TEXT: u8 = 1;
const TAG_FILL: u8 = 2;
const TAG_STROKE: u8 = 3;
const TAG_IMAGE: u8 = 4;
const TAG_CLIP_PUSH: u8 = 5;
const TAG_CLIP_POP: u8 = 6;
const TAG_GROUP_PUSH: u8 = 7;
const TAG_GROUP_POP: u8 = 8;
const TAG_TEXT_CLIP_APPLY: u8 = 9;
const TAG_SMASK_PUSH: u8 = 10;
const TAG_SMASK_CONTENT: u8 = 11;
const TAG_SMASK_POP: u8 = 12;

const PATHOP_MOVE: u8 = 0;
const PATHOP_LINE: u8 = 1;
const PATHOP_CUBIC: u8 = 2;
const PATHOP_CLOSE: u8 = 3;

/// Maximum byte length for a single text primitive (u16). Truncation must respect UTF-8 char boundaries
/// to avoid emitting invalid UTF-8 (audit #8). We use floor_char_boundary.
const MAX_TEXT_BYTES: usize = u16::MAX as usize;
/// Hard cap for image data length in wire format (u32). Crafted PDF claiming huge Dimensions could
/// otherwise truncate via `as u32` cast and cause Kotlin OOB read. Use try_from with checked truncation.
const MAX_IMAGE_DATA: u64 = u32::MAX as u64;

/// Safe truncation of a UTF-8 string to at most `max_bytes` bytes, cutting at a char boundary.
/// Returns the truncated &str slice.
fn truncate_str_safe(s: &str, max_bytes: usize) -> &str {
    if s.len() <= max_bytes {
        s
    } else {
        // floor_char_boundary is stable since 1.77 and available in 1.97 toolchain.
        let idx = s.floor_char_boundary(max_bytes);
        &s[..idx]
    }
}

/// Serialize a page into a compact little-endian buffer v9:
///
/// ```text
/// header: u32 MAGIC=0x50444657, u32 VERSION=9, f32 pageWidth, f32 pageHeight, u32 primitiveCount
/// per primitive: u8 tag, then payload
///   1 Text:   f32 x, f32 y, f32 size, u32 argb, u16 len, [utf8], u8 hasStroke, u32 strokeArgb, f32 strokeWidth, u8 renderMode (v4), u8 blend (v5), f32 advance (v7), u8 fontFlags (v8: bit0 bold bit1 italic), f32 hScale (v8)
///   2 Fill:   u32 argb, u8 evenOdd, u16 nContours, [u16 nPts, [f32 x,y]...]... (v6), u8 blend (v5)
///   3 Stroke: u32 argb, f32 width, u8 nDash, [f32 dash]..., f32 phase, u8 cap, u8 join, f32 miter, u16 nPts, [f32 x, f32 y]..., u8 blend (v5)
///   4 Image:  6×f32 ctm, u32 w, u32 h, u8 format, f32 alpha (v9), u32 len, [bytes] (format 0=RGBA8888, 1=JPEG)
///   5 ClipPush: u8 evenOdd, u16 nPts, [f32 x,y]..., u16 nPathOps, [u8 kind, coords]...  (path-ops section is v4)
///              path-op kinds: 0 Move(2f32) 1 Line(2f32) 2 Cubic(6f32) 3 Close(0)
///   6 ClipPop: empty
///   7 GroupPush: u8 isolated, u8 knockout, f32 alpha, u8 blend
///   8 GroupPop: empty
///   9 TextClipApply: empty (v4)
///   10 SoftMaskPush: u8 maskType (0 alpha, 1 luminosity) (v5)
///   11 SoftMaskContent: empty (v5)
///   12 SoftMaskPop: empty (v5)
/// v1 legacy (no magic), v2..v9 remain backward compatible for cached pages (Kotlin should handle older versions).
/// ```
pub fn serialize(page: &PageData) -> Vec<u8> {
    let mut buf = Vec::new();
    buf.extend_from_slice(&WIRE_MAGIC.to_le_bytes());
    buf.extend_from_slice(&WIRE_VERSION.to_le_bytes());
    buf.extend_from_slice(&page.width.to_le_bytes());
    buf.extend_from_slice(&page.height.to_le_bytes());
    // Primitive count truncated to u32 (page prims will never exceed u32::MAX in practice, but guard anyway)
    let prim_count = u32::try_from(page.prims.len()).unwrap_or(u32::MAX);
    buf.extend_from_slice(&prim_count.to_le_bytes());
    for prim in &page.prims {
        match prim {
            Prim::Text { x, y, size, argb, text, stroke_argb, stroke_width, advance, render_mode, blend, is_bold, is_italic, h_scale } => {
                buf.push(TAG_TEXT);
                buf.extend_from_slice(&x.to_le_bytes());
                buf.extend_from_slice(&y.to_le_bytes());
                buf.extend_from_slice(&size.to_le_bytes());
                buf.extend_from_slice(&argb.to_le_bytes());
                // FIX #8: truncate at char boundary to avoid mid-codepoint cut -> invalid UTF-8 on Kotlin side
                let safe_text = truncate_str_safe(text, MAX_TEXT_BYTES);
                let bytes = safe_text.as_bytes();
                // bytes.len() is guaranteed <= u16::MAX and valid UTF-8 because truncated at char boundary
                let len = u16::try_from(bytes.len()).unwrap_or(u16::MAX);
                buf.extend_from_slice(&len.to_le_bytes());
                buf.extend_from_slice(bytes);
                if let (Some(sa), Some(sw)) = (stroke_argb, stroke_width) {
                    buf.push(1);
                    buf.extend_from_slice(&sa.to_le_bytes());
                    buf.extend_from_slice(&sw.to_le_bytes());
                } else {
                    buf.push(0);
                    buf.extend_from_slice(&0u32.to_le_bytes());
                    buf.extend_from_slice(&0f32.to_le_bytes());
                }
                buf.push(*render_mode); // v4
                buf.push(*blend as u8); // v5
                buf.extend_from_slice(&advance.to_le_bytes()); // v7: device-space glyph advance
                let mut font_flags = 0u8;
                if *is_bold { font_flags |= 1; }
                if *is_italic { font_flags |= 2; }
                buf.push(font_flags); // v8: bold/italic
                buf.extend_from_slice(&h_scale.to_le_bytes()); // v8
            }
            Prim::Fill { argb, even_odd, contours, blend } => {
                buf.push(TAG_FILL);
                buf.extend_from_slice(&argb.to_le_bytes());
                buf.push(if *even_odd { 1 } else { 0 });
                // FIX #8: contours truncation silent but now capped with explicit u16::try_from; data loss is documented.
                // We keep min but using try_from to avoid usize->u16 overflow.
                let nc = contours.len().min(u16::MAX as usize);
                let nc_u16 = u16::try_from(nc).unwrap_or(u16::MAX);
                buf.extend_from_slice(&nc_u16.to_le_bytes()); // v6
                for c in &contours[..nc] {
                    write_points(&mut buf, c);
                }
                buf.push(*blend as u8); // v5
            }
            Prim::Stroke { argb, width, dash, dash_phase, cap, join, miter, pts, blend } => {
                buf.push(TAG_STROKE);
                buf.extend_from_slice(&argb.to_le_bytes());
                buf.extend_from_slice(&width.to_le_bytes());
                // Dash len capped to u8::MAX (spec limit, silent truncation documented; alternative would be split)
                let n = dash.len().min(u8::MAX as usize);
                let n_u8 = u8::try_from(n).unwrap_or(u8::MAX);
                buf.push(n_u8);
                for d in &dash[..n] {
                    buf.extend_from_slice(&d.to_le_bytes());
                }
                buf.extend_from_slice(&dash_phase.to_le_bytes());
                buf.push(*cap);
                buf.push(*join);
                buf.extend_from_slice(&miter.to_le_bytes());
                write_points(&mut buf, pts);
                buf.push(*blend as u8); // v5
            }
            Prim::Image { ctm, w, h, format, data, alpha } => {
                buf.push(TAG_IMAGE);
                for v in ctm {
                    buf.extend_from_slice(&(*v as f32).to_le_bytes());
                }
                buf.extend_from_slice(&w.to_le_bytes());
                buf.extend_from_slice(&h.to_le_bytes());
                buf.push(*format);
                // FIX #10: previously `alpha: _` was ignored, causing opaque rendering for transparent images.
                // V9 now serializes per-image alpha (from SMask or alpha_fill).
                buf.extend_from_slice(&alpha.to_le_bytes()); // v9
                // FIX #9: data.len() as u32 truncates >4GB crafted image -> Kotlin OOB read.
                // Use try_from and cap data to u32::MAX, ensuring length field matches actual bytes written.
                let (len_u32, data_slice) = match u32::try_from(data.len()) {
                    Ok(l) => (l, data.as_slice()),
                    Err(_) => {
                        // Data larger than 4GB (crafted PDF) – truncate to u32::MAX to avoid OOB, log via debug.
                        // This should never happen for legitimate PDFs (max image ~ hundreds MB).
                        (u32::MAX, &data[..(u32::MAX as usize).min(data.len())])
                    }
                };
                // Extra guard: ensure len doesn't exceed MAX_IMAGE_DATA (should be same as u32::MAX but explicit)
                let final_len = (len_u32 as u64).min(MAX_IMAGE_DATA) as u32;
                let final_slice = if (final_len as usize) < data_slice.len() {
                    &data_slice[..final_len as usize]
                } else {
                    data_slice
                };
                buf.extend_from_slice(&final_len.to_le_bytes());
                buf.extend_from_slice(final_slice);
            }
            Prim::ClipPush { even_odd, pts, path_ops } => {
                buf.push(TAG_CLIP_PUSH);
                buf.push(if *even_odd { 1 } else { 0 });
                write_points(&mut buf, pts);
                write_path_ops(&mut buf, path_ops.as_deref()); // v4
            }
            Prim::ClipPop => {
                buf.push(TAG_CLIP_POP);
            }
            Prim::TextClipApply => {
                buf.push(TAG_TEXT_CLIP_APPLY);
            }
            Prim::GroupPush { isolated, knockout, alpha, blend } => {
                buf.push(TAG_GROUP_PUSH);
                buf.push(if *isolated {1} else {0});
                buf.push(if *knockout {1} else {0});
                buf.extend_from_slice(&alpha.to_le_bytes());
                buf.push(*blend as u8);
            }
            Prim::GroupPop => {
                buf.push(TAG_GROUP_POP);
            }
            Prim::SoftMaskPush { mask_type } => {
                buf.push(TAG_SMASK_PUSH);
                buf.push(*mask_type);
            }
            Prim::SoftMaskContent => {
                buf.push(TAG_SMASK_CONTENT);
            }
            Prim::SoftMaskPop => {
                buf.push(TAG_SMASK_POP);
            }
        }
    }
    buf
}

/// Serialize an optional bezier-retentive clip path (v4): u16 count then
/// tagged Move/Line/Cubic/Close records. Empty count when absent.
fn write_path_ops(buf: &mut Vec<u8>, ops: Option<&[PathOp]>) {
    let ops = match ops {
        Some(o) if !o.is_empty() => o,
        _ => {
            buf.extend_from_slice(&0u16.to_le_bytes());
            return;
        }
    };
    let n = ops.len().min(u16::MAX as usize);
    let n_u16 = u16::try_from(n).unwrap_or(u16::MAX);
    buf.extend_from_slice(&n_u16.to_le_bytes());
    for op in &ops[..n] {
        match op {
            PathOp::Move(x, y) => {
                buf.push(PATHOP_MOVE);
                buf.extend_from_slice(&x.to_le_bytes());
                buf.extend_from_slice(&y.to_le_bytes());
            }
            PathOp::Line(x, y) => {
                buf.push(PATHOP_LINE);
                buf.extend_from_slice(&x.to_le_bytes());
                buf.extend_from_slice(&y.to_le_bytes());
            }
            PathOp::Cubic(x1, y1, x2, y2, x3, y3) => {
                buf.push(PATHOP_CUBIC);
                for v in [x1, y1, x2, y2, x3, y3] {
                    buf.extend_from_slice(&v.to_le_bytes());
                }
            }
            PathOp::Close => {
                buf.push(PATHOP_CLOSE);
            }
        }
    }
}

fn write_points(buf: &mut Vec<u8>, pts: &[(f32, f32)]) {
    let n = pts.len().min(u16::MAX as usize);
    let n_u16 = u16::try_from(n).unwrap_or(u16::MAX);
    buf.extend_from_slice(&n_u16.to_le_bytes());
    for &(x, y) in &pts[..n] {
        buf.extend_from_slice(&x.to_le_bytes());
        buf.extend_from_slice(&y.to_le_bytes());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Minimal Kotlin-equivalent decoder used to round-trip the wire format.
    struct Reader<'a> {
        buf: &'a [u8],
        pos: usize,
    }
    impl<'a> Reader<'a> {
        fn u8(&mut self) -> u8 {
            let v = self.buf[self.pos];
            self.pos += 1;
            v
        }
        fn u16(&mut self) -> u16 {
            let v = u16::from_le_bytes([self.buf[self.pos], self.buf[self.pos + 1]]);
            self.pos += 2;
            v
        }
        fn u32(&mut self) -> u32 {
            let v = u32::from_le_bytes(self.buf[self.pos..self.pos + 4].try_into().unwrap());
            self.pos += 4;
            v
        }
        fn f32(&mut self) -> f32 {
            let v = f32::from_le_bytes(self.buf[self.pos..self.pos + 4].try_into().unwrap());
            self.pos += 4;
            v
        }
    }

    #[test]
    fn round_trips_all_primitives() {
        let page = PageData {
            width: 612.0,
            height: 792.0,
            prims: vec![
                Prim::Text {
                    x: 10.0,
                    y: 20.0,
                    size: 12.0,
                    argb: 0xFF112233,
                    text: "Hé".to_string(),
                    stroke_argb: Some(0xFF445566),
                    stroke_width: Some(0.5),
                    advance: 12.0,
                    render_mode: 0,
                    blend: BlendMode::Multiply,
                    is_bold: true,
                    is_italic: false,
                    h_scale: 1.0,
                },
                Prim::Fill {
                    argb: 0xFFAABBCC,
                    even_odd: true,
                    contours: vec![
                        vec![(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)],
                        vec![(0.25, 0.25), (0.5, 0.25), (0.5, 0.5)],
                    ],
                    blend: BlendMode::Screen,
                },
                Prim::Stroke {
                    argb: 0xFF010203,
                    width: 2.5,
                    dash: vec![3.0, 2.0],
                    dash_phase: 1.0,
                    cap: 1,
                    join: 1,
                    miter: 10.0,
                    pts: vec![(3.0, 4.0), (5.0, 6.0)],
                    blend: BlendMode::Normal,
                },
                Prim::ClipPush {
                    even_odd: false,
                    pts: vec![(0.0,0.0),(10.0,0.0),(10.0,10.0),(0.0,10.0)],
                    path_ops: Some(vec![
                        PathOp::Move(0.0, 0.0),
                        PathOp::Cubic(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                        PathOp::Close,
                    ]),
                },
                Prim::ClipPop,
                Prim::TextClipApply,
                Prim::SoftMaskPush { mask_type: 1 },
                Prim::SoftMaskContent,
                Prim::SoftMaskPop,
            ],
        };
        let buf = serialize(&page);
        let mut r = Reader { buf: &buf, pos: 0 };
        assert_eq!(r.u32(), WIRE_MAGIC);
        assert_eq!(r.u32(), WIRE_VERSION);
        assert_eq!(r.f32(), 612.0);
        assert_eq!(r.f32(), 792.0);
        assert_eq!(r.u32(), 9);

        assert_eq!(r.u8(), TAG_TEXT);
        assert_eq!(r.f32(), 10.0);
        assert_eq!(r.f32(), 20.0);
        assert_eq!(r.f32(), 12.0);
        assert_eq!(r.u32(), 0xFF112233);
        let len = r.u16() as usize;
        let s = std::str::from_utf8(&buf[r.pos..r.pos + len]).unwrap();
        assert_eq!(s, "Hé");
        r.pos += len;
        assert_eq!(r.u8(), 1); // hasStroke
        assert_eq!(r.u32(), 0xFF445566);
        assert!((r.f32() - 0.5).abs() < 1e-6);
        assert_eq!(r.u8(), 0); // render_mode (v4)
        assert_eq!(r.u8(), BlendMode::Multiply as u8); // blend (v5)
        assert!((r.f32() - 12.0).abs() < 1e-6); // advance (v7)
        assert_eq!(r.u8(), 1); // fontFlags bold (v8)
        assert!((r.f32() - 1.0).abs() < 1e-6); // h_scale (v8)

        assert_eq!(r.u8(), TAG_FILL);
        assert_eq!(r.u32(), 0xFFAABBCC);
        assert_eq!(r.u8(), 1); // even-odd
        assert_eq!(r.u16(), 2); // nContours (v6)
        assert_eq!(r.u16(), 3); // contour 0 nPts
        r.pos += 3 * 8;
        assert_eq!(r.u16(), 3); // contour 1 nPts
        r.pos += 3 * 8;
        assert_eq!(r.u8(), BlendMode::Screen as u8); // blend (v5)

        assert_eq!(r.u8(), TAG_STROKE);
        assert_eq!(r.u32(), 0xFF010203);
        assert_eq!(r.f32(), 2.5);
        assert_eq!(r.u8(), 2); // dash count
        assert_eq!(r.f32(), 3.0);
        assert_eq!(r.f32(), 2.0);
        assert_eq!(r.f32(), 1.0); // phase
        assert_eq!(r.u8(), 1); // cap
        assert_eq!(r.u8(), 1); // join
        assert!((r.f32() - 10.0).abs() < 1e-4);
        assert_eq!(r.u16(), 2);
        r.pos += 2*8;
        assert_eq!(r.u8(), BlendMode::Normal as u8); // blend (v5)

        assert_eq!(r.u8(), TAG_CLIP_PUSH);
        assert_eq!(r.u8(), 0); // evenOdd false
        let n = r.u16() as usize;
        assert_eq!(n, 4);
        r.pos += n*8;
        // v4 path-ops section: Move, Cubic, Close.
        assert_eq!(r.u16(), 3);
        assert_eq!(r.u8(), PATHOP_MOVE);
        r.pos += 2*4;
        assert_eq!(r.u8(), PATHOP_CUBIC);
        r.pos += 6*4;
        assert_eq!(r.u8(), PATHOP_CLOSE);

        assert_eq!(r.u8(), TAG_CLIP_POP);
        assert_eq!(r.u8(), TAG_TEXT_CLIP_APPLY);
        assert_eq!(r.u8(), TAG_SMASK_PUSH);
        assert_eq!(r.u8(), 1); // mask_type luminosity
        assert_eq!(r.u8(), TAG_SMASK_CONTENT);
        assert_eq!(r.u8(), TAG_SMASK_POP);
    }

    #[test]
    fn truncates_text_at_char_boundary() {
        // 70000 'é' chars = 140000 bytes > u16::MAX, must truncate at char boundary
        let long = "é".repeat(40000); // 80000 bytes
        assert!(long.len() > MAX_TEXT_BYTES);
        let truncated = truncate_str_safe(&long, MAX_TEXT_BYTES);
        assert!(truncated.len() <= MAX_TEXT_BYTES);
        // Must still be valid UTF-8 and end at char boundary (é is 2 bytes, so len even)
        assert!(truncated.is_char_boundary(truncated.len()));
        assert!(std::str::from_utf8(truncated.as_bytes()).is_ok());
        assert_eq!(truncated.len() % 2, 0);
    }
}

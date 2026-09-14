use crate::*;

/// Diagnostic for an image that was dropped or degraded, naming the reason.
///
/// Per lead's decision on P0-4 we deliberately do NOT substitute a placeholder for
/// a failed decode — a grey box is not the graphic either, and it would create a
/// fresh "renders things that shouldn't be there" bug. So a failure stays invisible
/// on the page, and this is the only way to find out why. The crate has no logging
/// dependency (and adding one would touch a shared Cargo.toml), and these fire at
/// most once per image, so a debug-build stderr line is the proportionate tool.
macro_rules! image_warn {
    ($($arg:tt)*) => {{
        if cfg!(debug_assertions) {
            eprintln!("[pdf_render/images] {}", format_args!($($arg)*));
        }
    }};
}

/// JPEG2000 (`JPXDecode`) decoding via the pure-Rust `openjp2` port of OpenJPEG.
pub(crate) mod jp2 {
    use openjp2::openjpeg::*;
    use std::ffi::c_void;

    /// How to interpret the codestream's colour components. §7.4.9 makes the PDF
    /// image dictionary's own `/ColorSpace`, when present, OVERRIDE the colour space
    /// recorded in the JPEG2000 data, so the caller can force one of these.
    #[derive(Copy, Clone, PartialEq)]
    pub enum Interp {
        Gray,
        Rgb,
        /// sYCC/eYCC. Not a PDF colour space: `opj_decode` leaves the components in
        /// YCC and the conversion to RGB below is mandatory, so a `/ColorSpace`
        /// override must not replace this — it would emit raw YCC as RGB.
        Ycc,
        Cmyk,
    }

    impl Interp {
        /// Number of COLOUR channels, i.e. the index at which an alpha channel would
        /// start when the codestream carries no `cdef` box to say where it is.
        fn ncolour(self) -> usize {
            match self {
                Interp::Gray => 1,
                Interp::Rgb | Interp::Ycc => 3,
                Interp::Cmyk => 4,
            }
        }
    }

    /// Choose the component interpretation. §7.4.9: the PDF's `/ColorSpace` overrides
    /// the codestream's, EXCEPT for sYCC/eYCC, which is a channel encoding rather than
    /// a PDF colour space — `opj_decode` returns YCC components and the conversion to
    /// RGB is mandatory, so a `/DeviceRGB` hint must not suppress it.
    pub fn resolve_interp(codestream: Option<Interp>, hint: Option<Interp>, ncomp: usize) -> Interp {
        if codestream == Some(Interp::Ycc) {
            return Interp::Ycc;
        }
        hint.or(codestream).unwrap_or(match ncomp {
            1 => Interp::Gray,
            4 => Interp::Cmyk,
            _ => Interp::Rgb,
        })
    }

    /// Resolve `/SMaskInData` (§7.4.9 Table 89) against the codestream's channel
    /// layout, returning `(alpha component index, un-premultiply)`.
    ///
    /// `cdef_alpha` is the channel a JP2 `cdef` box names as opacity, if any.
    /// Split out of `image_to_rgba` so the three cases are testable without a JPX
    /// codestream, which is where the original bug hid: alpha was applied whenever the
    /// channel count merely suggested one, so value 0 (and an ABSENT entry, whose
    /// default is 0) wrongly made the image transparent, and value 2's premultiplication
    /// was never undone.
    pub fn resolve_alpha(
        smask_in_data: u8,
        ncomp: usize,
        ncolour: usize,
        cdef_alpha: Option<usize>,
    ) -> (Option<usize>, bool) {
        if smask_in_data == 0 {
            return (None, false);
        }
        let idx = cdef_alpha
            .filter(|i| *i >= ncolour && *i < ncomp)
            .or_else(|| (ncomp > ncolour).then_some(ncolour));
        (idx, idx.is_some() && smask_in_data == 2)
    }

    /// Decode a JPX codestream honouring the PDF image dictionary (§7.4.9).
    ///
    /// `smask_in_data` is `/SMaskInData` (Table 89): 0 = ignore any alpha channel in
    /// the codestream, 1 = the alpha channel IS the soft mask, 2 = the same but the
    /// colour data is PREMULTIPLIED by it and must be un-premultiplied.
    /// `cs_hint` is the PDF's own `/ColorSpace`, which overrides the codestream's.
    #[derive(Copy, Clone)]
    pub struct JpxOpts {
        pub smask_in_data: u8,
        pub cs_hint: Option<Interp>,
    }

    impl Default for JpxOpts {
        /// The spec default for `/SMaskInData` is 0, and 0 means "ignore the alpha
        /// channel". A mask stream decoded through this module wants exactly that: it
        /// reads the colour channels and supplies its own meaning.
        fn default() -> Self {
            JpxOpts { smask_in_data: 0, cs_hint: None }
        }
    }

    struct Slice<'a> {
        off: usize,
        buf: &'a [u8],
    }
    impl<'a> Slice<'a> {
        fn seek(&mut self, n: usize) -> usize {
            self.off = self.buf.len().min(n);
            self.off
        }
        fn consume(&mut self, n: usize) -> usize {
            self.off = self.buf.len().min(self.off.saturating_add(n));
            self.off
        }
    }
    extern "C" fn free_fn(p: *mut c_void) {
        if p.is_null() {
            return;
        }
        drop(unsafe { Box::from_raw(p as *mut Slice) })
    }
    extern "C" fn read_fn(pb: *mut c_void, nb: usize, p: *mut c_void) -> usize {
        if pb.is_null() || p.is_null() || nb == 0 {
            return usize::MAX;
        }
        let s = unsafe { &mut *(p as *mut Slice) };
        let remaining = s.buf.len().saturating_sub(s.off);
        if remaining == 0 {
            return usize::MAX;
        }
        let n = remaining.min(nb);
        let out = unsafe { std::slice::from_raw_parts_mut(pb as *mut u8, n) };
        out.copy_from_slice(&s.buf[s.off..s.off + n]);
        s.off += n;
        n
    }
    extern "C" fn skip_fn(nb: i64, p: *mut c_void) -> i64 {
        if p.is_null() {
            return -1;
        }
        let s = unsafe { &mut *(p as *mut Slice) };
        s.consume(nb.max(0) as usize) as i64
    }
    extern "C" fn seek_fn(nb: i64, p: *mut c_void) -> i32 {
        if p.is_null() {
            return 0;
        }
        let s = unsafe { &mut *(p as *mut Slice) };
        let want = nb.max(0) as usize;
        if s.seek(want) == want {
            1
        } else {
            0
        }
    }

    /// Decode JP2/J2K bytes to `(width, height, RGBA8888)`, or `None`.
    ///
    /// Alpha is DISCARDED (`/SMaskInData` 0, the spec default). Callers that have the
    /// image dictionary should use [`decode_with_opts`].
    pub fn decode(bytes: &[u8]) -> Option<(u32, u32, Vec<u8>)> {
        decode_with_opts(bytes, JpxOpts::default())
    }

    /// Decode JP2/J2K bytes honouring `/SMaskInData` and a `/ColorSpace` override.
    pub fn decode_with_opts(bytes: &[u8], opts: JpxOpts) -> Option<(u32, u32, Vec<u8>)> {
        // JP2 signature box vs raw codestream.
        // `bytes.len() > 4` is not enough to slice 4..8: a 5..7-byte stream panicked
        // with "range end index 8 out of range", and a panic at the JNI boundary
        // takes the whole document down for one short image.
        let fmt = if bytes.len() >= 8 && &bytes[4..8] == b"jP  " {
            OPJ_CODEC_JP2
        } else {
            OPJ_CODEC_J2K
        };
        unsafe {
            decode_with(bytes, fmt, opts).or_else(|| decode_with(bytes, OPJ_CODEC_JP2, opts))
        }
    }

    unsafe fn decode_with(
        bytes: &[u8],
        fmt: OPJ_CODEC_FORMAT,
        opts: JpxOpts,
    ) -> Option<(u32, u32, Vec<u8>)> {
        let data = Box::new(Slice { off: 0, buf: bytes });
        let stream = opj_stream_default_create(1);
        if stream.is_null() {
            return None;
        }
        let p = Box::into_raw(data) as *mut c_void;
        opj_stream_set_read_function(stream, Some(read_fn));
        opj_stream_set_skip_function(stream, Some(skip_fn));
        opj_stream_set_seek_function(stream, Some(seek_fn));
        opj_stream_set_user_data_length(stream, bytes.len() as u64);
        opj_stream_set_user_data(stream, p, Some(free_fn));

        let codec = opj_create_decompress(fmt);
        if codec.is_null() {
            opj_stream_destroy(stream);
            return None;
        }
        let mut params = opj_dparameters_t::default();
        opj_set_default_decoder_parameters(&mut params);
        let mut out = None;
        if opj_setup_decoder(codec, &mut params) != 0 {
            let mut image = std::ptr::null_mut() as *mut opj_image_t;
            if opj_read_header(stream, codec, &mut image) != 0
                && opj_decode(codec, stream, image) != 0
                && opj_end_decompress(codec, stream) != 0
                && !image.is_null()
            {
                // Null-checked deref via as_ref() (image is non-null here).
                if let Some(img) = image.as_ref() {
                    out = image_to_rgba(img, opts);
                }
            }
            if !image.is_null() {
                opj_image_destroy(image);
            }
        }
        opj_destroy_codec(codec);
        opj_stream_destroy(stream);
        out
    }

    unsafe fn image_to_rgba(img: &opj_image_t, opts: JpxOpts) -> Option<(u32, u32, Vec<u8>)> {
        // x1-x0 / y1-y0 are unsigned: a malformed header with x1 < x0 underflows to a
        // huge value. Release builds have no overflow checks, so this wrapped silently.
        let w = (img.x1.checked_sub(img.x0)?) as usize;
        let h = (img.y1.checked_sub(img.y0)?) as usize;
        if w == 0 || h == 0 || w > 20000 || h > 20000 || img.numcomps == 0 {
            return None;
        }
        // A dimension-only cap still permits 20000x20000 = 1.6 GB. `opj_decode` has
        // already committed the component buffers by the time we get here, so refusing
        // spent the memory and rendered nothing; decimate the RGBA instead. `sample`
        // below already maps an output coordinate onto each component's own grid, so
        // sampling every `step`th pixel needs no other change.
        let step = super::decimation_step(w as u32, h as u32);
        let (dw, dh) = (w.div_ceil(step), h.div_ceil(step));
        if img.comps.is_null() {
            return None;
        }
        let comps = std::slice::from_raw_parts(img.comps, img.numcomps as usize);
        let n = img.numcomps as usize;
        // Sample a component's value at (x,y) scaled to 8-bit.
        let sample = |c: &opj_image_comp_t, x: usize, y: usize| -> u8 {
            let cw = c.w as usize;
            let ch = c.h as usize;
            if cw == 0 || ch == 0 || c.data.is_null() {
                return 0;
            }
            let sx = (x * cw / w).min(cw - 1);
            let sy = (y * ch / h).min(ch - 1);
            let mut v = *c.data.add(sy * cw + sx);
            // prec == 0 underflows `1 << (prec-1)`, and prec >= 40 makes the shift
            // below panic (or wrap in release). Clamp to the representable range.
            // The bias itself is a saturating add: a 32-bit signed component whose
            // value is already near i32::MAX overflows, which PANICS in a debug build
            // and takes the whole document down at the JNI boundary.
            let prec = (c.prec as i32).clamp(1, 31);
            if c.sgnd != 0 {
                v = v.saturating_add(1 << (prec - 1));
            }
            let v = if prec > 8 {
                v >> (prec - 8)
            } else if prec < 8 {
                v << (8 - prec)
            } else {
                v
            };
            v.clamp(0, 255) as u8
        };

        // Decide how to interpret components. §7.4.9: the PDF image dictionary's own
        // /ColorSpace, when present, OVERRIDES the colour space in the JPEG2000 data;
        // only when the PDF is silent do we consult the codestream, and only then fall
        // back to the channel count.
        let codestream_interp = match img.color_space {
            OPJ_CLRSPC_GRAY => Some(Interp::Gray),
            OPJ_CLRSPC_CMYK => Some(Interp::Cmyk),
            OPJ_CLRSPC_SYCC | OPJ_CLRSPC_EYCC => Some(Interp::Ycc),
            OPJ_CLRSPC_SRGB => Some(Interp::Rgb),
            _ => None,
        };
        let interp = resolve_interp(codestream_interp, opts.cs_hint, n);

        // A JP2 `cdef` box names the opacity channel explicitly and openjp2 surfaces
        // that as `comp.alpha != 0`; without one, a channel past the colour channels is
        // the conventional place for it.
        let cdef_alpha = comps.iter().position(|c| c.alpha != 0);
        let (alpha_comp, unpremultiply) =
            resolve_alpha(opts.smask_in_data, n, interp.ncolour(), cdef_alpha);

        let mut rgba = vec![0u8; dw * dh * 4];
        for dy in 0..dh {
            let y = dy * step;
            for dx in 0..dw {
                let x = dx * step;
                let idx = (dy * dw + dx) * 4;
                let (r, g, b) = match interp {
                    Interp::Gray => {
                        let v = sample(&comps[0], x, y);
                        (v, v, v)
                    }
                    Interp::Rgb => (
                        sample(&comps[0], x, y),
                        sample(comps.get(1).unwrap_or(&comps[0]), x, y),
                        sample(comps.get(2).unwrap_or(&comps[0]), x, y),
                    ),
                    Interp::Ycc if n >= 3 => {
                        let yy = sample(&comps[0], x, y) as f32;
                        let cb = sample(&comps[1], x, y) as f32 - 128.0;
                        let cr = sample(&comps[2], x, y) as f32 - 128.0;
                        let r = (yy + 1.402 * cr).round().clamp(0.0, 255.0) as u8;
                        let g = (yy - 0.344136 * cb - 0.714136 * cr).round().clamp(0.0, 255.0) as u8;
                        let b = (yy + 1.772 * cb).round().clamp(0.0, 255.0) as u8;
                        (r, g, b)
                    }
                    Interp::Ycc => {
                        let v = sample(&comps[0], x, y);
                        (v, v, v)
                    }
                    Interp::Cmyk if n >= 4 => {
                        let c = sample(&comps[0], x, y) as f32 / 255.0;
                        let m = sample(&comps[1], x, y) as f32 / 255.0;
                        let ye = sample(&comps[2], x, y) as f32 / 255.0;
                        let k = sample(&comps[3], x, y) as f32 / 255.0;
                        let r = ((1.0 - c) * (1.0 - k) * 255.0).round() as u8;
                        let g = ((1.0 - m) * (1.0 - k) * 255.0).round() as u8;
                        let b = ((1.0 - ye) * (1.0 - k) * 255.0).round() as u8;
                        (r, g, b)
                    }
                    Interp::Cmyk => {
                        let v = sample(&comps[0], x, y);
                        (v, v, v)
                    }
                };
                let a = match alpha_comp {
                    Some(ai) => sample(&comps[ai], x, y),
                    None => 255,
                };
                let (r, g, b) = if unpremultiply && a > 0 && a < 255 {
                    // §7.4.9 /SMaskInData 2: c' = c * a, so recover c = c' / a. Doing this
                    // in RGB after conversion is exact for the gray and sRGB codestreams
                    // that actually use premultiplied alpha; without it every soft edge
                    // keeps the black it was multiplied towards, which is the dark fringe.
                    let s = 255.0 / a as f32;
                    let un = |v: u8| (v as f32 * s).round().clamp(0.0, 255.0) as u8;
                    (un(r), un(g), un(b))
                } else {
                    (r, g, b)
                };
                rgba[idx] = r;
                rgba[idx + 1] = g;
                rgba[idx + 2] = b;
                rgba[idx + 3] = a;
            }
        }
        Some((dw as u32, dh as u32, rgba))
    }
}

pub(crate) struct ImageData {
    pub(crate) w: u32,
    pub(crate) h: u32,
    /// 0 = raw RGBA8888, 1 = JPEG bytes.
    pub(crate) format: u8,
    pub(crate) data: Vec<u8>,
}

/// Map of XObject resource name -> object id from a resources dictionary.
pub(crate) fn xobjects_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, ObjectId> {
    let mut out = HashMap::new();
    if let Some(Object::Dictionary(xo)) = res_dict.get(b"XObject").ok().and_then(|o| deref(doc, o)) {
        for (name, v) in xo.iter() {
            if let Ok(id) = v.as_reference() {
                out.insert(name.clone(), id);
            }
        }
    }
    out
}

/// Map of ExtGState resource name -> object id
pub(crate) fn extgstates_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, ObjectId> {
    let mut out = HashMap::new();
    if let Some(Object::Dictionary(eg)) = res_dict.get(b"ExtGState").ok().and_then(|o| deref(doc, o)) {
        for (name, v) in eg.iter() {
            if let Ok(id) = v.as_reference() {
                out.insert(name.clone(), id);
            } else if let Object::Dictionary(_) = v {
                // A DIRECT ExtGState dictionary has no object id, so it cannot go in this
                // name -> ObjectId map. It is not lost: the `gs` operator resolves the
                // named entry straight out of `/Resources /ExtGState` itself, which
                // handles the direct and indirect forms alike.
            }
        }
    }
    out
}

/// The document's default optional-content configuration (`/OCProperties /D`,
/// §8.11.4.3) resolved into set membership, so a query is O(1).
///
/// Built ONCE per caller. Reading membership out of the arrays instead costs
/// O(|/ON| + |/OFF|) per query and a `BDC` asks per marked-content section, so a
/// document whose N layers are each opened once — a CAD or map export — paid
/// O(N²): 6400 distinct groups measured 57.8 ms against 40.7 ms for 6400
/// sections sharing one group, where the call-site memo absorbs every repeat.
///
/// `/ON` and `/OFF` hold indirect references; a group that is a direct
/// dictionary has no id to match, so a group absent from both sets falls back to
/// `/BaseState` exactly as scanning the arrays for it did.
pub(crate) struct OcConfig {
    on: std::collections::HashSet<ObjectId>,
    off: std::collections::HashSet<ObjectId>,
    /// `/BaseState`, defaulting to ON (§8.11.4.3). Also the answer for a
    /// document with no usable `/OCProperties /D`, where everything is visible.
    base_on: bool,
}

include!("images_part1.rs");
include!("images_part2.rs");
include!("images_part3.rs");
include!("images_part4.rs");
include!("images_part5.rs");
include!("images_part6.rs");
include!("images_part7.rs");
include!("images_part8.rs");
include!("images_part9.rs");
include!("images_part10.rs");
include!("images_part11.rs");
include!("images_part12.rs");
include!("images_part13.rs");
include!("images_part14.rs");
include!("images_part15.rs");
include!("images_part16.rs");
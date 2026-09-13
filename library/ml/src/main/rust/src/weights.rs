//! The `.maml` reader.
//!
//! `.maml` is a **weights container only** — ordered tensors, no operators and no
//! topology — because each network's forward pass is hardcoded in [`crate::nets`]
//! rather than interpreted. Format version 2 appends an optional *graph section*
//! describing that forward pass symbolically (see [`Graph`]); version 1 files have
//! none, and behave exactly as before. See `scripts/ml/maml_convert.py`, which writes
//! it, for the byte layout; this is the other half of that contract.
//!
//! Two consequences worth stating plainly:
//!
//! * Tensor **order** is the whole interface. A reordering loads cleanly and infers
//!   nonsense, which is why the converter pins a SHA-256 over the ordered layer
//!   table and why [`Weights::graph_id`] is checked before a net will touch a file.
//! * Nothing here allocates or copies the tensor data. The whole file is uploaded to
//!   one `VkBuffer` verbatim, so a [`Tensor`]'s offset is simultaneously its offset
//!   in the file and its offset in device memory. That is the reason the format is
//!   one contiguous blob.

use std::fs::File;

use crate::preprocess::f16_to_f32;

/// `b"MAML"`, little-endian, at offset 0.
const MAGIC: [u8; 4] = *b"MAML";
/// Bumped when the layout below changes incompatibly.
///
/// Version 1 is header + table + blob. Version 2 appends a graph section after the blob
/// (see [`Graph`]); a v1 reader rejects a v2 file here, loudly, rather than running a
/// topology it cannot see.
const FORMAT_VERSION: u32 = 1;
/// The newest version this reader accepts. Kept distinct from [`FORMAT_VERSION`] — the
/// version *written* by the converter — so emitting v2 files is a separate, deliberate
/// step from reading them.
const FORMAT_READ_VERSION: u32 = 2;
const HEADER_BYTES: usize = 64;
const TENSOR_ENTRY_BYTES: usize = 32;
/// fp16 tensor encoding. `pub(crate)` for the fusion tests' hand-made header; the
/// format's dtypes are otherwise an internal detail of the parser.
pub(crate) const DTYPE_F16: u32 = 0;

/// Signed 8-bit, with a **separate** scale tensor beside it.
///
/// Used only where the weights dominate the download and fp16 would double it: SMaLL-100 is
/// 330 million parameters, which is 660 MB at fp16 and 330 MB here.
///
/// The quantisation is symmetric — zero point 0 — and the scale is per output channel, both read
/// from the export rather than assumed. So a value is `int8 * scale`, and because the scale
/// applies to a whole output row it multiplies the *accumulator* once rather than every tap. The
/// scale lives in its own fp16 tensor because a table entry is already 32 bytes with no room
/// for it, and a companion tensor needs no format version bump.
///
/// Activations stay fp16. The export quantises them too, dynamically, per tensor; not doing
/// that is both simpler and strictly more accurate, and it saves nothing to copy since the
/// weights are what take the space.
const DTYPE_I8: u32 = 1;

/// Signed 4-bit, two per byte, with a **per-block** scale tensor beside it.
///
/// Where [`DTYPE_I8`] carries one scale per output row, this carries one per block of
/// [`I4_BLOCK`] taps along the contraction axis, so the scale tensor is rank 2:
/// `(out_channels, ceil(taps / I4_BLOCK))`. Four bits cannot represent a row's dynamic range
/// well enough for a single scale; a block of 32 can.
///
/// Nibbles are packed **two per byte along the contraction axis**, low nibble first, so a block
/// is contiguous and the inner loop reads it without striding. A tensor's byte length is
/// therefore `len.div_ceil(2)` and not `len * stride` - the only dtype whose stride is
/// fractional, which is why [`Dtype::bytes`] exists rather than a stride constant.
const DTYPE_I4: u32 = 2;

/// Taps one [`DTYPE_I4`] scale covers.
///
/// 32 rather than 64: at 4.5 bits per weight against 4.25 the difference is 3% of a download,
/// and `MIN_INT8_COSINE` is a strict gate that the wider block is more likely to miss.
pub const I4_BLOCK: u32 = 32;

/// How a tensor's payload is encoded.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Dtype {
    /// Half-precision float, two bytes an element.
    F16,
    /// Signed 8-bit with a per-output-channel scale. See [`DTYPE_I8`].
    I8,
    /// Signed 4-bit with a per-block scale. See [`DTYPE_I4`].
    I4,
}

impl Dtype {
    /// Bytes `len` elements of this dtype occupy.
    ///
    /// Not a stride: four-bit elements are half a byte each, so an odd length rounds up and the
    /// final nibble is padding.
    pub const fn bytes(self, len: u64) -> u64 {
        match self {
            Dtype::F16 => len * 2,
            Dtype::I8 => len,
            Dtype::I4 => len.div_ceil(2),
        }
    }

    /// Whether the payload is integer, and so read through the 32-bit word view with a scale.
    pub const fn is_quantised(self) -> bool {
        !matches!(self, Dtype::F16)
    }
}
/// Every tensor starts on this boundary, so an offset is a valid fp16 index too.
const ALIGNMENT: u32 = 16;

/// Graph ids, shared with `GRAPHS` in `scripts/ml/maml_convert.py`.
pub mod graph {
    /// MediaPipe Selfie Segmentation, 256x256.
    pub const SELFIE: u32 = 1;
    /// U^2-Net portable, 320x320.
    pub const U2NETP: u32 = 2;
    /// SCRFD 500M face detection, 640 on the long side.
    pub const SCRFD: u32 = 3;
    /// MobileFaceNet face embedding, 112x112 in, 512-d out.
    pub const MOBILEFACENET: u32 = 4;
    /// PP-OCRv5 mobile text detection, 960 on the long side.
    pub const PPOCR_DET: u32 = 5;
    /// PP-OCRv5 mobile text recognition, 48 tall.
    pub const PPOCR_REC: u32 = 6;
    // 7..10 were Piper's four VITS graphs, deleted when Supertonic replaced it. The numbers are
    // **not** reused: an id identifies a forward pass, and a `.maml` built for the old vocoder
    // must be rejected rather than loaded as whatever took its slot.
    /// Supertonic 3's ConvNeXt vocoder. See [`crate::nets::supertonic_vocoder`].
    pub const SUPERTONIC_VOC: u32 = 11;
    /// Supertonic 3's duration predictor. See [`crate::nets::supertonic_duration`].
    pub const SUPERTONIC_DP: u32 = 12;
    /// Supertonic 3's text encoder. See [`crate::nets::supertonic_text`].
    pub const SUPERTONIC_TTL: u32 = 13;
    /// Supertonic 3's flow-matching sampler. See [`crate::nets::supertonic_sampler`].
    pub const SUPERTONIC_VE: u32 = 14;
    // 15 was SMaLL-100's graph, deleted when NLLB replaced it. The number is **not** reused: an
    // id identifies a forward pass, and a `.maml` built for the old net must be rejected rather
    // than loaded as whatever took its slot.
    /// TinyCLIP-ViT-8M/16 Text-3M, both towers in one file. See [`crate::nets::tinyclip`].
    ///
    /// One graph rather than two because the towers share a file and a [`super::Weights`] upload,
    /// even though they share no weights: `Mode::Image` and `Mode::Text` are two plans over one net.
    pub const TINYCLIP: u32 = 16;
    /// whisper-base speech recognition, encoder and decoder in one file. See
    /// [`crate::nets::whisper`].
    ///
    /// One graph rather than two because the 51,865-row embedding is **tied**: it is the decoder's
    /// input table and the logits kernel, so two files would upload 26.6 MB of it twice.
    pub const WHISPER: u32 = 17;
    /// NLLB-200-distilled-600M translation, encoder and decoder in one file. See
    /// [`crate::nets::nllb`].
    ///
    /// One graph rather than two because the 256,206-row embedding is **tied**: it is the
    /// encoder's input table, the decoder's input table and the logits kernel, and two files would
    /// upload ~250 MiB of it twice.
    ///
    /// Agreed three-ways with model-eng and app-eng (team `nllb-translate`): the next free id
    /// after whisper's 17, with 7..10 staying retired. `maml_convert.py` has `GRAPHS["nllb600"]`
    /// at the same number.
    pub const NLLB: u32 = 18;
    /// Maia3-5M human-move prediction, one forward pass per move. See [`crate::nets::maia`].
    ///
    /// The next free id after NLLB's 18, with 7..10 and 15 staying retired.
    /// `maml_convert.py` has `GRAPHS["maia"]` at the same number.
    pub const MAIA: u32 = 19;

    /// Gemma 4 E2B instruction-tuned, the text decoder. See [`crate::nets::gemma4`].
    ///
    /// The next free id after Maia's 19, with 7..10 and 15 staying retired. **Not 19**: the port
    /// plan originally claimed that number, which Maia already holds - a collision would make a
    /// `.maml` parse as the wrong net rather than be refused.
    /// `maml_convert.py` has `GRAPHS["gemma4_text"]` at the same number.
    pub const GEMMA4_TEXT: u32 = 20;

    /// Gemma 4's two embedding tables, gathered on the host. See [`crate::nets::gemma4`].
    ///
    /// Its own file rather than tensors in [`GEMMA4_TEXT`] because nothing on the device reads
    /// it: a decode step needs one row of each table, and binding 4.7 GB to gather 1536 values
    /// would be absurd. Splitting it also lets the text model load while the embedding streams.
    pub const GEMMA4_EMBED: u32 = 21;

    /// Gemma 4's vision tower. See [`crate::nets::gemma4_vision`].
    ///
    /// Its own file because it is optional: a device that never sends an image never downloads
    /// 99 MB of encoder.
    pub const GEMMA4_VISION: u32 = 22;

    /// The Now Playing audio fingerprinter, 415 ms of log-mel in and a 64-d embedding out. See
    /// [`crate::nets::nnfp`].
    ///
    /// The next free id after Gemma 4's vision tower at 22, with 7..10 and 15 staying retired.
    /// `maml_convert.py` has `GRAPHS["nnfp"]` at the same number.
    ///
    /// Ported from a **TFLite** flatbuffer rather than an ONNX export or a PyTorch checkpoint,
    /// which is a third input kind for the converter; see `nnfp_layers` there.
    ///
    /// The music/not-music gate that runs in front of this on the DSP is a separate 8.2K-
    /// parameter network. It **is** ported, as [`crate::gate`], but it holds no id here and
    /// never will: it runs on the CPU with its 9,772 bytes of weights embedded by
    /// `include_bytes!`, so there is no `.maml` for an id to identify. An id names a forward
    /// pass the Vulkan runtime executes, and the gate is not one.
    ///
    /// So 23 has no successor reserved. 24 went to Gemma 4's audio encoder, which is the next
    /// thing that actually needed a graph.
    pub const NNFP: u32 = 23;

    /// Gemma 4's audio tower. See [`crate::nets::gemma4_audio`].
    ///
    /// The next free id after the fingerprinter at 23, with 7..10 and 15 staying retired.
    /// `maml_convert.py` has `GRAPHS["gemma4_audio"]` at the same number.
    ///
    /// Its own file for the reason the vision tower has one, and more so: it is 165 MB at int4,
    /// larger than the vision tower's 98 MB, and a device that never sends audio should not
    /// download it.
    pub const GEMMA4_AUDIO: u32 = 24;
}

/// One tensor's entry in the table: where it is and what shape it is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Tensor {
    /// 1..=4 significant entries of [`Tensor::dims`].
    pub rank: u32,
    /// Trailing entries beyond `rank` are zero.
    pub dims: [u32; 4],
    /// Byte offset from the start of the data section.
    pub offset: u32,
    /// Elements, not bytes.
    pub len: u32,
    /// How the payload is encoded, which decides how many bytes [`Tensor::len`] occupies.
    pub dtype: Dtype,
}

impl Tensor {
    /// The offset as an fp16 **element** index, which is how the shaders address it.
    pub fn elem_offset(&self) -> u32 {
        self.offset / 2
    }

    /// The offset as a 32-bit **word** index, which is how an int8 tensor is addressed.
    ///
    /// Int8 weights are read through a `uint` view of the same buffer and unpacked four at a
    /// time, because a byte view would need `VK_KHR_8bit_storage` — an extension on top of the
    /// fp16 one this runtime already requires, and every extra requirement narrows the fleet.
    /// `ALIGNMENT` is 16, so a tensor always starts on a word boundary.
    pub fn word_offset(&self) -> u32 {
        self.offset / 4
    }
}

/// A `.maml`'s data section, readable a piece at a time.
///
/// [`crate::vulkan::run::Net::new`] needs the data section and nothing else: the [`Plan`] it is
/// handed already carries every resolved offset. It does *not* need the section in one host
/// allocation, and for a bundled Supertonic it must not — the current path allocates the model
/// three times over, as a Java `byte[]`, as the `Vec<u8>` JNI hands Rust, and as [`Weights`]'s own
/// copy. At the ~105 MB an int8 bundle comes to, that is ~300 MB of transient heap and an
/// out-of-memory kill on a low-RAM device.
///
/// So the upload asks for ranges instead, and two implementations answer: [`Weights`], which has
/// the bytes already, and [`Streamed`], which leaves them in the APK and reads them positionally.
/// Neither is faster than the other in device time — both end up doing the same
/// `cmd_copy_buffer`s — and the second has a peak host cost of one chunk.
///
/// [`Plan`]: crate::nets::Plan
pub trait Blob {
    /// Bytes in the data section.
    fn data_len(&self) -> u64;

    /// The tensor table, so `vulkan::segment` can find the extent of every tensor an op reads.
    ///
    /// Nothing about *running* a net needs the table - a [`Plan`] carries every resolved offset,
    /// which is what makes [`Weights`] and [`Streamed`] interchangeable. Segmenting the weights
    /// buffer does: it has to know where each tensor ends to choose a boundary that does not fall
    /// inside one, and only the table says that.
    fn tensors(&self) -> &[Tensor];

    /// Fill `into` from `offset` bytes into the data section.
    ///
    /// A short read is an error rather than a partial fill: every caller here knows exactly how
    /// many bytes it wants, and silently uploading a half-read chunk would leave one tensor of a
    /// net holding whatever the buffer was allocated with.
    fn read_at(&self, offset: u64, into: &mut [u8]) -> Result<(), String>;
}

/// A `.maml` header and tensor table, without the data section.
///
/// Parsed by [`parse_header`] from the first `HEADER_BYTES + count * 32` bytes of a file, which is
/// a few kilobytes for even the largest net here. Both [`Weights::parse`] and [`Streamed::open`]
/// go through it, so there is one implementation of the format's bounds checks rather than two
/// that have to agree.
struct Header {
    graph_id: u32,
    source_sha256: [u8; 32],
    tensors: Vec<Tensor>,
    data_offset: usize,
    data_len: usize,
    /// Byte offset of the version-2 graph section, or `None` on a version-1 file.
    ///
    /// Read from the header's reserved bytes 56..64 (offset then length). Zero length means
    /// absent even on a v2 file — the section is optional per file, not per version.
    graph: Option<(usize, usize)>,
}

/// Parse the header and tensor table out of `bytes`, which must reach at least the table's end.
///
/// Every offset and length in the table is bounds-checked here rather than at use, so a truncated
/// or hand-edited asset fails at load with a message instead of dispatching a shader that reads
/// past the end of a device buffer — where the symptom would be a driver reset, not an error.
///
/// The one thing this does *not* check is that the file ends where the data section does: a
/// [`Streamed`] read only has the prefix in hand, and an asset inside an APK is followed by the
/// next asset. [`Weights::parse`] checks it separately, because there the whole file is the slice
/// and a length mismatch means a truncated download.
fn parse_header(bytes: &[u8], expect_graph: u32) -> Result<Header, String> {
    if bytes.len() < HEADER_BYTES {
        return Err(format!("{} bytes is shorter than a .maml header", bytes.len()));
    }
    if bytes[0..4] != MAGIC {
        return Err("not a .maml file (bad magic)".into());
    }
    let version = u32(bytes, 4);
    if version == 0 || version > FORMAT_READ_VERSION {
        return Err(format!("format version {version}, expected 1 or {FORMAT_READ_VERSION}"));
    }
    let graph_id = u32(bytes, 8);
    if graph_id != expect_graph {
        return Err(format!(
            "this file is for graph {graph_id}, but graph {expect_graph} asked for it"
        ));
    }
    let count = u32(bytes, 12) as usize;
    let mut source_sha256 = [0u8; 32];
    source_sha256.copy_from_slice(&bytes[16..48]);
    let data_offset = u32(bytes, 48) as usize;
    let data_len = u32(bytes, 52) as usize;
    // Version-2 graph section: byte offset and length in the reserved tail. Zero length
    // is absent, which is how a v2 file without an emitted graph reads — and how every
    // v1 file reads, since those bytes are zero there.
    let graph_at = u32(bytes, 56) as usize;
    let graph_len = u32(bytes, 60) as usize;
    let graph = if graph_len == 0 {
        None
    } else if version < 2 {
        return Err("a version-1 file naming a graph section it cannot hold".into());
    } else {
        Some((graph_at, graph_len))
    };

    let table_bytes = count
        .checked_mul(TENSOR_ENTRY_BYTES)
        .ok_or_else(|| format!("{count} tensors overflows the table size"))?;
    if data_offset != HEADER_BYTES + table_bytes {
        return Err(format!(
            "data starts at {data_offset}, but {count} tensors put it at {}",
            HEADER_BYTES + table_bytes
        ));
    }
    data_offset
        .checked_add(data_len)
        .ok_or_else(|| "data section overflows".to_string())?;
    if bytes.len() < data_offset {
        return Err(format!(
            "{} bytes does not reach the end of a {count}-tensor table at {data_offset}",
            bytes.len()
        ));
    }

    let mut tensors = Vec::with_capacity(count);
    for i in 0..count {
        let at = HEADER_BYTES + i * TENSOR_ENTRY_BYTES;
        let rank = u32(bytes, at);
        if rank == 0 || rank > 4 {
            return Err(format!("tensor {i} has rank {rank}"));
        }
        let dims =
            [u32(bytes, at + 4), u32(bytes, at + 8), u32(bytes, at + 12), u32(bytes, at + 16)];
        let dtype = u32(bytes, at + 20);
        let dtype = match dtype {
            DTYPE_F16 => Dtype::F16,
            DTYPE_I8 => Dtype::I8,
            DTYPE_I4 => Dtype::I4,
            other => {
                return Err(format!("tensor {i} has dtype {other}, expected fp16, int8 or int4"))
            }
        };
        let offset = u32(bytes, at + 24);
        let len = u32(bytes, at + 28);

        let expected: u64 = dims[..rank as usize].iter().map(|&d| d as u64).product();
        if expected != len as u64 {
            return Err(format!("tensor {i} has dims {dims:?} but len {len}"));
        }
        if !offset.is_multiple_of(ALIGNMENT) {
            return Err(format!("tensor {i} is at {offset}, not {ALIGNMENT}-aligned"));
        }
        let end = (offset as u64) + dtype.bytes(len as u64);
        if end > data_len as u64 {
            return Err(format!(
                "tensor {i} spans {offset}..{end} of a {data_len}-byte data section"
            ));
        }
        tensors.push(Tensor { rank, dims, offset, len, dtype });
    }
    // The graph section, when named, must sit exactly where the blob ends and end where
    // the lengths say. A section overlapping the blob would alias weights as topology;
    // one running past the declared end is truncated.
    if let Some((at, len)) = graph {
        let blob_end =
            data_offset.checked_add(data_len).ok_or_else(|| "data section overflows".to_string())?;
        if at != blob_end {
            return Err(format!(
                "graph section at {at}, but the data section ends at {blob_end}"
            ));
        }
        at.checked_add(len).ok_or_else(|| "graph section overflows".to_string())?;
    }
    Ok(Header { graph_id, source_sha256, tensors, data_offset, data_len, graph })
}

/// The tensor table on its own, without the data section.
///
/// [`crate::vulkan::run::Net::rebuild`] re-records a net at a new shape, and that means building a
/// fresh [`crate::nets::Plan`] — which needs a [`crate::nets::WeightSource`]. It does not need the
/// blob: a builder only ever consults offsets and shapes, and the blob is already in device memory
/// by then. Holding a whole [`Weights`] alive to rebuild from would keep 127 MB of host RSS for the
/// Supertonic sampler alone, beside the copy the device has, where the table is a few kilobytes.
///
/// So a caller that means to rebuild takes one of these first and lets the `Weights` go.
#[derive(Clone, Debug)]
pub struct Offsets {
    tensors: Vec<Tensor>,
}

impl Offsets {
    /// Tensor `index`, or an error naming the index — which is what a mismatch
    /// between the Rust forward pass and the converter's ordering looks like.
    pub fn tensor(&self, index: usize) -> Result<Tensor, String> {
        self.tensors
            .get(index)
            .copied()
            .ok_or_else(|| format!("tensor {index} of {}: out of range", self.tensors.len()))
    }

    /// Tensor `index`, checked against the shape the caller expects.
    ///
    /// The net modules use this for every weight, so a table that is the right
    /// length but the wrong order fails on the first layer whose shape differs
    /// rather than silently convolving with someone else's kernel.
    pub fn shaped(&self, index: usize, dims: &[u32]) -> Result<Tensor, String> {
        let tensor = self.tensor(index)?;
        let got = &tensor.dims[..tensor.rank as usize];
        if got != dims {
            return Err(format!("tensor {index} is {got:?}, the forward pass wants {dims:?}"));
        }
        Ok(tensor)
    }

    /// How many tensors the table holds.
    pub fn len(&self) -> usize {
        self.tensors.len()
    }

    /// An empty table, for callers that resolve nothing through it.
    ///
    /// [`crate::nets::Builder::finish`] records without a file table — it resolves
    /// offsets, never indices — so it passes this rather than threading an `Option`
    /// through the recording. Anything that actually looks a tensor up gets an
    /// out-of-range error, which is the honest answer for an empty table.
    pub fn empty() -> Offsets {
        Offsets { tensors: Vec::new() }
    }

    /// Whether the table is empty. Only ever true for a hand-made file.
    pub fn is_empty(&self) -> bool {
        self.tensors.is_empty()
    }

    /// A table over hand-made tensors, for the graph-section round-trip tests.
    ///
    /// Test-only: the emitter inverts offsets through a real table, and the `Shapes`
    /// stub is not one. The caller owns the shapes; this checks nothing.
    #[cfg(test)]
    pub(crate) fn from_test(tensors: Vec<Tensor>) -> Offsets {
        Offsets { tensors }
    }
}

/// A parsed `.maml` file: the tensor table, and the data section to upload.
#[derive(Debug)]
pub struct Weights {
    /// Which network this file is for. See [`graph`].
    pub graph_id: u32,
    /// SHA-256 of the ONNX it was converted from, for tracing a shipped asset.
    pub source_sha256: [u8; 32],
    table: Offsets,
    data: Vec<u8>,
    /// The version-2 graph section, when the file names one. See [`Graph`].
    graph: Option<Graph>,
}

impl Weights {
    /// Parse `bytes`, rejecting anything not built for `expect_graph`.
    ///
    /// The header and table go through `parse_header`, which does every bounds check; the one
    /// thing added here is that the file must end exactly where its last section does — the
    /// data section on a v1 file, the graph section on a v2 file that names one — since with
    /// the whole slice in hand a mismatch means a truncated or padded download.
    pub fn parse(bytes: &[u8], expect_graph: u32) -> Result<Weights, String> {
        let header = parse_header(bytes, expect_graph)?;
        let data_end = header.data_offset + header.data_len;
        let file_end = match header.graph {
            Some((at, len)) => at + len,
            None => data_end,
        };
        if file_end != bytes.len() {
            return Err(format!(
                "sections end at {file_end} but the file is {} bytes",
                bytes.len()
            ));
        }
        let data = bytes.get(header.data_offset..data_end).unwrap_or(&[]).to_vec();
        let graph = match header.graph {
            Some((at, len)) => {
                let section = bytes
                    .get(at..at + len)
                    .ok_or_else(|| format!("graph section {at}..{} past end", at + len))?;
                Some(Graph::parse(section, &header.tensors)?)
            }
            None => None,
        };
        Ok(Weights {
            graph_id: header.graph_id,
            source_sha256: header.source_sha256,
            table: Offsets { tensors: header.tensors },
            data,
            graph,
        })
    }

    /// The fp16 blob to upload, verbatim.
    pub fn data(&self) -> &[u8] {
        &self.data
    }

    /// A [`Weights`] that is nothing but a data section, for the device-parity fixtures.
    ///
    /// [`crate::vulkan::run::Net::new`] reads no part of a [`Weights`] except [`data`] to *record*
    /// the plan, because the [`crate::nets::Plan`] it is handed already carries every resolved
    /// offset. It does read the table to **validate** each op's weight range against it, though —
    /// see `segment::tensor_end` — so the table cannot be empty or every op is rejected as reading
    /// outside all zero of the tensors the file describes.
    ///
    /// One tensor spanning the whole blob is what a fixture can honestly assert: its plan was built
    /// against a [`WeightSource`] whose own offsets are already consistent, so the property left to
    /// check on the device is that nothing reads off the end of the data. Assembling a faithful
    /// per-tensor table here would duplicate the fixture's source and check the fixture rather than
    /// the shader.
    ///
    /// [`data`]: Weights::data
    #[cfg(test)]
    pub(crate) fn from_data(data: Vec<u8>) -> Weights {
        let table = Offsets {
            tensors: vec![Tensor {
                rank: 1,
                dims: [(data.len() / 2) as u32, 0, 0, 0],
                offset: 0,
                len: (data.len() / 2) as u32,
                dtype: Dtype::F16,
            }],
        };
        Weights { graph_id: 0, source_sha256: [0u8; 32], table, data, graph: None }
    }

    /// The tensor table alone, for a caller that will rebuild a plan after this `Weights` is
    /// gone. See [`Offsets`].
    pub fn offsets(&self) -> Offsets {
        self.table.clone()
    }

    /// This file's table and bytes, for the tensors the host reads itself. See [`Reader`].
    pub fn reader(&self) -> Reader<'_> {
        Reader::new(&self.table, self)
    }

    /// How many tensors the table holds.
    pub fn len(&self) -> usize {
        self.table.len()
    }

    /// Whether the table is empty. Only ever true for a hand-made file.
    pub fn is_empty(&self) -> bool {
        self.table.is_empty()
    }

    /// Tensor `index`, or an error naming the index — which is what a mismatch
    /// between the Rust forward pass and the converter's ordering looks like.
    pub fn tensor(&self, index: usize) -> Result<Tensor, String> {
        self.table.tensor(index)
    }

    /// Tensor `index`, checked against the shape the caller expects.
    ///
    /// The net modules use this for every weight, so a table that is the right
    /// length but the wrong order fails on the first layer whose shape differs
    /// rather than silently convolving with someone else's kernel.
    pub fn shaped(&self, index: usize, dims: &[u32]) -> Result<Tensor, String> {
        self.table.shaped(index, dims)
    }

    /// The version-2 graph section, when the file carries one.
    ///
    /// `None` on every version-1 file and on version-2 files converted before the
    /// converter emitted graphs. Callers that need a topology keep their hand-written
    /// forward pass for those; the section is authoritative only when present *and*
    /// parsed, never a silent fallback.
    pub fn graph_section(&self) -> Option<&Graph> {
        self.graph.as_ref()
    }
}

impl Blob for Weights {
    fn data_len(&self) -> u64 {
        self.data.len() as u64
    }

    fn tensors(&self) -> &[Tensor] {
        &self.table.tensors
    }

    fn read_at(&self, offset: u64, into: &mut [u8]) -> Result<(), String> {
        let start = usize::try_from(offset).map_err(|_| "a data offset overflowed usize")?;
        let end = start
            .checked_add(into.len())
            .ok_or("a data range overflowed")?;
        let from = self
            .data
            .get(start..end)
            .ok_or_else(|| format!("{start}..{end} of a {}-byte data section", self.data.len()))?;
        into.copy_from_slice(from);
        Ok(())
    }
}

/// A `.maml` whose table is in memory and whose data section is still in a file.
///
/// The counterpart of [`Weights`], and the whole of what bundling Supertonic needs: its table is a
/// few kilobytes, and its data section is the ~105 MB that must not be resident three times over.
/// See [`Blob`] for why that mattered enough to add a second implementation.
///
/// # Not `mmap`
///
/// `memmap2` was deliberately removed from this repo, and nothing here needs it back. A mapping
/// would let the upload read the data as a slice, but the upload does not want a slice: it wants
/// to hand fixed-size pieces to a staging buffer, and a positional read does that with no
/// unsafety, no dependency, and no page-fault behaviour to reason about on an unknown filesystem.
///
/// # The offset, and why it is not always zero
///
/// An asset inside an APK is a *range* of the APK, so `AssetManager.openFd` returns a descriptor
/// alongside a `startOffset` and a `length` rather than a file of its own. This holds that range
/// and adds it to every read, so a bundled asset and a downloaded file are one code path. It also
/// means `openFd` must succeed, which is what `noCompress += "maml"` in the app's Gradle
/// configuration is load-bearing for: `openFd` throws for a deflated asset.
pub struct Streamed {
    /// Which network this file is for. See [`graph`].
    pub graph_id: u32,
    /// SHA-256 of the ONNX it was converted from, for tracing a shipped asset.
    pub source_sha256: [u8; 32],
    table: Offsets,
    file: File,
    /// Byte offset of the **data section** within `file`, i.e. the asset's own start plus the
    /// header and table. Every [`Blob::read_at`] adds it, so callers index the data section.
    data_at: u64,
    data_len: u64,
}

impl Streamed {
    /// Read the header and table of the `.maml` occupying `at..at + len` of `file`.
    ///
    /// Only the prefix is read — the header plus the tensor table, a few kilobytes — so this costs
    /// nothing whatever the size of the net. `file` is retained; the data section is read later, by
    /// the upload.
    ///
    /// `len` is what the caller was told the asset is, and is checked against the header rather
    /// than trusted: a `.maml` claiming a data section past the end of its own range would
    /// otherwise be caught only by the first read that ran off the end, or not at all if a
    /// neighbouring asset happened to follow it.
    pub fn open(file: File, at: u64, len: u64, expect_graph: u32) -> Result<Streamed, String> {
        // A generous prefix read: one page covers the header and a 128-tensor table, and the
        // largest net here has 605. Reading `min(len, PREFIX)` rather than exactly the table means
        // one syscall instead of two, since the table's size is in the header being read.
        const PREFIX: u64 = 64 * 1024;
        let want = usize::try_from(len.min(PREFIX)).map_err(|_| "a .maml prefix overflowed")?;
        let mut prefix = vec![0u8; want];
        read_exact_at(&file, &mut prefix, at)
            .map_err(|e| format!("reading a .maml header at {at}: {e}"))?;
        let header = parse_header(&prefix, expect_graph)?;
        let data_at = header.data_offset as u64;
        let end = data_at
            .checked_add(header.data_len as u64)
            .ok_or("data section overflows")?;
        if end > len {
            return Err(format!(
                "the data section ends at {end} but this .maml is only {len} bytes"
            ));
        }
        Ok(Streamed {
            graph_id: header.graph_id,
            source_sha256: header.source_sha256,
            table: Offsets { tensors: header.tensors },
            file,
            data_at: at + data_at,
            data_len: header.data_len as u64,
        })
    }

    /// The tensor table alone, for building and rebuilding plans. See [`Offsets`].
    pub fn offsets(&self) -> Offsets {
        self.table.clone()
    }

    /// This file's table and bytes, for the tensors the host reads itself. See [`Reader`].
    ///
    /// Each read is a positional read of a few kilobytes out of the APK, which is what makes this
    /// affordable: the sampler's host block is 0.44% of its 127 MB.
    pub fn reader(&self) -> Reader<'_> {
        Reader::new(&self.table, self)
    }

    /// How many tensors the table holds.
    pub fn len(&self) -> usize {
        self.table.len()
    }

    /// Whether the table is empty. Only ever true for a hand-made file.
    pub fn is_empty(&self) -> bool {
        self.table.is_empty()
    }
}

impl Blob for Streamed {
    fn data_len(&self) -> u64 {
        self.data_len
    }

    fn tensors(&self) -> &[Tensor] {
        &self.table.tensors
    }

    fn read_at(&self, offset: u64, into: &mut [u8]) -> Result<(), String> {
        let end = offset
            .checked_add(into.len() as u64)
            .ok_or("a data range overflowed")?;
        if end > self.data_len {
            return Err(format!(
                "{offset}..{end} of a {}-byte data section",
                self.data_len
            ));
        }
        read_exact_at(&self.file, into, self.data_at + offset)
            .map_err(|e| format!("reading {} bytes at {offset}: {e}", into.len()))
    }
}

/// A tensor table and the bytes it indexes, for the reads the host does itself.
///
/// A handful of Supertonic's tensors never reach a shader: the sampler's timestep MLP depends on
/// the step number, its rotary `theta` on the sequence length, and its folded style keys are
/// inputs to other nets. `post::supertonic` reads those on the host, and needed a [`Weights`] to
/// do it — which is exactly the 105 MB allocation the bundled path exists to avoid.
///
/// So it takes one of these instead. Both halves come from the same file either way; keeping them
/// as one argument rather than two means a caller cannot pair one net's table with another's bytes.
#[derive(Clone, Copy)]
pub struct Reader<'a> {
    table: &'a Offsets,
    data: &'a dyn Blob,
}

impl<'a> Reader<'a> {
    /// A reader over `table` and `data`, which must describe the same file.
    pub fn new(table: &'a Offsets, data: &'a dyn Blob) -> Reader<'a> {
        Reader { table, data }
    }

    /// Tensor `index` as `f32`, in the file's order, checked against `dims`.
    ///
    /// fp16 only: every tensor the host reads is fp16, and an int8 one would need its companion
    /// scale, which is a caller's decision rather than something to guess at here.
    pub fn fp16(&self, index: usize, dims: &[u32]) -> Result<Vec<f32>, String> {
        let found = self.table.shaped(index, dims)?;
        if found.dtype.is_quantised() {
            return Err(format!("tensor {index} is {:?}, and the host reads fp16", found.dtype));
        }
        let mut bytes = vec![0u8; (found.len as usize) * 2];
        self.data
            .read_at(found.offset as u64, &mut bytes)
            .map_err(|e| format!("tensor {index}: {e}"))?;
        Ok(bytes
            .chunks_exact(2)
            .map(|c| f16_to_f32(u16::from_le_bytes([c[0], c[1]])))
            .collect())
    }

    /// One row of an int8 tensor, dequantised by that row's scale.
    ///
    /// The counterpart of [`Reader::fp16`] for a quantised table, and it exists for one caller:
    /// [`crate::nets::nllb`] gathers rows of the tied embedding on the host rather than in a
    /// shader. Doing so removes the need for an int8 `embed.comp`, lets `sqrt(d_model)` and the
    /// sinusoidal position be applied in f32 before anything is rounded, and reads 1 KB per token
    /// instead of uploading a ~250 MiB table a second time.
    ///
    /// A row rather than the whole tensor because the whole tensor is ~250 MiB. `dims[0]` is the
    /// row count and a row is contiguous, which is a property of the `[out, in, 1, 1]` layout
    /// `scripts/ml/maml_convert.py` writes rather than an assumption — and `shaped` checks it.
    pub fn int8_row(
        &self,
        index: usize,
        scale_index: usize,
        dims: &[u32],
        row: u32,
    ) -> Result<Vec<f32>, String> {
        let found = self.table.shaped(index, dims)?;
        if found.dtype != Dtype::I8 {
            return Err(format!("tensor {index} is {:?}, and this dequantises int8", found.dtype));
        }
        let rows = *dims.first().ok_or("an int8 row read needs a row count")?;
        if row >= rows {
            return Err(format!("row {row} of a {rows}-row tensor {index}"));
        }
        let stride = (found.len / rows) as usize;
        let mut bytes = vec![0u8; stride];
        let at = u64::from(found.offset) + u64::from(row) * stride as u64;
        self.data.read_at(at, &mut bytes).map_err(|e| format!("tensor {index} row {row}: {e}"))?;

        let scale = self.table.shaped(scale_index, &[rows])?;
        if scale.dtype.is_quantised() {
            return Err(format!("tensor {scale_index} is {:?}, and a scale is fp16", scale.dtype));
        }
        let mut half = [0u8; 2];
        self.data
            .read_at(u64::from(scale.offset) + u64::from(row) * 2, &mut half)
            .map_err(|e| format!("tensor {scale_index} row {row}: {e}"))?;
        let scale = f16_to_f32(u16::from_le_bytes(half));
        Ok(bytes.iter().map(|&b| f32::from(b as i8) * scale).collect())
    }

    /// One row of an int4 tensor, dequantised by that row's **block** scales.
    ///
    /// The int4 counterpart of [`Reader::int8_row`], and the difference is the scale: an int8 row
    /// has one, an int4 row has `ceil(stride / I4_BLOCK)` of them and each covers its own span of
    /// the row. Reading the first and applying it to the whole row would produce numbers of
    /// entirely the right magnitude for the first 32 columns and nonsense after.
    ///
    /// Exists for the same caller [`Reader::int8_row`] does: Gemma 4's embedding is two tables of
    /// 262144 rows, and a decode step needs one row of each. Binding 4.7 GB of table to gather
    /// 1536 values is not a trade worth making.
    ///
    /// # Odd strides
    ///
    /// A row starts on a **byte** boundary only if `stride` is even. Every table this reads has
    /// an even stride - 1536 and 8960 - so rather than carry a nibble offset through the loop,
    /// an odd stride is refused. A silent half-byte skew would be far harder to find later than
    /// this error is now.
    pub fn int4_row(
        &self,
        index: usize,
        scale_index: usize,
        dims: &[u32],
        row: u32,
    ) -> Result<Vec<f32>, String> {
        let found = self.table.shaped(index, dims)?;
        if found.dtype != Dtype::I4 {
            return Err(format!("tensor {index} is {:?}, and this dequantises int4", found.dtype));
        }
        let rows = *dims.first().ok_or("an int4 row read needs a row count")?;
        if row >= rows {
            return Err(format!("row {row} of a {rows}-row tensor {index}"));
        }
        let stride = (found.len / rows) as usize;
        if !stride.is_multiple_of(2) {
            return Err(format!(
                "tensor {index} has a {stride}-element row, which does not start on a byte"
            ));
        }
        let blocks = (stride as u32).div_ceil(I4_BLOCK);
        let mut packed = vec![0u8; stride / 2];
        let at = u64::from(found.offset) + u64::from(row) * (stride as u64 / 2);
        self.data.read_at(at, &mut packed).map_err(|e| format!("tensor {index} row {row}: {e}"))?;

        let scale = self.table.shaped(scale_index, &[rows, blocks])?;
        if scale.dtype.is_quantised() {
            return Err(format!("tensor {scale_index} is {:?}, and a scale is fp16", scale.dtype));
        }
        let mut scale_bytes = vec![0u8; blocks as usize * 2];
        self.data
            .read_at(
                u64::from(scale.offset) + u64::from(row) * u64::from(blocks) * 2,
                &mut scale_bytes,
            )
            .map_err(|e| format!("tensor {scale_index} row {row}: {e}"))?;
        let scales: Vec<f32> = scale_bytes
            .chunks_exact(2)
            .map(|pair| f16_to_f32(u16::from_le_bytes([pair[0], pair[1]])))
            .collect();

        let mut out = Vec::with_capacity(stride);
        for (at, &byte) in packed.iter().enumerate() {
            // Low nibble first, sign-extended from four bits, as `int4_at` does in the shaders.
            for nibble in [byte & 0x0f, byte >> 4] {
                let code = if nibble >= 8 { i32::from(nibble) - 16 } else { i32::from(nibble) };
                let column = out.len() as u32;
                out.push(code as f32 * scales[(column / I4_BLOCK) as usize]);
            }
            debug_assert!(out.len() <= stride, "byte {at} overran the row");
        }
        Ok(out)
    }
}

/// Fill `buf` from `offset` without moving the file's cursor.
///
/// The same helper as `library/tilecodec`'s `pmtiles::read_exact_at`, and copied rather than shared
/// because these two crates have no dependency between them and this is six lines. Both platforms
/// expose a positional read; neither is guaranteed to return everything at once, hence the loop.
///
/// Positional rather than seek-then-read because the cursor is shared with whatever else holds this
/// descriptor — on Android the descriptor came out of `AssetManager`, and moving its cursor is not
/// this module's business.
fn read_exact_at(file: &File, mut buf: &mut [u8], mut offset: u64) -> std::io::Result<()> {
    while !buf.is_empty() {
        #[cfg(windows)]
        let n = std::os::windows::fs::FileExt::seek_read(file, buf, offset)?;
        #[cfg(unix)]
        let n = std::os::unix::fs::FileExt::read_at(file, buf, offset)?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "the file ended mid-tensor",
            ));
        }
        buf = buf.get_mut(n..).unwrap_or(&mut []);
        offset += n as u64;
    }
    Ok(())
}

/// One tensor for [`write_mixed`]: fp16 values, or the int8 payload of a quantised kernel.
#[cfg(test)]
pub(crate) enum Fixture {
    /// fp16, the format every tensor but a quantised kernel is in.
    F16(Vec<u32>, Vec<f32>),
    /// int8, addressed by the shaders as a 32-bit word offset. See [`Tensor::word_offset`].
    I8(Vec<u32>, Vec<i8>),
    /// int4, two per byte, low nibble first. Values outside -8..=7 are a bug in the caller and
    /// are masked rather than clamped, so a fixture that overflows shows up as a wrong number
    /// rather than a silently saturated one.
    I4(Vec<u32>, Vec<i8>),
}

/// Build a `.maml` blob from a mix of fp16 and int8 tensors, for the fixtures.
///
/// The `write` helper in this module's tests emits fp16 only, and an int8 convolution needs a table
/// where one tensor is int8 and the two after it — its per-channel scale and its bias — are not.
/// Shared rather than hand-rolled per test because the 16-byte alignment and the resulting word
/// offsets are precisely what a second copy would get subtly wrong, and a wrong offset here reads a
/// neighbouring tensor at the right shape.
#[cfg(test)]
pub(crate) fn write_mixed(graph_id: u32, tensors: &[Fixture]) -> Vec<u8> {
    fn f32_to_f16(v: f32) -> u16 {
        let bits = v.to_bits();
        let sign = ((bits >> 16) & 0x8000) as u16;
        let exponent = ((bits >> 23) & 0xFF) as i32 - 127 + 15;
        let mantissa = bits & 0x007F_FFFF;
        if exponent >= 0x1F {
            return sign | 0x7C00;
        }
        if exponent <= 0 {
            return sign;
        }
        sign | ((exponent as u16) << 10) | ((mantissa >> 13) as u16)
    }

    let mut table = Vec::new();
    let mut data: Vec<u8> = Vec::new();
    for tensor in tensors {
        let (dims, dtype, len, bytes) = match tensor {
            Fixture::F16(dims, values) => (
                dims,
                DTYPE_F16,
                values.len() as u32,
                values.iter().flat_map(|&v| f32_to_f16(v).to_le_bytes()).collect::<Vec<u8>>(),
            ),
            Fixture::I8(dims, values) => (
                dims,
                DTYPE_I8,
                values.len() as u32,
                values.iter().map(|&v| v as u8).collect::<Vec<u8>>(),
            ),
            Fixture::I4(dims, values) => (
                dims,
                DTYPE_I4,
                values.len() as u32,
                values
                    .chunks(2)
                    .map(|pair| match pair {
                        [low, high] => (*low as u8 & 0x0f) | ((*high as u8 & 0x0f) << 4),
                        // An odd length leaves the high nibble of the last byte as padding,
                        // which is what `Dtype::bytes`'s `div_ceil` accounts for.
                        [low] => *low as u8 & 0x0f,
                        _ => 0,
                    })
                    .collect::<Vec<u8>>(),
            ),
        };
        while !data.len().is_multiple_of(ALIGNMENT as usize) {
            data.push(0);
        }
        let offset = data.len() as u32;
        data.extend_from_slice(&bytes);
        table.extend_from_slice(&(dims.len() as u32).to_le_bytes());
        for slot in 0..4 {
            table.extend_from_slice(&dims.get(slot).copied().unwrap_or(0).to_le_bytes());
        }
        table.extend_from_slice(&dtype.to_le_bytes());
        table.extend_from_slice(&offset.to_le_bytes());
        table.extend_from_slice(&len.to_le_bytes());
    }

    let mut blob = Vec::new();
    blob.extend_from_slice(&MAGIC);
    blob.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
    blob.extend_from_slice(&graph_id.to_le_bytes());
    blob.extend_from_slice(&(tensors.len() as u32).to_le_bytes());
    blob.extend_from_slice(&[0u8; 32]);
    blob.extend_from_slice(&((HEADER_BYTES + table.len()) as u32).to_le_bytes());
    blob.extend_from_slice(&(data.len() as u32).to_le_bytes());
    blob.extend_from_slice(&[0u8; 8]);
    blob.extend_from_slice(&table);
    blob.extend_from_slice(&data);
    blob
}

fn u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a file the way the converter does, so the round-trip is a real check of
    /// the two implementations agreeing rather than of this module agreeing with
    /// itself.
    fn write(graph_id: u32, tensors: &[(Vec<u32>, Vec<f32>)]) -> Vec<u8> {
        let mut table = Vec::new();
        let mut data: Vec<u8> = Vec::new();
        for (dims, values) in tensors {
            while !data.len().is_multiple_of(ALIGNMENT as usize) {
                data.push(0);
            }
            let offset = data.len() as u32;
            for &v in values {
                data.extend_from_slice(&f32_to_f16(v).to_le_bytes());
            }
            let mut padded = [0u32; 4];
            padded[..dims.len()].copy_from_slice(dims);
            table.extend_from_slice(&(dims.len() as u32).to_le_bytes());
            for d in padded {
                table.extend_from_slice(&d.to_le_bytes());
            }
            table.extend_from_slice(&DTYPE_F16.to_le_bytes());
            table.extend_from_slice(&offset.to_le_bytes());
            table.extend_from_slice(&(values.len() as u32).to_le_bytes());
        }

        let mut out = Vec::new();
        out.extend_from_slice(&MAGIC);
        out.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        out.extend_from_slice(&graph_id.to_le_bytes());
        out.extend_from_slice(&(tensors.len() as u32).to_le_bytes());
        out.extend_from_slice(&[0u8; 32]);
        out.extend_from_slice(&((HEADER_BYTES + table.len()) as u32).to_le_bytes());
        out.extend_from_slice(&(data.len() as u32).to_le_bytes());
        out.extend_from_slice(&[0u8; 8]);
        out.extend_from_slice(&table);
        out.extend_from_slice(&data);
        out
    }

    /// Round-half-to-even fp32 to fp16, enough for the test fixtures above.
    fn f32_to_f16(v: f32) -> u16 {
        let bits = v.to_bits();
        let sign = ((bits >> 16) & 0x8000) as u16;
        let exponent = ((bits >> 23) & 0xff) as i32 - 127 + 15;
        let mantissa = bits & 0x007f_ffff;
        if exponent <= 0 {
            return sign;
        }
        sign | ((exponent as u16) << 10) | ((mantissa >> 13) as u16)
    }

    /// Write `bytes` into a temp file after `pad` bytes of filler, and return the file.
    ///
    /// The padding is the point: a `.maml` bundled as an asset is a *range* of the APK, not a file,
    /// so [`Streamed`] adds a base offset to every read. A fixture at offset 0 passes whether or not
    /// that offset is applied, which is the one thing worth checking here.
    fn on_disk(name: &str, pad: usize, bytes: &[u8]) -> (File, u64, u64) {
        use std::io::Write;
        let path = std::env::temp_dir().join(format!("modelrunner-{name}.maml"));
        let mut file = std::fs::File::create(&path).expect("a temp file");
        file.write_all(&vec![0xABu8; pad]).expect("the padding writes");
        file.write_all(bytes).expect("the blob writes");
        // Trailing filler as well, so the file does not end where the data section does: an asset
        // is followed by the next asset, and `Streamed` must not require otherwise.
        file.write_all(&[0xCDu8; 7]).expect("the trailer writes");
        drop(file);
        let opened = std::fs::File::open(&path).expect("the temp file reopens");
        (opened, pad as u64, bytes.len() as u64)
    }

    #[test]
    fn a_streamed_file_answers_exactly_as_a_parsed_one() {
        // The bundled path: the table is read from the file's prefix and the data section stays on
        // disk. Both halves must match what `Weights::parse` produces from the same bytes, because
        // `Net::new` uploads one and the plan was resolved against the other.
        let bytes = write(
            graph::SUPERTONIC_VE,
            &[(vec![2, 3], vec![1.0, 2.0, 4.0, 8.0, 16.0, 32.0]), (vec![2], vec![0.5, 0.25])],
        );
        let parsed = Weights::parse(&bytes, graph::SUPERTONIC_VE).expect("parses");
        let (file, at, len) = on_disk("streamed", 4096, &bytes);
        let streamed =
            Streamed::open(file, at, len, graph::SUPERTONIC_VE).expect("the file streams");

        assert_eq!(streamed.graph_id, parsed.graph_id);
        assert_eq!(streamed.len(), parsed.len());
        assert_eq!(streamed.data_len(), parsed.data_len());
        for index in 0..parsed.len() {
            assert_eq!(
                streamed.offsets().tensor(index).expect("in range"),
                parsed.tensor(index).expect("in range")
            );
        }
        // Byte for byte, and read in pieces rather than whole: the upload asks for chunks, so a
        // base offset applied once at open rather than per read would pass a single-read fixture.
        let mut got = vec![0u8; parsed.data_len() as usize];
        for (chunk, into) in got.chunks_mut(7).enumerate() {
            streamed.read_at((chunk * 7) as u64, into).expect("the chunk reads");
        }
        let mut want = vec![0u8; parsed.data_len() as usize];
        parsed.read_at(0, &mut want).expect("the whole section reads");
        assert_eq!(got, want);

        // And the host-side reads go through the same table and the same bytes.
        assert_eq!(
            streamed.reader().fp16(0, &[2, 3]).expect("the streamed tensor"),
            parsed.reader().fp16(0, &[2, 3]).expect("the parsed tensor")
        );
    }

    #[test]
    fn a_streamed_read_past_the_data_section_is_refused() {
        let bytes = write(graph::SUPERTONIC_DP, &[(vec![4], vec![1.0, 2.0, 3.0, 4.0])]);
        let (file, at, len) = on_disk("streamed-bounds", 16, &bytes);
        let streamed = Streamed::open(file, at, len, graph::SUPERTONIC_DP).expect("streams");

        let mut into = [0u8; 8];
        assert!(streamed.read_at(streamed.data_len() - 4, &mut into).is_err());
        assert!(streamed.read_at(u64::MAX, &mut into).is_err());
        // The trailing filler `on_disk` wrote is past the data section and must stay unreachable,
        // or a truncated `.maml` would upload whatever followed it in the APK.
        assert!(streamed.read_at(streamed.data_len(), &mut into[..1]).is_err());
    }

    #[test]
    fn a_streamed_file_shorter_than_its_own_header_says_is_refused() {
        // A length the caller was told, against a header that claims more. `AssetManager` reports
        // the range it will serve, so a disagreement means the asset was truncated at build time —
        // and the reads that ran off the end would land in the next asset rather than failing.
        let bytes = write(graph::SUPERTONIC_VOC, &[(vec![8], vec![1.0; 8])]);
        let (file, at, len) = on_disk("streamed-short", 0, &bytes);
        assert!(Streamed::open(file, at, len - 8, graph::SUPERTONIC_VOC).is_err());
    }

    #[test]
    fn a_streamed_file_for_another_graph_is_refused() {
        // The same check `Weights::parse` makes, and it has to happen here too: the four Supertonic
        // plans arrive as four descriptors in a fixed order, so a caller that swapped two would
        // otherwise upload the vocoder's weights into the text encoder's buffer.
        let bytes = write(graph::SUPERTONIC_TTL, &[(vec![2], vec![1.0, 2.0])]);
        let (file, at, len) = on_disk("streamed-wrong-graph", 32, &bytes);
        assert!(Streamed::open(file, at, len, graph::SUPERTONIC_VE).is_err());
    }

    #[test]
    fn the_table_outlives_the_blob_and_answers_identically() {
        // What `Net::rebuild` depends on: the offsets a plan resolves against do not come from the
        // data section, so a retained table gives the same answers after the file is gone. If it
        // did not, a rebuilt plan would index device memory that holds something else — the right
        // shape and the wrong tensor, which no count or digest check would notice.
        let bytes = write(
            graph::SUPERTONIC_TTL,
            &[(vec![2, 1, 1, 1], vec![1.0, 2.0]), (vec![3], vec![4.0, 8.0, 16.0])],
        );
        let weights = Weights::parse(&bytes, graph::SUPERTONIC_TTL).expect("parses");
        let table = weights.offsets();
        let whole: Vec<Tensor> =
            (0..weights.len()).map(|i| weights.tensor(i).expect("in range")).collect();
        drop(weights);

        assert_eq!(table.len(), whole.len());
        for (index, expected) in whole.iter().enumerate() {
            assert_eq!(&table.tensor(index).expect("in range"), expected);
        }
        assert_eq!(table.shaped(0, &[2, 1, 1, 1]).expect("the declared shape").elem_offset(), 0);
        // And the shape check is still the shape check, not a length check.
        assert!(table.shaped(1, &[4]).is_err());
        assert!(table.tensor(2).is_err());
    }

    #[test]
    fn a_written_file_reads_back_with_the_same_table() {
        let bytes = write(
            graph::U2NETP,
            &[
                (vec![2, 1, 1, 1], vec![1.0, 2.0]),
                (vec![3], vec![4.0, 8.0, 16.0]),
            ],
        );
        let weights = Weights::parse(&bytes, graph::U2NETP).expect("parses");
        assert_eq!(weights.len(), 2);
        assert_eq!(
            weights.tensor(0).expect("first"),
            Tensor { rank: 4, dims: [2, 1, 1, 1], offset: 0, len: 2, dtype: Dtype::F16 }
        );
        // Tensor 0 is 4 bytes but the next starts at 16: the alignment the shaders
        // rely on, and the arithmetic most likely to be got wrong.
        assert_eq!(
            weights.tensor(1).expect("second"),
            Tensor { rank: 1, dims: [3, 0, 0, 0], offset: 16, len: 3, dtype: Dtype::F16 }
        );
        assert_eq!(weights.tensor(1).expect("second").elem_offset(), 8);
        assert_eq!(weights.data().len(), 16 + 6);
    }

    #[test]
    fn an_int8_tensor_reads_back_with_a_byte_stride_and_a_word_offset() {
        // Hand-built, because `write` above only emits fp16. One int8 tensor of five bytes,
        // then an fp16 scale after it — the layout `Builder::conv_int8` expects.
        let mut table = Vec::new();
        let mut data: Vec<u8> = Vec::new();
        // int8 [5], at offset 0.
        let payload: [i8; 5] = [-128, -1, 0, 1, 127];
        for (dtype, dims, len, bytes) in [
            (DTYPE_I8, [5u32, 0, 0, 0], 5u32, payload.iter().map(|&b| b as u8).collect::<Vec<u8>>()),
            (DTYPE_F16, [1u32, 0, 0, 0], 1u32, f32_to_f16(0.25).to_le_bytes().to_vec()),
        ] {
            while !data.len().is_multiple_of(ALIGNMENT as usize) {
                data.push(0);
            }
            let offset = data.len() as u32;
            data.extend_from_slice(&bytes);
            table.extend_from_slice(&1u32.to_le_bytes());
            for d in dims {
                table.extend_from_slice(&d.to_le_bytes());
            }
            table.extend_from_slice(&dtype.to_le_bytes());
            table.extend_from_slice(&offset.to_le_bytes());
            table.extend_from_slice(&len.to_le_bytes());
        }
        let mut blob = Vec::new();
        blob.extend_from_slice(&MAGIC);
        blob.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        blob.extend_from_slice(&graph::SUPERTONIC_VE.to_le_bytes());
        blob.extend_from_slice(&2u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 32]);
        blob.extend_from_slice(&((HEADER_BYTES + table.len()) as u32).to_le_bytes());
        blob.extend_from_slice(&(data.len() as u32).to_le_bytes());
        blob.extend_from_slice(&[0u8; 8]);
        blob.extend_from_slice(&table);
        blob.extend_from_slice(&data);

        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
        let quantised = weights.tensor(0).expect("the int8 tensor");
        assert_eq!(quantised.dtype, Dtype::I8);
        assert_eq!(quantised.len, 5);
        // Five elements at one byte each, so the *scale* still lands at 16: the alignment is
        // in bytes, not elements, and an int8 tensor must not be read with an fp16 stride.
        let scale = weights.tensor(1).expect("the scale");
        assert_eq!(scale.dtype, Dtype::F16);
        assert_eq!(scale.offset, ALIGNMENT);
        // The shader addresses int8 through the 32-bit view, so offset 0 is word 0.
        assert_eq!(quantised.word_offset(), 0);
        assert_eq!(scale.elem_offset(), ALIGNMENT / 2);
        // And the payload survived: a five-byte tensor is not padded to an even length.
        assert_eq!(&weights.data()[0..5], &[0x80, 0xFF, 0x00, 0x01, 0x7F]);
    }

    #[test]
    fn an_int4_tensor_of_odd_length_rounds_its_last_nibble_up() {
        // The one place the converter and the reader can disagree without either looking wrong.
        // Five four-bit elements are two and a half bytes; `Dtype::bytes` rounds to three and the
        // final high nibble is padding. A converter that wrote two bytes would produce a file
        // that parses - the bounds check would pass - and whose last element read as whatever
        // followed it.
        let values: Vec<i8> = vec![-8, -1, 0, 1, 7];
        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[
                Fixture::I4(vec![5], values.clone()),
                Fixture::F16(vec![1], vec![0.5]),
            ],
        );
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
        let quantised = weights.tensor(0).expect("the int4 tensor");
        assert_eq!(quantised.dtype, Dtype::I4);
        assert_eq!(quantised.len, 5, "five elements, not five bytes");
        assert_eq!(Dtype::I4.bytes(5), 3, "two and a half bytes rounds up");
        // The scale still lands on the 16-byte boundary, as it does after an int8 tensor.
        let scale = weights.tensor(1).expect("the scale");
        assert_eq!(scale.offset, ALIGNMENT);
        assert_eq!(quantised.word_offset(), 0);

        // Low nibble first, sign preserved. -8 and 7 are the ends of the representable range, so
        // a reader that treated the nibbles as unsigned would give 8 and 7 rather than -8 and 7.
        let packed = &weights.data()[0..3];
        assert_eq!(packed[0], 0x08 | (0x0f << 4), "-8 then -1");
        assert_eq!(packed[1], 0x00 | (0x01 << 4), "0 then 1");
        assert_eq!(packed[2], 0x07, "7, with the high nibble left as padding");
    }

    #[test]
    fn an_int4_tensor_of_even_length_uses_exactly_half_its_elements_in_bytes() {
        let values: Vec<i8> = (0..64).map(|i| ((i % 16) - 8) as i8).collect();
        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[Fixture::I4(vec![8, 8], values), Fixture::F16(vec![8], vec![1.0; 8])],
        );
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
        let quantised = weights.tensor(0).expect("the int4 tensor");
        assert_eq!(quantised.len, 64);
        assert_eq!(Dtype::I4.bytes(64), 32);
        // 32 bytes is two alignment units, so the scale follows at 32 rather than at 16.
        assert_eq!(weights.tensor(1).expect("the scale").offset, 32);
    }

    #[test]
    fn an_int4_tensor_past_the_data_section_is_refused() {
        // The bounds check has to use `Dtype::bytes` too, or an int4 tensor claiming twice the
        // elements the file holds would pass a byte-stride check.
        let mut blob = Vec::new();
        blob.extend_from_slice(&MAGIC);
        blob.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        blob.extend_from_slice(&graph::SUPERTONIC_VE.to_le_bytes());
        blob.extend_from_slice(&1u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 32]);
        blob.extend_from_slice(&((HEADER_BYTES + TENSOR_ENTRY_BYTES) as u32).to_le_bytes());
        blob.extend_from_slice(&16u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 8]);
        blob.extend_from_slice(&1u32.to_le_bytes()); // rank
        for dim in [64u32, 0, 0, 0] {
            blob.extend_from_slice(&dim.to_le_bytes());
        }
        blob.extend_from_slice(&2u32.to_le_bytes()); // DTYPE_I4
        blob.extend_from_slice(&0u32.to_le_bytes()); // offset
        blob.extend_from_slice(&64u32.to_le_bytes()); // len: 32 bytes, into a 16-byte section
        blob.extend_from_slice(&[0u8; 16]);
        let error = Weights::parse(&blob, graph::SUPERTONIC_VE).expect_err("out of bounds");
        assert!(error.contains("spans"), "{error}");
    }

    #[test]
    fn an_int4_row_is_dequantised_by_its_own_block_scales() {
        // The whole point of int4's rank-2 scale, and the failure it prevents: reading the first
        // block's scale and applying it to the row gives numbers of exactly the right magnitude
        // for the first 32 columns and nonsense after. So the fixture makes the blocks differ by
        // a factor of eight and checks every column, not a sample.
        let rows = 3u32;
        let stride = 96u32; // three whole blocks of 32
        let blocks = stride / I4_BLOCK;
        let codes: Vec<i8> = (0..(rows * stride) as i32).map(|i| ((i * 5) % 15 - 7) as i8).collect();
        let scales: Vec<f32> = (0..rows * blocks)
            .map(|i| 0.03125 * f32::from(1u8 << (i % blocks) as u8))
            .collect();
        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[
                Fixture::I4(vec![rows, stride], codes.clone()),
                Fixture::F16(vec![rows, blocks], scales.clone()),
            ],
        );
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
        let reader = weights.reader();
        for row in 0..rows {
            let got = reader.int4_row(0, 1, &[rows, stride], row).expect("a row");
            assert_eq!(got.len() as u32, stride);
            for column in 0..stride {
                let code = codes[(row * stride + column) as usize];
                let scale = scales[(row * blocks + column / I4_BLOCK) as usize];
                assert!(
                    (got[column as usize] - f32::from(code) * scale).abs() < 1e-6,
                    "row {row} column {column}: {} against {}",
                    got[column as usize],
                    f32::from(code) * scale
                );
            }
        }
    }

    #[test]
    fn an_int4_row_read_refuses_an_odd_stride_and_a_row_past_the_end() {
        // An odd stride would put every second row half a byte out of step. Refusing beats
        // carrying a nibble offset that nothing in this tree needs.
        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[Fixture::I4(vec![2, 3], vec![1, 2, 3, 4, 5, 6]), Fixture::F16(vec![2, 1], vec![1.0; 2])],
        );
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
        let reader = weights.reader();
        let odd = reader.int4_row(0, 1, &[2, 3], 0).expect_err("an odd stride");
        assert!(odd.contains("does not start on a byte"), "{odd}");

        let blob = write_mixed(
            graph::SUPERTONIC_VE,
            &[Fixture::I4(vec![2, 4], vec![1, 2, 3, 4, 5, 6, 7, -8]), Fixture::F16(vec![2, 1], vec![1.0; 2])],
        );
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
        let reader = weights.reader();
        let past = reader.int4_row(0, 1, &[2, 4], 2).expect_err("row 2 of 2");
        assert!(past.contains("row 2"), "{past}");
        // And the last code is -8, which only survives if the nibble is sign-extended.
        let last = reader.int4_row(0, 1, &[2, 4], 1).expect("row 1");
        assert!((last[3] + 8.0).abs() < 1e-6, "sign extension: {last:?}");
    }

    #[test]
    fn an_unknown_dtype_is_refused() {
        let mut blob = Vec::new();
        blob.extend_from_slice(&MAGIC);
        blob.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        blob.extend_from_slice(&graph::SELFIE.to_le_bytes());
        blob.extend_from_slice(&1u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 32]);
        blob.extend_from_slice(&((HEADER_BYTES + TENSOR_ENTRY_BYTES) as u32).to_le_bytes());
        blob.extend_from_slice(&2u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 8]);
        blob.extend_from_slice(&1u32.to_le_bytes());
        for d in [1u32, 0, 0, 0] {
            blob.extend_from_slice(&d.to_le_bytes());
        }
        // A dtype nothing implements. Refusing beats reading it as whichever stride is
        // nearest and returning plausible rubbish.
        blob.extend_from_slice(&7u32.to_le_bytes());
        blob.extend_from_slice(&0u32.to_le_bytes());
        blob.extend_from_slice(&1u32.to_le_bytes());
        blob.extend_from_slice(&[0u8; 2]);
        let error = Weights::parse(&blob, graph::SELFIE).expect_err("dtype 7");
        assert!(error.contains("dtype 7"), "{error}");
    }

    #[test]
    fn a_file_for_another_graph_is_refused() {
        let bytes = write(graph::SELFIE, &[(vec![1], vec![1.0])]);
        let error = Weights::parse(&bytes, graph::U2NETP).expect_err("wrong graph");
        assert!(error.contains("graph 1"), "{error}");
    }

    #[test]
    fn a_truncated_file_is_refused_at_load() {
        let bytes = write(graph::U2NETP, &[(vec![4], vec![1.0, 2.0, 3.0, 4.0])]);
        let error =
            Weights::parse(&bytes[..bytes.len() - 2], graph::U2NETP).expect_err("truncated");
        assert!(error.contains("sections end at"), "{error}");
    }

    #[test]
    fn a_shape_the_forward_pass_did_not_expect_is_refused() {
        let bytes = write(graph::U2NETP, &[(vec![2, 2], vec![1.0, 2.0, 3.0, 4.0])]);
        let weights = Weights::parse(&bytes, graph::U2NETP).expect("parses");
        assert!(weights.shaped(0, &[2, 2]).is_ok());
        let error = weights.shaped(0, &[4]).expect_err("wrong shape");
        assert!(error.contains("[2, 2]"), "{error}");
    }

    #[test]
    fn a_dims_and_len_disagreement_is_refused() {
        let mut bytes = write(graph::U2NETP, &[(vec![4], vec![1.0, 2.0, 3.0, 4.0])]);
        // Claim 5 elements for a 4-element tensor: the shape check must catch it
        // before the bounds check would.
        let len_at = HEADER_BYTES + 28;
        bytes[len_at..len_at + 4].copy_from_slice(&5u32.to_le_bytes());
        let error = Weights::parse(&bytes, graph::U2NETP).expect_err("bad len");
        assert!(error.contains("but len 5"), "{error}");
    }
}
///
/// The `.maml` tensor blob this describes is uploaded verbatim; this section only says what
/// to *do* with it. Nodes name file tensor indices for weights (resolved and shape-checked
/// against the table, exactly as [`crate::nets::Builder::weight`] does) and computed indices
/// for intermediate values. Lowering runs through the same `finish` — fusion fold, liveness,
/// arena packing — as a hand-written forward pass, so a section-derived plan and a
/// Rust-derived plan over the same graph are the same plan.
///
/// Layout (little-endian throughout, like the rest of the file):
///
/// ```text
/// 0   4   u32 node count N (0 < N <= 65536)
/// 4   ... N nodes, each:
///         1   u8 kind tag (see `TAG_*`)
///         ... kind payload, fixed size per kind
/// ... computed tensors: u32 count M, then M x (c, h, w) u32 triples
/// ... inputs: u32 count, then computed indices
/// ... outputs: u32 count, then computed indices
/// ... host tensors: u32 count, then (file index, rank, dims[4]) each
/// ```
///
/// Weight refs are file indices, validated against the table for both range and shape.
/// A file index past the table, or a shape the table disagrees with, is a load error —
/// the same strictness as the builder's, which is the point: a corrupt section must fail
/// here rather than dispatch a shader reading past a device buffer.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Graph {
    /// The section's nodes, in execution order.
    pub nodes: Vec<GraphNode>,
    /// Shapes of every computed tensor, indexed by node value references.
    pub computed: Vec<[u32; 3]>,
    /// Computed indices of the plan inputs, in declaration order.
    pub inputs: Vec<u32>,
    /// Computed indices of the plan outputs.
    pub outputs: Vec<u32>,
    /// `(file index, rank, dims)` tensors read on the host rather than by the plan.
    pub host: Vec<(u32, u32, [u32; 4])>,
}

/// One symbolic op. Phase 1 covers the foldable core — convolutions, the residual adds,
/// and layer norm — which is every node of the sampler's ConvNeXt blocks. Each variant
/// carries file tensor indices for weights and computed indices for values: the same
/// addressing `Builder` resolves, frozen to numbers.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum GraphNode {
    /// `out = act(conv(input))`: kernel/bias file indices, geometry, activation code.
    Conv {
        input: u32,
        out: u32,
        weight: u32,
        bias: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        act: u32,
        /// Slope file index for PReLU, `u32::MAX` otherwise. See [`crate::nets::Act`].
        act_weight: u32,
        /// Replicate the border instead of reading zeros. See [`crate::nets::Push::pad_edge`].
        pad_edge: bool,
    },
    /// `out = act(scale * conv_int(input) + bias)`: kernel/scale/bias file indices.
    ConvInt8 {
        input: u32,
        out: u32,
        weight: u32,
        scale: u32,
        bias: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        act: u32,
        /// 0 = eight-bit, 1 = four-bit. Anything else is a load error.
        quant: u32,
    },
    /// `out = a + b`.
    Add { a: u32, b: u32, out: u32 },
    /// `out = a + b[c]`, one shift per channel.
    AddBroadcast { a: u32, b: u32, out: u32 },
    /// Mean/variance normalisation over channels with a per-channel affine.
    LayerNorm { input: u32, out: u32, gamma: u32, beta: u32, epsilon_bits: u32 },
    /// Mean over H and W, keeping C.
    GlobalAvgPool { input: u32, out: u32 },
    /// Same elements under the computed shape. The section states the shape; the
    /// validator requires the element counts to agree.
    Reshape { input: u32, out: u32 },
}

/// Kind tags, one byte on the wire. Sequential from zero; unknown tags are a load error.
pub const TAG_CONV: u8 = 0;
pub const TAG_CONV_INT8: u8 = 1;
pub const TAG_ADD: u8 = 2;
pub const TAG_ADD_BROADCAST: u8 = 3;
pub const TAG_LAYER_NORM: u8 = 4;
/// Mean over H and W, keeping C. Only the attention-bias generator needs it in phase 1.
pub const TAG_GLOBAL_AVG_POOL: u8 = 5;
/// A relabelling, not a move: same elements under a new shape. Only `reshaped`'s
/// single-part form serialises — a multi-part `Concat` is a real join, not a view.
pub const TAG_RESHAPE: u8 = 6;

/// Nodes past this count are refused. The largest net here emits ~2,000 nodes per branch;
/// 64K is headroom, not a target, and its purpose is bounding the parse loop against a
/// corrupt count field claiming billions.
const MAX_GRAPH_NODES: usize = 65_536;

/// Computed tensors past this count are refused, for the same reason as nodes.
const MAX_GRAPH_TENSORS: usize = 65_536;

/// No weight tensor here: PReLU slope absent, int8-scale positions, host-tensor slots —
/// anywhere a file index is optional, all-bits-set means none. Zero is a live tensor
/// (the first input pins offset 0 in spirit, and file index 0 is the first weight), so
/// it cannot mean "absent".
const NO_TENSOR: u32 = u32::MAX;

impl Graph {
    /// Parse and validate `section` against the file's tensor `table`.
    ///
    /// Validation is in dependency order: structure first (counts, tags, payload lengths),
    /// then references (every index resolves), then shapes (recomputed, not trusted). Any
    /// failure is a `String`, like every other load refusal in this module.
    pub fn parse(section: &[u8], table: &[Tensor]) -> Result<Graph, String> {
        let mut cursor = Cursor { bytes: section };
        let count = cursor.u32("node count")? as usize;
        if count == 0 || count > MAX_GRAPH_NODES {
            return Err(format!("graph section claims {count} nodes"));
        }
        let mut nodes = Vec::with_capacity(count.min(1024));
        for i in 0..count {
            let tag = cursor.u8(&format!("node {i} tag"))?;
            let node = match tag {
                TAG_CONV => {
                    let f = cursor.take(72, &format!("node {i} conv"))?;
                    let g = |o: usize| u32_of(&f[o..o + 4]);
                    GraphNode::Conv {
                        input: g(0),
                        out: g(4),
                        weight: g(8),
                        bias: g(12),
                        kernel: (g(16), g(20)),
                        stride: (g(24), g(28)),
                        dilation: (g(32), g(36)),
                        pads: (g(40), g(44), g(48), g(52)),
                        group: g(56),
                        act: g(60),
                        act_weight: g(64),
                        pad_edge: match g(68) {
                            0 => false,
                            1 => true,
                            other => {
                                return Err(format!("node {i} pad_edge {other}, not 0 or 1"))
                            }
                        },
                    }
                }
                TAG_CONV_INT8 => {
                    let f = cursor.take(72, &format!("node {i} conv_int8"))?;
                    let g = |o: usize| u32_of(&f[o..o + 4]);
                    let quant = g(68);
                    if quant > 1 {
                        return Err(format!("node {i} quant {quant}, not 0 or 1"));
                    }
                    GraphNode::ConvInt8 {
                        input: g(0),
                        out: g(4),
                        weight: g(8),
                        scale: g(12),
                        bias: g(16),
                        kernel: (g(20), g(24)),
                        stride: (g(28), g(32)),
                        dilation: (g(36), g(40)),
                        pads: (g(44), g(48), g(52), g(56)),
                        group: g(60),
                        act: g(64),
                        quant,
                    }
                }
                TAG_ADD => {
                    let f = cursor.take(12, &format!("node {i} add"))?;
                    GraphNode::Add {
                        a: u32_of(&f[0..4]),
                        b: u32_of(&f[4..8]),
                        out: u32_of(&f[8..12]),
                    }
                }
                TAG_ADD_BROADCAST => {
                    let f = cursor.take(12, &format!("node {i} add_broadcast"))?;
                    GraphNode::AddBroadcast {
                        a: u32_of(&f[0..4]),
                        b: u32_of(&f[4..8]),
                        out: u32_of(&f[8..12]),
                    }
                }
                TAG_LAYER_NORM => {
                    let f = cursor.take(20, &format!("node {i} layer_norm"))?;
                    GraphNode::LayerNorm {
                        input: u32_of(&f[0..4]),
                        out: u32_of(&f[4..8]),
                        gamma: u32_of(&f[8..12]),
                        beta: u32_of(&f[12..16]),
                        epsilon_bits: u32_of(&f[16..20]),
                    }
                }
                TAG_GLOBAL_AVG_POOL => {
                    let f = cursor.take(8, &format!("node {i} global_avg_pool"))?;
                    GraphNode::GlobalAvgPool {
                        input: u32_of(&f[0..4]),
                        out: u32_of(&f[4..8]),
                    }
                }
                TAG_RESHAPE => {
                    let f = cursor.take(8, &format!("node {i} reshape"))?;
                    GraphNode::Reshape {
                        input: u32_of(&f[0..4]),
                        out: u32_of(&f[4..8]),
                    }
                }
                other => return Err(format!("node {i} has kind tag {other}")),
            };
            nodes.push(node);
        }
        // Computed-shape table.
        let tensor_count = cursor.u32("computed count")? as usize;
        if tensor_count == 0 || tensor_count > MAX_GRAPH_TENSORS {
            return Err(format!("graph section claims {tensor_count} computed tensors"));
        }
        let mut computed = Vec::with_capacity(tensor_count.min(1024));
        for i in 0..tensor_count {
            let f = cursor.take(12, &format!("computed tensor {i}"))?;
            computed.push([u32_of(&f[0..4]), u32_of(&f[4..8]), u32_of(&f[8..12])]);
        }
        // Bindings.
        let inputs = cursor.indices("inputs")?;
        let outputs = cursor.indices("outputs")?;
        if inputs.is_empty() {
            return Err("graph section names no inputs".into());
        }
        if outputs.is_empty() {
            return Err("graph section names no outputs".into());
        }
        let host_count = cursor.u32("host count")? as usize;
        if host_count > MAX_GRAPH_TENSORS {
            return Err(format!("graph section claims {host_count} host tensors"));
        }
        let mut host = Vec::with_capacity(host_count.min(64));
        for i in 0..host_count {
            let f = cursor.take(24, &format!("host tensor {i}"))?;
            let rank = u32_of(&f[4..8]);
            if rank == 0 || rank > 4 {
                return Err(format!("host tensor {i} has rank {rank}"));
            }
            host.push((
                u32_of(&f[0..4]),
                rank,
                [u32_of(&f[8..12]), u32_of(&f[12..16]), u32_of(&f[16..20]), u32_of(&f[20..24])],
            ));
        }
        if !cursor.rest().is_empty() {
            return Err(format!("graph section has {} trailing bytes", cursor.rest().len()));
        }
        let graph = Graph { nodes, computed, inputs, outputs, host };
        graph.validate(table)?;
        Ok(graph)
    }

    /// Reference and shape checks over an already-parsed section.
    ///
    /// Every computed index must resolve into `computed`; every weight ref must resolve
    /// into `table` *at the shape the node implies*; every computed shape must equal the
    /// shape propagation recomputes. This is the part that makes a corrupt section a load
    /// error rather than a wrong answer.
    fn validate(&self, table: &[Tensor]) -> Result<(), String> {
        let computed = |index: u32, what: &str| -> Result<[u32; 3], String> {
            self.computed.get(index as usize).copied().ok_or_else(|| {
                format!("{what} names computed tensor {index} of {}", self.computed.len())
            })
        };
        let weight = |index: u32, dims: &[u32], what: &str| -> Result<(), String> {
            let found = table.get(index as usize).ok_or_else(|| {
                format!("{what} names file tensor {index} of {}", table.len())
            })?;
            let got = &found.dims[..found.rank as usize];
            if got != dims {
                return Err(format!("{what} wants file tensor {index} as {dims:?}, it is {got:?}"));
            }
            Ok(())
        };
        for (i, node) in self.nodes.iter().enumerate() {
            let what = format!("node {i}");
            match node {
                GraphNode::Conv {
                    input,
                    out,
                    weight: w,
                    bias: b,
                    kernel,
                    stride,
                    dilation,
                    pads,
                    group,
                    act,
                    act_weight,
                    pad_edge: _,
                } => {
                    let input_shape = computed(*input, &what)?;
                    let (kh, kw) = *kernel;
                    if *group == 0 {
                        return Err(format!("{what} has no groups"));
                    }
                    if !input_shape[0].is_multiple_of(*group) {
                        return Err(format!(
                            "{what}: {} channels do not split into {group} groups",
                            input_shape[0],
                            group = group
                        ));
                    }
                    let per_group = input_shape[0] / group;
                    // Output channels come from the node's own computed shape — the one
                    // thing the section states rather than derives.
                    let out_shape = computed(*out, &what)?;
                    weight(*w, &[out_shape[0], per_group, kh, kw], &what)?;
                    weight(*b, &[out_shape[0]], &what)?;
                    if *act == 4 {
                        if *act_weight == NO_TENSOR {
                            return Err(format!("{what} is a PReLU with no slope tensor"));
                        }
                        weight(*act_weight, &[out_shape[0], 1, 1], &what)?;
                    } else if *act_weight != NO_TENSOR {
                        return Err(format!("{what} carries a slope without a PReLU"));
                    }
                    let (pad_t, pad_l, pad_b, pad_r) = *pads;
                    let want_h =
                        conv_out_shape(input_shape[1], kh, stride.0, dilation.0, pad_t + pad_b);
                    let want_w =
                        conv_out_shape(input_shape[2], kw, stride.1, dilation.1, pad_l + pad_r);
                    if (out_shape[1], out_shape[2]) != (want_h, want_w) {
                        return Err(format!(
                            "{what}: computed shape {out_shape:?} is not [{}, {want_h}, {want_w}]",
                            out_shape[0]
                        ));
                    }
                }
                GraphNode::ConvInt8 {
                    input,
                    out,
                    weight: w,
                    scale,
                    bias: b,
                    kernel,
                    stride,
                    dilation,
                    pads,
                    group,
                    act,
                    quant,
                } => {
                    if *act == 4 {
                        return Err(format!("{what}: a quantised convolution cannot carry a PReLU"));
                    }
                    let input_shape = computed(*input, &what)?;
                    let (kh, kw) = *kernel;
                    if *group == 0 || !input_shape[0].is_multiple_of(*group) {
                        return Err(format!("{what}: bad groups for {}", input_shape[0]));
                    }
                    let per_group = input_shape[0] / group;
                    let out_shape = computed(*out, &what)?;
                    let kernel_dims = [out_shape[0], per_group, kh, kw];
                    weight(*w, &kernel_dims, &what)?;
                    match quant {
                        0 => weight(*scale, &[out_shape[0]], &what)?,
                        1 => {
                            let blocks = (per_group * kh * kw).div_ceil(I4_BLOCK);
                            weight(*scale, &[out_shape[0], blocks], &what)?;
                        }
                        _ => return Err(format!("{what}: quant {quant}")),
                    }
                    weight(*b, &[out_shape[0]], &what)?;
                    let (pad_t, pad_l, pad_b, pad_r) = *pads;
                    let want_h =
                        conv_out_shape(input_shape[1], kh, stride.0, dilation.0, pad_t + pad_b);
                    let want_w =
                        conv_out_shape(input_shape[2], kw, stride.1, dilation.1, pad_l + pad_r);
                    if (out_shape[1], out_shape[2]) != (want_h, want_w) {
                        return Err(format!(
                            "{what}: computed shape {out_shape:?} mismatches the geometry"
                        ));
                    }
                }
                GraphNode::Add { a, b, out } => {
                    let (sa, sb, so) =
                        (computed(*a, &what)?, computed(*b, &what)?, computed(*out, &what)?);
                    if sa != sb || sa != so {
                        return Err(format!("{what}: add of {sa:?} and {sb:?} into {so:?}"));
                    }
                }
                GraphNode::AddBroadcast { a, b, out } => {
                    let (sa, sb, so) =
                        (computed(*a, &what)?, computed(*b, &what)?, computed(*out, &what)?);
                    if sb[1] != 1 || sb[2] != 1 || sb[0] != sa[0] || sa != so {
                        return Err(format!("{what}: channel add of {sa:?} by {sb:?} into {so:?}"));
                    }
                }
                GraphNode::LayerNorm { input, out, gamma, beta, epsilon_bits: _ } => {
                    let shape = computed(*input, &what)?;
                    let result = computed(*out, &what)?;
                    if shape != result {
                        return Err(format!("{what}: norm of {shape:?} into {result:?}"));
                    }
                    weight(*gamma, &[shape[0]], &what)?;
                    weight(*beta, &[shape[0]], &what)?;
                }
                GraphNode::GlobalAvgPool { input, out } => {
                    let shape = computed(*input, &what)?;
                    let result = computed(*out, &what)?;
                    if result != [shape[0], 1, 1] {
                        return Err(format!("{what}: pool of {shape:?} into {result:?}"));
                    }
                }
                GraphNode::Reshape { input, out } => {
                    let shape = computed(*input, &what)?;
                    let result = computed(*out, &what)?;
                    if shape[0] * shape[1] * shape[2] != result[0] * result[1] * result[2] {
                        return Err(format!("{what}: reshape of {shape:?} into {result:?}"));
                    }
                }
            }
        }
        // Bindings resolve too.
        for (i, index) in self.inputs.iter().enumerate() {
            computed(*index, &format!("input {i}"))?;
        }
        for (i, index) in self.outputs.iter().enumerate() {
            computed(*index, &format!("output {i}"))?;
        }
        for (i, (index, rank, dims)) in self.host.iter().enumerate() {
            let found = table.get(*index as usize).ok_or_else(|| {
                format!("host tensor {i} names file tensor {index} of {}", table.len())
            })?;
            let got = &found.dims[..found.rank as usize];
            let want = &dims[..*rank as usize];
            if got != want {
                return Err(format!("host tensor {i} is {got:?}, not {want:?}"));
            }
        }
        Ok(())
    }

    /// Lower the section to builder nodes, for `finish`'s shared pipeline.
    ///
    /// Phase 1 covers single-branch, shape-fixed graphs: inputs are declared in order and
    /// every node replays through the matching `Builder` call. `finish` then fuses,
    /// packs, and resolves exactly as it would for a hand-written pass — which is what
    /// makes the equivalence test meaningful rather than circular.
    ///
    /// `emit` records one entry per node for the converter (see
    /// `scripts/ml/maml_convert.py`): the node's kind and its file/computed refs, in
    /// execution order. The emitter is the section writer's checklist — every `lower`
    /// arm below has a matching `emit` entry, and vice versa.
    pub fn lower(
        &self,
        builder: &mut crate::nets::Builder,
        table: &crate::weights::Offsets,
    ) -> Result<Vec<crate::nets::Id>, String> {
        use crate::nets::{Act, Shape};
        let shape_of = |c: [u32; 3]| Shape::new(c[0], c[1], c[2]);
        // Computed index -> builder Id, in section order. Inputs are declared first so
        // their indices are dense from zero; every other computed tensor arrives via
        // its producing node.
        let mut ids: Vec<Option<crate::nets::Id>> = vec![None; self.computed.len()];
        for &index in &self.inputs {
            let shape = self.computed.get(index as usize).ok_or_else(|| {
                format!("input names computed tensor {index} of {}", self.computed.len())
            })?;
            ids[index as usize] = Some(builder.input(shape_of(*shape)));
        }
        let id = |ids: &[Option<crate::nets::Id>], index: u32| -> Result<crate::nets::Id, String> {
            ids.get(index as usize).copied().flatten().ok_or_else(|| {
                format!("computed tensor {index} read before it is written")
            })
        };
        // A file index, range-checked. The section parser already validated shapes
        // against the table; lowering resolves the same indices to offsets through it.
        let offset = |table: &crate::weights::Offsets, index: u32| -> Result<u32, String> {
            let found = table.tensor(index as usize).map_err(|_| {
                format!("file tensor {index} of {}", table.len())
            })?;
            Ok(found.elem_offset())
        };
        // A word-unit offset, for quantised kernels addressed through the 32-bit view.
        let word_offset =
            |table: &crate::weights::Offsets, index: u32| -> Result<u32, String> {
                let found = table.tensor(index as usize).map_err(|_| {
                    format!("file tensor {index} of {}", table.len())
                })?;
                Ok(found.word_offset())
            };
        let act_of = |code: u32| -> Result<Act, String> {
            match code {
                0 => Ok(Act::None),
                1 => Ok(Act::Relu),
                2 => Ok(Act::HardSwish),
                3 => Ok(Act::Sigmoid),
                5 => Ok(Act::Clip01),
                6 => Ok(Act::Swish),
                8 => Ok(Act::Gelu),
                // PReLU carries its slope file index separately (see below); the code
                // alone cannot reconstruct it, so it is refused rather than guessed.
                4 => Err("a section PReLU, which needs a slope the payload omits".into()),
                other => Err(format!("activation code {other}")),
            }
        };
        for node in &self.nodes {
            match node {
                GraphNode::Conv {
                    input,
                    out,
                    weight,
                    bias,
                    kernel,
                    stride,
                    dilation,
                    pads,
                    group,
                    act,
                    act_weight,
                    pad_edge,
                } => {
                    if *act == 4 || *act_weight != NO_TENSOR {
                        return Err("a section PReLU, which phase 1 does not lower".into());
                    }
                    let activation = act_of(*act)?;
                    let a = id(&ids, *input)?;
                    // Resolved offsets, not indices: the parser validated shapes, so
                    // lowering only translates addressing. `m` is the section's own
                    // computed output channels — the one thing stated, not derived.
                    let out_shape =
                        self.computed.get(*out as usize).copied().ok_or_else(|| {
                            format!("computed tensor {out} out of range")
                        })?;
                    let produced = builder.conv_raw(
                        a,
                        offset(table, *weight)?,
                        offset(table, *bias)?,
                        NO_TENSOR,
                        out_shape[0],
                        activation,
                        *kernel,
                        *stride,
                        *dilation,
                        *pads,
                        *group,
                        *pad_edge,
                    );
                    ids[*out as usize] = Some(produced);
                }
                GraphNode::ConvInt8 {
                    input,
                    out,
                    weight,
                    scale,
                    bias,
                    kernel,
                    stride,
                    dilation,
                    pads,
                    group,
                    act,
                    quant,
                } => {
                    let activation = act_of(*act)?;
                    let a = id(&ids, *input)?;
                    let out_shape =
                        self.computed.get(*out as usize).copied().ok_or_else(|| {
                            format!("computed tensor {out} out of range")
                        })?;
                    let produced = builder.conv_int8_raw(
                        a,
                        word_offset(table, *weight)?,
                        offset(table, *scale)?,
                        offset(table, *bias)?,
                        out_shape[0],
                        activation,
                        *kernel,
                        *stride,
                        *dilation,
                        *pads,
                        *group,
                        if *quant == 0 { crate::nets::Quant::I8 } else { crate::nets::Quant::I4 },
                    );
                    ids[*out as usize] = Some(produced);
                }
                GraphNode::Add { a, b, out } => {
                    let produced = builder.add(id(&ids, *a)?, id(&ids, *b)?);
                    ids[*out as usize] = Some(produced);
                }
                GraphNode::AddBroadcast { a, b, out } => {
                    let produced = builder.add_channel(id(&ids, *a)?, id(&ids, *b)?);
                    ids[*out as usize] = Some(produced);
                }
                GraphNode::LayerNorm { input, out, gamma, beta, epsilon_bits } => {
                    let produced = builder.layer_norm_raw(
                        id(&ids, *input)?,
                        offset(table, *gamma)?,
                        offset(table, *beta)?,
                        f32::from_bits(*epsilon_bits),
                    );
                    ids[*out as usize] = Some(produced);
                }
                GraphNode::GlobalAvgPool { input, out } => {
                    let from = id(&ids, *input)?;
                    // `Builder::global_avg_pool` derives the output shape from the
                    // input: the section states it, lowering recomputes it, and the
                    // validator already required the two to agree.
                    let produced = builder.global_avg_pool(from);
                    let want = self.computed.get(*out as usize).copied().ok_or_else(|| {
                        format!("computed tensor {out} out of range")
                    })?;
                    let got = builder.shape(produced);
                    if (got.c, got.h, got.w) != (want[0], want[1], want[2]) {
                        return Err(format!(
                            "global pool lowered to {got:?}, section says {want:?}"
                        ));
                    }
                    ids[*out as usize] = Some(produced);
                }
                GraphNode::Reshape { input, out } => {
                    let from = id(&ids, *input)?;
                    let want = self.computed.get(*out as usize).copied().ok_or_else(|| {
                        format!("computed tensor {out} out of range")
                    })?;
                    let produced = builder.reshaped(
                        from,
                        crate::nets::Shape::new(want[0], want[1], want[2]),
                    );
                    ids[*out as usize] = Some(produced);
                }
            }
        }
        // Host tensors named, so `finish`'s every-tensor rule holds over the union.
        for (index, rank, dims) in &self.host {
            table
                .shaped(*index as usize, &dims[..*rank as usize])
                .map_err(|e| format!("host tensor {index}: {e}"))?;
            builder.host_tensor(*index as usize, &dims[..*rank as usize]);
        }
        self.outputs
            .iter()
            .map(|index| {
                id(&ids, *index).map_err(|_| format!("output names computed tensor {index} unwritten"))
            })
            .collect()
    }

    /// Serialise a recorded forward pass to section bytes, for the converter.
    ///
    /// The inverse of [`Graph::lower`]: walks the fused [`Recorded`] nodes and writes
    /// one section entry per node, with weight *file indices* recovered from the
    /// builder's read flags. Only the phase-1 kinds serialise — anything else is an
    /// error naming the node, which is how a net that outgrew the section refuses to
    /// emit rather than emitting a partial graph.
    ///
    /// This is the emitter half of the equivalence loop: `maml_convert.py` will run it
    /// (via a host harness) over each net and compare against its own emission. The
    /// byte layout matches [`Graph::parse`] field for field; the two are tested by
    /// round-trip (`emit` then `parse` then `lower` then `finish`).
    ///
    /// `outputs` are the plan's output ids — `Recorded` carries inputs and pins but
    /// not which pins are outputs, and the section must name them.
    pub fn emit(
        recorded: &crate::nets::Recorded,
        table: &Offsets,
        outputs: &[crate::nets::Id],
    ) -> Result<Vec<u8>, String> {
        use crate::nets::{Act, Node, Quant};
        let mut bytes = Vec::new();
        let mut nodes_bytes: Vec<u8> = Vec::new();
        let u32s = |bytes: &mut Vec<u8>, values: &[u32]| {
            for v in values {
                bytes.extend_from_slice(&v.to_le_bytes());
            }
        };
        // Computed-index assignment: every tensor id in first-use order — inputs in
        // declaration order (they are pinned first), then each node's output as it
        // appears. The section's computed table is this order; node refs are positions
        // in it.
        //
        // A plain helper, not a closure: the emission match below borrows `order`
        // mutably per node while `file_index` borrows `table` immutably, and a
        // `FnMut` closure holding `&mut order` will not share the scope with those.
        fn position(order: &mut Vec<usize>, id: usize) -> u32 {
            match order.iter().position(|&i| i == id) {
                Some(at) => at as u32,
                None => {
                    order.push(id);
                    (order.len() - 1) as u32
                }
            }
        }
        let mut order: Vec<usize> = Vec::new();
        // Inputs first, in declaration order.
        for id in &recorded.inputs {
            position(&mut order, id.0);
        }
        // Weight file index for a resolved offset: invert through the table by byte
        // offset. Element and word views address the same bytes — an fp16 element
        // offset `e` is byte `2e`, a word offset `w` is byte `4w` — so normalise to
        // bytes before comparing. Byte offsets are unique per tensor (blobs never
        // alias), so the first match is the tensor. Unresolvable is an emitter bug.
        //
        // The `Shapes` test stub answers every tensor at its own index in both
        // views, so several stub tensors can share one byte offset. Disambiguate by
        // element count: the caller passes the length the node implies (kernel
        // elements, bias channels), and the match must agree on it. The real table
        // never collides at all; the stub is test-only, and a length mismatch there
        // is still an emitter error rather than a guess.
        let file_index =
            |offset: u32, is_word: bool, len: u32, kind: &str| -> Result<u32, String> {
                let bytes = if is_word { offset * 4 } else { offset * 2 };
                table
                    .tensors
                    .iter()
                    .position(|t| t.offset == bytes && t.len == len)
                    .map(|i| i as u32)
                    .ok_or_else(|| {
                        format!("emitter: {kind} at offset {offset} names no tensor")
                    })
            };
        let act_code = |act: Act| -> Result<u32, String> {
            match act {
                Act::None => Ok(0),
                Act::Relu => Ok(1),
                Act::HardSwish => Ok(2),
                Act::Sigmoid => Ok(3),
                Act::PRelu(_) => Err("emitter: a PReLU, which phase 1 does not serialise".into()),
                Act::Clip01 => Ok(5),
                Act::Swish => Ok(6),
                Act::Gelu => Ok(8),
            }
        };
        let mut nodes_bytes = Vec::new();
        for (i, node) in recorded.nodes.iter().enumerate() {
            match node {
                Node::Conv {
                    input,
                    out,
                    weight,
                    bias,
                    kernel,
                    stride,
                    dilation,
                    pad,
                    group,
                    act,
                    act_weight,
                    transpose,
                    pad_edge,
                    res,
                    shift,
                } => {
                    if *transpose {
                        return Err(format!("emitter: node {i} is transposed, not in phase 1"));
                    }
                    if res.is_some() || shift.is_some() {
                        return Err(format!(
                            "emitter: node {i} carries a fused addend; emit before fusion"
                        ));
                    }
                    let shape = recorded.shapes.get(out.0).copied().ok_or_else(|| {
                        format!("emitter: node {i} output {} has no shape", out.0)
                    })?;
                    nodes_bytes.push(TAG_CONV);
                    let input_at = position(&mut order, input.0);
                    let out_at = position(&mut order, out.0);
                    let in_shape =
                        recorded.shapes.get(input.0).copied().ok_or_else(|| {
                            format!("emitter: node {i} input {} has no shape", input.0)
                        })?;
                    let per_group = in_shape.c / group.max(&1);
                    let w = file_index(
                        *weight,
                        false,
                        shape.c * per_group * kernel.0 * kernel.1,
                        "kernel",
                    )?;
                    let b = file_index(*bias, false, shape.c, "bias")?;
                    let slope = match act {
                        Act::PRelu(_) => {
                            return Err(format!("emitter: node {i} is a PReLU"));
                        }
                        _ => {
                            if *act_weight != 0 {
                                return Err(format!(
                                    "emitter: node {i} carries a slope without a PReLU"
                                ));
                            }
                            NO_TENSOR
                        }
                    };
                    u32s(
                        &mut nodes_bytes,
                        &[
                            input_at,
                            out_at,
                            w,
                            b,
                            kernel.0,
                            kernel.1,
                            stride.0,
                            stride.1,
                            dilation.0,
                            dilation.1,
                            pad.0,
                            pad.1,
                            0,
                            0,
                            *group,
                            act_code(*act)?,
                            slope,
                            u32::from(*pad_edge),
                        ],
                    );
                }
                Node::ConvInt8 {
                    input,
                    out,
                    weight,
                    scale,
                    bias,
                    kernel,
                    stride,
                    dilation,
                    pad,
                    group,
                    act,
                    quant,
                    res,
                    shift,
                } => {
                    if res.is_some() || shift.is_some() {
                        return Err(format!(
                            "emitter: node {i} carries a fused addend; emit before fusion"
                        ));
                    }
                    if matches!(act, Act::PRelu(_)) {
                        return Err(format!("emitter: node {i} is a PReLU"));
                    }
                    let shape = recorded.shapes.get(out.0).copied().ok_or_else(|| {
                        format!("emitter: node {i} output {} has no shape", out.0)
                    })?;
                    nodes_bytes.push(TAG_CONV_INT8);
                    let input_at = position(&mut order, input.0);
                    let out_at = position(&mut order, out.0);
                    // Quantised kernels are word-addressed: flag the lookup so it
                    // normalises to bytes before comparing. Lengths are element
                    // counts, as the table records them.
                    let in_shape =
                        recorded.shapes.get(input.0).copied().ok_or_else(|| {
                            format!("emitter: node {i} input {} has no shape", input.0)
                        })?;
                    let per_group = in_shape.c / group.max(&1);
                    let w = file_index(
                        *weight,
                        true,
                        shape.c * per_group * kernel.0 * kernel.1,
                        "kernel-int",
                    )?;
                    let s = file_index(*scale, false, shape.c, "scale")?;
                    let b = file_index(*bias, false, shape.c, "bias")?;
                    u32s(
                        &mut nodes_bytes,
                        &[
                            input_at,
                            out_at,
                            w,
                            s,
                            b,
                            kernel.0,
                            kernel.1,
                            stride.0,
                            stride.1,
                            dilation.0,
                            dilation.1,
                            pad.0,
                            pad.1,
                            0,
                            0,
                            *group,
                            act_code(*act)?,
                            match quant {
                                Quant::I8 => 0,
                                Quant::I4 => 1,
                            },
                        ],
                    );
                }
                Node::Binary { kind, a, b, out } => {
                    use crate::nets::Kind;
                    match kind {
                        Kind::Add => nodes_bytes.push(TAG_ADD),
                        Kind::AddBroadcast => nodes_bytes.push(TAG_ADD_BROADCAST),
                        other => {
                            return Err(format!(
                                "emitter: node {i} is {other:?}, not in phase 1"
                            ));
                        }
                    }
                    let a_at = position(&mut order, a.0);
                    let b_at = position(&mut order, b.0);
                    let out_at = position(&mut order, out.0);
                    u32s(&mut nodes_bytes, &[a_at, b_at, out_at]);
                }
                Node::LayerNorm { input, out, gamma, beta, epsilon } => {
                    nodes_bytes.push(TAG_LAYER_NORM);
                    let input_at = position(&mut order, input.0);
                    let out_at = position(&mut order, out.0);
                    let shape =
                        recorded.shapes.get(out.0).copied().ok_or_else(|| {
                            format!("emitter: node {i} output {} has no shape", out.0)
                        })?;
                    let g = file_index(*gamma, false, shape.c, "gamma")?;
                    let be = file_index(*beta, false, shape.c, "beta")?;
                    u32s(
                        &mut nodes_bytes,
                        &[input_at, out_at, g, be, epsilon.to_bits()],
                    );
                }
                Node::GlobalAvgPool { input, out } => {
                    nodes_bytes.push(TAG_GLOBAL_AVG_POOL);
                    let input_at = position(&mut order, input.0);
                    let out_at = position(&mut order, out.0);
                    u32s(&mut nodes_bytes, &[input_at, out_at]);
                }
                Node::Concat { parts, out } if parts.len() == 1 => {
                    // `reshaped`: the single-part form is a relabelling, lowered as
                    // one copy. Multi-part joins are real concatenations, not views.
                    nodes_bytes.push(TAG_RESHAPE);
                    let input_at = position(&mut order, parts[0].0);
                    let out_at = position(&mut order, out.0);
                    u32s(&mut nodes_bytes, &[input_at, out_at]);
                }
                other => {
                    return Err(format!("emitter: node {i} is {other:?}, not in phase 1"));
                }
            }
        }
        // Header: node count, then payloads. (`bytes` is empty until here: node
        // payloads accumulate in `nodes_bytes` first, so the count leads.)
        bytes.extend_from_slice(&(recorded.nodes.len() as u32).to_le_bytes());
        bytes.extend_from_slice(&nodes_bytes);
        // Computed table in assignment order.
        bytes.extend_from_slice(&(order.len() as u32).to_le_bytes());
        for id in &order {
            let shape = recorded.shapes.get(*id).copied().ok_or_else(|| {
                format!("emitter: tensor {id} has no shape")
            })?;
            u32s(&mut bytes, &[shape.c, shape.h, shape.w]);
        }
        // Bindings: inputs and outputs as computed positions.
        let pos = |order: &[usize], id: usize| -> Result<u32, String> {
            order
                .iter()
                .position(|&i| i == id)
                .map(|at| at as u32)
                .ok_or_else(|| format!("emitter: binding {id} was never assigned"))
        };
        bytes.extend_from_slice(&(recorded.inputs.len() as u32).to_le_bytes());
        for id in &recorded.inputs {
            bytes.extend_from_slice(&pos(&order, id.0)?.to_le_bytes());
        }
        bytes.extend_from_slice(&(outputs.len() as u32).to_le_bytes());
        for id in outputs {
            bytes.extend_from_slice(&pos(&order, id.0)?.to_le_bytes());
        }
        // Host tensors: every read file tensor that no node consumed as a weight.
        // `Recorded.read` marks every file tensor the pass touched; node emission
        // above consumed the weights. The remainder are host-side by elimination —
        // and that is exactly `Builder::host_tensor`'s contract from the other side:
        // finish refuses an unread tensor, so every read tensor is either a weight
        // above or named here.
        //
        // Re-derived from the section bytes just written rather than threaded through
        // the emission match: every weight ref sits at a known payload slot per kind
        // tag (slots 2..4 — see the match above), so one walk over `nodes_bytes`
        // collects the used set without a second channel.
        let mut used = vec![false; table.len()];
        {
            let mut at = 0usize;
            for _ in &recorded.nodes {
                let tag = nodes_bytes.get(at).copied().unwrap_or(255);
                at += 1;
                let base = at;
                let slots: &[usize] = match tag {
                    TAG_CONV => &[2, 3],
                    TAG_CONV_INT8 => &[2, 3, 4],
                    TAG_LAYER_NORM => &[2, 3],
                    _ => &[],
                };
                for slot in slots {
                    let o = base + slot * 4;
                    if let Some(field) = nodes_bytes.get(o..o + 4) {
                        let index = u32_of(field) as usize;
                        if let Some(seen) = used.get_mut(index) {
                            *seen = true;
                        }
                    }
                }
                at += match tag {
                    TAG_CONV | TAG_CONV_INT8 => 18 * 4,
                    TAG_ADD | TAG_ADD_BROADCAST => 3 * 4,
                    TAG_LAYER_NORM => 5 * 4,
                    TAG_GLOBAL_AVG_POOL | TAG_RESHAPE => 2 * 4,
                    _ => 0,
                };
            }
        }
        let mut host: Vec<(u32, u32, [u32; 4])> = Vec::new();
        for (index, was_read) in recorded.read.iter().enumerate() {
            if *was_read && !used.get(index).copied().unwrap_or(true) {
                let found = table.tensors.get(index).ok_or_else(|| {
                    format!("emitter: read flag {index} past the table")
                })?;
                let mut dims = [0u32; 4];
                dims[..found.rank as usize].copy_from_slice(&found.dims[..found.rank as usize]);
                host.push((index as u32, found.rank, dims));
            }
        }
        bytes.extend_from_slice(&(host.len() as u32).to_le_bytes());
        for (index, rank, dims) in &host {
            bytes.extend_from_slice(&index.to_le_bytes());
            bytes.extend_from_slice(&rank.to_le_bytes());
            for d in dims {
                bytes.extend_from_slice(&d.to_le_bytes());
            }
        }
        Ok(bytes)
    }
}

/// `floor((in + pad - dilation * (k - 1) - 1) / stride) + 1`, ONNX's convolution output
/// size. Deliberately the same formula as `nets::conv_out` rather than a shared helper:
/// the validator and the builder must agree, and sharing the function would make a change
/// to one silently change the other's checks. Duplicated on purpose, tested by agreement
/// (see the round-trip tests).
fn conv_out_shape(input: u32, kernel: u32, stride: u32, dilation: u32, pad_total: u32) -> u32 {
    let effective = dilation * (kernel - 1) + 1;
    let padded = input + pad_total;
    if padded < effective || stride == 0 {
        return 0;
    }
    (padded - effective) / stride + 1
}

fn u32_of(bytes: &[u8]) -> u32 {
    u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]])
}

/// A cursor over the section bytes, refusing overruns with the field at fault.
struct Cursor<'a> {
    bytes: &'a [u8],
}

impl Cursor<'_> {
    fn take(&mut self, n: usize, what: &str) -> Result<Vec<u8>, String> {
        if self.bytes.len() < n {
            return Err(format!("graph section ends in {what}"));
        }
        let (head, tail) = self.bytes.split_at(n);
        self.bytes = tail;
        Ok(head.to_vec())
    }

    fn u8(&mut self, what: &str) -> Result<u8, String> {
        Ok(self.take(1, what)?[0])
    }

    fn u32(&mut self, what: &str) -> Result<u32, String> {
        let f = self.take(4, what)?;
        Ok(u32_of(&f))
    }

    fn indices(&mut self, what: &str) -> Result<Vec<u32>, String> {
        let count = self.u32(&format!("{what} count"))? as usize;
        if count > MAX_GRAPH_TENSORS {
            return Err(format!("graph section claims {count} {what}"));
        }
        let mut out = Vec::with_capacity(count.min(64));
        for i in 0..count {
            out.push(self.u32(&format!("{what} {i}"))?);
        }
        Ok(out)
    }

    fn rest(&self) -> &[u8] {
        self.bytes
    }
}

#[cfg(test)]
mod graph_tests {
    use super::*;

    /// A minimal section: one fp16 conv over a 2x2 input, then an output binding.
    fn one_conv_section() -> Vec<u8> {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&1u32.to_le_bytes());
        bytes.push(TAG_CONV);
        // input 0, out 1, weight 0, bias 1, 1x1, stride 1, dilation 1, no pads,
        // group 1, act none, no slope, pad_edge 0: 18 u32s = 72 bytes, matching
        // the parser's `take(72)` and the emitter's `18 * 4` stride.
        for v in [0u32, 1, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 0] {
            bytes.extend_from_slice(&v.to_le_bytes());
        }
        bytes.extend_from_slice(&NO_TENSOR.to_le_bytes());
        bytes.extend_from_slice(&0u32.to_le_bytes());
        // computed: [2,1,2] in, [2,1,2] out.
        bytes.extend_from_slice(&2u32.to_le_bytes());
        for shape in [[2u32, 1, 2], [2, 1, 2]] {
            for v in shape {
                bytes.extend_from_slice(&v.to_le_bytes());
            }
        }
        // inputs [0], outputs [1], no hosts.
        bytes.extend_from_slice(&1u32.to_le_bytes());
        bytes.extend_from_slice(&0u32.to_le_bytes());
        bytes.extend_from_slice(&1u32.to_le_bytes());
        bytes.extend_from_slice(&1u32.to_le_bytes());
        bytes.extend_from_slice(&0u32.to_le_bytes());
        bytes
    }

    fn one_conv_table() -> Vec<Tensor> {
        vec![
            Tensor { rank: 4, dims: [2, 2, 1, 1], offset: 0, len: 4, dtype: Dtype::F16 },
            Tensor { rank: 1, dims: [2, 0, 0, 0], offset: 16, len: 2, dtype: Dtype::F16 },
        ]
    }

    #[test]
    fn a_minimal_section_parses_and_validates() {
        let graph = Graph::parse(&one_conv_section(), &one_conv_table()).expect("parses");
        assert_eq!(graph.nodes.len(), 1);
        assert_eq!(graph.inputs, vec![0]);
        assert_eq!(graph.outputs, vec![1]);
    }

    #[test]
    fn an_unknown_kind_tag_is_refused() {
        let mut bytes = one_conv_section();
        bytes[4] = 9;
        let error = Graph::parse(&bytes, &one_conv_table()).expect_err("bad tag");
        assert!(error.contains("kind tag 9"), "{error}");
    }

    #[test]
    fn a_weight_ref_past_the_table_is_refused() {
        let mut bytes = one_conv_section();
        // weight file index lives at payload offset 8.
        bytes[5 + 8..5 + 12].copy_from_slice(&7u32.to_le_bytes());
        let error = Graph::parse(&bytes, &one_conv_table()).expect_err("bad weight");
        assert!(error.contains("file tensor 7"), "{error}");
    }

    #[test]
    fn a_shape_mismatch_is_refused() {
        // Bias table entry claims [3] but the node needs [2].
        let mut table = one_conv_table();
        table[1].dims = [3, 0, 0, 0];
        table[1].len = 3;
        let error = Graph::parse(&one_conv_section(), &table).expect_err("bad bias");
        assert!(error.contains("as [2]"), "{error}");
    }

    #[test]
    fn trailing_section_bytes_are_refused() {
        let mut bytes = one_conv_section();
        bytes.push(0);
        let error = Graph::parse(&bytes, &one_conv_table()).expect_err("trailing");
        assert!(error.contains("trailing"), "{error}");
    }

    #[test]
    fn an_empty_section_is_refused() {
        let error = Graph::parse(&[], &one_conv_table()).expect_err("empty");
        assert!(error.contains("node count"), "{error}");
    }

    #[test]
    fn a_version_two_file_without_a_section_parses_as_version_one() {
        // Bytes 56..64 are the graph offset/len pair; zero length is absent even at
        // version 2, so a v2 file with no emitted graph behaves exactly like v1.
        // One fp16 tensor [4]: rank 1, dims [4,0,0,0], offset 0, len 4.
        let mut bytes = vec![0u8; 64 + 32];
        bytes[0..4].copy_from_slice(&MAGIC);
        bytes[4..8].copy_from_slice(&2u32.to_le_bytes());
        bytes[8..12].copy_from_slice(&graph::U2NETP.to_le_bytes());
        bytes[12..16].copy_from_slice(&1u32.to_le_bytes());
        bytes[48..52].copy_from_slice(&96u32.to_le_bytes());
        bytes[52..56].copy_from_slice(&8u32.to_le_bytes());
        bytes[64..68].copy_from_slice(&1u32.to_le_bytes());
        bytes[68..72].copy_from_slice(&4u32.to_le_bytes());
        bytes[84..88].copy_from_slice(&DTYPE_F16.to_le_bytes());
        bytes[88..92].copy_from_slice(&0u32.to_le_bytes());
        bytes[92..96].copy_from_slice(&4u32.to_le_bytes());
        for v in [1.0f32, 2.0, 3.0, 4.0] {
            bytes.extend_from_slice(&crate::preprocess::f32_to_f16(v).to_le_bytes());
        }
        let weights = Weights::parse(&bytes, graph::U2NETP).expect("a section-less v2 parses");
        assert!(weights.graph_section().is_none());
    }

    #[test]
    fn a_version_one_file_naming_a_section_is_refused() {
        let mut bytes = vec![0u8; 64 + 32 + 8];
        bytes[0..4].copy_from_slice(&MAGIC);
        bytes[4..8].copy_from_slice(&1u32.to_le_bytes());
        bytes[8..12].copy_from_slice(&graph::U2NETP.to_le_bytes());
        bytes[12..16].copy_from_slice(&1u32.to_le_bytes());
        bytes[48..52].copy_from_slice(&96u32.to_le_bytes());
        bytes[52..56].copy_from_slice(&8u32.to_le_bytes());
        bytes[56..60].copy_from_slice(&104u32.to_le_bytes());
        bytes[60..64].copy_from_slice(&16u32.to_le_bytes());
        let error = Weights::parse(&bytes, graph::U2NETP).expect_err("v1 with section");
        assert!(error.contains("version-1"), "{error}");
    }

    #[test]
    fn a_future_version_is_refused_loudly() {
        let mut bytes = vec![0u8; 64 + 32 + 8];
        bytes[0..4].copy_from_slice(&MAGIC);
        bytes[4..8].copy_from_slice(&9u32.to_le_bytes());
        let error = Weights::parse(&bytes, graph::U2NETP).expect_err("version 9");
        assert!(error.contains("format version 9"), "{error}");
    }

    #[test]
    fn a_section_past_the_blob_parses_with_the_file() {
        // A full v2 file: header + table + blob + section, with the header's
        // reserved pair naming the section. Tensor 0 at blob offset 0 ([2,2,1,1]),
        // tensor 1 at 16 ([2]) — matching `one_conv_table`.
        let section = one_conv_section();
        let blob_len = 16 + 4;
        let mut bytes = vec![0u8; 64 + 64 + blob_len];
        bytes[0..4].copy_from_slice(&MAGIC);
        bytes[4..8].copy_from_slice(&2u32.to_le_bytes());
        bytes[8..12].copy_from_slice(&graph::U2NETP.to_le_bytes());
        bytes[12..16].copy_from_slice(&2u32.to_le_bytes());
        bytes[48..52].copy_from_slice(&128u32.to_le_bytes());
        bytes[52..56].copy_from_slice(&(blob_len as u32).to_le_bytes());
        bytes[56..60].copy_from_slice(&(128 + blob_len as u32).to_le_bytes());
        bytes[60..64].copy_from_slice(&(section.len() as u32).to_le_bytes());
        // table entries
        bytes[64..68].copy_from_slice(&4u32.to_le_bytes());
        for (o, d) in [2u32, 2, 1, 1].iter().enumerate() {
            bytes[68 + o * 4..72 + o * 4].copy_from_slice(&d.to_le_bytes());
        }
        bytes[84..88].copy_from_slice(&DTYPE_F16.to_le_bytes());
        bytes[88..92].copy_from_slice(&0u32.to_le_bytes());
        bytes[92..96].copy_from_slice(&4u32.to_le_bytes());
        bytes[96..100].copy_from_slice(&1u32.to_le_bytes());
        bytes[100..104].copy_from_slice(&2u32.to_le_bytes());
        bytes[116..120].copy_from_slice(&DTYPE_F16.to_le_bytes());
        bytes[120..124].copy_from_slice(&16u32.to_le_bytes());
        bytes[124..128].copy_from_slice(&2u32.to_le_bytes());
        // blob payload: 4 fp16 then pad to 16 then 2 fp16.
        let mut blob = vec![0u8; blob_len];
        for (i, v) in [1.0f32, 1.0, 1.0, 1.0, 0.5, 0.5].iter().enumerate() {
            let at = if i < 4 { i * 2 } else { 16 + (i - 4) * 2 };
            blob[at..at + 2].copy_from_slice(&crate::preprocess::f32_to_f16(*v).to_le_bytes());
        }
        bytes[128..128 + blob_len].copy_from_slice(&blob);
        bytes.extend_from_slice(&section);
        let weights = Weights::parse(&bytes, graph::U2NETP).expect("a v2 file parses");
        let graph = weights.graph_section().expect("a section");
        assert_eq!(graph.nodes.len(), 1);
    }

    #[test]
    fn a_misplaced_section_is_refused() {
        // One fp16 tensor [4]: rank 1, dims [4,0,0,0], blob 8 bytes at 96..104,
        // so naming the section at 100 points inside it — weights aliased as
        // topology.
        let mut bytes = vec![0u8; 64 + 32 + 8];
        bytes[0..4].copy_from_slice(&MAGIC);
        bytes[4..8].copy_from_slice(&2u32.to_le_bytes());
        bytes[8..12].copy_from_slice(&graph::U2NETP.to_le_bytes());
        bytes[12..16].copy_from_slice(&1u32.to_le_bytes());
        bytes[48..52].copy_from_slice(&96u32.to_le_bytes());
        bytes[52..56].copy_from_slice(&8u32.to_le_bytes());
        bytes[56..60].copy_from_slice(&100u32.to_le_bytes());
        bytes[60..64].copy_from_slice(&16u32.to_le_bytes());
        bytes[64..68].copy_from_slice(&1u32.to_le_bytes());
        bytes[68..72].copy_from_slice(&4u32.to_le_bytes());
        bytes[84..88].copy_from_slice(&DTYPE_F16.to_le_bytes());
        bytes[88..92].copy_from_slice(&0u32.to_le_bytes());
        bytes[92..96].copy_from_slice(&4u32.to_le_bytes());
        let error = Weights::parse(&bytes, graph::U2NETP).expect_err("misplaced");
        assert!(error.contains("graph section at 100"), "{error}");
    }
}

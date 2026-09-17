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

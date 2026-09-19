// Host-side embedding gathers for Gemma 4.
//
// Part of the gemma4 module: the `embed` table indices plus
// `gather`/`gather_soft`/`combine`, which read the EMBED file on the host
// because a pass reads one weights file, not two. Split out of `gemma4.rs`
// so each file stays under the 500-line Rust limit
// (`scripts/lint_rust_length.sh`).

/// The embedding tables, which live in their own `.maml`. See [`crate::weights::graph`].
///
/// Two tensors, each a `(kernel, scale, bias)` triple as everything quantised here is. Both are
/// read a row at a time by [`crate::weights::Reader::int4_row`] and neither is bound to a shader:
/// a decode step needs 1536 values from a 1.2 GB table.
pub mod embed {
    /// Working token table, int4 `[VOCAB, D_MODEL]` (converted from litertlm's
    /// 2-bit rows). The gather source only - the tied head reads HEAD_TABLE.
    pub const TOKENS: usize = 0;

    /// Raw-scale head table: S10's `embedder.decode` composite (t2698),
    /// stored fp16 (the 2-bit source only resolves 0.97 under int4 requant).
    /// The table the reference scores its final hidden against; the tied head
    /// reads this, not the working table (see [`super::EMBED_GAIN`]). LAST
    /// tensor in the file, so the per-layer tables keep their entries.
    pub const HEAD_TABLE: usize = 7 + super::LAYERS * 3;

    /// Shared per-layer projection, int8 `[LAYERS * PER_LAYER, D_MODEL]`.
    pub const SHARED_PROJ: usize = 3;

    /// Norm over one layer's slice of the shared projection.
    pub const SHARED_NORM: usize = 6;

    /// First per-layer mmap table. Table `i` lives at `TABLES + i * 3`
    /// (int4 triple), each int4 `[VOCAB, PER_LAYER]` in U8-typed storage.
    /// NOTE: the per-layer tables live at file entries 7+i*3 in BOTH the old
    /// (112-tensor) and new (113-tensor) embed files: the head table was
    /// appended at the END of the file, not after the working table, so all
    /// existing indices keep working and old files stay readable.
    pub const TABLES: usize = 7;

    /// Tensors the embedding `.maml` holds: working triple + projection
    /// triple + norm + 35 triples + head fp16. The head table is LAST so the
    /// per-layer tables keep their file entries (see TABLES).
    pub const TENSORS: usize = 7 + super::LAYERS * 3 + 1;

    /// Raw-scale head table (see HEAD_TABLE): the last tensor in the file.
    pub const HEAD_TENSOR: usize = 7 + super::LAYERS * 3;

    /// Table triple for layer `i`.
    pub const fn table(i: usize) -> usize {
        TABLES + i * 3
    }

    /// Ids the export maps to row 0 of the per-layer table before gathering.
    ///
    /// The image and audio placeholders. They have no per-layer input of their own - their
    /// embedding comes from the vision or audio tower - and the export masks them with a
    /// `Where` rather than letting them index the table. A gather that skipped this would read a
    /// real row for a placeholder and quietly perturb every layer.
    pub const PLACEHOLDERS: [u32; 2] = [258_880, 258_881];
}

/// One token's embedding and per-layer inputs, gathered on the host.
///
/// Returns `(inputs_embeds, per_layer_inputs)`, ready for [`build`]'s first two inputs.
/// `inputs_embeds` is the working-table row; `per_layer_inputs` is the COMBINED
/// block the reference's `maybe_preprocess_per_layer_embeddings` produces:
///
///     projected = hidden @ SHARED_PROJ * 1/sqrt(d_model)
///     combined  = (embedded + rms_norm(projected)) * 1/sqrt(2)
///
/// `embedded` concatenates one 256-wide row from each layer's own mmap table
/// (litertlm gathers the same way: 35 parallel lookups + concat).
/// The combination lives here rather than in [`build`] because the projection
/// lives in the EMBED file and a pass reads one weights file, not two.
pub fn gather(
    embed: &crate::weights::Reader<'_>,
    token: u32,
) -> Result<(Vec<f32>, Vec<f32>), String> {
    if token >= VOCAB {
        return Err(format!("token {token} is past the {}-entry vocabulary", VOCAB));
    }
    let hidden = embed.int4_row(embed::TOKENS, embed::TOKENS + 1, &[VOCAB, D_MODEL], token)?;
    // The placeholders have no per-layer row of their own; the export masks them to 0.
    let per_layer_row = if embed::PLACEHOLDERS.contains(&token) { 0 } else { token };
    let mut embedded = Vec::with_capacity((PER_LAYER as usize) * LAYERS);
    for i in 0..LAYERS {
        let at = embed::table(i);
        embedded.extend(embed.int4_row(at, at + 1, &[VOCAB, PER_LAYER], per_layer_row)?);
    }
    Ok((hidden.clone(), combine(embed, &hidden, &embedded)?))
}

/// The per-layer combination for an EXPLICIT hidden state (see [`gather`]).
///
/// For soft tokens: the hidden state is an encoder's output, not a table row,
/// while the table rows still come from `token`. The reference rewrites a
/// placeholder id to `pad_token_id` (0) before gathering and then overwrites
/// only the hidden state - so both halves come from row 0, and that is what
/// the caller passes here.
pub fn gather_soft(
    embed: &crate::weights::Reader<'_>,
    hidden: &[f32],
    token: u32,
) -> Result<Vec<f32>, String> {
    if hidden.len() != D_MODEL as usize {
        return Err(format!(
            "a soft hidden state of {} values, not {}",
            hidden.len(),
            D_MODEL
        ));
    }
    let mut embedded = Vec::with_capacity((PER_LAYER as usize) * LAYERS);
    for i in 0..LAYERS {
        let at = embed::table(i);
        embedded.extend(embed.int4_row(at, at + 1, &[VOCAB, PER_LAYER], token)?);
    }
    combine(embed, hidden, &embedded)
}

/// `combine` proper: project, grouped-norm, add, halve the variance.
fn combine(
    embed: &crate::weights::Reader<'_>,
    hidden: &[f32],
    embedded: &[f32],
) -> Result<Vec<f32>, String> {
    let proj = embed.int8_all(
        embed::SHARED_PROJ,
        embed::SHARED_PROJ + 1,
        &[LAYERS as u32 * PER_LAYER, D_MODEL, 1, 1],
    )?;
    let gamma = embed.fp16(embed::SHARED_NORM, &[PER_LAYER])?;
    let rows = LAYERS;
    let wide = PER_LAYER as usize;
    let inv_sqrt_d = 1.0 / (f32::from(D_MODEL as u16)).sqrt();
    let inv_sqrt_2 = 1.0 / std::f32::consts::SQRT_2;
    let mut out = Vec::with_capacity(rows * wide);
    for r in 0..rows {
        let row = &proj[r * wide * D_MODEL as usize..(r + 1) * wide * D_MODEL as usize];
        let emb = &embedded[r * wide..(r + 1) * wide];
        // projected = W @ hidden / sqrt(d_model), one 256-wide group.
        let mut group = vec![0f32; wide];
        for (o, wrow) in group.iter_mut().zip(row.chunks_exact(D_MODEL as usize)) {
            *o = wrow.iter().zip(hidden.iter()).map(|(a, b)| a * b).sum::<f32>() * inv_sqrt_d;
        }
        // Grouped RMS norm with the shared 256-wide gamma.
        let mean_sq = group.iter().map(|v| v * v).sum::<f32>() / wide as f32;
        let norm = 1.0 / (mean_sq + EPSILON).sqrt();
        for (v, &g) in group.iter_mut().zip(gamma.iter()) {
            *v = *v * norm * g;
        }
        // combined = (embedded + normed) / sqrt(2).
        for (v, &e) in group.iter_mut().zip(emb.iter()) {
            *v = (*v + e) * inv_sqrt_2;
        }
        out.extend_from_slice(&group);
    }
    Ok(out)
}

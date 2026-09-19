package com.vayunmathur.library.downloadservice

/**
 * Centralized model URL config for supply-chain mitigation #1.
 * Models are served ONLY from the self-hosted mirror (no HuggingFace fallback).
 * The server must host each file under https://data.vayunmathur.com/models/
 * and set Content-Length + ETag.
 *
 * # Gemma 4 is five files, not one
 *
 * The single `.litertlm` was replaced by this repo's own `.maml` format when `:openassistant`
 * moved off `com.google.ai.edge.litertlm` onto `:library:ml`. Several files rather than one because
 * they have different lifetimes and different readers:
 *
 *   * the decoder, which the GPU binds,
 *   * the embedding, which only the host reads a row at a time and which is therefore its own
 *     graph rather than tensors inside the decoder,
 *   * the tokenizer table, which is tiny and is read once into memory,
 *   * the vision tower and the audio tower, each of which is a separate encoder that produces
 *     soft tokens in the decoder's embedding space.
 *
 * The first three must be present before the assistant will start - see `Gemma4Handle.FILES`.
 * The two encoders are optional at load: a device that never sends a picture or a clip still
 * answers text.
 *
 * To populate the mirror, produce the files from the ONNX export and upload them:
 *
 *   models/gemma4_text.maml
 *     <- python3 scripts/ml/maml_convert.py decoder_model_merged_fp16.onnx \
 *          --graph gemma4_text -o gemma4_text.maml
 *   models/gemma4_embed.maml
 *     <- python3 scripts/ml/maml_convert.py embed_tokens_fp16.onnx \
 *          --graph gemma4_embed -o gemma4_embed.maml
 *   models/gemma4_tokenizer.spm1
 *     <- python3 scripts/ml/gemma4_tokenizer.py --tokenizer tokenizer.json \
 *          -o gemma4_tokenizer.spm1
 *   models/gemma4_vision.maml
 *     <- python3 scripts/ml/maml_convert.py vision_encoder_fp16.onnx \
 *          --graph gemma4_vision -o gemma4_vision.maml
 *   models/gemma4_audio.maml
 *     <- python3 scripts/ml/maml_convert.py audio_encoder_fp16.onnx \
 *          --graph gemma4_audio -o gemma4_audio.maml
 *
 * The ONNX inputs are `onnx-community/gemma-4-E2B-it-ONNX`, which is Apache-2.0 and ungated.
 */
object ModelUrls {
    const val MIRROR_BASE = "https://data.vayunmathur.com/models/"

    /** The decoder: 35 layers at int4 with per-block scales, converted DIRECTLY
     * from the litertlm GPU bundle (scripts/ml/litertlm_to_maml.py; the ONNX
     * export is a different model). Tied head (no head tensors). */
    const val GEMMA_TEXT_FILE = "gemma4_text.maml"
    const val GEMMA_TEXT_URL = "${MIRROR_BASE}gemma4_text.maml"
    const val GEMMA_TEXT_SHA256 =
        "e6b392c9d72f61d490099531edfaa38ac772f9a3fa7cc24be23f13b329ff3da3"

    /** The two embedding tables, gathered on the host. */
    const val GEMMA_EMBED_FILE = "gemma4_embed.maml"
    const val GEMMA_EMBED_URL = "${MIRROR_BASE}gemma4_embed.maml"
    const val GEMMA_EMBED_SHA256 =
        "ba0171d25feae227fc20f2c6203df7c98b3810c0f814b3be539fb0d3ed5c289f"

    /** The BPE table: 262144 pieces, byte fallback. Built from the SM
     * tokenizer.json (same Gemma 4 tokenizer) by scripts/ml/gemma4_tokenizer.py. */
    const val GEMMA_TOKENIZER_FILE = "gemma4_tokenizer.spm1"
    const val GEMMA_TOKENIZER_URL = "${MIRROR_BASE}gemma4_tokenizer.spm1"
    const val GEMMA_TOKENIZER_SHA256 =
        "520d011da5e54c4319a943b636f77e9b2e3ab1eda95a860e40184300bb1c4900"

    /**
     * The vision tower: 16 layers at int4, with the patch and output projections left at fp16.
     *
     * Optional, and deliberately not in the same download as the decoder. It is 93 MB on top of
     * 2.8 GB, a device that never sends a picture never needs it, and the assistant answers text
     * perfectly well without it - see `Gemma4Engine.canSeeImages`.
     */
    const val GEMMA_VISION_FILE = "gemma4_vision.maml"
    const val GEMMA_VISION_URL = "${MIRROR_BASE}gemma4_vision.maml"
    const val GEMMA_VISION_SHA256 =
        "4c53c0a8383f2ea99f8beed50bd5eac203565436313d0c2dd77b389ea1cf38e3"

    /**
     * The audio tower: a 12-layer conformer at int4, with the three end projections, the two
     * subsampling convolutions, the twelve depthwise convolutions and the twelve
     * relative-position tables all left at fp16.
     *
     * Optional on the same terms as the vision tower. 166 MB, and a device that never sends a
     * clip never needs it.
     */
    const val GEMMA_AUDIO_FILE = "gemma4_audio.maml"
    const val GEMMA_AUDIO_URL = "${MIRROR_BASE}gemma4_audio.maml"
    const val GEMMA_AUDIO_SHA256 =
        "be3ac4f968835fa257291f0845be642750f71e63e197a6e199ad5210316cb4f2"

    val GEMMA_TEXT =
        ModelDownloadItem(GEMMA_TEXT_URL, GEMMA_TEXT_FILE, "Model", GEMMA_TEXT_SHA256)
    val GEMMA_EMBED =
        ModelDownloadItem(GEMMA_EMBED_URL, GEMMA_EMBED_FILE, "Embeddings", GEMMA_EMBED_SHA256)
    val GEMMA_TOKENIZER = ModelDownloadItem(
        GEMMA_TOKENIZER_URL,
        GEMMA_TOKENIZER_FILE,
        "Tokenizer",
        GEMMA_TOKENIZER_SHA256,
    )
    val GEMMA_VISION =
        ModelDownloadItem(GEMMA_VISION_URL, GEMMA_VISION_FILE, "Vision", GEMMA_VISION_SHA256)
    val GEMMA_AUDIO =
        ModelDownloadItem(GEMMA_AUDIO_URL, GEMMA_AUDIO_FILE, "Audio", GEMMA_AUDIO_SHA256)

    /**
     * Everything downloaded on OpenAssistant first launch.
     *
     * Smallest first, so a user on a slow connection sees progress early and so the two large
     * files are the ones a resume is most likely to land in the middle of. The two encoders go
     * after the tokenizer and before the two large ones: together they are 259 MB, they are what
     * make the assistant able to answer about a photograph or a recording, and a resume that
     * stalls in the decoder should not leave them unfetched.
     */
    val INITIAL = listOf(GEMMA_TOKENIZER, GEMMA_VISION, GEMMA_AUDIO, GEMMA_TEXT, GEMMA_EMBED)

    /**
     * Files an earlier build downloaded and this one does not use.
     *
     * Deleted on startup rather than left to rot: the litertlm bundle alone was several
     * gigabytes, and a user upgrading would otherwise carry both copies forever.
     */
    val OBSOLETE = listOf(
        "gemma4-2b.litertlm",
        "gemma-4-E2B-it.litertlm",
        "gemma-4-E2B-it-gpu.litertlm",
        "gemma4.litertlm",
        "gemma4-4b.litertlm",
    )
}

/** A single model file fetched from the self-hosted mirror, with optional SHA-256. */
data class ModelDownloadItem(
    val url: String,
    val fileName: String,
    val description: String,
    val sha256: String? = null,
)

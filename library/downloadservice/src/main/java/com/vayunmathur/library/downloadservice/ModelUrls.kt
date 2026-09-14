package com.vayunmathur.library.downloadservice

/**
 * Centralized model URL config for supply-chain mitigation #1.
 * Models are served ONLY from the self-hosted mirror (no HuggingFace fallback).
 * The server must host each file under https://data.vayunmathur.com/models/
 * and set Content-Length + ETag.
 *
 * # Gemma 4 is nine files, not one
 *
 * The chat LLM is `onnx-community/gemma-4-E2B-it-ONNX` (Apache-2.0 and ungated),
 * specifically the q4f16 quant family, run on `:library:ml`'s reduced ONNX Runtime build
 * via `GemmaOnnxHandle` (embed graph per turn + KV-cached decode, vision/audio towers as
 * soft tokens). Files, with upstream names kept so external-data resolution needs no
 * mapping:
 *
 *   models/decoder_model_merged_q4f16.onnx + .onnx_data  (~1.5 GB decoder)
 *   models/embed_tokens_q4f16.onnx + .onnx_data          (~1.6 GB embeddings)
 *   models/tokenizer.json                               (~19 MB BPE table)
 *   models/vision_encoder_q4f16.onnx + .onnx_data       (~99 MB, optional tower)
 *   models/audio_encoder_q4f16.onnx + .onnx_data        (~171 MB, optional tower)
 *
 * The first five must be present before the assistant will start - see
 * `GemmaOnnxHandle.FILES`. The two towers are optional at load: a device that never sends
 * a picture or a clip still answers text.
 */
object ModelUrls {
    const val MIRROR_BASE = "https://data.vayunmathur.com/models/"

    /** The decoder export (graph only; weights ride alongside in the `.onnx_data`). */
    const val GEMMA_DECODER_FILE = "decoder_model_merged_q4f16.onnx"
    const val GEMMA_DECODER_URL = "${MIRROR_BASE}decoder_model_merged_q4f16.onnx"
    const val GEMMA_DECODER_SHA256 =
        "73c0f1fe04f9a3a048fb3319c0671b6cf0346bf33a3a8624c853bcffe01c24a4"

    const val GEMMA_DECODER_DATA_FILE = "decoder_model_merged_q4f16.onnx_data"
    const val GEMMA_DECODER_DATA_URL = "${MIRROR_BASE}decoder_model_merged_q4f16.onnx_data"
    const val GEMMA_DECODER_DATA_SHA256 =
        "3b27245a7396cb7039a4e4118bd2a8aa35106bae381522edf7c4867b5f22bb10"

    /** The embedding graph + tables. */
    const val GEMMA_EMBED_FILE = "embed_tokens_q4f16.onnx"
    const val GEMMA_EMBED_URL = "${MIRROR_BASE}embed_tokens_q4f16.onnx"
    const val GEMMA_EMBED_SHA256 =
        "d7ca53f6a169471b5699b2f57ee4c7aa2c73732b0152f3909e64b71384444825"

    const val GEMMA_EMBED_DATA_FILE = "embed_tokens_q4f16.onnx_data"
    const val GEMMA_EMBED_DATA_URL = "${MIRROR_BASE}embed_tokens_q4f16.onnx_data"
    const val GEMMA_EMBED_DATA_SHA256 =
        "024b199e6358ed42970f807686add5f9430d7e254ca7ce22fc9c83f015b9c517"

    /** The BPE table: 262,144 entries plus 24 added tokens. */
    const val GEMMA_TOKENIZER_FILE = "tokenizer.json"
    const val GEMMA_TOKENIZER_URL = "${MIRROR_BASE}tokenizer.json"
    const val GEMMA_TOKENIZER_SHA256 =
        "47bd35616c7c782aaca6ccf48c75f3461d5877170984b8836b375107d0a9f566"

    /**
     * The vision tower (optional, same terms as the old `.maml` tower).
     */
    const val GEMMA_VISION_FILE = "vision_encoder_q4f16.onnx"
    const val GEMMA_VISION_URL = "${MIRROR_BASE}vision_encoder_q4f16.onnx"
    const val GEMMA_VISION_SHA256 =
        "e0a4e48e519ade4eeddbb4cdadb812a7251aea871f7fb5f50576615fd3af22a3"

    const val GEMMA_VISION_DATA_FILE = "vision_encoder_q4f16.onnx_data"
    const val GEMMA_VISION_DATA_URL = "${MIRROR_BASE}vision_encoder_q4f16.onnx_data"
    const val GEMMA_VISION_DATA_SHA256 =
        "0835071d2c79c105f8e1b549b7f8dd8c9af07fa95f01ead2e7add280602d3c6d"

    /**
     * The audio tower (optional, same terms as the old `.maml` tower).
     */
    const val GEMMA_AUDIO_FILE = "audio_encoder_q4f16.onnx"
    const val GEMMA_AUDIO_URL = "${MIRROR_BASE}audio_encoder_q4f16.onnx"
    const val GEMMA_AUDIO_SHA256 =
        "5e0deb22791685c792d4b8e089deef9670fa4a4cecde434213d6a742e58fc3fa"

    const val GEMMA_AUDIO_DATA_FILE = "audio_encoder_q4f16.onnx_data"
    const val GEMMA_AUDIO_DATA_URL = "${MIRROR_BASE}audio_encoder_q4f16.onnx_data"
    const val GEMMA_AUDIO_DATA_SHA256 =
        "df58e61a00bafa9449ee5fd52895ce952f158bbdd1fe38df8a68f48f36842e62"

    val GEMMA_DECODER =
        ModelDownloadItem(GEMMA_DECODER_URL, GEMMA_DECODER_FILE, "Model", GEMMA_DECODER_SHA256)
    val GEMMA_DECODER_DATA = ModelDownloadItem(
        GEMMA_DECODER_DATA_URL, GEMMA_DECODER_DATA_FILE, "Weights", GEMMA_DECODER_DATA_SHA256,
    )
    val GEMMA_EMBED =
        ModelDownloadItem(GEMMA_EMBED_URL, GEMMA_EMBED_FILE, "Embeddings", GEMMA_EMBED_SHA256)
    val GEMMA_EMBED_DATA = ModelDownloadItem(
        GEMMA_EMBED_DATA_URL, GEMMA_EMBED_DATA_FILE, "Embeddings", GEMMA_EMBED_DATA_SHA256,
    )
    val GEMMA_TOKENIZER = ModelDownloadItem(
        GEMMA_TOKENIZER_URL,
        GEMMA_TOKENIZER_FILE,
        "Tokenizer",
        GEMMA_TOKENIZER_SHA256,
    )
    val GEMMA_VISION =
        ModelDownloadItem(GEMMA_VISION_URL, GEMMA_VISION_FILE, "Vision", GEMMA_VISION_SHA256)
    val GEMMA_VISION_DATA = ModelDownloadItem(
        GEMMA_VISION_DATA_URL, GEMMA_VISION_DATA_FILE, "Vision", GEMMA_VISION_DATA_SHA256,
    )
    val GEMMA_AUDIO =
        ModelDownloadItem(GEMMA_AUDIO_URL, GEMMA_AUDIO_FILE, "Audio", GEMMA_AUDIO_SHA256)
    val GEMMA_AUDIO_DATA = ModelDownloadItem(
        GEMMA_AUDIO_DATA_URL, GEMMA_AUDIO_DATA_FILE, "Audio", GEMMA_AUDIO_DATA_SHA256,
    )

    /**
     * Everything downloaded on OpenAssistant first launch.
     *
     * Smallest first, so a user on a slow connection sees progress early and so the two large
     * files are the ones a resume is most likely to land in the middle of. The two towers go
     * after the tokenizer and before the two large ones: together they are ~270 MB, they are
     * what make the assistant able to answer about a photograph or a recording, and a resume
     * that stalls in the decoder should not leave them unfetched.
     */
    val INITIAL = listOf(
        GEMMA_TOKENIZER,
        GEMMA_VISION, GEMMA_VISION_DATA, GEMMA_AUDIO, GEMMA_AUDIO_DATA,
        GEMMA_DECODER, GEMMA_DECODER_DATA, GEMMA_EMBED, GEMMA_EMBED_DATA,
    )

    /**
     * Files an earlier build downloaded and this one does not use.
     *
     * Deleted on startup rather than left to rot: the `.maml` set alone was 2.8 GB, and a
     * user upgrading would otherwise carry both copies forever.
     */
    val OBSOLETE = listOf(
        "gemma4-2b.litertlm",
        "gemma-4-E2B-it.litertlm",
        "gemma4.litertlm",
        "gemma4-4b.litertlm",
        "gemma4_text.maml",
        "gemma4_embed.maml",
        "gemma4_tokenizer.spm1",
        "gemma4_vision.maml",
        "gemma4_audio.maml",
        "gemma4_prefix.kv",
    )
}

/** A single model file fetched from the self-hosted mirror, with optional SHA-256. */
data class ModelDownloadItem(
    val url: String,
    val fileName: String,
    val description: String,
    val sha256: String? = null,
)

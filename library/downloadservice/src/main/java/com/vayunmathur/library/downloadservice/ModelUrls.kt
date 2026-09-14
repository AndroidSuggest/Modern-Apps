package com.vayunmathur.library.downloadservice

/**
 * Centralized model URL config for supply-chain mitigation #1.
 * Models are served ONLY from the self-hosted mirror (no HuggingFace fallback).
 * The server must host each file under https://data.vayunmathur.com/models/
 * and set Content-Length + ETag.
 *
 * # Bonsai 8B is three files, not one
 *
 * The chat LLM is `onnx-community/Bonsai-8B-ONNX` (Qwen3 8B, Apache-2.0 and ungated),
 * specifically the 1-bit quant `onnx/model_q1.onnx` (+ its external-data companion) with
 * the HuggingFace `tokenizer.json`. It runs on `:library:ml`'s reduced ONNX Runtime build
 * via `BonsaiHandle` (prefill + KV-cached decode), with the chat turn and tool loop in
 * `BonsaiEngine`.
 *
 * To populate the mirror, download from the upstream repo at the pinned revision and upload:
 *
 *   models/bonsai_q1.onnx       <- onnx/model_q1.onnx
 *   models/bonsai_q1.onnx_data  <- onnx/model_q1.onnx_data (~1.33 GB)
 *   models/bonsai_tokenizer.json <- tokenizer.json (~9 MB)
 *
 * The first three must be present before the assistant will start - see `BonsaiHandle.FILES`.
 */
object ModelUrls {
    const val MIRROR_BASE = "https://data.vayunmathur.com/models/"

    /** The decoder export (graph only; weights ride alongside in the `.onnx_data`). */
    const val BONSAI_MODEL_FILE = "bonsai_q1.onnx"
    const val BONSAI_MODEL_URL = "${MIRROR_BASE}bonsai_q1.onnx"
    const val BONSAI_MODEL_SHA256 = "REPLACE_WITH_MIRRORED_SHA256"

    /** The 1-bit weights for the export above. */
    const val BONSAI_DATA_FILE = "bonsai_q1.onnx_data"
    const val BONSAI_DATA_URL = "${MIRROR_BASE}bonsai_q1.onnx_data"
    const val BONSAI_DATA_SHA256 = "REPLACE_WITH_MIRRORED_SHA256"

    /** The BPE table: 151,643 entries plus 26 added tokens. */
    const val BONSAI_TOKENIZER_FILE = "bonsai_tokenizer.json"
    const val BONSAI_TOKENIZER_URL = "${MIRROR_BASE}bonsai_tokenizer.json"
    const val BONSAI_TOKENIZER_SHA256 = "REPLACE_WITH_MIRRORED_SHA256"

    /**
     * Everything downloaded on OpenAssistant first launch.
     *
     * Smallest first, so a user on a slow connection sees progress early and so the two large
     * files are the ones a resume is most likely to land in the middle of. The two encoders go
     * after the tokenizer and before the two large ones: together they are 259 MB, they are what
     * make the assistant able to answer about a photograph or a recording, and a resume that
     * stalls in the decoder should not leave them unfetched.
     */
    val BONSAI_MODEL =
        ModelDownloadItem(BONSAI_MODEL_URL, BONSAI_MODEL_FILE, "Model", BONSAI_MODEL_SHA256)
    val BONSAI_DATA =
        ModelDownloadItem(BONSAI_DATA_URL, BONSAI_DATA_FILE, "Weights", BONSAI_DATA_SHA256)
    val BONSAI_TOKENIZER = ModelDownloadItem(
        BONSAI_TOKENIZER_URL,
        BONSAI_TOKENIZER_FILE,
        "Tokenizer",
        BONSAI_TOKENIZER_SHA256,
    )

    /**
     * Everything downloaded on OpenAssistant first launch.
     *
     * Smallest first, so a user on a slow connection sees progress early and so the large
     * weights file is the one a resume is most likely to land in the middle of.
     */
    val INITIAL = listOf(BONSAI_TOKENIZER, BONSAI_MODEL, BONSAI_DATA)

    /**
     * Files an earlier build downloaded and this one does not use.
     *
     * Deleted on startup rather than left to rot: the Gemma `.maml` files alone were 2.8 GB,
     * and a user upgrading would otherwise carry both copies forever.
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

package com.vayunmathur.library.downloadservice

/**
 * Centralized model URL config for supply-chain mitigation #1.
 * Models are served ONLY from the self-hosted mirror (no HuggingFace fallback).
 * The server must host each file under https://data.vayunmathur.com/models/
 * and set Content-Length + ETag.
 *
 * # Gemma 4 is one file on LiteRT-LM
 *
 * The chat LLM is `litert-community/gemma-4-E2B-it-litert-lm` (Apache-2.0),
 * the `gemma-4-E2B-it.litertlm` bundle (~2.6 GB, CPU backend), run on the
 * LiteRT-LM runtime via `Gemma4Engine` in `:openassistant`. It must be present
 * before the assistant will start.
 */
object ModelUrls {
    const val MIRROR_BASE = "https://data.vayunmathur.com/models/"

    /** The litertlm bundle (CPU; vision/audio towers included in the bundle). */
    const val GEMMA_LITERTLM_FILE = "gemma-4-E2B-it.litertlm"
    const val GEMMA_LITERTLM_URL = "${MIRROR_BASE}gemma-4-E2B-it.litertlm"
    const val GEMMA_LITERTLM_SHA256 =
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    val GEMMA_LITERTLM =
        ModelDownloadItem(GEMMA_LITERTLM_URL, GEMMA_LITERTLM_FILE, "Gemma 4", GEMMA_LITERTLM_SHA256)

    /**
     * The ExecuTorch SpinQuant `.pte` (Vulkan 8da4w, text-only) plus its BPE table, served
     * by `Gemma4EtEngine` when present.
     *
     * SHAs verified against the staged files in `analysis/et-gemma/`; `GEMMA_LITERTLM`
     * untouched. Neither file is in [INITIAL] — first launch must not gate on them.
     * `ensureLoaded` resolves them opportunistically; absent means "litertlm serves",
     * never a throw.
     */
    const val GEMMA_ET_FILE = "gemma-4-e2b-vulkan-8da4w.pte"
    const val GEMMA_ET_URL = "${MIRROR_BASE}openassistant/gemma-4-e2b-vulkan-8da4w.pte"
    const val GEMMA_ET_SHA256 =
        "f57a627f9fc487003913be05c1d4d5ac75f6473550809c0a345e91c10a428e2a"

    const val GEMMA_ET_TOKENIZER_FILE = "gemma4_et_tokenizer.json"
    const val GEMMA_ET_TOKENIZER_URL = "${MIRROR_BASE}openassistant/gemma_tokenizer.json"
    const val GEMMA_ET_TOKENIZER_SHA256 =
        "4667f2089529e8e7657cfb6d1c19910ae71ff5f28aa7ab2ff2763330affad795"

    val GEMMA_ET = ModelDownloadItem(GEMMA_ET_URL, GEMMA_ET_FILE, "Gemma 4 ET", GEMMA_ET_SHA256)
    val GEMMA_ET_TOKENIZER = ModelDownloadItem(
        GEMMA_ET_TOKENIZER_URL, GEMMA_ET_TOKENIZER_FILE, "Gemma 4 ET tokenizer",
        GEMMA_ET_TOKENIZER_SHA256,
    )


    /**
     * Everything downloaded on OpenAssistant first launch: the single litertlm bundle.
     */
    val INITIAL = listOf(
        GEMMA_LITERTLM,
    )

    /**
     * Files an earlier build downloaded and this one does not use.
     *
     * Deleted on startup rather than left to rot: the ONNX set alone was ~3.4 GB, and a
     * user upgrading would otherwise carry both copies forever. (The `gemma-4-E2B-it.litertlm`
     * name stays listed because pre-ONNX builds predated it; the current bundle reuses the
     * name, so a fresh download lands after this cleanup runs — order matters, and
     * `InitialModelDownloadChecker` runs after `cleanupLegacyModelFile`.)
     *
     * The ET pair (`GEMMA_ET_FILE`, `GEMMA_ET_TOKENIZER_FILE`) is deliberately NOT listed:
     * they are opportunistically resolved, never required, so a partial download must not
     * be deleted out from under a future `ensureLoaded`.
     */
    val OBSOLETE = listOf(
        "gemma4-2b.litertlm",
        "gemma4.litertlm",
        "gemma4-4b.litertlm",
        "gemma4_text.maml",
        "gemma4_embed.maml",
        "gemma4_tokenizer.spm1",
        "gemma4_vision.maml",
        "gemma4_audio.maml",
        "gemma4_prefix.kv",
        "decoder_model_merged_q4f16.onnx",
        "decoder_model_merged_q4f16.onnx_data",
        "embed_tokens_q4f16.onnx",
        "embed_tokens_q4f16.onnx_data",
        "tokenizer.json",
        "vision_encoder_q4f16.onnx",
        "vision_encoder_q4f16.onnx_data",
        "audio_encoder_q4f16.onnx",
        "audio_encoder_q4f16.onnx_data",
    )
}

/** A single model file fetched from the self-hosted mirror, with optional SHA-256. */
data class ModelDownloadItem(
    val url: String,
    val fileName: String,
    val description: String,
    val sha256: String? = null,
)

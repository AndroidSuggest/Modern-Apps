package com.vayunmathur.library.downloadservice

/**
 * Centralized model URL config for supply-chain mitigation #1.
 * Models are served ONLY from the self-hosted mirror (no HuggingFace fallback).
 * The server must host each file under https://data.vayunmathur.com/models/
 * and set Content-Length + ETag.
 *
 * To populate the mirror, upload these HuggingFace source files under the given
 * mirror paths (left = mirror path, right = HuggingFace source):
 *
 *   models/gemma-4-E2B-it.litertlm
 *     <- https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm
 *   models/siglip2_vision_model_fp16.onnx
 *     <- https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/vision_model_fp16.onnx
 *   models/siglip2_text_model_int8.onnx
 *     <- https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/text_model_int8.onnx
 *   models/siglip2_tokenizer.model
 *     <- https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/tokenizer.model
 */
object ModelUrls {
    const val MIRROR_BASE = "https://data.vayunmathur.com/models/"

    // On-disk file names (also the mirror path for the SigLIP2 files).
    const val GEMMA_FILE = "gemma4-2b.litertlm"
    const val SIGLIP_VISION_FILE = "siglip2_vision_model_fp16.onnx"
    const val SIGLIP_TEXT_FILE = "siglip2_text_model_int8.onnx"
    const val SIGLIP_TOKENIZER_FILE = "siglip2_tokenizer.model"

    // Mirror download URLs.
    const val GEMMA_URL = "${MIRROR_BASE}gemma-4-E2B-it.litertlm"
    const val SIGLIP_VISION_URL = "${MIRROR_BASE}siglip2_vision_model_fp16.onnx"
    const val SIGLIP_TEXT_URL = "${MIRROR_BASE}siglip2_text_model_int8.onnx"
    const val SIGLIP_TOKENIZER_URL = "${MIRROR_BASE}siglip2_tokenizer.model"

    // SHA-256 integrity checks (verified against the files uploaded to the mirror).
    const val GEMMA_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    const val SIGLIP_VISION_SHA256 = "a1959f7bd3993a607e48839f6d01e25b876fe76afda301b028b78eef68aabd95"
    const val SIGLIP_TEXT_SHA256 = "3a0603d3a00c05a80a6ded4743c16aaac7b1e62cdcc7e362e7ce418659b96400"
    const val SIGLIP_TOKENIZER_SHA256 = "61a7b147390c64585d6c3543dd6fc636906c9af3865a5548f27f31aee1d4c8e2"

    val GEMMA = ModelDownloadItem(GEMMA_URL, GEMMA_FILE, "Model", GEMMA_SHA256)
    val SIGLIP_VISION = ModelDownloadItem(SIGLIP_VISION_URL, SIGLIP_VISION_FILE, "Vision Model", SIGLIP_VISION_SHA256)
    val SIGLIP_TEXT = ModelDownloadItem(SIGLIP_TEXT_URL, SIGLIP_TEXT_FILE, "Text Model", SIGLIP_TEXT_SHA256)
    val SIGLIP_TOKENIZER = ModelDownloadItem(SIGLIP_TOKENIZER_URL, SIGLIP_TOKENIZER_FILE, "Tokenizer", SIGLIP_TOKENIZER_SHA256)

    /** SigLIP2 semantic-search models fetched on demand by the photos app. */
    val SIGLIP = listOf(SIGLIP_VISION, SIGLIP_TEXT, SIGLIP_TOKENIZER)

    /** Everything downloaded on OpenAssistant first launch (Gemma + SigLIP2). */
    val INITIAL = listOf(GEMMA) + SIGLIP
}

/** A single model file fetched from the self-hosted mirror, with optional SHA-256. */
data class ModelDownloadItem(
    val url: String,
    val fileName: String,
    val description: String,
    val sha256: String? = null,
)

package com.vayunmathur.openassistant.util

import android.content.Context
import android.os.Bundle
import android.os.ResultReceiver
import android.util.Log
import com.vayunmathur.library.downloadservice.ModelUrls
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SigLIP2 embedding queue consumer for [InferenceService]. Owns the
 * on-demand model download and the text/image/info dispatch so the service
 * class stays under the TooManyFunctions limit.
 */
internal class InferenceEmbeddingHandler(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    @Volatile private var downloadJob: Job? = null

    fun isDownloadActive(): Boolean = downloadJob?.isActive == true

    suspend fun process(job: InferenceService.InferenceJobPublic.Embedding) {
        // Models are downloaded on demand; until all three files are present,
        // report "downloading" (code 2) with aggregate progress and retry later.
        if (!SiglipEmbedder.filesPresent(context)) {
            startModelDownloadIfNeeded()
            job.receiver.send(
                InferenceService.EMBEDDING_DOWNLOADING_CODE,
                Bundle().apply {
                    putString("status", "downloading")
                    putDouble("progress", currentDownloadProgress())
                },
            )
            return
        }

        if (!SiglipEmbedder.isAvailable(context)) {
            job.receiver.send(
                InferenceService.EMBEDDING_ERROR_CODE,
                Bundle().apply { putString("error", "Embedder failed to load") },
            )
            return
        }

        when (job.mode) {
            "info" -> sendInfo(job)
            "text" -> sendText(job)
            "image" -> sendImage(job)
            else -> job.receiver.send(
                InferenceService.EMBEDDING_ERROR_CODE,
                Bundle().apply { putString("error", "Unknown embed_mode ${job.mode}") },
            )
        }
    }

    fun reportError(job: InferenceService.InferenceJobPublic.Embedding, expected: Exception) {
        try {
            job.receiver.send(
                InferenceService.EMBEDDING_ERROR_CODE,
                Bundle().apply {
                    putString("error", expected.localizedMessage ?: "Embedding failed")
                },
            )
        } catch (ignored: Exception) {
            Log.e("InferenceService", "Failed to report embedding error", ignored)
        }
    }

    private fun sendInfo(job: InferenceService.InferenceJobPublic.Embedding) {
        job.receiver.send(
            InferenceService.EMBEDDING_OK_CODE,
            Bundle().apply {
                putString("model_id", SiglipEmbedder.MODEL_ID)
                putInt("dim", SiglipEmbedder.dim(context))
                putString("status", "ready")
            },
        )
    }

    private fun sendText(job: InferenceService.InferenceJobPublic.Embedding) {
        val t0 = System.currentTimeMillis()
        val emb = SiglipEmbedder.textEmbedding(context, job.userText)
        Log.i(
            "InferenceService",
            "Text embed (${emb?.size ?: 0}d) in " +
                "${System.currentTimeMillis() - t0}ms ok=${emb != null}",
        )
        sendEmbedding(job.receiver, emb)
    }

    private fun sendImage(job: InferenceService.InferenceJobPublic.Embedding) {
        val path = job.imagePath
        val t0 = System.currentTimeMillis()
        val emb = if (path != null) SiglipEmbedder.imageEmbedding(context, File(path)) else null
        Log.i(
            "InferenceService",
            "Image embed (${emb?.size ?: 0}d) in " +
                "${System.currentTimeMillis() - t0}ms ok=${emb != null} path=$path",
        )
        sendEmbedding(job.receiver, emb)
        // The temp copy from copyUriToFile is no longer needed.
        if (path != null) runCatching { File(path).delete() }
    }

    private fun sendEmbedding(receiver: ResultReceiver, emb: FloatArray?) {
        if (emb == null) {
            // Provider is up but this specific item couldn't be embedded; mark it
            // per_item so the client skips just this one rather than pausing.
            receiver.send(
                InferenceService.EMBEDDING_ERROR_CODE,
                Bundle().apply {
                    putString("error", "Embedding failed")
                    putBoolean("per_item", true)
                },
            )
        } else {
            receiver.send(
                InferenceService.EMBEDDING_OK_CODE,
                Bundle().apply {
                    putByteArray("embedding", SiglipEmbedder.floatsToBytes(emb))
                    putString("model_id", SiglipEmbedder.MODEL_ID)
                    putInt("dim", emb.size)
                },
            )
        }
    }

    private fun startModelDownloadIfNeeded() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            try {
                val ds = DataStoreUtils.getInstance(context)
                downloadModels(context, ds, ModelUrls.SIGLIP)
            } catch (expected: Exception) {
                Log.e("InferenceService", "SigLIP2 model download failed", expected)
            }
        }
    }

    private fun currentDownloadProgress(): Double {
        val ds = DataStoreUtils.getInstance(context)
        val files = listOf(
            SiglipEmbedder.VISION_FILE,
            SiglipEmbedder.TEXT_FILE,
            SiglipEmbedder.TOKENIZER_FILE,
        )
        return files.sumOf { ds.getDouble("progress_$it") ?: 0.0 } / files.size
    }
}

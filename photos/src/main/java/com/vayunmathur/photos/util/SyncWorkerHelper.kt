package com.vayunmathur.photos.util

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.photos.data.ClipResult
import com.vayunmathur.photos.data.PhotosRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ClipWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        clipMutex.withLock {
            setForeground(createForegroundInfo())
            val repository = PhotosRepository.get(applicationContext)
            runClipIndexing(repository, applicationContext)
            WorkResult.success()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 104,
        channelId = "clip_worker",
        channelName = "Photo Search Indexing",
        title = "Understanding Photos",
        text = "Indexing photos for visual search on-device...",
    )

    companion object {
        const val WORK_NAME = "ClipWorker"
        private val clipMutex = Mutex()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ClipWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/**
 * Embed any not-yet-embedded library photos into a SigLIP2 vector — served by
 * the bundled **TinyCLIP** model (see [ClipEmbedder]) — and store the L2-normalised
 * vector on the [Photo] row so semantic search can cosine-compare them against
 * the query's text embedding.
 *
 * Mirrors [runOCR]: incremental (only un-embedded, non-video photos), one image
 * at a time, throttled between items to keep battery/CPU low, and marks each
 * photo scanned regardless of per-image outcome so we never retry it forever.
 *
 * Gated on the bundled model loading ([ClipEmbedder.embeddingSupport]): if it cannot be opened
 * (corrupt install), skip indexing and leave `clipScanned=0` so OCR/filename search still works.
 */
suspend fun runClipIndexing(repository: PhotosRepository, context: Context) = coroutineScope {
    val dataStore = DataStoreUtils.getInstance(context)

    // The model ships in the APK, so this only fails on a broken install.
    if (ClipEmbedder.embeddingSupport(context) != ClipEmbedder.Support.READY) {
        Log.w("ClipWorker", "TinyCLIP embedder unavailable; skipping semantic indexing")
        return@coroutineScope
    }

    // If the embedder version OR the model id changed, old embeddings are incompatible (e.g.
    // OpenAssistant's 768-d SigLIP2 space -> TinyCLIP's 512-d): clear them and re-embed every
    // photo. Photo rows themselves (OCR text, faces) are untouched.
    val modelId = ClipEmbedder.embeddingInfo(context).modelId
    val storedVersion = dataStore.getLong("clip_embedder_version") ?: 0L
    val storedModelId = dataStore.getString("clip_model_id")
    if (storedVersion != ClipEmbedder.EMBEDDER_VERSION.toLong() || storedModelId != modelId) {
        repository.resetClipScanned()
        dataStore.setLong("clip_embedder_version", ClipEmbedder.EMBEDDER_VERSION.toLong())
        dataStore.setString("clip_model_id", modelId)
    }

    val photos = repository.getUnscannedForClip()
    if (photos.isEmpty()) return@coroutineScope

    // See INDEX_FLUSH_EVERY: batched so a scan doesn't invalidate the Photo
    // table once per embedded photo.
    val pending = mutableListOf<ClipResult>()
    suspend fun flush() {
        if (pending.isEmpty()) return
        repository.setClipResults(pending.toList())
        pending.clear()
    }

    var processed = 0
    try {
        for (photo in photos) {
            ensureActive()

            val t0 = System.currentTimeMillis()
            val embedding = try {
                ClipEmbedder.imageEmbedding(context, photo.uri.toUri())
            } catch (e: ClipEmbedder.ImageFailedException) {
                // The embedder is healthy but this one image couldn't be decoded: mark it scanned
                // with no vector so we skip it rather than blocking the queue on it forever.
                Log.w("ClipWorker", "Skipping un-embeddable photo ${photo.id}: ${e.message}")
                pending += ClipResult(id = photo.id, embedding = null)
                if (pending.size >= INDEX_FLUSH_EVERY) flush()
                delay(CLIP_INTER_ITEM_DELAY_MS)
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything else is systemic (model failed to load): stop the run WITHOUT marking
                // scanned so these photos retry on the next pass.
                Log.w("ClipWorker", "Embedding unavailable; pausing indexing", e)
                return@coroutineScope
            }

            Log.d("ClipWorker", "Embedded photo ${photo.id} (${embedding.size}d) in ${System.currentTimeMillis() - t0}ms")
            pending += ClipResult(id = photo.id, embedding = ClipEmbedder.floatsToBytes(embedding))
            if (pending.size >= INDEX_FLUSH_EVERY) flush()

            // Short pause between images keeps sustained CPU/battery use low.
            delay(CLIP_INTER_ITEM_DELAY_MS)
            // Longer cooling break every batch so the device can shed heat.
            processed++
            coolDownBetweenBatches(processed, "ClipWorker")
        }
    } finally {
        // Covers the systemic-failure return above as well as cancellation, so
        // photos already embedded in this run are never re-embedded.
        withContext(NonCancellable) { runCatching { flush() } }
    }
}

private const val CLIP_INTER_ITEM_DELAY_MS = 250L

package com.vayunmathur.photos.util
import com.vayunmathur.photos.R

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.database.getLongOrNull
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.photos.data.ClipResult
import com.vayunmathur.photos.data.ExifResult
import com.vayunmathur.photos.data.FaceRepository
import com.vayunmathur.photos.data.OcrResult
import com.vayunmathur.photos.data.Person
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoFace
import com.vayunmathur.photos.data.PhotoScanRepository
import com.vayunmathur.photos.data.PhotoScanTarget
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.VideoData
import com.vayunmathur.photos.data.toJson
import com.vayunmathur.photos.domain.MIN_OCR_DIM
import com.vayunmathur.photos.domain.decodeForOcr
import com.vayunmathur.photos.domain.toLayout
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.database.CursorIndexOutOfBoundsException
import java.io.IOException
import java.util.concurrent.TimeUnit

class FaceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        faceMutex.withLock {
            setForeground(createForegroundInfo())
            val faceRepository = FaceRepository.get(applicationContext)
            val scanRepository = PhotoScanRepository.get(applicationContext)
            runFaceIndexing(faceRepository, scanRepository, applicationContext)
            WorkResult.success()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 103,
        channelId = "face_worker",
        channelName = "People Indexing",
        title = "Finding People",
        text = "Grouping photos of the same person on-device...",
    )

    companion object {
        const val WORK_NAME = "FaceWorker"
        private val faceMutex = Mutex()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<FaceWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/**
 * Scan any not-yet-scanned library photos for faces, then group each detected
 * face into a [Person] cluster by cosine similarity of its embedding — all
 * on-device and unsupervised.
 *
 * Clustering is greedy and incremental: each face joins the nearest existing
 * cluster if similarity >= [FaceRecognizer.CLUSTER_THRESHOLD], otherwise it
 * starts a new cluster. Each cluster keeps a running-mean [Person.centroid] that
 * is updated as faces are added, so we never have to re-scan old photos.
 */
suspend fun runFaceIndexing(
    faceRepository: FaceRepository,
    scanRepository: PhotoScanRepository,
    context: Context,
) {
    val dataStore = DataStoreUtils.getInstance(context)

    // Feature is inert without the on-device models (see FaceRecognizer docs).
    // Return WITHOUT marking photos scanned so they get processed once the
    // model assets are present.
    if (!FaceRecognizer.modelsAvailable(context)) {
        Log.w("FaceWorker", "Face models missing; skipping face indexing")
        return
    }

    // If the embedder model/version changed, the old embeddings are
    // incompatible: drop all clusters + faces and re-scan every photo so they
    // get re-grouped with the new model. Photo rows themselves are untouched.
    val storedVersion = dataStore.getLong("face_embedder_version") ?: 0L
    if (storedVersion != FaceRecognizer.EMBEDDER_VERSION.toLong()) {
        faceRepository.clearPersons()
        faceRepository.clearPhotoFaces()
        scanRepository.resetFaceScanned()
        dataStore.setLong("face_embedder_version", FaceRecognizer.EMBEDDER_VERSION.toLong())
    }

    // Load existing clusters into memory once; centroids are cached as floats and
    // updated in place so we avoid re-reading them for every face.
    val clusters = faceRepository.getPersons()
        .map { Cluster(it, FaceRecognizer.bytesToFloats(it.centroid)) }
        .toMutableList()

    val photos = scanRepository.getUnscannedForFaces()
    Log.i("FaceWorker", "Face indexing start: ${photos.size} photos to scan, ${clusters.size} existing clusters")
    var facesTotal = 0
    var photosWithFaces = 0
    var decoded = 0

    // See INDEX_FLUSH_EVERY. Face rows are accumulated alongside the scanned ids and
    // committed with them in one transaction, so a batch is all-or-nothing: a worker
    // killed mid-batch cannot leave rows behind that a re-scan would duplicate.
    val pendingScanned = mutableListOf<Long>()
    val pendingFaces = mutableListOf<PhotoFace>()
    suspend fun flush() {
        if (pendingScanned.isEmpty()) return
        faceRepository.commitFaceScan(pendingScanned.toList(), pendingFaces.toList())
        pendingScanned.clear()
        pendingFaces.clear()
    }

    try {
        for (photo in photos) {
            currentCoroutineContext().ensureActive()
            // Face indexing runs two models per photo (detector + embedder), so
            // pace it like OCR/CLIP: a short pause after each photo we actually ran
            // inference on, plus a longer cooling break every batch.
            val outcome = scanPhotoFaces(context, faceRepository, photo, clusters)
            if (outcome.didInference) {
                decoded++
                if (outcome.faces.isNotEmpty()) {
                    photosWithFaces++
                    facesTotal += outcome.faces.size
                    Log.i("FaceWorker", "photo ${photo.id}: ${outcome.faces.size} face(s) (running total: $facesTotal)")
                }
                pendingFaces += outcome.faces
                delay(FACE_INTER_ITEM_DELAY_MS)
                coolDownBetweenBatches(decoded, "FaceWorker")
            }

            // Mark scanned regardless of outcome so we don't retry forever.
            pendingScanned += photo.id
            if (pendingScanned.size >= INDEX_FLUSH_EVERY) flush()
        }
    } finally {
        withContext(NonCancellable) { runCatching { flush() } }
    }
    val clusterCount = faceRepository.getPersons().size
    Log.i(
        "FaceWorker",
        "Face indexing done: decoded=$decoded/${photos.size}, " +
            "$facesTotal faces in $photosWithFaces photos, $clusterCount clusters",
    )

    // Second pass: fold together clusters whose centroids ended up very close,
    // which trims duplicate person-groups created early in the scan.
    mergeSimilarClusters(faceRepository)
}

/** A cluster held in memory during a scan: its [Person] row plus cached centroid. */
private class Cluster(var person: Person, var centroid: FloatArray)

/** Outcome of scanning one photo for faces: whether inference ran plus any face rows. */
private class FaceScanOutcome(val didInference: Boolean, val faces: List<PhotoFace>)

/**
 * Decode one photo and run face detection + clustering on it. Inference
 * failures yield no faces (the photo is still marked scanned by the caller).
 */
private suspend fun scanPhotoFaces(
    context: Context,
    faceRepository: FaceRepository,
    photo: PhotoScanTarget,
    clusters: MutableList<Cluster>,
): FaceScanOutcome {
    val bitmap = try {
        loadBitmapForFaces(context, photo.uri.toUri())
    } catch (e: IOException) {
        Log.e("FaceWorker", "Error scanning faces for photo ${photo.id}", e)
        null
    } catch (e: SecurityException) {
        Log.e("FaceWorker", "Error scanning faces for photo ${photo.id}", e)
        null
    } ?: return FaceScanOutcome(false, emptyList())
    // Read before recycle: the box columns are normalised against
    // this bitmap, so its dimensions have to be stored with them.
    val srcWidth = bitmap.width
    val srcHeight = bitmap.height
    val faces = try {
        FaceRecognizer.detectAndEmbed(context, bitmap)
    } catch (e: IllegalArgumentException) {
        Log.e("FaceWorker", "Error scanning faces for photo ${photo.id}", e)
        emptyList()
    } catch (e: IllegalStateException) {
        Log.e("FaceWorker", "Error scanning faces for photo ${photo.id}", e)
        emptyList()
    } finally {
        bitmap.recycle()
    }
    val rows = faces.map { face ->
        val clusterId = assignToCluster(face, clusters, faceRepository)
        PhotoFace(
            photoId = photo.id,
            clusterId = clusterId,
            embedding = FaceRecognizer.floatsToBytes(face.embedding),
            left = face.left,
            top = face.top,
            right = face.right,
            bottom = face.bottom,
            srcWidth = srcWidth,
            srcHeight = srcHeight,
        )
    }
    return FaceScanOutcome(true, rows)
}

private const val FACE_INTER_ITEM_DELAY_MS = 250L

/**
 * Put [face] in the nearest cluster above [FaceRecognizer.CLUSTER_THRESHOLD],
 * updating that cluster's running-mean centroid, or start a new cluster. Returns
 * the cluster (person) id the face was assigned to.
 */
private suspend fun assignToCluster(
    face: FaceRecognizer.DetectedFace,
    clusters: MutableList<Cluster>,
    faceRepository: FaceRepository,
): Long {
    var best: Cluster? = null
    var bestSim = FaceRecognizer.CLUSTER_THRESHOLD
    for (cluster in clusters) {
        val sim = FaceRecognizer.similarity(face.embedding, cluster.centroid)
        if (sim >= bestSim) {
            bestSim = sim
            best = cluster
        }
    }

    if (best != null) {
        val n = best.person.faceCount
        val mean = FloatArray(best.centroid.size) { i ->
            (best.centroid[i] * n + face.embedding[i]) / (n + 1)
        }
        // Centroid is a running mean kept L2-normalised so cosine stays well-behaved.
        best.centroid = FaceRecognizer.l2Normalize(mean)
        best.person = best.person.copy(
            centroid = FaceRecognizer.floatsToBytes(best.centroid),
            faceCount = n + 1,
        )
        // Column-targeted, so a name the user enters mid-scan is not overwritten
        // by the null carried in this snapshot (loaded once at scan start).
        faceRepository.updateClusterCentroid(
            id = best.person.id,
            centroid = best.person.centroid,
            faceCount = best.person.faceCount,
        )
        return best.person.id
    }

    val person = Person(
        centroid = FaceRecognizer.floatsToBytes(face.embedding),
        faceCount = 1,
        name = null,
    )
    val id = faceRepository.insertPerson(person)
    clusters += Cluster(person.copy(id = id), face.embedding.copyOf())
    return id
}

/**
 * Greedily merge clusters whose centroids are within [FaceRecognizer.MERGE_THRESHOLD]
 * cosine of each other. Faces from the merged cluster are moved over and the
 * centroid becomes the face-count-weighted, L2-normalised mean. O(n^2) over the
 * (small) number of person-clusters.
 */
private suspend fun mergeSimilarClusters(faceRepository: FaceRepository) {
    val persons = faceRepository.getPersons().toMutableList()
    var i = 0
    while (i < persons.size) {
        var j = i + 1
        while (j < persons.size) {
            val a = persons[i]
            val b = persons[j]
            val sim = FaceRecognizer.similarity(
                FaceRecognizer.bytesToFloats(a.centroid),
                FaceRecognizer.bytesToFloats(b.centroid),
            )
            if (sim >= FaceRecognizer.MERGE_THRESHOLD) {
                val na = a.faceCount
                val nb = b.faceCount
                val ca = FaceRecognizer.bytesToFloats(a.centroid)
                val cb = FaceRecognizer.bytesToFloats(b.centroid)
                val mean = FloatArray(ca.size) { (ca[it] * na + cb[it] * nb) / (na + nb) }
                val merged = a.copy(
                    centroid = FaceRecognizer.floatsToBytes(FaceRecognizer.l2Normalize(mean)),
                    faceCount = na + nb,
                    // A name is the one thing here the user typed, so the survivor
                    // inherits it from whichever side has one rather than losing it
                    // with the discarded row.
                    name = a.name ?: b.name,
                )
                faceRepository.reassignCluster(b.id, a.id)
                // The name is resolved in SQL against the row's current value, so a
                // name entered since `persons` was read still wins over this snapshot.
                faceRepository.mergeClusterInto(
                    id = a.id,
                    centroid = merged.centroid,
                    faceCount = merged.faceCount,
                    fallbackName = b.name,
                )
                faceRepository.deletePerson(b.id)
                persons[i] = merged
                persons.removeAt(j)
            } else {
                j++
            }
        }
        i++
    }
}

private fun loadBitmapForFaces(context: Context, uri: Uri): Bitmap? {
    return try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val maxDim = maxOf(info.size.width, info.size.height)
            val target = 720
            if (maxDim > target) {
                val scale = target.toFloat() / maxDim
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    } catch (e: IOException) {
        Log.e("FaceWorker", "Failed to decode $uri for faces", e)
        null
    } catch (e: SecurityException) {
        Log.e("FaceWorker", "Failed to decode $uri for faces", e)
        null
    }
}

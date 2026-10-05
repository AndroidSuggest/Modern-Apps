package com.vayunmathur.photos.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository

/**
 * Indexing reads/writes (OCR, CLIP, faces, EXIF backfill), split out of
 * [PhotosRepository] so neither class exceeds the function-count cap.
 * Owns no database of its own: shares the one [PhotoDatabase] instance via
 * [RoomRepository], touching only [PhotoScanDao].
 */
class PhotoScanRepository private constructor(context: Context) :
    RoomRepository<PhotoDatabase>(context, PhotoDatabase::class) {

    private val scanDao: PhotoScanDao get() = db.photoScanDao()

    suspend fun getUnscannedForOCR(): List<PhotoScanTarget> = scanDao.getUnscannedForOCR()
    suspend fun getUnscannedForFaces(): List<PhotoScanTarget> = scanDao.getUnscannedForFaces()
    suspend fun getUnscannedForClip(): List<PhotoScanTarget> = scanDao.getUnscannedForClip()
    suspend fun getUnscannedForExif(): List<PhotoExifTarget> = scanDao.getUnscannedForExif()
    suspend fun setOcrScanned(ids: List<Long>) = scanDao.setOcrScanned(ids)
    suspend fun setFaceScanned(ids: List<Long>) = scanDao.setFaceScanned(ids)
    suspend fun setOcrResults(results: List<OcrResult>) = scanDao.setOcrResults(results)
    suspend fun setClipResults(results: List<ClipResult>) = scanDao.setClipResults(results)
    suspend fun setExifResults(results: List<ExifResult>) = scanDao.setExifResults(results)
    suspend fun getClipEmbeddings(): List<PhotoEmbedding> = scanDao.getClipEmbeddings()
    suspend fun resetClipScanned() = scanDao.resetClipScanned()
    suspend fun resetFaceScanned() = scanDao.resetFaceScanned()
    suspend fun countMissingMimeType(): Int = scanDao.countMissingMimeType()
    suspend fun getOcrBoxes(id: Long): String? = scanDao.getOcrBoxes(id)
    suspend fun getStillPhotoUris(): List<String> = scanDao.getStillPhotoUris()
    suspend fun setOcrBoxes(id: Long, json: String?) = scanDao.setOcrBoxes(id, json)

    companion object {
        @Volatile
        private var instance: PhotoScanRepository? = null

        fun get(context: Context): PhotoScanRepository =
            instance ?: synchronized(this) {
                instance ?: PhotoScanRepository(context).also { instance = it }
            }
    }
}

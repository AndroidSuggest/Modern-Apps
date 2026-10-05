package com.vayunmathur.photos.data

import android.content.Context
import androidx.room3.withWriteTransaction
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Face clustering reads/writes (persons, photo faces), split out of
 * [PhotosRepository] so neither class exceeds the function-count cap.
 * Owns no database of its own: shares the one [PhotoDatabase] instance via
 * [RoomRepository], touching [FaceDao] (and [PhotoScanDao] for the scanned flag).
 */
class FaceRepository private constructor(context: Context) :
    RoomRepository<PhotoDatabase>(context, PhotoDatabase::class) {

    private val faceDao: FaceDao get() = db.faceDao()
    private val scanDao: PhotoScanDao get() = db.photoScanDao()

    fun personsFlow(): Flow<List<Person>> = faceDao.personsFlow()
    fun faceGeometryFlow(): Flow<List<FaceGeometry>> = faceDao.faceGeometryFlow()
    suspend fun insertPerson(person: Person): Long = faceDao.insertPerson(person)
    suspend fun updateClusterCentroid(id: Long, centroid: ByteArray, faceCount: Int) =
        faceDao.updateClusterCentroid(id, centroid, faceCount)
    suspend fun mergeClusterInto(id: Long, centroid: ByteArray, faceCount: Int, fallbackName: String?) =
        faceDao.mergeClusterInto(id, centroid, faceCount, fallbackName)
    suspend fun getPersons(): List<Person> = faceDao.getPersons()
    suspend fun clearPersons() = faceDao.clearPersons()
    suspend fun deletePerson(id: Long) = faceDao.deletePerson(id)
    suspend fun setPersonName(id: Long, name: String?) = faceDao.setPersonName(id, name)
    suspend fun clearPhotoFaces() = faceDao.clearPhotoFaces()
    suspend fun reassignCluster(oldId: Long, newId: Long) = faceDao.reassignCluster(oldId, newId)
    suspend fun photoIdsForCluster(clusterId: Long): List<Long> = faceDao.photoIdsForCluster(clusterId)

    /**
     * Commit one batch of face scanning atomically: replace the face rows for
     * [photoIds] and mark those photos scanned in the same transaction.
     *
     * All three statements have to land together. If the rows were written but the
     * flag was not (a worker killed mid-batch, which WorkManager does routinely),
     * the next run would re-scan those photos and insert a second set of rows for
     * them. Delete-then-insert also makes a re-scan idempotent on its own.
     */
    suspend fun commitFaceScan(photoIds: List<Long>, faces: List<PhotoFace>) =
        db.withWriteTransaction {
            faceDao.deletePhotoFacesByPhotoIds(photoIds)
            if (faces.isNotEmpty()) faceDao.insertPhotoFaces(faces)
            scanDao.setFaceScanned(photoIds)
        }

    companion object {
        @Volatile
        private var instance: FaceRepository? = null

        fun get(context: Context): FaceRepository =
            instance ?: synchronized(this) {
                instance ?: FaceRepository(context).also { instance = it }
            }
    }
}

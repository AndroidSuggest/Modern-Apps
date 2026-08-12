package com.vayunmathur.findfamily.tracker

import com.vayunmathur.library.util.DataStoreUtils

/**
 * Owner-only persistence for bound trackers. Holds, per tracker userid, the two
 * secrets that never leave the owner's device:
 *  - the beacon master secret (used to recompute rotating epoch-ids), and
 *  - the tracker's ML-KEM **private** bundle (used to decrypt crowd reports).
 *
 * The tracker's **public** bundle is stored on the `User` row itself
 * (`User.pqcEncryptionKey`), reusing the existing key-directory plumbing. The set
 * of owned trackers is simply the `User` rows with `kind == UserKind.TRACKER`, so
 * no separate index is kept here.
 */
class TrackerStore(private val ds: DataStoreUtils) {

    suspend fun save(trackerUserId: Long, secret: ByteArray, privateBundle: ByteArray) {
        ds.setByteArray(secretKey(trackerUserId), secret)
        ds.setByteArray(privKey(trackerUserId), privateBundle)
    }

    suspend fun secret(trackerUserId: Long): ByteArray? = ds.getByteArrayAwait(secretKey(trackerUserId))

    suspend fun privateBundle(trackerUserId: Long): ByteArray? = ds.getByteArrayAwait(privKey(trackerUserId))

    private fun secretKey(id: Long) = "ff_tracker_secret_$id"
    private fun privKey(id: Long) = "ff_tracker_priv_$id"
}

@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.messages.signal.contacts

import kotlin.uuid.Uuid
import android.util.Log
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import com.vayunmathur.messages.signal.store.SignalRecipientEntity
import com.vayunmathur.messages.signal.store.SignalRecipientStore
import com.vayunmathur.messages.util.ContactSuggestion
import java.nio.ByteBuffer
import java.security.MessageDigest

class ContactManager(
    private val recipientStore: SignalRecipientStore,
    private val profileManager: ProfileManager? = null,
) {
    suspend fun storeContactDetailsAsContact(
        contactDetails: SignalServiceProtos.ContactDetails,
        avatar: ByteArray? = null,
    ) {
        val aci = parseStringOrBinaryAci(
            contactDetails.aci,
            if (contactDetails.hasAciBinary()) contactDetails.aciBinary.toByteArray() else null,
        ) ?: return
        val existing = recipientStore.getRecipient(aci)
        val e164 = contactDetails.number.takeIf { it.isNotBlank() } ?: existing?.e164
        val contactName = contactDetails.name
        var avatarHash = existing?.contactAvatarHash
        if (avatar != null && avatar.isNotEmpty()) {
            val rawHash = MessageDigest.getInstance("SHA-256").digest(avatar)
            avatarHash = rawHash.joinToString("") { "%02x".format(it) }
        }
        recipientStore.storeRecipient(SignalRecipientEntity(
            aci = aci,
            e164 = e164,
            contactName = contactName,
            contactAvatarHash = avatarHash,
            profileKey = existing?.profileKey,
            profileName = existing?.profileName,
            profileAvatarPath = existing?.profileAvatarPath,
        ))
    }

    suspend fun handleContactSync(
        contacts: List<SignalServiceProtos.ContactDetails>,
        avatars: List<ByteArray?> = emptyList(),
    ) {
        for ((index, contact) in contacts.withIndex()) {
            val avatar = avatars.getOrNull(index)
            storeContactDetailsAsContact(contact, avatar)
        }
        Log.d(TAG, "Synced ${contacts.size} contacts")
    }

    suspend fun contactByACI(aci: String): SignalRecipientEntity? {
        return contactByACIWithRefreshAfter(aci, ProfileManager.DEFAULT_PROFILE_REFRESH_AFTER_MS)
    }

    suspend fun contactByACIWithRefreshAfter(aci: String, refreshAfter: Long): SignalRecipientEntity? {
        val existing = recipientStore.getRecipient(aci)
        if (profileManager != null && existing != null) {
            val profile = profileManager.retrieveProfileByID(aci, existing.profileKey, refreshAfter)
            if (profile != null) {
                val updated = existing.copy(
                    profileName = profile.name ?: existing.profileName,
                    profileAbout = profile.about ?: existing.profileAbout,
                    profileAboutEmoji = profile.aboutEmoji ?: existing.profileAboutEmoji,
                    profileAvatarPath = profile.avatarPath ?: existing.profileAvatarPath,
                    profileFetchedAt = profile.fetchedAt,
                )
                recipientStore.storeRecipient(updated)
                return updated
            }
        }
        return existing
    }

    suspend fun contactByE164(e164: String): SignalRecipientEntity? {
        val contact = recipientStore.getByE164(e164) ?: return null
        if (contact.aci.isNotBlank()) {
            return contactByACI(contact.aci)
        }
        return contact
    }

    suspend fun getDisplayName(aci: String): String? {
        val recipient = recipientStore.getRecipient(aci) ?: return null
        return recipient.contactName ?: recipient.profileName ?: recipient.e164
    }

    suspend fun searchContacts(query: String): List<ContactSuggestion> {
        val lowerQuery = query.lowercase()
        val all = recipientStore.getAllRecipients()
        return all.filter { recipient ->
            recipient.contactName?.lowercase()?.contains(lowerQuery) == true ||
                recipient.profileName?.lowercase()?.contains(lowerQuery) == true ||
                recipient.e164?.contains(lowerQuery) == true
        }.map { recipient ->
            ContactSuggestion(
                displayName = recipient.contactName ?: recipient.profileName ?: recipient.e164 ?: recipient.aci,
                phoneE164 = recipient.e164,
                avatarUrl = recipient.profileAvatarPath,
                source = MessageSource.SIGNAL,
            )
        }
    }

    companion object {
        private const val TAG = "ContactManager"

        fun parseStringOrBinaryAci(str: String?, bytes: ByteArray?): String? {
            if (!str.isNullOrBlank()) return str
            if (bytes != null && bytes.size == 16) {
                val buf = ByteBuffer.wrap(bytes)
                val uuid = Uuid.fromLongs(buf.long, buf.long)
                return uuid.toString()
            }
            return null
        }

        fun unmarshalContactDetailsMessages(
            byteStream: ByteArray,
        ): Pair<List<SignalServiceProtos.ContactDetails>, List<ByteArray?>> {
            val contactDetailsList = mutableListOf<SignalServiceProtos.ContactDetails>()
            val avatarList = mutableListOf<ByteArray?>()
            val buf = ByteBuffer.wrap(byteStream)
            while (buf.hasRemaining()) {
                val msgLen = readUvarint(buf) ?: break
                if (!buf.hasRemaining()) break
                val msgBytes = ByteArray(msgLen.toInt())
                buf.get(msgBytes)
                val contactDetails = SignalServiceProtos.ContactDetails.parseFrom(msgBytes)
                contactDetailsList.add(contactDetails)
                if (contactDetails.hasAvatar() && contactDetails.avatar.hasLength()
                    && contactDetails.avatar.length > 0
                ) {
                    val avatarBytes = ByteArray(contactDetails.avatar.length)
                    buf.get(avatarBytes)
                    avatarList.add(avatarBytes.copyOf())
                } else {
                    avatarList.add(null)
                }
            }
            return Pair(contactDetailsList, avatarList)
        }

        private fun readUvarint(buf: ByteBuffer): Long? {
            var result = 0L
            var shift = 0
            while (buf.hasRemaining()) {
                val b = buf.get().toLong() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0L) return result
                shift += 7
                if (shift >= 64) return null
            }
            return null
        }
    }
}

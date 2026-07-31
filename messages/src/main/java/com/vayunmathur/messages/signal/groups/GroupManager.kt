package com.vayunmathur.messages.signal.groups

import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.proto.Group
import com.vayunmathur.messages.signal.proto.GroupAttributeBlob
import com.vayunmathur.messages.signal.proto.GroupResponse
import com.vayunmathur.messages.signal.store.SignalGroupEntity
import com.vayunmathur.messages.signal.store.SignalGroupStore
import com.vayunmathur.messages.signal.store.SignalRecipientStore
import com.vayunmathur.messages.signal.web.SignalHttpClient
import com.vayunmathur.messages.signal.web.SignalWebSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.zkgroup.ServerPublicParams
import org.signal.libsignal.zkgroup.auth.AuthCredentialWithPniResponse
import org.signal.libsignal.zkgroup.auth.ClientZkAuthOperations
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher
import org.signal.libsignal.zkgroup.groups.UuidCiphertext
import org.signal.libsignal.zkgroup.profiles.ClientZkProfileOperations
import org.signal.libsignal.zkgroup.profiles.ExpiringProfileKeyCredentialResponse
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.libsignal.protocol.ServiceId
import com.google.protobuf.ByteString
import com.vayunmathur.messages.signal.proto.AccessControl as AccessControlProto
import com.vayunmathur.messages.signal.proto.GroupChange as GroupChangeProto
import com.vayunmathur.messages.signal.proto.Member as MemberProto
import com.vayunmathur.messages.signal.proto.MemberBanned
import com.vayunmathur.messages.signal.proto.MemberPendingProfileKey

class GroupManager(
    private val ws: SignalWebSocket,
    private val groupStore: SignalGroupStore,
    private val recipientStore: SignalRecipientStore,
    private val aci: String,
    private val pni: String,
    private val password: String,
) {
    

    // Builds a GroupChange.Actions with version = currentRevision + 1, lets [build] populate the
    // change, then encrypts/auths and PATCHes /v2/groups. zkgroup field encryption uses
    // ClientZkGroupCipher; auth + server signature handling lives in submitGroupChange.
    // Ref signalmeow/groups.go EncryptAndSignGroupChange + patchGroup.
    private suspend fun applyGroupChange(
        groupId: String,
        build: (ClientZkGroupCipher, GroupChangeProto.Actions.Builder) -> Boolean,
    ): Boolean {
        return try {
            val masterKey = groupStore.getMasterKeyByGroupId(groupId)
            if (masterKey == null) {
                Log.e(TAG, "No master key stored for group $groupId")
                return false
            }
            val group = getOrFetchGroup(groupId, masterKey)
            if (group == null) {
                Log.e(TAG, "Could not load group $groupId for mutation")
                return false
            }
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey))
            val cipher = ClientZkGroupCipher(groupSecretParams)
            val actions = GroupChangeProto.Actions.newBuilder()
                .setVersion(group.revision + 1)
            if (!build(cipher, actions)) return false
            val auth = getGroupAuth(masterKey) ?: return false
            submitGroupChange(groupId, masterKey, auth, actions.build())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply group change for $groupId", e)
            false
        }
    }

    private fun parseServiceId(serviceId: String): ServiceId {
        return if (serviceId.startsWith("PNI:")) {
            ServiceId.Pni(UUID.fromString(serviceId.removePrefix("PNI:")))
        } else {
            ServiceId.Aci(UUID.fromString(serviceId))
        }
    }

    private fun ClientZkGroupCipher.encryptServiceIdBytes(serviceId: ServiceId): ByteString =
        ByteString.copyFrom(encrypt(serviceId).serialize())

    private fun ClientZkGroupCipher.encryptAttribute(blob: GroupAttributeBlob): ByteString =
        ByteString.copyFrom(encryptBlob(blob.toByteArray()))

    suspend fun setGroupName(groupId: String, newName: String): Boolean =
        applyGroupChange(groupId) { cipher, actions ->
            val blob = GroupAttributeBlob.newBuilder().setTitle(newName).build()
            actions.setModifyTitle(
                GroupChangeProto.Actions.ModifyTitleAction.newBuilder()
                    .setTitle(cipher.encryptAttribute(blob)).build()
            )
            true
        }

    // Adds a member directly as a full group member using their expiring profile-key credential
    // presentation (requires us to know their profile key). Falls back to a pending invite (the
    // invitee promotes themselves on accept) when no profile key / credential is available, e.g.
    // for PNI-only invitees. Ref signalmeow/groups.go AddMembers + CreateExpiringProfileKeyCredentialPresentation.
    suspend fun inviteMember(groupId: String, serviceId: String, role: MemberRole = MemberRole.DEFAULT): Boolean {
        val masterKey = groupStore.getMasterKeyByGroupId(groupId)
        if (masterKey != null && !serviceId.startsWith("PNI:")) {
            val presentation = createExpiringProfileKeyPresentation(serviceId, masterKey)
            if (presentation != null) {
                val ok = applyGroupChange(groupId) { _, actions ->
                    actions.addAddMembers(
                        GroupChangeProto.Actions.AddMemberAction.newBuilder()
                            .setAdded(
                                MemberProto.newBuilder()
                                    .setRole(MemberProto.Role.forNumber(role.value))
                                    .setPresentation(ByteString.copyFrom(presentation))
                                    .build()
                            ).build()
                    )
                    true
                }
                if (ok) return true
                Log.w(TAG, "Full-member add failed for $serviceId, falling back to pending invite")
            }
        }
        // Fallback: invite as a pending member without a profile-key credential.
        return applyGroupChange(groupId) { cipher, actions ->
            val encUserId = cipher.encryptServiceIdBytes(parseServiceId(serviceId))
            val encAddedBy = cipher.encryptServiceIdBytes(ServiceId.Aci(UUID.fromString(aci)))
            val pending = MemberPendingProfileKey.newBuilder()
                .setMember(
                    MemberProto.newBuilder()
                        .setUserId(encUserId)
                        .setRole(MemberProto.Role.forNumber(role.value))
                        .build()
                )
                .setAddedByUserId(encAddedBy)
                .setTimestamp(System.currentTimeMillis())
                .build()
            actions.addAddMembersPendingProfileKey(
                GroupChangeProto.Actions.AddMemberPendingProfileKeyAction.newBuilder()
                    .setAdded(pending).build()
            )
            true
        }
    }

    suspend fun kickMember(groupId: String, memberAci: String): Boolean =
        applyGroupChange(groupId) { cipher, actions ->
            actions.addDeleteMembers(
                GroupChangeProto.Actions.DeleteMemberAction.newBuilder()
                    .setDeletedUserId(cipher.encryptServiceIdBytes(parseServiceId(memberAci))).build()
            )
            true
        }

    suspend fun banMember(groupId: String, serviceId: String): Boolean =
        applyGroupChange(groupId) { cipher, actions ->
            actions.addAddMembersBanned(
                GroupChangeProto.Actions.AddMemberBannedAction.newBuilder()
                    .setAdded(
                        MemberBanned.newBuilder()
                            .setUserId(cipher.encryptServiceIdBytes(parseServiceId(serviceId)))
                            .setTimestamp(System.currentTimeMillis())
                            .build()
                    ).build()
            )
            true
        }

    suspend fun leaveGroup(groupId: String): Boolean {
        return kickMember(groupId, aci)
    }

    suspend fun unbanMember(groupId: String, serviceId: String): Boolean =
        applyGroupChange(groupId) { cipher, actions ->
            actions.addDeleteMembersBanned(
                GroupChangeProto.Actions.DeleteMemberBannedAction.newBuilder()
                    .setDeletedUserId(cipher.encryptServiceIdBytes(parseServiceId(serviceId))).build()
            )
            true
        }

    // Promotes the invitee (normally self) from a pending profile-key member to a full member by
    // presenting their expiring profile-key credential. Ref signalmeow/groups.go
    // PromotePendingMembers. PNI->ACI promotion (invited by PNI) is not handled here.
    suspend fun acceptInvite(groupId: String, serviceId: String): Boolean {
        val masterKey = groupStore.getMasterKeyByGroupId(groupId)
        if (masterKey == null) {
            Log.e(TAG, "No master key stored for group $groupId")
            return false
        }
        if (serviceId.startsWith("PNI:")) {
            Log.w(TAG, "acceptInvite: PNI->ACI promotion not implemented for $serviceId")
            return false
        }
        val presentation = createExpiringProfileKeyPresentation(serviceId, masterKey)
        if (presentation == null) {
            Log.w(TAG, "acceptInvite: could not build profile-key credential presentation for $serviceId")
            return false
        }
        return applyGroupChange(groupId) { _, actions ->
            actions.addPromoteMembersPendingProfileKey(
                GroupChangeProto.Actions.PromoteMemberPendingProfileKeyAction.newBuilder()
                    .setPresentation(ByteString.copyFrom(presentation))
                    .build()
            )
            true
        }
    }

    var profileManager: com.vayunmathur.messages.signal.contacts.ProfileManager? = null

    // Fetches the target's expiring profile-key credential and builds a group-scoped presentation.
    // Mirrors signalmeow FetchExpiringProfileKeyCredentialById + CreateExpiringProfileKeyCredentialPresentation:
    // build a credential request from the target's profile key, fetch the profile with that request,
    // receive the credential, then create the presentation against this group's secret params.
    // Returns null when we lack the profile key, ProfileManager, or the server omits the credential.
    private suspend fun createExpiringProfileKeyPresentation(
        targetAci: String,
        masterKey: ByteArray,
    ): ByteArray? {
        return try {
            val pm = profileManager
            if (pm == null) {
                Log.w(TAG, "No ProfileManager wired; cannot build profile-key credential")
                return null
            }
            val profileKeyBytes = recipientStore.getRecipient(targetAci)?.profileKey
            if (profileKeyBytes == null || profileKeyBytes.size != 32) {
                Log.w(TAG, "No profile key for $targetAci; cannot build credential presentation")
                return null
            }
            val serverPublicParams = ServerPublicParams(SERVER_PUBLIC_PARAMS)
            val clientZkProfile = ClientZkProfileOperations(serverPublicParams)
            val aciServiceId = ServiceId.Aci(UUID.fromString(targetAci))
            val profileKey = ProfileKey(profileKeyBytes)
            val requestContext = clientZkProfile.createProfileKeyCredentialRequestContext(aciServiceId, profileKey)
            val credentialRequestHex = hexEncode(requestContext.request.serialize())
            val profile = pm.fetchProfile(targetAci, profileKeyBytes, credentialRequestHex)
            val credentialBytes = profile?.credential
            if (credentialBytes == null) {
                Log.w(TAG, "Profile fetch returned no expiring profile-key credential for $targetAci")
                return null
            }
            val response = ExpiringProfileKeyCredentialResponse(credentialBytes)
            val credential = clientZkProfile.receiveExpiringProfileKeyCredential(requestContext, response)
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey))
            val presentation = clientZkProfile.createProfileKeyCredentialPresentation(groupSecretParams, credential)
            presentation.serialize()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create expiring profile-key credential presentation for $targetAci", e)
            null
        }
    }

    suspend fun revokeInvite(groupId: String, serviceId: String): Boolean =
        applyGroupChange(groupId) { cipher, actions ->
            actions.addDeleteMembersPendingProfileKey(
                GroupChangeProto.Actions.DeleteMemberPendingProfileKeyAction.newBuilder()
                    .setDeletedUserId(cipher.encryptServiceIdBytes(parseServiceId(serviceId))).build()
            )
            true
        }

    suspend fun approveKnock(groupId: String, memberAci: String, role: MemberRole = MemberRole.DEFAULT): Boolean =
        applyGroupChange(groupId) { cipher, actions ->
            actions.addPromoteMembersPendingAdminApproval(
                GroupChangeProto.Actions.PromoteMemberPendingAdminApprovalAction.newBuilder()
                    .setUserId(cipher.encryptServiceIdBytes(parseServiceId(memberAci)))
                    .setRole(MemberProto.Role.forNumber(role.value))
                    .build()
            )
            true
        }

    suspend fun denyKnock(groupId: String, memberAci: String): Boolean =
        applyGroupChange(groupId) { cipher, actions ->
            actions.addDeleteMembersPendingAdminApproval(
                GroupChangeProto.Actions.DeleteMemberPendingAdminApprovalAction.newBuilder()
                    .setDeletedUserId(cipher.encryptServiceIdBytes(parseServiceId(memberAci))).build()
            )
            true
        }

    suspend fun setMemberRole(groupId: String, memberAci: String, role: MemberRole): Boolean =
        applyGroupChange(groupId) { cipher, actions ->
            actions.addModifyMemberRoles(
                GroupChangeProto.Actions.ModifyMemberRoleAction.newBuilder()
                    .setUserId(cipher.encryptServiceIdBytes(parseServiceId(memberAci)))
                    .setRole(MemberProto.Role.forNumber(role.value))
                    .build()
            )
            true
        }

    suspend fun setAnnouncementsOnly(groupId: String, announcementsOnly: Boolean): Boolean =
        applyGroupChange(groupId) { _, actions ->
            actions.setModifyAnnouncementsOnly(
                GroupChangeProto.Actions.ModifyAnnouncementsOnlyAction.newBuilder()
                    .setAnnouncementsOnly(announcementsOnly).build()
            )
            true
        }

    suspend fun setAttributesAccess(groupId: String, access: AccessControl): Boolean =
        applyGroupChange(groupId) { _, actions ->
            actions.setModifyAttributesAccess(
                GroupChangeProto.Actions.ModifyAttributesAccessControlAction.newBuilder()
                    .setAttributesAccess(AccessControlProto.AccessRequired.forNumber(access.value)).build()
            )
            true
        }

    suspend fun setMemberAccess(groupId: String, access: AccessControl): Boolean =
        applyGroupChange(groupId) { _, actions ->
            actions.setModifyMemberAccess(
                GroupChangeProto.Actions.ModifyMembersAccessControlAction.newBuilder()
                    .setMembersAccess(AccessControlProto.AccessRequired.forNumber(access.value)).build()
            )
            true
        }

    suspend fun setDisappearingTimer(groupId: String, expirationSeconds: Int): Boolean =
        applyGroupChange(groupId) { cipher, actions ->
            val blob = GroupAttributeBlob.newBuilder()
                .setDisappearingMessagesDuration(expirationSeconds).build()
            actions.setModifyDisappearingMessageTimer(
                GroupChangeProto.Actions.ModifyDisappearingMessageTimerAction.newBuilder()
                    .setTimer(cipher.encryptAttribute(blob)).build()
            )
            true
        }

    private suspend fun submitGroupChange(
        groupId: String,
        masterKey: ByteArray,
        auth: GroupAuthResult,
        actions: com.vayunmathur.messages.signal.proto.GroupChange.Actions,
    ): Boolean {
        val response = SignalHttpClient.request(
            host = SignalHttpClient.STORAGE_HOST,
            method = "PATCH",
            path = "/v2/groups/",
            body = actions.toByteArray(),
            contentType = "application/x-protobuf",
            username = auth.username,
            password = auth.password,
        )
        if (response.status == 200) {
            // Distribute the GroupChange as a DataMessage to all group members
            val changeBytes = response.bytes
            distributeGroupChange(groupId, masterKey, changeBytes, actions.version)
            invalidateCachedGroup(groupId)
            fetchGroup(groupId, masterKey)
            return true
        } else {
            Log.e(TAG, "Group change failed with status ${response.status}")
            return false
        }
    }

    var messageSender: com.vayunmathur.messages.signal.sending.MessageSender? = null

    private suspend fun distributeGroupChange(
        groupId: String,
        masterKey: ByteArray,
        changeBytes: ByteArray?,
        revision: Int,
    ) {
        val sender = messageSender ?: return
        val group = cache[groupId] ?: return
        val groupContext = com.vayunmathur.messages.signal.proto.SignalServiceProtos.GroupContextV2.newBuilder()
            .setMasterKey(com.google.protobuf.ByteString.copyFrom(masterKey))
            .setRevision(revision)
        if (changeBytes != null) {
            groupContext.setGroupChange(com.google.protobuf.ByteString.copyFrom(changeBytes))
        }
        val dm = com.vayunmathur.messages.signal.proto.SignalServiceProtos.DataMessage.newBuilder()
            .setGroupV2(groupContext.build())
            .setTimestamp(System.currentTimeMillis())
        val content = com.vayunmathur.messages.signal.proto.SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dm.build())
            .build()
        try {
            sender.sendGroupMessage(groupId, group.memberAcis, content, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to distribute group change to members", e)
        }
    }

    private suspend fun getGroupAuthPresentation(masterKey: ByteArray): ByteArray? {
        return try {
            val groupMasterKey = GroupMasterKey(masterKey)
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)
            val todaySeconds = (System.currentTimeMillis() / 1000 / 86400) * 86400
            val credential = getOrFetchCredential(todaySeconds) ?: return null
            val serverPublicParams = ServerPublicParams(SERVER_PUBLIC_PARAMS)
            val clientZkAuth = ClientZkAuthOperations(serverPublicParams)
            val authCredResponse = AuthCredentialWithPniResponse(credential)
            val authCred = clientZkAuth.receiveAuthCredentialWithPniAsServiceId(
                ServiceId.Aci(UUID.fromString(aci)),
                ServiceId.Pni(UUID.fromString(pni)),
                todaySeconds,
                authCredResponse,
            )
            val presentation = clientZkAuth.createAuthCredentialPresentation(
                java.security.SecureRandom(),
                groupSecretParams,
                authCred,
            )
            presentation.serialize()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get group auth presentation", e)
            null
        }
    }

    enum class AccessControl(val value: Int) {
        UNKNOWN(0), ANY(1), MEMBER(2), ADMINISTRATOR(3), UNSATISFIABLE(4)
    }

    enum class MemberRole(val value: Int) {
        UNKNOWN(0), DEFAULT(1), ADMINISTRATOR(2)
    }

    data class GroupMember(
        val aci: UUID,
        val role: MemberRole = MemberRole.UNKNOWN,
        val profileKey: ByteArray = ByteArray(0),
        val joinedAtRevision: Int = 0,
    )

    data class GroupAccessControl(
        val members: AccessControl,
        val addFromInviteLink: AccessControl,
        val attributes: AccessControl,
    )

    data class PendingMember(
        val serviceId: String,
        val role: MemberRole,
        val addedByUserId: UUID,
        val timestamp: Long,
    )

    data class RequestingMember(
        val aci: UUID,
        val profileKey: ByteArray,
        val timestamp: Long,
    )

    data class BannedMember(
        val serviceId: String,
        val timestamp: Long,
    )

    data class SignalGroup(
        val groupId: String,
        val title: String,
        val members: List<GroupMember>,
        val avatarPath: String?,
        val revision: Int,
        val description: String? = null,
        val disappearingMessagesDuration: Int = 0,
        val announcementsOnly: Boolean = false,
        val accessControl: GroupAccessControl? = null,
        val pendingMembers: List<PendingMember> = emptyList(),
        val requestingMembers: List<RequestingMember> = emptyList(),
        val bannedMembers: List<BannedMember> = emptyList(),
        val inviteLinkPassword: String? = null,
    ) {
        val memberAcis: List<String> get() = members.map { it.aci.toString() }

        fun findMemberOrEmpty(aci: UUID): GroupMember {
            return members.find { it.aci == aci } ?: GroupMember(aci)
        }
    }

    data class GroupChange(
        var groupMasterKey: ByteArray,
        val sourceServiceId: String? = null,
        var revision: Int = 0,
        val addMembers: MutableList<GroupMember> = mutableListOf(),
        val deleteMembers: MutableList<UUID> = mutableListOf(),
        val modifyMemberRoles: MutableList<Pair<UUID, MemberRole>> = mutableListOf(),
        val modifyMemberProfileKeys: MutableList<Pair<UUID, ByteArray>> = mutableListOf(),
        val addPendingMembers: MutableList<PendingMember> = mutableListOf(),
        val deletePendingMembers: MutableList<String> = mutableListOf(),
        val promotePendingMembers: MutableList<Pair<UUID, ByteArray>> = mutableListOf(),
        var modifyTitle: String? = null,
        var modifyAvatar: String? = null,
        var modifyDisappearingMessagesDuration: Int? = null,
        var modifyAttributesAccess: AccessControl? = null,
        var modifyMemberAccess: AccessControl? = null,
        var modifyAddFromInviteLinkAccess: AccessControl? = null,
        val addRequestingMembers: MutableList<RequestingMember> = mutableListOf(),
        val deleteRequestingMembers: MutableList<UUID> = mutableListOf(),
        val promoteRequestingMembers: MutableList<Pair<UUID, MemberRole>> = mutableListOf(),
        var modifyDescription: String? = null,
        var modifyAnnouncementsOnly: Boolean? = null,
        val addBannedMembers: MutableList<BannedMember> = mutableListOf(),
        val deleteBannedMembers: MutableList<String> = mutableListOf(),
        val promotePendingPniAciMembers: MutableList<Triple<UUID, ByteArray, UUID>> = mutableListOf(),
        var modifyInviteLinkPassword: String? = null,
    ) {
        fun isEmpty(): Boolean {
            return addMembers.isEmpty() && deleteMembers.isEmpty() &&
                modifyMemberRoles.isEmpty() && modifyMemberProfileKeys.isEmpty() &&
                addPendingMembers.isEmpty() &&
                promotePendingMembers.isEmpty() && modifyTitle == null &&
                modifyAvatar == null && modifyDisappearingMessagesDuration == null &&
                modifyAttributesAccess == null && modifyMemberAccess == null &&
                modifyAddFromInviteLinkAccess == null && addRequestingMembers.isEmpty() &&
                deleteRequestingMembers.isEmpty() && promoteRequestingMembers.isEmpty() &&
                modifyDescription == null && modifyAnnouncementsOnly == null &&
                addBannedMembers.isEmpty()
        }

        fun resolveConflict(group: SignalGroup) {
            if (modifyTitle != null && modifyTitle == group.title) modifyTitle = null
            if (modifyDescription != null && modifyDescription == group.description) modifyDescription = null
            if (modifyAvatar != null && modifyAvatar == group.avatarPath) modifyAvatar = null
            if (modifyDisappearingMessagesDuration != null &&
                modifyDisappearingMessagesDuration == group.disappearingMessagesDuration
            ) modifyDisappearingMessagesDuration = null
            if (modifyAttributesAccess != null && modifyAttributesAccess == group.accessControl?.attributes) {
                modifyAttributesAccess = null
            }
            if (modifyMemberAccess != null && modifyMemberAccess == group.accessControl?.members) {
                modifyAttributesAccess = null
            }
            if (modifyAddFromInviteLinkAccess != null &&
                modifyAddFromInviteLinkAccess == group.accessControl?.addFromInviteLink
            ) modifyAddFromInviteLinkAccess = null
            if (modifyAnnouncementsOnly != null && modifyAnnouncementsOnly == group.announcementsOnly) {
                modifyAnnouncementsOnly = null
            }
            val memberMap = group.members.associate { it.aci to it.role }
            val pendingSet = group.pendingMembers.map { it.serviceId }.toSet()
            val requestingSet = group.requestingMembers.map { it.aci }.toSet()
            addMembers.removeAll { memberMap.containsKey(it.aci) }
            promotePendingMembers.removeAll { memberMap.containsKey(it.first) }
            promoteRequestingMembers.removeAll { memberMap.containsKey(it.first) }
            addPendingMembers.removeAll { pendingSet.contains(it.serviceId) }
            addRequestingMembers.removeAll { requestingSet.contains(it.aci) }
            deletePendingMembers.removeAll { !pendingSet.contains(it) }
            deleteRequestingMembers.removeAll { !requestingSet.contains(it) }
            deleteMembers.removeAll { !memberMap.containsKey(it) }
            modifyMemberRoles.removeAll { memberMap[it.first] == it.second }
        }
    }

    data class GroupChangeState(
        val groupState: SignalGroup?,
        val groupChange: GroupChange?,
    )

    private val cache = ConcurrentHashMap<String, SignalGroup>()
    private val credentialCache = ConcurrentHashMap<Long, ByteArray>()
    private val activeCalls = ConcurrentHashMap<String, String>()

    fun updateActiveCall(groupId: String, callId: String): Boolean {
        val currentCallId = activeCalls[groupId]
        if (currentCallId != null && currentCallId == callId) {
            activeCalls.remove(groupId)
            return false
        }
        activeCalls[groupId] = callId
        return true
    }

    suspend fun fetchGroup(groupId: String, masterKey: ByteArray): SignalGroup? {
        return try {
            val auth = getGroupAuth(masterKey) ?: return null

            val response = SignalHttpClient.request(
                host = SignalHttpClient.STORAGE_HOST,
                method = "GET",
                path = "/v2/groups",
                contentType = "application/x-protobuf",
                username = auth.username,
                password = auth.password,
            )
            if (response.status != 200) return null

            val groupResponse = GroupResponse.parseFrom(response.bytes)
            val groupProto = groupResponse.group

            // Decrypt zkgroup-encrypted fields (title/description/timer/members).
            // Ref signalmeow/groups.go decryptGroup + decryptMember.
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey))
            val cipher = ClientZkGroupCipher(groupSecretParams)

            val title = try {
                GroupAttributeBlob.parseFrom(cipher.decryptBlob(groupProto.title.toByteArray()))
                    .title.takeIf { it.isNotEmpty() } ?: groupId.take(8)
            } catch (e: Exception) {
                groupId.take(8)
            }

            val description = try {
                if (!groupProto.description.isEmpty) {
                    GroupAttributeBlob.parseFrom(cipher.decryptBlob(groupProto.description.toByteArray()))
                        .descriptionText.takeIf { it.isNotEmpty() }
                } else null
            } catch (e: Exception) {
                null
            }

            val disappearing = try {
                if (!groupProto.disappearingMessagesTimer.isEmpty) {
                    GroupAttributeBlob.parseFrom(
                        cipher.decryptBlob(groupProto.disappearingMessagesTimer.toByteArray())
                    ).disappearingMessagesDuration
                } else 0
            } catch (e: Exception) {
                0
            }

            val decryptedMembers = groupProto.membersList.mapNotNull { member ->
                try {
                    val serviceId = cipher.decrypt(UuidCiphertext(member.userId.toByteArray()))
                    GroupMember(
                        aci = serviceId.rawUUID,
                        role = MemberRole.entries.find { it.value == member.role.number } ?: MemberRole.UNKNOWN,
                        profileKey = member.profileKey.toByteArray(),
                        joinedAtRevision = member.joinedAtVersion,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decrypt group member in $groupId: ${e.message}")
                    null
                }
            }

            val group = SignalGroup(
                groupId = groupId,
                title = title,
                members = decryptedMembers,
                avatarPath = groupProto.avatarUrl.ifEmpty { null },
                revision = groupProto.version,
                description = description,
                disappearingMessagesDuration = disappearing,
                announcementsOnly = groupProto.announcementsOnly,
                accessControl = groupProto.accessControl?.let {
                    GroupAccessControl(
                        members = AccessControl.entries.find { ac -> ac.value == it.members.number } ?: AccessControl.UNKNOWN,
                        addFromInviteLink = AccessControl.entries.find { ac -> ac.value == it.addFromInviteLink.number } ?: AccessControl.UNKNOWN,
                        attributes = AccessControl.entries.find { ac -> ac.value == it.attributes.number } ?: AccessControl.UNKNOWN,
                    )
                },
                inviteLinkPassword = groupProto.inviteLinkPassword?.let {
                    if (it.isEmpty) null else Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)
                },
            )

            cache[groupId] = group
            groupStore.storeGroup(
                SignalGroupEntity(
                    groupId = groupId,
                    masterKey = masterKey,
                    title = group.title,
                    avatarUrl = group.avatarPath,
                    revision = group.revision,
                )
            )
            group
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch group $groupId", e)
            null
        }
    }

    suspend fun retrieveGroupByID(groupId: String, masterKey: ByteArray, revision: Int = 0): SignalGroup? {
        val cached = cache[groupId]
        if (cached != null && cached.revision >= revision) {
            return cached
        }
        return fetchGroup(groupId, masterKey)
    }

    suspend fun getOrFetchGroup(groupId: String, masterKey: ByteArray): SignalGroup? {
        cache[groupId]?.let { return it }
        return fetchGroup(groupId, masterKey)
    }

    fun getCachedGroup(groupId: String): SignalGroup? = cache[groupId]

    fun invalidateCachedGroup(groupId: String) {
        cache.remove(groupId)
    }

    fun deriveGroupId(masterKey: ByteArray): String {
        val groupMasterKey = GroupMasterKey(masterKey)
        val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)
        val groupId = groupSecretParams.publicParams.groupIdentifier.serialize()
        return Base64.encodeToString(groupId, Base64.NO_WRAP)
    }

    suspend fun storeMasterKey(groupId: String, masterKey: ByteArray) {
        groupStore.storeGroup(
            SignalGroupEntity(
                groupId = groupId,
                masterKey = masterKey,
                title = "",
                avatarUrl = null,
                revision = 0,
            )
        )
    }

    suspend fun downloadGroupAvatar(avatarPath: String, masterKey: ByteArray): ByteArray? {
        return try {
            val response = SignalHttpClient.request(
                host = SignalHttpClient.CDN1_HOST,
                method = "GET",
                path = avatarPath,
                username = aci,
                password = password,
            )
            if (!response.isSuccess) return null
            val encryptedAvatar = response.bytes
            decryptGroupAvatar(encryptedAvatar, masterKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download group avatar", e)
            null
        }
    }

    suspend fun uploadGroupAvatar(groupId: String, avatarBytes: ByteArray): Boolean {
        Log.w(TAG, "uploadGroupAvatar: group encryption operations not available")
        return false
    }

    suspend fun setGroupDescription(groupId: String, newDescription: String): Boolean =
        applyGroupChange(groupId) { cipher, actions ->
            val blob = GroupAttributeBlob.newBuilder().setDescriptionText(newDescription).build()
            actions.setModifyDescription(
                GroupChangeProto.Actions.ModifyDescriptionAction.newBuilder()
                    .setDescription(cipher.encryptAttribute(blob)).build()
            )
            true
        }

    private fun decryptGroupAvatar(encryptedAvatar: ByteArray, masterKey: ByteArray): ByteArray {
        return try {
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey))
            val cipher = ClientZkGroupCipher(groupSecretParams)
            GroupAttributeBlob.parseFrom(cipher.decryptBlob(encryptedAvatar)).avatar.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt group avatar", e)
            ByteArray(0)
        }
    }

    private data class GroupAuthResult(val username: String, val password: String)

    private suspend fun getGroupAuth(masterKey: ByteArray): GroupAuthResult? {
        return try {
            val groupMasterKey = GroupMasterKey(masterKey)
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)
            val groupPublicParams = groupSecretParams.publicParams

            val todaySeconds = (System.currentTimeMillis() / 1000 / 86400) * 86400
            val credential = getOrFetchCredential(todaySeconds) ?: return null

            val serverPublicParams = ServerPublicParams(SERVER_PUBLIC_PARAMS)
            val clientZkAuth = ClientZkAuthOperations(serverPublicParams)

            val authCredResponse = AuthCredentialWithPniResponse(credential)
            val authCred = clientZkAuth.receiveAuthCredentialWithPniAsServiceId(
                ServiceId.Aci(java.util.UUID.fromString(aci)),
                ServiceId.Pni(java.util.UUID.fromString(pni)),
                todaySeconds,
                authCredResponse,
            )
            val presentation = clientZkAuth.createAuthCredentialPresentation(
                java.security.SecureRandom(),
                groupSecretParams,
                authCred,
            )

            GroupAuthResult(
                username = hexEncode(groupPublicParams.serialize()),
                password = hexEncode(presentation.serialize()),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get group auth", e)
            null
        }
    }

    private suspend fun getOrFetchCredential(todaySeconds: Long): ByteArray? {
        credentialCache[todaySeconds]?.let { return it }
        return try {
            val sevenDays = todaySeconds + 7 * 86400
            val path = "/v1/certificate/auth/group?redemptionStartSeconds=$todaySeconds&redemptionEndSeconds=$sevenDays&pniAsServiceId=true"
            val response = ws.sendRequest("GET", path)
            if (response.status != 200) return null

            val json = JSONObject(response.body.toStringUtf8())
            val pniFromResponse = json.optString("pni", "")
            if (pniFromResponse.isNotEmpty() && pniFromResponse != pni) {
                Log.e(TAG, "Mismatching PNI in group credentials: $pniFromResponse != $pni")
                return null
            }
            val credentials = json.getJSONArray("credentials")
            for (i in 0 until credentials.length()) {
                val cred = credentials.getJSONObject(i)
                val redemptionTime = cred.getLong("redemptionTime")
                val credBytes = Base64.decode(cred.getString("credential"), Base64.NO_WRAP)
                credentialCache[redemptionTime] = credBytes
            }
            credentialCache[todaySeconds]
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch group credentials", e)
            null
        }
    }

    companion object {
        private const val TAG = "GroupManager"

        private val SERVER_PUBLIC_PARAMS = Base64.decode(
            "AMhf5ywVwITZMsff/eCyudZx9JDmkkkbV6PInzG4p8x3VqVJSFiMvnvlEKWuRob/1eaIetR31IYeAbm0NdOuHH8Qi+Rexi1wLlpzIo1gstHWBfZzy1+qHRV5A4TqPp15YzBPm0WSggW6PbSn+F4lf57VCnHF7p8SvzAA2ZZJPYJURt8X7bbg+H3i+PEjH9DXItNEqs2sNcug37xZQDLm7X36nOoGPs54XsEGzPdEV+itQNGUFEjY6X9Uv+Acuks7NpyGvCoKxGwgKgE5XyJ+nNKlyHHOLb6N1NuHyBrZrgtY/JYJHRooo5CEqYKBqdFnmbTVGEkCvJKxLnjwKWf+fEPoWeQFj5ObDjcKMZf2Jm2Ae69x+ikU5gBXsRmoF94GXTLfN0/vLt98KDPnxwAQL9j5V1jGOY8jQl6MLxEs56cwXN0dqCnImzVH3TZT1cJ8SW1BRX6qIVxEzjsSGx3yxF3suAilPMqGRp4ffyopjMD1JXiKR2RwLKzizUe5e8XyGOy9fplzhw3jVzTRyUZTRSZKkMLWcQ/gv0E4aONNqs4P+NameAZYOD12qRkxosQQP5uux6B2nRyZ7sAV54DgFyLiRcq1FvwKw2EPQdk4HDoePrO/RNUbyNddnM/mMgj4FW65xCoT1LmjrIjsv/Ggdlx46ueczhMgtBunx1/w8k8V+l8LVZ8gAT6wkU5J+DPQalQguMg12Jzug3q4TbdHiGCmD9EunCwOmsLuLJkz6EcSYXtrlDEnAM+hicw7iergYLLlMXpfTdGxJCWJmP4zqUFeTTmsmhsjGBt7NiEB/9pFFEB3pSbf4iiUukw63Eo8Aqnf4iwob6X1QviCWuc8t0LUlT9vALgh/f2DPVOOmR0RW6bgRvc7DSF20V/omg+YBw==",
            Base64.NO_WRAP,
        )

        private fun hexEncode(bytes: ByteArray): String {
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun cleanupStringProperty(property: String): String {
            val cleaned = property.filter { ch ->
                val type = Character.getType(ch)
                type != Character.CONTROL.toInt() &&
                    type != Character.FORMAT.toInt() &&
                    type != Character.SURROGATE.toInt() &&
                    type != Character.UNASSIGNED.toInt() &&
                    type != Character.PRIVATE_USE.toInt() &&
                    type != Character.LINE_SEPARATOR.toInt() &&
                    type != Character.PARAGRAPH_SEPARATOR.toInt()
            }
            return cleaned.trim()
        }
    }
}

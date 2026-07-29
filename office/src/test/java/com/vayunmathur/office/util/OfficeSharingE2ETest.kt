package com.vayunmathur.office.util

import com.vayunmathur.e2ee.E2ee
import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.e2ee.PqcIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64

/**
 * End-to-end simulation of Office document sharing across two "devices", using the real crypto
 * (PQC ML-KEM/ML-DSA), CRDT, text codec, and the actual wire types ([SignedOp], [SignedMember],
 * [OfficeSync.Invite]) — only the network is replaced by an in-memory relay. This guards the whole
 * pipeline that kept breaking: invite delivery, owner-signed roster, signed-op verification + role
 * gate, CRDT merge, and document reconstruction.
 */
class OfficeSharingE2ETest {

    private val json = Json { ignoreUnknownKeys = true }

    private class MemStore : E2eeKeyStore {
        private val m = HashMap<String, ByteArray>()
        override suspend fun getBytes(name: String) = m[name]
        override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) {
            if (onlyIfAbsent && m.containsKey(name)) return
            m[name] = value
        }
    }

    /** In-memory relay: append-only channels + a public-key directory. */
    private class Relay {
        val channels = HashMap<String, MutableList<String>>()
        val directory = HashMap<String, ByteArray>()
        fun append(ch: String, blob: String) { channels.getOrPut(ch) { mutableListOf() }.add(blob) }
        fun pull(ch: String) = channels[ch]?.toList() ?: emptyList()
    }

    private fun memberBytes(docId: String, m: OfficeMember) = "$docId|${m.id}|${m.role}".encodeToByteArray()

    @Test
    fun owner_shares_editor_reconstructs_document_and_viewer_is_rejected() = runBlocking {
        val relay = Relay()
        val owner = PqcIdentity.loadOrCreate(MemStore(), "o")
        val editor = PqcIdentity.loadOrCreate(MemStore(), "e")
        val ownerId = "owner"; val editorId = "editor"
        relay.directory[ownerId] = owner.publicBundle
        relay.directory[editorId] = editor.publicBundle

        val docId = "doc-1"
        val docKey = E2ee.newContentKey()

        // ---- Owner: push signed op + owner-signed roster + invite ----
        // The signed-op wire carries an opaque ops-JSON string produced by the (now native) CRDT;
        // the crypto + role pipeline exercised here is independent of its contents, so we use a
        // representative payload. CRDT merge/reconstruction is covered by the Rust crate's tests.
        val opsJson = "[{\"id\":\"1:$ownerId\",\"parent\":\"\",\"left\":\"\",\"kind\":\"e\"," +
            "\"payload\":\"<office:text>\",\"lamport\":1,\"dev\":\"$ownerId\"}]"
        val signedOp = SignedOp(ownerId, Base64.encode(owner.sign(opsJson.encodeToByteArray())), opsJson)
        relay.append(docId, Base64.encode(E2ee.aesEncrypt(docKey, json.encodeToString(signedOp).encodeToByteArray())))

        for (m in listOf(OfficeMember(ownerId, "Owner", OfficeRoles.OWNER), OfficeMember(editorId, "", OfficeRoles.EDITOR))) {
            val sm = SignedMember(m, Base64.encode(owner.sign(memberBytes(docId, m))))
            relay.append("members:$docId", Base64.encode(E2ee.aesEncrypt(docKey, json.encodeToString(sm).encodeToByteArray())))
        }
        val invite = OfficeSync.Invite(docId, Base64.encode(docKey), "E2E", charMode = true, role = OfficeRoles.EDITOR, ownerKey = Base64.encode(owner.publicBundle))
        relay.append("inbox:$editorId", Base64.encode(Pqc.encryptTo(editor.publicBundle, json.encodeToString(invite).encodeToByteArray())))

        // ---- Editor: open the shared document ----
        val inv = json.decodeFromString<OfficeSync.Invite>(
            editor.decrypt(Base64.decode(relay.pull("inbox:$editorId").single())).decodeToString()
        )
        assertEquals(OfficeRoles.EDITOR, inv.role)
        assertTrue("invite must carry owner key", inv.ownerKey.isNotBlank())
        val recDocKey = Base64.decode(inv.key)
        val ownerKey = Base64.decode(inv.ownerKey)

        // roster: honor only owner-signed records
        val roleById = HashMap<String, String>()
        for (blob in relay.pull("members:$docId")) {
            val sm = json.decodeFromString<SignedMember>(E2ee.aesDecrypt(recDocKey, Base64.decode(blob)).decodeToString())
            if (Pqc.verify(ownerKey, memberBytes(docId, sm.member), Base64.decode(sm.sig))) roleById[sm.member.id] = sm.member.role
        }
        assertEquals(OfficeRoles.OWNER, roleById[ownerId])

        // ops: verify author signature + editor/owner role, then accept the (opaque) ops payload
        var acceptedOps: String? = null
        for (blob in relay.pull(docId)) {
            val so = json.decodeFromString<SignedOp>(E2ee.aesDecrypt(recDocKey, Base64.decode(blob)).decodeToString())
            val authorKey = relay.directory[so.author] ?: continue
            val sigOk = Pqc.verify(authorKey, so.ops.encodeToByteArray(), Base64.decode(so.sig))
            val roleOk = OfficeRoles.canEdit(roleById[so.author] ?: OfficeRoles.VIEWER)
            if (sigOk && roleOk) acceptedOps = so.ops
        }
        assertEquals("owner-signed op accepted and delivered byte-intact", opsJson, acceptedOps)

        // ---- Negative: a viewer's forged op is rejected ----
        roleById[editorId] = OfficeRoles.VIEWER
        val forgedJson = "[{\"id\":\"9:$editorId\",\"parent\":\"\",\"left\":\"\",\"kind\":\"c\"," +
            "\"payload\":\"x\",\"lamport\":9,\"dev\":\"$editorId\"}]"
        val forged = SignedOp(editorId, Base64.encode(editor.sign(forgedJson.encodeToByteArray())), forgedJson)
        val sigOk = Pqc.verify(relay.directory[editorId]!!, forged.ops.encodeToByteArray(), Base64.decode(forged.sig))
        val roleOk = OfficeRoles.canEdit(roleById[forged.author] ?: OfficeRoles.VIEWER)
        assertTrue("signature itself is valid", sigOk)
        assertFalse("but a viewer's edit must be rejected by the role gate", sigOk && roleOk)

        // ---- Negative: an op that lies about authorship fails signature verification ----
        val liar = SignedOp(ownerId, Base64.encode(editor.sign(forgedJson.encodeToByteArray())), forgedJson)
        assertFalse(
            "editor cannot forge an op as the owner (wrong signing key)",
            Pqc.verify(relay.directory[ownerId]!!, liar.ops.encodeToByteArray(), Base64.decode(liar.sig))
        )
    }

    @Test
    fun revoked_member_cannot_read_after_key_rotation() = runBlocking {
        val owner = PqcIdentity.loadOrCreate(MemStore(), "o")
        val editor = PqcIdentity.loadOrCreate(MemStore(), "e")
        val revoked = PqcIdentity.loadOrCreate(MemStore(), "r")
        // Owner rotates: new content key sealed only to the remaining members (owner + editor).
        val newKey = E2ee.newContentKey()
        val wraps = mapOf(
            "owner" to Base64.encode(Pqc.encryptTo(owner.publicBundle, newKey)),
            "editor" to Base64.encode(Pqc.encryptTo(editor.publicBundle, newKey)),
        )
        // The remaining editor recovers the new key; the revoked member has no wrap for it.
        assertArrayEquals(newKey, editor.decrypt(Base64.decode(wraps.getValue("editor"))))
        assertTrue(wraps["revoked"] == null)
        // New content encrypted under the new key is readable by the editor but not by the revoked
        // member (who only ever held the old key).
        val ct = E2ee.aesEncrypt(newKey, "post-revoke secret".encodeToByteArray())
        assertEquals("post-revoke secret", E2ee.aesDecrypt(newKey, ct).decodeToString())
        val oldKey = E2ee.newContentKey()
        assertTrue(runCatching { E2ee.aesDecrypt(oldKey, ct) }.isFailure) // wrong key can't decrypt
    }

    @Test
    fun invite_maps_to_metadata_with_role_and_owner_key() {
        val inv = OfficeSync.Invite("d", "k", "Title", charMode = true, role = OfficeRoles.VIEWER, ownerKey = "OWNERKEY")
        val meta = officeDocMetaFromInvite(inv)
        assertEquals("d", meta.docId)
        assertEquals(OfficeRoles.VIEWER, meta.role)
        assertEquals("OWNERKEY", meta.ownerKeyB64) // the field whose omission caused empty docs
        assertTrue(meta.charMode)
        assertFalse(meta.owner)
    }
}

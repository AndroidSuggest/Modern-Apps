package com.vayunmathur.passwords.platform.cable

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * caBLE v2 key derivations from the 16-byte QR secret.
 *
 * Mirrors Chromium's `device::cablev2::Derive` (`//device/fido/cable/v2_handshake.cc`):
 * HKDF-SHA256 with `IKM = secret`, `salt = nonce` (empty for the QR-derived keys), and
 * `info = little-endian uint32(purpose)`.
 *
 * NOTE: the numeric [Purpose] values, output lengths, and the EID layout are byte-exact protocol
 * constants copied from Chromium. They must be validated against Chromium/CTAP test vectors before
 * this is relied upon (see the plan's verification section) — a mismatch surfaces only as the
 * generic "make sure Bluetooth is on" browser error.
 */
object CableKeys {

    private const val PURPOSE_EID_KEY = 1
    private const val PURPOSE_TUNNEL_ID = 2
    private const val PURPOSE_PSK = 3
    private const val PURPOSE_PAIRED_SECRET = 4
    private const val PURPOSE_IDENTITY_KEY_SEED = 5
    private const val PURPOSE_PER_CONTACT_ID_SECRET = 6

    /** `DerivedValueType` enum values from Chromium. */
    enum class Purpose(val value: Int) {
        EID_KEY(PURPOSE_EID_KEY),
        TUNNEL_ID(PURPOSE_TUNNEL_ID),
        PSK(PURPOSE_PSK),
        PAIRED_SECRET(PURPOSE_PAIRED_SECRET),
        IDENTITY_KEY_SEED(PURPOSE_IDENTITY_KEY_SEED),
        PER_CONTACT_ID_SECRET(PURPOSE_PER_CONTACT_ID_SECRET),
    }

    // Output sizes (bytes).
    const val EID_KEY_SIZE = 64      // AES key + HMAC key material for the BLE EID
    const val TUNNEL_ID_SIZE = 16
    const val PSK_SIZE = 32
    const val NONCE_SIZE = 10        // BLE EID nonce

    private const val HMAC = "HmacSHA256"
    private const val HASH_LEN = 32
    private const val HKDF_MAX_MULTIPLIER = 255
    private const val BYTE_MASK = 0xFF
    private const val BYTE_SHIFT_1 = 8
    private const val BYTE_SHIFT_2 = 16
    private const val BYTE_SHIFT_3 = 24

    /**
     * HKDF-SHA256 (RFC 5869) as used by caBLE, on the platform [Mac] (Conscrypt) —
     * no Bouncy Castle. Empty/`null` salt = RFC 5869 zero salt (BoringSSL parity).
     */
    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        require(length <= HKDF_MAX_MULTIPLIER * HASH_LEN) { "HKDF length too large" }
        // Extract.
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val prk = hmac(actualSalt, ikm)
        // Expand.
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(prk, HMAC)) }
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(data)

    /** Chromium `Derive<N>(secret, salt, purpose)`. */
    fun derive(secret: ByteArray, salt: ByteArray?, purpose: Purpose, length: Int): ByteArray =
        hkdf(secret, salt, leUint32(purpose.value), length)

    /** Key that encrypts/authenticates the BLE ephemeral ID (EID). */
    fun eidKey(qrSecret: ByteArray): ByteArray =
        derive(qrSecret, null, Purpose.EID_KEY, EID_KEY_SIZE)

    /** Tunnel routing identifier used to pick + address the tunnel server. */
    fun tunnelId(qrSecret: ByteArray): ByteArray =
        derive(qrSecret, null, Purpose.TUNNEL_ID, TUNNEL_ID_SIZE)

    /**
     * Pre-shared key mixed into the Noise KNpsk0 handshake. Per Chromium
     * `v2_authenticator.cc`, the salt is the 16-byte plaintext EID that is BLE-advertised, binding
     * the handshake to the advertised beacon.
     */
    fun psk(qrSecret: ByteArray, plaintextEid: ByteArray): ByteArray =
        derive(qrSecret, plaintextEid, Purpose.PSK, PSK_SIZE)

    /** Encodes an int as a little-endian uint32 (the HKDF `info` field). */
    fun leUint32(value: Int): ByteArray = byteArrayOf(
        (value and BYTE_MASK).toByte(),
        ((value ushr BYTE_SHIFT_1) and BYTE_MASK).toByte(),
        ((value ushr BYTE_SHIFT_2) and BYTE_MASK).toByte(),
        ((value ushr BYTE_SHIFT_3) and BYTE_MASK).toByte(),
    )
}

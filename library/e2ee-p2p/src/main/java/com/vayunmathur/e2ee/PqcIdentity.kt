package com.vayunmathur.e2ee

/**
 * A device's post-quantum identity for the Office app: an ML-KEM keypair (encryption) plus an
 * ML-DSA keypair (signatures), persisted via an [E2eeKeyStore]. Keys are stored/handled as DER
 * (byte-compatible with the previous Bouncy Castle encoding). Private keys never leave the device;
 * [publicBundle] (both public keys) is what gets registered in the directory / used to encrypt to you.
 */
class PqcIdentity internal constructor(
    val publicBundle: ByteArray,
    private val kemPrivate: ByteArray,
    private val dsaPrivate: ByteArray,
) {
    /** Decrypts data encrypted to this identity via [Pqc.encryptTo]. */
    fun decrypt(ciphertext: ByteArray): ByteArray = Pqc.decrypt(kemPrivate, ciphertext)

    /** Signs data with this identity's ML-DSA key. */
    fun sign(data: ByteArray): ByteArray = Pqc.signWith(dsaPrivate, data)

    companion object {
        /** Loads the persisted PQC keypairs, generating + storing them on first use. */
        suspend fun loadOrCreate(store: E2eeKeyStore, prefix: String = "pqc"): PqcIdentity {
            val kemPub = store.getBytes("${prefix}KemPub")
            val kemPriv = store.getBytes("${prefix}KemPriv")
            val dsaPub = store.getBytes("${prefix}DsaPub")
            val dsaPriv = store.getBytes("${prefix}DsaPriv")
            if (kemPub != null && kemPriv != null && dsaPub != null && dsaPriv != null) {
                return PqcIdentity(Pqc.bundle(kemPub, dsaPub), kemPriv, dsaPriv)
            }
            val (kemPubNew, kemPrivNew) = Pqc.generateKem()
            val (dsaPubNew, dsaPrivNew) = Pqc.generateDsa()
            store.setBytes("${prefix}KemPub", kemPubNew, onlyIfAbsent = true)
            store.setBytes("${prefix}KemPriv", kemPrivNew, onlyIfAbsent = true)
            store.setBytes("${prefix}DsaPub", dsaPubNew, onlyIfAbsent = true)
            store.setBytes("${prefix}DsaPriv", dsaPrivNew, onlyIfAbsent = true)
            return PqcIdentity(Pqc.bundle(kemPubNew, dsaPubNew), kemPrivNew, dsaPrivNew)
        }
    }
}

package com.vayunmathur.findfamily.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Instant

/**
 * A link id is the server-side *recipient id* — it is what `/api/location/publish` addresses
 * and what the `findfamily.cc/view/<id>` URL resolves to — and it shares that namespace with
 * [com.vayunmathur.findfamily.data.User] ids, which are random 64-bit values.
 *
 * So it must be globally unique, not a per-device row number: with Room's `autoGenerate` every
 * device's first link was id 1, so every first `/view/1` URL pointed at the same server queue
 * and every device published its encrypted location into it.
 *
 * Positive-only, matching the userid generation in `Networking.init` — the server stores these
 * as ULong and a negative value round-trips inconsistently.
 */
fun newTemporaryLinkId(): Long = Random.nextLong(from = 1, until = Long.MAX_VALUE)

/**
 * An anonymous location-sharing link. Post-quantum only: there is no RSA keypair and no
 * classic fallback, so a link either has a usable ML-KEM/ML-DSA bundle or it is not created
 * at all. Both key fields are non-null for that reason.
 */
@Serializable
@Entity
data class TemporaryLink(
    val name: String,
    val deleteAt: Instant,

    /** PQC ephemeral public bundle (base64 [4B kemLen][kemPub][dsaPub]) — what we encrypt to. */
    val pqcPublicKey: String,
    /**
     * PQC ephemeral private bundle (base64 [4B kemLen][kemPriv][dsaPriv] — kemPriv =
     * seed+expanded via BC compat). Handed to the recipient in the URL fragment; never sent
     * to the server.
     */
    val pqcKey: String,

    @PrimaryKey override val id: Long = newTemporaryLinkId(),
): DatabaseItem
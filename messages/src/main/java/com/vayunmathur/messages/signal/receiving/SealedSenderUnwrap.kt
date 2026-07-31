@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vayunmathur.messages.signal.receiving

import org.signal.libsignal.internal.Native
import org.signal.libsignal.protocol.state.IdentityKeyStore

/**
 * Bridges to libsignal's `SealedSessionCipher_DecryptToUsmc` native binding.
 *
 * libsignal 0.86.5 has no public `SealedSessionCipher.decryptToUsmc()` method; the only way to
 * recover the `UnidentifiedSenderMessageContent` (and therefore the inner ciphertext type and
 * content hint) is the `org.signal.libsignal.internal.Native` binding.
 *
 * `Native` is declared `internal` in libsignal's own Kotlin sources, so it is not normally visible
 * from another module. The file-level `@Suppress` above is the standard escape hatch: the JVM
 * class and method are both public (verified with javap — an `internal` *class* does not get its
 * public members name-mangled), so the emitted call site is an ordinary `invokestatic`.
 *
 * Note the compiler warns that suppressing `INVISIBLE_REFERENCE` is unspecified behaviour and may
 * stop working in a future Kotlin release. If it ever does, reverting this file to a small Java
 * shim restores it, since Java interop does not enforce Kotlin's `internal`.
 * // UNVERIFIED: relies on an internal libsignal binding.
 */
internal object SealedSenderUnwrap {

    @Throws(Exception::class)
    fun decryptToUsmc(ciphertext: ByteArray, identityStore: IdentityKeyStore): Long =
        Native.SealedSessionCipher_DecryptToUsmc(ciphertext, identityStore)
}

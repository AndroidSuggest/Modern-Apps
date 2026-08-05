package com.vayunmathur.appstore.data.security

import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [SignedJarIndex] is the check the whole F-Droid trust chain rests on: the per-APK hash
 * and signer fingerprint only bind an APK to the index, so if an unauthenticated index
 * were accepted a hostile mirror could simply state its own APK's hash and key.
 *
 * These tests cover the fail-closed paths, which are the security-relevant ones — every
 * way an index can fail to be properly signed must raise rather than silently pass.
 * Producing a *validly* signed JAR needs a real PKCS#7 signer (`jarsigner` and a
 * keystore), so the accept path is exercised on-device instead; what must never regress
 * is that any of the states below is treated as "unsigned but fine".
 */
class SignedJarIndexTest {

    private val pinnedFingerprint = "0".repeat(64)
    private val temp: File = File.createTempFile("signedjar", "dir").let {
        it.delete(); it.mkdirs(); it
    }

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    /** A plain, unsigned JAR — no META-INF/MANIFEST.MF at all. */
    private fun unsignedJarWithoutManifest(vararg entries: Pair<String, String>): File {
        val f = File(temp, "no-manifest-${entries.hashCode()}.jar")
        JarOutputStream(f.outputStream()).use { out ->
            for ((name, body) in entries) {
                out.putNextEntry(JarEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return f
    }

    /** A JAR that has a manifest but no signature block, i.e. nothing is actually signed. */
    private fun jarWithManifestButNoSignature(vararg entries: Pair<String, String>): File {
        val f = File(temp, "manifest-only-${entries.hashCode()}.jar")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        }
        JarOutputStream(f.outputStream(), manifest).use { out ->
            for ((name, body) in entries) {
                out.putNextEntry(JarEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return f
    }

    // --- fail-closed ---

    @Test
    fun aJarWithNoManifestIsRejected() {
        val jar = unsignedJarWithoutManifest("entry.json" to """{"repo":1}""")
        val e = assertFailsWith<SignedJarIndex.VerificationException> {
            SignedJarIndex.readVerified(jar, "entry.json", pinnedFingerprint)
        }
        assertTrue(e.message!!.contains("not signed"), e.message!!)
    }

    @Test
    fun anUnsignedEntryIsRejectedRatherThanTreatedAsTrusted() {
        // This is the important one: JarFile happily returns content for an unsigned entry
        // and simply reports no certificates, so "no certificates" has to mean "reject".
        val jar = jarWithManifestButNoSignature("entry.json" to """{"repo":1}""")
        val e = assertFailsWith<SignedJarIndex.VerificationException> {
            SignedJarIndex.readVerified(jar, "entry.json", pinnedFingerprint)
        }
        assertTrue(e.message!!.contains("not covered by the JAR signature"), e.message!!)
    }

    @Test
    fun aMissingEntryIsRejected() {
        val jar = jarWithManifestButNoSignature("something-else.json" to "{}")
        val e = assertFailsWith<SignedJarIndex.VerificationException> {
            SignedJarIndex.readVerified(jar, "entry.json", pinnedFingerprint)
        }
        assertTrue(e.message!!.contains("does not contain"), e.message!!)
    }

    @Test
    fun aCorruptJarIsRejectedWithoutLeakingAnException() {
        val f = File(temp, "corrupt.jar")
        f.writeBytes(ByteArray(512) { 0x41 })
        // Not a VerificationException — the point is that it throws rather than returning
        // unverified content, and the caller treats any throw as a failed sync.
        assertFailsWith<Exception> {
            SignedJarIndex.readVerified(f, "entry.json", pinnedFingerprint)
        }
    }

    @Test
    fun anEmptyFileIsRejected() {
        val f = File(temp, "empty.jar")
        f.writeBytes(ByteArray(0))
        assertFailsWith<Exception> {
            SignedJarIndex.readVerified(f, "entry.json", pinnedFingerprint)
        }
    }

    // --- fingerprint helpers used to report pin mismatches ---

    @Test
    fun fingerprintsAreLowercaseHexSha256() {
        val hex = ApkCertificates.sha256(ByteArray(0))
        assertEquals(64, hex.length)
        assertEquals(hex.lowercase(), hex)
        // SHA-256 of the empty input, a known vector.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex)
    }

    @Test
    fun abbreviateShowsTheFirstEightBytesUppercase() {
        val fp = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals("E3:B0:C4:42:98:FC:1C:14", ApkCertificates.abbreviate(fp))
    }
}

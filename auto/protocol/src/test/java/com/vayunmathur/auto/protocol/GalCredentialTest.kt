package com.vayunmathur.auto.protocol

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the shipped GAL credential without a device.
 *
 * The PEMs are read straight out of `src/main/assets` rather than duplicated into test
 * resources, so this fails if the shipped files are wrong rather than passing against a
 * private copy.
 */
class GalCredentialTest {

    private fun asset(name: String): String {
        val file = File("src/main/assets/gal", name)
        assertTrue(file.exists(), "missing shipped asset ${file.absolutePath}")
        return file.readText()
    }

    @Test
    fun `the shipped leaf chains to the shipped GAL root`() {
        val factory = CertificateFactory.getInstance("X.509")
        val leaf = factory.generateCertificate(
            asset("client-cert.pem").byteInputStream(),
        ) as X509Certificate
        val root = factory.generateCertificate(
            asset("root.pem").byteInputStream(),
        ) as X509Certificate

        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("GAL", root)
        }
        val trust = TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(anchors) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

        // Throws if the chain does not validate.
        trust.checkClientTrusted(arrayOf(leaf, root), "RSA")
        assertTrue(
            leaf.subjectX500Principal.name.contains("O=CarService"),
            "unexpected leaf subject ${leaf.subjectX500Principal}",
        )
    }

    @Test
    fun `the engine is a server that demands a client certificate`() {
        val engine = GalCredential.serverEngine(TestTls.context())
        assertEquals(false, engine.useClientMode, "the phone is the TLS server, not the client")
        assertTrue(engine.needClientAuth, "the head unit must present a GAL-signed cert")
    }

    @Test
    fun `a mutually authenticated handshake completes`() {
        val context = TestTls.context()
        val server = GalCredential.serverEngine(context)
        val car = TestTls.carEngine(context)

        TestTls.handshake(server, car)

        assertEquals("TLSv1.2", server.session.protocol)
        val peer = server.session.peerCertificates.first() as X509Certificate
        assertTrue(
            peer.subjectX500Principal.name.contains("CarService"),
            "server should have authenticated the client, saw ${peer.subjectX500Principal}",
        )
    }
}

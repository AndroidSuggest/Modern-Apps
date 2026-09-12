package com.vayunmathur.auto.protocol

import java.io.InputStream
import java.io.OutputStream

/**
 * A [GalTransport] over an ordinary stream pair.
 *
 * Covers both transports: USB hands back a `ParcelFileDescriptor` that is read as a plain
 * file descriptor (`carsetup/setup/UsbConnectionHelper.java`), and wireless ends up at a TCP
 * socket. Neither needs anything the other does not.
 */
class StreamTransport(
    private val input: InputStream,
    private val output: OutputStream,
) : GalTransport {

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        input.read(buffer, offset, length)

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        output.write(bytes, offset, length)
    }

    override fun flush() {
        output.flush()
    }

    override fun close() {
        // Close both even if the first throws, so a failure on one does not leak the other.
        try {
            input.close()
        } finally {
            output.close()
        }
    }
}

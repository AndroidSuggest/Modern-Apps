package com.vayunmathur.auto.protocol

import java.io.Closeable

/**
 * A bidirectional byte stream to a head unit.
 *
 * Both GAL transports reduce to this. USB is an `UsbAccessory` opened as a
 * `ParcelFileDescriptor` and read as an ordinary file descriptor
 * (`carsetup/setup/UsbConnectionHelper.java`); wireless is a TCP socket after the Bluetooth
 * RFCOMM bootstrap hands over the credentials. Nothing above this layer knows which it has.
 */
interface GalTransport : Closeable {

    /**
     * Reads at least one byte, blocking until some arrive.
     *
     * @return the number of bytes read, or -1 at end of stream.
     */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun write(bytes: ByteArray, offset: Int, length: Int)

    /** Pushes anything buffered to the head unit. */
    fun flush()
}

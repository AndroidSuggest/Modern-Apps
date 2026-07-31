package org.schabi.newpipe.extractor.services.youtube.sabr

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Reader for YouTube's UMP envelope. UMP uses its own compact integer format, not protobuf varints.
 */
object UmpReader {

    /** Receives one UMP part at a time (used by readStreaming). */
    fun interface PartConsumer {
        @Throws(SabrProtocolException::class)
        fun accept(type: Int, payload: ByteArray)
    }

    /** Receives one UMP part and returns false when the caller has enough data. */
    fun interface StoppablePartConsumer {
        @Throws(SabrProtocolException::class)
        fun accept(type: Int, payload: ByteArray): Boolean
    }

    /** Receives one UMP part payload as a bounded stream. The consumer may stop at part boundary. */
    fun interface StoppablePayloadConsumer {
        @Throws(SabrProtocolException::class, IOException::class)
        fun accept(type: Int, size: Int, payload: InputStream): Boolean
    }

    /**
     * Stream the UMP envelope: read one part (type, size, payload) at a time from [in] and
     * hand it to [consumer], so the whole response body is never held in memory at once.
     */
    @JvmStatic
    @Throws(SabrProtocolException::class, IOException::class)
    fun readStreaming(`in`: InputStream, consumer: PartConsumer) {
        readStreamingUntil(`in`) { type, payload ->
            consumer.accept(type, payload)
            true
        }
    }

    /**
     * Like [readStreaming], but stops at a part boundary when [consumer] returns false.
     */
    @JvmStatic
    @Throws(SabrProtocolException::class, IOException::class)
    fun readStreamingUntil(`in`: InputStream, consumer: StoppablePartConsumer) {
        readPayloadsUntil(`in`) { type, size, payload ->
            consumer.accept(type, readExactly(payload, size))
        }
    }

    /**
     * Stream the UMP envelope while exposing each payload as a bounded stream.
     */
    @JvmStatic
    @Throws(SabrProtocolException::class, IOException::class)
    fun readPayloadsUntil(`in`: InputStream, consumer: StoppablePayloadConsumer) {
        while (true) {
            throwIfInterrupted()
            val first = `in`.read()
            if (first < 0) {
                return // clean EOF at a part boundary -> done
            }
            val type = readUmpInt(`in`, first)
            val size = readUmpInt(`in`, readByteOrThrow(`in`))
            if (type < 0 || size < 0) {
                throw SabrProtocolException("Invalid UMP part header")
            }
            val payload = BoundedInputStream(`in`, size)
            val keepGoing = consumer.accept(type, size, payload)
            payload.drain()
            if (!keepGoing) {
                return
            }
        }
    }

    // UMP compact int, given the already-read first byte. Mirrors Cursor.readUmpInt.
    @Throws(SabrProtocolException::class, IOException::class)
    private fun readUmpInt(`in`: InputStream, first: Int): Int {
        if (first < 0) {
            throw EOFException("Unexpected EOF in UMP integer")
        }
        if (first < 128) {
            return first
        }
        if (first < 192) {
            return (first and 0x3f) + 64 * readByteOrThrow(`in`)
        }
        if (first < 224) {
            return (first and 0x1f) + 32 * (readByteOrThrow(`in`) + 256 * readByteOrThrow(`in`))
        }
        if (first < 240) {
            return (first and 0x0f) + 16 * (readByteOrThrow(`in`) +
                256 * (readByteOrThrow(`in`) + 256 * readByteOrThrow(`in`)))
        }
        return readByteOrThrow(`in`) + 256 * (readByteOrThrow(`in`) +
            256 * (readByteOrThrow(`in`) + 256 * readByteOrThrow(`in`)))
    }

    @Throws(SabrProtocolException::class, IOException::class)
    private fun readByteOrThrow(`in`: InputStream): Int {
        throwIfInterrupted()
        val b = `in`.read()
        if (b < 0) {
            throw EOFException("Unexpected EOF in UMP integer")
        }
        return b
    }

    @Throws(SabrProtocolException::class, IOException::class)
    private fun readExactly(`in`: InputStream, length: Int): ByteArray {
        if (length < 0) {
            throw SabrProtocolException("Invalid UMP part length")
        }
        throwIfInterrupted()
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            throwIfInterrupted()
            val read = `in`.read(result, offset, length - offset)
            if (read < 0) {
                throw EOFException("Unexpected EOF while reading UMP part data")
            }
            offset += read
        }
        return result
    }

    @Throws(IOException::class)
    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw IOException("Interrupted while reading UMP stream")
        }
    }

    private class BoundedInputStream(
        private val source: InputStream,
        private var remaining: Int
    ) : InputStream() {

        override fun read(): Int {
            throwIfInterrupted()
            if (remaining <= 0) {
                return -1
            }
            val value = source.read()
            if (value < 0) {
                throw EOFException("Unexpected EOF while reading UMP part data")
            }
            remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            throwIfInterrupted()
            if (remaining <= 0) {
                return -1
            }
            val read = source.read(buffer, offset, minOf(length, remaining))
            if (read < 0) {
                throw EOFException("Unexpected EOF while reading UMP part data")
            }
            remaining -= read
            return read
        }

        fun drain() {
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                read(buffer, 0, minOf(buffer.size, remaining))
            }
        }
    }

    @JvmStatic
    @Throws(SabrProtocolException::class)
    fun readAll(data: ByteArray): List<UmpPart> {
        val cursor = Cursor(data)
        val parts = ArrayList<UmpPart>()
        while (!cursor.isDone()) {
            val type = cursor.readUmpInt()
            val size = cursor.readUmpInt()
            if (type < 0 || size < 0) {
                throw SabrProtocolException("Invalid UMP part header")
            }
            parts.add(UmpPart(type, size, cursor.readBytes(size)))
        }
        return parts
    }

    private class Cursor(private val data: ByteArray) {
        private var offset: Int = 0

        fun isDone(): Boolean = offset >= data.size

        @Throws(SabrProtocolException::class)
        fun readUmpInt(): Int {
            val first = readUnsignedByte()
            if (first < 128) {
                return first
            }
            if (first < 192) {
                return (first and 0x3f) + 64 * readUnsignedByte()
            }
            if (first < 224) {
                return (first and 0x1f) + 32 * (readUnsignedByte() + 256 * readUnsignedByte())
            }
            if (first < 240) {
                return (first and 0x0f) + 16 * (readUnsignedByte() +
                    256 * (readUnsignedByte() + 256 * readUnsignedByte()))
            }
            return readUnsignedByte() +
                256 * (readUnsignedByte() +
                256 * (readUnsignedByte() + 256 * readUnsignedByte()))
        }

        @Throws(SabrProtocolException::class)
        fun readBytes(length: Int): ByteArray {
            if (length < 0 || offset + length > data.size) {
                throw SabrProtocolException("Unexpected EOF while reading UMP part data")
            }
            val result = ByteArray(length)
            System.arraycopy(data, offset, result, 0, length)
            offset += length
            return result
        }

        @Throws(SabrProtocolException::class)
        private fun readUnsignedByte(): Int {
            if (offset >= data.size) {
                throw SabrProtocolException("Unexpected EOF in UMP integer")
            }
            return data[offset++].toInt() and 0xff
        }
    }
}

package org.schabi.newpipe.extractor.downloader

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import javax.annotation.Nonnull
import javax.annotation.Nullable

class StreamingResponse(
    private val responseCode: Int,
    responseHeaders: Map<String, List<String>>?,
    @field:Nonnull @get:Nonnull val body: InputStream
) : Closeable {

    @Nonnull
    private val responseHeaders: Map<String, List<String>> =
        responseHeaders ?: emptyMap()

    fun responseCode(): Int = responseCode
    fun body(): InputStream = body

    @Nullable
    fun getHeader(name: String): String? {
        for ((key, value) in responseHeaders) {
            if (key != null && key.equals(name, ignoreCase = true) && value.isNotEmpty()) {
                return value[0]
            }
        }
        return null
    }

    @Throws(IOException::class)
    override fun close() {
        body.close()
    }
}

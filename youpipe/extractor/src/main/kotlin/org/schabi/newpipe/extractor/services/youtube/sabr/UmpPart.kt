package org.schabi.newpipe.extractor.services.youtube.sabr

class UmpPart internal constructor(
    val type: Int,
    val size: Int,
    private val data: ByteArray
) {
    fun getData(): ByteArray = data.clone()
    internal fun getRawData(): ByteArray = data
}

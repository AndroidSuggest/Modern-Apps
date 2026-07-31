package org.schabi.newpipe.extractor.services.youtube.sabr

class UmpPart internal constructor(
    private val type: Int,
    private val size: Int,
    private val data: ByteArray
) {
    fun getType(): Int = type
    fun getSize(): Int = size
    fun getData(): ByteArray = data.clone()
    internal fun getRawData(): ByteArray = data
}

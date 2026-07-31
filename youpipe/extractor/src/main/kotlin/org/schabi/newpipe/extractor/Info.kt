package org.schabi.newpipe.extractor

import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.utils.ExtractorLogger
import java.io.Serializable

abstract class Info : Serializable {

    companion object {
        private const val TAG = "Info"
    }

    val serviceId: Int
    val id: String
    val url: String
    var originalUrl: String
        private set
    val name: String

    private val errors: MutableList<Throwable> = ArrayList()

    fun addError(throwable: Throwable) {
        errors.add(throwable)
    }

    fun addAllErrors(throwables: Collection<Throwable>) {
        errors.addAll(throwables)
    }

    constructor(
        serviceId: Int,
        id: String,
        url: String,
        originalUrl: String,
        name: String
    ) {
        this.serviceId = serviceId
        this.id = id
        this.url = url
        this.originalUrl = originalUrl
        this.name = name
        ExtractorLogger.d(TAG, "Base Created {}", this)
    }

    constructor(serviceId: Int, linkHandler: LinkHandler, name: String) : this(
        serviceId,
        linkHandler.id,
        linkHandler.url,
        linkHandler.originalUrl,
        name
    )

    override fun toString(): String {
        val ifDifferent = if (url == originalUrl) "" else " (originalUrl=\"$originalUrl\")"
        return "${javaClass.simpleName}[url=\"$url\"$ifDifferent, name=\"$name\"]"
    }

    fun setOriginalUrl(originalUrl: String) {
        this.originalUrl = originalUrl
    }


    fun getService(): StreamingService {
        try {
            return NewPipe.getService(serviceId)
        } catch (e: ExtractionException) {
            throw RuntimeException("Info object has invalid service id", e)
        }
    }

    fun getErrors(): List<Throwable> = errors
}

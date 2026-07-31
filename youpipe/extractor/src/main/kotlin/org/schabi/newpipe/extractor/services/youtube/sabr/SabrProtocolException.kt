package org.schabi.newpipe.extractor.services.youtube.sabr

import org.schabi.newpipe.extractor.exceptions.ExtractionException

open class SabrProtocolException : ExtractionException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

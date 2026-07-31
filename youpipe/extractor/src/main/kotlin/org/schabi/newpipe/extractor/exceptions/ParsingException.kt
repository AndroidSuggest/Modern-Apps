package org.schabi.newpipe.extractor.exceptions

open class ParsingException : ExtractionException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

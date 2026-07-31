package org.schabi.newpipe.extractor.exceptions

open class ContentNotSupportedException : ParsingException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

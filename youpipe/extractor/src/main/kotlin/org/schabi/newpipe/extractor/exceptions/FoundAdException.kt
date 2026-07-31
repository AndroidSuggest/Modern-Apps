package org.schabi.newpipe.extractor.exceptions

class FoundAdException : ParsingException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

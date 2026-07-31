package org.schabi.newpipe.extractor.exceptions

class UnsupportedContentInCountryException : ContentNotAvailableException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

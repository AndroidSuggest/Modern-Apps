package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrRecoverableException : SabrProtocolException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

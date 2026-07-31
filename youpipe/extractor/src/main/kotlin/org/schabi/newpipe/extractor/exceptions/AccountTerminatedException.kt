package org.schabi.newpipe.extractor.exceptions

class AccountTerminatedException : ContentNotAvailableException {

    var reason: Reason = Reason.UNKNOWN
        private set

    constructor(message: String) : super(message)

    constructor(message: String, reason: Reason) : super(message) {
        this.reason = reason
    }

    constructor(message: String, cause: Throwable) : super(message, cause)

    enum class Reason {
        UNKNOWN,
        VIOLATION
    }
}

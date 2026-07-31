package com.vayunmathur.youpipe.util.sabr

import java.io.IOException

internal class SabrLogicException : IOException {
    constructor(message: String) : super(message)

    constructor(message: String, cause: Throwable) : super(message, cause)
}

package org.schabi.newpipe.extractor.utils

interface Logger {
    fun debug(tag: String, message: String)
    fun debug(tag: String, message: String, throwable: Throwable)
    fun warn(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable)
    fun error(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable)
}

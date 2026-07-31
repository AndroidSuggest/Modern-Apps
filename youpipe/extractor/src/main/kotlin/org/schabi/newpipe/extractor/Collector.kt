package org.schabi.newpipe.extractor

import org.schabi.newpipe.extractor.exceptions.ParsingException

interface Collector<I, E> {
    fun commit(extractor: E)

    @Throws(ParsingException::class)
    fun extract(extractor: E): I

    fun getItems(): List<I>
    fun getErrors(): List<Throwable>
    fun reset()
}

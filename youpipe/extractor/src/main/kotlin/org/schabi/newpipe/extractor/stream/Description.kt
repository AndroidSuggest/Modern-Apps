package org.schabi.newpipe.extractor.stream

import java.io.Serializable
import java.util.Objects
import javax.annotation.Nullable

class Description : Serializable {

    private val content: String
    private val type: Int

    constructor(@Nullable content: String?, type: Int) {
        this.type = type
        this.content = content ?: ""
    }

    fun getContent(): String = content
    fun getType(): Int = type

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as Description
        return type == that.type && Objects.equals(content, that.content)
    }

    override fun hashCode(): Int = Objects.hash(content, type)

    companion object {
        const val HTML = 1
        const val MARKDOWN = 2
        const val PLAIN_TEXT = 3
        val EMPTY_DESCRIPTION = Description("", PLAIN_TEXT)
    }
}

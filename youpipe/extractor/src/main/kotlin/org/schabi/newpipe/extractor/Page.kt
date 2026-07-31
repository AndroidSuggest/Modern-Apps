package org.schabi.newpipe.extractor

import org.schabi.newpipe.extractor.utils.Utils
import java.io.Serializable
import javax.annotation.Nullable

class Page : Serializable {

    val url: String?
    val id: String?
    val ids: List<String>?
    val cookies: Map<String, String>?

    @Nullable
    val body: ByteArray?

    constructor(
        url: String?,
        id: String?,
        ids: List<String>?,
        cookies: Map<String, String>?,
        @Nullable body: ByteArray?
    ) {
        this.url = url
        this.id = id
        this.ids = ids
        this.cookies = cookies
        this.body = body
    }

    constructor(url: String) : this(url, null, null, null, null)

    constructor(url: String, id: String) : this(url, id, null, null, null)

    constructor(url: String, id: String, body: ByteArray) : this(url, id, null, null, body)

    constructor(url: String, body: ByteArray) : this(url, null, null, null, body)

    constructor(url: String, cookies: Map<String, String>) : this(url, null, null, cookies, null)

    constructor(ids: List<String>) : this(null, null, ids, null, null)

    constructor(ids: List<String>, cookies: Map<String, String>) : this(null, null, ids, cookies, null)



    companion object {
        @JvmStatic
        fun isValid(page: Page?): Boolean =
            page != null && (!Utils.isNullOrEmpty(page.url) || !Utils.isNullOrEmpty(page.ids))
    }
}

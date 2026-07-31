package org.schabi.newpipe.extractor.subscription

import java.io.Serializable

class SubscriptionItem(
    val serviceId: Int,
    val url: String,
    val name: String
) : Serializable {


    override fun toString(): String =
        "${javaClass.simpleName}@${Integer.toHexString(hashCode())}[name=$name > $serviceId:$url]"
}

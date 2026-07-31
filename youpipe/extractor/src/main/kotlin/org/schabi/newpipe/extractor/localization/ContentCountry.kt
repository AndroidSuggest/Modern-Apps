package org.schabi.newpipe.extractor.localization

import java.io.Serializable
import java.util.Collections
import javax.annotation.Nonnull

class ContentCountry(@field:Nonnull @get:Nonnull val countryCode: String) : Serializable {


    override fun toString(): String = countryCode

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContentCountry) return false
        return countryCode == other.countryCode
    }

    override fun hashCode(): Int = countryCode.hashCode()

    companion object {
        @JvmField
        val DEFAULT: ContentCountry = ContentCountry(Localization.DEFAULT.getCountryCode())

        @JvmStatic
        fun listFrom(vararg countryCodeList: String): List<ContentCountry> {
            val toReturn = ArrayList<ContentCountry>()
            for (code in countryCodeList) {
                toReturn.add(ContentCountry(code))
            }
            return Collections.unmodifiableList(toReturn)
        }
    }
}

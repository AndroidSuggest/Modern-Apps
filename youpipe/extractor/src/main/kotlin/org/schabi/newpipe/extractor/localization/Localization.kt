package org.schabi.newpipe.extractor.localization

import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.utils.LocaleCompat
import java.io.Serializable
import java.util.Collections
import java.util.Locale
import javax.annotation.Nonnull
import javax.annotation.Nullable

class Localization : Serializable {

    @Nonnull
    val languageCode: String
    @Nullable
    private val countryCodeNullable: String?

    constructor(languageCode: String, countryCode: String?) {
        this.languageCode = languageCode
        this.countryCodeNullable = countryCode
    }

    constructor(languageCode: String) : this(languageCode, null)


    @Nonnull
    fun getCountryCode(): String = countryCodeNullable ?: ""

    @Nonnull
    fun getLocalizationCode(): String =
        languageCode + if (countryCodeNullable == null) "" else "-$countryCodeNullable"

    override fun toString(): String = "Localization[${getLocalizationCode()}]"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Localization) return false
        return languageCode == other.languageCode && countryCodeNullable == other.countryCodeNullable
    }

    override fun hashCode(): Int {
        var result = languageCode.hashCode()
        result = 31 * result + countryCodeNullable.hashCode()
        return result
    }

    companion object {
        @JvmField
        val DEFAULT: Localization = Localization("en", "GB")

        @JvmStatic
        @Nonnull
        fun listFrom(vararg localizationCodeList: String): List<Localization> {
            val toReturn = ArrayList<Localization>()
            for (code in localizationCodeList) {
                toReturn.add(
                    fromLocalizationCode(code)
                        ?: throw IllegalArgumentException("Not a localization code: $code")
                )
            }
            return Collections.unmodifiableList(toReturn)
        }

        @JvmStatic
        @Nonnull
        fun fromLocalizationCode(localizationCode: String): Localization? =
            LocaleCompat.forLanguageTag(localizationCode)?.let { fromLocale(it) }

        @JvmStatic
        fun fromLocale(@Nonnull locale: Locale): Localization =
            Localization(locale.language, locale.country.let { if (it.isEmpty()) null else it })

        @JvmStatic
        @Throws(ParsingException::class)
        fun getLocaleFromThreeLetterCode(@Nonnull code: String): Locale {
            val languages = Locale.getISOLanguages()
            val localeMap = HashMap<String, Locale>(languages.size)
            for (language in languages) {
                val locale = Locale(language)
                localeMap[locale.isO3Language] = locale
            }
            if (localeMap.containsKey(code)) {
                return localeMap[code]!!
            } else {
                throw ParsingException("Could not get Locale from this three letter language code$code")
            }
        }
    }
}

package com.vayunmathur.contacts.ui

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.vayunmathur.contacts.data.hasYear
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
internal fun calculateAge(birthDate: LocalDate, currentDate: LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date): Int? {
    if (!birthDate.hasYear) return null
    if (currentDate < birthDate) return null
    var age = currentDate.year - birthDate.year
    if (currentDate.monthNumber < birthDate.monthNumber ||
        (currentDate.monthNumber == birthDate.monthNumber && currentDate.dayOfMonth < birthDate.dayOfMonth)
    ) {
        age--
    }
    return age
}

fun formatPhoneNumber(numberString: String, defaultRegion: String = "US"): String {
    val phoneUtil = PhoneNumberUtil.getInstance()

    return try {
        val phoneNumber = phoneUtil.parse(numberString, defaultRegion)
        if (!phoneUtil.isValidNumber(phoneNumber)) return numberString
        val regionOfNumber = phoneUtil.getRegionCodeForNumber(phoneNumber)
        val formatType = if (regionOfNumber == defaultRegion) {
            PhoneNumberUtil.PhoneNumberFormat.NATIONAL
        } else {
            PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL
        }

        phoneUtil.format(phoneNumber, formatType)

    } catch (e: NumberParseException) {
        numberString
    }
}

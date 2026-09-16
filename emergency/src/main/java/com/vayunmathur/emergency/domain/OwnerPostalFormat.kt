package com.vayunmathur.emergency.domain

/**
 * Formats a postal address from its components when the provider has no
 * preformatted address (`FORMATTED_ADDRESS` blank).
 *
 * Mirrors the fallback in the contacts app's `getDetailsInternal`: joins the
 * non-blank components with `", "`. Pure logic (no Android dependency) so it
 * can carry a plain unit test.
 */
fun formatPostalFallback(
    street: String?,
    city: String?,
    region: String?,
    postcode: String?,
    country: String?,
): String = listOfNotNull(street, city, region, postcode, country)
    .filter { it.isNotBlank() }
    .joinToString(", ")

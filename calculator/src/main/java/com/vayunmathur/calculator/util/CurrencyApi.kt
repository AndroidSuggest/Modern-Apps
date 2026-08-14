package com.vayunmathur.calculator.util

import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.Serializable

/**
 * Live currency exchange rates from the self-hosted proxy on `api.vayunmathur.com`, which
 * fetches and caches them from a free upstream. Rates are "units of the currency per 1
 * [base]" (base is USD, so USD == 1.0), which is exactly the `factorToBase` inverse the
 * converter needs — see [UnitRegistry.currencyCategory].
 */
object CurrencyApi {

    private const val URL = "https://api.vayunmathur.com/api/currency/rates"

    suspend fun rates(): CurrencyRatesDto = NetworkClient.getJson(URL)
}

@Serializable
data class CurrencyRatesDto(
    val base: String = "USD",
    val updated: Long = 0L,
    val rates: Map<String, Double> = emptyMap(),
)

package com.vayunmathur.travel.network

import com.vayunmathur.library.network.NetworkClient

/**
 * Customer-user endpoints under `/api/travel` (create, list, detail). Split
 * from [TravelApi] so neither object exceeds the TooManyFunctions cap. Shares
 * URL/error helpers via [TravelApiCore.kt].
 */
object TravelCustomerApi {

    private const val BASE = TRAVEL_BASE

    private fun enc(s: String): String = travelEnc(s)

    /** Create a Duffel customer user to associate orders with. */
    suspend fun createCustomer(input: CustomerUserInputDto): CustomerDto =
        travelPostJson("$BASE/customers", input, "Creating customer failed")

    /** List the account's customer users. */
    suspend fun customers(): List<CustomerDto> = NetworkClient.getJson("$BASE/customers")

    /** Full detail for one customer user. */
    suspend fun customer(id: String): CustomerDto =
        NetworkClient.getJson("$BASE/customers/${enc(id)}")
}

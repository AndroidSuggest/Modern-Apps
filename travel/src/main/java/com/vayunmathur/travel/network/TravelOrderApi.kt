package com.vayunmathur.travel.network

import com.vayunmathur.library.network.NetworkClient

/**
 * Order management endpoints under `/api/travel` (book, pay, list, change,
 * cancel, services). Split from [TravelApi] so neither object exceeds the
 * TooManyFunctions cap. Shares URL/error helpers via [TravelApiCore.kt].
 */
object TravelOrderApi {

    private const val BASE = TRAVEL_BASE

    private fun enc(s: String): String = travelEnc(s)

    /**
     * Book a selected offer. Returns the confirmed order (with a PNR). On a
     * non-2xx response the actual upstream message (e.g. a Duffel validation
     * error) is surfaced instead of a bare HTTP status.
     */
    suspend fun createOrder(request: OrderRequestDto): OrderResultDto {
        val res = NetworkClient.performRequest(
            url = "$BASE/orders",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = request,
        )
        if (!res.isSuccess) {
            throw IllegalStateException(
                travelExtractError(res.body).ifBlank { "Booking failed (HTTP ${res.status})." }
            )
        }
        return travelJson.decodeFromString(res.body)
    }

    /** Settle a hold order via balance (pay later). */
    suspend fun payOrder(orderId: String): OrderResultDto {
        val res = NetworkClient.performRequest(
            url = "$BASE/orders/${enc(orderId)}/pay",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
        )
        if (!res.isSuccess) {
            throw IllegalStateException(
                travelExtractError(res.body).ifBlank { "Payment failed (HTTP ${res.status})." }
            )
        }
        return travelJson.decodeFromString(res.body)
    }

    /** List the account's remote orders (for the Trips hub). */
    suspend fun listOrders(): List<OrderDetailDto> = NetworkClient.getJson("$BASE/orders")

    /** Full detail for one remote order. */
    suspend fun orderDetail(orderId: String): OrderDetailDto =
        NetworkClient.getJson("$BASE/orders/${enc(orderId)}")

    /** Server-recorded webhook events (schedule change / cancellation) for an order. */
    suspend fun orderEvents(orderId: String): List<OrderEventDto> =
        NetworkClient.getJson("$BASE/order-events?order_id=${enc(orderId)}")

    /** Get a refund quote for cancelling an order (does not confirm). */
    suspend fun cancelQuote(orderId: String): CancellationDto =
        travelPostJson("$BASE/orders/${enc(orderId)}/cancel", "", "Cancellation quote failed")

    /** Confirm a cancellation. */
    suspend fun confirmCancellation(cancellationId: String): CancellationDto =
        travelPostJson(
            "$BASE/cancellations/${enc(cancellationId)}/confirm",
            "",
            "Cancellation failed"
        )

    /** Start an order change; returns priced change offers. */
    suspend fun changeRequest(orderId: String, input: ChangeRequestInputDto): ChangeRequestResultDto =
        travelPostJson("$BASE/orders/${enc(orderId)}/changes", input, "Change request failed")

    /** Accept a change offer, paying any difference via balance. */
    suspend fun confirmChange(changeOfferId: String): OrderResultDto =
        travelPostJson("$BASE/changes/${enc(changeOfferId)}/confirm", "", "Change failed")

    /** Add services to a booked order, settling via balance. */
    suspend fun addServices(orderId: String, input: AddServicesInputDto): OrderResultDto =
        travelPostJson("$BASE/orders/${enc(orderId)}/services", input, "Adding services failed")
}

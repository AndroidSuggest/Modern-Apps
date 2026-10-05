package com.vayunmathur.travel.network

import com.vayunmathur.library.network.NetworkClient

/**
 * Thin wrapper over the shared [NetworkClient] for the `/api/travel` search and
 * reference endpoints on the self-hosted proxy (`api.vayunmathur.com`). The proxy
 * holds the Duffel token server-side, so the app ships no secret; it just does
 * URL/body construction + delegation to the shared client.
 *
 * Order management lives in [TravelOrderApi]; customers in [TravelCustomerApi].
 *
 * Each call throws on network/parse failure; callers (the ViewModel) wrap in
 * try/catch and surface an error state.
 */
object TravelApi {

    private const val BASE = TRAVEL_BASE

    private fun enc(s: String): String = travelEnc(s)

    /** Airport/city autocomplete. Returns empty for a blank query. */
    suspend fun places(query: String): List<PlaceDto> {
        if (query.isBlank()) return emptyList()
        return NetworkClient.getJson("$BASE/places?q=${enc(query)}")
    }

    /**
     * Search bookable flight offers. Either pass a single-leg / round-trip via
     * [origin]/[destination]/[depart]/[ret], or a multi-city [slices] string
     * (`"LHR:JFK:2026-09-01,JFK:LAX:2026-09-05"`) which overrides them.
     * [children] is comma-separated ages (`"5,11"`); [maxConnections] < 0 means
     * no cap. [cabin] is `economy`/`premium_economy`/`business`/`first`.
     */
    suspend fun flights(
        origin: String = "",
        destination: String = "",
        depart: String = "",
        ret: String? = null,
        slices: String? = null,
        adults: Int = 1,
        children: String = "",
        infants: Int = 0,
        cabin: String = "economy",
        maxConnections: Int = -1,
        loyalty: String = "",
    ): OfferSearchDto = NetworkClient.getJson(
        flightsUrl(
            "/flights",
            origin,
            destination,
            depart,
            ret,
            slices,
            adults,
            children,
            infants,
            cabin,
            maxConnections,
            loyalty
        )
    )

    /**
     * Kick off an incremental (batch) search: returns quickly with just the
     * [OfferSearchDto.offerRequestId] (no offers). Poll [offers] for results.
     */
    suspend fun flightsAsync(
        origin: String = "",
        destination: String = "",
        depart: String = "",
        ret: String? = null,
        slices: String? = null,
        adults: Int = 1,
        children: String = "",
        infants: Int = 0,
        cabin: String = "economy",
        maxConnections: Int = -1,
        loyalty: String = "",
    ): OfferSearchDto = NetworkClient.getJson(
        flightsUrl(
            "/flights-async",
            origin,
            destination,
            depart,
            ret,
            slices,
            adults,
            children,
            infants,
            cabin,
            maxConnections,
            loyalty
        )
    )

    private fun flightsUrl(
        path: String,
        origin: String,
        destination: String,
        depart: String,
        ret: String?,
        slices: String?,
        adults: Int,
        children: String,
        infants: Int,
        cabin: String,
        maxConnections: Int,
        loyalty: String,
    ): String = buildString {
        append(BASE).append(path)
        if (!slices.isNullOrBlank()) {
            append("?slices=").append(enc(slices))
        } else {
            append("?origin=").append(enc(origin))
            append("&destination=").append(enc(destination))
            append("&depart=").append(enc(depart))
            if (!ret.isNullOrBlank()) append("&return=").append(enc(ret))
        }
        append("&adults=").append(adults)
        if (children.isNotBlank()) append("&children=").append(enc(children))
        if (infants > 0) append("&infants=").append(infants)
        append("&cabin=").append(enc(cabin))
        if (maxConnections >= 0) append("&max_connections=").append(maxConnections)
        if (loyalty.isNotBlank()) append("&loyalty=").append(enc(loyalty))
    }

    /**
     * Server-sorted/filtered listing of a prior search's offers. [sort] is
     * `total_amount` or `total_duration`; [maxConnections] < 0 means no cap.
     */
    suspend fun offers(
        offerRequestId: String,
        sort: String? = null,
        maxConnections: Int = -1,
    ): List<OfferDto> {
        val url = buildString {
            append(BASE).append("/offers")
            append("?offer_request_id=").append(enc(offerRequestId))
            if (!sort.isNullOrBlank()) append("&sort=").append(enc(sort))
            if (maxConnections >= 0) append("&max_connections=").append(maxConnections)
        }
        return NetworkClient.getJson(url)
    }

    /**
     * Start a step-by-step (partial) offer request; returns the first leg's
     * offers plus the request id used for the following steps.
     */
    suspend fun createPartialOffers(
        slices: String? = null,
        origin: String = "",
        destination: String = "",
        depart: String = "",
        ret: String? = null,
        adults: Int = 1,
        children: String = "",
        infants: Int = 0,
        cabin: String = "economy",
        maxConnections: Int = -1,
        loyalty: String = "",
    ): PartialOfferDto {
        val url = flightsUrl(
            "/partial-offers",
            origin,
            destination,
            depart,
            ret,
            slices,
            adults,
            children,
            infants,
            cabin,
            maxConnections,
            loyalty
        )
        val res = NetworkClient.performRequest(
            url = url,
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
        )
        if (!res.isSuccess) {
            throw IllegalStateException(
                travelExtractError(res.body).ifBlank { "Search failed (HTTP ${res.status})." }
            )
        }
        return travelJson.decodeFromString(res.body)
    }

    /** The next leg's offers, given the already-selected partial-offer ids. */
    suspend fun selectPartialOffer(requestId: String, selected: List<String>): PartialOfferDto {
        val ids = enc(selected.joinToString(","))
        return NetworkClient.getJson("$BASE/partial-offers/${enc(requestId)}/select?selected=$ids")
    }

    /** The final orderable fares for the fully-selected combination. */
    suspend fun partialOfferFares(requestId: String, selected: List<String>): PartialOfferDto {
        val ids = enc(selected.joinToString(","))
        return NetworkClient.getJson("$BASE/partial-offers/${enc(requestId)}/fares?selected=$ids")
    }

    /** Reference list of airlines with logo URLs (cache client-side). */
    suspend fun airlines(): List<AirlineDto> = NetworkClient.getJson("$BASE/airlines")

    /** Reference list of aircraft types (cache client-side). */
    suspend fun aircraft(): List<AircraftDto> = NetworkClient.getJson("$BASE/aircraft")

    /** Reference list of cities (cache client-side). */
    suspend fun cities(): List<CityDto> = NetworkClient.getJson("$BASE/cities")

    /** Reference data for a single airport by IATA code. */
    suspend fun airport(code: String): AirportDto =
        NetworkClient.getJson("$BASE/airport?code=${enc(code)}")

    /** Re-price/confirm a single offer right before booking (offers expire). */
    suspend fun offer(id: String): OfferDto =
        NetworkClient.getJson("$BASE/offer?id=${enc(id)}")

    /** Per-segment seat maps for an offer. */
    suspend fun seatMap(offerId: String): List<SeatCabinDto> =
        NetworkClient.getJson("$BASE/seat-map?id=${enc(offerId)}")
}

package com.vayunmathur.travel.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.Vertical
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OfferSearchDto
import com.vayunmathur.travel.network.PlaceDto
import com.vayunmathur.travel.network.TravelApi
import kotlinx.coroutines.launch

/** Airport/city suggestions for [query]; never throws (empty on failure). */
suspend fun TravelViewModel.autocomplete(query: String): List<PlaceDto> =
    runCatching { TravelApi.places(query) }.getOrDefault(emptyList())

private const val POLL_ROUNDS = 6
private const val POLL_INTERVAL_MS = 1_200L
private const val POLL_STABLE_ROUNDS = 2

/** Parsed first-leg details used for the recent-search label. */
private data class FirstLeg(val origin: String, val destination: String, val depart: String)

private fun parseFirstLeg(slices: String): FirstLeg {
    val parts = slices.substringBefore(',').split(':')
    return FirstLeg(
        origin = parts.getOrNull(0).orEmpty(),
        destination = parts.getOrNull(1).orEmpty(),
        depart = parts.getOrNull(2).orEmpty()
    )
}

private fun TravelViewModel.recordFlightSearch(query: FlightQuery, leg: FirstLeg) {
    val multiCity = query.slices.contains(',') && query.slices.substringAfter(',').isNotBlank()
    recordRecent(
        RecentSearch(
            vertical = Vertical.FLIGHTS.name,
            label = if (multiCity) {
                "${leg.origin} → … · multi-city"
            } else {
                "${leg.origin} → ${leg.destination} · ${TravelViewModel.prettyDate(leg.depart)}"
            },
            origin = leg.origin,
            destination = leg.destination,
            depart = leg.depart,
            returnDate = null,
            adults = query.adults,
            cabin = query.cabin,
        )
    )
}

private suspend fun TravelViewModel.loyaltyParam(): String =
    repository.getAllFrequentFlyers()
        .joinToString(",") { "${it.airlineIata}:${it.accountNumber}" }

private fun TravelViewModel.handleSearchStarted(started: OfferSearchDto) {
    val requestId = started.offerRequestId
    if (requestId.isBlank()) {
        flightsMutable.value = FlightResultsState(
            error = "Search could not be started. Please try again.",
            hasSearched = true,
        )
        return
    }
    flightsMutable.value = FlightResultsState(
        hasSearched = true,
        offerRequestId = requestId,
        loading = true,
        polling = true,
    )
}

fun TravelViewModel.searchFlights(query: FlightQuery) {
    currentQuery = query
    recordFlightSearch(query, parseFirstLeg(query.slices))
    flightsMutable.value = FlightResultsState(loading = true, hasSearched = true)
    viewModelScope.launch {
        val loyalty = loyaltyParam()
        // Incremental search: create the request async, then poll /offers so
        // results appear progressively instead of blocking on the full set.
        runCatching {
            TravelApi.flightsAsync(
                slices = query.slices,
                adults = query.adults,
                children = query.children,
                infants = query.infants,
                cabin = query.cabin,
                maxConnections = query.maxConnections,
                loyalty = loyalty,
            )
        }
            .onSuccess { started ->
                handleSearchStarted(started)
                if (started.offerRequestId.isNotBlank()) {
                    pollOffers(started.offerRequestId, query.maxConnections)
                }
            }
            .onFailure {
                flightsMutable.value = FlightResultsState(error = errorMessage(it), hasSearched = true)
            }
    }
}

/** Poll `/offers` for [requestId] a few rounds, updating results as they arrive. */
internal suspend fun TravelViewModel.pollOffers(requestId: String, maxConnections: Int) {
    var lastCount = -1
    var stableRounds = 0
    repeat(POLL_ROUNDS) { round ->
        // Skip the initial wait on the first round so results show ASAP.
        if (round > 0) kotlinx.coroutines.delay(POLL_INTERVAL_MS)
        // Stop if the user has navigated to a different search.
        if (flightsMutable.value.offerRequestId != requestId) return
        val offers = runCatching { TravelApi.offers(requestId, null, maxConnections) }.getOrNull()
        if (offers != null) {
            flightsMutable.value = flightsMutable.value.copy(
                allOffers = offers,
                loading = offers.isEmpty(),
            )
            if (offers.size == lastCount) stableRounds++ else stableRounds = 0
            lastCount = offers.size
            // Stop early once the count stabilizes with some results in hand.
            if (stableRounds >= POLL_STABLE_ROUNDS && offers.isNotEmpty()) {
                flightsMutable.value = flightsMutable.value.copy(polling = false, loading = false)
                return
            }
        }
    }
    flightsMutable.value = flightsMutable.value.copy(polling = false, loading = false)
}

/** Change the server-side sort, re-fetching the offer list for the request. */
fun TravelViewModel.setSort(sort: OfferSort) {
    val state = flightsMutable.value
    if (state.sort == sort || state.offerRequestId.isBlank()) {
        flightsMutable.value = state.copy(sort = sort)
        return
    }
    flightsMutable.value = state.copy(sort = sort, loading = true, error = null)
    val requestId = state.offerRequestId
    val maxConnections = currentQuery?.maxConnections ?: -1
    viewModelScope.launch {
        runCatching { TravelApi.offers(requestId, sort.key, maxConnections) }
            .onSuccess { offers ->
                flightsMutable.value = flightsMutable.value.copy(loading = false, allOffers = offers)
            }
            .onFailure {
                flightsMutable.value = flightsMutable.value.copy(loading = false, error = errorMessage(it))
            }
    }
}

fun TravelViewModel.setMaxStopsFilter(maxStops: Int?) {
    flightsMutable.value = flightsMutable.value.copy(
        filters = flightsMutable.value.filters.copy(maxStops = maxStops),
    )
}

fun TravelViewModel.toggleAirlineFilter(iata: String) {
    val current = flightsMutable.value.filters.airlines
    val next = if (iata in current) current - iata else current + iata
    flightsMutable.value = flightsMutable.value.copy(filters = flightsMutable.value.filters.copy(airlines = next))
}

fun TravelViewModel.setFareBrandFilter(fareBrand: String?) {
    flightsMutable.value = flightsMutable.value.copy(
        filters = flightsMutable.value.filters.copy(fareBrand = fareBrand),
    )
}

fun TravelViewModel.clearFilters() {
    flightsMutable.value = flightsMutable.value.copy(filters = FlightFilters())
}

/** Seed the review screen with the tapped offer; clear any prior ancillaries. */
fun TravelViewModel.selectOffer(offer: OfferDto) {
    reviewMutable.value = OfferReviewState(offer = offer)
    selectedBaggageMutable.value = emptyMap()
    selectedExtrasMutable.value = emptyMap()
    selectedSeatsMutable.value = emptyMap()
    seatMapMutable.value = SeatMapState()
}

/** Re-price the chosen offer right before booking (offers expire). */
fun TravelViewModel.refreshOffer(offerId: String) {
    reviewMutable.value = reviewMutable.value.copy(loading = true, error = null)
    viewModelScope.launch {
        runCatching { TravelApi.offer(offerId) }
            .onSuccess { reviewMutable.value = OfferReviewState(offer = it) }
            .onFailure {
                // Keep the previously selected offer so the user can still proceed.
                reviewMutable.value = reviewMutable.value.copy(loading = false, error = errorMessage(it))
            }
    }
}

/** Start the round-trip partial flow: fetch the outbound leg's offers. */
fun TravelViewModel.startPartialSearch(query: FlightQuery) {
    partialFlowMutable.value = PartialFlowState(loading = true)
    viewModelScope.launch {
        val loyalty = repository.getAllFrequentFlyers()
            .joinToString(",") { "${it.airlineIata}:${it.accountNumber}" }
        runCatching {
            TravelApi.createPartialOffers(
                slices = query.slices,
                adults = query.adults,
                children = query.children,
                infants = query.infants,
                cabin = query.cabin,
                maxConnections = query.maxConnections,
                loyalty = loyalty,
            )
        }
            .onSuccess { partialFlowMutable.value = PartialFlowState(requestId = it.id, offers = it.offers) }
            .onFailure { partialFlowMutable.value = PartialFlowState(error = errorMessage(it)) }
    }
}

/** Load the return leg's offers after an outbound partial offer is chosen. */
fun TravelViewModel.loadPartialReturn(outboundId: String) {
    val requestId = partialFlowMutable.value.requestId
    if (requestId.isBlank()) {
        partialFlowMutable.value = partialFlowMutable.value.copy(error = "Please restart your search.")
        return
    }
    partialFlowMutable.value = partialFlowMutable.value.copy(loading = true, error = null, offers = emptyList())
    viewModelScope.launch {
        runCatching { TravelApi.selectPartialOffer(requestId, listOf(outboundId)) }
            .onSuccess {
                partialFlowMutable.value = partialFlowMutable.value.copy(
                    loading = false,
                    requestId = it.id.ifBlank { requestId },
                    offers = it.offers
                )
            }
            .onFailure {
                partialFlowMutable.value = partialFlowMutable.value.copy(
                    loading = false,
                    error = errorMessage(it)
                )
            }
    }
}

/** Load the final orderable fares after both legs are chosen. */
fun TravelViewModel.loadPartialFares(outboundId: String, returnId: String) {
    val requestId = partialFlowMutable.value.requestId
    if (requestId.isBlank()) {
        partialFlowMutable.value = partialFlowMutable.value.copy(error = "Please restart your search.")
        return
    }
    partialFlowMutable.value = partialFlowMutable.value.copy(loading = true, error = null, offers = emptyList())
    viewModelScope.launch {
        runCatching { TravelApi.partialOfferFares(requestId, listOf(outboundId, returnId)) }
            .onSuccess {
                partialFlowMutable.value = partialFlowMutable.value.copy(
                    loading = false,
                    offers = it.offers
                )
            }
            .onFailure {
                partialFlowMutable.value = partialFlowMutable.value.copy(
                    loading = false,
                    error = errorMessage(it)
                )
            }
    }
}

/** Look up a partial-flow offer by id (for seeding the review screen). */
fun TravelViewModel.partialOfferById(offerId: String): OfferDto? =
    partialFlowMutable.value.offers.find { it.offerId == offerId }

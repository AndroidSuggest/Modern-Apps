package com.vayunmathur.travel.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.Vertical
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.PlaceDto
import com.vayunmathur.travel.network.TravelApi
import kotlinx.coroutines.launch

/** Airport/city suggestions for [query]; never throws (empty on failure). */
suspend fun TravelViewModel.autocomplete(query: String): List<PlaceDto> =
    runCatching { TravelApi.places(query) }.getOrDefault(emptyList())

fun TravelViewModel.searchFlights(query: FlightQuery) {
    currentQuery = query
    val firstSlice = query.slices.substringBefore(',')
    val parts = firstSlice.split(':')
    val origin = parts.getOrNull(0).orEmpty()
    val destination = parts.getOrNull(1).orEmpty()
    val depart = parts.getOrNull(2).orEmpty()
    val multiCity = query.slices.contains(',') && query.slices.substringAfter(',').isNotBlank()
    recordRecent(
        RecentSearch(
            vertical = Vertical.FLIGHTS.name,
            label = if (multiCity) {
                "$origin → … · multi-city"
            } else {
                "$origin → $destination · ${TravelViewModel.prettyDate(depart)}"
            },
            origin = origin,
            destination = destination,
            depart = depart,
            returnDate = null,
            adults = query.adults,
            cabin = query.cabin,
        )
    )
    _flights.value = FlightResultsState(loading = true, hasSearched = true)
    viewModelScope.launch {
        val loyalty = repository.getAllFrequentFlyers()
            .joinToString(",") { "${it.airlineIata}:${it.accountNumber}" }
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
                val requestId = started.offerRequestId
                if (requestId.isBlank()) {
                    _flights.value = FlightResultsState(
                        error = "Search could not be started. Please try again.",
                        hasSearched = true,
                    )
                    return@onSuccess
                }
                _flights.value = FlightResultsState(
                    hasSearched = true,
                    offerRequestId = requestId,
                    loading = true,
                    polling = true,
                )
                pollOffers(requestId, query.maxConnections)
            }
            .onFailure {
                _flights.value = FlightResultsState(error = errorMessage(it), hasSearched = true)
            }
    }
}

/** Poll `/offers` for [requestId] a few rounds, updating results as they arrive. */
internal suspend fun TravelViewModel.pollOffers(requestId: String, maxConnections: Int) {
    var lastCount = -1
    var stableRounds = 0
    repeat(6) { round ->
        // Skip the initial wait on the first round so results show ASAP.
        if (round > 0) kotlinx.coroutines.delay(1_200)
        // Stop if the user has navigated to a different search.
        if (_flights.value.offerRequestId != requestId) return
        val offers = runCatching { TravelApi.offers(requestId, null, maxConnections) }.getOrNull()
        if (offers != null) {
            _flights.value = _flights.value.copy(
                allOffers = offers,
                loading = offers.isEmpty(),
            )
            if (offers.size == lastCount) stableRounds++ else stableRounds = 0
            lastCount = offers.size
            // Stop early once the count stabilizes with some results in hand.
            if (stableRounds >= 2 && offers.isNotEmpty()) {
                _flights.value = _flights.value.copy(polling = false, loading = false)
                return
            }
        }
    }
    _flights.value = _flights.value.copy(polling = false, loading = false)
}

/** Change the server-side sort, re-fetching the offer list for the request. */
fun TravelViewModel.setSort(sort: OfferSort) {
    val state = _flights.value
    if (state.sort == sort || state.offerRequestId.isBlank()) {
        _flights.value = state.copy(sort = sort)
        return
    }
    _flights.value = state.copy(sort = sort, loading = true, error = null)
    val requestId = state.offerRequestId
    val maxConnections = currentQuery?.maxConnections ?: -1
    viewModelScope.launch {
        runCatching { TravelApi.offers(requestId, sort.key, maxConnections) }
            .onSuccess { offers ->
                _flights.value = _flights.value.copy(loading = false, allOffers = offers)
            }
            .onFailure {
                _flights.value = _flights.value.copy(loading = false, error = errorMessage(it))
            }
    }
}

fun TravelViewModel.setMaxStopsFilter(maxStops: Int?) {
    _flights.value = _flights.value.copy(
        filters = _flights.value.filters.copy(maxStops = maxStops),
    )
}

fun TravelViewModel.toggleAirlineFilter(iata: String) {
    val current = _flights.value.filters.airlines
    val next = if (iata in current) current - iata else current + iata
    _flights.value = _flights.value.copy(filters = _flights.value.filters.copy(airlines = next))
}

fun TravelViewModel.setFareBrandFilter(fareBrand: String?) {
    _flights.value = _flights.value.copy(
        filters = _flights.value.filters.copy(fareBrand = fareBrand),
    )
}

fun TravelViewModel.clearFilters() {
    _flights.value = _flights.value.copy(filters = FlightFilters())
}

/** Seed the review screen with the tapped offer; clear any prior ancillaries. */
fun TravelViewModel.selectOffer(offer: OfferDto) {
    _review.value = OfferReviewState(offer = offer)
    _selectedBaggage.value = emptyMap()
    _selectedExtras.value = emptyMap()
    _selectedSeats.value = emptyMap()
    _seatMap.value = SeatMapState()
}

/** Re-price the chosen offer right before booking (offers expire). */
fun TravelViewModel.refreshOffer(offerId: String) {
    _review.value = _review.value.copy(loading = true, error = null)
    viewModelScope.launch {
        runCatching { TravelApi.offer(offerId) }
            .onSuccess { _review.value = OfferReviewState(offer = it) }
            .onFailure {
                // Keep the previously selected offer so the user can still proceed.
                _review.value = _review.value.copy(loading = false, error = errorMessage(it))
            }
    }
}

/** Start the round-trip partial flow: fetch the outbound leg's offers. */
fun TravelViewModel.startPartialSearch(query: FlightQuery) {
    _partialFlow.value = PartialFlowState(loading = true)
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
            .onSuccess { _partialFlow.value = PartialFlowState(requestId = it.id, offers = it.offers) }
            .onFailure { _partialFlow.value = PartialFlowState(error = errorMessage(it)) }
    }
}

/** Load the return leg's offers after an outbound partial offer is chosen. */
fun TravelViewModel.loadPartialReturn(outboundId: String) {
    val requestId = _partialFlow.value.requestId
    if (requestId.isBlank()) {
        _partialFlow.value = _partialFlow.value.copy(error = "Please restart your search.")
        return
    }
    _partialFlow.value = _partialFlow.value.copy(loading = true, error = null, offers = emptyList())
    viewModelScope.launch {
        runCatching { TravelApi.selectPartialOffer(requestId, listOf(outboundId)) }
            .onSuccess { _partialFlow.value = _partialFlow.value.copy(loading = false, requestId = it.id.ifBlank { requestId }, offers = it.offers) }
            .onFailure { _partialFlow.value = _partialFlow.value.copy(loading = false, error = errorMessage(it)) }
    }
}

/** Load the final orderable fares after both legs are chosen. */
fun TravelViewModel.loadPartialFares(outboundId: String, returnId: String) {
    val requestId = _partialFlow.value.requestId
    if (requestId.isBlank()) {
        _partialFlow.value = _partialFlow.value.copy(error = "Please restart your search.")
        return
    }
    _partialFlow.value = _partialFlow.value.copy(loading = true, error = null, offers = emptyList())
    viewModelScope.launch {
        runCatching { TravelApi.partialOfferFares(requestId, listOf(outboundId, returnId)) }
            .onSuccess { _partialFlow.value = _partialFlow.value.copy(loading = false, offers = it.offers) }
            .onFailure { _partialFlow.value = _partialFlow.value.copy(loading = false, error = errorMessage(it)) }
    }
}

/** Look up a partial-flow offer by id (for seeding the review screen). */
fun TravelViewModel.partialOfferById(offerId: String): OfferDto? =
    _partialFlow.value.offers.find { it.offerId == offerId }

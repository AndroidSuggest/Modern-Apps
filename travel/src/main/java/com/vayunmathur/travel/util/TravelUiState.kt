package com.vayunmathur.travel.util

import androidx.annotation.StringRes
import com.vayunmathur.travel.R
import com.vayunmathur.travel.network.CancellationDto
import com.vayunmathur.travel.network.ChangeOfferDto
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OrderDetailDto
import com.vayunmathur.travel.network.OrderResultDto
import com.vayunmathur.travel.network.SeatCabinDto
import com.vayunmathur.travel.network.StayBookingResultDto
import com.vayunmathur.travel.network.StayRatesDto
import com.vayunmathur.travel.network.StaySearchResultDto

/** Search state for the flight results list. */
data class SearchUiState<T>(
    val loading: Boolean = false,
    val error: String? = null,
    val results: List<T> = emptyList(),
    val hasSearched: Boolean = false,
)

/** A normalized flight query, carried by the results route and re-used for re-sort. */
data class FlightQuery(
    val slices: String,
    val adults: Int = 1,
    val children: String = "",
    val infants: Int = 0,
    val cabin: String = "economy",
    val maxConnections: Int = -1,
    val isRoundTrip: Boolean = false,
)

/** Server-side sort options for the offer list. */
enum class OfferSort(val key: String?, @StringRes val label: Int) {
    BEST(null, R.string.sort_best),
    CHEAPEST("total_amount", R.string.sort_cheapest),
    FASTEST("total_duration", R.string.sort_fastest),
}

/** Client-side filters applied over the fetched offers. */
data class FlightFilters(
    val maxStops: Int? = null,
    val airlines: Set<String> = emptySet(),
    val fareBrand: String? = null,
)

/** State for the flight results screen: offers plus sort/filter controls. */
data class FlightResultsState(
    val loading: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false,
    val offerRequestId: String = "",
    val allOffers: List<OfferDto> = emptyList(),
    val sort: OfferSort = OfferSort.BEST,
    val filters: FlightFilters = FlightFilters(),
    /** True while still polling for more offers (incremental search). */
    val polling: Boolean = false,
) {
    /** Offers after applying the client-side filters. */
    val visibleOffers: List<OfferDto>
        get() = allOffers.filter { offer ->
            (filters.maxStops == null || offer.slices.all { it.stops <= filters.maxStops }) &&
                (filters.airlines.isEmpty() || offer.airlineIatas.any { it in filters.airlines }) &&
                (filters.fareBrand == null || offer.fareBrand == filters.fareBrand)
        }

    /** Distinct airline IATA codes present in the results, for the filter UI. */
    val availableAirlines: List<String>
        get() = allOffers.flatMap { it.airlineIatas }.distinct().sorted()

    /** Distinct fare brands present in the results, for the filter UI. */
    val availableFareBrands: List<String>
        get() = allOffers.map { it.fareBrand }.filter { it.isNotBlank() }.distinct().sorted()
}

/** State for the single-offer review (re-price) screen. */
data class OfferReviewState(
    val loading: Boolean = false,
    val error: String? = null,
    val offer: OfferDto? = null,
)

/**
 * State for the step-by-step (partial offer) round-trip flow. [offers] holds the
 * choices for the current leg (outbound, then return, then final fares);
 * [requestId] threads the partial offer request across steps.
 */
data class PartialFlowState(
    val loading: Boolean = false,
    val error: String? = null,
    val requestId: String = "",
    val offers: List<OfferDto> = emptyList(),
)

/** State for the seat-map screen. */
data class SeatMapState(
    val loading: Boolean = false,
    val error: String? = null,
    val cabins: List<SeatCabinDto> = emptyList(),
)

/** Booking lifecycle for the payment/confirmation flow. */
sealed interface BookingState {
    data object Idle : BookingState
    data object Loading : BookingState
    data class Success(val result: OrderResultDto) : BookingState
    data class Error(val message: String) : BookingState
}

/** Lifecycle for the pay-later action on a hold order. */
sealed interface PaymentActionState {
    data object Idle : PaymentActionState
    data object Loading : PaymentActionState
    data object Success : PaymentActionState
    data class Error(val message: String) : PaymentActionState
}

/** State for the remote Trips sync (order list). */
data class RemoteOrdersState(
    val loading: Boolean = false,
    val error: String? = null,
    val orders: List<OrderDetailDto> = emptyList(),
)

/** State for a single remote order-detail screen. */
data class OrderDetailState(
    val loading: Boolean = false,
    val error: String? = null,
    val order: OrderDetailDto? = null,
)

/** State for the cancellation flow (quote → confirm). */
data class CancellationState(
    val loading: Boolean = false,
    val error: String? = null,
    val quote: CancellationDto? = null,
    val confirming: Boolean = false,
    val done: Boolean = false,
)

/** State for the change flow (request → offers → confirm). */
data class ChangeState(
    val loading: Boolean = false,
    val error: String? = null,
    val requested: Boolean = false,
    val offers: List<ChangeOfferDto> = emptyList(),
    val confirming: Boolean = false,
    val done: Boolean = false,
)

/** State for the stays search results. */
data class StaySearchState(
    val loading: Boolean = false,
    val error: String? = null,
    val results: List<StaySearchResultDto> = emptyList(),
    val hasSearched: Boolean = false,
)

/** State for the accommodation rates screen. */
data class StayRatesState(
    val loading: Boolean = false,
    val error: String? = null,
    val rates: StayRatesDto? = null,
)

/** Booking lifecycle for a stay. */
sealed interface StayBookingState {
    data object Idle : StayBookingState
    data object Loading : StayBookingState
    data class Success(val result: StayBookingResultDto) : StayBookingState
    data class Error(val message: String) : StayBookingState
}

package com.vayunmathur.travel.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.FrequentFlyer
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.network.AirlineDto
import com.vayunmathur.travel.network.AircraftDto
import com.vayunmathur.travel.network.CityDto
import com.vayunmathur.travel.network.OrderEventDto
import com.vayunmathur.travel.network.PassengerInputDto
import com.vayunmathur.travel.network.SeatElementDto
import com.vayunmathur.travel.network.StayQuoteDto
import com.vayunmathur.travel.network.StayRateDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The single hub for the Travel app: owns flight search + the
 * offer→passengers→order booking flow (calling [TravelApi][com.vayunmathur.travel.network.TravelApi]
 * and exposing loading/error state as [StateFlow]), persists recent searches, and stores
 * booked trips through the Room [RecentSearchDao][com.vayunmathur.travel.data.RecentSearchDao] /
 * [BookedTripDao][com.vayunmathur.travel.data.BookedTripDao].
 *
 * State classes live in [TravelUiState.kt]; the operations live in same-package files
 * ([TravelCustomerOps.kt][saveFrequentFlyer], [TravelSearchOps.kt][searchFlights],
 * [TravelBookingOps.kt][createOrder], [TravelOrderOps.kt][loadRemoteOrders],
 * [TravelStayOps.kt][searchStays]) as extensions over the internals below.
 */
class TravelViewModel(
    application: Application,
    internal val repository: TravelRepository,
) : AndroidViewModel(application) {

    internal val flightsMutable = MutableStateFlow(FlightResultsState())
    val flights: StateFlow<FlightResultsState> = flightsMutable.asStateFlow()

    /** The query backing the current results, kept so re-sort can re-fetch. */
    internal var currentQuery: FlightQuery? = null

    internal val reviewMutable = MutableStateFlow(OfferReviewState())
    val review: StateFlow<OfferReviewState> = reviewMutable.asStateFlow()

    internal val partialFlowMutable = MutableStateFlow(PartialFlowState())
    val partialFlow: StateFlow<PartialFlowState> = partialFlowMutable.asStateFlow()

    internal val passengersMutable = MutableStateFlow<List<PassengerInputDto>>(emptyList())
    val passengers: StateFlow<List<PassengerInputDto>> = passengersMutable.asStateFlow()

    internal val seatMapMutable = MutableStateFlow(SeatMapState())
    val seatMap: StateFlow<SeatMapState> = seatMapMutable.asStateFlow()

    /** Selected extra-baggage services: service id → quantity. */
    internal val selectedBaggageMutable = MutableStateFlow<Map<String, Long>>(emptyMap())
    val selectedBaggage: StateFlow<Map<String, Long>> = selectedBaggageMutable.asStateFlow()

    /** Selected non-baggage extra services (CFAR, priority boarding, …): id → quantity. */
    internal val selectedExtrasMutable = MutableStateFlow<Map<String, Long>>(emptyMap())
    val selectedExtras: StateFlow<Map<String, Long>> = selectedExtrasMutable.asStateFlow()

    /** Selected seats keyed by `"segmentId|designator"`. */
    internal val selectedSeatsMutable = MutableStateFlow<Map<String, SeatElementDto>>(emptyMap())
    val selectedSeats: StateFlow<Map<String, SeatElementDto>> = selectedSeatsMutable.asStateFlow()

    internal val airlinesMutable = MutableStateFlow<List<AirlineDto>>(emptyList())
    val airlines: StateFlow<List<AirlineDto>> = airlinesMutable.asStateFlow()

    internal val aircraftMutable = MutableStateFlow<List<AircraftDto>>(emptyList())
    val aircraft: StateFlow<List<AircraftDto>> = aircraftMutable.asStateFlow()

    internal val citiesMutable = MutableStateFlow<List<CityDto>>(emptyList())
    val cities: StateFlow<List<CityDto>> = citiesMutable.asStateFlow()

    internal val bookingMutable = MutableStateFlow<BookingState>(BookingState.Idle)
    val booking: StateFlow<BookingState> = bookingMutable.asStateFlow()

    internal val paymentMutable = MutableStateFlow<PaymentActionState>(PaymentActionState.Idle)
    val payment: StateFlow<PaymentActionState> = paymentMutable.asStateFlow()

    internal val remoteOrdersMutable = MutableStateFlow(RemoteOrdersState())
    val remoteOrders: StateFlow<RemoteOrdersState> = remoteOrdersMutable.asStateFlow()

    internal val orderDetailMutable = MutableStateFlow(OrderDetailState())
    val orderDetail: StateFlow<OrderDetailState> = orderDetailMutable.asStateFlow()

    internal val cancellationMutable = MutableStateFlow(CancellationState())
    val cancellation: StateFlow<CancellationState> = cancellationMutable.asStateFlow()

    internal val changeMutable = MutableStateFlow(ChangeState())
    val change: StateFlow<ChangeState> = changeMutable.asStateFlow()

    internal val stayResultsMutable = MutableStateFlow(StaySearchState())
    val stayResults: StateFlow<StaySearchState> = stayResultsMutable.asStateFlow()

    internal val stayRatesMutable = MutableStateFlow(StayRatesState())
    val stayRates: StateFlow<StayRatesState> = stayRatesMutable.asStateFlow()

    internal val stayBookingMutable = MutableStateFlow<StayBookingState>(StayBookingState.Idle)
    val stayBooking: StateFlow<StayBookingState> = stayBookingMutable.asStateFlow()

    /** The rate the user chose to book, plus context for persistence. */
    internal var selectedRate: StayRateDto? = null
    internal var selectedStayName: String = ""
    internal var selectedCheckIn: String = ""
    internal var selectedCheckOut: String = ""
    internal var stayQuote: StayQuoteDto? = null

    val recentSearches: StateFlow<List<RecentSearch>> = repository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookedTrips: StateFlow<List<BookedTrip>> = repository.observeBookedTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Saved frequent-flyer accounts, applied as loyalty pricing at search. */
    val frequentFlyers: StateFlow<List<FrequentFlyer>> = repository.observeFrequentFlyers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Customers (Duffel customer users) --------------------------------

    internal val dataStore = DataStoreUtils.getInstance(application)
    internal val activeCustomerKey = "travel_active_customer_id"

    /** Saved customer users; orders are associated with the active one. */
    val customers: StateFlow<List<Customer>> = repository.observeCustomers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The id of the currently selected customer (persisted), or blank. */
    val activeCustomerId: StateFlow<String> = dataStore.stringFlow(activeCustomerKey)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    internal val customerErrorMutable = MutableStateFlow<String?>(null)
    val customerError: StateFlow<String?> = customerErrorMutable.asStateFlow()

    /** Recorded webhook events per order id (schedule changes / cancellations). */
    internal val orderEventsMutable = MutableStateFlow<Map<String, List<OrderEventDto>>>(emptyMap())
    val orderEvents: StateFlow<Map<String, List<OrderEventDto>>> = orderEventsMutable.asStateFlow()

    companion object {
        /** "2026-09-01" -> "Sep 1"; falls back to the raw string on any parse error. */
        fun prettyDate(iso: String): String = runCatching {
            val date = java.time.LocalDate.parse(iso.take(10))
            date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
        }.getOrDefault(iso)
    }
}

/** Shared network-error text for the travel operations. */
internal fun TravelViewModel.errorMessage(t: Throwable): String =
    t.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again."

class TravelViewModelFactory(
    private val application: Application,
    private val repository: TravelRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TravelViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return TravelViewModel(application, repository) as T
    }
}

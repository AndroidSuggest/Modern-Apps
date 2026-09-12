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

    internal val _flights = MutableStateFlow(FlightResultsState())
    val flights: StateFlow<FlightResultsState> = _flights.asStateFlow()

    /** The query backing the current results, kept so re-sort can re-fetch. */
    internal var currentQuery: FlightQuery? = null

    internal val _review = MutableStateFlow(OfferReviewState())
    val review: StateFlow<OfferReviewState> = _review.asStateFlow()

    internal val _partialFlow = MutableStateFlow(PartialFlowState())
    val partialFlow: StateFlow<PartialFlowState> = _partialFlow.asStateFlow()

    internal val _passengers = MutableStateFlow<List<PassengerInputDto>>(emptyList())
    val passengers: StateFlow<List<PassengerInputDto>> = _passengers.asStateFlow()

    internal val _seatMap = MutableStateFlow(SeatMapState())
    val seatMap: StateFlow<SeatMapState> = _seatMap.asStateFlow()

    /** Selected extra-baggage services: service id → quantity. */
    internal val _selectedBaggage = MutableStateFlow<Map<String, Long>>(emptyMap())
    val selectedBaggage: StateFlow<Map<String, Long>> = _selectedBaggage.asStateFlow()

    /** Selected non-baggage extra services (CFAR, priority boarding, …): id → quantity. */
    internal val _selectedExtras = MutableStateFlow<Map<String, Long>>(emptyMap())
    val selectedExtras: StateFlow<Map<String, Long>> = _selectedExtras.asStateFlow()

    /** Selected seats keyed by `"segmentId|designator"`. */
    internal val _selectedSeats = MutableStateFlow<Map<String, SeatElementDto>>(emptyMap())
    val selectedSeats: StateFlow<Map<String, SeatElementDto>> = _selectedSeats.asStateFlow()

    internal val _airlines = MutableStateFlow<List<AirlineDto>>(emptyList())
    val airlines: StateFlow<List<AirlineDto>> = _airlines.asStateFlow()

    internal val _aircraft = MutableStateFlow<List<AircraftDto>>(emptyList())
    val aircraft: StateFlow<List<AircraftDto>> = _aircraft.asStateFlow()

    internal val _cities = MutableStateFlow<List<CityDto>>(emptyList())
    val cities: StateFlow<List<CityDto>> = _cities.asStateFlow()

    internal val _booking = MutableStateFlow<BookingState>(BookingState.Idle)
    val booking: StateFlow<BookingState> = _booking.asStateFlow()

    internal val _payment = MutableStateFlow<PaymentActionState>(PaymentActionState.Idle)
    val payment: StateFlow<PaymentActionState> = _payment.asStateFlow()

    internal val _remoteOrders = MutableStateFlow(RemoteOrdersState())
    val remoteOrders: StateFlow<RemoteOrdersState> = _remoteOrders.asStateFlow()

    internal val _orderDetail = MutableStateFlow(OrderDetailState())
    val orderDetail: StateFlow<OrderDetailState> = _orderDetail.asStateFlow()

    internal val _cancellation = MutableStateFlow(CancellationState())
    val cancellation: StateFlow<CancellationState> = _cancellation.asStateFlow()

    internal val _change = MutableStateFlow(ChangeState())
    val change: StateFlow<ChangeState> = _change.asStateFlow()

    internal val _stayResults = MutableStateFlow(StaySearchState())
    val stayResults: StateFlow<StaySearchState> = _stayResults.asStateFlow()

    internal val _stayRates = MutableStateFlow(StayRatesState())
    val stayRates: StateFlow<StayRatesState> = _stayRates.asStateFlow()

    internal val _stayBooking = MutableStateFlow<StayBookingState>(StayBookingState.Idle)
    val stayBooking: StateFlow<StayBookingState> = _stayBooking.asStateFlow()

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

    internal val _customerError = MutableStateFlow<String?>(null)
    val customerError: StateFlow<String?> = _customerError.asStateFlow()

    /** Recorded webhook events per order id (schedule changes / cancellations). */
    internal val _orderEvents = MutableStateFlow<Map<String, List<OrderEventDto>>>(emptyMap())
    val orderEvents: StateFlow<Map<String, List<OrderEventDto>>> = _orderEvents.asStateFlow()

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

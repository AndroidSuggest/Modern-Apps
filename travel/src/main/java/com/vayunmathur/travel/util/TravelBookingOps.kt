package com.vayunmathur.travel.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.network.LoyaltyAccountDto
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OrderResultDto
import com.vayunmathur.travel.network.PassengerInputDto
import com.vayunmathur.travel.network.PaymentInputDto
import com.vayunmathur.travel.network.OrderRequestDto
import com.vayunmathur.travel.network.SeatElementDto
import com.vayunmathur.travel.network.ServiceSelectionDto
import com.vayunmathur.travel.network.TravelApi
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Initialize one blank passenger per Duffel passenger id on the offer,
 * unless a matching set is already present (so returning to the form keeps
 * entered values).
 */
fun TravelViewModel.initPassengers(offer: OfferDto) {
    val ids = offer.passengerIds.ifEmpty { listOf("") }
    if (_passengers.value.map { it.id } == ids) return
    _passengers.value = ids.map { PassengerInputDto(id = it) }
    // Pre-fill the lead passenger with any saved frequent-flyer accounts.
    viewModelScope.launch {
        val saved = repository.getAllFrequentFlyers()
        if (saved.isEmpty()) return@launch
        val loyalty = saved.map {
            LoyaltyAccountDto(airlineIataCode = it.airlineIata, accountNumber = it.accountNumber)
        }
        _passengers.value = _passengers.value.toMutableList().also { list ->
            list.firstOrNull()?.let { lead ->
                if (lead.loyaltyProgrammeAccounts.isEmpty()) {
                    list[0] = lead.copy(loyaltyProgrammeAccounts = loyalty)
                }
            }
        }
    }
}

fun TravelViewModel.updatePassenger(index: Int, passenger: PassengerInputDto) {
    _passengers.value = _passengers.value.toMutableList().also {
        if (index in it.indices) it[index] = passenger
    }
}

/** Set the selected quantity for an extra-baggage service (0 removes it). */
fun TravelViewModel.setBaggageQuantity(serviceId: String, quantity: Long) {
    _selectedBaggage.value = _selectedBaggage.value.toMutableMap().also {
        if (quantity <= 0) it.remove(serviceId) else it[serviceId] = quantity
    }
}

/** Set the selected quantity for a non-baggage extra service (0 removes it). */
fun TravelViewModel.setExtraQuantity(serviceId: String, quantity: Long) {
    _selectedExtras.value = _selectedExtras.value.toMutableMap().also {
        if (quantity <= 0) it.remove(serviceId) else it[serviceId] = quantity
    }
}

/** Fetch seat maps for the given offer. */
fun TravelViewModel.loadSeatMaps(offerId: String) {
    _seatMap.value = SeatMapState(loading = true)
    viewModelScope.launch {
        runCatching { TravelApi.seatMap(offerId) }
            .onSuccess { _seatMap.value = SeatMapState(cabins = it) }
            .onFailure { _seatMap.value = SeatMapState(error = errorMessage(it)) }
    }
}

/** Load the airline reference list once (for the frequent-flyer picker). */
fun TravelViewModel.loadAirlines() {
    if (_airlines.value.isNotEmpty()) return
    viewModelScope.launch {
        runCatching { TravelApi.airlines() }
            .onSuccess { list -> _airlines.value = list.filter { it.iataCode.isNotBlank() }.sortedBy { it.name } }
    }
}

/** Load the aircraft reference list once, to label segments by aircraft name. */
fun TravelViewModel.loadAircraft() {
    if (_aircraft.value.isNotEmpty()) return
    viewModelScope.launch {
        runCatching { TravelApi.aircraft() }.onSuccess { _aircraft.value = it }
    }
}

/** Load the cities reference list once, to improve place labels. */
fun TravelViewModel.loadCities() {
    if (_cities.value.isNotEmpty()) return
    viewModelScope.launch {
        runCatching { TravelApi.cities() }.onSuccess { _cities.value = it }
    }
}

/** Human aircraft name for an IATA aircraft code, falling back to the code. */
fun TravelViewModel.aircraftName(iataCode: String): String =
    _aircraft.value.firstOrNull { it.iataCode == iataCode }?.name?.ifBlank { iataCode } ?: iataCode

/** City name for an IATA city code, falling back to the code. */
fun TravelViewModel.cityName(iataCode: String): String =
    _cities.value.firstOrNull { it.iataCode == iataCode }?.name?.ifBlank { iataCode } ?: iataCode

/** Toggle selection of a (selectable) seat on a segment. */
fun TravelViewModel.toggleSeat(segmentId: String, seat: SeatElementDto) {
    if (!seat.available || seat.serviceId == null) return
    val key = "$segmentId|${seat.designator}"
    _selectedSeats.value = _selectedSeats.value.toMutableMap().also {
        if (it.containsKey(key)) it.remove(key) else it[key] = seat
    }
}

/** Combined selected services (baggage + extras + seats) for the order body,
 *  tagged with the passenger each service belongs to when known. */
internal fun TravelViewModel.selectedServices(): List<ServiceSelectionDto> {
    val available = _review.value.offer?.availableServices.orEmpty()
    fun passengerFor(id: String): String? =
        available.firstOrNull { it.id == id }?.passengerIds?.firstOrNull()
    val bags = _selectedBaggage.value.map { (id, qty) ->
        ServiceSelectionDto(id = id, quantity = qty, passengerId = passengerFor(id))
    }
    val extras = _selectedExtras.value.map { (id, qty) ->
        ServiceSelectionDto(id = id, quantity = qty, passengerId = passengerFor(id))
    }
    val seats = _selectedSeats.value.values.mapNotNull { seat ->
        seat.serviceId?.let { ServiceSelectionDto(id = it, quantity = 1) }
    }
    return bags + extras + seats
}

/** Book the reviewed offer with the collected passengers via test balance. */
fun TravelViewModel.createOrder(hold: Boolean = false) {
    val offer = _review.value.offer ?: return
    val pax = _passengers.value
    val customerId = activeCustomerId.value.ifBlank { null }
    _booking.value = BookingState.Loading
    viewModelScope.launch {
        runCatching {
            TravelApi.createOrder(
                OrderRequestDto(
                    offerId = offer.offerId,
                    passengers = pax,
                    payment = PaymentInputDto(type = "balance"),
                    services = selectedServices(),
                    hold = hold,
                    customerUserId = customerId,
                )
            )
        }
            .onSuccess { result ->
                persistTrip(offer, pax, result, customerId.orEmpty())
                _booking.value = BookingState.Success(result)
            }
            .onFailure { _booking.value = BookingState.Error(errorMessage(it)) }
    }
}

/** Settle a hold order later; updates the stored trip on success. */
fun TravelViewModel.payOrder(orderId: String) {
    _payment.value = PaymentActionState.Loading
    viewModelScope.launch {
        runCatching { TravelApi.payOrder(orderId) }
            .onSuccess { result ->
                repository.getBookedTrip(orderId)?.let { trip ->
                    repository.upsertBookedTrip(
                        trip.copy(
                            awaitingPayment = result.awaitingPayment,
                            paymentRequiredBy = result.paymentRequiredBy.orEmpty(),
                            amount = result.totalAmount,
                            currency = result.currency,
                        )
                    )
                }
                _payment.value = PaymentActionState.Success
            }
            .onFailure { _payment.value = PaymentActionState.Error(errorMessage(it)) }
    }
}

fun TravelViewModel.resetPaymentAction() {
    _payment.value = PaymentActionState.Idle
}

fun TravelViewModel.tripById(orderId: String): BookedTrip? = bookedTrips.value.find { it.orderId == orderId }

internal suspend fun TravelViewModel.persistTrip(
    offer: OfferDto,
    passengers: List<PassengerInputDto>,
    result: OrderResultDto,
    customerId: String = "",
) {
    val first = offer.slices.firstOrNull()
    val roundTrip = offer.slices.size > 1
    val route = if (first == null) {
        ""
    } else {
        val base = "${first.origin} → ${first.destination}"
        if (roundTrip) "$base (round trip)" else base
    }
    repository.upsertBookedTrip(
        BookedTrip(
            orderId = result.orderId,
            bookingReference = result.bookingReference,
            route = route,
            departDate = first?.departureAt?.take(10).orEmpty(),
            amount = result.totalAmount,
            currency = result.currency,
            passengersJson = runCatching { bookingJson.encodeToString(passengers) }.getOrDefault(""),
            status = if (result.awaitingPayment) "on hold" else "confirmed",
            type = "flight",
            awaitingPayment = result.awaitingPayment,
            paymentRequiredBy = result.paymentRequiredBy.orEmpty(),
            customerId = customerId,
        )
    )
}

private val bookingJson = Json { ignoreUnknownKeys = true }

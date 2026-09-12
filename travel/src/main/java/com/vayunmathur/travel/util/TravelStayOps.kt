package com.vayunmathur.travel.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.network.StayBookingRequestDto
import com.vayunmathur.travel.network.StayBookingResultDto
import com.vayunmathur.travel.network.StayGuestInputDto
import com.vayunmathur.travel.network.StayRateDto
import com.vayunmathur.travel.network.StaySuggestionDto
import com.vayunmathur.travel.network.StaysApi
import kotlinx.coroutines.launch

fun TravelViewModel.searchStays(
    place: String,
    checkIn: String,
    checkOut: String,
    rooms: Int,
    adults: Int,
    latitude: Double? = null,
    longitude: Double? = null,
) {
    selectedCheckIn = checkIn
    selectedCheckOut = checkOut
    recordRecent(
        RecentSearch(
            vertical = "STAYS",
            label = "$place · ${TravelViewModel.prettyDate(checkIn)}–${TravelViewModel.prettyDate(checkOut)}",
            origin = place,
            depart = checkIn,
            returnDate = checkOut,
            adults = adults,
        )
    )
    _stayResults.value = StaySearchState(loading = true, hasSearched = true)
    viewModelScope.launch {
        runCatching { StaysApi.search(place, checkIn, checkOut, rooms, adults, latitude = latitude, longitude = longitude) }
            .onSuccess { _stayResults.value = StaySearchState(results = it, hasSearched = true) }
            .onFailure { _stayResults.value = StaySearchState(error = errorMessage(it), hasSearched = true) }
    }
}

/** Accommodation/location suggestions for the stays search box. */
suspend fun TravelViewModel.staySuggestions(query: String): List<StaySuggestionDto> =
    runCatching { StaysApi.suggestions(query) }.getOrDefault(emptyList())

fun TravelViewModel.loadStayRates(searchResultId: String, accommodationName: String) {
    selectedStayName = accommodationName
    _stayRates.value = StayRatesState(loading = true)
    viewModelScope.launch {
        runCatching { StaysApi.rates(searchResultId) }
            .onSuccess {
                if (it.name.isNotBlank()) selectedStayName = it.name
                _stayRates.value = StayRatesState(rates = it)
            }
            .onFailure { _stayRates.value = StayRatesState(error = errorMessage(it)) }
    }
}

/** Choose a rate and quote it (confirming price) before collecting guests. */
fun TravelViewModel.selectStayRate(rate: StayRateDto) {
    selectedRate = rate
    stayQuote = null
    _stayBooking.value = StayBookingState.Idle
    viewModelScope.launch {
        runCatching { StaysApi.quote(rate.id) }
            .onSuccess { stayQuote = it }
            .onFailure { /* fall back to the rate price at book time */ }
    }
}

fun TravelViewModel.selectedStayRate(): StayRateDto? = selectedRate

/** The confirmed quote total, falling back to the selected rate's price. */
fun TravelViewModel.stayTotal(): Pair<String, String> {
    val q = stayQuote
    val r = selectedRate
    return when {
        q != null -> q.totalAmount to q.totalCurrency
        r != null -> r.totalAmount to r.totalCurrency
        else -> "0" to "USD"
    }
}

fun TravelViewModel.bookStay(guest: StayGuestInputDto, email: String, phone: String) {
    val quoteId = stayQuote?.id
    if (quoteId == null) {
        _stayBooking.value = StayBookingState.Error("This rate is no longer available. Please pick another.")
        return
    }
    _stayBooking.value = StayBookingState.Loading
    viewModelScope.launch {
        runCatching {
            StaysApi.book(
                StayBookingRequestDto(
                    quoteId = quoteId,
                    guests = listOf(guest),
                    email = email,
                    phoneNumber = phone,
                )
            )
        }
            .onSuccess { result ->
                persistStay(result)
                _stayBooking.value = StayBookingState.Success(result)
            }
            .onFailure { _stayBooking.value = StayBookingState.Error(errorMessage(it)) }
    }
}

fun TravelViewModel.resetStayBooking() {
    _stayBooking.value = StayBookingState.Idle
}

private suspend fun TravelViewModel.persistStay(result: StayBookingResultDto) {
    val name = result.accommodationName.ifBlank { selectedStayName }
    val checkIn = result.checkInDate.ifBlank { selectedCheckIn }
    // Stay bookings don't carry a total; fall back to the confirmed quote.
    val (quoteAmount, quoteCurrency) = stayTotal()
    val amount = result.totalAmount.takeIf { it.isNotBlank() && it != "0" } ?: quoteAmount
    val currency = result.totalCurrency.takeIf { it.isNotBlank() } ?: quoteCurrency
    repository.upsertBookedTrip(
        BookedTrip(
            orderId = result.id,
            bookingReference = result.reference,
            route = name,
            departDate = checkIn.take(10),
            amount = amount,
            currency = currency,
            status = result.status.ifBlank { "confirmed" },
            type = "stay",
            customerId = activeCustomerId.value,
        )
    )
}

fun TravelViewModel.clearRecents() {
    viewModelScope.launch { repository.clearRecent() }
}

internal fun TravelViewModel.recordRecent(search: RecentSearch) {
    viewModelScope.launch {
        repository.insertRecent(search)
        repository.trimRecent()
    }
}

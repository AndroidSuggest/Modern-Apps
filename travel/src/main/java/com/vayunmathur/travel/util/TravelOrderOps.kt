package com.vayunmathur.travel.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.travel.network.ChangeRequestInputDto
import com.vayunmathur.travel.network.OrderEventDto
import com.vayunmathur.travel.network.SearchSliceInputDto
import com.vayunmathur.travel.network.TravelApi
import kotlinx.coroutines.launch

/** Fetch the account's remote orders for the Trips hub. */
fun TravelViewModel.loadRemoteOrders() {
    _remoteOrders.value = RemoteOrdersState(loading = true)
    viewModelScope.launch {
        runCatching { TravelApi.listOrders() }
            .onSuccess { _remoteOrders.value = RemoteOrdersState(orders = it) }
            .onFailure { _remoteOrders.value = RemoteOrdersState(error = errorMessage(it)) }
    }
}

/** Fetch full detail for a single remote order. */
fun TravelViewModel.loadOrderDetail(orderId: String) {
    _orderDetail.value = OrderDetailState(loading = true)
    viewModelScope.launch {
        runCatching { TravelApi.orderDetail(orderId) }
            .onSuccess { _orderDetail.value = OrderDetailState(order = it) }
            .onFailure { _orderDetail.value = OrderDetailState(error = errorMessage(it)) }
    }
}

/** Poll the server for order events; updates [TravelViewModel.orderEvents] for [orderId]. */
fun TravelViewModel.loadOrderEvents(orderId: String) {
    if (orderId.isBlank()) return
    viewModelScope.launch {
        runCatching { TravelApi.orderEvents(orderId) }
            .onSuccess { events ->
                _orderEvents.value = _orderEvents.value.toMutableMap().also { it[orderId] = events }
            }
    }
}

fun TravelViewModel.resetCancellation() {
    _cancellation.value = CancellationState()
}

/** Fetch a refund quote for cancelling [orderId]. */
fun TravelViewModel.quoteCancellation(orderId: String) {
    _cancellation.value = CancellationState(loading = true)
    viewModelScope.launch {
        runCatching { TravelApi.cancelQuote(orderId) }
            .onSuccess { _cancellation.value = CancellationState(quote = it) }
            .onFailure { _cancellation.value = CancellationState(error = errorMessage(it)) }
    }
}

/** Confirm the pending cancellation and mark the local trip cancelled. */
fun TravelViewModel.confirmCancellation(orderId: String) {
    val quote = _cancellation.value.quote ?: return
    _cancellation.value = _cancellation.value.copy(confirming = true, error = null)
    viewModelScope.launch {
        runCatching { TravelApi.confirmCancellation(quote.id) }
            .onSuccess {
                repository.getBookedTrip(orderId)?.let {
                    repository.upsertBookedTrip(it.copy(status = "cancelled", awaitingPayment = false))
                }
                _cancellation.value = _cancellation.value.copy(confirming = false, done = true)
            }
            .onFailure {
                _cancellation.value = _cancellation.value.copy(confirming = false, error = errorMessage(it))
            }
    }
}

fun TravelViewModel.resetChange() {
    _change.value = ChangeState()
}

/** Request a change: remove [removeSliceId] and add a slice on [newDate]. */
fun TravelViewModel.requestChange(
    orderId: String,
    removeSliceId: String,
    origin: String,
    destination: String,
    newDate: String,
    cabin: String,
) {
    _change.value = ChangeState(loading = true)
    viewModelScope.launch {
        runCatching {
            TravelApi.changeRequest(
                orderId,
                ChangeRequestInputDto(
                    removeSliceIds = listOf(removeSliceId),
                    add = listOf(SearchSliceInputDto(origin = origin, destination = destination, date = newDate)),
                    cabin = cabin.ifBlank { null },
                ),
            )
        }
            .onSuccess { _change.value = ChangeState(requested = true, offers = it.offers) }
            .onFailure { _change.value = ChangeState(requested = true, error = errorMessage(it)) }
    }
}

/** Accept a change offer, settling any difference via balance. */
fun TravelViewModel.confirmChange(orderId: String, offerId: String) {
    _change.value = _change.value.copy(confirming = true, error = null)
    viewModelScope.launch {
        runCatching { TravelApi.confirmChange(offerId) }
            .onSuccess { result ->
                repository.getBookedTrip(orderId)?.let {
                    repository.upsertBookedTrip(it.copy(amount = result.totalAmount, currency = result.currency))
                }
                _change.value = _change.value.copy(confirming = false, done = true)
            }
            .onFailure {
                _change.value = _change.value.copy(confirming = false, error = errorMessage(it))
            }
    }
}

fun TravelViewModel.resetBooking() {
    _booking.value = BookingState.Idle
}

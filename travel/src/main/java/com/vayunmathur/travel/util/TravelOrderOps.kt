package com.vayunmathur.travel.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.travel.network.ChangeRequestInputDto
import com.vayunmathur.travel.network.OrderEventDto
import com.vayunmathur.travel.network.SearchSliceInputDto
import com.vayunmathur.travel.network.TravelOrderApi
import kotlinx.coroutines.launch

/** Fetch the account's remote orders for the Trips hub. */
fun TravelViewModel.loadRemoteOrders() {
    remoteOrdersMutable.value = RemoteOrdersState(loading = true)
    viewModelScope.launch {
        runCatching { TravelOrderApi.listOrders() }
            .onSuccess { remoteOrdersMutable.value = RemoteOrdersState(orders = it) }
            .onFailure { remoteOrdersMutable.value = RemoteOrdersState(error = errorMessage(it)) }
    }
}

/** Fetch full detail for a single remote order. */
fun TravelViewModel.loadOrderDetail(orderId: String) {
    orderDetailMutable.value = OrderDetailState(loading = true)
    viewModelScope.launch {
        runCatching { TravelOrderApi.orderDetail(orderId) }
            .onSuccess { orderDetailMutable.value = OrderDetailState(order = it) }
            .onFailure { orderDetailMutable.value = OrderDetailState(error = errorMessage(it)) }
    }
}

/** Poll the server for order events; updates [TravelViewModel.orderEvents] for [orderId]. */
fun TravelViewModel.loadOrderEvents(orderId: String) {
    if (orderId.isBlank()) return
    viewModelScope.launch {
        runCatching { TravelOrderApi.orderEvents(orderId) }
            .onSuccess { events ->
                orderEventsMutable.value = orderEventsMutable.value.toMutableMap().also { it[orderId] = events }
            }
    }
}

fun TravelViewModel.resetCancellation() {
    cancellationMutable.value = CancellationState()
}

/** Fetch a refund quote for cancelling [orderId]. */
fun TravelViewModel.quoteCancellation(orderId: String) {
    cancellationMutable.value = CancellationState(loading = true)
    viewModelScope.launch {
        runCatching { TravelOrderApi.cancelQuote(orderId) }
            .onSuccess { cancellationMutable.value = CancellationState(quote = it) }
            .onFailure { cancellationMutable.value = CancellationState(error = errorMessage(it)) }
    }
}

/** Confirm the pending cancellation and mark the local trip cancelled. */
fun TravelViewModel.confirmCancellation(orderId: String) {
    val quote = cancellationMutable.value.quote ?: return
    cancellationMutable.value = cancellationMutable.value.copy(confirming = true, error = null)
    viewModelScope.launch {
        runCatching { TravelOrderApi.confirmCancellation(quote.id) }
            .onSuccess {
                repository.getBookedTrip(orderId)?.let {
                    repository.upsertBookedTrip(it.copy(status = "cancelled", awaitingPayment = false))
                }
                cancellationMutable.value = cancellationMutable.value.copy(confirming = false, done = true)
            }
            .onFailure {
                cancellationMutable.value = cancellationMutable.value.copy(confirming = false, error = errorMessage(it))
            }
    }
}

fun TravelViewModel.resetChange() {
    changeMutable.value = ChangeState()
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
    changeMutable.value = ChangeState(loading = true)
    viewModelScope.launch {
        runCatching {
            TravelOrderApi.changeRequest(
                orderId,
                ChangeRequestInputDto(
                    removeSliceIds = listOf(removeSliceId),
                    add = listOf(SearchSliceInputDto(origin = origin, destination = destination, date = newDate)),
                    cabin = cabin.ifBlank { null },
                ),
            )
        }
            .onSuccess { changeMutable.value = ChangeState(requested = true, offers = it.offers) }
            .onFailure { changeMutable.value = ChangeState(requested = true, error = errorMessage(it)) }
    }
}

/** Accept a change offer, settling any difference via balance. */
fun TravelViewModel.confirmChange(orderId: String, offerId: String) {
    changeMutable.value = changeMutable.value.copy(confirming = true, error = null)
    viewModelScope.launch {
        runCatching { TravelOrderApi.confirmChange(offerId) }
            .onSuccess { result ->
                repository.getBookedTrip(orderId)?.let {
                    repository.upsertBookedTrip(it.copy(amount = result.totalAmount, currency = result.currency))
                }
                changeMutable.value = changeMutable.value.copy(confirming = false, done = true)
            }
            .onFailure {
                changeMutable.value = changeMutable.value.copy(confirming = false, error = errorMessage(it))
            }
    }
}

fun TravelViewModel.resetBooking() {
    bookingMutable.value = BookingState.Idle
}

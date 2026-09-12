package com.vayunmathur.travel.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.FrequentFlyer
import com.vayunmathur.travel.network.CustomerUserInputDto
import com.vayunmathur.travel.network.TravelApi
import kotlinx.coroutines.launch

fun TravelViewModel.saveFrequentFlyer(airlineIata: String, accountNumber: String, airlineName: String) {
    val iata = airlineIata.trim().uppercase()
    val account = accountNumber.trim()
    if (iata.isBlank() || account.isBlank()) return
    viewModelScope.launch {
        repository.upsertFrequentFlyer(
            FrequentFlyer(airlineIata = iata, accountNumber = account, airlineName = airlineName),
        )
    }
}

fun TravelViewModel.removeFrequentFlyer(airlineIata: String) {
    viewModelScope.launch { repository.deleteFrequentFlyer(airlineIata) }
}

/** Create a Duffel customer user, store it locally, and make it active. */
fun TravelViewModel.createCustomer(email: String, givenName: String, familyName: String, phone: String) {
    viewModelScope.launch {
        runCatching {
            TravelApi.createCustomer(
                CustomerUserInputDto(
                    email = email.trim(),
                    givenName = givenName.trim(),
                    familyName = familyName.trim(),
                    phoneNumber = phone.trim().ifBlank { null },
                )
            )
        }.onSuccess { dto ->
            if (dto.id.isNotBlank()) {
                repository.upsertCustomer(
                    Customer(
                        id = dto.id,
                        email = dto.email,
                        givenName = dto.givenName,
                        familyName = dto.familyName,
                        phoneNumber = dto.phoneNumber.orEmpty(),
                    )
                )
                dataStore.setString(activeCustomerKey, dto.id)
                _customerError.value = null
            }
        }.onFailure { _customerError.value = errorMessage(it) }
    }
}

/** Select (or clear, with blank) the active customer. */
fun TravelViewModel.selectCustomer(id: String) {
    viewModelScope.launch { dataStore.setString(activeCustomerKey, id) }
}

fun TravelViewModel.removeCustomer(id: String) {
    viewModelScope.launch {
        repository.deleteCustomerById(id)
        if (activeCustomerId.value == id) dataStore.setString(activeCustomerKey, "")
    }
}

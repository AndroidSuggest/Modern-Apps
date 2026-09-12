@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.fooddelivery.ui

import android.content.Context
import android.location.Geocoder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AddressFormState(
    val label: String = "",
    val street: String = "",
    val city: String = "",
    val state: String = "",
    val zip: String = "",
    val aptUnit: String = "",
    val gateCode: String = "",
    val instructions: String = "",
    val editing: SavedAddress? = null,
    val showing: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val triedSubmit: Boolean = false,
)

@Composable
internal fun AccountAddressesSection(
    scope: CoroutineScope,
    context: Context,
    addressesLoaded: Boolean,
    addresses: List<SavedAddress>,
    onAddressesChange: (List<SavedAddress>) -> Unit,
    form: AddressFormState,
    onFormChange: (AddressFormState) -> Unit,
    onResetForm: () -> Unit,
) {
    if (addressesLoaded && addresses.isEmpty() && !form.showing) {
        Text(
            stringResource(R.string.no_saved_addresses_tap_to_add_one),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    addresses.forEach { addr ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconHome(
                            modifier = Modifier.size(20.dp),
                            tint = if (addr.isDefault) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    addr.label.ifEmpty { "Address" },
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                if (addr.isDefault) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.default_address),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                addr.addressStreet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${addr.addressCity}, ${addr.addressState} ${addr.addressZip}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = {
                        scope.launch {
                            AddressStore.delete(context, addr.id)
                            onAddressesChange(AddressStore.getAll(context))
                        }
                    }) {
                        IconDelete(
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!addr.isDefault) {
                        TextButton(onClick = {
                            scope.launch {
                                AddressStore.setDefault(context, addr.id)
                                onAddressesChange(AddressStore.getAll(context))
                            }
                        }) {
                            Text(
                                stringResource(R.string.set_default),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    TextButton(onClick = {
                        onFormChange(
                            form.copy(
                                label = addr.label,
                                street = addr.addressStreet,
                                city = addr.addressCity,
                                state = addr.addressState,
                                zip = addr.addressZip,
                                aptUnit = addr.aptUnit,
                                gateCode = addr.gateCode,
                                instructions = addr.deliveryInstructions,
                                editing = addr,
                                showing = true,
                            )
                        )
                    }) {
                        Text(
                            stringResource(R.string.edit),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }

    if (form.showing) {
        AddressFormCard(
            form = form,
            onFormChange = onFormChange,
            onCancel = onResetForm,
            onSave = {
                onFormChange(form.copy(triedSubmit = true))
                if (form.street.isBlank() || form.city.isBlank() ||
                    form.state.isBlank() || form.zip.isBlank()
                ) return@AddressFormCard
                scope.launch {
                    onFormChange(form.copy(saving = true, error = null, triedSubmit = true))
                    val fullAddress =
                        "${form.street}, ${form.city}, ${form.state} ${form.zip}"
                    val coords = geocodeAddress(context, fullAddress)
                    if (coords == null) {
                        onFormChange(
                            form.copy(
                                error = "Could not verify this address. Check the details and try again.",
                                saving = false,
                                triedSubmit = true,
                            )
                        )
                        return@launch
                    }
                    val addr = SavedAddress(
                        id = form.editing?.id ?: Uuid.random().toString(),
                        label = form.label,
                        addressStreet = form.street,
                        addressCity = form.city,
                        addressState = form.state,
                        addressZip = form.zip,
                        aptUnit = form.aptUnit,
                        gateCode = form.gateCode,
                        deliveryInstructions = form.instructions,
                        latitude = coords.first,
                        longitude = coords.second,
                        isDefault = form.editing?.isDefault ?: addresses.isEmpty()
                    )
                    AddressStore.save(context, addr)
                    onAddressesChange(AddressStore.getAll(context))
                    onResetForm()
                }
            },
        )
    }
}

// AddressFormCard lives in AddressFormCard.kt.

@Suppress("DEPRECATION")
internal suspend fun geocodeAddress(
    context: Context,
    address: String
): Pair<Double, Double>? {
    return withContext(Dispatchers.IO) {
        try {
            val results = Geocoder(context).getFromLocationName(address, 1)
            if (!results.isNullOrEmpty()) {
                Pair(results[0].latitude, results[0].longitude)
            } else null
        } catch (_: Exception) {
            null
        }
    }
}

package com.vayunmathur.travel.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExposedDropdownMenu
import com.vayunmathur.library.ui.ExposedDropdownMenuAnchorType
import com.vayunmathur.library.ui.ExposedDropdownMenuBox
import com.vayunmathur.library.ui.ExposedDropdownMenuDefaults
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.travel.R
import com.vayunmathur.travel.network.AirlineDto
import com.vayunmathur.travel.network.IdentityDocumentDto
import com.vayunmathur.travel.network.LoyaltyAccountDto

@Composable
internal fun IdentityDocumentFields(doc: IdentityDocumentDto?, onChange: (IdentityDocumentDto) -> Unit) {
    val current = doc ?: IdentityDocumentDto()
    Text(
        stringResource(R.string.passport),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = current.uniqueIdentifier,
        onValueChange = { onChange(current.copy(uniqueIdentifier = it)) },
        label = { Text(stringResource(R.string.passport_number)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = current.issuingCountryCode,
        onValueChange = { onChange(current.copy(issuingCountryCode = it.uppercase().take(2))) },
        label = { Text(stringResource(R.string.issuing_country_e_g_gb)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    DateField(
        stringResource(R.string.expiry_date),
        current.expiresOn,
        onDate = { onChange(current.copy(expiresOn = it)) },
        dateFormat = DateString::monthDayYear,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoyaltyFields(
    account: LoyaltyAccountDto?,
    airlines: List<AirlineDto>,
    onChange: (LoyaltyAccountDto?) -> Unit,
) {
    val current = account ?: LoyaltyAccountDto()
    Text(
        stringResource(R.string.frequent_flyer_optional),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AirlineDropdown(
            airlines = airlines,
            selectedIata = current.airlineIataCode,
            modifier = Modifier.weight(1f),
        ) { iata ->
            val next = current.copy(airlineIataCode = iata)
            onChange(if (next.airlineIataCode.isBlank() && next.accountNumber.isBlank()) null else next)
        }
        OutlinedTextField(
            value = current.accountNumber,
            onValueChange = {
                val next = current.copy(accountNumber = it)
                onChange(if (next.airlineIataCode.isBlank() && next.accountNumber.isBlank()) null else next)
            },
            label = { Text(stringResource(R.string.number)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Searchable airline picker: the user types to filter by name or IATA code and
 * taps a result. Falls back to a plain 2-letter code field until the reference
 * list has loaded (or if it failed to load).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirlineDropdown(
    airlines: List<AirlineDto>,
    selectedIata: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    if (airlines.isEmpty()) {
        OutlinedTextField(
            value = selectedIata,
            onValueChange = { onSelect(it.uppercase().take(2)) },
            label = { Text(stringResource(R.string.airline)) },
            singleLine = true,
            modifier = modifier,
        )
        return
    }
    val selectedName = airlines.firstOrNull { it.iataCode == selectedIata }?.name ?: selectedIata
    var expanded by remember { mutableStateOf(false) }
    var query by remember(selectedName) { mutableStateOf(selectedName) }
    val filtered = remember(query, airlines) {
        if (query.isBlank()) {
            airlines
        } else {
            airlines.filter {
                it.name.contains(query, ignoreCase = true) || it.iataCode.contains(query, ignoreCase = true)
            }
        }
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                expanded = true
                if (it.isBlank()) onSelect("")
            },
            label = { Text(stringResource(R.string.airline)) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
        )
        if (filtered.isNotEmpty()) {
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                filtered.forEach { airline ->
                    DropdownMenuItem(
                        text = { Text("${airline.name} (${airline.iataCode})") },
                        onClick = {
                            onSelect(airline.iataCode)
                            query = airline.name
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun InfantLinkField(
    selected: String?,
    infantOptions: List<Pair<String, String>>,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.accompanying_infant),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = selected.isNullOrBlank(), onClick = { onSelect(null) }, label = { Text(stringResource(R.string.none)) })
            infantOptions.forEach { (id, label) ->
                FilterChip(selected = selected == id, onClick = { onSelect(id) }, label = { Text(label) })
            }
        }
    }
}

@Composable
internal fun ChipRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(text) },
                )
            }
        }
    }
}

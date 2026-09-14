package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.staySuggestions
import androidx.compose.ui.res.stringResource

/**
 * Hotel search form (used from Home when the "Stays" product is selected):
 * location autocomplete, check-in/out dates, rooms and guests.
 */
@Composable
fun StaySearchForm(
    viewModel: TravelViewModel,
    modifier: Modifier = Modifier,
    onSearch: (place: String, checkIn: String, checkOut: String, rooms: Int, adults: Int, latitude: Double?, longitude: Double?) -> Unit,
) {
    var place by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    var checkIn by remember { mutableStateOf("") }
    var checkOut by remember { mutableStateOf("") }
    var rooms by remember { mutableIntStateOf(1) }
    var adults by remember { mutableIntStateOf(2) }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StaySuggestField(
            label = stringResource(com.vayunmathur.travel.R.string.destination_or_hotel),
            viewModel = viewModel,
            onSelect = { name, lat, lng ->
                place = name
                latitude = lat
                longitude = lng
            },
        )
        DateField(stringResource(R.string.check_in), checkIn, onDate = { checkIn = it })
        DateField(stringResource(R.string.check_out), checkOut, onDate = { checkOut = it })
        CountStepper(stringResource(R.string.rooms), rooms, onCount = { rooms = it }, min = 1, max = 5)
        CountStepper(stringResource(R.string.guests), adults, onCount = { adults = it }, min = 1, max = 9)
        Button(
            onClick = { onSearch(place, checkIn, checkOut, rooms, adults, latitude, longitude) },
            enabled = place.isNotBlank() && checkIn.isNotBlank() && checkOut.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.search_hotels)) }
    }
}

/**
 * Stays location autocomplete: type a city or hotel name and pick a suggestion,
 * which carries coordinates so the search can run without an airport code.
 */
@Composable
private fun StaySuggestField(
    label: String,
    viewModel: TravelViewModel,
    onSelect: (name: String, latitude: Double?, longitude: Double?) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<com.vayunmathur.travel.network.StaySuggestionDto>>(emptyList()) }
    var justSelected by remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        if (justSelected) {
            justSelected = false
            return@LaunchedEffect
        }
        if (text.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(250)
        suggestions = viewModel.staySuggestions(text)
    }

    Column {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                // Free-text fallback: pass the typed text with no coordinates so
                // the server resolves it as an IATA code.
                onSelect(it.trim(), null, null)
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggestions.isNotEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column {
                    suggestions.take(6).forEach { s ->
                        com.vayunmathur.library.ui.ListItem(
                            modifier = Modifier.clickable {
                                onSelect(s.name, s.latitude, s.longitude)
                                text = s.name
                                justSelected = true
                                suggestions = emptyList()
                            },
                        ) { Text(s.name) }
                    }
                }
            }
        }
    }
}

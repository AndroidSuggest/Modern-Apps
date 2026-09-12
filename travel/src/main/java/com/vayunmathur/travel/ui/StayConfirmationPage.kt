package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.util.TravelViewModel
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StayConfirmationPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.StayConfirmation,
) {
    val trips by viewModel.bookedTrips.collectAsStateWithLifecycle()
    val trip = trips.find { it.orderId == route.bookingId }

    DetailScaffold(title = stringResource(R.string.booking_confirmed), scrollBehavior = appBarScrollBehavior()) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconCheckCircle(
                modifier = Modifier.size(64.dp).padding(top = 8.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.you_re_booked), style = MaterialTheme.typography.headlineSmall)
            if (trip != null) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StayDetailRow("Reference", trip.bookingReference, emphasize = true)
                        HorizontalDivider()
                        StayDetailRow("Hotel", trip.route)
                        StayDetailRow("Check-in", TravelViewModel.prettyDate(trip.departDate))
                        StayDetailRow("Amount", formatMoney(trip.amount, trip.currency))
                    }
                }
            }
            Button(onClick = { backStack.reset(Route.Home) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(UiR.string.done)) }
        }
    }
}

@Composable
private fun StayDetailRow(label: String, value: String, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

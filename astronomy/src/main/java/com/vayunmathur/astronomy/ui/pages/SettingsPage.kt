package com.vayunmathur.astronomy.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.ui.AstronomyViewModel
import com.vayunmathur.astronomy.ui.ConstellationMode
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource

@Composable
fun SettingsPage(backStack: NavBackStack<Route>, viewModel: AstronomyViewModel) {
    val showConst by viewModel.constellationMode.collectAsState()
    val showGrid by viewModel.showGrid.collectAsState()
    val showDeep by viewModel.showDeepSky.collectAsState()
    val showPlanets by viewModel.showPlanets.collectAsState()
    val showBelow by viewModel.showBelowHorizon.collectAsState()
    val magLimit by viewModel.magLimit.collectAsState()
    val nightMode by viewModel.nightMode.collectAsState()
    val fov by viewModel.fovDeg.collectAsState()
    val observer by viewModel.observer.collectAsState()

    var latText by remember(observer) { mutableStateOf(observer?.latDeg?.toString() ?: "") }
    var lonText by remember(observer) { mutableStateOf(observer?.lonDeg?.toString() ?: "") }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { IconNavigation(backStack) })
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.display), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.constellations))
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) {
                        Text(
                            when (showConst) {
                                ConstellationMode.OFF -> stringResource(R.string.off)
                                ConstellationMode.LINES -> stringResource(R.string.lines_only)
                                ConstellationMode.LINES_AND_ART -> stringResource(R.string.lines_art)
                            }
                        )
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.off)) }, onClick = { viewModel.setShowConstellations(ConstellationMode.OFF); expanded = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lines_only)) }, onClick = { viewModel.setShowConstellations(ConstellationMode.LINES); expanded = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lines_art)) }, onClick = { viewModel.setShowConstellations(ConstellationMode.LINES_AND_ART); expanded = false })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.coordinate_grid_whole_sphere)); Switch(checked = showGrid, onCheckedChange = { viewModel.setShowGrid(it) }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.show_deep_sky)); Switch(checked = showDeep, onCheckedChange = { viewModel.setShowDeepSky(it) }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.planets_sun_moon)); Switch(checked = showPlanets, onCheckedChange = { viewModel.setShowPlanets(it) }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.show_below_horizon_all_sky)); Switch(checked = showBelow, onCheckedChange = { viewModel.setShowBelowHorizon(it) }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.night_mode)); Switch(checked = nightMode, onCheckedChange = { viewModel.setNightMode(it) }) }

            HorizontalDivider()

            Text(stringResource(R.string.magnitude_limit, magLimit.format(1)), style = MaterialTheme.typography.bodyMedium)
            Slider(value = magLimit, onValueChange = { viewModel.setMagLimit(it) }, valueRange = 1f..7f)

            Text(stringResource(R.string.fov, fov.toInt()), style = MaterialTheme.typography.bodyMedium)
            Slider(value = fov, onValueChange = { viewModel.setFov(it) }, valueRange = 10f..120f)

            HorizontalDivider()
            Text(stringResource(R.string.location), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = latText, onValueChange = { latText = it }, label = { Text(stringResource(R.string.latitude_deg)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = lonText, onValueChange = { lonText = it }, label = { Text(stringResource(R.string.longitude_deg)) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val lat = latText.toDoubleOrNull(); val lon = lonText.toDoubleOrNull()
                if (lat != null && lon != null) viewModel.setManualLocation(lat, lon)
            }) { Text(stringResource(R.string.save_location)) }
            Button(onClick = { viewModel.refreshLocation() }) { Text(stringResource(R.string.use_current_location)) }

            HorizontalDivider()
            Text(stringResource(R.string.notes_true_north_correction_via_geomagne), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.catalog_stars_constellations_dso, viewModel.getCatalog().stars.size, viewModel.getCatalog().constellations.size, viewModel.getCatalog().deepSky.size), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun Float.format(d: Int): String = "%.${d}f".format(this)

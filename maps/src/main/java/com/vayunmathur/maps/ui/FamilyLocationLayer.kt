package com.vayunmathur.maps.ui

import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.string
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Circle (dot) layer id — the reliable large tap target hit-tested in
 *  MapSurface.onMapClick so a tapped family pin selects that person. */
const val FAMILY_LOCATION_LAYER_ID = "family-location-pins"

/**
 * Convert a hit-tested family pin back into a selectable place, reusing
 * [SpecificFeature.GenericPlace] (name + position) so the existing place sheet
 * renders and its Directions button routes to the person — no new detail path.
 */
fun Feature1.toSelectedFamilyMember(): SpecificFeature? {
    val props = properties ?: return null
    val name = props.string("name")?.ifBlank { null } ?: return null
    val lat = props["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
    val lng = props["lng"]?.jsonPrimitive?.doubleOrNull ?: return null
    return SpecificFeature.GenericPlace(name, null, null, null, GeoPoint(lng, lat))
}

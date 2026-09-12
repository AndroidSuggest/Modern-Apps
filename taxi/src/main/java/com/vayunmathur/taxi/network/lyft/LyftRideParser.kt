package com.vayunmathur.taxi.network.lyft

import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.DriverInfo
import com.vayunmathur.taxi.data.DriverLocation
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.RideStatus
import com.vayunmathur.taxi.data.RideStopInfo
import com.vayunmathur.taxi.data.VehicleInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ----------------------------------------------------------------------------------------
// Active-ride / driver-location parsing (PassengerRide, ReadDriverLocationResponse).
// Field tags mirror the DTOs in api-notes §4; both JSON and binary protobuf are accepted,
// matching the codec negotiation the rest of this class uses.
//
//   PassengerRide: ride_id(1), status(2), driver(6), vehicle(8), stops(9 repeated),
//                  location(11 = live DriverLocation)
//   DriverLocation: lat(1 double), lng(2 double), bearing(3 double)
//   Driver:  first_name(5), last_name(6), image_url(7), phone_number(8), rating(9)
//   RideVehicle: make(1), model(2), license_plate(3), image_url(4), color(6)
//   RideStop: location(2 PlaceDTO), kind(3), completed(4), eta_seconds(6), location_v2(7)
//   PlaceDTO: lat(1 double), lng(2 double), address(3), place_name(5)
// ----------------------------------------------------------------------------------------

internal class LyftRideParser(private val json: Json) {

    fun parseActiveRide(resp: RawResponse): ActiveRide? {
        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
        val isProto = contentType.contains("protobuf") || contentType.contains("octet-stream")
        fun proto() = runCatching { toActiveRideProto(resp.bytes) }.getOrNull()
        fun asJson() = runCatching { toActiveRideJson(resp.text) }.getOrNull()
        val primary = if (isProto) proto() else asJson()
        if (primary != null && primary.hasContent) return primary
        return (if (isProto) asJson() else proto())?.takeIf { it.hasContent } ?: primary
    }

    fun toActiveRideJson(raw: String): ActiveRide? {
        val ride = firstRideObject(raw) ?: return null
        val statusRaw = ride.lyftStr("status") ?: ride.lyftStr("ride_status")
        val driver = (ride["driver"] as? JsonObject)?.let { d ->
            DriverInfo(
                firstName = d.lyftStr("first_name") ?: d.lyftStr("firstName"),
                lastName = d.lyftStr("last_name") ?: d.lyftStr("lastName"),
                imageUrl = d.lyftStr("image_url") ?: d.lyftStr("imageUrl"),
                phoneNumber = d.lyftStr("phone_number") ?: d.lyftStr("phoneNumber"),
                rating = d.lyftDbl("rating"),
            )
        }
        val vehicle = (ride["vehicle"] as? JsonObject)?.let { v ->
            VehicleInfo(
                make = v.lyftStr("make"),
                model = v.lyftStr("model"),
                color = v.lyftStr("color"),
                licensePlate = v.lyftStr("license_plate") ?: v.lyftStr("licensePlate"),
                imageUrl = v.lyftStr("image_url") ?: v.lyftStr("imageUrl"),
            )
        }
        val driverLocation = (ride["location"] as? JsonObject)?.let(::toDriverLocationJson)
        val stops = ride["stops"]?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.let(::toStopJson) }
            ?: emptyList()
        return ActiveRide(
            rideId = ride.lyftStr("ride_id") ?: ride.lyftStr("id"),
            status = RideStatus.fromWire(statusRaw),
            statusRaw = statusRaw,
            driver = driver,
            vehicle = vehicle,
            driverLocation = driverLocation,
            stops = stops,
            raw = raw.take(2000),
        )
    }

    fun toActiveRideProto(bytes: ByteArray): ActiveRide {
        val m = ProtoMessage(bytes, 0, bytes.size)
        val statusRaw = m.string(2)
        val driver = m.message(6)?.let { d ->
            DriverInfo(
                firstName = d.string(5),
                lastName = d.string(6),
                imageUrl = d.string(7),
                phoneNumber = d.string(8),
                rating = d.double(9) ?: d.wrappedDouble(9),
            )
        }
        val vehicle = m.message(8)?.let { v ->
            VehicleInfo(
                make = v.string(1),
                model = v.string(2),
                color = v.string(6),
                licensePlate = v.string(3),
                imageUrl = v.string(4),
            )
        }
        val driverLocation = m.message(11)?.let(::toDriverLocationProto)
        val stops = m.messages(9).map(::toStopProto)
        return ActiveRide(
            rideId = m.string(1),
            status = RideStatus.fromWire(statusRaw),
            statusRaw = statusRaw,
            driver = driver,
            vehicle = vehicle,
            driverLocation = driverLocation,
            stops = stops,
            raw = "<protobuf ${bytes.size} bytes>",
        )
    }

    fun parseDriverLocation(resp: RawResponse): DriverLocation? {
        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
        val isProto = contentType.contains("protobuf") || contentType.contains("octet-stream")
        fun proto(): DriverLocation? {
            val root = ProtoMessage(resp.bytes, 0, resp.bytes.size)
            // ReadDriverLocationResponse.location = 1; tolerate a bare DriverLocation too.
            return root.message(1)?.let(::toDriverLocationProto) ?: toDriverLocationProto(root)
        }
        fun asJson(): DriverLocation? {
            val root = runCatching { json.parseToJsonElement(resp.text) as? JsonObject }.getOrNull()
                ?: return null
            val loc = (root["location"] as? JsonObject) ?: root
            return toDriverLocationJson(loc)
        }
        return if (isProto) (proto() ?: asJson()) else (asJson() ?: proto())
    }

    fun toDriverLocationJson(o: JsonObject): DriverLocation? {
        val lat = o.lyftDbl("lat") ?: o.lyftDbl("latitude") ?: return null
        val lng = o.lyftDbl("lng") ?: o.lyftDbl("longitude") ?: return null
        return DriverLocation(lat, lng, o.lyftDbl("bearing"))
    }

    fun toDriverLocationProto(m: ProtoMessage): DriverLocation? {
        val lat = m.double(1) ?: return null
        val lng = m.double(2) ?: return null
        return DriverLocation(lat, lng, m.double(3))
    }

    fun toStopJson(o: JsonObject): RideStopInfo {
        val place = (o["location"] as? JsonObject) ?: (o["location_v2"] as? JsonObject)
        val latLng = place?.let { p ->
            val lat = p.lyftDbl("lat") ?: p.lyftDbl("latitude")
            val lng = p.lyftDbl("lng") ?: p.lyftDbl("longitude")
            if (lat != null && lng != null) LatLng(lat, lng) else null
        }
        return RideStopInfo(
            location = latLng,
            name = place?.lyftStr("place_name") ?: place?.lyftStr("placeName") ?: place?.lyftStr("address"),
            kind = o.lyftStr("kind"),
            etaSeconds = o["eta_seconds"]?.jsonPrimitive?.intOrNull
                ?: o["etaSeconds"]?.jsonPrimitive?.intOrNull,
            completed = o["completed"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    fun toStopProto(m: ProtoMessage): RideStopInfo {
        val place = m.message(2) ?: m.message(7)
        val latLng = place?.let {
            val lat = it.double(1)
            val lng = it.double(2)
            if (lat != null && lng != null) LatLng(lat, lng) else null
        }
        return RideStopInfo(
            location = latLng,
            name = place?.string(5) ?: place?.string(3),
            kind = m.string(3),
            etaSeconds = m.varint(6)?.toInt(),
            completed = m.varint(4)?.let { it != 0L } ?: false,
        )
    }

    /**
     * Best-effort pick of the ride/trip object out of a create or active-ride response. Exact
     * shapes are unverified (api-notes §4/§6); we look for the common wrappers and fall back to
     * the root so id/status are surfaced when present.
     */
    fun firstRideObject(raw: String): JsonObject? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return null
        return root["ride"]?.jsonObject
            ?: root["trip"]?.jsonObject
            ?: root["active_ride"]?.jsonObject
            ?: root
    }
}

internal fun JsonObject.lyftStr(key: String) = this[key]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.lyftLong(key: String) = this[key]?.jsonPrimitive?.longOrNull

internal fun JsonObject.lyftDbl(key: String) = this[key]?.jsonPrimitive?.doubleOrNull

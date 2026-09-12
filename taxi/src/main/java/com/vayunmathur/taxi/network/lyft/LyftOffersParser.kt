package com.vayunmathur.taxi.network.lyft

import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.RideQuote
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses `ReadOffersV2Response` (`/v2/offerings`) in both codecs the server negotiates.
 *
 * Request/response shapes recovered from the production APK (`me.lyft.android` v2026.29.3) with
 * jadx — see `lyft-re/api-notes.md` §3. On success the server negotiates a body: it may reply
 * with proto-JSON **or** binary protobuf depending on `Accept`.
 */
internal class LyftOffersParser(private val json: Json) {

    // ----------------------------------------------------------------------------------------
    // JSON response
    // ----------------------------------------------------------------------------------------

    /**
     * Parses proto-JSON `ReadOffersV2Response`: `offers.offers_list[]`, each an `OfferDTO` with a
     * `cost_estimate` (price), `ride_type_details` (name + seats) and `ride_travel_details`
     * (pickup ETA). int64 proto-JSON fields may arrive as strings, which the accessors handle.
     * The offers wrapper also carries `purchase_session_id`/`offers_response_id`, reused when
     * booking.
     */
    fun parseJson(raw: String): ParsedOffers {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return ParsedOffers.EMPTY
        val offers = root["offers"]?.jsonObject ?: return ParsedOffers.EMPTY
        val quotes = offers["offers_list"]?.jsonArray
            ?.mapNotNull { element -> (element as? JsonObject)?.let(::toQuoteJson) }
            ?: emptyList()
        return ParsedOffers(
            quotes = quotes,
            purchaseSessionId = offers.lyftStr("purchase_session_id"),
            offersResponseId = offers.lyftStr("offers_response_id"),
        )
    }

    fun toQuoteJson(offer: JsonObject): RideQuote? {
        val cost = offer["cost_estimate"]?.jsonObject
        val rideType = offer["ride_type_details"]?.jsonObject
        val display = rideType?.get("display_properties")?.jsonObject

        val name = display?.lyftStr("name")
            ?: cost?.lyftStr("ride_type")
            ?: offer.lyftStr("offer_product_id")
            ?: return null

        val min = cost?.lyftLong("estimated_cost_cents_min")
        val max = cost?.lyftLong("estimated_cost_cents_max")
        val upfront = cost?.lyftLong("upfront_cost_cents")
        // CostEstimate.applicable_coupons[0] carries the discount the rider actually receives.
        val coupon = cost?.get("applicable_coupons")?.jsonArray?.firstOrNull() as? JsonObject
        val fare = farePrice(
            min, max, upfront,
            discountMin = coupon?.lyftLong("discount_amount_min"),
            discountMax = coupon?.lyftLong("discount_amount_max"),
        ) ?: return null

        val pickupEtaMs = offer["ride_travel_details"]?.jsonObject
            ?.get("pickup_estimate")?.jsonObject
            ?.get("duration_range")?.jsonObject
            ?.lyftLong("duration_ms")

        return RideQuote(
            provider = Provider.LYFT,
            productId = offer.lyftStr("offer_product_id") ?: name,
            displayName = name,
            fareLowMinor = fare.low,
            fareHighMinor = fare.high,
            originalFareLowMinor = fare.originalLow,
            originalFareHighMinor = fare.originalHigh,
            currency = cost?.lyftStr("currency") ?: "USD",
            pickupEtaMinutes = pickupEtaMs?.let { (it / 60_000).toInt() },
            tripDurationMinutes = cost?.lyftLong("estimated_duration_seconds")?.let { (it / 60).toInt() },
            surgeMultiplier = cost?.get("primetime_multiplier")?.jsonPrimitive?.doubleOrNull,
            capacity = rideType?.get("seats")?.jsonPrimitive?.intOrNull,
            offerId = offer.lyftStr("id"),
            // StringValue wrappers serialize to the bare string in proto3-JSON.
            offerToken = offer.lyftStr("offer_token"),
            costToken = cost?.lyftStr("cost_token"),
            rideType = cost?.lyftStr("ride_type"),
            // `cost_token_expiry_time` is an epoch **seconds** value (measured live: ~120s
            // lifetime, shared across all fares); convert to ms so callers can compare to now.
            costTokenExpiryMs = cost?.lyftLong("cost_token_expiry_time")?.let { it * 1000 },
        )
    }

    // ----------------------------------------------------------------------------------------
    // Protobuf response (binary). Field tags mirror the DTOs decompiled from the APK.
    // ----------------------------------------------------------------------------------------

    /**
     * Parses binary `ReadOffersV2Response`. Message nesting (field numbers):
     *  - ReadOffersV2Response.offers = 1  → OffersV2DTO
     *  - OffersV2DTO.offers_list      = 3  (repeated) → OfferDTO
     *  - OfferDTO: offer_product_id=2, cost_estimate=4, ride_type_details=5, ride_travel_details=9
     *  - CostEstimate: cents_max=5, cents_min=6, upfront=7, currency=8, primetime_multiplier=12,
     *    estimated_duration_seconds=22  (the *_cents / currency / duration are wrapper messages)
     *  - RideMode: seats=7, display_properties=10 → name=3
     *  - RideTravelDetails.pickup_estimate=1 → duration_range=2 → duration_ms=1 (wrapper)
     */
    fun parseProto(bytes: ByteArray): ParsedOffers {
        val root = ProtoMessage(bytes, 0, bytes.size)
        val offers = root.message(1) ?: return ParsedOffers.EMPTY
        return ParsedOffers(
            quotes = offers.messages(3).mapNotNull { toQuoteProto(it) },
            purchaseSessionId = offers.string(1),
            offersResponseId = offers.string(2),
        )
    }

    fun toQuoteProto(offer: ProtoMessage): RideQuote? {
        val cost = offer.message(4)
        val rideType = offer.message(5)
        val display = rideType?.message(10)

        val name = display?.string(3)
            ?: cost?.string(4) // ride_type
            ?: offer.string(2) // offer_product_id
            ?: return null

        val min = cost?.wrappedLong(6)
        val max = cost?.wrappedLong(5)
        val upfront = cost?.wrappedLong(7)
        // applicable_coupons(18, repeated ApplicableCoupon): discount_amount_min(10),
        // discount_amount_max(11) are plain int64. First coupon is the one the client applies.
        val coupon = cost?.messages(18)?.firstOrNull()
        val fare = farePrice(
            min, max, upfront,
            discountMin = coupon?.varint(10),
            discountMax = coupon?.varint(11),
        ) ?: return null

        val pickupEtaMs = offer.message(9)?.message(1)?.message(2)?.wrappedLong(1)

        return RideQuote(
            provider = Provider.LYFT,
            productId = offer.string(2) ?: name,
            displayName = name,
            fareLowMinor = fare.low,
            fareHighMinor = fare.high,
            originalFareLowMinor = fare.originalLow,
            originalFareHighMinor = fare.originalHigh,
            currency = cost?.wrappedString(8) ?: "USD",
            pickupEtaMinutes = pickupEtaMs?.let { (it / 60_000).toInt() },
            tripDurationMinutes = cost?.wrappedLong(22)?.let { (it / 60).toInt() },
            surgeMultiplier = cost?.double(12),
            capacity = rideType?.varint(7)?.toInt(),
            // OfferDTO.id=1, offer_token=3 (StringValue). CostEstimate.cost_token=3,
            // ride_type=4, cost_token_expiry_time=20 (best-effort; type unverified — dry-run
            // surfaces the built request for validation).
            offerId = offer.string(1),
            offerToken = offer.wrappedString(3),
            costToken = cost?.string(3),
            rideType = cost?.string(4),
            // Epoch seconds (see toQuoteJson) -> ms.
            costTokenExpiryMs = (cost?.wrappedLong(20) ?: cost?.varint(20))?.let { it * 1000 },
        )
    }

    private fun priceRange(min: Long?, max: Long?, upfront: Long?): Pair<Long, Long>? = when {
        min != null && max != null -> min to max
        upfront != null -> upfront to upfront
        min != null -> min to min
        max != null -> max to max
        else -> null
    }

    /**
     * The pre-discount base range plus the actual price after the first applicable coupon. Lyft
     * carries no post-promo scalar; the app computes it as estimate − coupon discount (clamped at
     * 0), keeping `upfront`/estimate as the struck-through original. Mirrors that (see api-notes §3).
     */
    data class FarePrice(
        val low: Long,
        val high: Long,
        val originalLow: Long?,
        val originalHigh: Long?,
    )

    fun farePrice(
        min: Long?,
        max: Long?,
        upfront: Long?,
        discountMin: Long?,
        discountMax: Long?,
    ): FarePrice? {
        val (baseLow, baseHigh) = priceRange(min, max, upfront) ?: return null
        val dLow = (discountMin ?: 0).coerceAtLeast(0)
        val dHigh = (discountMax ?: 0).coerceAtLeast(0)
        if (dLow == 0L && dHigh == 0L) return FarePrice(baseLow, baseHigh, null, null)
        return FarePrice(
            low = (baseLow - dLow).coerceAtLeast(0),
            high = (baseHigh - dHigh).coerceAtLeast(0),
            originalLow = baseLow,
            originalHigh = baseHigh,
        )
    }
}

/**
 * The pieces of a `ReadOffersV2Response` the app keeps: the per-product [quotes] plus the
 * offers-wrapper identifiers reused when booking.
 */
internal data class ParsedOffers(
    val quotes: List<RideQuote>,
    val purchaseSessionId: String?,
    val offersResponseId: String?,
) {
    companion object {
        val EMPTY = ParsedOffers(emptyList(), null, null)
    }
}

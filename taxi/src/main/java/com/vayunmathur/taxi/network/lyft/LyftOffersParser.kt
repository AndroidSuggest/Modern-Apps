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
            pickupEtaMinutes = pickupEtaMs?.let { (it / MILLIS_PER_MINUTE).toInt() },
            tripDurationMinutes = cost?.lyftLong("estimated_duration_seconds")?.let {
                (it / SECONDS_PER_MINUTE).toInt()
            },
            surgeMultiplier = cost?.get("primetime_multiplier")?.jsonPrimitive?.doubleOrNull,
            capacity = rideType?.get("seats")?.jsonPrimitive?.intOrNull,
            offerId = offer.lyftStr("id"),
            // StringValue wrappers serialize to the bare string in proto3-JSON.
            offerToken = offer.lyftStr("offer_token"),
            costToken = cost?.lyftStr("cost_token"),
            rideType = cost?.lyftStr("ride_type"),
            // `cost_token_expiry_time` is an epoch **seconds** value (measured live: ~120s
            // lifetime, shared across all fares); convert to ms so callers can compare to now.
            costTokenExpiryMs = cost?.lyftLong("cost_token_expiry_time")?.let { it * MILLIS_PER_SECOND },
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
        val offers = root.message(OFFERS_FIELD) ?: return ParsedOffers.EMPTY
        return ParsedOffers(
            quotes = offers.messages(OFFERS_LIST_FIELD).mapNotNull { toQuoteProto(it) },
            purchaseSessionId = offers.string(PURCHASE_SESSION_ID_FIELD),
            offersResponseId = offers.string(OFFERS_RESPONSE_ID_FIELD),
        )
    }

    fun toQuoteProto(offer: ProtoMessage): RideQuote? {
        val cost = offer.message(OfferFields.COST_ESTIMATE)
        val rideType = offer.message(OfferFields.RIDE_TYPE_DETAILS)
        val display = rideType?.message(RideModeFields.DISPLAY_PROPERTIES)

        val name = display?.string(DisplayFields.NAME)
            ?: cost?.string(CostFields.RIDE_TYPE) // ride_type
            ?: offer.string(OfferFields.OFFER_PRODUCT_ID) // offer_product_id
            ?: return null

        val min = cost?.wrappedLong(CostFields.CENTS_MIN)
        val max = cost?.wrappedLong(CostFields.CENTS_MAX)
        val upfront = cost?.wrappedLong(CostFields.UPFRONT)
        // applicable_coupons: discount_amount_min/max are plain int64. First coupon is the one
        // the client applies.
        val coupon = cost?.messages(CostFields.APPLICABLE_COUPONS)?.firstOrNull()
        val fare = farePrice(
            min, max, upfront,
            discountMin = coupon?.varint(CouponFields.DISCOUNT_MIN),
            discountMax = coupon?.varint(CouponFields.DISCOUNT_MAX),
        ) ?: return null

        val pickupEtaMs = offer.message(OfferFields.RIDE_TRAVEL_DETAILS)
            ?.message(TravelFields.PICKUP_ESTIMATE)
            ?.message(EstimateFields.DURATION_RANGE)
            ?.wrappedLong(WrapperFields.VALUE)

        return RideQuote(
            provider = Provider.LYFT,
            productId = offer.string(OfferFields.OFFER_PRODUCT_ID) ?: name,
            displayName = name,
            fareLowMinor = fare.low,
            fareHighMinor = fare.high,
            originalFareLowMinor = fare.originalLow,
            originalFareHighMinor = fare.originalHigh,
            currency = cost?.wrappedString(CostFields.CURRENCY) ?: "USD",
            pickupEtaMinutes = pickupEtaMs?.let { (it / MILLIS_PER_MINUTE).toInt() },
            tripDurationMinutes = cost?.wrappedLong(CostFields.DURATION_SECONDS)?.let {
                (it / SECONDS_PER_MINUTE).toInt()
            },
            surgeMultiplier = cost?.double(CostFields.PRIMETIME_MULTIPLIER),
            capacity = rideType?.varint(RideModeFields.SEATS)?.toInt(),
            // OfferDTO.id, offer_token (StringValue). CostEstimate.cost_token,
            // ride_type, cost_token_expiry_time (best-effort; type unverified — dry-run
            // surfaces the built request for validation).
            offerId = offer.string(OfferFields.ID),
            offerToken = offer.wrappedString(OfferFields.OFFER_TOKEN),
            costToken = cost?.string(CostFields.COST_TOKEN),
            rideType = cost?.string(CostFields.RIDE_TYPE),
            // Epoch seconds (see toQuoteJson) -> ms.
            costTokenExpiryMs = (
                cost?.wrappedLong(CostFields.COST_TOKEN_EXPIRY)
                    ?: cost?.varint(CostFields.COST_TOKEN_EXPIRY)
                )?.let { it * MILLIS_PER_SECOND },
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

    private companion object {
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val SECONDS_PER_MINUTE = 60L
        private const val MILLIS_PER_SECOND = 1000L

        // ReadOffersV2Response / OffersV2DTO field tags.
        private const val OFFERS_FIELD = 1
        private const val PURCHASE_SESSION_ID_FIELD = 1
        private const val OFFERS_RESPONSE_ID_FIELD = 2
        private const val OFFERS_LIST_FIELD = 3
    }

    /** OfferDTO field tags. */
    private object OfferFields {
        const val ID = 1
        const val OFFER_PRODUCT_ID = 2
        const val OFFER_TOKEN = 3
        const val COST_ESTIMATE = 4
        const val RIDE_TYPE_DETAILS = 5
        const val RIDE_TRAVEL_DETAILS = 9
    }

    /** CostEstimate field tags. */
    private object CostFields {
        const val COST_TOKEN = 3
        const val RIDE_TYPE = 4
        const val CENTS_MAX = 5
        const val CENTS_MIN = 6
        const val UPFRONT = 7
        const val CURRENCY = 8
        const val PRIMETIME_MULTIPLIER = 12
        const val APPLICABLE_COUPONS = 18
        const val COST_TOKEN_EXPIRY = 20
        const val DURATION_SECONDS = 22
    }

    /** ApplicableCoupon field tags. */
    private object CouponFields {
        const val DISCOUNT_MIN = 10
        const val DISCOUNT_MAX = 11
    }

    /** RideMode field tags. */
    private object RideModeFields {
        const val SEATS = 7
        const val DISPLAY_PROPERTIES = 10
    }

    /** DisplayProperties field tags. */
    private object DisplayFields {
        const val NAME = 3
    }

    /** RideTravelDetails / estimate field tags. */
    private object TravelFields {
        const val PICKUP_ESTIMATE = 1
    }

    private object EstimateFields {
        const val DURATION_RANGE = 2
    }

    /** google.protobuf wrapper field tag: field 1 carries the value. */
    private object WrapperFields {
        const val VALUE = 1
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

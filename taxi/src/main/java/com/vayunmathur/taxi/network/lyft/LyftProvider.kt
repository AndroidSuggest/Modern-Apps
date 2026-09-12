package com.vayunmathur.taxi.network.lyft

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.taxi.data.AddCardResult
import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.BookingResult
import com.vayunmathur.taxi.data.CancelResult
import com.vayunmathur.taxi.data.ChargeAccount
import com.vayunmathur.taxi.data.DriverInfo
import com.vayunmathur.taxi.data.DriverLocation
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.NewCard
import com.vayunmathur.taxi.data.PaymentActionResult
import com.vayunmathur.taxi.data.PaymentMethodsResult
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.data.RideStatus
import com.vayunmathur.taxi.data.RideStatusResult
import com.vayunmathur.taxi.data.RideStopInfo
import com.vayunmathur.taxi.data.VehicleInfo
import com.vayunmathur.taxi.data.lyft.LyftTokenStore
import com.vayunmathur.taxi.platform.deeplink.RideDeepLinks
import com.vayunmathur.taxi.provider.RideProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Lyft fares from `POST /v2/offerings` (`/pb.api.endpoints.v1.offers.Offers/ReadOffersV2`).
 *
 * Request/response shapes recovered from the production APK (`me.lyft.android` v2026.29.3) with
 * jadx — see `lyft-re/api-notes.md` §3. The request is the proto-JSON form of `OffersRequestDTO`.
 * On success the server negotiates a body: it may reply with proto-JSON **or** binary protobuf
 * depending on `Accept`, so we read raw bytes and parse whichever came back (protobuf via the
 * hand-rolled reader below, keyed by the same field tags as the DTOs).
 */
class LyftProvider(private val context: Context) : RideProvider {
    override val provider = Provider.LYFT

    private val tokens = LyftTokenStore(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * TLS factory over the platform (system) CA store, for the external payment processors
     * (Stripe/Braintree). The shared [NetworkClient] is initialised with a reduced trust bundle
     * that only covers Lyft's own hosts, and its `useSystemTrust` flag falls back to that bundle
     * rather than to system trust — so passing an explicit system factory is the only way to
     * reach public processor endpoints without touching the shared network library.
     */
    private val systemTrustFactory: SSLSocketFactory by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?) // null keystore => platform default CAs
        SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
    }

    private val cardTokenizer: LyftCardTokenizer by lazy {
        LyftCardTokenizer(json, systemTrustFactory, ::authJsonHeaders)
    }

    private val rideParser: LyftRideParser by lazy { LyftRideParser(json) }

    private val offersParser: LyftOffersParser by lazy { LyftOffersParser(json) }

    override suspend fun isSignedIn(): Boolean = tokens.isSignedIn()

    override suspend fun quotes(pickup: Place, dropoff: Place): QuoteResult {
        val token = accessToken() ?: return QuoteResult.NotSignedIn
        return readOffers(token, "${LyftAuth.BASE}/v2/offerings", offersBody(pickup, dropoff, null, null))
    }

    /**
     * Re-quotes an existing offer set via `/v2/offerings/update` (`ReadOffersV2Update`), carrying
     * [lastOffersId] (the previous `offers_response_id`) and [purchaseSessionId] so the server
     * refreshes the same session's prices/cost-tokens. Used to replace fares whose cost token is
     * about to expire. Response shape matches `/v2/offerings`.
     */
    suspend fun updateQuotes(
        pickup: Place,
        dropoff: Place,
        lastOffersId: String?,
        purchaseSessionId: String?,
    ): QuoteResult {
        val token = accessToken() ?: return QuoteResult.NotSignedIn
        return readOffers(
            token,
            "${LyftAuth.BASE}/v2/offerings/update",
            offersBody(pickup, dropoff, lastOffersId, purchaseSessionId),
        )
    }

    /**
     * OffersRequestDTO body shared by `/v2/offerings` and `/v2/offerings/update`. See api-notes §3:
     * origin(1)+destination(2) as E6LatLng, request_source=OFFER_SELECTOR,
     * offer_selector_type=CATEGORIZED_VERTICAL, a session id, and an entry context. The update
     * path additionally carries `last_offers_id` + `purchase_session_id` to refresh a session.
     */
    private fun offersBody(
        pickup: Place,
        dropoff: Place,
        lastOffersId: String?,
        purchaseSessionId: String?,
    ): String = buildString {
        append("{")
        append(""""origin":${e6(pickup)},""")
        append(""""destination":${e6(dropoff)},""")
        append(""""request_source":"OFFER_SELECTOR",""")
        append(""""offer_selector_type":"CATEGORIZED_VERTICAL",""")
        append(""""offer_selector_session_id":"${java.util.UUID.randomUUID()}",""")
        lastOffersId?.let { append(""""last_offers_id":"$it",""") }
        purchaseSessionId?.let { append(""""purchase_session_id":"$it",""") }
        append(""""request_entry_context":{"entry_point":{"home":{}}}""")
        append("}")
    }

    private suspend fun readOffers(token: String, url: String, body: String): QuoteResult {
        val resp = NetworkClient.execute(
            url = url,
            method = "POST",
            // Reuse the exact standard header set the app sends (user-agent, user-device,
            // x-design-id, locale, timestamps). Without a valid `user-agent` the server
            // rejects the call outright ("invalid user agent") even with a good token.
            headers = LyftAuth.commonHeaders() + mapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json",
                "Accept" to "application/x-protobuf, application/json",
            ),
            body = body,
        )
        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
        Log.d(TAG, "POST $url -> ${resp.status} ($contentType, ${resp.bytes.size} bytes)")
        if (!resp.isSuccess) {
            // Error bodies come back as JSON regardless of Accept; surface the reason.
            return QuoteResult.Failed("Lyft returned HTTP ${resp.status}: ${resp.text.take(300)}")
        }

        val isProto = contentType.contains("protobuf") || contentType.contains("octet-stream")
        var parsed = runCatching {
            if (isProto) offersParser.parseProto(resp.bytes) else offersParser.parseJson(resp.text)
        }.onFailure { Log.w(TAG, "primary parse failed (proto=$isProto)", it) }.getOrDefault(ParsedOffers.EMPTY)
        // Content-Type can lie or be absent; fall back to the other codec before giving up.
        if (parsed.quotes.isEmpty()) {
            parsed = runCatching {
                if (isProto) offersParser.parseJson(resp.text) else offersParser.parseProto(resp.bytes)
            }.getOrDefault(ParsedOffers.EMPTY)
        }

        return if (parsed.quotes.isEmpty()) {
            QuoteResult.Failed("Lyft 200 ($contentType) but no offers parsed")
        } else {
            QuoteResult.Success(
                quotes = parsed.quotes,
                purchaseSessionId = parsed.purchaseSessionId,
                offersResponseId = parsed.offersResponseId,
            )
        }
    }

    override fun bookingUri(pickup: Place, dropoff: Place, quote: RideQuote?): String =
        RideDeepLinks.webUri(Provider.LYFT, pickup, dropoff, quote)

    // ----------------------------------------------------------------------------------------
    // In-app booking + payment management
    //
    // Endpoints recovered from the APK (`defpackage/l77`, `d77`, `ech0`, response DTO `u77` /
    // `c67`):
    //   GET    /chargeaccounts                → ReadChargeAccounts          (list)
    //   PUT    /charge-accounts-multi-provider → UpdateChargeAccount…      (set default)
    //   DELETE /chargeaccounts/{id}          → DeleteChargeAccount          (remove)
    //   POST   /v1/core_trips/create         → CreateTrip                   (book)
    //   GET    /v1/activeride                → ReadActiveRide               (status)
    //   POST   /v1/rides/{id}/cancel         → CancelRide                   (cancel)
    // Payment methods are held only in memory by callers and never persisted here.
    // ----------------------------------------------------------------------------------------

    override suspend fun paymentMethods(): PaymentMethodsResult {
        val token = accessToken() ?: return PaymentMethodsResult.NotSignedIn
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/chargeaccounts",
            method = "GET",
            headers = authHeaders(token),
        )
        Log.d(TAG, "GET /chargeaccounts -> ${resp.status} (${resp.bytes.size} bytes)")
        if (!resp.isSuccess) {
            return PaymentMethodsResult.Failed(httpError(resp))
        }
        return PaymentMethodsResult.Success(parseChargeAccounts(resp))
    }

    override suspend fun setDefaultPaymentMethod(id: String): PaymentActionResult {
        val token = accessToken() ?: return PaymentActionResult.Failed("Not signed in to Lyft")
        // UpdateChargeAccountMultiProvider (fvf0). The real client (t77.b via ech0, for
        // MakePersonalDefault + TriggerDebtCollection) always sends four fields on this PUT:
        //   default (tag 2, BoolValue)              = true
        //   charge_account_id (tag 4, string)       = id
        //   skip_debt_collection (tag 8, BoolValue) = false  (TriggerDebtCollection)
        //   skip_persisted_challenge (tag 9, ...)   = false
        // Omitting the two skip_* wrappers makes the server reject the update with 422, so we
        // mirror the real client exactly.
        val body = buildJsonObject {
            put("charge_account_id", id)
            put("default", true)
            put("skip_debt_collection", false)
            put("skip_persisted_challenge", false)
        }.toString()
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/charge-accounts-multi-provider",
            method = "PUT",
            headers = authProtoJsonHeaders(
                token,
                "pb.api.endpoints.charge_accounts.UpdateChargeAccountMultipleProviderRequest",
            ),
            body = body,
        )
        Log.d(TAG, "PUT /charge-accounts-multi-provider -> ${resp.status}")
        if (!resp.isSuccess) {
            // 422s here are opaque without the server's reason — log the full exchange so the
            // exact validation error (field name / compliance / challenge) is visible.
            Log.w(TAG, "set-default failed ${resp.status}: req=$body resp=${resp.text}")
            return PaymentActionResult.Failed(httpError(resp))
        }
        // The response is a ChargeAccountsResponse; return the refreshed list when it parses.
        val accounts = runCatching { parseChargeAccounts(resp) }.getOrDefault(emptyList())
        return PaymentActionResult.Success(accounts.ifEmpty { null })
    }

    override suspend fun removePaymentMethod(id: String): PaymentActionResult {
        val token = accessToken() ?: return PaymentActionResult.Failed("Not signed in to Lyft")
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/chargeaccounts/${Uri.encode(id)}",
            method = "DELETE",
            headers = authHeaders(token),
        )
        Log.d(TAG, "DELETE /chargeaccounts/{id} -> ${resp.status}")
        if (!resp.isSuccess) return PaymentActionResult.Failed(httpError(resp))
        // Callers re-fetch the list after a delete.
        return PaymentActionResult.Success(null)
    }

    // ----------------------------------------------------------------------------------------
    // Add a card (Create)
    //
    // Lyft never accepts a raw PAN. The card is tokenized by a payment processor first, and only
    // the resulting token/nonce is sent on. Two hops:
    //   1. POST /v1/tokenization_strategies (PostTokenizationStrategies, req `n1z` / resp `o1z`)
    //      → a list of TokenizationStrategy (`sbe0`), each carrying its processor's client key
    //        under `api_key` (Stripe publishable key `thc0`, Braintree client token `zo3`).
    //      NB: the strategy comes from *this* endpoint, not from `payment_options_response`.
    //   2. Tokenize at the processor (Stripe `POST /v1/tokens`, Braintree GraphQL) → token|nonce.
    //   3. POST /charge-accounts-multi-provider (CreateChargeAccountMultiProvider, req `una`)
    //      with provider_representation + card_meta_data → refreshed ChargeAccountsResponse.
    // Card data lives only for the duration of this call; full PAN/CVV are never logged.
    // ----------------------------------------------------------------------------------------

    override suspend fun addCard(card: NewCard, makeDefault: Boolean): AddCardResult {
        val token = accessToken() ?: return AddCardResult.Failed("Not signed in to Lyft")

        // No retrievable processor config → native add-card is blocked for this session.
        val config = cardTokenizer.fetchConfig(token, card) ?: return AddCardResult.Unsupported

        val tokenized = when (val r = cardTokenizer.tokenize(config, card)) {
            is TokenizeResult.Err -> return AddCardResult.Failed(r.message)
            is TokenizeResult.Ok -> r
        }

        // `una`: default(2, bool), provider_representation(4, repeated `mt00`), card_meta_data(6,
        // `lf6`), skip_debt_collection(8), skip_persisted_challenge(9). The real create (`t77.a`)
        // sets default only when making default, always sends the two skip_* flags, and each Stripe
        // `mt00` carries a `version` (STRIPE_TOKEN / STRIPE_SETUP_INTENT). Wrapper types serialize
        // as bare scalars in proto3-JSON, so this maps 1:1 onto the DTO.
        val body = buildJsonObject {
            if (makeDefault) put("default", true)
            put("skip_debt_collection", false)
            put("skip_persisted_challenge", false)
            putJsonArray("provider_representation") {
                addJsonObject {
                    put("provider", tokenized.provider)
                    tokenized.token?.let { put("token", it) }
                    tokenized.nonce?.let { put("nonce", it) }
                    tokenized.version?.let { put("version", it) }
                }
            }
            putJsonObject("card_meta_data") {
                put("expiration_month", card.expMonth)
                put("expiration_year", card.expYear)
                if (card.postalCode.isNotBlank()) put("postal_code", card.postalCode)
            }
        }.toString()

        val resp = runCatching {
            NetworkClient.execute(
                url = "${LyftAuth.BASE}/charge-accounts-multi-provider",
                method = "POST",
                headers = authProtoJsonHeaders(
                    token,
                    "pb.api.endpoints.charge_accounts.CreateChargeAccountMultipleProviderRequest",
                ),
                body = body,
            )
        }.getOrElse { return AddCardResult.Failed("Create card request failed: ${it.message}") }
        Log.d(TAG, "POST /charge-accounts-multi-provider (create) -> ${resp.status}")
        if (!resp.isSuccess) {
            Log.w(TAG, "add-card failed ${resp.status}: req=$body resp=${resp.text}")
            return AddCardResult.Failed(httpError(resp))
        }
        // Response is a ChargeAccountsResponse; return the refreshed list when it parses.
        val accounts = runCatching { parseChargeAccounts(resp) }.getOrDefault(emptyList())
        return AddCardResult.Success(accounts.ifEmpty { null })
    }

    override suspend fun createRide(
        quote: RideQuote,
        pickup: Place,
        dropoff: Place,
        account: ChargeAccount?,
        purchaseSessionId: String?,
        dryRun: Boolean,
    ): BookingResult {
        val token = accessToken() ?: return BookingResult.Failed("Not signed in to Lyft")
        val offerId = quote.offerId
            ?: return BookingResult.Failed("This fare has no offer id — re-quote and try again")
        val riderId = tokens.userId()

        // CreateTripRequest (defpackage/ura): rider_id(1), offer_id(2), origin(3), destination(4),
        // ride_segment_creation_args(5). Sent as proto-JSON, as the offers call is.
        val request = buildJsonObject {
            riderId?.let { put("rider_id", it) } // uint64 → string in proto-JSON
            put("offer_id", offerId)
            put("origin", locationV2(pickup))
            put("destination", locationV2(dropoff))
            putJsonObject("ride_segment_creation_args") {
                quote.offerToken?.let { put("offer_token", it) }
                quote.rideType?.let { put("ride_type", it) }
                quote.costToken?.let { put("cost_token", it) }
                put("party_size", 1)
                // pickup_mode defaults to "standard" in the official app (RideSegmentCreationArgs
                // tag 9); the resolver expects it set.
                put("pickup_mode", "standard")
                // ChargeAccountDTO exposes only an id; passed as charge_token (tag 12). Exact
                // charge_token vs shared_charge_account_id split is unverified — dry-run surfaces
                // the built body so it can be checked against a real capture.
                account?.let { put("charge_token", it.chargeToken ?: it.id) }
            }
            // NB: purchase_session_id is NOT a field on CreateTripRequest (it belongs to the
            // offers request); server-side dedupe is handled by the request throttler instead.
        }
        val requestJson = request.toString()

        // Master guard: never send while BOOKING_LIVE is false, or when the caller asked for a
        // dry run. The full request is returned so the UI/logcat can verify it — no charge.
        if (!BOOKING_LIVE || dryRun) {
            Log.i(TAG, "DRY-RUN /v1/core_trips/create (not sent): $requestJson")
            return BookingResult.DryRun(requestJson, account)
        }

        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/v1/core_trips/create",
            method = "POST",
            headers = authJsonHeaders(token),
            body = requestJson,
        )
        Log.d(TAG, "POST /v1/core_trips/create -> ${resp.status}")
        if (!resp.isSuccess) return BookingResult.Failed(httpError(resp))
        // CreateTripResponse (vra): trip_details(1 = TripDetails) → trip_id(1). No status here;
        // parse the id (JSON or protobuf) for tracking and show nothing else.
        return BookingResult.Created(rideId = parseCreatedRideId(resp), status = null, raw = "")
    }

    /** Extracts `trip_details.trip_id` from a CreateTripResponse (JSON or binary protobuf). */
    private fun parseCreatedRideId(resp: RawResponse): String? {
        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
        val isProto = contentType.contains("protobuf") || contentType.contains("octet-stream")
        fun proto(): String? = runCatching {
            ProtoMessage(resp.bytes, 0, resp.bytes.size).message(1)?.varint(1)?.toString()
        }.getOrNull()
        fun asJson(): String? = runCatching {
            (json.parseToJsonElement(resp.text) as? JsonObject)
                ?.get("trip_details")?.jsonObject
                ?.let { it.str("trip_id") ?: it.str("id") }
        }.getOrNull()
        return if (isProto) (proto() ?: asJson()) else (asJson() ?: proto())
    }

    override suspend fun activeRide(): RideStatusResult {
        val token = accessToken() ?: return RideStatusResult.Failed("Not signed in to Lyft")
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/v1/activeride",
            method = "GET",
            headers = authHeaders(token),
        )
        Log.d(TAG, "GET /v1/activeride -> ${resp.status} (${resp.bytes.size} bytes)")
        if (resp.status == 404) return RideStatusResult.None
        if (!resp.isSuccess) return RideStatusResult.Failed(httpError(resp))
        val ride = rideParser.parseActiveRide(resp)
        return if (ride == null || !ride.hasContent) RideStatusResult.None else RideStatusResult.Active(ride)
    }

    override suspend fun driverLocation(rideId: String): DriverLocation? {
        val token = accessToken() ?: return null
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/v1/rides/${Uri.encode(rideId)}/driver-location",
            method = "GET",
            headers = authHeaders(token),
        )
        Log.d(TAG, "GET /v1/rides/{id}/driver-location -> ${resp.status}")
        if (!resp.isSuccess) return null
        return rideParser.parseDriverLocation(resp)
    }

    override suspend fun cancelRide(rideId: String): CancelResult {
        val token = accessToken() ?: return CancelResult.Failed("Not signed in to Lyft")
        // A cancel genuinely cancels a live ride (and may incur a fee), so it is sent live
        // regardless of BOOKING_LIVE — that flag only gates ride *creation*.
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/v1/rides/${Uri.encode(rideId)}/cancel",
            method = "POST",
            headers = authJsonHeaders(token),
            body = "{}",
        )
        Log.d(TAG, "POST /v1/rides/{id}/cancel -> ${resp.status}")
        // Surface the server response verbatim either way — a cancel can carry a fee.
        return if (resp.isSuccess) {
            CancelResult.Done(resp.text.take(2000).ifBlank { "Ride cancelled" })
        } else {
            CancelResult.Failed(httpError(resp))
        }
    }

    /**
     * Signs out of Lyft: best-effort revokes the refresh and access tokens server-side, then
     * clears the local session regardless of the server's response.
     */
    suspend fun signOut() {
        tokens.refreshToken()?.let { LyftAuth.revoke(it) }
        tokens.accessToken()?.let { LyftAuth.revoke(it) }
        tokens.clear()
    }

    /** Reads a specific ride by id (`GET /v1/rides/{id}`), parsed like the active ride. */
    suspend fun rideById(rideId: String): RideStatusResult {
        val token = accessToken() ?: return RideStatusResult.Failed("Not signed in to Lyft")
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/v1/rides/${Uri.encode(rideId)}",
            method = "GET",
            headers = authHeaders(token),
        )
        Log.d(TAG, "GET /v1/rides/{id} -> ${resp.status}")
        if (resp.status == 404) return RideStatusResult.None
        if (!resp.isSuccess) return RideStatusResult.Failed(httpError(resp))
        val ride = rideParser.parseActiveRide(resp)
        return if (ride == null || !ride.hasContent) RideStatusResult.None else RideStatusResult.Active(ride)
    }

    // ----------------------------------------------------------------------------------------
    // Best-effort car ride endpoints. Their request/response shapes are unverified in the APK
    // teardown (api-notes §3/§4), so these issue the documented HTTP call and return the raw
    // response body (truncated) for the caller to interpret; null on any non-2xx or when signed
    // out. No dedicated UI consumes them yet.
    // ----------------------------------------------------------------------------------------

    /** `GET /v1/active-offer` — the current unaccepted offer, if any. */
    suspend fun activeOffer(): String? = getRaw("/v1/active-offer")

    /** `GET /v1/rides/{id}/pickupgeofence` — the pickup geofence for a ride. */
    suspend fun pickupGeofence(rideId: String): String? =
        getRaw("/v1/rides/${Uri.encode(rideId)}/pickupgeofence")

    /** `GET /v1/rides/{id}/paymentdetails` — payment breakdown for a ride. */
    suspend fun paymentDetails(rideId: String): String? =
        getRaw("/v1/rides/${Uri.encode(rideId)}/paymentdetails")

    /** `POST /v1/updated-cost-estimate/{id}` — refreshed cost for an in-flight ride. */
    suspend fun updatedCostEstimate(rideId: String): String? =
        postRaw("/v1/updated-cost-estimate/${Uri.encode(rideId)}", "{}")

    /** `POST /v1/scheduledridetimeestimates` — pickup-time estimates for a scheduled ride. */
    suspend fun scheduledRideTimeEstimates(pickup: Place, dropoff: Place): String? =
        postRaw("/v1/scheduledridetimeestimates", offersBody(pickup, dropoff, null, null))

    /** `POST /v1/offerings/overview` — offer overview for a route. */
    suspend fun offeringsOverview(pickup: Place, dropoff: Place): String? =
        postRaw("/v1/offerings/overview", offersBody(pickup, dropoff, null, null))

    /** `POST /v1/rides/{id}/pickup` — move the pickup of an existing ride. Mutates a live ride. */
    suspend fun updatePickup(rideId: String, pickup: Place): String? =
        postRaw("/v1/rides/${Uri.encode(rideId)}/pickup", locationV2(pickup).toString())

    /** `POST /v1/rides/redispatch` — request a new driver for a ride. Mutates a live ride. */
    suspend fun redispatch(rideId: String): String? =
        postRaw("/v1/rides/redispatch", buildJsonObject { put("ride_id", rideId) }.toString())

    private suspend fun getRaw(path: String): String? {
        val token = accessToken() ?: return null
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}$path",
            method = "GET",
            headers = authHeaders(token),
        )
        Log.d(TAG, "GET $path -> ${resp.status}")
        return if (resp.isSuccess) resp.text.take(4000) else null
    }

    private suspend fun postRaw(path: String, body: String): String? {
        val token = accessToken() ?: return null
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}$path",
            method = "POST",
            headers = authJsonHeaders(token),
            body = body,
        )
        Log.d(TAG, "POST $path -> ${resp.status}")
        return if (resp.isSuccess) resp.text.take(4000) else null
    }

    // ----------------------------------------------------------------------------------------
    // Active-ride / driver-location parsing lives in LyftRideParser.
    // ----------------------------------------------------------------------------------------

    private fun authHeaders(token: String): Map<String, String> =
        LyftAuth.commonHeaders() + mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/json, application/x-protobuf",
        )

    private fun authJsonHeaders(token: String): Map<String, String> =
        authHeaders(token) + mapOf("Content-Type" to "application/json")

    /**
     * Lyft's server deserializes request bodies with a reflection-based proto-JSON codec that is
     * told which proto message to map the JSON onto via a `messageType` Content-Type parameter.
     * The APK builds it in `defpackage/tvu.d`:
     *   `application/json;messageType=<proto full name>`   (name from `fkh0.a()`, the DTO's
     *   super-constructor arg, e.g. `pb.api.endpoints.charge_accounts.Update…Request`).
     * Paths that map to a single message (e.g. `/v2/offerings`) tolerate a bare `application/json`,
     * but `/charge-accounts-multi-provider` is shared by Create (POST) and Update (PUT), so without
     * `messageType` the server can't resolve the body and returns 422. We must send it.
     */
    private fun authProtoJsonHeaders(token: String, messageType: String): Map<String, String> =
        authHeaders(token) + mapOf("Content-Type" to "application/json;messageType=$messageType")

    private fun httpError(resp: RawResponse): String =
        "Lyft returned HTTP ${resp.status}: ${resp.text.take(300)}"

    /**
     * A `LocationV2DTO` for `origin`/`destination` on `/v1/core_trips/create`, recovered from the
     * APK (`kvp`, `LocationV2MapperKt.toLocationV2ForApiRequest`). Coordinates live three levels
     * deep as **integer microdegrees** (degrees × 1e6, sint32) at
     * `portable_location_with_features.portable_location.location.{lat,lng}_microdegrees` — a flat
     * `{latitude, longitude}` is not resolvable by the server (it answers 422 "not available for
     * your specified locations"). The display name/address go under
     * `location_metadata.static_metadata.spot`.
     */
    private fun locationV2(place: Place): JsonObject {
        val latMicro = (place.location.latitude * 1_000_000).roundToInt()
        val lngMicro = (place.location.longitude * 1_000_000).roundToInt()
        val basicLocation = buildJsonObject {
            put("lat_microdegrees", latMicro)
            put("lng_microdegrees", lngMicro)
        }
        return buildJsonObject {
            putJsonObject("portable_location_with_features") {
                putJsonObject("portable_location") {
                    put("location", basicLocation)
                }
            }
            putJsonObject("location_metadata") {
                putJsonObject("static_metadata") {
                    putJsonObject("spot") {
                        put("name", place.name)
                        place.address?.let {
                            put("display_address", it)
                            put("routable_address", it)
                        }
                    }
                }
            }
            put("source", "user")
        }
    }

    // ----------------------------------------------------------------------------------------
    // Charge-account parsing (ChargeAccountsResponse `u77` → ChargeAccountDTO `c67`)
    //   c67: id=1 (StringValue), kind=2, default=3 (BoolValue), label=5, lastFour=10
    // ----------------------------------------------------------------------------------------

    private fun parseChargeAccounts(resp: RawResponse): List<ChargeAccount> {
        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
        val isProto = contentType.contains("protobuf") || contentType.contains("octet-stream")
        var accounts = runCatching {
            if (isProto) parseChargeAccountsProto(resp.bytes) else parseChargeAccountsJson(resp.text)
        }.getOrDefault(emptyList())
        if (accounts.isEmpty()) {
            accounts = runCatching {
                if (isProto) parseChargeAccountsJson(resp.text) else parseChargeAccountsProto(resp.bytes)
            }.getOrDefault(emptyList())
        }
        return accounts
    }

    private fun parseChargeAccountsJson(raw: String): List<ChargeAccount> {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return emptyList()
        val arr = root["chargeAccounts"]?.jsonArray
            ?: root["charge_accounts"]?.jsonArray
            ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.let(::toChargeAccountJson) }
    }

    private fun toChargeAccountJson(o: JsonObject): ChargeAccount? {
        val id = o.str("id") ?: return null
        return ChargeAccount(
            id = id,
            chargeToken = null,
            label = accountLabel(o.str("label"), o.str("kind"), o.str("lastFour") ?: o.str("last_four")),
            isDefault = o["default"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun parseChargeAccountsProto(bytes: ByteArray): List<ChargeAccount> {
        val root = ProtoMessage(bytes, 0, bytes.size)
        return root.messages(1).mapNotNull { toChargeAccountProto(it) }
    }

    private fun toChargeAccountProto(m: ProtoMessage): ChargeAccount? {
        val id = m.wrappedString(1) ?: return null
        return ChargeAccount(
            id = id,
            chargeToken = null,
            label = accountLabel(m.wrappedString(5), m.wrappedString(2), m.wrappedString(10)),
            isDefault = m.wrappedBool(3) ?: false,
        )
    }

    private fun accountLabel(label: String?, kind: String?, lastFour: String?): String = when {
        !label.isNullOrBlank() -> label
        !kind.isNullOrBlank() && !lastFour.isNullOrBlank() -> "$kind ••$lastFour"
        !lastFour.isNullOrBlank() -> "•• $lastFour"
        !kind.isNullOrBlank() -> kind
        else -> "Card"
    }

    /**
     * Best-effort pick of the ride/trip object out of a create or active-ride response. Exact
     * shapes are unverified (api-notes §4/§6); we look for the common wrappers and fall back to
     * the root so id/status are surfaced when present.
     */
    private fun firstRideObject(raw: String): JsonObject? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return null
        return root["ride"]?.jsonObject
            ?: root["trip"]?.jsonObject
            ?: root["active_ride"]?.jsonObject
            ?: root
    }

    private suspend fun accessToken(): String? {
        if (!tokens.isExpired()) return tokens.accessToken()
        val refresh = tokens.refreshToken() ?: return null
        val fresh = LyftAuth.refresh(refresh) ?: return null
        tokens.save(fresh)
        return fresh.accessToken
    }

    /** A single `E6LatLngDTO`: lat/lng in integer micro-degrees (degrees × 1e6). */
    private fun e6(place: Place): String {
        val latE6 = (place.location.latitude * 1_000_000).roundToLong()
        val lngE6 = (place.location.longitude * 1_000_000).roundToLong()
        return """{"latitude_e6":$latE6,"longitude_e6":$lngE6}"""
    }

    companion object {
        private const val TAG = "LyftProvider"

        /**
         * Master guard for live ride booking. While false, [createRide] builds and returns the
         * request but never sends it. Flip to true only after the dry-run request body has been
         * verified against a real capture.
         *
         * Note: this gates *ride creation* only. Payment-method management and [cancelRide] are
         * sent live regardless, per product decision.
         */
        const val BOOKING_LIVE = true
    }
}

/**
 * The pieces of a `ReadOffersV2Response` the app keeps: the per-product [quotes] plus the
 * offers-wrapper identifiers reused when booking.
 */
private data class ParsedOffers(
    val quotes: List<RideQuote>,
    val purchaseSessionId: String?,
    val offersResponseId: String?,
) {
    companion object {
        val EMPTY = ParsedOffers(emptyList(), null, null)
    }
}

/**
 * Which processor to tokenize a new card with, and that processor's client [key]. For the modern
 * Stripe SetupIntent strategy (`sbe0` tag 22, `uhc0`) the extra SetupIntent fields are carried too.
 */
private data class TokenizerConfig(
    val provider: String,
    val key: String,
    val clientSecret: String? = null,
    val setupIntentId: String? = null,
    val stripeApiVersion: String? = null,
)

/**
 * Outcome of tokenizing a card at the processor: a Stripe [token] or a Braintree [nonce], plus the
 * [version] (`it00`) the server needs to interpret a Stripe token (STRIPE_TOKEN vs
 * STRIPE_SETUP_INTENT); null for processors that don't send one (Braintree).
 */
private sealed interface TokenizeResult {
    data class Ok(
        val provider: String,
        val token: String?,
        val nonce: String?,
        val version: String? = null,
    ) : TokenizeResult

    data class Err(val message: String) : TokenizeResult
}

package com.vayunmathur.taxi.provider

import com.vayunmathur.taxi.data.AddCardResult
import com.vayunmathur.taxi.data.BookingResult
import com.vayunmathur.taxi.data.CancelResult
import com.vayunmathur.taxi.data.ChargeAccount
import com.vayunmathur.taxi.data.DriverLocation
import com.vayunmathur.taxi.data.NewCard
import com.vayunmathur.taxi.data.PaymentActionResult
import com.vayunmathur.taxi.data.PaymentMethodsResult
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideStatusResult
import com.vayunmathur.taxi.data.RideQuote

/**
 * One ride-hailing service. Quotes come from the service's own API; booking either hands off to
 * the official app via [bookingUri] or, where the provider supports it, books in-app through the
 * methods below. In-app booking is opt-in per provider: the defaults report "unsupported" so a
 * provider that only deep-links (and callers that only quote) need no changes.
 */
interface RideProvider {
    val provider: Provider

    suspend fun isSignedIn(): Boolean

    suspend fun quotes(pickup: Place, dropoff: Place): QuoteResult

    /**
     * Deep link that opens the official app with the trip pre-filled. [quote] selects a
     * specific product where the provider supports it.
     */
    fun bookingUri(pickup: Place, dropoff: Place, quote: RideQuote?): String

    // ------------------------------------------------------------------------------------------
    // In-app booking (optional per provider). Defaults report "unsupported".
    // ------------------------------------------------------------------------------------------

    /** Fetches the user's payment methods live; the list is held in memory only, never stored. */
    suspend fun paymentMethods(): PaymentMethodsResult = PaymentMethodsResult.Unsupported

    /** Marks the given charge account as the account's default. Sent live. */
    suspend fun setDefaultPaymentMethod(id: String): PaymentActionResult =
        PaymentActionResult.Unsupported

    /** Removes the given charge account from the user's account. Sent live. */
    suspend fun removePaymentMethod(id: String): PaymentActionResult =
        PaymentActionResult.Unsupported

    /**
     * Adds a new card. The raw card is tokenized by the provider's payment processor first; only
     * the resulting token/nonce is sent on. Sent live. [makeDefault] sets it as the default.
     */
    suspend fun addCard(card: NewCard, makeDefault: Boolean): AddCardResult =
        AddCardResult.Unsupported

    /**
     * Creates a ride against the selected [quote]. When [dryRun] is true the request is built and
     * returned but never sent, so the exact body can be verified before any real charge.
     * [purchaseSessionId] (from the offers response) is reused so a retried create deduplicates.
     */
    suspend fun createRide(
        quote: RideQuote,
        pickup: Place,
        dropoff: Place,
        account: ChargeAccount?,
        purchaseSessionId: String?,
        dryRun: Boolean,
    ): BookingResult = BookingResult.Unsupported

    /** Reads the ride currently in progress, if any. */
    suspend fun activeRide(): RideStatusResult = RideStatusResult.Unsupported

    /** The driver's live position for [rideId], for higher-frequency marker refresh. */
    suspend fun driverLocation(rideId: String): DriverLocation? = null

    /** Cancels the ride with [rideId]. The server response is surfaced verbatim (may carry a fee). */
    suspend fun cancelRide(rideId: String): CancelResult = CancelResult.Unsupported
}

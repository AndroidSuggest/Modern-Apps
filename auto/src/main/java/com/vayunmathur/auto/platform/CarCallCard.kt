package com.vayunmathur.auto.platform

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.vayunmathur.auto.R
import com.vayunmathur.auto.platform.CarUiMetrics.BUTTON_DISABLED_ALPHA
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_CARD_DAY
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_SUBTITLE
import com.vayunmathur.auto.platform.CarUiMetrics.MATCH
import com.vayunmathur.auto.platform.CarUiMetrics.WRAP

/**
 * The projected call card (`UnCallView` parity): incoming vs active
 * rows with the 44dp (`un_ar_icon_size`) action buttons, the end-call
 * FAB focusable, and the `PHONE_FACET` content description. Held calls
 * disable the row with the button-disabled alpha. GONE with no live
 * call; the duration ticks every second like `postDelayed(l, 1000)`.
 *
 * Owns its views; the presentation drives state through [update] (null
 * hides) and ticks duration through [tickDuration]. Actions route to the
 * bound InCallService through the constructor callbacks. Views, not
 * Compose (private display, no lifecycle owner).
 */
internal class CarCallCardView(
    private val context: Context,
    private val onAnswer: () -> Unit,
    private val onEnd: () -> Unit,
    private val onHold: () -> Unit,
    private val onMute: () -> Unit,
) {
    private fun dp(value: Int): Int = context.carDp(value)

    private var callCardView: LinearLayout? = null
    private var callName: TextView? = null
    private var callState: TextView? = null
    private var callActions: LinearLayout? = null

    /**
     * Inner active-call snapshot: set by [update], read by [tickDuration],
     * so duration ticks against the card's own state.
     */
    private var innerActiveCall: ActiveCallInfo? = null

    /** Late-wired actions (service wires after `show()` starts the build). */
    var tapAnswer: () -> Unit = onAnswer
    var tapEnd: () -> Unit = onEnd
    var tapHold: () -> Unit = onHold
    var tapMute: () -> Unit = onMute

    /** The assembled card; add once above the nav/media split. */
    val view: LinearLayout = callCard()

    /** Clears view refs; call from `onStop`. */
    fun clear() {
        callCardView = null
        callName = null
        callState = null
        callActions = null
        innerActiveCall = null
    }

    /**
     * Pushes one active-call snapshot into the card. Main thread only;
     * null hides the card. Incoming shows answer/decline; active shows
     * end/mute/hold with the hold-disabled alpha; the duration ticks on
     * the 1Hz ticker while active.
     */
    fun update(info: ActiveCallInfo?) {
        innerActiveCall = info
        val card = callCardView ?: return
        if (info == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        val name = callName ?: return
        name.text = info.number ?: context.getString(R.string.car_call_unknown)
        val state = callState
        if (state != null) {
            state.text = if (info.isIncoming) {
                context.getString(R.string.car_call_incoming)
            } else {
                context.getString(R.string.car_call_active)
            }
        }
        val row = callActions ?: return
        row.removeAllViews()
        if (info.isIncoming) {
            row.addView(callActionButton(context.getString(R.string.car_call_answer)) { tapAnswer() })
            row.addView(callActionButton(context.getString(R.string.car_call_decline)) { tapEnd() })
        } else {
            val end = callActionButton(context.getString(R.string.car_call_end)) { tapEnd() }
            end.isFocusable = true
            row.addView(end)
            val mute = callActionButton(
                if (info.muted) context.getString(R.string.car_call_unmute)
                else context.getString(R.string.car_call_mute),
            ) { tapMute() }
            val hold = callActionButton(
                if (info.held) context.getString(R.string.car_call_resume)
                else context.getString(R.string.car_call_hold),
            ) { tapHold() }
            if (info.held) {
                // Held: row disabled with the button-disabled alpha.
                mute.isEnabled = false
                hold.isEnabled = false
                mute.alpha = BUTTON_DISABLED_ALPHA
                hold.alpha = BUTTON_DISABLED_ALPHA
            }
            row.addView(mute)
            row.addView(hold)
        }
    }

    /**
     * Ticks the active-call duration once per second. Main thread only;
     * called from the 1Hz ticker. Incoming (ringing) shows no duration;
     * active shows mm:ss since accept; held freezes on the held snapshot.
     */
    fun tickDuration() {
        val info = innerActiveCall ?: return
        val state = callState ?: return
        if (info.isIncoming || info.acceptedMs <= 0L) return
        val elapsed = ((System.currentTimeMillis() - info.acceptedMs) / 1000).coerceAtLeast(0)
        state.text = context.getString(
            R.string.car_call_duration,
            elapsed / 60,
            elapsed % 60,
        )
    }

    private fun callCard(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor(COLOR_CARD_DAY))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(8)
            }
            contentDescription = "phone"
            addView(
                TextView(context).apply {
                    textSize = 24f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    callName = this
                },
            )
            addView(
                TextView(context).apply {
                    textSize = 18f
                    setTextColor(Color.parseColor(COLOR_SUBTITLE))
                    gravity = Gravity.CENTER
                    callState = this
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                        topMargin = dp(12)
                    }
                    callActions = this
                },
            )
            callCardView = this
        }
    }

    /** One 44dp (`un_ar_icon_size`) call action button. */
    private fun callActionButton(label: String, onTap: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            background = context.carFocusRingBackground()
            isClickable = true
            isFocusable = true
            minWidth = dp(44)
            minHeight = dp(44)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
            setOnClickListener { onTap() }
        }
    }
}

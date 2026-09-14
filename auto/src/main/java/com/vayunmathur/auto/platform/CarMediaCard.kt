package com.vayunmathur.auto.platform

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.vayunmathur.auto.R
import com.vayunmathur.auto.platform.CarUiMetrics.BUTTON_DISABLED_ALPHA
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_ACCENT
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_CARD_DAY
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_CARD_NIGHT
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_SUBTITLE
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_TRACK
import com.vayunmathur.auto.platform.CarUiMetrics.MATCH
import com.vayunmathur.auto.platform.CarUiMetrics.WRAP

/**
 * The dash media card (`frag_dash_media` shape): full-bleed album art with
 * scrim + 24dp source badge, title (28sp, 1 line) + subtitle (24sp), 4dp
 * progress, and the 88dp prev/play/next row (Space weights 1/2/2/1, 12dp
 * insets). The whole text region taps to toggle; the row buttons fire
 * prev/play/next. Starts hidden: the first snapshot decides.
 *
 * Owns its views; the presentation drives state through [update]/[hide]
 * and recolors through [setNight]. Reports layout bounds for ch8 tap
 * routing through [onCardBounds]. Views, not Compose (private display, no
 * lifecycle owner).
 */
internal class CarMediaCardView(
    private val context: Context,
    private val onMediaTap: () -> Unit,
    private val onPreviousTap: () -> Unit,
    private val onNextTap: () -> Unit,
    private val onCardBounds: (Int, Int, Int, Int) -> Unit,
) {
    private fun dp(value: Int): Int = context.carDp(value)

    private var mediaTitle: TextView? = null
    private var mediaSubtitle: TextView? = null
    private var mediaState: TextView? = null
    private var mediaProgress: View? = null
    private var mediaCard: FrameLayout? = null
    private var albumArt: ImageView? = null
    private var sourceBadge: ImageView? = null
    private var mediaButtons: LinearLayout? = null
    private var prevButton: TextView? = null
    private var playButton: TextView? = null
    private var nextButton: TextView? = null

    /** The assembled card; add once to the split. */
    val view: FrameLayout = nowPlayingCard()

    /** Late-wired taps (service wires after `show()` starts the build). */
    var tapMedia: () -> Unit = onMediaTap
    var tapPrevious: () -> Unit = onPreviousTap
    var tapNext: () -> Unit = onNextTap

    /** Hides the card; the cached snapshot is untouched. Main thread only. */
    fun hide() {
        mediaCard?.visibility = View.GONE
    }

    /** Driving-restriction gate: hides the action row. Main thread only. */
    fun setButtonsVisible(visible: Boolean) {
        mediaButtons?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Recolors the card for night. Main thread only. */
    fun setNight(dark: Boolean) {
        mediaCard?.setBackgroundColor(
            Color.parseColor(if (dark) COLOR_CARD_NIGHT else COLOR_CARD_DAY),
        )
    }

    /** Clears view refs; call from `onStop`. */
    fun clear() {
        mediaTitle = null
        mediaSubtitle = null
        mediaState = null
        mediaProgress = null
        mediaCard = null
        albumArt = null
        sourceBadge = null
        mediaButtons = null
        prevButton = null
        playButton = null
        nextButton = null
    }

    /**
     * Applies a snapshot to the card. Main thread only.
     *
     * Visibility follows `shouldShowCard()`: `GONE` when idle so the car
     * shows no card with nothing playing; `VISIBLE` while playing, and
     * while paused with a title so resume keeps its context. Artwork and
     * prev/next enablement follow the snapshot (absent art hides the
     * art view rather than faking it).
     */
    fun update(info: NowPlayingInfo) {
        val card = mediaCard ?: return
        card.visibility = if (info.shouldShowCard()) View.VISIBLE else View.GONE
        mediaTitle?.text = info.title ?: context.getString(R.string.car_now_playing_unknown)
        mediaSubtitle?.text = info.artist ?: context.getString(R.string.car_now_playing_unknown_artist)
        mediaState?.text = context.getString(
            if (info.playing) R.string.car_now_playing_playing
            else R.string.car_now_playing_paused,
        )
        playButton?.text = context.getString(
            if (info.playing) R.string.car_media_pause_glyph else R.string.car_media_play_glyph,
        )
        playButton?.contentDescription = context.getString(
            if (info.playing) R.string.car_media_pause else R.string.car_media_play,
        )
        prevButton?.isEnabled = info.hasPrevious
        nextButton?.isEnabled = info.hasNext
        prevButton?.alpha = if (info.hasPrevious) 1f else BUTTON_DISABLED_ALPHA
        nextButton?.alpha = if (info.hasNext) 1f else BUTTON_DISABLED_ALPHA
        updateArtwork(info)
        val progress = mediaProgress ?: return
        val fraction = CarUiMetrics.progressFraction(info.positionMs, info.durationMs)
        val bar = mediaCard ?: return
        // Resize the fill once laid out: the bar width is only known after
        // the layout pass, and the encoder surface picks the re-layout up on
        // the next frame.
        progress.post {
            val total = bar.width
            if (total > 0) {
                progress.layoutParams = progress.layoutParams.apply {
                    width = (total * fraction).toInt().coerceIn(0, total)
                }
                progress.requestLayout()
            }
        }
    }

    /** Shows decoded artwork, the display icon, or hides the art view. Main thread only. */
    private fun updateArtwork(info: NowPlayingInfo) {
        val art = albumArt ?: return
        val bytes = info.artworkData
        if (bytes != null && bytes.isNotEmpty()) {
            val bitmap = runCatching {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            if (bitmap != null) {
                art.setImageBitmap(bitmap)
                art.visibility = View.VISIBLE
                return
            }
        }
        val icon = info.displayIcon
        if (icon != null) {
            art.setImageDrawable(icon)
            art.visibility = View.VISIBLE
            return
        }
        art.visibility = View.GONE
    }

    private fun nowPlayingCard(): FrameLayout {
        val card = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor(COLOR_CARD_DAY))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            isClickable = true
            isFocusable = true
            // Starts hidden: the first snapshot decides, and with nothing
            // playing there is nothing to show.
            visibility = View.GONE
        }
        card.addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                visibility = View.GONE
                albumArt = this
            },
        )
        card.addView(
            View(context).apply {
                // Album-art scrim over the full bleed (tinted dark).
                setBackgroundColor(Color.argb(153, 0x10, 0x14, 0x18))
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            },
        )
        card.addView(
            ImageView(context).apply {
                // Source badge: 24dp circle (`dashboard_media_badge_size`).
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END)
                clipToOutline = true
                outlineProvider = circularOutline()
                visibility = View.GONE
                sourceBadge = this
            },
        )
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(5), dp(4), dp(5), dp(7))
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
            background = context.carFocusRingBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener { tapMedia() }
        }
        textColumn.addView(
            TextView(context).apply {
                setTextColor(Color.parseColor(COLOR_ACCENT))
                textSize = 14f
                text = context.getString(R.string.car_now_playing)
            },
        )
        textColumn.addView(
            TextView(context).apply {
                textSize = 28f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.WHITE)
                includeFontPadding = false
                setPadding(0, dp(5), 0, 0)
                mediaTitle = this
            },
        )
        textColumn.addView(
            TextView(context).apply {
                textSize = 24f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.parseColor(COLOR_SUBTITLE))
                setPadding(0, 0, 0, dp(5))
                mediaSubtitle = this
            },
        )
        textColumn.addView(
            TextView(context).apply {
                textSize = 14f
                setTextColor(Color.parseColor(COLOR_ACCENT))
                mediaState = this
            },
        )
        card.addView(textColumn)
        val track = View(context).apply {
            setBackgroundColor(Color.parseColor(COLOR_TRACK))
            layoutParams = FrameLayout.LayoutParams(MATCH, dp(4), Gravity.BOTTOM).apply {
                bottomMargin = dp(4)
            }
        }
        card.addView(track)
        val fill = View(context).apply {
            setBackgroundColor(Color.parseColor(COLOR_ACCENT))
            layoutParams = FrameLayout.LayoutParams(0, dp(4), Gravity.BOTTOM or Gravity.START).apply {
                bottomMargin = dp(4)
            }
            mediaProgress = this
        }
        card.addView(fill)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
                bottomMargin = dp(4)
            }
            mediaButtons = this
        }
        row.addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
        row.addView(
            mediaActionButton(
                context.getString(R.string.car_media_prev_glyph),
                context.getString(R.string.car_media_previous),
            ) { tapPrevious() }.also { prevButton = it },
        )
        row.addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 2f) })
        row.addView(
            mediaActionButton(
                context.getString(R.string.car_media_play_glyph),
                context.getString(R.string.car_media_play),
            ) { tapMedia() }.also { playButton = it },
        )
        row.addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 2f) })
        row.addView(
            mediaActionButton(
                context.getString(R.string.car_media_next_glyph),
                context.getString(R.string.car_media_next),
            ) { tapNext() }.also { nextButton = it },
        )
        row.addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
        card.addView(row)
        // Report layout bounds for ch8 tap routing once the view is placed.
        card.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val loc = IntArray(2)
                    card.getLocationOnScreen(loc)
                    onCardBounds(loc[0], loc[1], loc[0] + card.width, loc[1] + card.height)
                }
            },
        )
        mediaCard = card
        return card
    }

    /** One 88dp (`dashboard_media_action_size`) media action button with 12dp insets. */
    private fun mediaActionButton(glyph: String, description: String, onTap: () -> Unit): TextView {
        return TextView(context).apply {
            text = glyph
            contentDescription = description
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            background = context.carFocusRingBackground()
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(88)).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
        }
    }
}

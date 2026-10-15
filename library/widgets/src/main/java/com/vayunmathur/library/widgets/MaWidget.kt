package com.vayunmathur.library.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.LocalContext
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.Text
import com.vayunmathur.library.log.Log

/**
 * Shared Glance widget shell for every Modern App widget.
 *
 * Every widget used to repeat the same `provideGlance` / `providePreview`:
 * load data, wrap in [DynamicThemeGlance], broad `try/catch` with `Log.error`,
 * and a centered fallback when loading or preview fails. That shell lives
 * here now; a widget keeps only what is truly per-app.
 *
 * Two shapes are supported:
 * - Data widgets (email, weather, calendar, photos): override [loadData] with
 *   the Room / DataStore / snapshot read and [Content] with the layout. The
 *   data type [D] is nullable-friendly: return `null` while nothing is cached
 *   yet and render a dash/placeholder, the way the weather widgets did.
 * - State widgets (calculator keypad, screentime, the analog clock): the data
 *   lives in Glance preferences read via `currentState()` inside composition.
 *   Use `Unit` for [D], return it from [loadData], and read the state inside
 *   [Content] — `currentState()` works in any composable under
 *   `provideContent`.
 *
 * Widgets with a bespoke preview (calculator's sample expression, photos'
 * drawable, screentime's sample numbers) override [PreviewContent]; the one
 * widget whose picker preview comes from `previewLayout` XML (the analog
 * clock — composing its `AndroidRemoteViews` inside `setWidgetPreviews`
 * crashes the preview host) sets [composePreview] to false.
 */
abstract class MaWidget<D> : GlanceAppWidget() {
    /**
     * Load the widget's data off the main thread. Called from [provideGlance]
     * only; keep it cheap and let it throw — failures render [FallbackContent].
     * The [GlanceId] is there for widgets that read per-widget Glance state
     * via `getAppWidgetState`; most widgets ignore it.
     */
    protected abstract suspend fun loadData(context: Context, id: GlanceId): D

    /** Sample data for the launcher widget picker. */
    protected abstract fun previewData(context: Context): D

    /** Glance layout for [data]. */
    @Composable
    protected abstract fun Content(data: D)

    /** False when the picker preview comes from `previewLayout` XML instead. */
    protected open val composePreview: Boolean = true

    /** Bespoke picker content; defaults to [Content] over [previewData]. */
    @Composable
    protected open fun PreviewContent() {
        Content(previewData(LocalContext.current))
    }

    protected open val logTag: String
        get() = this::class.simpleName ?: "MaWidget"

    // Broad catches are deliberate: a widget must never throw into the host,
    // and Glance plus Room throw undocumented RuntimeExceptions.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    final override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = try {
            loadData(context, id)
        } catch (e: Exception) {
            Log.error(logTag, "loadData failed", e)
            provideContent {
                DynamicThemeGlance(context) {
                    FallbackContent()
                }
            }
            return
        }
        try {
            provideContent {
                DynamicThemeGlance(context) {
                    Content(data)
                }
            }
        } catch (e: Exception) {
            Log.error(logTag, "provideContent failed", e)
            try {
                provideContent {
                    DynamicThemeGlance(context) {
                        FallbackContent()
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    final override suspend fun providePreview(context: Context, widgetCategory: Int) {
        if (!composePreview) return
        try {
            provideContent {
                DynamicThemeGlance(context) {
                    PreviewContent()
                }
            }
        } catch (e: Exception) {
            Log.error(logTag, "providePreview failed", e)
            try {
                provideContent {
                    DynamicThemeGlance(context) {
                        FallbackContent()
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    @Composable
    protected open fun FallbackContent() {
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("—")
        }
    }
}

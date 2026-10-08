package com.vayunmathur.library.log

import android.util.Log as AndroidLog

/**
 * Repo-wide logging facade. Replaces direct `android.util.Log` use everywhere; a
 * fatal lint check (`DirectAndroidLog`) bans that import outside this file.
 *
 * Four levels, each an explicit `tag` plus `content` (existing call-site `TAG`
 * constants are kept as-is, so custom tags like `MaAuto.Server` survive):
 *  - [dev]    → DEBUG, only when [init] was called with `isDev = true`
 *               (the `BuildConfig.DEV_BUILD` flag that also gates findfamily trackers)
 *  - [debug]  → DEBUG, always
 *  - [status] → INFO, always (absorbs old `Log.i` and `Log.w` — see below)
 *  - [error]  → ERROR, always
 *
 * The old `Log.w` maps to [status], not a warn level: nothing in the repo gates
 * behaviour on warn-vs-info, and keeping four functions keeps every call site a
 * mechanical rewrite. `Log.v` maps to [debug].
 *
 * The tag is an explicit parameter rather than a stack-derived caller class
 * name: R8 inlines and renames frames, so any `Thread.stackTrace` / reflection
 * scheme degrades in release builds, and an explicit tag costs nothing.
 *
 * Each app wires the dev gate once in its `Application.onCreate`:
 * `Log.init(BuildConfig.DEV_BUILD)`. Until then [dev] is silent.
 */
object Log {

    @Volatile
    private var devEnabled: Boolean = false

    /**
     * Wire the dev gate. Call once from `Application.onCreate` with the app's
     * `BuildConfig.DEV_BUILD` (true on `dev` builds, false on `release`).
     * Idempotent — the last call wins.
     */
    fun init(isDev: Boolean) {
        devEnabled = isDev
    }

    /** True once [init] has enabled dev logging. Exposed for tests and diagnostics. */
    fun isDevEnabled(): Boolean = devEnabled

    /** DEBUG-level log that only emits when the dev gate is enabled. See [init]. */
    @JvmStatic
    fun dev(tag: String, content: String) {
        if (devEnabled) AndroidLog.d(tag, content)
    }

    /** DEBUG-level log with an attached throwable, gated like [dev]. */
    @JvmStatic
    fun dev(tag: String, content: String, tr: Throwable?) {
        if (devEnabled) AndroidLog.d(tag, content, tr)
    }

    /** DEBUG-level log, always emitted. */
    @JvmStatic
    fun debug(tag: String, content: String) {
        AndroidLog.d(tag, content)
    }

    /** DEBUG-level log with an attached throwable, always emitted. */
    @JvmStatic
    fun debug(tag: String, content: String, tr: Throwable?) {
        AndroidLog.d(tag, content, tr)
    }

    /** INFO-level log, always emitted. Absorbs old `Log.i` and `Log.w` call sites. */
    @JvmStatic
    fun status(tag: String, content: String) {
        AndroidLog.i(tag, content)
    }

    /** INFO-level log with an attached throwable, always emitted. */
    @JvmStatic
    fun status(tag: String, content: String, tr: Throwable?) {
        AndroidLog.i(tag, content, tr)
    }

    /** ERROR-level log, always emitted. */
    @JvmStatic
    fun error(tag: String, content: String) {
        AndroidLog.e(tag, content)
    }

    /** ERROR-level log with an attached throwable, always emitted. */
    @JvmStatic
    fun error(tag: String, content: String, tr: Throwable?) {
        AndroidLog.e(tag, content, tr)
    }
}

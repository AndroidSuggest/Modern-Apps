package com.vayunmathur.web.platform

import android.webkit.WebChromeClient

/** Best-effort read of the chooser's accept list; platform getters must not crash the sheet. */
internal fun WebChromeClient.FileChooserParams.safeAcceptTypes(): List<String> =
    runCatching { acceptTypes.toList() }.getOrDefault(emptyList())

/** Best-effort read of the chooser mode; defaults to single selection. */
internal fun WebChromeClient.FileChooserParams.isMultipleSelection(): Boolean =
    runCatching { mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE }.getOrDefault(false)

/** Non-blank MIME types, or a wildcard document pick when none were specified. */
internal fun List<String>.toLaunchArray(): Array<String> =
    filter { it.isNotBlank() }.toTypedArray().takeIf { it.isNotEmpty() } ?: arrayOf("*/*")

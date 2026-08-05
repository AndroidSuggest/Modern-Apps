package com.vayunmathur.appstore.data

object PlayStoreLinks {

    const val PLAY_BASE = "https://play.google.com"

    fun playStoreUrl(pkg: String): String = "$PLAY_BASE/store/apps/details?id=$pkg"
}

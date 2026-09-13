package com.vayunmathur.auto.platform

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.vayunmathur.auto.service.ProjectionService

/**
 * Deep-link resolver, mirroring gearhead's `DeepLinkResolver`: a link or
 * launcher intent naming projection (`START_USB_PROJECTION`, `BT_START`,
 * wireless startup) lands here and is routed into the matching chain entry
 * point instead of `MainActivity`.
 *
 * Minimal: no UI -- it redirects and finishes in `onCreate`. Unknown
 * actions fall through to the projection listener (harmless when already
 * running) rather than a dead screen, and an explicit unknown-action log
 * keeps misdirected links visible.
 */
class DeepLinkResolverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            CarStartupService.ACTION_START_USB_PROJECTION,
            CarStartupService.ACTION_BT_START,
            WirelessStartupReceiver.ACTION_WIRELESS_STARTUP,
            -> {
                Log.i(TAG, "deep link ${intent.action}; entering the startup chain")
                startForegroundService(
                    Intent(this, CarStartupService::class.java)
                        .setAction(intent.action),
                )
            }
            else -> {
                Log.i(TAG, "deep link without a chain action; starting the listener")
                ProjectionService.start(this)
            }
        }
        finish()
    }

    private companion object {
        const val TAG = "MaAuto.DeepLink"
    }
}

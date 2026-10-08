package com.vayunmathur.fooddelivery

import android.app.Application
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.log.Log

class FoodDeliveryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.init(BuildConfig.DEV_BUILD)
        AppInit.start(this)
    }
}

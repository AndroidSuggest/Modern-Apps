package com.vayunmathur.calculator

import android.app.Application
import com.vayunmathur.library.log.Log

/**
 * Wires the repo logging facade's dev gate: `Log.dev` only emits on `dev`
 * builds (`BuildConfig.DEV_BUILD`), matching the findfamily tracker gate.
 */
class CalculatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.init(BuildConfig.DEV_BUILD)
    }
}

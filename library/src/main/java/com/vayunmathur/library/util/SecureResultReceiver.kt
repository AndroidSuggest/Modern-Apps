package com.vayunmathur.library.util

import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver

class SecureResultReceiver(
    handler: Handler?,
    private val onResult: (resultCode: Int, resultData: Bundle?) -> Unit
) : ResultReceiver(handler) {
    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
        onResult(resultCode, resultData)
    }
}

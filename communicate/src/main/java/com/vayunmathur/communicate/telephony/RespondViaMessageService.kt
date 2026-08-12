package com.vayunmathur.communicate.telephony

import android.app.Service
import android.content.Intent
import android.os.IBinder

class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

package com.vayunmathur.education

import android.app.Application
import com.vayunmathur.education.util.EducationDownloader
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import org.schabi.newpipe.extractor.NewPipe

class EducationApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // FIRST_PARTY covers api.vayunmathur.com + YouTube (GTS R1-R4) used by EducationDownloader
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        NewPipe.init(EducationDownloader())
    }
}

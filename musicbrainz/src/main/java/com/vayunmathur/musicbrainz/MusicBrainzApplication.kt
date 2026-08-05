package com.vayunmathur.musicbrainz

import android.app.Application
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.musicbrainz.download.MbDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class MusicBrainzApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // STANDARD rather than FIRST_PARTY: musicbrainz.org and lrclib.net are ISRG, but
        // coverartarchive.org redirects to ia*.us.archive.org, which chains to Amazon and
        // DigiCert roots that only STANDARD carries.
        NetworkClient.init(this, TrustBundle.STANDARD)
        NewPipe.init(MbDownloader(), Localization.fromLocale(Locale.getDefault()))
    }
}

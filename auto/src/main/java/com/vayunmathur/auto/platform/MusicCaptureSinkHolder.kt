package com.vayunmathur.auto.platform

/**
 * Process-wide holder for the ch5 media sink, bridging the projection session
 * (which owns the sink per connection) and the music-capture service (which
 * outlives any one session, like the media monitor).
 *
 * Mirrors the `systemSink = { audioSys }` lambda seam `CarTts` uses: the sink
 * resolves lazily at feed time, so capture before the ch5 grant (or after
 * teardown) drops chunks with a count instead of crashing. Written on the
 * projection worker thread, read on the capture thread -- a volatile var is
 * enough, matching the existing sink seams.
 */
object MusicCaptureSinkHolder {
    @Volatile
    var sink: AudioSinkChannel? = null
}

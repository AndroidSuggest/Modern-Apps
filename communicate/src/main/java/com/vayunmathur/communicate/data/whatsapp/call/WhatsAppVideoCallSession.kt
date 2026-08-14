package com.vayunmathur.communicate.data.whatsapp.call

import android.content.Context
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Video extension of [WhatsAppCallSession] (Phase E / Feature 4). Adds a camera
 * `VideoSource`/`VideoTrack` (WebRTC `Camera2Enumerator`), local/remote `SurfaceViewRenderer`
 * rendering off the shared `EglBase`, camera switch, and `OfferToReceiveVideo=true`.
 *
 * Renderers are owned by the UI ([WhatsAppCallScreen]); it calls [attachRenderers] once the
 * surfaces are laid out. Everything else (SDP/ICE, mute, speaker) is inherited from the base.
 */
class WhatsAppVideoCallSession(context: Context) : WhatsAppCallSession(context) {

    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: VideoTrack? = null

    override fun offerConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    override fun onPeerConnectionCreated(f: PeerConnectionFactory) {
        val capturer = createCameraCapturer() ?: return
        videoCapturer = capturer
        val helper = SurfaceTextureHelper.create("wa_capture", eglBase.eglBaseContext)
        surfaceHelper = helper
        val source = f.createVideoSource(false)
        videoSource = source
        capturer.initialize(helper, appContext, source.capturerObserver)
        runCatching { capturer.startCapture(1280, 720, 30) }
        val track = f.createVideoTrack("wa_video", source)
        localVideoTrack = track
        peerConnection?.addTrack(track, listOf("wa_stream"))
    }

    /** Attach the UI renderers; also renders the local track immediately. */
    fun attachRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        localRenderer = local
        remoteRenderer = remote
        runCatching {
            local?.init(eglBase.eglBaseContext, null)
            remote?.init(eglBase.eglBaseContext, null)
        }
        local?.let { localVideoTrack?.addSink(it) }
        remoteVideoTrack?.let { t -> remote?.let { t.addSink(it) } }
    }

    fun switchCamera() {
        runCatching { videoCapturer?.switchCamera(null) }
    }

    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        val names = enumerator.deviceNames
        // Prefer the front camera for calls.
        names.firstOrNull { enumerator.isFrontFacing(it) }?.let {
            return enumerator.createCapturer(it, null)
        }
        names.firstOrNull()?.let { return enumerator.createCapturer(it, null) }
        return null
    }

    override fun close() {
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        runCatching { localRenderer?.release() }
        runCatching { remoteRenderer?.release() }
        videoCapturer = null
        videoSource = null
        localVideoTrack = null
        remoteVideoTrack = null
        surfaceHelper = null
        super.close()
    }
}

package com.vayunmathur.youpipe.ui

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.findActivity
import com.vayunmathur.youpipe.rememberIsInPipMode
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.util.PlaybackService
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

/**
 * Playback-owned state for [VideoPlayer]: audio/language selection, the media controller
 * lifecycle, transport observers, history writes, the media item, subtitles and tempo.
 *
 * Moved from VideoPlayer.kt (FileLength split); behavior identical. The view-owned half
 * (menus, lock, hover, PiP, aspect ratio, cast session, controls timer) lives in
 * [VideoPlayerViewState]; the player UI reads both via `with(pb) { with(vs) { ... } }`.
 */
internal class VideoPlayerPlaybackState(
    languageState: androidx.compose.runtime.MutableState<String>,
    controllerState: androidx.compose.runtime.MutableState<MediaController?>,
    currentVideoStreamState: androidx.compose.runtime.MutableState<VideoStream>,
    currentAudioStreamState: androidx.compose.runtime.MutableState<AudioStream?>,
    historyPositionState: androidx.compose.runtime.MutableLongState,
    historyDurationState: androidx.compose.runtime.MutableLongState,
    playbackPitchState: androidx.compose.runtime.MutableFloatState,
    unhookPitchState: androidx.compose.runtime.MutableState<Boolean>,
    selectedSubtitleState: androidx.compose.runtime.MutableState<SubtitleTrack?>,
    cuesState: androidx.compose.runtime.MutableState<List<Cue>>,
    aspectRatioState: androidx.compose.runtime.MutableFloatState,
    val languageEntries: List<Pair<String, String>>,
    val languages: List<String>,
    val audioStreamOptions: List<AudioStream>,
    val currentPosition: Long,
    val bufferedPosition: Long,
    val duration: Long,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val isDragging: Boolean,
    val playbackSpeed: Float,
    val timeWatched: Long,
) {
    var language: String by languageState
    var controller: MediaController? by controllerState
    var currentVideoStream: VideoStream by currentVideoStreamState
    var currentAudioStream: AudioStream? by currentAudioStreamState
    var historyPosition: Long by historyPositionState
    var historyDuration: Long by historyDurationState
    var playbackPitch: Float by playbackPitchState
    var unhookPitch: Boolean by unhookPitchState
    var selectedSubtitle: SubtitleTrack? by selectedSubtitleState
    var cues: List<Cue> by cuesState
    var aspectRatio: Float by aspectRatioState
}

@OptIn(UnstableApi::class)
@Composable
internal fun rememberVideoPlayerPlaybackState(
    ypvm: YouPipeViewModel,
    videoInfo: VideoInfo,
    videoStreams: List<VideoStream>,
    audioStreams: List<AudioStream>,
    subtitles: List<SubtitleTrack>,
): VideoPlayerPlaybackState {
    val context = LocalContext.current
    val sponsorBlockEnabled by ypvm.sponsorBlockEnabled.collectAsState()
    val sponsorBlockCategories by ypvm.sponsorBlockCategories.collectAsState()
    val videoState by ypvm.videoState.collectAsState()
    val sponsorSegments = videoState.sponsorSegments.filter { it.category in sponsorBlockCategories }

    // Use displayName
    // Use displayName for language UI when available (audioTrackName like "English", "Original"), fallback to language code
    val languageEntries = remember(audioStreams) {
        audioStreams.map { it.language to (it.displayName ?: it.language) }
            .distinctBy { it.first }
            .sortedBy { it.first }
    }
    val languages = languageEntries.map { it.first }
    val languageState = remember(audioStreams) { mutableStateOf(if ("en" in languages) "en" else languages.firstOrNull() ?: "") }
    var language by languageState
    // Keep language valid when audioStreams change (new video)
    LaunchedEffect(languages) {
        if (language !in languages) {
            language = if ("en" in languages) "en" else languages.firstOrNull() ?: ""
        }
    }
    // Highest quality opus per language (sorted desc bitrate in VM), so first is best.
    val audioStreamOptions = remember(audioStreams, language) {
        audioStreams.filter { it.language == language }
    }

    val controllerState = remember { mutableStateOf<MediaController?>(null) }
    var controller by controllerState
    val currentVideoStreamState = remember(videoStreams) { mutableStateOf(videoStreams.first()) }
    var currentVideoStream by currentVideoStreamState
    // Always highest quality audio for selected language (user request: remove bitrate chooser)
    val currentAudioStreamState = remember(audioStreamOptions) { mutableStateOf(audioStreamOptions.maxByOrNull { it.bitrate }) }
    var currentAudioStream by currentAudioStreamState
    // Ensure currentAudioStream stays consistent with language filter = best quality
    LaunchedEffect(language) {
        val filtered = audioStreams.filter { it.language == language }
        currentAudioStream = filtered.maxByOrNull { it.bitrate } ?: filtered.firstOrNull()
    }

    // ---- transport state, which lives in CastPlayback rather than here ----
    // All of this used to be `remember`ed locals, which was right while the only reader was the seek bar
    // drawn beside them. A television showing this video is a second reader with a different lifetime -
    // it needs to know where playback is in order to draw a bar of its own - and a `remember` dies on
    // navigation while a cast session does not. The 300 ms loop below is still the only writer of the
    // position, so `dragging` remains a sufficient guard against two seeks fighting.
    val transport by CastPlayback.transport.collectAsState()
    val currentPosition = transport.positionMs
    val bufferedPosition = transport.bufferedMs
    val duration = transport.durationMs
    val isPlaying = transport.playing
    val isBuffering = transport.buffering
    val isDragging = transport.dragging
    val playbackSpeed = transport.speed

    // Position and duration for *this* video's history entry, kept per screen rather than read back out
    // of the shared transport state. A video-to-video navigation composes both screens at once, and
    // both see the same singleton - so a shared read would persist the incoming video's progress
    // against the outgoing video's id and lose the user's place in it.
    val historyPositionState = remember(videoInfo.videoID) { mutableLongStateOf(0L) }
    var historyPosition by historyPositionState
    val historyDurationState = remember(videoInfo.videoID) { mutableLongStateOf(0L) }
    var historyDuration by historyDurationState

    // Tempo (playback speed) and pitch, applied via PlaybackParameters. When pitch is "hooked"
    // to tempo it tracks it 1:1 (natural resample); unhooking lets the user set it independently.
    // The tempo itself is in `transport` above, because the TV shows it and can change it.
    val playbackPitchState = remember { mutableFloatStateOf(1f) }
    var playbackPitch by playbackPitchState
    val unhookPitchState = remember { mutableStateOf(false) }
    var unhookPitch by unhookPitchState

    // Hooked pitch follows tempo from *any* source, which is why it is an effect rather than a line in
    // the slider's callback: the television can change the tempo too, and a pitch left behind would
    // silently unhook itself without the checkbox moving.
    LaunchedEffect(playbackSpeed, unhookPitch) {
        if (!unhookPitch) playbackPitch = playbackSpeed
    }

    // **Seeded once, then written back one way only.** Following the stored value would mean an echo of
    // an earlier write arriving mid-drag and snapping the slider out from under the thumb, because
    // DataStore answers asynchronously and this cannot tell an echo from a fresh value. `drop(1)` is
    // what waits for storage rather than for the flow's own 1x placeholder; if nothing is stored it
    // never arrives and 1x stands, which is the right answer anyway.
    LaunchedEffect(Unit) {
        val stored = ypvm.playbackSpeed.drop(1).first()
        CastPlayback.update { it.copy(speed = stored) }
    }
    LaunchedEffect(playbackSpeed) {
        ypvm.setPlaybackSpeed(playbackSpeed)
    }

    val selectedSubtitleState = remember { mutableStateOf<SubtitleTrack?>(null) }
    var selectedSubtitle by selectedSubtitleState
    val cuesState = remember { mutableStateOf<List<Cue>>(emptyList()) }
    var cues by cuesState

    // Track if we are in PiP to avoid stopping playback on dispose when PiP is active or
    // when PiP is closed and we want to keep audio-only.
    val isPipModeForLifecycle = rememberIsInPipMode()
    val wasInPipModeState = remember { mutableStateOf(false) }
    var wasInPipMode by wasInPipModeState
    LaunchedEffect(isPipModeForLifecycle) {
        if (isPipModeForLifecycle) wasInPipMode = true
    }

    DisposableEffect(Unit) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture.addListener({
            controller = controllerFuture.get()
        }, MoreExecutors.directExecutor())

        onDispose {
            // If we were ever in PiP (including PiP closed -> background audio), don't stop the
            // ExoPlayer inside the service. Just release the controller connection. The service
            // will itself switch to audio-only when PiP is dismissed to save bandwidth.
            val activity = try { context.findActivity() } catch (_: Exception) { null }
            val keepPlaying = wasInPipMode || activity?.isInPictureInPictureMode == true
            if (keepPlaying) {
                controller?.let {
                    // Disable video track to stop streaming video (audio-only background)
                    try {
                        it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                            .build()
                    } catch (_: Exception) {}
                    it.release()
                }
            } else {
                controller?.let {
                    it.stop()
                    it.release()
                }
            }
            MediaController.releaseFuture(controllerFuture)
        }
    }

    val aspectRatioState = remember { mutableFloatStateOf(16f / 9f) }
    var aspectRatio by aspectRatioState

    DisposableEffect(controller) {
        val player = controller ?: return@DisposableEffect onDispose {}
        // Sync initial playing state once controller is available
        CastPlayback.update { it.copy(playing = player.isPlaying) }
        // What a remote command is applied to, so the TV drives the same transport these buttons do.
        CastPlayback.attachPlayer(player)
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    CastPlayback.update {
                        it.copy(
                            durationMs = player.duration.coerceAtLeast(0L),
                            buffering = player.playbackState == Player.STATE_BUFFERING,
                            playing = player.isPlaying,
                        )
                    }
                }
            }
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                CastPlayback.update { it.copy(playing = isPlayingNow) }
                context.findActivity().setPictureInPictureParams(PictureInPictureParams.Builder().apply {
                    setAutoEnterEnabled(isPlayingNow)
                }.build())
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width == 0 || videoSize.height == 0) return
                aspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat() * videoSize.pixelWidthHeightRatio
            }
            override fun onCues(cueGroup: CueGroup) {
                android.util.Log.d("YouPipeSubs", "onCues called with ${cueGroup.cues.size} cues")
                cues = cueGroup.cues
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            CastPlayback.detachPlayer(player)
            context.findActivity().setPictureInPictureParams(PictureInPictureParams.Builder().apply {
                setAutoEnterEnabled(false)
            }.build())
        }
    }

    LaunchedEffect(controller, isDragging) {
        val player = controller ?: return@LaunchedEffect
        var lastHistoryUpsert = 0L
        while (true) {
            if (!isDragging) {
                var position = player.currentPosition.coerceAtLeast(0L)

                if (sponsorBlockEnabled) {
                    val currentSegment = sponsorSegments.find { position in it.start until it.end }
                    if (currentSegment != null) {
                        player.seekTo(currentSegment.end)
                        position = currentSegment.end
                    }
                }
                CastPlayback.update {
                    it.copy(
                        positionMs = position,
                        bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
                    )
                }
                historyPosition = position
                historyDuration = player.duration.coerceAtLeast(0L)

                if (player.isPlaying) {
                    val now = System.currentTimeMillis()
                    if (now - lastHistoryUpsert >= HISTORY_UPSERT_INTERVAL_MS) {
                        ypvm.upsertHistoryVideo(HistoryVideo.fromVideoData(videoInfo.copy(duration = historyDuration), position))
                        lastHistoryUpsert = now
                    }
                }
            }
            delay(300)
        }
    }

    // Persist the final watch position once when leaving the player.
    DisposableEffect(videoInfo.videoID) {
        onDispose {
            ypvm.upsertHistoryVideo(
                HistoryVideo.fromVideoData(videoInfo.copy(duration = historyDuration), historyPosition),
            )
        }
    }

    val historyFlow = remember(videoInfo.videoID) { ypvm.historyById(videoInfo.videoID) }
    val historyVideo by historyFlow.collectAsState(initial = null)
    val timeWatched = historyVideo?.progress ?: 0

    // 3. Updated LaunchedEffect to pass audio URI through Metadata Extras
    LaunchedEffect(controller, currentVideoStream, currentAudioStream, videoInfo.name, videoInfo.author, subtitles) {
        val player = controller ?: return@LaunchedEffect

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(videoInfo.name)
            .setArtist(videoInfo.author)

        currentAudioStream?.let { audio ->
            val extras = Bundle().apply {
                putString("extra_audio_uri", audio.url)
                putString("extra_audio_track_id", audio.audioTrackId)
                // Parse itag from sabr:// URL for direct selection
                try {
                    val aUri = audio.url.toUri()
                    aUri.getQueryParameter("a")?.toIntOrNull()?.let { putInt("extra_audio_itag", it) }
                } catch (_: Exception) {}
            }
            metadataBuilder.setExtras(extras)
        }

        android.util.Log.d("YouPipeSubs", "Building ${subtitles.size} subtitle configs")
        val subtitleConfigs = subtitles.map { sub ->
            android.util.Log.d("YouPipeSubs", "Sub: lang=${sub.languageTag} mime=${sub.mimeType} url=${sub.url} auto=${sub.autoGenerated}")
            MediaItem.SubtitleConfiguration.Builder(sub.url.toUri())
                .setMimeType(sub.mimeType)
                .setLanguage(sub.languageTag)
                .setRoleFlags(if (sub.autoGenerated) C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND else C.ROLE_FLAG_CAPTION)
                .build()
        }

        val mediaItem = MediaItem.Builder()
            .setUri(currentVideoStream.url)
            .setSubtitleConfigurations(subtitleConfigs)
            .setMediaMetadata(metadataBuilder.build())
            .build()

        player.setMediaItem(mediaItem, timeWatched)
        player.prepare()
        CastPlayback.update { it.copy(positionMs = timeWatched) }
    }

    LaunchedEffect(controller, selectedSubtitle) {
        val player = controller ?: return@LaunchedEffect
        android.util.Log.d("YouPipeSubs", "Selected subtitle changed: ${selectedSubtitle?.languageTag} url=${selectedSubtitle?.url}")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            val sub = selectedSubtitle
            if (sub == null) {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setPreferredTextLanguage(sub.languageTag)
            }
        }.build()
    }

    // Apply tempo/pitch whenever they change (or the controller connects).
    LaunchedEffect(controller, playbackSpeed, playbackPitch) {
        controller?.playbackParameters = PlaybackParameters(playbackSpeed, playbackPitch)
    }

    return VideoPlayerPlaybackState(
        languageState = languageState,
        controllerState = controllerState,
        currentVideoStreamState = currentVideoStreamState,
        currentAudioStreamState = currentAudioStreamState,
        historyPositionState = historyPositionState,
        historyDurationState = historyDurationState,
        playbackPitchState = playbackPitchState,
        unhookPitchState = unhookPitchState,
        selectedSubtitleState = selectedSubtitleState,
        cuesState = cuesState,
        aspectRatioState = aspectRatioState,
        languageEntries = languageEntries,
        languages = languages,
        audioStreamOptions = audioStreamOptions,
        currentPosition = currentPosition,
        bufferedPosition = bufferedPosition,
        duration = duration,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        isDragging = isDragging,
        playbackSpeed = playbackSpeed,
        timeWatched = timeWatched,
    )
}

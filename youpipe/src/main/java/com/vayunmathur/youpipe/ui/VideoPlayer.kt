package com.vayunmathur.youpipe.ui

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.os.Bundle
import android.text.format.DateUtils
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconFullscreen
import com.vayunmathur.library.ui.IconFullscreenExit
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.SliderDefaults
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectTapGestures
import com.vayunmathur.library.ui.CircularProgressIndicator
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.google.common.util.concurrent.MoreExecutors
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.findActivity
import com.vayunmathur.youpipe.rememberIsInPipMode
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.util.PlaybackService
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconCast
import com.vayunmathur.library.ui.IconCastConnected
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.sdk.cast.CastClient
import com.vayunmathur.sdk.cast.CastContract
import com.vayunmathur.sdk.cast.CastPickerContract

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    ypvm: YouPipeViewModel,
    videoInfo: VideoInfo,
    videoStreams: List<VideoStream>,
    audioStreams: List<AudioStream>,
    subtitles: List<SubtitleTrack>,
    segments: List<VideoChapter>,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
) {
    if (videoStreams.isEmpty()) return

    // State lives in VideoPlayerPlaybackState.kt (playback) + VideoPlayerViewState.kt (view);
    // the UI below reads both through `with`, so no call site changes.
    val pb = rememberVideoPlayerPlaybackState(ypvm, videoInfo, videoStreams, audioStreams, subtitles)
    val vs = rememberVideoPlayerViewState(
        ypvm, videoInfo, pb.currentVideoStream, pb.aspectRatio, pb.controller, pb.isPlaying,
        pb.isDragging, pb.playbackSpeed,
    )
    with(pb) {
    with(vs) {
    val context = LocalContext.current
    val sponsorBlockEnabled by ypvm.sponsorBlockEnabled.collectAsState()
    val sponsorBlockCategories by ypvm.sponsorBlockCategories.collectAsState()
    val videoState by ypvm.videoState.collectAsState()
    val sponsorSegments = videoState.sponsorSegments.filter { it.category in sponsorBlockCategories }

    val hasAudio = audioStreams.isNotEmpty()
    // Use displayName for language UI when available (audioTrackName like "English", "Original"), fallback to language code
    val languageEntries = remember(audioStreams) {
        audioStreams.map { it.language to (it.displayName ?: it.language) }
            .distinctBy { it.first }
            .sortedBy { it.first }
    }
    val languages = languageEntries.map { it.first }
    var language by remember(audioStreams) { mutableStateOf(if("en" in languages) "en" else languages.firstOrNull() ?: "") }
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

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var currentVideoStream by remember(videoStreams) { mutableStateOf(videoStreams.first()) }
    // Always highest quality audio for selected language (user request: remove bitrate chooser)
    var currentAudioStream by remember(audioStreamOptions) { mutableStateOf(audioStreamOptions.maxByOrNull { it.bitrate }) }
    // Ensure currentAudioStream stays consistent with language filter = best quality
    LaunchedEffect(language) {
        val filtered = audioStreams.filter { it.language == language }
        currentAudioStream = filtered.maxByOrNull { it.bitrate } ?: filtered.firstOrNull()
    }
    var isControlsVisible by remember { mutableStateOf(true) }
    var controlsInteractionTick by remember { mutableStateOf(0) }
    val keepControlsVisible by ypvm.keepPlayerControlsVisible.collectAsState()

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
    var historyPosition by remember(videoInfo.videoID) { mutableLongStateOf(0L) }
    var historyDuration by remember(videoInfo.videoID) { mutableLongStateOf(0L) }

    var isVideoMenuExpanded by remember { mutableStateOf(false) }
    var isLanguageMenuExpanded by remember { mutableStateOf(false) }
    var isChapterMenuVisible by remember { mutableStateOf(false) }
    var isCaptionMenuExpanded by remember { mutableStateOf(false) }
    var isSpeedMenuExpanded by remember { mutableStateOf(false) }

    // Tempo (playback speed) and pitch, applied via PlaybackParameters. When pitch is "hooked"
    // to tempo it tracks it 1:1 (natural resample); unhooking lets the user set it independently.
    // The tempo itself is in `transport` above, because the TV shows it and can change it.
    var playbackPitch by remember { mutableFloatStateOf(1f) }
    var unhookPitch by remember { mutableStateOf(false) }

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

    // Lock hides all controls (and disables seek/speed gestures) so a fullscreen video can't be
    // disturbed by accidental touches, until the user taps the on-screen unlock button.
    var isLocked by remember { mutableStateOf(false) }

    // Desktop: hovering the player with a mouse keeps the controls up the way a finger
    // tap does on touch — the auto-hide timer below stands down while hovered.
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()

    var selectedSubtitle by remember { mutableStateOf<SubtitleTrack?>(null) }
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }

    // Track if we are in PiP to avoid stopping playback on dispose when PiP is active or
    // when PiP is closed and we want to keep audio-only.
    val isPipModeForLifecycle = rememberIsInPipMode()
    var wasInPipMode by remember { mutableStateOf(false) }
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

    var aspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    val activity = context.findActivity()
    DisposableEffect(Unit) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

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
                if(videoSize.width == 0 || videoSize.height == 0) return
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

    val isPipMode = rememberIsInPipMode()
    val scope = rememberCoroutineScope()

    // ---- casting ----
    // Cast state lives beside the transport state because that is where VideoPlayer keeps everything
    // else about this one player - but in a singleton rather than a `remember`, since the PCM half of
    // the session is produced inside PlaybackService's audio sink, which has no reference to any of
    // this.
    val castState by CastPlayback.state.collectAsState()
    val isCasting = castState is CastPlayback.State.Casting
    // Resolved here rather than inside the callbacks: reading resources off a Context in a composable
    // misses configuration changes, and lint says so.
    val castFailedMessage = stringResource(R.string.cast_failed)
    val castInstallMessage = stringResource(R.string.cast_install_prompt)
    val castUpdateMessage = stringResource(R.string.cast_update_prompt)
    val castPicker = rememberLauncherForActivityResult(CastPickerContract()) { picked ->
        if (!picked) {
            // Backed out, or pairing failed. Nothing should change in the player.
            CastPlayback.close()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val (requestedWidth, requestedHeight) = castRequestSize(currentVideoStream, aspectRatio)
            if (CastPlayback.open(context, requestedWidth, requestedHeight) != null) {
                AppMessages.show(castFailedMessage)
            }
        }
    }
    // Moving playback is one call. ExoPlayer keeps decoding and keeps its transport controls, so play,
    // pause and seek still work and still control the remote picture; only the render target changes.
    // Local audio is muted with volume rather than by stopping the renderer, because volume is applied
    // in the sink *after* the processor chain - so CastAudioTap still sees full-scale PCM.
    LaunchedEffect(controller, castState) {
        val player = controller ?: return@LaunchedEffect
        val casting = castState as? CastPlayback.State.Casting
        if (casting != null) {
            android.util.Log.i(
                "YpCastDiag",
                "handing the cast surface to the player: valid=${casting.surface.isValid} " +
                    "${casting.width}x${casting.height}",
            )
            player.setVideoSurface(casting.surface)
            player.volume = 0f
        } else {
            player.volume = 1f
        }
    }
    // TEMPORARY DIAGNOSTICS. Answers the one thing the logs cannot: whether the player ever draws a
    // frame into the encoder's input surface. Remove once the no-video-on-cast fault is understood.
    DisposableEffect(controller, isCasting) {
        val player = controller
        if (player == null || !isCasting) return@DisposableEffect onDispose { }
        android.util.Log.i(
            "YpCastDiag",
            "attached; videoSize=${player.videoSize.width}x${player.videoSize.height} " +
                "playing=${player.isPlaying} state=${player.playbackState}",
        )
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() {
                android.util.Log.i("YpCastDiag", "onRenderedFirstFrame")
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                android.util.Log.i(
                    "YpCastDiag",
                    "onVideoSizeChanged ${videoSize.width}x${videoSize.height}",
                )
            }

            override fun onSurfaceSizeChanged(width: Int, height: Int) {
                android.util.Log.i("YpCastDiag", "onSurfaceSizeChanged ${width}x$height")
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val video = tracks.groups.count {
                    it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO
                }
                val selected = tracks.groups.count {
                    it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.isSelected
                }
                android.util.Log.i(
                    "YpCastDiag",
                    "onTracksChanged videoGroups=$video selectedVideoGroups=$selected",
                )
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // What the television puts where "Receiving from YouPipe" used to be. Keyed on the video as well
    // as the session, because a `Next` from the remote reloads this player in place rather than
    // advancing a queue - so the title would otherwise describe the previous video for the rest of the
    // cast. Sending it again is free: it is an absolute snapshot that replaces wholesale.
    LaunchedEffect(castState, videoInfo.name, videoInfo.author) {
        if (castState is CastPlayback.State.Casting) {
            CastPlayback.setNowPlaying(videoInfo.name, videoInfo.author)
        }
    }
    // No DisposableEffect closing the cast here any more. Leaving a video used to end the session, on
    // the reasoning that there is one video output and it cannot follow the user to another screen -
    // which also meant a "next" from the television dropped the cast. CastPlayback now holds the
    // session briefly when nothing is drawing into it, so a replacement player takes it over and only
    // genuinely walking away ends it.

    // When paused, keep controls visible (no auto-hide). When playing resumes, re-show and start timer.
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            isControlsVisible = true
            controlsInteractionTick++
        } else {
            isControlsVisible = true
        }
    }

    // Auto-hide controls after 2 seconds of inactivity while playing. Resets on any interaction.
    LaunchedEffect(
        isControlsVisible, isPlaying, keepControlsVisible, isLocked, isDragging,
        isVideoMenuExpanded, isLanguageMenuExpanded, isCaptionMenuExpanded,
        isSpeedMenuExpanded, isChapterMenuVisible, isPipMode, controlsInteractionTick,
        isCasting, isHovered
    ) {
        if (!isControlsVisible) return@LaunchedEffect
        if (keepControlsVisible) return@LaunchedEffect
        if (isLocked) return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        if (isDragging) return@LaunchedEffect
        if (isVideoMenuExpanded || isLanguageMenuExpanded || isCaptionMenuExpanded || isSpeedMenuExpanded || isChapterMenuVisible) return@LaunchedEffect
        if (isPipMode) return@LaunchedEffect
        // While casting there is no picture here to get out of the way of, and the only way to stop is
        // a button in these controls.
        if (isCasting) return@LaunchedEffect
        // A hovering mouse is an engaged viewer: keep the controls up while it is over the player.
        if (isHovered) return@LaunchedEffect
        delay(CONTROLS_AUTO_HIDE_DELAY_MS)
        isControlsVisible = false
    }

    // Accessibility toggle: when "keep controls visible" is enabled, force controls on.
    LaunchedEffect(keepControlsVisible) {
        if (keepControlsVisible) {
            isControlsVisible = true
        } else {
            // Re-arm timer when re-enabling auto-hide while playing.
            if (isPlaying && isControlsVisible) controlsInteractionTick++
        }
    }

    val modifier = if(isFullscreen) Modifier.fillMaxHeight() else Modifier.aspectRatio(aspectRatio)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .hoverable(hoverSource)
            .pointerInput(isLocked, keepControlsVisible) {
                detectTapGestures(
                    onTap = {
                        if (isLocked) {
                            isControlsVisible = true
                            controlsInteractionTick++
                            return@detectTapGestures
                        }
                        if (isControlsVisible) {
                            // When auto-hide is disabled for accessibility, tapping keeps controls visible and resets timer.
                            if (keepControlsVisible) {
                                controlsInteractionTick++
                            } else {
                                isControlsVisible = false
                            }
                        } else {
                            isControlsVisible = true
                            controlsInteractionTick++
                        }
                    },
                    onDoubleTap = { offset ->
                        if (isLocked) return@detectTapGestures
                        // Any gesture re-shows controls and resets auto-hide timer.
                        isControlsVisible = true
                        controlsInteractionTick++
                        val isRightSide = offset.x > size.width / 2
                        if (isRightSide) {
                            controller?.seekTo(currentPosition + 10000L)
                        } else {
                            controller?.seekTo(currentPosition - 10000L)
                        }
                    },
                    onPress = {
                        if (isLocked) return@detectTapGestures
                        val job = scope.launch {
                            delay(viewConfiguration.longPressTimeoutMillis)
                            controller?.setPlaybackSpeed(2f)
                        }
                        try {
                            awaitRelease()
                        } finally {
                            job.cancel()
                            // Restore the user's chosen tempo/pitch, not a hardcoded 1x.
                            controller?.playbackParameters = PlaybackParameters(playbackSpeed, playbackPitch)
                        }
                    }
                )
            }
    ) {
        val playerModifier = if (aspectRatio > 16f/9f) {
            // Video is wider than container -> match width, height will follow ratio
            Modifier.fillMaxWidth().aspectRatio(aspectRatio)
        } else {
            // Video is taller than container -> match height, width will follow ratio
            Modifier.fillMaxHeight().aspectRatio(aspectRatio)
        }
        controller?.let { player ->
            // While casting there is no local picture: there is one video output and it is the TV, which
            // is also what the user expects to see when they have just cast something.
            val casting = castState as? CastPlayback.State.Casting
            if (casting != null) {
                CastingPanel(
                    receiverName = casting.receiverName,
                    onStop = { CastPlayback.close() },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PlayerSurface(
                    player = player,
                    modifier = playerModifier.align(Alignment.Center),
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW
                )
            }
        } ?: Box(modifier = Modifier.fillMaxSize().background(Color.Black))

        if (cues.isNotEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                cues.forEach { cue ->
                    cue.text?.let { text ->
                        Text(
                            text = text.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        FadeVisibility(visible = isControlsVisible && !isPipMode && !isLocked) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {

                Row(
                    modifier = Modifier.padding(16.dp).align(Alignment.TopEnd),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VideoPlayerTopControls(
                        hasAudio = hasAudio,
                        languages = languages,
                        languageEntries = languageEntries,
                        language = language,
                        onLanguageChange = { language = it },
                        isVideoMenuExpanded = isVideoMenuExpanded,
                        onVideoMenuExpandedChange = { isVideoMenuExpanded = it },
                        isLanguageMenuExpanded = isLanguageMenuExpanded,
                        onLanguageMenuExpandedChange = { isLanguageMenuExpanded = it },
                        isCaptionMenuExpanded = isCaptionMenuExpanded,
                        onCaptionMenuExpandedChange = { isCaptionMenuExpanded = it },
                        isSpeedMenuExpanded = isSpeedMenuExpanded,
                        onSpeedMenuExpandedChange = { isSpeedMenuExpanded = it },
                        currentVideoStream = currentVideoStream,
                        onVideoStreamChange = { currentVideoStream = it },
                        videoStreams = videoStreams,
                        subtitles = subtitles,
                        selectedSubtitle = selectedSubtitle,
                        onSubtitleChange = { selectedSubtitle = it },
                        segments = segments,
                        onChapterMenuVisibleChange = { isChapterMenuVisible = it },
                        playbackSpeed = playbackSpeed,
                        onSpeedChange = { CastPlayback.update { state -> state.copy(speed = it) } },
                        playbackPitch = playbackPitch,
                        onPitchChange = { playbackPitch = it },
                        unhookPitch = unhookPitch,
                        onUnhookPitchChange = {
                            unhookPitch = it
                            if (!it) playbackPitch = playbackSpeed
                        },
                        isCasting = isCasting,
                        onCastClick = {
                            controlsInteractionTick++
                            when (CastPlayback.support(context)) {
                                CastClient.Support.NOT_INSTALLED -> {
                                    AppMessages.show(castInstallMessage)
                                    ExternalIntents.openAppListing(context, CastContract.CAST_PACKAGE)
                                }
                                CastClient.Support.NEEDS_UPDATE -> {
                                    AppMessages.show(castUpdateMessage)
                                    ExternalIntents.openAppListing(context, CastContract.CAST_PACKAGE)
                                }
                                CastClient.Support.READY ->
                                    if (isCasting) {
                                        CastPlayback.close()
                                    } else {
                                        CastPlayback.markConnecting()
                                        castPicker.launch(Unit)
                                    }
                            }
                        },
                    )
                }

                controller?.let { player ->
                    PlayPauseButton(
                        player = player,
                        modifier = Modifier.size(64.dp).align(Alignment.Center),
                    )
                }

                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp).align(Alignment.Center),
                        color = Color.White
                    )
                }
                VideoPlayerSeekRow(
                    currentPosition = currentPosition,
                    bufferedPosition = bufferedPosition,
                    duration = duration,
                    sponsorSegments = sponsorSegments,
                    onSeekChange = { position ->
                        CastPlayback.update { state ->
                            state.copy(dragging = true, positionMs = position)
                        }
                    },
                    onSeekFinished = {
                        controller?.seekTo(currentPosition)
                        CastPlayback.update { it.copy(dragging = false) }
                    },
                    isFullscreen = isFullscreen,
                    onLock = {
                        isLocked = true
                        isControlsVisible = false
                    },
                    onFullscreenChange = onFullscreenChange,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }

        FadeVisibility(visible = isLocked && isControlsVisible && !isPipMode) {
            Box(modifier = Modifier.fillMaxSize()) {
                IconButton(
                    onClick = {
                        isLocked = false
                        isControlsVisible = true
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .size(56.dp)
                ) {
                    IconLock(tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }

        FadeVisibility(visible = isChapterMenuVisible && !isPipMode) {
            VideoPlayerChapterSheet(
                segments = segments,
                currentPosition = currentPosition,
                onSeekTo = { controller?.seekTo(it) },
                onDismiss = { isChapterMenuVisible = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Cast-request sizing, the casting panel and tempo formatting live in VideoPlayerCasting.kt. */

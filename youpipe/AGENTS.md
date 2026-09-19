# AGENTS.md — youpipe/ (':youpipe')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `youpipe/` + allowed shared modules. Do not root-scan.

_Install: ./install youpipe (dev by default)._

- Gradle: :youpipe / dir: youpipe/
- Package roots present: ui, data, platform
- Entry files: MainActivity.kt, YouPipeApplication.kt
- Deps: :library:image, :youpipe:extractor, :sdk:cast, :library:room, :library:work, :library:network
- Metadata: metadata_data/youpipe.md (present)
- Rust: yes / screenshotTest: yes

## Key files

- com/vayunmathur/youpipe/MainActivity.kt
- com/vayunmathur/youpipe/YouPipeApplication.kt
- com/vayunmathur/youpipe/data/CachedRelatedVideo.kt
- com/vayunmathur/youpipe/data/ChannelPreference.kt
- com/vayunmathur/youpipe/data/DownloadedVideo.kt
- com/vayunmathur/youpipe/data/HistoryVideo.kt
- com/vayunmathur/youpipe/data/KeywordPreference.kt
- com/vayunmathur/youpipe/data/Playlist.kt
- com/vayunmathur/youpipe/data/RecommendationImpression.kt
- com/vayunmathur/youpipe/data/RecommendationPreferences.kt
- com/vayunmathur/youpipe/data/Subscription.kt
- com/vayunmathur/youpipe/data/SubscriptionCategory.kt
- com/vayunmathur/youpipe/data/SubscriptionDatabase.kt
- com/vayunmathur/youpipe/data/SubscriptionRepository.kt
- com/vayunmathur/youpipe/data/SubscriptionVideo.kt
- com/vayunmathur/youpipe/nativeext/NativeExtractor.kt
- com/vayunmathur/youpipe/nativeext/YouPipeNative.kt
- com/vayunmathur/youpipe/platform/CastPlayback.kt
- com/vayunmathur/youpipe/ui/ChannelPage.kt
- com/vayunmathur/youpipe/ui/DataSettingsPage.kt
- com/vayunmathur/youpipe/ui/DownloadedVideosPage.kt
- com/vayunmathur/youpipe/ui/FeedGrid.kt
- com/vayunmathur/youpipe/ui/GeneralSettingsPage.kt
- com/vayunmathur/youpipe/ui/HistoryPage.kt
- com/vayunmathur/youpipe/ui/LinkifiedText.kt
- com/vayunmathur/youpipe/ui/ManageInterestsPage.kt
- com/vayunmathur/youpipe/ui/PlaylistDetailPage.kt
- com/vayunmathur/youpipe/ui/RecommendationsSettingsPage.kt
- com/vayunmathur/youpipe/ui/SavedPage.kt
- com/vayunmathur/youpipe/ui/SearchPage.kt
- com/vayunmathur/youpipe/ui/SettingsPage.kt
- com/vayunmathur/youpipe/ui/SponsorBlockSettingsPage.kt
- com/vayunmathur/youpipe/ui/SubscriptionsPage.kt
- com/vayunmathur/youpipe/ui/SubscriptionVideosPage.kt
- com/vayunmathur/youpipe/ui/VideoDetails.kt
- com/vayunmathur/youpipe/ui/VideoDetailScreen.kt
- com/vayunmathur/youpipe/ui/VideoFormatting.kt
- com/vayunmathur/youpipe/ui/VideoPage.kt
- com/vayunmathur/youpipe/ui/VideoPlayer.kt
- com/vayunmathur/youpipe/ui/VideoPlayerCasting.kt
- com/vayunmathur/youpipe/ui/VideoPlayerChapterSheet.kt
- com/vayunmathur/youpipe/ui/VideoPlayerPlaybackEffects.kt
- com/vayunmathur/youpipe/ui/VideoPlayerPlaybackState.kt
- com/vayunmathur/youpipe/ui/VideoPlayerSeekRow.kt
- com/vayunmathur/youpipe/ui/VideoPlayerTopControls.kt
- com/vayunmathur/youpipe/ui/VideoPlayerViewState.kt
- com/vayunmathur/youpipe/ui/VideoRow.kt
- com/vayunmathur/youpipe/ui/VideoSections.kt
- com/vayunmathur/youpipe/ui/dialogs/AddToPlaylist.kt
- com/vayunmathur/youpipe/ui/dialogs/AddToWatchLater.kt
- com/vayunmathur/youpipe/ui/dialogs/CreatePlaylist.kt
- com/vayunmathur/youpipe/ui/dialogs/CreateSubscriptionCategory.kt
- com/vayunmathur/youpipe/util/DownloadManager.kt
- com/vayunmathur/youpipe/util/DownloadWorker.kt
- com/vayunmathur/youpipe/util/Extractor.kt
- com/vayunmathur/youpipe/util/MyDownloader.kt
- com/vayunmathur/youpipe/util/PlaybackService.kt
- com/vayunmathur/youpipe/util/Recommender.kt
- com/vayunmathur/youpipe/util/SubscriptionFetchTask.kt
- com/vayunmathur/youpipe/util/YouPipeImportExport.kt
- com/vayunmathur/youpipe/util/YouPipeRecommendations.kt
- com/vayunmathur/youpipe/util/YouPipeUiContract.kt
- com/vayunmathur/youpipe/util/YouPipeVideoLoading.kt
- com/vayunmathur/youpipe/util/YouPipeViewModel.kt
- com/vayunmathur/youpipe/util/sabr/LocalDomPoTokenGenerator.kt
- com/vayunmathur/youpipe/util/sabr/LocalDomPoTokenProvider.kt
- com/vayunmathur/youpipe/util/sabr/SabrAttestationRetryHandler.kt
- com/vayunmathur/youpipe/util/sabr/SabrDownloadException.kt
- com/vayunmathur/youpipe/util/sabr/SabrFfmpegMuxer.kt
- com/vayunmathur/youpipe/util/sabr/SabrLocalDomPoTokenUtil.kt
- com/vayunmathur/youpipe/util/sabr/SabrNgDashMediaSource.kt
- com/vayunmathur/youpipe/util/sabr/SabrNgDownloadHelper.kt
- com/vayunmathur/youpipe/util/sabr/SabrNgSegmentDataSource.kt
- com/vayunmathur/youpipe/util/sabr/SabrNgSession.kt
- com/vayunmathur/youpipe/util/sabr/SabrNgSessionStore.kt
- com/vayunmathur/youpipe/util/sabr/SabrNgSourceSpec.kt
- com/vayunmathur/youpipe/util/sabr/SabrRequestCoordinator.kt
- com/vayunmathur/youpipe/util/sabr/SharedWebViewRuntime.kt
- com/vayunmathur/youpipe/util/sabr/YoutubePageAttestationBootstrap.kt

## Verify (this module only)
```
./gradlew :youpipe:compileDevKotlin
./gradlew :youpipe:lint
./gradlew :youpipe:checkMetadata
```



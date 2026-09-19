# AGENTS.md — music/ (':music')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `music/` + allowed shared modules. Do not root-scan.

_Install: ./install music (dev by default)._

- Gradle: :music / dir: music/
- Package roots present: ui, data, platform, intents, service
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:room, :library:image, :sdk:cast, :library:media
- Metadata: metadata_data/music.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/music/MainActivity.kt
- com/vayunmathur/music/Navigation.kt
- com/vayunmathur/music/Route.kt
- com/vayunmathur/music/data/Album.kt
- com/vayunmathur/music/data/Artist.kt
- com/vayunmathur/music/data/Music.kt
- com/vayunmathur/music/data/MusicDatabase.kt
- com/vayunmathur/music/data/MusicRepository.kt
- com/vayunmathur/music/data/Playlist.kt
- com/vayunmathur/music/intents/AudioPreviewActivity.kt
- com/vayunmathur/music/intents/PlayIntent.kt
- com/vayunmathur/music/intents/SearchIntent.kt
- com/vayunmathur/music/platform/CastPlayback.kt
- com/vayunmathur/music/platform/EmbeddedLyrics.kt
- com/vayunmathur/music/platform/Lyrics.kt
- com/vayunmathur/music/platform/MusicUiContract.kt
- com/vayunmathur/music/platform/MusicViewModel.kt
- com/vayunmathur/music/platform/PlaybackManager.kt
- com/vayunmathur/music/platform/SyncWorker.kt
- com/vayunmathur/music/platform/Util.kt
- com/vayunmathur/music/service/CastingPlayer.kt
- com/vayunmathur/music/service/CastQueue.kt
- com/vayunmathur/music/service/MusicLibraryTree.kt
- com/vayunmathur/music/service/PlaybackService.kt
- com/vayunmathur/music/ui/AlbumDetailContent.kt
- com/vayunmathur/music/ui/AlbumDetailScreen.kt
- com/vayunmathur/music/ui/AlbumScreen.kt
- com/vayunmathur/music/ui/ArtistDetailScreen.kt
- com/vayunmathur/music/ui/ArtistScreen.kt
- com/vayunmathur/music/ui/HomeScreen.kt
- com/vayunmathur/music/ui/LyricsView.kt
- com/vayunmathur/music/ui/MusicTabsScreen.kt
- com/vayunmathur/music/ui/NowPlayingColumn.kt
- com/vayunmathur/music/ui/NowPlayingQueue.kt
- com/vayunmathur/music/ui/NowPlayingScreen.kt
- com/vayunmathur/music/ui/NowPlayingWideLayout.kt
- com/vayunmathur/music/ui/PlaylistDetailScreen.kt
- com/vayunmathur/music/ui/PlaylistScreen.kt
- com/vayunmathur/music/ui/SongScreen.kt
- com/vayunmathur/music/ui/SongsScreen.kt
- com/vayunmathur/music/ui/components/AddToPlaylistButton.kt
- com/vayunmathur/music/ui/components/MusicTabsBar.kt
- com/vayunmathur/music/ui/components/NewPlaylistFab.kt
- com/vayunmathur/music/ui/components/NowPlayingBar.kt
- com/vayunmathur/music/ui/components/PlayingBottomBar.kt
- com/vayunmathur/music/ui/components/PlayShuffleRow.kt
- com/vayunmathur/music/ui/components/ShufflePlayFab.kt
- com/vayunmathur/music/ui/components/TrackListItem.kt
- com/vayunmathur/music/ui/dialogs/AddToPlaylistDialog.kt
- com/vayunmathur/music/ui/dialogs/CreatePlaylistDialog.kt

## Verify (this module only)
```
./gradlew :music:compileDevKotlin
./gradlew :music:lint
./gradlew :music:checkMetadata
```



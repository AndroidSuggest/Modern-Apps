# AGENTS.md — musicbrainz/ (':musicbrainz')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `musicbrainz/` + allowed shared modules. Do not root-scan.

_Install: ./install musicbrainz (dev by default)._

- Gradle: :musicbrainz / dir: musicbrainz/
- Package roots present: ui, data, domain, platform, network
- Entry files: MainActivity.kt, Route.kt, Navigation.kt, MusicBrainzApplication.kt
- Deps: :library:network, :library:image, :library:media, :youpipe:extractor, :library:room
- Metadata: metadata_data/musicbrainz.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/musicbrainz/MainActivity.kt
- com/vayunmathur/musicbrainz/MusicBrainzApplication.kt
- com/vayunmathur/musicbrainz/Navigation.kt
- com/vayunmathur/musicbrainz/Route.kt
- com/vayunmathur/musicbrainz/data/MusicBrainzDatabase.kt
- com/vayunmathur/musicbrainz/data/MusicBrainzRepository.kt
- com/vayunmathur/musicbrainz/data/download/AudioSource.kt
- com/vayunmathur/musicbrainz/data/download/AudioSources.kt
- com/vayunmathur/musicbrainz/data/download/Lyrics.kt
- com/vayunmathur/musicbrainz/data/download/MbDownloader.kt
- com/vayunmathur/musicbrainz/data/download/TidalAudioSource.kt
- com/vayunmathur/musicbrainz/data/download/TidalManifest.kt
- com/vayunmathur/musicbrainz/data/download/YouTubeAudioSource.kt
- com/vayunmathur/musicbrainz/data/library/LibraryIndex.kt
- com/vayunmathur/musicbrainz/data/library/LibraryScanner.kt
- com/vayunmathur/musicbrainz/data/library/TagReader.kt
- com/vayunmathur/musicbrainz/data/tidal/TidalAuth.kt
- com/vayunmathur/musicbrainz/data/tidal/TidalSession.kt
- com/vayunmathur/musicbrainz/domain/library/MatchKeys.kt
- com/vayunmathur/musicbrainz/network/api/MusicBrainzApi.kt
- com/vayunmathur/musicbrainz/network/api/MusicBrainzModels.kt
- com/vayunmathur/musicbrainz/network/api/TidalApi.kt
- com/vayunmathur/musicbrainz/network/api/TidalModels.kt
- com/vayunmathur/musicbrainz/platform/DownloadSource.kt
- com/vayunmathur/musicbrainz/platform/MusicBrainzPrefs.kt
- com/vayunmathur/musicbrainz/platform/MusicBrainzUiContract.kt
- com/vayunmathur/musicbrainz/platform/MusicBrainzViewModel.kt
- com/vayunmathur/musicbrainz/platform/SafTree.kt
- com/vayunmathur/musicbrainz/platform/download/DownloadQueue.kt
- com/vayunmathur/musicbrainz/platform/download/DownloadWorker.kt
- com/vayunmathur/musicbrainz/ui/ArtistScreen.kt
- com/vayunmathur/musicbrainz/ui/DownloadsScreen.kt
- com/vayunmathur/musicbrainz/ui/ReleaseGroupScreen.kt
- com/vayunmathur/musicbrainz/ui/ReleaseScreen.kt
- com/vayunmathur/musicbrainz/ui/SearchScreen.kt
- com/vayunmathur/musicbrainz/ui/SettingsScreen.kt
- com/vayunmathur/musicbrainz/ui/TidalLoginScreen.kt
- com/vayunmathur/musicbrainz/ui/components/CoverArtImage.kt
- com/vayunmathur/musicbrainz/ui/components/DurationLabel.kt
- com/vayunmathur/musicbrainz/ui/components/SecondaryText.kt
- com/vayunmathur/musicbrainz/ui/components/TrackTrailing.kt

## Verify (this module only)
```
./gradlew :musicbrainz:compileDevKotlin
./gradlew :musicbrainz:lint
./gradlew :musicbrainz:checkMetadata
```



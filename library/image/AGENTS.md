# AGENTS.md — library/image/ (':library:image')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/image/` + allowed shared modules. Do not root-scan.

- Gradle: :library:image / dir: library/image/
- Package roots present: 
- Entry files: 
- Deps: :library, :library:network
- Metadata: metadata_data/library-image.md (MISSING)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/library/image/DiskCache.kt
- com/vayunmathur/library/image/ImageLoader.kt
- com/vayunmathur/library/image/ImageRequest.kt
- com/vayunmathur/library/image/ImageResult.kt
- com/vayunmathur/library/image/MemoryCache.kt
- com/vayunmathur/library/image/Size.kt
- com/vayunmathur/library/image/Transformation.kt
- com/vayunmathur/library/image/coilcompat/CoilCompat.kt
- com/vayunmathur/library/image/compose/AnimatedImage.kt
- com/vayunmathur/library/image/compose/AsyncImage.kt
- com/vayunmathur/library/image/compose/AsyncImageState.kt
- com/vayunmathur/library/image/decoders/BitmapDecoder.kt
- com/vayunmathur/library/image/decoders/SvgDecoder.kt
- com/vayunmathur/library/image/decoders/VideoFrameDecoder.kt
- com/vayunmathur/library/image/fetchers/AssetFetcher.kt
- com/vayunmathur/library/image/fetchers/BitmapFetcher.kt
- com/vayunmathur/library/image/fetchers/ByteArrayFetcher.kt
- com/vayunmathur/library/image/fetchers/ContentResolverFetcher.kt
- com/vayunmathur/library/image/fetchers/Fetcher.kt
- com/vayunmathur/library/image/fetchers/FileFetcher.kt
- com/vayunmathur/library/image/fetchers/HttpFetcher.kt
- com/vayunmathur/library/image/util/PercentSizing.kt
- com/vayunmathur/library/image/util/Sha256.kt

## Verify (this module only)
```
./gradlew :library:image:compileDevKotlin
./gradlew :library:image:lint
```



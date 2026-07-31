package com.vayunmathur.photos.util
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.IconPlayCircle
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.image.ImageLoader as LibImageLoader
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.DiskCache
import com.vayunmathur.library.image.MemoryCache
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.photos.data.Photo

object ImageLoader {
    private lateinit var imageLoader: LibImageLoader

    fun init(context: Context) {
        imageLoader = LibImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context).maxSizePercent(0.25).build()
            }
            .diskCache {
                DiskCache.Builder().directory(context.cacheDir.resolve("image_cache")).maxSizePercent(0.05).build()
            }
            .respectCacheHeaders(false)
            .build()
    }

    @Composable
    fun PhotoItem(photo: Photo, modifier: Modifier, onClick: (() -> Unit)? = null) {
        val context = LocalContext.current
        val isVideo = photo.videoData != null

        val clickableModifier = if(onClick != null) modifier.invisibleClickable(onClick) else modifier

        Box(
            modifier = clickableModifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.DarkGray),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(photo.uri.toUri())
                    .videoFrameMillis(1000)
                    .diskCacheKey("thumb_${photo.id}_${photo.dateModified}")
                    .memoryCacheKey("thumb_${photo.id}_${photo.dateModified}")
                    .crossfade(true)
                    .size(256)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            if (isVideo) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.4f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconPlayCircle(
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun SelectablePhotoItem(
        photo: Photo,
        isSelected: Boolean,
        isSelectionMode: Boolean,
        onToggleSelection: () -> Unit,
        onClick: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onToggleSelection
                )
        ) {
            PhotoItem(photo, Modifier.fillMaxSize(), onClick = null)

            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    if (isSelected) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        ) {
                            IconCheck(tint = Color.White)
                        }
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.3f),
                            modifier = Modifier.size(24.dp)
                        ) {
                        }
                    }
                }
            }
        }
    }
}

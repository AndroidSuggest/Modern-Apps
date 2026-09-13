package com.vayunmathur.photos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.AppScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.pushPhoto
import com.vayunmathur.photos.util.Album
import com.vayunmathur.photos.util.ImageLoader

/**
 * The photo grid for one album, with no dependency on the ViewModel so it can
 * be rendered from a `@Preview`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    backStack: NavBackStack<Route>,
    albumName: String,
    album: Album?,
    /**
     * How a grid tile paints its photo. Previews pass a placeholder, because
     * Layoutlib has neither MediaStore nor the [ImageLoader] singleton
     * MainActivity initialises.
     */
    thumbnail: @Composable (Photo, Modifier) -> Unit = { photo, modifier -> ImageLoader.PhotoItem(photo, modifier) },
) {
    val title = if (albumName == Album.UNKNOWN_NAME) stringResource(R.string.albums_unknown) else albumName
    AppScaffold(
        title = title,
        onNavigateBack = { backStack.pop() },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        val photos = album?.photos.orEmpty()
        if (photos.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.albums_empty),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize().padding(padding).padding(4.dp),
            ) {
                items(photos, key = { it.id }, contentType = { "photo_thumbnail" }) { photo ->
                    ImageLoader.SelectablePhotoItem(
                        photo = photo,
                        isSelected = false,
                        isSelectionMode = false,
                        onToggleSelection = {},
                        onClick = {
                            backStack.pushPhoto(Route.PhotoPage(photo.id, photos))
                        },
                        thumbnail = thumbnail,
                        sharedKey = photo.id,
                    )
                }
            }
        }
    }
}

package com.vayunmathur.photos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.util.GalleryViewModel

/** Binds [GalleryViewModel] to the stateless [AlbumDetailScreen] for one album. */
@Composable
fun AlbumDetailPage(
    backStack: NavBackStack<Route>,
    galleryViewModel: GalleryViewModel,
    albumName: String,
) {
    val albums by galleryViewModel.albums.collectAsState()
    val album by remember { derivedStateOf { albums.firstOrNull { it.name == albumName } } }

    AlbumDetailScreen(
        backStack = backStack,
        albumName = albumName,
        album = album,
    )
}

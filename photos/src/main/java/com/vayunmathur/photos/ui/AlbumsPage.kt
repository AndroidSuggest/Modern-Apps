package com.vayunmathur.photos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.util.AlbumsUiState
import com.vayunmathur.photos.util.GalleryViewModel

/** Binds [GalleryViewModel] to the stateless [AlbumsScreen]. */
@Composable
fun AlbumsPage(
    backStack: NavBackStack<Route>,
    galleryViewModel: GalleryViewModel,
) {
    val albums by galleryViewModel.albums.collectAsState()

    AlbumsScreen(
        backStack = backStack,
        state = AlbumsUiState(albums = albums),
    )
}

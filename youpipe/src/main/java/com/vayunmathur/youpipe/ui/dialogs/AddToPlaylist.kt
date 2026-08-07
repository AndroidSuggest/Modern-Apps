package com.vayunmathur.youpipe.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * Add-to-playlist dialog opened from the video page. Lists Watch later + the user's playlists
 * (Downloads is not a playlist and is excluded), each with a membership [Checkbox]. A "New
 * playlist" field at the bottom creates a playlist and adds the current video in one step.
 */
@Composable
fun AddToPlaylist(
    backStack: NavBackStack<Route>,
    youPipeViewModel: YouPipeViewModel,
    videoID: Long,
    includeWatchLater: Boolean = true,
) {
    val allPlaylists by youPipeViewModel.playlists.collectAsStateWithLifecycle()
    val playlists = if (includeWatchLater) allPlaylists else allPlaylists.filter { !it.mandatory }
    val allItems by youPipeViewModel.allPlaylistItems.collectAsStateWithLifecycle()
    val videoState by youPipeViewModel.videoState.collectAsStateWithLifecycle()

    // We just navigated from this video, so its loaded state is the source for the stored row.
    val data = videoState.data
    if (data == null) {
        Dialog({ backStack.pop() }) {
            Card { Text(stringResource(R.string.add_to_playlist), Modifier.padding(16.dp)) }
        }
        return
    }

    val video = VideoInfo(
        data.title, videoID, data.duration, data.views, data.uploadDate, data.thumbnailURL, data.author,
    )

    // playlistIds this video already belongs to, for the checkbox states.
    val membership = remember(allItems, videoID) {
        allItems.filter { it.videoItem.videoID == videoID }.map { it.playlistId }.toSet()
    }

    var newPlaylistName by remember { mutableStateOf("") }

    Dialog({ backStack.pop() }) {
        Card {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.add_to_playlist),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(playlists, key = { it.id }) { playlist ->
                        val checked = playlist.id in membership
                        val label = if (playlist.mandatory) {
                            stringResource(R.string.playlist_watch_later)
                        } else {
                            playlist.name
                        }
                        ListItem(
                            content = { Text(label) },
                            trailingContent = {
                                Checkbox(checked, { isChecked ->
                                    if (isChecked) {
                                        youPipeViewModel.addVideoToPlaylist(playlist.id, video)
                                    } else {
                                        allItems.firstOrNull {
                                            it.playlistId == playlist.id && it.videoItem.videoID == videoID
                                        }?.let { youPipeViewModel.removeFromPlaylist(it) }
                                    }
                                })
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        newPlaylistName,
                        { newPlaylistName = it },
                        label = { Text(stringResource(R.string.new_playlist)) },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            youPipeViewModel.createPlaylistAndAddVideo(newPlaylistName.trim(), video)
                            newPlaylistName = ""
                        },
                        enabled = newPlaylistName.isNotBlank() &&
                            newPlaylistName.trim() !in allPlaylists.map { it.name },
                    ) {
                        IconAdd()
                    }
                }
            }
        }
    }
}

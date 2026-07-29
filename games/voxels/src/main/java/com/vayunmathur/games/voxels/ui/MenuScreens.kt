package com.vayunmathur.games.voxels.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.voxels.util.WorldInfo
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text

@Composable
fun MenuScreen(
    worlds: List<WorldInfo>,
    onPlay: (WorldInfo) -> Unit,
    onDelete: (WorldInfo) -> Unit,
    onCreate: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Voxels", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text("Select a world", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(20.dp))

            if (worlds.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Text("No worlds yet — create one to start.", color = MaterialTheme.colorScheme.onBackground)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(worlds, key = { it.id }) { world ->
                        WorldRow(world = world, onPlay = { onPlay(world) }, onDelete = { onDelete(world) })
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                Text("Create New World")
            }
        }
    }
}

@Composable
private fun WorldRow(world: WorldInfo, onPlay: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onPlay() }) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(world.meta.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                val last = DateUtils.getRelativeTimeSpanString(world.meta.lastPlayed).toString()
                Text("Seed: ${world.meta.seed}  •  $last", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            IconButton(onClick = onDelete) { IconDelete(tint = MaterialTheme.colorScheme.error) }
            IconButton(onClick = onPlay) { IconPlay(tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
fun WorldCreatorScreen(
    onBack: () -> Unit,
    onCreate: (name: String, seedText: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Create World", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("World name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = seed,
                onValueChange = { seed = it },
                label = { Text("Seed") },
                placeholder = { Text("Leave blank for random") },
                supportingText = { Text("A number, or any text (hashed to a seed).") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = { onCreate(name, seed) }, modifier = Modifier.weight(1f)) { Text("Create & Play") }
            }
        }
    }
}

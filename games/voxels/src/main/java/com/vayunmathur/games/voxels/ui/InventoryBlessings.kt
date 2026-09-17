package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.voxels.R
import com.vayunmathur.games.voxels.util.VoxelsNative
import com.vayunmathur.library.ui.Text

private data class BlessingInfo(val id: Int, val name: String, val effect: String)

private fun parseBlessings(json: String, key: String?): List<BlessingInfo> = try {
    val arr = if (key != null) org.json.JSONObject(json).getJSONArray(key) else org.json.JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        BlessingInfo(o.getInt("id"), o.optString("name"), o.optString("effect"))
    }
} catch (_: Exception) { emptyList() }

/**
 * Blessings are permanent attunements rather than consumables: bind a charm from the inventory to
 * one of the slots, or release a slot to get the charm back.
 */
@Composable
internal fun BlessingsView(blessingsJson: String, catalogJson: String, slots: List<InvSlot>) {
    val attuned = remember(blessingsJson) { parseBlessings(blessingsJson, "slots") }
    val catalog = remember(catalogJson) { parseBlessings(catalogJson, null) }
    // Charms sitting in the inventory that aren't bound yet.
    val held = remember(slots, attuned) {
        val boundIds = attuned.map { it.id }.toSet()
        slots.withIndex()
            .filter { (_, s) -> s.id != 0 && catalog.any { it.id == s.id } && s.id !in boundIds }
            .map { (i, s) -> i to s.id }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.attuned), color = Color.White.copy(0.6f), fontSize = 12.sp)
            attuned.forEachIndexed { i, b ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (b.id != 0) Color(0xFF3A5A6B) else Color.White.copy(0.06f))
                        .clickable(enabled = b.id != 0) {
                            try { VoxelsNative.releaseBlessing(i) } catch (_: Exception) {}
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val icon = if (b.id != 0) rememberBlockIcon(b.id) else null
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(30.dp), filterQuality = FilterQuality.None)
                    }
                    Column {
                        Text(
                            if (b.id != 0) b.name else stringResource(R.string.empty_slot),
                            color = Color.White.copy(if (b.id != 0) 0.95f else 0.45f), fontSize = 12.sp
                        )
                        if (b.id != 0) Text(b.effect, color = Color.White.copy(0.6f), fontSize = 10.sp)
                    }
                }
            }
            Text(stringResource(R.string.blessing_tap_hint), color = Color.White.copy(0.5f), fontSize = 10.sp)
        }

        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.charms_held), color = Color.White.copy(0.6f), fontSize = 12.sp)
            if (held.isEmpty()) {
                Text(stringResource(R.string.no_charms), color = Color.White.copy(0.45f), fontSize = 11.sp)
            }
            held.forEach { (invIdx, id) ->
                val info = catalog.firstOrNull { it.id == id } ?: return@forEach
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(0.08f))
                        .clickable { try { VoxelsNative.attuneBlessing(invIdx) } catch (_: Exception) {} }
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val icon = rememberBlockIcon(id)
                    if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(26.dp), filterQuality = FilterQuality.None)
                    Column {
                        Text(info.name, color = Color.White.copy(0.95f), fontSize = 12.sp)
                        Text(info.effect, color = Color.White.copy(0.6f), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

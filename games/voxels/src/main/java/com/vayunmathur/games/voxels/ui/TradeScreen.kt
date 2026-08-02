package com.vayunmathur.games.voxels.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.voxels.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.voxels.util.VoxelsNative
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text

private data class Trade(val cost: Int, val costN: Int, val give: Int, val giveN: Int)

@Composable
fun TradeOverlay(tradesJson: String, onClose: () -> Unit) {
    val trades = remember(tradesJson) {
        try {
            val arr = org.json.JSONArray(tradesJson)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Trade(o.getInt("cost"), o.getInt("costN"), o.getInt("give"), o.getInt("giveN"))
            }
        } catch (_: Exception) { emptyList() }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth(0.7f).fillMaxHeight(0.8f).clip(RoundedCornerShape(14.dp)).background(Color(0xF01A1E1A))
                .pointerInput(Unit) { detectTapGestures { } }.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.villager_trades), color = Color.White, fontSize = 18.sp)
            Text(stringResource(R.string.tap_a_trade_to_exchange_need_the_cost_it), color = Color.White.copy(0.6f), fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(trades.withIndex().toList()) { (i, t) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.07f))
                            .clickable { try { if (VoxelsNative.trade(i)) com.vayunmathur.games.voxels.util.SoundFx.playPlace() } catch (_: Exception) {} }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ItemChip(t.cost, t.costN)
                        Text("→", color = Color.White.copy(0.8f), fontSize = 20.sp)
                        ItemChip(t.give, t.giveN)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A6B3A)).clickable { onClose() }.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(stringResource(UiR.string.close), color = Color.White)
            }
        }
    }
}

@Composable
private fun ItemChip(id: Int, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val icon = rememberBlockIcon(id)
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(0.4f)), contentAlignment = Alignment.Center) {
            if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(28.dp), filterQuality = FilterQuality.None)
        }
        Text("${count}× ${blockNames[id] ?: id}", color = Color.White, fontSize = 12.sp)
    }
}

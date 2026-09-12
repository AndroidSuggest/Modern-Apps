package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.data.LevelDef
import com.vayunmathur.games.logicgate.ui.components.BitDotsRow
import com.vayunmathur.library.ui.Text

internal fun cellWidthForInput(level: LevelDef, idx: Int): Dp {
    val w = level.inputWidth(idx)
    return when {
        w >= 8 -> 168.dp // 8*14 + 7*4 = 140 needs 168 for padding
        w >= 4 -> 96.dp  // 4*14+3*4=68 fits 96
        else -> 72.dp
    }
}

internal fun cellWidthForOutput(level: LevelDef, idx: Int): Dp {
    val w = level.outputWidth(idx)
    return when {
        w >= 8 -> 168.dp
        w >= 4 -> 96.dp
        else -> 72.dp
    }
}

@Composable
internal fun AppBarActionBtn(glyph: String, enabled: Boolean = true, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
            .background(Turing.iconBg.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, fontSize = 18.sp, color = if (enabled) tint else tint.copy(alpha = 0.4f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileIoSheet(level: LevelDef, inputDecimals: Map<Int, Int>, inputBitSlices: Map<Int, List<Boolean>>, desiredDecimals: Map<Int, Int>, desiredBitSlices: Map<Int, List<Boolean>>, actualDecimals: Map<Int, Int>, actualBitSlices: Map<Int, List<Boolean>>, onDismiss: () -> Unit) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color(0xFF1D2A3A), contentColor = Color.White, scrimColor = Color.Black.copy(alpha = 0.4f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Turing.leftPanelCard).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.inputs), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                level.inputs.forEachIndexed { idx, _ ->
                    val dec = inputDecimals[idx] ?: 0
                    val bits = inputBitSlices[idx] ?: emptyList()
                    val label = displayInputLabel(level, idx)
                    Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, fontSize = 14.sp, color = Color(0xFFB8C6D8), modifier = Modifier.weight(1f))
                        BitDotsRow(bits = bits, dotSize = 16.dp, spacing = 6.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("$dec", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.width(48.dp))
                    }
                }
            }
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Turing.leftPanelCard).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.outputs), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                level.outputs.forEachIndexed { idx, _ ->
                    val decD = desiredDecimals[idx] ?: 0
                    val bitsD = desiredBitSlices[idx] ?: emptyList()
                    val decA = actualDecimals[idx] ?: decD
                    val bitsA = actualBitSlices[idx] ?: bitsD
                    val outLabel = displayOutputLabel(level, idx)
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.expected, outLabel), fontSize = 13.sp, color = Turing.orangeLabel, modifier = Modifier.weight(1f))
                            BitDotsRow(bits = bitsD, dotSize = 16.dp, spacing = 6.dp)
                            Text("$decD", fontSize = 14.sp, color = Color.White, modifier = Modifier.width(48.dp))
                        }
                        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.got_2, outLabel), fontSize = 13.sp, color = if (decD == decA) Turing.orangeLabel else Color(0xFFFF8A8A), modifier = Modifier.weight(1f))
                            BitDotsRow(bits = bitsA, dotSize = 16.dp, spacing = 6.dp)
                            Text("$decA", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (decD == decA) Color(0xFF8EF0B0) else Color(0xFFFF8A8A), modifier = Modifier.width(48.dp))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun MobileLeftPanel(tick: Int, simSpeed: Int, level: LevelDef, inputDecimals: Map<Int, Int>, inputBitSlices: Map<Int, List<Boolean>>, desiredDecimals: Map<Int, Int>, desiredBitSlices: Map<Int, List<Boolean>>, actualDecimals: Map<Int, Int>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(Turing.leftPanelBg).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Turing.leftPanelCard).padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.tick), fontSize = 13.sp, color = Color(0xFF8AA0BB)); Text("$tick", fontSize = 13.sp, color = Color.White) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.sim_speed), fontSize = 13.sp, color = Color(0xFF8AA0BB)); Text(stringResource(R.string.hz, simSpeed), fontSize = 13.sp, color = Color.White) }
        }
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Turing.leftPanelCard).padding(12.dp)) {
            Text(stringResource(R.string.inputs), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            level.inputs.forEachIndexed { idx, _ ->
                val dec = inputDecimals[idx] ?: 0
                val bits = inputBitSlices[idx] ?: emptyList()
                val label = displayInputLabel(level, idx)
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 13.sp, color = Color(0xFFB8C6D8)); Spacer(modifier = Modifier.height(4.dp)); BitDotsRow(bits = bits, dotSize = 10.dp); Spacer(modifier = Modifier.height(3.dp)); Text("$dec", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.outputs), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            level.outputs.forEachIndexed { idx, _ ->
                val dec = actualDecimals[idx] ?: desiredDecimals[idx] ?: 0
                val bits = desiredBitSlices[idx] ?: emptyList()
                val label = displayOutputLabel(level, idx)
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 13.sp, color = Color(0xFFB8C6D8)); Spacer(modifier = Modifier.height(4.dp)); BitDotsRow(bits = bits, dotSize = 10.dp); Spacer(modifier = Modifier.height(3.dp)); Text("$dec", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
internal fun MobileMiddleToolbar(onClear: () -> Unit, onUndo: () -> Unit, onRedo: () -> Unit, canUndo: Boolean, canRedo: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(Turing.iconBarBg).padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MobileToolbarBtn("⊕", onClick = {}); MobileToolbarBtn("⊖", onClick = {}); Spacer(modifier = Modifier.height(4.dp)); MobileToolbarBtn("▶", sub = "${20}kHz", onClick = {}); MobileToolbarBtn("↗", onClick = onRedo); MobileToolbarBtn("↩", onClick = { if (canUndo) onUndo() }); MobileToolbarBtn("■", onClick = onClear)
        Box(modifier = Modifier.height(1.dp).fillMaxWidth(0.6f).background(Color(0xFF2A3A50)))
        MobileToolbarBtn("⬚", onClick = {}); MobileToolbarBtn("🗑", onClick = onClear); MobileToolbarBtn("✎", onClick = {}); MobileToolbarBtn("◍", onClick = {}); Spacer(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Turing.iconBg).border(0.8.dp, Color(0xFF2E425C), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text("8↔", fontSize = 13.sp, color = Color.White) }
    }
}

@Composable
internal fun MobileToolbarBtn(text: String, sub: String? = null, onClick: () -> Unit = {}) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Turing.iconBg).border(0.8.dp, Color(0xFF2E425C), RoundedCornerShape(10.dp)).clickable { onClick() }, contentAlignment = Alignment.Center) { Text(text, fontSize = 20.sp, color = Color(0xFF9AA3BB)) }
        if (sub != null) Text(sub, fontSize = 11.sp, color = Color(0xFF7A8AA3))
    }
}

@Composable
internal fun MobileTestbench(
    level: LevelDef,
    tableRows: List<TableRowUi>,
    selectedIdx: Int,
    onSelectRow: (Int) -> Unit,
    outputsConnected: Boolean,
    failingSet: Set<Int>,
    modifier: Modifier = Modifier
) {
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val inputCellWs = level.inputs.indices.map { cellWidthForInput(level, it) }
    val outCellWs = level.outputs.indices.map { cellWidthForOutput(level, it) }
    val checkW = 28.dp

    Column(
        modifier = modifier
            .background(Turing.bottomBg)
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .verticalScroll(vScroll)
            .navigationBarsPadding()
    ) {
            Row(modifier = Modifier.horizontalScroll(hScroll)) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    // Header – fixed grid, same padding as rows
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        level.inputs.forEachIndexed { idx, _ ->
                            Box(modifier = Modifier.width(inputCellWs[idx]), contentAlignment = Alignment.Center) {
                                Text(displayInputLabel(level, idx), fontSize = 12.sp, color = Turing.orangeLabel, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                        Box(modifier = Modifier.width(1.dp).height(16.dp).background(Color(0xFF3A3A52)))
                        level.outputs.forEachIndexed { idx, _ ->
                            Box(modifier = Modifier.width(outCellWs[idx]), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.exp), fontSize = 12.sp, color = Turing.orangeLabel, fontWeight = FontWeight.Bold)
                            }
                        }
                        level.outputs.forEachIndexed { idx, _ ->
                            Box(modifier = Modifier.width(outCellWs[idx]), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.got), fontSize = 12.sp, color = Turing.orangeLabel, fontWeight = FontWeight.Bold)
                            }
                        }
                        Box(modifier = Modifier.width(checkW))
                    }
                    Box(modifier = Modifier.height(1.dp).background(Color(0xFF3A3A52).copy(alpha = 0.5f)))
                    tableRows.forEachIndexed { rowIdx, row ->
                        val isSelected = rowIdx == selectedIdx
                        val isFailing = failingSet.contains(rowIdx)
                        Row(
                            modifier = Modifier
                                .heightIn(min = 28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        isFailing -> Color(0x1AFF8A8A)
                                        isSelected -> Turing.iconBg
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable { onSelectRow(rowIdx) }
                                .padding(vertical = 2.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            level.inputs.forEachIndexed { inIdx, _ ->
                                Box(modifier = Modifier.width(inputCellWs[inIdx]), contentAlignment = Alignment.Center) {
                                    val bits = row.inputSlices[inIdx] ?: emptyList()
                                    BitDotsRow(bits = bits, dotSize = 12.dp, spacing = 4.dp, maxDots = level.inputWidth(inIdx))
                                }
                            }
                            Box(modifier = Modifier.width(1.dp).height(20.dp).background(Color(0xFF3A3A52).copy(alpha = 0.5f)))
                            level.outputs.forEachIndexed { outIdx, _ ->
                                Box(modifier = Modifier.width(outCellWs[outIdx]), contentAlignment = Alignment.Center) {
                                    val bits = row.desiredSlices[outIdx] ?: emptyList()
                                    BitDotsRow(bits = bits, dotSize = 12.dp, spacing = 4.dp, maxDots = level.outputWidth(outIdx))
                                }
                            }
                            level.outputs.forEachIndexed { outIdx, _ ->
                                Box(modifier = Modifier.width(outCellWs[outIdx]), contentAlignment = Alignment.Center) {
                                    val bits = if (outputsConnected) row.actualSlices?.get(outIdx) else null
                                    if (bits != null) {
                                        BitDotsRow(bits = bits, dotSize = 12.dp, spacing = 4.dp, maxDots = level.outputWidth(outIdx))
                                    } else {
                                        // Placeholder gray dots matching width – keeps columns aligned
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            repeat(level.outputWidth(outIdx)) {
                                                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color(0xFF3A3A52)))
                                            }
                                        }
                                    }
                                }
                            }
                            Box(modifier = Modifier.width(checkW), contentAlignment = Alignment.Center) {
                                // No check until an output is actually driven (nothing connected yet).
                                if (outputsConnected && row.actualSlices != null) {
                                    Text(if (isFailing) "✗" else "✓", fontSize = 13.sp, color = if (isFailing) Color(0xFFFF8A8A) else Color(0xFF22C55E), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

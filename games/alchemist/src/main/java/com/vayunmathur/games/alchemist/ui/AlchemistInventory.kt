package com.vayunmathur.games.alchemist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.alchemist.R
import com.vayunmathur.games.alchemist.data.AlchemyItem
import com.vayunmathur.games.alchemist.ui.components.DynamicAlchemyIcon
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.SwappedContent
import com.vayunmathur.library.ui.Text

/**
 * The bottom inventory panel: counter, A-Z bar, and the palette row (or the delete target
 * while a board item is being dragged).
 */
@Composable
internal fun AlchemistInventoryPanel(
    paletteItems: List<AlchemyItem>,
    discoveredCount: Int,
    totalCount: Int,
    isDraggingBoardItem: Boolean,
    lazyList: LazyListState,
    onBarTopPositioned: (Float) -> Unit,
    onLetterJump: (Int) -> Unit,
    onPlaceElement: (Long, Offset) -> Unit,
    onInventoryDragState: (Long?, Offset) -> Unit,
    playAreaOffsetInWindow: Offset,
    bottomBarTopInWindow: Float,
    onContextMenu: (Long) -> Unit,
) {
    var draggingInventoryId by remember { mutableStateOf<Long?>(null) }
    var draggingInventoryOffset by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // 2.1 INVENTORY COUNT (discovered / total)
        Text(
            stringResource(
                R.string.counter, discoveredCount, totalCount
            ),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        // 2.2 A-Z LETTER BAR
        val activeLetters = remember(paletteItems) {
            paletteItems.mapNotNull { it.name.firstOrNull()?.uppercaseChar() }.toSet()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ('A'..'Z').filter { it in activeLetters }.forEach { letter ->
                Text(
                    text = letter.toString(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            val index = paletteItems.indexOfFirst {
                                it.name.firstOrNull()?.uppercaseChar() == letter
                            }
                            if (index >= 0) {
                                onLetterJump(index)
                            }
                        }
                        .padding(horizontal = 2.dp, vertical = 4.dp)
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    onBarTopPositioned(it.positionInWindow().y)
                },
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
        ) {
            SwappedContent(isDraggingBoardItem) { isDragging ->
                if (isDragging) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.errorContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painterResource(id = android.R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.cd_delete),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                } else {
                    LazyRow(
                        state = lazyList,
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(paletteItems, key = { it.id }) { item ->
                            var itemPosInWindow by remember { mutableStateOf(Offset.Zero) }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .onGloballyPositioned {
                                        itemPosInWindow = it.positionInWindow()
                                    }) {
                                Box(
                                    Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .combinedClickable(onLongClick = {
                                            onContextMenu(item.id)
                                        }, onClick = {})
                                        .pointerInput(item.id) {
                                            // Direction split (45°): horizontal drag -> let the LazyRow scroll;
                                            // vertical drag -> lift the item out to place it on the board.
                                            val slop = viewConfiguration.touchSlop
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                var total = Offset.Zero
                                                var decided = false
                                                var pullOut = false
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                    if (ch.changedToUpIgnoreConsumed()) {
                                                        if (pullOut) {
                                                            val limitY = bottomBarTopInWindow - playAreaOffsetInWindow.y - 48f
                                                            if (draggingInventoryOffset.y < limitY) {
                                                                onPlaceElement(item.id, draggingInventoryOffset)
                                                            }
                                                        }
                                                        draggingInventoryId = null
                                                        onInventoryDragState(null, Offset.Zero)
                                                        break
                                                    }
                                                    val dragAmount = ch.position - ch.previousPosition
                                                    total += dragAmount
                                                    if (!decided && total.getDistance() > slop) {
                                                        decided = true
                                                        if (kotlin.math.abs(total.y) > kotlin.math.abs(total.x)) {
                                                            pullOut = true
                                                            draggingInventoryId = item.id
                                                            val fingerInWindow = itemPosInWindow + ch.position
                                                            draggingInventoryOffset = Offset(
                                                                x = fingerInWindow.x - playAreaOffsetInWindow.x - 100f,
                                                                y = fingerInWindow.y - playAreaOffsetInWindow.y - 100f
                                                            )
                                                            onInventoryDragState(item.id, draggingInventoryOffset)
                                                        } else break // horizontal -> don't consume; LazyRow scrolls
                                                    }
                                                    if (pullOut) {
                                                        ch.consume()
                                                        draggingInventoryOffset += dragAmount
                                                        onInventoryDragState(draggingInventoryId, draggingInventoryOffset)
                                                    }
                                                }
                                            }
                                        }
                                ) {
                                    DynamicAlchemyIcon(item.id)
                                    if (item.final) {
                                        Icon(
                                            painterResource(id = android.R.drawable.star_on),
                                            contentDescription = stringResource(R.string.cd_final_item),
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                            modifier = Modifier
                                                .size(24.dp)
                                                .align(Alignment.BottomEnd)
                                        )
                                    }
                                }
                                Text(item.name, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

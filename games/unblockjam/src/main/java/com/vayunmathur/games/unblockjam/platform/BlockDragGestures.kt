package com.vayunmathur.games.unblockjam.platform
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.Dimension
import com.vayunmathur.games.unblockjam.data.LevelData

private data class AxisParams(
    val pos: Int,
    val size: Int,
    val boardLimit: Int,
    val perpPos: Int,
    val perpSize: Int,
)

private fun axisParams(block: Block, levelData: LevelData, isHorizontal: Boolean): AxisParams =
    if (isHorizontal) {
        AxisParams(
            block.position.x,
            block.dimension.width,
            levelData.dimension.width,
            block.position.y,
            block.dimension.height,
        )
    } else {
        AxisParams(
            block.position.y,
            block.dimension.height,
            levelData.dimension.height,
            block.position.x,
            block.dimension.width,
        )
    }

private fun isOccupied(otherBlocks: List<Block>, x: Int, y: Int): Boolean = otherBlocks.any {
    x in it.position.x until it.position.x + it.dimension.width &&
        y in it.position.y until it.position.y + it.dimension.height
}

private fun isClearAt(
    otherBlocks: List<Block>,
    isHorizontal: Boolean,
    perpPos: Int,
    perpSize: Int,
    primary: Int,
): Boolean = (perpPos until perpPos + perpSize).all { p ->
    if (isHorizontal) !isOccupied(otherBlocks, primary, p) else !isOccupied(otherBlocks, p, primary)
}

private fun scanLimit(axis: AxisParams, isClear: (Int) -> Boolean, start: Int, dir: Int): Int {
    var edge = start
    while (true) {
        val inBounds = if (dir < 0) edge > 0 else edge + axis.size < axis.boardLimit
        val probe = if (dir < 0) edge - 1 else edge + axis.size
        if (!inBounds || !isClear(probe)) break
        edge += dir
    }
    return edge
}

private fun exitExtension(
    block: Block,
    otherBlocks: List<Block>,
    levelData: LevelData,
    isHorizontal: Boolean,
    isMainBlock: Boolean,
    axis: AxisParams,
    max: Int,
): Int {
    if (!isHorizontal || !isMainBlock) return max
    if (block.position.y != levelData.exit.y) return max
    val pathClear = (max + axis.size until levelData.dimension.width).all { x ->
        !isOccupied(otherBlocks, x, block.position.y)
    }
    return if (pathClear) levelData.exit.x else max
}

private fun findMovementRange(
    block: Block,
    otherBlocks: List<Block>,
    levelData: LevelData,
    isMainBlock: Boolean,
    isHorizontal: Boolean
): IntRange {
    val axis = axisParams(block, levelData, isHorizontal)
    val isClear = { primary: Int ->
        isClearAt(otherBlocks, isHorizontal, axis.perpPos, axis.perpSize, primary)
    }
    val min = scanLimit(axis, isClear, axis.pos, -1)
    val max = exitExtension(
        block,
        otherBlocks,
        levelData,
        isHorizontal,
        isMainBlock,
        axis,
        scanLimit(axis, isClear, axis.pos, 1),
    )
    return min..max
}

private class DragParams(
    val block: Block,
    val levelData: LevelData,
    val isHorizontal: Boolean,
    val isMainBlock: Boolean,
    val cellWidth: Dp,
    val cellHeight: Dp,
    val onLevelWon: () -> Unit,
    val onLevelChanged: (LevelData) -> Unit,
    val index: Int,
)

private fun settleAfterDrag(
    params: DragParams,
    offsetXProvider: () -> Dp,
    offsetYProvider: () -> Dp,
    offsetXUpdater: (Dp) -> Unit,
    offsetYUpdater: (Dp) -> Unit,
) {
    val (newX, newY) = snappedPosition(params, offsetXProvider, offsetYProvider)
    val newBlock = params.block.copy(position = Coord(newX, newY))
    when {
        isWinningMove(params, newX) -> params.onLevelWon()
        isValidRelocation(params, newBlock) -> applyRelocation(params, newBlock)
        else -> resetOffsets(params, offsetXUpdater, offsetYUpdater)
    }
}

private fun snappedPosition(
    params: DragParams,
    offsetXProvider: () -> Dp,
    offsetYProvider: () -> Dp,
): Pair<Int, Int> = if (params.isHorizontal) {
    (offsetXProvider() / params.cellWidth).roundToInt() to params.block.position.y
} else {
    params.block.position.x to (offsetYProvider() / params.cellHeight).roundToInt()
}

private fun isWinningMove(params: DragParams, newX: Int): Boolean =
    params.isMainBlock &&
        params.block.position.y == params.levelData.exit.y &&
        newX >= params.levelData.exit.x

private fun isValidRelocation(params: DragParams, newBlock: Block): Boolean =
    newBlock.position != params.block.position &&
        isMoveValid(newBlock, params.levelData.blocks - params.block, params.levelData.dimension)

private fun applyRelocation(params: DragParams, newBlock: Block) {
    val newBlocks = params.levelData.blocks.toMutableList()
    newBlocks[params.index] = newBlock
    params.onLevelChanged(params.levelData.copy(blocks = newBlocks, lastMovedBlockIndex = params.index))
}

private fun resetOffsets(
    params: DragParams,
    offsetXUpdater: (Dp) -> Unit,
    offsetYUpdater: (Dp) -> Unit,
) {
    offsetXUpdater(params.cellWidth * params.block.position.x)
    offsetYUpdater(params.cellHeight * params.block.position.y)
}

private fun Density.handleDragDelta(
    params: DragParams,
    minOffset: Dp,
    maxOffset: Dp,
    offsetXProvider: () -> Dp,
    offsetYProvider: () -> Dp,
    offsetXUpdater: (Dp) -> Unit,
    offsetYUpdater: (Dp) -> Unit,
    dragX: Float,
    dragY: Float,
) {
    if (!params.isHorizontal) {
        val newOffsetY = (offsetYProvider() + dragY.toDp()).coerceIn(minOffset, maxOffset)
        offsetYUpdater(newOffsetY)
        return
    }
    val newOffsetX = (offsetXProvider() + dragX.toDp()).coerceIn(minOffset, maxOffset)
    offsetXUpdater(newOffsetX)
    val reachedExit = (newOffsetX / params.cellWidth).roundToInt() +
        params.block.dimension.width - 1 >= params.levelData.exit.x
    if (params.isMainBlock && params.block.position.y == params.levelData.exit.y && reachedExit) {
        params.onLevelWon()
    }
}
fun Modifier.blockDragGestures(
    block: Block,
    levelData: LevelData,
    isLevelWon: Boolean,
    cellWidth: Dp,
    cellHeight: Dp,
    isMainBlock: Boolean,
    onLevelWon: () -> Unit,
    onLevelChanged: (LevelData) -> Unit,
    index: Int,
    offsetXProvider: () -> Dp,
    offsetYProvider: () -> Dp,
    offsetXUpdater: (Dp) -> Unit,
    offsetYUpdater: (Dp) -> Unit
): Modifier {
    val isHorizontal = block.dimension.width > block.dimension.height
    val params = DragParams(
        block,
        levelData,
        isHorizontal,
        isMainBlock,
        cellWidth,
        cellHeight,
        onLevelWon,
        onLevelChanged,
        index,
    )
    return pointerInput(block, levelData, isLevelWon) {
        if (isLevelWon || block.fixed) return@pointerInput

        var minOffset = 0.dp
        var maxOffset = 0.dp

        detectDragGestures(
            onDragStart = {
                val range = findMovementRange(
                    block,
                    levelData.blocks - block,
                    levelData,
                    isMainBlock,
                    isHorizontal,
                )
                val cellSize = if (isHorizontal) cellWidth else cellHeight
                minOffset = cellSize * range.first
                maxOffset = cellSize * range.last
            },
            onDragEnd = {
                settleAfterDrag(params, offsetXProvider, offsetYProvider, offsetXUpdater, offsetYUpdater)
            },
            onDrag = { change, dragAmount ->
                change.consume()
                handleDragDelta(
                    params,
                    minOffset,
                    maxOffset,
                    offsetXProvider,
                    offsetYProvider,
                    offsetXUpdater,
                    offsetYUpdater,
                    dragAmount.x,
                    dragAmount.y,
                )
            }
        )
    }
}

fun isMoveValid(movedBlock: Block, otherBlocks: List<Block>, dimension: Dimension): Boolean {
    if (movedBlock.position.x < 0 || movedBlock.position.y < 0) return false
    if (movedBlock.position.x + movedBlock.dimension.width > dimension.width) return false
    if (movedBlock.position.y + movedBlock.dimension.height > dimension.height) return false

    return otherBlocks.none { other ->
        movedBlock.position.x < other.position.x + other.dimension.width &&
        movedBlock.position.x + movedBlock.dimension.width > other.position.x &&
        movedBlock.position.y < other.position.y + other.dimension.height &&
        movedBlock.position.y + movedBlock.dimension.height > other.position.y
    }
}

package com.vayunmathur.library.ui

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Column spec for a grid that grows with the window instead of fixing its
 * column count.
 *
 * Hand-rolled column counts (`GridCells.Fixed(2)`, settings-derived counts)
 * look right at exactly one width: a phone grid stretched across a desktop
 * window shows a few giant tiles, and a desktop-tuned count crushes a phone
 * into slivers. An adaptive minimum cell size keeps tiles at a readable size
 * on every width, from a compact phone to a freeform desktop window.
 *
 * [minCellSize] is the smallest a tile may be — the grid fits as many columns
 * of at least that size as the window holds. Pick it from the content: ~160dp
 * for media tiles (video rows, photo thumbs), ~280dp for text cards.
 */
@Composable
fun adaptiveGridCells(minCellSize: Dp = 160.dp): GridCells = GridCells.Adaptive(minCellSize)

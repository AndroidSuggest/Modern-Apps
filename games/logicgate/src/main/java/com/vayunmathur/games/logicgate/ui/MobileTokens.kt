package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 1: Tokens & Responsive Scaffold
 * Centralizes mobile-first sizing and typography.
 * All values match plan spec for 48dp targets and 13sp+ readability.
 */
object MobileDimens {
    val topBarH = 56.dp
    val topBarHorizPad = 16.dp
    val filterRowH = 48.dp
    val filterChipH = 40.dp
    val filterChipSpacing = 10.dp
    val filterRowHorizPad = 16.dp
    val inventoryH = 80.dp
    val inventoryContentPad = 16.dp
    val inventorySpacing = 12.dp
    val chipMinW = 88.dp
    val chipMinH = 56.dp
    val chipHorizPad = 14.dp
    val chipVertPad = 12.dp
    val chipRadius = 12.dp
    val chipBusDot = 8.dp
    val chipAccentW = 4.dp
    val toolbarBtn = 48.dp
    val toolbarWidth = 56.dp
    val toolbarRadius = 16.dp
    val toolbarInnerPad = 6.dp
    val toolbarSpacing = 8.dp
    val toolbarBtnRadius = 10.dp
    val bottomSheetHandle = 48.dp
    val bitDot = 18.dp
    val bitDotSpacing = 6.dp
    val pinHitR = 36.dp
    val termDotR = 32.dp
    val wireHit = 40.dp
    val ioStripH = 52.dp
    val ioSheetPeek = 0.dp
    val ioSheetCardRadius = 12.dp
    val ioSheetCardPad = 16.dp
    val ioRowH = 56.dp
    val leftPanelW = 168.dp
    val leftPanelCardPad = 12.dp
    val rightTabW = 52.dp
    val badgeRadius = 8.dp
    val testbenchHeaderH = 48.dp
    val testbenchRowH = 44.dp
    val testbenchPad = 16.dp

    // Progression
    val progNodePhone = 84.dp
    val progNodeTablet = 96.dp
    val progRowPhone = 132.dp
    val progRowTablet = 144.dp
    val progChapterBar = 14.dp
    val progChapterContainer = 72.dp
    val progConnectorH = 44.dp
    val progBusDot = 8.dp
    val progSpacingDual = 72.dp

    // Canvas mobile – v2 enlarged terminals with outside pins
    val gateMinW = 88.dp
    val gateMinH = 38.dp
    val gateDotVisual = 12.dp
    val gateDotHit = 28.dp
    val termDiameter = 88.dp
    val termEdgeDot = 14.dp
    val termMinW = 120.dp
    val termMaxW = 160.dp
    val termH = 64.dp
    val termHCompact = 72.dp
    val termHit = 36.dp
    val pinOutside = 14.dp
    val gatePinOutside = 14.dp
    val gridStep = 72f
}

object MobileType {
    val levelTitle = 18.sp
    val gateBadge = 13.sp
    val chipName = 14.sp
    val chipCost = 11.sp
    val ioLabel = 13.sp
    val ioDecimal = 14.sp
    val testbenchLabel = 13.sp
    val testbenchValue = 14.sp
    val filterChipLabel = 14.sp
    val statusBanner = 13.sp
    val chapterName = 16.sp
    val chapterProgress = 13.sp
    val nodeName = 14.sp
    val nodeStatus = 12.sp
    val nodeDisplayBelow = 14.sp
    val nodeBusLabel = 11.sp
    val gateName = 11.sp
    val gateCostBadge = 9.sp
    val termDecimal = 14.sp
    val termName = 10.sp
    val termWidthBadge = 10.sp
}

data class ResponsiveInfo(
    val isCompact: Boolean,
    val isPortrait: Boolean,
    val isTablet: Boolean
)

@Composable
fun rememberResponsiveInfo(
    maxWidth: androidx.compose.ui.unit.Dp,
    maxHeight: androidx.compose.ui.unit.Dp
): ResponsiveInfo {
    val isCompact = maxWidth < 600.dp
    val isPortrait = maxHeight > maxWidth
    val isTablet = maxWidth >= 600.dp
    return ResponsiveInfo(isCompact, isPortrait, isTablet)
}

@Composable
fun BoxWithConstraintsScope.rememberResponsiveFromConstraints(): ResponsiveInfo {
    return rememberResponsiveInfo(maxWidth, maxHeight)
}

/**
 * Alternative helper using LocalConfiguration for places outside BoxWithConstraints
 */
@Composable
fun rememberResponsiveFromConfig(): ResponsiveInfo {
    val cfg = LocalConfiguration.current
    val w = cfg.screenWidthDp.dp
    val h = cfg.screenHeightDp.dp
    val isCompact = w < 600.dp
    val isPortrait = h > w
    val isTablet = w >= 600.dp
    return ResponsiveInfo(isCompact, isPortrait, isTablet)
}

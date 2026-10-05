package com.vayunmathur.games.hub.util

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * XP/Level system mirroring Play Games.
 * Formula: level = floor(sqrt(totalXp / 100)) + 1
 * L1=0xp, L2=100, L3=400, L4=900, L5=1600...
 * 7 games * ~7 achievements avg 25xp ~=1225 XP => L4.
 */
object XpLevelCalculator {

    fun level(totalXp: Int): Int {
        if (totalXp <= 0) return 1
        return floor(sqrt(totalXp.toDouble() / XP_PER_LEVEL_STEP)).toInt() + 1
    }

    fun xpForLevel(level: Int): Int {
        if (level <= 1) return 0
        val l = level - 1
        return l * l * XP_PER_LEVEL_STEP
    }

    fun xpToNextLevel(totalXp: Int): Int {
        val currentLevel = level(totalXp)
        val nextLevelXp = xpForLevel(currentLevel + 1)
        return nextLevelXp - totalXp
    }

    fun progressToNextLevel(totalXp: Int): Float {
        val currentLevel = level(totalXp)
        val currentLevelXp = xpForLevel(currentLevel)
        val nextLevelXp = xpForLevel(currentLevel + 1)
        val range = (nextLevelXp - currentLevelXp).toFloat()
        if (range <= 0f) return 1f
        return ((totalXp - currentLevelXp).toFloat() / range).coerceIn(0f, 1f)
    }

    fun title(level: Int): String = when {
        level >= LEGEND_LEVEL -> "Legend"
        level >= GRANDMASTER_LEVEL -> "Grandmaster"
        level >= MASTER_LEVEL -> "Master"
        level >= ENTHUSIAST_LEVEL -> "Enthusiast"
        level >= CASUAL_LEVEL -> "Casual Gamer"
        level >= NOVICE_LEVEL -> "Novice"
        else -> "Beginner"
    }

    private const val XP_PER_LEVEL_STEP = 100
    private const val LEGEND_LEVEL = 25
    private const val GRANDMASTER_LEVEL = 18
    private const val MASTER_LEVEL = 12
    private const val ENTHUSIAST_LEVEL = 8
    private const val CASUAL_LEVEL = 4
    private const val NOVICE_LEVEL = 2
}

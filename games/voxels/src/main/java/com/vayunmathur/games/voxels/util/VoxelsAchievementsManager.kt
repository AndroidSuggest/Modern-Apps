package com.vayunmathur.games.voxels.util

import android.content.Context
import com.vayunmathur.library.util.AchievementsManager

class VoxelsAchievementsManager(context: Context, json: String) : AchievementsManager(context, json) {
    override fun checkExistingAchievements() {}
}

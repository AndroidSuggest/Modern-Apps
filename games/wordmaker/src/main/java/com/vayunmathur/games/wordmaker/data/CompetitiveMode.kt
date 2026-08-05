package com.vayunmathur.games.wordmaker.data

/**
 * Whether the player is on the endless casual progression, the timed competitive ladder, or the
 * single date-seeded daily puzzle.
 */
enum class GameMode {
    CASUAL,
    COMPETITIVE,
    DAILY,
}

/**
 * Competitive difficulty. [timeLimitSeconds] is the countdown the player races against; harder
 * levels give a bigger [winDelta] but also cost a bigger [lossDelta] when the timer runs out.
 */
enum class Difficulty(
    val timeLimitSeconds: Int,
    val winDelta: Int,
    val lossDelta: Int,
) {
    EASY(timeLimitSeconds = 120, winDelta = 10, lossDelta = 5),
    MEDIUM(timeLimitSeconds = 45, winDelta = 25, lossDelta = 15),
    HARD(timeLimitSeconds = 20, winDelta = 50, lossDelta = 30),
}

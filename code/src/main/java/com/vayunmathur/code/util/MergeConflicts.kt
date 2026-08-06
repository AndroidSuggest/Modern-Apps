package com.vayunmathur.code.util

/** How a single merge conflict should be resolved. */
enum class Resolution { OURS, THEIRS, BOTH }

/**
 * One Git merge conflict block. [startLine]/[endLine] are 0-based, inclusive line indices spanning
 * the `<<<<<<<` … `>>>>>>>` markers; [ours] is the current-branch side, [theirs] the incoming side.
 */
data class Conflict(
    val startLine: Int,
    val endLine: Int,
    val ours: List<String>,
    val theirs: List<String>,
)

private const val OURS_MARKER = "<<<<<<<"
private const val SEPARATOR_MARKER = "======="
private const val THEIRS_MARKER = ">>>>>>>"

/**
 * Pure detection + resolution of Git merge-conflict markers. Kept out of the UI so both are
 * unit-tested directly.
 */

/** Finds every well-formed conflict block in [text]. Malformed/unterminated blocks are ignored. */
fun parseConflicts(text: String): List<Conflict> {
    val lines = text.split("\n")
    val out = ArrayList<Conflict>()
    var i = 0
    while (i < lines.size) {
        if (!lines[i].startsWith(OURS_MARKER)) {
            i++
            continue
        }
        val start = i
        var sep = -1
        var end = -1
        var j = i + 1
        while (j < lines.size) {
            when {
                sep == -1 && lines[j].startsWith(SEPARATOR_MARKER) -> sep = j
                lines[j].startsWith(THEIRS_MARKER) -> {
                    end = j
                    break
                }
                lines[j].startsWith(OURS_MARKER) -> break // a new conflict started; this one is malformed
            }
            j++
        }
        if (sep != -1 && end != -1) {
            out.add(
                Conflict(
                    startLine = start,
                    endLine = end,
                    ours = lines.subList(start + 1, sep).toList(),
                    theirs = lines.subList(sep + 1, end).toList(),
                ),
            )
            i = end + 1
        } else {
            i = start + 1
        }
    }
    return out
}

/**
 * Rewrites [text], replacing each conflict block with the chosen side. [resolutions] is applied in
 * document order; if it is shorter than the number of conflicts, the remaining blocks are left
 * untouched. [Resolution.BOTH] keeps ours followed by theirs.
 */
fun applyResolutions(text: String, resolutions: List<Resolution>): String {
    val conflicts = parseConflicts(text)
    if (conflicts.isEmpty()) return text
    val lines = text.split("\n")
    val out = ArrayList<String>(lines.size)
    var i = 0
    var ci = 0
    while (i < lines.size) {
        val conflict = conflicts.getOrNull(ci)
        if (conflict != null && i == conflict.startLine) {
            val resolution = resolutions.getOrNull(ci)
            if (resolution == null) {
                // No choice supplied: keep the original block verbatim.
                out.addAll(lines.subList(conflict.startLine, conflict.endLine + 1))
            } else {
                out.addAll(
                    when (resolution) {
                        Resolution.OURS -> conflict.ours
                        Resolution.THEIRS -> conflict.theirs
                        Resolution.BOTH -> conflict.ours + conflict.theirs
                    },
                )
            }
            i = conflict.endLine + 1
            ci++
        } else {
            out.add(lines[i])
            i++
        }
    }
    return out.joinToString("\n")
}

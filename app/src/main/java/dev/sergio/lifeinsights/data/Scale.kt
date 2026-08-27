package dev.sergio.lifeinsights.data

/**
 * The subjective scale used for both mood and energy.
 *
 * Seven signed points: one tap, an unambiguous neutral at zero, and enough distinct values that a
 * few weeks of entries are not dominated by ties. A five-point scale logs faster but leaves
 * correlations badly underpowered at this sample size; a slider adds precision that is mostly noise
 * and invites anchoring to the previous answer.
 */
object Scale {
    const val MIN = -3
    const val MAX = 3
    val VALUES = (MIN..MAX).toList()

    fun moodLabel(value: Int): String = when (value) {
        -3 -> "Very low"
        -2 -> "Low"
        -1 -> "Slightly low"
        0 -> "Neutral"
        1 -> "Slightly good"
        2 -> "Good"
        else -> "Very good"
    }

    fun energyLabel(value: Int): String = when (value) {
        -3 -> "Drained"
        -2 -> "Tired"
        -1 -> "Sluggish"
        0 -> "Neutral"
        1 -> "Alert"
        2 -> "Energised"
        else -> "Wired"
    }
}

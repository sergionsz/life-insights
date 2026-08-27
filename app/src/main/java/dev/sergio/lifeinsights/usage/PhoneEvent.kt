package dev.sergio.lifeinsights.usage

/**
 * A phone interaction event, stripped of every Android type.
 *
 * `UsageEvents.Event` cannot be constructed in a unit test, so the platform-specific reading lives
 * in [UsageStatsSource] and everything that decides what the numbers mean works on this instead.
 * The aggregation rules are where the bugs would be, so they are the part that has to be testable.
 */
data class PhoneEvent(
    val timestampUtc: Long,
    val type: PhoneEventType,
    val packageName: String? = null,
)

enum class PhoneEventType {
    /** An activity came to the foreground. */
    ACTIVITY_RESUMED,

    /** An activity left the foreground. */
    ACTIVITY_PAUSED,

    /** The device was unlocked; this is what a "pickup" counts. */
    KEYGUARD_HIDDEN,

    /** The lock screen appeared. */
    KEYGUARD_SHOWN,

    /** The screen turned on but may still be locked. */
    SCREEN_INTERACTIVE,

    /** The screen turned off. */
    SCREEN_NON_INTERACTIVE,

    /** The system observed a direct user interaction, such as a notification tap. */
    USER_INTERACTION,
    ;

    /**
     * Whether this event is evidence a person was touching the phone.
     *
     * The sleep proxy is built entirely on the absence of these, so it matters that screen-off and
     * lock events do not count: the screen turning itself off is exactly what happens when someone
     * puts the phone down and goes to sleep.
     */
    val indicatesInteraction: Boolean
        get() = this == ACTIVITY_RESUMED || this == KEYGUARD_HIDDEN ||
            this == SCREEN_INTERACTIVE || this == USER_INTERACTION
}

/** A continuous stretch of one app being in the foreground. */
data class ForegroundSession(
    val packageName: String,
    val startUtc: Long,
    val endUtc: Long,
) {
    val durationMillis: Long get() = (endUtc - startUtc).coerceAtLeast(0)
}

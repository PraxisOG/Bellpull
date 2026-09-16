package org.praxisog.hermes

import kotlin.math.roundToLong

/**
 * Relative time for a session's last activity. Hermes sends epoch seconds as a Double.
 *
 * [now] is a parameter rather than read from the clock so the output is deterministic — the
 * screenshot tests depend on this, and a formatter that reads the clock cannot be tested.
 */
internal fun formatLastActive(epochSeconds: Double?, now: Double): String {
    if (epochSeconds == null) return "never"
    val delta = (now - epochSeconds).roundToLong()
    return when {
        delta < 0L -> "just now" // clock skew between phone and host; don't render the future
        delta < 60L -> "just now"
        delta < 3_600L -> "${delta / 60L}m ago"
        delta < 86_400L -> "${delta / 3_600L}h ago"
        delta < 2_592_000L -> "${delta / 86_400L}d ago"
        else -> "${delta / 2_592_000L}mo ago"
    }
}

/** "1 message", not "1 messages". */
internal fun pluralize(count: Int, singular: String, plural: String = singular + "s"): String =
    if (count == 1) "$count $singular" else "$count $plural"

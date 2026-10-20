package org.praxisog.hermes

/** Monotonic retry bound. A loaded model never consults this gate. */
internal class ModelLoadCooldown(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var attemptedAt: Long? = null
    @Synchronized fun begin(explicit: Boolean = false): Boolean {
        val now = clock()
        val previous = attemptedAt
        if (!explicit && previous != null && now - previous < 10_000) return false
        attemptedAt = now
        return true
    }
    @Synchronized fun reset() { attemptedAt = null }
}

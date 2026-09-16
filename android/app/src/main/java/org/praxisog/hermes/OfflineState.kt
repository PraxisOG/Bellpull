package org.praxisog.hermes

import org.praxisog.hermes.model.HermesError

/**
 * Set when the session list on screen came from [SessionCache] rather than from Hermes.
 *
 * Its whole job is to make that visible. "No silent fallback anywhere" is a project
 * non-negotiable, and showing cached sessions as though they were live is exactly that kind
 * of silence — the user would have no way to tell a quiet network from a quiet agent.
 */
internal data class OfflineState(
    val cachedAtEpochSeconds: Double,
    /** Why the live fetch failed. Named, so the banner can say which layer broke. */
    val error: HermesError,
)

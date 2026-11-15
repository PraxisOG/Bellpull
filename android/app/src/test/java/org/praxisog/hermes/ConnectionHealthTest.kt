package org.praxisog.hermes

import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.HermesError
import org.praxisog.hermes.model.HermesResult

class ConnectionHealthTest {
    private val timeout = HermesResult.Err(HermesError.Unreachable(HermesError.Unreachable.Kind.TIMEOUT, "synthetic"))
    private val ok = HermesResult.Ok(Unit)

    @Test fun aSingleCellularTimeoutDoesNotOfferLocalOrDisableRemoteChat() {
        val initial = ConnectionHealth().record(ok, 0, 50)
        val failed = initial.record(timeout, 17_000, 5000)
        assertEquals(ConnectionHealth.Phase.RECONNECTING, failed.phase)
        assertFalse(failed.unavailable)
        assertFalse(failed.localEligible)
        assertEquals(2000, failed.nextProbeMs)
        val recovered = failed.record(ok, 19_150, 150)
        assertEquals(ConnectionHealth.Phase.ONLINE, recovered.phase)
        assertEquals(0, recovered.failures)
    }

    @Test fun thirtySecondOutageNeverBecomesAnAutomaticLocalModelLoad() {
        var health = ConnectionHealth().record(ok, 0, 50)
        for (seconds in 1..30) {
            health = health.record(timeout, seconds * 1000L, 100)
            assertFalse(health.localEligible)
            if (seconds >= 11) assertTrue(health.offline)
        }
        health = health.record(ok, 30_100, 100)
        assertFalse(health.unavailable)
        assertFalse(health.localEligible)
    }

    @Test fun flappingDoesNotAccumulateFailuresAcrossSuccessfulProbes() {
        var health = ConnectionHealth()
        repeat(30) {
            health = health.record(timeout, it * 20_000L, 5000)
            assertFalse(health.offline)
            health = health.record(ok, it * 20_000L + 7000, 200)
        }
        assertFalse(health.localEligible)
    }

    @Test fun confirmedLongOutageAllowsOnlyTheExplicitlyEnabledLocalWarmup() {
        var health = ConnectionHealth()
        for (seconds in 0..60 step 2) health = health.record(timeout, seconds * 1000L, 100)
        assertTrue(health.localEligible)
        assertFalse(LocalFallbackConfiguration().autoLoad)
        assertFalse(health.record(ok, 60_200, 200).localEligible)
    }

    @Test fun authenticationTlsThrottlingAndContractErrorsNeverBecomeOfflineFallback() {
        val errors = listOf(HermesError.AuthFailed("synthetic"), HermesError.RateLimited("busy", 45),
            HermesError.Unreachable(HermesError.Unreachable.Kind.TLS, "certificate"),
            HermesError.UnexpectedRedirect(302, null), HermesError.MalformedResponse("invalid"))
        for (error in errors) {
            var health = ConnectionHealth()
            repeat(20) { health = health.record(HermesResult.Err(error), it * 10_000L, 20) }
            assertEquals(ConnectionHealth.Phase.ATTENTION, health.phase)
            assertTrue(health.unavailable)
            assertFalse(health.offline)
            assertFalse(health.localEligible)
            if (error is HermesError.RateLimited) assertEquals(45_000, health.nextProbeMs)
        }
    }

    @Test fun serverOutageRequiresRepeatedEvidenceAndRecoversOnOneSuccess() {
        val failure = HermesResult.Err(HermesError.ServerError(503, "unavailable", null))
        var health = ConnectionHealth().record(failure, 0, 5)
        health = health.record(failure, 20_000, 5)
        assertFalse(health.offline) // elapsed time alone is insufficient
        health = health.record(failure, 22_000, 5)
        assertTrue(health.offline)
        assertEquals(ConnectionHealth.Phase.ONLINE, health.record(ok, 22_050, 50).phase)
    }
}

package org.praxisog.hermes.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RunModelRoutingTest {
    @Test fun inheritedTextRouteCanFallBackWhileFilesAndExplicitSelectionsStayPinned() {
        val route = ModelSelection("primary", "provider")
        val inherited = SessionModel("chat", route, followsServerRoute = true)
        assertNull(inherited.runOptions(emptyList()))
        assertEquals(BellpullRunOptions(selection = route, artifactIds = listOf("file")), inherited.runOptions(listOf("file")))
        val explicit = inherited.copy(followsServerRoute = false)
        assertEquals(route, explicit.runOptions(emptyList())?.selection)
        val oldHost = Json.decodeFromString<SessionModel>("""{"session_id":"chat","selection":{"model":"primary","provider":"provider"}}""")
        assertEquals(explicit, oldHost)
    }
}

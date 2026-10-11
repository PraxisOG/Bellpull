package org.praxisog.hermes.core

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.*

class ProjectsContractTest {
    @Test fun incompleteAndAmbiguousSnapshotsAreRejectedInsteadOfReplacingOrganization() {
        val invalid = listOf("{}", """{"version":1,"revision":0,"projects":[]}""",
            """{"version":2,"revision":0,"projects":[],"memberships":[]}""",
            """{"version":1,"revision":0,"projects":[],"memberships":[{"session_id":"root","project_id":"missing"}]}""",
            """{"version":1,"revision":0,"projects":[],"memberships":[{"session_id":"root","session_ids":["root","tip"]},{"session_id":"tip"}]}""")
        for (body in invalid) {
            val client = HermesClient("https://fixture.invalid", { "fixture" }, httpClient = HermesClient.defaultHttpClient().newBuilder()
                .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build() }.build())
            try { assertTrue(client.projects().errorOrNull() is HermesError.MalformedResponse) } finally { client.close() }
        }
    }
    @Test fun discoveryMutationAndCreationKeepProfileAndWireIdentities() {
        val requests = mutableListOf<Request>()
        val project = "a".repeat(32)
        val client = HermesClient("https://fixture.invalid/p/work", { "fixture" }, httpClient = HermesClient.defaultHttpClient().newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().also { requests += it }
                assertEquals("Bearer fixture", request.header("Authorization"))
                assertTrue(request.url.encodedPath.startsWith("/p/work/"))
                val body = when (request.url.encodedPath.removePrefix("/p/work")) {
                    "/v1/capabilities" -> """{"features":{"session_chat_streaming":true}}"""
                    "/api/bellpull/capabilities" -> """{"version":1,"plugin_version":"0.3.0","features":{"bellpull_projects":true}}"""
                    "/api/bellpull/projects" -> {
                        if (request.method == "POST") {
                            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                            val json = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                            assertEquals("move", json["operation"]!!.jsonPrimitive.content)
                            assertEquals(4, json["expected_revision"]!!.jsonPrimitive.int)
                            assertEquals("root", json["session_id"]!!.jsonPrimitive.content)
                            assertEquals(project, json["project_id"]!!.jsonPrimitive.content)
                        }
                        """{"version":1,"revision":5,"projects":[{"id":"$project","name":"Work"}],"memberships":[{"session_id":"root","session_ids":["root","tip"],"project_id":"$project","pinned":true}]}"""
                    }
                    "/api/bellpull/projects/$project/sessions" -> {
                        val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                        val json = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                        assertEquals("api_once", json["id"]!!.jsonPrimitive.content)
                        assertEquals(5, json["expected_revision"]!!.jsonPrimitive.int)
                        assertEquals("model", json["model"]!!.jsonPrimitive.content)
                        assertTrue(json["require_model_lock"]!!.jsonPrimitive.boolean)
                        assertFalse("project_id" in json || "selection" in json)
                        """{"session":{"id":"api_once","source":"api_server"}}"""
                    }
                    else -> error("Unexpected request")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build())
        try {
            assertTrue((client.capabilities() as HermesResult.Ok).value.features["bellpull_projects"] == true)
            val snapshot = (client.projects() as HermesResult.Ok).value
            assertEquals(project, snapshot.projectFor("tip"))
            assertTrue(snapshot.membership("tip")!!.pinned)
            assertTrue(client.mutateProject(ProjectMutation("move", "b".repeat(32), 4, project, "root")) is HermesResult.Ok)
            assertEquals("api_once", (client.createProjectChat(PendingProjectChat("api_once", project, 5,
                selection = ModelSelection("model", "provider"))) as HermesResult.Ok).value.id)
            assertEquals(5, requests.size)
        } finally { client.close() }
    }
}

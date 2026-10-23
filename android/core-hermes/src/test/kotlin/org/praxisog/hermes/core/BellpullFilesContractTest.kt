package org.praxisog.hermes.core

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.*
import java.nio.file.Files
import kotlinx.serialization.json.*

class BellpullFilesContractTest {
    private val id = "a".repeat(32)
    private val bytes = byteArrayOf(0, 1, 2, 3, -1)
    private val hash get() = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun response(request: Request, content: ByteArray) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(content.toResponseBody("application/octet-stream".toMediaType())).build()
    private fun client(handler: (Request) -> Response) = HermesClient("http://127.0.0.1:1", { "fixture-only" },
        httpClient = HermesClient.defaultHttpClient().newBuilder().addInterceptor { handler(it.request()) }.build())

    @Test fun nativeModelCapabilityDoesNotEnableTheLegacyAttachmentExtension() {
        val api = client { request ->
            val body = if (request.url.encodedPath == "/v1/capabilities")
                """{"features":{"run_submission":true}}"""
            else """{"version":1,"features":{"bellpull_native_model_controls":true,"bellpull_model_controls":false,"bellpull_run_attachments":false}}"""
            response(request, body.toByteArray())
        }
        try {
            val caps = (api.capabilities() as HermesResult.Ok).value
            assertTrue(caps.features["bellpull_native_model_controls"] == true)
            assertFalse(caps.features["bellpull_model_controls"] == true)
            assertFalse(caps.features["bellpull_run_attachments"] == true)
            assertTrue(caps.features["run_submission"] == true)
        } finally { api.close() }
    }

    @Test fun serverCompressionAliasesKeepFilesInBothOldAndContinuedConversationFilters() {
        val api = client { request -> response(request, """{"data":[{"id":"$id","session_id":"root","session_ids":["root","middle","tip"],"message_id":10,"name":"answer.txt","mime":"text/plain","size":5,"sha256":"$hash","created_at":100,"source":"agent"}]}""".toByteArray()) }
        try {
            val row = (api.artifacts() as HermesResult.Ok).value.data.single()
            for (session in listOf("root", "middle", "tip")) assertNotNull(row.forSession(session))
            assertNull(row.forSession("branch"))
            assertNull(row.forSession("tip")!!.messageId) // Never invent a mapped message association.
            assertEquals(10, row.forSession("root")!!.messageId)
        } finally { api.close() }
    }

    @Test fun uploadPreservesBytesEncodedNamesAuthenticationAndProgress() {
        val file = Files.createTempFile("bellpull", ".bin").toFile().apply { writeBytes(bytes) }
        val progress = mutableListOf<Long>()
        val api = client { request ->
            assertEquals("Bearer fixture-only", request.header("Authorization"))
            assertEquals("résumé & notes.pdf", request.url.queryParameter("name"))
            assertEquals("chat_one", request.url.queryParameter("session_id"))
            val sent = Buffer(); request.body!!.writeTo(sent)
            assertArrayEquals(bytes, sent.readByteArray())
            response(request, """{"id":"$id","session_id":"chat_one","name":"résumé & notes.pdf","mime":"application/pdf","size":5,"sha256":"$hash","created_at":100,"source":"upload","available":true}""".toByteArray())
        }
        try {
            val result = api.uploadArtifact(file, DraftFile(id, "chat_one", "résumé & notes.pdf", "application/pdf", 5), {}, progress::add)
            assertTrue(result is HermesResult.Ok)
            assertEquals(5L, progress.last())
        } finally { api.close(); file.delete() }
    }

    @Test fun downloadRejectsChangedBytesAndAcceptsOriginals() {
        val artifact = Artifact(id, "chat", "fixture.pdf", "application/pdf", 5, hash, 100.0, "upload")
        val file = Files.createTempFile("bellpull-download", ".tmp").toFile()
        for (data in listOf(bytes, byteArrayOf(4, 3, 2, 1, 0), byteArrayOf(1, 2))) {
            val api = client { response(it, data) }
            try {
                val result = api.downloadArtifact(artifact, file) {}
                assertEquals(data === bytes, result is HermesResult.Ok)
            } finally { api.close() }
        }
        file.delete()
    }

    @Test fun submissionCarriesExplicitVersionAndThinkingOffWithoutInventedEffort() {
        val api = client { request ->
            val sink = Buffer(); request.body!!.writeTo(sink)
            val body = Json.parseToJsonElement(sink.readUtf8()).jsonObject
            val bellpull = body.getValue("bellpull").jsonObject
            assertEquals(1, bellpull.getValue("version").jsonPrimitive.int)
            val reasoning = bellpull.getValue("selection").jsonObject.getValue("model_options").jsonObject.getValue("reasoning").jsonObject
            assertFalse(reasoning.getValue("enabled").jsonPrimitive.boolean)
            assertFalse(reasoning.containsKey("effort"))
            assertEquals("qwen38-flash-next", body.getValue("model").jsonPrimitive.content)
            assertEquals("custom", body.getValue("provider").jsonPrimitive.content)
            assertEquals(bellpull.getValue("selection").jsonObject.getValue("model_options"), body.getValue("model_options"))
            assertTrue(body.getValue("require_model_lock").jsonPrimitive.boolean)
            assertEquals(id, bellpull.getValue("artifact_ids").jsonArray.single().jsonPrimitive.content)
            response(request, """{"run_id":"run_fixture","status":"started"}""".toByteArray())
        }
        try { assertTrue(api.submitRun("chat", "hello", "stable-id", bellpull = BellpullRunOptions(
            selection = ModelSelection("qwen38-flash-next", "custom", ModelOptions(ReasoningChoice(false))), artifactIds = listOf(id))) is HermesResult.Ok) }
        finally { api.close() }
    }
    @Test fun effortAndNativeSelectionRemainIdenticalAcrossAdmissionRetries() {
        val bodies = mutableListOf<String>()
        val api = client { request ->
            val sink = Buffer(); request.body!!.writeTo(sink); bodies += sink.readUtf8()
            val body = Json.parseToJsonElement(bodies.last()).jsonObject
            val selected = body.getValue("bellpull").jsonObject.getValue("selection").jsonObject
            assertEquals(selected.getValue("model_options"), body.getValue("model_options"))
            assertEquals("xhigh", body.getValue("model_options").jsonObject.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
            assertEquals("stable-effort-id", request.header("Idempotency-Key"))
            response(request, """{"run_id":"run_fixture","status":"started"}""".toByteArray())
        }
        try {
            val options = BellpullRunOptions(selection = ModelSelection("qwen38-flash-next", "custom", ModelOptions(ReasoningChoice(effort = "xhigh"))))
            repeat(2) { assertTrue(api.submitRun("chat", "hello", "stable-effort-id", bellpull = options) is HermesResult.Ok) }
            assertEquals(bodies[0], bodies[1])
        } finally { api.close() }
    }
}

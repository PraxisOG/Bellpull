package org.praxisog.hermes.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.praxisog.hermes.model.*

class CodingContractTest {
    @Test fun authenticatedReadRequestsKeepNativeProfileAndCursorAndNeverSubmitWork() {
        val paths=mutableListOf<String>()
        val id="a".repeat(32); val file="b".repeat(64)
        val client=HermesClient("https://fixture.invalid/p/work/",{"fixture"},httpClient=HermesClient.defaultHttpClient().newBuilder()
            .addInterceptor { chain ->
                val request=chain.request(); paths += request.url.encodedPath
                assertEquals("GET",request.method); assertEquals("Bearer fixture",request.header("Authorization"))
                val body=if (request.url.encodedPath.endsWith("/coding")) {
                    assertEquals("4",request.url.queryParameter("after"))
                    Json.encodeToString(CodingSnapshot(1,"root",listOf("root"),4,false,false,works=emptyList(),rows=emptyList(),observedAt=1.0))
                } else {
                    assertEquals(file,request.url.queryParameter("file"))
                    Json.encodeToString(CodingWorkspaceSnapshot(1,"Workspace changes",listOf(CodingFile(file,"hello.kt")),file=CodingFile(file,"hello.kt"),diff="+hello"))
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build())
        try {
            assertTrue(client.codingActivity("root",4) is HermesResult.Ok)
            assertEquals("+hello",(client.codingWorkspace(id,file) as HermesResult.Ok).value.diff)
            assertEquals(listOf("/p/work/api/bellpull/sessions/root/coding","/p/work/api/bellpull/coding/$id/workspace"),paths)
            assertThrows(IllegalArgumentException::class.java) { client.codingWorkspace("../host") }
        } finally { client.close() }
    }
}

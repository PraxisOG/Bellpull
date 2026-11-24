package org.praxisog.hermes.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject

@Serializable data class LiveFile(
    val id: String, val path: String, val name: String, val access: String = "read", val pinned: Boolean = false,
    @SerialName("referenced_at") val referencedAt: Double = 0.0,
    @SerialName("accessed_at") val accessedAt: Double = 0.0,
    @SerialName("reference_revision") val referenceRevision: Long = 1,
)
@Serializable data class LiveFileList(val version: Int = 1, val files: List<LiveFile> = emptyList(),
    @SerialName("path_roots") val pathRoots: List<String> = emptyList())
@Serializable data class LiveFileTask(val line: Int, val text: String, val checked: Boolean)
@Serializable data class LiveFileSnapshot(val version: Int = 1, val id: String, val text: String,
    val etag: String, val access: String, val tasks: List<LiveFileTask> = emptyList(),
    @SerialName("source_path") val sourcePath: String? = null) {
    fun validated(expected: String): LiveFileSnapshot = apply {
        require(version == 1 && id == expected && text.length <= 2 * 1024 * 1024)
        require(access in setOf("read", "checklist") && etag.matches(Regex("\"[a-f0-9]{64}\"")))
        require(tasks.size <= text.length && tasks.map { it.line }.distinct().size == tasks.size)
        val lines = text.count { it == '\n' } + 1
        require(tasks.all { it.line in 0 until lines })
    }
}
@Serializable data class PendingFileEdit(val fileId: String, val body: JsonObject)

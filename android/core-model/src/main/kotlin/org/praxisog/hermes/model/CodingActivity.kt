package org.praxisog.hermes.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CodingWork(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("run_id") val runId: String,
    @SerialName("process_id") val processId: String,
    @SerialName("open_code_session_id") val openCodeSessionId: String? = null,
    val transport: String = "cli_json",
    val state: String,
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("workspace_label") val workspaceLabel: String? = null,
    @SerialName("started_at") val startedAt: Double = 0.0,
    @SerialName("exit_code") val exitCode: Int? = null,
    val gap: Boolean = false,
    val note: String = "",
) { override fun toString() = "CodingWork(state=$state, identities=redacted)" }

@Serializable
data class CodingRow(
    val sequence: Long,
    @SerialName("work_id") val workId: String,
    val key: String,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val kind: String,
    val label: String,
    val detail: String,
    val command: String? = null,
    val outcome: String? = null,
    val truncated: Boolean = false,
) { override fun toString() = "CodingRow(kind=$kind, content=redacted)" }

@Serializable
data class CodingSnapshot(
    val version: Int,
    @SerialName("session_id") val sessionId: String,
    @SerialName("session_ids") val sessionIds: List<String>,
    val cursor: Long,
    val reset: Boolean,
    val gap: Boolean,
    val available: Boolean = true,
    val note: String = "",
    val works: List<CodingWork>,
    val rows: List<CodingRow>,
    @SerialName("observed_at") val observedAt: Double,
) {
    fun validated(requested: String): CodingSnapshot {
        require(version == 1 && sessionId == requested && requested in sessionIds && cursor >= 0)
        require(sessionIds.size in 1..1000 && sessionIds.distinct().size == sessionIds.size)
        require(works.size <= 128 && works.map { it.id }.distinct().size == works.size)
        val ids = works.map { it.id }.toSet()
        works.forEach { require(it.id.matches(Regex("[a-f0-9]{32}")) && it.sessionId in sessionIds &&
            it.runId.isNotBlank() && it.processId.isNotBlank() && it.state in setOf("running", "completed", "failed", "stopped", "lost", "unknown")) }
        require(rows.size <= 300 && rows.zipWithNext().all { (a,b) -> a.sequence < b.sequence })
        rows.forEach { require(it.workId in ids && it.sequence in 1..cursor && it.key.length <= 1024 &&
            it.detail.length <= 32768 && (it.command?.length ?: 0) <= 32768 && it.label.length <= 512) }
        return this
    }
    fun merge(previous: CodingSnapshot?): CodingSnapshot {
        if (reset || previous == null || previous.sessionId != sessionId) return copy(reset = true)
        require(cursor >= previous.cursor)
        val valid = works.map { it.id }.toSet()
        val merged = linkedMapOf<Pair<String,String>, CodingRow>()
        (previous.rows + rows).filter { it.workId in valid }.forEach { merged[it.workId to it.key] = it }
        return copy(rows = merged.values.sortedBy { it.sequence }.takeLast(300), reset = true, gap = gap || previous.gap)
    }
}

@Serializable data class CodingFile(val id: String, val path: String)
@Serializable data class CodingWorkspaceSnapshot(val version: Int, val label: String,
    val files: List<CodingFile>, val note: String = "", val truncated: Boolean = false,
    val file: CodingFile? = null, val diff: String? = null)

package org.praxisog.hermes

import org.praxisog.hermes.model.PendingProjectChat
import org.praxisog.hermes.model.ProjectSnapshot

/** A server snapshot plus local navigation/recovery, never an offline mutation queue. */
internal interface ProjectCache {
    fun readProjects(): ProjectSnapshot? = null
    fun writeProjects(value: ProjectSnapshot): Boolean = true
    fun projectScope(): String = ALL_PROJECT_CHATS
    fun rememberProjectScope(value: String) {}
    fun pendingProjectChat(): PendingProjectChat? = null
    fun writePendingProjectChat(value: PendingProjectChat?): Boolean = true
}

internal const val ALL_PROJECT_CHATS = "*"
internal const val UNFILED_PROJECT_CHATS = "?"

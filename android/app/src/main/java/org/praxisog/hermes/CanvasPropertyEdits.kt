package org.praxisog.hermes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.praxisog.hermes.model.CanvasNode

internal enum class CanvasProperty(val key: String, val title: String) {
    LABEL("label", "Object text"), WIDTH("width", "Width"), HEIGHT("height", "Height");

    fun saved(node: CanvasNode): String = when (this) {
        LABEL -> node.label
        WIDTH -> node.width.toString().removeSuffix(".0")
        HEIGHT -> node.height.toString().removeSuffix(".0")
    }

    fun encode(text: String): JsonPrimitive? = when (this) {
        LABEL -> JsonPrimitive(text)
        WIDTH, HEIGHT -> text.toDoubleOrNull()?.takeIf { it in 40.0..2000.0 }?.let(::JsonPrimitive)
    }
}

internal data class CanvasFieldEdit(val base: String, val text: String)

internal data class CanvasPropertyEdits(private val drafts: Map<CanvasProperty, CanvasFieldEdit> = emptyMap()) {
    fun text(property: CanvasProperty, node: CanvasNode) = drafts[property]?.text ?: property.saved(node)

    fun change(property: CanvasProperty, node: CanvasNode, text: String): CanvasPropertyEdits {
        val saved = property.saved(node)
        if (text == saved) return discard(listOf(property))
        return copy(drafts = drafts + (property to CanvasFieldEdit(drafts[property]?.base ?: saved, text)))
    }

    fun conflicts(node: CanvasNode) = drafts.filter { (property, draft) ->
        val saved = property.saved(node)
        draft.base != saved && property.encode(draft.text) != property.encode(saved)
    }.keys

    fun discard(properties: Collection<CanvasProperty>) = copy(drafts = drafts - properties.toSet())

    // A snapshot can confirm an edit even if the HTTP acknowledgement was lost.
    fun reconcile(node: CanvasNode) = copy(drafts = drafts.filter { (property, draft) ->
        property.encode(draft.text) != property.encode(property.saved(node))
    })

    fun changes(node: CanvasNode): JsonObject? {
        val changed = mutableMapOf<String, JsonPrimitive>()
        for ((property, draft) in drafts) {
            val value = property.encode(draft.text) ?: return null
            if (value != property.encode(property.saved(node))) changed[property.key] = value
        }
        return JsonObject(changed)
    }
}

package com.flowforge.app.model

import org.json.JSONArray
import org.json.JSONObject

/** Bounded command history. Each entry is one completed user operation. */
class HistoryManager(private val capacity: Int = 2000) {
    private data class Entry(val before: String, val after: String)
    private val undo = ArrayDeque<Entry>()
    private val redo = ArrayDeque<Entry>()

    fun record(before: FlowDocument, after: FlowDocument) {
        val b = before.toJson(); val a = after.toJson()
        if (b == a) return
        undo.addLast(Entry(b, a))
        while (undo.size > capacity) undo.removeFirst()
        redo.clear()
    }
    fun canUndo() = undo.isNotEmpty()
    fun canRedo() = redo.isNotEmpty()
    fun undo(current: FlowDocument): FlowDocument? {
        val e = undo.removeLastOrNull() ?: return null
        redo.addLast(e)
        return FlowDocument.fromJson(e.before)
    }
    fun redo(current: FlowDocument): FlowDocument? {
        val e = redo.removeLastOrNull() ?: return null
        undo.addLast(e)
        return FlowDocument.fromJson(e.after)
    }
    fun clear() { undo.clear(); redo.clear() }

    /** Serialize the complete undo/redo stacks for application hibernation. */
    fun toJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("capacity", capacity)
        root.put("undo", entriesToJson(undo))
        root.put("redo", entriesToJson(redo))
        return root.toString()
    }

    private fun entriesToJson(entries: ArrayDeque<Entry>): JSONArray {
        val a = JSONArray()
        entries.forEach { e ->
            a.put(JSONObject().put("before", e.before).put("after", e.after))
        }
        return a
    }

    companion object {
        fun fromJson(json: String, fallbackCapacity: Int = 2000): HistoryManager {
            val result = HistoryManager(fallbackCapacity)
            if (json.isBlank()) return result
            return runCatching {
                val root = JSONObject(json)
                val capacity = root.optInt("capacity", fallbackCapacity).coerceAtLeast(1)
                val restored = HistoryManager(capacity)
                appendEntries(root.optJSONArray("undo"), restored.undo, capacity)
                appendEntries(root.optJSONArray("redo"), restored.redo, capacity)
                restored
            }.getOrElse { result }
        }

        private fun appendEntries(array: JSONArray?, target: ArrayDeque<Entry>, capacity: Int) {
            if (array == null) return
            val start = maxOf(0, array.length() - capacity)
            for (i in start until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val before = item.optString("before", "")
                val after = item.optString("after", "")
                if (before.isNotBlank() && after.isNotBlank()) target.addLast(Entry(before, after))
            }
        }
    }
}

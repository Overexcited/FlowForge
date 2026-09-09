package com.flowforge.app.model

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
}

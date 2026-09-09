package com.flowforge.app.mermaid

import com.flowforge.app.model.*
import java.util.UUID

object Mermaid {
    fun export(d: FlowDocument): String {
        val sb = StringBuilder("flowchart TD\n")
        d.elements.forEach { e ->
            val id = safeId(e.id)
            val label = e.label.replace("\"", "'")
            when (e.type) {
                ElementType.DECISION -> sb.append("    $id{\"$label\"}\n")
                ElementType.TERMINAL -> sb.append("    $id((\"$label\"))\n")
                ElementType.SERVER -> sb.append("    $id[[\"$label\"]]\n")
                ElementType.DATA -> sb.append("    $id[/\"$label\"/]\n")
                else -> sb.append("    $id[\"$label\"]\n")
            }
        }
        d.connections.forEach { c ->
            val a = safeId(c.fromId); val b = safeId(c.toId)
            val arrow = when (c.arrowType) {
                ArrowType.NONE -> "--"
                ArrowType.BOTH -> "<-->"
                else -> "-->"
            }
            val edge = if (c.label.isBlank()) "$a $arrow $b" else "$a $arrow|${c.label.replace("|","/")}| $b"
            sb.append("    $edge\n")
        }
        return sb.toString()
    }

    fun import(text: String): FlowDocument {
        val d = FlowDocument("Imported Mermaid")
        val nodes = linkedMapOf<String, FlowElement>()
        val nodeRegex = Regex("""^\s*([A-Za-z0-9_-]+)\s*(?:\(\(|\{\{|\{\[|\[|/)?["']?([^"\]\(\{}/]+)["']?.*$""")
        val edgeRegex = Regex("""([A-Za-z0-9_-]+)\s+(?:-->|---|-.->|<-->)\s*(?:\|([^|]*)\|\s*)?([A-Za-z0-9_-]+)""")
        var row = 0
        text.lines().forEach { line ->
            if (line.trim().startsWith("flowchart") || line.trim().startsWith("graph")) return@forEach
            val em = edgeRegex.find(line)
            if (em != null) {
                val from = em.groupValues[1]
                val label = em.groupValues[2]
                val to = em.groupValues[3]
                val a = nodes.getOrPut(from) { el(d, ElementType.PROCESS, 140f, 140f + row*130f, from) }
                val b = nodes.getOrPut(to) { el(d, ElementType.PROCESS, 500f, 140f + row*130f, to) }
                d.connections += FlowConnection(fromId=a.id,toId=b.id,label=label)
                row++
            } else {
                val nm = nodeRegex.find(line)
                if (nm != null && !line.contains("--")) {
                    val id=nm.groupValues[1]
                    val label=nm.groupValues[2].trim().ifBlank { id }
                    nodes.getOrPut(id) { el(d, ElementType.PROCESS, 180f + (nodes.size%3)*300f, 180f + (nodes.size/3)*150f, label) }
                }
            }
        }
        if (d.elements.isEmpty()) {
            d.elements += FlowElement(label="Imported Mermaid", x=300f, y=250f)
        }
        return d
    }

    private fun safeId(id: String) = "n" + id.replace(Regex("[^A-Za-z0-9_]"), "").take(12)
    private fun el(d: FlowDocument,type:ElementType,x:Float,y:Float,label:String):FlowElement =
        FlowElement(type=type,x=x,y=y,label=label).also{d.elements+=it}
}

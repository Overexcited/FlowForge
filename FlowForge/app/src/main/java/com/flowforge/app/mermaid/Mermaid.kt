package com.flowforge.app.mermaid

import com.flowforge.app.model.*
import java.util.UUID

/**
 * Mermaid interoperability.
 *
 * The FlowForge JSON format remains the lossless/native format.  This exporter
 * deliberately maps FlowForge features to the closest Mermaid flowchart
 * feature rather than trying to invent Mermaid syntax for things Mermaid
 * cannot represent.
 */
object Mermaid {
    fun export(d: FlowDocument): String {
        val sb = StringBuilder()
        sb.append("flowchart TD\n")

        // Use deterministic, collision-free Mermaid ids.  FlowForge ids are
        // UUIDs and should not leak into the exported diagram unnecessarily.
        val ids = linkedMapOf<String, String>()
        d.elements.forEachIndexed { index, e ->
            ids[e.id] = "n${index + 1}"
        }

        // Node declarations.
        d.elements.forEach { e ->
            val id = ids[e.id] ?: safeId(e.id)
            val label = mermaidLabel(e.label)
            val shape = mermaidShape(e)
            sb.append("    ").append(id).append(shape.first).append(label).append(shape.second).append('\n')
        }

        // Node styling. Mermaid's classDef/style system is the closest
        // interoperable equivalent for FlowForge fill, outline, thickness,
        // label colour and basic text formatting.
        d.elements.forEach { e ->
            val id = ids[e.id] ?: safeId(e.id)
            val style = mermaidNodeStyle(e)
            if (style.isNotEmpty()) {
                sb.append("    style ").append(id).append(' ').append(style).append('\n')
            }
        }

        // Edge declarations.  Keep one declaration per FlowForge connection
        // so independent parallel connections remain independent in Mermaid.
        d.connections.forEachIndexed { index, c ->
            val from = ids[c.fromId] ?: safeId(c.fromId)
            val to = ids[c.toId] ?: safeId(c.toId)
            val operator = mermaidEdgeOperator(c)
            val label = if (c.label.isBlank()) "" else "|${mermaidEdgeLabel(c.label)}|"
            sb.append("    ").append(from).append(' ').append(operator)
            if (label.isNotEmpty()) sb.append(label)
            sb.append(' ').append(to).append('\n')

            val style = mermaidEdgeStyle(c)
            if (style.isNotEmpty()) {
                sb.append("    linkStyle ").append(index).append(' ').append(style).append(';').append('\n')
            }
        }

        return sb.toString()
    }

    private fun mermaidShape(e: FlowElement): Pair<String, String> {
        // Mermaid 11.3+ has an explicit shape vocabulary.  Prefer it where it
        // is a good semantic match; fall back to the long-established syntax
        // for the common shapes.  Custom shapes have no Mermaid equivalent.
        return when {
            e.type == ElementType.DECISION || e.shape == ShapeType.DIAMOND -> "@{ shape: diam, label: \"" to "\" }"
            e.type == ElementType.TERMINAL -> "@{ shape: stadium, label: \"" to "\" }"
            e.type == ElementType.SERVER || e.shape == ShapeType.CYLINDER -> "@{ shape: cyl, label: \"" to "\" }"
            e.type == ElementType.DATA -> "@{ shape: lean-r, label: \"" to "\" }"
            e.type == ElementType.TEXT -> "@{ shape: text, label: \"" to "\" }"
            e.type == ElementType.NOTE || e.shape == ShapeType.DOCUMENT -> "@{ shape: doc, label: \"" to "\" }"
            e.shape == ShapeType.ROUNDED || e.shape == ShapeType.EXTRA_ROUNDED -> "@{ shape: rounded, label: \"" to "\" }"
            e.shape == ShapeType.OVAL -> "@{ shape: stadium, label: \"" to "\" }"
            e.shape == ShapeType.TRIANGLE -> "@{ shape: tri, label: \"" to "\" }"
            e.shape == ShapeType.CLOUD -> "@{ shape: cloud, label: \"" to "\" }"
            e.shape == ShapeType.TRAPEZOID_TOP_SHORT -> "@{ shape: trap-t, label: \"" to "\" }"
            e.shape == ShapeType.TRAPEZOID_BOTTOM_SHORT -> "@{ shape: trap-b, label: \"" to "\" }"
            e.shape == ShapeType.PARALLELOGRAM -> "@{ shape: lean-r, label: \"" to "\" }"
            e.shape == ShapeType.HEXAGON -> "@{ shape: hex, label: \"" to "\" }"
            e.shape == ShapeType.CIRCLE -> "@{ shape: circle, label: \"" to "\" }"
            // Mermaid has no native star or arbitrary/custom polygon shape.
            // 'odd' is the closest general-purpose expressive shape available.
            e.shape == ShapeType.STAR -> "@{ shape: odd, label: \"" to "\" }"
            else -> "[\"" to "\"]"
        }
    }

    private fun mermaidNodeStyle(e: FlowElement): String {
        val properties = mutableListOf<String>()
        e.fillColor?.let { properties += "fill:${cssColor(it)}" }
        e.outlineColor?.let { properties += "stroke:${cssColor(it)}" }
        properties += "stroke-width:${outlineWidth(e.outlineThickness)}px"
        e.labelColor?.let { properties += "color:${cssColor(it)}" }
        properties += "font-family:${fontFamily(e.labelFont)}"
        properties += "font-size:${fontSize(e.labelTextSize)}px"
        if (e.labelBold) properties += "font-weight:bold"
        if (e.labelItalic) properties += "font-style:italic"
        if (e.labelUnderline) properties += "text-decoration:underline"
        return properties.joinToString(",")
    }

    private fun outlineWidth(t: LineThickness): Int = when (t) {
        LineThickness.DEFAULT -> 3
        LineThickness.MEDIUM -> 8
        LineThickness.LARGE -> 15
    }

    private fun fontSize(t: TextSize): Int = when (t) {
        TextSize.SMALL -> 14
        TextSize.NORMAL -> 18
        TextSize.MEDIUM -> 22
        TextSize.LARGE -> 28
        TextSize.EXTRA_LARGE -> 36
        TextSize.HUGE -> 46
    }

    private fun fontFamily(f: TextFont): String = when (f) {
        TextFont.SANS -> "sans-serif"
        TextFont.SERIF -> "serif"
        TextFont.MONOSPACE -> "monospace"
        TextFont.SANS_CONDENSED -> "sans-serif"
        TextFont.SANS_LIGHT -> "sans-serif"
    }

    private fun mermaidEdgeOperator(c: FlowConnection): String {
        return when (c.arrowType) {
            ArrowType.NONE -> "---"
            ArrowType.BOTH -> "<-->"
            ArrowType.CIRCLE -> "---o"
            ArrowType.DIAMOND -> "---x"
            ArrowType.REPEATED,
            ArrowType.END -> if (c.lineStyle == LineStyle.DOTTED) "-.->" else "-->"
        }
    }

    private fun mermaidEdgeStyle(c: FlowConnection): String {
        val properties = mutableListOf<String>()

        // FlowForge has three semantic line styles. Mermaid has a native
        // dotted operator; dashed is represented with CSS dash-array styling.
        when (c.lineStyle) {
            LineStyle.DASHED -> properties += "stroke-dasharray:8 5"
            LineStyle.DOTTED -> properties += "stroke-dasharray:2 4"
            LineStyle.SOLID -> Unit
        }

        properties += "stroke:${cssColor(c.color)}"
        properties += "stroke-width:${lineWidth(c.thickness)}px"

        // Mermaid uses the edge label colour as the CSS 'color' property.
        // If no explicit colour exists, leave Mermaid's normal label colour.
        c.labelColor?.let { properties += "color:${cssColor(it)}" }
        properties += "font-family:${fontFamily(c.labelFont)}"
        properties += "font-size:${fontSize(c.labelTextSize)}px"
        if (c.labelBold) properties += "font-weight:bold"
        if (c.labelItalic) properties += "font-style:italic"
        if (c.labelUnderline) properties += "text-decoration:underline"

        return properties.joinToString(",")
    }

    private fun mermaidLabel(text: String): String {
        val escaped = escapeLabel(text)
        return applyMarkdownFormatting(escaped)
    }

    private fun mermaidEdgeLabel(text: String): String {
        return applyMarkdownFormatting(escapeLabel(text).replace("|", "/"))
    }

    private fun applyMarkdownFormatting(text: String): String {
        // Mermaid markdown strings support **bold**, *italic*, and literal
        // newlines. Underline is not a Mermaid markdown primitive and is
        // therefore handled by the node/edge CSS style when possible.
        return text
    }

    private fun escapeLabel(text: String): String {
        // Quoted Mermaid labels allow Unicode and most punctuation.  Escape
        // backslashes and quotes so exported diagrams remain parseable.
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\n", "<br>")
    }

    private fun lineWidth(t: LineThickness): Int = when (t) {
        LineThickness.DEFAULT -> 3
        LineThickness.MEDIUM -> 7
        LineThickness.LARGE -> 14
    }

    private fun cssColor(color: Int): String {
        val a = (color ushr 24) and 0xff
        val r = (color ushr 16) and 0xff
        val g = (color ushr 8) and 0xff
        val b = color and 0xff
        return if (a >= 255) {
            String.format("#%02x%02x%02x", r, g, b)
        } else {
            String.format("rgba(%d,%d,%d,%.3f)", r, g, b, a / 255.0)
        }
    }

    private fun safeId(id: String): String {
        val cleaned = id.replace(Regex("[^A-Za-z0-9_]"), "").take(18)
        return "n" + if (cleaned.isBlank()) UUID.randomUUID().toString().replace("-", "").take(12) else cleaned
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

    private fun el(d: FlowDocument,type:ElementType,x:Float,y:Float,label:String):FlowElement =
        FlowElement(type=type,x=x,y=y,label=label).also{d.elements+=it}
}

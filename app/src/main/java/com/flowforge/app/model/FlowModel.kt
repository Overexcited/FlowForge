package com.flowforge.app.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ElementType { PROCESS, DECISION, TERMINAL, DATA, SERVER, TEXT, NOTE }
enum class ShapeType { RECTANGLE, ROUNDED, DIAMOND, OVAL, PARALLELOGRAM, CYLINDER, DOCUMENT, HEXAGON, CLOUD, CIRCLE }
enum class ArrowType { NONE, END, BOTH, CIRCLE, DIAMOND, REPEATED }
enum class LineStyle { SOLID, DASHED, DOTTED }
enum class LineThickness { DEFAULT, MEDIUM, LARGE }
enum class ConnectionSide { AUTO, TOP, RIGHT, BOTTOM, LEFT }

fun defaultShapeFor(type: ElementType): ShapeType = when (type) {
    ElementType.DECISION -> ShapeType.DIAMOND
    ElementType.TERMINAL -> ShapeType.OVAL
    ElementType.DATA -> ShapeType.PARALLELOGRAM
    ElementType.SERVER -> ShapeType.CYLINDER
    ElementType.TEXT -> ShapeType.RECTANGLE
    ElementType.NOTE -> ShapeType.DOCUMENT
    ElementType.PROCESS -> ShapeType.ROUNDED
}

data class FlowElement(
    val id: String = UUID.randomUUID().toString(),
    var type: ElementType = ElementType.PROCESS,
    var shape: ShapeType = defaultShapeFor(type),
    var x: Float = 300f,
    var y: Float = 300f,
    var width: Float = 180f,
    var height: Float = 90f,
    var label: String = "Process",
    var notes: String = "",
    var outlineThickness: LineThickness = LineThickness.DEFAULT,
    var fillColor: Int? = null,
    var outlineColor: Int? = null,
    var labelColor: Int? = null
) {
    companion object {
        fun defaultShape(type: ElementType): ShapeType = defaultShapeFor(type)
    }
}

data class ConnectionPoint(var x: Float, var y: Float)

data class FlowConnection(
    val id: String = UUID.randomUUID().toString(),
    var fromId: String,
    var toId: String,
    var label: String = "",
    var arrowType: ArrowType = ArrowType.END,
    var lineStyle: LineStyle = LineStyle.SOLID,
    var color: Int = 0xff475569.toInt(),
    var thickness: LineThickness = LineThickness.DEFAULT,
    var fromSide: ConnectionSide = ConnectionSide.AUTO,
    var toSide: ConnectionSide = ConnectionSide.AUTO,
    var bendX: Float = 0f,
    var bendY: Float = 0f,
    var routePoints: MutableList<ConnectionPoint> = mutableListOf(),
    var notes: String = "",
    var labelColor: Int? = null
)

data class FlowDocument(
    var title: String = "Untitled flowchart",
    val elements: MutableList<FlowElement> = mutableListOf(),
    val connections: MutableList<FlowConnection> = mutableListOf()
) {
    fun deepCopy(): FlowDocument = fromJson(toJson())

    fun toJson(): String {
        val o = JSONObject()
        o.put("format", "flowforge")
        o.put("version", 5)
        o.put("title", title)
        val es = JSONArray()
        elements.forEach { e ->
            es.put(JSONObject().apply {
                put("id", e.id); put("type", e.type.name); put("shape", e.shape.name)
                put("x", e.x); put("y", e.y); put("width", e.width); put("height", e.height)
                put("label", e.label); put("notes", e.notes)
                put("outlineThickness", e.outlineThickness.name)
                if (e.outlineColor == null) put("outlineColor", JSONObject.NULL) else put("outlineColor", e.outlineColor)
                if (e.fillColor == null) put("fillColor", JSONObject.NULL) else put("fillColor", e.fillColor)
                if (e.labelColor == null) put("labelColor", JSONObject.NULL) else put("labelColor", e.labelColor)
            })
        }
        val cs = JSONArray()
        connections.forEach { c ->
            cs.put(JSONObject().apply {
                put("id", c.id); put("from", c.fromId); put("to", c.toId)
                put("label", c.label); put("arrow", c.arrowType.name); put("style", c.lineStyle.name)
                put("color", c.color); put("thickness", c.thickness.name)
                put("fromSide", c.fromSide.name); put("toSide", c.toSide.name)
                put("bendX", c.bendX); put("bendY", c.bendY)
                val route = JSONArray(); c.routePoints.forEach { p -> route.put(JSONObject().apply { put("x", p.x); put("y", p.y) }) }
                put("route", route)
                put("notes", c.notes)
                if (c.labelColor == null) put("labelColor", JSONObject.NULL) else put("labelColor", c.labelColor)
            })
        }
        o.put("elements", es); o.put("connections", cs)
        return o.toString(2)
    }

    companion object {
        fun fromJson(text: String): FlowDocument {
            val o = JSONObject(text)
            val d = FlowDocument(o.optString("title", "Untitled flowchart"))
            val es = o.optJSONArray("elements") ?: JSONArray()
            for (i in 0 until es.length()) {
                val e = es.getJSONObject(i)
                val type = runCatching { ElementType.valueOf(e.optString("type")) }.getOrDefault(ElementType.PROCESS)
                val shape = runCatching { ShapeType.valueOf(e.optString("shape")) }.getOrDefault(defaultShapeFor(type))
                val thickness = runCatching { LineThickness.valueOf(e.optString("outlineThickness")) }.getOrDefault(LineThickness.DEFAULT)
                val fill = if (e.has("fillColor") && !e.isNull("fillColor")) e.optInt("fillColor") else null
                val outlineColor = if (e.has("outlineColor") && !e.isNull("outlineColor")) e.optInt("outlineColor") else null
                val labelColor = if (e.has("labelColor") && !e.isNull("labelColor")) e.optInt("labelColor") else null
                d.elements += FlowElement(
                    id = e.optString("id", UUID.randomUUID().toString()), type = type, shape = shape,
                    x = e.optDouble("x", 300.0).toFloat(), y = e.optDouble("y", 300.0).toFloat(),
                    width = e.optDouble("width", 180.0).toFloat(), height = e.optDouble("height", 90.0).toFloat(),
                    label = e.optString("label", "Process"), notes = e.optString("notes", ""),
                    outlineThickness = thickness, fillColor = fill, outlineColor = outlineColor, labelColor = labelColor
                )
            }
            val cs = o.optJSONArray("connections") ?: JSONArray()
            for (i in 0 until cs.length()) {
                val c = cs.getJSONObject(i)
                d.connections += FlowConnection(
                    id = c.optString("id", UUID.randomUUID().toString()), fromId = c.optString("from"), toId = c.optString("to"),
                    label = c.optString("label", ""),
                    arrowType = runCatching { ArrowType.valueOf(c.optString("arrow")) }.getOrDefault(ArrowType.END),
                    lineStyle = runCatching { LineStyle.valueOf(c.optString("style")) }.getOrDefault(LineStyle.SOLID),
                    color = c.optInt("color", 0xff475569.toInt()),
                    thickness = runCatching { LineThickness.valueOf(c.optString("thickness")) }.getOrDefault(LineThickness.DEFAULT),
                    fromSide = runCatching { ConnectionSide.valueOf(c.optString("fromSide")) }.getOrDefault(ConnectionSide.AUTO),
                    toSide = runCatching { ConnectionSide.valueOf(c.optString("toSide")) }.getOrDefault(ConnectionSide.AUTO),
                    bendX = c.optDouble("bendX", 0.0).toFloat(), bendY = c.optDouble("bendY", 0.0).toFloat(),
                    routePoints = mutableListOf<ConnectionPoint>().apply {
                        val route = c.optJSONArray("route") ?: JSONArray()
                        for (j in 0 until route.length()) {
                            val p = route.getJSONObject(j)
                            add(ConnectionPoint(p.optDouble("x", 0.0).toFloat(), p.optDouble("y", 0.0).toFloat()))
                        }
                    },
                    notes = c.optString("notes", ""),
                    labelColor = if (c.has("labelColor") && !c.isNull("labelColor")) c.optInt("labelColor") else null
                )
            }
            return d
        }
    }
}

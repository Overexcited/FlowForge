package com.flowforge.app.model

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ElementAsset(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var element: FlowElement
) {
    fun toJsonObject() = JSONObject().apply {
        put("id", id); put("name", name)
        put("element", JSONObject(element.toJsonLike()))
    }
    companion object {
        private fun JSONObject.toElement(): FlowElement {
            val type = runCatching { ElementType.valueOf(optString("type")) }.getOrDefault(ElementType.PROCESS)
            return FlowElement(
                id = UUID.randomUUID().toString(), type = type,
                shape = runCatching { ShapeType.valueOf(optString("shape")) }.getOrDefault(FlowElement.defaultShape(type)),
                width = optDouble("width", 180.0).toFloat(), height = optDouble("height", 90.0).toFloat(),
                label = optString("label", "Process"), notes = optString("notes", "")
            )
        }
        fun fromJsonObject(o: JSONObject): ElementAsset = ElementAsset(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "Building block"),
            element = o.optJSONObject("element")!!.toElement()
        )
    }
}

private fun FlowElement.toJsonLike(): String = JSONObject().apply {
    put("type", type.name); put("shape", shape.name); put("width", width); put("height", height)
    put("label", label); put("notes", notes)
}.toString()

class AssetStore(private val prefs: SharedPreferences) {
    private val key = "building_blocks"
    fun all(): MutableList<ElementAsset> {
        val arr = JSONArray(prefs.getString(key, "[]") ?: "[]")
        return MutableList(arr.length()) { ElementAsset.fromJsonObject(arr.getJSONObject(it)) }
    }
    fun save(asset: ElementAsset) {
        val list = all(); list.removeAll { it.id == asset.id }; list.add(asset)
        val arr = JSONArray(); list.forEach { arr.put(it.toJsonObject()) }
        prefs.edit().putString(key, arr.toString()).apply()
    }
    fun delete(id: String) {
        val arr = JSONArray(); all().filterNot { it.id == id }.forEach { arr.put(it.toJsonObject()) }
        prefs.edit().putString(key, arr.toString()).apply()
    }
    fun clear() = prefs.edit().remove(key).apply()
}

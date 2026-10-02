package com.mobilemcp.pro

import org.json.JSONArray
import org.json.JSONObject

/** Keep actionable screen content within the prompt budget without cutting JSON. */
object UiSnapshotFormatter {
    fun format(data: JSONObject, maxChars: Int = 6000): JSONObject {
        require(maxChars >= 256)
        val tree = data.optJSONObject("tree") ?: JSONObject()
        val result = JSONObject()
            .put("package", tree.optString("packageName"))
            .put("screenWidth", data.optInt("screenWidth"))
            .put("screenHeight", data.optInt("screenHeight"))
            .put("nodes", JSONArray())
            .put("omittedNodes", 0)
        val nodes = result.getJSONArray("nodes")
        var budget = result.toString().length + 32
        var omitted = 0
        fun visit(node: JSONObject, depth: Int) {
            if (depth > 30) { omitted++; return }
            val text = node.optString("text").takeUnless { it == "null" }.orEmpty()
            val desc = node.optString("contentDescription").takeUnless { it == "null" }.orEmpty()
            val important = text.isNotBlank() || desc.isNotBlank() ||
                node.optBoolean("isClickable") || node.optBoolean("isEditable") || node.optBoolean("isScrollable")
            if (important) {
                val item = JSONObject()
                    .put("class", node.optString("className").substringAfterLast('.'))
                    .put("enabled", node.optBoolean("isEnabled", true))
                if (text.isNotBlank()) item.put("text", text.take(180))
                if (desc.isNotBlank()) item.put("description", desc.take(160))
                val id = node.optString("viewId").takeUnless { it == "null" }.orEmpty()
                if (id.isNotBlank()) item.put("id", id.take(180))
                for ((source, target) in listOf("isClickable" to "clickable", "isEditable" to "editable",
                    "isScrollable" to "scrollable", "isFocused" to "focused", "isChecked" to "checked")) {
                    if (node.optBoolean(source)) item.put(target, true)
                }
                node.optJSONObject("bounds")?.let { item.put("bounds", it) }
                val size = item.toString().length + 1
                if (budget + size <= maxChars && nodes.length() < 120) {
                    nodes.put(item); budget += size
                } else omitted++
            }
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) children.optJSONObject(i)?.let { visit(it, depth + 1) }
        }
        visit(tree, 0)
        result.put("omittedNodes", omitted)
        return result
    }
}

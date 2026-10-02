package com.mobilemcp.pro

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UiSnapshotFormatterTest {
    @Test fun preservesActionTargetsBeyondEmptyLayoutContainers() {
        val children = JSONArray()
        repeat(100) { children.put(JSONObject().put("className", "android.widget.LinearLayout")) }
        children.put(JSONObject().put("text", "علی").put("viewId", "test:id/chat")
            .put("isClickable", true).put("bounds", JSONObject().put("left", 10).put("top", 200)))
        val data = JSONObject().put("tree", JSONObject().put("packageName", "test.telegram").put("children", children))
            .put("screenWidth", 1080).put("screenHeight", 2400)
        val snapshot = UiSnapshotFormatter.format(data)
        assertEquals("test.telegram", snapshot.getString("package"))
        assertEquals(1080, snapshot.getInt("screenWidth"))
        assertEquals("علی", snapshot.getJSONArray("nodes").getJSONObject(0).getString("text"))
        assertEquals("test:id/chat", snapshot.getJSONArray("nodes").getJSONObject(0).getString("id"))
    }
    @Test fun largeTreesStayWithinBudgetAndRemainValidJson() {
        val children = JSONArray()
        repeat(300) { children.put(JSONObject().put("text", "متن طولانی شماره $it ".repeat(20)).put("isEditable", true)) }
        val snapshot = UiSnapshotFormatter.format(JSONObject().put("tree", JSONObject().put("children", children)), 1000)
        assertTrue(snapshot.toString().length <= 1000)
        assertTrue(JSONObject(snapshot.toString()).getInt("omittedNodes") > 0)
        assertTrue(snapshot.getJSONArray("nodes").length() > 0)
    }
    @Test fun emptyScreenIsReadableAndNeverInventsAnActionTarget() {
        val snapshot = UiSnapshotFormatter.format(JSONObject())
        assertEquals(0, snapshot.getJSONArray("nodes").length())
    }
}

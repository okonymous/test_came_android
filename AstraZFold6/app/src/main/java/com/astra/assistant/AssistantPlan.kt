package com.astra.assistant

import org.json.JSONObject

data class AssistantAction(
    val type: String = "none",
    val params: Map<String, String> = emptyMap()
)

data class AssistantPlan(
    val spoken: String,
    val action: AssistantAction = AssistantAction()
) {
    companion object {
        fun fromJson(text: String): AssistantPlan {
            val root = JSONObject(text)
            val spoken = root.optString("spoken", "Siap.")
            val actionObject = root.optJSONObject("action")
                ?: JSONObject().put("type", "none").put("params", JSONObject())
            val type = actionObject.optString("type", "none")
            val paramsObject = actionObject.optJSONObject("params") ?: JSONObject()
            val params = mutableMapOf<String, String>()
            paramsObject.keys().forEach { key ->
                if (!paramsObject.isNull(key)) {
                    val value = paramsObject.optString(key, "").trim()
                    if (value.isNotBlank()) params[key] = value
                }
            }
            return AssistantPlan(spoken, AssistantAction(type, params))
        }
    }
}

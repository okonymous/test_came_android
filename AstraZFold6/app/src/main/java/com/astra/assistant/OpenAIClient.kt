package com.astra.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAIClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun plan(
        apiKey: String,
        model: String,
        assistantName: String,
        userText: String,
        memory: List<MemoryTurn>
    ): AssistantPlan = withContext(Dispatchers.IO) {
        val recent = memory.takeLast(12).joinToString("\n") { it.role + ": " + it.text }

        val instructions = """
            You are $assistantName, a personal Android assistant with a futuristic JARVIS-like interaction style.
            Speak naturally in Indonesian unless the user speaks another language.
            Be concise, practical, and calm.
            You may prepare Android actions, but never claim an action has already happened.
            The phone always asks for confirmation before executing an action.

            Allowed action types:
            none, whatsapp, email, dial, sms, alarm, maps, open_url, open_app.

            Parameter rules:
            whatsapp -> number, message
            email -> to, subject, body
            dial -> number
            sms -> number, message
            alarm -> hour, minute, label
            maps -> query
            open_url -> url
            open_app -> name

            If required information is missing, ask for it and return action type none.
            Never invent phone numbers or email addresses.
        """.trimIndent()

        val input = buildString {
            if (recent.isNotBlank()) {
                append("Recent conversation:\n")
                append(recent)
                append("\n\n")
            }
            append("User: ")
            append(userText)
        }

        val paramsSchema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", JSONObject().put("type", "string"))

        val actionSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("type", JSONObject().put("type", "string"))
                .put("params", paramsSchema))
            .put("required", JSONArray().put("type").put("params"))
            .put("additionalProperties", false)

        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("spoken", JSONObject().put("type", "string"))
                .put("action", actionSchema))
            .put("required", JSONArray().put("spoken").put("action"))
            .put("additionalProperties", false)

        val body = JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", input)
            .put("max_output_tokens", 900)
            .put("text", JSONObject().put(
                "format",
                JSONObject()
                    .put("type", "json_schema")
                    .put("name", "astra_plan")
                    .put("strict", true)
                    .put("schema", schema)
            ))

        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .addHeader("Authorization", "Bearer " + apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("OpenAI " + response.code + ": " + friendlyError(raw))
            }
            AssistantPlan.fromJson(extractOutputText(raw))
        }
    }

    private fun extractOutputText(raw: String): String {
        val root = JSONObject(raw)
        root.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = root.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val item = content.optJSONObject(j) ?: continue
                if (item.optString("type") == "output_text") return item.optString("text")
            }
        }
        throw IOException("Respons model tidak memiliki output teks.")
    }

    private fun friendlyError(raw: String): String = runCatching {
        JSONObject(raw).optJSONObject("error")?.optString("message") ?: raw.take(300)
    }.getOrDefault(raw.take(300))
}

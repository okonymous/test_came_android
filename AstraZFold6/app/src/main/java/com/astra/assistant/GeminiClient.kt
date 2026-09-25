package com.astra.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiClient {
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
        val preferred = model.trim().ifBlank { "gemini-3.5-flash-lite" }
        val fallbackModels = listOf(
            preferred,
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.8-flash"
        ).distinct()

        var lastError: IOException? = null

        for ((index, candidate) in fallbackModels.withIndex()) {
            try {
                return@withContext requestPlan(
                    apiKey = apiKey,
                    model = candidate,
                    assistantName = assistantName,
                    userText = userText,
                    memory = memory
                )
            } catch (e: GeminiHttpException) {
                lastError = e
                val retryable = e.code == 429 || e.code == 503 || e.code == 404
                if (!retryable || index == fallbackModels.lastIndex) throw e
                delay(450L + (index * 500L))
            } catch (e: IOException) {
                lastError = e
                if (index == fallbackModels.lastIndex) throw e
                delay(350L)
            }
        }

        throw lastError ?: IOException("Tidak ada model Gemini yang tersedia.")
    }

    private fun requestPlan(
        apiKey: String,
        model: String,
        assistantName: String,
        userText: String,
        memory: List<MemoryTurn>
    ): AssistantPlan {
        val recent = memory.takeLast(12).joinToString("\n") { it.role + ": " + it.text }

        val instructions = """
            You are $assistantName, a personal Android assistant with a futuristic JARVIS-like interaction style.
            Speak naturally in Indonesian unless the user speaks another language.
            Be concise, practical, calm, and useful.
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
            Never invent phone numbers, email addresses, URLs, or contact details.
            For action type none, params should be an empty object.
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

        val paramsProperties = JSONObject()
            .put("number", JSONObject().put("type", "string"))
            .put("message", JSONObject().put("type", "string"))
            .put("to", JSONObject().put("type", "string"))
            .put("subject", JSONObject().put("type", "string"))
            .put("body", JSONObject().put("type", "string"))
            .put("hour", JSONObject().put("type", "string"))
            .put("minute", JSONObject().put("type", "string"))
            .put("label", JSONObject().put("type", "string"))
            .put("query", JSONObject().put("type", "string"))
            .put("url", JSONObject().put("type", "string"))
            .put("name", JSONObject().put("type", "string"))

        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("spoken", JSONObject().put("type", "string"))
                .put("action", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("type", JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray(listOf(
                                "none", "whatsapp", "email", "dial", "sms",
                                "alarm", "maps", "open_url", "open_app"
                            ))))
                        .put("params", JSONObject()
                            .put("type", "object")
                            .put("properties", paramsProperties)
                            .put("additionalProperties", false)))
                    .put("required", JSONArray(listOf("type", "params")))
                    .put("additionalProperties", false)))
            .put("required", JSONArray(listOf("spoken", "action")))
            .put("additionalProperties", false)

        val body = JSONObject()
            .put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", instructions))))
            .put("contents", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", input)))
            ))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseJsonSchema", schema)
                .put("temperature", 0.35)
                .put("maxOutputTokens", 1200))

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
            .addHeader("x-goog-api-key", apiKey.trim())
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GeminiHttpException(
                    code = response.code,
                    message = "Gemini " + response.code + " [" + model + "]: " + friendlyError(raw)
                )
            }
            return AssistantPlan.fromJson(extractText(raw))
        }
    }

    private fun extractText(raw: String): String {
        val root = JSONObject(raw)
        val candidates = root.optJSONArray("candidates")
            ?: throw IOException("Gemini tidak mengembalikan kandidat jawaban.")
        if (candidates.length() == 0) {
            val feedback = root.optJSONObject("promptFeedback")?.toString().orEmpty()
            throw IOException("Gemini tidak menghasilkan jawaban. " + feedback)
        }
        val parts = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw IOException("Format respons Gemini tidak dikenali.")

        val combined = buildString {
            for (i in 0 until parts.length()) {
                val text = parts.optJSONObject(i)?.optString("text").orEmpty()
                if (text.isNotBlank()) append(text)
            }
        }.trim()

        if (combined.isBlank()) throw IOException("Respons Gemini kosong.")
        return combined
    }

    private fun friendlyError(raw: String): String = runCatching {
        val error = JSONObject(raw).optJSONObject("error")
        error?.optString("message")?.takeIf { it.isNotBlank() } ?: raw.take(400)
    }.getOrDefault(raw.take(400))
}

class GeminiHttpException(
    val code: Int,
    message: String
) : IOException(message)

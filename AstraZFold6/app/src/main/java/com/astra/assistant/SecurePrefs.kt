package com.astra.assistant

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class MemoryTurn(val role: String, val text: String)

class SecurePrefs(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "astra_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) = prefs.edit().putString("gemini_api_key", value.trim()).apply()

    var model: String
        get() = prefs.getString("gemini_model", "gemini-3.8-flash") ?: "gemini-3.8-flash"
        set(value) = prefs.edit().putString("gemini_model", value.trim().ifBlank { "gemini-3.8-flash" }).apply()

    var assistantName: String
        get() = prefs.getString("assistant_name", "Astra") ?: "Astra"
        set(value) = prefs.edit().putString("assistant_name", value.trim().ifBlank { "Astra" }).apply()

    fun loadMemory(): MutableList<MemoryTurn> {
        val raw = prefs.getString("memory", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                MemoryTurn(o.optString("role"), o.optString("text"))
            }
        }.getOrElse { mutableListOf() }
    }

    fun saveMemory(turns: List<MemoryTurn>) {
        val arr = JSONArray()
        turns.takeLast(16).forEach { turn ->
            arr.put(JSONObject().put("role", turn.role).put("text", turn.text))
        }
        prefs.edit().putString("memory", arr.toString()).apply()
    }

    fun clearMemory() {
        prefs.edit().remove("memory").apply()
    }
}

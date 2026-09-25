package com.astra.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object AndroidActions {
    fun execute(context: Context, action: AssistantAction): String {
        return try {
            when (action.type) {
                "whatsapp" -> openWhatsApp(context, action.params["number"].orEmpty(), action.params["message"].orEmpty())
                "email" -> composeEmail(context, action.params["to"].orEmpty(), action.params["subject"].orEmpty(), action.params["body"].orEmpty())
                "dial" -> dial(context, action.params["number"].orEmpty())
                "sms" -> sms(context, action.params["number"].orEmpty(), action.params["message"].orEmpty())
                "alarm" -> setAlarm(context, action.params)
                "maps" -> maps(context, action.params["query"].orEmpty())
                "open_url" -> openUrl(context, action.params["url"].orEmpty())
                "open_app" -> openApp(context, action.params["name"].orEmpty())
                else -> "Tidak ada aksi perangkat yang perlu dijalankan."
            }
        } catch (e: Exception) {
            "Aksi gagal dibuka: " + (e.message ?: "unknown error")
        }
    }

    private fun openWhatsApp(context: Context, number: String, message: String): String {
        val digits = number.filter { it.isDigit() }
        require(digits.isNotBlank()) { "Nomor WhatsApp belum ada." }
        val encoded = URLEncoder.encode(message, StandardCharsets.UTF_8.toString()).replace("+", "%20")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + digits + "?text=" + encoded))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            intent.setPackage("com.whatsapp")
            context.startActivity(intent)
        }.recoverCatching {
            intent.setPackage(null)
            context.startActivity(intent)
        }.getOrThrow()
        return "WhatsApp dibuka dengan pesan yang sudah disiapkan."
    }

    private fun composeEmail(context: Context, to: String, subject: String, body: String): String {
        require(to.contains("@")) { "Alamat email belum lengkap." }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Aplikasi email dibuka dengan draft yang sudah disiapkan."
    }

    private fun dial(context: Context, number: String): String {
        require(number.isNotBlank()) { "Nomor telepon belum ada." }
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return "Dialer dibuka."
    }

    private fun sms(context: Context, number: String, message: String): String {
        require(number.isNotBlank()) { "Nomor SMS belum ada." }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number)).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Aplikasi SMS dibuka dengan pesan yang sudah disiapkan."
    }

    private fun setAlarm(context: Context, p: Map<String, String>): String {
        val hour = p["hour"]?.toIntOrNull() ?: error("Jam alarm belum ada.")
        val minute = p["minute"]?.toIntOrNull() ?: 0
        require(hour in 0..23 && minute in 0..59) { "Waktu alarm tidak valid." }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, p["label"].orEmpty())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Pengaturan alarm dibuka."
    }

    private fun maps(context: Context, query: String): String {
        require(query.isNotBlank()) { "Tujuan peta belum ada." }
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(query))
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Maps dibuka untuk " + query + "."
    }

    private fun openUrl(context: Context, raw: String): String {
        require(raw.isNotBlank()) { "URL belum ada." }
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://" + raw
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Tautan dibuka."
    }

    private fun openApp(context: Context, name: String): String {
        val normalized = name.lowercase().trim()
        if (normalized == "camera" || normalized == "kamera") {
            context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Kamera dibuka."
        }
        if (normalized == "settings" || normalized == "pengaturan") {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Pengaturan dibuka."
        }

        val packages = mapOf(
            "whatsapp" to "com.whatsapp",
            "gmail" to "com.google.android.gm",
            "youtube" to "com.google.android.youtube",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "chrome" to "com.android.chrome"
        )
        val pkg = packages[normalized] ?: error("Aplikasi '" + name + "' belum ada di daftar Astra.")
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw ActivityNotFoundException("Aplikasi tidak ditemukan.")
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return name + " dibuka."
    }
}

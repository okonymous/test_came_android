package com.astra.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class WeatherSnapshot(
    val city: String,
    val temperatureC: Double,
    val feelsLikeC: Double,
    val weatherCode: Int,
    val windKmh: Double,
    val description: String
)

class RealtimeWeather(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun load(): WeatherSnapshot = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) {
            throw SecurityException("Izin lokasi diperlukan untuk cuaca real-time.")
        }

        val location = getBestLocation()
            ?: throw IOException("Lokasi perangkat belum tersedia. Aktifkan Location/GPS.")

        val city = resolveCity(location)
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=" + location.latitude +
            "&longitude=" + location.longitude +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&timezone=auto"

        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Weather " + response.code)
            }

            val root = JSONObject(raw)
            val current = root.optJSONObject("current")
                ?: throw IOException("Data cuaca tidak tersedia.")

            val code = current.optInt("weather_code", -1)
            WeatherSnapshot(
                city = city,
                temperatureC = current.optDouble("temperature_2m", Double.NaN),
                feelsLikeC = current.optDouble("apparent_temperature", Double.NaN),
                weatherCode = code,
                windKmh = current.optDouble("wind_speed_10m", Double.NaN),
                description = weatherDescription(code)
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun getBestLocation(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).filter { provider ->
            runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
        }

        val last = providers.mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

        if (last != null && System.currentTimeMillis() - last.time < 30 * 60 * 1000L) {
            return last
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (provider in providers) {
                val fresh = currentLocation(lm, provider)
                if (fresh != null) return fresh
            }
        }

        return last
    }

    private suspend fun currentLocation(
        manager: LocationManager,
        provider: String
    ): Location? = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        try {
            manager.getCurrentLocation(
                provider,
                signal,
                ContextCompat.getMainExecutor(context)
            ) { location ->
                if (cont.isActive) cont.resume(location)
            }
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveCity(location: Location): String {
        return runCatching {
            val geocoder = Geocoder(context, Locale("id", "ID"))
            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            val address = addresses?.firstOrNull()
            address?.locality
                ?: address?.subAdminArea
                ?: address?.adminArea
                ?: "Lokasi Anda"
        }.getOrDefault("Lokasi Anda")
    }

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "Cerah"
        1 -> "Cerah Berawan"
        2 -> "Berawan Sebagian"
        3 -> "Berawan"
        45, 48 -> "Berkabut"
        51, 53, 55 -> "Gerimis"
        56, 57 -> "Gerimis Beku"
        61, 63, 65 -> "Hujan"
        66, 67 -> "Hujan Beku"
        71, 73, 75, 77 -> "Salju"
        80, 81, 82 -> "Hujan Lokal"
        85, 86 -> "Salju Lokal"
        95 -> "Badai Petir"
        96, 99 -> "Badai + Hujan Es"
        else -> "Cuaca Terkini"
    }
}

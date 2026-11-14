package dev.tvlauncher.home

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class City(val name: String, val region: String, val country: String, val lat: Double, val lon: Double) {
    fun label(): String = listOf(name, region, country).filter { it.isNotBlank() }.joinToString(", ")
}

class Weather(
    val tempC: Double, val feelsC: Double, val code: Int, val isDay: Boolean,
    val hourNow: Double, val sunrise: Double, val sunset: Double
) {
    enum class Kind { CLEAR, PARTLY, CLOUD, FOG, RAIN, STORM, SNOW }

    fun kind(): Kind = when (code) {
        0, 1 -> Kind.CLEAR
        2 -> Kind.PARTLY
        3 -> Kind.CLOUD
        45, 48 -> Kind.FOG
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Kind.RAIN
        71, 73, 75, 77, 85, 86 -> Kind.SNOW
        95, 96, 99 -> Kind.STORM
        else -> Kind.CLOUD
    }

    fun text(): String = when (code) {
        0 -> if (isDay) "Clear sky" else "Clear night"
        1 -> if (isDay) "Mainly clear" else "Clear night"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> "Cloudy"
    }

    /** Hours between now and the nearest sunrise or sunset, 0 when the sun is down. */
    fun toHorizon(): Double = if (!isDay) 0.0 else Math.max(0.0, Math.min(hourNow - sunrise, sunset - hourNow))
}

/** Open-Meteo: free for non-commercial use, no key. Data licence CC BY 4.0. */
object WeatherRepo {
    private const val UA = "tvlauncher/0.2 (personal home screen)"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", UA)
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun hours(iso: String): Double {
        // "2026-11-07T06:33"
        val t = iso.substringAfter('T')
        return t.substringBefore(':').toDouble() + t.substringAfter(':').take(2).toDouble() / 60.0
    }

    fun parse(json: String): Weather {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        return Weather(
            cur.getDouble("temperature_2m"), cur.getDouble("apparent_temperature"),
            cur.getInt("weather_code"), cur.getInt("is_day") == 1,
            hours(cur.getString("time")),
            hours(daily.getJSONArray("sunrise").getString(0)),
            hours(daily.getJSONArray("sunset").getString(0))
        )
    }

    fun fetchJson(lat: Double, lon: Double): String = get(
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
            "&daily=sunrise,sunset&timezone=auto&forecast_days=1")

    fun search(query: String): List<City> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=en&format=json"))
        val arr = o.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<City>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            out.add(City(r.optString("name"), r.optString("admin1"), r.optString("country"),
                r.getDouble("latitude"), r.getDouble("longitude")))
        }
        return out
    }
}

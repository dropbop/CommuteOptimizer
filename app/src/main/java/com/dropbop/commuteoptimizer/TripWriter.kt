package com.dropbop.commuteoptimizer

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object TripWriter {
    private val gson = Gson()

    fun saveTrip(
        ctx: Context,
        fixes: List<Fix>,
        startUtc: Long,
        endUtc: Long,
        routeLabel: String
    ) {
        if (fixes.isEmpty()) return

        val latLngs = fixes.map { it.lat to it.lon }
        val encoded = encodePolyline(latLngs)

        val (minLat, minLon, maxLat, maxLon) = bbox(latLngs)

        val props = mapOf(
            "polyline" to encoded,
            "start_time" to iso(startUtc),
            "end_time" to iso(endUtc),
            "direction" to inferDirection(fixes.first(), fixes.last()),
            "route_label" to routeLabel,
            "sample_rate_s" to 2,
            "point_count" to fixes.size,
            "bbox" to listOf(minLon, minLat, maxLon, maxLat)
        )

        val feature = mapOf(
            "type" to "Feature",
            "geometry" to mapOf("type" to "LineString", "coordinates" to emptyList<Any>()),
            "properties" to props
        )

        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "commute-logs")
        dir.mkdirs()
        val file = File(dir, filename(props["start_time"]!!.toString(), routeLabel))
        OutputStreamWriter(file.outputStream()).use { it.write(gson.toJson(feature)) }
    }

    private fun filename(startIso: String, label: String): String {
        val safeIso = startIso.replace(":", "-")
        val safeLabel = label.lowercase()
            .replace("\\s+".toRegex(), "-")
            .replace("[^a-z0-9\\-_]".toRegex(), "")
            .ifBlank { "unlabeled" }
        return "trip_${safeIso}_${safeLabel}.json"
    }

    private fun inferDirection(a: Fix, b: Fix): String =
        if (a.lon < b.lon) "A->B" else "B->A" // toy heuristic; customize to your commute

    private fun iso(t: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(t))
    }

    private fun bbox(points: List<Pair<Double, Double>>): List<Double> {
        var minLat =  90.0; var minLon =  180.0
        var maxLat = -90.0; var maxLon = -180.0
        for ((lat, lon) in points) {
            minLat = min(minLat, lat); minLon = min(minLon, lon)
            maxLat = max(maxLat, lat); maxLon = max(maxLon, lon)
        }
        return listOf(minLat, minLon, maxLat, maxLon)
    }

    /** Minimal Encoded Polyline Algorithm (5 decimals). */
    private fun encodePolyline(points: List<Pair<Double, Double>>): String {
        var lastLat = 0
        var lastLng = 0
        val sb = StringBuilder()
        for ((lat, lng) in points) {
            val ilat = floor(lat * 1e5).toInt()
            val ilng = floor(lng * 1e5).toInt()
            encodeSignedNumber(ilat - lastLat, sb)
            encodeSignedNumber(ilng - lastLng, sb)
            lastLat = ilat
            lastLng = ilng
        }
        return sb.toString()
    }
    private fun encodeSignedNumber(num: Int, sb: StringBuilder) {
        var sgnNum = num shl 1
        if (num < 0) sgnNum = sgnNum.inv()
        encodeNumber(sgnNum, sb)
    }
    private fun encodeNumber(numIn: Int, sb: StringBuilder) {
        var num = numIn
        while (num >= 0x20) {
            sb.append(((0x20 or (num and 0x1f)) + 63).toChar())
            num = num shr 5
        }
        sb.append((num + 63).toChar())
    }
}

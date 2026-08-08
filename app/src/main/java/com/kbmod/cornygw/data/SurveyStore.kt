package com.kbmod.cornygw.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedSurvey(
    val file: File,
    val label: String,
    val ssid: String,
    val bssid: String,
    val sampleCount: Int,
    val savedAtMs: Long,
)

/**
 * Persists surveys as plain CSV in app-private storage.
 *
 * CSV rather than a database or a serialization library, because the whole
 * point of a survey is that you might want to argue with the result — in a
 * spreadsheet, in Python, on a map. A format anyone can open is worth more here
 * than query performance over a few thousand rows.
 */
class SurveyStore(context: Context) {

    private val appContext = context.applicationContext
    private val directory: File
        get() = File(appContext.filesDir, "surveys").apply { mkdirs() }

    fun save(ssid: String, bssid: String, samples: List<SurveySample>): File {
        val stamp = FILE_STAMP.format(Date())
        val safeBssid = bssid.replace(":", "")
        val file = File(directory, "survey_${stamp}_$safeBssid.csv")
        file.bufferedWriter().use { writer ->
            writer.appendLine(HEADER)
            for (sample in samples) {
                writer.appendLine(
                    listOf(
                        sample.atMs.toString(),
                        ISO_STAMP.format(Date(sample.atMs)),
                        escape(ssid),
                        sample.bssid,
                        sample.rssi.toString(),
                        String.format(Locale.US, "%.7f", sample.latitude),
                        String.format(Locale.US, "%.7f", sample.longitude),
                        String.format(Locale.US, "%.1f", sample.accuracyM),
                    ).joinToString(","),
                )
            }
        }
        return file
    }

    fun list(): List<SavedSurvey> =
        directory.listFiles { file -> file.extension == "csv" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { describe(it) }
            .orEmpty()

    fun delete(survey: SavedSurvey): Boolean = survey.file.delete()

    fun load(file: File): List<SurveySample> =
        file.useLines { lines ->
            lines.drop(1).mapNotNull { parseLine(it) }.toList()
        }

    fun shareIntent(file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun describe(file: File): SavedSurvey? {
        val rows = runCatching {
            file.useLines { lines -> lines.drop(1).take(MAX_PREVIEW_ROWS).toList() }
        }.getOrNull().orEmpty()
        if (rows.isEmpty()) return null

        val firstColumns = splitCsv(rows.first())
        if (firstColumns.size < COLUMN_COUNT) return null

        val count = runCatching {
            file.useLines { lines -> lines.drop(1).count { it.isNotBlank() } }
        }.getOrDefault(rows.size)

        val ssid = firstColumns[2].removeSurrounding("\"").replace("\"\"", "\"")
        val bssid = firstColumns[3]
        return SavedSurvey(
            file = file,
            label = if (ssid.isBlank()) bssid else ssid,
            ssid = ssid,
            bssid = bssid,
            sampleCount = count,
            savedAtMs = file.lastModified(),
        )
    }

    private fun parseLine(line: String): SurveySample? {
        if (line.isBlank()) return null
        val columns = splitCsv(line)
        if (columns.size < COLUMN_COUNT) return null
        return runCatching {
            SurveySample(
                atMs = columns[0].toLong(),
                bssid = columns[3],
                rssi = columns[4].toInt(),
                latitude = columns[5].toDouble(),
                longitude = columns[6].toDouble(),
                accuracyM = columns[7].toFloat(),
            )
        }.getOrNull()
    }

    /** Minimal RFC 4180 reader: only the SSID column is ever quoted. */
    private fun splitCsv(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }

                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    fields += current.toString()
                    current.clear()
                }

                else -> current.append(char)
            }
            index++
        }
        fields += current.toString()
        return fields
    }

    private fun escape(value: String): String =
        if (value.contains(',') || value.contains('"')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private companion object {
        const val HEADER =
            "timestamp_ms,timestamp_iso,ssid,bssid,rssi_dbm,latitude,longitude,gps_accuracy_m"
        const val COLUMN_COUNT = 8
        const val MAX_PREVIEW_ROWS = 1
        val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val ISO_STAMP = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}

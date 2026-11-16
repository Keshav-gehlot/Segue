package com.segue.app.bench

import android.content.Context
import com.segue.engine.CsvEventSink
import com.segue.engine.EventSink
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-session CSV event log, read by scripts/analyze_handoffs.py. Written to app-specific external
 * storage so it can be pulled without root:
 *   adb pull /sdcard/Android/data/com.segue.app/files/logs/
 */
class EventLogFile private constructor(val file: File, private val writer: BufferedWriter) : EventSink {
    private val csv = CsvEventSink(writer)

    override fun event(monoMs: Long, wallMs: Long, device: Int, kind: String, detail: String) =
        csv.event(monoMs, wallMs, device, kind, detail)

    fun close() = try { writer.close() } catch (e: IOException) { }

    companion object {
        fun open(context: Context): EventLogFile? = try {
            val dir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val f = File(dir, "segue-$stamp.csv")
            EventLogFile(f, BufferedWriter(FileWriter(f)))
        } catch (e: IOException) { null }
    }
}

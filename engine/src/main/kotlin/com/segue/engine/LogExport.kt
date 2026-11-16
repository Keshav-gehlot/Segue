package com.segue.engine

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packs a device's event logs into one ZIP: logs/<file>.csv plus small text entries (device info). */
object LogExport {
    fun zip(out: OutputStream, logs: List<File>, texts: Map<String, String>): Int {
        var n = 0
        ZipOutputStream(out).use { z ->
            for ((name, body) in texts) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
            for (f in logs.sortedBy { it.name }) {
                if (!f.isFile) continue
                z.putNextEntry(ZipEntry("logs/" + f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
                n++
            }
        }
        return n
    }
}

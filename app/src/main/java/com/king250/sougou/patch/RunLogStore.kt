package com.king250.sougou.patch

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RunLogEntry(
    val timestamp: Long,
    val level: String,
    val message: String,
) {
    val displayTime: String
        get() = requireNotNull(DATE_FORMAT.get()).format(Date(timestamp))

    fun encode(): String = listOf(
        timestamp.toString(),
        level.replace('\t', ' '),
        message.replace('\t', ' ').replace('\n', ' '),
    ).joinToString("\t")

    companion object {
        private val DATE_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        }

        fun decode(line: String): RunLogEntry? {
            val values = line.split('\t', limit = 3)
            if (values.size != 3) return null
            return RunLogEntry(
                timestamp = values[0].toLongOrNull() ?: return null,
                level = values[1],
                message = values[2],
            )
        }
    }
}

class RunLogStore(context: Context) {
    private val file = File(context.filesDir, "run.log")

    @Synchronized
    fun read(): List<RunLogEntry> = if (file.isFile) {
        file.readLines().mapNotNull(RunLogEntry::decode)
    } else {
        emptyList()
    }

    @Synchronized
    fun append(entry: RunLogEntry) {
        val entries = read().takeLast(MAX_ENTRIES - 1) + entry
        file.writeText(entries.joinToString("\n", postfix = "\n", transform = RunLogEntry::encode))
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.writeText("")
    }

    private companion object {
        const val MAX_ENTRIES = 1_000
    }
}

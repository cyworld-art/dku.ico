package com.dku.malnote.ui

import com.dku.malnote.data.Recording
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatDate(ts: Long): String = SimpleDateFormat("yyyy.MM.dd (E) HH:mm", Locale.KOREAN).format(Date(ts))

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%.0f KB".format(b / 1024.0)
}

private fun srtTime(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

fun Recording.toSrt(): String = buildString {
    segments.forEachIndexed { i, s ->
        append(i + 1).append('\n')
        append(srtTime(s.startMs)).append(" --> ").append(srtTime(s.endMs)).append('\n')
        append(s.text.trim()).append("\n\n")
    }
}

fun Recording.toPlainText(withTimestamps: Boolean): String = buildString {
    append(title).append('\n').append(formatDate(createdAt)).append("\n\n")
    segments.forEach { s ->
        if (withTimestamps) append('[').append(formatDuration(s.startMs)).append("] ")
        append(s.text.trim()).append('\n')
    }
}

package com.browns.cloudbuild

import java.util.Locale

object Fmt {
    fun bytes(n: Long): String = when {
        n >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", n / 1024.0 / 1024 / 1024)
        n >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", n / 1024.0 / 1024)
        n >= 1024 -> String.format(Locale.US, "%.1f KB", n / 1024.0)
        else -> "$n B"
    }

    fun speed(bps: Long): String = bytes(bps) + "/s"

    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }
}

class ApiException(val code: Int, message: String) : Exception(message)
package com.example.kioskbrowser

import android.net.Uri
import java.util.Locale

object UrlValidator {
    fun isValid(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false

        val uri = try {
            Uri.parse(trimmed)
        } catch (e: Exception) {
            return false
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false

        val host = uri.host ?: return false
        if (host.isBlank()) return false

        // If a port is specified, it must be valid.
        val port = uri.port
        if (port != -1 && (port < 1 || port > 65535)) return false

        return true
    }
}

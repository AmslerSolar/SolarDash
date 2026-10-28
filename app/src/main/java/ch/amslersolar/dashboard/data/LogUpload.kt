// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.dashboard.data

import android.content.Context
import ch.amslersolar.dashboard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Sends a register sweep log, only when the user confirmed it, to a server of their choice (Amsler Solar by
 * default). One HTTPS POST: the file as it is (`text/plain`), its name in `X-File-Name`, the app version in
 * `X-App-Version`. Nothing else is sent.
 *
 * So a server can tell these uploads from anything else at the same address, each one says what it is:
 * - `User-Agent: AmslerSolarDashboard/<version> (<build>; Android <release>)`
 * - `X-Dashboard-Upload: register-sweep-log/1` (kind and format version of the body)
 */
object LogUpload {
    /** The value of `X-Dashboard-Upload`: what the body is, and its format version. */
    const val KIND = "register-sweep-log/1"
    const val DEFAULT_URL = "https://amsler-solar.ch/"
    private const val PREFS = "log_upload"
    private const val KEY_URL = "url"

    fun url(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, null) ?: DEFAULT_URL

    fun setUrl(context: Context, url: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URL, url.trim()).apply()

    /** An https address with a host (plain http isn't allowed: the log names the device and its address). */
    fun valid(url: String): Boolean = runCatching {
        val u = URI(url.trim())
        u.scheme.equals("https", ignoreCase = true) && !u.host.isNullOrBlank()
    }.getOrDefault(false)

    fun userAgent(): String =
        "AmslerSolarDashboard/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}; Android ${android.os.Build.VERSION.RELEASE})"

    /** The server's HTTP status; throws when the server can't be reached or doesn't accept it (not 2xx). */
    suspend fun upload(file: File, url: String): Int = withContext(Dispatchers.IO) {
        require(valid(url)) { "Not an https address: $url" }
        val body = file.readBytes()
        val c = URI(url.trim()).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.setRequestProperty("X-File-Name", file.name)
            c.setRequestProperty("X-App-Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            c.setRequestProperty("User-Agent", userAgent())
            c.setRequestProperty("X-Dashboard-Upload", KIND)
            c.outputStream.use { it.write(body) }
            val status = c.responseCode
            if (status !in 200..299) throw IOException("The server answered HTTP $status")
            status
        } finally {
            c.disconnect()
        }
    }
}

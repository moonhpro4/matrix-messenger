package com.moonlight.matrixmessenger

/**
 * Central place for all credentials and settings.
 *
 * NOTE: Cloudflare credentials are no longer needed here at all — the app
 * talks to our Cloudflare Worker proxy (see CloudflareKvClient.kt), which
 * holds the real KV binding server-side. There's no API token anywhere
 * in this app anymore, which ends the token-leak/revocation loop we kept
 * hitting when a token lived in committed code or public web JS.
 *
 * Only the Gmail App Password still needs to be supplied via
 * local.properties (gitignored) -> BuildConfig, since email sending is
 * the one remaining thing this app does directly.
 */
object Config {
    // --- Gmail SMTP ---
    const val SMTP_HOST = "smtp.gmail.com"
    const val SMTP_PORT = 587
    const val GMAIL_USER = "moonhpro4@gmail.com"
    val GMAIL_APP_PASSWORD: String = BuildConfig.GMAIL_APP_PASSWORD
    const val FROM_DISPLAY_NAME = "Matrix"

    // --- App behavior ---
    const val MAGIC_LINK_EXPIRY_MINUTES = 20
    const val APP_URI_SCHEME = "myapp://verify?token="
}

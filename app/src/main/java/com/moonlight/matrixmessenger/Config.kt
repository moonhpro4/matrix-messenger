package com.moonlight.matrixmessenger

/**
 * Central place for all credentials and settings.
 *
 * IMPORTANT: real values are NOT stored here or committed to git.
 * They're loaded from local.properties (gitignored) via BuildConfig,
 * or you can set them directly in local.properties as:
 *   CLOUDFLARE_ACCOUNT_ID=xxx
 *   CLOUDFLARE_NAMESPACE_ID=xxx
 *   CLOUDFLARE_API_TOKEN=xxx
 *   GMAIL_APP_PASSWORD=xxx
 *
 * See README.md "Building from source" for setup instructions.
 */
object Config {
    // --- Cloudflare KV ---
    val CLOUDFLARE_ACCOUNT_ID: String = BuildConfig.CLOUDFLARE_ACCOUNT_ID
    val CLOUDFLARE_NAMESPACE_ID: String = BuildConfig.CLOUDFLARE_NAMESPACE_ID
    val CLOUDFLARE_API_TOKEN: String = BuildConfig.CLOUDFLARE_API_TOKEN

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


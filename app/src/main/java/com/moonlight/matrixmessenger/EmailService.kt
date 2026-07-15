package com.moonlight.matrixmessenger

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.SSLSocketFactory

/**
 * Sends email through Gmail's SMTP relay using an App Password.
 * Implements SMTP + STARTTLS manually over a raw socket so there's
 * no dependency on JavaMail or any external library.
 */
object EmailService {

    fun sendMagicLink(toEmail: String, token: String) {
        val link = Config.APP_URI_SCHEME + URLEncoder.encode(token, "UTF-8")

        val subject = "Your Matrix login link"
        val body = buildString {
            append("Click the link below to log in. This link expires in ")
            append("${Config.MAGIC_LINK_EXPIRY_MINUTES} minutes and can only be used once.\r\n\r\n")
            append(link)
            append("\r\n\r\nIf you didn't request this, you can ignore this email.\r\n")
        }

        sendRaw(toEmail, subject, body)
    }

    private fun sendRaw(toEmail: String, subject: String, body: String) {
        Socket(Config.SMTP_HOST, Config.SMTP_PORT).use { plainSocket ->
            var reader = BufferedReader(InputStreamReader(plainSocket.getInputStream(), StandardCharsets.UTF_8))
            var writer = PrintWriter(plainSocket.getOutputStream(), true)

            fun readResponse(): String {
                val sb = StringBuilder()
                var line: String?
                do {
                    line = reader.readLine() ?: break
                    sb.append(line).append("\n")
                } while (line != null && line.length >= 4 && line[3] == '-') // multi-line responses use "250-"
                return sb.toString()
            }

            fun expect(code: String, response: String) {
                if (!response.trimStart().startsWith(code)) {
                    throw RuntimeException("Unexpected SMTP response, expected $code: $response")
                }
            }

            readResponse() // server greeting (220)

            writer.print("EHLO matrixapp\r\n"); writer.flush()
            expect("250", readResponse())

            writer.print("STARTTLS\r\n"); writer.flush()
            expect("220", readResponse())

            // Upgrade the plain socket to TLS
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = sslFactory.createSocket(plainSocket, Config.SMTP_HOST, Config.SMTP_PORT, true)

            reader = BufferedReader(InputStreamReader(sslSocket.getInputStream(), StandardCharsets.UTF_8))
            writer = PrintWriter(sslSocket.getOutputStream(), true)

            writer.print("EHLO matrixapp\r\n"); writer.flush()
            expect("250", readResponse())

            writer.print("AUTH LOGIN\r\n"); writer.flush()
            expect("334", readResponse())

            val userB64 = Base64.getEncoder().encodeToString(Config.GMAIL_USER.toByteArray(StandardCharsets.UTF_8))
            writer.print("$userB64\r\n"); writer.flush()
            expect("334", readResponse())

            val passB64 = Base64.getEncoder().encodeToString(Config.GMAIL_APP_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            writer.print("$passB64\r\n"); writer.flush()
            expect("235", readResponse()) // authentication successful

            writer.print("MAIL FROM:<${Config.GMAIL_USER}>\r\n"); writer.flush()
            expect("250", readResponse())

            writer.print("RCPT TO:<$toEmail>\r\n"); writer.flush()
            expect("250", readResponse())

            writer.print("DATA\r\n"); writer.flush()
            expect("354", readResponse())

            val message = buildString {
                append("From: ${Config.FROM_DISPLAY_NAME} <${Config.GMAIL_USER}>\r\n")
                append("To: $toEmail\r\n")
                append("Subject: $subject\r\n")
                append("MIME-Version: 1.0\r\n")
                append("Content-Type: text/plain; charset=UTF-8\r\n")
                append("\r\n")
                append(body)
                append("\r\n.\r\n") // end of DATA marker
            }
            writer.print(message); writer.flush()
            expect("250", readResponse())

            writer.print("QUIT\r\n"); writer.flush()
            sslSocket.close()
        }
    }
}

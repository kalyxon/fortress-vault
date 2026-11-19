package com.fortress.vault.core

import java.net.IDN
import java.net.URI
import java.util.Locale

fun normalizeWebsiteDomain(value: String): String {
    val input = value.trim()
    require(input.isNotEmpty()) { "Enter a website address." }

    val candidate = if (input.contains("://")) input else "https://$input"
    val uri = try {
        URI(candidate)
    } catch (_: Exception) {
        throw IllegalArgumentException("Enter a valid website address, such as example.com.")
    }
    require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
        "Website addresses must use HTTP or HTTPS."
    }
    require(uri.userInfo == null && uri.rawAuthority?.contains('@') != true) {
        "Enter a website domain without a username."
    }

    val host = uri.host
        ?: throw IllegalArgumentException("Enter a valid website domain, such as example.com.")
    val domain = try {
        IDN.toASCII(host.trimEnd('.'), IDN.USE_STD3_ASCII_RULES)
            .lowercase(Locale.ROOT)
            .removePrefix("www.")
    } catch (_: Exception) {
        throw IllegalArgumentException("Enter a valid website domain, such as example.com.")
    }
    require(domain.length <= 253 && domain.contains('.') && domain.split('.').all { label ->
        label.isNotEmpty() && label.length <= 63 &&
            label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
            label.all { it.isLetterOrDigit() || it == '-' }
    }) {
        "Enter a valid website domain, such as example.com."
    }
    return domain
}

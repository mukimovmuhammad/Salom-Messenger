package com.mukimov.salom

import java.net.URI
import java.util.Locale

internal object ProfileLinks {
    private const val HOST = "mukimov.site"
    private val reserved = setOf("salom", "privacy", "terms")
    val inMessage = Regex("""https://mukimov\.site/(?:u/)?[A-Za-z][A-Za-z0-9_]{3,23}(?![A-Za-z0-9_]|/\w|\.[A-Za-z0-9])""")

    fun shareUrl(rawUsername: String): String? {
        val username = normalize(rawUsername.trim().removePrefix("@")) ?: return null
        val path = if (username in reserved) "u/$username" else username
        return "https://$HOST/$path"
    }

    fun username(url: String): String? {
        val uri = try { URI(url) } catch (_: java.net.URISyntaxException) { return null }
        if (uri.rawUserInfo != null || uri.port != -1) return null
        val path = uri.rawPath ?: return null
        return when {
            uri.scheme.equals("https", true) && uri.host.equals(HOST, true) ->
                if (path.startsWith("/u/")) normalize(path.removePrefix("/u/"))
                else normalize(path.removePrefix("/")).takeUnless { it in reserved }
            uri.scheme == "salom" && uri.host == "profile" -> normalize(path.removePrefix("/"))
            else -> null
        }
    }

    private fun normalize(username: String): String? =
        username.takeIf(AuthPolicy::validUsername)?.lowercase(Locale.ROOT)
}

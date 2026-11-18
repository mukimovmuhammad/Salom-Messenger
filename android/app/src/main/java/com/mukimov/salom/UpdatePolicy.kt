package com.mukimov.salom

import java.net.URI

internal data class AppRelease(val version: String, val code: Long, val minSdk: Int, val url: String, val size: Long, val sha256: String, val notes: List<String> = emptyList(), val displayName: String = "") {
    init {
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        require(displayName.length < 24 && displayName.matches(Regex("[A-Za-z0-9 -]*")))
        require(code > 0 && minSdk in 24..100 && size in 100_000L..104_857_600L)
        require(sha256.matches(Regex("[a-fA-F0-9]{64}")))
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "mukimov.site" && uri.port in listOf(-1, 443))
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(uri.rawPath == "/salom/salom-$version.apk" || uri.rawPath == "/salom/salom-latest.apk")
    }
    val displayVersion: String get() = version + if (displayName.isBlank()) "" else " $displayName"
}

internal enum class ReleaseRelation { CURRENT, NEWER_AVAILABLE, LOCAL_AHEAD, INCOMPATIBLE }
internal fun releaseRelation(localCode: Long, localVersion: String, sdk: Int, release: AppRelease): ReleaseRelation {
    // A version code identifies a build. Conflicting labels are not a trustworthy "latest" result.
    require(release.code != localCode || release.version == localVersion)
    return when {
        release.code < localCode -> ReleaseRelation.LOCAL_AHEAD
        release.code == localCode -> ReleaseRelation.CURRENT
        release.minSdk > sdk -> ReleaseRelation.INCOMPATIBLE
        else -> ReleaseRelation.NEWER_AVAILABLE
    }
}

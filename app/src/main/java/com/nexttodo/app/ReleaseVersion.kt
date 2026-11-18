package com.nexttodo.app

import java.math.BigInteger
import java.net.URI

internal const val RELEASES_URL = "https://github.com/Rkgua/Next-to-do/releases"
internal const val LATEST_RELEASE_API = "https://api.github.com/repos/Rkgua/Next-to-do/releases/latest"

internal data class ReleaseVersion(val parts: List<BigInteger>, val prerelease: String?) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        for (index in 0 until maxOf(parts.size, other.parts.size)) {
            val compared = (parts.getOrNull(index) ?: BigInteger.ZERO)
                .compareTo(other.parts.getOrNull(index) ?: BigInteger.ZERO)
            if (compared != 0) return compared
        }
        if (prerelease == other.prerelease) return 0
        if (prerelease == null) return 1
        if (other.prerelease == null) return -1
        val left = prerelease.split('.')
        val right = other.prerelease.split('.')
        for (index in 0 until minOf(left.size, right.size)) {
            val a = left[index].toBigIntegerOrNull()
            val b = right[index].toBigIntegerOrNull()
            val compared = when {
                a != null && b != null -> a.compareTo(b)
                a != null -> -1
                b != null -> 1
                else -> left[index].compareTo(right[index])
            }
            if (compared != 0) return compared
        }
        return left.size.compareTo(right.size)
    }

    companion object {
        fun parse(value: String): ReleaseVersion? {
            val match = Regex("^[vV]?(\\d+(?:\\.\\d+){1,3})(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z.-]+)?$")
                .matchEntire(value.trim()) ?: return null
            return ReleaseVersion(match.groupValues[1].split('.').map { it.toBigInteger() },
                match.groupValues[2].ifEmpty { null })
        }
    }
}

internal fun isProjectReleaseUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
        uri.rawPath.startsWith("/Rkgua/Next-to-do/releases/tag/") &&
        uri.rawPath.removePrefix("/Rkgua/Next-to-do/releases/tag/").isNotBlank() &&
        uri.fragment == null && uri.query == null && !uri.path.contains("/../")
}.getOrDefault(false)

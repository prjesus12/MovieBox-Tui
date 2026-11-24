package com.moviebox.android

import uniffi.moviebox_tui.MobileHistoryItem
import uniffi.moviebox_tui.MobileRelease
import java.util.Locale

/** Formatting and source-ranking helpers shared by the screens and the player. */
object Format {
    fun bytes(value: ULong?): String? {
        val bytes = value?.toDouble() ?: return null
        if (bytes <= 0) return null
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var size = bytes
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) {
            size /= 1024
            unit++
        }
        return if (unit >= 3) String.format(Locale.US, "%.1f %s", size, units[unit])
        else String.format(Locale.US, "%.0f %s", size, units[unit])
    }

    fun clock(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    fun episodeLabel(season: UInt, episode: UInt): String = "S$season · E$episode"

    fun progress(entry: MobileHistoryItem): Float {
        val duration = entry.durationSeconds?.toDouble() ?: return 0f
        if (duration <= 0) return 0f
        return (entry.progressSeconds.toDouble() / duration).toFloat().coerceIn(0f, 1f)
    }

    /** 0 is best. Mirrors the iOS ranking: 1080p first, then 720p, 4K, lower. */
    private fun qualityRank(quality: String?): Int {
        val q = quality?.lowercase() ?: return 6
        return when {
            q.contains("1080") -> 0
            q.contains("720") -> 1
            q.contains("2160") || q.contains("4k") -> 2
            q.contains("1440") -> 3
            q.contains("480") -> 4
            q.contains("360") -> 5
            else -> 6
        }
    }

    /** H.264 decodes everywhere; HEVC/AV1 depend on the device. */
    private fun codecRank(codec: String?): Int {
        val c = codec?.lowercase() ?: return 1
        return when {
            c.contains("264") || c.contains("avc") -> 0
            c.contains("265") || c.contains("hevc") -> 1
            c.contains("av1") -> 2
            else -> 1
        }
    }

    fun isPlayable(release: MobileRelease): Boolean = !release.url.isNullOrBlank()

    /**
     * Playable candidates in the order they should be tried. ExoPlayer opens
     * direct files, HLS and DASH alike, so every release with a URL counts;
     * direct files still go first because they start fastest.
     */
    fun playableOrdered(releases: List<MobileRelease>): List<MobileRelease> =
        releases.filter(::isPlayable)
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<MobileRelease>>(
                    { if (it.value.isDirectFile) 0 else 1 },
                    { codecRank(it.value.codec) * 8 + qualityRank(it.value.quality) },
                    { it.index },
                ),
            )
            .map { it.value }

    fun releaseLabel(release: MobileRelease): String =
        listOfNotNull(release.quality, release.sourceLabel)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifEmpty { release.sourceLabel }

    /** Extra sources tried after the first one fails. */
    const val MAX_FALLBACK_ATTEMPTS = 2

    fun fallbackCandidates(after: MobileRelease, candidates: List<MobileRelease>): List<MobileRelease> {
        val index = candidates.indexOf(after)
        val rest = if (index >= 0) candidates.drop(index + 1) else candidates
        return rest.filter { it.url != after.url }
    }
}

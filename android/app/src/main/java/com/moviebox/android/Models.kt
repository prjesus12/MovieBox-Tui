package com.moviebox.android

import uniffi.moviebox_tui.MobileCatalogItem
import uniffi.moviebox_tui.MobileDetails
import uniffi.moviebox_tui.MobileHeader
import uniffi.moviebox_tui.MobileRelease

sealed interface LoadPhase {
    data object Idle : LoadPhase
    data object Loading : LoadPhase
    data object Loaded : LoadPhase
    data class Failed(val message: String) : LoadPhase
}

data class HomeTab(val id: String, val label: String)

val HOME_TABS = listOf(
    HomeTab("1", "Home"),
    HomeTab("2", "Movies"),
    HomeTab("5", "TV Shows"),
    HomeTab("8", "Anime"),
)

data class HomeFeed(
    val items: List<MobileCatalogItem> = emptyList(),
    val phase: LoadPhase = LoadPhase.Idle,
    val page: UInt = 0u,
    val canLoadMore: Boolean = true,
    val isLoadingMore: Boolean = false,
)

/** A title or episode the viewer wants sources for (the source sheet). */
data class SourceRequest(
    val subjectId: String,
    val provider: String,
    val title: String,
    val year: String,
    val posterUrl: String?,
    val isSeries: Boolean,
    val season: UInt,
    val episode: UInt,
    val episodeTitle: String?,
    val startAtSeconds: Long,
    val siblingIds: List<String> = emptyList(),
) {
    val contextLabel: String
        get() = if (isSeries) {
            buildString {
                append("Season $season · Episode $episode")
                if (!episodeTitle.isNullOrBlank()) append(" · $episodeTitle")
            }
        } else {
            "Movie"
        }

    companion object {
        fun from(
            details: MobileDetails,
            season: UInt,
            episode: UInt,
            episodeTitle: String?,
            startAtSeconds: Long = 0,
        ) = SourceRequest(
            subjectId = details.id,
            provider = details.provider,
            title = details.title,
            year = details.year.orEmpty(),
            posterUrl = details.posterUrl,
            isSeries = details.isSeries,
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
            startAtSeconds = startAtSeconds,
            siblingIds = details.siblingSubjectIds,
        )
    }
}

/** A resolved stream handed to [PlayerActivity] through [PlaybackSession]. */
data class PlaybackRequest(
    val source: SourceRequest,
    val release: MobileRelease,
    val startAtSeconds: Long,
    val alternatives: List<MobileRelease>,
) {
    val url: String get() = release.url.orEmpty()
    val headers: List<MobileHeader> get() = release.headers
    val releaseLabel: String get() = Format.releaseLabel(release)
}

/** In-process handoff between the catalog UI and the player activity. */
object PlaybackSession {
    @Volatile
    var current: PlaybackRequest? = null
}

fun MobileCatalogItem.key(): String = "$provider|$id"

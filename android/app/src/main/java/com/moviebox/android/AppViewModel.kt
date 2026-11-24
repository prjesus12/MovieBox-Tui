package com.moviebox.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uniffi.moviebox_tui.MobileCatalogItem
import uniffi.moviebox_tui.MobileDetails
import uniffi.moviebox_tui.MobileHistoryItem
import uniffi.moviebox_tui.MobileRelease
import uniffi.moviebox_tui.MobileSubtitle

/** Screens stacked on top of the tabbed root. */
sealed interface Route {
    data class Details(val subjectId: String, val title: String, val posterUrl: String?) : Route
}

class AppViewModel : ViewModel() {
    // Navigation
    var selectedTab by mutableStateOf(0)
    val stack = mutableStateListOf<Route>()

    // Home
    val feeds = mutableStateMapOf<String, HomeFeed>()

    // Search
    var searchQuery by mutableStateOf("")
    var searchPhase by mutableStateOf<LoadPhase>(LoadPhase.Idle)
        private set
    var searchResults by mutableStateOf<List<MobileCatalogItem>>(emptyList())
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    private var searchPage = 0u
    private var searchCanLoadMore = true
    private var isLoadingMoreSearch = false
    private var searchJob: Job? = null

    // Library
    var favorites by mutableStateOf<List<MobileCatalogItem>>(emptyList())
        private set
    var history by mutableStateOf<List<MobileHistoryItem>>(emptyList())
        private set

    // Sources sheet
    var sourceRequest by mutableStateOf<SourceRequest?>(null)

    // Transient message
    var banner by mutableStateOf<String?>(null)

    fun open(route: Route) {
        stack.add(route)
    }

    fun back(): Boolean {
        if (sourceRequest != null) {
            sourceRequest = null
            return true
        }
        if (stack.isNotEmpty()) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        return false
    }

    // region Home

    fun feed(tabId: String): HomeFeed = feeds[tabId] ?: HomeFeed()

    fun loadHome(tabId: String, refresh: Boolean = false) {
        val current = feed(tabId)
        if (current.phase == LoadPhase.Loading || current.isLoadingMore) return
        if (!refresh && current.phase == LoadPhase.Loaded && current.items.isNotEmpty()) return
        feeds[tabId] = current.copy(phase = LoadPhase.Loading, canLoadMore = true)
        viewModelScope.launch {
            try {
                val items = withCategoryFallback(tabId, Bridge.call { it.home(tabId, 1u) })
                feeds[tabId] = HomeFeed(
                    items = appendUnique(emptyList(), items),
                    phase = LoadPhase.Loaded,
                    page = 1u,
                    canLoadMore = items.isNotEmpty(),
                )
            } catch (error: Throwable) {
                feeds[tabId] = feed(tabId).copy(phase = LoadPhase.Failed(Bridge.message(error)))
            }
        }
    }

    fun loadMoreHome(tabId: String) {
        val current = feed(tabId)
        if (current.phase != LoadPhase.Loaded || !current.canLoadMore || current.isLoadingMore) return
        feeds[tabId] = current.copy(isLoadingMore = true)
        val next = current.page + 1u
        viewModelScope.launch {
            try {
                val items = Bridge.call { it.home(tabId, next) }
                val latest = feed(tabId)
                val merged = appendUnique(latest.items, items)
                feeds[tabId] = latest.copy(
                    items = merged,
                    page = next,
                    isLoadingMore = false,
                    // The home endpoint can ignore `page` and repeat itself: stop once
                    // a page adds nothing new instead of asking forever.
                    canLoadMore = merged.size > latest.items.size,
                )
            } catch (error: Throwable) {
                feeds[tabId] = feed(tabId).copy(isLoadingMore = false, canLoadMore = false)
                banner = Bridge.message(error)
            }
        }
    }

    // endregion

    // region Search

    fun runSearch(query: String = searchQuery) {
        val trimmed = query.trim()
        searchQuery = query
        searchJob?.cancel()
        if (trimmed.isEmpty()) {
            searchResults = emptyList()
            suggestions = emptyList()
            searchPhase = LoadPhase.Idle
            return
        }
        searchPhase = LoadPhase.Loading
        suggestions = emptyList()
        searchJob = viewModelScope.launch {
            try {
                val items = Bridge.call { it.search(trimmed, 1u) }
                searchResults = appendUnique(emptyList(), items)
                searchPage = 1u
                searchCanLoadMore = items.isNotEmpty()
                searchPhase = LoadPhase.Loaded
                if (items.isEmpty()) {
                    suggestions = runCatching { Bridge.call { it.suggest(trimmed) } }
                        .getOrDefault(emptyList())
                        .take(8)
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                searchPhase = LoadPhase.Failed(Bridge.message(error))
            }
        }
    }

    fun loadMoreSearch() {
        if (searchPhase != LoadPhase.Loaded || !searchCanLoadMore || isLoadingMoreSearch) return
        val query = searchQuery.trim()
        if (query.isEmpty()) return
        isLoadingMoreSearch = true
        val next = searchPage + 1u
        viewModelScope.launch {
            try {
                val items = Bridge.call { it.search(query, next) }
                if (query == searchQuery.trim()) {
                    val merged = appendUnique(searchResults, items)
                    searchCanLoadMore = merged.size > searchResults.size
                    searchResults = merged
                    searchPage = next
                }
            } catch (_: Throwable) {
                searchCanLoadMore = false
            } finally {
                isLoadingMoreSearch = false
            }
        }
    }

    // endregion

    // region Details / sources

    suspend fun details(subjectId: String): MobileDetails = Bridge.call { it.details(subjectId) }

    suspend fun releases(request: SourceRequest): List<MobileRelease> =
        Bridge.call { it.releases(request.subjectId, request.season, request.episode) }

    // endregion

    // region Library

    fun refreshLibrary() {
        viewModelScope.launch {
            favorites = Bridge.call { it.favorites() }.reversed()
            history = Bridge.call { it.history() }.reversed()
        }
    }

    suspend fun isFavorite(item: MobileCatalogItem): Boolean =
        Bridge.call { it.isFavorite(item.id, item.provider) }

    suspend fun toggleFavorite(item: MobileCatalogItem): Boolean {
        val now = Bridge.call { it.toggleFavorite(item) }
        favorites = Bridge.call { it.favorites() }.reversed()
        banner = if (now) "Added “${item.title}” to favorites" else "Removed “${item.title}” from favorites"
        return now
    }

    fun removeHistory(entry: MobileHistoryItem) {
        viewModelScope.launch {
            Bridge.call { it.removeHistoryEntry(entry.provider, entry.subjectId, entry.season, entry.episode) }
            history = Bridge.call { it.history() }.reversed()
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            Bridge.call { it.clearHistory() }
            history = emptyList()
            banner = "Watch history cleared"
        }
    }

    fun resume(entry: MobileHistoryItem) {
        sourceRequest = SourceRequest(
            subjectId = entry.subjectId,
            provider = entry.provider,
            title = entry.title,
            year = entry.year,
            posterUrl = entry.posterUrl,
            isSeries = entry.isSeries,
            season = entry.season,
            episode = entry.episode,
            episodeTitle = null,
            startAtSeconds = if (entry.completed) 0 else entry.progressSeconds.toLong(),
        )
    }

    // endregion

    // region Deep links

    /** `moviebox://play/<subjectId>?season=1&episode=2` opens the source sheet. */
    fun handleDeepLink(uri: android.net.Uri) {
        if (uri.scheme != "moviebox" || uri.host != "play") return
        val subjectId = uri.pathSegments.firstOrNull().orEmpty()
        if (subjectId.isEmpty()) {
            banner = "That link is missing a title identifier."
            return
        }
        viewModelScope.launch {
            try {
                val details = details(subjectId)
                val season = uri.getQueryParameter("season")?.toUIntOrNull()
                    ?: details.seasons.firstOrNull()?.number ?: 0u
                val episode = uri.getQueryParameter("episode")?.toUIntOrNull()
                    ?: details.seasons.firstOrNull()?.episodes?.firstOrNull()?.number ?: 0u
                sourceRequest = if (details.isSeries) {
                    SourceRequest.from(details, season, episode, null)
                } else {
                    SourceRequest.from(details, 0u, 0u, null)
                }
            } catch (error: Throwable) {
                banner = Bridge.message(error)
            }
        }
    }

    // endregion

    /**
     * Movies and TV Shows are curated pages made mostly of category tiles, so they can
     * hold one or two playable titles. Home lists the same catalogue as plain titles,
     * so borrow its movies or series (the Roku channel does the same). Anime has no
     * genre on [MobileCatalogItem] to filter by, so it keeps what the API returns.
     */
    private suspend fun withCategoryFallback(
        tabId: String,
        items: List<MobileCatalogItem>,
    ): List<MobileCatalogItem> {
        val wantSeries = when (tabId) {
            "2" -> false
            "5" -> true
            else -> return items
        }
        if (items.size >= CATEGORY_MIN_ITEMS) return items
        val home = runCatching { Bridge.call { it.home("1", 1u) } }.getOrNull() ?: return items
        return appendUnique(items, home.filter { it.isSeries == wantSeries })
    }

    private fun appendUnique(
        existing: List<MobileCatalogItem>,
        incoming: List<MobileCatalogItem>,
    ): List<MobileCatalogItem> {
        val seen = existing.mapTo(HashSet()) { it.key() }
        return existing + incoming.filter { seen.add(it.key()) }
    }

    private companion object {
        const val CATEGORY_MIN_ITEMS = 8
    }
}

package com.moviebox.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moviebox.android.AppViewModel
import com.moviebox.android.HOME_TABS
import com.moviebox.android.LoadPhase
import com.moviebox.android.Route

@Composable
fun HomeScreen(model: AppViewModel) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tab = HOME_TABS[selected]

    LaunchedEffect(tab.id) { model.loadHome(tab.id) }

    Column(Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
            HOME_TABS.forEachIndexed { index, homeTab ->
                Tab(
                    selected = index == selected,
                    onClick = { selected = index },
                    text = { Text(homeTab.label) },
                    selectedContentColor = Palette.text,
                    unselectedContentColor = Palette.subtle,
                )
            }
        }
        val feed = model.feed(tab.id)
        Box(Modifier.fillMaxSize()) {
            when (val phase = feed.phase) {
                LoadPhase.Idle, LoadPhase.Loading ->
                    if (feed.items.isEmpty()) LoadingBlock("Loading ${tab.label}…")
                is LoadPhase.Failed ->
                    if (feed.items.isEmpty()) ErrorBlock(phase.message) { model.loadHome(tab.id, refresh = true) }
                LoadPhase.Loaded ->
                    if (feed.items.isEmpty()) EmptyHint("Nothing here", "The provider returned no titles for this tab.")
            }
            if (feed.items.isNotEmpty()) {
                // One grid state per tab, so switching tabs keeps each scroll position.
                val gridState = rememberSaveable(tab.id, saver = androidx.compose.foundation.lazy.grid.LazyGridState.Saver) {
                    androidx.compose.foundation.lazy.grid.LazyGridState()
                }
                PosterGrid(
                    items = feed.items,
                    onOpen = { model.open(Route.Details(it.id, it.title, it.posterUrl)) },
                    onLoadMore = { model.loadMoreHome(tab.id) },
                    isLoadingMore = feed.isLoadingMore,
                    state = gridState,
                )
            }
        }
    }
}

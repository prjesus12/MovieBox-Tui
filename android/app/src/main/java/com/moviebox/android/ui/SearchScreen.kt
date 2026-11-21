package com.moviebox.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.moviebox.android.AppViewModel
import com.moviebox.android.LoadPhase
import com.moviebox.android.Route

@Composable
fun SearchScreen(model: AppViewModel) {
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = model.searchQuery,
            onValueChange = { model.searchQuery = it },
            placeholder = { Text("Search movies, shows, anime") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (model.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { model.runSearch("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focus.clearFocus()
                model.runSearch()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        when (val phase = model.searchPhase) {
            LoadPhase.Idle -> EmptyHint("Search", "Type a title and press search.")
            LoadPhase.Loading -> LoadingBlock("Searching…")
            is LoadPhase.Failed -> ErrorBlock(phase.message) { model.runSearch() }
            LoadPhase.Loaded -> {
                if (model.searchResults.isEmpty()) {
                    EmptyHint("No results", "Nothing matched “${model.searchQuery.trim()}”.")
                    if (model.suggestions.isNotEmpty()) {
                        Text("Did you mean", modifier = Modifier.padding(horizontal = 16.dp), color = Palette.subtle)
                        FlowRow(Modifier.padding(16.dp)) {
                            model.suggestions.forEach { term ->
                                AssistChip(
                                    onClick = { model.runSearch(term) },
                                    label = { Text(term) },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        }
                    }
                } else {
                    PosterGrid(
                        items = model.searchResults,
                        onOpen = { model.open(Route.Details(it.id, it.title, it.posterUrl)) },
                        onLoadMore = { model.loadMoreSearch() },
                        isLoadingMore = false,
                    )
                }
            }
        }
    }
}

package com.moviebox.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moviebox.android.AppViewModel
import com.moviebox.android.Format
import com.moviebox.android.Route

@Composable
fun LibraryScreen(model: AppViewModel) {
    LaunchedEffect(Unit) { model.refreshLibrary() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Continue watching", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (model.history.isNotEmpty()) {
                    TextButton(onClick = { model.clearHistory() }) { Text("Clear") }
                }
            }
        }
        if (model.history.isEmpty()) {
            item { EmptyHint("No history yet", "Titles you play show up here so you can resume them.") }
        }
        items(model.history, key = { "${it.provider}|${it.subjectId}|${it.season}|${it.episode}" }) { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { model.resume(entry) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Poster(entry.posterUrl, entry.title, Modifier.width(56.dp).height(84.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val label = buildString {
                        if (entry.isSeries) append(Format.episodeLabel(entry.season, entry.episode)).append(" · ")
                        append(if (entry.completed) "Watched" else Format.clock(entry.progressSeconds.toLong()))
                        entry.durationSeconds?.let { append(" / ").append(Format.clock(it.toLong())) }
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.subtle)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { if (entry.completed) 1f else Format.progress(entry) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(onClick = { model.removeHistory(entry) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = Palette.subtle)
                }
            }
        }
        item {
            Text(
                "Favorites",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
            )
        }
        if (model.favorites.isEmpty()) {
            item { EmptyHint("No favorites", "Tap the heart on a title to keep it here.") }
        } else {
            item {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(model.favorites, key = { "${it.provider}|${it.id}" }) { item ->
                        Column(Modifier.width(110.dp)) {
                            PosterCard(item) { model.open(Route.Details(item.id, item.title, item.posterUrl)) }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }
}

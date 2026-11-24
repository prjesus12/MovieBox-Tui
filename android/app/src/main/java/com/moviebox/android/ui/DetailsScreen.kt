package com.moviebox.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moviebox.android.AppViewModel
import com.moviebox.android.Bridge
import com.moviebox.android.LoadPhase
import com.moviebox.android.Route
import com.moviebox.android.SourceRequest
import kotlinx.coroutines.launch
import uniffi.moviebox_tui.MobileCatalogItem
import uniffi.moviebox_tui.MobileDetails

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(model: AppViewModel, route: Route.Details) {
    var phase by remember(route.subjectId) { mutableStateOf<LoadPhase>(LoadPhase.Loading) }
    var details by remember(route.subjectId) { mutableStateOf<MobileDetails?>(null) }
    var favorite by remember(route.subjectId) { mutableStateOf(false) }
    var selectedSeason by remember(route.subjectId) { mutableStateOf<UInt?>(null) }
    var attempt by remember(route.subjectId) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(route.subjectId, attempt) {
        phase = LoadPhase.Loading
        try {
            val loaded = model.details(route.subjectId)
            details = loaded
            selectedSeason = loaded.seasons.firstOrNull()?.number
            favorite = model.isFavorite(loaded.asCatalogItem())
            phase = LoadPhase.Loaded
        } catch (error: Throwable) {
            phase = LoadPhase.Failed(Bridge.message(error))
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(route.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = { model.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                val loaded = details
                if (loaded != null) {
                    IconButton(onClick = {
                        scope.launch { favorite = model.toggleFavorite(loaded.asCatalogItem()) }
                    }) {
                        Icon(
                            if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (favorite) Palette.accent else Palette.text,
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.background),
        )

        when (val current = phase) {
            LoadPhase.Idle, LoadPhase.Loading -> LoadingBlock("Loading details…")
            is LoadPhase.Failed -> ErrorBlock(current.message) { attempt++ }
            LoadPhase.Loaded -> details?.let { loaded ->
                DetailsBody(
                    details = loaded,
                    selectedSeason = selectedSeason,
                    onSelectSeason = { selectedSeason = it },
                    onPlay = { season, episode, title ->
                        model.sourceRequest = SourceRequest.from(loaded, season, episode, title)
                    },
                )
            }
        }
    }
}

@Composable
private fun DetailsBody(
    details: MobileDetails,
    selectedSeason: UInt?,
    onSelectSeason: (UInt) -> Unit,
    onPlay: (UInt, UInt, String?) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp)) {
                Poster(details.posterUrl, details.title, Modifier.width(120.dp).height(180.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(details.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    val meta = listOfNotNull(
                        details.year,
                        details.runtime,
                        details.rating?.let { "★ $it" },
                        if (details.isSeries) "Series" else "Movie",
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.labelMedium, color = Palette.subtle)
                    if (details.genres.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            details.genres.forEach { TagChip(it) }
                        }
                    }
                    if (!details.isSeries) {
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = { onPlay(0u, 0u, null) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Play")
                        }
                    }
                }
            }
        }
        details.tagline?.takeIf { it.isNotBlank() }?.let { tagline ->
            item {
                Text(
                    tagline,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        details.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            item {
                Text(
                    overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.text.copy(alpha = 0.85f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        val credits = listOfNotNull(
            details.director?.takeIf { it.isNotBlank() }?.let { "Director: $it" },
            details.stars?.takeIf { it.isNotBlank() }?.let { "Cast: $it" },
        )
        items(credits) { line ->
            Text(
                line,
                style = MaterialTheme.typography.labelMedium,
                color = Palette.subtle,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        if (details.isSeries && details.seasons.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(details.seasons, key = { it.number.toInt() }) { season ->
                        FilterChip(
                            selected = season.number == selectedSeason,
                            onClick = { onSelectSeason(season.number) },
                            label = { Text("Season ${season.number}") },
                        )
                    }
                }
            }
            val season = details.seasons.firstOrNull { it.number == selectedSeason } ?: details.seasons.first()
            items(season.episodes, key = { "${it.season}-${it.number}" }) { episode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(episode.season, episode.number, episode.title) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(40.dp)) {
                        Text("${episode.number}", style = MaterialTheme.typography.titleMedium, color = Palette.subtle)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            episode.title?.takeIf { it.isNotBlank() } ?: "Episode ${episode.number}",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        episode.overview?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = Palette.subtle,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Palette.accent)
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

fun MobileDetails.asCatalogItem() = MobileCatalogItem(
    id = id,
    provider = provider,
    title = title,
    isSeries = isSeries,
    year = year,
    posterUrl = posterUrl,
)

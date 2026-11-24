package com.moviebox.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moviebox.android.AppViewModel
import com.moviebox.android.Bridge
import com.moviebox.android.Format
import com.moviebox.android.LoadPhase
import com.moviebox.android.PlaybackRequest
import com.moviebox.android.SourceRequest
import uniffi.moviebox_tui.MobileRelease

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesSheet(
    model: AppViewModel,
    request: SourceRequest,
    onPlay: (PlaybackRequest) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var phase by remember(request) { mutableStateOf<LoadPhase>(LoadPhase.Loading) }
    var releases by remember(request) { mutableStateOf<List<MobileRelease>>(emptyList()) }
    var attempt by remember(request) { mutableIntStateOf(0) }

    LaunchedEffect(request, attempt) {
        phase = LoadPhase.Loading
        try {
            releases = Format.playableOrdered(model.releases(request))
            phase = LoadPhase.Loaded
        } catch (error: Throwable) {
            phase = LoadPhase.Failed(Bridge.message(error))
        }
    }

    ModalBottomSheet(
        onDismissRequest = { model.sourceRequest = null },
        sheetState = sheetState,
        containerColor = Palette.surface,
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(request.posterUrl, request.title, Modifier.width(48.dp).height(72.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(request.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(request.contextLabel, style = MaterialTheme.typography.labelMedium, color = Palette.subtle)
                if (request.startAtSeconds > 0) {
                    Text(
                        "Resuming at ${Format.clock(request.startAtSeconds)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.accent,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (val current = phase) {
            LoadPhase.Idle, LoadPhase.Loading -> LoadingBlock("Finding sources…")
            is LoadPhase.Failed -> ErrorBlock(current.message) { attempt++ }
            LoadPhase.Loaded -> if (releases.isEmpty()) {
                EmptyHint("No sources", "The provider returned no playable streams. Try another episode or come back later.")
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(releases) { release ->
                        ReleaseRow(release) {
                            onPlay(
                                PlaybackRequest(
                                    source = request,
                                    release = release,
                                    startAtSeconds = request.startAtSeconds,
                                    alternatives = Format.fallbackCandidates(release, releases),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseRow(release: MobileRelease, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Palette.accent)
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                release.filename,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                release.quality?.takeIf { it.isNotBlank() }?.let { TagChip(it, highlighted = true) }
                release.codec?.takeIf { it.isNotBlank() }?.let { TagChip(it) }
                release.language?.takeIf { it.isNotBlank() }?.let { TagChip(it.uppercase()) }
                Format.bytes(release.sizeBytes)?.let { TagChip(it) }
                if (!release.isDirectFile) TagChip("Stream")
                if (release.resourceId != null) TagChip("CC")
            }
            Text(release.sourceLabel, style = MaterialTheme.typography.labelSmall, color = Palette.subtle)
        }
    }
}

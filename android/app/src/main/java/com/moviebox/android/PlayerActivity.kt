package com.moviebox.android

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.moviebox.android.ui.MovieBoxTheme
import com.moviebox.android.ui.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.moviebox_tui.MobileHistoryItem
import uniffi.moviebox_tui.MobileRelease
import uniffi.moviebox_tui.MobileSubtitle

/**
 * Full-screen ExoPlayer. Unlike AVPlayer on iOS, ExoPlayer opens direct files,
 * HLS and DASH alike, and sends the provider's request headers on every segment.
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {
    private lateinit var request: PlaybackRequest
    private var player: ExoPlayer? = null

    private var currentRelease: MobileRelease? = null
    private var remaining: List<MobileRelease> = emptyList()
    private var fallbackAttempts = 0
    @Volatile private var preferSoftwareDecoders = false
    private var progressJob: Job? = null

    private var subtitles by mutableStateOf<List<MobileSubtitle>>(emptyList())
    private var subtitlesLoading by mutableStateOf(true)
    private var selectedSubtitle by mutableStateOf<MobileSubtitle?>(null)
    private var showSubtitlePicker by mutableStateOf(false)
    private var statusMessage by mutableStateOf<String?>(null)
    private var fatalError by mutableStateOf<String?>(null)
    private var releaseLabel by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pending = PlaybackSession.current
        if (pending == null || pending.url.isBlank()) {
            finish()
            return
        }
        request = pending
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // Decoder fallback lets ExoPlayer try the next decoder for the codec (for
        // example a software HEVC decoder) when the first one fails to initialise.
        // After a decoding error the same source is retried with software decoders
        // first: some hardware decoders (the emulator's among them) reject streams
        // the software ones play.
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector { mimeType, secure, tunneling ->
                val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                if (preferSoftwareDecoders) decoders.sortedBy { it.hardwareAccelerated } else decoders
            }
        val exo = ExoPlayer.Builder(this, renderers).build().also { player = it }
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleError(error)

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) statusMessage = null
                if (state == Player.STATE_ENDED) {
                    saveProgress(completed = true)
                    finish()
                }
            }
        })
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setSelectUndeterminedTextLanguage(true)
            .build()

        remaining = request.alternatives
        val startMs = request.startAtSeconds.coerceAtLeast(0) * 1000
        load(request.release, startMs)
        loadSubtitles()
        startProgressTicker()

        setContent {
            MovieBoxTheme {
                PlayerScreen()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @androidx.compose.runtime.Composable
    private fun PlayerScreen() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        player = this@PlayerActivity.player
                        keepScreenOn = true
                        setShowSubtitleButton(true)
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == android.view.View.VISIBLE
                        })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (controlsVisible || fatalError != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            request.source.title,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOf(request.source.contextLabel, releaseLabel).filter { it.isNotBlank() }.joinToString(" · "),
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = { showSubtitlePicker = true }) {
                        Icon(
                            Icons.Default.ClosedCaption,
                            contentDescription = "Subtitles",
                            tint = if (selectedSubtitle != null) Palette.accent else Color.White,
                        )
                    }
                }
            }

            statusMessage?.let {
                Text(
                    it,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 96.dp)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            fatalError?.let { message ->
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .background(Palette.surface)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Playback failed", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(message, color = Palette.subtle, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { finish() }, modifier = Modifier.padding(top = 12.dp)) { Text("Close") }
                }
            }
        }

        if (showSubtitlePicker) {
            AlertDialog(
                onDismissRequest = { showSubtitlePicker = false },
                confirmButton = {
                    TextButton(onClick = { showSubtitlePicker = false }) { Text("Close") }
                },
                title = { Text("Subtitles") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        item {
                            SubtitleRow("Off", selectedSubtitle == null) { chooseSubtitle(null) }
                        }
                        if (subtitlesLoading) {
                            item { Text("Loading subtitles…", color = Palette.subtle, modifier = Modifier.padding(12.dp)) }
                        } else if (subtitles.isEmpty()) {
                            item { Text("No subtitles found.", color = Palette.subtle, modifier = Modifier.padding(12.dp)) }
                        }
                        items(subtitles) { subtitle ->
                            SubtitleRow(subtitle.name, subtitle == selectedSubtitle) { chooseSubtitle(subtitle) }
                        }
                    }
                },
            )
        }
    }

    private var controlsVisible by mutableStateOf(true)

    @androidx.compose.runtime.Composable
    private fun SubtitleRow(label: String, selected: Boolean, onClick: () -> Unit) {
        Text(
            text = if (selected) "✓  $label" else "     $label",
            color = if (selected) Palette.accent else Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp, horizontal = 8.dp),
        )
    }

    // region Playback

    private fun load(release: MobileRelease, startMs: Long) {
        val exo = player ?: return
        val url = release.url ?: return
        currentRelease = release
        releaseLabel = Format.releaseLabel(release)

        val headers = release.headers.associate { it.name to it.value }
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        val dataSource = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        if (userAgent != null) dataSource.setUserAgent(userAgent)

        val builder = MediaItem.Builder().setUri(Uri.parse(url))
        mimeTypeFor(url)?.let(builder::setMimeType)
        selectedSubtitle?.let { subtitle ->
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                        .setMimeType(subtitleMimeType(subtitle.url))
                        .setLabel(subtitle.name)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                        .build(),
                ),
            )
        }

        val source = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSource)
            .createMediaSource(builder.build())
        Log.i(TAG, "loading ${releaseLabel} at ${startMs}ms: $url")
        exo.setMediaSource(source, startMs)
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun handleError(error: PlaybackException) {
        Log.w(TAG, "playback error ${error.errorCodeName}", error)
        val position = player?.currentPosition ?: 0L
        val release = currentRelease
        if (isDecoderError(error) && !preferSoftwareDecoders && release != null) {
            preferSoftwareDecoders = true
            statusMessage = "Retrying with a software decoder…"
            load(release, position)
            return
        }
        val next = remaining.firstOrNull()
        if (next != null && fallbackAttempts < Format.MAX_FALLBACK_ATTEMPTS) {
            fallbackAttempts++
            remaining = remaining.drop(1)
            statusMessage = "Source failed, trying ${Format.releaseLabel(next)}…"
            load(next, position)
            return
        }
        statusMessage = null
        fatalError = if (isDecoderError(error)) {
            "This device could not decode this video ($releaseLabel). Try another source."
        } else {
            "${error.errorCodeName}: ${error.message ?: "the stream could not be opened"}"
        }
    }

    private fun chooseSubtitle(subtitle: MobileSubtitle?) {
        showSubtitlePicker = false
        if (subtitle == selectedSubtitle) return
        selectedSubtitle = subtitle
        val release = currentRelease ?: return
        // A side-loaded subtitle is part of the media item, so switching it
        // re-prepares the stream at the current position.
        load(release, player?.currentPosition ?: 0L)
    }

    private fun loadSubtitles() {
        lifecycleScope.launch {
            val provider = async { runCatching { Bridge.captions(request) }.getOrDefault(emptyList()) }
            val online = async {
                runCatching {
                    Bridge.call {
                        it.searchOnlineSubtitles(
                            request.source.title,
                            request.source.year.ifBlank { null },
                            request.source.isSeries,
                            request.source.season,
                            request.source.episode,
                        )
                    }
                }.getOrDefault(emptyList())
            }
            subtitles = provider.await()
            subtitles = (subtitles + online.await()).distinctBy { it.url }
            subtitlesLoading = false
        }
    }

    // endregion

    // region Progress

    private fun startProgressTicker() {
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                saveProgress(completed = false)
            }
        }
    }

    private fun saveProgress(completed: Boolean) {
        val exo = player ?: return
        val positionSec = exo.currentPosition / 1000
        val durationMs = exo.duration
        val durationSec = if (durationMs != C.TIME_UNSET && durationMs > 0) durationMs / 1000 else null
        if (positionSec < 5 && !completed) return
        val done = completed || (durationSec != null && positionSec >= durationSec * 0.92)
        val source = request.source
        val item = MobileHistoryItem(
            provider = source.provider,
            subjectId = source.subjectId,
            title = source.title,
            posterUrl = source.posterUrl,
            isSeries = source.isSeries,
            year = source.year,
            season = source.season,
            episode = source.episode,
            progressSeconds = positionSec.toULong(),
            durationSeconds = durationSec?.toULong(),
            completed = done,
        )
        // Detached from the activity scope so the final save survives finish().
        kotlinx.coroutines.GlobalScope.launch {
            runCatching {
                Bridge.call { it.recordProgress(item, item.progressSeconds, item.durationSeconds, done) }
            }
        }
    }

    // endregion

    override fun onStop() {
        super.onStop()
        if (::request.isInitialized) saveProgress(completed = false)
        player?.pause()
    }

    override fun onDestroy() {
        progressJob?.cancel()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private fun isDecoderError(error: PlaybackException): Boolean = when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> true
            else -> false
        }

        private const val TAG = "MovieBoxPlayer"

        fun mimeTypeFor(url: String): String? {
            val path = Uri.parse(url).path?.lowercase().orEmpty()
            return when {
                path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
                path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }

        fun subtitleMimeType(url: String): String {
            val path = Uri.parse(url).path?.lowercase().orEmpty()
            return when {
                path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                path.endsWith(".ttml") || path.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
        }
    }
}

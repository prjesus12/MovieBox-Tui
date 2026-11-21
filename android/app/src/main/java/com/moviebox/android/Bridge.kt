package com.moviebox.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import uniffi.moviebox_tui.MobileClient
import java.io.File

/**
 * Owns the single [MobileClient] for the process.
 *
 * Every client method blocks on the Rust Tokio runtime, so calls must never run on
 * the main thread. [call] moves them onto a bounded IO pool; the Rust client is
 * thread-safe, so a slow stream lookup does not stall the home feed.
 */
object Bridge {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(4)

    @Volatile
    private var client: MobileClient? = null
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun client(): MobileClient {
        client?.let { return it }
        return synchronized(this) {
            client ?: run {
                val files = appContext.filesDir
                val config = File(files, "config").apply { mkdirs() }
                val data = File(files, "data").apply { mkdirs() }
                val cache = File(appContext.cacheDir, "moviebox").apply { mkdirs() }
                MobileClient(config.absolutePath, cache.absolutePath, data.absolutePath)
                    .also { client = it }
            }
        }
    }

    suspend fun <T> call(block: (MobileClient) -> T): T =
        withContext(dispatcher) { block(client()) }

    /** Provider captions for the release being played (empty without a resource id). */
    suspend fun captions(request: PlaybackRequest): List<uniffi.moviebox_tui.MobileSubtitle> {
        val resourceId = request.release.resourceId ?: return emptyList()
        val source = request.source
        return call {
            it.captions(source.subjectId, resourceId, source.siblingIds, source.season, source.episode)
        }
    }

    fun message(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
}

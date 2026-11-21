package com.moviebox.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.moviebox.android.ui.DetailsScreen
import com.moviebox.android.ui.HomeScreen
import com.moviebox.android.ui.LibraryScreen
import com.moviebox.android.ui.MovieBoxTheme
import com.moviebox.android.ui.Palette
import com.moviebox.android.ui.SearchScreen
import com.moviebox.android.ui.SourcesSheet

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MovieBoxTheme {
                Root(model, onPlay = ::startPlayer)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // The player writes progress while it runs; pick it up on return.
        model.refreshLibrary()
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let(model::handleDeepLink)
    }

    private fun startPlayer(request: PlaybackRequest) {
        model.sourceRequest = null
        PlaybackSession.current = request
        startActivity(Intent(this, PlayerActivity::class.java))
    }
}

private data class RootTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val ROOT_TABS = listOf(
    RootTab("Home", Icons.Default.Home),
    RootTab("Search", Icons.Default.Search),
    RootTab("Library", Icons.Default.VideoLibrary),
)

@Composable
private fun Root(model: AppViewModel, onPlay: (PlaybackRequest) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val message = model.banner
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            model.banner = null
        }
    }

    BackHandler(enabled = model.stack.isNotEmpty() || model.sourceRequest != null) {
        model.back()
    }

    Scaffold(
        containerColor = Palette.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = Palette.surface) {
                ROOT_TABS.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = model.selectedTab == index && model.stack.isEmpty(),
                        onClick = {
                            model.stack.clear()
                            model.selectedTab = index
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // Keeps each tab's saveable state (selected home tab, scroll) while a
            // details screen is stacked on top of it.
            val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            val top = model.stack.lastOrNull()
            when (top) {
                is Route.Details -> androidx.compose.runtime.key(top) { DetailsScreen(model, top) }
                null -> holder.SaveableStateProvider("tab-${model.selectedTab}") {
                    when (model.selectedTab) {
                        0 -> HomeScreen(model)
                        1 -> SearchScreen(model)
                        else -> LibraryScreen(model)
                    }
                }
            }
        }
    }

    model.sourceRequest?.let { request ->
        SourcesSheet(model, request, onPlay)
    }
}

package dev.horizonmanager

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val openFavoriteSaver = Saver<SavedPublication?, Bundle>(
    save = { favorite -> favorite?.let {
        Bundle().apply {
            putString("server", it.server)
            putString("article", it.article)
            putString("run", it.run)
            putString("language", it.language)
            putLong("savedAt", it.savedAt)
            putString("data", it.data)
        }
    } },
    restore = { SavedPublication(it.getString("server").orEmpty(), it.getString("article").orEmpty(),
        it.getString("run").orEmpty(), it.getString("language").orEmpty(), it.getLong("savedAt"),
        requireNotNull(it.getString("data"))) },
)

@Composable
internal fun FavoritesScreen(
    model: ManagerModel,
    panel: Panel,
    header: @Composable () -> Unit,
    focus: (ReadingOverlay) -> Unit,
) {
    val servers = panel.servers
    val favorites by model.favorites.collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by rememberSaveable(stateSaver = openFavoriteSaver) { mutableStateOf<SavedPublication?>(null) }
    val grid = rememberLazyStaggeredGridState()
    val movingBorder = rememberUnreadBorderClock {
        selected == null && grid.layoutInfo.visibleItemsInfo.any { favorites.getOrNull(it.index)?.read == false }
    }
    SideEffect { focus(if (selected != null) ReadingOverlay.Article else ReadingOverlay.None) }
    DisposableEffect(Unit) { onDispose { focus(ReadingOverlay.None) } }
    Box(Modifier.fillMaxSize().editorialInsets()) {
        Column(Modifier.fillMaxSize().then(
            if (selected != null) Modifier.clearAndSetSemantics {} else Modifier
        )) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { header() }
            if (favorites.isEmpty()) {
                Text(ui(R.string.favorites_empty), Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = LocalHorizonPalette.current.muted)
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(154.dp),
                    state = grid,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 5.dp,
                        bottom = LocalNavigationClearance.current),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(favorites, key = { it.snapshot.server + "/" + it.snapshot.article }) { favorite ->
                        val item = favorite.snapshot
                        val article = remember(item) { item.publication() }
                        PublicationTile(article, ArticleFlag(item.server, item.article, true, favorite.read),
                            Modifier, artworkServer = item.server,
                            relay = model.coverRelay(servers.find { it.id == item.server }, item.run, item.article),
                            movingBorder = movingBorder,
                            open = {
                                selected = item
                                if (!favorite.read) model.flag(item, read = true)
                            },
                            save = { model.flag(item, save = false) })
                    }
                }
            }
        }
        selected?.let { item ->
            val favorite = favorites.find { it.snapshot.server == item.server && it.snapshot.article == item.article }
            val snapshot = favorite?.snapshot ?: item
            val article = remember(snapshot) { snapshot.publication() }
            LaunchedEffect(snapshot) {
                if (!article.optBoolean("summary_from_analysis")) model.refreshSavedArticle(snapshot)
            }
            val liveOptions = panel.status.optJSONObject("article_options").takeIf { panel.server?.id == item.server }
            ArticleDialog(
                article, ArticleFlag(item.server, item.article, favorite != null, favorite?.read ?: true),
                onSave = { model.flag(snapshot, save = favorite == null) },
                onRead = { model.flag(item, read = !(favorite?.read ?: true)) },
                artworkServer = item.server,
                displayOptions = liveOptions ?: model.articleDisplayOptions(item.server),
                relay = model.coverRelay(servers.find { it.id == item.server }, item.run, item.article),
                contextContent = {
                    ArticleContext(article) { submit ->
                        model.getArticleContext(item.server, item.run, item.article, item.language, submit)
                    }
                },
            ) { selected = null }
        }
    }
}

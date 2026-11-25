@file:OptIn(ExperimentalMaterial3Api::class)

package app.hybridreading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * История посещений и закладки: поиск по названию и адресу, переход на страницу,
 * снятие/добавление закладки, очистка истории.
 */
@Composable
fun HistoryScreen(
    bookmarksOnly: Boolean = false,
    onBack: () -> Unit,
    onOpen: (url: String, siteId: String) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var bookmarks by remember { mutableStateOf(History.bookmarks(context)) }
    var entries by remember { mutableStateOf(History.list(context)) }
    var onlyBookmarks by remember { mutableStateOf(bookmarksOnly) }

    fun reload() {
        bookmarks = History.bookmarks(context)
        entries = History.list(context, query)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (onlyBookmarks) "Закладки · ${bookmarks.size}" else "История · ${entries.size}",
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    TextButton(onClick = { onlyBookmarks = !onlyBookmarks }) {
                        Text(if (onlyBookmarks) "История" else "Закладки")
                    }
                    if (!onlyBookmarks) {
                        IconButton(onClick = {
                            History.clear(context)
                            reload()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Очистить историю")
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!onlyBookmarks) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        entries = History.list(context, it)
                    },
                    label = { Text("Поиск по истории") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            val bookmarkUrls = bookmarks.map { it.url }.toSet()
            val rows =
                if (onlyBookmarks) {
                    bookmarks.map { History.Entry(it.url, it.title, "", 0L) }
                } else {
                    entries
                }

            if (rows.isEmpty()) {
                Text(
                    if (onlyBookmarks) {
                        "Закладок пока нет: звёздочка в меню на странице."
                    } else {
                        "История пуста."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rows, key = { it.url }) { entry ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(entry.url, entry.siteId) }
                                .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.title.ifBlank { UrlTools.host(entry.url) ?: entry.url },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                UrlTools.host(entry.url) ?: entry.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            History.toggleBookmark(context, entry.url, entry.title)
                            reload()
                        }) {
                            if (entry.url in bookmarkUrls) {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = "В закладках",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Text(
                                    "☆",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (onlyBookmarks) {
                            IconButton(onClick = {
                                History.removeBookmark(context, entry.url)
                                reload()
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Убрать из закладок")
                            }
                        }
                    }
                }
            }
        }
    }
}

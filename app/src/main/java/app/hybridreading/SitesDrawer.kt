@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package app.hybridreading

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Полноэкранная страница «Сайты»: список, добавление, правка (долгое нажатие),
 * блокировка рекламы у каждого сайта отдельно, очистка кэша глав и переход на главную.
 */
@Composable
fun SitesDrawerContent(
    sites: List<Site>,
    activeId: String?,
    onSelect: (Site) -> Unit,
    onAdd: (url: String, title: String?) -> String?,
    onUpdate: (site: Site, url: String, title: String?) -> String?,
    onDelete: (Site, Boolean) -> Unit,
    onClearSiteCache: (Site, Boolean) -> Unit,
    onMeasureSiteCache: ((Site, (Long?) -> Unit) -> Unit)? = null,
    onReload: () -> Unit,
    onHome: () -> Unit,
    onClose: () -> Unit,
) {
    var editor by remember { mutableStateOf<Site?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Site?>(null) }
    var pendingCache by remember { mutableStateOf<Pair<Site, Boolean>?>(null) }
    val context = LocalContext.current
    // Пересчитывать счётчик скрытых блоков после «Вернуть».
    var hiddenVersion by remember { mutableStateOf(0) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Сайты") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                    }
                },
                actions = {
                    IconButton(onClick = onReload) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить страницу")
                    }
                    IconButton(onClick = {
                        editor = null
                        editorOpen = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "Добавить сайт")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Text(
                    "Кэш глав чистится у каждого сайта отдельно — в правке сайта.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("home") {
                HomeEntryCard(onClick = onHome)
            }
            items(sites, key = { it.id }) { site ->
                SiteCard(
                    site = site,
                    hiddenCount = remember(site.id, hiddenVersion) {
                        HiddenBlocks.count(context, site.id)
                    },
                    active = site.id == activeId,
                    onSelect = { onSelect(site) },
                    onEdit = {
                        editor = site
                        editorOpen = true
                    },
                    onDelete = { pendingDelete = site },
                )
            }
            item("hint") {
                Text(
                    "Долгое нажатие на сайт — правка названия, ссылки и скрытых блоков.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (editorOpen) {
        SiteEditorDialog(
            initial = editor,
            onDismiss = { editorOpen = false },
            onSave = { url, title ->
                val site = editor
                if (site == null) onAdd(url, title) else onUpdate(site, url, title)
            },
            onClearCache = editor?.let { site ->
                {
                    editorOpen = false
                    pendingCache = site to false
                }
            },
            onClearAll = editor?.let { site ->
                {
                    editorOpen = false
                    pendingCache = site to true
                }
            },
            onMeasure = editor?.let { site ->
                { callback: (Long?) -> Unit -> onMeasureSiteCache?.invoke(site, callback) }
            },
            hiddenCount = editor?.let {
                remember(it.id, hiddenVersion) { HiddenBlocks.count(context, it.id) }
            } ?: 0,
            onClearHidden = editor?.let { site ->
                {
                    HiddenBlocks.clear(context, site.id)
                    hiddenVersion++
                    editorOpen = false
                }
            },
        )
    }

    pendingDelete?.let { site ->
        var wipeData by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить сайт?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("«${site.title}» исчезнет из списка. Ссылку можно добавить снова в любой момент.")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(checked = wipeData, onCheckedChange = { wipeData = it })
                        Column(Modifier.weight(1f)) {
                            Text("Удалить кэш и данные", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (wipeData) {
                                    "Кэш глав, хранилище, куки и общий кэш страниц пропадут с устройства."
                                } else {
                                    "Данные останутся на устройстве и продолжат занимать место."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val wipe = wipeData
                    pendingDelete = null
                    onDelete(site, wipe)
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            },
        )
    }

    pendingCache?.let { (site, full) ->
        AlertDialog(
            onDismissRequest = { pendingCache = null },
            title = { Text(if (full) "Полная очистка сайта?" else "Очистить кэш глав?") },
            text = {
                Text(
                    if (full) {
                        "У «${site.title}» удалятся кэш глав, localStorage, IndexedDB и куки. " +
                            "Вы выйдете из аккаунта на этом сайте и войдёте заново. " +
                            "Общий кэш страниц приложения тоже очистится — другие сайты загрузятся заново."
                    } else {
                        "У «${site.title}» удалятся скачанные глав и картинки (кэш). " +
                            "Куки и вход сохранятся — заново входить не придётся."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingCache = null
                    onClearSiteCache(site, full)
                }) { Text(if (full) "Удалить всё" else "Очистить кэш") }
            },
            dismissButton = {
                TextButton(onClick = { pendingCache = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun HomeEntryCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Home,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Главная страница", style = MaterialTheme.typography.titleMedium)
                Text(
                    "начальный экран: добавление сайтов и список",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SiteCard(
    site: Site,
    hiddenCount: Int,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onSelect, onLongClick = onEdit),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = site.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = UrlTools.host(site.url) ?: site.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hiddenCount > 0) {
                    Text(
                        text = "скрыто блоков: $hiddenCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (active) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Активный сайт",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp).size(20.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить сайт")
            }
        }
    }
}

/**
 * Диалог добавления и правки сайта. [onSave] возвращает текст ошибки или null, если всё сохранилось.
 * [onClearCache] есть только при правке: чистит кэш глав этого сайта.
 */
@Composable
internal fun SiteEditorDialog(
    initial: Site?,
    onDismiss: () -> Unit,
    onSave: (url: String, title: String?) -> String?,
    onClearCache: (() -> Unit)? = null,
    onClearAll: (() -> Unit)? = null,
    onMeasure: (((Long?) -> Unit) -> Unit)? = null,
    hiddenCount: Int = 0,
    onClearHidden: (() -> Unit)? = null,
) {
    var url by rememberSaveable { mutableStateOf(initial?.url.orEmpty()) }
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var measuring by remember { mutableStateOf(onMeasure != null) }

    // Размер кэша узнаём при открытии: для этого сайт один раз загружается в скрытом WebView.
    LaunchedEffect(Unit) {
        if (onMeasure == null) return@LaunchedEffect
        onMeasure { bytes ->
            cacheBytes = bytes
            measuring = false
        }
    }

    val cacheSummary = when {
        measuring -> "Считаем занятое место…"
        cacheBytes == null -> "Не удалось измерить: сайт не открылся"
        else -> "Занято ${formatBytes(cacheBytes!!)}: кэш, офлайн-главы и картинки."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новый сайт" else "Правка сайта") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text("Ссылка") },
                    placeholder = { Text("*****.me") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(
                            if (error != null) error!!
                            else "Можно без https:// — например: *****.me или *****.com/ranobe/123",
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название") },
                    placeholder = { Text("Необязательно") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onClearHidden != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Скрытые блоки", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (hiddenCount > 0) {
                                    "Спрятано на этом сайте: $hiddenCount"
                                } else {
                                    "Пока ничего не скрыто. В меню на странице есть «Удалить блок»."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (hiddenCount > 0) {
                            TextButton(onClick = onClearHidden) { Text("Вернуть") }
                        }
                    }
                }
                if (onClearCache != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Кэш глав сайта", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                cacheSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Очистка не сбрасывает вход на сайт.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            onClearCache()
                            onDismiss()
                        }) { Text("Очистить") }
                    }
                }
                if (onClearAll != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Полная очистка сайта", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Кэш, хранилище и куки: сайт забудет вход, придётся войти заново.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        TextButton(onClick = {
                            onClearAll()
                            onDismiss()
                        }) { Text("Стереть") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = onSave(url.trim(), title.trim().ifBlank { null })
                if (problem == null) onDismiss() else error = problem
            }) { Text(if (initial == null) "Добавить" else "Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

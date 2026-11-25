@file:OptIn(ExperimentalMaterial3Api::class)

package app.hybridreading

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Домашняя страница: продолжить чтение, добавление сайта, профиль (экспорт/импорт),
 * проверка обновлений и список сайтов.
 */
@Composable
fun HomeScreen(
    sites: List<Site>,
    activeId: String?,
    recents: List<ReadingState.Recent>,
    versionName: String,
    onOpenSite: (Site) -> Unit,
    onContinue: (ReadingState.Recent) -> Unit,
    onAddSite: (url: String, title: String?, limitMb: Int, zoom: Int, dark: Boolean) -> String?,
    onHistory: () -> Unit,
    onMenu: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Счётчики скрытых блоков считаем один раз на список, а не в каждой карточке при рекомпозиции.
    val hiddenCounts =
        remember(sites, UiState.resumeTick) {
            sites.associate { it.id to HiddenBlocks.count(context, it.id) }
        }
    var addOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<UpdateChecker.Check?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var pendingRelease by remember { mutableStateOf<UpdateChecker.Release?>(null) }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(ProfileBackup.buildJson(context).toByteArray())
                } ?: error("Не удалось открыть файл")
            }.onSuccess { message = "Профиль сохранён" }
                .onFailure { message = "Экспорт не удался: ${it.message}" }
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                val text =
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("Не удалось прочитать файл")
                ProfileBackup.restore(context, text)
            }.onSuccess { message = "Импорт: $it. Входы на сайтах нужно выполнить заново." }
                .onFailure { message = "Импорт не удался: ${it.message}" }
        }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Hybrid Reading", fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = onMenu) {
                        Icon(Icons.Filled.Menu, contentDescription = null)
                        Text("Сайты", Modifier.padding(start = 4.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (recents.isNotEmpty()) {
                val first = recents.first()
                item("continue") {
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    "ВЫ ОСТАНОВИЛИСЬ ЗДЕСЬ",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                            Text(
                                first.title.ifBlank { UrlTools.host(first.url) ?: first.url },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                (UrlTools.host(first.url) ?: first.url) + " · " + agoLabel(first.time),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            )
                            FilledTonalButton(
                                onClick = { onContinue(first) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Text("Продолжить чтение", Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
                if (recents.size > 1) {
                    item("recent-title") {
                        Text(
                            "Ранее открытые",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(recents.drop(1), key = { "recent-${it.url}" }) { recent ->
                        Card(
                            onClick = { onContinue(recent) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        recent.title.ifBlank { UrlTools.host(recent.url) ?: recent.url },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        (UrlTools.host(recent.url) ?: recent.url) + " · " + agoLabel(recent.time),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    "Открыть",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            item("add") {
                FilledTonalButton(onClick = { addOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Добавить сайт", Modifier.padding(start = 8.dp))
                }
            }

            item("profile") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Профиль", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Экспорт сохраняет сайты, лимиты кэша и скрытые блоки в файл; импорт возвращает их " +
                                "после переустановки. Куки и входы в файл не попадают.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                exportLauncher.launch(ProfileBackup.suggestedFileName())
                            }) { Text("Экспорт") }
                            TextButton(onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            }) { Text("Импорт") }
                            TextButton(onClick = onHistory) { Text("История") }
                        }
                    }
                }
            }

            item("update") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Обновления",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Установлена версия $versionName. Обновление скачивается с GitHub и ставится поверх.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            enabled = busy == null,
                            onClick = {
                                busy = "Проверяем…"
                                scope.launch {
                                    val result =
                                        withContext(Dispatchers.IO) {
                                            runCatching { UpdateChecker.check() }.getOrElse {
                                                UpdateChecker.Check.Failed(it.message ?: "Ошибка сети")
                                            }
                                        }
                                    busy = null
                                    update = result
                                }
                            },
                        ) { Text(busy ?: "Проверить обновления") }
                    }
                }
            }

            item("sites-title") {
                Text(
                    "Ваши сайты",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            items(sites, key = { it.id }) { site ->
                Card(
                    onClick = { onOpenSite(site) },
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                if (site.id == activeId) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                        ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                site.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                UrlTools.host(site.url) ?: site.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val hidden = hiddenCounts[site.id] ?: 0
                        if (hidden > 0) {
                            Text(
                                "скрыто блоков: $hidden",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            item("hint") {
                Text(
                    "Долгое нажатие на сайт в меню — правка названия, ссылки, лимита кэша и скрытых блоков.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    if (addOpen) {
        SiteEditorDialog(
            initial = null,
            onDismiss = { addOpen = false },
            onSave = { url, title, limit, zoom, dark -> onAddSite(url, title, limit, zoom, dark) },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Готово") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("Закрыть") } },
        )
    }

    when (val state = update) {
        is UpdateChecker.Check.Newer -> {
            pendingRelease = state.release
            AlertDialog(
                onDismissRequest = { update = null },
                title = { Text("Доступна версия ${state.release.version}") },
                text = {
                    Text(
                        state.release.notes.ifBlank { "Новая версия на GitHub." } + "\n\nСкачать и установить?",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val release = pendingRelease
                        update = null
                        if (release == null) return@TextButton
                        busy = "Скачиваем…"
                        scope.launch {
                            val file =
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        UpdateChecker.download(context, release) { percent ->
                                            busy = "Скачиваем… $percent%"
                                        }
                                    }
                                }
                            busy = null
                            file.onSuccess { apk ->
                                runCatching { UpdateChecker.install(context, apk) }
                                    .onFailure { message = "Не удалось открыть установщик: ${it.message}" }
                            }.onFailure { message = "Загрузка не удалась: ${it.message}" }
                        }
                    }) { Text("Скачать") }
                },
                dismissButton = { TextButton(onClick = { update = null }) { Text("Позже") } },
            )
        }

        is UpdateChecker.Check.Actual ->
            AlertDialog(
                onDismissRequest = { update = null },
                title = { Text("Обновлений нет") },
                text = { Text("Установлена последняя версия ${state.version}.") },
                confirmButton = { TextButton(onClick = { update = null }) { Text("Закрыть") } },
            )

        is UpdateChecker.Check.Failed ->
            AlertDialog(
                onDismissRequest = { update = null },
                title = { Text("Не удалось проверить") },
                text = { Text(state.message) },
                confirmButton = { TextButton(onClick = { update = null }) { Text("Закрыть") } },
            )

        null -> Unit
    }
}

/** «только что», «12 мин назад», «сегодня», «вчера», «3 дня назад». */
private fun agoLabel(time: Long): String {
    val now = System.currentTimeMillis()
    val minutes = ((now - time) / 60_000).coerceAtLeast(0)
    val days = ((now - time) / 86_400_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин назад"
        days < 1 -> "сегодня"
        days == 1L -> "вчера"
        else -> "$days дн назад"
    }
}

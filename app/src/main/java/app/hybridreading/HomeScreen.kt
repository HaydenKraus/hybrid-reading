@file:OptIn(ExperimentalMaterial3Api::class)

package app.hybridreading

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Домашняя страница: пояснение про входы, добавление сайта и список сайтов внизу.
 */
@Composable
fun HomeScreen(
    sites: List<Site>,
    activeId: String?,
    onOpenSite: (Site) -> Unit,
    onAddSite: (url: String, title: String?) -> String?,
    onMenu: () -> Unit,
) {
    var addOpen by remember { mutableStateOf(false) }

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
            item("hello") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Читайте сайты без рекламы", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Добавьте сайт, войдите в аккаунт на самом сайте и читайте с сохранением места.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item("accounts") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Про входы на сайтах", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "У каждого сайта своя сессия: вход на одном сайте не открывает доступ к другому, " +
                                "и в приложении ничего не объединяется. Входите на самом сайте — его форма " +
                                "входа, пароль или кнопка «Войти через VK/Telegram» работают прямо в приложении. " +
                                "Google во встроенном браузере не пускает, поэтому сайты с входом только через " +
                                "Google открывайте во внешнем браузере: там вход работает, но сессия останется " +
                                "в браузере и в приложение не перенесётся.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item("add") {
                FilledTonalButton(onClick = { addOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Добавить сайт", Modifier.padding(start = 8.dp))
                }
            }

            item("list-title") {
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
                    colors = CardDefaults.cardColors(
                        containerColor = if (site.id == activeId) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surface,
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
                        val hidden = HiddenBlocks.count(LocalContext.current, site.id)
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
                    "Долгое нажатие на сайт в меню — правка названия, ссылки и скрытых блоков.",
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
            onSave = { url, title -> onAddSite(url, title) },
        )
    }
}

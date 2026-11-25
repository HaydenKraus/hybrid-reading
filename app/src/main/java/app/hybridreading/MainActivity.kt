package app.hybridreading

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    /** Ссылка из внешнего intent: WebView создаётся позже, поэтому адрес запоминаем. */
    private var pendingUrl by mutableStateOf<String?>(null)

    private val ui = Handler(Looper.getMainLooper())
    private val pauseTimers = Runnable { WebHolder.pauseTimers() }

    /** Экран погас — гасим WebView целиком; включился — возвращаем к жизни. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> WebHolder.onScreenOff()
                Intent.ACTION_SCREEN_ON -> WebHolder.onScreenOn()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingUrl = safeWebUrl(intent?.dataString)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        enableEdgeToEdge()
        setContent {
            InkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().systemBarsPadding()) {
                        val context = LocalContext.current
                        // Утром открываем ту страницу, где остановились, а не корень сайта.
                        val startUrl = remember {
                            SitesStore.active.value?.let { ReadingState.lastUrl(context, it.url) }
                        }
                        val firstRun = remember { SitesStore.sites.value.isEmpty() }
                        AppRoot(
                            pendingUrl = pendingUrl,
                            startUrl = startUrl,
                            startAtHome = firstRun || remember { ReadingState.wasHome(context) },
                            onPendingHandled = { pendingUrl = null },
                            onExit = { finish() },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ui.removeCallbacks(pauseTimers)
        WebHolder.resumeTimers()
        WebHolder.webView?.onResume()
    }

    // Куки сбрасываем на диск в фоне: синхронный flush() в onStop — это дисковый I/O в главном потоке.
    override fun onStop() {
        // Запоминаем место чтения: если систему ночью всё-таки выгрузит, откроем ту же страницу.
        WebHolder.webView?.let { ReadingState.remember(it, this) }
        // И то, где пользователь был: на главной странице или на сайте.
        ReadingState.saveHome(this, UiState.atHome)
        Thread { runCatching { CookieManager.getInstance().flush() } }.start()
        WebHolder.webView?.onPause()
        // Короткие переходы (OAuth-попап, выбор файла) не рвём: таймеры гасим через 10 секунд в фоне.
        ui.postDelayed(pauseTimers, 10_000)
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacks(pauseTimers)
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        safeWebUrl(intent.dataString)?.let { WebHolder.webView?.loadUrl(it) }
    }

    /**
     * Ссылку из внешнего intent отдаём WebView только если это http/https.
     * Activity экспортирована, а loadUrl("javascript:...") выполнил бы чужой код в контексте сайта.
     */
    private fun safeWebUrl(raw: String?): String? = raw?.let { UrlTools.normalize(it) }
}

/** Что показывать при следующем запуске: главную или сайт. Живёт вне композиции. */
object UiState {
    @Volatile
    var atHome: Boolean = false
}

@Composable
private fun AppRoot(
    pendingUrl: String?,
    startUrl: String?,
    startAtHome: Boolean,
    onPendingHandled: () -> Unit,
    onExit: () -> Unit,
) {
    val site by SitesStore.active.collectAsState()
    val sites by SitesStore.sites.collectAsState()
    val context = LocalContext.current

    var drawer by remember { mutableStateOf(false) }
    var home by remember { mutableStateOf(startAtHome || site == null) }
    var reloadToken by remember { mutableStateOf(0) }

    // Сайтов нет — показывать нечего, кроме домашней страницы.
    LaunchedEffect(site) { if (site == null) home = true }

    // Какое состояние показывать при следующем запуске (главная или сайт).
    LaunchedEffect(home) { UiState.atHome = home }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    BackHandler(enabled = true) {
        when {
            drawer -> drawer = false
            home -> home = false
            WebHolder.goBack() -> Unit
            else -> onExit()
        }
    }

    // Внешняя ссылка: грузим после того, как WebView создан.
    LaunchedEffect(pendingUrl) {
        val target = pendingUrl ?: return@LaunchedEffect
        withFrameNanos { }
        WebHolder.webView?.loadUrl(target)
        onPendingHandled()
    }

    Box(Modifier.fillMaxSize()) {
        site?.let { current ->
            WebScreen(
                site = current,
                active = !drawer && !home,
                reloadToken = reloadToken,
                startUrl = startUrl,
                onOpenSites = { drawer = true },
                onHome = { home = true },
                onPageTitle = { },
            )
        }

        if (home || site == null) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                HomeScreen(
                    sites = sites,
                    activeId = site?.id,
                    onOpenSite = {
                        SitesStore.select(it.id)
                        home = false
                    },
                    onAddSite = { url, title ->
                        val error = SitesStore.add(url, title).exceptionOrNull()?.message
                        // Добавили — сразу открываем сайт, чтобы не искать его в списке.
                        if (error == null) home = false
                        error
                    },
                    onMenu = { drawer = true },
                )
            }
        }

        if (drawer) {
            // Меню — обычный Compose-слой поверх WebView, который на время меню скрыт (active=false).
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                SitesDrawerContent(
                    sites = sites,
                    activeId = site?.id,
                    onSelect = {
                        SitesStore.select(it.id)
                        drawer = false
                    },
                    onAdd = { url, title ->
                        val error = SitesStore.add(url, title).exceptionOrNull()?.message
                        // Добавили — сразу открываем сайт: меню закрываем, чтобы не искать его в списке.
                        if (error == null) {
                            drawer = false
                            home = false
                        }
                        error
                    },
                    onUpdate = { target, url, title ->
                        SitesStore.update(target.id, url, title).exceptionOrNull()?.message
                    },
                    onDelete = { target, wipeData ->
                        SitesStore.remove(target.id)
                        if (wipeData) {
                            SiteCache.clearAll(context, target.url) { count ->
                                val what = if (count >= 0) "Сайт удалён, данных удалено: $count" else "Сайт удалён"
                                toast(what)
                            }
                        } else {
                            toast("Сайт удалён, данные оставлены")
                        }
                    },
                    onMeasureSiteCache = { target, callback ->
                        SiteCache.measure(context, target.url, callback)
                    },
                    onClearSiteCache = { target, full ->
                        val done: (Int) -> Unit = { count ->
                            val what = if (full) "Сайт очищен" else "Кэш глав очищен"
                            toast(if (count >= 0) "$what, объектов: $count" else "$what")
                            if (full) WebHolder.reload()
                        }
                        if (full) {
                            SiteCache.clearAll(context, target.url, done)
                        } else {
                            SiteCache.clearChapters(context, target.url, done)
                        }
                    },
                    onReload = {
                        drawer = false
                        reloadToken++
                    },
                    onHome = {
                        drawer = false
                        home = true
                    },
                    onClose = { drawer = false },
                )
            }
        }
    }
}

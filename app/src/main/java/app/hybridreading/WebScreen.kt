package app.hybridreading

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.widget.Toast
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.net.URI

/** Один WebView на всё приложение: из Activity к нему обращаются back-обработчик и intent'ы. */
object WebHolder {
    var webView: WebView? = null

    private var pausedForScreen = false

    /** true, если WebView ушёл на предыдущую страницу. */
    fun goBack(): Boolean {
        val view = webView ?: return false
        if (!view.canGoBack()) return false
        view.goBack()
        return true
    }

    fun reload() {
        webView?.reload()
    }

    val currentUrl: String? get() = webView?.url

    val pageTitle: String? get() = webView?.title

    /**
     * Экран погас: останавливаем и рендеринг, и таймеры JS, чтобы страница не будила процесс
     * ночью. Без pauseTimers таймеры страницы (setTimeout, анимации, опросы) продолжают жечь батарею.
     */
    fun onScreenOff() {
        val view = webView ?: return
        pausedForScreen = true
        runCatching {
            view.onPause()
            view.pauseTimers()
        }
    }

    fun onScreenOn() {
        val view = webView ?: return
        if (!pausedForScreen) return
        pausedForScreen = false
        runCatching {
            view.resumeTimers()
            view.onResume()
        }
    }

    /** Пауза таймеров в фоне (вызывается с задержкой, чтобы не рвать короткие переходы и OAuth). */
    fun pauseTimers() {
        runCatching { webView?.pauseTimers() }
    }

    fun resumeTimers() {
        runCatching { webView?.resumeTimers() }
    }
}

/** «Чистый» Chrome без токена wv: с ним меньше отказов у Google и части сайтов. */
internal const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Mobile Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(
    site: Site,
    active: Boolean,
    reloadToken: Int,
    startUrl: String?,
    onOpenSites: () -> Unit,
    onHome: () -> Unit,
    onPageTitle: (String) -> Unit,
) {
    val context = LocalContext.current
    val engine = remember { WebEngineState(context) }

    // Камера/микрофон: страница просит доступ, спрашиваем разрешение у системы и отдаём ответ сайту.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val request = engine.pendingPermission
        engine.pendingPermission = null
        if (request == null) return@rememberLauncherForActivityResult
        if (allGranted(context, request)) grantSupported(request) else request.deny()
    }

    // Номер поколения WebView: ++ после краша рендерера — композиция соберёт новый.
    val generation = engine.generation

    // WebView создаётся один раз: пересоздание рвёт историю, куки в памяти и OAuth-цепочки.
    val webView = remember(generation) {
        engine.createMain().also { WebHolder.webView = it }
    }

    // Кнопка меню — обычный View поверх WebView в том же контейнере: Compose-оверлей
    // поверх AndroidView не рисуется, а Popup на Android 16 не получает касания.
    val openSites by rememberUpdatedState(onOpenSites)
    val goHome by rememberUpdatedState(onHome)

    // Панель режима выбора блока: подсказка и выход. Обычный View — Compose поверх AndroidView не рисуется.
    val pickerBar = remember(generation) {
        android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xF22E2A54.toInt())
            setPadding(context.dp(16), context.dp(8), context.dp(4), context.dp(8))
            addView(
                android.widget.TextView(context).apply {
                    text = "Выберите блок, который нужно скрыть"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 13f
                },
                android.widget.LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
            addView(
                android.widget.Button(context).apply {
                    text = "Отмена"
                    setOnClickListener { engine.stopPicker() }
                },
                android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            visibility = View.GONE
        }
    }

    val container = remember(generation) {
        FrameLayout(context).apply {
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            val menu = MenuController(context, this).apply {
                bind(
                    onHome = { goHome() },
                    onSites = { openSites() },
                    onHide = { engine.startPicker() },
                )
            }
            addView(
                pickerBar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = context.dp(8) },
            )
            // Позицию кнопки восстанавливаем, когда контейнер уже измерился.
            post { menu.restore() }
        }
    }

    SideEffect {
        // Свежие значения из композиции — обычные поля, чтобы не пересобирать WebView.
        engine.onPicking = { picking ->
            pickerBar.visibility = if (picking) View.VISIBLE else View.GONE
        }
        engine.site = site
        engine.onTitle = onPageTitle
        engine.permissionHandler = { request ->
            val needed = requiredPermissions(request)
            when {
                // Камеру/микрофон без https не отдаём.
                request.origin?.toString()?.startsWith("https://") != true -> request.deny()
                // Второй запрос не должен затирать первый: незакрытый getUserMedia завис бы навсегда.
                engine.pendingPermission != null -> request.deny()
                needed.all { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED } ->
                    grantSupported(request)
                else -> {
                    engine.pendingPermission = request
                    permissionLauncher.launch(needed.toTypedArray())
                }
            }
        }
        webView.visibility = if (active) View.VISIBLE else View.INVISIBLE
    }

    // Приоритет рендерера меняем только при смене видимости, а не на каждой рекомпозиции.
    LaunchedEffect(active, generation) {
        webView.setRendererPriorityPolicy(
            if (active) WebView.RENDERER_PRIORITY_IMPORTANT else WebView.RENDERER_PRIORITY_BOUND,
            !active,
        )
    }

    // Смена сайта в меню: закрываем чужой попап и грузим адрес, только если он действительно другой.
    // На самом первом запуске открываем не корень сайта, а страницу, где пользователь остановился.
    var firstLoad by remember { mutableStateOf(true) }
    LaunchedEffect(site.id, generation) {
        engine.closePopup()
        val target = if (firstLoad) (startUrl ?: site.url) else site.url
        firstLoad = false
        if (!sameUrl(webView.url, target)) {
            val scroll = if (target == startUrl) ReadingState.scrollFor(context, target) else -1f
            if (scroll > 0f) engine.restoreScroll = target to scroll
            webView.loadUrl(target)
        }
    }

    // Токен перезагрузки: ++ в Activity -> reload текущей страницы.
    var seenToken by remember { mutableStateOf(reloadToken) }
    LaunchedEffect(reloadToken) {
        if (reloadToken != seenToken) {
            seenToken = reloadToken
            webView.reload()
        }
    }

    // Выбор файла: WebChromeClient положил запрос в состояние, здесь показываем системный пикер.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = engine.takeChooser() ?: return@rememberLauncherForActivityResult
        request.callback.onReceiveValue(collectUris(result))
    }

    LaunchedEffect(engine.chooser) {
        val request = engine.chooser ?: return@LaunchedEffect
        val started = runCatching { filePicker.launch(request.intent()) }.isSuccess
        if (!started) {
            // Нет приложения для выбора файлов — обязательно отвечаем null, иначе input зависнет.
            engine.takeChooser()?.callback?.onReceiveValue(null)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (WebHolder.webView === webView) WebHolder.webView = null
            engine.closePopup()
            engine.cancelChooser()
            val parent = webView.parent as? ViewGroup
            parent?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // key(generation): после краша рендерера container новый, и AndroidView должен подхватить именно его.
        key(generation) {
            AndroidView(factory = { container }, modifier = Modifier.fillMaxSize())
        }

        // Compose не рисует поверх AndroidView, поэтому индикатор и кнопку держим в Popup-окнах.
        if (active && engine.loading && engine.error == null) {
            val screenWidth = LocalConfiguration.current.screenWidthDp.dp
            Popup(
                alignment = Alignment.TopStart,
                properties = PopupProperties(focusable = false, clippingEnabled = false),
            ) {
                LinearProgressIndicator(
                    progress = { engine.progress / 100f },
                    modifier = Modifier.width(screenWidth).height(3.dp),
                    trackColor = Color.Transparent,
                )
            }
        }

        // Ошибка главного фрейма или сертификата: без диалога пользователь видит пустой экран.
        val errorText = engine.error
        if (errorText != null) {
            AlertDialog(
                onDismissRequest = { engine.error = null },
                title = { Text("Не удалось открыть страницу") },
                text = { Text(errorText) },
                confirmButton = {
                    TextButton(onClick = {
                        engine.error = null
                        webView.reload()
                    }) { Text("Повторить") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        engine.error = null
                        webView.loadUrl(site.url)
                    }) { Text("На главную сайта") }
                },
            )
        }

        // Попап window.open(): свой WebView в диалоге на весь экран.
        val popupView = engine.popup
        if (popupView != null) {
            Dialog(
                onDismissRequest = { engine.closePopup() },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().systemBarsPadding()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = engine.popupTitle ?: engine.site?.title ?: "Окно сайта",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { engine.closePopup() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Закрыть окно")
                            }
                        }
                        // key(popupView): попап может смениться (вложенный window.open) — factory вызывается один раз
                        // и без ключа в диалоге остался бы старый, уже уничтоженный WebView.
                        key(popupView) {
                            AndroidView(factory = { popupView }, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }

        // Полноэкранное видео: view из onShowCustomView рисуем поверх всего.
        val customView = engine.fullscreen
        if (customView != null) {
            Dialog(
                onDismissRequest = { engine.hideCustomView() },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { customView }, modifier = Modifier.fillMaxSize())
                }
            }
        }

        val jsDialog = engine.jsDialog
        if (jsDialog != null) {
            JsDialogUi(dialog = jsDialog, onDone = { engine.finishDialog() })
        }
    }
}

/** Состояние и клиенты WebView. Обычные поля вместо Compose-состояния — клиенты живут вне композиции. */
@SuppressLint("SetJavaScriptEnabled")

@Composable
private fun JsDialogUi(dialog: JsDialogRequest, onDone: () -> Unit) {
    var text by remember(dialog) { mutableStateOf(dialog.defaultValue.orEmpty()) }

    val finish: (Boolean) -> Unit = { ok ->
        if (ok) {
            if (dialog.kind == JsDialogRequest.Kind.PROMPT) dialog.accept(text) else dialog.accept()
        } else {
            dialog.dismiss()
        }
        onDone()
    }

    AlertDialog(
        onDismissRequest = { finish(false) },
        title = {
            Text(
                when (dialog.kind) {
                    JsDialogRequest.Kind.ALERT -> "Сообщение сайта"
                    JsDialogRequest.Kind.CONFIRM -> "Подтвердите действие"
                    JsDialogRequest.Kind.PROMPT -> "Сайт спрашивает"
                }
            )
        },
        text = {
            Column {
                Text(dialog.message)
                if (dialog.kind == JsDialogRequest.Kind.PROMPT) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { finish(true) }) { Text("OK") } },
        dismissButton = {
            if (dialog.kind != JsDialogRequest.Kind.ALERT) {
                TextButton(onClick = { finish(false) }) { Text("Отмена") }
            }
        },
    )
}

/** Круглая кнопка меню в стиле темы: обычный View внутри контейнера WebView, поэтому касания доходят всегда. */

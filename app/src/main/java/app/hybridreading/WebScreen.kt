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
private const val USER_AGENT =
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
private class WebEngineState(private val context: Context) {

    @Volatile
    var site: Site? = null

    var onTitle: (String) -> Unit = {}

    /** Режим выбора включён; переживает переходы по страницам. */
    var picking: Boolean = false

    var onPicking: (Boolean) -> Unit = {}

    var loading by mutableStateOf(false)
        private set

    var progress by mutableStateOf(0)
        private set

    var error by mutableStateOf<String?>(null)

    var popup by mutableStateOf<WebView?>(null)
        private set

    /** Заголовок открытого окна: попап может быть чужого сайта, поэтому своё поле. */
    var popupTitle by mutableStateOf<String?>(null)
        private set

    var chooser by mutableStateOf<FileChooserRequest?>(null)
        private set

    var fullscreen by mutableStateOf<View?>(null)
        private set

    var jsDialog by mutableStateOf<JsDialogRequest?>(null)
        private set

    /** Номер поколения основного WebView: ++ после краша рендерера, композиция создаёт новый. */
    var generation by mutableStateOf(0)
        private set

    /** Попапы, которые уже уходили на чужой хост: только их возвращение переносим в основное окно. */
    private val popupLeftSite = mutableSetOf<WebView>()

    /** Обработчик запроса камеры/микрофона ставит композиция: ей доступен launcher разрешений. */
    var permissionHandler: (PermissionRequest) -> Unit = { it.deny() }

    /** Куда вернуть прокрутку после перезапуска: адрес и позиция. */
    var restoreScroll: Pair<String, Float>? = null

    private var scrollRestored = false

    /** Прокрутку восстанавливаем один раз, когда нужная страница догрузилась. */
    fun takeScrollFor(url: String): Float? {
        if (scrollRestored) return null
        val (target, y) = restoreScroll ?: return null
        if (!sameUrl(url, target)) return null
        scrollRestored = true
        return y
    }

    var pendingPermission: PermissionRequest? = null

    private var main: WebView? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val jsQueue = ArrayDeque<JsDialogRequest>()
    private val handler = Handler(Looper.getMainLooper())

    fun crashRenderer(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        handler.post { runCatching { view.stopLoading(); view.destroy() } }
        if (view === main) {
            error = "Встроенный браузер перезапустился: страница упала (не хватило памяти)."
            generation++
        }
    }

    /** Приём выбранного блока из JS: селектор сохраняем и сразу прячем. */
    private inner class PickerBridge {
        @JavascriptInterface
        fun onPicked(selector: String, label: String) {
            val target = site ?: return
            Handler(Looper.getMainLooper()).post {
                if (selector.isBlank()) {
                    Toast.makeText(context, "Блок не выбран", Toast.LENGTH_SHORT).show()
                    return@post
                }
                confirmHide(target.id, selector, label)
            }
        }
    }

    /** Подтверждение: режим выбора при этом не заканчивается. */
    private fun confirmHide(siteId: String, selector: String, label: String) {
        val view = main ?: return
        android.app.AlertDialog.Builder(context)
            .setTitle("Скрыть этот блок?")
            .setMessage(label.ifBlank { selector })
            .setPositiveButton("Скрыть") { _, _ ->
                if (HiddenBlocks.add(context, siteId, selector)) {
                    applyHiddenBlocks(view)
                    view.evaluateJavascript(PICK_CLEAR_JS, null)
                    Toast.makeText(context, "Блок скрыт на этом сайте", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Этот блок уже скрыт", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена") { _, _ ->
                view.evaluateJavascript(PICK_CLEAR_JS, null)
            }
            .show()
    }

    /** Прячет сохранённые блоки сайта на загруженной странице. */
    fun applyHiddenBlocks(view: WebView?) {
        val target = site ?: return
        val css = HiddenBlocks.css(context, target.id)
        val js = if (css.isEmpty()) hiddenCssJs("") else hiddenCssJs(css)
        runCatching { view?.evaluateJavascript(js, null) }
    }

    /** Режим выбора блока: касание подсвечивает элемент и спрашивает подтверждение. */
    fun startPicker() {
        val view = main ?: return
        picking = true
        onPicking(true)
        runCatching { view.evaluateJavascript(PICK_JS, null) }
        Toast.makeText(context, "Коснитесь блока, который нужно скрыть", Toast.LENGTH_SHORT).show()
    }

    fun stopPicker() {
        picking = false
        onPicking(false)
        runCatching { main?.evaluateJavascript(STOP_PICK_JS, null) }
    }

    fun createMain(): WebView {
        val view = WebView(context)
        applySettings(view)
        view.addJavascriptInterface(PickerBridge(), "InkPicker")
        view.webViewClient = buildClient()
        view.webChromeClient = buildChrome()
        main = view
        return view
    }

    /** Общие настройки для основного WebView и для попапов. */
    fun applySettings(view: WebView) {
        view.setBackgroundColor(0xFF101014.toInt())
        // Плавное пролистывание: страница растеризуется заранее, бары не перерисовываются на ходу.
        // Резиновый отскок в сторону убираем совсем: при чтении он дёргает страницу по горизонтали.
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.isHorizontalScrollBarEnabled = false
        view.isVerticalScrollBarEnabled = true
        view.scrollBarStyle = WebView.SCROLLBARS_INSIDE_OVERLAY
        view.isScrollbarFadingEnabled = true
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            userAgentString = USER_AGENT
            setOffscreenPreRaster(true)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
    }

    private fun buildClient(): WebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url ?: return false
            val scheme = uri.scheme?.lowercase().orEmpty()
            if (scheme != "http" && scheme != "https") {
                if (scheme in EXTERNAL_SCHEMES) openExternal(uri)
                return true
            }
            // http/https всегда внутри: никаких белых списков, иначе OAuth выкидывает из приложения.
            return false
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view !== main) {
                onPopupPage(view, url)
                return
            }
            error = null
            progress = 0
            loading = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== main) {
                onPopupPage(view, url)
                return
            }
            loading = false
            progress = 100
            if (url != null && isWebUrl(url)) {
                view.evaluateJavascript(SMOOTH_JS, null)
                // Постоянное хранилище: иначе браузер вправе вытеснить скачанные сайтом главы.
                view.evaluateJavascript(PERSIST_JS, null)
            }
            if (url != null && isWebUrl(url)) {
                applyHiddenBlocks(view)
                if (picking) view.evaluateJavascript(PICK_JS, null)
            }
            if (url != null) {
                takeScrollFor(url)?.let { y ->
                    view.evaluateJavascript("window.scrollTo(0, ${y.toInt()})", null)
                }
            }
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // Иначе система убивает процесс приложения целиком.
            view?.let { crashRenderer(it) }
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
            if (!request.isForMainFrame || view !== main) return
            loading = false
            error = "Сайт не ответил (код ${failure.errorCode}). ${failure.description}"
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, sslError: SslError) {
            // Сертификат не подтверждён — соединение прерываем и говорим об этом прямо.
            handler.cancel()
            if (view === main) {
                loading = false
                error = "Сертификат сайта недействителен, соединение прервано."
            }
        }
    }

    private fun buildChrome(): WebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view !== main) return
            progress = newProgress
            loading = newProgress < 100
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (title.isNullOrBlank()) return
            if (view === popup) popupTitle = title else if (view === main) onTitle(title)
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            // Без жеста пользователя окна открывает только реклама — такие запросы игнорируем.
            if (!isUserGesture && !isDialog) return false
            val popupView = WebView(context)
            applySettings(popupView)
            popupView.webViewClient = buildClient()
            popupView.webChromeClient = buildChrome()
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            // Вложенный попап заменяет предыдущий: старый WebView надо освободить.
            val previous = popup
            popup = popupView
            popupTitle = null
            if (previous != null && previous !== popupView) destroyLater(previous)
            transport.webView = popupView
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (window === popup) closePopup()
        }

        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: WebChromeClient.FileChooserParams,
        ): Boolean {
            // Незакрытый предыдущий запрос отменяем, иначе input навсегда останется «занятым».
            chooser?.callback?.onReceiveValue(null)
            chooser = FileChooserRequest(callback, params)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // Решение принимает композиция: она запрашивает системные разрешения и отвечает сайту.
            permissionHandler(request)
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback,
        ) {
            // Геолокации в приложении нет (и разрешения в манифесте тоже) — честно отказываем.
            callback.invoke(origin, false, false)
        }

        override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
            if (fullscreen != null) {
                callback.onCustomViewHidden()
                return
            }
            fullscreen = view
            customViewCallback = callback
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        override fun onJsAlert(
            view: WebView,
            url: String?,
            message: String?,
            result: JsResult,
        ): Boolean {
            enqueueDialog(JsDialogRequest(JsDialogRequest.Kind.ALERT, message.orEmpty(), null, result, null))
            return true
        }

        override fun onJsConfirm(
            view: WebView,
            url: String?,
            message: String?,
            result: JsResult,
        ): Boolean {
            enqueueDialog(JsDialogRequest(JsDialogRequest.Kind.CONFIRM, message.orEmpty(), null, result, null))
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult,
        ): Boolean {
            enqueueDialog(
                JsDialogRequest(JsDialogRequest.Kind.PROMPT, message.orEmpty(), defaultValue, result, result),
            )
            return true
        }
    }

    /**
     * Окно попапа: пока оно на своём сайте, это обычный target=_blank — оставляем как есть.
     * Ушло на чужой хост (OAuth) и вернулось на сайт — переносим результат в основное окно,
     * иначе вход обрывается на полпути.
     */
    private fun onPopupPage(view: WebView, url: String?) {
        val target = main ?: return
        val page = url ?: return
        if (!isWebUrl(page)) return
        val onSite = sameHost(page, site?.url) || sameHost(page, target.url)
        if (!onSite) {
            popupLeftSite.add(view)
            return
        }
        if (view !in popupLeftSite) return
        // destroy() внутри колбэка самого WebView небезопасен — откладываем на следующий цикл.
        view.post {
            closePopup()
            target.loadUrl(page)
        }
    }

    private fun openExternal(uri: Uri) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun closePopup() {
        val view = popup ?: return
        popup = null
        popupTitle = null
        popupLeftSite.remove(view)
        destroyLater(view)
    }

    /**
     * Отцепляем сразу, destroy() — следующим циклом: внутри колбэка WebView это небезопасно.
     * Через Handler, а не view.post: у отвязанного View post() копит runnable до повторного attach,
     * которого уже не будет, — WebView с Activity-контекстом утёк бы навсегда.
     */
    private fun destroyLater(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        handler.post {
            runCatching {
                view.stopLoading()
                view.destroy()
            }
        }
    }

    fun hideCustomView() {
        fullscreen = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    fun takeChooser(): FileChooserRequest? {
        val request = chooser ?: return null
        chooser = null
        return request
    }

    fun cancelChooser() {
        takeChooser()?.callback?.onReceiveValue(null)
    }

    private fun enqueueDialog(dialog: JsDialogRequest) {
        // Диалогов может прилететь несколько подряд: показываем по одному, остальные в очередь.
        if (jsDialog == null) jsDialog = dialog else jsQueue.addLast(dialog)
    }

    fun finishDialog() {
        jsDialog = jsQueue.removeFirstOrNull()
    }
}

/** Запрос выбора файла из onShowFileChooser. */
private class FileChooserRequest(
    val callback: ValueCallback<Array<Uri>>,
    private val params: WebChromeClient.FileChooserParams,
) {
    fun intent(): Intent {
        val types: List<String> = params.acceptTypes?.filter { it.contains("/") }.orEmpty()
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (types.size == 1) types.first() else "*/*"
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
    }
}

/** Один JS-диалог: alert/confirm/prompt. */
private class JsDialogRequest(
    val kind: Kind,
    val message: String,
    val defaultValue: String?,
    private val result: JsResult,
    private val prompt: JsPromptResult?,
) {
    enum class Kind { ALERT, CONFIRM, PROMPT }

    fun accept(text: String? = null) {
        if (prompt != null) prompt.confirm(text.orEmpty()) else result.confirm()
    }

    fun dismiss() {
        result.cancel()
    }
}

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
private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** Схемы, которые отдаём другим приложениям. Остальные (file:, javascript:, content:) игнорируем. */
private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "sms", "tg", "intent", "market", "whatsapp", "viber")

/** Отдаём странице только камеру и микрофон: остальное (MIDI, protected media) не разрешаем. */
private fun grantSupported(request: PermissionRequest) {
    val allowed = request.resources.filter {
        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
    }
    if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
}

/** Разрешения, которые нужно спросить у системы под запрос страницы. */
private fun requiredPermissions(request: PermissionRequest): List<String> = buildList {
    for (resource in request.resources) {
        when (resource) {
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> add(android.Manifest.permission.CAMERA)
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> add(android.Manifest.permission.RECORD_AUDIO)
        }
    }
}.distinct()

private fun allGranted(context: Context, request: PermissionRequest): Boolean =
    requiredPermissions(request).all {
        ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

private fun collectUris(result: ActivityResult): Array<Uri>? {
    if (result.resultCode != android.app.Activity.RESULT_OK) return null
    val data = result.data ?: return null
    val clip = data.clipData
    if (clip != null && clip.itemCount > 0) {
        return Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
    }
    return data.data?.let { arrayOf(it) }
}

private fun isWebUrl(url: String): Boolean =
    url.startsWith("http://") || url.startsWith("https://")

private fun hostOf(url: String?): String? =
    url?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }

private fun sameHost(a: String?, b: String?): Boolean {
    val hostA = hostOf(a) ?: return false
    val hostB = hostOf(b) ?: return false
    return hostA == hostB || hostA.endsWith(".$hostB") || hostB.endsWith(".$hostA")
}

private fun sameUrl(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    return a.trimEnd('/') == b.trimEnd('/')
}

private fun jsQuote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""


/** Плавная прокрутка: якорные переходы и скролл внутри контейнеров идут без рывков. */
private const val SMOOTH_JS = """
(function () {
  try {
    if (window.__inkSmooth) { return; }
    window.__inkSmooth = true;
    var style = document.createElement('style');
    style.textContent =
      'html{scroll-behavior:smooth!important}' +
      'html,body{overscroll-behavior:none}' +
      'img,video,canvas{max-width:100%!important;height:auto}' +
      'pre,table{max-width:100%;overflow-x:auto}' +
      'body{overflow-x:clip;word-break:break-word}';
    (document.head || document.documentElement).appendChild(style);
  } catch (e) {}
})();
"""

/** Постоянное хранилище: сайт просит его сам, но без жеста браузер отвечает отказом. */
private const val PERSIST_JS = """
(function () {
  try {
    if (navigator.storage && navigator.storage.persist) { navigator.storage.persist(); }
  } catch (e) {}
})();
"""

/** Прячет все сохранённые блоки: одна таблица стилей на страницу. */
private fun hiddenCssJs(css: String): String =
    "(function(){var s=document.getElementById('ink-hidden');" +
        "if(!s){s=document.createElement('style');s.id='ink-hidden';" +
        "(document.head||document.documentElement).appendChild(s);}" +
        "s.textContent=" + jsQuote(css) + ";})();"

/**
 * Выбор блока: подсвечиваем элемент рамкой, отдаём селектор и описание в Kotlin и ждём решения.
 * Режим сам не выключается — пока пользователь не нажмёт «Отмена» в панели.
 */
private const val PICK_JS = """
(function () {
  if (window.__inkPicker) { return; }
  window.__inkPicker = true;
  window.__inkSel = null;

  // Короткий и устойчивый селектор: якорные атрибуты, иначе путь тег:nth-of-type.
  // Классы не используем: у современных сайтов их десятки на элемент, и такой селектор не совпадает.
  function selectorFor(el) {
    if (!el || el.nodeType !== 1) { return ''; }
    var anchors = ['data-testid', 'data-test', 'itemprop', 'aria-label', 'name'];
    for (var a = 0; a < anchors.length; a++) {
      var value = el.getAttribute && el.getAttribute(anchors[a]);
      if (value && value.length < 40) {
        return el.tagName.toLowerCase() + '[' + anchors[a] + '="' + value.replace(/"/g, '') + '"]';
      }
    }
    if (el.id && el.id.length < 40) { return '#' + CSS.escape(el.id); }
    var parts = [];
    var node = el;
    var depth = 0;
    while (node && node.nodeType === 1 && depth < 6 && node !== document.body) {
      var index = 1;
      var parent = node.parentElement;
      if (parent) {
        var same = [];
        for (var k = 0; k < parent.children.length; k++) {
          if (parent.children[k].tagName === node.tagName) { same.push(parent.children[k]); }
        }
        index = same.indexOf(node) + 1;
      }
      parts.unshift(node.tagName.toLowerCase() + ':nth-of-type(' + index + ')');
      node = parent;
      depth++;
    }
    return parts.join(' > ');
  }

  function describe(el) {
    if (!el) { return ''; }
    var text = (el.textContent || '').replace(/\\s+/g, ' ').trim();
    var tag = el.tagName ? el.tagName.toLowerCase() : '';
    if (text.length > 0) { return tag + ': ' + text.slice(0, 40); }
    return tag + (el.className ? '.' + String(el.className).split(' ')[0] : '');
  }

  function paint(el) {
    clearPaint();
    if (!el || !el.style) { return; }
    window.__inkSel = { el: el, outline: el.style.outline, offset: el.style.outlineOffset };
    el.style.outline = '2px solid #7c5cff';
    el.style.outlineOffset = '-2px';
  }

  function clearPaint() {
    var saved = window.__inkSel;
    if (!saved || !saved.el) { return; }
    saved.el.style.outline = saved.outline || '';
    saved.el.style.outlineOffset = saved.offset || '';
    window.__inkSel = null;
  }

  window.__inkClearSel = clearPaint;

  function onMove(e) { paint(e.target); }
  function onTouch(e) { paint(e.target); }
  function onClick(e) {
    e.preventDefault();
    e.stopPropagation();
    var el = e.target;
    paint(el);
    try { InkPicker.onPicked(selectorFor(el), describe(el)); } catch (err) {}
  }

  document.addEventListener('mousemove', onMove, true);
  document.addEventListener('touchstart', onTouch, true);
  document.addEventListener('click', onClick, true);

  window.__inkPickStop = function () {
    document.removeEventListener('mousemove', onMove, true);
    document.removeEventListener('touchstart', onTouch, true);
    document.removeEventListener('click', onClick, true);
    clearPaint();
    window.__inkPicker = false;
  };
})();
"""

/** Снять подсветку с выбранного блока (пользователь отказался скрывать). */
private const val PICK_CLEAR_JS = """
(function () { if (window.__inkClearSel) { window.__inkClearSel(); } })();
"""

/** Выйти из режима выбора. */
private const val STOP_PICK_JS = """
(function () { if (window.__inkPickStop) { window.__inkPickStop(); } })();
"""

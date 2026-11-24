package app.hybridreading

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        SitesStore.init(this)
        // Отладка WebView — только в debug-сборке: в релизе чужая страница не должна получить CDP.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        // Сайт работает через service worker: такие запросы не проходят через WebViewClient,
        // поэтому рекламу фильтруем здесь тем же списком и уважаем выключатель в меню.
        runCatching {
            ServiceWorkerController.getInstance().setServiceWorkerClient(
                object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                        if (SitesStore.active.value?.adBlock == false) return null
                        val url = request.url.toString()
                        return if (AdBlocker.shouldBlock(url)) {
                            AdBlocker.emptyResponse(AdBlocker.mimeFor(url))
                        } else {
                            null
                        }
                    }
                }
            )
        }.onFailure { Log.w("HybridReading", "ServiceWorkerClient не установлен", it) }
    }
}

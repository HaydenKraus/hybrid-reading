package app.hybridreading

import android.app.Application
import android.content.pm.ApplicationInfo
import android.webkit.WebView

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SitesStore.init(this)
        // Недельный бэкап профиля в «Загрузки» — страховка от потери данных.
        BackupWorker.schedule(this)
        // Отладка WebView — только в debug-сборке: в релизе чужая страница не должна получить CDP.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
    }
}

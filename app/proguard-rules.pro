# InkStory 1.1 — правила R8 для release-сборки.
# Задача: не сломать WebView (JS-мосты) и Compose, всё остальное пусть вырезается.
# Лишние keep-правила вредны: они возвращают в APK тот код, который R8 должен удалить.

# Методы с @JavascriptInterface вызываются из JS по строковому имени.
# R8 этих вызовов не видит и без правила удалит/переименует методы — JS начнёт молча падать.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Имена JS-объектов в addJavascriptInterface("obj", ...) задаются строкой, сами классы-мосты
# остаются достижимыми из кода приложения (их создаёт и передаёт WebView), отдельный keep не нужен.

# Классы android.webkit зовут наши WebViewClient/WebChromeClient через переопределения;
# предупреждения R8 о reflection внутри SDK гасим — это не наш код.
-dontwarn android.webkit.**

# JetBrains-аннотации приходят транзитивно и в Android-рантайме отсутствуют.
-dontwarn org.jetbrains.annotations.**

# Compose: правила поставляются внутри compose-runtime (consumer rules) — дублировать не нужно.
# kotlinx.coroutines: правила встроены в артефакт — дублировать не нужно.

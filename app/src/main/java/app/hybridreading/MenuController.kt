package app.hybridreading

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Кнопка меню и действия к ней: «Главная», «Сайты», «Скрыть блок», «Закладка», «История».
 *
 * Кнопку можно перетащить в любое место экрана — по нажатию раскрывается столбик действий
 * с подписями. Кнопки лежат прямо в контейнере WebView: Compose-оверлей поверх AndroidView
 * не рисуется, а вложенные контейнеры дают непредсказуемую высоту при раскрытии.
 *
 * Корень занимает весь экран, но не перехватывает касания (не clickable), а строки прижаты
 * к правому краю с отступом — так подписи всегда влезают, как бы далеко ни увели кнопку.
 */
class MenuController(
    private val context: Context,
    private val parent: FrameLayout,
) {
    private val size = dp(BUTTON_DP)
    private val row = dp(ROW_DP)

    private val root = FrameLayout(context)

    private data class Action(
        val icon: Int,
        val caption: String,
        val sizeDp: Int,
        val onClick: () -> Unit,
    )

    private class Row(
        val view: LinearLayout,
        val params: FrameLayout.LayoutParams,
    )

    private var rows: List<Row> = emptyList()
    private var mainParams: FrameLayout.LayoutParams? = null
    private var main: ImageButton? = null
    private var totalHeight = 0
    private var built = false

    private var onHome: () -> Unit = {}
    private var onSites: () -> Unit = {}
    private var onHide: () -> Unit = {}
    private var onBookmark: () -> Unit = {}
    private var onHistory: () -> Unit = {}

    private var leftX = 0
    private var bottomY = 0
    private var expanded = false

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var startLeft = 0
    private var startBottom = 0
    private var dragging = false

    fun bind(
        onHome: () -> Unit,
        onSites: () -> Unit,
        onHide: () -> Unit,
        onBookmark: () -> Unit = {},
        onHistory: () -> Unit = {},
    ) {
        this.onHome = onHome
        this.onSites = onSites
        this.onHide = onHide
        this.onBookmark = onBookmark
        this.onHistory = onHistory
    }

    /** Собирает столбик действий. Вызывается один раз при первом размещении. */
    private fun build() {
        built = true
        root.layoutParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        root.isClickable = false

        val list =
            listOf(
                Action(R.drawable.ic_home, "Главная", BUTTON_DP) {
                    close()
                    onHome()
                },
                Action(R.drawable.ic_globe, "Сайты", BUTTON_DP) {
                    close()
                    onSites()
                },
                Action(R.drawable.ic_hide, "Скрыть блок", BUTTON_DP) {
                    close()
                    onHide()
                },
                // Кнопка закладки намеренно вдвое меньше остальных.
                Action(R.drawable.ic_star, "Закладка", BOOKMARK_DP) {
                    close()
                    onBookmark()
                },
                Action(R.drawable.ic_history, "История", BUTTON_DP) {
                    close()
                    onHistory()
                },
            )

        val builtRows = ArrayList<Row>()
        list.forEach { action ->
            val caption =
                TextView(context).apply {
                    text = action.caption
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    // Подложка: подписи читаются и на светлой, и на тёмной странице.
                    background =
                        GradientDrawable().apply {
                            cornerRadius = dp(12).toFloat()
                            setColor(Color.argb(170, 0, 0, 0))
                        }
                    setPadding(dp(10), dp(3), dp(10), dp(3))
                }
            val icon =
                button(action.icon, action.caption, 0xFF3A3566.toInt(), action.sizeDp).apply {
                    setOnClickListener { action.onClick() }
                }
            val rowView =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(caption)
                    addView(icon)
                }
            val params =
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    row,
                    Gravity.END,
                )
            root.addView(rowView, params)
            builtRows += Row(rowView, params)
        }
        rows = builtRows
        totalHeight = row * list.size + size

        val menuButton = button(R.drawable.ic_dots, "Меню", 0xFF2E2A54.toInt(), BUTTON_DP)
        menuButton.setOnTouchListener(DragListener())
        mainParams = FrameLayout.LayoutParams(size, size, Gravity.END)
        root.addView(menuButton, mainParams)
        main = menuButton

        parent.addView(root)
        setExpanded(false)
    }

    /** Первое размещение: сохранённое место или правый нижний угол. */
    fun restore() {
        if (!built) build()
        if (parent.width == 0 || parent.height == 0) {
            parent.post { restore() }
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedX = prefs.getInt(KEY_X, -1)
        val savedBottom = prefs.getInt(KEY_BOTTOM, -1)
        leftX = if (savedX < 0) parent.width - dp(16) else savedX
        bottomY = if (savedBottom < 0) parent.height - dp(24) else savedBottom
        apply()
    }

    private fun apply() {
        leftX = leftX.coerceIn(size, parent.width)
        bottomY = bottomY.coerceIn(totalHeight + dp(8), parent.height)
        val marginEnd = (parent.width - leftX).coerceAtLeast(0)
        val stackTop = bottomY - totalHeight
        rows.forEachIndexed { index, item ->
            item.params.marginEnd = marginEnd
            item.params.topMargin = stackTop + row * index
            item.view.layoutParams = item.params
        }
        mainParams?.let {
            it.marginEnd = marginEnd
            it.topMargin = stackTop + row * rows.size
            main?.layoutParams = it
        }
        root.requestLayout()
    }

    private fun close() = setExpanded(false)

    private fun setExpanded(value: Boolean) {
        expanded = value
        val state = if (value) View.VISIBLE else View.GONE
        rows.forEach { rowView ->
            rowView.view.visibility = state
            for (i in 0 until rowView.view.childCount) {
                rowView.view.getChildAt(i).visibility = state
            }
        }
    }

    /** Раскрыть/свернуть список действий (в приложении — по нажатию кнопки). */
    internal fun expand(value: Boolean) = setExpanded(value)

    internal fun isExpanded(): Boolean = expanded

    private fun save() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_X, leftX)
            .putInt(KEY_BOTTOM, bottomY)
            .apply()
    }

    private fun button(
        icon: Int,
        description: String,
        color: Int,
        sizeDp: Int,
    ): ImageButton =
        ImageButton(context).apply {
            setImageResource(icon)
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            contentDescription = description
            val pad = dp(if (sizeDp <= BOOKMARK_DP) 4 else 16)
            setPadding(pad, pad, pad, pad)
            elevation = dp(6).toFloat()
            layoutParams = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }

    /** Перетаскивание: двигаем весь столбик, по отпусканию без движения — раскрываем список. */
    @SuppressLint("ClickableViewAccessibility")
    private inner class DragListener : View.OnTouchListener {
        override fun onTouch(
            view: View,
            event: MotionEvent,
        ): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    startLeft = leftX
                    startBottom = bottomY
                    parent.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        dragging = true
                    }
                    if (dragging) {
                        leftX = startLeft + dx.toInt()
                        bottomY = startBottom + dy.toInt()
                        apply()
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        save()
                        setExpanded(false)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        setExpanded(!expanded)
                    }
                    dragging = false
                }
            }
            return true
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val BUTTON_DP = 56
        private const val ROW_DP = 64
        private const val BOOKMARK_DP = 28
        private const val PREFS = "menu_button"
        private const val KEY_X = "x"
        private const val KEY_BOTTOM = "bottom"
    }
}

package app.hybridreading

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Кнопка меню: перетаскивание, прилипание к краю, уход за край и столбик подписанных действий.
 *
 * Поведение:
 * - кнопку можно тянуть, после отпускания она прилипает к ближнему краю;
 * - через несколько секунд бездействия прячется за край, оставляя видимую полоску;
 * - касание полоски возвращает кнопку и раскрывает действия;
 * - подписи всегда обращены к центру экрана.
 *
 * Позиция задаётся отступами от краёв (gravity + margin), а не координатами: так кнопка
 * остаётся на месте при повороте экрана и изменении размера окна, а спрятать её можно
 * отрицательным отступом — контейнер обрезает лишнее.
 */
class MenuController(
    private val context: Context,
    private val parent: FrameLayout,
) {
    private val size = dp(BUTTON_DP)
    private val row = dp(ROW_DP)
    private val peek = dp(PEEK_DP)
    private val gap = dp(6)
    private val handleWidth = dp(HANDLE_DP)
    private val handleHeight = size + dp(28)

    private val root = FrameLayout(context)
    private val handler = Handler(Looper.getMainLooper())

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
    private var handle: View? = null
    private var handleParams: FrameLayout.LayoutParams? = null
    private var built = false

    private var onHome: () -> Unit = {}
    private var onSites: () -> Unit = {}
    private var onHide: () -> Unit = {}
    private var onBookmark: () -> Unit = {}
    private var onHistory: () -> Unit = {}

    /** Отступ кнопки от ближнего края (справа или слева) и от низа экрана. */
    private var edgeMargin = 0
    private var bottomMargin = 0
    private var leftSide = false
    private var hidden = false
    private var expanded = false

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var startEdge = 0
    private var startBottom = 0
    private var dragging = false
    private var animator: ValueAnimator? = null

    private val hideTask = Runnable { if (!expanded) hide() }

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

    private fun build() {
        built = true
        root.layoutParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        root.isClickable = false
        root.clipChildren = true

        val list =
            listOf(
                Action(R.drawable.ic_home, "Главная", BUTTON_DP) {
                    collapse()
                    onHome()
                },
                Action(R.drawable.ic_globe, "Сайты", BUTTON_DP) {
                    collapse()
                    onSites()
                },
                Action(R.drawable.ic_hide, "Скрыть блок", BUTTON_DP) {
                    collapse()
                    onHide()
                },
                // Кнопка закладки намеренно вдвое меньше остальных.
                Action(R.drawable.ic_star, "Закладка", BOOKMARK_DP) {
                    collapse()
                    onBookmark()
                },
                Action(R.drawable.ic_history, "История", BUTTON_DP) {
                    collapse()
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
                    background =
                        GradientDrawable().apply {
                            cornerRadius = dp(12).toFloat()
                            setColor(Color.argb(170, 0, 0, 0))
                        }
                    setPadding(dp(10), dp(3), dp(10), dp(3))
                }
            val icon =
                actionButton(action.icon, action.caption, action.sizeDp).apply {
                    setOnClickListener { action.onClick() }
                }
            val rowView =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(icon)
                    addView(caption)
                }
            val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, row)
            root.addView(rowView, params)
            builtRows += Row(rowView, params)
        }
        rows = builtRows

        val menuButton =
            actionButton(R.drawable.ic_dots, "Меню", BUTTON_DP).apply {
                background = menuBackground()
                elevation = dp(10).toFloat()
                setOnTouchListener(DragListener())
            }
        mainParams = FrameLayout.LayoutParams(size, size)
        root.addView(menuButton, mainParams)
        main = menuButton

        // Широкая невидимая зона у края: спрятанную кнопку проще достать.
        val touch =
            View(context).apply {
                isClickable = true
                visibility = View.GONE
                setOnClickListener {
                    reveal()
                    setExpanded(true)
                }
            }
        handleParams = FrameLayout.LayoutParams(handleWidth, handleHeight)
        root.addView(touch, handleParams)
        handle = touch

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
        leftSide = prefs.getBoolean(KEY_LEFT, false)
        edgeMargin = prefs.getInt(KEY_EDGE, dp(16))
        bottomMargin = prefs.getInt(KEY_BOTTOM, dp(24))
        apply()
        scheduleHide()
    }

    /** Расставляет кнопку и подписи по текущим отступам. */
    private fun apply() {
        val maxEdge = (parent.width - size).coerceAtLeast(0)
        edgeMargin = edgeMargin.coerceIn(-(size - peek), maxEdge)
        bottomMargin = bottomMargin.coerceIn(dp(8), (parent.height - size).coerceAtLeast(dp(8)))

        mainParams?.let { params ->
            params.gravity = Gravity.BOTTOM or if (leftSide) Gravity.START else Gravity.END
            params.leftMargin = if (leftSide) edgeMargin else 0
            params.rightMargin = if (leftSide) 0 else edgeMargin
            params.bottomMargin = bottomMargin
            main?.layoutParams = params
        }

        orientRows()
        // Если сверху не хватает места — раскрываемся вниз; если не хватает и снизу,
        // сдвигаем кнопку так, чтобы подписи поместились над ней.
        val stackHeight = rows.size * row
        val roomAbove = parent.height - (bottomMargin + size)
        val roomBelow = bottomMargin - gap
        var openDown = false
        if (roomAbove < stackHeight) {
            if (roomBelow >= stackHeight) {
                openDown = true
            } else {
                bottomMargin += stackHeight - roomAbove
            }
        }
        mainParams?.bottomMargin = bottomMargin
        main?.layoutParams = mainParams

        handleParams?.let { params ->
            params.gravity = Gravity.BOTTOM or if (leftSide) Gravity.START else Gravity.END
            params.leftMargin = if (leftSide) 0 else 0
            params.rightMargin = 0
            params.bottomMargin = bottomMargin - (handleHeight - size) / 2
            handle?.layoutParams = params
            handle?.visibility = if (hidden) View.VISIBLE else View.GONE
        }
        rows.forEachIndexed { index, item ->
            val params = item.params
            params.gravity = Gravity.BOTTOM or if (leftSide) Gravity.START else Gravity.END
            params.bottomMargin =
                if (openDown) {
                    bottomMargin - (rows.size - index) * row
                } else {
                    bottomMargin + size + gap + (rows.size - index - 1) * row
                }
            params.leftMargin = if (leftSide) size + gap else 0
            params.rightMargin = if (leftSide) 0 else size + gap
            item.view.layoutParams = params
        }
        root.requestLayout()
    }

    /** Иконка всегда рядом с кнопкой: справа от неё подпись левее иконки, слева — наоборот. */
    private fun orientRows() {
        rows.forEach { item ->
            val rowView = item.view
            if (rowView.childCount < 2) return@forEach
            val first = rowView.getChildAt(0)
            val second = rowView.getChildAt(1)
            val iconFirst = first.contentDescription != null
            if (iconFirst != leftSide) {
                rowView.removeAllViews()
                rowView.addView(second)
                rowView.addView(first)
            }
        }
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        rows.forEach { item ->
            item.view.visibility = if (value) View.VISIBLE else View.GONE
            for (i in 0 until item.view.childCount) {
                item.view.getChildAt(i).visibility = if (value) View.VISIBLE else View.GONE
            }
        }
        if (value) {
            cancelHide()
            rows.forEach { item ->
                item.view.alpha = 0f
                item.view.animate().alpha(1f).setDuration(140).start()
            }
        } else {
            scheduleHide()
        }
    }

    private fun collapse() = setExpanded(false)

    /** Раскрыть/свернуть список действий (в приложении — по нажатию кнопки). */
    internal fun expand(value: Boolean) = setExpanded(value)

    internal fun isExpanded(): Boolean = expanded

    internal fun isHidden(): Boolean = hidden

    /** Уводит кнопку за ближний край: остаётся видимой полоска [PEEK_DP]. */
    internal fun hide() {
        if (hidden || expanded) return
        hidden = true
        slideTo(-(size - peek))
        main?.animate()?.alpha(0.75f)?.setDuration(260)?.start()
    }

    /** Возвращает кнопку из-за края. */
    internal fun reveal() {
        if (!hidden) return
        hidden = false
        slideTo(dp(8))
        main?.animate()?.alpha(1f)?.setDuration(200)?.start()
    }

    private fun slideTo(target: Int) {
        animator?.cancel()
        animator =
            ValueAnimator.ofInt(edgeMargin, target).apply {
                duration = 260
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    edgeMargin = it.animatedValue as Int
                    apply()
                }
                start()
            }
    }

    private fun scheduleHide() {
        cancelHide()
        if (hidden) return
        handler.postDelayed(hideTask, HIDE_DELAY)
    }

    private fun cancelHide() = handler.removeCallbacks(hideTask)

    private fun save() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LEFT, leftSide)
            .putInt(KEY_EDGE, if (hidden) dp(8) else edgeMargin)
            .putInt(KEY_BOTTOM, bottomMargin)
            .apply()
    }

    /** Круглая кнопка: градиент, светлая окантовка и тень. */
    private fun menuBackground(): LayerDrawable {
        val ring =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(55, 255, 255, 255))
            }
        val body =
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF7C5CFF.toInt(), 0xFFC86DD7.toInt()),
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.argb(90, 255, 255, 255))
            }
        return LayerDrawable(arrayOf(ring, body)).apply {
            setLayerInset(0, -dp(3), -dp(3), -dp(3), -dp(3))
        }
    }

    private fun actionButton(
        icon: Int,
        description: String,
        sizeDp: Int,
    ): ImageButton =
        ImageButton(context).apply {
            setImageResource(icon)
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF5145C8.toInt(), 0xFF8E5BD8.toInt()),
                ).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(1), Color.argb(70, 255, 255, 255))
                }
            contentDescription = description
            val pad = dp(if (sizeDp <= BOOKMARK_DP) 3 else 9)
            setPadding(pad, pad, pad, pad)
            elevation = dp(6).toFloat()
            layoutParams = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }

    /** Нажатие, перетаскивание, прилипание к краю. */
    @SuppressLint("ClickableViewAccessibility")
    private inner class DragListener : View.OnTouchListener {
        override fun onTouch(
            view: View,
            event: MotionEvent,
        ): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelHide()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    startEdge = edgeMargin
                    startBottom = bottomMargin
                    if (hidden) reveal()
                    view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(110).start()
                    parent.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        dragging = true
                        if (expanded) collapse()
                        view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(110).start()
                    }
                    if (dragging) {
                        // Слева отступ растёт вправо, справа — наоборот.
                        edgeMargin = startEdge + dx.toInt() * if (leftSide) 1 else -1
                        bottomMargin = startBottom - dy.toInt()
                        apply()
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    if (dragging) {
                        // Пересчитываем отступ уже в системе нового края.
                        val leftPx = if (leftSide) edgeMargin else parent.width - size - edgeMargin
                        leftSide = leftPx + size / 2 < parent.width / 2
                        edgeMargin = if (leftSide) leftPx else parent.width - size - leftPx
                        if (edgeMargin < dp(SNAP_HIDE_DP)) {
                            // Подвели к краю — убираем за край, оставляя полоску.
                            hidden = true
                            slideTo(-(size - peek))
                            main?.animate()?.alpha(0.75f)?.setDuration(260)?.start()
                        } else {
                            slideTo(dp(8))
                            scheduleHide()
                        }
                        apply()
                        save()
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
        private const val BUTTON_DP = 36
        private const val ROW_DP = 46
        private const val BOOKMARK_DP = 18
        private const val PEEK_DP = 10
        private const val HANDLE_DP = 44
        private const val SNAP_HIDE_DP = 40
        private const val HIDE_DELAY = 4500L
        private const val PREFS = "menu_button_v2"
        private const val KEY_LEFT = "left"
        private const val KEY_EDGE = "edge"
        private const val KEY_BOTTOM = "bottom"
    }
}

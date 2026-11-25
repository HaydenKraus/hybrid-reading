package app.hybridreading

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageButton

/**
 * Кнопка меню и три действия: «Главная», «Сайты», «Удалить блок с сайта».
 *
 * Кнопку можно перетащить в любое место экрана — по нажатию раскрываются три действия.
 * Раскрытие идёт вверх от основной кнопки, поэтому она не сдвигается.
 *
 * Всё лежит в одном View-контейнере фиксированного размера, который двигается трансформацией:
 * так положение кнопок совпадает с тем, где они нарисованы, и касания приходят по адресу.
 */
class MenuController(
    private val context: Context,
    private val parent: FrameLayout,
) {

    private val size = dp(BUTTON_DP)
    private val row = dp(ROW_DP)
    private val totalHeight = size + row * ACTIONS

    private val root = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(size, totalHeight)
    }

    private val main = button(R.drawable.ic_dots, "Меню", 0xFF2E2A54.toInt())
    private val actions = listOf(
        button(R.drawable.ic_home, "Главная", 0xFF3A3566.toInt()),
        button(R.drawable.ic_globe, "Сайты", 0xFF3A3566.toInt()),
        button(R.drawable.ic_hide, "Удалить блок с сайта", 0xFF3A3566.toInt()),
    )

    private var onHome: () -> Unit = {}
    private var onSites: () -> Unit = {}
    private var onHide: () -> Unit = {}

    private var leftX = 0
    private var bottomY = 0
    private var expanded = false

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var startLeft = 0
    private var startBottom = 0
    private var dragging = false

    init {
        root.addView(main, box(totalHeight - size))
        // Сверху вниз: главная, сайты, удалить блок.
        actions.forEachIndexed { index, view ->
            root.addView(view, box(row * index))
        }
        setExpanded(false)
        parent.addView(root)

        main.setOnTouchListener(DragListener())
        actions[0].setOnClickListener { close(); onHome() }
        actions[1].setOnClickListener { close(); onSites() }
        actions[2].setOnClickListener { close(); onHide() }
    }

    fun bind(onHome: () -> Unit, onSites: () -> Unit, onHide: () -> Unit) {
        this.onHome = onHome
        this.onSites = onSites
        this.onHide = onHide
    }

    /** Первое размещение: сохранённое место или правый нижний угол. */
    fun restore() {
        if (parent.width == 0 || parent.height == 0) {
            parent.post { restore() }
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedX = prefs.getInt(KEY_X, -1)
        val savedBottom = prefs.getInt(KEY_BOTTOM, -1)
        leftX = if (savedX < 0) parent.width - size - dp(16) else savedX
        bottomY = if (savedBottom < 0) parent.height - dp(24) else savedBottom
        apply()
    }

    private fun apply() {
        leftX = leftX.coerceIn(0, (parent.width - size).coerceAtLeast(0))
        bottomY = bottomY.coerceIn(totalHeight + dp(8), parent.height)
        root.translationX = leftX.toFloat()
        root.translationY = (bottomY - totalHeight).toFloat()
    }

    private fun close() = setExpanded(false)

    private fun setExpanded(value: Boolean) {
        expanded = value
        actions.forEach { it.visibility = if (value) View.VISIBLE else View.GONE }
    }

    private fun save() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_X, leftX)
            .putInt(KEY_BOTTOM, bottomY)
            .apply()
    }

    /** Кнопка в контейнере фиксированного размера: положение задаётся отступом сверху. */
    private fun box(top: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(size, size).apply { topMargin = top }

    private fun button(icon: Int, description: String, color: Int): ImageButton =
        ImageButton(context).apply {
            setImageResource(icon)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            contentDescription = description
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            elevation = dp(6).toFloat()
        }

    /** Перетаскивание: двигаем весь столбик, по отпусканию без движения — раскрываем список. */
    @SuppressLint("ClickableViewAccessibility")
    private inner class DragListener : View.OnTouchListener {
        override fun onTouch(view: View, event: MotionEvent): Boolean {
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
        private const val ACTIONS = 3
        private const val PREFS = "menu_button"
        private const val KEY_X = "x"
        private const val KEY_BOTTOM = "bottom"
    }
}

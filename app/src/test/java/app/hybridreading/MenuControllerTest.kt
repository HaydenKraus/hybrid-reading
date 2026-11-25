package app.hybridreading

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Кнопка меню: три действия, раскрытие и колбэки. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MenuControllerTest {
    private fun controller(): Triple<MenuController, FrameLayout, Activity> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(activity)
        activity.setContentView(parent)
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, 1080, 1920)
        return Triple(MenuController(activity, parent), parent, activity)
    }

    private fun buttons(parent: ViewGroup): Map<String, ImageButton> {
        val found = HashMap<String, ImageButton>()

        fun walk(view: ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is ImageButton) found[child.contentDescription?.toString().orEmpty()] = child
                if (child is ViewGroup) walk(child)
            }
        }
        walk(parent)
        return found
    }

    @Test
    fun allFourButtonsExist() {
        val (controller, parent, _) = controller()
        controller.restore()
        val names = buttons(parent).keys
        assertEquals(
            setOf("Меню", "Главная", "Сайты", "Скрыть блок", "Закладка", "История"),
            names,
        )
    }

    @Test
    fun actionsHiddenUntilExpanded() {
        val (controller, parent, _) = controller()
        controller.restore()
        val found = buttons(parent)

        assertFalse(controller.isExpanded())
        assertEquals(View.GONE, found.getValue("Главная").visibility)

        controller.expand(true)
        assertTrue(controller.isExpanded())
        assertEquals(View.VISIBLE, found.getValue("Главная").visibility)

        controller.expand(false)
        assertEquals(View.GONE, found.getValue("Сайты").visibility)
    }

    @Test
    fun actionsCallTheirCallbacks() {
        val (controller, parent, _) = controller()
        controller.restore()
        controller.expand(true)
        val found = buttons(parent)
        var homeCalls = 0
        var sitesCalls = 0
        var hideCalls = 0
        var bookmarkCalls = 0
        var historyCalls = 0
        controller.bind(
            onHome = { homeCalls++ },
            onSites = { sitesCalls++ },
            onHide = { hideCalls++ },
            onBookmark = { bookmarkCalls++ },
            onHistory = { historyCalls++ },
        )

        found.getValue("Главная").performClick()
        found.getValue("Сайты").performClick()
        found.getValue("Скрыть блок").performClick()
        found.getValue("Закладка").performClick()
        found.getValue("История").performClick()

        assertEquals(1, homeCalls)
        assertEquals(1, sitesCalls)
        assertEquals(1, hideCalls)
        assertEquals(1, bookmarkCalls)
        assertEquals(1, historyCalls)
        // После выбора действия меню сворачивается.
        assertFalse(controller.isExpanded())
    }

    @Test
    fun restoredPositionKeepsButtonsInsideScreen() {
        val (controller, parent, _) = controller()
        controller.restore()

        val found = buttons(parent)
        val main = found.getValue("Меню")
        assertTrue(main.left >= 0)
        assertTrue(main.top >= 0)
        assertTrue(main.right <= 1080)
        assertTrue(main.bottom <= 1920)
    }
}

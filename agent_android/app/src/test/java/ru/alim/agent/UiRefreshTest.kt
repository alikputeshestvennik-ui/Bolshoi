package ru.alim.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clearRecords() {
        ctx.getSharedPreferences("journal", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("events", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun menuOpensNamedDestinations() {
        var opened: Scr? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalNavigate provides { opened = it }) { PageNavigation("Чат") }
        } }
        compose.onNodeWithText("Чат").performClick()
        compose.onNodeWithText("История").performClick()
        compose.runOnIdle { assertEquals(Scr.History, opened) }
    }

    @Test fun historyTotalsIncludeBackgroundAndNeverDisplayRubles() {
        val now = System.currentTimeMillis()
        Journal.add(ctx, TaskRec(now, "Поездка в Петербург", 3, "gpt-6-luna", 8.61, true, tokIn = 40_000, tokOut = 17_000))
        Journal.add(ctx, TaskRec(now, "Новости электричества", 2, "gpt-6-luna", 4.0, true, tokIn = 30_000, tokOut = 18_000))
        Journal.add(ctx, TaskRec(now, "Мониторинг", 1, "gpt-6-luna", 1.0, true, tokIn = 7_000, tokOut = 3_000))
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize().background(Bg)) { HistoryScreen({}, {}) } } }
        compose.onNodeWithTag("usage-total").assertTextEquals(tok(115_000)).assertIsDisplayed()
        compose.onAllNodes(hasText("₽", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Мониторинг").assertDoesNotExist()
        screenshot("history")
        compose.onNodeWithText("Показать фон").performClick()
        compose.onNodeWithText("Мониторинг").performScrollTo().assertIsDisplayed()
    }

    @Test fun importantPageHasNoDailySummaryOrCounters() {
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize().background(Bg)) { DigestScreen({}) } } }
        compose.onNodeWithText("Всё спокойно").assertIsDisplayed()
        compose.onAllNodes(hasText("Итог дня", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("Сегодня увидел", substring = true)).assertCountEquals(0)
        screenshot("important-empty")
    }

    @Test fun importantPageShowsOnlyMarkedEvents() {
        val important = Events.add(ctx, "msg", "Telegram", "Поддержка VPN", "Ответ поддержки", "Проверьте новое сообщение")!!
        Events.add(ctx, "mail", "Gmail", "Рассылка", "Обычная новость", "Новое письмо")
        Events.mark(ctx, setOf(important.id), mapOf(important.id to "Поддержка ответила на обращение"))
        Digests.add(ctx, "Старый итог дня не должен появляться на странице")
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize().background(Bg)) { DigestScreen({}) } } }
        compose.onNodeWithText("Поддержка ответила на обращение").assertIsDisplayed()
        compose.onNodeWithText("Обычная новость").assertDoesNotExist()
        compose.onAllNodes(hasText("Старый итог дня", substring = true)).assertCountEquals(0)
        screenshot("important")
    }

    @Test fun changingPeriodRecalculatesTokens() {
        Journal.add(ctx, TaskRec(System.currentTimeMillis(), "Сегодня", 1, "test", 1.0, true, tokIn = 1_000))
        Journal.add(ctx, TaskRec(Journal.startOfDay() - 3 * 86_400_000L, "Раньше", 1, "test", 1.0, true, tokOut = 2_000))
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize().background(Bg)) { HistoryScreen({}, {}) } } }
        compose.onNodeWithText("7 дней").performClick()
        compose.onNodeWithTag("usage-total").assertTextEquals(tok(3_000)).assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        // Draw the real Compose host view. PixelCopy waits for a hardware frame
        // that a JVM test runner does not produce.
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/screenshots/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

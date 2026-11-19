package dev.horizonmanager

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ru-rRU-w412dp-h860dp-xhdpi")
class ArticleContextTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun moreShowsPendingThenReopensSavedContextWithoutAnotherSubmission() {
        val article = JSONObject().put("id", "article-one")
        val open = mutableStateOf(true)
        val submissions = AtomicInteger(0)
        val serverState = AtomicReference(JSONObject().put("state", "idle"))
        compose.runOnUiThread {
            compose.activity.setContent {
                HorizonTheme(ThemePreset.Graphite) {
                    if (open.value) ArticleContext(article) { submit ->
                        if (submit) {
                            submissions.incrementAndGet()
                            serverState.set(JSONObject().put("state", "queued"))
                        }
                        serverState.get()
                    }
                }
            }
        }

        val more = compose.onNodeWithText("Подробнее")
        more.assertIsEnabled().performClick()
        compose.onNodeWithText("Подбираем контекст…").assertIsDisplayed()
        more.assertIsNotEnabled()
        compose.runOnIdle { open.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("Подробнее").assertDoesNotExist()
        compose.runOnIdle { open.value = true }
        compose.onNodeWithText("Подбираем контекст…").assertIsDisplayed()

        val context = JSONObject().put("title", "Детали").put("content", "Данные из профиля.")
        serverState.set(JSONObject().put("state", "ready").put("blocks", JSONArray(listOf(context))))
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText("Дополнительный контекст").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Данные из профиля.").assertIsDisplayed()
        more.performClick()
        more.performClick()
        compose.onNodeWithText("Данные из профиля.").assertIsDisplayed()
        assertEquals(1, submissions.get())
    }
}

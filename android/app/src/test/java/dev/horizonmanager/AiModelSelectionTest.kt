package dev.horizonmanager

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ru-rRU-w412dp-h860dp-xhdpi")
class AiModelSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun aDifferentProviderWithTheSameModelIdCannotTestTheOldConfiguration() {
        val router = JSONObject().put("provider", "openai").put("model", "shared-id")
            .put("base_url", AiRouter.OpenRouter.baseUrl)
        val chatgpt = JSONObject().put("provider", "openai-chatgpt").put("model", "shared-id")
        val selected = mutableStateOf(router)
        val saved = mutableStateOf(router)
        val checks = AtomicInteger()
        compose.runOnUiThread {
            compose.activity.setContent {
                HorizonTheme {
                    SavedModelTest(selected.value, saved.value, revision = 3, busy = false, check = null, ready = true) {
                        checks.incrementAndGet()
                    }
                }
            }
        }

        val test = compose.onNodeWithText(ui(R.string.ui_test_saved_model))
        test.assertIsEnabled()
        compose.runOnIdle { selected.value = chatgpt }
        test.assertIsNotEnabled()
        compose.onNodeWithText(ui(R.string.hovq_ai_test_save_first)).assertIsDisplayed()
        assertEquals(0, checks.get())
        compose.runOnIdle { saved.value = chatgpt }
        test.assertIsEnabled().performClick()
        assertEquals(1, checks.get())
    }

    @Test
    fun refreshIsInsideThePickerAndChoosingCommitsTheModelWithItsKey() {
        val selected = AtomicReference<Pair<String, String?>?>()
        val requests = AtomicInteger()
        compose.runOnUiThread {
            compose.activity.setContent {
                HorizonTheme {
                    RouterSettings(AiRouter.OpenRouter, "", hasSavedKey = false, busy = false,
                        load = {
                            requests.incrementAndGet()
                            listOf(RouterModel("provider/model", "Fixture model", null))
                        },
                        select = { model, key -> selected.set(model to key) })
                }
            }
        }

        val refresh = compose.onNodeWithText(ui(R.string.ui_refresh_list))
        refresh.assertDoesNotExist()
        compose.onNodeWithText(ui(R.string.ui_save_settings)).assertDoesNotExist()
        compose.onNodeWithText(ui(R.string.ui_api_key, AiRouter.OpenRouter.title)).performTextInput("fixture-key")
        compose.onNodeWithText(ui(R.string.ui_select_model)).assertIsEnabled().performClick()
        compose.waitUntil(5000) { requests.get() == 1 }
        refresh.assertIsDisplayed().performClick()
        compose.waitUntil(5000) { requests.get() == 2 }
        assertNull(selected.get())
        compose.onNodeWithText("Fixture model").performClick()
        assertEquals("provider/model" to "fixture-key", selected.get())
        refresh.assertDoesNotExist()
    }
}

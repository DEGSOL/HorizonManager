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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ru-rRU-w412dp-h860dp-xhdpi")
class AiModelSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun providerSwitchFindsOnlyPersistedModelsAndPreservesTheirOwnAuthSettings() {
        val openRouter = JSONObject().put("provider", "openai").put("model", "saved/router")
            .put("base_url", AiRouter.OpenRouter.baseUrl).put("api_key_env", "HM_SECRET_OPENROUTER_KEY")
        val chatgpt = JSONObject().put("provider", "openai-chatgpt").put("model", "saved-chatgpt")
        val settings = JSONObject().put("ai_providers", JSONObject().put("openai-chatgpt", chatgpt)
            .put("unorouter", JSONObject().put("model", ""))
            .put("anthropic", JSONObject().put("model", JSONObject.NULL)))

        assertEquals(chatgpt, rememberedAiModel(settings, openRouter, "openai-chatgpt"))
        assertEquals(openRouter, rememberedAiModel(settings, openRouter, "openrouter"))
        assertNull(rememberedAiModel(settings, openRouter, "openai"))
        assertNull(rememberedAiModel(settings, openRouter, "unorouter"))
        assertNull(rememberedAiModel(settings, openRouter, "anthropic"))
    }

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
}

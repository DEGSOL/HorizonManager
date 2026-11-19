package dev.horizonmanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RouterCatalogTest {
    @Test fun parsesNamesContextAndTextModels() {
        val models = RouterCatalog.parse("""
            {"data":[
                {"id":"beta/free:free","context_length":48000},
                {"id":"alpha/pro","name":"Alpha Pro","architecture":{"output_modalities":["text","image"]}},
                {"id":"alpha/embedding","architecture":{"output_modalities":["embeddings"]}},
                {"id":"alpha/pro","name":"Repeated"},
                {"id":"bad identifier"},
                {"id":12}
            ]}
        """.trimIndent())
        assertEquals(listOf("alpha/pro", "beta/free:free"), models.map { it.id })
        assertEquals("Alpha Pro", models.first().name)
        assertEquals(48000, models.last().contextLength)
    }

    @Test fun rejectsInvalidResponsesAndAcceptsEmptyCatalogs() {
        assertEquals(emptyList<RouterModel>(), RouterCatalog.parse("""{"data":[]}"""))
        assertEquals("ROUTER_CATALOG_FORMAT", assertThrows(Problem::class.java) {
            RouterCatalog.parse("""{"error":{"message":"quota"}}""")
        }.code)
    }

    @Test fun usesExistingAgentOpenAiShapeAndDistinctSecretSlots() {
        for (router in AiRouter.entries) {
            val original = JSONObject().put("model", "old").put("provider_chain", "openai,gemini")
            val configured = router.defaults(original)
            assertEquals("openai", configured.getString("provider"))
            assertEquals(router.secretName, configured.getString("api_key_env"))
            assertEquals(router, AiRouter.configured(configured))
            assertEquals("", configured.getString("model"))
            assertEquals("old", original.getString("model"))
        }
        assertEquals(2, AiRouter.entries.map(AiRouter::secretName).distinct().size)
        assertNull(AiRouter.configured(JSONObject().put("provider", "openai")
            .put("base_url", "https://example.test/api/v1")))
    }
}

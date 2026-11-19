package dev.horizonmanager

import org.json.JSONException
import org.json.JSONObject

internal enum class AiRouter(
    val id: String, val title: String, val baseUrl: String, val secretName: String,
) {
    OpenRouter("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "HM_SECRET_OPENROUTER_KEY"),
    UnoRouter("unorouter", "UnoRouter", "https://api.unorouter.com/v1", "HM_SECRET_UNOROUTER_KEY");

    fun secretAlias(saved: JSONObject): String =
        saved.optString("api_key_env").takeIf { configured(saved) == this && it.isNotBlank() && it != "null" } ?: secretName

    fun defaults(ai: JSONObject): JSONObject = ai.copyObject()
        .put("provider", "openai").put("base_url", baseUrl).put("api_key_env", secretName).put("model", "")
        .put("provider_chain", JSONObject.NULL).put("extra_body", JSONObject.NULL)
        .put("azure_endpoint_env", JSONObject.NULL).put("api_version", JSONObject.NULL)

    companion object {
        fun configured(ai: JSONObject): AiRouter? {
            if (ai.optString("provider") != "openai") return null
            val url = ai.optString("base_url").trimEnd('/')
            return entries.find { it.baseUrl == url }
        }
    }
}

internal data class RouterModel(val id: String, val name: String, val contextLength: Int?)

internal object RouterCatalog {
    fun parse(json: String): List<RouterModel> {
        val data = try {
            JSONObject(json).getJSONArray("data")
        } catch (_: JSONException) {
            throw Problem("ROUTER_CATALOG_FORMAT")
        }
        return data.objects().mapNotNull { entry ->
            val id = (entry.opt("id") as? String)?.trim().orEmpty()
            if (id.isEmpty() || id.length > 256 || id.any { it.isWhitespace() || it.isISOControl() }) return@mapNotNull null
            val modalities = entry.optJSONObject("architecture")?.optJSONArray("output_modalities")
            if (modalities != null && modalities.length() > 0 && "text" !in modalities.strings()) return@mapNotNull null
            val name = (entry.opt("name") as? String)?.trim()?.take(200).orEmpty().ifBlank { id }
            RouterModel(id, name, entry.optInt("context_length").takeIf { it > 0 })
        }.distinctBy(RouterModel::id).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}

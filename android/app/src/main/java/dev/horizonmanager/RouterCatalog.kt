package dev.horizonmanager

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal enum class AiRouter(
    val id: String, val title: String, val baseUrl: String, val catalogPath: String, val secretName: String,
) {
    OpenRouter("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "/models/user", "HM_SECRET_OPENROUTER_KEY"),
    UnoRouter("unorouter", "UnoRouter", "https://api.unorouter.com/v1", "/models", "HM_SECRET_UNOROUTER_KEY");

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
    private val client = OkHttpClient.Builder()
        .callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun fetch(router: AiRouter, key: String): List<RouterModel> {
        if (key.length !in 8..512 || key.any { it.code !in 0x21..0x7e }) throw Problem("ROUTER_KEY_FORMAT")
        val request = Request.Builder().url(router.baseUrl + router.catalogPath)
            .header("Authorization", "Bearer $key").header("Accept", "application/json").build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401, 403 -> throw Problem("ROUTER_KEY_REJECTED")
                    429 -> throw Problem("ROUTER_RATE_LIMIT")
                }
                if (!response.isSuccessful) throw Problem("ROUTER_CATALOG_HTTP", response.code.toString())
                val input = response.body ?: throw Problem("ROUTER_CATALOG_FORMAT")
                return parse(input.byteStream().readBounded(4 * 1024 * 1024).decodeToString())
            }
        } catch (_: javax.net.ssl.SSLException) {
            throw Problem("TLS_INVALID")
        } catch (_: IOException) {
            throw Problem("ROUTER_CATALOG_NETWORK")
        }
    }

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
